#!/usr/bin/env python3
"""Regenerate the final Contracts package through one staged pipeline.

Both modes build and validate a temporary package first.  ``--check`` leaves
the source tree untouched and fails on any byte difference.  ``--write``
publishes the already validated temporary result with per-file atomic replaces.
"""
from __future__ import annotations

from argparse import ArgumentParser
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import tempfile
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BASELINE_SOURCE_PATHS = [
    "blue-language-core/src/main/java/blue/language/identity/CircularSetIdentityCalculator.java",
    "blue-language-core/src/main/java/blue/language/provider/NodeContentHandler.java",
    "blue-language-core/src/main/java/blue/language/provider/CyclicSetProof.java",
    "blue-language-core/src/main/java/blue/language/provider/CyclicSetProofResult.java",
    "blue-language-core/src/main/java/blue/language/provider/CyclicAwareNodeProvider.java",
    "blue-contracts-core/src/main/java/blue/language/processor/DocumentProcessor.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ProcessorInvocationOrchestrator.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ProcessorExecutionContext.java",
    "blue-contracts-core/src/main/java/blue/language/processor/EmbeddedScopePlanner.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ProcessGasMeter.java",
    "blue-contracts-core/src/main/java/blue/language/processor/GasSchedule.java",
    "blue-contracts-core/src/main/java/blue/language/processor/PlatformCommitCompanion.java",
]


class RegenerationFailure(RuntimeError):
    pass


