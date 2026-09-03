#!/usr/bin/env python3
from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import hashlib
import json
import os
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
from gas_reference import gas_trace_identity  # noqa: E402

PE = "9ftzzP6ySLmbJ43bjwTbrm6Ff79FKqsVy5xdA1zWxoQ3"
SEC = "2hesjWGVbvcJSu6woCUTssU9S7A69ep93UzdgvwosDLt"
SH = "9Wa77paaHDctnRmgwcXMGUkYeE5EzBcLf2ZRTbBhDA8"
TEC = "DRxc8GkSGPbdENdB8ZK976i1Jzc6M1QdG8UsVMHcqQcf"
ENC = "7ZgUJxCyokHf84uibaQz138mFRLarykWLewVAn8bibTN"
FE = "5KUZWsqRuW7SyRj1oCK7hRTmJKVCHTiVJboxy4nas8KX"
INITIALIZED_MARKER = "Hp3fNbpFxKwLiTwWAf3swpN7gKbsr6ofwEDMntiwXPaB"
TERMINATED_MARKER = "4c1aabU6a3idKpWPzTRS4upLjCb6eZh3F1PDXkNh7i6v"
CHECKPOINT_MARKER = "Ag2NpsQnNpn8nNRopURxcWVHRnYu5REDZeS8YJcfvQUS"

FIXTURE_DIR = ROOT / "conformance/contracts/fixtures/closure"
ORACLE_DIR = ROOT / "conformance/contracts/oracles"
FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
ORACLE_DIR.mkdir(parents=True, exist_ok=True)


def canonical_bytes(value: Any) -> bytes:
    return jcs_dumps(value)


def sha_id(domain: str, value: Any) -> str:
    payload = {"domain": domain, "value": value}
    return "sha256:" + hashlib.sha256(canonical_bytes(payload)).hexdigest()


DEFAULT_BINDING_POLICY_IDENTITY = sha_id(
    "blue-contracts-managed-binding-policy/1.0",
    {"label": "exact-document-lineage"},
)
EXTERNAL_ORDER_POLICY_IDENTITY = sha_id(
    "blue-contracts-external-order-policy/1.0",
    {"label": "canonical-source-order-v1"},
)


def ref(blue_id: str) -> dict[str, str]:
    return {"blueId": blue_id}


def initialized_marker(pre_initialization_document: Any) -> dict[str, Any]:
    """Return the exact direct processing-initialized marker value.

    ProcessorMarkerFactory persists the historical scope as its exact pure
    reference.  The referenced content is supplied by the initialization
    input/current node, not duplicated into the marker representation.
    """
    return {
        "type": ref(INITIALIZED_MARKER),
        "document": ref(direct_blue_id(pre_initialization_document)),
    }


def checkpoint_domain_value(channel: dict[str, Any]) -> dict[str, Any]:
    """Mirror the released Java CheckpointDomain direct Blue value."""
    effective_type = channel.get("type", {}).get("blueId")
    if not isinstance(effective_type, str) or not effective_type:
        raise ValueError("checkpoint channel requires an exact effective type")
    value: dict[str, Any] = {
        "contractsVersion": "1.0",
        "effectiveTypeBlueId": effective_type,
        "sourceContributionNodeBlueIds": [direct_blue_id(channel)],
    }
    discriminator = channel.get("checkpointDomain")
    if isinstance(discriminator, str) and discriminator:
        value["runtimeDiscriminator"] = discriminator
    return value


def checkpoint_domain_blue_id(channel: dict[str, Any]) -> str:
    return direct_blue_id(checkpoint_domain_value(channel))


def event(event_id: str) -> dict[str, Any]:
    return {"type": ref(FE), "subscriptionKey": "fixture", "id": event_id}


def occurrence(source: str, path: str, generation: int, target: str, target_blue_id: str, *, active: bool = True, pending_epoch: int | None = None) -> dict[str, Any]:
    lineage_basis = {
        "sourceDocumentId": source,
        "sourcePath": path,
        "activationGeneration": generation,
        "targetDocumentId": target,
        "bindingPolicyIdentity": DEFAULT_BINDING_POLICY_IDENTITY,
    }
    occurrence_identity = sha_id("blue-contracts-managed-occurrence-lineage/1.0", lineage_basis)
    result = {
        "occurrenceIdentity": occurrence_identity,
        "bindingIdentity": sha_id(
            "blue-contracts-managed-occurrence/1.0",
            {**lineage_basis, "expectedTargetBlueId": target_blue_id},
        ),
        **lineage_basis,
        "expectedTargetBlueId": target_blue_id,
        "active": active,
        "pendingHistoricalEpoch": pending_epoch,
    }
    return result


def component(kind: str, generation: int, members: list[str], *, master: str | None = None, stage: str | None = None) -> dict[str, Any]:
    basis = {"kind": kind, "generation": generation, "members": members}
    result = {
        "componentIdentity": sha_id("blue-contracts-component/1.0", basis),
        "componentGeneration": generation,
        "kind": kind,
        "orderedMemberDocumentIds": members,
    }
    if master is not None:
        result["masterBlueId"] = master
    if stage is not None:
        result["oracleStage"] = stage
    return result


def document_record(document_id: str, blue_id: str, document: Any, *, initialized: bool = True, terminated: bool = False, public_root: bool = False, epoch: int = 0, component_generation: int = 0) -> dict[str, Any]:
    result = {
        "documentId": document_id,
        "blueId": blue_id,
        "document": document,
        "initialized": initialized,
        "terminated": terminated,
        "publicRoot": public_root,
        "epoch": epoch,
        "componentGeneration": component_generation,
    }
    return result


def external_cause(evt: Any, order: list[Any]) -> dict[str, Any]:
    event_blue_id = direct_blue_id(evt)
    return {
        "kind": "external",
        "causeIdentity": sha_id(
            "blue-contracts-external-cause/1.0",
            {
                "eventBlueId": event_blue_id,
                "sourceOrder": order,
                "externalOrderPolicyIdentity": EXTERNAL_ORDER_POLICY_IDENTITY,
            },
        ),
        "event": evt,
        "eventBlueId": event_blue_id,
        "sourceOrder": order,
        "externalOrderPolicyIdentity": EXTERNAL_ORDER_POLICY_IDENTITY,
    }


def admission_cause(kind: str, label: str) -> dict[str, Any]:
    policy = sha_id("blue-contracts-admission-policy/1.0", {"label": label})
    return {
        "kind": "admission",
        "causeIdentity": sha_id(
            "blue-contracts-admission-cause/1.0",
            {
                "admissionKind": kind,
                "label": label,
                "triggeringEventBlueId": None,
                "parentTransitionIdentity": None,
                "policyIdentity": policy,
            },
        ),
        "admissionKind": kind,
        "label": label,
        "triggeringEventBlueId": None,
        "parentTransitionIdentity": None,
        "policyIdentity": policy,
    }


def managed_revision_cause(
    target_occurrence_identity: str,
    child_document_id: str,
    from_epoch: int,
    before_blue_id: str,
    after_blue_id: str,
    after_document: Any,
    original_source_cause_identity: str,
) -> dict[str, Any]:
    to_epoch = from_epoch + 1
    receipt_value = {
        "childDocumentId": child_document_id,
        "fromEpoch": from_epoch,
        "toEpoch": to_epoch,
        "beforeBlueId": before_blue_id,
        "afterBlueId": after_blue_id,
        "originalSourceCauseIdentity": original_source_cause_identity,
    }
    receipt_identity = sha_id(
        "blue-contracts-source-revision-receipt/1.0", receipt_value
    )
    cause_value = {
        "targetOccurrenceIdentity": target_occurrence_identity,
        **receipt_value,
        "sourceRevisionReceiptIdentity": receipt_identity,
    }
    return {
        "kind": "managed-revision",
        "causeIdentity": sha_id(
            "blue-contracts-managed-revision-cause/1.0", cause_value
        ),
        **cause_value,
        "afterDocument": deepcopy(after_document),
    }


def policy(limit: int = 100000, *, default: bool = True, locals: dict[str, int] | None = None, label: str = "release-default") -> dict[str, Any]:
    local_limits = locals or {}
    data = {
        "sharedLimit": limit,
        "localLimits": [
            {"documentId": document_id, "limit": local_limits[document_id]}
            for document_id in sorted(local_limits)
        ],
        "label": label,
    }
    return {
        "policyIdentity": sha_id("blue-contracts-execution-policy/1.0", data),
        "sharedLimit": limit,
        "usesReleaseDefault": default,
        "localLimits": local_limits,
        "label": label,
    }


def direct_delivery(doc: str, channel: str = "source", key: str = "start", order: int = 0) -> dict[str, Any]:
    return {
        "targetDocumentId": doc,
        "scopePath": "/",
        "activationGeneration": 0,
        "channelKey": channel,
        "logicalDeliveryKey": key,
        "rawOccurrenceOrder": order,
    }


def work(ordinal: int, kind: str, doc: str, channel: str, *, event_id: str | None = None, occurrence_ordinal: int | None = None, invocation: str = "fixture") -> dict[str, Any]:
    basis = {"invocation": invocation, "ordinal": ordinal, "kind": kind, "target": doc, "channel": channel, "event": event_id, "occurrence": occurrence_ordinal}
    result = {
        "ordinal": ordinal,
        "kind": kind,
        "targetDocumentId": doc,
        "channelKey": channel,
        # A deterministic generation seed only. refine_closure_fixtures binds
        # the normative invocation/scope/source tuple and replaces this value.
        "workIdentity": sha_id("blue-contracts-fixture-work-seed/1.0", basis),
    }
    if event_id is not None:
        result["eventBlueId"] = event_id
    if occurrence_ordinal is not None:
        result["occurrenceOrdinal"] = occurrence_ordinal
    return result


def cyclic_canonical_bytes(oracle: Any) -> int:
    return cyclic_canonical_limit_bytes(oracle)


