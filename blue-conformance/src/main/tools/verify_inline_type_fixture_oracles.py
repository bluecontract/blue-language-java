#!/usr/bin/env python3
"""Verify the inline-type parity fixtures with the independent Python oracle.

This checker never invokes the Java resolver or canonicalizer. It verifies the
frozen Canonical Identity Inputs, Source-derived BlueIds, and provider exact
node identities with ``blue_identity.py`` so regenerated Java output cannot
silently become its own golden oracle.
"""
from __future__ import annotations

from argparse import ArgumentParser
from pathlib import Path
import sys
from typing import Any

sys.dont_write_bytecode = True

import yaml

from blue_identity import direct_blue_id


FIXTURE_IDS = frozenset(
    (
        "R_inline_item_type_reference_identity_parity",
        "R_inline_key_type_reference_identity_parity",
        "R_inline_type_false_convergence_guard",
        "R_inline_type_parent_chain_identity_parity",
        "R_inline_type_reference_identity_parity",
        "R_inline_value_type_reference_identity_parity",
        "R_instance_field_kept",
        "R_nested_collection_type_reference_identity_parity",
        "R_nested_inline_type_reference_identity_parity",
        "R_positional_inline_item_type_identity_parity",
        "R_type_derived_field_removed",
        "R_wholly_inherited_collection_metadata_identity_parity",
    )
)
RESERVED_TYPE_FIELDS = frozenset(("type", "itemType", "keyType", "valueType"))
LIST_CONTROLS = frozenset(("$pos", "$previous", "$replace", "$empty"))
LIMITED_RETRY_FIXTURE_ID = "R_incomplete_cannot_canonicalize"
CORE_TYPE_BLUE_IDS = {
    "Boolean": "AwvXD961fmnmqcSQhjMA7r15HpVh39cefb6ZTyUz2Fm2",
    "Dictionary": "5WQ4tVb4gUUdZa7EfaiUa2XKQwgAurvfYY3ALPauxcAF",
    "Double": "9eWaHYz2vKrFofdHTHAizNNu8xP6QE3WQ5y7DGrGZvyJ",
    "Integer": "E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq",
    "List": "85ip88snCGrgUNdi1rUFqqAxcxwVGKV2g4LjsKoyKmXK",
    "Text": "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC",
}


