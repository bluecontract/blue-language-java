#!/usr/bin/env python3
from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import hashlib
import math
import sys
from typing import Any
import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from blue_identity import (  # noqa: E402
    ZERO_BLUEID,
    canonical_json_bytes,
    cyclic_set_oracle,
    direct_blue_id,
    direct_identity_facts,
    materialize_cyclic_members,
)
import generate_closure_fixtures as g  # noqa: E402
from gas_reference import (  # noqa: E402
    GasReferenceTrace,
    collect_existing_descendant_ids,
    establish_exact_value,
    finalize_cyclic_component,
)

FIX = ROOT / "conformance/contracts/fixtures/closure"
ORC = ROOT / "conformance/contracts/oracles"
TRACE = FIX / "traces"
TRACE.mkdir(parents=True, exist_ok=True)


def load(name: str) -> Any:
    return yaml.safe_load((FIX / name).read_text())


def dump_fixture(name: str, value: Any) -> None:
    g.dump(FIX / name, value)


def exact_trace(steps: list[tuple[str, int, str]]) -> tuple[list[dict[str, Any]], int, str]:
    manifest = yaml.safe_load((ROOT / "conformance/contracts/gas-manifest.yaml").read_text())
    weights = manifest["namespaces"]["processor"]["counters"]
    trace: list[dict[str, Any]] = []
    total = 0
    for counter, quantity, reason in steps:
        weight = weights[counter]
        subtotal = weight * quantity
        trace.append({
            "sequence": len(trace), "namespace": "processor", "counter": counter,
            "quantity": quantity, "weight": weight, "subtotal": subtotal, "reason": reason,
        })
        total += subtotal
    return trace, total, g.sha_id("blue-contracts-gas-trace/1.0", trace)


def oracle_stage_candidates(value: Any) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    if isinstance(value, dict):
        if {
            "name", "sourceDocumentsWithThisReferences", "masterBlueId",
            "memberBlueIdsInSourceOrder",
        }.issubset(value):
            result.append(value)
        for child in value.values():
            result.extend(oracle_stage_candidates(child))
    elif isinstance(value, list):
        for child in value:
            result.extend(oracle_stage_candidates(child))
    return result


def component_oracle_stage(
    fixture_path: Path,
    fixture: dict[str, Any],
    component: dict[str, Any],
) -> dict[str, Any]:
    reference = fixture.get("oracle")
    if not isinstance(reference, str):
        raise AssertionError(f"cyclic component lacks oracle in {fixture_path.name}")
    oracle_path = (fixture_path.parent / reference).resolve()
    candidates = oracle_stage_candidates(yaml.safe_load(oracle_path.read_text()))
    expected_name = component.get("oracleStage")
    expected_documents = set(component["orderedMemberDocumentIds"])
    matches: list[dict[str, Any]] = []
    for stage in candidates:
        source_documents = stage["sourceDocumentsWithThisReferences"]
        stage_documents = {
            value.get("documentId")
            for value in source_documents
            if isinstance(value, dict) and isinstance(value.get("documentId"), str)
        }
        if expected_name is not None and stage["name"] != expected_name:
            continue
        if stage_documents == expected_documents:
            matches.append(stage)
    if len(matches) != 1:
        raise AssertionError(
            f"cannot select one oracle stage for {fixture_path.name}: "
            f"name={expected_name!r} documents={sorted(expected_documents)!r} matches={len(matches)}"
        )
    return matches[0]


def finalization_oracle_stage(
    fixture_path: Path,
    fixture: dict[str, Any],
    finalization: dict[str, Any],
) -> dict[str, Any]:
    reference = fixture.get("oracle")
    if not isinstance(reference, str):
        raise AssertionError(f"finalization lacks oracle in {fixture_path.name}")
    candidates = oracle_stage_candidates(
        yaml.safe_load((fixture_path.parent / reference).resolve().read_text())
    )
    document_ids = set(finalization["memberBlueIds"])
    matches = [
        stage
        for stage in candidates
        if stage["name"] == finalization["oracleStage"]
        and {
            document.get("documentId")
            for document in stage["sourceDocumentsWithThisReferences"]
        }
        == document_ids
    ]
    if len(matches) != 1:
        raise AssertionError(
            f"cannot bind finalization stage in {fixture_path.name}: "
            f"{finalization['oracleStage']!r} ({len(matches)} matches)"
        )
    return matches[0]


def handler_result_for_work(
    fixture: dict[str, Any], work_item: dict[str, Any]
) -> tuple[str | None, dict[str, Any] | None]:
    document_id = work_item["targetDocumentId"]
    document = fixture["input"]["documents"][document_id]["document"]
    contracts = document.get("contracts", {}) if isinstance(document, dict) else {}
    buckets = fixture["input"].get("runtime", {})
    candidates: list[tuple[str, dict[str, Any]]] = []
    for bucket_name in ("handlers", "initializationHandlers"):
        for qualified, result in buckets.get(bucket_name, {}).items():
            if not qualified.startswith(document_id + "/"):
                continue
            handler_key = qualified[len(document_id) + 1:]
            contract = contracts.get(handler_key, {})
            if isinstance(contract, dict) and contract.get("channel") == work_item["channelKey"]:
                candidates.append((handler_key, result))
    if not candidates:
        return None, None
    candidates.sort(key=lambda item: item[0])
    return candidates[0]


def generic_complete_trace(fixture_path: Path, fixture: dict[str, Any]) -> tuple[list[dict[str, Any]], int, str]:
    """Produce the canonical reference trace for non-flagship closure rows.

    Flagship finite/loop/split/containing-spine traces remain independently
    authored below.  This path still constructs every admitted queue,
    dispatch, patch, event, checkpoint, acyclic identity, and cyclic
    finalization charge in execution order; it is not a fixture-size proxy.
    """
    fixture_input = fixture["input"]
    expected = fixture["expected"]
    gas_policy = fixture_input["gasPolicy"]
    trace = GasReferenceTrace(gas_policy["sharedLimit"], gas_policy.get("localLimits", {}))
    established: set[str] = set()
    existing = collect_existing_descendant_ids(
        record["document"] for record in fixture_input["documents"].values()
    )

    def charge(
        counter: str,
        quantity: int,
        reason: str,
        work_item: dict[str, Any] | None = None,
        *,
        document_id: str | None = None,
        contract_key: str | None = None,
        component_generation: int | None = None,
    ) -> None:
        context: dict[str, Any] = {}
        if work_item is not None:
            target = work_item["targetDocumentId"]
            context.update({
                "documentId": target,
                "scopePath": "/",
                "activationGeneration": 0,
                "componentGeneration": fixture_input["documents"][target].get("componentGeneration", 0),
                "logicalPath": f"work/{work_item['ordinal']}",
                "workOccurrenceId": work_item["workIdentity"],
            })
        if document_id is not None:
            context["documentId"] = document_id
        if component_generation is not None:
            context["componentGeneration"] = component_generation
        if contract_key is not None:
            context["contractKey"] = contract_key
        if not trace.charge("processor", counter, quantity, reason=reason, context=context or None):
            raise AssertionError(
                f"unexpected gas rejection while constructing {fixture_path.name}: {counter}"
            )

    # Malformed serialized evidence and up-front portable-limit rejection may
    # precede the meter.  A structurally valid, invocation-bound admission
    # candidate is different: its selected semantic verifier runs after the
    # complete ordinary closure-admission prefix.
    admission_candidate = fixture_input.get("admissionCandidate")
    if expected.get("status") == "portable-limit-exceeded" or (
        expected.get("status") == "invalid-processing-document"
        and admission_candidate is None
    ):
        return [], 0, g.sha_id("blue-contracts-gas-trace/1.0", [])

    charge("processInvocation", 1, "admission.process")
    charge("closureInvocation", 1, "admission.closure")
    if fixture_input.get("directDeliveries"):
        charge(
            "deliverySnapshotEntry", len(fixture_input["directDeliveries"]),
            "admission.direct-deliveries",
        )
    for document_id in sorted(fixture_input["documents"]):
        charge("managedDocumentOpened", 1, f"admission.document.{document_id}", document_id=document_id)
    for occurrence in sorted(
        fixture_input.get("occurrences", []),
        key=lambda item: (item["occurrenceIdentity"], item["bindingIdentity"]),
    ):
        charge(
            "managedOccurrenceBindingVerified", 1,
            f"admission.binding.{occurrence['occurrenceIdentity']}",
            document_id=occurrence["sourceDocumentId"],
        )
        if occurrence.get("active"):
            charge(
                "processEmbeddedEdgeExamined", 1,
                f"admission.edge.{occurrence['occurrenceIdentity']}",
                document_id=occurrence["sourceDocumentId"],
            )
    active_edges = [o for o in fixture_input.get("occurrences", []) if o.get("active")]
    for component in fixture_input["components"]:
        members = set(component["orderedMemberDocumentIds"])
        for document_id in component["orderedMemberDocumentIds"]:
            charge("componentMemberPartitioned", 1, "admission.component-member", document_id=document_id)
        internal_edges = [
            edge for edge in active_edges
            if edge["sourceDocumentId"] in members and edge["targetDocumentId"] in members
        ]
        for edge in internal_edges:
            charge("componentEdgePartitioned", 1, "admission.component-edge", document_id=edge["sourceDocumentId"])

    if admission_candidate is not None:
        def semantic_charge(
            counter: str,
            quantity: int,
            reason: str,
            *,
            document_id: str | None = None,
        ) -> None:
            context = {"documentId": document_id} if document_id is not None else None
            if not trace.charge(
                "semantic", counter, quantity, reason=reason, context=context
            ):
                raise AssertionError(
                    f"unexpected candidate-verifier gas rejection in {fixture_path.name}: {counter}"
                )

        def compare_identity_token(
            actual: str, expected_token: str, reason: str
        ) -> bool:
            read = 0
            for actual_cp, expected_cp in zip(actual, expected_token):
                read += 1
                if actual_cp != expected_cp:
                    break
            else:
                read = min(len(actual), len(expected_token))
                if len(actual) != len(expected_token):
                    read += 1
            read = max(1, read)
            semantic_charge("scalarComparison", 1, f"{reason}.scalar")
            semantic_charge(
                "textBlockExamined",
                math.ceil(read / 64) * 2,
                f"{reason}.text",
            )
            return actual == expected_token

        candidate_kind = admission_candidate["kind"]
        evidence = admission_candidate["evidence"]
        if candidate_kind == "BAD_CYCLIC_PROOF":
            proof = evidence["candidateCyclicProof"]
            component = next(
                component
                for component in fixture_input["components"]
                if component["componentIdentity"] == proof["componentIdentity"]
            )
            semantic_charge(
                "validationMemberExamined", 1,
                "candidate.bad-proof.record",
            )
            if compare_identity_token(
                proof["componentIdentity"],
                component["componentIdentity"],
                "candidate.bad-proof.component",
            ):
                compare_identity_token(
                    proof["masterBlueId"],
                    component["masterBlueId"],
                    "candidate.bad-proof.master",
                )
        elif candidate_kind == "AMBIGUOUS_PRELIMINARY_MEMBERS":
            members = evidence["candidateCyclicMembers"]
            for member in members:
                semantic_charge(
                    "validationMemberExamined", 1,
                    f"candidate.ambiguous.member.{member['documentId']}",
                    document_id=member["documentId"],
                )
            first = members[0]["document"]

            def replace_this(value: Any) -> Any:
                if isinstance(value, dict):
                    if set(value) == {"blueId"} and isinstance(value["blueId"], str) and (
                        value["blueId"] == "this"
                        or value["blueId"].startswith("this#")
                    ):
                        return {"blueId": ZERO_BLUEID}
                    return {key: replace_this(child) for key, child in value.items()}
                if isinstance(value, list):
                    return [replace_this(child) for child in value]
                return value

            zeroed = replace_this(first)
            zeroed_facts = direct_identity_facts(
                zeroed, allow_cyclic_placeholders=True
            )
            preliminary_blue_id = zeroed_facts.blue_id
            semantic_charge(
                "nodeIdentityEstablished", 1,
                "candidate.ambiguous.preliminary.node",
                document_id=members[0]["documentId"],
            )
            semantic_charge(
                "objectMemberRebuilt", zeroed_facts.direct_member_count,
                "candidate.ambiguous.preliminary.members",
                document_id=members[0]["documentId"],
            )
            semantic_charge(
                "directIdentityHashBlock",
                math.ceil((zeroed_facts.canonical_input_utf8_bytes + 9) / 64),
                "candidate.ambiguous.preliminary.hash",
                document_id=members[0]["documentId"],
            )
            semantic_charge("sortComparison", 1, "candidate.ambiguous.sort")
            compare_identity_token(
                preliminary_blue_id,
                direct_identity_facts(
                    replace_this(members[1]["document"]),
                    allow_cyclic_placeholders=True,
                ).blue_id,
                "candidate.ambiguous.preliminary-identity",
            )
            first_canonical = canonical_json_bytes(zeroed).decode("utf-8")
            second_canonical = canonical_json_bytes(
                replace_this(members[1]["document"])
            ).decode("utf-8")
            compare_identity_token(
                first_canonical,
                second_canonical,
                "candidate.ambiguous.canonical-input",
            )
        elif candidate_kind == "INVALID_OCCURRENCE_BINDING":
            row = evidence["candidateOccurrenceBindings"][0]
            charge(
                "managedOccurrenceBindingVerified", 1,
                "candidate.invalid-binding.row",
                document_id=row["sourceDocumentId"],
            )
            charge(
                "pointerSegmentTraversed", 1,
                "candidate.invalid-binding.path.missing",
                document_id=row["sourceDocumentId"],
            )
        else:
            raise AssertionError(f"unknown admission candidate kind {candidate_kind!r}")
        return trace.entries, trace.total, g.sha_id(
            "blue-contracts-gas-trace/1.0", trace.entries
        )

    work_trace = expected.get("workTrace", [])
    finalizations = list(expected.get("tentativeFinalizations", []))
    work_finalizations: dict[int, list[dict[str, Any]]] = {}
    initialization_finalizations: dict[int, list[dict[str, Any]]] = {}
    checkpoint_finalizations: list[dict[str, Any]] = []
    for finalization in finalizations:
        boundary = finalization["boundary"]
        if boundary["kind"] == "WORK":
            work_finalizations.setdefault(
                boundary["afterWorkOrdinal"], []
            ).append(finalization)
        elif boundary["kind"] == "INITIALIZATION_BATCH":
            initialization_finalizations.setdefault(
                boundary["afterWorkOrdinal"], []
            ).append(finalization)
        elif boundary["kind"] == "CHECKPOINT_SETTLEMENT":
            checkpoint_finalizations.append(finalization)
        else:
            raise AssertionError(f"unknown finalization boundary {boundary!r}")

    # Reconstruct the exact state immediately before checkpoint settlement.
    # It is not serialized as a second hidden fixture result; it is derived
    # from the final exact result and the closed checkpoint-write rule.
    pre_checkpoint_documents = deepcopy(expected.get("finalDocuments", {}))
    pre_checkpoint_components = deepcopy(expected.get("finalComponents", []))
    pre_checkpoint_occurrences = deepcopy(expected.get("finalOccurrences", []))
    external_work = [
        item for item in work_trace if item["kind"] == "EXTERNAL_DELIVERY"
    ]
    for document_id, record in pre_checkpoint_documents.items():
        document = record["document"]
        contracts = document.setdefault("contracts", {})
        input_checkpoint = fixture_input["documents"][document_id]["document"].get(
            "contracts", {}
        ).get("checkpoint")
        if input_checkpoint is None:
            contracts.pop("checkpoint", None)
        else:
            contracts["checkpoint"] = deepcopy(input_checkpoint)
        if not contracts:
            document.pop("contracts", None)
    if pre_checkpoint_documents:
        _recompute_snapshot(
            pre_checkpoint_documents,
            pre_checkpoint_components,
            pre_checkpoint_occurrences,
            stage_prefix="gas-pre-checkpoint",
        )

    def finalization_component_generation(finalization: dict[str, Any]) -> int:
        members = set(finalization["memberBlueIds"])
        for component in expected.get("finalComponents", []) + fixture_input["components"]:
            if set(component["orderedMemberDocumentIds"]) == members:
                return component["componentGeneration"]
        raise AssertionError(f"finalization members have no component: {sorted(members)}")

    def apply_cyclic_finalization(
        finalization: dict[str, Any],
        owner_work: dict[str, Any] | None,
        phase: str,
    ) -> None:
        stage = finalization_oracle_stage(fixture_path, fixture, finalization)
        oracle = cyclic_set_oracle(stage["sourceDocumentsWithThisReferences"])
        finalize_cyclic_component(
            oracle,
            trace,
            established,
            existing,
            stage=f"{phase}.{finalization['oracleStage']}",
            component_generation=finalization_component_generation(finalization),
            document_ids=[
                value["documentId"]
                for value in stage["sourceDocumentsWithThisReferences"]
            ],
            work_occurrence_id=(
                owner_work["workIdentity"] if owner_work is not None else None
            ),
        )

    event_groups: dict[tuple[str, int], list[dict[str, Any]]] = {}
    for item in work_trace:
        if item["kind"] in {"TRIGGERED_EVENT", "EMBEDDED_EVENT"}:
            event_groups.setdefault(
                (item["eventBlueId"], item["occurrenceOrdinal"]), []
            ).append(item)
    for group in event_groups.values():
        group.sort(key=lambda item: item["ordinal"])
    dequeued_events: set[tuple[str, int]] = set()
    emitted_events: list[tuple[tuple[str, int], dict[str, Any]]] = []
    event_occurrence_ordinal = 0
    enqueued_work: set[int] = set()

    # Direct-source checkpoint comparison is admission/classification work.
    # It must precede initialization and Handler execution.  Only the later
    # marker write is deferred to the post-quiescence settlement barrier.
    for work_item in external_work:
        charge(
            "checkpointCompared",
            1,
            f"direct-admission.{work_item['ordinal']}.checkpoint-compare",
            work_item,
        )

    initialization_seeded = False
    has_external_work = any(
        item["kind"] == "EXTERNAL_DELIVERY" for item in work_trace
    )
    for item in work_trace:
        is_initialization_seed = (
            not has_external_work
            and item["kind"] == "INITIALIZATION"
            and not initialization_seeded
        )
        if item["kind"] == "EXTERNAL_DELIVERY" or is_initialization_seed:
            charge(
                "closureWorkOccurrenceEnqueued",
                1,
                f"work.{item['ordinal']}.seed-enqueue",
                item,
            )
            enqueued_work.add(item["ordinal"])
            if is_initialization_seed:
                initialization_seeded = True

    last_initialization_ordinal = max(
        (
            item["ordinal"]
            for item in work_trace
            if item["kind"] == "INITIALIZATION"
        ),
        default=None,
    )
    last_work_ordinal = max(
        (item["ordinal"] for item in work_trace), default=None
    )
    last_work_for_document: dict[str, int] = {}
    directly_mutated_documents: set[str] = set()
    for work_item in work_trace:
        last_work_for_document[work_item["targetDocumentId"]] = work_item["ordinal"]

    managed_paths = {
        (binding["sourceDocumentId"], binding["sourcePath"])
        for binding in (
            fixture_input.get("occurrences", [])
            + expected.get("finalOccurrences", [])
        )
    }
    final_active_edge_count = sum(
        bool(binding.get("active"))
        for binding in expected.get("finalOccurrences", [])
    )

    def enqueue_lazy_kind(kind: str, before: dict[str, Any]) -> None:
        for candidate in work_trace:
            if candidate["kind"] != kind or candidate["ordinal"] in enqueued_work:
                continue
            charge(
                "closureWorkOccurrenceEnqueued",
                1,
                f"work.{candidate['ordinal']}.{kind.lower()}-enqueue",
                candidate,
            )
            enqueued_work.add(candidate["ordinal"])
            return

    for work_item in work_trace:
        ordinal = work_item["ordinal"]
        if work_item["kind"] in {"TRIGGERED_EVENT", "EMBEDDED_EVENT"}:
            event_key = (work_item["eventBlueId"], work_item["occurrenceOrdinal"])
            if event_key not in dequeued_events:
                charge(
                    "internalEventDequeued",
                    1,
                    f"event.{event_key[1]}.dequeue",
                )
                for delivery in event_groups[event_key]:
                    charge(
                        "closureWorkOccurrenceEnqueued",
                        1,
                        f"event.{event_key[1]}.delivery.{delivery['ordinal']}.enqueue",
                        delivery,
                    )
                    enqueued_work.add(delivery["ordinal"])
                dequeued_events.add(event_key)
        elif ordinal not in enqueued_work:
            enqueue_lazy_kind(work_item["kind"], work_item)
        charge("closureWorkOccurrenceDequeued", 1, f"work.{ordinal}.dequeue", work_item)
        delivery_counter = {
            "INITIALIZATION": "scopeInitialization",
            "LIFECYCLE": "lifecycleDelivered",
            "DOCUMENT_UPDATE": "documentUpdateDelivered",
            "TRIGGERED_EVENT": "triggeredEventDelivered",
            "EMBEDDED_EVENT": "embeddedEventDelivered",
            "CONTAINING_REFERENCE_UPDATE": "containingReferenceUpdated",
        }.get(work_item["kind"])
        if delivery_counter is not None:
            charge(delivery_counter, 1, f"work.{ordinal}.delivery", work_item, contract_key=work_item["channelKey"])
        charge("scopeOpened", 1, f"work.{ordinal}.scope", work_item)
        charge("contractHeaderRecognized", 1, f"work.{ordinal}.contracts", work_item)

        handler_key, handler_result = handler_result_for_work(fixture, work_item)
        if handler_result is not None:
            charge("channelCandidateTested", 1, f"work.{ordinal}.channel-test", work_item, contract_key=work_item["channelKey"])
            charge("channelAccepted", 1, f"work.{ordinal}.channel-accepted", work_item, contract_key=work_item["channelKey"])
            charge("handlerCandidateTested", 1, f"work.{ordinal}.handler-test", work_item, contract_key=handler_key)
            charge("handlerCall", 1, f"work.{ordinal}.handler-call", work_item, contract_key=handler_key)
            for patch_index, patch in enumerate(handler_result.get("patches", [])):
                directly_mutated_documents.add(work_item["targetDocumentId"])
                charge("patchBoundaryChecked", 1, f"work.{ordinal}.patch.{patch_index}.boundary", work_item)
                segments = len(patch["path"].split("/")[1:])
                if segments:
                    charge("pointerSegmentTraversed", segments, f"work.{ordinal}.patch.{patch_index}.pointer", work_item)
                charge(
                    "patchRemove" if patch["op"] == "remove" else "patchAddOrReplace",
                    1, f"work.{ordinal}.patch.{patch_index}.{patch['op']}", work_item,
                )
                if "val" in patch:
                    establish_exact_value(
                        patch["val"], trace, established, existing,
                        reason_prefix=f"work.{ordinal}.patch.{patch_index}.value",
                        context={
                            "documentId": work_item["targetDocumentId"],
                            "workOccurrenceId": work_item["workIdentity"],
                        },
                    )
                if (work_item["targetDocumentId"], patch["path"]) in managed_paths:
                    if patch["op"] != "remove":
                        charge(
                            "managedOccurrenceBindingVerified",
                            1,
                            f"work.{ordinal}.patch.{patch_index}.binding",
                            work_item,
                        )
                    charge(
                        "processEmbeddedEdgeExamined",
                        1,
                        f"work.{ordinal}.patch.{patch_index}.edge",
                        work_item,
                    )
                    charge(
                        "componentPartitionChanged",
                        1,
                        f"work.{ordinal}.patch.{patch_index}.partition-change",
                        work_item,
                    )
                    charge(
                        "componentMemberPartitioned",
                        len(fixture_input["documents"]),
                        f"work.{ordinal}.patch.{patch_index}.partition-members",
                        work_item,
                    )
                    if final_active_edge_count:
                        charge(
                            "componentEdgePartitioned",
                            final_active_edge_count,
                            f"work.{ordinal}.patch.{patch_index}.partition-edges",
                            work_item,
                        )
        # Every patch/update continuation and its exact affected-component
        # finalization completes before the Handler result's emitted events
        # are recorded and exposed to the FIFO drain.
        for finalization in work_finalizations.get(ordinal, []):
            apply_cyclic_finalization(finalization, work_item, f"work.{ordinal}")

        if handler_result is not None:
            for event_index, event_value in enumerate(handler_result.get("events", [])):
                establish_exact_value(
                    event_value,
                    trace,
                    established,
                    existing - {direct_blue_id(event_value)},
                    reason_prefix=f"work.{ordinal}.event.{event_index}",
                    context={
                        "documentId": work_item["targetDocumentId"],
                        "workOccurrenceId": work_item["workIdentity"],
                    },
                )
                charge("internalEventEnqueued", 1, f"work.{ordinal}.event.{event_index}.enqueue", work_item)
                emitted_events.append(
                    (
                        (direct_blue_id(event_value), event_occurrence_ordinal),
                        work_item,
                    )
                )
                event_key = (direct_blue_id(event_value), event_occurrence_ordinal)
                if event_key not in event_groups:
                    charge(
                        "internalEventDequeued",
                        1,
                        f"event.{event_occurrence_ordinal}.dequeue-zero-target",
                    )
                    dequeued_events.add(event_key)
                event_occurrence_ordinal += 1

        if last_initialization_ordinal == ordinal:
            enqueue_lazy_kind("LIFECYCLE", work_item)

        if ordinal in initialization_finalizations:
            for document_id, record in sorted(expected.get("finalDocuments", {}).items()):
                if (
                    not fixture_input["documents"][document_id].get("initialized")
                    and record.get("initialized")
                ):
                    charge(
                        "processorMarkerWritten",
                        1,
                        f"initialization-batch.marker.{document_id}",
                        document_id=document_id,
                    )
            for finalization in initialization_finalizations[ordinal]:
                apply_cyclic_finalization(
                    finalization, None, "initialization-batch"
                )

        document_id = work_item["targetDocumentId"]
        if last_work_for_document.get(document_id) == ordinal:
            final_record = pre_checkpoint_documents.get(document_id)
            final_component = next(
                (
                    component for component in expected.get("finalComponents", [])
                    if document_id in component["orderedMemberDocumentIds"]
                ),
                None,
            )
            if final_record is not None and final_component is not None and final_component["kind"] == "ACYCLIC":
                establish_exact_value(
                    final_record["document"], trace, established, existing,
                    reason_prefix=f"work.{ordinal}.acyclic-result",
                    context={
                        "documentId": document_id,
                        "componentGeneration": final_component["componentGeneration"],
                        "workOccurrenceId": work_item["workIdentity"],
                    },
                )
        if last_work_ordinal == ordinal:
            for remaining_id, final_record in sorted(pre_checkpoint_documents.items()):
                if remaining_id in last_work_for_document:
                    continue
                before = fixture_input["documents"].get(remaining_id)
                if before is None or before["blueId"] == final_record["blueId"]:
                    continue
                component = next(
                    component
                    for component in pre_checkpoint_components
                    if remaining_id in component["orderedMemberDocumentIds"]
                )
                if component["kind"] != "ACYCLIC":
                    continue
                charge(
                    "tentativeComponentFinalization",
                    1,
                    f"work.{ordinal}.acyclic-finalize.{remaining_id}",
                    work_item,
                    document_id=remaining_id,
                    component_generation=component["componentGeneration"],
                )
                if remaining_id not in directly_mutated_documents:
                    charge(
                        "containingReferenceUpdated",
                        1,
                        f"work.{ordinal}.containing-reference.{remaining_id}",
                        work_item,
                        document_id=remaining_id,
                        component_generation=component["componentGeneration"],
                    )
                establish_exact_value(
                    final_record["document"],
                    trace,
                    established,
                    existing,
                    reason_prefix=f"work.{ordinal}.acyclic-result.{remaining_id}",
                    context={
                        "documentId": remaining_id,
                        "componentGeneration": component["componentGeneration"],
                        "workOccurrenceId": work_item["workIdentity"],
                    },
                )

    for event_key, _owner_work in emitted_events:
        if event_key in event_groups or event_key in dequeued_events:
            continue
        raise AssertionError(
            f"emitted event was not drained at its canonical boundary: {event_key!r}"
        )
    if set(event_groups) != dequeued_events.intersection(event_groups):
        raise AssertionError(f"not every event occurrence was dequeued in {fixture_path.name}")

    # Checkpoint settlement is after the entire cause queue, never between an
    # event occurrence and its frozen deliveries.  A noncommitting attempt
    # never crosses this barrier: it may have compared a frozen checkpoint,
    # but it writes no marker and performs no settlement-owned finalization.
    committing_success = expected.get("status") == "success"
    if committing_success:
        source_checkpoint_keys: set[tuple[str, str]] = set()
        for work_item in external_work:
            source_checkpoint_keys.add(
                (work_item["targetDocumentId"], work_item["channelKey"])
            )
            charge(
                "checkpointWritten",
                1,
                f"checkpoint-settlement.{work_item['ordinal']}.write",
                work_item,
            )
        for document_id in sorted(expected.get("finalDocuments", {})):
            before_document = pre_checkpoint_documents[document_id]["document"]
            after_document = expected["finalDocuments"][document_id]["document"]
            before_entries = (
                before_document.get("contracts", {})
                .get("checkpoint", {})
                .get("entries", {})
            )
            after_entries = (
                after_document.get("contracts", {})
                .get("checkpoint", {})
                .get("entries", {})
            )
            for raw_key in sorted(set(before_entries) | set(after_entries)):
                if (document_id, raw_key) in source_checkpoint_keys:
                    continue
                if before_entries.get(raw_key) == after_entries.get(raw_key):
                    continue
                charge(
                    "checkpointWritten",
                    1,
                    f"checkpoint-settlement.cleanup.{document_id}.{raw_key}",
                    document_id=document_id,
                    contract_key=raw_key,
                    component_generation=expected["finalDocuments"][document_id][
                        "componentGeneration"
                    ],
                )
        for finalization in checkpoint_finalizations:
            apply_cyclic_finalization(
                finalization, None, "checkpoint-settlement"
            )

    final_documents = expected.get("finalDocuments", {}) if committing_success else {}
    final_components = expected.get("finalComponents", []) if committing_success else []
    settlement_changed_acyclic = []
    for document_id, final_record in final_documents.items():
        before = pre_checkpoint_documents.get(document_id)
        if before is None or before["blueId"] == final_record["blueId"]:
            continue
        component = next(
            component
            for component in final_components
            if document_id in component["orderedMemberDocumentIds"]
        )
        if component["kind"] == "ACYCLIC":
            settlement_changed_acyclic.append((document_id, final_record, component))

    # Child identities must exist before a containing parent is rebuilt.
    pending = {item[0]: item for item in settlement_changed_acyclic}
    ordered_settlement: list[tuple[str, dict[str, Any], dict[str, Any]]] = []
    while pending:
        progressed = False
        for document_id in sorted(list(pending)):
            dependencies = {
                binding["targetDocumentId"]
                for binding in expected.get("finalOccurrences", [])
                if binding.get("active") and binding["sourceDocumentId"] == document_id
            }
            if dependencies.intersection(pending):
                continue
            ordered_settlement.append(pending.pop(document_id))
            progressed = True
        if not progressed:
            raise AssertionError("acyclic settlement dependency graph is cyclic")
    checkpoint_target_documents = {
        item["targetDocumentId"] for item in external_work
    }
    for document_id, final_record, component in ordered_settlement:
        charge(
            "tentativeComponentFinalization",
            1,
            f"checkpoint-settlement.acyclic-finalize.{document_id}",
            document_id=document_id,
            component_generation=component["componentGeneration"],
        )
        if document_id not in checkpoint_target_documents:
            charge(
                "containingReferenceUpdated",
                1,
                f"checkpoint-settlement.containing-reference.{document_id}",
                document_id=document_id,
                component_generation=component["componentGeneration"],
            )
        establish_exact_value(
            final_record["document"],
            trace,
            established,
            existing,
            reason_prefix=f"checkpoint-settlement.acyclic-result.{document_id}",
            context={
                "documentId": document_id,
                "componentGeneration": component["componentGeneration"],
            },
        )

    return (
        trace.entries,
        trace.total,
        g.sha_id("blue-contracts-gas-trace/1.0", trace.entries),
    )


