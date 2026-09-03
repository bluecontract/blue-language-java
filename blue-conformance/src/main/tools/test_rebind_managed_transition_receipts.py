#!/usr/bin/env python3
"""Regression tests for runtime-owned closure evidence rebinding."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

import yaml


TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location(
    "rebind_managed_transition_receipts",
    TOOLS / "rebind_managed_transition_receipts.py",
)
assert SPEC is not None and SPEC.loader is not None
rebind = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rebind)


class RuntimeEvidenceRebindTest(unittest.TestCase):

    def test_identity_projection_ignores_only_exact_identity_fields(self) -> None:
        authored = {
            "kind": "Add",
            "subscriptionIdentity": "sha256:old",
            "targetBlueId": "old-blue-id",
            "nested": [{"active": True, "memberIdentities": ["old"]}],
        }
        executed = {
            "kind": "Add",
            "subscriptionIdentity": "sha256:new",
            "targetBlueId": "new-blue-id",
            "nested": [{"active": True, "memberIdentities": ["new"]}],
        }
        self.assertEqual(
            rebind.identity_free(authored),
            rebind.identity_free(executed),
        )
        executed["kind"] = "Remove"
        self.assertNotEqual(
            rebind.identity_free(authored),
            rebind.identity_free(executed),
        )

    def gas_package(self, root: Path) -> Path:
        package = root / "package"
        package.mkdir()
        (package / "gas-manifest.yaml").write_text(
            yaml.safe_dump({
                "traceEntryFields": [
                    "sequence", "namespace", "counter", "quantity",
                    "weight", "subtotal", "documentId", "scopePath",
                    "activationGeneration", "componentGeneration",
                    "contractKey", "logicalPath", "workOccurrenceId", "reason",
                ],
                "namespaces": {
                    "processor": {
                        "counters": {
                            "processInvocation": 50,
                            "channelCandidateTested": 5,
                        }
                    },
                    "semantic": {"counters": {"nodeIdentityEstablished": 1}},
                },
            }, sort_keys=False),
            encoding="utf-8",
        )
        return package

    @staticmethod
    def runtime_gas() -> dict[str, object]:
        trace = [
            {
                "sequence": 0,
                "namespace": "processor",
                "counter": "processInvocation",
                "quantity": 1,
                "weight": 50,
                "subtotal": 50,
                "reason": "admission.process",
            },
            {
                "sequence": 1,
                "namespace": "processor",
                "counter": "channelCandidateTested",
                "quantity": 1,
                "weight": 5,
                "subtotal": 5,
                "documentId": "b",
                "scopePath": "/",
                "activationGeneration": 0,
                "componentGeneration": 2,
                "contractKey": "fromA",
                "reason": "acceptance",
            },
        ]
        return {
            "totalGas": 55,
            "gasTrace": trace,
            "gasTraceIdentity": rebind.gas_trace_identity(trace),
        }

    def test_inserted_channel_candidate_charge_is_valid_runtime_evidence(self) -> None:
        with tempfile.TemporaryDirectory(prefix="closure-runtime-gas-") as temporary:
            package = self.gas_package(Path(temporary))
            runtime = self.runtime_gas()
            self.assertEqual(
                runtime["gasTrace"],
                rebind.validate_executed_gas(package, "C-CLO-TEST", runtime),
            )

    def test_malformed_runtime_gas_is_rejected_even_with_matching_identity(self) -> None:
        with tempfile.TemporaryDirectory(prefix="closure-runtime-gas-") as temporary:
            package = self.gas_package(Path(temporary))
            runtime = self.runtime_gas()
            trace = runtime["gasTrace"]
            trace[1]["weight"] = 4
            trace[1]["subtotal"] = 4
            runtime["totalGas"] = 54
            runtime["gasTraceIdentity"] = rebind.gas_trace_identity(trace)
            with self.assertRaisesRegex(rebind.RebindFailure, "weight mismatch"):
                rebind.validate_executed_gas(package, "C-CLO-TEST", runtime)

    def test_runtime_gas_total_and_identity_are_both_checked(self) -> None:
        with tempfile.TemporaryDirectory(prefix="closure-runtime-gas-") as temporary:
            package = self.gas_package(Path(temporary))
            runtime = self.runtime_gas()
            runtime["totalGas"] = 56
            with self.assertRaisesRegex(rebind.RebindFailure, "total mismatch"):
                rebind.validate_executed_gas(package, "C-CLO-TEST", runtime)

    def test_rejected_charge_rebinds_only_verified_identity(self) -> None:
        basis = {
            "namespace": "processor",
            "counter": "handlerCall",
            "quantity": 1,
            "weight": 50,
            "subtotal": 50,
            "applicableCap": {"kind": "SHARED"},
            "remainingBeforeCharge": 29,
            "owner": {
                "kind": "WORK",
                "workOccurrenceIdentity": "sha256:work",
            },
        }
        authored = {**basis, "rejectedChargeIdentity": "sha256:stale"}
        executed = {
            **basis,
            "rejectedChargeIdentity": rebind.closure_generator.sha_id(
                "blue-contracts-rejected-charge/1.0", basis
            ),
        }

        self.assertEqual(
            executed["rejectedChargeIdentity"],
            rebind.validate_rejected_charge("C-CLO-TEST", authored, executed),
        )
        self.assertEqual("sha256:stale", authored["rejectedChargeIdentity"])

    def test_rejected_charge_semantics_cannot_be_rebound(self) -> None:
        authored = {
            "namespace": "processor",
            "counter": "handlerCall",
            "quantity": 1,
            "weight": 50,
            "subtotal": 50,
            "applicableCap": {"kind": "SHARED"},
            "remainingBeforeCharge": 49,
            "owner": {"kind": "INVOCATION"},
            "rejectedChargeIdentity": "sha256:old",
        }
        executed = {**authored, "remainingBeforeCharge": 29}
        executed["rejectedChargeIdentity"] = rebind.closure_generator.sha_id(
            "blue-contracts-rejected-charge/1.0",
            {
                field: executed[field]
                for field in rebind.REJECTED_CHARGE_FIELDS
            },
        )

        with self.assertRaisesRegex(
            rebind.RebindFailure, "rejectedCharge semantics changed"
        ):
            rebind.validate_rejected_charge(
                "C-CLO-TEST", authored, executed
            )

    def test_external_trace_is_loaded_from_fixture_directory(self) -> None:
        with tempfile.TemporaryDirectory(prefix="closure-gas-trace-") as temporary:
            root = Path(temporary)
            fixture = root / "fixture.yaml"
            fixture.write_text("id: C-CLO-TEST\n", encoding="utf-8")
            trace = root / "trace.yaml"
            trace.write_text(
                "fixture: C-CLO-TEST\n"
                "entries:\n"
                "  - counter: closure.finalization\n",
                encoding="utf-8",
            )
            entries, path, document = rebind.existing_gas_trace(
                fixture,
                "C-CLO-TEST",
                {"gasTraceFile": "trace.yaml"},
            )
            self.assertEqual([{"counter": "closure.finalization"}], entries)
            self.assertEqual(trace.resolve(), path)
            self.assertEqual("C-CLO-TEST", document["fixture"])

    def test_external_trace_cannot_escape_fixture_directory(self) -> None:
        with tempfile.TemporaryDirectory(prefix="closure-gas-trace-") as temporary:
            fixture = Path(temporary) / "fixture.yaml"
            with self.assertRaisesRegex(rebind.RebindFailure, "unsafe gasTraceFile"):
                rebind.existing_gas_trace(
                    fixture,
                    "C-CLO-TEST",
                    {"gasTraceFile": "../trace.yaml"},
                )


if __name__ == "__main__":
    unittest.main()