def finalize(
    ordinal: int,
    stage: str,
    oracle: Any,
    doc_ids: list[str],
    *,
    boundary: dict[str, Any] | None = None,
) -> dict[str, Any]:
    member_ids = oracle.member_ids_in_source_order()
    return {
        "ordinal": ordinal,
        "boundary": boundary or {"kind": "WORK", "afterWorkOrdinal": ordinal},
        "oracleStage": stage,
        "masterBlueId": oracle.master_blue_id,
        "canonicalBytes": cyclic_canonical_bytes(oracle),
        "memberBlueIds": {doc_ids[i]: member_ids[i] for i in range(len(doc_ids))},
    }


class _NoAliasSafeDumper(yaml.SafeDumper):
    def ignore_aliases(self, data: Any) -> bool:
        return True


def dump(path: Path, value: Any) -> None:
    path.write_text(
        yaml.dump(
            value,
            Dumper=_NoAliasSafeDumper,
            sort_keys=False,
            allow_unicode=True,
            width=1000,
        )
    )
    write_log = os.environ.get("BLUE_CONTRACTS_ORACLE_WRITE_LOG")
    if not write_log:
        return
    try:
        relative = path.resolve().relative_to(ORACLE_DIR.resolve())
    except ValueError:
        return
    with Path(write_log).open("a", encoding="utf-8") as stream:
        stream.write(f"{relative.as_posix()}\n")


def oracle_payload(label: str, stages: list[tuple[str, list[Any], Any]]) -> dict[str, Any]:
    out = {"schema": "blue-language-cyclic-oracle/1.0", "id": label, "stages": []}
    for name, source_docs, oracle in stages:
        mats = materialize_cyclic_members(source_docs, oracle)
        out["stages"].append({
            "name": name,
            "sourceDocumentsWithThisReferences": source_docs,
            "preliminaryBlueIds": {str(m.source_index): m.preliminary_blue_id for m in oracle.members},
            "sortedSourceIndices": list(oracle.sorted_source_indices),
            "canonicalLimitForm": cyclic_canonical_limit_form(oracle),
            "canonicalMasterInputUtf8Bytes": cyclic_canonical_bytes(oracle),
            "masterBlueId": oracle.master_blue_id,
            "memberBlueIdsInSourceOrder": list(oracle.member_ids_in_source_order()),
            "materializedDocuments": mats,
        })
    return out


def gas_trace_loop(limit: int) -> tuple[list[dict[str, Any]], int, str, int]:
    weights = yaml.safe_load((ROOT / "conformance/contracts/gas-manifest.yaml").read_text())["namespaces"]["processor"]["counters"]
    trace: list[dict[str, Any]] = []
    total = 0
    rejected = ""
    accepted_work = 0

    def charge(counter: str, quantity: int = 1, *, logical: str = "", scope: str = "", contract: str = "", reason: str = "") -> bool:
        nonlocal total, rejected
        subtotal = weights[counter] * quantity
        if total + subtotal > limit:
            rejected = counter
            return False
        entry = {"sequence": len(trace), "namespace": "processor", "counter": counter, "quantity": quantity, "weight": weights[counter], "subtotal": subtotal}
        if scope:
            entry["scopePath"] = scope
        if contract:
            entry["contractKey"] = contract
        if logical:
            entry["logicalPath"] = logical
        if reason:
            entry["reason"] = reason
        trace.append(entry)
        total += subtotal
        return True

    fixed = [
        ("processInvocation", 1), ("closureInvocation", 1), ("deliverySnapshotEntry", 1),
        ("managedDocumentOpened", 2), ("managedOccurrenceBindingVerified", 2),
        ("componentMemberPartitioned", 2), ("componentEdgePartitioned", 2),
    ]
    for counter, qty in fixed:
        assert charge(counter, qty, reason="closure-admission")

    # Direct seed A.
    direct_steps = [
        ("closureWorkOccurrenceDequeued", 1), ("scopeOpened", 1),
        ("contractHeaderRecognized", 2), ("channelCandidateTested", 1),
        ("channelAccepted", 1), ("handlerCandidateTested", 1),
        ("handlerCall", 1), ("internalEventEnqueued", 1),
        ("closureWorkOccurrenceEnqueued", 1),
    ]
    for counter, qty in direct_steps:
        assert charge(counter, qty, logical="work/0", scope="/", contract="source" if "contract" in counter.lower() else "")
    accepted_work = 1

    internal_index = 0
    while True:
        logical = f"work/{internal_index + 1}"
        internal_steps = [
            ("closureWorkOccurrenceDequeued", 1), ("internalEventDequeued", 1),
            ("embeddedEventDelivered", 1), ("scopeOpened", 1),
            ("contractHeaderRecognized", 2), ("channelCandidateTested", 1),
            ("channelAccepted", 1), ("handlerCandidateTested", 1),
            ("handlerCall", 1), ("internalEventEnqueued", 1),
            ("closureWorkOccurrenceEnqueued", 1),
        ]
        completed = True
        for counter, qty in internal_steps:
            if not charge(counter, qty, logical=logical, scope="/", contract="fromEmbedded" if "contract" in counter.lower() else ""):
                completed = False
                break
        if not completed:
            break
        accepted_work += 1
        internal_index += 1
        if accepted_work > 10000:
            raise AssertionError("loop trace did not terminate")
    trace_identity = gas_trace_identity(trace)
    return trace, total, rejected, accepted_work


