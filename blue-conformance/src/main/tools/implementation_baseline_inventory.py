#!/usr/bin/env python3
"""Canonical inventory I/O and source-tree discovery for release baselines."""
from __future__ import annotations

from pathlib import Path, PurePosixPath
from collections import Counter
import os
import stat
import unicodedata
from typing import Iterable


RUNTIME_MODULE_NAMES = (
    "blue-contracts-core",
    "blue-language-core",
    "blue-language-ipfs",
    "blue-language-java",
    "blue-language-mapping",
    "blue-language-model",
)
RUNTIME_SOURCE_ROOTS = tuple(
    PurePosixPath(module, "src/main/java")
    for module in RUNTIME_MODULE_NAMES
)
IMPLEMENTATION_BASELINE_INVENTORY_NAME = (
    "implementation-baseline-paths.txt"
)


class ImplementationBaselineError(ValueError):
    """Raised when baseline ownership or checked-out sources are invalid."""


def _path_sort_key(path: str) -> bytes:
    return path.encode("utf-8")


def _is_below_allowed_root(path: PurePosixPath) -> bool:
    return any(
        len(path.parts) > len(root.parts)
        and path.parts[: len(root.parts)] == root.parts
        for root in RUNTIME_SOURCE_ROOTS
    )


def validate_inventory_paths(paths: Iterable[str]) -> tuple[str, ...]:
    """Validate and return one closed, canonical path inventory."""
    checked = tuple(paths)
    if not checked:
        raise ImplementationBaselineError(
            "implementation baseline inventory must contain at least one source"
        )
    if any(not isinstance(path, str) for path in checked):
        raise ImplementationBaselineError(
            "implementation baseline inventory paths must be strings"
        )
    duplicates = sorted(
        (path for path, count in Counter(checked).items() if count > 1),
        key=_path_sort_key,
    )
    if duplicates:
        raise ImplementationBaselineError(
            "implementation baseline inventory contains duplicate paths: "
            f"{duplicates}"
        )
    if checked != tuple(sorted(checked, key=_path_sort_key)):
        raise ImplementationBaselineError(
            "implementation baseline inventory paths must be strictly sorted"
        )

    for path in checked:
        parsed = PurePosixPath(path)
        if (
            not path
            or path != path.strip()
            or "\\" in path
            or "\x00" in path
            or unicodedata.normalize("NFC", path) != path
            or parsed.is_absolute()
            or path != parsed.as_posix()
            or any(part in {"", ".", ".."} for part in parsed.parts)
        ):
            raise ImplementationBaselineError(
                "implementation baseline path is not canonical and relative: "
                f"{path!r}"
            )
        if parsed.suffix != ".java":
            raise ImplementationBaselineError(
                f"implementation baseline source is not Java: {path!r}"
            )
        if not _is_below_allowed_root(parsed):
            raise ImplementationBaselineError(
                "implementation baseline source is outside the fixed runtime "
                f"module roots: {path!r}"
            )
    return checked


def load_inventory_paths(path: Path) -> tuple[str, ...]:
    """Load a canonical LF-terminated inventory from one regular file."""
    try:
        mode = path.lstat().st_mode
    except FileNotFoundError as exc:
        raise ImplementationBaselineError(
            f"implementation baseline inventory is missing: {path}"
        ) from exc
    except OSError as exc:
        raise ImplementationBaselineError(
            f"implementation baseline inventory cannot be inspected: {path}: {exc}"
        ) from exc
    if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
        raise ImplementationBaselineError(
            "implementation baseline inventory must be a non-symlink regular "
            f"file: {path}"
        )
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise ImplementationBaselineError(
            f"implementation baseline inventory cannot be read: {path}: {exc}"
        ) from exc
    if not raw.endswith(b"\n") or b"\r" in raw:
        raise ImplementationBaselineError(
            "implementation baseline inventory must use LF lines and end with "
            "one newline"
        )
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ImplementationBaselineError(
            "implementation baseline inventory is not valid UTF-8"
        ) from exc
    lines = text[:-1].split("\n")
    if any(not line for line in lines):
        raise ImplementationBaselineError(
            "implementation baseline inventory cannot contain blank lines"
        )
    return validate_inventory_paths(lines)


def _directory_entries(directory: Path) -> list[os.DirEntry[str]]:
    try:
        with os.scandir(directory) as iterator:
            return sorted(
                iterator, key=lambda entry: entry.name.encode("utf-8")
            )
    except OSError as exc:
        raise ImplementationBaselineError(
            f"implementation source directory cannot be read: {directory}: {exc}"
        ) from exc


def _discover_directory(
    directory: Path,
    repository_root: Path,
    discovered: list[str],
) -> None:
    for entry in _directory_entries(directory):
        path = Path(entry.path)
        try:
            if entry.is_symlink():
                raise ImplementationBaselineError(
                    f"implementation source tree contains a symlink: {path}"
                )
            mode = entry.stat(follow_symlinks=False).st_mode
        except OSError as exc:
            raise ImplementationBaselineError(
                f"implementation source path cannot be inspected: {path}: {exc}"
            ) from exc
        if stat.S_ISDIR(mode):
            _discover_directory(path, repository_root, discovered)
        elif stat.S_ISREG(mode):
            if path.suffix == ".java":
                discovered.append(path.relative_to(repository_root).as_posix())
        else:
            raise ImplementationBaselineError(
                "implementation source tree contains a non-regular entry: "
                f"{path}"
            )


def _require_real_directory(path: Path, label: str) -> None:
    try:
        mode = path.lstat().st_mode
    except FileNotFoundError as exc:
        raise ImplementationBaselineError(f"{label} is missing: {path}") from exc
    except OSError as exc:
        raise ImplementationBaselineError(
            f"{label} cannot be inspected: {path}: {exc}"
        ) from exc
    if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
        raise ImplementationBaselineError(
            f"{label} must be a non-symlink directory: {path}"
        )


def discover_runtime_java_sources(repository_root: Path) -> tuple[str, ...]:
    """Discover every regular Java source under the six fixed runtime roots."""
    repository_root = repository_root.expanduser().absolute()
    _require_real_directory(repository_root, "repository root")
    discovered: list[str] = []
    for relative_root in RUNTIME_SOURCE_ROOTS:
        source_root = repository_root
        for part in relative_root.parts:
            source_root /= part
            _require_real_directory(source_root, "runtime Java source root")
        _discover_directory(source_root, repository_root, discovered)
    return validate_inventory_paths(sorted(discovered, key=_path_sort_key))


def inventory_mismatch(
    expected: tuple[str, ...],
    actual: tuple[str, ...],
) -> str:
    """Describe a closed-inventory mismatch without truncating its meaning."""
    expected_set = set(expected)
    actual_set = set(actual)
    missing = sorted(expected_set - actual_set, key=_path_sort_key)
    extra = sorted(actual_set - expected_set, key=_path_sort_key)
    order_only = not missing and not extra and expected != actual
    return (
        "implementation baseline inventory does not match runtime Java sources: "
        f"missing={missing}, extra={extra}, orderOnly={order_only}"
    )


def render_inventory(paths: Iterable[str]) -> bytes:
    """Render validated paths in the only accepted on-disk representation."""
    checked = validate_inventory_paths(paths)
    return ("\n".join(checked) + "\n").encode("utf-8")
