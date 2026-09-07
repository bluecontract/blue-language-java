#!/usr/bin/env python3
"""Execute the normative Java exporter and append full-lifecycle fixtures.

This adapter owns no Contracts semantics. It supplies explicit paths to the
checked-in Java exporter, validates the complete exported inventory, and only
then publishes those already-complete fixture bytes into a disposable
canonical package candidate.
"""
from __future__ import annotations

from argparse import ArgumentParser
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
from typing import Any

sys.dont_write_bytecode = True

import yaml


SUCCESS_MARKER = "FULL_LIFECYCLE_FIXTURES_EXPORTED count=31"
EXPECTED_FIXTURE_VECTORS = {
    "fl-adm-01-root-patch-event.yaml": "FL-ADM-01",
    "fl-adm-02-duplicate-equal-events.yaml": "FL-ADM-02",
    "fl-adm-03-non-public-containing-route.yaml": "FL-ADM-03",
    "fl-adm-04-document-update-continuation.yaml": "FL-ADM-04",
    "fl-adm-05-graceful-termination.yaml": "FL-ADM-05",
    "fl-adm-06-order-representation-reference.yaml": "FL-ADM-06",
    "fl-adm-06-order-representation-reversed.yaml": "FL-ADM-06",
    "fl-adm-06-order-representation-inline.yaml": "FL-ADM-06",
    "fl-adm-07-finite-cyclic-route.yaml": "FL-ADM-07",
    "fl-adm-08-infinite-cycle-gas-retry-first.yaml": "FL-ADM-08",
    "fl-adm-08-infinite-cycle-gas-retry-retry.yaml": "FL-ADM-08",
    "fl-adm-09-late-member-rollback.yaml": "FL-ADM-09",
    "fl-adm-10-unknown-occurrence.yaml": "FL-ADM-10",
    "c-evo-18-missing-exact-node.yaml": "C-EVO-18",
    "c-evo-19-missing-occurrence-evidence.yaml": "C-EVO-19",
    "c-evo-20-canonical-demand-order.yaml": "C-EVO-20",
    "c-evo-21-retry-determinism-missing-first.yaml": "C-EVO-21",
    "c-evo-21-retry-determinism-missing-repeat.yaml": "C-EVO-21",
    "c-evo-21-retry-determinism-resolved-first.yaml": "C-EVO-21",
    "c-evo-21-retry-determinism-resolved-repeat.yaml": "C-EVO-21",
    "c-evo-22-low-gas-expanded-evidence-demand.yaml": "C-EVO-22",
    "c-evo-22-low-gas-expanded-evidence-expanded-low-gas.yaml": "C-EVO-22",
    "c-evo-22-low-gas-expanded-evidence-expanded-low-gas-repeat.yaml": "C-EVO-22",
    "c-evo-23-automatic-explicit-retry-parity-automatic-demand.yaml": "C-EVO-23",
    "c-evo-23-automatic-explicit-retry-parity-automatic-resolved.yaml": "C-EVO-23",
    "c-evo-23-automatic-explicit-retry-parity-explicit-resolved.yaml": "C-EVO-23",
    "c-emb-empty-05-prospective-activation.yaml": "C-EMB-EMPTY-05",
    "c-evt-collection-07-closure-work-order-inline-cold.yaml":
        "C-EVT-COLLECTION-07",
    "c-evt-collection-07-closure-work-order-inline-warm.yaml":
        "C-EVT-COLLECTION-07",
    "c-evt-collection-07-closure-work-order-reference-cold.yaml":
        "C-EVT-COLLECTION-07",
    "c-evt-collection-07-closure-work-order-reference-warm.yaml":
        "C-EVT-COLLECTION-07",
}
EXPECTED_FIXTURES = frozenset(EXPECTED_FIXTURE_VECTORS)
GENERATED_FIXTURE_GLOBS = (
    "fl-adm-*.yaml",
    "c-evo-*.yaml",
    "c-emb-empty-*.yaml",
    "c-evt-collection-*.yaml",
)


class ExportFailure(RuntimeError):
    """Raised before incomplete exporter output can enter the candidate."""


def absolute_directory(path: Path, label: str) -> Path:
    value = path.expanduser().resolve()
    if not value.is_dir():
        raise ExportFailure(f"{label} must be an existing directory: {value}")
    return value


def load_mapping(path: Path) -> dict[str, Any]:
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError) as exc:
        raise ExportFailure(f"cannot parse exported fixture {path.name}: {exc}") from exc
    if not isinstance(value, dict):
        raise ExportFailure(f"exported fixture is not a mapping: {path.name}")
    return value


def reject_unresolved_macros(
    value: Any,
    label: str,
    path: tuple[str, ...] = (),
) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if isinstance(key, str) and key.startswith("$"):
                raise ExportFailure(f"{label} contains unresolved macro {key}")
            if (
                key == "blueId"
                and isinstance(child, str)
                and child.startswith("this#")
                and "declaredPlaceholderSet" not in path
            ):
                raise ExportFailure(
                    f"{label} contains unresolved cyclic placeholder {child}"
                )
            reject_unresolved_macros(child, label, path + (str(key),))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            reject_unresolved_macros(child, label, path + (str(index),))