def ensure_complete_gas_traces() -> None:
    independently_bounded_loops = {
        "c-clo-04-same-event-gas-loop.yaml",
        "c-clo-04-default-policy-loop.yaml",
        "c-clo-25-policy-identity-binding.yaml",
        "c-clo-26-embedded-local-cap.yaml",
    }
    for path in sorted(FIX.glob("*.yaml")):
        fixture = yaml.safe_load(path.read_text())
        if fixture["operation"] == "limit-micro":
            continue
        expected = fixture["expected"]
        if expected.get("attemptOutcome") != "Complete":
            continue
        if path.name not in independently_bounded_loops:
            trace, total, trace_identity = generic_complete_trace(path, fixture)
            expected["gasTrace"] = trace
            expected["totalGas"] = total
            expected["gasTraceIdentity"] = trace_identity
            expected.pop("gasTraceFile", None)
        dump_fixture(path.name, fixture)


def add_rejected_owner_coverage_fixtures() -> None:
    """Cover non-WORK gas rejection owners with executable exact prefixes."""
    successful = load("c-clo-01-static-cycle-admission.yaml")
    input_documents = deepcopy(successful["input"]["documents"])
    input_components = deepcopy(successful["input"]["components"])
    input_occurrences = deepcopy(successful["input"].get("occurrences", []))

    def rollback_expected(
        fixture: dict[str, Any],
        *,
        trace: list[dict[str, Any]],
        rejected_charge: dict[str, Any],
        work_trace: list[dict[str, Any]],
    ) -> dict[str, Any]:
        return {
            "attemptOutcome": "Complete",
            "status": "gas-limit-exceeded",
            "diagnostic": "GasLimitExceeded",
            "atomic": True,
            "workTrace": deepcopy(work_trace),
            "tentativeFinalizations": [],
            "finalGraphGeneration": fixture["input"]["graphGeneration"],
            "finalDocuments": deepcopy(input_documents),
            "finalComponents": deepcopy(input_components),
            "finalOccurrences": deepcopy(input_occurrences),
            "publicEvents": [],
            "checkpointsCommitted": False,
            "rollbackToInput": True,
            "gasTrace": deepcopy(trace),
            "totalGas": sum(entry["subtotal"] for entry in trace),
            "gasTraceIdentity": g.sha_id(
                "blue-contracts-gas-trace/1.0", trace
            ),
            "rejectedCharge": rejected_charge,
        }

    invocation = deepcopy(successful)
    invocation["id"] = "c-clo-31-invocation-owned-gas-rejection"
    invocation["vectors"] = ["C-CLO-31"]
    invocation["description"] = (
        "A zero shared ceiling rejects processInvocation before any work "
        "occurrence exists; exact rejection ownership is INVOCATION and the "
        "literal closure snapshot is returned."
    )
    invocation["input"]["gasPolicy"] = g.policy(
        0, default=False, label="invocation-owner-zero"
    )
    invocation["expected"] = rollback_expected(
        invocation,
        trace=[],
        work_trace=[],
        rejected_charge={
            "namespace": "processor",
            "counter": "processInvocation",
            "quantity": 1,
            "weight": 50,
            "subtotal": 50,
            "applicableCap": {"kind": "SHARED"},
            "remainingBeforeCharge": 0,
            "owner": {"kind": "INVOCATION"},
        },
    )
    dump_fixture("c-clo-31-invocation-owned-gas-rejection.yaml", invocation)

    finalization = deepcopy(successful)
    finalization["id"] = "c-clo-32-finalization-owned-gas-rejection"
    finalization["vectors"] = ["C-CLO-32"]
    finalization["description"] = (
        "Initialization work and marker writes fit, but the first whole-component "
        "tentative finalization charge is rejected. Ownership names the exact "
        "finalization ordinal and component; the closure rolls back atomically."
    )
    success_trace = successful["expected"]["gasTrace"]
    rejection_index = next(
        index
        for index, entry in enumerate(success_trace)
        if entry["counter"] == "tentativeComponentFinalization"
    )
    prefix = deepcopy(success_trace[:rejection_index])
    prefix_total = sum(entry["subtotal"] for entry in prefix)
    limit = prefix_total + 19
    finalization["input"]["gasPolicy"] = g.policy(
        limit, default=False, label="finalization-owner-boundary"
    )
    component = input_components[0]
    finalization["expected"] = rollback_expected(
        finalization,
        trace=prefix,
        work_trace=successful["expected"]["workTrace"],
        rejected_charge={
            "namespace": "processor",
            "counter": "tentativeComponentFinalization",
            "quantity": 1,
            "weight": 20,
            "subtotal": 20,
            "applicableCap": {"kind": "SHARED"},
            "remainingBeforeCharge": 19,
            "owner": {
                "kind": "FINALIZATION",
                "finalizationOrdinal": 0,
                "componentIdentity": component["componentIdentity"],
                "componentGeneration": component["componentGeneration"],
            },
        },
    )
    dump_fixture(
        "c-clo-32-finalization-owned-gas-rejection.yaml", finalization
    )


def normalize_literal_rollback_results() -> None:
    """Make every noncommitting completed result return the literal snapshot."""
    for path in sorted(FIX.glob("*.yaml")):
        fixture = yaml.safe_load(path.read_text())
        if fixture["operation"] == "limit-micro":
            continue
        expected = fixture["expected"]
        if expected.get("attemptOutcome") == "Complete" and expected.get("rollbackToInput") is True:
            expected["finalGraphGeneration"] = fixture["input"]["graphGeneration"]
            expected["finalDocuments"] = deepcopy(fixture["input"]["documents"])
            expected["finalComponents"] = deepcopy(fixture["input"]["components"])
            expected["finalOccurrences"] = deepcopy(fixture["input"].get("occurrences", []))
            expected["publicEvents"] = []
            expected["checkpointsCommitted"] = False
        dump_fixture(path.name, fixture)


def rejected_charge_payload(trace: GasReferenceTrace) -> dict[str, Any]:
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


def loop_trace(
    fixture: dict[str, Any],
    invocation: str,
) -> tuple[list[dict[str, Any]], int, list[dict[str, Any]], dict[str, Any] | None, dict[str, Any]]:
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
            return g.work(
                0, "EXTERNAL_DELIVERY", "loop-a", "source",
                event_id=start_event_id, occurrence_ordinal=0, invocation=invocation,
            )
        target = "loop-b" if ordinal % 2 == 1 else "loop-a"
        channel = "fromA" if target == "loop-b" else "fromB"
        return g.work(
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
        "checkpointCompared", 1,
        "direct-admission.0.checkpoint-compare", current,
    ):
        raise AssertionError("loop policy cannot reject fixed checkpoint comparison")
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
            steps.extend([
                ("embeddedEventDelivered", 1, "embedded-delivery", channel_key),
            ])
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
            "internalEventDequeued", 1,
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
        rejected_charge_payload(trace),
    )


def rewrite_loop_gas_traces() -> None:
    cases = [
        ("c-clo-04-same-event-gas-loop.yaml", "loop"),
        ("c-clo-04-default-policy-loop.yaml", "default-loop"),
        ("c-clo-25-policy-identity-binding.yaml", "loop"),
        ("c-clo-26-embedded-local-cap.yaml", "local-cap"),
    ]
    for name, invocation in cases:
        fixture = load(name)
        trace, total, completed, rejected_work, rejected_charge = loop_trace(
            fixture, invocation
        )
        expected = fixture["expected"]
        expected["workTrace"] = completed
        if rejected_work is not None:
            expected["rejectedWork"] = rejected_work
        else:
            expected.pop("rejectedWork", None)
            rejected_charge["owner"] = {"kind": "INVOCATION"}
        expected["rejectedCharge"] = rejected_charge
        expected["gasTrace"] = trace
        expected["totalGas"] = total
        expected["gasTraceIdentity"] = g.sha_id(
            "blue-contracts-gas-trace/1.0", trace
        )
        dump_fixture(name, fixture)