def build_scenarios() -> dict[str, Any]:
    # Exact application events.
    events = {name: event(name) for name in ["START-FINITE", "X", "Y", "START-LOOP", "LOOP", "DIRECT-BOTH", "INIT", "SPLIT", "MERGE", "ATTACH-HISTORICAL"]}
    event_ids = {name: direct_blue_id(value) for name, value in events.items()}

    # Dynamic finite A/B scenario.
    a0 = {
        "documentId": "a", "memberIdentity": "a", "localXSeen": 0, "result": "pending",
        "contracts": {
            "embedded": {"type": ref(PE), "paths": ["/b"]},
            "source": {"type": ref(SEC), "subscriptionKey": "fixture", "eventKey": "fixture", "accept": True, "checkpointDomain": "domain", "logicalDeliveryKey": "start"},
            "start": {"type": ref(SH), "channel": "source", "order": 0},
            "localX": {"type": ref(TEC), "event": ref(event_ids["X"]), "order": 0},
            "onLocalX": {"type": ref(SH), "channel": "localX", "order": 0},
            "fromB": {"type": ref(ENC), "sourcePath": "/b", "event": ref(event_ids["Y"]), "order": 0},
            "onY": {"type": ref(SH), "channel": "fromB", "order": 0},
        },
    }
    a0_id = direct_blue_id(a0)
    b0 = {
        "documentId": "b", "memberIdentity": "b", "xHandled": 0, "a": ref(a0_id),
        "contracts": {
            "embedded": {"type": ref(PE), "paths": ["/a"]},
            "fromA": {"type": ref(ENC), "sourcePath": "/a", "event": ref(event_ids["X"]), "order": 0},
            "onX": {"type": ref(SH), "channel": "fromA", "order": 0},
        },
    }
    b0_id = direct_blue_id(b0)
    finite_sources: list[tuple[str, list[Any], Any]] = []
    a1 = {**a0, "b": ref("this#1")}
    b1 = {**b0, "a": ref("this#0")}
    finite_stage_docs = [
        ("cycle-formed", a1, b1),
        ("a-local-x", {**a1, "localXSeen": 1}, b1),
        ("b-handled-x", {**a1, "localXSeen": 1}, {**b1, "xHandled": 1}),
        ("a-finished", {**a1, "localXSeen": 1, "result": "done"}, {**b1, "xHandled": 1}),
    ]
    for name, a, b in finite_stage_docs:
        finite_sources.append((name, [a, b], cyclic_set_oracle([a, b])))
    dump(ORACLE_DIR / "finite-dynamic-a-b.yaml", oracle_payload("finite-dynamic-a-b", finite_sources))

    # Static infinite loop component.
    loop_a_source = {
        "documentId": "loop-a", "memberIdentity": "loop-a", "b": ref("this#1"),
        "contracts": {
            "embedded": {"type": ref(PE), "paths": ["/b"]},
            "source": {"type": ref(SEC), "subscriptionKey": "fixture", "eventKey": "fixture", "accept": True, "checkpointDomain": "domain", "logicalDeliveryKey": "loop"},
            "start": {"type": ref(SH), "channel": "source", "order": 0},
            "fromB": {"type": ref(ENC), "sourcePath": "/b", "event": ref(event_ids["LOOP"]), "order": 0},
            "onLoopFromB": {"type": ref(SH), "channel": "fromB", "order": 0},
        },
    }
    loop_b_source = {
        "documentId": "loop-b", "memberIdentity": "loop-b", "a": ref("this#0"),
        "contracts": {
            "embedded": {"type": ref(PE), "paths": ["/a"]},
            "source": {"type": ref(SEC), "subscriptionKey": "fixture", "eventKey": "fixture", "accept": True, "checkpointDomain": "domain", "logicalDeliveryKey": "loop"},
            "start": {"type": ref(SH), "channel": "source", "order": 0},
            "fromA": {"type": ref(ENC), "sourcePath": "/a", "event": ref(event_ids["LOOP"]), "order": 0},
            "onLoopFromA": {"type": ref(SH), "channel": "fromA", "order": 0},
        },
    }
    loop_oracle = cyclic_set_oracle([loop_a_source, loop_b_source])
    loop_mats = materialize_cyclic_members([loop_a_source, loop_b_source], loop_oracle)
    dump(ORACLE_DIR / "infinite-loop-a-b.yaml", oracle_payload("infinite-loop-a-b", [("initial", [loop_a_source, loop_b_source], loop_oracle)]))

    # Simple static pair and self-cycle.
    simple_a = {"documentId": "simple-a", "memberIdentity": "simple-a", "b": ref("this#1"), "contracts": {
        "embedded": {"type": ref(PE), "paths": ["/b"]},
        "source": {"type": ref(SEC), "subscriptionKey": "fixture", "eventKey": "fixture", "accept": True, "checkpointDomain": "domain", "logicalDeliveryKey": "simple-a"},
        "start": {"type": ref(SH), "channel": "source", "order": 0},
    }}
    simple_b = {"documentId": "simple-b", "memberIdentity": "simple-b", "a": ref("this#0"), "contracts": {
        "embedded": {"type": ref(PE), "paths": ["/a"]},
        "source": {"type": ref(SEC), "subscriptionKey": "fixture", "eventKey": "fixture", "accept": True, "checkpointDomain": "domain", "logicalDeliveryKey": "simple-b"},
        "start": {"type": ref(SH), "channel": "source", "order": 0},
    }}
    simple_oracle = cyclic_set_oracle([simple_a, simple_b])
    self_source = {"documentId": "self", "memberIdentity": "self", "self": ref("this#0"), "contracts": {"embedded": {"type": ref(PE), "paths": ["/self"]}}}
    self_oracle = cyclic_set_oracle([self_source])
    dump(ORACLE_DIR / "static-and-self-cycle.yaml", {
        "schema": "blue-language-cyclic-oracle/1.0", "id": "static-and-self-cycle",
        "sets": [oracle_payload("static", [("initial", [simple_a, simple_b], simple_oracle)]), oracle_payload("self", [("initial", [self_source], self_oracle)])],
    })

    # Four-member merge and split oracle.
    ab_a = {"documentId": "ma", "memberIdentity": "ma", "b": ref("this#1"), "contracts": {
        "embedded": {"type": ref(PE), "paths": ["/b", "/c"]},
        "source": {"type": ref(SEC), "subscriptionKey": "fixture", "eventKey": "fixture", "accept": True, "checkpointDomain": "domain", "logicalDeliveryKey": "ma"},
        "start": {"type": ref(SH), "channel": "source", "order": 0},
    }}
    ab_b = {"documentId": "mb", "memberIdentity": "mb", "a": ref("this#0"), "contracts": {"embedded": {"type": ref(PE), "paths": ["/a"]}}}
    ab_oracle = cyclic_set_oracle([ab_a, ab_b]); ab_ids = ab_oracle.member_ids_in_source_order()
    cd_c = {"documentId": "mc", "memberIdentity": "mc", "d": ref("this#1"), "a": ref(ab_ids[0]), "contracts": {
        "embedded": {"type": ref(PE), "paths": ["/d", "/a"]},
        "source": {"type": ref(SEC), "subscriptionKey": "fixture", "eventKey": "fixture", "accept": True, "checkpointDomain": "domain", "logicalDeliveryKey": "mc"},
        "start": {"type": ref(SH), "channel": "source", "order": 0},
    }}
    cd_d = {"documentId": "md", "memberIdentity": "md", "c": ref("this#0"), "contracts": {"embedded": {"type": ref(PE), "paths": ["/c"]}}}
    cd_oracle = cyclic_set_oracle([cd_c, cd_d]); cd_ids = cd_oracle.member_ids_in_source_order()
    split_cd_c = deepcopy(cd_c); split_cd_c.pop("a")
    split_cd_oracle = cyclic_set_oracle([split_cd_c, cd_d])
    merged = [
        {**ab_a, "b": ref("this#1"), "c": ref("this#2")},
        {**ab_b, "a": ref("this#0")},
        {**cd_c, "d": ref("this#3"), "a": ref("this#0")},
        {**cd_d, "c": ref("this#2")},
    ]
    merged_oracle = cyclic_set_oracle(merged)
    # Split final AB and CD use the original independently computed sets.
    dump(ORACLE_DIR / "merge-split-four.yaml", {
        "schema": "blue-language-cyclic-oracle/1.0", "id": "merge-split-four",
        "initialComponents": [oracle_payload("ab", [("initial-ab", [ab_a, ab_b], ab_oracle)]), oracle_payload("cd", [("initial-cd", [cd_c, cd_d], cd_oracle)])],
        "merged": oracle_payload("merged", [("merged", merged, merged_oracle)]),
        "splitComponent": oracle_payload("split-cd", [("split-cd", [split_cd_c, cd_d], split_cd_oracle)]),
        "split": {"abMaster": ab_oracle.master_blue_id, "cdMaster": split_cd_oracle.master_blue_id},
    })

    # A10 / B attaches exact historical A5 while A already embeds B. Processor
    # markers are part of the source history from the outset: A is initialized
    # at epoch 5 and carries that one immutable marker through A6..A10.
    hist_b_pre = {"documentId": "history-b", "memberIdentity": "history-b", "observedA": 0, "contracts": {
        "embedded": {"type": ref(PE), "paths": ["/a"]},
        "source": {"type": ref(SEC), "subscriptionKey": "fixture", "eventKey": "fixture", "accept": True, "checkpointDomain": "domain", "logicalDeliveryKey": "attach-history"},
        "start": {"type": ref(SH), "channel": "source", "order": 0},
    }}
    hist_b0 = deepcopy(hist_b_pre)
    hist_b0.setdefault("contracts", {})["initialized"] = initialized_marker(hist_b_pre)
    hist_b0_id = direct_blue_id(hist_b0)
    hist_a5_pre = {"documentId": "history-a", "memberIdentity": "history-a", "counter": 5, "b": ref(hist_b0_id), "contracts": {"embedded": {"type": ref(PE), "paths": ["/b"]}}}
    hist_a_marker = initialized_marker(hist_a5_pre)
    a_epochs: dict[int, tuple[Any, str]] = {}
    for epoch in range(5, 11):
        doc = {"documentId": "history-a", "memberIdentity": "history-a", "counter": epoch, "b": ref(hist_b0_id), "contracts": {"embedded": {"type": ref(PE), "paths": ["/b"],}, "initialized": deepcopy(hist_a_marker)}}
        a_epochs[epoch] = (doc, direct_blue_id(doc))
    revisions = []
    for epoch in range(5, 10):
        before = a_epochs[epoch][1]; after_doc, after = a_epochs[epoch + 1]
        original_cause = external_cause(
            event(f"HISTORY-A-{epoch + 1}"),
            [epoch + 1, "timeline-history-a", epoch + 1],
        )
        revisions.append({
            "documentId": "history-a", "fromEpoch": epoch, "toEpoch": epoch + 1,
            "beforeBlueId": before, "afterBlueId": after, "afterDocument": after_doc,
            "originalSourceCauseIdentity": original_cause["causeIdentity"],
        })
    dump(ORACLE_DIR / "history-a10-b-attaches-a5.yaml", {
        "schema": "blue-language-cyclic-oracle/1.0", "id": "history-a10-b-attaches-a5",
        "aEpochs": {str(k): {"blueId": v[1], "document": v[0]} for k, v in a_epochs.items()},
        "bInitial": {"blueId": hist_b0_id, "document": hist_b0},
        "revisions": revisions,
    })

    return locals()


def base_fixture(fid: str, vectors: list[str], category: str, operation: str, description: str, oracle: str | None, input_value: dict[str, Any], expected: dict[str, Any]) -> dict[str, Any]:
    result = {
        "schema": "blue-contracts-closure-fixture/1.0", "id": fid, "vectors": vectors,
        "category": category, "operation": operation, "description": description,
        "releaseManifest": "../../release-manifest.yaml",
    }
    if oracle:
        result["oracle"] = f"../../oracles/{oracle}"
    result["input"] = input_value
    result["expected"] = expected
    return result


def empty_expected(status: str = "success", *, atomic: bool = True) -> dict[str, Any]:
    return {
        "attemptOutcome": "Complete", "status": status, "atomic": atomic,
        "workTrace": [], "tentativeFinalizations": [], "finalDocuments": {},
        "finalComponents": [], "finalOccurrences": [], "publicEvents": [],
    }


