#!/usr/bin/env python3
"""Tests for aggregate Language/Contracts release-manifest regeneration."""
from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
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


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _bind_identity(manifest: dict, null_fields: tuple[str, ...]) -> dict:
    manifest = deepcopy(manifest)
    payload = deepcopy(manifest)
    for field in null_fields:
        payload[field] = None
    manifest["packageIdentity"] = "sha256:" + hashlib.sha256(
        aggregate.jcs_dumps(payload)
    ).hexdigest()
    return manifest


def _write_manifest(path: Path, manifest: dict) -> None:
    _write(
        path,
        yaml.safe_dump(
            manifest,
            sort_keys=False,
            allow_unicode=True,
            width=120,
        ),
    )


class RepositoryFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self._write_direct_files()
        language_registry = self._registry(
            aggregate.LANGUAGE_REGISTRY,
            "Boolean",
            "Boolean.blue",
            "boolean\n",
            "fixture-pending",
        )
        contracts_registry = self._registry(
            aggregate.CONTRACTS_REGISTRY,
            "ProcessEmbedded",
            "ProcessEmbedded.blue",
            "process embedded\n",
            "sha256:" + "0" * 64,
        )
        language_fixtures = self._fixtures(
            aggregate.LANGUAGE_FIXTURES,
            language_registry["packageIdentity"],
            language=True,
        )
        contracts_fixtures = self._fixtures(
            aggregate.CONTRACTS_FIXTURES,
            contracts_registry["packageIdentity"],
            language=False,
        )
        language_registry["fixturePackageIdentity"] = language_fixtures[
            "packageIdentity"
        ]
        language_registry = _bind_identity(
            language_registry,
            ("packageIdentity", "fixturePackageIdentity"),
        )
        _write_manifest(self.root / aggregate.LANGUAGE_REGISTRY, language_registry)
        self.language_registry = language_registry
        self.language_fixtures = language_fixtures
        self.contracts_registry = contracts_registry
        self.contracts_fixtures = contracts_fixtures

    def _write_direct_files(self) -> None:
        _write(self.root / aggregate.LANGUAGE_SPEC, "language specification\n")
        _write(self.root / aggregate.CONTRACTS_SPEC, "contracts specification\n")
        _write(
            self.root / "docs/embedded-process-modules-and-collections-summary.md",
            "embedded summary\n",
        )
        _write(
            self.root / "docs/enum-normalization-registry-correction.md",
            "enum correction\n",
        )
        gas = _bind_identity(
            {
                "manifestType": "contracts-gas",
                "packageIdentity": None,
                "defaultExecutionPolicy": "test",
            },
            ("packageIdentity",),
        )
        _write_manifest(self.root / aggregate.CONTRACTS_GAS, gas)

    def _registry(
        self,
        relative_manifest: str,
        key: str,
        relative_file: str,
        content: str,
        fixture_identity: str,
    ) -> dict:
        root = (self.root / relative_manifest).parent
        source = root / relative_file
        _write(source, content)
        manifest = _bind_identity(
            {
                "registry": key.lower(),
                "fixturePackageIdentity": fixture_identity,
                "entries": [
                    {
                        "key": key,
                        "path": relative_file,
                        "blueId": "test-" + key,
                        "sha256": _sha256(source),
                    }
                ],
                "packageIdentity": None,
            },
            ("packageIdentity", "fixturePackageIdentity"),
        )
        _write_manifest(self.root / relative_manifest, manifest)
        return manifest

    def _fixtures(
        self, relative_manifest: str, registry_identity: str, *, language: bool
    ) -> dict:
        root = (self.root / relative_manifest).parent
        source = root / "fixture.yaml"
        _write(source, "fixture: true\n")
        if language:
            _write(root / "preprocessing/registry/manifest.yaml", "registry: local\n")
            counts = {
                "vectorCount": 1,
                "behaviorFixtureCount": 1,
                "gasFixtureCount": 0,
            }
        else:
            counts = {
                "vectorCount": 2,
                "ordinaryBehaviorFixtureCount": 1,
                "ordinaryGasFixtureCount": 1,
                "closureFixtureCount": 1,
                "totalExecutableFixtureCount": 3,
            }
        manifest = _bind_identity(
            {
                "fixturePackage": "language" if language else "contracts",
                "registryPackageIdentity": registry_identity,
                **counts,
                "files": [
                    {
                        "path": "fixture.yaml",
                        "role": "behavior-fixture",
                        "sha256": _sha256(source),
                        "bytes": source.stat().st_size,
                    }
                ],
                "packageIdentity": None,
            },
            ("packageIdentity",),
        )
        _write_manifest(self.root / relative_manifest, manifest)
        return manifest

    def rebind_source(self, source) -> dict:
        path = self.root / source.manifest_path
        manifest = yaml.safe_load(path.read_text(encoding="utf-8"))
        manifest = _bind_identity(manifest, source.identity_null_fields)
        _write_manifest(path, manifest)
        return manifest


class AggregateReleaseManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repository = Path(self.temp.name)
        self.fixture = RepositoryFixture(self.repository)

    def test_rebuild_uses_only_authoritative_sources(self) -> None:
        stale_path = self.repository / aggregate.MANIFEST_PATH
        _write_manifest(
            stale_path,
            {
                "package": "copied-stale-package",
                "files": [
                    {"path": "obsolete.txt", "sha256": "0" * 64, "bytes": 1}
                ],
                "packageIdentity": "stale",
            },
        )

        first = aggregate.expected_manifest(self.repository)
        second = aggregate.expected_manifest(self.repository)
        manifest = yaml.safe_load(first)
        paths = [entry["path"] for entry in manifest["files"]]

        self.assertEqual(first, second)
        self.assertEqual(sorted(paths), paths)
        self.assertEqual(len(paths), len(set(paths)))
        self.assertNotIn("obsolete.txt", paths)
        self.assertIn("conformance/contracts/fixtures/fixture.yaml", paths)
        self.assertIn("conformance/contracts/fixtures/manifest.yaml", paths)
        self.assertIn(
            "conformance/language/fixtures/preprocessing/registry/manifest.yaml",
            paths,
        )
        self.assertEqual(aggregate.PACKAGE_NAME, manifest["package"])
        self.assertEqual(
            aggregate.authoritative_components(self.repository),
            manifest["components"],
        )
        self.assertRegex(manifest["packageIdentity"], r"^sha256:[0-9a-f]{64}$")
        identity_payload = deepcopy(manifest)
        identity_payload["packageIdentity"] = None
        expected_identity = "sha256:" + hashlib.sha256(
            aggregate.jcs_dumps(identity_payload)
        ).hexdigest()
        self.assertEqual(expected_identity, manifest["packageIdentity"])

    def test_rejects_stale_nested_package_identity(self) -> None:
        path = self.repository / aggregate.LANGUAGE_FIXTURES
        manifest = yaml.safe_load(path.read_text(encoding="utf-8"))
        manifest["packageIdentity"] = "sha256:" + "f" * 64
        _write_manifest(path, manifest)

        with self.assertRaisesRegex(ValueError, "packageIdentity is stale"):
            aggregate._verified_manifest_source(
                self.repository, aggregate.LANGUAGE_FIXTURE_SOURCE
            )

    def test_rejects_stale_nested_hash_and_size(self) -> None:
        source = aggregate.LANGUAGE_FIXTURE_SOURCE
        manifest_path = self.repository / source.manifest_path
        source_path = manifest_path.parent / "fixture.yaml"

        _write(source_path, "fixture: changed\n")
        with self.assertRaisesRegex(ValueError, "sha256 is stale"):
            aggregate._verified_manifest_source(self.repository, source)

        manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
        manifest["files"][0]["sha256"] = _sha256(source_path)
        manifest["files"][0]["bytes"] = source_path.stat().st_size + 1
        _write_manifest(
            manifest_path,
            _bind_identity(manifest, source.identity_null_fields),
        )
        with self.assertRaisesRegex(ValueError, "byte count is stale"):
            aggregate._verified_manifest_source(self.repository, source)

    def test_rejects_duplicate_unsorted_and_escaping_nested_paths(self) -> None:
        source = aggregate.CONTRACTS_FIXTURE_SOURCE
        manifest_path = self.repository / source.manifest_path
        original = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))

        cases = (
            (
                "duplicate",
                [deepcopy(original["files"][0]), deepcopy(original["files"][0])],
                "repeats a path",
            ),
            (
                "unsorted",
                [
                    {
                        "path": "z.yaml",
                        "sha256": "0" * 64,
                        "bytes": 0,
                    },
                    deepcopy(original["files"][0]),
                ],
                "references missing file z.yaml",
            ),
            (
                "escape",
                [
                    {
                        "path": "../outside.yaml",
                        "sha256": "0" * 64,
                        "bytes": 0,
                    }
                ],
                "unsafe path",
            ),
        )
        for label, entries, error in cases:
            with self.subTest(label=label):
                manifest = deepcopy(original)
                manifest["files"] = entries
                _write_manifest(
                    manifest_path,
                    _bind_identity(manifest, source.identity_null_fields),
                )
                with self.assertRaisesRegex(ValueError, error):
                    aggregate._verified_manifest_source(self.repository, source)

        _write_manifest(manifest_path, original)
        extra = manifest_path.parent / "a.yaml"
        _write(extra, "extra: true\n")
        manifest = deepcopy(original)
        manifest["files"].insert(
            0,
            {
                "path": "a.yaml",
                "sha256": _sha256(extra),
                "bytes": extra.stat().st_size,
            },
        )
        manifest["files"].reverse()
        _write_manifest(
            manifest_path,
            _bind_identity(manifest, source.identity_null_fields),
        )
        with self.assertRaisesRegex(ValueError, "paths are not sorted"):
            aggregate._verified_manifest_source(self.repository, source)

    def test_rejects_missing_and_unmanifested_source_files(self) -> None:
        source = aggregate.CONTRACTS_FIXTURE_SOURCE
        source_root = (self.repository / source.manifest_path).parent
        fixture = source_root / "fixture.yaml"
        fixture.unlink()
        with self.assertRaisesRegex(ValueError, "references missing file"):
            aggregate._verified_manifest_source(self.repository, source)

        _write(fixture, "fixture: true\n")
        _write(source_root / "unmanifested.yaml", "extra: true\n")
        with self.assertRaisesRegex(ValueError, "unmanifested paths"):
            aggregate._verified_manifest_source(self.repository, source)

    def test_aggregate_validation_rejects_inventory_corruption(self) -> None:
        expected = aggregate.expected_manifest(self.repository)
        original = yaml.safe_load(expected)

        corruptions = []
        missing = deepcopy(original)
        missing["files"].pop(0)
        corruptions.append(("missing", missing, "missing files"))

        extra = deepcopy(original)
        extra["files"].append(
            {"path": "zz-stale", "sha256": "0" * 64, "bytes": 0}
        )
        corruptions.append(("extra", extra, "stale extra files"))

        duplicate = deepcopy(original)
        duplicate["files"].insert(1, deepcopy(duplicate["files"][0]))
        corruptions.append(("duplicate", duplicate, "repeats a file path"))

        escape = deepcopy(original)
        escape["files"][0]["path"] = "../escape"
        corruptions.append(("escape", escape, "unsafe path"))

        stale = deepcopy(original)
        stale["files"][0]["sha256"] = "0" * 64
        corruptions.append(("stale", stale, "stale file metadata"))

        for label, manifest, error in corruptions:
            with self.subTest(label=label):
                actual = yaml.safe_dump(
                    manifest,
                    sort_keys=False,
                    allow_unicode=True,
                    width=120,
                ).encode("utf-8")
                with self.assertRaisesRegex(ValueError, error):
                    aggregate.validate_aggregate_manifest(actual, expected)

    def test_current_repository_authoritative_sources_are_consistent(self) -> None:
        repository = TOOLS.parents[3]
        manifest = aggregate.build_manifest(repository)
        self.assertEqual(
            aggregate.authoritative_components(repository),
            manifest["components"],
        )
        self.assertGreater(len(manifest["files"]), 500)


if __name__ == "__main__":
    unittest.main()