def package_inventory(root: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        relative_text = relative.as_posix()
        if "__pycache__" in relative.parts or relative_text.startswith("build/classes/"):
            continue
        result[relative_text] = path
    return result


def normalized_file_value(relative: str, path: Path) -> bytes | Any:
    if relative == "validation-output.json":
        value = json.loads(path.read_text())
        value.get("sourceArchive", {}).pop("suppliedName", None)
        return value
    return path.read_bytes()


def compare_packages(left: Path, right: Path) -> list[str]:
    left_files = package_inventory(left)
    right_files = package_inventory(right)
    differences: list[str] = []
    for relative in sorted(set(left_files) | set(right_files)):
        if relative not in left_files:
            differences.append(f"missing from source: {relative}")
        elif relative not in right_files:
            differences.append(f"not regenerated: {relative}")
        elif normalized_file_value(relative, left_files[relative]) != normalized_file_value(relative, right_files[relative]):
            differences.append(f"content differs: {relative}")
    return differences


def run(command: list[str], *, cwd: Path, environment: dict[str, str]) -> None:
    result = subprocess.run(command, cwd=cwd, env=environment, text=True, capture_output=True)
    if result.returncode != 0:
        raise RegenerationFailure(
            f"command failed ({' '.join(command)}):\n{result.stdout}\n{result.stderr}"
        )


def regenerate(candidate: Path, language_source_root: Path, source_zip: Path) -> None:
    environment = dict(os.environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    tools = candidate / "tools"

    manifest_command = [
        sys.executable,
        str(tools / "build_release_manifests.py"),
        "--language-source-root",
        str(language_source_root),
    ]

    # Refinement binds fixture identities to implementation-baseline
    # identities read from release-manifest.yaml.  Refresh that input before
    # any fixture binding occurs.  This first manifest set is deliberately
    # transient: it describes the copied fixture inventory and is rebuilt
    # after generation and refinement below.
    run(manifest_command, cwd=candidate.parent, environment=environment)

    # Seed generation plus refinement owns every closure fixture, trace and
    # cyclic oracle YAML.  Clear those generated surfaces in the disposable
    # copy so a renamed or retired file cannot survive into a rebuilt manifest.
    closure_directory = candidate / "conformance/contracts/fixtures/closure"
    for path in closure_directory.glob("*.yaml"):
        path.unlink()
    trace_directory = candidate / "conformance/contracts/fixtures/closure/traces"
    for path in trace_directory.glob("*.yaml"):
        path.unlink()
    oracle_directory = candidate / "conformance/contracts/oracles"
    for path in oracle_directory.glob("*.yaml"):
        path.unlink()

    commands = [
        [sys.executable, str(tools / "generate_closure_fixtures.py")],
        [sys.executable, str(tools / "refine_closure_fixtures.py")],
        manifest_command,
        [
            sys.executable,
            str(tools / "validate_package.py"),
            "--source",
            str(source_zip),
            "--write-output",
        ],
    ]
    for command in commands:
        run(command, cwd=candidate.parent, environment=environment)


def managed_generated_removal(relative: str) -> bool:
    path = Path(relative)
    closure = Path("conformance/contracts/fixtures/closure")
    oracles = Path("conformance/contracts/oracles")
    return (
        path.parent == closure and path.suffix == ".yaml"
    ) or (
        path.parent == closure / "traces" and path.suffix == ".yaml"
    ) or (
        path.parent == oracles and path.suffix == ".yaml"
    )


def publication_order(relative: str) -> tuple[int, str]:
    manifest_names = {
        "MANIFEST.sha256",
        "package-manifest.yaml",
        "validation-output.json",
        "conformance/contracts/release-manifest.yaml",
        "conformance/contracts/fixtures/manifest.yaml",
        "conformance/contracts/fixtures/vector-coverage.yaml",
        "conformance/contracts/oracles/manifest.yaml",
        "conformance/contracts/registry/manifest.yaml",
        "conformance/contracts/gas-manifest.yaml",
    }
    return (1 if relative in manifest_names else 0, relative)


def publish(candidate: Path, destination: Path) -> None:
    candidate_files = package_inventory(candidate)
    destination_files = package_inventory(destination)
    removals = sorted(set(destination_files) - set(candidate_files))
    unsafe_removals = [relative for relative in removals if not managed_generated_removal(relative)]
    if unsafe_removals:
        raise RegenerationFailure(f"refusing to remove non-generated package files: {unsafe_removals}")
    for relative in removals:
        destination_files[relative].unlink()

    for relative in sorted(candidate_files, key=publication_order):
        source = candidate_files[relative]
        target = destination / relative
        if target.exists() and normalized_file_value(relative, source) == normalized_file_value(relative, target):
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(prefix=f".{target.name}.", dir=target.parent)
        os.close(descriptor)
        temporary = Path(temporary_name)
        try:
            shutil.copy2(source, temporary)
            os.replace(temporary, target)
        finally:
            if temporary.exists():
                temporary.unlink()

    differences = compare_packages(destination, candidate)
    if differences:
        raise RegenerationFailure(f"published package differs from validated staging tree: {differences[:20]}")


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--language-source-root", type=Path, required=True)
    parser.add_argument("--source-zip", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="Regenerate in a temporary copy and require byte identity (default)")
    mode.add_argument("--write", action="store_true", help="Publish the validated temporary result into this package")
    args = parser.parse_args()

    language_source_root = args.language_source_root.expanduser().resolve()
    source_zip = args.source_zip.expanduser().resolve()
    if not language_source_root.is_dir():
        raise RegenerationFailure(f"language source root is not a directory: {language_source_root}")
    missing_baselines = [relative for relative in BASELINE_SOURCE_PATHS if not (language_source_root / relative).is_file()]
    if missing_baselines:
        raise RegenerationFailure(f"language source root lacks release baseline files: {missing_baselines}")
    if not source_zip.is_file():
        raise RegenerationFailure(f"original source ZIP is not a file: {source_zip}")

    with tempfile.TemporaryDirectory(prefix="blue-contracts-regenerate-") as temporary_root:
        candidate = Path(temporary_root) / ROOT.name
        shutil.copytree(ROOT, candidate)
        regenerate(candidate, language_source_root, source_zip)
        differences = compare_packages(ROOT, candidate)
        if args.write:
            publish(candidate, ROOT)
            print("PACKAGE_REGENERATED_AND_VALIDATED")
        elif differences:
            detail = "\n".join(f"  - {difference}" for difference in differences[:50])
            remainder = "" if len(differences) <= 50 else f"\n  ... and {len(differences) - 50} more"
            raise RegenerationFailure(f"package is not the authoritative regenerated output:\n{detail}{remainder}")
        else:
            print("PACKAGE_REGENERATION_CHECK_OK")


if __name__ == "__main__":
    try:
        main()
    except RegenerationFailure as exc:
        print(f"PACKAGE_REGENERATION_FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
