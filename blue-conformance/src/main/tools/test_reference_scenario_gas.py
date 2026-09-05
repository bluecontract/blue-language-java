#!/usr/bin/env python3
"""Independent reference gas regression against retained runtime receipts."""
from copy import deepcopy
import math
from pathlib import Path
import unittest
import reference_scenarios as reference
from blue_identity import direct_identity_facts


class ReferenceScenarioGasTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.paths = reference.FIX, reference.ORC
        if not reference.FIX.is_dir():
            package = Path(__file__).resolve().parents[1] / "resources/blue-contracts-closure-1.0"
            reference.FIX = package / "fixtures/closure"
            reference.ORC = package / "oracles"

    @classmethod
    def tearDownClass(cls):
        reference.FIX, reference.ORC = cls.paths

    def test_exact_finite_cycle_trace_and_oracle_binding(self):
        fixture = reference.load_yaml(reference.FIX / "c-clo-02-dynamic-finite-cycle.yaml")
        trace, total, identity = reference.derive_finite_trace(fixture)
        self.assertEqual(fixture["expected"]["gasTrace"], trace)
        self.assertEqual(fixture["expected"]["totalGas"], total)
        self.assertEqual(fixture["expected"]["gasTraceIdentity"], identity)
        acceptance = [row for row in trace if row["counter"] == "channelCandidateTested"
                      and row.get("contractKey") in ("fromA", "fromB")]
        self.assertEqual([("b", "fromA"), ("a", "fromB")],
                         [(row["documentId"], row["contractKey"]) for row in acceptance])
        forged = deepcopy(fixture)
        forged["expected"]["tentativeFinalizations"][0]["canonicalBytes"] += 1
        with self.assertRaises(AssertionError): reference.derive_finite_trace(forged)
        forged = deepcopy(fixture)
        forged["expected"]["tentativeFinalizations"][0]["masterBlueId"] = "forged"
        with self.assertRaises(AssertionError): reference.derive_finite_trace(forged)

    def test_split_merge_and_containing_spine_exact_traces(self):
        self.assertEqual(6, len(reference.check_identity_transition_gas()))

    def test_empty_object_contributes_to_direct_hash_bytes(self):
        marker = {"type": {"blueId": "Ag2NpsQnNpn8nNRopURxcWVHRnYu5REDZeS8YJcfvQUS"}, "entries": {}}
        rows = []
        reference._reference_charge_direct_node(marker,
            charge=lambda ns, counter, quantity, reason, context: rows.append((counter, quantity)),
            context={}, recurse=False)
        expected = math.ceil((direct_identity_facts(marker).canonical_input_utf8_bytes + 9) / 64)
        absent = {"type": marker["type"]}
        self.assertNotEqual(expected, math.ceil((direct_identity_facts(absent).canonical_input_utf8_bytes + 9) / 64))
        self.assertIn(("directIdentityHashBlock", expected), rows)
        self.assertIn(("objectMemberRebuilt", 2), rows)


if __name__ == "__main__": unittest.main()
