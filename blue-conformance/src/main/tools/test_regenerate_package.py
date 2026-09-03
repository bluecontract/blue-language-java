#!/usr/bin/env python3
"""Regression tests for self-hosting oracle regeneration."""

from __future__ import annotations

from collections import Counter
from pathlib import Path
import os
import shutil
import tempfile
from typing import Any
import unittest

import yaml

import release_regenerate_package as regenerate_package


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = ROOT.parents[2]
RELEASED_PACKAGE = ROOT / "resources/blue-contracts-closure-1.0"
ORACLE_STAGE_KEYS = {
    "name",
    "sourceDocumentsWithThisReferences",
    "masterBlueId",
    "memberBlueIdsInSourceOrder",
    "canonicalMasterInputUtf8Bytes",
}


def fixture_gas_trace(package_root: Path, name: str) -> list[dict[str, Any]]:
    """Read one complete generated closure trace from a package root."""
    fixture_path = package_root / "fixtures/closure" / name
    fixture = yaml.safe_load(fixture_path.read_text(encoding="utf-8"))
    expected = fixture["expected"]
    if "gasTrace" in expected:
        return expected["gasTrace"]
    trace_path = fixture_path.parent / expected["gasTraceFile"]
    return yaml.safe_load(trace_path.read_text(encoding="utf-8"))["entries"]


def recursive_oracle_stage_count(value: Any) -> int:
    """Count cyclic stages at every supported nested oracle shape."""
    count = 0
    if isinstance(value, dict):
        if ORACLE_STAGE_KEYS.issubset(value):
            count += 1
        for child in value.values():
            count += recursive_oracle_stage_count(child)
    elif isinstance(value, list):
        for child in value:
            count += recursive_oracle_stage_count(child)
    return count


def oracle_stage_inventory(oracle_directory: Path) -> Counter[tuple[str, str]]:
    """Return file/name stage keys, preserving duplicates for useful diffs."""
    inventory: Counter[tuple[str, str]] = Counter()

    def visit(file_name: str, value: Any) -> None:
        if isinstance(value, dict):
            if ORACLE_STAGE_KEYS.issubset(value):
                inventory[(file_name, str(value.get("name")))] += 1
            for child in value.values():
                visit(file_name, child)
        elif isinstance(value, list):
            for child in value:
                visit(file_name, child)

    for path in regenerate_package.oracle_data_files(oracle_directory):
        visit(
            path.relative_to(oracle_directory).as_posix(),
            yaml.safe_load(path.read_text(encoding="utf-8")),
        )
    return inventory


