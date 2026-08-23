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


if __name__ == "__main__":
    unittest.main()
