#!/usr/bin/env python3
"""Tests for the exact-identity migration inventory."""
from __future__ import annotations

import importlib.util
import hashlib
import json
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

    def _full_lifecycle_sources(self) -> tuple[dict[str, str], bytes]:
        baseline = inventory._full_lifecycle_oracle_baseline(
            self.repository
        )["oracles"]
        # Existing literal oracle/mutation cases describe this historical source.
        # Keep their exact bytes and all assertions when current runtime IDs move.
        current = (TOOLS / "testdata/identity-impact-full-lifecycle-d1194293.java.txt").read_bytes()
        self.assertEqual(
            "7ba009d1e6e53e48e3d9ee1f745482d1ac24cb89e9b89a2a8d7b03399399c4a9",
            hashlib.sha256(current).hexdigest(),
        )
        return baseline, current

    def test_current_lifecycle_oracles_keep_exact_roles_and_reject_mutations(self) -> None:
        baseline = inventory._full_lifecycle_oracle_baseline(self.repository)["oracles"]
        current = (self.repository / inventory.FULL_LIFECYCLE_JAVA_TEST).read_bytes()
        expected = {'fixture:full-lifecycle:duplicate-event:first-occurrence-identity': 'sha256:c465b987961e2f0ae79cfe699f48f5bf2ed898cfceb47e977ac448dd5a28f22a',
         'fixture:full-lifecycle:duplicate-event:invocation-identity': 'sha256:4e9cc1c2dad1b3ff442efafb3c867104032c681c8d89a64c42cb1fae0fbdf1cb',
         'fixture:full-lifecycle:duplicate-event:second-occurrence-identity': 'sha256:2ffc73ae79ee5ac50860995647139a2e106cc474668ae1dab9ef27c6ae272464',
         'fixture:full-lifecycle:gas-failure:embedded-work-identity': 'sha256:1cc6e957d2b9657fe61696e53c817fb928391072f25f7627023df3453549ff1a',
         'fixture:full-lifecycle:gas-failure:gas-trace-identity': 'sha256:b7c504bfac0774539471a251eb7b6e878648f4b32024621df9f647c8dcb7f8d3',
         'fixture:full-lifecycle:gas-failure:initialization-work-identity': 'sha256:c7660e2af3f67df690726f1b3fd4a404417d9b8d4fe2415d317f966f22ee6207',
         'fixture:full-lifecycle:gas-failure:invocation-identity': 'sha256:490cb3215bb7316ec47979ba173a539e61185b63dee9f896111147f02b2b593b',
         'fixture:full-lifecycle:gas-failure:rejected-charge-identity': 'sha256:0a405e3df4cda10a75658d7249b08467cd4d329fc6d5393e6dfbdad666b6bb55'}
        rotations = inventory._full_lifecycle_oracle_identity_rotations(baseline, current)
        self.assertEqual(expected, {value["stableKey"]: value["new"] for value in rotations})
        self.assertEqual(8, len(rotations))
        self.assertEqual([7, 9], next(value["positions"] for value in rotations
            if value["role"] == "embedded-work-identity"))
        # Concrete mutations retain each original negative validation boundary.
        mutations = [
            (current.replace(b"|20000|2123", b"|20001|2123", 1),
             "GAS_FAILURE_ORACLE non-identity skeleton changed"),
            (current.replace(b"31JtLEZds6saFSDKKWh4XZrWf63BQywpRUB4wDt766Jo",
                             b"44444444444444444444444444444444444444444444", 1),
             "stable identity anchor rotated: event-blue-id"),
        ]
        embedded = expected["fixture:full-lifecycle:gas-failure:embedded-work-identity"].encode()
        prefix, separator, suffix = current.rpartition(embedded)
        self.assertEqual(embedded, separator)
        mutations.append((prefix + b"sha256:" + b"a" * 64 + suffix,
                          "duplicate semantic role diverged: embedded-work-identity"))
        for mutated, diagnostic in mutations:
            with self.subTest(diagnostic=diagnostic):
                self.assertNotEqual(current, mutated)
                with self.assertRaisesRegex(ValueError, diagnostic):
                    inventory._full_lifecycle_oracle_identity_rotations(baseline, mutated)
        malformed_baseline = dict(baseline)
        malformed_baseline["DUPLICATE_EVENT_IDENTITY_ORACLE"] = malformed_baseline[
            "DUPLICATE_EVENT_IDENTITY_ORACLE"].replace(
                "sha256:6c4dedf7301ee2e6d87423d04762705ccf701861ca1c41acfc2a7ebbbc640f97",
                "not-an-identity", 1)
        malformed_current = current.replace(expected[
            "fixture:full-lifecycle:duplicate-event:invocation-identity"].encode(), b"not-an-identity", 1)
        self.assertNotEqual(current, malformed_current)
        with self.assertRaisesRegex(ValueError, "unexpected exact identity layout"):
            inventory._full_lifecycle_oracle_identity_rotations(malformed_baseline, malformed_current)

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
            {
                "path": "blue-contracts-core/src/test/java/x/OracleTest.java",
                "line": 10,
                "disposition": "requires-review-or-update",
            },
            {
                "path": "reports/modernization/ArchivedTest.java",
                "line": 11,
                "disposition": "retained-immutable-history",
            },
        ]
        partitions = inventory._reference_partitions(references)
        self.assertEqual(references[:2] + [references[3]], partitions["active"])
        self.assertEqual(
            [references[0], references[3]],
            partitions["fixtures"],
        )
        self.assertEqual([references[1]], partitions["manifests"])
        self.assertEqual(
            [references[2], references[4]],
            partitions["historical"],
        )

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
            sorted([digest, "sha256:" + raw_digest, commit, blue_id]),
            actual,
        )

    def test_exact_reference_scan_normalizes_sha_and_joins_java_literals(self) -> None:
        digest = "sha256:" + "a" * 64
        member = "4ZMfXZbSNVnEaqHVwYyYFHSfJ4JYs6VbR2oLZNqNkScr#1"
        text = (
            'private static final String HASH = "sha256:' + "a" * 20 + '"\n'
            '        + "' + "a" * 44 + '";\n'
            'private static final String MEMBER = "' + member + '";\n'
        )
        occurrences = inventory._identifier_occurrences("OracleTest.java", text)
        self.assertIn((digest, 1), occurrences)
        self.assertIn((member, 3), occurrences)
        self.assertEqual(digest, inventory._normalized_identifier("a" * 64))
        self.assertEqual(digest, inventory._normalized_identifier(digest))

    def test_closure_rotation_inventory_is_complete_and_excludes_invalid_proof(self) -> None:
        summary = self.report["summary"]
        self.assertEqual(34, summary["closureCorpusRotationCount"])
        self.assertEqual(10, summary["specializedJavaRotationCount"])
        self.assertEqual(44, summary["modeledClosureRotationCount"])
        self.assertEqual(1, summary["excludedInvalidVectorCount"])

        artifacts = {
            value["stableKey"]: value for value in self.report["artifacts"]
        }
        invocation = artifacts[
            "fixture:c-clo-34:java-invocation-identity"
        ]
        self.assertEqual(
            "sha256:7a602429b3959efe5bddf701231474a42c9cafe4c58abd65d655dc46265f9a06",
            invocation["oldExactIdentity"],
        )
        self.assertEqual(
            "sha256:e6aaa6471166daaa602aa8f505d5a467e1de6a0ee8ce4204b2b6211c2dcea8db",
            invocation["newExactIdentity"],
        )
        contracts_specification = artifacts[
            "fixture:c-clo-34:java-contracts-specification-identity"
        ]
        self.assertEqual(
            "sha256:e88147e8d6b6e8f1b0975979363ca21d3abbec96cfafeec5e106870cf5801193",
            contracts_specification["oldExactIdentity"],
        )
        self.assertEqual(
            "sha256:99445f8ad407c146804ae3bcad1e060a2c7bac3d32492808ea6fd7caf2fe7bdd",
            contracts_specification["newExactIdentity"],
        )
        current_binding = artifacts[
            "fixture:c-clo-34:java-invocation-binding"
        ]
        self.assertEqual(
            current_binding["newExactIdentity"],
            current_binding["oldExactIdentity"],
        )
        excluded = self.report["excludedIdentityVectors"]
        self.assertEqual(inventory.INVALID_CYCLIC_PROOF_MASTER, excluded[0]["identity"])
        self.assertIn("BAD_CYCLIC_PROOF", excluded[0]["reason"])

    def test_full_lifecycle_oracle_rotations_are_role_preserving_and_complete(self) -> None:
        baseline, current = self._full_lifecycle_sources()
        rotations = inventory._full_lifecycle_oracle_identity_rotations(
            baseline,
            current,
        )
        actual = {
            value["stableKey"]: (value["old"], value["new"])
            for value in rotations
        }
        self.assertEqual(
            {
                "fixture:full-lifecycle:duplicate-event:invocation-identity": (
                    "sha256:6c4dedf7301ee2e6d87423d04762705ccf701861ca1c41acfc2a7ebbbc640f97",
                    "sha256:9a09924bb68966c33a1fd2ca6fa2d71eb163d08eb82c60367f8cec8b30edd00e",
                ),
                "fixture:full-lifecycle:duplicate-event:first-occurrence-identity": (
                    "sha256:53365d2d325dca5499055a7780b3848ad597697ca244e3ab86f7f112c8663095",
                    "sha256:93c7be872d7c8f261a63b7f972147c997e3eeaf44dd208c0737e2d4ac91d1055",
                ),
                "fixture:full-lifecycle:duplicate-event:second-occurrence-identity": (
                    "sha256:7dde48d6e83e259960c1bd6edf6d1f6d54bd8798d91fd1d4c7a3cd9056eec9a8",
                    "sha256:7ce98318d2daf3a04752d75fe17d880d31d841415d2021a92c093eeef4d7257c",
                ),
                "fixture:full-lifecycle:gas-failure:invocation-identity": (
                    "sha256:f866ec935ec5e02c380033741a667dcd182ca3835a7dc33a5ec2fda94d6ac7e7",
                    "sha256:e0281a012d85796b57810ca21d84f441d350be3382dea5be496f39f71377ef45",
                ),
                "fixture:full-lifecycle:gas-failure:gas-trace-identity": (
                    "sha256:8d9e5c8892401acc8eecb26acb39a8c172879bc6d6bf4f1ea5203f7564304491",
                    "sha256:73d4b84884c99293ec8628bf628bae0ef37de348f44bbbf7a60a4b490008129d",
                ),
                "fixture:full-lifecycle:gas-failure:rejected-charge-identity": (
                    "sha256:509be835825c819657ede50dbea8468e175af60511ad113584ce5b680a6db300",
                    "sha256:f94c892bed31272d2227d63866ea8a38a4c826e740fc143ed87e96fe2e9d71c7",
                ),
                "fixture:full-lifecycle:gas-failure:embedded-work-identity": (
                    "sha256:77b934032184b1206ebf6a711c51c76d6c84c08652495ed43a917f8db2992b8a",
                    "sha256:5cb549fbc9251d5eaa0a1f365c5ed04cc213f412a01199952bc052a41778c906",
                ),
                "fixture:full-lifecycle:gas-failure:initialization-work-identity": (
                    "sha256:d12892b30044cd6a7264080c609756d2662f855bd68eecef1de697584efbea6c",
                    "sha256:b4c4cd22e5528d5f22088a36e9a531018b0a17872a28f85781b39b6d282275e5",
                ),
            },
            actual,
        )
        embedded = next(
            value
            for value in rotations
            if value["role"] == "embedded-work-identity"
        )
        self.assertEqual([7, 9], embedded["positions"])
        self.assertEqual(8, len(rotations))

        artifacts = {
            value["stableKey"]: value
            for value in self.report["artifacts"]
        }
        for stable_key in actual:
            artifact = artifacts[stable_key]
            self.assertEqual("transitively-changed", artifact["classification"])
            self.assertEqual(
                inventory.FULL_LIFECYCLE_ORACLE_BASELINE_PATH,
                artifact["closureRotationEvidence"]["oracleBaselineInput"],
            )
            self.assertEqual(
                "91a0cf80ddf238a9a9648f85432884f005e75425",
                artifact["closureRotationEvidence"]
                ["oracleBaselineProvenanceCommit"],
            )
            self.assertTrue(artifact["firstChangedDependency"])

    def test_full_lifecycle_oracle_parser_rejects_malformed_or_missing_constant(self) -> None:
        baseline, current = self._full_lifecycle_sources()
        malformed = current.replace(
            b"DUPLICATE_EVENT_IDENTITY_ORACLE =",
            b"DUPLICATE_EVENT_IDENTITY_ORACLE_REMOVED =",
            1,
        )
        with self.assertRaisesRegex(
            ValueError,
            "Missing or malformed FullLifecycleAdmissionTest oracle: "
            "DUPLICATE_EVENT_IDENTITY_ORACLE",
        ):
            inventory._full_lifecycle_oracle_identity_rotations(
                baseline,
                malformed,
            )

    def test_full_lifecycle_oracle_parser_rejects_skeleton_and_layout_drift(self) -> None:
        baseline, current = self._full_lifecycle_sources()
        skeleton_drift = current.replace(
            b"|20000|2123",
            b"|20001|2123",
            1,
        )
        with self.assertRaisesRegex(
            ValueError,
            "GAS_FAILURE_ORACLE non-identity skeleton changed",
        ):
            inventory._full_lifecycle_oracle_identity_rotations(
                baseline,
                skeleton_drift,
            )

        baseline_layout_drift = dict(baseline)
        baseline_layout_drift["DUPLICATE_EVENT_IDENTITY_ORACLE"] = (
            baseline_layout_drift["DUPLICATE_EVENT_IDENTITY_ORACLE"].replace(
                "sha256:6c4dedf7301ee2e6d87423d04762705ccf701861ca1c41acfc2a7ebbbc640f97",
                "not-an-identity",
                1,
            )
        )
        current_layout_drift = current.replace(
            b"sha256:9a09924bb68966c33a1fd2ca6fa2d71eb163d08eb82c60367f8cec8b30edd00e",
            b"not-an-identity",
            1,
        )
        with self.assertRaisesRegex(
            ValueError,
            "DUPLICATE_EVENT_IDENTITY_ORACLE has an unexpected exact identity layout",
        ):
            inventory._full_lifecycle_oracle_identity_rotations(
                baseline_layout_drift,
                current_layout_drift,
            )

    def test_reviewed_baselines_have_strict_shapes(self) -> None:
        baseline_path = self.repository / (
            inventory.FULL_LIFECYCLE_ORACLE_BASELINE_PATH
        )
        parsed = inventory._parse_full_lifecycle_oracle_baseline(
            baseline_path.read_bytes()
        )
        self.assertEqual(
            inventory.FULL_LIFECYCLE_ORACLE_BASELINE_SCHEMA,
            parsed["schema"],
        )
        self.assertEqual(
            inventory.FULL_LIFECYCLE_ORACLE_NAMES,
            set(parsed["oracles"]),
        )
        self.assertIn(
            inventory.FULL_LIFECYCLE_ORACLE_BASELINE_PATH,
            inventory.REVIEWED_IDENTITY_BASELINE_INPUTS,
        )
        self.assertIn(
            inventory.FULL_LIFECYCLE_ORACLE_BASELINE_PATH,
            inventory.SELF_PATHS,
        )
        classifier_path = self.repository / (
            inventory.CLASSIFIER_IMPLEMENTATION_BASELINE_PATH
        )
        classifier_baseline = (
            inventory._parse_classifier_implementation_baseline(
                classifier_path.read_bytes()
            )
        )
        self.assertEqual(
            inventory.CLASSIFIER_IMPLEMENTATION_BASELINE_SCHEMA,
            classifier_baseline["schema"],
        )
        self.assertEqual(
            inventory.CLASSIFIER_IMPLEMENTATION_BASELINE_PROVENANCE,
            classifier_baseline["provenanceCommit"],
        )
        self.assertIn(
            inventory.CLASSIFIER_IMPLEMENTATION_BASELINE_PATH,
            inventory.REVIEWED_IDENTITY_BASELINE_INPUTS,
        )
        self.assertIn(
            inventory.CLASSIFIER_IMPLEMENTATION_BASELINE_PATH,
            inventory.SELF_PATHS,
        )
        executable_classifier = (
            "blue-conformance/src/main/tools/"
            "classify_fixture_identity_delta.py"
        )
        self.assertNotIn(executable_classifier, inventory.SELF_PATHS)
        self.assertFalse(inventory._is_declared_history(executable_classifier))
        self.assertIn(
            executable_classifier,
            inventory._tracked_text_files(self.repository),
        )
        reviewed = self.report["scope"]["reviewedBaselineInputs"]
        self.assertEqual(
            {
                inventory.FULL_LIFECYCLE_ORACLE_BASELINE_PATH,
                inventory.CLASSIFIER_IMPLEMENTATION_BASELINE_PATH,
            },
            {entry["path"] for entry in reviewed},
        )
        self.assertTrue(
            all(
                entry["referenceScanDisposition"]
                == "excluded-reviewed-generator-input"
                for entry in reviewed
            )
        )

    def test_full_lifecycle_baseline_rejects_malformed_inputs(self) -> None:
        baseline_path = self.repository / (
            inventory.FULL_LIFECYCLE_ORACLE_BASELINE_PATH
        )
        valid = json.loads(baseline_path.read_text(encoding="utf-8"))

        with self.assertRaisesRegex(ValueError, "Invalid reviewed.*JSON"):
            inventory._parse_full_lifecycle_oracle_baseline(b"{")

        extra_key = dict(valid)
        extra_key["unexpected"] = True
        with self.assertRaisesRegex(ValueError, "exact reviewed shape"):
            inventory._parse_full_lifecycle_oracle_baseline(
                json.dumps(extra_key).encode("utf-8")
            )

        missing_oracle = dict(valid)
        missing_oracle["oracles"] = dict(valid["oracles"])
        del missing_oracle["oracles"]["GAS_FAILURE_ORACLE"]
        with self.assertRaisesRegex(ValueError, "exactly the two reviewed"):
            inventory._parse_full_lifecycle_oracle_baseline(
                json.dumps(missing_oracle).encode("utf-8")
            )

        invalid_provenance = dict(valid)
        invalid_provenance["provenanceCommit"] = "main"
        with self.assertRaisesRegex(ValueError, "lowercase 40-hex"):
            inventory._parse_full_lifecycle_oracle_baseline(
                json.dumps(invalid_provenance).encode("utf-8")
            )

    def test_full_lifecycle_oracle_parser_rejects_anchor_and_duplicate_drift(self) -> None:
        baseline, current = self._full_lifecycle_sources()
        stable_anchor_drift = current.replace(
            b"31JtLEZds6saFSDKKWh4XZrWf63BQywpRUB4wDt766Jo",
            b"44444444444444444444444444444444444444444444",
            1,
        )
        with self.assertRaisesRegex(
            ValueError,
            "stable identity anchor rotated: event-blue-id",
        ):
            inventory._full_lifecycle_oracle_identity_rotations(
                baseline,
                stable_anchor_drift,
            )

        embedded = (
            b"sha256:5cb549fbc9251d5eaa0a1f365c5ed04cc213f412a01199952bc052a41778c906"
        )
        prefix, separator, suffix = current.rpartition(embedded)
        self.assertEqual(embedded, separator)
        duplicate_drift = (
            prefix
            + b"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            + suffix
        )
        with self.assertRaisesRegex(
            ValueError,
            "duplicate semantic role diverged: embedded-work-identity",
        ):
            inventory._full_lifecycle_oracle_identity_rotations(
                baseline,
                duplicate_drift,
            )

    def test_java_identity_oracle_audit_is_explicit_and_fails_closed(self) -> None:
        audit = self.report["javaIdentityOracleAudit"]
        self.assertEqual(
            {
                "DUPLICATE_EVENT_IDENTITY_ORACLE": 4,
                "GAS_FAILURE_ORACLE": 10,
            },
            {
                value["constant"]: value["exactIdentifierCount"]
                for value in audit
            },
        )
        self.assertTrue(all(
            value["classification"]
            == inventory.FULL_LIFECYCLE_ORACLE_CLASSIFICATION
            for value in audit
        ))

        unknown = (
            b'class Unknown { static final String SURPRISE_ORACLE = "sha256:'
            + b"a" * 64
            + b'"; }'
        )
        with self.assertRaisesRegex(
            ValueError,
            "Unclassified nonhistorical Java identity oracle: "
            "src/test/java/Unknown.java#SURPRISE_ORACLE",
        ):
            inventory._classify_java_identity_oracles(
                {"src/test/java/Unknown.java": unknown},
                {},
            )

    def test_java_identity_oracle_audit_rejects_every_computed_declaration(self) -> None:
        sources = {
            "constant": (
                'class Computed { String CONSTANT_ORACLE = "ready" '
                '+ SUFFIX; }'
            ),
            "property": (
                'class Computed { String PROPERTY_ORACLE = '
                'System.getProperty("oracle", "ready"); }'
            ),
        }
        for name, source in sources.items():
            with self.subTest(name=name):
                with self.assertRaisesRegex(
                    ValueError,
                    "Java oracle must be a literal-only String concatenation: "
                    "src/test/java/Computed.java#",
                ):
                    inventory._classify_java_identity_oracles(
                        {"src/test/java/Computed.java": source.encode("utf-8")},
                        {},
                    )

    def test_java_identity_oracle_audit_finds_hidden_multi_declarator(self) -> None:
        identity = "sha256:" + "c" * 64
        source = (
            'class Hidden { String status = "ready", '
            'HIDDEN_ORACLE = "' + identity + '"; }'
        ).encode("utf-8")
        with self.assertRaisesRegex(
            ValueError,
            "Unclassified nonhistorical Java identity oracle: "
            "src/test/java/Hidden.java#HIDDEN_ORACLE",
        ):
            inventory._classify_java_identity_oracles(
                {"src/test/java/Hidden.java": source},
                {},
            )

    def test_java_identity_oracle_backstop_rejects_generic_comma_hiding(self) -> None:
        identity = "sha256:" + "d" * 64
        initializers = {
            "constructor": "new Pair<String, String>().toString()",
            "explicit-method-types": "Util.<String, String>render()",
        }
        for name, initializer in initializers.items():
            source = (
                "class Hidden { String status = " + initializer
                + ', HIDDEN_ORACLE = "' + identity + '"; }'
            ).encode("utf-8")
            with self.subTest(name=name):
                with self.assertRaisesRegex(
                    ValueError,
                    "Unparsed uppercase Java oracle assignment: "
                    "src/test/java/Hidden.java#HIDDEN_ORACLE",
                ):
                    inventory._classify_java_identity_oracles(
                        {"src/test/java/Hidden.java": source},
                        {},
                    )

    def test_java_identity_oracle_rejects_empty_argument_annotation_decoration(self) -> None:
        identity = "sha256:" + "2" * 64
        source = (
            'class Hidden { String HIDDEN_ORACLE @Ann() [] = {"'
            + identity + '"}; }'
        ).encode("utf-8")
        with self.assertRaisesRegex(
            ValueError,
            "Post-name Java oracle decoration is forbidden: "
            "src/test/java/Hidden.java#HIDDEN_ORACLE",
        ):
            inventory._classify_java_identity_oracles(
                {"src/test/java/Hidden.java": source},
                {},
            )

    def test_java_identity_oracle_rejects_argument_annotation_decoration(self) -> None:
        identity = "sha256:" + "3" * 64
        source = (
            'class Hidden { String HIDDEN_ORACLE @Ann(value = 1) [] = {"'
            + identity + '"}; }'
        ).encode("utf-8")
        with self.assertRaisesRegex(
            ValueError,
            "Post-name Java oracle decoration is forbidden: "
            "src/test/java/Hidden.java#HIDDEN_ORACLE",
        ):
            inventory._classify_java_identity_oracles(
                {"src/test/java/Hidden.java": source},
                {},
            )

    def test_java_identity_oracle_backstop_rejects_annotated_array(self) -> None:
        identity = "sha256:" + "e" * 64
        source = (
            'class Hidden { String @Ann [] HIDDEN_ORACLE = "'
            + identity + '"; }'
        ).encode("utf-8")
        with self.assertRaisesRegex(
            ValueError,
            "Unparsed uppercase Java oracle assignment: "
            "src/test/java/Hidden.java#HIDDEN_ORACLE",
        ):
            inventory._classify_java_identity_oracles(
                {"src/test/java/Hidden.java": source},
                {},
            )

    def test_java_identity_oracle_audit_rejects_augmented_assignment(self) -> None:
        identity = "sha256:" + "f" * 64
        source = (
            'class Hidden { String HIDDEN_ORACLE = ""; '
            'HIDDEN_ORACLE += "' + identity + '"; }'
        ).encode("utf-8")
        with self.assertRaisesRegex(
            ValueError,
            "Augmented Java oracle assignment is forbidden: "
            "src/test/java/Hidden.java#HIDDEN_ORACLE",
        ):
            inventory._classify_java_identity_oracles(
                {"src/test/java/Hidden.java": source},
                {},
            )

    def test_java_identity_oracle_backstop_rejects_post_name_array_dims(self) -> None:
        identity = "sha256:" + "1" * 64
        declarations = {
            "multidimensional": (
                'String HIDDEN_ORACLE[][] = {{"' + identity + '"}};'
            ),
            "annotated": (
                'String HIDDEN_ORACLE @Ann [] = {"' + identity + '"};'
            ),
        }
        for name, declaration in declarations.items():
            source = ("class Hidden { " + declaration + " }").encode("utf-8")
            with self.subTest(name=name):
                with self.assertRaisesRegex(
                    ValueError,
                    "Post-name Java oracle decoration is forbidden: "
                    "src/test/java/Hidden.java#HIDDEN_ORACLE",
                ):
                    inventory._classify_java_identity_oracles(
                        {"src/test/java/Hidden.java": source},
                        {},
                    )

    def test_java_identity_oracle_audit_classifies_identity_free_and_ignores_decoys(self) -> None:
        identity = "sha256:" + "b" * 64
        source = (
            "class Legitimate {\n"
            "  static final String STATUS_ORACLE = \"ready\";\n"
            "  // static final String COMMENT_ORACLE = \"" + identity + "\";\n"
            "  // String COMMENT_DECORATED_ORACLE @Ann() [] = \""
            + identity + "\";\n"
            "  static final String DESCRIPTION = \"String TEXT_ORACLE = "
            + identity + ";\";\n"
            "  static final String DECORATED_DESCRIPTION = \"String "
            "TEXT_DECORATED_ORACLE @Ann(value = 1) [] = " + identity + ";\";\n"
            "}\n"
        ).encode("utf-8")
        self.assertEqual(
            [
                {
                    "path": "src/test/java/Legitimate.java",
                    "constant": "STATUS_ORACLE",
                    "classification": "identity-free-java-oracle",
                    "exactIdentifierCount": 0,
                }
            ],
            inventory._classify_java_identity_oracles(
                {"src/test/java/Legitimate.java": source},
                {},
            ),
        )
        self.assertEqual(
            [],
            inventory._classify_java_identity_oracles(
                {"reports/modernization/Archived.java": (
                    b'class Archived { static final String OLD_ORACLE = "'
                    + identity.encode("utf-8")
                    + b'"; }'
                )},
                {},
            ),
        )

    def test_cclo10_reordered_limit_form_matches_stable_document_identity(self) -> None:
        document_a = "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"
        document_b = "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"
        old_a = "7iWdksGRG7vaHczbr18QqK8Ex598ZBjQJdMZbAe3nRHB"
        old_b = "DwqgPP4QrvY1f96zSo7k6hgoq1YRi2CD8Hkvu2YTLgNA"
        new_a = "ERrv83b9RbGZSg2vyzFmzQiU2KoLyfzkuEn234gm7oFA"
        new_b = "8uvxz52uKPTRgGi4bB9HDnd5wVi4uGZ9ot1jtZppXA7Z"
        baseline = {
            "canonicalLimitForm": [
                {
                    "documentId": {"blueId": document_b},
                    "contracts": {"blueId": old_b},
                },
                {
                    "documentId": {"blueId": document_a},
                    "contracts": {"blueId": old_a},
                },
            ]
        }
        current = {
            "canonicalLimitForm": [
                {
                    "documentId": {"blueId": document_a},
                    "contracts": {"blueId": new_a},
                },
                {
                    "documentId": {"blueId": document_b},
                    "contracts": {"blueId": new_b},
                },
            ]
        }
        pairs = inventory._paired_identity_scalars(baseline, current)
        rotations = {
            old: (new, old_pointer, new_pointer)
            for old, new, old_pointer, new_pointer in pairs
            if old in {old_a, old_b}
        }
        self.assertEqual(
            {
                old_a: (
                    new_a,
                    "/canonicalLimitForm/1/contracts/blueId",
                    "/canonicalLimitForm/0/contracts/blueId",
                ),
                old_b: (
                    new_b,
                    "/canonicalLimitForm/0/contracts/blueId",
                    "/canonicalLimitForm/1/contracts/blueId",
                ),
            },
            rotations,
        )

        artifacts = {
            value["oldExactIdentity"]: value
            for value in self.report["artifacts"]
        }
        self.assertEqual(new_a, artifacts[old_a]["newExactIdentity"])
        self.assertEqual(new_b, artifacts[old_b]["newExactIdentity"])

    def test_ambiguous_repeated_identity_shapes_are_not_paired_by_index(self) -> None:
        old = [
            {"blueId": "7iWdksGRG7vaHczbr18QqK8Ex598ZBjQJdMZbAe3nRHB"},
            {"blueId": "DwqgPP4QrvY1f96zSo7k6hgoq1YRi2CD8Hkvu2YTLgNA"},
        ]
        new = [
            {"blueId": "8uvxz52uKPTRgGi4bB9HDnd5wVi4uGZ9ot1jtZppXA7Z"},
            {"blueId": "ERrv83b9RbGZSg2vyzFmzQiU2KoLyfzkuEn234gm7oFA"},
        ]
        self.assertEqual([], inventory._aligned_list_indexes(old, new))
        self.assertEqual([], inventory._paired_identity_scalars(old, new))

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
