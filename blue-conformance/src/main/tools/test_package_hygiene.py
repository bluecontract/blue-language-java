#!/usr/bin/env python3
"""Regression tests for deterministic package-junk exclusion."""

from __future__ import annotations

from pathlib import Path
import hashlib
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
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


def write_complete_release(root: Path, receipt_name: str, receipt_hash: str) -> None:
    authored = {
        "package-manifest.yaml": (
            "packageName: "
            f"{build_release_archive.COMPLETE_RELEASE_ARCHIVE_ROOT_NAME}\n"
        ),
        "specifications/blue-contracts-and-processor-specification-1.0.md": (
            "contracts specification\n"
        ),
        "reference/blue-language-specification-1.0.md": (
            "language specification\n"
        ),
        "conformance/contracts/release-manifest.yaml": "releaseIdentity: test\n",
        "tools/build_release_archive.py": "# archive tool\n",
        "tools/validate_package.py": "# validator\n",
        "tools/implementation-baseline-paths.txt": "module/src/main/java/A.java\n",
    }
    for relative, content in authored.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    (root / "validation-output.json").write_text(
        '{"sourceArchive":{"provided":true,'
        f'"suppliedName":"{receipt_name}","sha256":"{receipt_hash}"}}}}\n',
        encoding="utf-8",
    )
    files = release_inventory_files(
        root, {"MANIFEST.sha256", "validation-output.json"}
    )
    lines = [
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  "
        f"{path.relative_to(root).as_posix()}"
        for path in files
    ]
    (root / "MANIFEST.sha256").write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )


