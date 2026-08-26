#!/usr/bin/env python3
"""Regression tests for deterministic package-junk exclusion."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest
import zipfile

import build_release_archive
import build_release_manifests
import release_regenerate_package as regenerate_package
from package_hygiene import release_inventory_files


HOST_METADATA_PATHS = (
    Path(".DS_Store"),
    Path("nested/.DS_Store"),
    Path("nested/._payload.yaml"),
    Path("__MACOSX/nested/payload.yaml"),
    Path(".Spotlight-V100/index"),
    Path(".Trashes/501/deleted"),
    Path(".fseventsd/no_log"),
    Path("Thumbs.db"),
    Path("nested/Desktop.ini"),
)

BUILD_CACHE_PATHS = (
    Path("loose.pyc"),
    Path("nested/loose.PYC"),
    Path("build/generated/output.class"),
    Path("nested/build/generated.txt"),
    Path(".gradle/caches/state.bin"),
    Path("nested/.cache/index"),
    Path(".mypy_cache/3.11/metadata"),
    Path(".pytest_cache/v/cache/nodeids"),
    Path(".ruff_cache/content"),
    Path("__pycache__/module.pyc"),
    Path("dist/package.whl"),
    Path("out/classes/Output.class"),
    Path("target/classes/Target.class"),
)


class PackageHygieneTest(unittest.TestCase):

    def test_host_metadata_never_changes_release_inventories_or_archive(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="blue-package-hygiene-"
        ) as temporary:
            temporary_root = Path(temporary)
            package = temporary_root / "package"
            fixtures = package / "fixtures"
            fixtures.mkdir(parents=True)
            (package / "README.md").write_text("release\n", encoding="utf-8")
            (package / "package-manifest.yaml").write_text(
                "fileCount: 2\n", encoding="utf-8"
            )
            (package / "validation-output.json").write_text(
                "{}\n", encoding="utf-8"
            )
            (fixtures / "manifest.yaml").write_text(
                "files: []\n", encoding="utf-8"
            )
            (fixtures / "fixture.yaml").write_text(
                "vectors: [C-TEST-01]\n", encoding="utf-8"
            )

            build_release_manifests.write_checksum_manifest(package)
            checksum_before = (package / "MANIFEST.sha256").read_bytes()
            package_files_before = self._relative_paths(
                build_release_manifests.package_files(package), package
            )
            checksum_inventory_before = self._relative_paths(
                release_inventory_files(
                    package,
                    {"MANIFEST.sha256", "validation-output.json"},
                ),
                package,
            )
            regeneration_inventory_before = set(
                regenerate_package.package_inventory(package)
            )
            fixture_inventory_before = self._relative_paths(
                release_inventory_files(fixtures, {"manifest.yaml"}),
                fixtures,
            )
            archive_files_before = self._relative_paths(
                build_release_archive.package_files(package), package
            )
            archive_before = temporary_root / "before.zip"
            build_release_archive.build_archive(archive_before, package)

            for relative in HOST_METADATA_PATHS + BUILD_CACHE_PATHS:
                target = package / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("host-specific\n", encoding="utf-8")
            fixture_metadata = fixtures / ".DS_Store"
            fixture_metadata.write_text("host-specific\n", encoding="utf-8")

            build_release_manifests.write_checksum_manifest(package)
            self.assertEqual(
                checksum_before,
                (package / "MANIFEST.sha256").read_bytes(),
            )
            self.assertEqual(
                package_files_before,
                self._relative_paths(
                    build_release_manifests.package_files(package), package
                ),
            )
            self.assertEqual(
                checksum_inventory_before,
                self._relative_paths(
                    release_inventory_files(
                        package,
                        {"MANIFEST.sha256", "validation-output.json"},
                    ),
                    package,
                ),
            )
            self.assertEqual(
                regeneration_inventory_before,
                set(regenerate_package.package_inventory(package)),
            )
            self.assertEqual(
                fixture_inventory_before,
                self._relative_paths(
                    release_inventory_files(fixtures, {"manifest.yaml"}),
                    fixtures,
                ),
            )
            self.assertEqual(
                archive_files_before,
                self._relative_paths(
                    build_release_archive.package_files(package), package
                ),
            )

            archive_after = temporary_root / "after.zip"
            build_release_archive.build_archive(archive_after, package)
            self.assertEqual(
                archive_before.read_bytes(), archive_after.read_bytes()
            )
            with zipfile.ZipFile(archive_after) as archive:
                archived_names = set(archive.namelist())
                for relative in BUILD_CACHE_PATHS:
                    self.assertNotIn(
                        f"{package.name}/{relative.as_posix()}",
                        archived_names,
                    )
                self.assertFalse(
                    any(
                        part.startswith("._")
                        or part.casefold()
                        in {
                            ".ds_store",
                            ".fseventsd",
                            ".spotlight-v100",
                            ".trashes",
                            "__macosx",
                            "desktop.ini",
                            "thumbs.db",
                        }
                        for name in archive.namelist()
                        for part in Path(name).parts
                    )
                )

    @staticmethod
    def _relative_paths(paths: list[Path], root: Path) -> list[str]:
        return [path.relative_to(root).as_posix() for path in paths]


if __name__ == "__main__":
    unittest.main()
