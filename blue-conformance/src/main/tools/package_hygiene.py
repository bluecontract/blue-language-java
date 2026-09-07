#!/usr/bin/env python3
"""Shared exclusion rules for host and build-generated package files."""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import stat
from typing import Callable, Collection


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


def copy_regular_file(source: Path, destination: Path) -> None:
    """Copy one real regular file without dereferencing a symlink."""
    try:
        mode = source.lstat().st_mode
    except OSError as exc:
        raise ValueError(f"release source file cannot be inspected: {source}") from exc
    if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
        raise ValueError(
            f"release source must be a non-symlink regular file: {source}"
        )
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination, follow_symlinks=False)
    copied_mode = destination.lstat().st_mode
    if stat.S_ISLNK(copied_mode) or not stat.S_ISREG(copied_mode):
        raise ValueError(
            f"copied release file is not a regular file: {destination}"
        )


def copy_regular_tree(
    source: Path,
    destination: Path,
    *,
    dirs_exist_ok: bool = False,
    ignore: Callable[[str, list[str]], set[str]] | None = None,
) -> None:
    """Copy a release tree without ever dereferencing an untrusted symlink."""
    # The pre-scan gives clear diagnostics. ``symlinks=True`` closes the race:
    # an entry replaced by a symlink after the scan is copied as a symlink and
    # is then rejected by the destination scan, never dereferenced.
    release_inventory_files(source)
    shutil.copytree(
        source,
        destination,
        symlinks=True,
        dirs_exist_ok=dirs_exist_ok,
        ignore=ignore,
    )
    release_inventory_files(destination)
