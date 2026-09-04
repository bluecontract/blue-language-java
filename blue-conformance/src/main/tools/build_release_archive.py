#!/usr/bin/env python3
"""Build the validated Contracts package as a deterministic ZIP archive."""
from __future__ import annotations

from argparse import ArgumentParser
from pathlib import Path
import os
import tempfile
import zipfile

from package_hygiene import release_inventory_files


ROOT = Path(__file__).resolve().parents[1]
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
NON_RELEASE_RECEIPTS = frozenset({"validation-output.json"})


def package_files(root: Path = ROOT) -> list[Path]:
    return release_inventory_files(root, NON_RELEASE_RECEIPTS)


def build_archive(destination: Path, root: Path = ROOT) -> None:
    destination = destination.expanduser().resolve()
    root = root.expanduser().resolve()
    if destination == root or root in destination.parents:
        raise ValueError("release archive must be written outside the package tree")
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
                    f"{root.name}/{relative}", date_time=FIXED_TIMESTAMP
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


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT.parent / f"{ROOT.name}.zip",
        help="Archive path (default: package sibling with .zip suffix)",
    )
    args = parser.parse_args()
    build_archive(args.output)
    print(f"RELEASE_ARCHIVE_WRITTEN {args.output.expanduser().resolve()}")


if __name__ == "__main__":
    main()
