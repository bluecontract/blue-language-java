#!/usr/bin/env python3
"""Tests for aggregate Language/Contracts release-manifest regeneration."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest

import yaml


TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location(
    "regenerate_aggregate_release_manifest",
    TOOLS / "regenerate_aggregate_release_manifest.py",
)
assert SPEC is not None and SPEC.loader is not None
aggregate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(aggregate)


class AggregateReleaseManifestTest(unittest.TestCase):

    def test_expected_manifest_binds_every_authoritative_component(self) -> None:
        repository = TOOLS.parents[3]
        expected = yaml.safe_load(aggregate.expected_manifest(repository))
        self.assertEqual(
            aggregate.authoritative_components(repository),
            expected["components"],
        )
        self.assertRegex(
            expected["packageIdentity"], r"^sha256:[0-9a-f]{64}$"
        )

    def test_missing_new_count_is_inserted_once(self) -> None:
        source = "components:\n  contractsGasFixtureCount: 1\npackageIdentity: old\n"
        updated = aggregate._replace_component(
            source, "contractsClosureFixtureCount", 93
        )
        self.assertEqual(1, updated.count("contractsClosureFixtureCount"))
        self.assertIn("contractsClosureFixtureCount: 93", updated)


if __name__ == "__main__":
    unittest.main()
