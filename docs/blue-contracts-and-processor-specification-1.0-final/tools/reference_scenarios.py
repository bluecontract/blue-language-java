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
import sys
from typing import Any
import yaml

from jcs import dumps as jcs_dumps

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from blue_identity import (  # noqa: E402
    cyclic_canonical_limit_bytes,
    cyclic_canonical_limit_form,
    cyclic_set_oracle,
    direct_blue_id,
    materialize_cyclic_members,
)
from gas_reference import (  # noqa: E402
    GasReferenceTrace,
    collect_existing_descendant_ids,
    establish_exact_value,
    finalize_cyclic_component,
)

FIX = ROOT / "conformance/contracts/fixtures/closure"
ORC = ROOT / "conformance/contracts/oracles"


def load_yaml(path: Path) -> Any:
    return yaml.safe_load(path.read_text())


def canonical_json(value: Any) -> bytes:
    return jcs_dumps(value)


def domain_identity(domain: str, value: Any) -> str:
    return "sha256:" + hashlib.sha256(
        canonical_json({"domain": domain, "value": value})
    ).hexdigest()


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
    gas_policy = fixture["input"]["gasPolicy"]
    trace = GasReferenceTrace(
        gas_policy["sharedLimit"], gas_policy.get("localLimits", {})
    )
    loop_event = fixture["runtime"]["handlers"]["loop-a/start"]["events"][0]
    loop_event_id = direct_blue_id(loop_event)
    start_event_id = fixture["input"]["cause"]["eventBlueId"]
    established: set[str] = set()

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
        return {
            "documentId": work_item["targetDocumentId"],
            "scopePath": "/",
            "activationGeneration": 0,
            "componentGeneration": 1,
            "logicalPath": f"work/{work_item['ordinal']}",
            "workOccurrenceId": work_item["workIdentity"],
        }

    def charge(
        counter: str,
        quantity: int,
        reason: str,
        work_item: dict[str, Any] | None = None,
        *,
        contract_key: str | None = None,
        document_id: str | None = None,
    ) -> bool:
        context: dict[str, Any] = {}
        if work_item is not None:
            context.update(context_for(work_item))
        if document_id is not None:
            context["documentId"] = document_id
        if contract_key is not None:
            context["contractKey"] = contract_key
        return trace.charge(
            "processor", counter, quantity, reason=reason,
            context=context or None,
        )

    current = make_work(0)
    for counter, quantity, reason in [
        ("processInvocation", 1, "admission.process"),
        ("closureInvocation", 1, "admission.closure"),
        ("deliverySnapshotEntry", 1, "admission.direct-delivery"),
    ]:
        if not charge(counter, quantity, reason):
            raise AssertionError("loop policy cannot reject fixed global admission")
    for document_id in ("loop-a", "loop-b"):
        for counter, reason in [
            ("managedDocumentOpened", "admission.document"),
            ("managedOccurrenceBindingVerified", "admission.binding"),
            ("processEmbeddedEdgeExamined", "admission.edge"),
            ("componentMemberPartitioned", "admission.component-member"),
            ("componentEdgePartitioned", "admission.component-edge"),
        ]:
            if not charge(counter, 1, f"{reason}.{document_id}", document_id=document_id):
                raise AssertionError("loop policy cannot reject fixed document admission")
    if not charge(
        "checkpointCompared",
        1,
        "direct-admission.0.checkpoint-compare",
        current,
    ):
        raise AssertionError("loop policy cannot reject checkpoint comparison")
    if not charge("closureWorkOccurrenceEnqueued", 1, "work.0.enqueue", current):
        raise AssertionError("loop policy cannot reject initial work enqueue")

    completed: list[dict[str, Any]] = []
    rejected_owner_work: dict[str, Any] | None = None
    while True:
        handler_key = (
            "start" if current["ordinal"] == 0
            else "onLoopFromA" if current["targetDocumentId"] == "loop-b"
            else "onLoopFromB"
        )
        channel_key = current["channelKey"]
        steps: list[tuple[str, int, str, str | None]] = [
            ("closureWorkOccurrenceDequeued", 1, "dequeue", None),
        ]
        if current["ordinal"] > 0:
            steps.append(
                ("embeddedEventDelivered", 1, "embedded-delivery", channel_key)
            )
        steps.extend([
            ("scopeOpened", 1, "scope", None),
            ("contractHeaderRecognized", 2, "contracts", None),
            ("channelCandidateTested", 1, "channel-test", channel_key),
            ("channelAccepted", 1, "channel-accepted", channel_key),
            ("handlerCandidateTested", 1, "handler-test", handler_key),
            ("handlerCall", 1, "handler-call", handler_key),
        ])
        rejected = False
        for counter, quantity, suffix, contract_key in steps:
            if not charge(
                counter, quantity, f"work.{current['ordinal']}.{suffix}", current,
                contract_key=contract_key,
            ):
                rejected = True
                break
        if rejected:
            rejected_owner_work = current
            break

        # Handler output constructs this exact event value once. Equal later
        # occurrences reuse that established value but remain distinct work.
        establish_exact_value(
            loop_event, trace, established, set(),
            reason_prefix=f"work.{current['ordinal']}.event-loop",
            context=context_for(current),
        )
        if trace.rejected is not None:
            rejected_owner_work = current
            break
        if fixture["input"]["documents"][current["targetDocumentId"]].get(
            "publicRoot"
        ) and not charge(
            "rootEventRecorded",
            1,
            f"work.{current['ordinal']}.event-public-record",
            current,
        ):
            rejected_owner_work = current
            break
        if not charge(
            "internalEventEnqueued", 1,
            f"work.{current['ordinal']}.event-enqueue", current,
        ):
            rejected_owner_work = current
            break
        next_work = make_work(current["ordinal"] + 1)
        if not charge(
            "internalEventDequeued",
            1,
            f"event.{next_work['occurrenceOrdinal']}.dequeue",
        ):
            completed.append(current)
            rejected_owner_work = None
            break
        if not charge(
            "closureWorkOccurrenceEnqueued", 1,
            f"work.{next_work['ordinal']}.enqueue", next_work,
        ):
            completed.append(current)
            rejected_owner_work = next_work
            break
        completed.append(current)
        current = next_work
        if current["ordinal"] > 10000:
            raise AssertionError("loop trace did not terminate")

    return (
        trace.entries,
        trace.total,
        completed,
        rejected_owner_work,
        reference_rejected_charge_payload(trace),
    )


