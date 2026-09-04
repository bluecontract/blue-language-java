#!/usr/bin/env python3
"""Regression tests for release implementation-baseline ownership."""
from __future__ import annotations

from pathlib import Path
import hashlib
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import yaml

import build_release_manifests
from implementation_baseline import (
    CONTRACTS_PROCESSOR,
    CYCLIC_FINALIZER,
    CYCLIC_PROOF_VERIFIER,
    IMPLEMENTATION_BASELINE_SOURCE_PATHS,
    ImplementationBaselineError,
    require_implementation_baseline_files,
    source_paths_for_role,
)
from implementation_baseline_inventory import (
    IMPLEMENTATION_BASELINE_INVENTORY_NAME,
    RUNTIME_SOURCE_ROOTS,
    discover_runtime_java_sources,
    load_inventory_paths,
    render_inventory,
    validate_inventory_paths,
)
from release_provenance import source_archive_provenance


TOOLS_ROOT = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_ROOT.parents[3]
GENERATOR = TOOLS_ROOT / "generate_implementation_baseline_inventory.py"


def create_minimal_runtime_tree(root: Path) -> tuple[str, ...]:
    paths: list[str] = []
    for source_root in RUNTIME_SOURCE_ROOTS:
        directory = root / source_root / "example"
        directory.mkdir(parents=True)
        source = directory / "Example.java"
        source.write_text("final class Example {}\n", encoding="utf-8")
        paths.append(source.relative_to(root).as_posix())
    return tuple(sorted(paths))


