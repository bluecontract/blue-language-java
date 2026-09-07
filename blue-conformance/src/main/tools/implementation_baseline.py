#!/usr/bin/env python3
"""Closed Java source ownership for identity-bearing release baselines.

The adjacent path inventory is the canonical, reviewable declaration of every
Java source in the six runtime modules published downstream.  Files in the
Language model and core modules form a conservative transitive source closure
for both cyclic identity roles.  The Contracts processor role covers the full
six-module runtime source closure.
"""
from __future__ import annotations

from pathlib import Path

from implementation_baseline_inventory import (
    IMPLEMENTATION_BASELINE_INVENTORY_NAME,
    ImplementationBaselineError,
    RUNTIME_SOURCE_ROOTS,
    discover_runtime_java_sources,
    inventory_mismatch,
    load_inventory_paths,
)


CYCLIC_FINALIZER = "cyclic-finalizer"
CYCLIC_PROOF_VERIFIER = "cyclic-proof-verifier"
CONTRACTS_PROCESSOR = "contracts-processor"
_KNOWN_ROLES = frozenset(
    {CYCLIC_FINALIZER, CYCLIC_PROOF_VERIFIER, CONTRACTS_PROCESSOR}
)
_CYCLIC_TRANSITIVE_MODULES = frozenset(
    {"blue-language-core", "blue-language-model"}
)
INVENTORY_PATH = (
    Path(__file__).resolve().with_name(IMPLEMENTATION_BASELINE_INVENTORY_NAME)
)


IMPLEMENTATION_BASELINE_SOURCE_PATHS = load_inventory_paths(INVENTORY_PATH)


def source_paths_for_role(role: str) -> tuple[str, ...]:
    """Return the canonical path projection for one declared baseline role."""
    if role not in _KNOWN_ROLES:
        raise ImplementationBaselineError(
            f"unknown implementation baseline role: {role!r}"
        )
    if role == CONTRACTS_PROCESSOR:
        return IMPLEMENTATION_BASELINE_SOURCE_PATHS
    return tuple(
        path
        for path in IMPLEMENTATION_BASELINE_SOURCE_PATHS
        if path.split("/", 1)[0] in _CYCLIC_TRANSITIVE_MODULES
    )


def require_implementation_baseline_files(
    repository_root: Path,
) -> tuple[tuple[str, Path], ...]:
    """Require inventory/discovery parity and return every owned source path.

    Discovery always starts from the six fixed roots.  It never uses the
    checked-in inventory to decide what to scan, so a newly added Java source
    fails closed until the inventory is deliberately regenerated and reviewed.
    """
    inventory_paths = load_inventory_paths(INVENTORY_PATH)
    if inventory_paths != IMPLEMENTATION_BASELINE_SOURCE_PATHS:
        raise ImplementationBaselineError(
            "implementation baseline inventory changed after module load"
        )
    discovered_paths = discover_runtime_java_sources(repository_root)
    if inventory_paths != discovered_paths:
        raise ImplementationBaselineError(
            inventory_mismatch(inventory_paths, discovered_paths)
        )
    root = repository_root.expanduser().resolve()
    return tuple((relative, root / relative) for relative in inventory_paths)


__all__ = (
    "CONTRACTS_PROCESSOR",
    "CYCLIC_FINALIZER",
    "CYCLIC_PROOF_VERIFIER",
    "IMPLEMENTATION_BASELINE_SOURCE_PATHS",
    "ImplementationBaselineError",
    "RUNTIME_SOURCE_ROOTS",
    "require_implementation_baseline_files",
    "source_paths_for_role",
)
