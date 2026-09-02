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

    EMPTY_OBJECT_BLUE_ID = "5ajuwjHoLj33yG5t5UFsJtUb3vnRaJQEMPqSLz6VyoHK"

    def test_root_empty_object_remains_valid(self) -> None:
        self.assertEqual(self.EMPTY_OBJECT_BLUE_ID, direct_blue_id({}))
        self.assertEqual(canonical_sha256_blue_id({}), direct_blue_id({}))
        self.assertEqual(b"{}", normalized_preliminary_input_bytes({}))

    def test_recursively_empty_object_fields_are_preserved(self) -> None:
        retained = {
            "empty": {"nested": {}},
            "kept": {"blueId": TEXT_TYPE_BLUE_ID},
            "schema": {"fields": {"ghost": {}}},
        }
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

    def test_null_object_field_normalizes_to_present_empty_object(self) -> None:
        self.assertEqual(direct_blue_id({}), direct_blue_id({"x": None}))
        self.assertEqual(b"{}", normalized_preliminary_input_bytes({"x": None}))

    def test_empty_object_child_contributes_to_parent_identity(self) -> None:
        self.assertNotEqual(direct_blue_id({}), direct_blue_id({"x": {}}))
        self.assertEqual(
            direct_blue_id({"x": {}}),
            direct_blue_id({"x": {"blueId": self.EMPTY_OBJECT_BLUE_ID}}),
        )

    def test_empty_object_list_elements_are_preserved_after_cleaning(self) -> None:
        self.assertIsInstance(direct_blue_id([{}]), str)
        self.assertEqual(b"[{}]", normalized_preliminary_input_bytes([{}]))
        recursively_cleaned = [{"nested": {"missing": None}}]
        self.assertEqual(
            direct_blue_id([{"nested": {}}]),
            direct_blue_id(recursively_cleaned),
        )
        self.assertEqual(
            b'[{"nested":{}}]',
            normalized_preliminary_input_bytes(recursively_cleaned),
        )

    def test_empty_object_list_and_placeholder_are_distinct(self) -> None:
        value = [{"$empty": True}, {}, []]

        self.assertEqual(
            b'[{"$empty":true},{},[]]',
            normalized_preliminary_input_bytes(value),
        )
        self.assertEqual(3, len({
            direct_blue_id([{"$empty": True}]),
            direct_blue_id([{}]),
            direct_blue_id([[]]),
        }))

    def test_raw_null_list_element_remains_invalid(self) -> None:
        with self.assertRaisesRegex(ValueError, "null list placeholders"):
            direct_blue_id([None])
        with self.assertRaisesRegex(ValueError, "null list placeholders"):
            normalized_preliminary_input_bytes([None])

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
