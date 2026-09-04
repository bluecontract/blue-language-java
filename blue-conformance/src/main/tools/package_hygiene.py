#!/usr/bin/env python3
"""Shared exclusion rules for host and build-generated package files."""

from __future__ import annotations

import os
from pathlib import Path
import stat
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
    """Return sorted authored files, rejecting unsafe filesystem entries."""
    root = root.expanduser().absolute()
    try:
        root_mode = root.lstat().st_mode
    except OSError as exc:
        raise ValueError(f"release inventory root cannot be inspected: {root}") from exc
    if stat.S_ISLNK(root_mode) or not stat.S_ISDIR(root_mode):
        raise ValueError(
            f"release inventory root must be a non-symlink directory: {root}"
        )

    excluded = set(excluded_paths)
    files: list[Path] = []

    def visit(directory: Path) -> None:
        try:
            entries = list(os.scandir(directory))
        except OSError as exc:
            raise ValueError(
                f"release inventory directory cannot be inspected: {directory}"
            ) from exc
        entries.sort(key=lambda entry: os.fsencode(entry.name))
        for entry in entries:
            path = directory / entry.name
            relative = path.relative_to(root)
            relative_text = relative.as_posix()
            try:
                mode = entry.stat(follow_symlinks=False).st_mode
            except OSError as exc:
                raise ValueError(
                    f"release inventory entry cannot be inspected: {relative_text}"
                ) from exc
            if stat.S_ISLNK(mode):
                raise ValueError(
                    f"release inventory contains a symlink: {relative_text}"
                )
            if stat.S_ISDIR(mode):
                visit(path)
                continue
            if not stat.S_ISREG(mode):
                raise ValueError(
                    f"release inventory contains a non-regular entry: {relative_text}"
                )
            if relative_text in excluded:
                continue
            if relative.suffix.casefold() == ".pyc":
                continue
            if is_build_or_cache_path(relative):
                continue
            if is_host_metadata_path(relative):
                continue
            files.append(path)

    visit(root)
    return sorted(
        files,
        key=lambda path: os.fsencode(path.relative_to(root).as_posix()),
    )
