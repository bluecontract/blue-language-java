#!/usr/bin/env python3
"""Structural tests for the fail-closed Java exporter adapter."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import yaml

import generate_full_lifecycle_fixtures as adapter


def fixture_for(name: str) -> dict[str, object]:
    stem = name.removesuffix(".yaml")
    return {
        "schema": "blue-contracts-closure-fixture/1.0",
        "id": stem,
        "vectors": [adapter.EXPECTED_FIXTURE_VECTORS[name]],
        "category": "admission",
        "operation": "admit-closure",
        "releaseManifest": "../../release-manifest.yaml",
        "input": {},
        "runtime": {},
        "expected": {},
    }


def write_complete_export(root: Path) -> None:
    for name in adapter.EXPECTED_FIXTURES:
        (root / name).write_text(
            yaml.safe_dump(fixture_for(name), sort_keys=False),
            encoding="utf-8",
        )


class GenerateFullLifecycleFixturesTest(unittest.TestCase):

    def test_complete_export_inventory_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory(prefix="fl-export-test-") as name:
            root = Path(name)
            write_complete_export(root)

            files = adapter.validate_export(root)

            self.assertEqual(adapter.EXPECTED_FIXTURES, frozenset(files))

    def test_exact_inventory_maps_multisegment_families_to_vectors(self) -> None:
        self.assertEqual(31, len(adapter.EXPECTED_FIXTURE_VECTORS))
        self.assertEqual(
            "C-EMB-EMPTY-05",
            adapter.EXPECTED_FIXTURE_VECTORS[
                "c-emb-empty-05-prospective-activation.yaml"
            ],
        )
        for form in ("inline", "reference"):
            for cache in ("cold", "warm"):
                name = (
                    "c-evt-collection-07-closure-work-order-"
                    f"{form}-{cache}.yaml"
                )
                self.assertEqual(
                    "C-EVT-COLLECTION-07",
                    adapter.EXPECTED_FIXTURE_VECTORS[name],
                )

    def test_derived_multisegment_vector_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="fl-export-test-") as name:
            root = Path(name)
            write_complete_export(root)
            fixture_name = (
                "c-evt-collection-07-closure-work-order-inline-cold.yaml"
            )
            fixture = fixture_for(fixture_name)
            fixture["vectors"] = ["C-EVT-COLLECTION"]
            (root / fixture_name).write_text(
                yaml.safe_dump(fixture, sort_keys=False),
                encoding="utf-8",
            )

            with self.assertRaises(adapter.ExportFailure):
                adapter.validate_export(root)

    def test_missing_export_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="fl-export-test-") as name:
            root = Path(name)
            write_complete_export(root)
            (root / next(iter(adapter.EXPECTED_FIXTURES))).unlink()

            with self.assertRaises(adapter.ExportFailure):
                adapter.validate_export(root)

    def test_stale_multisegment_fixture_is_rejected_before_publish(self) -> None:
        with tempfile.TemporaryDirectory(prefix="fl-export-test-") as name:
            root = Path(name)
            exported = root / "exported"
            closure = root / "closure"
            exported.mkdir()
            closure.mkdir()
            write_complete_export(exported)
            stale = closure / "c-evt-collection-07-stale.yaml"
            stale.write_text("stale\n", encoding="utf-8")

            with self.assertRaises(adapter.ExportFailure):
                adapter.publish(adapter.validate_export(exported), closure)

    def test_unresolved_authoring_macro_is_rejected(self) -> None:
        with self.assertRaises(adapter.ExportFailure):
            adapter.reject_unresolved_macros(
                {"input": {"$ref": "document-a"}},
                "fixture.yaml",
            )

    def test_declared_proof_placeholders_are_evidence_not_macros(self) -> None:
        adapter.reject_unresolved_macros(
            {
                "expected": {
                    "completeCyclicProof": {
                        "declaredPlaceholderSet": [
                            {"blueId": "this#0"},
                            {"blueId": "this#1"},
                        ]
                    }
                }
            },
            "fixture.yaml",
        )

    def test_placeholder_outside_declared_proof_set_is_rejected(self) -> None:
        with self.assertRaises(adapter.ExportFailure):
            adapter.reject_unresolved_macros(
                {"input": {"document": {"blueId": "this#0"}}},
                "fixture.yaml",
            )


if __name__ == "__main__":
    unittest.main()