def finite_trace(fixture: dict[str, Any]) -> tuple[list[dict[str, Any]], int, str]:
    trace = GasReferenceTrace(fixture["input"]["gasPolicy"]["sharedLimit"])
    established: set[str] = set()
    existing = collect_existing_descendant_ids(
        record["document"] for record in fixture["input"]["documents"].values()
    )
    work = fixture["expected"]["workTrace"]

    def processor(counter: str, quantity: int, reason: str, work_index: int | None = None, document_id: str | None = None) -> None:
        context: dict[str, Any] = {}
        if work_index is not None:
            context["workOccurrenceId"] = work[work_index]["workIdentity"]
        if document_id is not None:
            context.update({"documentId": document_id, "scopePath": "/", "activationGeneration": 0})
        trace.charge("processor", counter, quantity, reason=reason, context=context)

    for counter, quantity, reason in [
        ("processInvocation", 1, "admission.process"),
        ("closureInvocation", 1, "admission.closure"),
        ("deliverySnapshotEntry", 1, "admission.direct-delivery"),
        ("managedDocumentOpened", 2, "admission.documents"),
        ("managedOccurrenceBindingVerified", 1, "admission.binding-b-a"),
        ("processEmbeddedEdgeExamined", 1, "admission.edge-b-a"),
        ("componentMemberPartitioned", 2, "admission.singleton-partition"),
        ("closureWorkOccurrenceEnqueued", 1, "work.0.enqueue"),
    ]:
        processor(counter, quantity, reason)

    stages = yaml.safe_load((ORC / "finite-dynamic-a-b.yaml").read_text())["stages"]
    stage_oracles = {
        stage["name"]: cyclic_set_oracle(stage["sourceDocumentsWithThisReferences"])
        for stage in stages
    }

    # Work 0: A adds /b, forms the SCC, finalizes M1, then emits X.
    for counter, quantity, reason in [
        ("closureWorkOccurrenceDequeued", 1, "work.0.dequeue"), ("scopeOpened", 1, "work.0.scope"),
        ("contractHeaderRecognized", 2, "work.0.contracts"), ("channelCandidateTested", 1, "work.0.channel-test"),
        ("channelAccepted", 1, "work.0.channel-accept"), ("handlerCandidateTested", 1, "work.0.handler-test"),
        ("handlerCall", 1, "work.0.handler"), ("patchBoundaryChecked", 1, "work.0.patch-boundary"),
        ("patchAddOrReplace", 1, "work.0.patch-add-b"), ("managedOccurrenceBindingVerified", 1, "work.0.binding-a-b"),
        ("processEmbeddedEdgeExamined", 2, "work.0.edges"), ("componentPartitionChanged", 1, "work.0.form-scc"),
        ("componentMemberPartitioned", 2, "work.0.partition-members"), ("componentEdgePartitioned", 2, "work.0.partition-edges"),
    ]:
        processor(counter, quantity, reason, 0, "a")
    finalize_cyclic_component(
        stage_oracles["cycle-formed"], trace, established, existing,
        stage="m1-cycle-formed", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[0]["workIdentity"],
    )
    x_event = fixture["input"]["runtime"]["handlers"]["a/start"]["events"][0]
    establish_exact_value(
        x_event,
        trace,
        established,
        existing - {direct_blue_id(x_event)},
        reason_prefix="work.0.event-x",
        context={"documentId": "a", "workOccurrenceId": work[0]["workIdentity"]},
    )
    processor("internalEventEnqueued", 1, "work.0.event-x.enqueue", 0, "a")
    processor("closureWorkOccurrenceEnqueued", 2, "work.0.caused-work.enqueue", 0, "a")

    # Work 1: A handles local X and finalizes M2.
    for counter, quantity, reason in [
        ("closureWorkOccurrenceDequeued", 1, "work.1.dequeue"), ("internalEventDequeued", 1, "work.1.event-dequeue"),
        ("triggeredEventDelivered", 1, "work.1.triggered"), ("scopeOpened", 1, "work.1.scope"),
        ("contractHeaderRecognized", 2, "work.1.contracts"), ("channelCandidateTested", 1, "work.1.channel-test"),
        ("channelAccepted", 1, "work.1.channel-accept"), ("handlerCandidateTested", 1, "work.1.handler-test"),
        ("handlerCall", 1, "work.1.handler"), ("patchBoundaryChecked", 1, "work.1.patch-boundary"),
        ("patchAddOrReplace", 1, "work.1.patch-local-x"),
    ]:
        processor(counter, quantity, reason, 1, "a")
    finalize_cyclic_component(
        stage_oracles["a-local-x"], trace, established, existing,
        stage="m2-a-local-x", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[1]["workIdentity"],
    )

    # Work 2: B handles embedded X, finalizes M3, then emits Y.
    for counter, quantity, reason in [
        ("closureWorkOccurrenceDequeued", 1, "work.2.dequeue"), ("internalEventDequeued", 1, "work.2.event-dequeue"),
        ("embeddedEventDelivered", 1, "work.2.embedded"), ("scopeOpened", 1, "work.2.scope"),
        ("contractHeaderRecognized", 2, "work.2.contracts"), ("channelCandidateTested", 1, "work.2.channel-test"),
        ("channelAccepted", 1, "work.2.channel-accept"), ("handlerCandidateTested", 1, "work.2.handler-test"),
        ("handlerCall", 1, "work.2.handler"), ("patchBoundaryChecked", 1, "work.2.patch-boundary"),
        ("patchAddOrReplace", 1, "work.2.patch-x-handled"),
    ]:
        processor(counter, quantity, reason, 2, "b")
    finalize_cyclic_component(
        stage_oracles["b-handled-x"], trace, established, existing,
        stage="m3-b-handled-x", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[2]["workIdentity"],
    )
    y_event = fixture["input"]["runtime"]["handlers"]["b/onX"]["events"][0]
    establish_exact_value(
        y_event,
        trace,
        established,
        existing - {direct_blue_id(y_event)},
        reason_prefix="work.2.event-y",
        context={"documentId": "b", "workOccurrenceId": work[2]["workIdentity"]},
    )
    processor("internalEventEnqueued", 1, "work.2.event-y.enqueue", 2, "b")
    processor("closureWorkOccurrenceEnqueued", 1, "work.2.caused-work.enqueue", 2, "b")

    # Work 3: A handles Y and finalizes M4 immediately.
    for counter, quantity, reason in [
        ("closureWorkOccurrenceDequeued", 1, "work.3.dequeue"), ("internalEventDequeued", 1, "work.3.event-dequeue"),
        ("embeddedEventDelivered", 1, "work.3.embedded"), ("scopeOpened", 1, "work.3.scope"),
        ("contractHeaderRecognized", 2, "work.3.contracts"), ("channelCandidateTested", 1, "work.3.channel-test"),
        ("channelAccepted", 1, "work.3.channel-accept"), ("handlerCandidateTested", 1, "work.3.handler-test"),
        ("handlerCall", 1, "work.3.handler"), ("patchBoundaryChecked", 1, "work.3.patch-boundary"),
        ("patchAddOrReplace", 1, "work.3.patch-result"),
    ]:
        processor(counter, quantity, reason, 3, "a")
    finalize_cyclic_component(
        stage_oracles["a-finished"], trace, established, existing,
        stage="m4-a-finished", component_generation=2,
        document_ids=["a", "b"], work_occurrence_id=work[3]["workIdentity"],
    )
    processor("checkpointWritten", 1, "work.3.checkpoint", 3, "a")
    return trace.entries, trace.total, g.sha_id("blue-contracts-gas-trace/1.0", trace.entries)


def oracle_stage(name: str, stage: str) -> dict[str, Any]:
    data = yaml.safe_load((ORC / name).read_text())
    for s in data.get("stages", []):
        if s["name"] == stage:
            return s
    raise KeyError((name, stage))


def add_exact_identity_visibility_fixture() -> None:
    finite = load("c-clo-02-dynamic-finite-cycle.yaml")
    # Keep vector-to-fixture coverage explicit rather than claiming four vectors from one file.
    finite["vectors"] = ["C-CLO-02"]
    dump_fixture("c-clo-02-dynamic-finite-cycle.yaml", finite)

    result = deepcopy(finite)
    result["id"] = "c-clo-03-exact-tentative-identity-visibility"
    result["vectors"] = ["C-CLO-03"]
    result["description"] = (
        "After each identity-affecting transition the complete cyclic component is tentatively "
        "re-finalized. B reads A through the exact M2 member identity and A later reads B through "
        "the exact M3 member identity; no virtual or identity-less handle is application-visible."
    )
    m2 = result["expected"]["tentativeFinalizations"][1]["memberBlueIds"]["a"]
    m3 = result["expected"]["tentativeFinalizations"][2]["memberBlueIds"]["b"]
    result["expected"]["observations"] = [
        {"workOrdinal": 2, "targetDocumentId": "b", "pointer": "/a", "observedBlueId": m2},
        {"workOrdinal": 3, "targetDocumentId": "a", "pointer": "/b", "observedBlueId": m3},
    ]
    trace, total, trace_id = finite_trace(finite)
    result["expected"]["gasTrace"] = trace
    result["expected"]["totalGas"] = total
    result["expected"]["gasTraceIdentity"] = trace_id
    dump_fixture("c-clo-03-exact-tentative-identity-visibility.yaml", result)
    # The flagship finite fixture carries the same exact gas proof.
    finite["expected"]["gasTrace"] = trace
    finite["expected"]["totalGas"] = total
    finite["expected"]["gasTraceIdentity"] = trace_id
    dump_fixture("c-clo-02-dynamic-finite-cycle.yaml", finite)


def rewrite_frozen_edge_removal() -> None:
    stage = oracle_stage("finite-dynamic-a-b.yaml", "cycle-formed")
    src_a, src_b = deepcopy(stage["sourceDocumentsWithThisReferences"])
    ids = stage["memberBlueIdsInSourceOrder"]
    mats = stage["materializedDocuments"]

    # X is created while A<->B exists. A's local X reaction then retires /b.
    # B still receives that already-created X once through the frozen target set.
    a_final = deepcopy(src_a)
    a_final.pop("b", None)
    a_final_id = direct_blue_id(a_final)
    b_final = deepcopy(src_b)
    b_final["a"] = g.ref(a_final_id)
    b_final_id = direct_blue_id(b_final)

    evt = g.event("FROZEN-REMOVE")
    x = g.event("X")
    x_id = direct_blue_id(x)
    # src docs already bind the exact X event used by the finite oracle.
    input_value = {
        "graphGeneration": 1,
        "cause": g.external_cause(evt, [250, "timeline-frozen-remove", 1]),
        "documents": {
            "a": g.document_record("a", ids[0], mats[0], public_root=True, epoch=0, component_generation=1),
            "b": g.document_record("b", ids[1], mats[1], epoch=0, component_generation=1),
        },
        "occurrences": [
            g.occurrence("a", "/b", 1, "b", ids[1]),
            g.occurrence("b", "/a", 1, "a", ids[0]),
        ],
        "components": [g.component("CYCLIC", 1, ["a", "b"], master=stage["masterBlueId"], stage="cycle-formed")],
        "directDeliveries": [g.direct_delivery("a")],
        "gasPolicy": g.policy(),
        "runtime": {"handlers": {
            "a/start": {"events": [x]},
            "a/onLocalX": {"patches": [{"op": "remove", "path": "/b"}]},
            "b/onX": {},
        }},
    }
    expected = g.empty_expected()
    expected.update({
        "finalGraphGeneration": 2,
        "workTrace": [
            g.work(0, "EXTERNAL_DELIVERY", "a", "source", event_id=direct_blue_id(evt), occurrence_ordinal=0, invocation="frozen-remove"),
            g.work(1, "TRIGGERED_EVENT", "a", "localX", event_id=x_id, occurrence_ordinal=0, invocation="frozen-remove"),
            g.work(2, "EMBEDDED_EVENT", "b", "fromA", event_id=x_id, occurrence_ordinal=0, invocation="frozen-remove"),
        ],
        "finalDocuments": {
            "a": g.document_record("a", a_final_id, a_final, public_root=True, epoch=1, component_generation=2),
            "b": g.document_record("b", b_final_id, b_final, epoch=1, component_generation=2),
        },
        "finalComponents": [
            g.component("ACYCLIC", 2, ["a"]),
            g.component("ACYCLIC", 2, ["b"]),
        ],
        "finalOccurrences": [g.occurrence("b", "/a", 1, "a", a_final_id)],
        "publicEvents": [],
        "checkpointsCommitted": True,
        "rollbackToInput": False,
    })
    f = g.base_fixture(
        "c-clo-12-frozen-edge-removal", ["C-CLO-12"], "ordering", "process-closure",
        "X is created while A<->B exists. A's local X reaction retires /b, but B remains an exact frozen target for this X occurrence once. Later occurrences use the new B->A graph.",
        "finite-dynamic-a-b.yaml", input_value, expected,
    )
    dump_fixture("c-clo-12-frozen-edge-removal.yaml", f)


def rewrite_frozen_edge_addition() -> None:
    # Use IDs that force the source direct seed before the owner direct seed.
    evt = g.event("FROZEN-ADD")
    x = g.event("X-BEFORE-ADD")
    x_id = direct_blue_id(x)

    owner = {
        "documentId": "z-owner", "memberIdentity": "z-owner", "receivedOldX": False,
        "contracts": {
            "embedded": {"type": g.ref(g.PE), "paths": ["/source"]},
            "sourceChannel": {"type": g.ref(g.SEC), "subscriptionKey": "fixture", "eventKey": "fixture", "accept": True, "checkpointDomain": "domain", "logicalDeliveryKey": "owner"},
            "attach": {"type": g.ref(g.SH), "channel": "sourceChannel", "order": 0},
            "fromSource": {"type": g.ref(g.ENC), "sourcePath": "/source", "event": g.ref(x_id), "order": 0},
            "onOldX": {"type": g.ref(g.SH), "channel": "fromSource", "order": 0},
        },
    }
    owner_id = direct_blue_id(owner)
    source = {
        "documentId": "a-source", "memberIdentity": "a-source", "owner": g.ref(owner_id),
        "contracts": {
            "embedded": {"type": g.ref(g.PE), "paths": ["/owner"]},
            "sourceChannel": {"type": g.ref(g.SEC), "subscriptionKey": "fixture", "eventKey": "fixture", "accept": True, "checkpointDomain": "domain", "logicalDeliveryKey": "source"},
            "emit": {"type": g.ref(g.SH), "channel": "sourceChannel", "order": 0},
        },
    }
    source_id = direct_blue_id(source)
    final_source_src = deepcopy(source); final_source_src["owner"] = g.ref("this#1")
    final_owner_src = deepcopy(owner); final_owner_src["source"] = g.ref("this#0")
    oracle = cyclic_set_oracle([final_source_src, final_owner_src])
    mats = materialize_cyclic_members([final_source_src, final_owner_src], oracle)
    g.dump(ORC / "frozen-edge-addition.yaml", g.oracle_payload("frozen-edge-addition", [("final", [final_source_src, final_owner_src], oracle)]))
    ids = oracle.member_ids_in_source_order()

    input_value = {
        "graphGeneration": 1,
        "cause": g.external_cause(evt, [251, "timeline-frozen-add", 1]),
        "documents": {
            "a-source": g.document_record("a-source", source_id, source, epoch=0, component_generation=1),
            "z-owner": g.document_record("z-owner", owner_id, owner, public_root=True, epoch=0, component_generation=1),
        },
        "occurrences": [
            g.occurrence("a-source", "/owner", 1, "z-owner", owner_id),
            g.occurrence(
                "z-owner", "/source", 1, "a-source", source_id, active=False
            ),
        ],
        "components": [g.component("ACYCLIC", 1, ["z-owner"]), g.component("ACYCLIC", 1, ["a-source"])],
        "directDeliveries": [
            g.direct_delivery("a-source", channel="sourceChannel", key="source", order=0),
            g.direct_delivery("z-owner", channel="sourceChannel", key="owner", order=1),
        ],
        "gasPolicy": g.policy(),
        "runtime": {"handlers": {
            "a-source/emit": {"events": [x]},
            "z-owner/attach": {"patches": [{"op": "add", "path": "/source", "val": g.ref(source_id)}]},
            "z-owner/onOldX": {"patches": [{"op": "replace", "path": "/receivedOldX", "val": True}]},
        }},
    }
    expected = g.empty_expected()
    expected.update({
        "finalGraphGeneration": 2,
        "workTrace": [
            g.work(0, "EXTERNAL_DELIVERY", "a-source", "sourceChannel", event_id=direct_blue_id(evt), occurrence_ordinal=0, invocation="frozen-add"),
            g.work(1, "EXTERNAL_DELIVERY", "z-owner", "sourceChannel", event_id=direct_blue_id(evt), occurrence_ordinal=1, invocation="frozen-add"),
        ],
        "tentativeFinalizations": [g.finalize(0, "final", oracle, ["a-source", "z-owner"])],
        "finalDocuments": {
            "a-source": g.document_record("a-source", ids[0], mats[0], epoch=1, component_generation=2),
            "z-owner": g.document_record("z-owner", ids[1], mats[1], public_root=True, epoch=1, component_generation=2),
        },
        "finalComponents": [g.component("CYCLIC", 2, ["a-source", "z-owner"], master=oracle.master_blue_id, stage="final")],
        "finalOccurrences": [
            g.occurrence("a-source", "/owner", 1, "z-owner", ids[1]),
            g.occurrence("z-owner", "/source", 1, "a-source", ids[0]),
        ],
        "publicEvents": [],
        "observations": [{"workOrdinal": 0, "targetDocumentId": "z-owner", "pointer": "/receivedOldX", "observedValue": False}],
        "checkpointsCommitted": True,
        "rollbackToInput": False,
    })
    f = g.base_fixture(
        "c-clo-13-frozen-edge-addition", ["C-CLO-13"], "ordering", "process-closure",
        "The a-source seed emits X before z-owner adds /source. X's frozen targets do not retroactively include the new z-owner->a-source edge; the owner remains receivedOldX=false. Later occurrences use the new cycle.",
        "frozen-edge-addition.yaml", input_value, expected,
    )
    dump_fixture("c-clo-13-frozen-edge-addition.yaml", f)


def fix_invalid_and_ambiguous() -> None:
    invalid = load("c-clo-14-invalid-cyclic-proof.yaml")
    # Keep the authoritative snapshot valid and supply a separately named bad
    # proof candidate.  A failed proof admission can then return the literal
    # input snapshot rather than a harness-corrected variant of it.
    wrong = direct_blue_id(g.event("WRONG-PROOF-MASTER"))
    invalid["input"]["components"] = deepcopy(invalid["expected"]["finalComponents"])
    invalid["input"]["candidateCyclicProof"] = {"wrongMasterBlueId": wrong}
    invalid["expected"]["finalComponents"] = deepcopy(invalid["input"]["components"])
    dump_fixture("c-clo-14-invalid-cyclic-proof.yaml", invalid)

    ambiguous = load("c-clo-15-ambiguous-preliminary-members.yaml")
    candidate = [
        {"documentId": "amb-a", "document": {"same": True, "other": g.ref("this#1")}},
        {"documentId": "amb-b", "document": {"same": True, "other": g.ref("this#0")}},
    ]
    ambiguous["input"]["candidateCyclicMembers"] = candidate
    singleton_components = [
        g.component("ACYCLIC", 0, ["amb-a"]),
        g.component("ACYCLIC", 0, ["amb-b"]),
    ]
    ambiguous["input"]["components"] = singleton_components
    ambiguous["expected"]["finalComponents"] = deepcopy(singleton_components)
    dump_fixture("c-clo-15-ambiguous-preliminary-members.yaml", ambiguous)

    missing_path = load("c-clo-29-active-edge-path-validation.yaml")
    candidate_binding = deepcopy(missing_path["input"]["occurrences"][0])
    candidate_binding.pop("pendingHistoricalEpoch", None)
    missing_path["input"]["candidateOccurrenceBindings"] = [candidate_binding]
    missing_path["input"]["occurrences"] = deepcopy(
        missing_path["expected"]["finalOccurrences"]
    )
    missing_path["input"]["components"] = deepcopy(
        missing_path["expected"]["finalComponents"]
    )
    dump_fixture("c-clo-29-active-edge-path-validation.yaml", missing_path)


