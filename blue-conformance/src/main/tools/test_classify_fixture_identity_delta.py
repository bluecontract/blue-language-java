#!/usr/bin/env python3
"""Focused fail-closed tests for the fixture identity-delta classifier."""

from __future__ import annotations

from pathlib import Path
from copy import deepcopy
import hashlib
import tempfile
import unittest
from unittest.mock import patch

import yaml

import classify_fixture_identity_delta as classifier
from implementation_baseline import (
    CYCLIC_FINALIZER,
    CYCLIC_PROOF_VERIFIER,
    IMPLEMENTATION_BASELINE_SOURCE_PATHS,
    source_paths_for_role,
)


RESOURCE_ROOT = (
    Path(__file__).resolve().parent.parent
    / "resources"
    / "blue-contracts-closure-1.0"
)
REPOSITORY_ROOT = Path(__file__).resolve().parents[4]


def write_yaml(root: Path, relative: str, value: object) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        yaml.safe_dump(value, sort_keys=False),
        encoding="utf-8",
    )


class ClassifyFixtureIdentityDeltaTest(unittest.TestCase):

    def classify_pair(
        self, relative: str, before_value: object, after_value: object
    ) -> dict[str, object]:
        temporary = tempfile.TemporaryDirectory(prefix="identity-delta-test-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        before = root / "before"
        after = root / "after"
        write_yaml(before, relative, before_value)
        write_yaml(after, relative, after_value)
        return classifier.classify(before, after)

    @staticmethod
    def admission_fixture(*, rooted: bool, quantity: int = 1) -> dict[str, object]:
        entry: dict[str, object] = {
            "sequence": 2,
            "namespace": "processor",
            "counter": "managedDocumentOpened",
            "quantity": quantity,
            "weight": 10,
            "subtotal": 10,
            "documentId": "root",
            "reason": "admission.document.root",
        }
        if rooted:
            entry.update({
                "scopePath": "/",
                "activationGeneration": 0,
                "componentGeneration": 1,
            })
        trace = [entry]
        return {
            "operation": "admit-closure",
            "expected": {
                "gasTrace": trace,
                "gasTraceIdentity": classifier.gas_trace_identity(trace),
            },
        }

    def test_exact_normative_admission_context_removal_is_expected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            root = Path(name)
            before = root / "before"
            after = root / "after"
            fixture = "fixtures/closure/c-clo-01.yaml"
            write_yaml(before, fixture, self.admission_fixture(rooted=True))
            write_yaml(after, fixture, self.admission_fixture(rooted=False))

            report = classifier.classify(before, after)

            self.assertEqual(0, report["unexpectedCount"])
            self.assertEqual(1, report["summary"][classifier.TRACE])
            self.assertNotIn(
                classifier.SEMANTIC, report["files"][0]["categories"]
            )

    def test_partial_admission_context_removal_is_unexpected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            root = Path(name)
            before = root / "before"
            after = root / "after"
            fixture = "fixtures/closure/c-clo-01.yaml"
            before_fixture = self.admission_fixture(rooted=True)
            after_fixture = self.admission_fixture(rooted=True)
            after_fixture["expected"]["gasTrace"][0].pop("scopePath")  # type: ignore[index]
            after_trace = after_fixture["expected"]["gasTrace"]  # type: ignore[index]
            after_fixture["expected"]["gasTraceIdentity"] = (  # type: ignore[index]
                classifier.gas_trace_identity(after_trace)
            )
            write_yaml(before, fixture, before_fixture)
            write_yaml(after, fixture, after_fixture)

            report = classifier.classify(before, after)

            self.assertEqual(1, report["unexpectedCount"])
            self.assertIn(
                classifier.UNEXPECTED, report["files"][0]["categories"]
            )

    def test_unrelated_admission_gas_change_is_unexpected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            root = Path(name)
            before = root / "before"
            after = root / "after"
            fixture = "fixtures/closure/c-clo-01.yaml"
            write_yaml(before, fixture, self.admission_fixture(rooted=True))
            write_yaml(
                after,
                fixture,
                self.admission_fixture(rooted=True, quantity=2),
            )

            report = classifier.classify(before, after)

            self.assertEqual(1, report["unexpectedCount"])
            self.assertIn(
                classifier.UNEXPECTED, report["files"][0]["categories"]
            )

    def test_process_closure_cannot_use_the_admission_context_exception(self) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            root = Path(name)
            before = root / "before"
            after = root / "after"
            fixture = "fixtures/closure/c-clo-01.yaml"
            before_fixture = self.admission_fixture(rooted=True)
            after_fixture = self.admission_fixture(rooted=False)
            before_fixture["operation"] = "process-closure"
            after_fixture["operation"] = "process-closure"
            write_yaml(before, fixture, before_fixture)
            write_yaml(after, fixture, after_fixture)

            report = classifier.classify(before, after)

            self.assertEqual(1, report["unexpectedCount"])
            self.assertIn(
                classifier.UNEXPECTED, report["files"][0]["categories"]
            )

    def test_process_closure_identity_rebind_is_expected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            root = Path(name)
            before = root / "before"
            after = root / "after"
            fixture = "fixtures/closure/c-clo-01.yaml"
            write_yaml(
                before,
                fixture,
                {
                    "operation": "process-closure",
                    "expected": {"invocationIdentity": "sha256:before"},
                },
            )
            write_yaml(
                after,
                fixture,
                {
                    "operation": "process-closure",
                    "expected": {"invocationIdentity": "sha256:after"},
                },
            )

            report = classifier.classify(before, after)

            self.assertEqual(0, report["unexpectedCount"])
            self.assertEqual(1, report["summary"][classifier.INVOCATION])

    def test_process_closure_semantic_change_is_unexpected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            root = Path(name)
            before = root / "before"
            after = root / "after"
            fixture = "fixtures/closure/c-clo-01.yaml"
            write_yaml(
                before,
                fixture,
                {
                    "operation": "process-closure",
                    "expected": {"status": "SUCCESS", "gasUsed": 10},
                },
            )
            write_yaml(
                after,
                fixture,
                {
                    "operation": "process-closure",
                    "expected": {"status": "SUCCESS", "gasUsed": 11},
                },
            )

            report = classifier.classify(before, after)

            self.assertEqual(1, report["unexpectedCount"])
            self.assertIn(classifier.UNEXPECTED, report["files"][0]["categories"])

    @staticmethod
    def initialization_cycle_fixture(*, corrected: bool) -> dict[str, object]:
        marker = "intermediate-b" if corrected else "input-b"
        return {
            "operation": "admit-closure",
            "expected": {
                "status": "success",
                "workTrace": [
                    {"ordinal": 1, "kind": "LIFECYCLE"},
                ],
                "tentativeFinalizations": [
                    {
                        "boundary": {
                            "kind": "WORK",
                            "afterWorkOrdinal": 1 if corrected else 0,
                        },
                        "memberBlueIds": {"a": "intermediate-a", "b": "intermediate-b"},
                    },
                    {
                        "masterBlueId": "final-master",
                        "memberBlueIds": {"a": "final-a", "b": "final-b"},
                    },
                ],
                "resultingDocuments": [
                    {"documentId": "a", "document": {}},
                    {
                        "documentId": "b",
                        "document": {
                            "contracts": {
                                "initialized": {
                                    "document": {"blueId": marker},
                                },
                            },
                        },
                    },
                ],
                "resultingComponents": [{"masterBlueId": "final-master"}],
            },
        }

    def test_exact_initialization_cycle_marker_correction_is_expected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            root = Path(name)
            before = root / "before"
            after = root / "after"
            fixture = (
                "fixtures/closure/"
                "c-clo-08-cycle-during-initialization.yaml"
            )
            write_yaml(
                before,
                fixture,
                self.initialization_cycle_fixture(corrected=False),
            )
            write_yaml(
                after,
                fixture,
                self.initialization_cycle_fixture(corrected=True),
            )

            report = classifier.classify(before, after)

            self.assertEqual(0, report["unexpectedCount"])
            self.assertEqual(1, report["summary"][classifier.SEMANTIC])
            self.assertEqual(1, report["summary"][classifier.TRACE])
            self.assertEqual(1, len(report["approvedCorrections"]))
            correction = report["approvedCorrections"][0]
            self.assertEqual(1, correction["lifecycleWorkOrdinal"])
            self.assertEqual(
                "intermediate-b", correction["intermediateBMemberBlueId"]
            )
            self.assertEqual("final-master", correction["finalMasterBlueId"])

    def test_initialization_cycle_exception_rejects_unrelated_change(self) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            root = Path(name)
            before = root / "before"
            after = root / "after"
            fixture = (
                "fixtures/closure/"
                "c-clo-08-cycle-during-initialization.yaml"
            )
            before_fixture = self.initialization_cycle_fixture(corrected=False)
            after_fixture = self.initialization_cycle_fixture(corrected=True)
            after_fixture["expected"]["status"] = "rejected"  # type: ignore[index]
            write_yaml(before, fixture, before_fixture)
            write_yaml(after, fixture, after_fixture)

            report = classifier.classify(before, after)

            self.assertEqual(1, report["unexpectedCount"])
            self.assertIn(classifier.UNEXPECTED, report["files"][0]["categories"])

    def test_new_full_lifecycle_fixture_is_allowed(self) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            root = Path(name)
            before = root / "before"
            after = root / "after"
            before.mkdir()
            write_yaml(
                after,
                "fixtures/closure/fl-adm-01-root-patch-event.yaml",
                {"operation": "admit-closure"},
            )

            report = classifier.classify(before, after)

            self.assertEqual(0, report["unexpectedCount"])
            self.assertEqual(1, report["summary"][classifier.SEMANTIC])

    def test_exact_cevo_fixtures_are_allowed_and_semantic_mutations_fail(self) -> None:
        samples = tuple(sorted(classifier.APPROVED_CEVO_FIXTURE_IDENTITIES))
        for relative in samples:
            source = RESOURCE_ROOT / relative
            with self.subTest(relative=relative, mutation="none"):
                row = classifier.classify_added_file(relative, source)
                self.assertFalse(row["unexpected"])

            original = yaml.safe_load(source.read_text(encoding="utf-8"))
            for field, value in (
                ("status", "tampered"),
                ("gasUsed", 999999),
                ("event", {"kind": "Tampered"}),
                ("checkpoint", {"domain": "tampered"}),
            ):
                mutated = deepcopy(original)
                mutated.setdefault("expected", {})[field] = value
                with tempfile.TemporaryDirectory(
                    prefix="identity-delta-test-"
                ) as name, self.subTest(relative=relative, mutation=field):
                    target = Path(name) / Path(relative).name
                    write_yaml(target.parent, target.name, mutated)
                    row = classifier.classify_added_file(relative, target)
                    self.assertTrue(row["unexpected"])

            wrong_vector = deepcopy(original)
            wrong_vector["vectors"] = ["C-EVO-99"]
            with tempfile.TemporaryDirectory(
                prefix="identity-delta-test-"
            ) as name, self.subTest(relative=relative, mutation="vector"):
                target = Path(name) / Path(relative).name
                write_yaml(target.parent, target.name, wrong_vector)
                self.assertTrue(
                    classifier.classify_added_file(relative, target)[
                        "unexpected"
                    ]
                )

    def test_unapproved_cevo_filename_and_registry_collision_are_rejected(self) -> None:
        fixture = RESOURCE_ROOT / "fixtures/evo/c-evo-01.yaml"
        self.assertTrue(
            classifier.classify_added_file(
                "fixtures/evo/c-evo-99.yaml", fixture
            )["unexpected"]
        )
        scripted = RESOURCE_ROOT / "registry/ScriptedOperation.blue"
        self.assertFalse(
            classifier.classify_added_file(
                "registry/ScriptedOperation.blue", scripted
            )["unexpected"]
        )
        self.assertTrue(
            classifier.classify_added_file(
                "registry/ConflictingOperation.blue", scripted
            )["unexpected"]
        )
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            mutated = Path(name) / "ScriptedOperation.blue"
            mutated.write_text(
                scripted.read_text(encoding="utf-8") + "tampered: true\n",
                encoding="utf-8",
            )
            self.assertTrue(
                classifier.classify_added_file(
                    "registry/ScriptedOperation.blue", mutated
                )["unexpected"]
            )

    def test_vector_alias_may_only_append_the_exact_approved_vector(self) -> None:
        relative = "fixtures/closure/c-clo-02-dynamic-finite-cycle.yaml"
        before = {
            "operation": "admit-closure",
            "vectors": ["C-CLO-02"],
            "expected": {"status": "success"},
        }
        after = deepcopy(before)
        after["vectors"].append("C-EVO-13")
        before["expected"]["invocationIdentity"] = "sha256:before"
        after["expected"]["invocationIdentity"] = "sha256:after"
        self.assertEqual(0, self.classify_pair(relative, before, after)["unexpectedCount"])

        tampered = deepcopy(after)
        tampered["expected"]["status"] = "rejected"
        self.assertEqual(
            1, self.classify_pair(relative, before, tampered)["unexpectedCount"]
        )

    def test_exact_node_resource_demand_is_whole_document_bounded(self) -> None:
        relative = (
            "fixtures/closure/c-clo-22-a10-attach-a5-needs-resources.yaml"
        )
        before = {
            "operation": "admit-closure",
            "expected": {
                "status": "needs-resources",
                "specificationIdentity": "sha256:before",
            },
        }
        after = deepcopy(before)
        after["expected"]["specificationIdentity"] = "sha256:after"
        after["expected"]["resourceDemands"] = [
            deepcopy(classifier.APPROVED_EXACT_NODE_DEMAND)
        ]
        self.assertEqual(0, self.classify_pair(relative, before, after)["unexpectedCount"])

        tampered = deepcopy(after)
        tampered["expected"]["resourceDemands"][0]["sourcePath"] = "/other"
        self.assertEqual(
            1, self.classify_pair(relative, before, tampered)["unexpectedCount"]
        )

    def test_fixture_schema_accepts_only_the_exact_runtime_property(self) -> None:
        relative = "fixtures/fixture-schema.yaml"
        before = {"$defs": {"runtime": {"properties": {"handlers": {}}}}}
        after = deepcopy(before)
        after["$defs"]["runtime"]["properties"][
            "generalizationSubtypeContracts"
        ] = {"$ref": "#/$defs/blueValue"}
        self.assertEqual(0, self.classify_pair(relative, before, after)["unexpectedCount"])

        widened = deepcopy(after)
        widened["$defs"]["runtime"]["properties"]["extra"] = {}
        self.assertEqual(
            1, self.classify_pair(relative, before, widened)["unexpectedCount"]
        )

    def test_projection_catalog_accepts_exact_additions_and_rejects_drift(self) -> None:
        relative = "fixtures/projection-catalog.yaml"
        before = {
            "schema": "blue-contracts-projection-catalog/2.0",
            "entries": [
                {
                    "path": "existing.path",
                    "type": "value",
                    "definition": "Existing definition.",
                },
                {
                    "path": "existing.untyped",
                    "definition": "Existing untyped definition.",
                },
            ],
        }
        after = deepcopy(before)
        after["entries"].extend(
            {
                "path": path,
                "type": value_type,
                "definition": definition,
            }
            for path, (value_type, definition) in (
                classifier.APPROVED_PROJECTION_ADDITIONS.items()
            )
        )
        self.assertEqual(0, self.classify_pair(relative, before, after)["unexpectedCount"])

        mutations = []
        changed_existing = deepcopy(after)
        changed_existing["entries"][0]["definition"] = "Changed."
        mutations.append(changed_existing)
        deleted_existing = deepcopy(after)
        deleted_existing["entries"].pop(0)
        mutations.append(deleted_existing)
        duplicate = deepcopy(after)
        duplicate["entries"].append(deepcopy(duplicate["entries"][0]))
        mutations.append(duplicate)
        unapproved = deepcopy(after)
        unapproved["entries"].append(
            {"path": "other.path", "type": "value", "definition": "Other."}
        )
        mutations.append(unapproved)
        wrong_type = deepcopy(after)
        wrong_type["entries"][-1]["type"] = "integer"
        mutations.append(wrong_type)
        for index, mutated in enumerate(mutations):
            with self.subTest(mutation=index):
                self.assertEqual(
                    1,
                    self.classify_pair(relative, before, mutated)[
                        "unexpectedCount"
                    ],
                )

    def test_pinned_schema_constructor_and_manifest_transitions_fail_closed(self) -> None:
        cases = (
            (
                "fixtures/closure-fixture-schema.yaml",
                {"properties": {"vectors": {"pattern": "old"}}},
                {"properties": {"vectors": {"pattern": "exact-new"}}},
                lambda value: value["properties"]["vectors"].update(
                    {"pattern": "widened|namespace"}
                ),
            ),
            (
                "identity-constructors.yaml",
                {"constructors": {"existing": {"fields": ["a"]}}},
                {
                    "constructors": {
                        "existing": {"fields": ["a"]},
                        "closureResourceDemandIdentity": {
                            "fields": ["kind", "demandOrdinal"]
                        },
                    }
                },
                lambda value: value["constructors"][
                    "closureResourceDemandIdentity"
                ].update({"fields": ["demandOrdinal", "kind"]}),
            ),
            (
                "fixtures/manifest.yaml",
                {"files": [{"path": "old", "sha256": "a"}]},
                {"files": [{"path": "new", "sha256": "b"}]},
                lambda value: value["files"][0].update({"path": "repointed"}),
            ),
        )
        for relative, before, after, mutate in cases:
            transition = (
                classifier.structured_identity(before),
                classifier.structured_identity(after),
            )
            with patch.dict(
                classifier.APPROVED_STRUCTURED_TRANSITIONS,
                {relative: transition},
            ):
                with self.subTest(relative=relative, mutation="none"):
                    self.assertEqual(
                        0,
                        self.classify_pair(relative, before, after)[
                            "unexpectedCount"
                        ],
                    )
                tampered = deepcopy(after)
                mutate(tampered)
                with self.subTest(relative=relative, mutation="tampered"):
                    self.assertEqual(
                        1,
                        self.classify_pair(relative, before, tampered)[
                            "unexpectedCount"
                        ],
                )

    def test_release_manifest_accepts_only_the_reviewed_specification_rebind(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            package_root = Path(name)
            constructors = package_root / "identity-constructors.yaml"
            constructors.write_text("constructors: reviewed\n", encoding="utf-8")
            old_baseline = [
                {"path": path, "sha256": digest}
                for path, digest in (
                    classifier.APPROVED_IMPLEMENTATION_BASELINE_BEFORE
                )
            ]
            current_baseline = [
                {
                    "path": path,
                    "sha256": classifier.sha256(REPOSITORY_ROOT / path),
                }
                for path in IMPLEMENTATION_BASELINE_SOURCE_PATHS
            ]
            before = {
                "specificationDocument": {"sha256": "old-specification"},
                "languageDependency": {
                    **{
                        field: transition[0]
                        for field, transition in (
                            classifier.APPROVED_LANGUAGE_DEPENDENCY_TRANSITION.items()
                        )
                    },
                    "inputImplementationBaseline": old_baseline,
                },
                "contractsRegistry": {"packageIdentity": "sha256:registry"},
                "identityConstructors": {"sha256": "old-constructors"},
                "fixturePackage": {
                    "path": "fixtures/manifest.yaml",
                    "packageIdentity": "sha256:old-fixtures",
                    "vectorCount": 1,
                    "fixtureCount": 1,
                },
                "sourceArchiveBaseline": deepcopy(
                    classifier.APPROVED_REMOVED_SOURCE_ARCHIVE_BASELINE
                ),
                "releaseIdentity": "sha256:old-release",
            }
            after = deepcopy(before)
            after.pop("sourceArchiveBaseline")
            fixture_manifest = {
                "packageIdentity": "sha256:new-fixtures",
                "vectorCount": 2,
                "totalExecutableFixtureCount": 2,
            }
            registry_manifest = {"packageIdentity": "sha256:registry"}
            after["fixturePackage"] = {
                "path": "fixtures/manifest.yaml",
                "packageIdentity": "sha256:new-fixtures",
                "vectorCount": 2,
                "fixtureCount": 2,
            }
            after["identityConstructors"]["sha256"] = classifier.sha256(
                constructors
            )
            after["languageDependency"]["inputImplementationBaseline"] = (
                current_baseline
            )
            for field, transition in (
                classifier.APPROVED_LANGUAGE_DEPENDENCY_TRANSITION.items()
            ):
                after["languageDependency"][field] = transition[1]
            after["specificationDocument"]["sha256"] = (
                classifier.APPROVED_CONTRACTS_SPECIFICATION_SHA256
            )
            after["releaseIdentity"] = None
            after["releaseIdentity"] = classifier.package_identity(
                after, "releaseIdentity"
            )

            self.assertTrue(
                classifier.approved_release_manifest_transition(
                    package_root,
                    before,
                    after,
                    fixture_manifest,
                    registry_manifest,
                )
            )

            def assert_rejected(
                candidate_before: dict[str, object],
                candidate_after: dict[str, object],
            ) -> None:
                candidate_after["releaseIdentity"] = None
                candidate_after["releaseIdentity"] = classifier.package_identity(
                    candidate_after, "releaseIdentity"
                )
                self.assertFalse(
                    classifier.approved_release_manifest_transition(
                        package_root,
                        candidate_before,
                        candidate_after,
                        fixture_manifest,
                        registry_manifest,
                    )
                )

            tampered_cases = {
                "contracts specification": lambda prior, candidate: candidate[
                    "specificationDocument"
                ].update({"sha256": "0" * 64}),  # type: ignore[union-attr]
                "language specification": lambda prior, candidate: candidate[
                    "languageDependency"
                ].update({"specificationSha256": "0" * 64}),  # type: ignore[union-attr]
                "current digest": lambda prior, candidate: candidate[
                    "languageDependency"
                ]["inputImplementationBaseline"][0].update(  # type: ignore[index,union-attr]
                    {"sha256": "0" * 64}
                ),
                "current order": lambda prior, candidate: candidate[
                    "languageDependency"
                ]["inputImplementationBaseline"].reverse(),  # type: ignore[index,union-attr]
                "current extra path": lambda prior, candidate: candidate[
                    "languageDependency"
                ]["inputImplementationBaseline"].append(  # type: ignore[index,union-attr]
                    {"path": "unreviewed/Owner.java", "sha256": "0" * 64}
                ),
                "prior digest": lambda prior, candidate: prior[
                    "languageDependency"
                ]["inputImplementationBaseline"][0].update(  # type: ignore[index,union-attr]
                    {"sha256": "0" * 64}
                ),
                "prior order": lambda prior, candidate: prior[
                    "languageDependency"
                ]["inputImplementationBaseline"].reverse(),  # type: ignore[index,union-attr]
                "retained source provenance": lambda prior, candidate: candidate.update(
                    {
                        "sourceArchiveBaseline": deepcopy(
                            classifier.APPROVED_REMOVED_SOURCE_ARCHIVE_BASELINE
                        )
                    }
                ),
            }
            for mutation, mutate in tampered_cases.items():
                with self.subTest(mutation=mutation):
                    candidate_before = deepcopy(before)
                    candidate_after = deepcopy(after)
                    mutate(candidate_before, candidate_after)
                    assert_rejected(candidate_before, candidate_after)

    def test_approved_release_source_pins_match_current_sources(
        self,
    ) -> None:
        contracts_specification = (
            REPOSITORY_ROOT
            / "blue-contracts-core/src/main/resources/specifications/"
            "blue-contracts-and-processor-specification-1.0.md"
        )
        self.assertEqual(
            classifier.APPROVED_CONTRACTS_SPECIFICATION_SHA256,
            classifier.sha256(contracts_specification),
        )
        language_specification = (
            REPOSITORY_ROOT
            / "blue-language-core/src/main/resources/specifications/"
            "blue-language-specification-1.0.md"
        )
        language_digest = classifier.sha256(language_specification)
        self.assertEqual(
            classifier.APPROVED_LANGUAGE_DEPENDENCY_TRANSITION[
                "specificationSha256"
            ][1],
            language_digest,
        )
        baseline_by_path = {
            path: {
                "path": path,
                "sha256": classifier.sha256(REPOSITORY_ROOT / path),
            }
            for path in IMPLEMENTATION_BASELINE_SOURCE_PATHS
        }
        aggregate_input = {
            "domain": classifier.IMPLEMENTATION_BASELINE_AGGREGATE_DOMAIN,
            "files": [
                baseline_by_path[path]
                for path in IMPLEMENTATION_BASELINE_SOURCE_PATHS
            ],
        }
        independently_calculated_aggregate = "sha256:" + hashlib.sha256(
            classifier.jcs_dumps(aggregate_input)
        ).hexdigest()
        self.assertEqual(
            classifier.APPROVED_IMPLEMENTATION_BASELINE_AGGREGATE_IDENTITY,
            independently_calculated_aggregate,
        )
        self.assertEqual(
            independently_calculated_aggregate,
            classifier.implementation_baseline_aggregate_identity(
                IMPLEMENTATION_BASELINE_SOURCE_PATHS,
                {path: entry["sha256"] for path, entry in baseline_by_path.items()},
            ),
        )
        identity_expectations = (
            (
                "cyclicSetFinalizerBaselineIdentity",
                "blue-language-cyclic-set-finalizer-baseline/1.0",
                source_paths_for_role(CYCLIC_FINALIZER),
            ),
            (
                "cyclicSetProofVerifierBaselineIdentity",
                "blue-language-cyclic-set-proof-verifier-baseline/1.0",
                source_paths_for_role(CYCLIC_PROOF_VERIFIER),
            ),
        )
        for field, domain, paths in identity_expectations:
            identity_input = {
                "domain": domain,
                "value": {
                    "languageSpecificationSha256": language_digest,
                    "files": [baseline_by_path[path] for path in paths],
                },
            }
            actual_identity = "sha256:" + hashlib.sha256(
                classifier.jcs_dumps(identity_input)
            ).hexdigest()
            self.assertEqual(
                classifier.APPROVED_LANGUAGE_DEPENDENCY_TRANSITION[field][1],
                actual_identity,
            )
        self.assertEqual(
            IMPLEMENTATION_BASELINE_SOURCE_PATHS,
            tuple(baseline_by_path),
        )

    def test_structurally_equal_yaml_is_formatting_only(self) -> None:
        with tempfile.TemporaryDirectory(prefix="identity-delta-test-") as name:
            root = Path(name)
            before = root / "before"
            after = root / "after"
            relative = "fixtures/example.yaml"
            (before / "fixtures").mkdir(parents=True)
            (after / "fixtures").mkdir(parents=True)
            (before / relative).write_text("value: 1\n", encoding="utf-8")
            (after / relative).write_text("{value: 1}\n", encoding="utf-8")

            report = classifier.classify(before, after)

            self.assertEqual(0, report["unexpectedCount"])
            self.assertEqual(1, report["summary"][classifier.FORMATTING])

    def test_report_context_replaces_disposable_paths_with_provenance(self) -> None:
        report = {
            "beforePackage": "/private/tmp/before",
            "afterPackage": "/private/tmp/after",
            "beforeFileCount": 1,
            "afterFileCount": 1,
            "changedFileCount": 0,
            "unexpectedCount": 0,
            "summary": {category: 0 for category in classifier.CATEGORIES},
            "files": [],
        }

        contextual = classifier.apply_report_context(
            report,
            before_reference="git:baseline/package",
            after_reference="worktree:canonical/package",
            baseline_commit="a" * 40,
            baseline_tree="b" * 40,
            baseline_package_identity="sha256:" + "c" * 64,
        )

        self.assertEqual("git:baseline/package", contextual["beforePackage"])
        self.assertEqual("worktree:canonical/package", contextual["afterPackage"])
        self.assertEqual("a" * 40, contextual["baseline"]["commit"])
        self.assertNotIn("/private/tmp", classifier.markdown_report(contextual))

    def test_report_context_rejects_partial_baseline_provenance(self) -> None:
        with self.assertRaises(classifier.ClassificationFailure):
            classifier.apply_report_context(
                {},
                before_reference=None,
                after_reference=None,
                baseline_commit="a" * 40,
                baseline_tree=None,
                baseline_package_identity=None,
            )


if __name__ == "__main__":
    unittest.main()
