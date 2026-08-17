#!/usr/bin/env python3
"""Focused executable vectors for the vendored JCS and cyclic tie-break."""

from __future__ import annotations

from pathlib import Path
import unittest

import yaml

from blue_identity import (
    INTEGER_TYPE_BLUE_ID,
    ZERO_BLUEID,
    _order_preliminary_members,
    canonical_json_bytes,
    normalized_preliminary_input_bytes,
)
from jcs import (
    CanonicalizationError,
    FloatDomainError,
    IntegerDomainError,
    dumps,
)


ERROR_TYPES = {
    error_type.__name__: error_type
    for error_type in (
        CanonicalizationError,
        FloatDomainError,
        IntegerDomainError,
    )
}


class JcsVectorTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        vector_path = Path(__file__).with_name("jcs-vectors.yaml")
        cls.vectors = yaml.safe_load(vector_path.read_text(encoding="utf-8"))[
            "vectors"
        ]

    def test_vectors(self) -> None:
        for vector in self.vectors:
            with self.subTest(vector=vector["name"]):
                value = self._materialize_input(vector)
                expected_error = vector.get("expectedError")
                if expected_error is not None:
                    with self.assertRaises(ERROR_TYPES[expected_error]):
                        dumps(value)
                else:
                    self.assertEqual(
                        vector["expectedCanonicalJson"],
                        dumps(value).decode("utf-8"),
                    )

    @staticmethod
    def _materialize_input(vector: dict[str, object]) -> object:
        if "inputCodePoints" in vector:
            return "".join(chr(code) for code in vector["inputCodePoints"])
        if "inputUtf16CodeUnits" in vector:
            return "".join(
                chr(code) for code in vector["inputUtf16CodeUnits"]
            )
        return vector["input"]


class CyclicPreliminaryOrderTest(unittest.TestCase):

    def test_tie_bytes_are_normalized_input_not_raw_authoring_shape(self) -> None:
        authored_zeroed = {
            "name": "member",
            "count": 1,
            "next": {"blueId": ZERO_BLUEID},
        }

        raw_bytes = canonical_json_bytes(authored_zeroed)
        tie_bytes = normalized_preliminary_input_bytes(authored_zeroed)

        self.assertNotEqual(raw_bytes, tie_bytes)
        self.assertEqual(
            (
                '{"count":{"type":{"blueId":"'
                + INTEGER_TYPE_BLUE_ID
                + '"},"value":1},"name":"member","next":{"blueId":"'
                + ZERO_BLUEID
                + '"}}'
            ).encode("utf-8"),
            tie_bytes,
        )

    def test_digest_collision_uses_unsigned_canonical_bytes(self) -> None:
        members = [
            (0, "constant", {"name": "later"}, b"\xff"),
            (1, "constant", {"name": "earlier"}, b"\x00"),
        ]

        ordered = _order_preliminary_members(members)
        permuted = _order_preliminary_members(list(reversed(members)))

        self.assertEqual([1, 0], [member[0] for member in ordered])
        self.assertEqual([1, 0], [member[0] for member in permuted])

    def test_exact_digest_and_canonical_input_tie_is_ambiguous(self) -> None:
        members = [
            (0, "constant", {"name": "same"}, b"same"),
            (1, "constant", {"name": "same"}, b"same"),
        ]

        with self.assertRaisesRegex(
            ValueError, "Duplicate preliminary cyclic BlueId"
        ):
            _order_preliminary_members(members)


if __name__ == "__main__":
    unittest.main()
