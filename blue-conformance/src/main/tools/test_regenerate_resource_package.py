#!/usr/bin/env python3
"""Regression tests for the repository resource-package regeneration shell."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import regenerate_package as regenerator


TOOLS_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = TOOLS_ROOT.parent
REPOSITORY_ROOT = TOOLS_ROOT.parents[3]
PACKAGE_ROOT = (
    MODULE_ROOT / "resources/blue-contracts-closure-1.0"
)


class RegenerateResourcePackageTest(unittest.TestCase):

    def test_stage_refreshes_canonical_mirrors_and_retains_closure_inputs(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="contracts-resource-shell-"
        ) as temporary:
            release_root = Path(temporary) / "release"
            regenerator.stage_release_shell(
                PACKAGE_ROOT,
                REPOSITORY_ROOT,
                release_root,
            )

            staged_contracts = release_root / "conformance/contracts"
            canonical_fixtures = (
                REPOSITORY_ROOT / regenerator.CANONICAL_ORDINARY_FIXTURES
            )
            canonical_registry = (
                REPOSITORY_ROOT / regenerator.CANONICAL_RUNTIME_REGISTRY
            )
            for source in canonical_fixtures.rglob("*"):
                if source.is_file():
                    target = (
                        staged_contracts
                        / "fixtures"
                        / source.relative_to(canonical_fixtures)
                    )
                    self.assertEqual(source.read_bytes(), target.read_bytes())
            for source in canonical_registry.rglob("*"):
                if source.is_file():
                    target = (
                        staged_contracts
                        / "registry"
                        / source.relative_to(canonical_registry)
                    )
                    self.assertEqual(source.read_bytes(), target.read_bytes())

            self.assertTrue(
                (staged_contracts / "fixtures/closure/README.md").is_file()
            )
            self.assertTrue(
                (staged_contracts / "registry/ScriptedOperation.blue").is_file()
            )


if __name__ == "__main__":
    unittest.main()
