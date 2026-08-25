#!/usr/bin/env python3
"""Independent reference checks for the release-defining closure fixtures.

This is intentionally not a replacement for blue-contracts-core. It validates
exact fixture oracles, queue shape, dynamic graph changes, gas prefix, and
history reconciliation before an implementation is allowed to claim conformance.
"""
from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import hashlib
import json
import math
import sys
from typing import Any, Callable
import yaml

from jcs import dumps as jcs_dumps

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from blue_identity import (  # noqa: E402
    cyclic_canonical_limit_bytes,
    cyclic_canonical_limit_form,
    cyclic_set_oracle,
    direct_blue_id,
    direct_identity_facts,
    materialize_cyclic_members,
)
from gas_reference import (  # noqa: E402
    GasReferenceTrace,
    collect_existing_descendant_ids,
    establish_exact_value,
    finalize_cyclic_component,
    gas_trace_identity,
)

FIX = ROOT / "conformance/contracts/fixtures/closure"
ORC = ROOT / "conformance/contracts/oracles"
PROCESS_EMBEDDED = "EVJk3e7MLRhtTfMBNyrWYz1pWFXsbDTkPczeTviUuB4e"


def load_yaml(path: Path) -> Any:
    return yaml.safe_load(path.read_text())


def canonical_json(value: Any) -> bytes:
    return jcs_dumps(value)


def domain_identity(domain: str, value: Any) -> str:
    return "sha256:" + hashlib.sha256(
        canonical_json({"domain": domain, "value": value})
    ).hexdigest()


def closure_resource_demand_identity(demand: dict[str, Any]) -> str:
    """Independent RFC 8785/SHA-256 replay of the closed demand constructor."""
    exact = demand["kind"] == "EXACT_NODE"
    return domain_identity(
        "blue-contracts-closure-resource-demand/1.0",
        {
            "kind": demand["kind"],
            "logicalCauseIdentity": (
                None if exact else demand["logicalCauseIdentity"]
            ),
            "inputClosureIdentity": (
                None if exact else demand["inputClosureIdentity"]
            ),
            "inputGraphGeneration": (
                None if exact else demand["inputGraphGeneration"]
            ),
            "sourceDocumentId": demand["sourceDocumentId"],
            "sourcePath": demand["sourcePath"],
            "processEmbeddedDeclarationIdentity": (
                None if exact
                else demand["processEmbeddedDeclarationIdentity"]
            ),
            "suppliedValueBlueId": demand["suppliedValueBlueId"],
            "demandOrdinal": None if exact else demand["demandOrdinal"],
        },
    )


def assert_typed_demand_result(
    fixture: dict[str, Any],
    kinds: list[str],
) -> list[dict[str, Any]]:
    """Independently verify one authoritative NeedsResources transcript."""
    expected = fixture["expected"]
    assert expected["attemptOutcome"] == "NeedsResources"
    demands = expected["resourceDemands"]
    assert [demand["kind"] for demand in demands] == kinds
    assert all(
        demand["demandIdentity"]
        == closure_resource_demand_identity(demand)
        for demand in demands
    )
    order = [
        (
            demand["sourceDocumentId"],
            demand["sourcePath"],
            demand["suppliedValueBlueId"],
            ((0, 0) if demand["kind"] == "EXACT_NODE"
             else (1, demand["demandOrdinal"])),
            demand["demandIdentity"],
        )
        for demand in demands
    ]
    assert order == sorted(order)
    assert expected["requiredBlueIds"] == sorted(
        {
            demand["blueId"]
            for demand in demands
            if demand["kind"] == "EXACT_NODE"
        }
    )
    return demands


def stable_route_sort_comparisons(
    values: list[str],
) -> list[tuple[str, str, int]]:
    """Independently replay the normative bottom-up comparator sequence."""
    source = list(values)
    comparisons: list[tuple[str, str, int]] = []
    width = 1
    while width < len(source):
        target: list[str] = []
        for start in range(0, len(source), width * 2):
            left = source[start:start + width]
            right = source[start + width:start + width * 2]
            left_index = right_index = 0
            while left_index < len(left) and right_index < len(right):
                left_value = left[left_index]
                right_value = right[right_index]
                read = 0
                for left_point, right_point in zip(
                    left_value, right_value
                ):
                    read += 1
                    if left_point != right_point:
                        break
                comparisons.append((left_value, right_value, read))
                if left_value <= right_value:
                    target.append(left_value)
                    left_index += 1
                else:
                    target.append(right_value)
                    right_index += 1
            target.extend(left[left_index:])
            target.extend(right[right_index:])
        source = target
        width *= 2
    return comparisons


def assert_occurrence_successor_generation(
    before: dict[str, Any] | None,
    after: dict[str, Any],
    *,
    preserves_lineage: bool,
) -> None:
    """Assert the exact generation law for one known predecessor transition."""
    if before is None:
        assert after["activationGeneration"] == 1
        return
    assert before["sourceDocumentId"] == after["sourceDocumentId"]
    assert before["sourcePath"] == after["sourcePath"]
    if preserves_lineage:
        assert after["activationGeneration"] == before["activationGeneration"]
        assert after["occurrenceIdentity"] == before["occurrenceIdentity"]
    else:
        assert before["activationGeneration"] < 2**53 - 1
        assert (
            after["activationGeneration"]
            == before["activationGeneration"] + 1
        )
        assert after["occurrenceIdentity"] != before["occurrenceIdentity"]
        assert after["bindingIdentity"] != before["bindingIdentity"]


def assert_occurrence_transition_law(
    before_occurrences: list[dict[str, Any]],
    after_occurrences: list[dict[str, Any]],
) -> None:
    before_by_path = {
        (row["sourceDocumentId"], row["sourcePath"]): row
        for row in before_occurrences
    }
    after_by_path = {
        (row["sourceDocumentId"], row["sourcePath"]): row
        for row in after_occurrences
    }
    assert len(before_by_path) == len(before_occurrences)
    assert len(after_by_path) == len(after_occurrences)
    assert set(before_by_path) <= set(after_by_path)
    for key, after in after_by_path.items():
        before = before_by_path.get(key)
        assert before is not None
        same_lineage = (
            before["targetDocumentId"] == after["targetDocumentId"]
            and before["bindingPolicyIdentity"]
            == after["bindingPolicyIdentity"]
        )
        assert same_lineage
        retirement = same_lineage and before["active"] and not after["active"]
        assert_occurrence_successor_generation(
            before,
            after,
            preserves_lineage=same_lineage and not retirement,
        )


def check_occurrence_transition_law() -> dict[str, Any]:
    """Check all complete snapshots plus every deterministic transition branch."""
    completed = 0
    for path in sorted(FIX.glob("*.yaml")):
        fixture = load_yaml(path)
        if fixture.get("operation") == "limit-micro":
            continue
        expected = fixture["expected"]
        if expected.get("attemptOutcome") != "Complete":
            continue
        assert_occurrence_transition_law(
            fixture["input"].get("occurrences", []),
            expected["occurrenceBindings"],
        )
        completed += 1

    def row(
        generation: int,
        target: str,
        active: bool,
        occurrence_identity: str,
        *,
        binding_identity: str = "binding-0",
        policy: str = "policy-0",
    ) -> dict[str, Any]:
        return {
            "sourceDocumentId": "source",
            "sourcePath": "/child",
            "activationGeneration": generation,
            "targetDocumentId": target,
            "bindingPolicyIdentity": policy,
            "occurrenceIdentity": occurrence_identity,
            "bindingIdentity": binding_identity,
            "expectedTargetBlueId": binding_identity,
            "active": active,
        }

    reserved = row(1, "target-a", False, "occurrence-1")
    active = row(1, "target-a", True, "occurrence-1")
    retirement_successor = row(
        2,
        "target-a",
        False,
        "occurrence-2",
        binding_identity="binding-2",
    )
    readded = row(
        2,
        "target-a",
        True,
        "occurrence-2",
        binding_identity="binding-2",
    )
    rebound = row(
        1,
        "target-a",
        True,
        "occurrence-1",
        binding_identity="binding-1",
    )
    illegal_same_invocation_retarget = row(
        2,
        "target-b",
        True,
        "occurrence-3",
        binding_identity="binding-3",
    )
    assert_occurrence_successor_generation(
        None, reserved, preserves_lineage=False
    )
    assert_occurrence_transition_law([reserved], [active])
    assert_occurrence_transition_law([active], [retirement_successor])
    assert_occurrence_transition_law([retirement_successor], [readded])
    assert_occurrence_transition_law([active], [rebound])
    try:
        assert_occurrence_transition_law(
            [active], [illegal_same_invocation_retarget]
        )
    except AssertionError:
        pass
    else:
        raise AssertionError("same-invocation retarget unexpectedly accepted")
    illegal_same_invocation_remove_readd = row(
        2,
        "target-a",
        True,
        "occurrence-2",
        binding_identity="binding-2",
    )
    try:
        assert_occurrence_transition_law(
            [active], [illegal_same_invocation_remove_readd]
        )
    except AssertionError:
        pass
    else:
        raise AssertionError(
            "same-invocation remove-then-re-add unexpectedly accepted"
        )
    illegal_reserved_retarget = row(
        2,
        "target-b",
        False,
        "occurrence-3",
        binding_identity="binding-3",
    )
    try:
        assert_occurrence_transition_law(
            [retirement_successor], [illegal_reserved_retarget]
        )
    except AssertionError:
        pass
    else:
        raise AssertionError("reserved-path retarget unexpectedly accepted")
    try:
        assert_occurrence_transition_law([active], [])
    except AssertionError:
        pass
    else:
        raise AssertionError("deleted active occurrence row unexpectedly accepted")
    return {"completedFixtures": completed, "syntheticBranches": 9}


def assert_generation_transition_law(fixture: dict[str, Any]) -> None:
    fixture_input = fixture["input"]
    expected = fixture["expected"]
    before_occurrences = fixture_input.get("occurrences", [])
    after_occurrences = expected["occurrenceBindings"]
    before_active = {
        row["occurrenceIdentity"] for row in before_occurrences if row["active"]
    }
    after_active = {
        row["occurrenceIdentity"] for row in after_occurrences if row["active"]
    }
    expected_graph_generation = fixture_input["graphGeneration"]
    if expected["status"] == "success" and before_active != after_active:
        expected_graph_generation += 1
    assert expected["graphGeneration"] == expected_graph_generation

    def members(component: dict[str, Any]) -> frozenset[str]:
        return frozenset(component["orderedMemberDocumentIds"])

    def internal_edges(
        component: dict[str, Any], occurrences: list[dict[str, Any]]
    ) -> frozenset[tuple[str, str, str]]:
        component_members = members(component)
        return frozenset(
            (
                row["occurrenceIdentity"],
                row["sourceDocumentId"],
                row["targetDocumentId"],
            )
            for row in occurrences
            if row["active"]
            and row["sourceDocumentId"] in component_members
            and row["targetDocumentId"] in component_members
        )

    before_shapes = [
        (component, members(component), internal_edges(component, before_occurrences))
        for component in fixture_input["components"]
    ]
    for component in expected["resultingComponents"]:
        component_members = members(component)
        component_edges = internal_edges(component, after_occurrences)
        exact = [
            before
            for before, before_members, before_edges in before_shapes
            if before_members == component_members and before_edges == component_edges
        ]
        assert len(exact) <= 1
        if exact:
            generation = exact[0]["componentGeneration"]
        else:
            generation = 1 + max(
                (
                    before["componentGeneration"]
                    for before, before_members, _before_edges in before_shapes
                    if before_members & component_members
                ),
                default=0,
            )
        assert component["componentGeneration"] == generation


def check_generation_transition_law() -> dict[str, Any]:
    completed = 0
    for path in sorted(FIX.glob("*.yaml")):
        fixture = load_yaml(path)
        if fixture.get("operation") == "limit-micro":
            continue
        if fixture["expected"].get("attemptOutcome") != "Complete":
            continue
        assert_generation_transition_law(fixture)
        completed += 1
    return {"completedFixtures": completed}


def canonical_direct_seed_identities(
    fixture: dict[str, Any],
) -> list[str]:
    """Derive Phase-D order independently of raw snapshot serialization."""
    fixture_input = fixture["input"]
    component_rank = {
        document_id: rank
        for rank, component in enumerate(fixture_input["components"])
        for document_id in component["orderedMemberDocumentIds"]
    }

    def seed_key(delivery: dict[str, Any]) -> tuple[Any, ...]:
        assert delivery["scopePath"] == "/"
        assert delivery["activationGeneration"] == 0
        document_id = delivery["targetDocumentId"]
        channel = fixture_input["documents"][document_id]["document"][
            "contracts"
        ][delivery["channelKey"]]
        channel_order = channel.get("order", 0)
        assert isinstance(channel_order, int) and not isinstance(
            channel_order, bool
        )
        return (
            component_rank[document_id],
            document_id,
            delivery["scopePath"],
            delivery["activationGeneration"],
            channel_order,
            delivery["channelKey"],
            delivery["logicalDeliveryKey"],
            delivery["rawOccurrenceOrder"],
        )

    return [
        domain_identity("blue-contracts-direct-delivery/1.0", delivery)
        for delivery in sorted(
            fixture_input.get("directDeliveries", []), key=seed_key
        )
    ]


def check_direct_seed_order() -> dict[str, Any]:
    checked = 0
    deliberate_conflicts = 0
    for path in sorted(FIX.glob("*.yaml")):
        fixture = load_yaml(path)
        if fixture.get("operation") == "limit-micro":
            continue
        expected = fixture["expected"]
        if expected.get("attemptOutcome") != "Complete":
            continue
        ordered = canonical_direct_seed_identities(fixture)
        work = list(expected["workTrace"])
        rejected = expected.get("rejectedWorkOccurrence")
        if rejected is not None:
            work.append(rejected)
        actual = [
            item["sourceOccurrenceIdentity"]
            for item in work
            if item["kind"] == "EXTERNAL_DELIVERY"
        ]
        assert actual == ordered[: len(actual)], path.name
        if expected["status"] == "success":
            assert actual == ordered, path.name
        if fixture["id"] == "c-clo-13-frozen-edge-addition":
            raw = [
                domain_identity("blue-contracts-direct-delivery/1.0", item)
                for item in fixture["input"]["directDeliveries"]
            ]
            assert raw != ordered
            assert actual == ordered
            deliberate_conflicts += 1
        checked += 1
    assert deliberate_conflicts == 1
    return {
        "completedFixtures": checked,
        "rawSnapshotOrderConflicts": deliberate_conflicts,
    }


