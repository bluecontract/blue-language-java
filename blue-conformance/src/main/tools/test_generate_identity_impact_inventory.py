#!/usr/bin/env python3
"""Tests for the exact-identity migration inventory."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


TOOLS = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "generate_identity_impact_inventory",
    TOOLS / "generate_identity_impact_inventory.py",
)
assert SPEC is not None and SPEC.loader is not None
inventory = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inventory)


class IdentityImpactInventoryTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.repository = TOOLS.parents[3]
        cls.report = inventory.generate(cls.repository)

    def test_classifies_direct_transitive_new_and_unchanged(self) -> None:
        self.assertEqual(
            "unchanged-direct",
            inventory._classification("language:Text", "same", "same"),
        )
        self.assertEqual(
            "directly-changed",
            inventory._classification("language:Dictionary", "old", "new"),
        )
        self.assertEqual(
            "transitively-changed",
            inventory._classification("contracts:RuntimeLedger", "old", "new"),
        )
        self.assertEqual(
            "newly-introduced",
            inventory._classification("contracts:EmbeddedCollectionEventChannel", None, "new"),
        )

    def test_dependency_closure_reaches_release_packages(self) -> None:
        dependents = inventory._transitive_dependents("language:List")
        self.assertIn("contracts:RuntimeLedger", dependents)
        self.assertIn("package:contracts-release", dependents)
        self.assertIn("package:language-registry", dependents)
        self.assertIn(
            "package:language-contracts-aggregate",
            inventory._transitive_dependents(
                "package:contracts-closure-fixtures"
            ),
        )
        self.assertNotIn(
            "package:contracts-oracles",
            inventory._transitive_dependents(
                "package:contracts-closure-fixtures"
            ),
        )

    def test_repository_inventory_keeps_root_empty_object_identity(self) -> None:
        empty = next(
            value
            for value in self.report["artifacts"]
            if value["stableKey"] == "language:exact-empty-object"
        )
        self.assertEqual(inventory.EMPTY_OBJECT_BLUE_ID, empty["oldExactIdentity"])
        self.assertEqual(inventory.EMPTY_OBJECT_BLUE_ID, empty["newExactIdentity"])
        self.assertEqual("unchanged-direct", empty["classification"])
        self.assertTrue(self.report["noCompatibilityAliases"])
        self.assertNotIn("currentCommit", self.report)

    def test_scope_explicitly_requires_downstream_bex_and_coordination_closure(self) -> None:
        downstream = {
            value["repository"]: value["status"]
            for value in self.report["scope"]["downstreamClosure"]
        }
        self.assertEqual(
            {
                "blue-bex-java": "required-after-upstream-candidate-is-sealed",
                "blue-coordination-java": "required-after-bex-candidate-is-sealed",
            },
            downstream,
        )

    def test_reference_partitions_are_derived_from_real_paths(self) -> None:
        references = [
            {
                "path": "blue-conformance/src/main/resources/x/fixtures/a.yaml",
                "line": 7,
                "disposition": "requires-review-or-update",
            },
            {
                "path": "blue-conformance/src/main/resources/x/release-manifest.yaml",
                "line": 8,
                "disposition": "requires-review-or-update",
            },
            {
                "path": "reports/modernization/old.json",
                "line": 9,
                "disposition": "retained-immutable-history",
            },
        ]
        partitions = inventory._reference_partitions(references)
        self.assertEqual(references[:2], partitions["active"])
        self.assertEqual([references[0]], partitions["fixtures"])
        self.assertEqual([references[1]], partitions["manifests"])
        self.assertEqual([references[2]], partitions["historical"])

    def test_generated_reference_summaries_equal_artifact_partitions(self) -> None:
        artifacts = self.report["artifacts"]
        self.assertEqual(
            sum(len(value["storedReferencesRequiringUpdate"]) for value in artifacts),
            self.report["summary"]["activeStoredReferenceCount"],
        )
        self.assertEqual(
            sum(len(value["historicalReferencesRetained"]) for value in artifacts),
            self.report["summary"]["historicalReferenceCount"],
        )

    def test_required_identity_surfaces_are_complete_and_reviewed(self) -> None:
        artifacts = {
            value["stableKey"]: value for value in self.report["artifacts"]
        }
        required = {
            "closure:ScriptedOperation",
            "document:contracts-specification",
            "document:identity-constructors",
            "document:language-specification",
            "fixture:c-clo-23-05-a9-to-a10:masterBlueId",
            "implementation:cyclic-set-finalizer",
            "implementation:cyclic-set-proof-verifier",
            "language:Dictionary",
            "language:List",
            "package:contracts-closure-fixtures",
            "package:contracts-gas",
            "package:contracts-oracles",
            "package:contracts-ordinary-fixtures",
            "package:contracts-registry",
            "package:contracts-release",
            "package:language-contracts-aggregate",
            "package:language-fixtures",
            "package:language-registry",
        }
        self.assertTrue(required.issubset(artifacts))
        self.assertEqual(
            "FE68gr14jPkb9RfHbQH1tHGE4YALJbZvgC6CtdHwYheX",
            artifacts["closure:ScriptedOperation"]["oldExactIdentity"],
        )
        self.assertEqual(
            "3awNZ6spv8gmjB9m5Sihj73diGVjVN33e14Vg67zEGFp",
            artifacts["closure:ScriptedOperation"]["newExactIdentity"],
        )
        cyclic_master = artifacts[
            "fixture:c-clo-23-05-a9-to-a10:masterBlueId"
        ]
        self.assertEqual(
            "B4s6BMi4HbXS48DC1GTuozEfbSdbBepRnpP5TrsJTdkE",
            cyclic_master["oldExactIdentity"],
        )
        self.assertEqual(
            "AqxN3nEKymbTyHrfjFhHcEH35YRyz3Ggcoch3dEzMkH5",
            cyclic_master["newExactIdentity"],
        )
        self.assertEqual([], cyclic_master["fixtureConstantsRequiringUpdate"])
        for artifact in artifacts.values():
            self.assertNotEqual("unresolved", artifact["classification"])
            self.assertNotIn("None", artifact["reason"])
            if artifact["oldExactIdentity"] != artifact["newExactIdentity"]:
                self.assertTrue(artifact["reason"])

    def test_registry_and_spec_mirrors_are_byte_identical(self) -> None:
        mirrors = [
            mirror
            for artifact in self.report["artifacts"]
            for mirror in artifact["mirroredPaths"]
        ]
        self.assertGreater(len(mirrors), 0)
        self.assertTrue(all(value["byteIdentical"] for value in mirrors))
        self.assertEqual(0, self.report["summary"]["mirrorMismatchCount"])

    def test_checked_inventory_has_no_active_old_identity_or_alias(self) -> None:
        self.assertEqual(
            0, self.report["summary"]["activeStoredReferenceCount"]
        )
        self.assertEqual(0, self.report["summary"]["unresolvedArtifactCount"])
        self.assertTrue(self.report["noCompatibilityAliases"])
        inventory._validate_upstream_report(self.report)

    def test_registry_manifest_validation_rejects_missing_and_duplicate_keys(self) -> None:
        with self.assertRaisesRegex(ValueError, "missing a stable key"):
            inventory._registry_entries({"entries": [{"path": "A.blue", "blueId": "x"}]})
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            inventory._registry_entries(
                {
                    "entries": [
                        {"key": "A", "path": "A.blue", "blueId": "x"},
                        {"key": "A", "path": "B.blue", "blueId": "y"},
                    ]
                }
            )

    def test_history_is_declared_narrowly_and_scanned_byte_for_byte(self) -> None:
        self.assertTrue(inventory._is_declared_history("api/semantic-baseline-1.0.json"))
        self.assertTrue(inventory._is_declared_history("MANAGED_TRANSITION_RECEIPT_EXAMPLE.json"))
        self.assertFalse(inventory._is_declared_history("docs/reference/public-api.md"))
        self.assertFalse(inventory._is_declared_history("release/PACKAGE-MANIFEST.yaml"))

    def test_reference_scan_includes_generator_and_release_binding_suffixes(self) -> None:
        for suffix in (".py", ".kts", ".sha256", ".toml"):
            self.assertIn(suffix, inventory.TEXT_SUFFIXES)

    def test_optional_cross_repository_identifier_extraction_is_exact(self) -> None:
        digest = "sha256:" + "a" * 64
        raw_digest = "b" * 64
        commit = "c" * 40
        blue_id = "5WQ4tVb4gUUdZa7EfaiUa2XKQwgAurvfYY3ALPauxcAF"
        actual = inventory._exact_identifiers(
            (digest + "\n" + raw_digest + "\n" + commit + "\n" + blue_id)
            .encode("utf-8")
        )
        self.assertEqual(
            sorted([digest, raw_digest, commit, blue_id]),
            actual,
        )

    def test_optional_cross_repository_mode_limits_itself_to_identity_surfaces(self) -> None:
        self.assertTrue(inventory._is_identity_surface("docs/bex-specification.md"))
        self.assertTrue(
            inventory._is_identity_surface(
                "src/main/resources/specifications/blue-coordination-profile-1.0.md"
            )
        )
        self.assertTrue(inventory._is_identity_surface("gradle/blue-sibling-lock.properties"))
        self.assertTrue(inventory._is_identity_surface("release/PACKAGE-MANIFEST.yaml"))
        self.assertTrue(inventory._is_identity_surface("release/receipt.json"))
        self.assertFalse(inventory._is_identity_surface("src/main/java/Example.java"))


if __name__ == "__main__":
    unittest.main()
