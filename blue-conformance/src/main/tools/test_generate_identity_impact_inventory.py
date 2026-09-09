#!/usr/bin/env python3
"""Tests for the exact-identity migration inventory."""
from __future__ import annotations

import importlib.util
import hashlib
import json
from pathlib import Path
import re
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

    def _reviewed_lifecycle_roles(self) -> dict[str, tuple[str, str, str, list[int]]]:
        # This reviewed adoption already changed the Java literals. Keep the
        # earlier migration baseline and derive no expectations from report output.
        provenance = "957e24f76d74fc1cf2b994fcd5d149c3979c978c"
        archived = inventory._baseline_bytes(
            self.repository, provenance, inventory.FULL_LIFECYCLE_JAVA_TEST)
        self.assertEqual(
            "2baa9de01b819190ff0e5995d148b7313493acdedca0e7138b5bb9f348f0b59a",
            hashlib.sha256(archived).hexdigest())
        baseline = inventory._full_lifecycle_oracle_baseline(self.repository)
        self.assertEqual("91a0cf80ddf238a9a9648f85432884f005e75425",
                         baseline["provenanceCommit"])
        original = inventory._baseline_bytes(
            self.repository, baseline["provenanceCommit"], inventory.FULL_LIFECYCLE_JAVA_TEST)
        current = (self.repository / inventory.FULL_LIFECYCLE_JAVA_TEST).read_bytes()
        duplicate = "DUPLICATE_EVENT_IDENTITY_ORACLE"
        gas = "GAS_FAILURE_ORACLE"
        roles = {
            "fixture:full-lifecycle:duplicate-event:invocation-identity": (
                "sha256:6c4dedf7301ee2e6d87423d04762705ccf701861ca1c41acfc2a7ebbbc640f97",
                "sha256:ce40b086e35d158583ea85606ae807cdd3eb6a1ec6912f557daf2f943f065ccc",
                duplicate, [0]),
            "fixture:full-lifecycle:duplicate-event:first-occurrence-identity": (
                "sha256:53365d2d325dca5499055a7780b3848ad597697ca244e3ab86f7f112c8663095",
                "sha256:6ed473999a105e8baca12eb6e7c8a441ff7ad2497d729b52595fe9876458594e",
                duplicate, [2]),
            "fixture:full-lifecycle:duplicate-event:second-occurrence-identity": (
                "sha256:7dde48d6e83e259960c1bd6edf6d1f6d54bd8798d91fd1d4c7a3cd9056eec9a8",
                "sha256:e84572de20fce5cce2dc08f8ded11eabd430ebd2f40825e404d9b05c04f905bb",
                duplicate, [3]),
            "fixture:full-lifecycle:gas-failure:invocation-identity": (
                "sha256:f866ec935ec5e02c380033741a667dcd182ca3835a7dc33a5ec2fda94d6ac7e7",
                "sha256:1296fc8f2b7be431b7cc1c5d2a1fd0acb499a186c883b7141ef7db2cd01ac84e",
                gas, [0]),
            "fixture:full-lifecycle:gas-failure:gas-trace-identity": (
                "sha256:8d9e5c8892401acc8eecb26acb39a8c172879bc6d6bf4f1ea5203f7564304491",
                "sha256:4647823cc749a1fb9d0fdea12b54909a78f6848eff0e895b78bac9c01a09052c",
                gas, [1]),
            "fixture:full-lifecycle:gas-failure:rejected-charge-identity": (
                "sha256:509be835825c819657ede50dbea8468e175af60511ad113584ce5b680a6db300",
                "sha256:d6ce799cff80e33387de1ab42b137edd74dfbc60756af376b68ba367f10a277f",
                gas, [6]),
            "fixture:full-lifecycle:gas-failure:initialization-work-identity": (
                "sha256:d12892b30044cd6a7264080c609756d2662f855bd68eecef1de697584efbea6c",
                "sha256:ef927d94521de04c29419bc1ab4090438cafbe74f6377a06511cd553c4c49394",
                gas, [8]),
            "fixture:full-lifecycle:gas-failure:embedded-work-identity": (
                "sha256:77b934032184b1206ebf6a711c51c76d6c84c08652495ed43a917f8db2992b8a",
                "sha256:654a5b7d1dfe5573902e0728138590e49b7d4f930a7302526e8a522ed573492e",
                gas, [7, 9]),
        }
        for constant in (duplicate, gas):
            before = inventory._java_string_constant(original, constant)
            reviewed = inventory._java_string_constant(archived, constant)
            self.assertEqual(before, baseline["oracles"][constant])
            self.assertEqual(reviewed, inventory._java_string_constant(current, constant))
            # A positional source check independent of the role-classifier parser.
            pattern = r"sha256:[0-9a-f]{64}|31JtLEZds6saFSDKKWh4XZrWf63BQywpRUB4wDt766Jo"
            before_ids = re.findall(pattern, before)
            reviewed_ids = re.findall(pattern, reviewed)
            self.assertEqual(4 if constant == duplicate else 10, len(reviewed_ids))
            for old, new, name, positions in roles.values():
                if name == constant:
                    self.assertEqual([old] * len(positions), [before_ids[i] for i in positions])
                    self.assertEqual([new] * len(positions), [reviewed_ids[i] for i in positions])
        return roles

    def test_current_lifecycle_oracles_keep_exact_roles_and_reject_mutations(self) -> None:
        baseline = inventory._full_lifecycle_oracle_baseline(self.repository)["oracles"]
        current = (self.repository / inventory.FULL_LIFECYCLE_JAVA_TEST).read_bytes()
        expected = {key: row[1] for key, row in self._reviewed_lifecycle_roles().items()}
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
        # Name every reviewed source/role; a total alone cannot show completeness.
        roles = self._reviewed_lifecycle_roles()
        fixture = inventory._yaml((self.repository / inventory.C_CLO_34_FIXTURE).read_bytes())
        current_invocation = fixture["input"]["invocationIdentity"]
        current_spec = "sha256:" + hashlib.sha256(
            (self.repository / inventory.CONTRACTS_SPEC).read_bytes()).hexdigest()
        historical_invocation = "sha256:d47c4c728d36948b3a02e00f37a6f8ab3f7ff844913f7e35b6b0a5618407787f"
        java = inventory.C_CLO_34_JAVA_TEST
        authority = inventory.C_CLO_34_FIXTURE + "#/input/invocationIdentity"
        closure = {
            "fixture:c-clo-34:java-invocation-identity": (
                "sha256:7a602429b3959efe5bddf701231474a42c9cafe4c58abd65d655dc46265f9a06",
                current_invocation, java + "#C_CLO_34_INVOCATION_IDENTITY", authority),
            "fixture:c-clo-34:java-contracts-specification-identity": (
                "sha256:e88147e8d6b6e8f1b0975979363ca21d3abbec96cfafeec5e106870cf5801193",
                current_spec, java + "#C_CLO_34_CANONICAL_INVOCATION_ENVELOPE/"
                "contractsSpecificationIdentity", inventory.CONTRACTS_SPEC),
            "fixture:c-clo-34:java-invocation-binding": (
                historical_invocation, current_invocation,
                java + "#C_CLO_34_INVOCATION_IDENTITY", authority),
        }
        expected_names = set(roles) | set(closure)
        specialized_rows = [row for row in self.report["artifacts"]
            if row["artifactKind"] in ("java-frozen-fixture-identity",
                                       "java-frozen-runtime-oracle-identity")]
        artifacts = {row["stableKey"]: row for row in specialized_rows}
        self.assertEqual(expected_names, set(artifacts))
        self.assertEqual(len(expected_names), len(specialized_rows))
        summary = self.report["summary"]
        self.assertEqual(34, summary["closureCorpusRotationCount"])
        self.assertEqual(len(expected_names), summary["specializedJavaRotationCount"])
        self.assertEqual(34 + len(expected_names), summary["modeledClosureRotationCount"])
        self.assertEqual(1, summary["excludedInvalidVectorCount"])
        for key, (old, new, path, authority) in closure.items():
            with self.subTest(record=key):
                row = artifacts[key]
                self.assertEqual("java-frozen-fixture-identity", row["artifactKind"])
                self.assertEqual((old, new, path),
                    (row["oldExactIdentity"], row["newExactIdentity"], row["pathOrStableKey"]))
                self.assertEqual({"matchedLocationCount": 1, "representativeLocations": [{
                    "path": authority, "baselineJsonPointer": "", "currentJsonPointer": ""}]},
                    row["closureRotationEvidence"])
        for key, (old, new, constant, positions) in roles.items():
            with self.subTest(record=key):
                row = artifacts[key]
                role = key.rsplit(":", 1)[1]
                self.assertEqual("java-frozen-runtime-oracle-identity", row["artifactKind"])
                self.assertEqual((old, new, inventory.FULL_LIFECYCLE_JAVA_TEST + "#" + constant + "/" + role),
                    (row["oldExactIdentity"], row["newExactIdentity"], row["pathOrStableKey"]))
                self.assertEqual({
                    "matchedLocationCount": len(positions), "semanticRole": role,
                    "identifierPositions": positions,
                    "oracleBaselineInput": inventory.FULL_LIFECYCLE_ORACLE_BASELINE_PATH,
                    "oracleBaselineProvenanceCommit": "91a0cf80ddf238a9a9648f85432884f005e75425",
                    "representativeLocations": [{
                        "path": inventory.FULL_LIFECYCLE_JAVA_TEST,
                        "baselineJsonPointer": "#" + constant + "/identity/" + str(position),
                        "currentJsonPointer": "#" + constant + "/identity/" + str(position),
                    } for position in positions],
                }, row["closureRotationEvidence"])
        for row in specialized_rows:
            self.assertEqual([], row["storedReferencesRequiringUpdate"])
            self.assertEqual("transitively-changed", row["classification"])
            self.assertEqual("blue-contracts-core", row["repositoryModule"])
        binding = artifacts["fixture:c-clo-34:java-invocation-binding"]
        self.assertNotEqual(binding["oldExactIdentity"], binding["newExactIdentity"])
        self.assertEqual([{
            "path": java, "line": 43, "disposition": "retained-immutable-history",
            "historicalDeclarationProof": {
                "path": java, "line": 43, "identity": historical_invocation,
                "symbol": "ClosureInvocationVerifierTest#C_CLO_34_INVOCATION_IDENTITY",
                "provenanceCommit": "3ee576244a1951583e42eec3a8c29a4f703a73e9",
                "canonicalEnvelopeBytes": 2131,
                "canonicalEnvelopeSha256": historical_invocation.removeprefix("sha256:"),
            },
        }], binding["historicalReferencesRetained"])
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



