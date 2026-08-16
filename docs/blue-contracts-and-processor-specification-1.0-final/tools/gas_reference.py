#!/usr/bin/env python3
"""Canonical mixed processor/Language gas reference for closure fixtures.

This is an independent executable model for the closed JSON/YAML value subset
used by the package. It is not an implementation-conformance runner.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import math
from typing import Any, Iterable

import yaml

from blue_identity import (
    ZERO_BLUEID,
    CyclicSetOracle,
    cyclic_canonical_limit_bytes,
    direct_identity_facts,
)


ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class RejectedCharge:
    namespace: str
    counter: str
    quantity: int
    weight: int
    subtotal: int
    applicable_cap: dict[str, str]
    remaining_before_charge: int


class GasReferenceTrace:
    def __init__(
        self,
        shared_limit: int = 100000,
        local_limits: dict[str, int] | None = None,
    ) -> None:
        manifest = yaml.safe_load((ROOT / "conformance/contracts/gas-manifest.yaml").read_text())
        self.weights = {
            namespace: data["counters"]
            for namespace, data in manifest["namespaces"].items()
            if "counters" in data
        }
        self.shared_limit = shared_limit
        self.local_limits = dict(local_limits or {})
        self.local_used = {document_id: 0 for document_id in self.local_limits}
        self.entries: list[dict[str, Any]] = []
        self.total = 0
        self.rejected: RejectedCharge | None = None

    def charge(
        self,
        namespace: str,
        counter: str,
        quantity: int = 1,
        *,
        reason: str,
        context: dict[str, Any] | None = None,
    ) -> bool:
        if quantity == 0:
            return True
        weight = self.weights[namespace][counter]
        subtotal = quantity * weight
        shared_remaining = self.shared_limit - self.total
        document_id = context.get("documentId") if context else None
        local_remaining = (
            self.local_limits[document_id] - self.local_used[document_id]
            if document_id in self.local_limits
            else None
        )
        shared_failed = subtotal > shared_remaining
        local_failed = local_remaining is not None and subtotal > local_remaining
        if shared_failed or local_failed:
            if local_failed and (not shared_failed or local_remaining < shared_remaining):
                applicable_cap = {"kind": "LOCAL", "documentId": document_id}
                remaining = local_remaining
            else:
                applicable_cap = {"kind": "SHARED"}
                remaining = shared_remaining
            self.rejected = RejectedCharge(
                namespace, counter, quantity, weight, subtotal, applicable_cap, remaining
            )
            return False
        entry: dict[str, Any] = {
            "sequence": len(self.entries),
            "namespace": namespace,
            "counter": counter,
            "quantity": quantity,
            "weight": weight,
            "subtotal": subtotal,
        }
        if context:
            entry.update(context)
        entry["reason"] = reason
        self.entries.append(entry)
        self.total += subtotal
        if document_id in self.local_used:
            self.local_used[document_id] += subtotal
        return True


def _replace_this(value: Any, replacement: str) -> Any:
    if isinstance(value, list):
        return [_replace_this(child, replacement) for child in value]
    if isinstance(value, dict):
        return {
            key: (
                replacement
                if key == "blueId" and isinstance(child, str) and child.startswith("this#")
                else _replace_this(child, replacement)
            )
            for key, child in value.items()
        }
    return value


def collect_existing_descendant_ids(values: Iterable[Any]) -> set[str]:
    result: set[str] = set()

    def visit(value: Any, *, include_self: bool) -> None:
        facts = direct_identity_facts(value, allow_cyclic_placeholders=True)
        if facts.pure_reference:
            result.add(facts.blue_id)
            return
        if include_self:
            result.add(facts.blue_id)
        for child in facts.child_values:
            visit(child, include_self=True)

    for value in values:
        visit(value, include_self=False)
    return result


def establish_exact_value(
    value: Any,
    trace: GasReferenceTrace,
    established: set[str],
    existing: set[str],
    *,
    reason_prefix: str,
    context: dict[str, Any],
    allow_cyclic_placeholders: bool = False,
) -> str:
    facts = direct_identity_facts(
        value, allow_cyclic_placeholders=allow_cyclic_placeholders
    )
    if facts.pure_reference or facts.blue_id in existing or facts.blue_id in established:
        return facts.blue_id
    for index, child in enumerate(facts.child_values):
        establish_exact_value(
            child,
            trace,
            established,
            existing,
            reason_prefix=f"{reason_prefix}.child.{index}",
            context=context,
            allow_cyclic_placeholders=allow_cyclic_placeholders,
        )
    trace.charge(
        "semantic", "nodeIdentityEstablished", 1,
        reason=f"{reason_prefix}.node-established", context=context,
    )
    if facts.kind == "list":
        trace.charge(
            "semantic", "listFoldStepRecomputed", facts.list_length,
            reason=f"{reason_prefix}.list-fold", context=context,
        )
    else:
        trace.charge(
            "semantic", "objectMemberRebuilt", facts.direct_member_count,
            reason=f"{reason_prefix}.object-members", context=context,
        )
        blocks = math.ceil((facts.canonical_input_utf8_bytes + 9) / 64)
        trace.charge(
            "semantic", "directIdentityHashBlock", blocks,
            reason=f"{reason_prefix}.direct-hash", context=context,
        )
    established.add(facts.blue_id)
    return facts.blue_id


def _stable_merge_sort_comparisons(values: list[str]) -> list[tuple[str, str, int]]:
    items = list(values)
    comparisons: list[tuple[str, str, int]] = []
    width = 1
    while width < len(items):
        merged: list[str] = []
        for start in range(0, len(items), width * 2):
            left = items[start:start + width]
            right = items[start + width:start + width * 2]
            li = ri = 0
            while li < len(left) and ri < len(right):
                a, b = left[li], right[ri]
                read = 0
                for ac, bc in zip(a, b):
                    read += 1
                    if ac != bc:
                        break
                else:
                    read = min(len(a), len(b))
                comparisons.append((a, b, max(1, read)))
                if a <= b:
                    merged.append(a); li += 1
                else:
                    merged.append(b); ri += 1
            merged.extend(left[li:]); merged.extend(right[ri:])
        items = merged
        width *= 2
    return comparisons


def finalize_cyclic_component(
    oracle: CyclicSetOracle,
    trace: GasReferenceTrace,
    established: set[str],
    existing: set[str],
    *,
    stage: str,
    component_generation: int,
    document_ids: list[str],
    work_occurrence_id: str | None,
) -> int:
    base_context = {
        "componentGeneration": component_generation,
    }
    if work_occurrence_id is not None:
        base_context["workOccurrenceId"] = work_occurrence_id
    trace.charge(
        "processor", "tentativeComponentFinalization", 1,
        reason=f"{stage}.finalization-boundary", context=base_context,
    )
    preliminaries: list[str] = []
    by_source = sorted(oracle.members, key=lambda member: member.source_index)
    for member in by_source:
        context = {**base_context, "documentId": document_ids[member.source_index]}
        zeroed = _replace_this(member.source_document, ZERO_BLUEID)
        preliminaries.append(establish_exact_value(
            zeroed, trace, established, existing,
            reason_prefix=f"{stage}.preliminary.{member.source_index}",
            context=context, allow_cyclic_placeholders=True,
        ))
    for ordinal, (_left, _right, code_points_read) in enumerate(
        _stable_merge_sort_comparisons(preliminaries)
    ):
        trace.charge(
            "semantic", "sortComparison", 1,
            reason=f"{stage}.preliminary-sort.{ordinal}", context=base_context,
        )
        trace.charge(
            "semantic", "scalarComparison", 1,
            reason=f"{stage}.preliminary-compare.{ordinal}", context=base_context,
        )
        blocks_per_operand = math.ceil(code_points_read / 64)
        trace.charge(
            "semantic", "textBlockExamined", blocks_per_operand * 2,
            reason=f"{stage}.preliminary-text.{ordinal}", context=base_context,
        )
    sorted_members = sorted(oracle.members, key=lambda member: member.sorted_index)
    sorted_documents = []
    for member in sorted_members:
        context = {**base_context, "documentId": document_ids[member.source_index]}
        sorted_documents.append(member.sorted_document)
        establish_exact_value(
            member.sorted_document, trace, established, existing,
            reason_prefix=f"{stage}.canonical-member.{member.sorted_index}",
            context=context, allow_cyclic_placeholders=True,
        )
    establish_exact_value(
        sorted_documents, trace, established, existing,
        reason_prefix=f"{stage}.master", context=base_context,
        allow_cyclic_placeholders=True,
    )
    for member in sorted_members:
        trace.charge(
            "processor", "cyclicMemberFinalized", 1,
            reason=f"{stage}.member-finalized.{member.sorted_index}",
            context={**base_context, "documentId": document_ids[member.source_index]},
        )
    return cyclic_canonical_limit_bytes(oracle)
