#!/usr/bin/env python3
"""Focused vectors for the Contracts gas-trace identity projection."""

from __future__ import annotations

from copy import deepcopy
import unittest

from gas_reference import (
    gas_trace_identity,
    gas_trace_identity_projection,
)


class GasTraceIdentityTest(unittest.TestCase):

    def setUp(self) -> None:
        self.entry = {
            "sequence": 0,
            "namespace": "processor",
            "counter": "closureInvocation",
            "quantity": 1,
            "weight": 100,
            "subtotal": 100,
            "documentId": "root",
            "scopePath": "/",
            "activationGeneration": 0,
            "componentGeneration": 3,
            "contractKey": "alpha",
            "logicalPath": "/contracts/alpha",
            "workOccurrenceId": "sha256:" + "a" * 64,
            "reason": "first diagnostic wording",
        }

    def test_reason_is_observable_but_not_identity_bearing(self) -> None:
        rewritten = deepcopy(self.entry)
        rewritten["reason"] = "rewritten diagnostic wording"

        self.assertNotEqual(self.entry["reason"], rewritten["reason"])
        self.assertNotIn(
            "reason", gas_trace_identity_projection([self.entry])[0]
        )
        self.assertEqual(
            gas_trace_identity([self.entry]),
            gas_trace_identity([rewritten]),
        )

    def test_semantic_attribution_remains_identity_bearing(self) -> None:
        reattributed = deepcopy(self.entry)
        reattributed["logicalPath"] = "/contracts/beta"

        self.assertNotEqual(
            gas_trace_identity([self.entry]),
            gas_trace_identity([reattributed]),
        )


if __name__ == "__main__":
    unittest.main()