class ImplementationBaselineTest(unittest.TestCase):

    def test_authoritative_inventory_is_complete_sorted_and_checked_in(self) -> None:
        self.assertEqual(722, len(IMPLEMENTATION_BASELINE_SOURCE_PATHS))
        self.assertEqual(
            tuple(sorted(IMPLEMENTATION_BASELINE_SOURCE_PATHS)),
            IMPLEMENTATION_BASELINE_SOURCE_PATHS,
        )
        self.assertEqual(
            len(set(IMPLEMENTATION_BASELINE_SOURCE_PATHS)),
            len(IMPLEMENTATION_BASELINE_SOURCE_PATHS),
        )
        resolved = require_implementation_baseline_files(REPOSITORY_ROOT)
        self.assertEqual(IMPLEMENTATION_BASELINE_SOURCE_PATHS, tuple(
            relative for relative, _ in resolved
        ))
        self.assertEqual(
            IMPLEMENTATION_BASELINE_SOURCE_PATHS,
            discover_runtime_java_sources(REPOSITORY_ROOT),
        )

    def test_fixed_roots_cover_the_exact_documented_runtime_modules(self) -> None:
        self.assertEqual(
            (
                "blue-contracts-core/src/main/java",
                "blue-language-core/src/main/java",
                "blue-language-ipfs/src/main/java",
                "blue-language-java/src/main/java",
                "blue-language-mapping/src/main/java",
                "blue-language-model/src/main/java",
            ),
            tuple(path.as_posix() for path in RUNTIME_SOURCE_ROOTS),
        )

    def test_role_projections_are_conservative_source_closures(self) -> None:
        language_closure = tuple(
            path
            for path in IMPLEMENTATION_BASELINE_SOURCE_PATHS
            if path.startswith(
                ("blue-language-core/", "blue-language-model/")
            )
        )
        self.assertEqual(246, len(language_closure))
        self.assertEqual(
            language_closure,
            source_paths_for_role(CYCLIC_FINALIZER),
        )
        self.assertEqual(
            language_closure,
            source_paths_for_role(CYCLIC_PROOF_VERIFIER),
        )
        self.assertEqual(
            IMPLEMENTATION_BASELINE_SOURCE_PATHS,
            source_paths_for_role(CONTRACTS_PROCESSOR),
        )

    def test_invalid_inventory_paths_are_rejected(self) -> None:
        invalid = (
            "",
            "/blue-language-core/src/main/java/Absolute.java",
            "blue-language-core\\src\\main\\java\\Backslash.java",
            "blue-language-core/src/main/java/../Escaped.java",
            "blue-language-core/src/main/java/Trailing.java/",
            "blue-language-core/src/main/java/NotJava.txt",
            "blue-language-core/src/test/java/TestOnly.java",
            "blue-language-core/src/main/java/Cafe\u0301.java",
        )
        for path in invalid:
            with self.subTest(path=path), self.assertRaises(
                ImplementationBaselineError
            ):
                validate_inventory_paths((path,))

    def test_duplicate_and_unsorted_inventory_paths_are_rejected(self) -> None:
        first = (
            "blue-language-core/src/main/java/example/A.java"
        )
        second = (
            "blue-language-core/src/main/java/example/B.java"
        )
        with self.assertRaisesRegex(
            ImplementationBaselineError, "duplicate paths"
        ):
            validate_inventory_paths((first, first))
        with self.assertRaisesRegex(
            ImplementationBaselineError, "strictly sorted"
        ):
            validate_inventory_paths((second, first))

    def test_empty_inventory_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            ImplementationBaselineError,
            "at least one source",
        ):
            validate_inventory_paths(())

    def test_discovery_rejects_missing_roots_symlinks_and_special_files(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="implementation-baseline-invalid-tree-"
        ) as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(
                ImplementationBaselineError, "source root is missing"
            ):
                discover_runtime_java_sources(root)

            create_minimal_runtime_tree(root)
            source = root / RUNTIME_SOURCE_ROOTS[0] / "example/Example.java"
            target = root / "target.java"
            target.write_text("final class Target {}\n", encoding="utf-8")
            source.unlink()
            source.symlink_to(target)
            with self.assertRaisesRegex(
                ImplementationBaselineError, "contains a symlink"
            ):
                discover_runtime_java_sources(root)

            source.unlink()
            source.write_text("final class Example {}\n", encoding="utf-8")
            fifo = root / RUNTIME_SOURCE_ROOTS[0] / "example/source.fifo"
            os.mkfifo(fifo)
            with self.assertRaisesRegex(
                ImplementationBaselineError, "non-regular entry"
            ):
                discover_runtime_java_sources(root)

    def test_inventory_loader_rejects_symlink(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="implementation-baseline-symlink-"
        ) as temporary:
            root = Path(temporary)
            target = root / "target.txt"
            target.write_bytes(render_inventory((
                "blue-language-core/src/main/java/example/Example.java",
            )))
            inventory = root / "inventory.txt"
            inventory.symlink_to(target)
            with self.assertRaisesRegex(
                ImplementationBaselineError, "non-symlink regular file"
            ):
                load_inventory_paths(inventory)

    def test_generator_write_and_check_are_exact_and_non_mutating(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="implementation-baseline-generator-"
        ) as temporary:
            root = Path(temporary)
            expected = create_minimal_runtime_tree(root)
            inventory = root / IMPLEMENTATION_BASELINE_INVENTORY_NAME
            common = (
                sys.executable,
                str(GENERATOR),
                "--repository-root",
                str(root),
                "--inventory",
                str(inventory),
            )
            subprocess.run((*common, "--write"), check=True)
            self.assertEqual(expected, load_inventory_paths(inventory))
            before_bytes = inventory.read_bytes()
            before_mtime = inventory.stat().st_mtime_ns
            subprocess.run((*common, "--check"), check=True)
            self.assertEqual(before_bytes, inventory.read_bytes())
            self.assertEqual(before_mtime, inventory.stat().st_mtime_ns)

            extra = root / RUNTIME_SOURCE_ROOTS[0] / "example/Added.java"
            extra.write_text("final class Added {}\n", encoding="utf-8")
            failed = subprocess.run(
                (*common, "--check"),
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(1, failed.returncode)
            self.assertIn("Added.java", failed.stderr)
            self.assertEqual(before_bytes, inventory.read_bytes())
            self.assertEqual(before_mtime, inventory.stat().st_mtime_ns)

            extra.unlink()
            listed = root / expected[0]
            listed.unlink()
            failed = subprocess.run(
                (*common, "--check"),
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(1, failed.returncode)
            self.assertIn(expected[0], failed.stderr)
            self.assertEqual(before_bytes, inventory.read_bytes())
            self.assertEqual(before_mtime, inventory.stat().st_mtime_ns)

    def test_release_manifest_excludes_source_archive_provenance(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="implementation-baseline-manifest-"
        ) as temporary:
            root = Path(temporary)
            registry = root / "registry"
            registry.mkdir()
            (root / "conformance/contracts").mkdir(parents=True)
            gas = root / "gas-manifest.yaml"
            specification = root / "contracts.md"
            language_specification = root / "language.md"
            constructors = root / "identity-constructors.yaml"
            (registry / "manifest.yaml").write_text(
                "packageIdentity: sha256:registry\n",
                encoding="utf-8",
            )
            gas.write_text(
                "packageIdentity: sha256:gas\n"
                "maxProcessGas: 100\n"
                "numericWeightsStatus: test\n",
                encoding="utf-8",
            )
            specification.write_text("contracts\n", encoding="utf-8")
            language_specification.write_text("language\n", encoding="utf-8")
            constructors.write_text("constructors: {}\n", encoding="utf-8")

            with mock.patch.multiple(
                build_release_manifests,
                ROOT=root,
                REG=registry,
                GAS=gas,
                SPEC=specification,
                LANG_SPEC=language_specification,
                IDENTITY_CONSTRUCTORS=constructors,
            ):
                release = build_release_manifests.build_release_manifest(
                    {
                        "packageIdentity": "sha256:fixtures",
                        "vectorCount": 1,
                        "totalExecutableFixtureCount": 1,
                    },
                    {
                        "packageIdentity": "sha256:oracles",
                        "oracleCount": 1,
                    },
                    REPOSITORY_ROOT,
                )

            self.assertNotIn("sourceArchiveBaseline", release)
            persisted = yaml.safe_load(
                (root / "conformance/contracts/release-manifest.yaml")
                .read_text(encoding="utf-8")
            )
            self.assertNotIn("sourceArchiveBaseline", persisted)
            self.assertEqual(
                list(IMPLEMENTATION_BASELINE_SOURCE_PATHS),
                [
                    entry["path"]
                    for entry in persisted["languageDependency"][
                        "inputImplementationBaseline"
                    ]
                ],
            )

    def test_archive_hash_and_name_live_only_in_validation_receipt(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="source-archive-provenance-"
        ) as temporary:
            archive = Path(temporary) / "arbitrary-source-name.zip"
            content = b"distribution provenance, not semantic identity\n"
            archive.write_bytes(content)

            receipt = source_archive_provenance(archive)

        self.assertEqual(
            {
                "provided": True,
                "suppliedName": "arbitrary-source-name.zip",
                "sha256": hashlib.sha256(content).hexdigest(),
            },
            receipt,
        )
        self.assertEqual(
            {"provided": False},
            source_archive_provenance(None),
        )


if __name__ == "__main__":
    unittest.main()
