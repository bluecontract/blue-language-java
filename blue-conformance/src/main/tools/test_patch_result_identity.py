#!/usr/bin/env python3
"""Regression for post-patch ancestor identity charging."""

from __future__ import annotations

from copy import deepcopy
import unittest

from gas_reference import GasReferenceTrace
import reference_scenarios
import refine_closure_fixtures


class PatchResultIdentityTest(unittest.TestCase):

    def test_add_replace_remove_charge_sequential_result_ancestors(self) -> None:
        initial = {
            "container": {
                "original": {"old": 0},
                "removable": {"drop": True},
            },
            "stable": "same",
        }
        patches = [
            {
                "op": "add",
                "path": "/container/added",
                "val": {"new": 1},
            },
            {
                "op": "replace",
                "path": "/container/original",
                "val": {"replacement": 2, "extra": 3},
            },
            {
                "op": "remove",
                "path": "/container/removable",
            },
        ]
        original = deepcopy(initial)
        expected_document, expected_trace, expected_total = (
            reference_scenarios.derive_direct_patch_result_identity_trace(
                initial, patches
            )
        )

        actual_document = deepcopy(initial)
        actual = GasReferenceTrace()
        for patch in patches:
            actual_document = (
                refine_closure_fixtures._charge_direct_patch_result(
                    actual_document,
                    patch,
                    actual,
                    context={"logicalPath": patch["path"]},
                )
            )

        self.assertEqual(original, initial, "the source projection was mutated")
        self.assertEqual(expected_document, actual_document)
        self.assertEqual(expected_trace, actual.entries)
        self.assertEqual(expected_total, actual.total)
        self.assertEqual(
            {
                "container": {
                    "original": {"replacement": 2, "extra": 3},
                    "added": {"new": 1},
                },
                "stable": "same",
            },
            actual_document,
        )

        expected_result_ancestor_members = {
            "/container/added": [3, 2],
            "/container/original": [3, 2],
            "/container/removable": [2, 2],
        }
        for path, quantities in expected_result_ancestor_members.items():
            member_rows = [
                entry["quantity"]
                for entry in actual.entries
                if entry.get("logicalPath") == path
                and entry["counter"] == "objectMemberRebuilt"
            ]
            self.assertEqual(quantities, member_rows[-2:], path)


if __name__ == "__main__":
    unittest.main()
