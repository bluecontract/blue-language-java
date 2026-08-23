#!/usr/bin/env python3
"""Regression coverage for invocation-wide checkpoint receipt ordering."""

from __future__ import annotations

from pathlib import Path
import os
import shutil
import tempfile
from typing import Any
import unittest

import yaml

import generate_closure_fixtures as fixture_model
import release_regenerate_package as regenerate_package


ROOT = Path(__file__).resolve().parents[1]


def managed_scope_identity(
    document_id: str,
    scope_path: str = "/",
    activation_generation: int = 0,
) -> str:
    """Derive the exact managed-scope address used by checkpoint receipts."""
    return fixture_model.sha_id(
        "blue-contracts-managed-scope-key/1.0",
        {
            "documentId": document_id,
            "scopePath": scope_path,
            "activationGeneration": activation_generation,
        },
    )


def checkpoint_entries(document: dict[str, Any]) -> dict[str, Any]:
    """Project the closed direct-checkpoint entry map from one Root."""
    marker = document.get("contracts", {}).get("checkpoint")
    return {} if marker is None else marker.get("entries", {})


def canonical_checkpoint_write_addresses(
    fixture: dict[str, Any],
) -> list[tuple[str, str]]:
    """Independently derive source-first, cleanup-second receipt order."""
    fixture_input = fixture["input"]
    expected = fixture["expected"]
    resulting_documents = {
        record["documentId"]: record["document"]
        for record in expected["resultingDocuments"]
    }
    actual_changes: set[tuple[str, str]] = set()
    for document_id, input_record in fixture_input["documents"].items():
        before_entries = checkpoint_entries(input_record["document"])
        after_entries = checkpoint_entries(resulting_documents[document_id])
        scope_identity = managed_scope_identity(document_id)
        for raw_key in set(before_entries) | set(after_entries):
            if before_entries.get(raw_key) != after_entries.get(raw_key):
                actual_changes.add((scope_identity, raw_key))

    accepted_source_keys = {
        (work["targetManagedScopeIdentity"], work["channelKey"])
        for work in expected["workTrace"]
        if work["kind"] == "EXTERNAL_DELIVERY"
    }
    source_keys: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for delivery in sorted(
        fixture_input.get("directDeliveries", []),
        key=lambda item: (
            item["rawOccurrenceOrder"],
            item["targetDocumentId"],
            item["scopePath"],
            item["activationGeneration"],
            item["channelKey"],
            item["logicalDeliveryKey"],
        ),
    ):
        key = (
            managed_scope_identity(
                delivery["targetDocumentId"],
                delivery["scopePath"],
                delivery["activationGeneration"],
            ),
            delivery["channelKey"],
        )
        if (
            key in actual_changes
            and key in accepted_source_keys
            and key not in seen
        ):
            source_keys.append(key)
            seen.add(key)
    return source_keys + sorted(actual_changes - seen)


class CheckpointWriteOrderTest(unittest.TestCase):

    def test_refiner_orders_all_receipts_by_source_then_cleanup(self) -> None:
        """Exercise the archived generator/refiner against its 67 core fixtures.

        Full-lifecycle ADMIT_CLOSURE fixtures are appended by the normative
        Java exporter after this preserved PROCESS_CLOSURE stage.
        """
        with tempfile.TemporaryDirectory(
            prefix="blue-checkpoint-order-test-"
        ) as temporary:
            candidate = Path(temporary) / ROOT.name
            shutil.copytree(ROOT, candidate)
            environment = dict(os.environ)
            environment["PYTHONDONTWRITEBYTECODE"] = "1"
            regenerate_package.regenerate_generated_surfaces(
                candidate,
                environment=environment,
            )

            fixture_paths = sorted(
                (candidate / "conformance/contracts/fixtures/closure")
                .glob("*.yaml")
            )
            self.assertEqual(67, len(fixture_paths))
            c13: dict[str, Any] | None = None
            for path in fixture_paths:
                fixture = yaml.safe_load(path.read_text(encoding="utf-8"))
                if path.name == "c-clo-13-frozen-edge-addition.yaml":
                    c13 = fixture
                expected = fixture.get("expected", {})
                if "checkpointWrites" not in expected:
                    continue
                actual_addresses = [
                    (
                        write["targetManagedScopeIdentity"],
                        write["rawChannelKey"],
                    )
                    for write in expected["checkpointWrites"]
                ]
                self.assertEqual(
                    list(range(len(actual_addresses))),
                    [
                        write["checkpointWriteOrdinal"]
                        for write in expected["checkpointWrites"]
                    ],
                    path.name,
                )
                self.assertEqual(
                    canonical_checkpoint_write_addresses(fixture),
                    actual_addresses,
                    path.name,
                )

            self.assertIsNotNone(c13)
            assert c13 is not None
            self.assertEqual(
                ["z-owner", "a-source"],
                [
                    item["targetDocumentId"]
                    for item in sorted(
                        c13["input"]["directDeliveries"],
                        key=lambda item: item["rawOccurrenceOrder"],
                    )
                ],
            )
            self.assertEqual(
                ["a-source", "z-owner"],
                [
                    item["targetDocumentId"]
                    for item in c13["expected"]["workTrace"]
                    if item["kind"] == "EXTERNAL_DELIVERY"
                ],
            )
            c13_scope_documents = {
                managed_scope_identity(document_id): document_id
                for document_id in c13["input"]["documents"]
            }
            self.assertEqual(
                ["z-owner", "a-source"],
                [
                    c13_scope_documents[
                        write["targetManagedScopeIdentity"]
                    ]
                    for write in c13["expected"]["checkpointWrites"]
                ],
            )


if __name__ == "__main__":
    unittest.main()