def generate() -> None:
    # Clear generated closure fixtures/oracles.
    for path in FIXTURE_DIR.glob("*.yaml"):
        path.unlink()
    for path in ORACLE_DIR.glob("*.yaml"):
        path.unlink()

    s = build_scenarios()
    event_ids = s["event_ids"]; events = s["events"]
    a0=s["a0"]; a0_id=s["a0_id"]; b0=s["b0"]; b0_id=s["b0_id"]
    finite_sources=s["finite_sources"]
    loop_oracle=s["loop_oracle"]; loop_mats=s["loop_mats"]; loop_a_source=s["loop_a_source"]; loop_b_source=s["loop_b_source"]
    simple_oracle=s["simple_oracle"]; self_oracle=s["self_oracle"]
    ab_oracle=s["ab_oracle"]; cd_oracle=s["cd_oracle"]; merged_oracle=s["merged_oracle"]; split_cd_oracle=s["split_cd_oracle"]
    a_epochs=s["a_epochs"]; hist_b0=s["hist_b0"]; hist_b0_id=s["hist_b0_id"]; revisions=s["revisions"]

    default_policy = policy()
    finite_initial_occ = occurrence("b", "/a", 1, "a", a0_id)
    finite_final_oracle = finite_sources[-1][2]
    finite_final_mats = materialize_cyclic_members(finite_sources[-1][1], finite_final_oracle)
    finite_components_initial = [component("ACYCLIC", 1, ["a"]), component("ACYCLIC", 1, ["b"])]
    finite_component_final = component("CYCLIC", 2, ["a", "b"], master=finite_final_oracle.master_blue_id, stage="a-finished")
    finite_scripts = {
        "a/start": {"patches": [{"op": "add", "path": "/b", "val": ref(b0_id)}], "events": [events["X"]]},
        "a/onLocalX": {"patches": [{"op": "replace", "path": "/localXSeen", "val": 1}]},
        "b/onX": {"patches": [{"op": "replace", "path": "/xHandled", "val": 1}], "events": [events["Y"]]},
        "a/onY": {"patches": [{"op": "replace", "path": "/result", "val": "done"}]},
    }
    finite_work = [
        work(0,"EXTERNAL_DELIVERY","a","source",event_id=event_ids["START-FINITE"],occurrence_ordinal=0,invocation="finite"),
        work(1,"TRIGGERED_EVENT","a","localX",event_id=event_ids["X"],occurrence_ordinal=0,invocation="finite"),
        work(2,"EMBEDDED_EVENT","b","fromA",event_id=event_ids["X"],occurrence_ordinal=0,invocation="finite"),
        work(3,"EMBEDDED_EVENT","a","fromB",event_id=event_ids["Y"],occurrence_ordinal=0,invocation="finite"),
    ]
    finite_input = {
        "graphGeneration":1, "cause":external_cause(events["START-FINITE"],[100,"timeline-a",1]),
        "documents":{
            "a":document_record("a",a0_id,a0,public_root=True,epoch=0,component_generation=1),
            "b":document_record("b",b0_id,b0,epoch=0,component_generation=1),
        },
        "occurrences":[finite_initial_occ, occurrence("a", "/b", 1, "b", b0_id, active=False)], "components":finite_components_initial,
        "directDeliveries":[direct_delivery("a")], "gasPolicy":default_policy,
        "runtime":{"handlers":finite_scripts},
    }
    finite_expected = {
        "attemptOutcome":"Complete","status":"success","atomic":True,
        "workTrace":finite_work,
        "tentativeFinalizations":[finalize(i,name,o,["a","b"]) for i,(name,_docs,o) in enumerate(finite_sources)],
        "finalGraphGeneration":2,
        "finalDocuments":{
            "a":document_record("a",finite_final_oracle.member_ids_in_source_order()[0],finite_final_mats[0],public_root=True,epoch=1,component_generation=2),
            "b":document_record("b",finite_final_oracle.member_ids_in_source_order()[1],finite_final_mats[1],epoch=1,component_generation=2),
        },
        "finalComponents":[finite_component_final], "finalOccurrences":[
            occurrence("a","/b",1,"b",finite_final_oracle.member_ids_in_source_order()[1]),
            occurrence("b","/a",1,"a",finite_final_oracle.member_ids_in_source_order()[0]),
        ], "publicEvents":[], "checkpointsCommitted":True, "rollbackToInput":False,
    }
    dump(FIXTURE_DIR/"c-clo-02-dynamic-finite-cycle.yaml", base_fixture(
        "c-clo-02-dynamic-finite-cycle",["C-CLO-02","C-CLO-03","C-CLO-29","C-CLO-30","C-EVO-13"],"cycle","process-closure",
        "A direct work adds B through an ordinary patch; the existing B->A edge becomes A<->B. A handles its own X, B handles X and emits Y, and A handles Y. Every identity-affecting transition re-finalizes the complete component before later work observes it.",
        "finite-dynamic-a-b.yaml",finite_input,finite_expected))

    # Static admission fixture.
    simple_ids=simple_oracle.member_ids_in_source_order(); simple_mats=materialize_cyclic_members([s["simple_a"],s["simple_b"]],simple_oracle)
    simple_occ=[occurrence("simple-a","/b",1,"simple-b",simple_ids[1]),occurrence("simple-b","/a",1,"simple-a",simple_ids[0])]
    simple_docs={
        "simple-a":document_record("simple-a",simple_ids[0],simple_mats[0],initialized=False,public_root=True,component_generation=1),
        "simple-b":document_record("simple-b",simple_ids[1],simple_mats[1],initialized=False,component_generation=1),
    }
    simple_comp=component("CYCLIC",1,["simple-a","simple-b"],master=simple_oracle.master_blue_id,stage="initial")
    admit_expected={"attemptOutcome":"Complete","status":"success","atomic":True,"workTrace":[work(0,"INITIALIZATION","simple-a","lifecycle",invocation="admit"),work(1,"INITIALIZATION","simple-b","lifecycle",invocation="admit")],"tentativeFinalizations":[finalize(0,"initial",simple_oracle,["simple-a","simple-b"])],"finalGraphGeneration":1,"finalDocuments":deepcopy(simple_docs),"finalComponents":[simple_comp],"finalOccurrences":simple_occ,"publicEvents":[],"checkpointsCommitted":False,"rollbackToInput":False}
    for d in admit_expected["finalDocuments"].values(): d["initialized"]=True
    admit_input={"graphGeneration":1,"cause":admission_cause("TOP_LEVEL_ADMISSION","static-cycle"),"documents":simple_docs,"occurrences":simple_occ,"components":[simple_comp],"directDeliveries":[],"gasPolicy":default_policy,"runtime":{"handlers":{},"initializationHandlers":{}}}
    dump(FIXTURE_DIR/"c-clo-01-static-cycle-admission.yaml",base_fixture("c-clo-01-static-cycle-admission",["C-CLO-01","C-CLO-21","C-CLO-30"],"admission","admit-closure","Static two-member cyclic closure admission uses a complete real Language cyclic proof, zero external deliveries, and no fabricated Timeline Entry.","static-and-self-cycle.yaml",admit_input,admit_expected))

    # Infinite loop with concise exact override.
    loop_ids=loop_oracle.member_ids_in_source_order()
    loop_occ=[occurrence("loop-a","/b",1,"loop-b",loop_ids[1]),occurrence("loop-b","/a",1,"loop-a",loop_ids[0])]
    loop_docs={"loop-a":document_record("loop-a",loop_ids[0],loop_mats[0],initialized=True,public_root=True,component_generation=1),"loop-b":document_record("loop-b",loop_ids[1],loop_mats[1],initialized=True,component_generation=1)}
    loop_comp=component("CYCLIC",1,["loop-a","loop-b"],master=loop_oracle.master_blue_id,stage="initial")
    trace,total,rejected_counter,accepted=gas_trace_loop(816)
    loop_work=[work(0,"EXTERNAL_DELIVERY","loop-a","source",event_id=event_ids["START-LOOP"],occurrence_ordinal=0,invocation="loop")]
    for i in range(1,accepted):
        target="loop-b" if i%2==1 else "loop-a"; channel="fromA" if target=="loop-b" else "fromB"
        loop_work.append(work(i,"EMBEDDED_EVENT",target,channel,event_id=event_ids["LOOP"],occurrence_ordinal=i-1,invocation="loop"))
    rejected_target="loop-b" if accepted%2==1 else "loop-a"; rejected_channel="fromA" if rejected_target=="loop-b" else "fromB"
    loop_rejected=work(accepted,"EMBEDDED_EVENT",rejected_target,rejected_channel,event_id=event_ids["LOOP"],occurrence_ordinal=accepted-1,invocation="loop")
    loop_policy=policy(816,default=False,label="fixture-loop-816")
    loop_input={"graphGeneration":1,"cause":external_cause(events["START-LOOP"],[200,"timeline-loop",1]),"documents":loop_docs,"occurrences":loop_occ,"components":[loop_comp],"directDeliveries":[direct_delivery("loop-a",key="loop")],"gasPolicy":loop_policy,"runtime":{"handlers":{"loop-a/start":{"events":[events["LOOP"]]},"loop-b/onLoopFromA":{"events":[events["LOOP"]]},"loop-a/onLoopFromB":{"events":[events["LOOP"]]}}}}
    loop_expected={"attemptOutcome":"Complete","status":"gas-limit-exceeded","diagnostic":"GasLimitExceeded","atomic":True,"workTrace":loop_work,"rejectedWork":loop_rejected,"tentativeFinalizations":[],"finalGraphGeneration":1,"finalDocuments":loop_docs,"finalComponents":[loop_comp],"finalOccurrences":loop_occ,"publicEvents":[],"checkpointsCommitted":False,"rollbackToInput":True,"totalGas":total,"gasTrace":trace,"gasTraceIdentity":gas_trace_identity(trace)}
    dump(FIXTURE_DIR/"c-clo-04-same-event-gas-loop.yaml",base_fixture("c-clo-04-same-event-gas-loop",["C-CLO-04","C-CLO-25","C-CLO-30"],"gas","process-closure","A and B emit the same exact LOOP value back and forth. One exact lowered policy stops the next canonical charge, preserves the exact trace prefix, and rolls back the full component.","infinite-loop-a-b.yaml",loop_input,loop_expected))

    # Default policy loop (compact expected trace via identity only; full trace still generated).
    dtrace,dtotal,drej,daccepted=gas_trace_loop(100000)
    def_expected=deepcopy(loop_expected);def_expected["workTrace"]=[];def_expected["rejectedWork"]=work(daccepted,"EMBEDDED_EVENT","loop-b" if daccepted%2==1 else "loop-a","fromA" if daccepted%2==1 else "fromB",event_id=event_ids["LOOP"],occurrence_ordinal=daccepted-1,invocation="default-loop");def_expected["totalGas"]=dtotal;def_expected["gasTrace"]=dtrace;def_expected["gasTraceIdentity"]=gas_trace_identity(dtrace)
    def_input=deepcopy(loop_input);def_input["gasPolicy"]=default_policy
    dump(FIXTURE_DIR/"c-clo-04-default-policy-loop.yaml",base_fixture("c-clo-04-default-policy-loop",["C-CLO-04","C-CLO-25"],"gas","process-closure","No document or host policy is authored. The exact finite Contracts 1.0 default still terminates the cyclic loop deterministically.","infinite-loop-a-b.yaml",def_input,def_expected))

    # Same entry directly targets both members.
    both_input=deepcopy(loop_input);both_input["cause"]=external_cause(events["DIRECT-BOTH"],[210,"timeline-both",1]);both_input["directDeliveries"]=[direct_delivery("loop-a",key="a",order=0),direct_delivery("loop-b",key="b",order=1)];both_input["gasPolicy"]=default_policy;both_input["runtime"]={"handlers":{}}
    both_expected=empty_expected();both_expected.update({"finalGraphGeneration":1,"finalDocuments":loop_docs,"finalComponents":[loop_comp],"finalOccurrences":loop_occ,"workTrace":[work(0,"EXTERNAL_DELIVERY","loop-a","source",event_id=direct_blue_id(events["DIRECT-BOTH"]),invocation="both"),work(1,"EXTERNAL_DELIVERY","loop-b","source",event_id=direct_blue_id(events["DIRECT-BOTH"]),invocation="both")]})
    dump(FIXTURE_DIR/"c-clo-05-direct-both-members.yaml",base_fixture("c-clo-05-direct-both-members",["C-CLO-05"],"ordering","process-closure","One entry directly targets both members; stable DocumentId order controls direct seeds independently of admission or map order.","infinite-loop-a-b.yaml",both_input,both_expected))

    # Duplicate event occurrences.
    dup_input=deepcopy(loop_input);dup_input["gasPolicy"]=default_policy;dup_input["runtime"]={"handlers":{"loop-a/start":{"events":[events["LOOP"],events["LOOP"]]},"loop-b/onLoopFromA":{}}}
    dup_expected=empty_expected();dup_expected.update({"finalGraphGeneration":1,"finalDocuments":loop_docs,"finalComponents":[loop_comp],"finalOccurrences":loop_occ,"workTrace":[work(0,"EXTERNAL_DELIVERY","loop-a","source",event_id=event_ids["START-LOOP"],invocation="dup"),work(1,"EMBEDDED_EVENT","loop-b","fromA",event_id=event_ids["LOOP"],occurrence_ordinal=0,invocation="dup"),work(2,"EMBEDDED_EVENT","loop-b","fromA",event_id=event_ids["LOOP"],occurrence_ordinal=1,invocation="dup")]})
    dump(FIXTURE_DIR/"c-clo-06-duplicate-event-occurrences.yaml",base_fixture("c-clo-06-duplicate-event-occurrences",["C-CLO-06"],"ordering","process-closure","Two equal event values emitted by one Handler remain two exact occurrences with distinct ordinals.","infinite-loop-a-b.yaml",dup_input,dup_expected))

    # Self-cycle fixture.
    self_ids=self_oracle.member_ids_in_source_order();self_mats=materialize_cyclic_members([s["self_source"]],self_oracle);self_occ=[occurrence("self","/self",1,"self",self_ids[0])];self_comp=component("CYCLIC",1,["self"],master=self_oracle.master_blue_id,stage="initial");self_docs={"self":document_record("self",self_ids[0],self_mats[0],initialized=True,public_root=True,component_generation=1)}
    self_input={"graphGeneration":1,"cause":admission_cause("TOP_LEVEL_ADMISSION","self-cycle"),"documents":self_docs,"occurrences":self_occ,"components":[self_comp],"directDeliveries":[],"gasPolicy":default_policy,"runtime":{"handlers":{}}}
    self_expected=empty_expected();self_expected.update({"finalGraphGeneration":1,"finalDocuments":self_docs,"finalComponents":[self_comp],"finalOccurrences":self_occ})
    dump(FIXTURE_DIR/"c-clo-07-self-cycle.yaml",base_fixture("c-clo-07-self-cycle",["C-CLO-07","C-CLO-30"],"cycle","admit-closure","A one-member self-cycle uses the same complete-set proof and atomic finalization rules.","static-and-self-cycle.yaml",self_input,self_expected))

    # Cycle during initialization: start acyclic B->A; A init patch adds B.
    init_input=deepcopy(finite_input);init_input["cause"]=admission_cause("TOP_LEVEL_ADMISSION","cycle-during-init");init_input["directDeliveries"]=[];init_input["documents"]["a"]["initialized"]=False;init_input["documents"]["b"]["initialized"]=False;init_input["runtime"]={"handlers":{},"initializationHandlers":{"a/lifecycle":{"patches":[{"op":"add","path":"/b","val":ref(b0_id)}]}}}
    init_expected=deepcopy(finite_expected);init_expected["workTrace"]=[work(0,"INITIALIZATION","a","lifecycle",invocation="init-cycle"),work(1,"INITIALIZATION","b","lifecycle",invocation="init-cycle")];init_expected["tentativeFinalizations"]=[finalize(0,"cycle-formed",finite_sources[0][2],["a","b"])];init_expected["finalDocuments"]={"a":document_record("a",finite_sources[0][2].member_ids_in_source_order()[0],materialize_cyclic_members(finite_sources[0][1],finite_sources[0][2])[0],initialized=True,public_root=True,component_generation=2),"b":document_record("b",finite_sources[0][2].member_ids_in_source_order()[1],materialize_cyclic_members(finite_sources[0][1],finite_sources[0][2])[1],initialized=True,component_generation=2)};init_expected["finalComponents"]=[component("CYCLIC",2,["a","b"],master=finite_sources[0][2].master_blue_id,stage="cycle-formed")];init_expected["finalOccurrences"]=[occurrence("a","/b",1,"b",finite_sources[0][2].member_ids_in_source_order()[1]),occurrence("b","/a",1,"a",finite_sources[0][2].member_ids_in_source_order()[0])]
    dump(FIXTURE_DIR/"c-clo-08-cycle-during-initialization.yaml",base_fixture("c-clo-08-cycle-during-initialization",["C-CLO-08","C-CLO-21"],"initialization","admit-closure","Initialization uses an ordinary patch to add the reciprocal edge, reclassifies the closure without replaying completed initialization, and publishes no member early.","finite-dynamic-a-b.yaml",init_input,init_expected))

    # Merge/split structure fixtures use exact independent oracles.
    ab_ids=ab_oracle.member_ids_in_source_order();cd_ids=cd_oracle.member_ids_in_source_order();merged_ids=merged_oracle.member_ids_in_source_order()
    ab_mats=materialize_cyclic_members([s["ab_a"],s["ab_b"]],ab_oracle);cd_mats=materialize_cyclic_members([s["cd_c"],s["cd_d"]],cd_oracle);merged_mats=materialize_cyclic_members(s["merged"],merged_oracle);split_cd_mats=materialize_cyclic_members([s["split_cd_c"],s["cd_d"]],split_cd_oracle)
    merge_docs={"ma":document_record("ma",ab_ids[0],ab_mats[0],initialized=True,public_root=True,component_generation=1),"mb":document_record("mb",ab_ids[1],ab_mats[1],initialized=True,component_generation=1),"mc":document_record("mc",cd_ids[0],cd_mats[0],initialized=True,component_generation=1),"md":document_record("md",cd_ids[1],cd_mats[1],initialized=True,component_generation=1)}
    merge_occ=[occurrence("ma","/b",1,"mb",ab_ids[1]),occurrence("mb","/a",1,"ma",ab_ids[0]),occurrence("mc","/d",1,"md",cd_ids[1]),occurrence("md","/c",1,"mc",cd_ids[0]),occurrence("mc","/a",1,"ma",ab_ids[0])]
    merge_components=[component("CYCLIC",1,["ma","mb"],master=ab_oracle.master_blue_id,stage="initial-ab"),component("CYCLIC",1,["mc","md"],master=cd_oracle.master_blue_id,stage="initial-cd")]
    merge_input={"graphGeneration":1,"cause":external_cause(events["MERGE"],[230,"timeline-merge",1]),"documents":merge_docs,"occurrences":merge_occ+[occurrence("ma","/c",1,"mc",cd_ids[0],active=False)],"components":merge_components,"directDeliveries":[direct_delivery("ma")],"gasPolicy":default_policy,"runtime":{"handlers":{"ma/start":{"patches":[{"op":"add","path":"/c","val":ref(cd_ids[0])}]}}}}
    merge_expected=empty_expected();merge_expected.update({"finalGraphGeneration":2,"finalDocuments":{k:document_record(k,merged_ids[i],merged_mats[i],initialized=True,public_root=(k=="ma"),epoch=1,component_generation=2) for i,k in enumerate(["ma","mb","mc","md"])},"finalComponents":[component("CYCLIC",2,["ma","mb","mc","md"],master=merged_oracle.master_blue_id,stage="merged")],"finalOccurrences":[occurrence("ma","/b",1,"mb",merged_ids[1]),occurrence("ma","/c",1,"mc",merged_ids[2]),occurrence("mb","/a",1,"ma",merged_ids[0]),occurrence("mc","/d",1,"md",merged_ids[3]),occurrence("mc","/a",1,"ma",merged_ids[0]),occurrence("md","/c",1,"mc",merged_ids[2])],"tentativeFinalizations":[finalize(0,"merged",merged_oracle,["ma","mb","mc","md"])],"workTrace":[work(0,"EXTERNAL_DELIVERY","ma","source",event_id=event_ids["MERGE"],invocation="merge")]})
    dump(FIXTURE_DIR/"c-clo-09-merge-two-cycles.yaml",base_fixture("c-clo-09-merge-two-cycles",["C-CLO-09","C-CLO-27","C-CLO-30"],"dynamic-graph","process-closure","An ordinary patch adds the reciprocal cross-component edge and merges two verified cyclic components into one exact four-member component.","merge-split-four.yaml",merge_input,merge_expected))

    split_input=deepcopy(merge_expected); # construct actual input from merged state
    split_input={"graphGeneration":2,"cause":external_cause(events["SPLIT"],[240,"timeline-split",1]),"documents":merge_expected["finalDocuments"],"occurrences":merge_expected["finalOccurrences"],"components":merge_expected["finalComponents"],"directDeliveries":[direct_delivery("ma",order=0),direct_delivery("mc",order=1)],"gasPolicy":default_policy,"runtime":{"handlers":{"ma/start":{"patches":[{"op":"remove","path":"/c"}]},"mc/start":{"patches":[{"op":"remove","path":"/a"}]}}}}
    split_cd_ids=split_cd_oracle.member_ids_in_source_order()
    split_docs={"ma":document_record("ma",ab_ids[0],ab_mats[0],initialized=True,public_root=True,epoch=2,component_generation=3),"mb":document_record("mb",ab_ids[1],ab_mats[1],initialized=True,epoch=2,component_generation=3),"mc":document_record("mc",split_cd_ids[0],split_cd_mats[0],initialized=True,epoch=2,component_generation=3),"md":document_record("md",split_cd_ids[1],split_cd_mats[1],initialized=True,epoch=2,component_generation=3)}
    split_components=[component("CYCLIC",3,["ma","mb"],master=ab_oracle.master_blue_id,stage="initial-ab"),component("CYCLIC",3,["mc","md"],master=split_cd_oracle.master_blue_id,stage="split-cd")]
    split_occ=[occurrence("ma","/b",1,"mb",ab_ids[1]),occurrence("mb","/a",1,"ma",ab_ids[0]),occurrence("mc","/d",1,"md",split_cd_ids[1]),occurrence("md","/c",1,"mc",split_cd_ids[0]),occurrence("ma","/c",2,"mc",split_cd_ids[0],active=False),occurrence("mc","/a",2,"ma",ab_ids[0],active=False)]
    split_expected=empty_expected();split_expected.update({"finalGraphGeneration":3,"finalDocuments":split_docs,"finalComponents":split_components,"finalOccurrences":split_occ,"workTrace":[work(0,"EXTERNAL_DELIVERY","ma","source",event_id=event_ids["SPLIT"],invocation="split"),work(1,"EXTERNAL_DELIVERY","mc","source",event_id=event_ids["SPLIT"],invocation="split")],"tentativeFinalizations":[finalize(0,"initial-ab",ab_oracle,["ma","mb"]),finalize(1,"split-cd",split_cd_oracle,["mc","md"])]})
    dump(FIXTURE_DIR/"c-clo-11-split-into-two-cycles.yaml",base_fixture("c-clo-11-split-into-two-cycles",["C-CLO-11","C-CLO-28","C-CLO-30","C-EVO-12"],"dynamic-graph","process-closure","Two ordinary edge removals split one four-member component into two exact cyclic components; the result contains both final components.","merge-split-four.yaml",split_input,split_expected))

    # Split simple AB to acyclic singletons.
    split2_input=deepcopy(admit_input);split2_input["cause"]=external_cause(events["SPLIT"],[241,"timeline-split",1]);split2_input["documents"]={k:{**v,"initialized":True} for k,v in simple_docs.items()};split2_input["directDeliveries"]=[direct_delivery("simple-a",order=0),direct_delivery("simple-b",order=1)];split2_input["runtime"]={"handlers":{"simple-a/start":{"patches":[{"op":"remove","path":"/b"}]},"simple-b/start":{"patches":[{"op":"remove","path":"/a"}]}}}
    a_single={k:v for k,v in simple_mats[0].items() if k!='b'};b_single={k:v for k,v in simple_mats[1].items() if k!='a'};a_sid=direct_blue_id(a_single);b_sid=direct_blue_id(b_single)
    split2_expected=empty_expected();split2_expected.update({"finalGraphGeneration":2,"finalDocuments":{"simple-a":document_record("simple-a",a_sid,a_single,initialized=True,public_root=True,epoch=1,component_generation=2),"simple-b":document_record("simple-b",b_sid,b_single,initialized=True,epoch=1,component_generation=2)},"finalComponents":[component("ACYCLIC",2,["simple-a"]),component("ACYCLIC",2,["simple-b"])],"finalOccurrences":[occurrence("simple-a","/b",2,"simple-b",b_sid,active=False),occurrence("simple-b","/a",2,"simple-a",a_sid,active=False)],"workTrace":[work(0,"EXTERNAL_DELIVERY","simple-a","source",event_id=event_ids["SPLIT"],invocation="split-single"),work(1,"EXTERNAL_DELIVERY","simple-b","source",event_id=event_ids["SPLIT"],invocation="split-single")]})
    dump(FIXTURE_DIR/"c-clo-10-split-to-singletons.yaml",base_fixture("c-clo-10-split-to-singletons",["C-CLO-10","C-CLO-28"],"dynamic-graph","process-closure","Removing both reciprocal edges dissolves the cyclic component and returns two ordinary acyclic document identities with no residual MASTER.","static-and-self-cycle.yaml",split2_input,split2_expected))

    # Frozen edge behavior pair.
    frozen_before=deepcopy(finite_input);frozen_before["cause"]=external_cause(events["SPLIT"],[250,"timeline-frozen",1]);frozen_before["runtime"]={"handlers":{"a/start":{"events":[events["X"]],"patches":[{"op":"remove","path":"/b"}]},"b/onX":{}}}
    frozen_expected=empty_expected();frozen_expected.update({"finalGraphGeneration":2,"finalDocuments":{},"finalComponents":[],"finalOccurrences":[finite_initial_occ],"workTrace":[work(0,"EXTERNAL_DELIVERY","a","source",event_id=event_ids["SPLIT"],invocation="frozen-remove"),work(1,"EMBEDDED_EVENT","b","fromA",event_id=event_ids["X"],occurrence_ordinal=0,invocation="frozen-remove")]})
    dump(FIXTURE_DIR/"c-clo-12-frozen-edge-removal.yaml",base_fixture("c-clo-12-frozen-edge-removal",["C-CLO-12","C-EVO-11"],"ordering","process-closure","An event occurrence created before edge retirement retains its frozen target exactly once; retirement affects only later occurrences.",None,frozen_before,frozen_expected))
    frozen_add=deepcopy(finite_input);frozen_add["runtime"]={"handlers":{"a/start":{"events":[events["X"]],"patches":[{"op":"add","path":"/b","val":ref(b0_id)}]},"b/onX":{}}}
    add_expected=deepcopy(finite_expected);add_expected["workTrace"]=[work(0,"EXTERNAL_DELIVERY","a","source",event_id=event_ids["START-FINITE"],invocation="frozen-add"),work(1,"TRIGGERED_EVENT","a","localX",event_id=event_ids["X"],occurrence_ordinal=0,invocation="frozen-add")];add_expected["tentativeFinalizations"]=[finalize(0,"cycle-formed",finite_sources[0][2],["a","b"])]
    dump(FIXTURE_DIR/"c-clo-13-frozen-edge-addition.yaml",base_fixture("c-clo-13-frozen-edge-addition",["C-CLO-13"],"ordering","process-closure","An event emitted before the reciprocal edge is activated does not retroactively use that edge; later caused occurrences use the new graph.","finite-dynamic-a-b.yaml",frozen_add,add_expected))

    # Proof failures and ambiguity.
    bad_input=deepcopy(admit_input);bad_input["components"][0]["masterBlueId"]="11111111111111111111111111111112";bad_expected=empty_expected("invalid-processing-document");bad_expected.update({"diagnostic":"CyclicSetProofInvalid","finalGraphGeneration":1,"finalDocuments":simple_docs,"finalComponents":[simple_comp],"finalOccurrences":simple_occ,"rollbackToInput":True})
    dump(FIXTURE_DIR/"c-clo-14-invalid-cyclic-proof.yaml",base_fixture("c-clo-14-invalid-cyclic-proof",["C-CLO-14"],"identity","admit-closure","An apparently complete but mismatched cyclic proof is independently verified and rejected before initialization or Handler work.","static-and-self-cycle.yaml",bad_input,bad_expected))
    amb_a={"documentId":"amb-a","same":True,"other":ref("this#1")};amb_b={"documentId":"amb-b","same":True,"other":ref("this#0")}
    # Remove documentId for the identity input to demonstrate real ambiguity; DocumentId remains platform evidence.
    amb_calc=[{"same":True,"other":ref("this#1")},{"same":True,"other":ref("this#0")}]
    try:
        cyclic_set_oracle(amb_calc);ambiguous=False
    except ValueError:
        ambiguous=True
    dump(ORACLE_DIR/"ambiguous-preliminary-members.yaml",{"schema":"blue-language-cyclic-oracle/1.0","id":"ambiguous-preliminary-members","sourceDocumentsWithThisReferences":amb_calc,"expectedDiagnostic":"CyclicPreliminaryMemberAmbiguous","independentlyDetected":ambiguous})
    amb_id=direct_blue_id({"same":True});amb_docs={"amb-a":document_record("amb-a",amb_id,{"same":True},initialized=False,public_root=True)}
    amb_input={"graphGeneration":1,"cause":admission_cause("TOP_LEVEL_ADMISSION","ambiguous"),"documents":amb_docs,"occurrences":[],"components":[component("ACYCLIC",0,["amb-a"])],"directDeliveries":[],"gasPolicy":default_policy,"runtime":{"handlers":{}}}
    amb_expected=empty_expected("invalid-processing-document");amb_expected.update({"diagnostic":"CyclicPreliminaryMemberAmbiguous","finalGraphGeneration":1,"finalDocuments":amb_docs,"finalComponents":amb_input["components"],"finalOccurrences":[],"rollbackToInput":True})
    dump(FIXTURE_DIR/"c-clo-15-ambiguous-preliminary-members.yaml",base_fixture("c-clo-15-ambiguous-preliminary-members",["C-CLO-15"],"identity","admit-closure","The runner derives duplicate preliminary cyclic identities from exact member bodies; the input does not prelabel the proof as invalid.","ambiguous-preliminary-members.yaml",amb_input,amb_expected))

    # Limits at and above; generated limitProbe is semantic fixture control, not Blue graph control.
    limit_base=deepcopy(admit_input);limit_base["limitProbe"]={"limit":"cyclicMembersPerComponent","value":128}
    lim_expected=deepcopy(admit_expected)
    dump(FIXTURE_DIR/"c-clo-16-limit-at-bound.yaml",base_fixture("c-clo-16-limit-at-bound",["C-CLO-16"],"limits","admit-closure","The exact configured cyclic member bound is accepted; generator-backed members are verified by the fixture runner.","static-and-self-cycle.yaml",limit_base,lim_expected))
    limit_over=deepcopy(limit_base);limit_over["limitProbe"]["value"]=129
    over_expected=empty_expected("portable-limit-exceeded");over_expected.update({"diagnostic":"CyclicComponentMemberLimitExceeded","finalGraphGeneration":1,"finalDocuments":simple_docs,"finalComponents":[simple_comp],"finalOccurrences":simple_occ,"rollbackToInput":True})
    dump(FIXTURE_DIR/"c-clo-17-limit-above-bound.yaml",base_fixture("c-clo-17-limit-above-bound",["C-CLO-17"],"limits","admit-closure","The first member count above the exact configured bound is rejected before member execution.","static-and-self-cycle.yaml",limit_over,over_expected))

    # Locality.
    # Both cyclic members are exact invocation documents, so admitting this
    # closure must not fetch them again through the external node provider.
    # Locality is witnessed by the closed unrelated-document counter instead.
    loc_input=deepcopy(admit_input);loc_input["unrelatedDocumentCount"]=1000
    loc_expected=deepcopy(admit_expected);loc_expected["unrelatedDocumentsOpened"]=0
    dump(FIXTURE_DIR/"c-clo-18-locality-1000-unrelated.yaml",base_fixture("c-clo-18-locality-1000-unrelated",["C-CLO-18"],"locality","admit-closure","A two-member cyclic component among 1,000 unrelated documents opens only the exact component and required containing spine.","static-and-self-cycle.yaml",loc_input,loc_expected))

    # Public events under outer root and late rollback (structural exact scenario).
    outer={"documentId":"outer","memberIdentity":"outer","inner":ref(simple_ids[0]),"published":False,"contracts":{"embedded":{"type":ref(PE),"paths":["/inner"]}}};outer_id=direct_blue_id(outer);outer_occ=occurrence("outer","/inner",1,"simple-a",simple_ids[0]);outer_docs={**simple_docs,"outer":document_record("outer",outer_id,outer,initialized=True,public_root=True,component_generation=1)};outer_comps=[simple_comp,component("ACYCLIC",1,["outer"])]
    pub_input={"graphGeneration":1,"cause":external_cause(events["INIT"],[260,"timeline-outer",1]),"documents":outer_docs,"occurrences":simple_occ+[outer_occ],"components":outer_comps,"directDeliveries":[direct_delivery("simple-a")],"gasPolicy":default_policy,"runtime":{"handlers":{"simple-a/start":{"events":[events["X"]]}}}}
    pub_expected=empty_expected();pub_expected.update({"finalGraphGeneration":1,"finalDocuments":outer_docs,"finalComponents":outer_comps,"finalOccurrences":simple_occ+[outer_occ],"publicEvents":[],"workTrace":[work(0,"EXTERNAL_DELIVERY","simple-a","source",event_id=event_ids["INIT"],invocation="public-boundary")]})
    dump(FIXTURE_DIR/"c-clo-19-public-event-boundary.yaml",base_fixture("c-clo-19-public-event-boundary",["C-CLO-19"],"public-events","process-closure","An inner cyclic member emission remains internal; only an explicit emission by the declared outer public Root may appear in publicEvents.","static-and-self-cycle.yaml",pub_input,pub_expected))
    fail_input=deepcopy(pub_input);fail_input["runtime"]={"handlers":{"outer/finalValidation":{"fail":"outer-failure-after-inner-work"}}}
    fail_expected=deepcopy(pub_expected);fail_expected["status"]="runtime-fatal";fail_expected["diagnostic"]="RuntimeExecutionFailure";fail_expected["rollbackToInput"]=True;fail_expected["checkpointsCommitted"]=False;fail_expected["finalDocuments"]=outer_docs
    dump(FIXTURE_DIR/"c-clo-20-late-outer-failure.yaml",base_fixture("c-clo-20-late-outer-failure",["C-CLO-20"],"atomicity","process-closure","A deterministic failure after all member reactions but before closure publication rolls back every member, containing Root, checkpoint, graph change, and public event.","static-and-self-cycle.yaml",fail_input,fail_expected))

    # Explicit admission fixture already C-CLO-21; add dedicated no-delivery case.
    no_delivery=deepcopy(admit_input);no_delivery["cause"]=admission_cause("EMBEDDED_ACTIVATION","zero-direct");
    dump(FIXTURE_DIR/"c-clo-21-admission-zero-direct.yaml",base_fixture("c-clo-21-admission-zero-direct",["C-CLO-21"],"admission","admit-closure","Admission and initialization are first-class processing causes and permit zero direct external deliveries without a synthetic event.","static-and-self-cycle.yaml",no_delivery,admit_expected))

    # Historical A10 / A5 feeder sequence. The missing-resource attempt and its
    # retry have byte-identical normative inputs; only provider availability in
    # harness evidence differs. Every later A(n)->A(n+1) step is its own cause,
    # gas ledger, rollback boundary and commit.
    hist_a10_doc,hist_a10_id=a_epochs[10];hist_a5_doc,hist_a5_id=a_epochs[5]
    hist_occ_a_b=occurrence("history-a","/b",1,"history-b",hist_b0_id)
    hist_occ_b_a=occurrence("history-b","/a",1,"history-a",hist_a5_id,active=False,pending_epoch=5)
    hist_initial_docs={"history-a":document_record("history-a",hist_a10_id,hist_a10_doc,initialized=True,public_root=True,epoch=10,component_generation=1),"history-b":document_record("history-b",hist_b0_id,hist_b0,initialized=True,epoch=0,component_generation=1)}
    hist_base={"graphGeneration":1,"cause":external_cause(events["ATTACH-HISTORICAL"],[270,"timeline-history",1]),"documents":hist_initial_docs,"occurrences":[hist_occ_a_b,hist_occ_b_a],"components":[component("ACYCLIC",1,["history-b"]),component("ACYCLIC",1,["history-a"])],"directDeliveries":[direct_delivery("history-b")],"gasPolicy":default_policy,"runtime":{"handlers":{"history-b/start":{"patches":[{"op":"add","path":"/a","val":ref(hist_a5_id)}]}}}}
    exact_demand_value={"kind":"EXACT_NODE","logicalCauseIdentity":None,"inputClosureIdentity":None,"inputGraphGeneration":None,"sourceDocumentId":"blue-contracts/exact-node-provider","sourcePath":"/","processEmbeddedDeclarationIdentity":None,"suppliedValueBlueId":hist_a5_id,"demandOrdinal":None}
    exact_demand={"kind":"EXACT_NODE","demandIdentity":sha_id("blue-contracts-closure-resource-demand/1.0",exact_demand_value),"sourceDocumentId":"blue-contracts/exact-node-provider","sourcePath":"/","suppliedValueBlueId":hist_a5_id,"blueId":hist_a5_id,"logicalPath":"/"}
    needs=deepcopy(hist_base);needs_expected=empty_expected("success");needs_expected.pop("status", None);needs_expected.update({"attemptOutcome":"NeedsResources","requiredBlueIds":[hist_a5_id],"resourceDemands":[exact_demand],"finalGraphGeneration":1,"finalDocuments":hist_initial_docs,"finalComponents":hist_base["components"],"finalOccurrences":[hist_occ_a_b,hist_occ_b_a],"rollbackToInput":True})
    dump(FIXTURE_DIR/"c-clo-22-a10-attach-a5-needs-resources.yaml",base_fixture("c-clo-22-a10-attach-a5-needs-resources",["C-CLO-22"],"history","process-closure","B attempts to attach exact historical A5 while authoritative managed A is at epoch 10. The exact A5 node is unavailable, so the processor requests only A5 and returns the unchanged invocation state.","history-a10-b-attaches-a5.yaml",needs,needs_expected))

    attach_input=deepcopy(hist_base)
    attach_input["availableDocuments"]={hist_a5_id:deepcopy(hist_a5_doc)}
    attached_b=deepcopy(hist_b0);attached_b["a"]=ref(hist_a5_id)
    source_channel=attached_b["contracts"]["source"]
    attached_b.setdefault("contracts", {})["checkpoint"]={
        "type":ref(CHECKPOINT_MARKER),
        "entries":{"source":{
            "domain":ref(checkpoint_domain_blue_id(source_channel)),
            "subject":ref(event_ids["ATTACH-HISTORICAL"]),
        }},
    }
    attached_b_id=direct_blue_id(attached_b)
    attached_a=deepcopy(hist_a10_doc);attached_a["b"]=ref(attached_b_id)
    attached_a_id=direct_blue_id(attached_a)
    attached_occurrences=[
        occurrence("history-a","/b",1,"history-b",attached_b_id),
        deepcopy(hist_occ_b_a),
    ]
    attached_documents={
        "history-a":document_record("history-a",attached_a_id,attached_a,initialized=True,public_root=True,epoch=10,component_generation=1),
        "history-b":document_record("history-b",attached_b_id,attached_b,initialized=True,epoch=1,component_generation=1),
    }
    attached_components=[component("ACYCLIC",1,["history-b"]),component("ACYCLIC",1,["history-a"])]
    attach_expected=empty_expected();attach_expected.update({
        "finalGraphGeneration":1,
        "finalDocuments":attached_documents,
        "finalComponents":attached_components,
        "finalOccurrences":attached_occurrences,
        "workTrace":[work(0,"EXTERNAL_DELIVERY","history-b","source",event_id=event_ids["ATTACH-HISTORICAL"],occurrence_ordinal=0,invocation="attach-a5")],
    })
    dump(FIXTURE_DIR/"c-clo-23-00-attach-a5-retry.yaml",base_fixture("c-clo-23-00-attach-a5-retry",["C-CLO-23"],"history","process-closure","Retrying the identical C-CLO-22 invocation with exact A5 provider content commits B's inactive historical reference at cursor 5. It does not replay or discover later revisions.","history-a10-b-attaches-a5.yaml",attach_input,attach_expected))

    current_documents=deepcopy(attached_documents)
    current_occurrences=deepcopy(attached_occurrences)
    current_components=deepcopy(attached_components)
    current_graph_generation=1
    for revision_index, revision in enumerate(revisions, start=1):
        before_epoch=revision["fromEpoch"];after_epoch=revision["toEpoch"]
        pending=next(item for item in current_occurrences if item["sourceDocumentId"]=="history-b" and item["sourcePath"]=="/a")
        revision_input={
            "graphGeneration":current_graph_generation,
            "cause":managed_revision_cause(
                pending["occurrenceIdentity"],
                "history-a",
                before_epoch,
                revision["beforeBlueId"],
                revision["afterBlueId"],
                revision["afterDocument"],
                revision["originalSourceCauseIdentity"],
            ),
            "documents":deepcopy(current_documents),
            "occurrences":deepcopy(current_occurrences),
            "components":deepcopy(current_components),
            "directDeliveries":[],
            "gasPolicy":default_policy,
            "runtime":{"handlers":{}},
        }
        next_b=deepcopy(current_documents["history-b"]["document"])
        next_b["a"]=ref(revision["afterBlueId"])
        work_item=work(0,"CONTAINING_REFERENCE_UPDATE","history-b","managed-revision",invocation=f"history-{before_epoch}-{after_epoch}")
        revision_expected=empty_expected()
        if after_epoch < 10:
            next_b_id=direct_blue_id(next_b)
            next_a=deepcopy(current_documents["history-a"]["document"])
            next_a["b"]=ref(next_b_id)
            next_a_id=direct_blue_id(next_a)
            next_occurrences=[
                occurrence("history-a","/b",1,"history-b",next_b_id),
                occurrence("history-b","/a",1,"history-a",revision["afterBlueId"],active=False,pending_epoch=after_epoch),
            ]
            next_documents={
                "history-a":document_record("history-a",next_a_id,next_a,initialized=True,public_root=True,epoch=10,component_generation=1),
                "history-b":document_record("history-b",next_b_id,next_b,initialized=True,epoch=revision_index+1,component_generation=1),
            }
            next_components=[component("ACYCLIC",1,["history-b"]),component("ACYCLIC",1,["history-a"])]
            revision_expected.update({"finalGraphGeneration":current_graph_generation,"finalDocuments":next_documents,"finalComponents":next_components,"finalOccurrences":next_occurrences,"workTrace":[work_item]})
        else:
            # The historical A10 body still points at the old B state. Reaching
            # epoch 10 authorizes one processor-owned same-lineage rewrite to
            # the latest authoritative A head before edge activation.
            source_a=deepcopy(current_documents["history-a"]["document"]);source_a["b"]=ref("this#1")
            source_b=deepcopy(next_b);source_b["a"]=ref("this#0")
            final_oracle=cyclic_set_oracle([source_a,source_b])
            final_ids=final_oracle.member_ids_in_source_order()
            final_materialized=materialize_cyclic_members([source_a,source_b],final_oracle)
            history_oracle_path = ORACLE_DIR / "history-a10-b-attaches-a5.yaml"
            history_oracle_value = yaml.safe_load(history_oracle_path.read_text())
            history_oracle_value["managedRevisionFinal"] = oracle_payload(
                "managed-revision-final",
                [("managed-revision-9-10", [source_a, source_b], final_oracle)],
            )
            dump(history_oracle_path, history_oracle_value)
            next_documents={
                "history-a":document_record("history-a",final_ids[0],final_materialized[0],initialized=True,public_root=True,epoch=10,component_generation=2),
                "history-b":document_record("history-b",final_ids[1],final_materialized[1],initialized=True,epoch=revision_index+1,component_generation=2),
            }
            next_components=[component("CYCLIC",2,["history-a","history-b"],master=final_oracle.master_blue_id,stage="managed-revision-9-10")]
            next_occurrences=[occurrence("history-a","/b",1,"history-b",final_ids[1]),occurrence("history-b","/a",1,"history-a",final_ids[0])]
            current_graph_generation+=1
            revision_expected.update({"finalGraphGeneration":current_graph_generation,"finalDocuments":next_documents,"finalComponents":next_components,"finalOccurrences":next_occurrences,"workTrace":[work_item],"tentativeFinalizations":[finalize(0,"managed-revision-9-10",final_oracle,["history-a","history-b"],boundary={"kind":"WORK","afterWorkOrdinal":0})]})
        fixture_name=f"c-clo-23-{revision_index:02d}-a{before_epoch}-to-a{after_epoch}"
        dump(FIXTURE_DIR/f"{fixture_name}.yaml",base_fixture(fixture_name,["C-CLO-23","C-CLO-30"] if after_epoch==10 else ["C-CLO-23"],"history","process-closure",f"Coordination selects only authenticated A{before_epoch}→A{after_epoch}. This invocation advances one pending historical cursor and commits independently; later live work remains behind the feeder barrier.","history-a10-b-attaches-a5.yaml",revision_input,revision_expected))
        current_documents=deepcopy(next_documents);current_occurrences=deepcopy(next_occurrences);current_components=deepcopy(next_components)

    # Occurrence continuity under master churn.
    continuity_input=deepcopy(finite_input);continuity_expected=deepcopy(finite_expected)
    dump(FIXTURE_DIR/"c-clo-24-occurrence-continuity.yaml",base_fixture("c-clo-24-occurrence-continuity",["C-CLO-24"],"identity","process-closure","Changing cyclic MASTER/member identities does not retire unchanged source/path/target occurrence lineages or increment activation generation.","finite-dynamic-a-b.yaml",continuity_input,continuity_expected))

    # Gas policy binding and local cap.
    policy_input=deepcopy(loop_input);policy_input["gasPolicy"]=loop_policy
    policy_expected=deepcopy(loop_expected)
    dump(FIXTURE_DIR/"c-clo-25-policy-identity-binding.yaml",base_fixture("c-clo-25-policy-identity-binding",["C-CLO-25"],"gas","process-closure","A host-lowered semantic gas limit is exact policy evidence bound to the invocation and receipt; it cannot silently reuse the release-default identity.","infinite-loop-a-b.yaml",policy_input,policy_expected))
    local_input=deepcopy(loop_input);local_input["gasPolicy"]=policy(100000,default=True,locals={"loop-b":80},label="local-b-80")
    local_expected=deepcopy(loop_expected);local_expected["diagnostic"]="GasLimitExceeded";local_expected.pop("gasTrace",None);local_expected.pop("gasTraceIdentity",None);local_expected.pop("totalGas",None);local_expected["workTrace"]=loop_work[:1];local_expected["rejectedWork"]=work(1,"EMBEDDED_EVENT","loop-b","fromA",event_id=event_ids["LOOP"],occurrence_ordinal=0,invocation="local-cap")
    dump(FIXTURE_DIR/"c-clo-26-embedded-local-cap.yaml",base_fixture("c-clo-26-embedded-local-cap",["C-CLO-26"],"gas","process-closure","The Root uses the release default, but embedded B has an exact lower local cap. Exceeding B's cap kills and rolls back the complete required closure; B never receives a fresh independent meter.","infinite-loop-a-b.yaml",local_input,local_expected))

    # Multiple SCCs one closure and mixed result are represented by split/merge fixtures; add dedicated wrapper.
    multi_input=deepcopy(merge_input);multi_input["runtime"]={"handlers":{}}
    multi_expected=empty_expected();multi_expected.update({"finalGraphGeneration":1,"finalDocuments":merge_docs,"finalComponents":merge_components,"finalOccurrences":merge_occ+[occurrence("ma","/c",1,"mc",cd_ids[0],active=False)]})
    dump(FIXTURE_DIR/"c-clo-27-multiple-scc-one-closure.yaml",base_fixture("c-clo-27-multiple-scc-one-closure",["C-CLO-27"],"atomicity","process-closure","One connected condensation closure containing two SCCs and acyclic edges uses one shared gas ledger and one atomic result without intermediate component publication.","merge-split-four.yaml",multi_input,multi_expected))
    dump(FIXTURE_DIR/"c-clo-28-mixed-result-shape.yaml",base_fixture("c-clo-28-mixed-result-shape",["C-CLO-28"],"dynamic-graph","process-closure","The closure result explicitly represents every final cyclic and acyclic component and every changed containing document after repartition.","merge-split-four.yaml",split_input,split_expected))

    # Active edge path validation: claim edge not in document; runner derives mismatch, input does not prelabel proof.
    edge_bad=deepcopy(admit_input);edge_bad["occurrences"][0]=occurrence("simple-a","/missing",1,"simple-b",simple_ids[1])
    edge_expected=empty_expected("invalid-processing-document");edge_expected.update({"diagnostic":"ManagedOccurrenceBindingMissing","finalGraphGeneration":1,"finalDocuments":simple_docs,"finalComponents":[simple_comp],"finalOccurrences":simple_occ,"rollbackToInput":True})
    dump(FIXTURE_DIR/"c-clo-29-active-edge-path-validation.yaml",base_fixture("c-clo-29-active-edge-path-validation",["C-CLO-29"],"identity","admit-closure","An out-of-band occurrence row cannot create an edge absent from the exact source document and active Process Embedded declaration.","static-and-self-cycle.yaml",edge_bad,edge_expected))

    # Oracle integrity fixture.
    oracle_input=deepcopy(finite_input);oracle_expected=deepcopy(finite_expected)
    dump(FIXTURE_DIR/"c-clo-30-language-cyclic-oracles.yaml",base_fixture("c-clo-30-language-cyclic-oracles",["C-CLO-30"],"identity","process-closure","Independent exact Language-generated current, temporary, and final cyclic identities are immutable conformance oracles rather than values recalculated from the implementation under test.","finite-dynamic-a-b.yaml",oracle_input,oracle_expected))

    print(f"generated {len(list(FIXTURE_DIR.glob('*.yaml')))} closure fixtures and {len(list(ORACLE_DIR.glob('*.yaml')))} oracle files")


if __name__ == "__main__":
    generate()
