#!/usr/bin/env python3
"""Shared exclusion rules for host and build-generated package files."""

from __future__ import annotations

from pathlib import Path
from typing import Collection


_HOST_METADATA_NAMES = frozenset(
    {
        ".ds_store",
        ".fseventsd",
        ".spotlight-v100",
        ".trashes",
        "__macosx",
        "desktop.ini",
        "thumbs.db",
    }
)

_BUILD_CACHE_DIRECTORY_NAMES = frozenset(
    {
        ".cache",
        ".gradle",
        ".mypy_cache",
        ".pytest_cache",
        ".ruff_cache",
        "__pycache__",
        "build",
        "dist",
        "out",
        "target",
    }
)


def is_host_metadata_path(path: Path) -> bool:
    """Return whether ``path`` is OS metadata, never authored package input."""
    for part in path.parts:
        if part.casefold() in _HOST_METADATA_NAMES or part.startswith("._"):
            return True
    return False


def is_build_or_cache_path(path: Path) -> bool:
    """Return whether ``path`` is nested below a build/cache directory."""
    return any(
        part.casefold() in _BUILD_CACHE_DIRECTORY_NAMES
        for part in path.parts[:-1]
    )


def release_inventory_files(
    root: Path,
    excluded_paths: Collection[str] = (),
) -> list[Path]:
    """Return sorted authored files eligible for package inventories."""
    excluded = set(excluded_paths)
    files: list[Path] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        relative_text = relative.as_posix()
        if relative_text in excluded:
            continue
        if relative.suffix.casefold() == ".pyc":
            continue
        if is_build_or_cache_path(relative):
            continue
        if is_host_metadata_path(relative):
            continue
        files.append(path)
    return sorted(files, key=lambda path: path.relative_to(root).as_posix())