def ring_fixture(count: int, *, accepted: bool) -> tuple[dict[str, Any], dict[str, Any]]:
    source_docs: list[Any] = []
    for i in range(count):
        target = (i + 1) % count
        source_docs.append({
            "documentId": f"limit-{i:03d}",
            "memberIdentity": f"limit-{i:03d}",
            "next": g.ref(f"this#{target}"),
            "contracts": {"embedded": {"type": g.ref(g.PE), "paths": ["/next"]}},
        })
    oracle = cyclic_set_oracle(source_docs)
    mats = materialize_cyclic_members(source_docs, oracle)
    ids = oracle.member_ids_in_source_order()
    oracle_name = f"limit-ring-{count}.yaml"
    g.dump(ORC / oracle_name, g.oracle_payload(f"limit-ring-{count}", [("initial", source_docs, oracle)]))
    docs = {
        f"limit-{i:03d}": g.document_record(
            f"limit-{i:03d}", ids[i], mats[i], initialized=True,
            public_root=(i == 0), epoch=0, component_generation=1,
        ) for i in range(count)
    }
    occ = [
        g.occurrence(f"limit-{i:03d}", "/next", 1, f"limit-{(i+1)%count:03d}", ids[(i+1)%count])
        for i in range(count)
    ]
    comp = g.component("CYCLIC", 1, sorted(docs), master=oracle.master_blue_id, stage="initial")
    inp = {
        "graphGeneration": 1,
        "cause": g.admission_cause("TOP_LEVEL_ADMISSION", f"limit-ring-{count}"),
        "documents": docs,
        "occurrences": occ,
        "components": [comp],
        "directDeliveries": [],
        "gasPolicy": g.policy(),
        "runtime": {"handlers": {}},
    }
    exp = g.empty_expected("success" if accepted else "portable-limit-exceeded")
    exp.update({
        "finalGraphGeneration": 1,
        "finalDocuments": docs,
        "finalComponents": [comp],
        "finalOccurrences": occ,
        "rollbackToInput": not accepted,
        "checkpointsCommitted": False,
    })
    if not accepted:
        exp["diagnostic"] = "CyclicComponentMemberLimitExceeded"
    return inp, exp


def rewrite_limits() -> None:
    at_in, at_exp = ring_fixture(128, accepted=True)
    above_in, above_exp = ring_fixture(129, accepted=False)
    dump_fixture("c-clo-16-limit-at-bound.yaml", g.base_fixture(
        "c-clo-16-limit-at-bound", ["C-CLO-16"], "limits", "admit-closure",
        "A real 128-member ring component, with every exact document, edge, member identity, and complete cyclic proof present, is accepted at the frozen member limit.",
        "limit-ring-128.yaml", at_in, at_exp,
    ))
    dump_fixture("c-clo-17-limit-above-bound.yaml", g.base_fixture(
        "c-clo-17-limit-above-bound", ["C-CLO-17"], "limits", "admit-closure",
        "A real 129-member ring component is independently valid as Blue cyclic content but is rejected before semantic work by the frozen 128-member Contracts limit.",
        "limit-ring-129.yaml", above_in, above_exp,
    ))


def add_split_identity_trace() -> None:
    fixture = load("c-clo-10-split-to-singletons.yaml")
    trace = GasReferenceTrace(fixture["input"]["gasPolicy"]["sharedLimit"])
    existing = collect_existing_descendant_ids(
        record["document"] for record in fixture["input"]["documents"].values()
    )
    established: set[str] = set()
    work = fixture["expected"]["workTrace"]

    def processor(counter: str, quantity: int, reason: str, work_index: int | None = None, document_id: str | None = None, component_generation: int | None = None) -> None:
        context: dict[str, Any] = {}
        if work_index is not None:
            context["workOccurrenceId"] = work[work_index]["workIdentity"]
        if document_id is not None:
            context.update({"documentId": document_id, "scopePath": "/", "activationGeneration": 0})
        if component_generation is not None:
            context["componentGeneration"] = component_generation
        trace.charge("processor", counter, quantity, reason=reason, context=context)

    for counter, quantity, reason in [
        ("processInvocation", 1, "admission.process"),
        ("closureInvocation", 1, "admission.closure"),
        ("deliverySnapshotEntry", 2, "admission.direct-deliveries"),
        ("managedDocumentOpened", 2, "admission.documents"),
        ("managedOccurrenceBindingVerified", 2, "admission.bindings"),
        ("processEmbeddedEdgeExamined", 2, "admission.edges"),
        ("componentMemberPartitioned", 2, "admission.component-members"),
        ("componentEdgePartitioned", 2, "admission.component-edges"),
        ("closureWorkOccurrenceEnqueued", 2, "admission.direct-work"),
    ]:
        processor(counter, quantity, reason)

    for counter, quantity, reason in [
        ("closureWorkOccurrenceDequeued", 1, "work.0.dequeue"),
        ("scopeOpened", 1, "work.0.scope"),
        ("contractHeaderRecognized", 2, "work.0.contracts"),
        ("channelCandidateTested", 1, "work.0.channel-test"),
        ("channelAccepted", 1, "work.0.channel-accept"),
        ("handlerCandidateTested", 1, "work.0.handler-test"),
        ("handlerCall", 1, "work.0.handler"),
        ("patchBoundaryChecked", 1, "work.0.patch-boundary"),
        ("patchRemove", 1, "work.0.remove-a-b"),
        ("processEmbeddedEdgeExamined", 1, "work.0.remaining-edge"),
        ("componentPartitionChanged", 1, "work.0.split-component"),
        ("componentMemberPartitioned", 2, "work.0.partition-members"),
        ("componentEdgePartitioned", 1, "work.0.partition-edge"),
    ]:
        processor(counter, quantity, reason, 0, "simple-a", 2)

    a_after = fixture["expected"]["finalDocuments"]["simple-a"]["document"]
    a_after_id = fixture["expected"]["finalDocuments"]["simple-a"]["blueId"]
    processor("tentativeComponentFinalization", 1, "work.0.finalize-a", 0, "simple-a", 2)
    establish_exact_value(
        a_after, trace, established, existing, reason_prefix="work.0.acyclic-a",
        context={"documentId": "simple-a", "componentGeneration": 2, "workOccurrenceId": work[0]["workIdentity"]},
    )
    b_intermediate = deepcopy(fixture["input"]["documents"]["simple-b"]["document"])
    b_intermediate["a"] = g.ref(a_after_id)
    processor("tentativeComponentFinalization", 1, "work.0.finalize-b", 0, "simple-b", 2)
    processor("containingReferenceUpdated", 1, "work.0.rewrite-b-a", 0, "simple-b", 2)
    establish_exact_value(
        b_intermediate, trace, established, existing, reason_prefix="work.0.acyclic-b",
        context={"documentId": "simple-b", "componentGeneration": 2, "workOccurrenceId": work[0]["workIdentity"]},
    )
    processor("checkpointWritten", 1, "work.0.checkpoint", 0, "simple-a", 2)

    for counter, quantity, reason in [
        ("closureWorkOccurrenceDequeued", 1, "work.1.dequeue"),
        ("scopeOpened", 1, "work.1.scope"),
        ("contractHeaderRecognized", 2, "work.1.contracts"),
        ("channelCandidateTested", 1, "work.1.channel-test"),
        ("channelAccepted", 1, "work.1.channel-accept"),
        ("handlerCandidateTested", 1, "work.1.handler-test"),
        ("handlerCall", 1, "work.1.handler"),
        ("patchBoundaryChecked", 1, "work.1.patch-boundary"),
        ("patchRemove", 1, "work.1.remove-b-a"),
        ("tentativeComponentFinalization", 1, "work.1.finalize-b"),
    ]:
        processor(counter, quantity, reason, 1, "simple-b", 2)
    b_after = fixture["expected"]["finalDocuments"]["simple-b"]["document"]
    establish_exact_value(
        b_after, trace, established, existing, reason_prefix="work.1.acyclic-b",
        context={"documentId": "simple-b", "componentGeneration": 2, "workOccurrenceId": work[1]["workIdentity"]},
    )
    processor("checkpointWritten", 1, "work.1.checkpoint", 1, "simple-b", 2)
    fixture["expected"]["gasTrace"] = trace.entries
    fixture["expected"]["totalGas"] = trace.total
    fixture["expected"]["gasTraceIdentity"] = g.sha_id("blue-contracts-gas-trace/1.0", trace.entries)
    dump_fixture("c-clo-10-split-to-singletons.yaml", fixture)


def add_containing_spine_identity_fixture() -> None:
    stage = oracle_stage("finite-dynamic-a-b.yaml", "cycle-formed")
    member_ids = stage["memberBlueIdsInSourceOrder"]
    member_documents = stage["materializedDocuments"]
    a_initial, b_initial = deepcopy(member_documents[0]), deepcopy(member_documents[1])

    inner_initial = {
        "documentId": "container-inner",
        "memberIdentity": "container-inner",
        "child": g.ref(member_ids[0]),
        "contracts": {"embedded": {"type": g.ref(g.PE), "paths": ["/child"]}},
    }
    inner_initial_id = direct_blue_id(inner_initial)
    root_initial = {
        "documentId": "container-root",
        "memberIdentity": "container-root",
        "child": g.ref(inner_initial_id),
        "contracts": {"embedded": {"type": g.ref(g.PE), "paths": ["/child"]}},
    }
    root_initial_id = direct_blue_id(root_initial)

    event = g.event("CONTAINING-SPINE-SPLIT")
    input_value = {
        "graphGeneration": 1,
        "cause": g.external_cause(event, [242, "timeline-containing-spine", 1]),
        "documents": {
            "a": g.document_record("a", member_ids[0], a_initial, initialized=True, component_generation=1),
            "b": g.document_record("b", member_ids[1], b_initial, initialized=True, component_generation=1),
            "container-inner": g.document_record("container-inner", inner_initial_id, inner_initial, initialized=True, component_generation=1),
            "container-root": g.document_record("container-root", root_initial_id, root_initial, initialized=True, public_root=True, component_generation=1),
        },
        "occurrences": [
            g.occurrence("a", "/b", 1, "b", member_ids[1]),
            g.occurrence("b", "/a", 1, "a", member_ids[0]),
            g.occurrence("container-inner", "/child", 1, "a", member_ids[0]),
            g.occurrence("container-root", "/child", 1, "container-inner", inner_initial_id),
        ],
        "components": [
            g.component("CYCLIC", 1, ["a", "b"], master=stage["masterBlueId"], stage="cycle-formed"),
            g.component("ACYCLIC", 1, ["container-inner"]),
            g.component("ACYCLIC", 1, ["container-root"]),
        ],
        "directDeliveries": [g.direct_delivery("a")],
        "gasPolicy": g.policy(),
        "runtime": {"handlers": {"a/start": {"patches": [{"op": "remove", "path": "/b"}]}}},
    }

    a_final = deepcopy(a_initial); a_final.pop("b")
    a_final_id = direct_blue_id(a_final)
    b_final = deepcopy(b_initial); b_final["a"] = g.ref(a_final_id)
    b_final_id = direct_blue_id(b_final)
    inner_final = deepcopy(inner_initial); inner_final["child"] = g.ref(a_final_id)
    inner_final_id = direct_blue_id(inner_final)
    root_final = deepcopy(root_initial); root_final["child"] = g.ref(inner_final_id)
    root_final_id = direct_blue_id(root_final)
    work_trace = [
        g.work(0, "EXTERNAL_DELIVERY", "a", "source", event_id=direct_blue_id(event), occurrence_ordinal=0, invocation="containing-spine"),
    ]
    expected = g.empty_expected()
    expected.update({
        "finalGraphGeneration": 2,
        "workTrace": work_trace,
        "finalDocuments": {
            "a": g.document_record("a", a_final_id, a_final, initialized=True, epoch=1, component_generation=2),
            "b": g.document_record("b", b_final_id, b_final, initialized=True, epoch=1, component_generation=2),
            "container-inner": g.document_record("container-inner", inner_final_id, inner_final, initialized=True, epoch=1, component_generation=1),
            "container-root": g.document_record("container-root", root_final_id, root_final, initialized=True, public_root=True, epoch=1, component_generation=1),
        },
        "finalComponents": [
            g.component("ACYCLIC", 2, ["a"]),
            g.component("ACYCLIC", 2, ["b"]),
            g.component("ACYCLIC", 1, ["container-inner"]),
            g.component("ACYCLIC", 1, ["container-root"]),
        ],
        "finalOccurrences": [
            g.occurrence("b", "/a", 1, "a", a_final_id),
            g.occurrence("container-inner", "/child", 1, "a", a_final_id),
            g.occurrence("container-root", "/child", 1, "container-inner", inner_final_id),
        ],
        "publicEvents": [],
        "checkpointsCommitted": True,
        "rollbackToInput": False,
    })

    trace = GasReferenceTrace(100000)
    established: set[str] = set()
    existing = collect_existing_descendant_ids([a_initial, b_initial, inner_initial, root_initial])

    def processor(counter: str, quantity: int, reason: str, document_id: str | None = None, generation: int | None = None) -> None:
        context: dict[str, Any] = {}
        if document_id is not None:
            context.update({
                "documentId": document_id, "scopePath": "/", "activationGeneration": 0,
                "workOccurrenceId": work_trace[0]["workIdentity"],
            })
        if generation is not None:
            context["componentGeneration"] = generation
        trace.charge("processor", counter, quantity, reason=reason, context=context)

    for counter, quantity, reason in [
        ("processInvocation", 1, "admission.process"), ("closureInvocation", 1, "admission.closure"),
        ("deliverySnapshotEntry", 1, "admission.direct-delivery"), ("managedDocumentOpened", 4, "admission.documents"),
        ("managedOccurrenceBindingVerified", 4, "admission.bindings"), ("processEmbeddedEdgeExamined", 4, "admission.edges"),
        ("componentMemberPartitioned", 4, "admission.component-members"), ("componentEdgePartitioned", 4, "admission.component-edges"),
        ("closureWorkOccurrenceEnqueued", 1, "work.0.enqueue"),
    ]:
        processor(counter, quantity, reason)
    for counter, quantity, reason in [
        ("closureWorkOccurrenceDequeued", 1, "work.0.dequeue"), ("scopeOpened", 1, "work.0.scope"),
        ("contractHeaderRecognized", 2, "work.0.contracts"), ("channelCandidateTested", 1, "work.0.channel-test"),
        ("channelAccepted", 1, "work.0.channel-accept"), ("handlerCandidateTested", 1, "work.0.handler-test"),
        ("handlerCall", 1, "work.0.handler"), ("patchBoundaryChecked", 1, "work.0.patch-boundary"),
        ("patchRemove", 1, "work.0.remove-a-b"), ("processEmbeddedEdgeExamined", 3, "work.0.remaining-edges"),
        ("componentPartitionChanged", 1, "work.0.split-component"), ("componentMemberPartitioned", 4, "work.0.partition-members"),
        ("componentEdgePartitioned", 3, "work.0.partition-edges"),
    ]:
        processor(counter, quantity, reason, "a", 2)
    final_values = [
        ("a", 2, a_final, False),
        ("b", 2, b_final, True),
        ("container-inner", 1, inner_final, True),
        ("container-root", 1, root_final, True),
    ]
    for document_id, generation, value, reference_changed in final_values:
        processor("tentativeComponentFinalization", 1, f"work.0.finalize-{document_id}", document_id, generation)
        if reference_changed:
            processor("containingReferenceUpdated", 1, f"work.0.rewrite-{document_id}", document_id, generation)
        establish_exact_value(
            value, trace, established, existing, reason_prefix=f"work.0.acyclic-{document_id}",
            context={
                "documentId": document_id,
                "componentGeneration": generation,
                "workOccurrenceId": work_trace[0]["workIdentity"],
            },
        )
    processor("checkpointWritten", 1, "work.0.checkpoint", "a", 2)
    expected["gasTrace"] = trace.entries
    expected["totalGas"] = trace.total
    expected["gasTraceIdentity"] = g.sha_id("blue-contracts-gas-trace/1.0", trace.entries)
    fixture = g.base_fixture(
        "c-clo-28-containing-spine-identity-gas",
        ["C-CLO-28"],
        "dynamic-graph",
        "process-closure",
        "Removing A/b splits cyclic A/B. Exact acyclic A and B identities are established first, then a two-level containing spine is rebuilt child-to-root; unchanged exact descendants are carried and no patch value is double charged.",
        "finite-dynamic-a-b.yaml",
        input_value,
        expected,
    )
    dump_fixture("c-clo-28-containing-spine-identity-gas.yaml", fixture)


def write_limit_boundary_microfixtures() -> None:
    cases = [
        ("managed-documents", "managedDocumentsPerClosure", 4096, "ManagedDocumentsPerClosureExceeded", {}),
        ("embedded-edges", "processEmbeddedEdgesPerClosure", 16384, "ProcessEmbeddedEdgesPerClosureExceeded", {}),
        ("cyclic-members", "cyclicMembersPerComponent", 128, "CyclicComponentMemberLimitExceeded", {}),
        ("cyclic-edges", "cyclicEdgesPerComponent", 1024, "CyclicComponentEdgeLimitExceeded", {"members": 128}),
        ("cyclic-canonical-bytes", "cyclicCanonicalBytesPerComponent", 16777216, "CyclicComponentCanonicalBytesExceeded", {}),
        ("graph-changes", "closureGraphChangesPerInvocation", 4096, "ClosureGraphChangeLimitExceeded", {}),
        ("closure-expansions", "closureExpansionsPerInvocation", 4096, "ClosureExpansionLimitExceeded", {}),
        ("work-occurrences", "closureWorkOccurrencesPerInvocation", 8192, "ClosureWorkOccurrenceLimitExceeded", {}),
        ("tentative-finalizations", "closureTentativeFinalizationsPerInvocation", 8192, "ClosureTentativeFinalizationLimitExceeded", {}),
    ]
    for slug, limit_name, configured, diagnostic, extra_parameters in cases:
        for vector, side, observed, decision in (
            ("C-CLO-16", "at-bound", configured, "ACCEPT"),
            ("C-CLO-17", "above-bound", configured + 1, "REJECT"),
        ):
            parameters = {"count": observed, **extra_parameters}
            expected: dict[str, Any] = {
                "limitDecision": decision,
                "rejectedStepAdmitted": False,
                "subsequentOutcome": "NOT_EXECUTED_BY_MICROFIXTURE",
            }
            if decision == "REJECT":
                expected["diagnostic"] = diagnostic
                expected["rejectedStep"] = f"{limit_name}:{observed}"
            fixture = g.base_fixture(
                f"c-clo-{16 if decision == 'ACCEPT' else 17:02d}-{slug}-{side}",
                [vector],
                "limits",
                "limit-micro",
                (
                    f"The {limit_name} guard independently measures {observed} against the frozen "
                    f"bound {configured} and returns {decision}; this microfixture does not claim "
                    "that a later gas-expensive invocation phase completes."
                ),
                None,
                {
                    "limit": limit_name,
                    "configured": configured,
                    "observed": observed,
                    "generator": {"kind": slug, "parameters": parameters},
                },
                expected,
            )
            dump_fixture(f"{fixture['id']}.yaml", fixture)