class Cclo34HistoricalProjectionTest(unittest.TestCase):
    """Source mutations cross the real scanner proof and its existing partitions."""

    @classmethod
    def setUpClass(cls):
        cls.repository = TOOLS.parents[3]
        cls.source = (cls.repository / inventory.C_CLO_34_JAVA_TEST).read_bytes()
        cls.digest = inventory._java_string_constant(cls.source, "C_CLO_34_INVOCATION_IDENTITY")

    def proof(self, changed=None):
        from unittest.mock import patch
        original = inventory._current_bytes
        with patch.object(inventory, "_current_bytes", side_effect=lambda root, path:
                changed[path] if changed and path in changed else original(root, path)):
            return inventory._cclo34_historical_reference(self.repository)

    def test_exact_historical_declaration_and_current_constructor_both_verify(self):
        proof = self.proof()
        self.assertEqual(self.digest, proof["identity"])
        self.assertEqual(2131, proof["canonicalEnvelopeBytes"])
        self.assertEqual("ClosureInvocationVerifierTest#C_CLO_34_INVOCATION_IDENTITY", proof["symbol"])
        self.assertEqual(43, proof["line"])
        current = inventory._cclo34_current_envelope(self.repository)
        self.assertNotEqual(self.digest, "sha256:" + hashlib.sha256(current).hexdigest())

    def test_changed_frozen_envelope_digest_and_source_context_reject(self):
        changes = {
            "digest": self.source.replace(self.digest.encode(), b"sha256:" + b"f" * 64, 1),
            "envelope": self.source.replace(b'\\"inputGraphGeneration\\":1', b'\\"inputGraphGeneration\\":2', 1),
            "assertion": self.source.replace(b"assertEquals(2131,", b"assertEquals(2132,", 1),
            "annotation": self.source.replace(b"    @Test\n    void shouldMatchReleasedCclo34FullInvocationIdentity", b"    void shouldMatchReleasedCclo34FullInvocationIdentity", 1),
            "class": self.source.replace(b"final class ClosureInvocationVerifierTest {", b"final class AnotherTest {", 1),
            "package": self.source.replace(b"package blue.language.processor.closure;", b"package blue.language.processor.other;", 1),
            "same-line": self.source.replace(self.digest.encode() + b'";', self.digest.encode() + b'"; // ' + self.digest.encode(), 1),
        }
        for name, data in changes.items():
            with self.subTest(name=name):
                self.assertNotEqual(self.source, data)
                with self.assertRaisesRegex(ValueError, "Historical C-CLO-34"):
                    self.proof({inventory.C_CLO_34_JAVA_TEST: data})

    def test_historical_provenance_specification_and_implementation_operands_reject(self):
        for path in (inventory.CCLO34_HISTORICAL_SPEC, inventory.CCLO34_HISTORICAL_RELEASE):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "retained provenance operand"):
                self.proof({path: (self.repository / path).read_bytes() + b"\n"})
        text = self.source.decode()
        selected, _ = inventory._cclo34_source_slice(text, "releasedEnvironment", True)
        for name in ("contractsSpecificationIdentity", "cyclicFinalizerIdentity", "cyclicProofVerifierIdentity"):
            envelope = json.loads(inventory._java_string_constant(self.source, "C_CLO_34_CANONICAL_INVOCATION_ENVELOPE"))
            old = envelope["value"][name]
            modified = selected.replace(old, "sha256:" + "f" * 64, 1)
            self.assertNotEqual(selected, modified)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "anchored source changed: releasedEnvironment"):
                self.proof({inventory.C_CLO_34_JAVA_TEST: text.replace(selected, modified, 1).encode()})
        from unittest.mock import patch
        original = inventory._baseline_bytes
        with patch.object(inventory, "_baseline_bytes", side_effect=lambda root, revision, path:
                self.source + b"\n" if revision == inventory.CCLO34_HISTORICAL_PROVENANCE and path == inventory.C_CLO_34_JAVA_TEST
                else original(root, revision, path)):
            with self.assertRaisesRegex(ValueError, "provenance source identity"):
                self.proof()

    def fixture_changes(self, change, rebind):
        import yaml
        data = yaml.safe_load((self.repository / inventory.C_CLO_34_FIXTURE).read_text())
        change(data)
        encoded = yaml.safe_dump(data, sort_keys=False).encode()
        result = {inventory.C_CLO_34_FIXTURE: encoded}
        if rebind:
            manifest = yaml.safe_load((self.repository / inventory.CLOSURE_FIXTURES).read_text())
            row = next(row for row in manifest["files"] if row["path"] == "closure/c-clo-34-separate-document-steps.yaml")
            row.update(sha256=hashlib.sha256(encoded).hexdigest(), bytes=len(encoded))
            result[inventory.CLOSURE_FIXTURES] = yaml.safe_dump(manifest, sort_keys=False).encode()
        return result

    def test_current_yaml_runtime_and_constructor_drift_remain_rejected(self):
        changes = [
            self.fixture_changes(lambda data: data["input"]["gasPolicy"].update(sharedLimit=100001), False),
            self.fixture_changes(lambda data: data["input"]["environment"].update(contractsSpecificationIdentity="sha256:" + "f" * 64), True),
            self.fixture_changes(lambda data: data["input"].update(invocationIdentity="sha256:" + "f" * 64), True),
        ]
        runtime = (self.repository / inventory.CCLO34_RUNTIME_DESCRIPTOR).read_bytes()
        finalizer = inventory._java_string_constant(runtime, "CYCLIC_FINALIZER_IDENTITY")
        changes.append({inventory.CCLO34_RUNTIME_DESCRIPTOR: runtime.replace(finalizer.encode(), b"sha256:" + b"f" * 64, 1)})
        changes.append({inventory.CONTRACTS_SPEC: (self.repository / inventory.CONTRACTS_SPEC).read_bytes() + b"\n"})
        for index, changed in enumerate(changes):
            with self.subTest(index=index), self.assertRaisesRegex(ValueError, "Current C-CLO-34"):
                self.proof(changed)

    def test_coherent_current_substitution_cannot_replace_rooted_historical_vector(self):
        text = self.source.decode()
        current = inventory._cclo34_current_envelope(self.repository)
        field, _ = inventory._cclo34_source_slice(text, "C_CLO_34_CANONICAL_INVOCATION_ENVELOPE", False)
        replacement = "    private static final String C_CLO_34_CANONICAL_INVOCATION_ENVELOPE =\n            " + json.dumps(current.decode()) + ";\n"
        changed = text.replace(field, replacement, 1).replace(self.digest, "sha256:" + hashlib.sha256(current).hexdigest(), 1)
        with self.assertRaisesRegex(ValueError, "Historical C-CLO-34 anchored source changed"):
            self.proof({inventory.C_CLO_34_JAVA_TEST: changed.encode()})

    def test_only_reviewed_occurrence_is_historical_extra_same_identity_stays_active(self):
        from unittest.mock import patch
        extra = self.source.replace(b"final class ClosureInvocationVerifierTest {", b"final class ClosureInvocationVerifierTest {\n    private static final String CURRENT_BINDING = \"" + self.digest.encode() + b"\";", 1)
        original = inventory._current_bytes
        with patch.object(inventory, "_current_bytes", side_effect=lambda root, path:
                extra if path == inventory.C_CLO_34_JAVA_TEST else original(root, path)), patch.object(
                inventory, "_tracked_text_files", return_value=[inventory.C_CLO_34_JAVA_TEST]):
            references = inventory._reference_index(self.repository, inventory.DEFAULT_BASELINE)[self.digest]
        partitions = inventory._reference_partitions(references)
        self.assertEqual(1, len(partitions["historical"]))
        self.assertEqual(1, len(partitions["active"]))
        self.assertEqual(44, partitions["historical"][0]["line"])
        self.assertEqual(31, partitions["active"][0]["line"])
        with self.assertRaisesRegex(ValueError, "active old-identity references=1"):
            inventory._validate_upstream_report({"summary": {"unresolvedArtifactCount": 0, "mirrorMismatchCount": 0,
                "activeStoredReferenceCount": len(partitions["active"])}, "noCompatibilityAliases": False})


if __name__ == "__main__":
    unittest.main()
