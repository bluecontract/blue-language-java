#!/usr/bin/env python3
"""Write or check the closed runtime Java implementation inventory."""
from __future__ import annotations

from argparse import ArgumentParser
from pathlib import Path
import os
import stat
import sys
import tempfile

from implementation_baseline_inventory import (
    IMPLEMENTATION_BASELINE_INVENTORY_NAME,
    ImplementationBaselineError,
    discover_runtime_java_sources,
    inventory_mismatch,
    load_inventory_paths,
    render_inventory,
)


TOOLS_ROOT = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_ROOT.parents[3]
DEFAULT_INVENTORY = TOOLS_ROOT / IMPLEMENTATION_BASELINE_INVENTORY_NAME


def write_inventory(path: Path, content: bytes) -> None:
    """Atomically publish canonical inventory bytes without following links."""
    if path.is_symlink():
        raise ImplementationBaselineError(
            f"refusing to replace symlink inventory: {path}"
        )
    if path.exists():
        try:
            mode = path.lstat().st_mode
        except OSError as exc:
            raise ImplementationBaselineError(
                f"inventory cannot be inspected: {path}: {exc}"
            ) from exc
        if not stat.S_ISREG(mode):
            raise ImplementationBaselineError(
                f"inventory is not a regular file: {path}"
            )
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        dir=path.parent,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def check_inventory(
    repository_root: Path,
    inventory_path: Path,
) -> tuple[str, ...]:
    """Return exact discovered paths or fail if the checked-in list drifts."""
    expected = load_inventory_paths(inventory_path)
    actual = discover_runtime_java_sources(repository_root)
    if expected != actual:
        raise ImplementationBaselineError(inventory_mismatch(expected, actual))
    return actual


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repository-root",
        type=Path,
        default=REPOSITORY_ROOT,
        help=f"Repository root (default: {REPOSITORY_ROOT})",
    )
    parser.add_argument(
        "--inventory",
        type=Path,
        default=DEFAULT_INVENTORY,
        help=f"Inventory path (default: {DEFAULT_INVENTORY})",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--check",
        action="store_true",
        help="Require exact inventory/source-tree parity (default)",
    )
    mode.add_argument(
        "--write",
        action="store_true",
        help="Atomically replace the inventory from exact source discovery",
    )
    args = parser.parse_args()

    repository_root = args.repository_root.expanduser().resolve()
    inventory_path = args.inventory.expanduser().absolute()
    if args.write:
        discovered = discover_runtime_java_sources(repository_root)
        write_inventory(inventory_path, render_inventory(discovered))
        checked = check_inventory(repository_root, inventory_path)
        print(f"IMPLEMENTATION_BASELINE_INVENTORY_WRITTEN files={len(checked)}")
    else:
        checked = check_inventory(repository_root, inventory_path)
        print(f"IMPLEMENTATION_BASELINE_INVENTORY_OK files={len(checked)}")


if __name__ == "__main__":
    try:
        main()
    except ImplementationBaselineError as exc:
        print(f"IMPLEMENTATION_BASELINE_INVENTORY_INVALID: {exc}", file=sys.stderr)
        raise SystemExit(1)
