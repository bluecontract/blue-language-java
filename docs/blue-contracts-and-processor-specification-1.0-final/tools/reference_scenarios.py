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
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def domain_identity(domain: str, value: Any) -> str:
    return "sha256:" + hashlib.sha256(
        canonical_json({"domain": domain, "value": value})
    ).hexdigest()


def resulting_documents(expected: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {item["documentId"]: item for item in expected["resultingDocuments"]}


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
            parent[int(final)] = value
        else:
            parent[final] = value

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
    loop_event = fixture["input"]["runtime"]["handlers"]["loop-a/start"]["events"][0]
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
            "activationGeneration": 0 if work_item["ordinal"] == 0 else 1,
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

    oracle_path = (FIX / fixture["oracle"]).resolve()
    stages = yaml.safe_load(oracle_path.read_text())["stages"]
    stage_oracles = {
        stage["name"]: cyclic_set_oracle(stage["sourceDocumentsWithThisReferences"])
        for stage in stages
    }

    # Work 0: A adds /b and emits X; the new SCC is finalized immediately
    # after all identity-changing Handler effects.
    for counter, quantity, reason, contract_key in [
        ("closureWorkOccurrenceDequeued", 1, "work.0.dequeue", None),
        ("scopeOpened", 1, "work.0.scope", None),
        ("contractHeaderRecognized", 1, "work.0.contracts", None),
        ("channelCandidateTested", 1, "work.0.channel-test", "source"),
        ("channelAccepted", 1, "work.0.channel-accepted", "source"),
        ("handlerCandidateTested", 1, "work.0.handler-test", "start"),
        ("handlerCall", 1, "work.0.handler-call", "start"),
        ("patchBoundaryChecked", 1, "work.0.patch.0.boundary", None),
        ("pointerSegmentTraversed", 1, "work.0.patch.0.pointer", None),
        ("patchAddOrReplace", 1, "work.0.patch.0.add", None),
    ]:
        processor(counter, quantity, reason, 0, contract_key=contract_key)
    patch = fixture["input"]["runtime"]["handlers"]["a/start"]["patches"][0]
    establish_exact_value(
        patch["val"],
        trace,
        established,
        existing,
        reason_prefix="work.0.patch.0.value",
        context={"documentId": "a", "workOccurrenceId": work[0]["workIdentity"]},
    )
    for counter, quantity, reason in [
        ("managedOccurrenceBindingVerified", 1, "work.0.patch.0.binding"),
        ("processEmbeddedEdgeExamined", 1, "work.0.patch.0.edge"),
        ("componentPartitionChanged", 1, "work.0.patch.0.partition-change"),
        ("componentMemberPartitioned", 2, "work.0.patch.0.partition-members"),
        ("componentEdgePartitioned", 2, "work.0.patch.0.partition-edges"),
    ]:
        processor(counter, quantity, reason, 0)
    finalize_cyclic_component(
        stage_oracles["transition-0-cycle-formed"], trace, established, existing,
        stage="work.0.transition-0-cycle-formed", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[0]["workIdentity"],
    )
    x_event = fixture["input"]["runtime"]["handlers"]["a/start"]["events"][0]
    establish_exact_value(
        x_event,
        trace,
        established,
        existing - {direct_blue_id(x_event)},
        reason_prefix="work.0.event.0",
        context={"documentId": "a", "workOccurrenceId": work[0]["workIdentity"]},
    )
    processor("internalEventEnqueued", 1, "work.0.event.0.enqueue", 0)

    # Work 1: A handles local X and finalizes M2.
    processor("internalEventDequeued", 1, "event.0.dequeue")
    processor("closureWorkOccurrenceEnqueued", 1, "event.0.delivery.1.enqueue", 1)
    processor("closureWorkOccurrenceEnqueued", 1, "event.0.delivery.2.enqueue", 2)
    for counter, quantity, reason, contract_key in [
        ("closureWorkOccurrenceDequeued", 1, "work.1.dequeue", None),
        ("triggeredEventDelivered", 1, "work.1.delivery", "localX"),
        ("scopeOpened", 1, "work.1.scope", None),
        ("contractHeaderRecognized", 1, "work.1.contracts", None),
        ("channelCandidateTested", 1, "work.1.channel-test", "localX"),
        ("channelAccepted", 1, "work.1.channel-accepted", "localX"),
        ("handlerCandidateTested", 1, "work.1.handler-test", "onLocalX"),
        ("handlerCall", 1, "work.1.handler-call", "onLocalX"),
        ("patchBoundaryChecked", 1, "work.1.patch.0.boundary", None),
        ("pointerSegmentTraversed", 1, "work.1.patch.0.pointer", None),
        ("patchAddOrReplace", 1, "work.1.patch.0.replace", None),
    ]:
        processor(counter, quantity, reason, 1, contract_key=contract_key)
    patch = fixture["input"]["runtime"]["handlers"]["a/onLocalX"]["patches"][0]
    establish_exact_value(
        patch["val"], trace, established, existing,
        reason_prefix="work.1.patch.0.value",
        context={"documentId": "a", "workOccurrenceId": work[1]["workIdentity"]},
    )
    finalize_cyclic_component(
        stage_oracles["transition-1-a-local-x"], trace, established, existing,
        stage="work.1.transition-1-a-local-x", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[1]["workIdentity"],
    )

    # Work 2: B handles embedded X, finalizes M3, then emits Y.
    for counter, quantity, reason, contract_key in [
        ("closureWorkOccurrenceDequeued", 1, "work.2.dequeue", None),
        ("embeddedEventDelivered", 1, "work.2.delivery", "fromA"),
        ("scopeOpened", 1, "work.2.scope", None),
        ("contractHeaderRecognized", 1, "work.2.contracts", None),
        ("channelCandidateTested", 1, "work.2.channel-test", "fromA"),
        ("channelAccepted", 1, "work.2.channel-accepted", "fromA"),
        ("handlerCandidateTested", 1, "work.2.handler-test", "onX"),
        ("handlerCall", 1, "work.2.handler-call", "onX"),
        ("patchBoundaryChecked", 1, "work.2.patch.0.boundary", None),
        ("pointerSegmentTraversed", 1, "work.2.patch.0.pointer", None),
        ("patchAddOrReplace", 1, "work.2.patch.0.replace", None),
    ]:
        processor(counter, quantity, reason, 2, contract_key=contract_key)
    patch = fixture["input"]["runtime"]["handlers"]["b/onX"]["patches"][0]
    establish_exact_value(
        patch["val"], trace, established, existing,
        reason_prefix="work.2.patch.0.value",
        context={"documentId": "b", "workOccurrenceId": work[2]["workIdentity"]},
    )
    finalize_cyclic_component(
        stage_oracles["transition-2-b-handled-x"], trace, established, existing,
        stage="work.2.transition-2-b-handled-x", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[2]["workIdentity"],
    )
    y_event = fixture["input"]["runtime"]["handlers"]["b/onX"]["events"][0]
    establish_exact_value(
        y_event,
        trace,
        established,
        existing - {direct_blue_id(y_event)},
        reason_prefix="work.2.event.0",
        context={"documentId": "b", "workOccurrenceId": work[2]["workIdentity"]},
    )
    processor("internalEventEnqueued", 1, "work.2.event.0.enqueue", 2)

    # Work 3: A handles Y and finalizes M4 immediately.
    processor("internalEventDequeued", 1, "event.1.dequeue")
    processor("closureWorkOccurrenceEnqueued", 1, "event.1.delivery.3.enqueue", 3)
    for counter, quantity, reason, contract_key in [
        ("closureWorkOccurrenceDequeued", 1, "work.3.dequeue", None),
        ("embeddedEventDelivered", 1, "work.3.delivery", "fromB"),
        ("scopeOpened", 1, "work.3.scope", None),
        ("contractHeaderRecognized", 1, "work.3.contracts", None),
        ("channelCandidateTested", 1, "work.3.channel-test", "fromB"),
        ("channelAccepted", 1, "work.3.channel-accepted", "fromB"),
        ("handlerCandidateTested", 1, "work.3.handler-test", "onY"),
        ("handlerCall", 1, "work.3.handler-call", "onY"),
        ("patchBoundaryChecked", 1, "work.3.patch.0.boundary", None),
        ("pointerSegmentTraversed", 1, "work.3.patch.0.pointer", None),
        ("patchAddOrReplace", 1, "work.3.patch.0.replace", None),
    ]:
        processor(counter, quantity, reason, 3, contract_key=contract_key)
    patch = fixture["input"]["runtime"]["handlers"]["a/onY"]["patches"][0]
    establish_exact_value(
        patch["val"], trace, established, existing,
        reason_prefix="work.3.patch.0.value",
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


def check_oracle(path: Path) -> int:
    data = load_yaml(path)
    stages: list[dict[str, Any]] = []
    if "stages" in data:
        stages.extend(data["stages"])
    for value in data.get("sets", []):
        stages.extend(value.get("stages", []))
    for value in data.get("initialComponents", []):
        stages.extend(value.get("stages", []))
    if isinstance(data.get("merged"), dict):
        stages.extend(data["merged"].get("stages", []))
    if isinstance(data.get("final"), dict):
        stages.extend(data["final"].get("stages", []))
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
    assert [s["oracleStage"] for s in stages] == [
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
        for counter, quantity, reason, contract_key in [
            ("closureWorkOccurrenceDequeued", 1, f"work.{work_index}.dequeue", None),
            ("scopeOpened", 1, f"work.{work_index}.scope", None),
            ("contractHeaderRecognized", 1, f"work.{work_index}.contracts", None),
            ("channelCandidateTested", 1, f"work.{work_index}.channel-test", "source"),
            ("channelAccepted", 1, f"work.{work_index}.channel-accepted", "source"),
            ("handlerCandidateTested", 1, f"work.{work_index}.handler-test", "start"),
            ("handlerCall", 1, f"work.{work_index}.handler-call", "start"),
            ("patchBoundaryChecked", 1, f"work.{work_index}.patch.0.boundary", None),
            ("pointerSegmentTraversed", 1, f"work.{work_index}.patch.0.pointer", None),
            ("patchRemove", 1, f"work.{work_index}.patch.0.remove", None),
            ("processEmbeddedEdgeExamined", 1, f"work.{work_index}.patch.0.edge", None),
            ("componentPartitionChanged", 1, f"work.{work_index}.patch.0.partition-change", None),
            ("componentMemberPartitioned", 2, f"work.{work_index}.patch.0.partition-members", None),
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
    for counter, quantity, reason, contract_key in [
        ("closureWorkOccurrenceDequeued", 1, "work.0.dequeue", None),
        ("scopeOpened", 1, "work.0.scope", None),
        ("contractHeaderRecognized", 1, "work.0.contracts", None),
        ("channelCandidateTested", 1, "work.0.channel-test", "source"),
        ("channelAccepted", 1, "work.0.channel-accepted", "source"),
        ("handlerCandidateTested", 1, "work.0.handler-test", "start"),
        ("handlerCall", 1, "work.0.handler-call", "start"),
        ("patchBoundaryChecked", 1, "work.0.patch.0.boundary", None),
        ("pointerSegmentTraversed", 1, "work.0.patch.0.pointer", None),
        ("patchRemove", 1, "work.0.patch.0.remove", None),
        ("processEmbeddedEdgeExamined", 1, "work.0.patch.0.edge", None),
        ("componentPartitionChanged", 1, "work.0.patch.0.partition-change", None),
        ("componentMemberPartitioned", 4, "work.0.patch.0.partition-members", None),
        ("componentEdgePartitioned", 3, "work.0.patch.0.partition-edges", None),
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


def check_history() -> dict[str, Any]:
    missing = load_yaml(FIX / "c-clo-22-a10-attach-a5-needs-resources.yaml")
    complete = load_yaml(FIX / "c-clo-23-a10-attach-a5-catch-up.yaml")
    assert missing["expected"]["attemptOutcome"] == "NeedsResources"
    assert missing["operation"] == complete["operation"] == "process-closure"
    prospective = next(
        occurrence
        for occurrence in complete["input"]["occurrences"]
        if occurrence["sourceDocumentId"] == "history-b"
        and occurrence["sourcePath"] == "/a"
    )
    assert prospective["active"] is False
    assert prospective["pendingHistoricalEpoch"] == 5
    transitions = complete["input"]["historicalTransitions"]
    assert [(t["fromEpoch"], t["toEpoch"]) for t in transitions] == [(5, 6), (6, 7), (7, 8), (8, 9), (9, 10)]
    for left, right in zip(transitions, transitions[1:]):
        assert left["afterBlueId"] == right["beforeBlueId"]
    final = complete["expected"]
    work = final["workTrace"]
    assert [(item["ordinal"], item["kind"]) for item in work] == [
        (0, "EXTERNAL_DELIVERY"),
        (1, "HISTORICAL_TRANSITION"),
        (2, "HISTORICAL_TRANSITION"),
        (3, "HISTORICAL_TRANSITION"),
        (4, "HISTORICAL_TRANSITION"),
        (5, "HISTORICAL_TRANSITION"),
    ]
    work_finalizations = [
        item
        for item in final["tentativeFinalizations"]
        if item["boundary"]["kind"] == "WORK"
    ]
    assert [item["boundary"]["afterWorkOrdinal"] for item in work_finalizations] == list(range(6))
    assert [item["oracleStage"].rsplit("epoch-", 1)[1] for item in work_finalizations] == [
        "5", "6", "7", "8", "9", "10"
    ]
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
    assert documents["history-b"]["document"]["observedA"] == 10
    return {
        "transitions": len(transitions),
        "finalizations": len(work_finalizations),
        "finalMaster": final["resultingComponents"][0]["masterBlueId"],
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
        name = fixture["input"]["limit"]
        configured = gas["portableLimits"][name]
        assert fixture["input"]["configured"] == configured
        assert fixture["input"]["observed"] in {configured, configured + 1}
        decision = fixture["expected"]["limitDecision"]
        assert decision == ("ACCEPT" if fixture["input"]["observed"] == configured else "REJECT")
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
            191,
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
                ("semantic", "textBlockExamined", 4),
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
        "occurrenceContinuity": check_occurrence_continuity(),
        "admissionCandidates": check_admission_candidates(),
        "checkpointSettlement": check_checkpoint_settlement(),
    }
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
