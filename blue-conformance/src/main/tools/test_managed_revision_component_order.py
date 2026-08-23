#!/usr/bin/env python3
"""Regression for reverse-topological managed-revision admission order."""

from __future__ import annotations

from pathlib import Path
import unittest
from unittest.mock import patch

import yaml

import reference_scenarios
import refine_closure_fixtures


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = (
    ROOT
    / "conformance/contracts/fixtures/closure"
    / "c-clo-23-01-a5-to-a6.yaml"
)


class AdmissionPartitionCaptured(Exception):
    """Stop a trace derivation once its admission partition is observed."""


class RecordingTrace:

    def __init__(self) -> None:
        self.partition_documents: list[str] = []

    def charge(
        self,
        namespace: str,
        counter: str,
        quantity: int,
        *,
        reason: str,
        context: dict[str, object] | None = None,
    ) -> bool:
        if (
            counter == "componentMemberPartitioned"
            and reason == "admission.component-member"
        ):
            assert quantity == 1
            assert context is not None
            self.partition_documents.append(str(context["documentId"]))
            if len(self.partition_documents) == 2:
                raise AdmissionPartitionCaptured
        return True


def derive_partition_documents(
    module: object,
    derive: object,
    *args: object,
) -> list[str]:
    trace = RecordingTrace()
    with patch.object(module, "GasReferenceTrace", return_value=trace):
        with unittest.TestCase().assertRaises(AdmissionPartitionCaptured):
            derive(*args)  # type: ignore[operator]
    return trace.partition_documents


class ManagedRevisionComponentOrderTest(unittest.TestCase):

    def test_c23_01_preserves_reverse_topological_b_then_a_order(self) -> None:
        fixture = yaml.safe_load(FIXTURE.read_text(encoding="utf-8"))
        fixture_order = [
            document_id
            for component in fixture["input"]["components"]
            for document_id in component["orderedMemberDocumentIds"]
        ]
        self.assertEqual(["history-b", "history-a"], fixture_order)

        refiner_order = derive_partition_documents(
            refine_closure_fixtures,
            refine_closure_fixtures.generic_complete_trace,
            FIXTURE,
            fixture,
        )
        reference_order = derive_partition_documents(
            reference_scenarios,
            reference_scenarios.derive_managed_revision_trace,
            fixture,
        )

        self.assertEqual(["history-b", "history-a"], refiner_order)
        self.assertEqual(refiner_order, reference_order)


if __name__ == "__main__":
    unittest.main()
