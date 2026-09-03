#!/usr/bin/env python3
"""Regenerate the checked-in Contracts conformance resource package.

The repository stores the release ``conformance/contracts`` subtree rather
than a complete specification release.  This entry point stages that subtree
with the checked-in specifications and this vendored tool closure, runs the
canonical release generator/refiner, and only then compares, stages, or
publishes the resource package.

With no output mode the command is read-only ``--check``.  ``--stage-output``
writes a candidate package to a separate directory.  ``--write`` is the only
mode allowed to modify ``--package-root``.
"""
from __future__ import annotations

from argparse import ArgumentParser
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

# Keep the checked-in tool directory cache-free even when callers do not set
# PYTHONDONTWRITEBYTECODE in their shell.
sys.dont_write_bytecode = True

import yaml

from package_hygiene import release_inventory_files
from release_regenerate_package import regenerate_generated_surfaces


TOOLS_ROOT = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_ROOT.parents[3]
CONTRACTS_SPECIFICATION = Path(
    "blue-contracts-core/src/main/resources/specifications/"
    "blue-contracts-and-processor-specification-1.0.md"
)
LANGUAGE_SPECIFICATION = Path(
    "blue-language-core/src/main/resources/specifications/"
    "blue-language-specification-1.0.md"
)
CANONICAL_ORDINARY_FIXTURES = Path(
    "blue-conformance/src/main/resources/blue-contracts-1.0/fixtures"
)
CANONICAL_RUNTIME_REGISTRY = Path(
    "blue-contracts-core/src/main/resources/registry/blue-contracts-1.0"
)
BASELINE_SOURCE_PATHS = (
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
)


class RegenerationFailure(RuntimeError):
    """Raised when a candidate cannot be produced or verified safely."""


def inventory(root: Path) -> dict[str, Path]:
    return {
        path.relative_to(root).as_posix(): path
        for path in release_inventory_files(root)
    }


def compare_packages(left: Path, right: Path) -> list[str]:
    left_files = inventory(left)
    right_files = inventory(right)
    differences: list[str] = []
    for relative in sorted(set(left_files) | set(right_files)):
        if relative not in left_files:
            differences.append(f"missing from source: {relative}")
        elif relative not in right_files:
            differences.append(f"not regenerated: {relative}")
        elif left_files[relative].read_bytes() != right_files[relative].read_bytes():
            differences.append(f"content differs: {relative}")
    return differences


def run(command: list[str], *, cwd: Path, environment: dict[str, str]) -> None:
    # Some nested generators invoke Gradle. A daemon can retain an inherited
    # PIPE after the immediate child exits, causing communicate() to wait for
    # EOF forever. Capture through a regular file so completion is tied only
    # to the process we launched while preserving complete diagnostics.
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
            f"command failed ({' '.join(command)}):\n"
            f"{combined}"
        )


