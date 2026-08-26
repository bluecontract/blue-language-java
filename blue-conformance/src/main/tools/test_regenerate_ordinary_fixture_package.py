#!/usr/bin/env python3
"""Focused fail-closed tests for ordinary fixture-package regeneration."""
from __future__ import annotations

from pathlib import Path
import hashlib
import shutil
import tempfile
import unittest

import yaml

import regenerate_ordinary_fixture_package as regenerator


TOOLS_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = TOOLS_ROOT.parent
REPOSITORY_ROOT = TOOLS_ROOT.parents[3]
FIXTURE_ROOT = MODULE_ROOT / "resources/blue-contracts-1.0/fixtures"
REGISTRY_MANIFEST = (
    REPOSITORY_ROOT
    / "blue-contracts-core/src/main/resources/registry/"
    "blue-contracts-1.0/manifest.yaml"
)
GAS_MANIFEST = (
    MODULE_ROOT / "resources/blue-contracts-closure-1.0/gas-manifest.yaml"
)


def tree_bytes(root: Path) -> dict[str, bytes]:
    return {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


class RegenerateOrdinaryFixturePackageTest(unittest.TestCase):

    def copied_inputs(self, temporary: str, label: str) -> tuple[Path, Path, Path]:
        root = Path(temporary) / label
        fixtures = root / "blue-contracts-1.0/fixtures"
        registry = root / "core-registry/manifest.yaml"
        gas = root / "closure/gas-manifest.yaml"
        shutil.copytree(FIXTURE_ROOT, fixtures)
        registry.parent.mkdir(parents=True)
        shutil.copy2(REGISTRY_MANIFEST, registry)
        gas.parent.mkdir(parents=True)
        shutil.copy2(GAS_MANIFEST, gas)
        return fixtures, registry, gas

    def candidate(
        self, fixtures: Path, registry: Path, gas: Path
    ) -> dict[str, bytes]:
        return regenerator.build_candidate(fixtures, registry, gas)

    def test_two_fresh_generations_are_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="ordinary-fixture-fresh-"
        ) as temporary:
            first = self.copied_inputs(temporary, "first")
            second = self.copied_inputs(temporary, "second")

            self.assertEqual(
                self.candidate(*first),
                self.candidate(*second),
            )

    def test_check_write_and_stage_are_narrow_and_deterministic(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="ordinary-fixture-modes-"
        ) as temporary:
            fixtures, registry, gas = self.copied_inputs(temporary, "source")
            stale_fixture = fixtures / "evo/c-evo-01.yaml"
            stale_fixture.write_bytes(stale_fixture.read_bytes() + b"# stale\n")
            registry.write_bytes(
                regenerator.replace_top_level_scalar(
                    registry.read_bytes(),
                    "fixturePackageIdentity",
                    "sha256:" + "0" * 64,
                )
            )
            candidate = self.candidate(fixtures, registry, gas)
            destinations = regenerator.destination_paths(
                candidate, fixtures, registry
            )
            owned_fixture_paths = {
                relative.removeprefix("fixtures/")
                for relative in candidate
                if relative.startswith("fixtures/")
            }
            unowned_before = {
                relative: content
                for relative, content in tree_bytes(fixtures).items()
                if relative not in owned_fixture_paths
            }
            gas_before = gas.read_bytes()
            registry_before = yaml.safe_load(registry.read_text())

            self.assertTrue(regenerator.compare_candidate(candidate, destinations))
            regenerator.publish(candidate, destinations)

            self.assertEqual([], regenerator.compare_candidate(candidate, destinations))
            self.assertEqual(
                unowned_before,
                {
                    relative: content
                    for relative, content in tree_bytes(fixtures).items()
                    if relative not in owned_fixture_paths
                },
            )
            self.assertEqual(gas_before, gas.read_bytes())
            registry_after = yaml.safe_load(registry.read_text())
            changed_registry_fields = {
                key
                for key in set(registry_before) | set(registry_after)
                if registry_before.get(key) != registry_after.get(key)
            }
            self.assertEqual(
                {"fixturePackageIdentity"}, changed_registry_fields
            )

            stage_root = Path(temporary) / "stage"
            regenerator.stage(candidate, stage_root)
            self.assertEqual(candidate, tree_bytes(stage_root))

    def test_stage_rejects_nonempty_destination(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="ordinary-fixture-stage-"
        ) as temporary:
            inputs = self.copied_inputs(temporary, "source")
            candidate = self.candidate(*inputs)
            stage_root = Path(temporary) / "stage"
            stage_root.mkdir()
            (stage_root / "foreign.txt").write_text("unowned\n")

            with self.assertRaisesRegex(
                regenerator.RegenerationFailure,
                "nonexistent or an empty directory",
            ):
                regenerator.stage(candidate, stage_root)

    def test_stale_c_evo_file_is_rejected_without_deletion(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="ordinary-fixture-stale-"
        ) as temporary:
            fixtures, registry, gas = self.copied_inputs(temporary, "source")
            stale = fixtures / "evo/c-evo-99.yaml"
            stale.write_text(
                "schema: blue-contracts-fixture/1.0\n"
                "id: c-evo-99\n"
                "vectors: [C-EVO-99]\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                regenerator.RegenerationFailure,
                "unexpected stale ordinary C-EVO fixtures",
            ):
                self.candidate(fixtures, registry, gas)
            self.assertTrue(stale.is_file())

    def test_candidate_binds_counts_hashes_coverage_and_reverse_identity(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="ordinary-fixture-bindings-"
        ) as temporary:
            fixtures, registry, gas = self.copied_inputs(temporary, "source")
            candidate = self.candidate(fixtures, registry, gas)
            manifest = yaml.safe_load(candidate["fixtures/manifest.yaml"])
            coverage = yaml.safe_load(
                candidate["fixtures/vector-coverage.yaml"]
            )
            rebound_registry = yaml.safe_load(
                candidate["registry/manifest.yaml"]
            )
            gas_value = yaml.safe_load(gas.read_text())

            self.assertEqual(114, manifest["vectorCount"])
            self.assertEqual(112, manifest["behaviorFixtureCount"])
            self.assertEqual(58, manifest["gasFixtureCount"])
            self.assertEqual(
                170,
                manifest["behaviorFixtureCount"]
                + manifest["gasFixtureCount"],
            )
            self.assertEqual(114, len(coverage["vectors"]))
            self.assertEqual(
                regenerator.EXPECTED_EVO_VECTORS,
                {
                    vector
                    for vector in coverage["vectors"]
                    if vector.startswith("C-EVO-")
                },
            )

            rendered = regenerator.rendered_evolution()
            fixture_values = regenerator.candidate_fixture_files(
                fixtures, rendered
            )
            self.assertEqual(
                coverage,
                regenerator.build_vector_coverage(fixture_values),
            )
            for entry in manifest["files"]:
                relative = entry["path"]
                candidate_key = f"fixtures/{relative}"
                content = candidate.get(candidate_key)
                if content is None:
                    content = (fixtures / relative).read_bytes()
                self.assertEqual(len(content), entry["bytes"], relative)
                self.assertEqual(
                    hashlib.sha256(content).hexdigest(),
                    entry["sha256"],
                    relative,
                )

            self.assertEqual(
                manifest["packageIdentity"],
                regenerator.package_identity(manifest, ("packageIdentity",)),
            )
            self.assertEqual(
                manifest["packageIdentity"],
                rebound_registry["fixturePackageIdentity"],
            )
            self.assertEqual(
                manifest["registryPackageIdentity"],
                rebound_registry["packageIdentity"],
            )
            self.assertEqual(
                rebound_registry["packageIdentity"],
                regenerator.package_identity(
                    rebound_registry,
                    ("packageIdentity", "fixturePackageIdentity"),
                ),
            )
            self.assertEqual(
                gas_value["packageIdentity"],
                manifest["gasManifestPackageIdentity"],
            )
            self.assertEqual(
                hashlib.sha256(gas.read_bytes()).hexdigest(),
                manifest["gasManifestSha256"],
            )
            for name, content in rendered.items():
                self.assertEqual(
                    content,
                    candidate[f"fixtures/evo/{name}"],
                )
                self.assertEqual(
                    content,
                    (regenerator.CANONICAL_CLOSURE_EVO_ROOT / name).read_bytes(),
                )

    def test_registry_identity_drift_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="ordinary-fixture-registry-"
        ) as temporary:
            fixtures, registry, gas = self.copied_inputs(temporary, "source")
            value = yaml.safe_load(registry.read_text())
            value["packageIdentity"] = "sha256:" + "0" * 64
            registry.write_text(yaml.safe_dump(value, sort_keys=False))

            with self.assertRaisesRegex(
                regenerator.RegenerationFailure,
                "registry packageIdentity mismatch",
            ):
                self.candidate(fixtures, registry, gas)


if __name__ == "__main__":
    unittest.main()