def rewrite_outer_public_and_failure() -> None:
    stage = oracle_stage("finite-dynamic-a-b.yaml", "cycle-formed")
    ids = stage["memberBlueIdsInSourceOrder"]
    mats = stage["materializedDocuments"]
    outer = {
        "documentId": "outer", "memberIdentity": "outer", "inner": g.ref(ids[0]), "observed": 0,
        "contracts": {
            "embedded": {"type": g.ref(g.PE), "paths": ["/inner"]},
            "fromInner": {"type": g.ref(g.ENC), "sourcePath": "/inner", "event": g.ref(direct_blue_id(g.event("X"))), "order": 0},
            "observeInner": {"type": g.ref(g.SH), "channel": "fromInner", "order": 0},
        },
    }
    outer_id = direct_blue_id(outer)
    docs = {
        "a": g.document_record("a", ids[0], mats[0], initialized=True, epoch=0, component_generation=1),
        "b": g.document_record("b", ids[1], mats[1], initialized=True, epoch=0, component_generation=1),
        "outer": g.document_record("outer", outer_id, outer, initialized=True, public_root=True, epoch=0, component_generation=1),
    }
    occ = [
        g.occurrence("a", "/b", 1, "b", ids[1]),
        g.occurrence("b", "/a", 1, "a", ids[0]),
        g.occurrence("outer", "/inner", 1, "a", ids[0]),
    ]
    comps = [g.component("CYCLIC", 1, ["a", "b"], master=stage["masterBlueId"], stage="cycle-formed"), g.component("ACYCLIC", 1, ["outer"])]
    evt = g.event("OUTER-BOUNDARY")
    x = g.event("X"); xid = direct_blue_id(x)
    base_input = {
        "graphGeneration": 1, "cause": g.external_cause(evt, [260, "timeline-outer", 1]),
        "documents": docs, "occurrences": occ, "components": comps,
        "directDeliveries": [g.direct_delivery("a")], "gasPolicy": g.policy(),
        "runtime": {"handlers": {
            "a/start": {"events": [x]}, "a/onLocalX": {}, "b/onX": {},
            "outer/observeInner": {"patches": [{"op": "replace", "path": "/observed", "val": 1}]},
        }},
    }
    outer_after = deepcopy(outer); outer_after["observed"] = 1
    outer_after_id = direct_blue_id(outer_after)
    expected = g.empty_expected()
    expected.update({
        "finalGraphGeneration": 1,
        "workTrace": [
            g.work(0, "EXTERNAL_DELIVERY", "a", "source", event_id=direct_blue_id(evt), occurrence_ordinal=0, invocation="outer"),
            g.work(1, "TRIGGERED_EVENT", "a", "localX", event_id=xid, occurrence_ordinal=0, invocation="outer"),
            g.work(2, "EMBEDDED_EVENT", "b", "fromA", event_id=xid, occurrence_ordinal=0, invocation="outer"),
            g.work(3, "EMBEDDED_EVENT", "outer", "fromInner", event_id=xid, occurrence_ordinal=0, invocation="outer"),
        ],
        "finalDocuments": {
            "a": docs["a"], "b": docs["b"],
            "outer": g.document_record("outer", outer_after_id, outer_after, initialized=True, public_root=True, epoch=1, component_generation=1),
        },
        "finalComponents": comps,
        "finalOccurrences": [occ[0], occ[1], g.occurrence("outer", "/inner", 1, "a", ids[0])],
        "publicEvents": [], "checkpointsCommitted": True, "rollbackToInput": False,
    })
    dump_fixture("c-clo-19-public-event-boundary.yaml", g.base_fixture(
        "c-clo-19-public-event-boundary", ["C-CLO-19"], "public-events", "process-closure",
        "A and B react to X and the outer public Root observes it, but no internal member event appears in publicEvents because outer emits nothing explicitly.",
        "finite-dynamic-a-b.yaml", base_input, expected,
    ))

    fail_input = deepcopy(base_input)
    fail_input["runtime"]["handlers"]["outer/observeInner"] = {"fail": "outer-failure-after-inner-work"}
    fail_expected = g.empty_expected("runtime-fatal")
    fail_expected.update({
        "diagnostic": "RuntimeExecutionFailure", "finalGraphGeneration": 1,
        "finalDocuments": docs, "finalComponents": comps, "finalOccurrences": occ,
        "workTrace": expected["workTrace"], "rollbackToInput": True,
        "checkpointsCommitted": False, "publicEvents": [],
    })
    dump_fixture("c-clo-20-late-outer-failure.yaml", g.base_fixture(
        "c-clo-20-late-outer-failure", ["C-CLO-20"], "atomicity", "process-closure",
        "The outer public Root fails only after A local work and B embedded delivery were reached. The complete closure rolls back, including the inner component and all checkpoints.",
        "finite-dynamic-a-b.yaml", fail_input, fail_expected,
    ))



def rewrite_initialization_cycle() -> None:
    value = load("c-clo-08-cycle-during-initialization.yaml")
    lifecycle = "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo"
    for document_id in ("a", "b"):
        contracts = value["input"]["documents"][document_id]["document"].setdefault("contracts", {})
        contracts["initLifecycle"] = {"type": g.ref(lifecycle), "order": 0}
        contracts["onInit"] = {"type": g.ref(g.SH), "channel": "initLifecycle", "order": 0}

    a_document = value["input"]["documents"]["a"]["document"]
    a_blue_id = direct_blue_id(a_document)
    value["input"]["documents"]["a"]["blueId"] = a_blue_id

    b_document = value["input"]["documents"]["b"]["document"]
    b_document["a"] = g.ref(a_blue_id)
    b_blue_id = direct_blue_id(b_document)
    value["input"]["documents"]["b"]["blueId"] = b_blue_id

    def refresh_binding(binding: dict[str, Any]) -> None:
        binding["bindingIdentity"] = g.sha_id(
            "blue-contracts-managed-occurrence/1.0",
            {
                "sourceDocumentId": binding["sourceDocumentId"],
                "sourcePath": binding["sourcePath"],
                "activationGeneration": binding["activationGeneration"],
                "targetDocumentId": binding["targetDocumentId"],
                "expectedTargetBlueId": binding["expectedTargetBlueId"],
                "bindingPolicyIdentity": binding["bindingPolicyIdentity"],
            },
        )

    for binding in value["input"]["occurrences"]:
        if binding["targetDocumentId"] == "a":
            binding["expectedTargetBlueId"] = a_blue_id
            refresh_binding(binding)
    for binding in value["input"]["occurrences"]:
        if not binding["active"] and binding["targetDocumentId"] == "b":
            binding["expectedTargetBlueId"] = b_blue_id
            refresh_binding(binding)

    value["input"]["runtime"]["initializationHandlers"] = {
        "a/onInit": {"patches": [{"op": "add", "path": "/b", "val": g.ref(b_blue_id)}]},
        "b/onInit": {},
    }
    value["expected"]["workTrace"] = [
        g.work(0, "INITIALIZATION", "a", "initialization"),
        g.work(1, "LIFECYCLE", "a", "initLifecycle"),
        g.work(2, "INITIALIZATION", "b", "initialization"),
        g.work(3, "LIFECYCLE", "b", "initLifecycle"),
    ]

    source_a = deepcopy(a_document)
    source_a["b"] = g.ref("this#1")
    source_b = deepcopy(b_document)
    source_b["a"] = g.ref("this#0")
    oracle = cyclic_set_oracle([source_a, source_b])
    materialized = materialize_cyclic_members([source_a, source_b], oracle)
    member_ids = oracle.member_ids_in_source_order()

    value["oracle"] = "../../oracles/initialization-cycle.yaml"
    value["expected"]["tentativeFinalizations"] = [
        g.finalize(
            0,
            "cycle-during-initialization",
            oracle,
            ["a", "b"],
            boundary={"kind": "WORK", "afterWorkOrdinal": 0},
        ),
        g.finalize(
            1,
            "cycle-after-initialization-batch",
            oracle,
            ["a", "b"],
            boundary={"kind": "INITIALIZATION_BATCH", "afterWorkOrdinal": 3},
        ),
    ]
    for index, document_id in enumerate(("a", "b")):
        result = value["expected"]["finalDocuments"][document_id]
        result["blueId"] = member_ids[index]
        result["document"] = materialized[index]
    value["expected"]["finalComponents"][0]["masterBlueId"] = oracle.master_blue_id
    value["expected"]["finalComponents"][0]["oracleStage"] = "cycle-during-initialization"
    component_value = value["expected"]["finalComponents"][0]
    component_value["componentIdentity"] = g.sha_id(
        "blue-contracts-component/1.0",
        {
            "kind": component_value["kind"],
            "generation": component_value["componentGeneration"],
            "members": component_value["orderedMemberDocumentIds"],
        },
    )
    for binding in value["expected"]["finalOccurrences"]:
        binding["expectedTargetBlueId"] = member_ids[0 if binding["targetDocumentId"] == "a" else 1]
        refresh_binding(binding)

    oracle_value = g.oracle_payload(
        "initialization-cycle",
        [
            ("cycle-during-initialization", [source_a, source_b], oracle),
            ("cycle-after-initialization-batch", [source_a, source_b], oracle),
        ],
    )
    g.dump(ORC / "initialization-cycle.yaml", oracle_value)
    dump_fixture("c-clo-08-cycle-during-initialization.yaml", value)


def ensure_direct_delivery_work() -> None:
    """Make every frozen direct delivery an explicit queued work occurrence."""
    for fixture_path in sorted(FIX.glob("*.yaml")):
        fixture = yaml.safe_load(fixture_path.read_text())
        if fixture["operation"] == "limit-micro":
            continue
        expected = fixture["expected"]
        if (
            expected.get("attemptOutcome") != "Complete"
            or expected.get("status") != "success"
        ):
            continue
        work_trace = expected.get("workTrace", [])
        represented = {
            (item["targetDocumentId"], item["channelKey"])
            for item in work_trace
            if item["kind"] == "EXTERNAL_DELIVERY"
        }
        missing = [
            delivery
            for delivery in fixture["input"].get("directDeliveries", [])
            if (delivery["targetDocumentId"], delivery["channelKey"])
            not in represented
        ]
        if not missing:
            continue
        missing.sort(
            key=lambda item: (
                item["rawOccurrenceOrder"],
                item["targetDocumentId"],
                item["scopePath"],
                item["channelKey"],
            )
        )
        shift = len(missing)
        for item in work_trace:
            item["ordinal"] += shift
        for finalization in expected.get("tentativeFinalizations", []):
            boundary = finalization.get("boundary")
            if isinstance(boundary, dict) and boundary.get("kind") == "WORK":
                boundary["afterWorkOrdinal"] += shift
        prefix = [
            g.work(
                ordinal,
                "EXTERNAL_DELIVERY",
                delivery["targetDocumentId"],
                delivery["channelKey"],
                event_id=fixture["input"]["cause"].get("eventBlueId"),
                occurrence_ordinal=delivery["rawOccurrenceOrder"],
                invocation=fixture["id"],
            )
            for ordinal, delivery in enumerate(missing)
        ]
        expected["workTrace"] = prefix + work_trace
        dump_fixture(fixture_path.name, fixture)


def order_initialization_batch_work() -> None:
    """Run each member's initialization immediately before its lifecycle work."""
    for fixture_path in sorted(FIX.glob("*.yaml")):
        fixture = yaml.safe_load(fixture_path.read_text())
        if fixture.get("operation") != "admit-closure":
            continue
        expected = fixture["expected"]
        if (
            expected.get("attemptOutcome") != "Complete"
            or expected.get("status") != "success"
        ):
            continue
        work_trace = expected.get("workTrace", [])
        ordered = sorted(
            work_trace,
            key=lambda item: (
                item["targetDocumentId"],
                0 if item["kind"] == "INITIALIZATION" else 1,
                item["ordinal"],
            ),
        )
        for ordinal, item in enumerate(ordered):
            item["ordinal"] = ordinal
        expected["workTrace"] = ordered
        dump_fixture(fixture_path.name, fixture)


def normalize_event_occurrence_ordinals() -> None:
    """Allocate one invocation-global ordinal per emitted event occurrence."""
    for fixture_path in sorted(FIX.glob("*.yaml")):
        fixture = yaml.safe_load(fixture_path.read_text())
        if fixture["operation"] == "limit-micro":
            continue
        expected = fixture["expected"]
        if expected.get("attemptOutcome") != "Complete":
            continue
        work_trace = expected.get("workTrace", [])
        groups: dict[tuple[str, int], list[dict[str, Any]]] = {}
        first_ordinal: dict[tuple[str, int], int] = {}
        for item in work_trace:
            if item["kind"] not in {"TRIGGERED_EVENT", "EMBEDDED_EVENT"}:
                continue
            key = (item["eventBlueId"], item.get("occurrenceOrdinal", 0))
            groups.setdefault(key, []).append(item)
            first_ordinal.setdefault(key, item["ordinal"])
        available: dict[str, list[tuple[str, int]]] = {}
        for key in groups:
            available.setdefault(key[0], []).append(key)
        for values in available.values():
            values.sort(key=lambda key: first_ordinal[key])

        emission_ordinal = 0
        used: set[tuple[str, int]] = set()
        for work_item in work_trace:
            _handler_key, handler_result = handler_result_for_work(fixture, work_item)
            if handler_result is None:
                continue
            for event_value in handler_result.get("events", []):
                event_blue_id = direct_blue_id(event_value)
                candidates = [
                    key for key in available.get(event_blue_id, []) if key not in used
                ]
                if candidates:
                    key = candidates[0]
                    used.add(key)
                    for delivery in groups[key]:
                        delivery["occurrenceOrdinal"] = emission_ordinal
                emission_ordinal += 1
        if set(groups) != used:
            missing = sorted(set(groups) - used, key=lambda key: first_ordinal[key])
            raise AssertionError(
                f"event deliveries have no emitted occurrence in {fixture_path.name}: {missing}"
            )
        dump_fixture(fixture_path.name, fixture)


def normalize_finalization_boundaries() -> None:
    """Replace the draft's positional finalization inference with evidence."""
    for fixture_path in sorted(FIX.glob("*.yaml")):
        fixture = yaml.safe_load(fixture_path.read_text())
        if fixture["operation"] == "limit-micro":
            continue
        expected = fixture["expected"]
        finalizations = expected.get("tentativeFinalizations", [])
        work_trace = expected.get("workTrace", [])
        if not finalizations:
            continue
        if fixture["operation"] == "admit-closure":
            if not work_trace:
                raise AssertionError(
                    f"initialization finalization has no causal work in {fixture_path.name}"
                )
            after_work_ordinal = max(item["ordinal"] for item in work_trace)
            for finalization in finalizations:
                if finalization.get("boundary", {}).get("kind") == "WORK":
                    continue
                finalization["boundary"] = {
                    "kind": "INITIALIZATION_BATCH",
                    "afterWorkOrdinal": after_work_ordinal,
                }
        elif len(finalizations) == 1 and work_trace:
            finalizations[0]["boundary"] = {
                "kind": "WORK",
                "afterWorkOrdinal": work_trace[-1]["ordinal"],
            }
        elif len(finalizations) == len(work_trace):
            for finalization, work_item in zip(finalizations, work_trace):
                finalization["boundary"] = {
                    "kind": "WORK",
                    "afterWorkOrdinal": work_item["ordinal"],
                }
        dump_fixture(fixture_path.name, fixture)


def _decode_pointer(path: str) -> list[str]:
    if not isinstance(path, str) or not path.startswith("/"):
        raise ValueError(f"expected an absolute JSON pointer, got {path!r}")
    if path == "/":
        return [""]
    return [part.replace("~1", "/").replace("~0", "~") for part in path[1:].split("/")]


def _write_pointer(document: Any, path: str, value: Any) -> None:
    current = document
    parts = _decode_pointer(path)
    for part in parts[:-1]:
        current = current[int(part)] if isinstance(current, list) else current[part]
    leaf = parts[-1]
    if isinstance(current, list):
        current[int(leaf)] = deepcopy(value)
    else:
        current[leaf] = deepcopy(value)


def _replace_exact_references(value: Any, replacements: dict[str, str]) -> Any:
    if isinstance(value, list):
        return [_replace_exact_references(child, replacements) for child in value]
    if isinstance(value, dict):
        if set(value) == {"blueId"} and value["blueId"] in replacements:
            return g.ref(replacements[value["blueId"]])
        return {
            key: _replace_exact_references(child, replacements)
            for key, child in value.items()
        }
    if isinstance(value, str):
        return replacements.get(value, value)
    return value


def _refresh_occurrence_binding(binding: dict[str, Any], target_blue_id: str) -> None:
    binding["expectedTargetBlueId"] = target_blue_id
    binding["bindingIdentity"] = g.sha_id(
        "blue-contracts-managed-occurrence/1.0",
        {
            "sourceDocumentId": binding["sourceDocumentId"],
            "sourcePath": binding["sourcePath"],
            "activationGeneration": binding["activationGeneration"],
            "targetDocumentId": binding["targetDocumentId"],
            "expectedTargetBlueId": target_blue_id,
            "bindingPolicyIdentity": binding["bindingPolicyIdentity"],
        },
    )


def _component_sources(
    documents: dict[str, Any],
    component: dict[str, Any],
    occurrences: list[dict[str, Any]],
) -> tuple[list[str], list[Any]]:
    members = sorted(component["orderedMemberDocumentIds"])
    member_index = {document_id: index for index, document_id in enumerate(members)}
    sources = [deepcopy(documents[document_id]["document"]) for document_id in members]
    by_document = {document_id: sources[index] for index, document_id in enumerate(members)}
    for binding in occurrences:
        if not binding.get("active") or binding["sourceDocumentId"] not in member_index:
            continue
        target = binding["targetDocumentId"]
        target_ref = (
            g.ref(f"this#{member_index[target]}")
            if target in member_index
            else g.ref(documents[target]["blueId"])
        )
        _write_pointer(
            by_document[binding["sourceDocumentId"]],
            binding["sourcePath"],
            target_ref,
        )
    return members, sources


def _recompute_snapshot(
    documents: dict[str, Any],
    components: list[dict[str, Any]],
    occurrences: list[dict[str, Any]],
    *,
    stage_prefix: str,
) -> list[tuple[str, list[Any], Any]]:
    """Rebuild exact component identities child-to-root.

    Active managed pointers are the only live references rewritten.  Historic
    nodes retained inside processor markers therefore remain exact snapshots
    instead of being silently retargeted during later component churn.
    """
    owner: dict[str, int] = {}
    for index, component in enumerate(components):
        component["orderedMemberDocumentIds"] = sorted(
            component["orderedMemberDocumentIds"]
        )
        for document_id in component["orderedMemberDocumentIds"]:
            if document_id in owner:
                raise AssertionError(f"duplicate component member {document_id!r}")
            owner[document_id] = index
    dependencies: dict[int, set[int]] = {index: set() for index in range(len(components))}
    for binding in occurrences:
        if not binding.get("active"):
            continue
        source_index = owner[binding["sourceDocumentId"]]
        target_index = owner[binding["targetDocumentId"]]
        if source_index != target_index:
            dependencies[source_index].add(target_index)

    stages: list[tuple[str, list[Any], Any]] = []
    completed: set[int] = set()
    active: set[int] = set()

    def rebuild(index: int) -> None:
        if index in completed:
            return
        if index in active:
            raise AssertionError("component condensation graph is cyclic")
        active.add(index)
        for target in sorted(dependencies[index]):
            rebuild(target)
        component = components[index]
        members, sources = _component_sources(documents, component, occurrences)
        stage_name = f"{stage_prefix}-{index}"
        if component["kind"] == "CYCLIC":
            oracle = cyclic_set_oracle(sources)
            materialized = materialize_cyclic_members(sources, oracle)
            member_ids = oracle.member_ids_in_source_order()
            for member_index, document_id in enumerate(members):
                documents[document_id]["document"] = materialized[member_index]
                documents[document_id]["blueId"] = member_ids[member_index]
            component["masterBlueId"] = oracle.master_blue_id
            component["oracleStage"] = stage_name
            stages.append((stage_name, sources, oracle))
        else:
            if len(members) != 1:
                raise AssertionError("an acyclic component must contain one document")
            document_id = members[0]
            documents[document_id]["document"] = sources[0]
            documents[document_id]["blueId"] = direct_blue_id(sources[0])
            component.pop("masterBlueId", None)
            component.pop("oracleStage", None)
        active.remove(index)
        completed.add(index)

    for index in range(len(components)):
        rebuild(index)
    for binding in occurrences:
        _refresh_occurrence_binding(
            binding, documents[binding["targetDocumentId"]]["blueId"]
        )
    return stages