def validate_export(output_root: Path) -> dict[str, Path]:
    files = {
        path.name: path
        for path in output_root.iterdir()
        if path.is_file() and not path.is_symlink()
    }
    actual_entries = {path.name for path in output_root.iterdir()}
    if actual_entries != EXPECTED_FIXTURES or set(files) != EXPECTED_FIXTURES:
        raise ExportFailure(
            "Java exporter did not produce the exact 31-file inventory: "
            f"actual={sorted(actual_entries)}"
        )
    for name in sorted(files):
        path = files[name]
        fixture = load_mapping(path)
        stem = name.removesuffix(".yaml")
        vector = EXPECTED_FIXTURE_VECTORS[name]
        required = {
            "schema": "blue-contracts-closure-fixture/1.0",
            "id": stem,
            "category": "admission",
            "operation": "admit-closure",
            "releaseManifest": "../../release-manifest.yaml",
        }
        for field, expected in required.items():
            if fixture.get(field) != expected:
                raise ExportFailure(
                    f"{name} has {field}={fixture.get(field)!r}; "
                    f"expected {expected!r}"
                )
        if fixture.get("vectors") != [vector]:
            raise ExportFailure(
                f"{name} vectors={fixture.get('vectors')!r}; expected [{vector!r}]"
            )
        for field in ("input", "runtime", "expected"):
            if not isinstance(fixture.get(field), dict):
                raise ExportFailure(f"{name} has no mapping {field}")
        reject_unresolved_macros(fixture, name)
    return files


def run_exporter(
    repository_root: Path,
    source_root: Path,
    package_root: Path,
    output_root: Path,
) -> None:
    gradlew = repository_root / "gradlew"
    if not gradlew.is_file():
        raise ExportFailure(f"Gradle wrapper is missing: {gradlew}")
    command = [
        str(gradlew),
        ":blue-conformance:exportFullLifecycleFixtures",
        "--no-daemon",
        "--no-parallel",
        f"-PfullLifecycleSourceRoot={source_root}",
        f"-PfullLifecyclePackageRoot={package_root}",
        f"-PfullLifecycleOutputRoot={output_root}",
    ]
    environment = dict(os.environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    # A Gradle daemon can inherit a PIPE descriptor after the wrapper process
    # exits, leaving subprocess.communicate() blocked even though the exporter
    # completed. A regular temporary file has no pipe lifetime coupling and
    # still gives us complete diagnostics plus the required success marker.
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as output:
        result = subprocess.run(
            command,
            cwd=repository_root,
            env=environment,
            text=True,
            stdout=output,
            stderr=subprocess.STDOUT,
        )
        output.seek(0)
        combined = output.read()
    if result.returncode != 0:
        raise ExportFailure(
            f"normative Java exporter failed ({result.returncode}):\n{combined}"
        )
    if SUCCESS_MARKER not in combined:
        raise ExportFailure(
            "normative Java exporter returned success without its exact marker:\n"
            f"{combined}"
        )


def publish(files: dict[str, Path], closure_root: Path) -> None:
    closure_root.mkdir(parents=True, exist_ok=True)
    existing_full_lifecycle = {
        path.name
        for pattern in GENERATED_FIXTURE_GLOBS
        for path in closure_root.glob(pattern)
    }
    unexpected = existing_full_lifecycle - EXPECTED_FIXTURES
    if unexpected:
        raise ExportFailure(
            "candidate contains unexpected pre-existing full-lifecycle fixtures: "
            f"{sorted(unexpected)}"
        )
    with tempfile.TemporaryDirectory(
        prefix=".full-lifecycle-publish-", dir=closure_root
    ) as temporary:
        prepared_root = Path(temporary)
        for name in sorted(EXPECTED_FIXTURES):
            shutil.copy2(files[name], prepared_root / name)
        backups = {
            name: (closure_root / name).read_bytes()
            for name in EXPECTED_FIXTURES
            if (closure_root / name).is_file()
        }
        replaced: list[str] = []
        try:
            for name in sorted(EXPECTED_FIXTURES):
                os.replace(prepared_root / name, closure_root / name)
                replaced.append(name)
        except OSError as exc:
            for name in replaced:
                target = closure_root / name
                if name in backups:
                    target.write_bytes(backups[name])
                elif target.exists():
                    target.unlink()
            raise ExportFailure(
                f"failed to publish complete FL fixture set: {exc}"
            ) from exc


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--package-root", type=Path, required=True)
    parser.add_argument("--repository-root", type=Path, required=True)
    args = parser.parse_args()
    source_root = absolute_directory(args.source_root, "source root")
    package_root = absolute_directory(args.package_root, "package root")
    repository_root = absolute_directory(args.repository_root, "repository root")
    fixture_schema = package_root / "fixtures/closure-fixture-schema.yaml"
    if not fixture_schema.is_file():
        raise ExportFailure(
            f"candidate package lacks closure fixture schema: {fixture_schema}"
        )
    with tempfile.TemporaryDirectory(
        prefix="full-lifecycle-java-export-", dir=package_root.parent
    ) as output_name:
        output_root = Path(output_name)
        run_exporter(repository_root, source_root, package_root, output_root)
        files = validate_export(output_root)
        publish(files, package_root / "fixtures/closure")
    print("FULL_LIFECYCLE_FIXTURES_APPENDED count=31")


if __name__ == "__main__":
    try:
        main()
    except ExportFailure as exc:
        print(f"FULL_LIFECYCLE_FIXTURE_APPEND_FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