def derive_finite_trace(fixture: dict[str, Any]) -> tuple[list[dict[str, Any]], int, str]:
    trace = GasReferenceTrace(fixture["input"]["gasPolicy"]["sharedLimit"])
    established: set[str] = set()
    existing = collect_existing_descendant_ids(
        record["document"] for record in fixture["input"]["documents"].values()
    )
    work = fixture["expected"]["workTrace"]

    def processor(
        counter: str,
        quantity: int,
        reason: str,
        work_index: int | None = None,
        document_id: str | None = None,
        contract_key: str | None = None,
        component_generation: int | None = None,
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
        if contract_key is not None:
            context["contractKey"] = contract_key
        if component_generation is not None:
            context["componentGeneration"] = component_generation
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
        if occurrence["active"]:
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
        0,
    )
    processor("closureWorkOccurrenceEnqueued", 1, "work.0.seed-enqueue", 0)

    oracle_path = (FIX / fixture["oracle"]["path"]).resolve()
    stages = yaml.safe_load(oracle_path.read_text())["stages"]
    stage_oracles = {
        stage["name"]: cyclic_set_oracle(stage["sourceDocumentsWithThisReferences"])
        for stage in stages
    }

    # Work 0: A adds /b and emits X; the new SCC is finalized immediately
    # after all identity-changing Handler effects. Handler-owned reasons name
    # the selected Handler frame, while the finalization reason remains owned
    # by the work occurrence itself.
    start_handler = "work.0.handler.0.start"
    for counter, quantity, reason, contract_key in [
        ("closureWorkOccurrenceDequeued", 1, "work.0.dequeue", None),
        ("scopeOpened", 1, "work.0.scope", None),
        ("contractHeaderRecognized", 1, "work.0.contracts", None),
        ("channelCandidateTested", 1, "work.0.channel-test", "source"),
        ("channelAccepted", 1, "work.0.channel-accepted", "source"),
        ("handlerCandidateTested", 1, f"{start_handler}.candidate", "start"),
        ("handlerCall", 1, f"{start_handler}.call", "start"),
        ("patchBoundaryChecked", 1, f"{start_handler}.patch.0.boundary", None),
        ("pointerSegmentTraversed", 1, f"{start_handler}.patch.0.pointer", None),
        ("patchAddOrReplace", 1, f"{start_handler}.patch.0.add", None),
    ]:
        processor(counter, quantity, reason, 0, contract_key=contract_key)
    patch = fixture["runtime"]["handlers"]["a/start"]["patches"][0]
    establish_exact_value(
        patch["val"],
        trace,
        established,
        existing,
        reason_prefix=f"{start_handler}.patch.0.value",
        context={"documentId": "a", "workOccurrenceId": work[0]["workIdentity"]},
    )
    for counter, quantity, reason in [
        ("managedOccurrenceBindingVerified", 1, f"{start_handler}.patch.0.binding"),
        ("processEmbeddedEdgeExamined", 1, f"{start_handler}.patch.0.edge"),
        ("componentPartitionChanged", 1, f"{start_handler}.patch.0.partition-change"),
        ("componentMemberPartitioned", 2, f"{start_handler}.patch.0.partition-members"),
        ("componentEdgePartitioned", 2, f"{start_handler}.patch.0.partition-edges"),
    ]:
        processor(counter, quantity, reason, 0)
    finalize_cyclic_component(
        stage_oracles["transition-0-cycle-formed"], trace, established, existing,
        stage="work.0.transition-0-cycle-formed", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[0]["workIdentity"],
    )
    x_event = fixture["runtime"]["handlers"]["a/start"]["events"][0]
    establish_exact_value(
        x_event,
        trace,
        established,
        existing - {direct_blue_id(x_event)},
        reason_prefix=f"{start_handler}.event.0",
        context={"documentId": "a", "workOccurrenceId": work[0]["workIdentity"]},
    )
    processor("rootEventRecorded", 1, f"{start_handler}.event.0.public-record", 0)
    processor("internalEventEnqueued", 1, f"{start_handler}.event.0.enqueue", 0)

    # Work 1: A handles local X and finalizes M2.
    processor("internalEventDequeued", 1, "event.0.dequeue")
    processor("closureWorkOccurrenceEnqueued", 1, "event.0.delivery.1.enqueue", 1)
    processor("closureWorkOccurrenceEnqueued", 1, "event.0.delivery.2.enqueue", 2)
    local_x_handler = "work.1.handler.0.onLocalX"
    for counter, quantity, reason, contract_key in [
        ("closureWorkOccurrenceDequeued", 1, "work.1.dequeue", None),
        ("triggeredEventDelivered", 1, "work.1.delivery", "localX"),
        ("scopeOpened", 1, "work.1.scope", None),
        ("contractHeaderRecognized", 1, "work.1.contracts", None),
        ("channelCandidateTested", 1, "work.1.channel-test", "localX"),
        ("channelAccepted", 1, "work.1.channel-accepted", "localX"),
        ("handlerCandidateTested", 1, f"{local_x_handler}.candidate", "onLocalX"),
        ("handlerCall", 1, f"{local_x_handler}.call", "onLocalX"),
        ("patchBoundaryChecked", 1, f"{local_x_handler}.patch.0.boundary", None),
        ("pointerSegmentTraversed", 1, f"{local_x_handler}.patch.0.pointer", None),
        ("patchAddOrReplace", 1, f"{local_x_handler}.patch.0.replace", None),
    ]:
        processor(counter, quantity, reason, 1, contract_key=contract_key)
    patch = fixture["runtime"]["handlers"]["a/onLocalX"]["patches"][0]
    establish_exact_value(
        patch["val"], trace, established, existing,
        reason_prefix=f"{local_x_handler}.patch.0.value",
        context={"documentId": "a", "workOccurrenceId": work[1]["workIdentity"]},
    )
    finalize_cyclic_component(
        stage_oracles["transition-1-a-local-x"], trace, established, existing,
        stage="work.1.transition-1-a-local-x", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[1]["workIdentity"],
    )

    # Work 2: B handles embedded X, finalizes M3, then emits Y.
    on_x_handler = "work.2.handler.0.onX"
    for counter, quantity, reason, contract_key in [
        ("closureWorkOccurrenceDequeued", 1, "work.2.dequeue", None),
        ("embeddedEventDelivered", 1, "work.2.delivery", "fromA"),
        ("scopeOpened", 1, "work.2.scope", None),
        ("contractHeaderRecognized", 1, "work.2.contracts", None),
        ("channelCandidateTested", 1, "work.2.channel-test", "fromA"),
        ("channelAccepted", 1, "work.2.channel-accepted", "fromA"),
        ("handlerCandidateTested", 1, f"{on_x_handler}.candidate", "onX"),
        ("handlerCall", 1, f"{on_x_handler}.call", "onX"),
        ("patchBoundaryChecked", 1, f"{on_x_handler}.patch.0.boundary", None),
        ("pointerSegmentTraversed", 1, f"{on_x_handler}.patch.0.pointer", None),
        ("patchAddOrReplace", 1, f"{on_x_handler}.patch.0.replace", None),
    ]:
        processor(counter, quantity, reason, 2, contract_key=contract_key)
    patch = fixture["runtime"]["handlers"]["b/onX"]["patches"][0]
    establish_exact_value(
        patch["val"], trace, established, existing,
        reason_prefix=f"{on_x_handler}.patch.0.value",
        context={"documentId": "b", "workOccurrenceId": work[2]["workIdentity"]},
    )
    finalize_cyclic_component(
        stage_oracles["transition-2-b-handled-x"], trace, established, existing,
        stage="work.2.transition-2-b-handled-x", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[2]["workIdentity"],
    )
    y_event = fixture["runtime"]["handlers"]["b/onX"]["events"][0]
    establish_exact_value(
        y_event,
        trace,
        established,
        existing - {direct_blue_id(y_event)},
        reason_prefix=f"{on_x_handler}.event.0",
        context={"documentId": "b", "workOccurrenceId": work[2]["workIdentity"]},
    )
    processor("internalEventEnqueued", 1, f"{on_x_handler}.event.0.enqueue", 2)

    # Work 3: A handles Y and finalizes M4 immediately.
    processor("internalEventDequeued", 1, "event.1.dequeue")
    processor("closureWorkOccurrenceEnqueued", 1, "event.1.delivery.3.enqueue", 3)
    on_y_handler = "work.3.handler.0.onY"
    for counter, quantity, reason, contract_key in [
        ("closureWorkOccurrenceDequeued", 1, "work.3.dequeue", None),
        ("embeddedEventDelivered", 1, "work.3.delivery", "fromB"),
        ("scopeOpened", 1, "work.3.scope", None),
        ("contractHeaderRecognized", 1, "work.3.contracts", None),
        ("channelCandidateTested", 1, "work.3.channel-test", "fromB"),
        ("channelAccepted", 1, "work.3.channel-accepted", "fromB"),
        ("handlerCandidateTested", 1, f"{on_y_handler}.candidate", "onY"),
        ("handlerCall", 1, f"{on_y_handler}.call", "onY"),
        ("patchBoundaryChecked", 1, f"{on_y_handler}.patch.0.boundary", None),
        ("pointerSegmentTraversed", 1, f"{on_y_handler}.patch.0.pointer", None),
        ("patchAddOrReplace", 1, f"{on_y_handler}.patch.0.replace", None),
    ]:
        processor(counter, quantity, reason, 3, contract_key=contract_key)
    patch = fixture["runtime"]["handlers"]["a/onY"]["patches"][0]
    establish_exact_value(
        patch["val"], trace, established, existing,
        reason_prefix=f"{on_y_handler}.patch.0.value",
        context={"documentId": "a", "workOccurrenceId": work[3]["workIdentity"]},
    )
    finalize_cyclic_component(
        stage_oracles["transition-3-a-finished"], trace, established, existing,
        stage="work.3.transition-3-a-finished", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[3]["workIdentity"],
    )
    processor("checkpointWritten", 1, "checkpoint-settlement.0.write", 0)
    finalize_cyclic_component(
        stage_oracles["checkpoint-settlement-0"], trace, established, existing,
        stage="checkpoint-settlement.checkpoint-settlement-0",
        component_generation=2,
        document_ids=["a", "b"],
        work_occurrence_id=None,
    )
    return trace.entries, trace.total, domain_identity("blue-contracts-gas-trace/1.0", trace.entries)


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
        derived_work = completed + ([rejected_work] if rejected_work is not None else [])
        expected_work = expected["workTrace"] + (
            [expected_rejected_work] if expected_rejected_work is not None else []
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
        assert expected["gasTraceIdentity"] == domain_identity(
            "blue-contracts-gas-trace/1.0", rebound_trace
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
            assert expected_rejected_work["ordinal"] == len(expected["workTrace"])
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
        ("c-clo-10-split-to-singletons.yaml", derive_split_trace),
        ("c-clo-28-containing-spine-identity-gas.yaml", derive_containing_spine_trace),
    ]
    result: dict[str, Any] = {}
    for name, derive in cases:
        fixture = load_yaml(FIX / name)
        trace, total = derive(fixture)
        assert trace == fixture["expected"]["gasTrace"]
        assert total == fixture["expected"]["totalGas"]
        assert fixture["expected"]["gasTraceIdentity"] == domain_identity(
            "blue-contracts-gas-trace/1.0", trace
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
    )
    processor(
        "closureWorkOccurrenceDequeued",
        1,
        f"work.{work['ordinal']}.dequeue",
        work_context=True,
    )
    processor(
        "containingReferenceUpdated",
        1,
        f"work.{work['ordinal']}.delivery",
        work_context=True,
        contract_key=work["channelKey"],
    )
    processor(
        "managedOccurrenceBindingVerified",
        1,
        f"work.{work['ordinal']}.managed-revision.binding",
        work_context=True,
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

    def finalize_intermediate(document_id: str, reason_suffix: str) -> None:
        generation = fixture_input["documents"][document_id].get(
            "componentGeneration", 0
        )
        processor(
            "tentativeComponentFinalization",
            1,
            f"work.{work['ordinal']}.managed-revision.{reason_suffix}.finalize",
            work_context=True,
            document_id=document_id,
            component_generation=generation,
        )
        establish_exact_value(
            intermediate_documents[document_id],
            trace,
            established,
            existing,
            reason_prefix=(
                f"work.{work['ordinal']}.managed-revision.{reason_suffix}.identity"
            ),
            context={
                "documentId": document_id,
                "componentGeneration": generation,
                "workOccurrenceId": work["workIdentity"],
            },
        )

    finalize_intermediate(source_document_id, "source")
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
                processor(
                    "containingReferenceUpdated",
                    1,
                    (
                        f"work.{work['ordinal']}.managed-revision.ancestor."
                        f"{document_id}.{occurrence_index}.rewrite"
                    ),
                    work_context=True,
                    document_id=document_id,
                    component_generation=fixture_input["documents"][document_id].get(
                        "componentGeneration", 0
                    ),
                )
            intermediate_blue_ids[document_id] = direct_blue_id(
                intermediate_documents[document_id]
            )
            finalize_intermediate(document_id, f"ancestor.{document_id}")
            pending_ancestors.remove(document_id)

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
            f"work.{work['ordinal']}.managed-revision.reconcile",
            work_context=True,
        )
        processor(
            "processEmbeddedEdgeExamined",
            1,
            f"work.{work['ordinal']}.managed-revision.edge",
            work_context=True,
        )
        processor(
            "componentPartitionChanged",
            1,
            f"work.{work['ordinal']}.managed-revision.partition-change",
            work_context=True,
        )
        processor(
            "componentMemberPartitioned",
            len(fixture_input["documents"]),
            f"work.{work['ordinal']}.managed-revision.partition-members",
            work_context=True,
        )
        final_active_edges = sum(
            occurrence["active"] for occurrence in expected["occurrenceBindings"]
        )
        if final_active_edges:
            processor(
                "componentEdgePartitioned",
                final_active_edges,
                f"work.{work['ordinal']}.managed-revision.partition-edges",
                work_context=True,
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
        )

    processor(
        "scopeOpened",
        1,
        f"work.{work['ordinal']}.scope",
        work_context=True,
    )
    processor(
        "contractHeaderRecognized",
        1,
        f"work.{work['ordinal']}.contracts",
        work_context=True,
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
        assert expected["gasTraceIdentity"] == domain_identity(
            "blue-contracts-gas-trace/1.0", reference_trace
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
        assert fixture["expected"]["gasTraceIdentity"] == domain_identity(
            "blue-contracts-gas-trace/1.0", trace
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
                "closureWorkOccurrenceEnqueued",
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


def main() -> None:
    oracle_stages = sum(check_oracle(p) for p in sorted(ORC.glob("*.yaml")))
    result = {
        "status": "SEMANTIC_REFERENCE_VALID",
        "oracleStages": oracle_stages,
        "finite": check_finite(),
        "loop": check_loop(),
        "identityTransitionGas": check_identity_transition_gas(),
        "history": check_history(),
        "limits": check_limits(),
        "occurrenceTransitionLaw": check_occurrence_transition_law(),
        "generationTransitionLaw": check_generation_transition_law(),
        "directSeedOrder": check_direct_seed_order(),
        "occurrenceContinuity": check_occurrence_continuity(),
        "admissionCandidates": check_admission_candidates(),
        "checkpointSettlement": check_checkpoint_settlement(),
    }
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