def _add_initialized_marker(document: Any, pre_initialization_document: Any) -> Any:
    value = deepcopy(document)
    contracts = value.setdefault("contracts", {})
    contracts.setdefault(
        "initialized", g.initialized_marker(pre_initialization_document)
    )
    return value


def _add_checkpoint_entry(
    document: Any,
    channel_key: str,
    domain_blue_id: str,
    event_blue_id: str,
) -> Any:
    value = deepcopy(document)
    contracts = value.setdefault("contracts", {})
    marker = contracts.setdefault(
        "checkpoint", {"type": g.ref(g.CHECKPOINT_MARKER), "entries": {}}
    )
    if marker.get("type") != g.ref(g.CHECKPOINT_MARKER):
        raise AssertionError("contracts/checkpoint is not the exact checkpoint marker")
    marker.setdefault("entries", {})[channel_key] = {
        "domain": g.ref(domain_blue_id),
        "subject": g.ref(event_blue_id),
    }
    return value


def _checkpoint_domain_value_and_id(
    domain: Any,
    *,
    current_channel: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], str]:
    """Return independently checkable checkpoint-domain evidence.

    An inline exact value is self-describing.  A pure reference is resolvable
    here only when it is the exact current default Channel domain; any other
    historical pure reference would require separately supplied exact-node
    evidence and is deliberately rejected by this closed fixture generator.
    """
    if not isinstance(domain, dict):
        raise AssertionError("checkpoint domain must be an exact Blue value")
    if set(domain) == {"blueId"}:
        blue_id = domain["blueId"]
        if not isinstance(current_channel, dict):
            raise AssertionError("historical checkpoint domain reference lacks evidence")
        value = g.checkpoint_domain_value(current_channel)
        if direct_blue_id(value) != blue_id:
            raise AssertionError("checkpoint domain reference does not match current evidence")
        return value, blue_id
    value = deepcopy(domain)
    return value, direct_blue_id(value)


def add_checkpoint_retirement_coverage_fixture() -> None:
    """Add one exact replacement plus orphan-cleanup settlement vector."""
    fixture = deepcopy(load("c-clo-30-language-cyclic-oracles.yaml"))
    fixture["id"] = "c-clo-33-checkpoint-domain-retirement"
    fixture["vectors"] = ["C-CLO-33"]
    fixture["description"] = (
        "A structurally valid but retired source checkpoint domain is virtual "
        "empty for direct admission and is replaced at settlement; an orphan "
        "raw-key entry is removed in the same exact non-notifying batch."
    )
    source_document = fixture["input"]["documents"]["a"]["document"]
    source_channel = source_document["contracts"]["source"]
    current_domain = g.checkpoint_domain_value(source_channel)
    stale_domain = deepcopy(current_domain)
    stale_domain["runtimeDiscriminator"] = "retired-source-domain-v0"
    orphan_domain = deepcopy(current_domain)
    orphan_domain["runtimeDiscriminator"] = "retired-orphan-domain-v0"
    previous_subject = direct_blue_id(g.event("PREVIOUS-CHECKPOINT-SUBJECT"))
    source_document["contracts"]["checkpoint"] = {
        "type": g.ref(g.CHECKPOINT_MARKER),
        "entries": {
            "source": {
                "domain": stale_domain,
                "subject": g.ref(previous_subject),
            },
            "orphan": {
                "domain": orphan_domain,
                "subject": g.ref(previous_subject),
            },
        },
    }
    dump_fixture("c-clo-33-checkpoint-domain-retirement.yaml", fixture)


def _select_stage(
    candidates: list[dict[str, Any]],
    name: str,
    document_ids: set[str],
) -> dict[str, Any]:
    matches = []
    for stage in candidates:
        ids = {
            document.get("documentId")
            for document in stage["sourceDocumentsWithThisReferences"]
            if isinstance(document, dict)
        }
        if stage["name"] == name and ids == document_ids:
            matches.append(stage)
    if len(matches) != 1:
        raise AssertionError(
            f"cannot select oracle stage {name!r} for {sorted(document_ids)!r}: "
            f"{len(matches)} matches"
        )
    return matches[0]


def _transform_transition_evidence(
    fixture: dict[str, Any],
    old_to_new_input_ids: dict[str, str],
) -> None:
    transitions = fixture["input"].get("historicalTransitions", [])
    if not transitions:
        return
    marker_by_document = {
        document_id: record["document"].get("contracts", {}).get("initialized")
        for document_id, record in fixture["input"]["documents"].items()
    }
    previous_after: dict[str, str] = {}
    first_before: dict[str, str] = {}
    reference = fixture.get("oracle")
    oracle_value = (
        yaml.safe_load((FIX / reference).resolve().read_text())
        if isinstance(reference, str)
        else {}
    )
    for epoch_value in oracle_value.get("aEpochs", {}).values():
        old_id = epoch_value.get("blueId")
        document = epoch_value.get("document")
        if not isinstance(old_id, str) or not isinstance(document, dict):
            continue
        document_id = document.get("documentId")
        marker = marker_by_document.get(document_id)
        transformed = _replace_exact_references(document, old_to_new_input_ids)
        if marker is not None:
            transformed = deepcopy(transformed)
            transformed.setdefault("contracts", {})["initialized"] = deepcopy(marker)
        first_before[old_id] = direct_blue_id(transformed)
    for transition in transitions:
        document_id = transition["documentId"]
        after_document = _replace_exact_references(
            transition["afterDocument"], old_to_new_input_ids
        )
        marker = marker_by_document.get(document_id)
        if marker is not None:
            after_document.setdefault("contracts", {})["initialized"] = deepcopy(marker)
        before_blue_id = previous_after.get(
            document_id, first_before.get(transition["beforeBlueId"])
        )
        if before_blue_id is None:
            raise AssertionError("historical transition lacks its exact first predecessor")
        after_blue_id = direct_blue_id(after_document)
        transition["beforeBlueId"] = before_blue_id
        transition["afterBlueId"] = after_blue_id
        transition["afterDocument"] = after_document
        transition["transitionIdentity"] = g.sha_id(
            "blue-contracts-historical-transition/1.0",
            {
                "documentId": document_id,
                "fromEpoch": transition["fromEpoch"],
                "toEpoch": transition["toEpoch"],
                "beforeBlueId": before_blue_id,
                "afterBlueId": after_blue_id,
            },
        )
        previous_after[document_id] = after_blue_id
    first_transition = transitions[0]
    for binding in fixture["input"].get("occurrences", []):
        if binding.get("pendingHistoricalEpoch") is None:
            continue
        target_blue_id = (
            first_transition["beforeBlueId"]
            if binding.get("pendingHistoricalEpoch") == first_transition["fromEpoch"]
            else fixture["input"]["documents"][binding["targetDocumentId"]]["blueId"]
        )
        _refresh_occurrence_binding(binding, target_blue_id)


def materialize_processor_state() -> None:
    """Make marker and checkpoint state part of every exact closure document.

    Earlier draft fixtures treated initialization/checkpoint state as side
    booleans.  This pass deliberately starts from those seed scenarios and
    produces the normative, self-contained exact Blue snapshots and cyclic
    oracles consumed by the validator and implementation harness.
    """
    for fixture_path in sorted(FIX.glob("*.yaml")):
        fixture = yaml.safe_load(fixture_path.read_text())
        if fixture["operation"] == "limit-micro":
            continue
        fixture_input = fixture["input"]
        old_expected = fixture["expected"]
        old_input_documents = deepcopy(fixture_input["documents"])
        old_input_ids = {
            document_id: record["blueId"]
            for document_id, record in old_input_documents.items()
        }

        old_candidates: list[dict[str, Any]] = []
        reference = fixture.get("oracle")
        if isinstance(reference, str):
            old_candidates = oracle_stage_candidates(
                yaml.safe_load((fixture_path.parent / reference).resolve().read_text())
            )

        for document_id, record in fixture_input["documents"].items():
            if record.get("initialized"):
                record["document"] = _add_initialized_marker(
                    record["document"], old_input_documents[document_id]["document"]
                )
            if record.get("terminated"):
                record["document"].setdefault("contracts", {})["terminated"] = {
                    "type": g.ref(g.TERMINATED_MARKER)
                }

        new_oracle_stages = _recompute_snapshot(
            fixture_input["documents"],
            fixture_input["components"],
            fixture_input.get("occurrences", []),
            stage_prefix="input",
        )
        input_masters = {
            tuple(component["orderedMemberDocumentIds"]): component.get("masterBlueId")
            for component in fixture_input["components"]
        }
        old_to_new_input_ids = {
            old_input_ids[document_id]: fixture_input["documents"][document_id]["blueId"]
            for document_id in old_input_ids
        }
        _transform_transition_evidence(fixture, old_to_new_input_ids)
        if "requestedProviderNodes" in fixture_input:
            fixture_input["requestedProviderNodes"] = [
                old_to_new_input_ids.get(value, value)
                for value in fixture_input["requestedProviderNodes"]
            ]

        if old_expected.get("attemptOutcome") == "NeedsResources":
            per_fixture_oracle = g.oracle_payload(
                fixture["id"], new_oracle_stages
            ) if new_oracle_stages else None
            if per_fixture_oracle is not None:
                oracle_name = f"{fixture['id']}.yaml"
                g.dump(ORC / oracle_name, per_fixture_oracle)
                fixture["oracle"] = f"../../oracles/{oracle_name}"
            dump_fixture(fixture_path.name, fixture)
            continue

        if old_expected.get("status") != "success":
            if new_oracle_stages:
                oracle_name = f"{fixture['id']}.yaml"
                g.dump(
                    ORC / oracle_name,
                    g.oracle_payload(fixture["id"], new_oracle_stages),
                )
                fixture["oracle"] = f"../../oracles/{oracle_name}"
            dump_fixture(fixture_path.name, fixture)
            continue

        final_documents = old_expected["finalDocuments"]
        final_components = old_expected["finalComponents"]
        final_occurrences = old_expected["finalOccurrences"]
        marker_by_document: dict[str, Any] = {}
        new_marker_documents: set[str] = set()
        checkpoint_by_document: dict[str, Any] = {}
        for document_id, record in final_documents.items():
            input_record = fixture_input["documents"][document_id]
            input_marker = input_record["document"].get("contracts", {}).get("initialized")
            input_checkpoint = input_record["document"].get("contracts", {}).get("checkpoint")
            if record.get("initialized"):
                marker = (
                    deepcopy(input_marker)
                    if input_marker is not None
                    else g.initialized_marker(old_input_documents[document_id]["document"])
                )
                marker_by_document[document_id] = marker
                if input_marker is None:
                    new_marker_documents.add(document_id)
                record["document"] = deepcopy(record["document"])
                record["document"].setdefault("contracts", {})["initialized"] = deepcopy(marker)
            if input_checkpoint is not None:
                checkpoint_by_document[document_id] = deepcopy(input_checkpoint)
                record["document"] = deepcopy(record["document"])
                record["document"].setdefault("contracts", {})["checkpoint"] = deepcopy(
                    input_checkpoint
                )
            if record.get("terminated"):
                record["document"].setdefault("contracts", {})["terminated"] = {
                    "type": g.ref(g.TERMINATED_MARKER)
                }

        transformed_finalizations: list[dict[str, Any]] = []
        member_identity_replacements: dict[str, str] = {}
        for finalization in old_expected.get("tentativeFinalizations", []):
            document_ids = set(finalization["memberBlueIds"])
            old_stage = _select_stage(
                old_candidates, finalization["oracleStage"], document_ids
            )
            transformed_sources: list[Any] = []
            boundary_kind = finalization.get("boundary", {}).get("kind")
            for source in old_stage["sourceDocumentsWithThisReferences"]:
                document_id = source["documentId"]
                transformed = _replace_exact_references(
                    source, old_to_new_input_ids
                )
                marker = marker_by_document.get(document_id)
                if marker is not None and (
                    document_id not in new_marker_documents
                    or boundary_kind != "WORK"
                ):
                    transformed.setdefault("contracts", {})["initialized"] = deepcopy(marker)
                checkpoint = checkpoint_by_document.get(document_id)
                if checkpoint is not None:
                    transformed.setdefault("contracts", {})["checkpoint"] = deepcopy(
                        checkpoint
                    )
                transformed_sources.append(transformed)
            oracle = cyclic_set_oracle(transformed_sources)
            stage_name = f"transition-{finalization['ordinal']}-{finalization['oracleStage']}"
            transformed = g.finalize(
                finalization["ordinal"],
                stage_name,
                oracle,
                [source["documentId"] for source in transformed_sources],
                boundary=deepcopy(finalization.get("boundary")),
            )
            transformed_finalizations.append(transformed)
            new_oracle_stages.append((stage_name, transformed_sources, oracle))
            for document_id, old_blue_id in finalization["memberBlueIds"].items():
                member_identity_replacements[old_blue_id] = transformed["memberBlueIds"][document_id]

        old_expected["tentativeFinalizations"] = transformed_finalizations
        if "observations" in old_expected:
            old_expected["observations"] = _replace_exact_references(
                old_expected["observations"], member_identity_replacements
            )

        pre_checkpoint_stages = _recompute_snapshot(
            final_documents,
            final_components,
            final_occurrences,
            stage_prefix="result",
        )
        new_oracle_stages.extend(pre_checkpoint_stages)

        # A successful external cause persists checkpoints only after its
        # complete event/work queue drains.  All direct writes are one atomic
        # settlement batch before publication.
        checkpoint_targets: set[str] = set()
        event_blue_id = fixture_input.get("cause", {}).get("eventBlueId")
        if isinstance(event_blue_id, str):
            for work_item in old_expected.get("workTrace", []):
                if work_item["kind"] != "EXTERNAL_DELIVERY":
                    continue
                document_id = work_item["targetDocumentId"]
                input_document = fixture_input["documents"][document_id]["document"]
                channel = input_document.get("contracts", {}).get(work_item["channelKey"])
                if not isinstance(channel, dict):
                    raise AssertionError(
                        f"external work lacks channel {document_id}/{work_item['channelKey']}"
                    )
                domain_blue_id = g.checkpoint_domain_blue_id(channel)
                final_documents[document_id]["document"] = _add_checkpoint_entry(
                    final_documents[document_id]["document"],
                    work_item["channelKey"],
                    domain_blue_id,
                    event_blue_id,
                )
                checkpoint_targets.add(document_id)

        # Retire entries that no longer denote a final effective Channel
        # lineage.  Accepted-source replacement happened first, so a stale
        # source entry becomes one before->after receipt rather than a remove
        # followed by an add.  Remaining cleanup is DocumentId/raw-key order.
        for document_id in sorted(final_documents):
            document = final_documents[document_id]["document"]
            contracts = document.get("contracts", {}) if isinstance(document, dict) else {}
            checkpoint = contracts.get("checkpoint") if isinstance(contracts, dict) else None
            if not isinstance(checkpoint, dict):
                continue
            entries = checkpoint.get("entries", {})
            if not isinstance(entries, dict):
                raise AssertionError("checkpoint entries must be an object")
            removed = False
            for raw_key in sorted(list(entries)):
                channel = contracts.get(raw_key)
                if not isinstance(channel, dict):
                    entries.pop(raw_key)
                    removed = True
                    continue
                current_domain_id = g.checkpoint_domain_blue_id(channel)
                _stored_value, stored_domain_id = _checkpoint_domain_value_and_id(
                    entries[raw_key].get("domain"), current_channel=channel
                )
                if stored_domain_id != current_domain_id:
                    entries.pop(raw_key)
                    removed = True
            if removed:
                checkpoint_targets.add(document_id)
            if not entries:
                contracts.pop("checkpoint", None)

        before_checkpoint_masters = {
            tuple(component["orderedMemberDocumentIds"]): component.get("masterBlueId")
            for component in final_components
        }
        checkpoint_stages: list[tuple[str, list[Any], Any]] = []
        if checkpoint_targets:
            checkpoint_stages = _recompute_snapshot(
                final_documents,
                final_components,
                final_occurrences,
                stage_prefix="checkpoint-settlement",
            )
            new_oracle_stages.extend(checkpoint_stages)
            stage_by_members = {
                tuple(source["documentId"] for source in sources): (name, sources, oracle)
                for name, sources, oracle in checkpoint_stages
            }
            for component in final_components:
                members = tuple(component["orderedMemberDocumentIds"])
                if component["kind"] != "CYCLIC":
                    continue
                if component.get("masterBlueId") == before_checkpoint_masters.get(members):
                    continue
                name, sources, oracle = stage_by_members[members]
                old_expected["tentativeFinalizations"].append(
                    g.finalize(
                        len(old_expected["tentativeFinalizations"]),
                        name,
                        oracle,
                        [source["documentId"] for source in sources],
                        boundary={"kind": "CHECKPOINT_SETTLEMENT"},
                    )
                )

        # Ensure every final cyclic state has a matching transition receipt;
        # initialization batches deliberately finalize only after all member
        # markers have been installed.
        finalization_masters = {
            item["masterBlueId"] for item in old_expected["tentativeFinalizations"]
        }
        all_result_stages = pre_checkpoint_stages + checkpoint_stages
        for component in final_components:
            if component["kind"] != "CYCLIC" or component["masterBlueId"] in finalization_masters:
                continue
            members_key = tuple(component["orderedMemberDocumentIds"])
            if component["masterBlueId"] == input_masters.get(members_key):
                continue
            matches = [
                (name, sources, oracle)
                for name, sources, oracle in all_result_stages
                if {source["documentId"] for source in sources}
                == set(component["orderedMemberDocumentIds"])
                and oracle.master_blue_id == component["masterBlueId"]
            ]
            if len(matches) != 1:
                raise AssertionError("final cyclic state lacks one exact oracle stage")
            name, sources, oracle = matches[0]
            is_initialization = fixture["operation"] == "admit-closure"
            work_items = old_expected.get("workTrace", [])
            if not is_initialization and not work_items:
                raise AssertionError(
                    "a changed final cyclic state has neither initialization nor work"
                )
            boundary = (
                {
                    "kind": "INITIALIZATION_BATCH",
                    "afterWorkOrdinal": max(
                        item["ordinal"] for item in work_items
                    ),
                }
                if is_initialization
                else {
                    "kind": "WORK",
                    "afterWorkOrdinal": max(item["ordinal"] for item in work_items),
                }
            )
            old_expected["tentativeFinalizations"].append(
                g.finalize(
                    len(old_expected["tentativeFinalizations"]),
                    name,
                    oracle,
                    [source["documentId"] for source in sources],
                    boundary=boundary,
                )
            )

        if fixture["operation"] == "admit-closure":
            work_items = old_expected.get("workTrace", [])
            if old_expected["tentativeFinalizations"] and not work_items:
                raise AssertionError(
                    "initialization finalization has no accepted causal work"
                )
            after_work_ordinal = max(
                (item["ordinal"] for item in work_items), default=0
            )
            for finalization in old_expected["tentativeFinalizations"]:
                if finalization.get("boundary", {}).get("kind") == "WORK":
                    continue
                finalization["boundary"] = {
                    "kind": "INITIALIZATION_BATCH",
                    "afterWorkOrdinal": after_work_ordinal,
                }

        old_expected["finalDocuments"] = final_documents
        old_expected["finalComponents"] = final_components
        old_expected["finalOccurrences"] = final_occurrences
        if new_oracle_stages:
            oracle_name = f"{fixture['id']}.yaml"
            g.dump(
                ORC / oracle_name,
                g.oracle_payload(fixture["id"], new_oracle_stages),
            )
            fixture["oracle"] = f"../../oracles/{oracle_name}"
        dump_fixture(fixture_path.name, fixture)


