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

import yaml

from package_hygiene import release_inventory_files


ROOT = Path(__file__).resolve().parents[1]
BASELINE_SOURCE_PATHS = [
    "blue-language-core/src/main/java/blue/language/identity/CircularSetIdentityCalculator.java",
    "blue-language-core/src/main/java/blue/language/identity/CyclicMemberFinalization.java",
    "blue-language-core/src/main/java/blue/language/identity/CyclicSetFinalization.java",
    "blue-language-core/src/main/java/blue/language/provider/NodeContentHandler.java",
    "blue-language-core/src/main/java/blue/language/provider/CyclicSetProof.java",
    "blue-language-core/src/main/java/blue/language/provider/CyclicSetProofResult.java",
    "blue-language-core/src/main/java/blue/language/provider/CyclicAwareNodeProvider.java",
    "blue-language-core/src/main/java/blue/language/provider/VerifyingNodeProvider.java",
    "blue-language-core/src/main/java/blue/language/provider/CyclicProofMemberComparator.java",
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


ORACLE_WRITE_LOG_ENVIRONMENT_VARIABLE = (
    "BLUE_CONTRACTS_ORACLE_WRITE_LOG"
)


def package_inventory(root: Path) -> dict[str, Path]:
    return {
        path.relative_to(root).as_posix(): path
        for path in release_inventory_files(root)
    }


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
    # Nested generators may invoke Gradle. Avoid PIPE capture so an inherited
    # descriptor in a daemon cannot keep communicate() blocked after the
    # immediate child has exited.
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as output:
        result = subprocess.run(
            command,
            cwd=cwd,
            env=environment,
            text=True,
            stdout=output,
            stderr=subprocess.STDOUT,
        )
        output.seek(0)
        combined = output.read()
    if result.returncode != 0:
        raise RegenerationFailure(
            f"command failed ({' '.join(command)}):\n{combined}"
        )


def oracle_data_files(oracle_directory: Path) -> list[Path]:
    """Return authored/generated oracle YAML, excluding its derived manifest."""
    return sorted(
        (
            path
            for path in oracle_directory.rglob("*.yaml")
            if path.relative_to(oracle_directory).as_posix() != "manifest.yaml"
        ),
        key=lambda path: path.relative_to(oracle_directory).as_posix(),
    )


def snapshot_oracle_inputs(oracle_directory: Path) -> dict[Path, bytes]:
    """Snapshot possible refinement inputs before seed generation clears them."""
    return {
        path.relative_to(oracle_directory): path.read_bytes()
        for path in oracle_data_files(oracle_directory)
    }


def restore_missing_oracle_inputs(
    oracle_directory: Path,
    snapshot: dict[Path, bytes],
) -> None:
    """Restore only inputs not freshly owned by seed generation."""
    for relative, content in snapshot.items():
        target = oracle_directory / relative
        if target.exists():
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)


def pipeline_owned_oracle_paths(write_log: Path) -> set[Path]:
    """Read the exact oracle paths written by generator/refiner ``dump`` calls."""
    if not write_log.is_file():
        return set()
    owned: set[Path] = set()
    for raw_line in write_log.read_text(encoding="utf-8").splitlines():
        relative = Path(raw_line)
        if (
            not raw_line
            or relative.is_absolute()
            or ".." in relative.parts
            or relative.suffix != ".yaml"
            or relative.as_posix() == "manifest.yaml"
        ):
            raise RegenerationFailure(
                f"invalid oracle output path recorded by generation: {raw_line!r}"
            )
        owned.add(relative)
    return owned


def referenced_oracle_paths(
    closure_directory: Path,
    oracle_directory: Path,
) -> set[Path]:
    """Resolve both released oracle route shapes from final closure fixtures."""
    oracle_root = oracle_directory.resolve()
    referenced: set[Path] = set()
    for fixture_path in sorted(closure_directory.glob("*.yaml")):
        fixture = yaml.safe_load(fixture_path.read_text(encoding="utf-8"))
        if not isinstance(fixture, dict):
            raise RegenerationFailure(
                f"closure fixture is not an object: {fixture_path.name}"
            )
        route = fixture.get("oracle")
        if route is None:
            continue
        if isinstance(route, str):
            route_path = route
        elif isinstance(route, dict) and isinstance(route.get("path"), str):
            route_path = route["path"]
        else:
            raise RegenerationFailure(
                f"unsupported oracle route in {fixture_path.name}: {route!r}"
            )
        resolved = (fixture_path.parent / route_path).resolve()
        try:
            relative = resolved.relative_to(oracle_root)
        except ValueError as exc:
            raise RegenerationFailure(
                f"oracle route escapes the package in {fixture_path.name}: "
                f"{route_path}"
            ) from exc
        if relative.suffix != ".yaml" or relative.as_posix() == "manifest.yaml":
            raise RegenerationFailure(
                f"oracle route is not an oracle data YAML in "
                f"{fixture_path.name}: {route_path}"
            )
        if not resolved.is_file():
            raise RegenerationFailure(
                f"referenced oracle does not exist in {fixture_path.name}: "
                f"{route_path}"
            )
        referenced.add(relative)
    return referenced


def prune_unowned_oracles(
    oracle_directory: Path,
    retained: set[Path],
) -> None:
    """Remove preserved-only oracles that no pipeline stage or fixture owns."""
    for path in oracle_data_files(oracle_directory):
        if path.relative_to(oracle_directory) not in retained:
            path.unlink()


def regenerate_generated_surfaces(
    candidate: Path,
    *,
    environment: dict[str, str],
) -> None:
    """Regenerate closure surfaces while retaining only proven oracle inputs."""
    tools = candidate / "tools"
    closure_directory = candidate / "conformance/contracts/fixtures/closure"
    trace_directory = closure_directory / "traces"
    oracle_directory = candidate / "conformance/contracts/oracles"
    preserved_oracles = snapshot_oracle_inputs(oracle_directory)

    for path in closure_directory.glob("*.yaml"):
        path.unlink()
    for path in trace_directory.glob("*.yaml"):
        path.unlink()

    with tempfile.TemporaryDirectory(
        prefix="blue-oracle-ownership-",
        dir=candidate.parent,
    ) as temporary:
        write_log = Path(temporary) / "writes.txt"
        generation_environment = dict(environment)
        generation_environment[
            ORACLE_WRITE_LOG_ENVIRONMENT_VARIABLE
        ] = str(write_log)

        run(
            [sys.executable, str(tools / "generate_closure_fixtures.py")],
            cwd=candidate.parent,
            environment=generation_environment,
        )
        restore_missing_oracle_inputs(oracle_directory, preserved_oracles)
        run(
            [sys.executable, str(tools / "refine_closure_fixtures.py")],
            cwd=candidate.parent,
            environment=generation_environment,
        )
        retained = pipeline_owned_oracle_paths(write_log)

    retained.update(
        referenced_oracle_paths(closure_directory, oracle_directory)
    )
    prune_unowned_oracles(oracle_directory, retained)


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

    # Generator and refiner both consume and replace cyclic oracle YAML. Keep
    # authored/static inputs available to refinement, record exact pipeline
    # writes, then discard preserved-only paths before manifests are rebuilt.
    regenerate_generated_surfaces(candidate, environment=environment)

    commands = [
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
