#!/usr/bin/env python3
"""Determinism and checked-package parity tests for C-EVO generation."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import yaml

import generate_contract_evolution_fixtures as generator


TOOLS_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = TOOLS_ROOT.parent
ORDINARY_ROOT = MODULE_ROOT / "resources/blue-contracts-1.0/fixtures/evo"
CLOSURE_ROOT = MODULE_ROOT / "resources/blue-contracts-closure-1.0/fixtures/evo"


class GenerateContractEvolutionFixturesTest(unittest.TestCase):

    def test_inventory_is_explicit_and_excludes_alias_and_demand_vectors(self) -> None:
        rendered = generator.render_fixtures()
        parsed = [yaml.safe_load(content) for content in rendered.values()]
        observed = {vector for fixture in parsed for vector in fixture["vectors"]}

        expected = {
            *(f"C-EVO-{ordinal:02d}" for ordinal in range(1, 11)),
            *(f"C-EVO-{ordinal:02d}" for ordinal in range(14, 18)),
        }
        self.assertEqual(expected, observed)
        self.assertEqual(16, len(rendered))
        self.assertTrue(observed.isdisjoint({"C-EVO-11", "C-EVO-12", "C-EVO-13"}))
        self.assertTrue(
            observed.isdisjoint(generator.FULL_LIFECYCLE_DEMAND_VECTORS)
        )

    def test_two_independent_generations_are_byte_equal(self) -> None:
        with tempfile.TemporaryDirectory(prefix="c-evo-a-") as first_name:
            with tempfile.TemporaryDirectory(prefix="c-evo-b-") as second_name:
                first = Path(first_name)
                second = Path(second_name)
                generator.write_fixtures(first)
                generator.write_fixtures(second)

                self.assertEqual(
                    {path.name: path.read_bytes() for path in first.iterdir()},
                    {path.name: path.read_bytes() for path in second.iterdir()},
                )

    def test_checked_package_mirrors_equal_authoritative_generation(self) -> None:
        rendered = generator.render_fixtures()

        for root in (ORDINARY_ROOT, CLOSURE_ROOT):
            checked = {
                path.name: path.read_bytes()
                for path in root.glob("c-evo-*.yaml")
            }
            self.assertEqual(rendered, checked, str(root))

    def test_every_rendered_fixture_has_stable_identity(self) -> None:
        for name, content in generator.render_fixtures().items():
            fixture = yaml.safe_load(content)
            self.assertEqual(Path(name).stem, fixture["id"])
            self.assertEqual("blue-contracts-fixture/1.0", fixture["schema"])
            self.assertEqual(1, len(fixture["vectors"]))
            self.assertRegex(fixture["vectors"][0], r"^C-EVO-[0-9]{2}$")


if __name__ == "__main__":
    unittest.main()
