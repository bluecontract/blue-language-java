#!/usr/bin/env python3
"""Focused Blue Language 1.0 identity oracle for fixture generation.

This module intentionally supports the JSON/YAML-compatible Blue value shapes
used by this package. It implements the released direct object/scalar/list
identity formulas and the released cyclic-set algorithm from Language §15.
It is not a replacement for blue-language-java.
"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from decimal import Decimal
import hashlib
import math
from typing import Any, Callable, Iterable, Mapping, Sequence

from jcs import dumps as jcs_dumps

TEXT_TYPE_BLUE_ID = "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC"
DOUBLE_TYPE_BLUE_ID = "9eWaHYz2vKrFofdHTHAizNNu8xP6QE3WQ5y7DGrGZvyJ"
INTEGER_TYPE_BLUE_ID = "E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq"
BOOLEAN_TYPE_BLUE_ID = "AwvXD961fmnmqcSQhjMA7r15HpVh39cefb6ZTyUz2Fm2"
ZERO_BLUEID = "0" * 44
BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
LITERAL_FIELDS = frozenset(("name", "description", "value"))
SAFE_INTEGER_MIN = -(2**53) + 1
SAFE_INTEGER_MAX = 2**53 - 1
SCHEMA_SCALAR_FIELDS = frozenset(
    (
        "required",
        "minLength",
        "maxLength",
        "minimum",
        "maximum",
        "exclusiveMinimum",
        "exclusiveMaximum",
        "multipleOf",
        "minItems",
        "maxItems",
        "uniqueItems",
        "minFields",
        "maxFields",
    )
)


def _base58(data: bytes) -> str:
    leading = 0
    for b in data:
        if b != 0:
            break
        leading += 1
    number = int.from_bytes(data, "big")
    chars: list[str] = []
    while number:
        number, rem = divmod(number, 58)
        chars.append(BASE58_ALPHABET[rem])
    body = "".join(reversed(chars))
    return BASE58_ALPHABET[0] * leading + body


def _canonical_json(value: Any) -> bytes:
    return jcs_dumps(value)


def canonical_json_bytes(value: Any) -> bytes:
    """Return the exact canonical bytes shared by package identity oracles."""
    return _canonical_json(value)


def canonical_sha256_blue_id(value: Any) -> str:
    return _base58(hashlib.sha256(_canonical_json(value)).digest())


def _scalar_node(value: Any) -> Mapping[str, Any]:
    if isinstance(value, bool):
        type_id = BOOLEAN_TYPE_BLUE_ID
        canonical = value
    elif isinstance(value, int) and not isinstance(value, bool):
        type_id = INTEGER_TYPE_BLUE_ID
        canonical = (
            value
            if SAFE_INTEGER_MIN <= value <= SAFE_INTEGER_MAX
            else str(value)
        )
    elif isinstance(value, Decimal):
        type_id = DOUBLE_TYPE_BLUE_ID
        canonical = _finite_double(value)
    elif isinstance(value, float):
        type_id = DOUBLE_TYPE_BLUE_ID
        canonical = _finite_double(value)
    elif isinstance(value, str):
        type_id = TEXT_TYPE_BLUE_ID
        canonical = value
    else:
        raise TypeError(f"Unsupported Blue scalar: {type(value)!r}")
    return {"type": {"blueId": type_id}, "value": canonical}


def _finite_double(value: Decimal | float | int) -> float:
    canonical = float(value)
    if not math.isfinite(canonical):
        raise ValueError(f"Non-finite Double is not valid BlueId input: {value!r}")
    return canonical


def _canonical_scalar_payload(value: Any, type_blue_id: str | None = None) -> Any:
    if isinstance(value, bool) or isinstance(value, str):
        return value
    if isinstance(value, int):
        if type_blue_id == DOUBLE_TYPE_BLUE_ID:
            return _finite_double(value)
        return (
            value
            if SAFE_INTEGER_MIN <= value <= SAFE_INTEGER_MAX
            else str(value)
        )
    if isinstance(value, (Decimal, float)):
        return _finite_double(value)
    raise TypeError(f"Unsupported Blue scalar payload: {type(value)!r}")


def _pure_reference_blue_id(value: Any) -> str | None:
    if not isinstance(value, dict) or set(value) != {"blueId"}:
        return None
    blue_id = value["blueId"]
    if not isinstance(blue_id, str):
        raise TypeError("blueId must be text")
    return blue_id


def _empty_list_placeholder(value: Any) -> bool:
    return isinstance(value, dict) and value == {"$empty": True}


def _validate_empty_list_placeholder(value: Mapping[str, Any]) -> None:
    if "$empty" in value and not _empty_list_placeholder(value):
        raise ValueError(
            '"$empty" list placeholder must have exact shape '
            '{"$empty": true}'
        )


def _clean_direct_object(value: Mapping[str, Any]) -> dict[str, Any]:
    """Recursively omit null fields while preserving exact empty objects."""
    cleaned: dict[str, Any] = {}
    for key, child in value.items():
        if child is None:
            continue
        if isinstance(child, dict):
            normalized_child = _clean_direct_object(child)
            cleaned[key] = normalized_child
        elif isinstance(child, list):
            cleaned[key] = _clean_direct_list(child)
        else:
            cleaned[key] = child
    return cleaned


def _clean_direct_list(value: Sequence[Any]) -> list[Any]:
    """Clean list contents while preserving their exact positional shape."""
    cleaned: list[Any] = []
    for item in value:
        if item is None:
            raise ValueError(
                'Use {"$empty": true} for null list placeholders'
            )
        if isinstance(item, dict):
            _validate_empty_list_placeholder(item)
            normalized_item = _clean_direct_object(item)
            cleaned.append(normalized_item)
        elif isinstance(item, list):
            cleaned.append(_clean_direct_list(item))
        else:
            cleaned.append(item)
    return cleaned


def _store_normalized_object_field(
        target: dict[str, Any], key: str, value: Any) -> None:
    """Store one present field, including an exact empty-object value."""
    target[key] = value


def _normalized_schema_input(value: Any) -> Any:
    if not isinstance(value, dict):
        raise TypeError("schema must be an object")
    cleaned = {key: child for key, child in value.items() if child is not None}
    if _pure_reference_blue_id(cleaned) is not None:
        return deepcopy(cleaned)
    normalized: dict[str, Any] = {}
    for key, child in cleaned.items():
        if key in SCHEMA_SCALAR_FIELDS:
            normalized_child = (
                _canonical_scalar_payload(child)
                if not isinstance(child, (dict, list))
                else _normalized_preliminary_input(child)
            )
            _store_normalized_object_field(
                normalized, key, normalized_child
            )
        elif key == "enum":
            if not isinstance(child, list):
                raise TypeError("schema enum must be a list")
            normalized_items: list[Any] = []
            for item in child:
                if item is None:
                    raise ValueError(
                        'Use {"$empty": true} for null list placeholders'
                    )
                if isinstance(item, dict):
                    _validate_empty_list_placeholder(item)
                normalized_item = (
                    _canonical_scalar_payload(item)
                    if not isinstance(item, (dict, list))
                    else _normalized_preliminary_input(item)
                )
                normalized_items.append(normalized_item)
            normalized[key] = normalized_items
        else:
            _store_normalized_object_field(
                normalized, key, _normalized_preliminary_input(child)
            )
    return normalized


def _normalized_preliminary_input(value: Any) -> Any:
    """Project one fixture-subset Blue value to normalized BlueId Input."""
    if isinstance(value, (str, int, float, Decimal, bool)):
        return dict(_scalar_node(value))
    if isinstance(value, list):
        normalized_items: list[Any] = []
        for item in value:
            if item is None:
                raise ValueError('Use {"$empty": true} for null list placeholders')
            if isinstance(item, dict):
                _validate_empty_list_placeholder(item)
            if _empty_list_placeholder(item):
                normalized_items.append({"$empty": True})
            else:
                normalized_item = _normalized_preliminary_input(item)
                normalized_items.append(normalized_item)
        return normalized_items
    if not isinstance(value, dict):
        raise TypeError(f"Unsupported Blue value: {type(value)!r}")

    cleaned = {key: child for key, child in value.items() if child is not None}
    if _pure_reference_blue_id(cleaned) is not None:
        return deepcopy(cleaned)
    if cleaned == {"$empty": True}:
        return {"$empty": True}
    if set(cleaned) == {"value"}:
        return dict(_scalar_node(cleaned["value"]))
    if set(cleaned) == {"items"} and isinstance(cleaned["items"], list):
        return _normalized_preliminary_input(cleaned["items"])

    normalized: dict[str, Any] = {}
    explicit_type = _pure_reference_blue_id(cleaned.get("type"))
    for key, child in cleaned.items():
        if key in ("name", "description", "mergePolicy"):
            normalized[key] = _canonical_scalar_payload(child)
        elif key == "value":
            if explicit_type is None:
                scalar = _scalar_node(child)
                normalized.setdefault("type", deepcopy(scalar["type"]))
                normalized[key] = scalar["value"]
            else:
                normalized[key] = _canonical_scalar_payload(
                    child, explicit_type
                )
        elif key == "schema":
            _store_normalized_object_field(
                normalized, key, _normalized_schema_input(child)
            )
        elif key == "items":
            _store_normalized_object_field(
                normalized, key, _normalized_preliminary_input(child)
            )
        else:
            _store_normalized_object_field(
                normalized, key, _normalized_preliminary_input(child)
            )
    return normalized


def normalized_preliminary_input_bytes(value: Any) -> bytes:
    """Return RFC 8785 bytes for complete normalized preliminary BlueId Input.

    ``value`` is the preliminary fixture-subset value after each direct
    internal cyclic reference has been replaced by ``ZERO_BLUEID``. Source
    scalar/list sugar, null object fields, and map insertion order therefore
    cannot leak into the cyclic tie-break or its semantic gas accounting.
    """
    return _canonical_json(_normalized_preliminary_input(value))


def _list_seed() -> str:
    return canonical_sha256_blue_id({"$list": "empty"})


def _list_append(previous: str, element: str) -> str:
    return canonical_sha256_blue_id(
        {"$listCons": {"elem": {"blueId": element}, "prev": {"blueId": previous}}}
    )


def direct_blue_id(value: Any, *, allow_cyclic_placeholders: bool = False) -> str:
    """Calculate direct identity for the closed fixture value subset."""
    if value is None:
        raise ValueError("Root null is not valid BlueId input")
    if isinstance(value, (str, int, float, Decimal, bool)):
        return _object_blue_id(_scalar_node(value), allow_cyclic_placeholders)
    if isinstance(value, list):
        acc = _list_seed()
        for item in _clean_direct_list(value):
            if _empty_list_placeholder(item):
                empty_id = canonical_sha256_blue_id(
                    {"$empty": {"blueId": canonical_sha256_blue_id(True)}}
                )
                acc = _list_append(acc, empty_id)
            else:
                acc = _list_append(
                    acc,
                    direct_blue_id(item, allow_cyclic_placeholders=allow_cyclic_placeholders),
                )
        return acc
    if isinstance(value, dict):
        return _object_blue_id(value, allow_cyclic_placeholders)
    raise TypeError(f"Unsupported Blue value: {type(value)!r}")


def _object_blue_id(value: Mapping[str, Any], allow_cyclic_placeholders: bool) -> str:
    cleaned = _clean_direct_object(value)
    if set(cleaned) == {"blueId"}:
        blue_id = cleaned["blueId"]
        if not isinstance(blue_id, str):
            raise TypeError("blueId must be text")
        if not allow_cyclic_placeholders and (
            blue_id == "this" or blue_id.startswith("this#") or blue_id == ZERO_BLUEID
        ):
            raise ValueError("Cyclic placeholders require cyclic API")
        return blue_id
    contributions: dict[str, Any] = {}
    explicit_type = _pure_reference_blue_id(cleaned.get("type"))
    for key in sorted(cleaned):
        child = cleaned[key]
        if key in LITERAL_FIELDS:
            contributions[key] = (
                _canonical_scalar_payload(child, explicit_type)
                if key == "value"
                else child
            )
        else:
            contributions[key] = {
                "blueId": direct_blue_id(
                    child, allow_cyclic_placeholders=allow_cyclic_placeholders
                )
            }
    return canonical_sha256_blue_id(contributions)


def _rewrite_refs(value: Any, rewrite: Callable[[int], str]) -> Any:
    if isinstance(value, list):
        return [_rewrite_refs(v, rewrite) for v in value]
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for key, child in value.items():
            if key == "blueId" and isinstance(child, str) and child.startswith("this#"):
                suffix = child[5:]
                if not suffix.isdigit():
                    raise ValueError(f"Invalid cyclic placeholder {child!r}")
                out[key] = rewrite(int(suffix))
            else:
                out[key] = _rewrite_refs(child, rewrite)
        return out
    return value


def _find_refs(value: Any, refs: list[int]) -> None:
    if isinstance(value, list):
        for item in value:
            _find_refs(item, refs)
    elif isinstance(value, dict):
        for key, child in value.items():
            if key == "blueId" and isinstance(child, str) and child.startswith("this#"):
                suffix = child[5:]
                if not suffix.isdigit():
                    raise ValueError(f"Invalid cyclic placeholder {child!r}")
                refs.append(int(suffix))
            else:
                _find_refs(child, refs)


@dataclass(frozen=True)
class CyclicMemberOracle:
    source_index: int
    sorted_index: int
    preliminary_blue_id: str
    member_blue_id: str
    source_document: Any
    sorted_document: Any


@dataclass(frozen=True)
class CyclicSetOracle:
    master_blue_id: str
    members: tuple[CyclicMemberOracle, ...]
    sorted_source_indices: tuple[int, ...]

    def member_ids_in_source_order(self) -> tuple[str, ...]:
        return tuple(m.member_blue_id for m in sorted(self.members, key=lambda x: x.source_index))


@dataclass(frozen=True)
class DirectIdentityFacts:
    blue_id: str
    kind: str
    direct_member_count: int
    canonical_input_utf8_bytes: int
    child_values: tuple[Any, ...]
    list_length: int
    pure_reference: bool


def direct_identity_facts(value: Any, *, allow_cyclic_placeholders: bool = False) -> DirectIdentityFacts:
    """Return the exact direct-identity accounting inputs for the fixture subset."""
    if isinstance(value, (str, int, float, Decimal, bool)):
        value = _scalar_node(value)
    if isinstance(value, list):
        return DirectIdentityFacts(
            blue_id=direct_blue_id(value, allow_cyclic_placeholders=allow_cyclic_placeholders),
            kind="list",
            direct_member_count=0,
            canonical_input_utf8_bytes=0,
            child_values=tuple(value),
            list_length=len(value),
            pure_reference=False,
        )
    if not isinstance(value, dict):
        raise TypeError(f"Unsupported Blue value: {type(value)!r}")
    cleaned = {k: v for k, v in value.items() if v is not None}
    if set(cleaned) == {"blueId"}:
        return DirectIdentityFacts(
            blue_id=direct_blue_id(cleaned, allow_cyclic_placeholders=allow_cyclic_placeholders),
            kind="reference",
            direct_member_count=0,
            canonical_input_utf8_bytes=0,
            child_values=(),
            list_length=0,
            pure_reference=True,
        )
    contributions: dict[str, Any] = {}
    children: list[Any] = []
    explicit_type = _pure_reference_blue_id(cleaned.get("type"))
    for key in sorted(cleaned):
        child = cleaned[key]
        if key in LITERAL_FIELDS:
            contributions[key] = (
                _canonical_scalar_payload(child, explicit_type)
                if key == "value"
                else child
            )
        else:
            contributions[key] = {
                "blueId": direct_blue_id(child, allow_cyclic_placeholders=allow_cyclic_placeholders)
            }
            children.append(child)
    return DirectIdentityFacts(
        blue_id=canonical_sha256_blue_id(contributions),
        kind="object",
        direct_member_count=len(contributions),
        canonical_input_utf8_bytes=len(_canonical_json(contributions)),
        child_values=tuple(children),
        list_length=0,
        pure_reference=False,
    )


def _order_preliminary_members(
    preliminary: Sequence[tuple[int, str, Any, bytes]],
) -> list[tuple[int, str, Any, bytes]]:
    ordered = sorted(preliminary, key=lambda member: (member[1], member[3]))
    for previous, current in zip(ordered, ordered[1:]):
        if previous[1] == current[1] and previous[3] == current[3]:
            raise ValueError(
                "Duplicate preliminary cyclic BlueId and normalized input bytes"
            )
    return ordered


def cyclic_set_oracle(documents: Sequence[Any]) -> CyclicSetOracle:
    if not documents:
        raise ValueError("Cyclic set must not be empty")
    refs: list[int] = []
    for doc in documents:
        _find_refs(doc, refs)
    if not refs:
        raise ValueError("Cyclic set requires at least one this#index reference")
    if any(index < 0 or index >= len(documents) for index in refs):
        raise ValueError("Cyclic reference points outside set")

    preliminary: list[tuple[int, str, Any, bytes]] = []
    for source_index, doc in enumerate(documents):
        zeroed = _rewrite_refs(deepcopy(doc), lambda _target: ZERO_BLUEID)
        preliminary.append(
            (
                source_index,
                direct_blue_id(zeroed, allow_cyclic_placeholders=True),
                zeroed,
                normalized_preliminary_input_bytes(zeroed),
            )
        )
    ordered = _order_preliminary_members(preliminary)
    sorted_index_by_source = {
        source: index
        for index, (source, _pid, _zeroed, _bytes) in enumerate(ordered)
    }

    sorted_docs: list[Any] = []
    for source_index, _pid, _zeroed, _bytes in ordered:
        rewritten = _rewrite_refs(
            deepcopy(documents[source_index]),
            lambda target: f"this#{sorted_index_by_source[target]}",
        )
        sorted_docs.append(rewritten)
    master = direct_blue_id(sorted_docs, allow_cyclic_placeholders=True)

    members: list[CyclicMemberOracle] = []
    sorted_doc_by_source = {
        source: sorted_docs[index]
        for index, (source, _pid, _zeroed, _bytes) in enumerate(ordered)
    }
    prelim_by_source = {
        source: pid for source, pid, _zeroed, _bytes in preliminary
    }
    for source_index, doc in enumerate(documents):
        sorted_index = sorted_index_by_source[source_index]
        members.append(
            CyclicMemberOracle(
                source_index=source_index,
                sorted_index=sorted_index,
                preliminary_blue_id=prelim_by_source[source_index],
                member_blue_id=f"{master}#{sorted_index}",
                source_document=deepcopy(doc),
                sorted_document=deepcopy(sorted_doc_by_source[source_index]),
            )
        )
    return CyclicSetOracle(
        master,
        tuple(members),
        tuple(source for source, _pid, _zeroed, _bytes in ordered),
    )


def materialize_cyclic_members(documents: Sequence[Any], oracle: CyclicSetOracle) -> list[Any]:
    ids = oracle.member_ids_in_source_order()
    return [_rewrite_refs(deepcopy(doc), lambda target: ids[target]) for doc in documents]


def _contains_cyclic_reference(value: Any) -> bool:
    if isinstance(value, list):
        return any(_contains_cyclic_reference(child) for child in value)
    if isinstance(value, dict):
        return any(
            key == "blueId" and isinstance(child, str) and child.startswith("this#")
            or _contains_cyclic_reference(child)
            for key, child in value.items()
        )
    return False


def _collapse_cyclic_limit_member(value: Any) -> Any:
    if isinstance(value, list):
        return [
            _collapse_cyclic_limit_member(child)
            if _contains_cyclic_reference(child)
            else {"blueId": direct_blue_id(child, allow_cyclic_placeholders=True)}
            for child in value
        ]
    if isinstance(value, dict):
        if set(value) == {"blueId"}:
            return deepcopy(value)
        result: dict[str, Any] = {}
        for key, child in value.items():
            if key in LITERAL_FIELDS:
                # Literal fields participate in their containing object's direct
                # helper map as their complete value, not by child BlueId.
                result[key] = deepcopy(child)
            elif _contains_cyclic_reference(child):
                result[key] = _collapse_cyclic_limit_member(child)
            else:
                result[key] = {
                    "blueId": direct_blue_id(child, allow_cyclic_placeholders=True)
                }
        return result
    return deepcopy(value)


def cyclic_canonical_limit_form(oracle: CyclicSetOracle) -> list[Any]:
    """Representation-invariant Contracts byte-limit input, ordered by member suffix."""
    return [
        _collapse_cyclic_limit_member(member.sorted_document)
        for member in sorted(oracle.members, key=lambda item: item.sorted_index)
    ]


def cyclic_canonical_limit_bytes(oracle: CyclicSetOracle) -> int:
    return len(_canonical_json(cyclic_canonical_limit_form(oracle)))


def validate_known_language_vector() -> None:
    docs = [
        {"name": "A", "next": {"blueId": "this#1"}},
        {"name": "B", "next": {"blueId": "this#0"}},
    ]
    actual = cyclic_set_oracle(docs).member_ids_in_source_order()
    expected = (
        "C18ETfS2A7MNmBGo67MYaQrRL9TrUSGwvvEu6KoMqC2R#0",
        "C18ETfS2A7MNmBGo67MYaQrRL9TrUSGwvvEu6KoMqC2R#1",
    )
    if actual != expected:
        raise AssertionError(f"Language cyclic vector mismatch: {actual!r}")


def validate_cyclic_limit_representation_invariance() -> None:
    inline_child = {"kind": "auxiliary", "payload": {"name": "same"}}
    child_id = direct_blue_id(inline_child)
    inline = [
        {"name": "A", "next": {"blueId": "this#1"}, "auxiliary": inline_child},
        {"name": "B", "next": {"blueId": "this#0"}},
    ]
    referenced = [
        {"name": "A", "next": {"blueId": "this#1"}, "auxiliary": {"blueId": child_id}},
        {"name": "B", "next": {"blueId": "this#0"}},
    ]
    inline_oracle = cyclic_set_oracle(inline)
    referenced_oracle = cyclic_set_oracle(referenced)
    if inline_oracle.master_blue_id != referenced_oracle.master_blue_id:
        raise AssertionError("inline/reference cyclic identity mismatch")
    if cyclic_canonical_limit_form(inline_oracle) != cyclic_canonical_limit_form(referenced_oracle):
        raise AssertionError("cyclic canonical limit form depends on inline/reference representation")


if __name__ == "__main__":
    validate_known_language_vector()
    validate_cyclic_limit_representation_invariance()
    print("BLUE_IDENTITY_ORACLE_OK")
