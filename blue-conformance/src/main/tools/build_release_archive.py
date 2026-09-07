#!/usr/bin/env python3
"""Build the validated Contracts package as a deterministic ZIP archive."""
from __future__ import annotations

from argparse import ArgumentParser
from pathlib import Path
import hashlib
import os
import re
import stat
import tempfile
import zipfile

import yaml

from package_hygiene import release_inventory_files


ROOT = Path(__file__).resolve().parents[1]
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
NON_RELEASE_RECEIPTS = frozenset({"validation-output.json"})
COMPLETE_RELEASE_ARCHIVE_ROOT_NAME = (
    "blue-contracts-and-processor-specification-1.0-final"
)
LOWERCASE_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
REQUIRED_COMPLETE_RELEASE_FILES = (
    "MANIFEST.sha256",
    "package-manifest.yaml",
    "specifications/blue-contracts-and-processor-specification-1.0.md",
    "reference/blue-language-specification-1.0.md",
    "conformance/contracts/release-manifest.yaml",
    "tools/build_release_archive.py",
    "tools/validate_package.py",
    "tools/implementation-baseline-paths.txt",
)


def require_complete_release_root(root: Path) -> Path:
    """Return a real complete release root or fail before archiving."""
    absolute = root.expanduser().absolute()
    try:
        mode = absolute.lstat().st_mode
    except FileNotFoundError as exc:
        raise ValueError(f"release root does not exist: {absolute}") from exc
    if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
        raise ValueError(
            f"release root must be a non-symlink directory: {absolute}"
        )
    invalid: list[str] = []
    for relative in REQUIRED_COMPLETE_RELEASE_FILES:
        path = absolute / relative
        try:
            entry_mode = path.lstat().st_mode
        except FileNotFoundError:
            invalid.append(relative)
            continue
        if stat.S_ISLNK(entry_mode) or not stat.S_ISREG(entry_mode):
            invalid.append(relative)
    if invalid:
        raise ValueError(
            "release root is incomplete or contains invalid required files: "
            f"{invalid}"
        )
    try:
        package_manifest = yaml.safe_load(
            (absolute / "package-manifest.yaml").read_text(encoding="utf-8")
        )
    except (OSError, UnicodeError, yaml.YAMLError) as exc:
        raise ValueError("release package manifest cannot be read") from exc
    if (
        not isinstance(package_manifest, dict)
        or package_manifest.get("packageName")
        != COMPLETE_RELEASE_ARCHIVE_ROOT_NAME
    ):
        raise ValueError(
            "release package manifest has an unexpected packageName"
        )
    return absolute


def package_files(root: Path = ROOT) -> list[Path]:
    return release_inventory_files(root, NON_RELEASE_RECEIPTS)


def verify_checksum_manifest(root: Path) -> None:
    """Require the exact canonical checksum inventory before publication."""
    manifest = root / "MANIFEST.sha256"
    expected_files = release_inventory_files(
        root, {"MANIFEST.sha256", "validation-output.json"}
    )
    expected_paths = [
        path.relative_to(root).as_posix() for path in expected_files
    ]
    expected_path_set = set(expected_paths)
    try:
        raw = manifest.read_bytes()
    except OSError as exc:
        raise ValueError("release checksum manifest cannot be read") from exc
    if not raw.endswith(b"\n") or b"\r" in raw:
        raise ValueError(
            "release checksum manifest must use LF lines and end in newline"
        )
    try:
        lines = raw[:-1].decode("utf-8").split("\n")
    except UnicodeDecodeError as exc:
        raise ValueError("release checksum manifest is not UTF-8") from exc
    actual_paths: list[str] = []
    for line in lines:
        parts = line.split("  ", 1)
        if len(parts) != 2:
            raise ValueError("malformed release checksum manifest line")
        digest, relative = parts
        if LOWERCASE_SHA256_RE.fullmatch(digest) is None:
            raise ValueError(
                f"malformed release checksum digest: {relative}"
            )
        if not relative or "  " in relative:
            raise ValueError("malformed release checksum manifest path")
        if relative not in expected_path_set:
            raise ValueError(
                f"unexpected release checksum manifest path: {relative}"
            )
        path = root / relative
        if not path.is_file() or path.is_symlink():
            raise ValueError(
                f"release checksum target is missing or invalid: {relative}"
            )
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != digest:
            raise ValueError(f"release checksum mismatch: {relative}")
        actual_paths.append(relative)
    if actual_paths != expected_paths:
        raise ValueError(
            "release checksum inventory/order mismatch: "
            f"expected={expected_paths[:5]}, actual={actual_paths[:5]}"
        )


def build_archive(
    destination: Path,
    root: Path = ROOT,
    *,
    archive_root_name: str | None = None,
) -> None:
    destination = destination.expanduser().absolute()
    if destination.is_symlink():
        raise ValueError(
            f"release archive destination must not be a symlink: {destination}"
        )
    resolved_destination = destination.resolve(strict=False)
    root = root.expanduser().absolute()
    try:
        root_mode = root.lstat().st_mode
    except FileNotFoundError as exc:
        raise ValueError(f"archive source root does not exist: {root}") from exc
    if stat.S_ISLNK(root_mode) or not stat.S_ISDIR(root_mode):
        raise ValueError(
            f"archive source root must be a non-symlink directory: {root}"
        )
    root = root.resolve()
    if resolved_destination == root or root in resolved_destination.parents:
        raise ValueError("release archive must be written outside the package tree")
    if destination.exists() and not destination.is_file():
        raise ValueError(
            f"release archive destination must be a file path: {destination}"
        )
    entry_root = root.name if archive_root_name is None else archive_root_name
    if (
        not entry_root
        or entry_root in {".", ".."}
        or "/" in entry_root
        or "\\" in entry_root
    ):
        raise ValueError(f"invalid release archive root name: {entry_root!r}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.", dir=destination.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        with zipfile.ZipFile(
            temporary,
            mode="w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=9,
        ) as archive:
            for source in package_files(root):
                relative = source.relative_to(root).as_posix()
                entry = zipfile.ZipInfo(
                    f"{entry_root}/{relative}", date_time=FIXED_TIMESTAMP
                )
                entry.create_system = 3
                entry.external_attr = (0o100644 & 0xFFFF) << 16
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(
                    entry,
                    source.read_bytes(),
                    compress_type=zipfile.ZIP_DEFLATED,
                    compresslevel=9,
                )
        os.replace(temporary, destination)
    finally:
        if temporary.exists():
            temporary.unlink()


def build_complete_release_archive(destination: Path, root: Path) -> None:
    """Archive one complete staged release under its canonical entry prefix."""
    complete_root = require_complete_release_root(root)
    verify_checksum_manifest(complete_root)
    build_archive(
        destination,
        complete_root,
        archive_root_name=COMPLETE_RELEASE_ARCHIVE_ROOT_NAME,
    )


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=None,
        help=(
            "Explicit complete staged release root. When omitted, use the "
            "release tree containing this vendored tool."
        ),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Archive path (default: release-root sibling with .zip suffix)",
    )
    args = parser.parse_args()
    release_root = ROOT if args.root is None else args.root
    absolute_root = release_root.expanduser().absolute()
    output = (
        absolute_root.parent / f"{absolute_root.name}.zip"
        if args.output is None
        else args.output
    )
    if args.root is None:
        # Preserve the vendored tool's historical default: archive its
        # containing tree under that directory's name.
        build_archive(output, absolute_root)
    else:
        build_complete_release_archive(output, absolute_root)
    print(f"RELEASE_ARCHIVE_WRITTEN {output.expanduser().resolve()}")


if __name__ == "__main__":
    main()
