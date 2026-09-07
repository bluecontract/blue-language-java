#!/usr/bin/env python3
"""Unit tests for fail-closed processor type classification generation."""

from __future__ import annotations

import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).with_name(
    "generate_processor_type_classification.py"
)
SPEC = importlib.util.spec_from_file_location(
    "generate_processor_type_classification", SCRIPT
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class ProcessorTypeClassificationTest(unittest.TestCase):

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        (self.root / "model").mkdir()
        (self.root / "package-info.java").write_text(
            "package blue.language.processor;\n", encoding="utf-8"
        )
        (self.root / "Api.java").write_text(
            "package blue.language.processor;\n"
            "public final class Api {}\n",
            encoding="utf-8",
        )
        (self.root / "Pair.java").write_text(
            "package blue.language.processor;\n"
            "final class Pair {}\n"
            "final class PairEvidence {}\n",
            encoding="utf-8",
        )
        (self.root / "model" / "Value.java").write_text(
            "package blue.language.processor.model;\n"
            "public final class Value {}\n",
            encoding="utf-8",
        )

    def tearDown(self):
        self.temporary.cleanup()

    def payload(self):
        return {
            "schema": MODULE.SCHEMA,
            "classifications": [
                {
                    "classification": "PUBLIC_API",
                    "types": ["blue.language.processor.Api"],
                },
                {"classification": "PUBLIC_SPI", "types": []},
                {
                    "classification": "PUBLIC_MODEL",
                    "types": ["blue.language.processor.model.Value"],
                },
                {"classification": "INTERNAL_ENGINE", "types": []},
                {
                    "classification": "INTERNAL_SUPPORT",
                    "types": [
                        "blue.language.processor.PairEvidence",
                        "blue.language.processor.Pair",
                    ],
                },
            ],
        }

    def test_counts_all_sources_and_top_level_declarations(self):
        result = MODULE.canonical_payload(self.payload(), self.root)
        self.assertEqual(4, result["counts"]["productionSourceFiles"])
        self.assertEqual(1, result["counts"]["packageDescriptors"])
        self.assertEqual(4, result["counts"]["topLevelTypes"])
        self.assertEqual(2, result["counts"]["publicTopLevelTypes"])
        self.assertEqual(2, result["counts"]["packagePrivateTopLevelTypes"])
        self.assertEqual(
            [
                "blue.language.processor.Pair",
                "blue.language.processor.PairEvidence",
            ],
            result["classifications"][4]["types"],
        )

    def test_rejects_unclassified_source_type(self):
        payload = self.payload()
        payload["classifications"][4]["types"].pop()
        with self.assertRaisesRegex(
            MODULE.ClassificationError, "unclassified=.*Pair"
        ):
            MODULE.canonical_payload(payload, self.root)

    def test_rejects_stale_classification(self):
        payload = self.payload()
        payload["classifications"][4]["types"].append(
            "blue.language.processor.Removed"
        )
        with self.assertRaisesRegex(
            MODULE.ClassificationError, "stale=.*Removed"
        ):
            MODULE.canonical_payload(payload, self.root)

    def test_rejects_duplicate_classification(self):
        payload = self.payload()
        payload["classifications"][3]["types"].append(
            "blue.language.processor.Pair"
        )
        with self.assertRaisesRegex(
            MODULE.ClassificationError, "classified more than once"
        ):
            MODULE.canonical_payload(payload, self.root)

    def test_canonical_bytes_are_stable(self):
        first = MODULE.canonical_bytes(self.payload(), self.root)
        second = MODULE.canonical_bytes(json.loads(first), self.root)
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