def copy_file(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def stage_release_shell(
    package_root: Path,
    repository_root: Path,
    release_root: Path,
) -> None:
    copy_file(
        repository_root / CONTRACTS_SPECIFICATION,
        release_root
        / "specifications/blue-contracts-and-processor-specification-1.0.md",
    )
    copy_file(
        repository_root / LANGUAGE_SPECIFICATION,
        release_root / "reference/blue-language-specification-1.0.md",
    )
    shutil.copytree(package_root, release_root / "conformance/contracts")
    # The closure release is the published superset of the ordinary fixture
    # package and the production runtime registry.  Refresh those mirrors from
    # their canonical repository locations before any generated surface reads
    # them, while retaining closure-only fixtures, gas microfixtures and the
    # conformance-only ScriptedOperation adapter.
    shutil.copytree(
        repository_root / CANONICAL_ORDINARY_FIXTURES,
        release_root / "conformance/contracts/fixtures",
        dirs_exist_ok=True,
    )
    shutil.copytree(
        repository_root / CANONICAL_RUNTIME_REGISTRY,
        release_root / "conformance/contracts/registry",
        dirs_exist_ok=True,
    )
    shutil.copytree(
        TOOLS_ROOT,
        release_root / "tools",
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
    )


def run_full_lifecycle_generator(
    release_root: Path,
    fixture_source_root: Path | None,
    repository_root: Path,
    environment: dict[str, str],
) -> None:
    if fixture_source_root is None:
        return
    generator = release_root / "tools/generate_full_lifecycle_fixtures.py"
    if not generator.is_file():
        raise RegenerationFailure(
            "full-lifecycle sources were requested, but the checked-in "
            "normative Java exporter bridge is not present at "
            "blue-conformance/src/main/tools/"
            "generate_full_lifecycle_fixtures.py; refusing to fabricate "
            "expected identities or semantic output"
        )
    staged_sources = release_root / "fixture-sources/full-lifecycle"
    shutil.copytree(fixture_source_root, staged_sources)
    run(
        [
            sys.executable,
            str(generator),
            "--source-root",
            str(staged_sources),
            "--package-root",
            str(release_root / "conformance/contracts"),
            "--repository-root",
            str(repository_root),
        ],
        cwd=release_root.parent,
        environment=environment,
    )


def run_managed_transition_receipt_rebind(
    release_root: Path,
    repository_root: Path,
    environment: dict[str, str],
) -> None:
    """Bind generated closure expectations to executed Contracts receipts."""
    adapter = release_root / "tools/rebind_managed_transition_receipts.py"
    if not adapter.is_file():
        raise RegenerationFailure(
            "managed-transition receipt rebind adapter is missing from the "
            "staged tool closure"
        )
    run(
        [
            sys.executable,
            str(adapter),
            "--package-root",
            str(release_root / "conformance/contracts"),
            "--repository-root",
            str(repository_root),
        ],
        cwd=release_root.parent,
        environment=environment,
    )


def run_contract_evolution_generator(
    release_root: Path,
    environment: dict[str, str],
) -> None:
    """Regenerate the ordinary C-EVO tranche into the staged package."""
    generator = release_root / "tools/generate_contract_evolution_fixtures.py"
    if not generator.is_file():
        raise RegenerationFailure(
            "contract-evolution generator is missing from the staged tool "
            "closure"
        )
    run(
        [
            sys.executable,
            str(generator),
            "--output-root",
            str(release_root / "conformance/contracts/fixtures/evo"),
        ],
        cwd=release_root.parent,
        environment=environment,
    )


def regenerate(
    release_root: Path,
    repository_root: Path,
    fixture_source_root: Path | None,
) -> None:
    environment = dict(os.environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    manifest_command = [
        sys.executable,
        str(release_root / "tools/build_release_manifests.py"),
        "--language-source-root",
        str(repository_root),
    ]
    run_contract_evolution_generator(release_root, environment)
    # The first pass provides implementation/spec identities consumed by the
    # canonical refiner.  The final pass inventories only complete output.
    run(manifest_command, cwd=release_root.parent, environment=environment)
    regenerate_generated_surfaces(release_root, environment=environment)
    run_full_lifecycle_generator(
        release_root,
        fixture_source_root,
        repository_root,
        environment,
    )
    run_managed_transition_receipt_rebind(
        release_root,
        repository_root,
        environment,
    )
    run(manifest_command, cwd=release_root.parent, environment=environment)
    if fixture_source_root is not None:
        validate_full_lifecycle_package_counts(
            release_root / "conformance/contracts"
        )


def load_mapping(path: Path) -> dict[str, object]:
    value = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RegenerationFailure(f"expected mapping in generated file: {path}")
    return value


def validate_full_lifecycle_package_counts(package_root: Path) -> None:
    """Require the complete, prompt-bound FL release inventory."""
    fixtures = load_mapping(package_root / "fixtures/manifest.yaml")
    vectors = load_mapping(package_root / "fixtures/vector-coverage.yaml")
    release = load_mapping(package_root / "release-manifest.yaml")
    expected_fixture_counts = {
        "ordinaryFixtureCount": 197,
        "closureFixtureCount": 93,
        "totalExecutableFixtureCount": 290,
        "vectorCount": 182,
        "ordinaryVectorCount": 128,
        "closureVectorCount": 54,
    }
    for field, expected in expected_fixture_counts.items():
        actual = fixtures.get(field)
        if actual != expected:
            raise RegenerationFailure(
                f"full-lifecycle fixture manifest {field}={actual!r}; "
                f"expected {expected}"
            )
    expected_vector_counts = {
        "vectorCount": 182,
        "ordinaryVectorCount": 128,
        "closureVectorCount": 54,
    }
    for field, expected in expected_vector_counts.items():
        actual = vectors.get(field)
        if actual != expected:
            raise RegenerationFailure(
                f"full-lifecycle vector coverage {field}={actual!r}; "
                f"expected {expected}"
            )
    vector_map = vectors.get("vectors")
    if not isinstance(vector_map, dict):
        raise RegenerationFailure(
            "full-lifecycle vector coverage has no vectors mapping"
        )
    actual_full_lifecycle_vectors = {
        key
        for key in vector_map
        if isinstance(key, str) and key.startswith("FL-ADM-")
    }
    expected_full_lifecycle_vectors = {
        f"FL-ADM-{index:02d}" for index in range(1, 11)
    }
    if actual_full_lifecycle_vectors != expected_full_lifecycle_vectors:
        raise RegenerationFailure(
            "full-lifecycle vector family mismatch: "
            f"actual={sorted(actual_full_lifecycle_vectors)}"
        )
    actual_contract_evolution_vectors = {
        key
        for key in vector_map
        if isinstance(key, str) and key.startswith("C-EVO-")
    }
    expected_contract_evolution_vectors = {
        f"C-EVO-{index:02d}" for index in range(1, 24)
    }
    if actual_contract_evolution_vectors != expected_contract_evolution_vectors:
        raise RegenerationFailure(
            "contract-evolution vector family mismatch: "
            f"actual={sorted(actual_contract_evolution_vectors)}"
        )
    fixture_binding = release.get("fixturePackage")
    if not isinstance(fixture_binding, dict):
        raise RegenerationFailure(
            "release manifest has no fixturePackage binding"
        )
    if fixture_binding.get("vectorCount") != 182:
        raise RegenerationFailure(
            "release fixture-package vectorCount is not 182"
        )
    if fixture_binding.get("fixtureCount") != 290:
        raise RegenerationFailure(
            "release fixture-package fixtureCount is not 290"
        )


def generated_removal(relative: str) -> bool:
    path = Path(relative)
    closure = Path("fixtures/closure")
    oracles = Path("oracles")
    return (
        (path.parent == closure and path.suffix == ".yaml")
        or (path.parent == closure / "traces" and path.suffix == ".yaml")
        or (path.parent == oracles and path.suffix == ".yaml")
    )


def publication_order(relative: str) -> tuple[int, str]:
    manifests = {
        "release-manifest.yaml",
        "fixtures/manifest.yaml",
        "fixtures/vector-coverage.yaml",
        "oracles/manifest.yaml",
        "registry/manifest.yaml",
        "gas-manifest.yaml",
    }
    return (1 if relative in manifests else 0, relative)


def publish(candidate: Path, destination: Path) -> None:
    candidate_files = inventory(candidate)
    destination_files = inventory(destination)
    removals = sorted(set(destination_files) - set(candidate_files))
    unsafe = [item for item in removals if not generated_removal(item)]
    if unsafe:
        raise RegenerationFailure(
            f"refusing to remove non-generated package files: {unsafe}"
        )
    for relative in removals:
        destination_files[relative].unlink()
    for relative in sorted(candidate_files, key=publication_order):
        source = candidate_files[relative]
        target = destination / relative
        if target.exists() and source.read_bytes() == target.read_bytes():
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{target.name}.", dir=target.parent
        )
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
        raise RegenerationFailure(
            "published package differs from staging tree: "
            f"{differences[:20]}"
        )


def copy_stage(candidate: Path, destination: Path) -> None:
    if destination.exists():
        if not destination.is_dir() or any(destination.iterdir()):
            raise RegenerationFailure(
                "stage output must be nonexistent or an empty directory: "
                f"{destination}"
            )
    else:
        destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(candidate, destination, dirs_exist_ok=True)


def validate_inputs(
    package_root: Path,
    repository_root: Path,
    fixture_source_root: Path | None,
) -> None:
    required_package = (
        "fixtures/manifest.yaml",
        "registry/manifest.yaml",
        "release-manifest.yaml",
        "gas-manifest.yaml",
    )
    missing_package = [
        relative
        for relative in required_package
        if not (package_root / relative).is_file()
    ]
    if missing_package:
        raise RegenerationFailure(
            f"resource package lacks required files: {missing_package}"
        )
    required_sources = (
        str(CONTRACTS_SPECIFICATION),
        str(LANGUAGE_SPECIFICATION),
        *BASELINE_SOURCE_PATHS,
    )
    missing_sources = [
        relative
        for relative in required_sources
        if not (repository_root / relative).is_file()
    ]
    if missing_sources:
        raise RegenerationFailure(
            f"repository root lacks release source inputs: {missing_sources}"
        )
    required_directories = (
        CANONICAL_ORDINARY_FIXTURES,
        CANONICAL_RUNTIME_REGISTRY,
    )
    missing_directories = [
        str(relative)
        for relative in required_directories
        if not (repository_root / relative).is_dir()
    ]
    if missing_directories:
        raise RegenerationFailure(
            "repository root lacks canonical release input directories: "
            f"{missing_directories}"
        )
    if fixture_source_root is not None and not fixture_source_root.is_dir():
        raise RegenerationFailure(
            f"fixture source root is not a directory: {fixture_source_root}"
        )
    if (
        fixture_source_root is not None
        and not (TOOLS_ROOT / "generate_full_lifecycle_fixtures.py").is_file()
    ):
        raise RegenerationFailure(
            "full-lifecycle sources were requested, but the checked-in "
            "normative Java exporter bridge is not present at "
            "blue-conformance/src/main/tools/"
            "generate_full_lifecycle_fixtures.py; refusing to fabricate "
            "expected identities or semantic output"
        )
    if not (TOOLS_ROOT / "rebind_managed_transition_receipts.py").is_file():
        raise RegenerationFailure(
            "managed-transition receipt rebind adapter is missing from "
            "blue-conformance/src/main/tools/"
        )


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument(
        "--package-root",
        type=Path,
        required=True,
        help="Explicit checked-in blue-contracts-closure-1.0 resource root",
    )
    parser.add_argument(
        "--repository-root",
        type=Path,
        default=REPOSITORY_ROOT,
        help=f"Blue Java repository root (default: {REPOSITORY_ROOT})",
    )
    parser.add_argument(
        "--fixture-source-root",
        type=Path,
        default=None,
        help=(
            "Optional identity-free full-lifecycle source root executed by "
            "the checked-in normative Java exporter (fail-closed)."
        ),
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--check",
        action="store_true",
        help="Regenerate in temporary storage and require byte identity (default)",
    )
    mode.add_argument(
        "--stage-output",
        type=Path,
        help="Write the generated candidate package to a separate directory",
    )
    mode.add_argument(
        "--write",
        action="store_true",
        help="Publish the generated candidate into --package-root",
    )
    args = parser.parse_args()

    package_root = args.package_root.expanduser().resolve()
    repository_root = args.repository_root.expanduser().resolve()
    fixture_source_root = (
        None
        if args.fixture_source_root is None
        else args.fixture_source_root.expanduser().resolve()
    )
    validate_inputs(package_root, repository_root, fixture_source_root)

    with tempfile.TemporaryDirectory(
        prefix="blue-contracts-resource-regenerate-"
    ) as temporary:
        release_root = Path(temporary) / "release"
        stage_release_shell(package_root, repository_root, release_root)
        regenerate(release_root, repository_root, fixture_source_root)
        candidate = release_root / "conformance/contracts"
        differences = compare_packages(package_root, candidate)
        if args.stage_output is not None:
            output = args.stage_output.expanduser().resolve()
            copy_stage(candidate, output)
            print(f"PACKAGE_REGENERATION_STAGED {output}")
        elif args.write:
            publish(candidate, package_root)
            print("PACKAGE_REGENERATED_AND_VALIDATED")
        elif differences:
            detail = "\n".join(
                f"  - {difference}" for difference in differences[:50]
            )
            remainder = (
                ""
                if len(differences) <= 50
                else f"\n  ... and {len(differences) - 50} more"
            )
            raise RegenerationFailure(
                "package is not the authoritative regenerated output:\n"
                f"{detail}{remainder}"
            )
        else:
            print("PACKAGE_REGENERATION_CHECK_OK")


if __name__ == "__main__":
    try:
        main()
    except RegenerationFailure as exc:
        print(f"PACKAGE_REGENERATION_FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
