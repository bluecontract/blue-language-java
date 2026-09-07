#!/usr/bin/env python3
"""Regression tests for the repository resource-package regeneration shell."""

from __future__ import annotations

from pathlib import Path
import hashlib
import shutil
import tempfile
import unittest

import build_release_archive
import regenerate_package as regenerator
from package_hygiene import release_inventory_files


TOOLS_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = TOOLS_ROOT.parent
REPOSITORY_ROOT = TOOLS_ROOT.parents[3]
PACKAGE_ROOT = (
    MODULE_ROOT / "resources/blue-contracts-closure-1.0"
)


class RegenerateResourcePackageTest(unittest.TestCase):

    @staticmethod
    def add_generated_root_manifests(release_root: Path) -> None:
        (release_root / "package-manifest.yaml").write_text(
            "packageName: "
            f"{build_release_archive.COMPLETE_RELEASE_ARCHIVE_ROOT_NAME}\n",
            encoding="utf-8",
        )
        files = release_inventory_files(
            release_root,
            {"MANIFEST.sha256", "validation-output.json"},
        )
        lines = [
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  "
            f"{path.relative_to(release_root).as_posix()}"
            for path in files
        ]
        (release_root / "MANIFEST.sha256").write_text(
            "\n".join(lines) + "\n", encoding="utf-8"
        )

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

            templates = REPOSITORY_ROOT / regenerator.CANONICAL_JAVA_TEMPLATES
            self.assertEqual(58, len(list(templates.rglob("*.java"))))
            self.assertEqual([], regenerator.compare_packages(
                templates, release_root / "java-templates"))

            self.assertTrue(
                (staged_contracts / "fixtures/closure/README.md").is_file()
            )
            self.assertTrue(
                (staged_contracts / "registry/ScriptedOperation.blue").is_file()
            )

    def test_complete_stage_retains_the_exact_generated_release_shell(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="contracts-complete-release-stage-"
        ) as temporary:
            root = Path(temporary)
            release_root = root / "generated-release"
            regenerator.stage_release_shell(
                PACKAGE_ROOT,
                REPOSITORY_ROOT,
                release_root,
            )
            self.add_generated_root_manifests(release_root)
            candidate = root / "validated-contracts-candidate"
            shutil.copytree(
                release_root / "conformance/contracts", candidate
            )
            destination = root / "retained-release"

            result = regenerator.copy_complete_release_stage(
                release_root,
                candidate,
                destination,
            )

            self.assertEqual(destination, result)
            required = (
                "MANIFEST.sha256",
                "package-manifest.yaml",
                "specifications/blue-contracts-and-processor-specification-1.0.md",
                "reference/blue-language-specification-1.0.md",
                "tools/regenerate_package.py",
                "tools/build_release_archive.py",
                "tools/validate_package.py",
                "tools/implementation-baseline-paths.txt",
                "conformance/contracts/release-manifest.yaml",
            )
            self.assertEqual(
                [], [path for path in required if not (result / path).is_file()]
            )
            self.assertEqual(
                [], regenerator.compare_packages(release_root, result)
            )
            self.assertEqual(
                [],
                regenerator.compare_packages(
                    candidate, result / "conformance/contracts"
                ),
            )

            candidate_manifest = candidate / "release-manifest.yaml"
            candidate_bytes = candidate_manifest.read_bytes()
            candidate_manifest.write_text(
                "releaseIdentity: mismatched-candidate\n", encoding="utf-8"
            )
            mismatched = root / "mismatched-release"
            with self.assertRaisesRegex(
                regenerator.RegenerationFailure,
                "does not contain the validated candidate",
            ):
                regenerator.copy_complete_release_stage(
                    release_root,
                    candidate,
                    mismatched,
                )
            self.assertFalse(mismatched.exists())
            candidate_manifest.write_bytes(candidate_bytes)

            (release_root / "tools/README.md").write_text(
                "tampered after manifest generation\n", encoding="utf-8"
            )
            rejected = root / "rejected-release"
            with self.assertRaisesRegex(
                regenerator.RegenerationFailure, "checksum mismatch"
            ):
                regenerator.copy_complete_release_stage(
                    release_root,
                    candidate,
                    rejected,
                )
            self.assertFalse(rejected.exists())

    def test_stage_destinations_and_validation_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="contracts-stage-safety-"
        ) as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "payload.txt").write_text(
                "candidate\n", encoding="utf-8"
            )

            invalid_file = root / "output-file"
            invalid_file.write_text("occupied\n", encoding="utf-8")
            nonempty = root / "nonempty"
            nonempty.mkdir()
            (nonempty / "occupied.txt").write_text(
                "occupied\n", encoding="utf-8"
            )
            symlink = root / "output-link"
            symlink.symlink_to(root / "link-target")
            for destination in (
                invalid_file,
                nonempty,
                symlink,
                source / "nested-output",
            ):
                with self.subTest(destination=destination), self.assertRaises(
                    regenerator.RegenerationFailure
                ):
                    regenerator.copy_stage(source, destination)

            forbidden = root / "repository-source"
            forbidden.mkdir()
            with self.assertRaisesRegex(
                regenerator.RegenerationFailure, "outside every source tree"
            ):
                regenerator.copy_stage(
                    source,
                    forbidden / "output",
                    forbidden_roots=(forbidden,),
                )

            def reject(_staged: Path) -> None:
                raise regenerator.RegenerationFailure("validation rejected")

            absent = root / "absent-after-failure"
            with self.assertRaisesRegex(
                regenerator.RegenerationFailure, "validation rejected"
            ):
                regenerator.copy_stage(source, absent, validator=reject)
            self.assertFalse(absent.exists())

            empty = root / "empty-after-failure"
            empty.mkdir()
            with self.assertRaisesRegex(
                regenerator.RegenerationFailure, "validation rejected"
            ):
                regenerator.copy_stage(source, empty, validator=reject)
            self.assertTrue(empty.is_dir())
            self.assertEqual([], list(empty.iterdir()))


if __name__ == "__main__":
    unittest.main()