def content_sha256_identity(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def fixture_environment(release: dict[str, Any]) -> dict[str, Any]:
    gas_manifest = yaml.safe_load(
        (ROOT / "conformance/contracts/gas-manifest.yaml").read_text()
    )
    managed_label = "nfc-document-lineage-v1"
    binding_label = "exact-document-lineage"
    provider_label = "fixture-exact-node-provider-v1"
    external_order_label = "canonical-source-order-v1"
    limit_label = "blue-contracts-1.0-portable-limits"
    limits = [
        {"name": name, "value": value}
        for name, value in sorted(gas_manifest["portableLimits"].items())
    ]
    return {
        "blueLanguageSpecificationIdentity": content_sha256_identity(
            ROOT / "reference/blue-language-specification-1.0.md"
        ),
        "contractsSpecificationIdentity": content_sha256_identity(
            ROOT / "specifications/blue-contracts-and-processor-specification-1.0.md"
        ),
        "runtimeRegistryIdentity": release["contractsRegistry"]["packageIdentity"],
        "gasManifestIdentity": content_sha256_identity(
            ROOT / "conformance/contracts/gas-manifest.yaml"
        ),
        "managedDocumentIdentityPolicy": {
            "identity": g.sha_id(
                "blue-contracts-managed-document-identity-policy/1.0",
                {"label": managed_label},
            ),
            "label": managed_label,
        },
        "managedBindingPolicy": {
            "identity": g.sha_id(
                "blue-contracts-managed-binding-policy/1.0",
                {"label": binding_label},
            ),
            "label": binding_label,
        },
        "exactNodeProviderDomain": {
            "identity": g.sha_id(
                "blue-contracts-exact-node-provider-domain/1.0",
                {"label": provider_label},
            ),
            "label": provider_label,
        },
        "externalOrderPolicy": {
            "identity": g.sha_id(
                "blue-contracts-external-order-policy/1.0",
                {"label": external_order_label},
            ),
            "label": external_order_label,
        },
        "portableLimitPolicy": {
            "identity": g.sha_id(
                "blue-contracts-portable-limit-policy/1.0",
                {"label": limit_label, "limits": limits},
            ),
            "label": limit_label,
            "limits": limits,
        },
        "cyclicFinalizerIdentity": release["languageDependency"][
            "cyclicSetFinalizerBaselineIdentity"
        ],
        "cyclicProofVerifierIdentity": release["languageDependency"][
            "cyclicSetProofVerifierBaselineIdentity"
        ],
    }


def decorate_component(
    fixture_path: Path,
    fixture: dict[str, Any],
    component: dict[str, Any],
    documents: dict[str, Any],
) -> None:
    members = sorted(component["orderedMemberDocumentIds"])
    component["orderedMemberDocumentIds"] = members
    component_identity = g.sha_id(
        "blue-contracts-component/1.0",
        {
            "kind": component["kind"],
            "generation": component["componentGeneration"],
            "members": members,
        },
    )
    component["componentIdentity"] = component_identity
    member_states = [
        {"documentId": document_id, "blueId": documents[document_id]["blueId"]}
        for document_id in members
    ]
    component["orderedMemberBlueIds"] = [
        item["blueId"] for item in member_states
    ]
    master_blue_id: str | None = None
    proof_identity: str | None = None
    if component["kind"] == "CYCLIC":
        stage = component_oracle_stage(fixture_path, fixture, component)
        master_blue_id = stage["masterBlueId"]
        if component.get("masterBlueId") != master_blue_id:
            raise AssertionError(
                f"component/oracle master mismatch in {fixture_path.name}: {members}"
            )
        proof_value = {
            "componentIdentity": component_identity,
            "masterBlueId": master_blue_id,
            "memberStates": member_states,
            "declaredPlaceholderSet": stage["canonicalLimitForm"],
        }
        proof_identity = g.sha_id(
            "blue-contracts-cyclic-proof-evidence/1.0", proof_value
        )
        component["completeCyclicProof"] = proof_value
        component["cyclicProofIdentity"] = proof_identity
    else:
        component.pop("masterBlueId", None)
        component.pop("oracleStage", None)
        component.pop("completeCyclicProof", None)
        component.pop("completeCyclicProofBlueId", None)
        component.pop("cyclicProofIdentity", None)
    component["componentStateIdentity"] = g.sha_id(
        "blue-contracts-component-state/1.0",
        {
            "componentIdentity": component_identity,
            "memberStates": member_states,
            "masterBlueId": master_blue_id,
            "cyclicProofIdentity": proof_identity,
        },
    )


def binding_set_identity(occurrences: list[dict[str, Any]]) -> str:
    items = sorted(
        [
            {
                "occurrenceIdentity": occurrence["occurrenceIdentity"],
                "bindingIdentity": occurrence["bindingIdentity"],
                "active": occurrence["active"],
                "pendingHistoricalEpoch": occurrence["pendingHistoricalEpoch"],
            }
            for occurrence in occurrences
        ],
        key=lambda item: (item["occurrenceIdentity"], item["bindingIdentity"]),
    )
    return g.sha_id("blue-contracts-occurrence-binding-set/1.0", items)


def derive_graph_changes(
    before: list[dict[str, Any]], after: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    before_by_path = {
        (item["sourceDocumentId"], item["sourcePath"]): item
        for item in before
        if item["active"]
    }
    after_by_path = {
        (item["sourceDocumentId"], item["sourcePath"]): item
        for item in after
        if item["active"]
    }
    changes: list[dict[str, Any]] = []
    for source_document_id, source_path in sorted(
        set(before_by_path) | set(after_by_path)
    ):
        old = before_by_path.get((source_document_id, source_path))
        new = after_by_path.get((source_document_id, source_path))
        if old is not None and new is not None and old == new:
            continue
        if old is None:
            kind = "ADD"
        elif new is None:
            kind = "REMOVE"
        elif (
            old["targetDocumentId"] != new["targetDocumentId"]
            or old["occurrenceIdentity"] != new["occurrenceIdentity"]
        ):
            kind = "RETARGET"
        else:
            kind = "REBIND"
        changes.append({
            "graphChangeOrdinal": len(changes),
            "changeKind": kind,
            "sourceDocumentId": source_document_id,
            "sourcePath": source_path,
            "beforeActivationGeneration": old["activationGeneration"] if old else None,
            "beforeOccurrenceIdentity": old["occurrenceIdentity"] if old else None,
            "beforeBindingIdentity": old["bindingIdentity"] if old else None,
            "beforeTargetDocumentId": old["targetDocumentId"] if old else None,
            "beforeTargetBlueId": old["expectedTargetBlueId"] if old else None,
            "afterActivationGeneration": new["activationGeneration"] if new else None,
            "afterOccurrenceIdentity": new["occurrenceIdentity"] if new else None,
            "afterBindingIdentity": new["bindingIdentity"] if new else None,
            "afterTargetDocumentId": new["targetDocumentId"] if new else None,
            "afterTargetBlueId": new["expectedTargetBlueId"] if new else None,
        })
    return changes


def root_channel_contracts(document: Any) -> dict[str, Any]:
    if not isinstance(document, dict) or not isinstance(document.get("contracts"), dict):
        return {}
    result: dict[str, Any] = {}
    for key, contract in document["contracts"].items():
        if not isinstance(contract, dict):
            continue
        type_value = contract.get("type")
        type_id = type_value.get("blueId") if isinstance(type_value, dict) else None
        if type_id in {g.SEC, g.TEC, g.ENC, "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo"}:
            result[key] = contract
    return result


def subscription_state(
    document_id: str,
    document_record: dict[str, Any],
    channel_key: str,
    contract: Any,
    graph_generation: int,
) -> dict[str, Any]:
    contribution_blue_id = direct_blue_id(contract)
    header_blue_id = direct_blue_id(contract)
    occurrence_value = {
        "managedDocumentId": document_id,
        "scopePath": "/",
        "scopeActivationGeneration": 0,
        "rawChannelKey": channel_key,
        "effectiveRuntimeContributionBlueId": contribution_blue_id,
        "subscriptionHeaderBlueId": header_blue_id,
    }
    occurrence_identity = g.sha_id(
        "blue-contracts-channel-occurrence/1.0", occurrence_value
    )
    occurrence_value = {
        "channelOccurrenceIdentity": occurrence_identity,
        **occurrence_value,
    }
    identity = g.sha_id(
        "blue-contracts-subscription/1.0",
        {
            "channelOccurrenceIdentity": occurrence_identity,
            "documentBlueId": document_record["blueId"],
            "graphGeneration": graph_generation,
            "componentGeneration": document_record["componentGeneration"],
        },
    )
    return {
        "subscriptionIdentity": identity,
        "channelOccurrence": occurrence_value,
        "documentBlueId": document_record["blueId"],
        "graphGeneration": graph_generation,
        "componentGeneration": document_record["componentGeneration"],
    }


def derive_subscription_deltas(
    before_documents: dict[str, Any],
    after_documents: dict[str, Any],
    before_graph_generation: int,
    after_graph_generation: int,
) -> list[dict[str, Any]]:
    before_states: dict[tuple[str, str], dict[str, Any]] = {}
    after_states: dict[tuple[str, str], dict[str, Any]] = {}
    for document_id, record in before_documents.items():
        for key, contract in root_channel_contracts(record["document"]).items():
            before_states[(document_id, key)] = subscription_state(
                document_id, record, key, contract, before_graph_generation
            )
    for document_id, record in after_documents.items():
        for key, contract in root_channel_contracts(record["document"]).items():
            after_states[(document_id, key)] = subscription_state(
                document_id, record, key, contract, after_graph_generation
            )
    deltas: list[dict[str, Any]] = []
    for document_id, key in sorted(set(before_states) | set(after_states)):
        old = before_states.get((document_id, key))
        new = after_states.get((document_id, key))
        if old == new:
            continue
        if old is None:
            operation = "ADD"
        elif new is None:
            operation = "REMOVE"
        else:
            operation = "REPLACE"
        exemplar = new or old
        assert exemplar is not None
        deltas.append({
            "subscriptionDeltaOrdinal": len(deltas),
            "operation": operation,
            "targetManagedScopeIdentity": g.sha_id(
                "blue-contracts-managed-scope-key/1.0",
                {"documentId": document_id, "scopePath": "/", "activationGeneration": 0},
            ),
            "channelOccurrenceIdentity": exemplar["channelOccurrence"]["channelOccurrenceIdentity"],
            "beforeSubscriptionIdentity": old["subscriptionIdentity"] if old else None,
            "afterSubscriptionIdentity": new["subscriptionIdentity"] if new else None,
            "beforeDocumentBlueId": old["documentBlueId"] if old else None,
            "afterDocumentBlueId": new["documentBlueId"] if new else None,
            "beforeGraphGeneration": old["graphGeneration"] if old else None,
            "afterGraphGeneration": new["graphGeneration"] if new else None,
            "beforeComponentGeneration": old["componentGeneration"] if old else None,
            "afterComponentGeneration": new["componentGeneration"] if new else None,
            "beforeSubscription": old,
            "afterSubscription": new,
        })
    return deltas


def normalize_admission_candidates() -> None:
    """Close and identity-bind ADMIT verifier evidence before gas modeling."""
    for path in sorted(FIX.glob("*.yaml")):
        fixture = yaml.safe_load(path.read_text())
        if fixture["operation"] == "limit-micro":
            continue
        fixture_input = fixture["input"]
        for component in fixture_input["components"]:
            decorate_component(path, fixture, component, fixture_input["documents"])
        if isinstance(fixture_input.get("candidateCyclicProof"), dict) and (
            "wrongMasterBlueId" in fixture_input["candidateCyclicProof"]
        ):
            cyclic_components = [
                component
                for component in fixture_input["components"]
                if component["kind"] == "CYCLIC"
            ]
            if len(cyclic_components) != 1:
                raise AssertionError(
                    f"bad proof candidate needs one cyclic component in {path.name}"
                )
            candidate_proof = deepcopy(
                cyclic_components[0]["completeCyclicProof"]
            )
            candidate_proof["masterBlueId"] = fixture_input[
                "candidateCyclicProof"
            ]["wrongMasterBlueId"]
            fixture_input["candidateCyclicProof"] = candidate_proof

        admission_candidate = fixture_input.get("admissionCandidate")
        if "candidateCyclicProof" in fixture_input:
            admission_candidate = {
                "kind": "BAD_CYCLIC_PROOF",
                "evidence": {
                    "candidateCyclicProof": fixture_input.pop(
                        "candidateCyclicProof"
                    )
                },
            }
        elif "candidateCyclicMembers" in fixture_input:
            admission_candidate = {
                "kind": "AMBIGUOUS_PRELIMINARY_MEMBERS",
                "evidence": {
                    "candidateCyclicMembers": fixture_input.pop(
                        "candidateCyclicMembers"
                    )
                },
            }
        elif "candidateOccurrenceBindings" in fixture_input:
            admission_candidate = {
                "kind": "INVALID_OCCURRENCE_BINDING",
                "evidence": {
                    "candidateOccurrenceBindings": fixture_input.pop(
                        "candidateOccurrenceBindings"
                    )
                },
            }
        fixture_input["admissionCandidate"] = admission_candidate
        fixture_input["admissionCandidateIdentity"] = (
            g.sha_id(
                "blue-contracts-admission-candidate/1.0",
                admission_candidate,
            )
            if admission_candidate is not None
            else None
        )
        dump_fixture(path.name, fixture)


def bind_exact_fixture_identities() -> None:
    release = yaml.safe_load(
        (ROOT / "conformance/contracts/release-manifest.yaml").read_text()
    )
    environment = fixture_environment(release)

    def document_items(documents: dict[str, Any]) -> list[dict[str, Any]]:
        return [
            {
                "documentId": document_id,
                "blueId": record["blueId"],
                "initialized": record["initialized"],
                "terminated": record["terminated"],
                "publicRoot": record["publicRoot"],
                "epoch": record["epoch"],
                "componentGeneration": record["componentGeneration"],
            }
            for document_id, record in sorted(documents.items())
        ]

    def closure_identity(
        graph_generation: int,
        documents: dict[str, Any],
        occurrences: list[dict[str, Any]],
        components: list[dict[str, Any]],
    ) -> tuple[str, str, list[str]]:
        # Durable closure state excludes cause and direct-delivery evidence;
        # both are bound by invocationIdentity below.
        occurrence_set_identity = binding_set_identity(occurrences)
        public_roots = sorted(
            document_id
            for document_id, record in documents.items()
            if record["publicRoot"]
        )
        identity = g.sha_id(
            "blue-contracts-affected-closure/1.0",
            {
                "graphGeneration": graph_generation,
                "documents": document_items(documents),
                "occurrenceBindingSetIdentity": occurrence_set_identity,
                "components": sorted(
                    component["componentStateIdentity"] for component in components
                ),
                "publicRootDocumentIds": public_roots,
            },
        )
        return identity, occurrence_set_identity, public_roots

    def component_for_document(
        components: list[dict[str, Any]], document_id: str
    ) -> dict[str, Any]:
        matches = [
            component
            for component in components
            if document_id in component["orderedMemberDocumentIds"]
        ]
        if len(matches) != 1:
            raise AssertionError(
                f"document {document_id!r} belongs to {len(matches)} components"
            )
        return matches[0]

    def trace_entries(expected: dict[str, Any]) -> list[dict[str, Any]]:
        if "gasTrace" in expected:
            return expected["gasTrace"]
        trace_file = expected.get("gasTraceFile")
        if isinstance(trace_file, str):
            return yaml.safe_load((FIX / trace_file).read_text())["entries"]
        raise AssertionError("completed fixture lacks a gas trace")

    for path in sorted(FIX.glob("*.yaml")):
        fixture = yaml.safe_load(path.read_text())
        if fixture["operation"] == "limit-micro":
            continue
        fixture_input = fixture["input"]
        old_expected = fixture["expected"]

        for component in fixture_input["components"]:
            decorate_component(path, fixture, component, fixture_input["documents"])
        if isinstance(fixture_input.get("candidateCyclicProof"), dict) and (
            "wrongMasterBlueId" in fixture_input["candidateCyclicProof"]
        ):
            cyclic_components = [
                component
                for component in fixture_input["components"]
                if component["kind"] == "CYCLIC"
            ]
            if len(cyclic_components) != 1:
                raise AssertionError(
                    f"bad proof candidate needs one cyclic component in {path.name}"
                )
            candidate = deepcopy(cyclic_components[0]["completeCyclicProof"])
            candidate["masterBlueId"] = fixture_input["candidateCyclicProof"][
                "wrongMasterBlueId"
            ]
            fixture_input["candidateCyclicProof"] = candidate

        admission_candidate: dict[str, Any] | None = fixture_input.get(
            "admissionCandidate"
        )
        if "candidateCyclicProof" in fixture_input:
            admission_candidate = {
                "kind": "BAD_CYCLIC_PROOF",
                "evidence": {
                    "candidateCyclicProof": fixture_input.pop(
                        "candidateCyclicProof"
                    )
                },
            }
        elif "candidateCyclicMembers" in fixture_input:
            admission_candidate = {
                "kind": "AMBIGUOUS_PRELIMINARY_MEMBERS",
                "evidence": {
                    "candidateCyclicMembers": fixture_input.pop(
                        "candidateCyclicMembers"
                    )
                },
            }
        elif "candidateOccurrenceBindings" in fixture_input:
            admission_candidate = {
                "kind": "INVALID_OCCURRENCE_BINDING",
                "evidence": {
                    "candidateOccurrenceBindings": fixture_input.pop(
                        "candidateOccurrenceBindings"
                    )
                },
            }
        admission_candidate_identity = (
            g.sha_id(
                "blue-contracts-admission-candidate/1.0",
                admission_candidate,
            )
            if admission_candidate is not None
            else None
        )
        fixture_input["admissionCandidate"] = admission_candidate
        fixture_input["admissionCandidateIdentity"] = admission_candidate_identity

        direct_identities: list[tuple[dict[str, Any], str]] = []
        for delivery in fixture_input.get("directDeliveries", []):
            identity = g.sha_id("blue-contracts-direct-delivery/1.0", delivery)
            direct_identities.append((delivery, identity))
        ordered_direct = sorted(
            direct_identities,
            key=lambda item: (
                item[0]["rawOccurrenceOrder"],
                item[0]["targetDocumentId"],
                item[0]["scopePath"],
                item[0]["activationGeneration"],
                item[0]["channelKey"],
                item[0]["logicalDeliveryKey"],
            ),
        )
        direct_snapshot_identity = g.sha_id(
            "blue-contracts-direct-delivery-snapshot/1.0",
            [identity for _delivery, identity in ordered_direct],
        )

        input_closure_identity, input_binding_set_identity, public_roots = (
            closure_identity(
                fixture_input["graphGeneration"],
                fixture_input["documents"],
                fixture_input.get("occurrences", []),
                fixture_input["components"],
            )
        )
        input_document_items = document_items(fixture_input["documents"])
        invocation_identity = g.sha_id(
            "blue-contracts-invocation/1.0",
            {
                "operation": fixture["operation"],
                "causeIdentity": fixture_input["cause"]["causeIdentity"],
                "inputGraphGeneration": fixture_input["graphGeneration"],
                "inputClosureIdentity": input_closure_identity,
                "documents": input_document_items,
                "directDeliverySnapshotIdentity": direct_snapshot_identity,
                "occurrenceBindingSetIdentity": input_binding_set_identity,
                "runtimeRegistryIdentity": environment["runtimeRegistryIdentity"],
                "gasPolicyIdentity": fixture_input["gasPolicy"]["policyIdentity"],
                "cyclicFinalizerIdentity": environment["cyclicFinalizerIdentity"],
                "cyclicProofVerifierIdentity": environment["cyclicProofVerifierIdentity"],
                "blueLanguageSpecificationIdentity": environment[
                    "blueLanguageSpecificationIdentity"
                ],
                "contractsSpecificationIdentity": environment[
                    "contractsSpecificationIdentity"
                ],
                "managedDocumentIdentityPolicyIdentity": environment[
                    "managedDocumentIdentityPolicy"
                ]["identity"],
                "managedBindingPolicyIdentity": environment[
                    "managedBindingPolicy"
                ]["identity"],
                "exactNodeProviderDomainIdentity": environment[
                    "exactNodeProviderDomain"
                ]["identity"],
                "externalOrderPolicyIdentity": environment[
                    "externalOrderPolicy"
                ]["identity"],
                "gasManifestIdentity": environment["gasManifestIdentity"],
                "portableLimitPolicyIdentity": environment[
                    "portableLimitPolicy"
                ]["identity"],
                "admissionCandidateIdentity": admission_candidate_identity,
            },
        )
        fixture_input["publicRootDocumentIds"] = public_roots
        fixture_input["directDeliverySnapshotIdentity"] = direct_snapshot_identity
        fixture_input["occurrenceBindingSetIdentity"] = input_binding_set_identity
        fixture_input["closureIdentity"] = input_closure_identity
        fixture_input["invocationIdentity"] = invocation_identity
        fixture_input["environment"] = deepcopy(environment)

        old_to_new: dict[str, str] = {}
        work_items = list(old_expected.get("workTrace", []))
        if old_expected.get("rejectedWork"):
            work_items.append(old_expected["rejectedWork"])
        historical = fixture_input.get("historicalTransitions", [])
        historical_work_index = 0

        for work_item in work_items:
            scope_identity = g.sha_id(
                "blue-contracts-managed-scope-key/1.0",
                {
                    "documentId": work_item["targetDocumentId"],
                    "scopePath": "/",
                    "activationGeneration": 0,
                },
            )
            if work_item["kind"] == "EXTERNAL_DELIVERY":
                matches = [
                    identity
                    for delivery, identity in ordered_direct
                    if delivery["targetDocumentId"] == work_item["targetDocumentId"]
                    and delivery["channelKey"] == work_item["channelKey"]
                ]
                if len(matches) != 1:
                    raise AssertionError(
                        f"cannot bind external work source in {path.name}: {work_item}"
                    )
                source_identity = matches[0]
            elif work_item["kind"] in {"TRIGGERED_EVENT", "EMBEDDED_EVENT"}:
                source_identity = g.sha_id(
                    "blue-contracts-event-occurrence/1.0",
                    {
                        "invocationIdentity": invocation_identity,
                        "eventOccurrenceOrdinal": work_item["occurrenceOrdinal"],
                        "eventBlueId": work_item["eventBlueId"],
                    },
                )
            elif work_item["kind"] == "HISTORICAL_TRANSITION" and historical_work_index < len(historical):
                source_identity = historical[historical_work_index]["transitionIdentity"]
                historical_work_index += 1
            else:
                source_identity = fixture_input["cause"]["causeIdentity"]

            basis = {
                "invocationIdentity": invocation_identity,
                "workOrdinal": work_item["ordinal"],
                "workKind": work_item["kind"],
                "targetManagedScopeIdentity": scope_identity,
                "sourceOccurrenceIdentity": source_identity,
            }
            old_identity = work_item["workIdentity"]
            new_identity = g.sha_id("blue-contracts-work-occurrence/1.0", basis)
            work_item["targetManagedScopeIdentity"] = scope_identity
            work_item["sourceOccurrenceIdentity"] = source_identity
            work_item["workIdentity"] = new_identity
            old_to_new[old_identity] = new_identity

        for entry in old_expected.get("gasTrace", []):
            old_identity = entry.get("workOccurrenceId")
            if old_identity in old_to_new:
                entry["workOccurrenceId"] = old_to_new[old_identity]

        if old_expected.get("attemptOutcome") == "NeedsResources":
            fixture["expected"] = {
                "attemptOutcome": "NeedsResources",
                "requiredBlueIds": sorted(old_expected["requiredBlueIds"]),
            }
            dump_fixture(path.name, fixture)
            continue

        final_documents = old_expected["finalDocuments"]
        final_components = old_expected["finalComponents"]
        final_occurrences = old_expected["finalOccurrences"]
        for component in final_components:
            decorate_component(path, fixture, component, final_documents)

        output_closure_identity, output_binding_set_identity, _output_public_roots = (
            closure_identity(
                old_expected["finalGraphGeneration"],
                final_documents,
                final_occurrences,
                final_components,
            )
        )

        resulting_documents: list[dict[str, Any]] = []
        for document_id, after in sorted(final_documents.items()):
            before = fixture_input["documents"][document_id]
            component = component_for_document(final_components, document_id)
            member_index = (
                int(after["blueId"].rsplit("#", 1)[1])
                if component["kind"] == "CYCLIC"
                else None
            )
            resulting_documents.append({
                "documentId": document_id,
                "beforeBlueId": before["blueId"],
                "afterBlueId": after["blueId"],
                "document": after["document"],
                "initialized": after["initialized"],
                "terminated": after["terminated"],
                "publicRoot": after["publicRoot"],
                "epoch": after["epoch"],
                "componentGeneration": after["componentGeneration"],
                "componentIdentity": component["componentIdentity"],
                "componentStateIdentity": component["componentStateIdentity"],
                "memberIndex": member_index,
            })

        graph_changes = derive_graph_changes(
            fixture_input.get("occurrences", []), final_occurrences
        )
        graph_changes_identity = g.sha_id(
            "blue-contracts-graph-changes/1.0", graph_changes
        )
        subscription_deltas = derive_subscription_deltas(
            fixture_input["documents"],
            final_documents,
            fixture_input["graphGeneration"],
            old_expected["finalGraphGeneration"],
        )
        subscription_deltas_identity = g.sha_id(
            "blue-contracts-subscription-deltas/1.0",
            [
                {
                    key: delta[key]
                    for key in (
                        "subscriptionDeltaOrdinal",
                        "operation",
                        "targetManagedScopeIdentity",
                        "channelOccurrenceIdentity",
                        "beforeSubscriptionIdentity",
                        "afterSubscriptionIdentity",
                        "beforeDocumentBlueId",
                        "afterDocumentBlueId",
                        "beforeGraphGeneration",
                        "afterGraphGeneration",
                        "beforeComponentGeneration",
                        "afterComponentGeneration",
                    )
                }
                for delta in subscription_deltas
            ],
        )

        checkpoint_writes: list[dict[str, Any]] = []
        if old_expected["status"] == "success":
            def checkpoint_entry(document: Any, raw_key: str) -> Any:
                if not isinstance(document, dict):
                    return None
                return (
                    document.get("contracts", {})
                    .get("checkpoint", {})
                    .get("entries", {})
                    .get(raw_key)
                )

            def checkpoint_side(
                document: Any, raw_key: str, entry: Any
            ) -> tuple[str | None, dict[str, Any] | None, str | None]:
                if entry is None:
                    return None, None, None
                if not isinstance(entry, dict) or set(entry) != {"domain", "subject"}:
                    raise AssertionError("checkpoint entry is not the exact closed shape")
                channel = (
                    document.get("contracts", {}).get(raw_key)
                    if isinstance(document, dict)
                    else None
                )
                domain_value, domain_blue_id = _checkpoint_domain_value_and_id(
                    entry["domain"],
                    current_channel=channel if isinstance(channel, dict) else None,
                )
                subject = entry["subject"]
                if not isinstance(subject, dict) or set(subject) != {"blueId"}:
                    raise AssertionError("checkpoint subject must be a pure reference")
                return domain_blue_id, domain_value, subject["blueId"]

            external_by_key: dict[tuple[str, str], dict[str, Any]] = {}
            ordered_changes: list[tuple[str, str, dict[str, Any] | None]] = []
            for work_item in old_expected.get("workTrace", []):
                if work_item["kind"] != "EXTERNAL_DELIVERY":
                    continue
                key = (work_item["targetDocumentId"], work_item["channelKey"])
                if key not in external_by_key:
                    external_by_key[key] = work_item
                    ordered_changes.append((key[0], key[1], work_item))

            for document_id in sorted(final_documents):
                before_document = fixture_input["documents"][document_id]["document"]
                after_document = final_documents[document_id]["document"]
                before_entries = (
                    before_document.get("contracts", {})
                    .get("checkpoint", {})
                    .get("entries", {})
                )
                after_entries = (
                    after_document.get("contracts", {})
                    .get("checkpoint", {})
                    .get("entries", {})
                )
                for raw_key in sorted(set(before_entries) | set(after_entries)):
                    key = (document_id, raw_key)
                    if key in external_by_key:
                        continue
                    if before_entries.get(raw_key) == after_entries.get(raw_key):
                        continue
                    ordered_changes.append((document_id, raw_key, None))

            for document_id, raw_key, source_work in ordered_changes:
                before_document = fixture_input["documents"][document_id]["document"]
                after_document = final_documents[document_id]["document"]
                before_entry = checkpoint_entry(before_document, raw_key)
                after_entry = checkpoint_entry(after_document, raw_key)
                before_domain_id, before_domain, before_subject = checkpoint_side(
                    before_document, raw_key, before_entry
                )
                after_domain_id, after_domain, after_subject = checkpoint_side(
                    after_document, raw_key, after_entry
                )
                if before_entry == after_entry:
                    raise AssertionError("checkpoint receipt does not describe a change")
                scope_identity = (
                    source_work["targetManagedScopeIdentity"]
                    if source_work is not None
                    else g.sha_id(
                        "blue-contracts-managed-scope-key/1.0",
                        {
                            "documentId": document_id,
                            "scopePath": "/",
                            "activationGeneration": 0,
                        },
                    )
                )
                checkpoint_writes.append({
                    "checkpointWriteOrdinal": len(checkpoint_writes),
                    "targetManagedScopeIdentity": scope_identity,
                    "rawChannelKey": raw_key,
                    "beforePresent": before_entry is not None,
                    "beforeDomainBlueId": before_domain_id,
                    "beforeDomainValue": before_domain,
                    "beforeSubjectBlueId": before_subject,
                    "afterPresent": after_entry is not None,
                    "afterDomainBlueId": after_domain_id,
                    "afterDomainValue": after_domain,
                    "afterSubjectBlueId": after_subject,
                })
        checkpoint_writes_identity = g.sha_id(
            "blue-contracts-checkpoint-writes/1.0", checkpoint_writes
        )

        public_events: list[dict[str, Any]] = []
        for ordinal, event_value in enumerate(old_expected.get("publicEvents", [])):
            event_blue_id = direct_blue_id(event_value)
            public_events.append({
                "publicEventOrdinal": ordinal,
                "publicRootDocumentId": public_roots[0],
                "eventOccurrenceIdentity": g.sha_id(
                    "blue-contracts-event-occurrence/1.0",
                    {
                        "invocationIdentity": invocation_identity,
                        "eventOccurrenceOrdinal": ordinal,
                        "eventBlueId": event_blue_id,
                    },
                ),
                "eventBlueId": event_blue_id,
                "event": event_value,
            })
        public_events_identity = g.sha_id(
            "blue-contracts-public-events/1.0",
            [
                {
                    key: event[key]
                    for key in (
                        "publicEventOrdinal",
                        "publicRootDocumentId",
                        "eventOccurrenceIdentity",
                        "eventBlueId",
                    )
                }
                for event in public_events
            ],
        )

        trace = trace_entries(old_expected)
        gas_trace_identity = g.sha_id("blue-contracts-gas-trace/1.0", trace)
        resulting_components = deepcopy(final_components)
        for component in resulting_components:
            component.pop("oracleStage", None)
        complete_result: dict[str, Any] = {
            "attemptOutcome": "Complete",
            "status": old_expected["status"],
            "invocationIdentity": invocation_identity,
            "inputClosureIdentity": input_closure_identity,
            "outputClosureIdentity": output_closure_identity,
            "atomic": True,
            "workTrace": old_expected.get("workTrace", []),
            "tentativeFinalizations": old_expected.get(
                "tentativeFinalizations", []
            ),
            "graphGeneration": old_expected["finalGraphGeneration"],
            "resultingDocuments": resulting_documents,
            "resultingComponents": resulting_components,
            "occurrenceBindings": final_occurrences,
            "occurrenceBindingSetIdentity": output_binding_set_identity,
            "graphChanges": graph_changes,
            "graphChangesIdentity": graph_changes_identity,
            "subscriptionDeltas": subscription_deltas,
            "subscriptionDeltasIdentity": subscription_deltas_identity,
            "checkpointWrites": checkpoint_writes,
            "checkpointWritesIdentity": checkpoint_writes_identity,
            "publicEvents": public_events,
            "publicEventsIdentity": public_events_identity,
            "rollbackToInput": bool(
                old_expected.get(
                    "rollbackToInput", old_expected["status"] != "success"
                )
            ),
            "totalGas": sum(entry["subtotal"] for entry in trace),
            "gasTraceIdentity": gas_trace_identity,
        }
        if "gasTrace" in old_expected:
            complete_result["gasTrace"] = trace
        else:
            complete_result["gasTraceFile"] = old_expected["gasTraceFile"]
        for optional in (
            "diagnostic",
            "observations",
            "providerLoads",
            "unrelatedDocumentsOpened",
        ):
            if optional in old_expected:
                complete_result[optional] = old_expected[optional]

        if old_expected.get("rejectedCharge") is not None:
            rejected_work = old_expected.get("rejectedWork")
            if rejected_work is not None:
                complete_result["rejectedWorkOccurrence"] = rejected_work
            rejected_charge = deepcopy(old_expected["rejectedCharge"])
            cap = rejected_charge["applicableCap"]
            if isinstance(cap, str):
                rejected_charge["applicableCap"] = (
                    {"kind": "LOCAL", "documentId": cap.split(":", 1)[1]}
                    if cap.startswith("LOCAL:")
                    else {"kind": "SHARED"}
                )
            if rejected_work is not None:
                rejected_charge["owner"] = {
                    "kind": "WORK",
                    "workOccurrenceIdentity": rejected_work["workIdentity"],
                }
            elif "owner" not in rejected_charge:
                raise AssertionError("non-work rejection lacks an exact owner")
            rejected_charge["rejectedChargeIdentity"] = g.sha_id(
                "blue-contracts-rejected-charge/1.0",
                {
                    key: rejected_charge[key]
                    for key in (
                        "namespace",
                        "counter",
                        "quantity",
                        "weight",
                        "subtotal",
                        "applicableCap",
                        "remainingBeforeCharge",
                        "owner",
                    )
                },
            )
            complete_result["rejectedCharge"] = rejected_charge

        if old_expected["status"] == "success":
            expected_input_components = [
                {
                    "componentIdentity": component["componentIdentity"],
                    "componentStateIdentity": component["componentStateIdentity"],
                    "componentGeneration": component["componentGeneration"],
                    "masterBlueId": component.get("masterBlueId"),
                }
                for component in fixture_input["components"]
            ]
            commit_result_components = [
                {
                    "componentIdentity": component["componentIdentity"],
                    "componentStateIdentity": component["componentStateIdentity"],
                    "cyclicProofIdentity": component.get("cyclicProofIdentity"),
                }
                for component in final_components
            ]
            commit_document_deltas = [
                {
                    "documentId": item["documentId"],
                    "beforeBlueId": item["beforeBlueId"],
                    "afterBlueId": item["afterBlueId"],
                }
                for item in resulting_documents
            ]
            companion_basis = {
                "invocationIdentity": invocation_identity,
                "inputClosureIdentity": input_closure_identity,
                "outputClosureIdentity": output_closure_identity,
                "expectedInputGraphGeneration": fixture_input["graphGeneration"],
                "expectedInputDocuments": [
                    {
                        "documentId": item["documentId"],
                        "blueId": item["blueId"],
                    }
                    for item in input_document_items
                ],
                "expectedInputComponents": expected_input_components,
                "inputOccurrenceBindingSetIdentity": input_binding_set_identity,
                "outputGraphGeneration": old_expected["finalGraphGeneration"],
                "resultingDocuments": commit_document_deltas,
                "resultingComponents": commit_result_components,
                "occurrenceBindingSetIdentity": output_binding_set_identity,
                "graphChangesIdentity": graph_changes_identity,
                "checkpointWritesIdentity": checkpoint_writes_identity,
                "subscriptionDeltasIdentity": subscription_deltas_identity,
                "publicEventsIdentity": public_events_identity,
                "gasTraceIdentity": gas_trace_identity,
                "blueLanguageSpecificationIdentity": environment[
                    "blueLanguageSpecificationIdentity"
                ],
                "contractsSpecificationIdentity": environment[
                    "contractsSpecificationIdentity"
                ],
                "managedDocumentIdentityPolicyIdentity": environment[
                    "managedDocumentIdentityPolicy"
                ]["identity"],
                "managedBindingPolicyIdentity": environment[
                    "managedBindingPolicy"
                ]["identity"],
                "exactNodeProviderDomainIdentity": environment[
                    "exactNodeProviderDomain"
                ]["identity"],
                "externalOrderPolicyIdentity": environment[
                    "externalOrderPolicy"
                ]["identity"],
                "runtimeRegistryIdentity": environment["runtimeRegistryIdentity"],
                "gasManifestIdentity": environment["gasManifestIdentity"],
                "portableLimitPolicyIdentity": environment[
                    "portableLimitPolicy"
                ]["identity"],
                "cyclicFinalizerIdentity": environment["cyclicFinalizerIdentity"],
                "cyclicProofVerifierIdentity": environment[
                    "cyclicProofVerifierIdentity"
                ],
            }
            complete_result["platformCommitCompanion"] = {
                "companionIdentity": g.sha_id(
                    "blue-contracts-platform-commit-companion/1.0",
                    companion_basis,
                ),
                **companion_basis,
            }

        fixture["expected"] = complete_result
        dump_fixture(path.name, fixture)


def externalize_large_gas_trace() -> None:
    name = "c-clo-04-default-policy-loop.yaml"
    fixture = load(name)
    trace = fixture["expected"].pop("gasTrace")
    trace_name = "c-clo-04-default-policy-loop-gas.yaml"
    g.dump(
        TRACE / trace_name,
        {
            "schema": "blue-contracts-gas-trace/1.0",
            "fixture": fixture["id"],
            "entries": trace,
        },
    )
    fixture["expected"]["gasTraceFile"] = f"traces/{trace_name}"
    dump_fixture(name, fixture)

def refine() -> None:
    add_exact_identity_visibility_fixture()
    rewrite_loop_gas_traces()
    rewrite_frozen_edge_removal()
    rewrite_frozen_edge_addition()
    fix_invalid_and_ambiguous()
    rewrite_limits()
    add_split_identity_trace()
    add_containing_spine_identity_fixture()
    write_limit_boundary_microfixtures()
    rewrite_outer_public_and_failure()
    rewrite_initialization_cycle()
    ensure_direct_delivery_work()
    order_initialization_batch_work()
    normalize_event_occurrence_ordinals()
    normalize_finalization_boundaries()
    add_checkpoint_retirement_coverage_fixture()
    materialize_processor_state()
    normalize_admission_candidates()
    normalize_literal_rollback_results()
    ensure_complete_gas_traces()
    add_rejected_owner_coverage_fixtures()
    bind_exact_fixture_identities()
    externalize_large_gas_trace()
    print("REFINED_CLOSURE_FIXTURES_OK")


if __name__ == "__main__":
    refine()
