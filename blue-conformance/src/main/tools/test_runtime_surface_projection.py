#!/usr/bin/env python3
"""Focused vectors for normalized effective subscription surfaces."""

from __future__ import annotations

from pathlib import Path
import unittest

import yaml

from runtime_surface_projection import project_runtime_surface


ROOT = Path(__file__).resolve().parents[1]
RELEASE_FIXTURES = ROOT / "conformance/contracts/fixtures/closure"
REPOSITORY_FIXTURES = (
    ROOT / "resources/blue-contracts-closure-1.0/fixtures/closure"
)
FIXTURES = (
    RELEASE_FIXTURES
    if RELEASE_FIXTURES.is_dir()
    else REPOSITORY_FIXTURES
)


class RuntimeSurfaceProjectionTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.vectors = yaml.safe_load(
            Path(__file__).with_name("runtime-surface-vectors.yaml").read_text(
                encoding="utf-8"
            )
        )["vectors"]

    def test_c_clo_34_normalized_contribution_and_header_pairs(self) -> None:
        for vector in self.vectors:
            with self.subTest(vector=vector["name"]):
                fixture = yaml.safe_load(
                    (FIXTURES / vector["fixture"]).read_text(
                        encoding="utf-8"
                    )
                )
                contract = fixture["input"]["documents"][
                    vector["documentId"]
                ]["document"]["contracts"][vector["channelKey"]]
                projection = project_runtime_surface(contract)
                self.assertEqual(
                    vector["effectiveRuntimeContributionBlueId"],
                    projection.effective_runtime_contribution_blue_id,
                )
                self.assertEqual(
                    vector["subscriptionHeaderBlueId"],
                    projection.subscription_header_blue_id,
                )

    def test_scalar_overlay_retains_registry_header_metadata(self) -> None:
        projection = self._projection("a", "fromB")
        self.assertEqual(
            {
                "description": "Optional deterministic order. Missing is zero.",
                "type": {
                    "blueId": (
                        "E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq"
                    )
                },
                "value": 0,
            },
            projection.subscription_header["order"],
        )
        self.assertEqual(
            {"description": "Optional event matcher."},
            projection.subscription_header["event"],
        )

    def test_schema_enum_is_a_canonical_set(self) -> None:
        projection = self._projection("a", "source")
        self.assertEqual(
            ["catalog", "exact", "none"],
            projection.subscription_header["dependencyMode"]
            ["schema"]["enum"],
        )

    @staticmethod
    def _projection(document_id: str, channel_key: str):
        fixture = yaml.safe_load(
            (FIXTURES / "c-clo-34-separate-document-steps.yaml").read_text(
                encoding="utf-8"
            )
        )
        contract = fixture["input"]["documents"][document_id]["document"][
            "contracts"
        ][channel_key]
        return project_runtime_surface(contract)


if __name__ == "__main__":
    unittest.main()