class OracleFailure(RuntimeError):
    """One frozen fixture claim disagreed with the independent oracle."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise OracleFailure(message)


def fixture_index(root: Path) -> dict[str, tuple[Path, dict[str, Any]]]:
    require(root.is_dir(), f"fixture root does not exist: {root}")
    result: dict[str, tuple[Path, dict[str, Any]]] = {}
    for path in sorted(root.rglob("*.yaml")):
        require(not path.is_symlink(), f"fixture package contains symlink: {path}")
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or not isinstance(value.get("id"), str):
            continue
        fixture_id = value["id"]
        require(bool(fixture_id), f"empty fixture id in {path}")
        require(
            path.stem == fixture_id,
            f"fixture id does not match its file name: {path} -> {fixture_id!r}",
        )
        require(fixture_id not in result, f"duplicate fixture id: {fixture_id}")
        result[fixture_id] = (path, value)
    return result


def verify_canonical_shape(value: Any, path: str = "") -> None:
    if isinstance(value, list):
        for index, child in enumerate(value):
            verify_canonical_shape(child, f"{path}/{index}")
        return
    if not isinstance(value, dict):
        return
    for key, child in value.items():
        child_path = f"{path}/{key}"
        require(key not in LIST_CONTROLS,
                f"list control leaked into Canonical Identity Input at {child_path}")
        if key in RESERVED_TYPE_FIELDS:
            require(
                isinstance(child, dict)
                and set(child) == {"blueId"}
                and isinstance(child.get("blueId"), str)
                and bool(child["blueId"]),
                f"reserved type position is not a non-null pure reference at {child_path}",
            )
        else:
            verify_canonical_shape(child, child_path)


def canonical_type_blue_id(value: Any) -> str:
    """Independently identify one authored type declaration.

    The selected fixtures deliberately use type declarations whose own
    Canonical Identity Input differs from their authored form only by recursive
    canonicalization of reserved type metadata. Keeping this small oracle
    explicit prevents the Java implementation from generating both sides of a
    supposedly independent parity assertion.
    """
    if isinstance(value, str):
        require(value in CORE_TYPE_BLUE_IDS,
                f"unsupported type alias in independent oracle: {value!r}")
        return CORE_TYPE_BLUE_IDS[value]
    if (isinstance(value, dict)
            and set(value) == {"blueId"}
            and isinstance(value.get("blueId"), str)
            and bool(value["blueId"])):
        return value["blueId"]
    require(isinstance(value, dict),
            f"inline type declaration is not an object: {value!r}")
    return direct_blue_id(canonicalize_authored_type(value))


def canonicalize_authored_type(value: Any) -> Any:
    """Build the frozen fixture type input without calling Java resolution."""
    if isinstance(value, list):
        return [canonicalize_authored_type(child) for child in value]
    if not isinstance(value, dict):
        return value
    canonical: dict[str, Any] = {}
    for key, child in value.items():
        if key in RESERVED_TYPE_FIELDS:
            canonical[key] = {"blueId": canonical_type_blue_id(child)}
        else:
            canonical[key] = canonicalize_authored_type(child)
    return canonical


def verify_paired_type_positions(
        fixture_id: str,
        source: Any,
        equivalent: Any,
        expected: Any,
        path: str = "") -> None:
    """Tie each expected type reference to both independently authored forms."""
    if not isinstance(expected, dict):
        return
    require(isinstance(source, dict) and isinstance(equivalent, dict),
            f"{fixture_id} expected object has no paired Source objects at {path or '/'}")
    for key, expected_child in expected.items():
        child_path = f"{path}/{key}"
        if key in RESERVED_TYPE_FIELDS:
            require(key in source and key in equivalent,
                    f"{fixture_id} lacks paired type metadata at {child_path}")
            expected_id = expected_child["blueId"]
            inline_id = canonical_type_blue_id(source[key])
            reference_id = canonical_type_blue_id(equivalent[key])
            require(
                inline_id == reference_id == expected_id,
                f"{fixture_id} independent type identity mismatch at {child_path}: "
                f"inline={inline_id!r}, reference={reference_id!r}, "
                f"expected={expected_id!r}",
            )
        elif isinstance(expected_child, dict):
            require(key in source and key in equivalent,
                    f"{fixture_id} lacks paired object content at {child_path}")
            verify_paired_type_positions(
                fixture_id,
                source[key],
                equivalent[key],
                expected_child,
                child_path,
            )


def pure_reference_id(value: Any) -> str | None:
    if (isinstance(value, dict)
            and set(value) == {"blueId"}
            and isinstance(value.get("blueId"), str)
            and bool(value["blueId"])):
        return value["blueId"]
    return None


def reserved_reference_ids(value: Any) -> set[str]:
    result: set[str] = set()
    if isinstance(value, list):
        for child in value:
            result.update(reserved_reference_ids(child))
    elif isinstance(value, dict):
        for key, child in value.items():
            if key in RESERVED_TYPE_FIELDS:
                reference_id = pure_reference_id(child)
                if reference_id is not None:
                    result.add(reference_id)
            result.update(reserved_reference_ids(child))
    return result


def paired_reference_ids(source: Any, equivalent: Any) -> set[str]:
    result: set[str] = set()
    if isinstance(source, list) and isinstance(equivalent, list):
        for source_child, equivalent_child in zip(source, equivalent):
            result.update(paired_reference_ids(source_child, equivalent_child))
    elif isinstance(source, dict) and isinstance(equivalent, dict):
        for key in set(source) & set(equivalent):
            source_child = source[key]
            equivalent_child = equivalent[key]
            if key in RESERVED_TYPE_FIELDS:
                source_id = pure_reference_id(source_child)
                equivalent_id = pure_reference_id(equivalent_child)
                if source_id is not None and equivalent_id is None:
                    result.add(source_id)
                if equivalent_id is not None and source_id is None:
                    result.add(equivalent_id)
            result.update(paired_reference_ids(source_child, equivalent_child))
    return result


def verify_provider_nodes(
    fixture_id: str, fixture: dict[str, Any]
) -> set[str]:
    provider = fixture.get("provider", [])
    require(isinstance(provider, list),
            f"{fixture_id} provider must be a list")
    verified_ids: set[str] = set()
    referenced_ids: set[str] = set()
    for index, entry in enumerate(provider):
        require(isinstance(entry, dict),
                f"{fixture_id} provider[{index}] must be a mapping")
        require("node" in entry,
                f"{fixture_id} provider[{index}] lacks an exact node")
        requested = entry.get("requestedBlueId")
        require(isinstance(requested, str) and bool(requested),
                f"{fixture_id} provider[{index}] lacks a requested BlueId")
        require(requested not in verified_ids,
                f"{fixture_id} has duplicate provider identity {requested}")
        actual = direct_blue_id(entry["node"])
        require(
            actual == requested,
            f"{fixture_id} provider[{index}] identity mismatch: "
            f"declared={requested!r}, oracle={actual!r}",
        )
        verified_ids.add(requested)
        referenced_ids.update(reserved_reference_ids(entry["node"]))
    unresolved = referenced_ids - verified_ids - set(CORE_TYPE_BLUE_IDS.values())
    require(not unresolved,
            f"{fixture_id} provider has unresolved exact type references: {sorted(unresolved)}")
    return verified_ids


def verify_false_convergence(fixture: dict[str, Any]) -> None:
    source = fixture["source"]
    different = fixture["alsoDifferentFrom"]
    source_type_id = direct_blue_id(source["type"])
    different_type_id = direct_blue_id(different["type"])
    expected = fixture["expectedCanonicalOverlay"]
    require(expected["type"] == {"blueId": source_type_id},
            "false-convergence fixture does not retain the first exact type identity")
    alternate_canonical = dict(different)
    alternate_canonical["type"] = {"blueId": different_type_id}
    first_parent_id = direct_blue_id(expected)
    alternate_parent_id = direct_blue_id(alternate_canonical)
    require(source_type_id != different_type_id,
            "false-convergence fixture types are not distinct")
    require(first_parent_id != alternate_parent_id,
            "false-convergence fixture parent identities are not distinct")


def verify_fixture(fixture_id: str, fixture: dict[str, Any]) -> None:
    require(fixture.get("operation") == "canonicalize",
            f"{fixture_id} is not a canonicalization fixture")
    expected = fixture.get("expectedCanonicalOverlay")
    require(isinstance(expected, dict),
            f"{fixture_id} lacks an explicit expected Canonical Identity Input")
    verify_canonical_shape(expected)
    oracle_parent_id = direct_blue_id(expected)
    require(
        fixture.get("expectedNodeBlueId") == oracle_parent_id,
        f"{fixture_id} Source-derived BlueId mismatch: "
        f"declared={fixture.get('expectedNodeBlueId')!r}, oracle={oracle_parent_id!r}",
    )
    provider_ids = verify_provider_nodes(fixture_id, fixture)
    if fixture_id == "R_inline_type_false_convergence_guard":
        verify_false_convergence(fixture)
    else:
        require("alsoEquivalentTo" in fixture,
                f"{fixture_id} lacks its independent equivalent Source form")
        source = fixture["source"]
        equivalent = fixture["alsoEquivalentTo"]
        require(
            canonicalize_authored_type(source)
            == canonicalize_authored_type(equivalent),
            f"{fixture_id} paired Source forms differ beyond type representation",
        )
        unresolved = (
            paired_reference_ids(source, equivalent)
            - provider_ids
            - set(CORE_TYPE_BLUE_IDS.values())
        )
        require(
            not unresolved,
            f"{fixture_id} paired reference form lacks exact provider nodes: "
            f"{sorted(unresolved)}",
        )
        verify_paired_type_positions(
            fixture_id,
            source,
            equivalent,
            expected,
        )
        if fixture_id == "R_positional_inline_item_type_identity_parity":
            expected_items = expected.get("items")
            require(
                isinstance(expected_items, list),
                "positional parity fixture lacks its final item list",
            )
            expected_values = [
                item.get("value") if isinstance(item, dict) else item
                for item in expected_items
            ]
            inherited_item_type_id = canonical_type_blue_id(
                source["type"]["itemType"]
            )
            require(
                expected_values == fixture.get("expectedCanonicalItems")
                and all(
                    isinstance(item, dict)
                    and item.get("type") == {"blueId": inherited_item_type_id}
                    for item in expected_items
                )
                and fixture.get("expectedCanonicalContainsControls") is False,
                "positional parity fixture does not freeze its final control-free payload",
            )
        else:
            require(
                canonicalize_authored_type(source) == expected,
                f"{fixture_id} expected canonical input drifted from its paired Source",
            )


def main() -> int:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-root", type=Path, required=True)
    arguments = parser.parse_args()
    index = fixture_index(arguments.fixture_root)
    missing = sorted(FIXTURE_IDS - set(index))
    require(not missing, f"missing inline-type oracle fixtures: {missing}")
    for fixture_id in sorted(FIXTURE_IDS):
        verify_fixture(fixture_id, index[fixture_id][1])
    limited = index.get(LIMITED_RETRY_FIXTURE_ID)
    require(limited is not None,
            f"missing limited retry fixture: {LIMITED_RETRY_FIXTURE_ID}")
    limited_expected = limited[1].get("expectedCanonicalOverlay")
    require(isinstance(limited_expected, dict),
            "limited retry fixture lacks its recovered canonical input")
    verify_canonical_shape(limited_expected)
    require(
        direct_blue_id(limited_expected) == limited[1].get("expectedNodeBlueId"),
        "limited retry fixture recovered Source-derived BlueId mismatch",
    )
    print(
        "INLINE_TYPE_FIXTURE_ORACLES_OK "
        f"fixtures={len(FIXTURE_IDS)} limitedRetry=verified "
        "pairedTypeIdentities=verified providerAndParentIdentities=verified"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KeyError, TypeError, ValueError, OracleFailure) as failure:
        print(f"INLINE_TYPE_FIXTURE_ORACLES_FAILED: {failure}", file=sys.stderr)
        raise SystemExit(1)
