#!/usr/bin/env python3
"""Focused parity tests for Python and Java BlueId input cleaning."""

from __future__ import annotations

import unittest

from blue_identity import (
    TEXT_TYPE_BLUE_ID,
    canonical_sha256_blue_id,
    direct_blue_id,
    normalized_preliminary_input_bytes,
)


class BlueIdentityNormalizationTest(unittest.TestCase):

    def test_root_empty_object_remains_valid(self) -> None:
        self.assertEqual(canonical_sha256_blue_id({}), direct_blue_id({}))
        self.assertEqual(b"{}", normalized_preliminary_input_bytes({}))

    def test_recursively_empty_object_fields_are_omitted(self) -> None:
        retained = {"kept": {"blueId": TEXT_TYPE_BLUE_ID}}
        authored = {
            "empty": {"nested": {"missing": None}},
            "kept": {"blueId": TEXT_TYPE_BLUE_ID},
            "schema": {"fields": {"ghost": {"value": None}}},
        }

        self.assertEqual(direct_blue_id(retained), direct_blue_id(authored))
        self.assertEqual(
            normalized_preliminary_input_bytes(retained),
            normalized_preliminary_input_bytes(authored),
        )

    def test_empty_object_list_elements_are_rejected_after_cleaning(self) -> None:
        for value in ([{}], [{"nested": {"missing": None}}]):
            with self.subTest(value=value):
                with self.assertRaisesRegex(
                    ValueError, "empty object list placeholders"
                ):
                    direct_blue_id(value)
                with self.assertRaisesRegex(
                    ValueError, "empty object list placeholders"
                ):
                    normalized_preliminary_input_bytes(value)

    def test_exact_empty_list_placeholder_is_preserved(self) -> None:
        value = [{"$empty": True}]

        self.assertIsInstance(direct_blue_id(value), str)
        self.assertEqual(
            b'[{"$empty":true}]',
            normalized_preliminary_input_bytes(value),
        )

    def test_malformed_empty_list_placeholder_is_rejected(self) -> None:
        value = [{"$empty": True, "extra": True}]

        with self.assertRaisesRegex(ValueError, "exact shape"):
            direct_blue_id(value)
        with self.assertRaisesRegex(ValueError, "exact shape"):
            normalized_preliminary_input_bytes(value)


if __name__ == "__main__":
    unittest.main()
