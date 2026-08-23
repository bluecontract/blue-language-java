#!/usr/bin/env python3
"""Focused fail-closed tests for the fixture identity-delta classifier."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import yaml

import classify_fixture_identity_delta as classifier


def write_yaml(root: Path, relative: str, value: object) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        yaml.safe_dump(value, sort_keys=False),
        encoding="utf-8",
    )


class ClassifyFixtureIdentityDeltaTest(unittest.TestCase):

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