class RegeneratePackageTest(unittest.TestCase):

    def test_generator_refiner_owns_oracles_and_admission_attribution(self) -> None:
        """Exercise the pre-runtime-rebind stage on a clean package copy."""
        with tempfile.TemporaryDirectory(
            prefix="blue-oracle-regeneration-test-"
        ) as temporary:
            candidate = Path(temporary) / ROOT.name
            shutil.copytree(ROOT, candidate)
            candidate_package = candidate / "conformance/contracts"
            shutil.copytree(
                RELEASED_PACKAGE,
                candidate_package,
                dirs_exist_ok=True,
            )
            reference_directory = candidate / "reference"
            specification_directory = candidate / "specifications"
            reference_directory.mkdir()
            specification_directory.mkdir()
            shutil.copy2(
                REPOSITORY_ROOT
                / "blue-language-core/src/main/resources/specifications/"
                "blue-language-specification-1.0.md",
                reference_directory / "blue-language-specification-1.0.md",
            )
            shutil.copy2(
                REPOSITORY_ROOT
                / "blue-contracts-core/src/main/resources/specifications/"
                "blue-contracts-and-processor-specification-1.0.md",
                specification_directory
                / "blue-contracts-and-processor-specification-1.0.md",
            )
            oracle_directory = candidate_package / "oracles"
            baseline_stage_inventory = oracle_stage_inventory(
                RELEASED_PACKAGE / "oracles"
            )
            orphan = oracle_directory / "preserved-only-orphan.yaml"
            orphan.write_text(
                "schema: blue-language-cyclic-oracle/1.0\n"
                "id: preserved-only-orphan\n"
                "stages: []\n",
                encoding="utf-8",
            )

            environment = dict(os.environ)
            environment["PYTHONDONTWRITEBYTECODE"] = "1"
            regenerate_package.regenerate_generated_surfaces(
                candidate,
                environment=environment,
            )

            oracle_files = regenerate_package.oracle_data_files(
                oracle_directory
            )
            candidate_stage_inventory = oracle_stage_inventory(
                oracle_directory
            )
            self.assertEqual(42, len(oracle_files))
            self.assertEqual(
                121,
                sum(
                    recursive_oracle_stage_count(
                        yaml.safe_load(path.read_text(encoding="utf-8"))
                    )
                    for path in oracle_files
                ),
                "stage drift: "
                f"added={candidate_stage_inventory - baseline_stage_inventory}; "
                f"removed={baseline_stage_inventory - candidate_stage_inventory}",
            )
            self.assertFalse(orphan.exists())

            # This unit stops at the generator/refiner ownership boundary.
            # The released package then receives authoritative Java-exported
            # traces and runtime-owned receipts, so comparing those final
            # traces with this intermediate model would conflate two stages.
            # regenerate_package.py --check separately compares the complete
            # post-rebind package byte-for-byte.
            admission_trace = fixture_gas_trace(
                candidate_package,
                "c-clo-01-static-cycle-admission.yaml",
            )
            planning = [
                entry for entry in admission_trace
                if entry.get("reason", "").startswith((
                    "admission.document.",
                    "admission.binding.",
                    "admission.edge.",
                    "admission.component-",
                ))
            ]
            self.assertTrue(planning)
            for entry in planning:
                self.assertIn("documentId", entry)
                self.assertNotIn("scopePath", entry)
                self.assertNotIn("activationGeneration", entry)
                self.assertNotIn("componentGeneration", entry)

            marker_writes = [
                entry for entry in admission_trace
                if entry.get("reason", "").startswith(
                    "initialization-batch.marker."
                )
            ]
            marker_identity = [
                entry for entry in admission_trace
                if entry.get("reason") == "identity-rebuild"
                and entry.get("logicalPath") == "/contracts/initialized"
                and "workOccurrenceId" not in entry
            ]
            self.assertTrue(marker_writes)
            self.assertTrue(marker_identity)
            for entry in marker_writes + marker_identity:
                self.assertIn("documentId", entry)
                self.assertNotIn("scopePath", entry)
                self.assertNotIn("activationGeneration", entry)
                self.assertNotIn("componentGeneration", entry)

            rooted_work = [
                entry for entry in admission_trace
                if "workOccurrenceId" in entry and "scopePath" in entry
            ]
            self.assertTrue(rooted_work)
            self.assertTrue(all(
                entry["scopePath"] == "/"
                and entry["activationGeneration"] == 0
                for entry in rooted_work
            ))

    def test_oracle_reference_reader_supports_both_released_shapes(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="blue-oracle-route-test-"
        ) as temporary:
            root = Path(temporary)
            fixtures = root / "fixtures/closure"
            oracles = root / "oracles"
            fixtures.mkdir(parents=True)
            oracles.mkdir()
            (oracles / "string.yaml").write_text("stages: []\n")
            (oracles / "harness.yaml").write_text("stages: []\n")
            (fixtures / "string.yaml").write_text(
                "oracle: ../../oracles/string.yaml\n"
            )
            (fixtures / "harness.yaml").write_text(
                "oracle:\n  path: ../../oracles/harness.yaml\n"
            )

            self.assertEqual(
                {Path("string.yaml"), Path("harness.yaml")},
                regenerate_package.referenced_oracle_paths(
                    fixtures,
                    oracles,
                ),
            )


if __name__ == "__main__":
    unittest.main()