class PackageHygieneTest(unittest.TestCase):

    def test_archive_cli_without_root_keeps_legacy_generic_mode(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="blue-release-archive-default-"
        ) as temporary:
            output = Path(temporary) / "default.zip"
            with (
                mock.patch.object(
                    sys,
                    "argv",
                    ["build_release_archive.py", "--output", str(output)],
                ),
                mock.patch.object(
                    build_release_archive, "build_archive"
                ) as generic,
                mock.patch.object(
                    build_release_archive, "build_complete_release_archive"
                ) as complete,
            ):
                build_release_archive.main()
            generic.assert_called_once_with(
                output, build_release_archive.ROOT.absolute()
            )
            complete.assert_not_called()

    def test_release_inventory_rejects_symlinks_and_special_files(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="blue-release-unsafe-entry-"
        ) as temporary:
            root = Path(temporary)
            package = root / "package"
            package.mkdir()
            outside = root / "outside.txt"
            outside.write_text("outside\n", encoding="utf-8")

            file_link = package / "file-link"
            file_link.symlink_to(outside)
            with self.assertRaisesRegex(ValueError, "contains a symlink"):
                release_inventory_files(package)
            file_link.unlink()

            outside_directory = root / "outside-directory"
            outside_directory.mkdir()
            directory_link = package / "directory-link"
            directory_link.symlink_to(outside_directory, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "contains a symlink"):
                release_inventory_files(package)
            directory_link.unlink()

            if hasattr(os, "mkfifo"):
                fifo = package / "pipe"
                os.mkfifo(fifo)
                with self.assertRaisesRegex(ValueError, "non-regular entry"):
                    release_inventory_files(package)

    def test_release_copy_never_dereferences_symlinks(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="blue-release-copy-symlink-"
        ) as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            outside = root / "outside.txt"
            outside.write_text("outside\n", encoding="utf-8")
            (source / "payload").symlink_to(outside)

            with self.assertRaisesRegex(ValueError, "contains a symlink"):
                regenerate_package.copy_release_tree_fail_closed(
                    source, root / "destination"
                )
            self.assertFalse((root / "destination").exists())

    def test_validation_receipt_is_not_part_of_release_archive(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="blue-release-receipt-exclusion-"
        ) as temporary:
            root = Path(temporary)
            package = root / "package"
            package.mkdir()
            (package / "README.md").write_text(
                "semantic package\n", encoding="utf-8"
            )
            receipt = package / "validation-output.json"
            receipt.write_text(
                '{"sourceArchive":{"provided":true,"suppliedName":"one.zip",'
                '"sha256":"' + "1" * 64 + '"}}\n',
                encoding="utf-8",
            )

            first = root / "first.zip"
            build_release_archive.build_archive(first, package)
            receipt.write_text(
                '{"sourceArchive":{"provided":true,"suppliedName":"two.zip",'
                '"sha256":"' + "2" * 64 + '"}}\n',
                encoding="utf-8",
            )
            second = root / "second.zip"
            build_release_archive.build_archive(second, package)

            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(second) as archive:
                self.assertEqual(
                    [f"{package.name}/README.md"], archive.namelist()
                )

    def test_explicit_complete_roots_build_identical_canonical_archives(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="blue-complete-release-archive-"
        ) as temporary:
            temporary_root = Path(temporary)
            first_root = temporary_root / "arbitrary-stage-one"
            second_root = temporary_root / "differently-named-stage-two"
            write_complete_release(first_root, "one.zip", "1" * 64)
            write_complete_release(second_root, "two.zip", "2" * 64)
            first = temporary_root / "first.zip"
            second = temporary_root / "second.zip"
            tool = Path(build_release_archive.__file__).resolve()

            subprocess.run(
                [
                    sys.executable,
                    str(tool),
                    "--root",
                    str(first_root),
                    "--output",
                    str(first),
                ],
                check=True,
            )
            subprocess.run(
                [
                    sys.executable,
                    str(tool),
                    "--root",
                    str(second_root),
                    "--output",
                    str(second),
                ],
                check=True,
            )

            self.assertEqual(first.read_bytes(), second.read_bytes())
            prefix = (
                build_release_archive.COMPLETE_RELEASE_ARCHIVE_ROOT_NAME + "/"
            )
            with zipfile.ZipFile(first) as archive:
                self.assertTrue(archive.namelist())
                self.assertTrue(
                    all(name.startswith(prefix) for name in archive.namelist())
                )
                self.assertNotIn(
                    prefix + "validation-output.json", archive.namelist()
                )

            with self.assertRaisesRegex(ValueError, "outside the package tree"):
                build_release_archive.build_complete_release_archive(
                    first_root / "inside.zip", first_root
                )

            target = temporary_root / "must-not-be-overwritten.zip"
            target.write_bytes(b"preserve me")
            output_link = temporary_root / "output-link.zip"
            output_link.symlink_to(target)
            with self.assertRaisesRegex(ValueError, "must not be a symlink"):
                build_release_archive.build_complete_release_archive(
                    output_link, first_root
                )
            self.assertEqual(b"preserve me", target.read_bytes())

            (first_root / "tools/validate_package.py").write_text(
                "# tampered validator\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                build_release_archive.build_complete_release_archive(
                    temporary_root / "tampered.zip", first_root
                )
            self.assertFalse((temporary_root / "tampered.zip").exists())

    def test_regeneration_comparison_ignores_only_archive_provenance(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="blue-release-provenance-comparison-"
        ) as temporary:
            root = Path(temporary)
            left = root / "left"
            right = root / "right"
            left.mkdir()
            right.mkdir()
            for package, name, digest in (
                (left, "one.zip", "1" * 64),
                (right, "two.zip", "2" * 64),
            ):
                (package / "payload.yaml").write_text(
                    "value: stable\n", encoding="utf-8"
                )
                (package / "validation-output.json").write_text(
                    '{"status":"PACKAGE_VALID","sourceArchive":'
                    f'{{"provided":true,"suppliedName":"{name}",'
                    f'"sha256":"{digest}"}}}}\n',
                    encoding="utf-8",
                )

            self.assertEqual([], regenerate_package.compare_packages(left, right))

            right_receipt = right / "validation-output.json"
            right_receipt.write_text(
                right_receipt.read_text(encoding="utf-8").replace(
                    "PACKAGE_VALID", "DIFFERENT_STATUS"
                ),
                encoding="utf-8",
            )
            self.assertEqual(
                ["content differs: validation-output.json"],
                regenerate_package.compare_packages(left, right),
            )

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
            self.assertNotIn("validation-output.json", archive_files_before)
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