def resulting_documents(expected: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {item["documentId"]: item for item in expected["resultingDocuments"]}


def replace_pointer(document: Any, pointer: str, value: Any) -> None:
    segments = [
        segment.replace("~1", "/").replace("~0", "~")
        for segment in pointer.split("/")[1:]
    ]
    parent = document
    for segment in segments[:-1]:
        parent = parent[int(segment)] if isinstance(parent, list) else parent[segment]
    final = segments[-1]
    if isinstance(parent, list):
        parent[int(final)] = deepcopy(value)
    else:
        parent[final] = deepcopy(value)


def cyclic_oracle_from_result(
    expected: dict[str, Any], component: dict[str, Any]
) -> Any:
    """Reconstruct the Language cyclic source form from exact result evidence."""
    documents = resulting_documents(expected)
    member_states = component["completeCyclicProof"]["memberStates"]
    source_index_by_blue_id = {
        state["blueId"]: source_index
        for source_index, state in enumerate(member_states)
    }

    def restore_this_references(value: Any) -> Any:
        if isinstance(value, dict):
            if set(value) == {"blueId"} and value["blueId"] in source_index_by_blue_id:
                return {
                    "blueId": f"this#{source_index_by_blue_id[value['blueId']]}"
                }
            return {
                key: restore_this_references(child)
                for key, child in value.items()
            }
        if isinstance(value, list):
            return [restore_this_references(child) for child in value]
        return value

    source_documents = [
        restore_this_references(documents[state["documentId"]]["document"])
        for state in member_states
    ]
    oracle = cyclic_set_oracle(source_documents)
    assert oracle.master_blue_id == component["masterBlueId"]
    assert list(oracle.member_ids_in_source_order()) == [
        state["blueId"] for state in member_states
    ]
    return oracle


def pre_checkpoint_documents(fixture: dict[str, Any]) -> dict[str, Any]:
    """Derive the exact state at causal quiescence, before marker settlement."""
    stripped: dict[str, Any] = {}
    output = resulting_documents(fixture["expected"])
    for document_id, record in output.items():
        document = deepcopy(record["document"])
        contracts = document.setdefault("contracts", {})
        input_checkpoint = (
            fixture["input"]["documents"][document_id]["document"]
            .get("contracts", {})
            .get("checkpoint")
        )
        if input_checkpoint is None:
            contracts.pop("checkpoint", None)
        else:
            contracts["checkpoint"] = deepcopy(input_checkpoint)
        if not contracts:
            document.pop("contracts", None)
        stripped[document_id] = document

    active_by_source: dict[str, list[dict[str, Any]]] = {}
    for occurrence in fixture["expected"]["occurrenceBindings"]:
        if occurrence["active"]:
            active_by_source.setdefault(occurrence["sourceDocumentId"], []).append(
                occurrence
            )

    # Settlement can change a referenced child's exact identity. Rebuild an
    # acyclic containing spine bottom-up so the pre-settlement parents refer
    # to the pre-settlement child BlueIds rather than the serialized outputs.
    result: dict[str, Any] = {}
    pending = set(stripped)
    while pending:
        progressed = False
        for document_id in sorted(pending):
            occurrences = active_by_source.get(document_id, [])
            targets = {item["targetDocumentId"] for item in occurrences}
            if targets.intersection(pending):
                continue
            document = deepcopy(stripped[document_id])
            for occurrence in occurrences:
                target_id = occurrence["targetDocumentId"]
                assert target_id in result
                replace_pointer(
                    document,
                    occurrence["sourcePath"],
                    {"blueId": direct_blue_id(result[target_id])},
                )
            result[document_id] = document
            pending.remove(document_id)
            progressed = True
            break
        if not progressed:
            # Cyclic settlement is replayed through the independent Language
            # cyclic oracle, not this acyclic containing-spine helper.
            raise AssertionError("pre-checkpoint dependency graph is cyclic")
    return result


def seed_work(
    ordinal: int,
    kind: str,
    document_id: str,
    channel: str,
    *,
    event_id: str | None = None,
    occurrence_ordinal: int | None = None,
    invocation: str = "fixture",
) -> dict[str, Any]:
    basis = {
        "invocation": invocation,
        "ordinal": ordinal,
        "kind": kind,
        "target": document_id,
        "channel": channel,
        "event": event_id,
        "occurrence": occurrence_ordinal,
    }
    result = {
        "ordinal": ordinal,
        "kind": kind,
        "targetDocumentId": document_id,
        "channelKey": channel,
        "workIdentity": domain_identity(
            "blue-contracts-fixture-work-seed/1.0", basis
        ),
    }
    if event_id is not None:
        result["eventBlueId"] = event_id
    if occurrence_ordinal is not None:
        result["occurrenceOrdinal"] = occurrence_ordinal
    return result


def gas_trace(fixture: dict[str, Any], name: str) -> list[dict[str, Any]]:
    expected = fixture["expected"]
    if "gasTrace" in expected:
        return expected["gasTrace"]
    payload = load_yaml(FIX / expected["gasTraceFile"])
    assert payload["schema"] == "blue-contracts-gas-trace/1.0"
    assert payload["fixture"] == Path(name).stem
    return payload["entries"]


def reference_rejected_charge_payload(trace: GasReferenceTrace) -> dict[str, Any]:
    rejected = trace.rejected
    if rejected is None:
        raise AssertionError("expected one rejected gas charge")
    return {
        "namespace": rejected.namespace,
        "counter": rejected.counter,
        "quantity": rejected.quantity,
        "weight": rejected.weight,
        "subtotal": rejected.subtotal,
        "applicableCap": dict(rejected.applicable_cap),
        "remainingBeforeCharge": rejected.remaining_before_charge,
    }


def derive_loop_trace(
    fixture: dict[str, Any],
    invocation: str,
) -> tuple[
    list[dict[str, Any]],
    int,
    list[dict[str, Any]],
    dict[str, Any] | None,
    dict[str, Any],
]:
    """Independently replay the bounded loop through the Root runtime surface."""
    fixture_input = fixture["input"]
    gas_policy = fixture_input["gasPolicy"]
    trace = GasReferenceTrace(
        gas_policy["sharedLimit"], gas_policy.get("localLimits", {})
    )
    trace.weights.setdefault("runtime", {})["scriptedResultApplied"] = 1
    runtime = fixture.get("runtime", fixture_input.get("runtime", {}))
    loop_event = runtime["handlers"]["loop-a/start"]["events"][0]
    loop_event_id = direct_blue_id(loop_event)
    start_event_id = fixture_input["cause"]["eventBlueId"]
    documents = fixture_input["documents"]
    occurrences_by_source: dict[str, list[dict[str, Any]]] = {}
    for occurrence in fixture_input.get("occurrences", []):
        occurrences_by_source.setdefault(
            occurrence["sourceDocumentId"], []
        ).append(occurrence)
    for occurrences in occurrences_by_source.values():
        occurrences.sort(
            key=lambda item: (
                item["sourcePath"].encode("utf-8"),
                item["occurrenceIdentity"],
            )
        )
    marker_types = {
        "Hp3fNbpFxKwLiTwWAf3swpN7gKbsr6ofwEDMntiwXPaB",
        "9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR",
    }

    def participating_contract_keys(document_id: str) -> list[str]:
        keys: list[str] = []
        contracts = documents[document_id]["document"].get("contracts", {})
        for key, contract in contracts.items():
            type_value = contract.get("type") if isinstance(contract, dict) else None
            type_id = (
                type_value.get("blueId")
                if isinstance(type_value, dict)
                and set(type_value) == {"blueId"}
                else None
            )
            if type_id not in marker_types:
                keys.append(key)
        return keys

    def make_work(ordinal: int) -> dict[str, Any]:
        if ordinal == 0:
            return seed_work(
                0, "EXTERNAL_DELIVERY", "loop-a", "source",
                event_id=start_event_id, occurrence_ordinal=0, invocation=invocation,
            )
        target = "loop-b" if ordinal % 2 == 1 else "loop-a"
        channel = "fromA" if target == "loop-b" else "fromB"
        return seed_work(
            ordinal, "EMBEDDED_EVENT", target, channel,
            event_id=loop_event_id, occurrence_ordinal=ordinal - 1,
            invocation=invocation,
        )

    def context_for(work_item: dict[str, Any]) -> dict[str, Any]:
        context = {
            "documentId": work_item["targetDocumentId"],
            "scopePath": "/",
            "activationGeneration": 0,
            "componentGeneration": 1,
            "workOccurrenceId": work_item["workIdentity"],
        }
        return context

    def charge(
        namespace: str,
        counter: str,
        quantity: int,
        reason: str,
        work_item: dict[str, Any] | None = None,
        *,
        contract_key: str | None = None,
        logical_path: str | None = None,
        include_work_path: bool = False,
        document_id: str | None = None,
    ) -> bool:
        context: dict[str, Any] = {}
        if work_item is not None:
            context.update(context_for(work_item))
            selected_contract = (
                work_item["channelKey"]
                if contract_key is None else contract_key
            )
            if selected_contract:
                context["contractKey"] = selected_contract
            if include_work_path:
                context["logicalPath"] = f"work/{work_item['ordinal']}"
            elif logical_path is not None:
                context["logicalPath"] = logical_path
        if document_id is not None:
            context["documentId"] = document_id
        if work_item is None and contract_key is not None:
            context["contractKey"] = contract_key
        return trace.charge(
            namespace, counter, quantity, reason=reason,
            context=context or None,
        )

    current = make_work(0)
    for counter, quantity, reason in [
        ("processInvocation", 1, "admission.process"),
        ("closureInvocation", 1, "admission.closure"),
        ("deliverySnapshotEntry", 1, "admission.direct-deliveries"),
    ]:
        if not charge("processor", counter, quantity, reason):
            raise AssertionError("loop policy cannot reject fixed global admission")
    for document_id in documents:
        if not charge(
            "processor", "managedDocumentOpened", 1,
            f"admission.document.{document_id}", document_id=document_id,
        ):
            raise AssertionError("loop policy cannot reject fixed document admission")
    for occurrence in fixture_input.get("occurrences", []):
        document_id = occurrence["sourceDocumentId"]
        if not charge(
            "processor", "managedOccurrenceBindingVerified", 1,
            f"admission.binding.{occurrence['occurrenceIdentity']}",
            document_id=document_id,
        ):
            raise AssertionError("loop policy cannot reject fixed occurrence admission")
        if occurrence.get("active") and not charge(
            "processor", "processEmbeddedEdgeExamined", 1,
            f"admission.edge.{occurrence['occurrenceIdentity']}",
            document_id=document_id,
        ):
            raise AssertionError("loop policy cannot reject fixed edge admission")
    for document_id in documents:
        if not charge(
            "processor", "componentMemberPartitioned", 1,
            "admission.component-member", document_id=document_id,
        ):
            raise AssertionError("loop policy cannot reject fixed component admission")
    for counter, reason in [
        ("channelCandidateTested", "acceptance"),
        ("channelAccepted", "acceptance"),
        ("checkpointCompared", "direct-admission.0.classification"),
    ]:
        if not charge(
            "processor", counter, 1, reason, current,
            include_work_path=True,
        ):
            raise AssertionError("loop policy cannot reject fixed direct classification")
    if not charge(
        "processor", "closureWorkOccurrenceEnqueued", 1,
        "work.0.seed-enqueue", current, include_work_path=True,
    ):
        raise AssertionError("loop policy cannot reject initial work enqueue")

    accepted: list[dict[str, Any]] = [current]
    rejected_owner_work: dict[str, Any] | None = None
    while True:
        handler_key = (
            "start" if current["ordinal"] == 0
            else "onLoopFromA" if current["targetDocumentId"] == "loop-b"
            else "onLoopFromB"
        )
        if not charge(
            "processor", "closureWorkOccurrenceDequeued", 1,
            f"work.{current['ordinal']}.dequeue", current,
            include_work_path=True,
        ):
            rejected_owner_work = current
            break
        if not charge(
            "processor", "scopeOpened", 1,
            "participating-scope", current,
        ):
            rejected_owner_work = current
            break
        rejected = False
        for contract_key in participating_contract_keys(
            current["targetDocumentId"]
        ):
            if not charge(
                "processor", "contractHeaderRecognized", 1,
                "participating-contract-header", current,
                contract_key=contract_key,
            ):
                rejected = True
                break
        if rejected:
            rejected_owner_work = current
            break
        for occurrence in occurrences_by_source.get(
            current["targetDocumentId"], []
        ):
            source_path = occurrence["sourcePath"]
            if not charge(
                "processor", "embeddedPathEntryRead", 1,
                "route", current, logical_path=source_path,
            ):
                rejected = True
                break
            segments = len(
                [segment for segment in source_path.split("/") if segment]
            )
            if segments and not charge(
                "processor", "embeddedPathSegmentValidated", segments,
                "route", current, logical_path=source_path,
            ):
                rejected = True
                break
        if rejected:
            rejected_owner_work = current
            break
        if current["ordinal"] > 0 and not charge(
            "processor", "embeddedEventDelivered", 1,
            "embedded-event", current,
        ):
            rejected_owner_work = current
            break
        for counter, reason in [
            ("handlerCandidateTested", "matching"),
            ("handlerCall", "handler-call"),
        ]:
            if not charge(
                "processor", counter, 1, reason, current,
                contract_key=handler_key,
            ):
                rejected = True
                break
        if rejected:
            rejected_owner_work = current
            break
        if not charge(
            "runtime", "scriptedResultApplied", 1,
            "unspecified", current,
        ):
            rejected_owner_work = current
            break
        if documents[current["targetDocumentId"]].get("publicRoot") and not charge(
            "processor", "rootEventRecorded", 1,
            f"event.{current['ordinal']}.public-record", current,
            include_work_path=True,
        ):
            rejected_owner_work = current
            break
        if not charge(
            "processor", "internalEventEnqueued", 1,
            f"event.{current['ordinal']}.enqueue", current,
            include_work_path=True,
        ):
            rejected_owner_work = current
            break
        next_work = make_work(current["ordinal"] + 1)
        if not charge(
            "processor", "internalEventDequeued", 1,
            f"event.{next_work['occurrenceOrdinal']}.dequeue",
        ):
            rejected_owner_work = None
            break
        if not charge(
            "processor", "closureWorkOccurrenceEnqueued", 1,
            f"event.{next_work['occurrenceOrdinal']}.delivery.0.enqueue",
            next_work, include_work_path=True,
        ):
            rejected_owner_work = next_work
            break
        accepted.append(next_work)
        current = next_work
        if current["ordinal"] > 10000:
            raise AssertionError("loop trace did not terminate")

    return (
        trace.entries,
        trace.total,
        accepted,
        rejected_owner_work,
        reference_rejected_charge_payload(trace),
    )


def _reference_decode_pointer(path: str) -> list[str]:
    if not isinstance(path, str) or not path.startswith("/"):
        raise AssertionError(
            f"expected an absolute JSON pointer, got {path!r}"
        )
    return [
        part.replace("~1", "/").replace("~0", "~")
        for part in path[1:].split("/")
    ]


def _reference_value_at_pointer(value: Any, parts: list[str]) -> Any | None:
    current = value
    for part in parts:
        if isinstance(current, list):
            try:
                current = current[int(part)]
            except (IndexError, ValueError):
                return None
        elif isinstance(current, dict) and part in current:
            current = current[part]
        else:
            return None
    return current


def _reference_write_pointer(document: Any, path: str, value: Any) -> None:
    parts = _reference_decode_pointer(path)
    current = document
    for part in parts[:-1]:
        current = (
            current[int(part)] if isinstance(current, list)
            else current[part]
        )
    if isinstance(current, list):
        current[int(parts[-1])] = deepcopy(value)
    else:
        current[parts[-1]] = deepcopy(value)


def _reference_remove_pointer(document: Any, path: str) -> None:
    parts = _reference_decode_pointer(path)
    current = document
    for part in parts[:-1]:
        current = (
            current[int(part)] if isinstance(current, list)
            else current[part]
        )
    if isinstance(current, list):
        del current[int(parts[-1])]
    else:
        del current[parts[-1]]


def _reference_charge_direct_node(
    value: Any,
    *,
    charge: Callable[
        [str, str, int, str, dict[str, Any] | None], None
    ],
    context: dict[str, Any],
    recurse: bool,
) -> None:
    """Independently derive Language direct-node identity work."""
    raw_scalar = isinstance(value, (str, int, float, bool))
    facts = direct_identity_facts(value, allow_cyclic_placeholders=True)
    if facts.pure_reference:
        return
    direct_member_count = 1 if raw_scalar else facts.direct_member_count
    canonical_facts = facts
    if isinstance(value, dict):
        identity_value = {
            key: child
            for key, child in value.items()
            if not (isinstance(child, dict) and not child)
        }
        if len(identity_value) != len(value):
            canonical_facts = direct_identity_facts(
                identity_value, allow_cyclic_placeholders=True
            )
    charge(
        "semantic", "nodeIdentityEstablished", 1,
        "identity-rebuild", context,
    )
    if facts.kind == "list":
        if facts.list_length:
            charge(
                "semantic", "listFoldStepRecomputed", facts.list_length,
                "identity-rebuild", context,
            )
    else:
        if direct_member_count:
            charge(
                "semantic", "objectMemberRebuilt", direct_member_count,
                "identity-rebuild", context,
            )
        charge(
            "semantic", "directIdentityHashBlock",
            math.ceil((canonical_facts.canonical_input_utf8_bytes + 9) / 64),
            "identity-rebuild", context,
        )
    if recurse:
        for child in facts.child_values:
            _reference_charge_direct_node(
                child, charge=charge, context=context, recurse=True
            )


def _reference_charge_direct_patch_result(
    before_document: Any,
    patch: dict[str, Any],
    *,
    charge: Callable[
        [str, str, int, str, dict[str, Any] | None], None
    ],
    context: dict[str, Any],
) -> Any:
    """Return and charge the immutable result of one sequential patch."""
    operation = patch.get("op")
    if operation not in {"add", "remove", "replace"}:
        raise AssertionError(f"unsupported patch operation: {operation!r}")
    if operation != "remove" and "val" not in patch:
        raise AssertionError(f"{operation} patch is missing val")
    if "val" in patch:
        _reference_charge_direct_node(
            patch["val"], charge=charge, context=context, recurse=True
        )

    result = deepcopy(before_document)
    if operation == "remove":
        _reference_remove_pointer(result, patch["path"])
    else:
        _reference_write_pointer(result, patch["path"], patch["val"])

    parts = _reference_decode_pointer(patch["path"])
    for count in range(len(parts) - 1, -1, -1):
        ancestor = _reference_value_at_pointer(result, parts[:count])
        if ancestor is not None:
            _reference_charge_direct_node(
                ancestor, charge=charge, context=context, recurse=False
            )
    return result


def derive_direct_patch_result_identity_trace(
    initial_document: Any,
    patches: list[dict[str, Any]],
) -> tuple[Any, list[dict[str, Any]], int]:
    """Expose the independent ADD/REPLACE/REMOVE identity reference."""
    trace = GasReferenceTrace()

    def charge(
        namespace: str,
        counter: str,
        quantity: int,
        reason: str,
        context: dict[str, Any] | None = None,
    ) -> None:
        assert trace.charge(
            namespace, counter, quantity, reason=reason, context=context
        )

    result = deepcopy(initial_document)
    for patch in patches:
        result = _reference_charge_direct_patch_result(
            result,
            patch,
            charge=charge,
            context={"logicalPath": patch["path"]},
        )
    return result, trace.entries, trace.total


def derive_finite_trace(fixture: dict[str, Any]) -> tuple[list[dict[str, Any]], int, str]:
    fixture_input = fixture["input"]
    expected = fixture["expected"]
    trace = GasReferenceTrace(fixture_input["gasPolicy"]["sharedLimit"])
    trace.weights.setdefault("runtime", {})["scriptedResultApplied"] = 1
    established: set[str] = set()
    existing = collect_existing_descendant_ids(
        record["document"] for record in fixture_input["documents"].values()
    )
    documents = {
        document_id: deepcopy(record["document"])
        for document_id, record in fixture_input["documents"].items()
    }
    current_blue_ids = {
        document_id: record["blueId"]
        for document_id, record in fixture_input["documents"].items()
    }
    active_generations = {
        document_id: record["componentGeneration"]
        for document_id, record in fixture_input["documents"].items()
    }
    work = expected["workTrace"]
    work_generations: dict[int, int] = {}

    def work_context(
        work_item: dict[str, Any],
        *,
        contract_key: str | None = None,
        logical_path: str | None = None,
        include_work_path: bool = False,
    ) -> dict[str, Any]:
        document_id = work_item["targetDocumentId"]
        generation = work_generations.setdefault(
            work_item["ordinal"], active_generations[document_id]
        )
        context = {
            "documentId": document_id,
            "scopePath": "/",
            "activationGeneration": 0,
            "componentGeneration": generation,
            "contractKey": (
                work_item["channelKey"]
                if contract_key is None else contract_key
            ),
            "workOccurrenceId": work_item["workIdentity"],
        }
        if include_work_path:
            context["logicalPath"] = f"work/{work_item['ordinal']}"
        elif logical_path is not None:
            context["logicalPath"] = logical_path
        return context

    def charge(
        namespace: str,
        counter: str,
        quantity: int,
        reason: str,
        context: dict[str, Any] | None = None,
    ) -> None:
        assert trace.charge(
            namespace, counter, quantity, reason=reason, context=context
        )

    def processor(
        counter: str,
        quantity: int,
        reason: str,
        context: dict[str, Any] | None = None,
    ) -> None:
        charge("processor", counter, quantity, reason, context)

    def charge_direct_node(
        value: Any,
        *,
        context: dict[str, Any],
        recurse: bool,
    ) -> None:
        _reference_charge_direct_node(
            value,
            charge=charge,
            context=context,
            recurse=recurse,
        )

    # Admission is reconstructed from the actual input graph, not result gas.
    processor("processInvocation", 1, "admission.process")
    processor("closureInvocation", 1, "admission.closure")
    processor(
        "deliverySnapshotEntry",
        len(fixture_input["directDeliveries"]),
        "admission.direct-deliveries",
    )
    for document_id in sorted(fixture_input["documents"]):
        processor(
            "managedDocumentOpened", 1,
            f"admission.document.{document_id}",
            {"documentId": document_id},
        )
    for occurrence in sorted(
        fixture_input["occurrences"],
        key=lambda item: (item["occurrenceIdentity"], item["bindingIdentity"]),
    ):
        processor(
            "managedOccurrenceBindingVerified", 1,
            f"admission.binding.{occurrence['occurrenceIdentity']}",
            {"documentId": occurrence["sourceDocumentId"]},
        )
        if occurrence["active"]:
            processor(
                "processEmbeddedEdgeExamined", 1,
                f"admission.edge.{occurrence['occurrenceIdentity']}",
                {"documentId": occurrence["sourceDocumentId"]},
            )
    for component in fixture_input["components"]:
        for document_id in component["orderedMemberDocumentIds"]:
            processor(
                "componentMemberPartitioned", 1,
                "admission.component-member", {"documentId": document_id},
            )

    external_work = [item for item in work if item["kind"] == "EXTERNAL_DELIVERY"]
    assert all(item["occurrenceOrdinal"] == 0 for item in external_work)
    direct_for_work: dict[int, dict[str, Any]] = {}
    available_direct = sorted(
        fixture_input["directDeliveries"],
        key=lambda item: item["rawOccurrenceOrder"],
    )
    for work_item in external_work:
        delivery = next(
            item for item in available_direct
            if item["targetDocumentId"] == work_item["targetDocumentId"]
            and item["channelKey"] == work_item["channelKey"]
        )
        available_direct.remove(delivery)
        direct_for_work[work_item["ordinal"]] = delivery

    classification_work = sorted(
        external_work,
        key=lambda item: (
            direct_for_work[item["ordinal"]]["rawOccurrenceOrder"],
            item["ordinal"],
        ),
    )
    for work_item in classification_work:
        delivery = direct_for_work[work_item["ordinal"]]
        context = work_context(work_item, include_work_path=True)
        processor("channelCandidateTested", 1, "acceptance", context)
        processor("channelAccepted", 1, "acceptance", context)
        processor(
            "checkpointCompared", 1,
            f"direct-admission.{delivery['rawOccurrenceOrder']}.classification",
            context,
        )

    # Phase B freezes every comparison before Phase D constructs/enqueues the
    # accepted direct work occurrences.
    for work_item in external_work:
        context = work_context(work_item, include_work_path=True)
        processor(
            "closureWorkOccurrenceEnqueued", 1,
            f"work.{work_item['ordinal']}.seed-enqueue", context,
        )

    oracle_route = fixture.get("oracle")
    if oracle_route is None:
        if expected.get("tentativeFinalizations"):
            raise AssertionError(
                "tentative finalization lacks an exact oracle route"
            )
        stage_sources: dict[str, list[dict[str, Any]]] = {}
        stage_by_finalization: dict[int, str] = {}
    else:
        oracle_path = (FIX / oracle_route["path"]).resolve()
        stages = yaml.safe_load(oracle_path.read_text())["stages"]
        stage_sources = {
            stage["name"]: stage["sourceDocumentsWithThisReferences"]
            for stage in stages
        }
        stage_by_finalization = {
            route["ordinal"]: route["stage"]
            for route in oracle_route["finalizationStages"]
        }
    work_finalizations: dict[int, list[dict[str, Any]]] = {}
    checkpoint_finalizations: list[dict[str, Any]] = []
    for finalization in expected["tentativeFinalizations"]:
        boundary = finalization["boundary"]
        if boundary["kind"] == "WORK":
            work_finalizations.setdefault(
                boundary["afterWorkOrdinal"], []
            ).append(finalization)
        elif boundary["kind"] == "CHECKPOINT_SETTLEMENT":
            checkpoint_finalizations.append(finalization)

    def apply_finalization(
        finalization: dict[str, Any],
        owner_work: dict[str, Any] | None,
        boundary_prefix: str,
    ) -> None:
        stage_name = stage_by_finalization[finalization["ordinal"]]
        source_documents = stage_sources[stage_name]
        oracle = cyclic_set_oracle(source_documents)
        document_ids = [value["documentId"] for value in source_documents]
        generation = component_generations[frozenset(document_ids)]
        defaults = None
        if owner_work is not None:
            defaults = {
                "documentId": owner_work["targetDocumentId"],
                "scopePath": "/",
                "activationGeneration": 0,
                "contractKey": owner_work["channelKey"],
            }
        finalize_cyclic_component(
            oracle, trace, established, existing,
            stage=f"{boundary_prefix}.{stage_name}",
            component_generation=generation,
            document_ids=document_ids,
            work_occurrence_id=(
                owner_work["workIdentity"] if owner_work is not None else None
            ),
            attribution_defaults=defaults,
        )
        materialized = materialize_cyclic_members(source_documents, oracle)
        member_ids = oracle.member_ids_in_source_order()
        for index, document_id in enumerate(document_ids):
            documents[document_id] = materialized[index]
            current_blue_ids[document_id] = member_ids[index]
            active_generations[document_id] = generation

    occurrence_by_path = {
        (item["sourceDocumentId"], item["sourcePath"]): deepcopy(item)
        for item in fixture_input["occurrences"]
    }
    final_occurrence_by_path = {
        (item["sourceDocumentId"], item["sourcePath"]): deepcopy(item)
        for item in expected["occurrenceBindings"]
    }

    def active_adjacency() -> dict[str, set[str]]:
        adjacency = {document_id: set() for document_id in documents}
        for occurrence in occurrence_by_path.values():
            if occurrence["active"]:
                adjacency[occurrence["sourceDocumentId"]].add(
                    occurrence["targetDocumentId"]
                )
        return adjacency

    def component_partition(
        adjacency: dict[str, set[str]],
    ) -> list[frozenset[str]]:
        index = 0
        indexes: dict[str, int] = {}
        lowlinks: dict[str, int] = {}
        stack: list[str] = []
        on_stack: set[str] = set()
        components: list[frozenset[str]] = []

        def visit(document_id: str) -> None:
            nonlocal index
            indexes[document_id] = index
            lowlinks[document_id] = index
            index += 1
            stack.append(document_id)
            on_stack.add(document_id)
            for target in sorted(adjacency[document_id]):
                if target not in indexes:
                    visit(target)
                    lowlinks[document_id] = min(
                        lowlinks[document_id], lowlinks[target]
                    )
                elif target in on_stack:
                    lowlinks[document_id] = min(
                        lowlinks[document_id], indexes[target]
                    )
            if lowlinks[document_id] != indexes[document_id]:
                return
            members: set[str] = set()
            while True:
                member = stack.pop()
                on_stack.remove(member)
                members.add(member)
                if member == document_id:
                    break
            components.append(frozenset(members))

        for document_id in sorted(adjacency):
            if document_id not in indexes:
                visit(document_id)
        return components

    def component_shape(
        members: frozenset[str],
    ) -> tuple[frozenset[str], frozenset[tuple[str, str, str]]]:
        internal_edges = frozenset(
            (
                occurrence["sourceDocumentId"],
                occurrence["sourcePath"],
                occurrence["targetDocumentId"],
            )
            for occurrence in occurrence_by_path.values()
            if occurrence["active"]
            and occurrence["sourceDocumentId"] in members
            and occurrence["targetDocumentId"] in members
        )
        return members, internal_edges

    initial_partition = component_partition(active_adjacency())
    declared_generations = {
        frozenset(component["orderedMemberDocumentIds"]): component[
            "componentGeneration"
        ]
        for component in fixture_input["components"]
    }
    assert set(declared_generations) == set(initial_partition)
    shape_generations = {
        component_shape(members): generation
        for members, generation in declared_generations.items()
    }
    component_generations = dict(declared_generations)

    def assign_component_generations(
        preceding: list[frozenset[str]],
        resulting: list[frozenset[str]],
    ) -> None:
        nonlocal shape_generations, component_generations
        assigned_shapes: dict[
            tuple[frozenset[str], frozenset[tuple[str, str, str]]], int
        ] = {}
        assigned_members: dict[frozenset[str], int] = {}
        for component in resulting:
            shape = component_shape(component)
            generation = shape_generations.get(shape)
            if generation is None:
                predecessors = [
                    prior for prior in preceding
                    if prior.intersection(component)
                ]
                generation = 1 + max(
                    component_generations[prior]
                    for prior in predecessors
                )
            assigned_shapes[shape] = generation
            assigned_members[component] = generation
            for document_id in component:
                active_generations[document_id] = generation
        shape_generations = assigned_shapes
        component_generations = assigned_members

    def target_first_components(
        adjacency: dict[str, set[str]],
    ) -> list[frozenset[str]]:
        components = component_partition(adjacency)
        owner = {
            document_id: component
            for component in components
            for document_id in component
        }
        remaining = set(components)
        ordered: list[frozenset[str]] = []
        while remaining:
            ready = [
                component
                for component in remaining
                if not any(
                    owner[target] in remaining
                    and owner[target] != component
                    for source in component
                    for target in adjacency[source]
                )
            ]
            assert ready
            for component in sorted(ready, key=lambda value: tuple(sorted(value))):
                remaining.remove(component)
                ordered.append(component)
        return ordered

    def finalize_changed_acyclic(
        adjacency: dict[str, set[str]],
        *,
        reason_prefix: str,
        owner_work: dict[str, Any] | None,
    ) -> None:
        for component in target_first_components(adjacency):
            if len(component) != 1:
                continue
            document_id = next(iter(component))
            if document_id in adjacency[document_id]:
                continue
            body = deepcopy(documents[document_id])
            for occurrence in sorted(
                (
                    value
                    for value in occurrence_by_path.values()
                    if value["active"]
                    and value["sourceDocumentId"] == document_id
                ),
                key=lambda value: (
                    value["sourcePath"].encode("utf-8"),
                    value["occurrenceIdentity"],
                ),
            ):
                _reference_write_pointer(
                    body,
                    occurrence["sourcePath"],
                    {
                        "blueId": current_blue_ids[
                            occurrence["targetDocumentId"]
                        ]
                    },
                )
            after_blue_id = direct_blue_id(body)
            if current_blue_ids[document_id] == after_blue_id:
                documents[document_id] = body
                continue
            context = {
                "documentId": document_id,
                "componentGeneration": component_generations[component],
            }
            if owner_work is not None:
                context.update({
                    "scopePath": "/",
                    "activationGeneration": 0,
                    "contractKey": owner_work["channelKey"],
                    "workOccurrenceId": owner_work["workIdentity"],
                })
            establish_exact_value(
                body,
                trace,
                established,
                existing,
                reason_prefix=reason_prefix,
                context=context,
            )
            documents[document_id] = body
            current_blue_ids[document_id] = after_blue_id

    def embedded_route_plans(
        document_id: str,
        document: dict[str, Any],
    ) -> list[tuple[str, list[str], list[str]]]:
        active_paths: list[str] = []
        seen_active: set[str] = set()
        for occurrence in occurrence_by_path.values():
            path = occurrence["sourcePath"]
            if (
                occurrence["active"]
                and occurrence["sourceDocumentId"] == document_id
                and path not in seen_active
            ):
                seen_active.add(path)
                active_paths.append(path)

        plans: list[tuple[str, list[str], list[str]]] = []
        for contract_key, contract in document.get("contracts", {}).items():
            type_value = (
                contract.get("type") if isinstance(contract, dict) else None
            )
            if (
                not isinstance(type_value, dict)
                or type_value.get("blueId") != PROCESS_EMBEDDED
            ):
                continue
            exact_paths = list(contract.get("paths", []))
            collection_paths = list(contract.get("collectionPaths", []))
            declarations: list[str] = []
            seen_declarations: set[str] = set()
            for path in exact_paths + collection_paths:
                if path not in seen_declarations:
                    seen_declarations.add(path)
                    declarations.append(path)

            def claimed(path: str) -> bool:
                if path in exact_paths:
                    return True
                for collection in collection_paths:
                    prefix = collection.rstrip("/") + "/"
                    if path.startswith(prefix) and "/" not in path[len(prefix):]:
                        return True
                return False

            plans.append((
                contract_key,
                declarations,
                [path for path in active_paths if claimed(path)],
            ))
        return plans

    event_groups: dict[tuple[str, int], list[dict[str, Any]]] = {}
    for item in work:
        if item["kind"] in {"TRIGGERED_EVENT", "EMBEDDED_EVENT"}:
            event_groups.setdefault(
                (item["eventBlueId"], item["occurrenceOrdinal"]), []
            ).append(item)
    for rows in event_groups.values():
        rows.sort(key=lambda item: item["ordinal"])
    enqueued = {item["ordinal"] for item in external_work}
    emitted_events: set[tuple[str, int]] = set()
    dequeued_events: set[tuple[str, int]] = set()
    event_ordinal = 0
    marker_types = {
        "Hp3fNbpFxKwLiTwWAf3swpN7gKbsr6ofwEDMntiwXPaB",
        "9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR",
    }

    for work_item in work:
        ordinal = work_item["ordinal"]
        if work_item["kind"] in {"TRIGGERED_EVENT", "EMBEDDED_EVENT"}:
            event_key = (work_item["eventBlueId"], work_item["occurrenceOrdinal"])
            if event_key not in dequeued_events:
                assert event_key in emitted_events
                processor(
                    "internalEventDequeued", 1,
                    f"event.{event_key[1]}.dequeue",
                )
                for delivery_index, delivery in enumerate(event_groups[event_key]):
                    context = work_context(delivery, include_work_path=True)
                    processor(
                        "closureWorkOccurrenceEnqueued", 1,
                        f"event.{event_key[1]}.delivery.{delivery_index}.enqueue",
                        context,
                    )
                    enqueued.add(delivery["ordinal"])
                dequeued_events.add(event_key)
        assert ordinal in enqueued
        # Queue construction freezes the then-current component generation,
        # while execution is attributed to the generation selected when the
        # independently managed document is actually dequeued.  The document
        # step keeps that generation for its complete callback frame.
        work_generations[ordinal] = active_generations[
            work_item["targetDocumentId"]
        ]
        processor(
            "closureWorkOccurrenceDequeued", 1,
            f"work.{ordinal}.dequeue",
            work_context(work_item, include_work_path=True),
        )
        processor(
            "scopeOpened", 1, "participating-scope", work_context(work_item)
        )
        document_id = work_item["targetDocumentId"]
        for contract_key, contract in documents[document_id].get(
            "contracts", {}
        ).items():
            type_value = contract.get("type") if isinstance(contract, dict) else None
            type_id = (
                type_value.get("blueId")
                if isinstance(type_value, dict) and set(type_value) == {"blueId"}
                else None
            )
            if type_id not in marker_types:
                processor(
                    "contractHeaderRecognized", 1,
                    "participating-contract-header",
                    work_context(work_item, contract_key=contract_key),
                )
        for contract_key, declarations, concrete in embedded_route_plans(
            document_id, documents[document_id]
        ):
            for source_path in declarations:
                context = work_context(
                    work_item,
                    logical_path=source_path,
                )
                processor("embeddedPathEntryRead", 1, "route", context)
                segments = len(
                    [part for part in source_path.split("/") if part]
                )
                if segments:
                    processor(
                        "embeddedPathSegmentValidated", segments, "route", context
                    )
            sort_context = work_context(
                work_item,
                contract_key=contract_key,
                logical_path="/",
            )
            for _left, _right, read in stable_route_sort_comparisons(
                sorted(concrete)
            ):
                charge(
                    "semantic", "sortComparison", 1,
                    "route", sort_context,
                )
                charge(
                    "semantic", "scalarComparison", 1,
                    "route", sort_context,
                )
                blocks = math.ceil(read / 64)
                charge(
                    "semantic", "textBlockExamined", blocks,
                    "route", sort_context,
                )
                charge(
                    "semantic", "textBlockExamined", blocks,
                    "route", sort_context,
                )
        delivered = {
            "TRIGGERED_EVENT": ("triggeredEventDelivered", "triggered-event"),
            "EMBEDDED_EVENT": ("embeddedEventDelivered", "embedded-event"),
        }.get(work_item["kind"])
        if delivered is not None:
            processor(delivered[0], 1, delivered[1], work_context(work_item))

        handlers = []
        for handler_key, contract in documents[document_id].get(
            "contracts", {}
        ).items():
            if isinstance(contract, dict) and contract.get("channel") == work_item[
                "channelKey"
            ]:
                handlers.append((contract.get("order", 0), handler_key))
        handlers.sort()
        pending_finalizations = list(work_finalizations.get(ordinal, []))
        for handler_index, (_order, handler_key) in enumerate(handlers):
            result = fixture["runtime"]["handlers"][
                f"{document_id}/{handler_key}"
            ]
            processor(
                "handlerCandidateTested", 1, "matching",
                work_context(work_item, contract_key=handler_key),
            )
            processor(
                "handlerCall", 1, "handler-call",
                work_context(work_item, contract_key=handler_key),
            )
            charge(
                "runtime", "scriptedResultApplied", 1, "unspecified",
                work_context(work_item),
            )
            for patch in result.get("patches", []):
                before_adjacency = active_adjacency()
                before_partition = component_partition(before_adjacency)
                processor(
                    "patchBoundaryChecked", 1, "patch-boundary",
                    work_context(work_item),
                )
                processor(
                    "patchRemove" if patch["op"] == "remove"
                    else "patchAddOrReplace",
                    1,
                    "application-remove" if patch["op"] == "remove"
                    else "application-patch",
                    work_context(work_item),
                )
                identity_context = work_context(
                    work_item, logical_path=patch["path"]
                )
                documents[document_id] = (
                    _reference_charge_direct_patch_result(
                        documents[document_id],
                        patch,
                        charge=charge,
                        context=identity_context,
                    )
                )
                occurrence_key = (document_id, patch["path"])
                before_occurrence = occurrence_by_path.get(occurrence_key)
                final_occurrence = final_occurrence_by_path.get(
                    occurrence_key
                )
                if before_occurrence is not None or final_occurrence is not None:
                    after_occurrence = deepcopy(
                        final_occurrence
                        if final_occurrence is not None
                        else before_occurrence
                    )
                    if patch["op"] == "remove":
                        after_occurrence["active"] = False
                    occurrence_by_path[occurrence_key] = after_occurrence

                after_adjacency = active_adjacency()
                if before_adjacency != after_adjacency:
                    context = work_context(work_item, include_work_path=True)
                    reason = f"work.{ordinal}.topology-change"
                    activated = int(
                        before_occurrence is not None
                        and not before_occurrence["active"]
                        and occurrence_by_path[occurrence_key]["active"]
                    )
                    if activated:
                        processor(
                            "processEmbeddedEdgeExamined", activated,
                            reason, context,
                        )
                        processor(
                            "managedOccurrenceBindingVerified", activated,
                            reason, context,
                        )
                    after_partition = component_partition(after_adjacency)
                    if frozenset(before_partition) != frozenset(after_partition):
                        processor(
                            "componentPartitionChanged", 1, reason, context
                        )
                    assign_component_generations(
                        before_partition, after_partition
                    )
                    processor(
                        "componentMemberPartitioned", len(documents),
                        reason, context,
                    )
                    active_edge_count = sum(
                        int(occurrence["active"])
                        for occurrence in occurrence_by_path.values()
                    )
                    if active_edge_count:
                        processor(
                            "componentEdgePartitioned",
                            active_edge_count, reason, context,
                        )
                finalize_changed_acyclic(
                    after_adjacency,
                    reason_prefix=(
                        f"work.{ordinal}.acyclic-finalization"
                    ),
                    owner_work=work_item,
                )
            if handler_index == 0:
                for finalization in pending_finalizations:
                    apply_finalization(
                        finalization, work_item, f"work.{ordinal}"
                    )
                pending_finalizations = []
            for event in result.get("events", []):
                event_key = (direct_blue_id(event), event_ordinal)
                emitted_events.add(event_key)
                context = work_context(work_item, include_work_path=True)
                if fixture_input["documents"][document_id].get("publicRoot"):
                    processor(
                        "rootEventRecorded", 1,
                        f"event.{event_ordinal}.public-record", context,
                    )
                processor(
                    "internalEventEnqueued", 1,
                    f"event.{event_ordinal}.enqueue", context,
                )
                if event_key not in event_groups:
                    processor(
                        "internalEventDequeued", 1,
                        f"event.{event_ordinal}.dequeue",
                    )
                    dequeued_events.add(event_key)
                event_ordinal += 1
        for finalization in pending_finalizations:
            apply_finalization(finalization, work_item, f"work.{ordinal}")

    assert emitted_events == dequeued_events

    for settlement_index, work_item in enumerate(sorted(
        external_work,
        key=lambda item: (
            direct_for_work[item["ordinal"]]["rawOccurrenceOrder"],
            item["ordinal"],
        ),
    )):
        raw_order = direct_for_work[work_item["ordinal"]]["rawOccurrenceOrder"]
        document_id = work_item["targetDocumentId"]
        source_key = work_item["channelKey"]
        processor(
            "checkpointWritten", 1,
            f"checkpoint-settlement.{settlement_index}.write.{raw_order}",
            {
                "documentId": document_id,
                "scopePath": "/",
                "activationGeneration": 0,
                "componentGeneration": active_generations[document_id],
                "contractKey": source_key,
            },
        )
        body = documents[document_id]
        contracts = body.setdefault("contracts", {})
        marker = contracts.get("checkpoint")
        marker_path = "/contracts/checkpoint"
        if marker is None:
            marker = {
                "type": {
                    "blueId": "9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR"
                },
                "entries": {},
            }
            marker_context = {"logicalPath": marker_path}
            documents[document_id] = (
                _reference_charge_direct_patch_result(
                    body,
                    {
                        "op": "add",
                        "path": marker_path,
                        "val": marker,
                    },
                    charge=charge,
                    context=marker_context,
                )
            )
            body = documents[document_id]
            contracts = body["contracts"]
            marker = contracts["checkpoint"]
        source_contract = contracts[source_key]
        domain_value = {
            "contractsVersion": "1.0",
            "effectiveTypeBlueId": source_contract["type"]["blueId"],
            "sourceContributionNodeBlueIds": [direct_blue_id(source_contract)],
        }
        discriminator = source_contract.get("checkpointDomain")
        if discriminator:
            domain_value["runtimeDiscriminator"] = discriminator
        entry = {
            "domain": {"blueId": direct_blue_id(domain_value)},
            "subject": {"blueId": work_item["eventBlueId"]},
        }
        entry_path = (
            "/contracts/checkpoint/entries/"
            + source_key.replace("~", "~0").replace("/", "~1")
        )
        entry_context = {"logicalPath": entry_path}
        documents[document_id] = _reference_charge_direct_patch_result(
            body,
            {
                "op": (
                    "replace" if source_key in marker["entries"] else "add"
                ),
                "path": entry_path,
                "val": entry,
            },
            charge=charge,
            context=entry_context,
        )

    source_checkpoint_keys = {
        (work_item["targetDocumentId"], work_item["channelKey"])
        for work_item in external_work
    }
    final_documents = resulting_documents(expected)
    ordered_document_ids = sorted(
        final_documents,
        key=lambda document_id: domain_identity(
            "blue-contracts-managed-scope-key/1.0",
            {
                "documentId": document_id,
                "scopePath": "/",
                "activationGeneration": 0,
            },
        ),
    )
    for document_id in ordered_document_ids:
        before_entries = (
            fixture_input["documents"][document_id]["document"]
            .get("contracts", {}).get("checkpoint", {}).get("entries", {})
        )
        after_record = final_documents[document_id]
        after_entries = (
            after_record["document"].get("contracts", {})
            .get("checkpoint", {}).get("entries", {})
        )
        for raw_key in sorted(set(before_entries) | set(after_entries)):
            if (document_id, raw_key) in source_checkpoint_keys:
                continue
            if before_entries.get(raw_key) == after_entries.get(raw_key):
                continue
            assert raw_key not in after_entries
            processor(
                "checkpointWritten", 1,
                f"checkpoint-settlement.cleanup.{document_id}.{raw_key}",
                {
                    "documentId": document_id,
                    "scopePath": "/",
                    "activationGeneration": 0,
                    "componentGeneration": after_record[
                        "componentGeneration"
                    ],
                    "contractKey": raw_key,
                },
            )
            entry_path = (
                "/contracts/checkpoint/entries/"
                + raw_key.replace("~", "~0").replace("/", "~1")
            )
            documents[document_id] = (
                _reference_charge_direct_patch_result(
                    documents[document_id],
                    {"op": "remove", "path": entry_path},
                    charge=charge,
                    context={"logicalPath": entry_path},
                )
            )

    for finalization in checkpoint_finalizations:
        apply_finalization(
            finalization, None, "checkpoint-settlement"
        )
    if external_work:
        finalize_changed_acyclic(
            active_adjacency(),
            reason_prefix="checkpoint-settlement.0.acyclic-finalization",
            owner_work=None,
        )
    return trace.entries, trace.total, gas_trace_identity(trace.entries)


def oracle_stage_candidates(value: Any) -> list[dict[str, Any]]:
    """Discover every stage-shaped record using the package validator rule."""
    candidates: list[dict[str, Any]] = []
    if isinstance(value, dict):
        if {
            "name",
            "sourceDocumentsWithThisReferences",
            "masterBlueId",
            "memberBlueIdsInSourceOrder",
            "canonicalMasterInputUtf8Bytes",
        }.issubset(value):
            candidates.append(value)
        for child in value.values():
            candidates.extend(oracle_stage_candidates(child))
    elif isinstance(value, list):
        for child in value:
            candidates.extend(oracle_stage_candidates(child))
    return candidates


def check_oracle(path: Path) -> int:
    stages = oracle_stage_candidates(load_yaml(path))
    checked = 0
    for stage in stages:
        docs = stage["sourceDocumentsWithThisReferences"]
        oracle = cyclic_set_oracle(docs)
        assert oracle.master_blue_id == stage["masterBlueId"], (path.name, stage["name"], "master")
        assert list(oracle.member_ids_in_source_order()) == stage["memberBlueIdsInSourceOrder"], (path.name, stage["name"], "members")
        assert list(oracle.sorted_source_indices) == stage["sortedSourceIndices"], (path.name, stage["name"], "order")
        assert materialize_cyclic_members(docs, oracle) == stage["materializedDocuments"], (path.name, stage["name"], "materialized")
        assert cyclic_canonical_limit_form(oracle) == stage["canonicalLimitForm"], (path.name, stage["name"], "canonical-limit-form")
        canonical_bytes = cyclic_canonical_limit_bytes(oracle)
        assert canonical_bytes == stage["canonicalMasterInputUtf8Bytes"], (path.name, stage["name"], "canonical-bytes")
        checked += 1
    return checked


def check_finite() -> dict[str, Any]:
    fixture = load_yaml(FIX / "c-clo-02-dynamic-finite-cycle.yaml")
    expected = fixture["expected"]
    assert [w["targetDocumentId"] for w in expected["workTrace"]] == ["a", "a", "b", "a"]
    assert [w["kind"] for w in expected["workTrace"]] == ["EXTERNAL_DELIVERY", "TRIGGERED_EVENT", "EMBEDDED_EVENT", "EMBEDDED_EVENT"]
    stages = expected["tentativeFinalizations"]
    stage_routes = sorted(
        fixture["oracle"]["finalizationStages"], key=lambda item: item["ordinal"]
    )
    assert [item["ordinal"] for item in stage_routes] == list(range(len(stages)))
    assert [item["stage"] for item in stage_routes] == [
        "transition-0-cycle-formed",
        "transition-1-a-local-x",
        "transition-2-b-handled-x",
        "transition-3-a-finished",
        "checkpoint-settlement-0",
    ]
    assert [s["boundary"] for s in stages] == [
        {"kind": "WORK", "afterWorkOrdinal": 0},
        {"kind": "WORK", "afterWorkOrdinal": 1},
        {"kind": "WORK", "afterWorkOrdinal": 2},
        {"kind": "WORK", "afterWorkOrdinal": 3},
        {"kind": "CHECKPOINT_SETTLEMENT"},
    ]
    documents = resulting_documents(expected)
    assert documents["a"]["document"]["result"] == "done"
    assert documents["a"]["document"]["localXSeen"] == 1
    assert documents["b"]["document"]["xHandled"] == 1
    x_event = fixture["runtime"]["handlers"]["a/start"]["events"][0]
    x_event_blue_id = direct_blue_id(x_event)
    assert expected["publicEvents"] == [
        {
            "publicEventOrdinal": 0,
            "eventOccurrenceOrdinal": 0,
            "publicRootDocumentId": "a",
            "eventOccurrenceIdentity": domain_identity(
                "blue-contracts-event-occurrence/1.0",
                {
                    "invocationIdentity": expected["invocationIdentity"],
                    "eventOccurrenceOrdinal": 0,
                    "eventBlueId": x_event_blue_id,
                },
            ),
            "eventBlueId": x_event_blue_id,
            "event": x_event,
        }
    ]
    trace, total, trace_identity = derive_finite_trace(fixture)
    assert trace == expected["gasTrace"]
    assert total == expected["totalGas"]
    assert trace_identity == expected["gasTraceIdentity"]
    assert all(e["sequence"] == i for i, e in enumerate(trace))
    identity_fixture = load_yaml(FIX / "c-clo-03-exact-tentative-identity-visibility.yaml")
    observations = identity_fixture["expected"]["observations"]
    assert observations[0]["observedBlueId"] == stages[1]["memberBlueIds"]["a"]
    assert observations[1]["observedBlueId"] == stages[2]["memberBlueIds"]["b"]
    return {"work": len(expected["workTrace"]), "finalizations": len(stages), "gas": expected["totalGas"]}


def check_loop() -> dict[str, Any]:
    cases = [
        ("c-clo-04-same-event-gas-loop.yaml", "loop"),
        ("c-clo-04-default-policy-loop.yaml", "default-loop"),
        ("c-clo-25-policy-identity-binding.yaml", "loop"),
        ("c-clo-26-embedded-local-cap.yaml", "local-cap"),
    ]
    checked: dict[str, dict[str, Any]] = {}
    for name, invocation in cases:
        fixture = load_yaml(FIX / name)
        expected = fixture["expected"]
        trace, total, completed, rejected_work, rejected_charge = derive_loop_trace(
            fixture, invocation
        )
        expected_rejected_work = expected.get("rejectedWorkOccurrence")

        def distinct_work(
            admitted: list[dict[str, Any]],
            rejected: dict[str, Any] | None,
        ) -> list[dict[str, Any]]:
            if rejected is None:
                return list(admitted)
            if rejected["ordinal"] == len(admitted):
                return [*admitted, rejected]
            assert admitted
            assert rejected["ordinal"] == len(admitted) - 1
            assert rejected == admitted[-1]
            return list(admitted)

        derived_work = distinct_work(completed, rejected_work)
        expected_work = distinct_work(
            expected["workTrace"], expected_rejected_work
        )
        assert len(derived_work) == len(expected_work)
        identity_map: dict[str, str] = {}
        identity_fields = {"workIdentity", "targetManagedScopeIdentity", "sourceOccurrenceIdentity"}
        for derived_item, expected_item in zip(derived_work, expected_work):
            assert {key: value for key, value in derived_item.items() if key not in identity_fields} == {
                key: value for key, value in expected_item.items() if key not in identity_fields
            }
            identity_map[derived_item["workIdentity"]] = expected_item["workIdentity"]
        rebound_trace = deepcopy(trace)
        for entry in rebound_trace:
            if entry.get("workOccurrenceId") in identity_map:
                entry["workOccurrenceId"] = identity_map[entry["workOccurrenceId"]]
        assert expected["status"] == "gas-limit-exceeded"
        assert expected["rollbackToInput"] is True
        assert expected["publicEvents"] == []
        assert gas_trace(fixture, name) == rebound_trace
        assert expected["totalGas"] == total
        assert expected["gasTraceIdentity"] == gas_trace_identity(
            rebound_trace
        )
        rejected_fields = (
            "namespace",
            "counter",
            "quantity",
            "weight",
            "subtotal",
            "applicableCap",
            "remainingBeforeCharge",
        )
        assert {
            key: expected["rejectedCharge"][key] for key in rejected_fields
        } == rejected_charge
        owner = (
            {
                "kind": "WORK",
                "workOccurrenceIdentity": expected_rejected_work["workIdentity"],
            }
            if expected_rejected_work is not None
            else {"kind": "INVOCATION"}
        )
        assert expected["rejectedCharge"]["owner"] == owner
        rejected_basis = {
            key: expected["rejectedCharge"][key]
            for key in (*rejected_fields, "owner")
        }
        assert expected["rejectedCharge"]["rejectedChargeIdentity"] == domain_identity(
            "blue-contracts-rejected-charge/1.0", rejected_basis
        )
        if expected_rejected_work is not None:
            assert expected_rejected_work["ordinal"] in {
                len(expected["workTrace"]) - 1,
                len(expected["workTrace"]),
            }
            if expected_rejected_work["ordinal"] < len(expected["workTrace"]):
                assert expected_rejected_work == expected["workTrace"][-1]
        else:
            assert rejected_work is None
        assert rejected_charge["subtotal"] > rejected_charge["remainingBeforeCharge"]
        checked[name] = expected
    expected = checked["c-clo-04-same-event-gas-loop.yaml"]
    assert expected["rejectedCharge"]["applicableCap"] == {"kind": "SHARED"}
    default = checked["c-clo-04-default-policy-loop.yaml"]
    assert default["totalGas"] <= 100000
    local = checked["c-clo-26-embedded-local-cap.yaml"]
    assert local["rejectedCharge"]["applicableCap"] == {"kind": "LOCAL", "documentId": "loop-b"}
    return {
        "overrideAcceptedWork": len(expected["workTrace"]),
        "overrideGas": expected["totalGas"],
        "defaultGas": default["totalGas"],
        "localCapGas": local["totalGas"],
    }


def derive_split_trace(fixture: dict[str, Any]) -> tuple[list[dict[str, Any]], int]:
    trace = GasReferenceTrace(fixture["input"]["gasPolicy"]["sharedLimit"])
    existing = collect_existing_descendant_ids(
        record["document"] for record in fixture["input"]["documents"].values()
    )
    established: set[str] = set()
    work = fixture["expected"]["workTrace"]
    before_settlement = pre_checkpoint_documents(fixture)

    def processor(
        counter: str,
        quantity: int,
        reason: str,
        work_index: int | None = None,
        document_id: str | None = None,
        component_generation: int | None = None,
        contract_key: str | None = None,
    ) -> None:
        context: dict[str, Any] = {}
        if work_index is not None:
            work_item = work[work_index]
            target = work_item["targetDocumentId"]
            context.update(
                {
                    "documentId": target,
                    "scopePath": "/",
                    "activationGeneration": 0,
                    "componentGeneration": fixture["input"]["documents"][target][
                        "componentGeneration"
                    ],
                    "logicalPath": f"work/{work_item['ordinal']}",
                    "workOccurrenceId": work_item["workIdentity"],
                }
            )
        if document_id is not None:
            context["documentId"] = document_id
        if component_generation is not None:
            context["componentGeneration"] = component_generation
        if contract_key is not None:
            context["contractKey"] = contract_key
        assert trace.charge(
            "processor", counter, quantity, reason=reason, context=context or None
        )

    processor("processInvocation", 1, "admission.process")
    processor("closureInvocation", 1, "admission.closure")
    processor("deliverySnapshotEntry", 2, "admission.direct-deliveries")
    for document_id in sorted(fixture["input"]["documents"]):
        processor(
            "managedDocumentOpened",
            1,
            f"admission.document.{document_id}",
            document_id=document_id,
        )
    active_edges = [
        occurrence
        for occurrence in fixture["input"]["occurrences"]
        if occurrence["active"]
    ]
    for occurrence in sorted(
        fixture["input"]["occurrences"],
        key=lambda item: (item["occurrenceIdentity"], item["bindingIdentity"]),
    ):
        processor(
            "managedOccurrenceBindingVerified",
            1,
            f"admission.binding.{occurrence['occurrenceIdentity']}",
            document_id=occurrence["sourceDocumentId"],
        )
        processor(
            "processEmbeddedEdgeExamined",
            1,
            f"admission.edge.{occurrence['occurrenceIdentity']}",
            document_id=occurrence["sourceDocumentId"],
        )
    for component in fixture["input"]["components"]:
        members = set(component["orderedMemberDocumentIds"])
        for document_id in component["orderedMemberDocumentIds"]:
            processor(
                "componentMemberPartitioned",
                1,
                "admission.component-member",
                document_id=document_id,
            )
        for occurrence in active_edges:
            if (
                occurrence["sourceDocumentId"] in members
                and occurrence["targetDocumentId"] in members
            ):
                processor(
                    "componentEdgePartitioned",
                    1,
                    "admission.component-edge",
                    document_id=occurrence["sourceDocumentId"],
                )
    for work_index in range(2):
        processor(
            "checkpointCompared",
            1,
            f"direct-admission.{work_index}.checkpoint-compare",
            work_index,
        )
    for work_index in range(2):
        processor(
            "closureWorkOccurrenceEnqueued",
            1,
            f"work.{work_index}.seed-enqueue",
            work_index,
        )

    for work_index, document_id in enumerate(("simple-a", "simple-b")):
        handler_prefix = f"work.{work_index}.handler.0.start"
        for counter, quantity, reason, contract_key in [
            ("closureWorkOccurrenceDequeued", 1, f"work.{work_index}.dequeue", None),
            ("scopeOpened", 1, f"work.{work_index}.scope", None),
            ("contractHeaderRecognized", 1, f"work.{work_index}.contracts", None),
            ("channelCandidateTested", 1, f"work.{work_index}.channel-test", "source"),
            ("channelAccepted", 1, f"work.{work_index}.channel-accepted", "source"),
            ("handlerCandidateTested", 1, f"{handler_prefix}.candidate", "start"),
            ("handlerCall", 1, f"{handler_prefix}.call", "start"),
            ("patchBoundaryChecked", 1, f"{handler_prefix}.patch.0.boundary", None),
            ("pointerSegmentTraversed", 1, f"{handler_prefix}.patch.0.pointer", None),
            ("patchRemove", 1, f"{handler_prefix}.patch.0.remove", None),
            ("processEmbeddedEdgeExamined", 1, f"{handler_prefix}.patch.0.edge", None),
            ("componentPartitionChanged", 1, f"{handler_prefix}.patch.0.partition-change", None),
            ("componentMemberPartitioned", 2, f"{handler_prefix}.patch.0.partition-members", None),
        ]:
            processor(
                counter,
                quantity,
                reason,
                work_index,
                contract_key=contract_key,
            )
        establish_exact_value(
            before_settlement[document_id],
            trace,
            established,
            existing,
            reason_prefix=f"work.{work_index}.acyclic-result",
            context={
                "documentId": document_id,
                "componentGeneration": 2,
                "workOccurrenceId": work[work_index]["workIdentity"],
            },
        )

    documents = resulting_documents(fixture["expected"])
    for work_index, document_id in enumerate(("simple-a", "simple-b")):
        processor(
            "checkpointWritten",
            1,
            f"checkpoint-settlement.{work_index}.write",
            work_index,
        )
    for document_id in ("simple-a", "simple-b"):
        processor(
            "tentativeComponentFinalization",
            1,
            f"checkpoint-settlement.acyclic-finalize.{document_id}",
            document_id=document_id,
            component_generation=2,
        )
        establish_exact_value(
            documents[document_id]["document"],
            trace,
            established,
            existing,
            reason_prefix=f"checkpoint-settlement.acyclic-result.{document_id}",
            context={"documentId": document_id, "componentGeneration": 2},
        )
    return trace.entries, trace.total


def derive_containing_spine_trace(fixture: dict[str, Any]) -> tuple[list[dict[str, Any]], int]:
    trace = GasReferenceTrace(fixture["input"]["gasPolicy"]["sharedLimit"])
    existing = collect_existing_descendant_ids(
        record["document"] for record in fixture["input"]["documents"].values()
    )
    established: set[str] = set()
    work = fixture["expected"]["workTrace"][0]
    before_settlement = pre_checkpoint_documents(fixture)

    def processor(
        counter: str,
        quantity: int,
        reason: str,
        document_id: str | None = None,
        component_generation: int | None = None,
        contract_key: str | None = None,
        work_context: bool = False,
    ) -> None:
        context: dict[str, Any] = {}
        if work_context:
            context.update({
                "documentId": work["targetDocumentId"],
                "scopePath": "/",
                "activationGeneration": 0,
                "componentGeneration": fixture["input"]["documents"][
                    work["targetDocumentId"]
                ]["componentGeneration"],
                "logicalPath": f"work/{work['ordinal']}",
                "workOccurrenceId": work["workIdentity"],
            })
        if document_id is not None:
            context["documentId"] = document_id
        if component_generation is not None:
            context["componentGeneration"] = component_generation
        if contract_key is not None:
            context["contractKey"] = contract_key
        assert trace.charge(
            "processor", counter, quantity, reason=reason, context=context or None
        )

    processor("processInvocation", 1, "admission.process")
    processor("closureInvocation", 1, "admission.closure")
    processor("deliverySnapshotEntry", 1, "admission.direct-deliveries")
    for document_id in sorted(fixture["input"]["documents"]):
        processor(
            "managedDocumentOpened",
            1,
            f"admission.document.{document_id}",
            document_id=document_id,
        )
    active_edges = [
        occurrence
        for occurrence in fixture["input"]["occurrences"]
        if occurrence["active"]
    ]
    for occurrence in sorted(
        fixture["input"]["occurrences"],
        key=lambda item: (item["occurrenceIdentity"], item["bindingIdentity"]),
    ):
        processor(
            "managedOccurrenceBindingVerified",
            1,
            f"admission.binding.{occurrence['occurrenceIdentity']}",
            document_id=occurrence["sourceDocumentId"],
        )
        processor(
            "processEmbeddedEdgeExamined",
            1,
            f"admission.edge.{occurrence['occurrenceIdentity']}",
            document_id=occurrence["sourceDocumentId"],
        )
    for component in fixture["input"]["components"]:
        members = set(component["orderedMemberDocumentIds"])
        for document_id in component["orderedMemberDocumentIds"]:
            processor(
                "componentMemberPartitioned",
                1,
                "admission.component-member",
                document_id=document_id,
            )
        for occurrence in active_edges:
            if (
                occurrence["sourceDocumentId"] in members
                and occurrence["targetDocumentId"] in members
            ):
                processor(
                    "componentEdgePartitioned",
                    1,
                    "admission.component-edge",
                    document_id=occurrence["sourceDocumentId"],
                )
    processor(
        "checkpointCompared",
        1,
        "direct-admission.0.checkpoint-compare",
        work_context=True,
    )
    processor(
        "closureWorkOccurrenceEnqueued",
        1,
        "work.0.seed-enqueue",
        work_context=True,
    )
    handler_prefix = "work.0.handler.0.start"
    for counter, quantity, reason, contract_key in [
        ("closureWorkOccurrenceDequeued", 1, "work.0.dequeue", None),
        ("scopeOpened", 1, "work.0.scope", None),
        ("contractHeaderRecognized", 1, "work.0.contracts", None),
        ("channelCandidateTested", 1, "work.0.channel-test", "source"),
        ("channelAccepted", 1, "work.0.channel-accepted", "source"),
        ("handlerCandidateTested", 1, f"{handler_prefix}.candidate", "start"),
        ("handlerCall", 1, f"{handler_prefix}.call", "start"),
        ("patchBoundaryChecked", 1, f"{handler_prefix}.patch.0.boundary", None),
        ("pointerSegmentTraversed", 1, f"{handler_prefix}.patch.0.pointer", None),
        ("patchRemove", 1, f"{handler_prefix}.patch.0.remove", None),
        ("processEmbeddedEdgeExamined", 1, f"{handler_prefix}.patch.0.edge", None),
        ("componentPartitionChanged", 1, f"{handler_prefix}.patch.0.partition-change", None),
        ("componentMemberPartitioned", 4, f"{handler_prefix}.patch.0.partition-members", None),
        ("componentEdgePartitioned", 3, f"{handler_prefix}.patch.0.partition-edges", None),
    ]:
        processor(
            counter,
            quantity,
            reason,
            contract_key=contract_key,
            work_context=True,
        )

    establish_exact_value(
        before_settlement["a"],
        trace,
        established,
        existing,
        reason_prefix="work.0.acyclic-result",
        context={
            "documentId": "a",
            "componentGeneration": 2,
            "workOccurrenceId": work["workIdentity"],
        },
    )
    for document_id, generation in [
        ("b", 2),
        ("container-inner", 1),
        ("container-root", 1),
    ]:
        processor(
            "tentativeComponentFinalization",
            1,
            f"work.0.acyclic-finalize.{document_id}",
            document_id,
            generation,
            work_context=True,
        )
        processor(
            "containingReferenceUpdated",
            1,
            f"work.0.containing-reference.{document_id}",
            document_id,
            generation,
            work_context=True,
        )
        establish_exact_value(
            before_settlement[document_id],
            trace,
            established,
            existing,
            reason_prefix=f"work.0.acyclic-result.{document_id}",
            context={"documentId": document_id, "componentGeneration": generation, "workOccurrenceId": work["workIdentity"]},
        )

    processor(
        "checkpointWritten",
        1,
        "checkpoint-settlement.0.write",
        work_context=True,
    )
    output = resulting_documents(fixture["expected"])
    checkpoint_targets = {"a"}
    for document_id, generation in [
        ("a", 2),
        ("b", 2),
        ("container-inner", 1),
        ("container-root", 1),
    ]:
        processor(
            "tentativeComponentFinalization",
            1,
            f"checkpoint-settlement.acyclic-finalize.{document_id}",
            document_id,
            generation,
        )
        if document_id not in checkpoint_targets:
            processor(
                "containingReferenceUpdated",
                1,
                f"checkpoint-settlement.containing-reference.{document_id}",
                document_id,
                generation,
            )
        establish_exact_value(
            output[document_id]["document"],
            trace,
            established,
            existing,
            reason_prefix=f"checkpoint-settlement.acyclic-result.{document_id}",
            context={"documentId": document_id, "componentGeneration": generation},
        )
    return trace.entries, trace.total


def check_identity_transition_gas() -> dict[str, Any]:
    cases = [
        (
            "c-clo-09-merge-two-cycles.yaml",
            lambda fixture: derive_finite_trace(fixture)[:2],
        ),
        (
            "c-clo-10-split-to-singletons.yaml",
            lambda fixture: derive_finite_trace(fixture)[:2],
        ),
        (
            "c-clo-11-split-into-two-cycles.yaml",
            lambda fixture: derive_finite_trace(fixture)[:2],
        ),
        (
            "c-clo-13-frozen-edge-addition.yaml",
            lambda fixture: derive_finite_trace(fixture)[:2],
        ),
        (
            "c-clo-28-containing-spine-identity-gas.yaml",
            lambda fixture: derive_finite_trace(fixture)[:2],
        ),
        (
            "c-clo-28-mixed-result-shape.yaml",
            lambda fixture: derive_finite_trace(fixture)[:2],
        ),
    ]
    result: dict[str, Any] = {}
    for name, derive in cases:
        fixture = load_yaml(FIX / name)
        trace, total = derive(fixture)
        assert trace == fixture["expected"]["gasTrace"]
        assert total == fixture["expected"]["totalGas"]
        assert fixture["expected"]["gasTraceIdentity"] == gas_trace_identity(
            trace
        )
        result[Path(name).stem] = {"entries": len(trace), "gas": total}
    return result


def derive_managed_revision_trace(
    fixture: dict[str, Any],
) -> tuple[list[dict[str, Any]], int, dict[str, Any], dict[str, str]]:
    """Replay one revision receipt without using the fixture refiner.

    The receipt-owned direct rewrite and every acyclic containing ancestor are
    finalized before an authoritative-head reconciliation is allowed to turn
    the last historical step into a cycle.
    """
    fixture_input = fixture["input"]
    expected = fixture["expected"]
    cause = fixture_input["cause"]
    assert cause["kind"] == "managed-revision"
    work = expected["workTrace"][0]
    gas_policy = fixture_input["gasPolicy"]
    trace = GasReferenceTrace(
        gas_policy["sharedLimit"], gas_policy.get("localLimits", {})
    )
    established: set[str] = set()
    existing = collect_existing_descendant_ids(
        record["document"] for record in fixture_input["documents"].values()
    )

    def processor(
        counter: str,
        quantity: int,
        reason: str,
        *,
        work_context: bool = False,
        document_id: str | None = None,
        component_generation: int | None = None,
        contract_key: str | None = None,
    ) -> None:
        context: dict[str, Any] = {}
        if work_context:
            target = work["targetDocumentId"]
            context.update({
                "documentId": target,
                "scopePath": "/",
                "activationGeneration": 0,
                "componentGeneration": fixture_input["documents"][target].get(
                    "componentGeneration", 0
                ),
                "logicalPath": f"work/{work['ordinal']}",
                "workOccurrenceId": work["workIdentity"],
            })
        if document_id is not None:
            context["documentId"] = document_id
        if component_generation is not None:
            context["componentGeneration"] = component_generation
        if contract_key is not None:
            context["contractKey"] = contract_key
        assert trace.charge(
            "processor", counter, quantity, reason=reason, context=context or None
        )

    processor("processInvocation", 1, "admission.process")
    processor("closureInvocation", 1, "admission.closure")
    for document_id in sorted(fixture_input["documents"]):
        processor(
            "managedDocumentOpened",
            1,
            f"admission.document.{document_id}",
            document_id=document_id,
        )
    active_occurrences = [
        occurrence
        for occurrence in fixture_input["occurrences"]
        if occurrence["active"]
    ]
    for occurrence in sorted(
        fixture_input["occurrences"],
        key=lambda item: (item["occurrenceIdentity"], item["bindingIdentity"]),
    ):
        processor(
            "managedOccurrenceBindingVerified",
            1,
            f"admission.binding.{occurrence['occurrenceIdentity']}",
            document_id=occurrence["sourceDocumentId"],
        )
        if occurrence["active"]:
            processor(
                "processEmbeddedEdgeExamined",
                1,
                f"admission.edge.{occurrence['occurrenceIdentity']}",
                document_id=occurrence["sourceDocumentId"],
            )
    for component in fixture_input["components"]:
        members = set(component["orderedMemberDocumentIds"])
        for document_id in component["orderedMemberDocumentIds"]:
            processor(
                "componentMemberPartitioned",
                1,
                "admission.component-member",
                document_id=document_id,
            )
        for occurrence in active_occurrences:
            if (
                occurrence["sourceDocumentId"] in members
                and occurrence["targetDocumentId"] in members
            ):
                processor(
                    "componentEdgePartitioned",
                    1,
                    "admission.component-edge",
                    document_id=occurrence["sourceDocumentId"],
                )

    establish_exact_value(
        cause["afterDocument"],
        trace,
        established,
        existing,
        reason_prefix="admission.managed-revision.after-document",
        context={"documentId": cause["childDocumentId"]},
    )
    processor(
        "closureWorkOccurrenceEnqueued",
        1,
        f"work.{work['ordinal']}.seed-enqueue",
        work_context=True,
        contract_key=work["channelKey"],
    )
    processor(
        "closureWorkOccurrenceDequeued",
        1,
        f"work.{work['ordinal']}.dequeue",
        work_context=True,
        contract_key=work["channelKey"],
    )

    target_occurrence = next(
        occurrence
        for occurrence in fixture_input["occurrences"]
        if occurrence["occurrenceIdentity"] == cause["targetOccurrenceIdentity"]
    )
    source_document_id = target_occurrence["sourceDocumentId"]
    assert source_document_id == work["targetDocumentId"]
    intermediate_documents = {
        document_id: deepcopy(record["document"])
        for document_id, record in fixture_input["documents"].items()
    }
    replace_pointer(
        intermediate_documents[source_document_id],
        target_occurrence["sourcePath"],
        {"blueId": cause["afterBlueId"]},
    )
    intermediate_blue_ids = {
        source_document_id: direct_blue_id(
            intermediate_documents[source_document_id]
        )
    }

    def runtime_context(
        *,
        contract_key: str | None = None,
        logical_path: str | None = None,
    ) -> dict[str, Any]:
        context: dict[str, Any] = {
            "documentId": source_document_id,
            "scopePath": "/",
            "activationGeneration": 0,
            "componentGeneration": fixture_input["documents"][
                source_document_id
            ].get("componentGeneration", 0),
            "workOccurrenceId": work["workIdentity"],
        }
        if contract_key is not None:
            context["contractKey"] = contract_key
        if logical_path is not None:
            context["logicalPath"] = logical_path
        return context

    def runtime_processor(
        counter: str,
        quantity: int,
        reason: str,
        *,
        contract_key: str | None = None,
        logical_path: str | None = None,
    ) -> None:
        assert trace.charge(
            "processor",
            counter,
            quantity,
            reason=reason,
            context=runtime_context(
                contract_key=contract_key,
                logical_path=logical_path,
            ),
        )

    runtime_processor(
        "scopeOpened",
        1,
        "participating-scope",
        contract_key=work["channelKey"],
    )
    source_contracts = intermediate_documents[source_document_id].get(
        "contracts", {}
    )
    marker_type_ids = {
        "Hp3fNbpFxKwLiTwWAf3swpN7gKbsr6ofwEDMntiwXPaB",
        "9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR",
    }
    participating_contract_keys = []
    for contract_key, contract in source_contracts.items():
        contract_type = contract.get("type") if isinstance(contract, dict) else None
        type_id = (
            contract_type.get("blueId")
            if isinstance(contract_type, dict)
            and set(contract_type) == {"blueId"}
            else None
        )
        if type_id not in marker_type_ids:
            participating_contract_keys.append(contract_key)
    for contract_key in sorted(participating_contract_keys):
        runtime_processor(
            "contractHeaderRecognized",
            1,
            "participating-contract-header",
            contract_key=contract_key,
        )
    source_path = target_occurrence["sourcePath"]
    runtime_processor(
        "embeddedPathEntryRead",
        1,
        "route",
        contract_key=work["channelKey"],
        logical_path=source_path,
    )
    path_segment_count = len(
        [segment for segment in source_path.split("/") if segment]
    )
    runtime_processor(
        "embeddedPathSegmentValidated",
        path_segment_count,
        "route",
        contract_key=work["channelKey"],
        logical_path=source_path,
    )
    runtime_processor(
        "patchBoundaryChecked",
        1,
        "patch-boundary",
        contract_key=work["channelKey"],
    )
    runtime_processor(
        "patchAddOrReplace",
        1,
        "application-patch",
        contract_key=work["channelKey"],
    )
    runtime_identity_start = len(trace.entries)
    establish_exact_value(
        intermediate_documents[source_document_id],
        trace,
        established,
        existing,
        reason_prefix="identity-rebuild",
        context=runtime_context(
            contract_key=work["channelKey"],
            logical_path=source_path,
        ),
    )
    for entry in trace.entries[runtime_identity_start:]:
        entry["reason"] = "identity-rebuild"
    processor(
        "containingReferenceUpdated",
        1,
        "managed-revision.receipt-reference",
        work_context=True,
        contract_key=work["channelKey"],
    )
    processor(
        "managedOccurrenceBindingVerified",
        1,
        "managed-revision.receipt-binding",
        work_context=True,
        contract_key=work["channelKey"],
    )

    changed_documents = [source_document_id]
    ancestor_rewrites: list[tuple[str, str]] = []
    affected_documents = {source_document_id}
    while True:
        parents = {
            occurrence["sourceDocumentId"]
            for occurrence in active_occurrences
            if occurrence["targetDocumentId"] in affected_documents
        } - affected_documents
        if not parents:
            break
        affected_documents.update(parents)
    pending_ancestors = affected_documents - {source_document_id}
    while pending_ancestors:
        ready = [
            document_id
            for document_id in sorted(pending_ancestors)
            if all(
                occurrence["targetDocumentId"] in intermediate_blue_ids
                for occurrence in active_occurrences
                if occurrence["sourceDocumentId"] == document_id
                and occurrence["targetDocumentId"] in affected_documents
            )
        ]
        assert ready
        for document_id in ready:
            rewritten = sorted(
                (
                    occurrence
                    for occurrence in active_occurrences
                    if occurrence["sourceDocumentId"] == document_id
                    and occurrence["targetDocumentId"] in intermediate_blue_ids
                ),
                key=lambda occurrence: (
                    occurrence["sourcePath"], occurrence["occurrenceIdentity"]
                ),
            )
            for occurrence_index, occurrence in enumerate(rewritten):
                replace_pointer(
                    intermediate_documents[document_id],
                    occurrence["sourcePath"],
                    {"blueId": intermediate_blue_ids[occurrence["targetDocumentId"]]},
                )
                ancestor_rewrites.append(
                    (document_id, occurrence["sourcePath"])
                )
            intermediate_blue_ids[document_id] = direct_blue_id(
                intermediate_documents[document_id]
            )
            changed_documents.append(document_id)
            pending_ancestors.remove(document_id)

    for document_id in changed_documents:
        generation = fixture_input["documents"][document_id].get(
            "componentGeneration", 0
        )
        processor(
            "tentativeComponentFinalization",
            1,
            "managed-revision.acyclic-finalization",
            work_context=True,
            document_id=document_id,
            component_generation=generation,
            contract_key=work["channelKey"],
        )
        if document_id != source_document_id:
            establish_exact_value(
                intermediate_documents[document_id],
                trace,
                established,
                existing,
                reason_prefix=f"work.{work['ordinal']}.acyclic-finalization",
                context={
                    "documentId": document_id,
                    "scopePath": "/",
                    "activationGeneration": 0,
                    "componentGeneration": generation,
                    "contractKey": work["channelKey"],
                    "workOccurrenceId": work["workIdentity"],
                },
            )
    for document_id, path in ancestor_rewrites:
        processor(
            "containingReferenceUpdated",
            1,
            f"managed-revision.ancestor-reference.{path}",
            work_context=True,
            document_id=document_id,
            component_generation=fixture_input["documents"][document_id].get(
                "componentGeneration", 0
            ),
            contract_key=work["channelKey"],
        )

    final_binding = next(
        occurrence
        for occurrence in expected["occurrenceBindings"]
        if occurrence["occurrenceIdentity"] == cause["targetOccurrenceIdentity"]
    )
    if final_binding["active"]:
        assert cause["childDocumentId"] in intermediate_blue_ids
        reconciled_source = deepcopy(intermediate_documents[source_document_id])
        replace_pointer(
            reconciled_source,
            target_occurrence["sourcePath"],
            {"blueId": intermediate_blue_ids[cause["childDocumentId"]]},
        )
        processor(
            "containingReferenceUpdated",
            1,
            "managed-revision.authoritative-reconciliation",
            work_context=True,
            contract_key=work["channelKey"],
        )
        processor(
            "processEmbeddedEdgeExamined",
            1,
            "managed-revision.topology-change",
            work_context=True,
            contract_key=work["channelKey"],
        )
        processor(
            "managedOccurrenceBindingVerified",
            1,
            "managed-revision.topology-change",
            work_context=True,
            contract_key=work["channelKey"],
        )
        processor(
            "componentPartitionChanged",
            1,
            "managed-revision.topology-change",
            work_context=True,
            contract_key=work["channelKey"],
        )
        processor(
            "componentMemberPartitioned",
            len(fixture_input["documents"]),
            "managed-revision.topology-change",
            work_context=True,
            contract_key=work["channelKey"],
        )
        final_active_edges = sum(
            occurrence["active"] for occurrence in expected["occurrenceBindings"]
        )
        if final_active_edges:
            processor(
                "componentEdgePartitioned",
                final_active_edges,
                "managed-revision.topology-change",
                work_context=True,
                contract_key=work["channelKey"],
            )
        cyclic_components = [
            component
            for component in expected["resultingComponents"]
            if component["kind"] == "CYCLIC"
        ]
        assert len(cyclic_components) == 1
        component = cyclic_components[0]
        oracle = cyclic_oracle_from_result(expected, component)
        member_states = component["completeCyclicProof"]["memberStates"]
        source_index = {
            state["documentId"]: index for index, state in enumerate(member_states)
        }
        expected_sources = deepcopy(intermediate_documents)
        expected_sources[source_document_id] = reconciled_source
        for occurrence in expected["occurrenceBindings"]:
            if occurrence["active"]:
                replace_pointer(
                    expected_sources[occurrence["sourceDocumentId"]],
                    occurrence["sourcePath"],
                    {"blueId": f"this#{source_index[occurrence['targetDocumentId']]}"},
                )
        assert [member.source_document for member in oracle.members] == [
            expected_sources[state["documentId"]] for state in member_states
        ]
        finalization = expected["tentativeFinalizations"][0]
        finalization_stage = next(
            route["stage"]
            for route in fixture["oracle"]["finalizationStages"]
            if route["ordinal"] == finalization["ordinal"]
        )
        finalize_cyclic_component(
            oracle,
            trace,
            established,
            existing,
            stage=f"work.{work['ordinal']}.{finalization_stage}",
            component_generation=component["componentGeneration"],
            document_ids=[state["documentId"] for state in member_states],
            work_occurrence_id=work["workIdentity"],
            attribution_defaults={
                "documentId": source_document_id,
                "scopePath": "/",
                "activationGeneration": 0,
                "contractKey": work["channelKey"],
            },
        )
    return trace.entries, trace.total, intermediate_documents, intermediate_blue_ids


def check_history() -> dict[str, Any]:
    missing = load_yaml(FIX / "c-clo-22-a10-attach-a5-needs-resources.yaml")
    retry = load_yaml(FIX / "c-clo-23-00-attach-a5-retry.yaml")
    assert missing["expected"]["attemptOutcome"] == "NeedsResources"
    assert retry["expected"]["attemptOutcome"] == "Complete"
    assert missing["operation"] == retry["operation"] == "process-closure"
    assert missing["input"] == retry["input"]
    assert missing["input"]["invocationIdentity"] == retry["input"]["invocationIdentity"]
    for field in ("runtime", "sharedLimitSource", "locality", "limit"):
        assert missing[field] == retry[field]
    required = missing["expected"]["requiredBlueIds"]
    demands = missing["expected"]["resourceDemands"]
    assert len(demands) == 1
    assert demands[0]["kind"] == "EXACT_NODE"
    assert demands[0]["blueId"] == required[0]
    assert demands[0]["demandIdentity"] == (
        closure_resource_demand_identity(demands[0])
    )
    assert missing["provider"]["expectedRequiredBlueIds"] == required
    assert retry["provider"]["expectedRequiredBlueIds"] == []
    assert missing["provider"]["expectedLoads"] == retry["provider"]["expectedLoads"]
    assert set(required).isdisjoint(missing["provider"]["nodes"])
    assert set(required) <= set(retry["provider"]["nodes"])

    prospective = next(
        occurrence
        for occurrence in retry["input"]["occurrences"]
        if occurrence["sourceDocumentId"] == "history-b"
        and occurrence["sourcePath"] == "/a"
    )
    assert prospective["active"] is False
    assert prospective["pendingHistoricalEpoch"] == 5
    retry_result = retry["expected"]
    retry_binding = next(
        occurrence
        for occurrence in retry_result["occurrenceBindings"]
        if occurrence["occurrenceIdentity"] == prospective["occurrenceIdentity"]
    )
    assert retry_binding["active"] is False
    assert retry_binding["pendingHistoricalEpoch"] == 5
    assert [(item["ordinal"], item["kind"]) for item in retry_result["workTrace"]] == [
        (0, "EXTERNAL_DELIVERY")
    ]

    def input_documents_from_result(expected: dict[str, Any]) -> dict[str, Any]:
        return {
            item["documentId"]: {
                "documentId": item["documentId"],
                "blueId": item["afterBlueId"],
                "document": item["document"],
                "initialized": item["initialized"],
                "terminated": item["terminated"],
                "publicRoot": item["publicRoot"],
                "epoch": item["epoch"],
                "componentGeneration": item["componentGeneration"],
            }
            for item in expected["resultingDocuments"]
        }

    revision_paths = [
        FIX / f"c-clo-23-{index:02d}-a{epoch}-to-a{epoch + 1}.yaml"
        for index, epoch in enumerate(range(5, 10), start=1)
    ]
    previous = retry_result
    previous_after_blue_id: str | None = None
    finalizations = 0
    final: dict[str, Any] | None = None
    for offset, path in enumerate(revision_paths):
        fixture = load_yaml(path)
        fixture_input = fixture["input"]
        expected = fixture["expected"]
        cause = fixture_input["cause"]
        from_epoch = offset + 5
        to_epoch = from_epoch + 1
        assert fixture["operation"] == "process-closure"
        assert cause["kind"] == "managed-revision"
        assert (cause["fromEpoch"], cause["toEpoch"]) == (from_epoch, to_epoch)
        assert direct_blue_id(cause["afterDocument"]) == cause["afterBlueId"]
        receipt_value = {
            "childDocumentId": cause["childDocumentId"],
            "fromEpoch": cause["fromEpoch"],
            "toEpoch": cause["toEpoch"],
            "beforeBlueId": cause["beforeBlueId"],
            "afterBlueId": cause["afterBlueId"],
            "originalSourceCauseIdentity": cause["originalSourceCauseIdentity"],
        }
        assert cause["sourceRevisionReceiptIdentity"] == domain_identity(
            "blue-contracts-source-revision-receipt/1.0", receipt_value
        )
        assert cause["causeIdentity"] == domain_identity(
            "blue-contracts-managed-revision-cause/1.0",
            {
                "targetOccurrenceIdentity": cause["targetOccurrenceIdentity"],
                **receipt_value,
                "sourceRevisionReceiptIdentity": cause[
                    "sourceRevisionReceiptIdentity"
                ],
            },
        )
        if previous_after_blue_id is not None:
            assert cause["beforeBlueId"] == previous_after_blue_id
        previous_after_blue_id = cause["afterBlueId"]
        assert input_documents_from_result(previous) == fixture_input["documents"]
        assert previous["occurrenceBindings"] == fixture_input["occurrences"]
        assert previous["resultingComponents"] == fixture_input["components"]
        assert previous["graphGeneration"] == fixture_input["graphGeneration"]
        assert expected["attemptOutcome"] == "Complete"
        assert expected["status"] == "success"
        assert len(expected["workTrace"]) == 1
        work = expected["workTrace"][0]
        assert (work["ordinal"], work["kind"]) == (
            0,
            "CONTAINING_REFERENCE_UPDATE",
        )
        assert work["sourceOccurrenceIdentity"] == cause["causeIdentity"]
        binding = next(
            occurrence
            for occurrence in expected["occurrenceBindings"]
            if occurrence["occurrenceIdentity"] == cause["targetOccurrenceIdentity"]
        )
        (
            reference_trace,
            reference_total,
            intermediate_documents,
            intermediate_blue_ids,
        ) = derive_managed_revision_trace(fixture)
        assert reference_trace == expected["gasTrace"]
        assert reference_total == expected["totalGas"]
        assert expected["gasTraceIdentity"] == gas_trace_identity(
            reference_trace
        )
        if to_epoch < 10:
            assert binding["expectedTargetBlueId"] == cause["afterBlueId"]
            assert binding["active"] is False
            assert binding["pendingHistoricalEpoch"] == to_epoch
            assert expected["graphGeneration"] == 1
            assert all(
                component["kind"] == "ACYCLIC"
                for component in expected["resultingComponents"]
            )
            assert expected["tentativeFinalizations"] == []
            outputs = resulting_documents(expected)
            for document_id, blue_id in intermediate_blue_ids.items():
                assert outputs[document_id]["document"] == intermediate_documents[
                    document_id
                ]
                assert outputs[document_id]["afterBlueId"] == blue_id
        else:
            resulting_child = next(
                document
                for document in expected["resultingDocuments"]
                if document["documentId"] == cause["childDocumentId"]
            )
            assert binding["expectedTargetBlueId"] == resulting_child["afterBlueId"]
            assert binding["expectedTargetBlueId"] != cause["afterBlueId"]
            assert binding["active"] is True
            assert binding["pendingHistoricalEpoch"] is None
            assert expected["graphGeneration"] == 2
            cyclic = [
                component
                for component in expected["resultingComponents"]
                if component["kind"] == "CYCLIC"
            ]
            assert len(cyclic) == 1
            assert len(expected["tentativeFinalizations"]) == 1
            assert fixture["oracle"]["finalizationStages"] == [
                {"ordinal": 0, "stage": "transition-0-managed-revision-9-10"}
            ]
            finalizations += 1
            final = expected
        previous = expected

    assert final is not None
    final_binding = next(
        occurrence
        for occurrence in final["occurrenceBindings"]
        if occurrence["sourceDocumentId"] == "history-b"
        and occurrence["sourcePath"] == "/a"
    )
    assert final_binding["active"] is True
    assert final_binding["pendingHistoricalEpoch"] is None
    documents = resulting_documents(final)
    assert documents["history-a"]["epoch"] == 10
    assert documents["history-a"]["document"]["counter"] == 10
    final_component = next(
        component
        for component in final["resultingComponents"]
        if component["kind"] == "CYCLIC"
    )
    return {
        "revisions": len(revision_paths),
        "finalizations": finalizations,
        "finalMaster": final_component["masterBlueId"],
    }


def check_contract_evolution_demands() -> dict[str, Any]:
    """Pin C-EVO-18..23 typed suspension and retry semantics."""
    exact = load_yaml(FIX / "c-evo-18-missing-exact-node.yaml")
    exact_demands = assert_typed_demand_result(exact, ["EXACT_NODE"])
    assert exact["expected"]["requiredBlueIds"] == [
        exact_demands[0]["suppliedValueBlueId"]
    ]
    assert exact_demands[0]["sourceDocumentId"] == "c-evo-18-a"
    assert exact_demands[0]["sourcePath"] == "/peer"

    occurrence = load_yaml(
        FIX / "c-evo-19-missing-occurrence-evidence.yaml"
    )
    occurrence_demands = assert_typed_demand_result(
        occurrence, ["MANAGED_OCCURRENCE_EVIDENCE"]
    )
    assert occurrence["expected"]["requiredBlueIds"] == []
    assert occurrence_demands[0]["sourceDocumentId"] == "c-evo-19-a"
    assert occurrence_demands[0]["sourcePath"] == "/peer"

    mixed = load_yaml(FIX / "c-evo-20-canonical-demand-order.yaml")
    mixed_demands = assert_typed_demand_result(
        mixed,
        [
            "MANAGED_OCCURRENCE_EVIDENCE",
            "EXACT_NODE",
            "MANAGED_OCCURRENCE_EVIDENCE",
        ],
    )
    assert [
        (demand["sourceDocumentId"], demand["sourcePath"])
        for demand in mixed_demands
    ] == [
        ("c-evo-20-a", "/aKnown"),
        ("c-evo-20-a", "/zUnknown"),
        ("c-evo-20-b", "/peer"),
    ]

    missing_first = load_yaml(
        FIX / "c-evo-21-retry-determinism-missing-first.yaml"
    )
    missing_repeat = load_yaml(
        FIX / "c-evo-21-retry-determinism-missing-repeat.yaml"
    )
    assert_typed_demand_result(
        missing_first, ["MANAGED_OCCURRENCE_EVIDENCE"]
    )
    assert missing_repeat["input"] == missing_first["input"]
    assert missing_repeat["expected"] == missing_first["expected"]
    resolved_first = load_yaml(
        FIX / "c-evo-21-retry-determinism-resolved-first.yaml"
    )
    resolved_repeat = load_yaml(
        FIX / "c-evo-21-retry-determinism-resolved-repeat.yaml"
    )
    assert resolved_repeat["input"] == resolved_first["input"]
    assert resolved_repeat["expected"] == resolved_first["expected"]
    assert resolved_first["expected"]["attemptOutcome"] == "Complete"

    low_gas_demand = load_yaml(
        FIX / "c-evo-22-low-gas-expanded-evidence-demand.yaml"
    )
    assert_typed_demand_result(
        low_gas_demand, ["MANAGED_OCCURRENCE_EVIDENCE"]
    )
    low_gas = load_yaml(
        FIX / "c-evo-22-low-gas-expanded-evidence-expanded-low-gas.yaml"
    )
    low_gas_repeat = load_yaml(
        FIX
        / "c-evo-22-low-gas-expanded-evidence-expanded-low-gas-repeat.yaml"
    )
    assert low_gas_repeat["input"] == low_gas["input"]
    assert low_gas_repeat["expected"] == low_gas["expected"]
    assert low_gas["expected"]["attemptOutcome"] == "Complete"
    assert low_gas["expected"]["status"] == "gas-limit-exceeded"
    assert low_gas["expected"]["rollbackToInput"] is True
    assert low_gas["expected"]["workTrace"]
    assert low_gas["expected"]["documentStepTrace"]
    assert low_gas["expected"]["publicEvents"] == []
    assert low_gas["expected"]["checkpointWrites"] == []
    assert low_gas["expected"].get("commitCompanion") is None

    automatic_demand = load_yaml(
        FIX
        / "c-evo-23-automatic-explicit-retry-parity-automatic-demand.yaml"
    )
    assert_typed_demand_result(
        automatic_demand, ["MANAGED_OCCURRENCE_EVIDENCE"]
    )
    automatic = load_yaml(
        FIX
        / "c-evo-23-automatic-explicit-retry-parity-automatic-resolved.yaml"
    )
    explicit = load_yaml(
        FIX
        / "c-evo-23-automatic-explicit-retry-parity-explicit-resolved.yaml"
    )
    assert automatic["input"] == explicit["input"]
    assert automatic["expected"] == explicit["expected"]
    assert automatic["expected"]["attemptOutcome"] == "Complete"
    assert automatic["expected"]["status"] == "success"

    return {
        "exactDemands": len(exact_demands),
        "occurrenceDemands": len(occurrence_demands),
        "mixedKinds": [demand["kind"] for demand in mixed_demands],
        "deterministicPairs": 4,
    }


def check_limits() -> dict[str, Any]:
    at = load_yaml(FIX / "c-clo-16-limit-at-bound.yaml")
    above = load_yaml(FIX / "c-clo-17-limit-above-bound.yaml")
    assert len(at["input"]["documents"]) == 128
    assert len(at["input"]["occurrences"]) == 128
    assert at["expected"]["status"] == "success"
    assert len(above["input"]["documents"]) == 129
    assert len(above["input"]["occurrences"]) == 129
    assert above["expected"]["status"] == "portable-limit-exceeded"
    assert above["expected"]["diagnostic"] == "CyclicComponentMemberLimitExceeded"
    gas = load_yaml(ROOT / "conformance/contracts/gas-manifest.yaml")
    matrix: dict[str, set[str]] = {}
    for path in sorted(FIX.glob("c-clo-1[67]-*.yaml")):
        fixture = load_yaml(path)
        if fixture.get("operation") != "limit-micro":
            continue
        name = fixture["limit"]["limit"]
        configured = gas["portableLimits"][name]
        assert fixture["input"] == {}
        assert fixture["limit"]["configured"] == configured
        assert fixture["limit"]["observed"] in {configured, configured + 1}
        decision = fixture["expected"]["limitDecision"]
        assert decision == (
            "ACCEPT" if fixture["limit"]["observed"] == configured else "REJECT"
        )
        matrix.setdefault(name, set()).add(decision)
    assert len(matrix) == 9
    assert all(decisions == {"ACCEPT", "REJECT"} for decisions in matrix.values())
    return {"executableMemberAccepted": 128, "executableMemberRejected": 129, "boundaryGuards": len(matrix)}


def check_occurrence_continuity() -> dict[str, Any]:
    fixture = load_yaml(FIX / "c-clo-24-occurrence-continuity.yaml")
    initial = {
        (o["sourceDocumentId"], o["sourcePath"]): o
        for o in fixture["input"]["occurrences"]
    }
    final = {
        (o["sourceDocumentId"], o["sourcePath"]): o
        for o in fixture["expected"]["occurrenceBindings"]
    }
    changed_bindings = 0
    for key, before in initial.items():
        after = final[key]
        assert before["occurrenceIdentity"] == after["occurrenceIdentity"]
        assert before["activationGeneration"] == after["activationGeneration"]
        if before["expectedTargetBlueId"] != after["expectedTargetBlueId"]:
            assert before["bindingIdentity"] != after["bindingIdentity"]
            changed_bindings += 1
    assert changed_bindings == 2
    return {"stableLineages": len(initial), "changedBindings": changed_bindings}


def check_admission_candidates() -> dict[str, Any]:
    bad = load_yaml(FIX / "c-clo-14-invalid-cyclic-proof.yaml")
    ambiguous = load_yaml(FIX / "c-clo-15-ambiguous-preliminary-members.yaml")
    invalid_binding = load_yaml(FIX / "c-clo-29-active-edge-path-validation.yaml")

    for fixture in (bad, ambiguous, invalid_binding):
        candidate = fixture["input"]["admissionCandidate"]
        assert candidate is not None
        assert fixture["input"]["admissionCandidateIdentity"] == domain_identity(
            "blue-contracts-admission-candidate/1.0", candidate
        )

    def ordinary_admission_shape(
        fixture: dict[str, Any],
    ) -> list[tuple[str, str, int]]:
        fixture_input = fixture["input"]
        shape: list[tuple[str, str, int]] = [
            ("processor", "processInvocation", 1),
            ("processor", "closureInvocation", 1),
        ]
        if fixture_input.get("directDeliveries"):
            shape.append(
                (
                    "processor",
                    "deliverySnapshotEntry",
                    len(fixture_input["directDeliveries"]),
                )
            )
        shape.extend(
            ("processor", "managedDocumentOpened", 1)
            for _document_id in sorted(fixture_input["documents"])
        )
        for occurrence in sorted(
            fixture_input.get("occurrences", []),
            key=lambda item: (
                item["occurrenceIdentity"],
                item["bindingIdentity"],
            ),
        ):
            shape.append(("processor", "managedOccurrenceBindingVerified", 1))
            if occurrence.get("active"):
                shape.append(("processor", "processEmbeddedEdgeExamined", 1))
        active_edges = [
            occurrence
            for occurrence in fixture_input.get("occurrences", [])
            if occurrence.get("active")
        ]
        for component in fixture_input["components"]:
            members = set(component["orderedMemberDocumentIds"])
            shape.extend(
                ("processor", "componentMemberPartitioned", 1)
                for _document_id in component["orderedMemberDocumentIds"]
            )
            shape.extend(
                ("processor", "componentEdgePartitioned", 1)
                for edge in active_edges
                if edge["sourceDocumentId"] in members
                and edge["targetDocumentId"] in members
            )
        return shape

    suffixes: dict[str, tuple[int, list[tuple[str, str, int]]]] = {
        "BAD_CYCLIC_PROOF": (
            199,
            [
                ("semantic", "validationMemberExamined", 1),
                ("semantic", "scalarComparison", 1),
                ("semantic", "textBlockExamined", 4),
                ("semantic", "scalarComparison", 1),
                ("semantic", "textBlockExamined", 2),
            ],
        ),
        "AMBIGUOUS_PRELIMINARY_MEMBERS": (
            181,
            [
                ("semantic", "validationMemberExamined", 1),
                ("semantic", "validationMemberExamined", 1),
                ("semantic", "nodeIdentityEstablished", 1),
                ("semantic", "objectMemberRebuilt", 2),
                ("semantic", "directIdentityHashBlock", 3),
                ("semantic", "sortComparison", 1),
                ("semantic", "scalarComparison", 1),
                ("semantic", "textBlockExamined", 2),
                ("semantic", "scalarComparison", 1),
                ("semantic", "textBlockExamined", 6),
            ],
        ),
        "INVALID_OCCURRENCE_BINDING": (
            196,
            [
                ("processor", "managedOccurrenceBindingVerified", 1),
                ("processor", "pointerSegmentTraversed", 1),
            ],
        ),
    }
    candidate_gas: dict[str, int] = {}
    for name, fixture in (
        ("C-CLO-14", bad),
        ("C-CLO-15", ambiguous),
        ("C-CLO-29", invalid_binding),
    ):
        kind = fixture["input"]["admissionCandidate"]["kind"]
        total, suffix = suffixes[kind]
        trace = gas_trace(fixture, fixture["id"])
        assert [
            (entry["namespace"], entry["counter"], entry["quantity"])
            for entry in trace
        ] == ordinary_admission_shape(fixture) + suffix
        assert fixture["expected"]["totalGas"] == total
        assert fixture["expected"]["gasTraceIdentity"] == gas_trace_identity(
            trace
        )
        assert fixture["expected"]["status"] == "invalid-processing-document"
        assert fixture["expected"]["rollbackToInput"] is True
        assert fixture["expected"]["workTrace"] == []
        assert fixture["expected"]["tentativeFinalizations"] == []
        candidate_gas[name] = total

    bad_proof = bad["input"]["admissionCandidate"]["evidence"][
        "candidateCyclicProof"
    ]
    authoritative_proof = bad["input"]["components"][0]["completeCyclicProof"]
    assert bad_proof["componentIdentity"] == authoritative_proof["componentIdentity"]
    assert bad_proof["memberStates"] == authoritative_proof["memberStates"]
    assert (
        bad_proof["declaredPlaceholderSet"]
        == authoritative_proof["declaredPlaceholderSet"]
    )
    assert bad_proof["masterBlueId"] != authoritative_proof["masterBlueId"]
    assert bad["expected"]["diagnostic"] == "CyclicSetProofInvalid"

    members = ambiguous["input"]["admissionCandidate"]["evidence"][
        "candidateCyclicMembers"
    ]
    assert [item["documentId"] for item in members] == sorted(
        item["documentId"] for item in members
    )
    try:
        cyclic_set_oracle([item["document"] for item in members])
    except ValueError as exc:
        assert "Duplicate preliminary cyclic BlueId" in str(exc)
    else:
        raise AssertionError("ambiguous candidate unexpectedly finalized")
    assert ambiguous["expected"]["diagnostic"] == "CyclicPreliminaryMemberAmbiguous"

    occurrence = invalid_binding["input"]["admissionCandidate"]["evidence"][
        "candidateOccurrenceBindings"
    ][0]
    lineage_basis = {
        key: occurrence[key]
        for key in (
            "sourceDocumentId",
            "sourcePath",
            "activationGeneration",
            "targetDocumentId",
            "bindingPolicyIdentity",
        )
    }
    binding_basis = {
        **lineage_basis,
        "expectedTargetBlueId": occurrence["expectedTargetBlueId"],
    }
    assert occurrence["occurrenceIdentity"] == domain_identity(
        "blue-contracts-managed-occurrence-lineage/1.0", lineage_basis
    )
    assert occurrence["bindingIdentity"] == domain_identity(
        "blue-contracts-managed-occurrence/1.0", binding_basis
    )
    source_document = invalid_binding["input"]["documents"][
        occurrence["sourceDocumentId"]
    ]["document"]
    assert occurrence["sourcePath"] == "/missing"
    assert "missing" not in source_document
    assert invalid_binding["expected"]["diagnostic"] == "ManagedOccurrenceBindingMissing"

    return {
        "branches": 3,
        "badProofMismatch": "masterBlueId",
        "ambiguousMembers": len(members),
        "gas": candidate_gas,
        "invalidPath": occurrence["sourcePath"],
    }


def check_checkpoint_settlement() -> dict[str, Any]:
    transitions: set[str] = set()
    combined_cleanup_vectors: list[str] = []
    receipts = 0
    for path in sorted(FIX.glob("*.yaml")):
        fixture = load_yaml(path)
        if fixture.get("operation") == "limit-micro":
            continue
        expected = fixture["expected"]
        if expected.get("attemptOutcome") != "Complete":
            continue
        trace = gas_trace(fixture, path.name)
        comparisons = [
            entry for entry in trace if entry["counter"] == "checkpointCompared"
        ]
        external_work = [
            item
            for item in expected["workTrace"]
            if item["kind"] == "EXTERNAL_DELIVERY"
        ]
        assert len(comparisons) == len(external_work)
        assert {
            entry.get("workOccurrenceId") for entry in comparisons
        } == {item["workIdentity"] for item in external_work}
        queue_execution = [
            entry
            for entry in trace
            if entry["counter"]
            in {
                "closureWorkOccurrenceDequeued",
                "handlerCall",
                "scopeInitialization",
            }
        ]
        if comparisons and queue_execution:
            assert max(entry["sequence"] for entry in comparisons) < min(
                entry["sequence"] for entry in queue_execution
            )

        writes = [entry for entry in trace if entry["counter"] == "checkpointWritten"]
        assert len(writes) == len(expected["checkpointWrites"])
        assert all(entry["quantity"] == 1 for entry in writes)
        if expected["status"] != "success":
            assert writes == []
            assert expected["checkpointWrites"] == []
        causal = [
            entry
            for entry in trace
            if entry["counter"]
            in {
                "handlerCall",
                "scopeInitialization",
                "internalEventDequeued",
                "closureWorkOccurrenceDequeued",
            }
        ]
        if writes and causal:
            assert min(entry["sequence"] for entry in writes) > max(
                entry["sequence"] for entry in causal
            )
        settlement_finalizations = [
            entry
            for entry in trace
            if entry["counter"] == "tentativeComponentFinalization"
            and entry.get("reason", "").startswith("checkpoint-settlement.")
        ]
        if writes and settlement_finalizations:
            assert max(entry["sequence"] for entry in writes) < min(
                entry["sequence"] for entry in settlement_finalizations
            )

        local_transitions: set[str] = set()
        for receipt in expected["checkpointWrites"]:
            receipts += 1
            if receipt["beforePresent"]:
                assert receipt["beforeDomainValue"] is not None
                assert receipt["beforeDomainBlueId"] == direct_blue_id(
                    receipt["beforeDomainValue"]
                )
            else:
                assert receipt["beforeDomainValue"] is None
                assert receipt["beforeDomainBlueId"] is None
                assert receipt["beforeSubjectBlueId"] is None
            if receipt["afterPresent"]:
                assert receipt["afterDomainValue"] is not None
                assert receipt["afterDomainBlueId"] == direct_blue_id(
                    receipt["afterDomainValue"]
                )
            else:
                assert receipt["afterDomainValue"] is None
                assert receipt["afterDomainBlueId"] is None
                assert receipt["afterSubjectBlueId"] is None
            if not receipt["beforePresent"] and receipt["afterPresent"]:
                transition = "ADD"
            elif receipt["beforePresent"] and not receipt["afterPresent"]:
                transition = "REMOVE"
            else:
                assert receipt["beforePresent"] and receipt["afterPresent"]
                assert (
                    receipt["beforeDomainBlueId"],
                    receipt["beforeSubjectBlueId"],
                ) != (
                    receipt["afterDomainBlueId"],
                    receipt["afterSubjectBlueId"],
                )
                transition = "REPLACE"
            transitions.add(transition)
            local_transitions.add(transition)
        if {"REPLACE", "REMOVE"} <= local_transitions:
            combined_cleanup_vectors.append(path.stem)

    assert {"ADD", "REPLACE", "REMOVE"} <= transitions
    assert len(combined_cleanup_vectors) == 1
    return {
        "receipts": receipts,
        "transitions": sorted(transitions),
        "combinedCleanupVector": combined_cleanup_vectors[0],
    }


def check_stabilized_closure_vectors() -> dict[str, Any]:
    """Pin the four closure vectors repaired during final stabilization."""
    mixed = load_yaml(FIX / "c-clo-28-mixed-result-shape.yaml")
    mixed_expected = mixed["expected"]
    mixed_work_zero = [
        finalization
        for finalization in mixed_expected["tentativeFinalizations"]
        if finalization["boundary"]
        == {"kind": "WORK", "afterWorkOrdinal": 0}
    ]
    assert [set(value["memberBlueIds"]) for value in mixed_work_zero] == [
        {"ma", "mb"},
        {"mc", "md"},
    ]
    assert mixed_expected["totalGas"] == 1151

    rejection = load_yaml(
        FIX / "c-clo-32-finalization-owned-gas-rejection.yaml"
    )
    rejection_expected = rejection["expected"]
    rejection_trace = gas_trace(rejection, rejection["id"])
    assert len(rejection_expected["workTrace"]) == 1
    assert len(rejection_expected["documentStepTrace"]) == 1
    assert rejection_expected["documentStepTrace"][0]["workOrdinal"] == (
        rejection_expected["workTrace"][0]["ordinal"]
    )
    assert rejection_expected["totalGas"] == 1218
    assert rejection_trace[-1]["counter"] == "scopeInitialization"
    assert rejection_expected["rejectedCharge"]["counter"] == (
        "tentativeComponentFinalization"
    )
    assert rejection_expected["rejectedCharge"]["remainingBeforeCharge"] == 19

    retirement = load_yaml(
        FIX / "c-clo-33-checkpoint-domain-retirement.yaml"
    )
    retirement_trace = gas_trace(retirement, retirement["id"])
    retirement_expected = retirement["expected"]
    assert retirement_expected["totalGas"] == 1445
    cleanup_path = "/contracts/checkpoint/entries/orphan"
    cleanup_rows = [
        entry
        for entry in retirement_trace
        if entry.get("logicalPath") == cleanup_path
        and entry["namespace"] == "semantic"
    ]
    assert [
        entry["quantity"]
        for entry in cleanup_rows
        if entry["counter"] == "objectMemberRebuilt"
    ] == [1, 2, 9, 6]
    assert [
        entry["quantity"]
        for entry in cleanup_rows
        if entry["counter"] == "directIdentityHashBlock"
    ] == [2, 3, 10, 7]
    assert sum(entry["subtotal"] for entry in cleanup_rows) == 44

    readd = load_yaml(
        FIX / "c-clo-35-01-readd-committed-successor.yaml"
    )
    readd_trace = gas_trace(readd, readd["id"])
    readd_expected = readd["expected"]
    assert readd_expected["totalGas"] == 646
    source_path = "/contracts/checkpoint/entries/source"
    source_rows = [
        entry
        for entry in readd_trace
        if entry.get("logicalPath") == source_path
        and entry["namespace"] == "semantic"
    ]
    assert [
        entry["quantity"]
        for entry in source_rows
        if entry["counter"] == "objectMemberRebuilt"
    ] == [2, 1, 2, 9, 6]
    assert [
        entry["quantity"]
        for entry in source_rows
        if entry["counter"] == "directIdentityHashBlock"
    ] == [3, 2, 3, 10, 7]
    assert sum(entry["subtotal"] for entry in source_rows) == 50

    fixtures = (mixed, rejection, retirement, readd)
    for fixture in fixtures:
        trace = gas_trace(fixture, fixture["id"])
        assert fixture["expected"]["gasTraceIdentity"] == gas_trace_identity(
            trace
        )
    return {
        fixture["id"]: {
            "gas": fixture["expected"]["totalGas"],
            "gasTraceIdentity": fixture["expected"]["gasTraceIdentity"],
        }
        for fixture in fixtures
    }


def main() -> None:
    oracle_stages = sum(check_oracle(p) for p in sorted(ORC.glob("*.yaml")))
    result = {
        "status": "SEMANTIC_REFERENCE_VALID",
        "oracleStages": oracle_stages,
        "finite": check_finite(),
        "loop": check_loop(),
        "identityTransitionGas": check_identity_transition_gas(),
        "history": check_history(),
        "contractEvolutionDemands": check_contract_evolution_demands(),
        "limits": check_limits(),
        "occurrenceTransitionLaw": check_occurrence_transition_law(),
        "generationTransitionLaw": check_generation_transition_law(),
        "directSeedOrder": check_direct_seed_order(),
        "occurrenceContinuity": check_occurrence_continuity(),
        "admissionCandidates": check_admission_candidates(),
        "checkpointSettlement": check_checkpoint_settlement(),
        "stabilizedClosureVectors": check_stabilized_closure_vectors(),
    }
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
