#!/usr/bin/env python3
"""Focused digest-format tests for the Contracts package validator."""
from __future__ import annotations

import importlib.util
from copy import deepcopy
from pathlib import Path
import hashlib
import sys
import tempfile
import types
import unittest

if importlib.util.find_spec("jsonschema") is None:
    # This focused unit does not execute schema validation.  Keep it runnable
    # in the lightweight tool-test environment where jsonschema is optional.
    sys.modules["jsonschema"] = types.ModuleType("jsonschema")

import validate_package


class ValidatePackageBaselineTest(unittest.TestCase):

    def test_checksum_manifest_requires_exact_canonical_lines(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="blue-checksum-manifest-"
        ) as temporary:
            root = Path(temporary)
            for name in ("a.txt", "b.txt"):
                (root / name).write_text(name + "\n", encoding="utf-8")
            canonical_lines = [
                f"{hashlib.sha256((root / name).read_bytes()).hexdigest()}  {name}"
                for name in ("a.txt", "b.txt")
            ]
            manifest = root / "MANIFEST.sha256"
            previous_root = validate_package.ROOT
            validate_package.ROOT = root
            try:
                manifest.write_text(
                    "\n".join(canonical_lines) + "\n", encoding="utf-8"
                )
                validate_package.validate_checksum_manifest()

                invalid_values = {
                    "duplicate": canonical_lines + [canonical_lines[-1]],
                    "reordered": list(reversed(canonical_lines)),
                    "malformed separator": [canonical_lines[0].replace("  ", " ")],
                    "uppercase digest": [canonical_lines[0].upper()],
                    "unexpected traversal": ["0" * 64 + "  ../outside"],
                    "blank line": canonical_lines + [""],
                }
                for name, lines in invalid_values.items():
                    with self.subTest(name=name):
                        manifest.write_text(
                            "\n".join(lines) + "\n", encoding="utf-8"
                        )
                        with self.assertRaises(
                            validate_package.ValidationFailure
                        ):
                            validate_package.validate_checksum_manifest()

                manifest.write_bytes(
                    ("\r\n".join(canonical_lines) + "\r\n").encode("utf-8")
                )
                with self.assertRaises(validate_package.ValidationFailure):
                    validate_package.validate_checksum_manifest()
            finally:
                validate_package.ROOT = previous_root

    @staticmethod
    def baseline() -> list[dict[str, str]]:
        return [
            {"path": path, "sha256": "0" * 64}
            for path in validate_package.IMPLEMENTATION_BASELINE_SOURCE_PATHS
        ]

    def test_only_lowercase_unprefixed_sha256_is_accepted(self) -> None:
        self.assertTrue(validate_package.is_lowercase_sha256("0" * 64))
        for invalid in (
            "A" * 64,
            "0" * 63,
            "0" * 65,
            "sha256:" + "0" * 64,
            "g" * 64,
            None,
            0,
        ):
            with self.subTest(invalid=invalid):
                self.assertFalse(validate_package.is_lowercase_sha256(invalid))

    def test_exact_checked_in_inventory_is_accepted(self) -> None:
        baseline = self.baseline()
        by_path = validate_package.validate_input_implementation_baseline(
            baseline
        )
        self.assertEqual(
            validate_package.IMPLEMENTATION_BASELINE_SOURCE_PATHS,
            tuple(by_path),
        )

    def test_inventory_or_digest_drift_is_rejected(self) -> None:
        cases: dict[str, list[dict[str, str]]] = {}
        missing = self.baseline()
        missing.pop()
        cases["missing"] = missing
        added = self.baseline()
        added.append({"path": "unreviewed/Added.java", "sha256": "0" * 64})
        cases["added"] = added
        reordered = self.baseline()
        reordered[0], reordered[1] = reordered[1], reordered[0]
        cases["reordered"] = reordered
        uppercase = self.baseline()
        uppercase[0]["sha256"] = "A" * 64
        cases["uppercase digest"] = uppercase
        extra_key = deepcopy(self.baseline())
        extra_key[0]["bytes"] = "1"
        cases["extra entry key"] = extra_key

        for name, baseline in cases.items():
            with self.subTest(name=name), self.assertRaises(
                validate_package.ValidationFailure
            ):
                validate_package.validate_input_implementation_baseline(
                    baseline
                )


if __name__ == "__main__":
    unittest.main()
