#!/usr/bin/env python3
"""Validate the final Blue Contracts 1.0 specification package.

This validator establishes PACKAGE_VALID and SEMANTIC_REFERENCE_VALID. It does
not claim that blue-contracts-core or blue-coordination-java executes the
fixtures.
"""
from __future__ import annotations

from argparse import ArgumentParser
from collections import defaultdict
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any, Iterable

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parents[1]
FIX = ROOT / "conformance/contracts/fixtures"
CLOSURE = FIX / "closure"
ORACLES = ROOT / "conformance/contracts/oracles"
REGISTRY = ROOT / "conformance/contracts/registry"
SPEC = ROOT / "specifications/blue-contracts-and-processor-specification-1.0.md"
LANG_SPEC = ROOT / "reference/blue-language-specification-1.0.md"
GAS = ROOT / "conformance/contracts/gas-manifest.yaml"
IDENTITY_CONSTRUCTORS = ROOT / "conformance/contracts/identity-constructors.yaml"
RELEASE = ROOT / "conformance/contracts/release-manifest.yaml"
PACKAGE_MANIFEST = ROOT / "package-manifest.yaml"

sys.path.insert(0, str(ROOT / "tools"))
from blue_identity import (  # noqa: E402
    BASE58_ALPHABET,
    cyclic_canonical_limit_bytes,
    cyclic_canonical_limit_form,
    cyclic_set_oracle,
    direct_blue_id,
)

PROCESS_EMBEDDED = "EVJk3e7MLRhtTfMBNyrWYz1pWFXsbDTkPczeTviUuB4e"
SCRIPTED_EXTERNAL = "2hesjWGVbvcJSu6woCUTssU9S7A69ep93UzdgvwosDLt"
SCRIPTED_HANDLER = "6rznQbYVahD1UVqdRXbPy7wF1NV5LYhDyzThEL1znaFw"
TRIGGERED_EVENT_CHANNEL = "DRxc8GkSGPbdENdB8ZK976i1Jzc6M1QdG8UsVMHcqQcf"
EMBEDDED_NODE_CHANNEL = "7ZgUJxCyokHf84uibaQz138mFRLarykWLewVAn8bibTN"
LIFECYCLE_EVENT_CHANNEL = "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo"
SUBSCRIPTION_CHANNEL_TYPES = {
    SCRIPTED_EXTERNAL,
    TRIGGERED_EVENT_CHANNEL,
    EMBEDDED_NODE_CHANNEL,
    LIFECYCLE_EVENT_CHANNEL,
}

STATUSES = {
    "success", "no-match", "stale", "terminated", "invalid-processing-document",
    "capability-failure", "runtime-fatal", "gas-limit-exceeded",
    "portable-limit-exceeded", "subscription-surface-invalid",
}
DIAGNOSTICS = {
    "InvalidProcessingDocument", "InvalidProcessingEvent", "InvalidClosureSnapshot",
    "InvalidAdmissionCause", "InvalidRuntimePointer", "InvalidPatch",
    "PatchBoundaryViolation", "ProtectedProcessorStateMutation",
    "InvalidReservedRuntimeState", "UnsupportedRuntimeType", "UnsupportedRuntimeRole",
    "InvalidContractKey", "InvalidContractBinding", "InvalidExternalChannelSnapshot",
    "ExternalSubscriptionLawViolation", "ManagedDocumentIdInvalid",
    "ManagedDocumentIdentityConflict", "ManagedOccurrenceBindingMissing",
    "ManagedOccurrenceBindingConflict", "ManagedOccurrenceStateMismatch",
    "EmbeddedRouteNotFound", "EmbeddedScopeNotObject", "EmbeddedCollectionMustBeObject",
    "EmbeddedCollectionMemberMustBeObject", "InvalidEmbeddedCollectionPath",
    "EmbeddedPathSelectorUnsupported", "OverlappingEmbeddedDeclaration",
    "UnsupportedOpaqueEmbeddedCycle", "CyclicProcessingContextRequired",
    "CyclicSetEmbeddedBoundaryUnsupported",
    "CyclicSetProofUnavailable", "CyclicSetProofInvalid", "CyclicComponentIdentityMismatch",
    "CyclicMemberMappingMismatch", "CyclicPreliminaryMemberAmbiguous",
    "CyclicComponentFinalizationFailed", "DynamicClosureExpansionFailed",
    "DynamicComponentReclassificationFailed", "ActiveScopeCutOff", "CheckpointDomainError",
    "CheckpointPolicyError", "FixedValueConflict", "TypeCompatibilityViolation",
    "SchemaViolation", "TypeGeneralizationFailure", "CyclicSetMutationUnsupported",
    "CyclicMemberProcessingRootUnsupported", "CyclicMemberProcessingEventUnsupported",
    "DirectNodeLimitExceeded", "MatchingDeliveryLimitExceeded",
    "ParticipatingScopeLimitExceeded", "InternalEventLimitExceeded", "PatchLimitExceeded",
    "RuntimeLedgerLimitExceeded", "ManagedDocumentsPerClosureExceeded",
    "ProcessEmbeddedEdgesPerClosureExceeded", "CyclicComponentMemberLimitExceeded",
    "CyclicComponentEdgeLimitExceeded", "CyclicComponentCanonicalBytesExceeded",
    "ClosureGraphChangeLimitExceeded", "ClosureExpansionLimitExceeded",
    "ClosureWorkOccurrenceLimitExceeded", "ClosureTentativeFinalizationLimitExceeded",
    "SubscriptionSurfaceInvalid", "RuntimeExecutionFailure", "GasLimitExceeded",
}

LIMIT_DIAGNOSTICS = {
    "managedDocumentsPerClosure": "ManagedDocumentsPerClosureExceeded",
    "processEmbeddedEdgesPerClosure": "ProcessEmbeddedEdgesPerClosureExceeded",
    "cyclicMembersPerComponent": "CyclicComponentMemberLimitExceeded",
    "cyclicEdgesPerComponent": "CyclicComponentEdgeLimitExceeded",
    "cyclicCanonicalBytesPerComponent": "CyclicComponentCanonicalBytesExceeded",
    "closureGraphChangesPerInvocation": "ClosureGraphChangeLimitExceeded",
    "closureExpansionsPerInvocation": "ClosureExpansionLimitExceeded",
    "closureWorkOccurrencesPerInvocation": "ClosureWorkOccurrenceLimitExceeded",
    "closureTentativeFinalizationsPerInvocation": "ClosureTentativeFinalizationLimitExceeded",
}

LIMIT_GENERATORS = {
    "managedDocumentsPerClosure": "managed-documents",
    "processEmbeddedEdgesPerClosure": "embedded-edges",
    "cyclicMembersPerComponent": "cyclic-members",
    "cyclicEdgesPerComponent": "cyclic-edges",
    "cyclicCanonicalBytesPerComponent": "cyclic-canonical-bytes",
    "closureGraphChangesPerInvocation": "graph-changes",
    "closureExpansionsPerInvocation": "closure-expansions",
    "closureWorkOccurrencesPerInvocation": "work-occurrences",
    "closureTentativeFinalizationsPerInvocation": "tentative-finalizations",
}

LIMIT_GENERATOR_PARAMETER_KEYS = {
    "managed-documents": {"count"},
    "embedded-edges": {"count"},
    "cyclic-members": {"count"},
    "cyclic-edges": {"count", "members"},
    "cyclic-canonical-bytes": {"count"},
    "graph-changes": {"count"},
    "closure-expansions": {"count"},
    "work-occurrences": {"count"},
    "tentative-finalizations": {"count"},
}


class ValidationFailure(RuntimeError):
    pass


_REGISTRY_BLUE_IDS: dict[str, str] | None = None


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationFailure(message)


def load_yaml(path: Path) -> Any:
    try:
        return yaml.safe_load(path.read_text())
    except Exception as exc:
        raise ValidationFailure(f"YAML parse failed: {path.relative_to(ROOT)}: {exc}") from exc


def registry_blue_id(key: str) -> str:
    """Resolve one exact released registry identity by semantic key."""
    global _REGISTRY_BLUE_IDS
    if _REGISTRY_BLUE_IDS is None:
        manifest = load_yaml(REGISTRY / "manifest.yaml")
        _REGISTRY_BLUE_IDS = {
            entry["key"]: entry["blueId"] for entry in manifest["entries"]
        }
    require(key in _REGISTRY_BLUE_IDS, f"registry entry is missing: {key}")
    return _REGISTRY_BLUE_IDS[key]


def expected_gas_trace(path: Path, expected: dict[str, Any]) -> list[dict[str, Any]]:
    if "gasTrace" in expected:
        return expected["gasTrace"]
    reference = expected.get("gasTraceFile")
    require(isinstance(reference, str), f"gas trace missing in {path.name}")
    trace_path = (path.parent / reference).resolve()
    try:
        trace_path.relative_to(CLOSURE.resolve())
    except ValueError as exc:
        raise ValidationFailure(f"gas trace escapes closure fixture directory in {path.name}: {reference}") from exc
    payload = load_yaml(trace_path)
    require(payload.get("schema") == "blue-contracts-gas-trace/1.0", f"gas trace schema mismatch in {path.name}")
    require(payload.get("fixture") == path.stem, f"gas trace fixture mismatch in {path.name}")
    require(isinstance(payload.get("entries"), list), f"gas trace entries missing in {path.name}")
    return payload["entries"]


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def canonical_identity(value: dict[str, Any], field: str) -> str:
    copy = json.loads(json.dumps(value))
    copy[field] = None
    return "sha256:" + hashlib.sha256(canonical_json(copy)).hexdigest()


def domain_identity(domain: str, value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json({"domain": domain, "value": value})).hexdigest()


def generated_document_id(ordinal: int) -> str:
    require(0 <= ordinal <= 99_999_999, f"generated DocumentId ordinal out of range: {ordinal}")
    return f"document-{ordinal:08d}"


def measure_limit_generator(kind: str, parameters: dict[str, Any]) -> int:
    """Construct and independently measure one normative closure-limit input.

    The fixture's ``observed`` value is deliberately not an input to this
    function.  In particular, the canonical-byte case materializes the actual
    padding value and runs the unchanged cyclic-set algorithm over it.
    """
    require(kind in LIMIT_GENERATOR_PARAMETER_KEYS, f"unknown closure limit generator: {kind}")
    expected_keys = LIMIT_GENERATOR_PARAMETER_KEYS[kind]
    require(set(parameters) == expected_keys, f"invalid parameters for {kind}: expected {sorted(expected_keys)}, got {sorted(parameters)}")
    for key, value in parameters.items():
        require(isinstance(value, int) and not isinstance(value, bool) and value >= 0, f"invalid {kind} parameter {key}: {value!r}")
    count = parameters["count"]

    if kind == "managed-documents":
        managed = {
            generated_document_id(ordinal): {
                "documentId": generated_document_id(ordinal),
                "initialized": True,
            }
            for ordinal in range(count)
        }
        return len(managed)

    if kind == "embedded-edges":
        source_id = generated_document_id(0)
        target_id = generated_document_id(1)
        active_occurrences = {
            (source_id, f"/edges/e{ordinal:08d}", 1, target_id)
            for ordinal in range(count)
        }
        return len({occurrence[:3] for occurrence in active_occurrences})

    if kind == "cyclic-members":
        require(count > 0, "cyclic-members generator requires at least one member")
        documents = [
            {
                "memberIdentity": generated_document_id(ordinal),
                "next": {"blueId": f"this#{(ordinal + 1) % count}"},
            }
            for ordinal in range(count)
        ]
        oracle = cyclic_set_oracle(documents)
        return len(oracle.members)

    if kind == "cyclic-edges":
        members = parameters["members"]
        require(members > 0, "cyclic-edges generator requires at least one member")
        require(count >= members, "cyclic-edges generator cannot contain fewer edges than its ring")
        require(count <= members * members, "cyclic-edges generator exceeds its unique directed-edge capacity")
        edges: list[tuple[int, int]] = []
        seen_edges: set[tuple[int, int]] = set()

        def admit_edge(source: int, target: int) -> None:
            edge = (source, target)
            if len(edges) < count and edge not in seen_edges:
                seen_edges.add(edge)
                edges.append(edge)

        for source in range(members):
            admit_edge(source, (source + 1) % members)
        for source in range(members):
            for target in range(members):
                admit_edge(source, target)
        require(len(edges) == count, f"cyclic-edges construction stopped at {len(edges)}, expected {count}")
        documents: list[dict[str, Any]] = [
            {"memberIdentity": generated_document_id(ordinal), "edges": {}}
            for ordinal in range(members)
        ]
        for source, target in edges:
            documents[source]["edges"][f"e{target:08d}"] = {"blueId": f"this#{target}"}
        oracle = cyclic_set_oracle(documents)
        require(len(oracle.members) == members, "cyclic-edges construction did not retain one complete component")
        measured_edges = sum(len(document["edges"]) for document in documents)
        require(measured_edges == len(seen_edges), "cyclic-edges construction lost an occurrence")
        return measured_edges

    if kind == "cyclic-canonical-bytes":
        empty_documents = [
            {"memberIdentity": "a", "next": {"blueId": "this#1"}, "value": ""},
            {"memberIdentity": "b", "next": {"blueId": "this#0"}},
        ]
        base_bytes = cyclic_canonical_limit_bytes(cyclic_set_oracle(empty_documents))
        require(count >= base_bytes, f"canonical-byte generator target {count} is below its minimum {base_bytes}")
        documents = [
            {
                "memberIdentity": "a",
                "next": {"blueId": "this#1"},
                "value": "a" * (count - base_bytes),
            },
            {"memberIdentity": "b", "next": {"blueId": "this#0"}},
        ]
        return cyclic_canonical_limit_bytes(cyclic_set_oracle(documents))

    if kind == "graph-changes":
        changes: list[tuple[int, str, str, bool, bool]] = []
        active = False
        for ordinal in range(count):
            before = active
            active = not active
            changes.append((ordinal, generated_document_id(0), "/peer", before, active))
        require(all(before != after for _ordinal, _document, _path, before, after in changes), "graph-change construction contains a no-op")
        return len(changes)

    if kind == "closure-expansions":
        managed = {generated_document_id(0)}
        expansions: list[tuple[str, str, int]] = []
        for ordinal in range(count):
            target = generated_document_id(ordinal + 1)
            require(target not in managed, f"closure-expansion target was already managed: {target}")
            managed.add(target)
            expansions.append((generated_document_id(ordinal), target, ordinal))
        require(len(managed) == count + 1, "closure-expansion construction has the wrong final document count")
        return len(expansions)

    if kind == "work-occurrences":
        invocation_identity = domain_identity(
            "blue-contracts-limit-generator-invocation/1.0",
            {"kind": kind, "count": count},
        )
        target_scope_identity = domain_identity(
            "blue-contracts-managed-scope-key/1.0",
            {"documentId": generated_document_id(0), "scopePath": "/", "activationGeneration": 0},
        )
        work_identities: set[str] = set()
        for ordinal in range(count):
            transition_identity = domain_identity(
                "blue-contracts-historical-transition/1.0",
                {
                    "documentId": generated_document_id(0),
                    "fromEpoch": ordinal,
                    "toEpoch": ordinal + 1,
                    "beforeBlueId": direct_blue_id({"revision": ordinal}),
                    "afterBlueId": direct_blue_id({"revision": ordinal + 1}),
                },
            )
            work_identities.add(domain_identity(
                "blue-contracts-work-occurrence/1.0",
                {
                    "invocationIdentity": invocation_identity,
                    "workOrdinal": ordinal,
                    "workKind": "HISTORICAL_TRANSITION",
                    "targetManagedScopeIdentity": target_scope_identity,
                    "sourceOccurrenceIdentity": transition_identity,
                },
            ))
        return len(work_identities)

    if kind == "tentative-finalizations":
        finalized_masters: list[str] = []
        for ordinal in range(count):
            oracle = cyclic_set_oracle([
                {
                    "memberIdentity": generated_document_id(0),
                    "next": {"blueId": "this#0"},
                    "value": ordinal,
                }
            ])
            finalized_masters.append(oracle.master_blue_id)
        require(len(set(finalized_masters)) == len(finalized_masters), "tentative-finalization construction repeated an exact state")
        return len(finalized_masters)

    raise AssertionError(kind)


def base58_decode(value: str) -> bytes:
    index = {char: i for i, char in enumerate(BASE58_ALPHABET)}
    number = 0
    for char in value:
        if char not in index:
            raise ValueError(char)
        number = number * 58 + index[char]
    data = b"" if number == 0 else number.to_bytes((number.bit_length() + 7) // 8, "big")
    leading = len(value) - len(value.lstrip(BASE58_ALPHABET[0]))
    return b"\x00" * leading + data


def valid_exact_blue_id(value: str) -> bool:
    master = value.split("#", 1)[0]
    if "#" in value:
        suffix = value.split("#", 1)[1]
        if not re.fullmatch(r"0|[1-9][0-9]*", suffix):
            return False
    if not re.fullmatch(r"[1-9A-HJ-NP-Za-km-z]+", master):
        return False
    try:
        return len(base58_decode(master)) == 32
    except ValueError:
        return False


def walk_values(value: Any, path: tuple[str, ...] = ()) -> Iterable[tuple[tuple[str, ...], Any]]:
    yield path, value
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk_values(child, path + (str(key),))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk_values(child, path + (str(index),))


def pointer_get(value: Any, pointer: str) -> Any:
    require(pointer.startswith("/"), f"pointer must be absolute: {pointer}")
    current = value
    if pointer == "/":
        return current
    for raw in pointer.split("/")[1:]:
        segment = raw.replace("~1", "/").replace("~0", "~")
        if isinstance(current, dict) and segment in current:
            current = current[segment]
        elif isinstance(current, list) and segment.isdigit() and int(segment) < len(current):
            current = current[int(segment)]
        else:
            raise KeyError(pointer)
    return current


def type_blue_id(contract: Any) -> str | None:
    if not isinstance(contract, dict):
        return None
    type_value = contract.get("type")
    if isinstance(type_value, dict):
        blue_id = type_value.get("blueId")
        return blue_id if isinstance(blue_id, str) else None
    return None


def pure_blue_reference(value: Any) -> str | None:
    if not isinstance(value, dict) or set(value) != {"blueId"}:
        return None
    blue_id = value.get("blueId")
    if not isinstance(blue_id, str) or not valid_exact_blue_id(blue_id):
        return None
    return blue_id


def direct_processor_markers(
    path: Path,
    documents: dict[str, Any],
    phase: str,
) -> dict[str, dict[str, Any]]:
    """Validate direct reserved state and return its exact values by document.

    Fixture booleans are derived assertions.  A similarly typed value at any
    other key or depth has no effect; only the three direct reserved keys are
    inspected here.
    """
    initialized_type = registry_blue_id("ProcessingInitializedMarker")
    terminated_type = registry_blue_id("ProcessingTerminatedMarker")
    checkpoint_type = registry_blue_id("ChannelEventCheckpoint")
    result: dict[str, dict[str, Any]] = {}
    for document_id, record in documents.items():
        document = record["document"]
        require(
            isinstance(document, dict),
            f"{phase} managed document is not an object in {path.name}: {document_id}",
        )
        contracts = document.get("contracts", {})
        require(
            isinstance(contracts, dict),
            f"{phase} direct contracts is not an object in {path.name}: {document_id}",
        )

        initialized = contracts.get("initialized")
        if initialized is not None:
            require(
                isinstance(initialized, dict)
                and set(initialized) == {"type", "document"}
                and pure_blue_reference(initialized["type"]) == initialized_type
                and pure_blue_reference(initialized["document"]) is not None,
                f"invalid direct initialized marker in {path.name}: {phase}/{document_id}",
            )
        require(
            record["initialized"] is (initialized is not None),
            f"initialized flag is not derived from the direct marker in {path.name}: "
            f"{phase}/{document_id}",
        )

        terminated = contracts.get("terminated")
        if terminated is not None:
            require(
                isinstance(terminated, dict)
                and set(terminated) in ({"type", "cause"}, {"type", "cause", "reason"})
                and pure_blue_reference(terminated["type"]) == terminated_type
                and isinstance(terminated.get("cause"), str)
                and ("reason" not in terminated or isinstance(terminated["reason"], str)),
                f"invalid direct terminated marker in {path.name}: {phase}/{document_id}",
            )
        require(
            record["terminated"] is (terminated is not None),
            f"terminated flag is not derived from the direct marker in {path.name}: "
            f"{phase}/{document_id}",
        )

        checkpoint = contracts.get("checkpoint")
        if checkpoint is not None:
            require(
                isinstance(checkpoint, dict)
                and set(checkpoint) == {"type", "entries"}
                and pure_blue_reference(checkpoint["type"]) == checkpoint_type
                and isinstance(checkpoint.get("entries"), dict),
                f"invalid direct checkpoint marker in {path.name}: {phase}/{document_id}",
            )
            for raw_key, entry in checkpoint["entries"].items():
                domain = entry.get("domain") if isinstance(entry, dict) else None
                domain_reference = pure_blue_reference(domain)
                inline_domain_valid = False
                if domain_reference is None and isinstance(domain, dict):
                    try:
                        inline_domain_valid = valid_exact_blue_id(direct_blue_id(domain))
                    except (TypeError, ValueError):
                        inline_domain_valid = False
                require(
                    isinstance(raw_key, str)
                    and isinstance(entry, dict)
                    and set(entry) == {"domain", "subject"}
                    and (domain_reference is not None or inline_domain_valid)
                    and pure_blue_reference(entry["subject"]) is not None,
                    f"invalid checkpoint entry in {path.name}: "
                    f"{phase}/{document_id}/{raw_key}",
                )
        result[document_id] = {
            "initialized": initialized,
            "terminated": terminated,
            "checkpoint": checkpoint,
        }
    return result


def validate_direct_marker_transition(
    path: Path,
    before_documents: dict[str, Any],
    after_documents: dict[str, Any],
    before_markers: dict[str, dict[str, Any]],
    after_markers: dict[str, dict[str, Any]],
) -> None:
    for document_id in sorted(before_documents):
        before = before_markers[document_id]
        after = after_markers[document_id]
        if before["initialized"] is not None:
            require(
                after["initialized"] == before["initialized"],
                f"protected initialized marker changed in {path.name}: {document_id}",
            )
        elif after["initialized"] is not None:
            frozen_blue_id = direct_blue_id(before_documents[document_id]["document"])
            require(
                after["initialized"]["document"] == {"blueId": frozen_blue_id},
                f"initialized marker does not reference the exact frozen input document "
                f"in {path.name}: {document_id}",
            )
        if before["terminated"] is not None:
            require(
                after["terminated"] == before["terminated"],
                f"protected terminated marker changed in {path.name}: {document_id}",
            )


def contracts_at_scope(document: Any, scope_path: str) -> dict[str, Any]:
    scope = pointer_get(document, scope_path)
    require(isinstance(scope, dict), f"scope is not object: {scope_path}")
    contracts = scope.get("contracts", {})
    require(isinstance(contracts, dict), f"contracts is not object at {scope_path}")
    return contracts


def active_declared_path(document: dict[str, Any], source_path: str) -> bool:
    contracts = document.get("contracts", {})
    if not isinstance(contracts, dict):
        return False
    for value in contracts.values():
        if type_blue_id(value) != PROCESS_EMBEDDED:
            continue
        paths = value.get("paths", []) if isinstance(value, dict) else []
        if source_path in paths:
            return True
        for collection_path in value.get("collectionPaths", []) if isinstance(value, dict) else []:
            prefix = collection_path.rstrip("/") + "/"
            remainder = source_path[len(prefix):] if source_path.startswith(prefix) else None
            if remainder is not None and remainder and "/" not in remainder:
                return True
    return False


def scc_partition(document_ids: set[str], occurrences: list[dict[str, Any]]) -> list[frozenset[str]]:
    graph: dict[str, list[str]] = {doc: [] for doc in document_ids}
    for occurrence in occurrences:
        if occurrence.get("active") and occurrence["sourceDocumentId"] in graph and occurrence["targetDocumentId"] in graph:
            graph[occurrence["sourceDocumentId"]].append(occurrence["targetDocumentId"])
    for edges in graph.values():
        edges.sort()

    index = 0
    stack: list[str] = []
    on_stack: set[str] = set()
    indices: dict[str, int] = {}
    low: dict[str, int] = {}
    result: list[frozenset[str]] = []

    def visit(node: str) -> None:
        nonlocal index
        indices[node] = index
        low[node] = index
        index += 1
        stack.append(node)
        on_stack.add(node)
        for target in graph[node]:
            if target not in indices:
                visit(target)
                low[node] = min(low[node], low[target])
            elif target in on_stack:
                low[node] = min(low[node], indices[target])
        if low[node] == indices[node]:
            members: set[str] = set()
            while True:
                member = stack.pop()
                on_stack.remove(member)
                members.add(member)
                if member == node:
                    break
            result.append(frozenset(members))

    for node in sorted(graph):
        if node not in indices:
            visit(node)
    return sorted(result, key=lambda s: tuple(sorted(s)))


def component_sets(components: list[dict[str, Any]]) -> list[frozenset[str]]:
    return sorted((frozenset(c["orderedMemberDocumentIds"]) for c in components), key=lambda s: tuple(sorted(s)))


def verify_manifest(path: Path, identity_field: str, additionally_null: tuple[str, ...] = ()) -> dict[str, Any]:
    data = load_yaml(path)
    copy = json.loads(json.dumps(data))
    copy[identity_field] = None
    for field in additionally_null:
        if field in copy:
            copy[field] = None
    expected = "sha256:" + hashlib.sha256(canonical_json(copy)).hexdigest()
    require(data[identity_field] == expected, f"identity mismatch: {path.relative_to(ROOT)}")
    return data


def verify_listed_files(base: Path, entries: list[dict[str, Any]]) -> None:
    for entry in entries:
        path = base / entry["path"]
        require(path.is_file(), f"manifest file missing: {path.relative_to(ROOT)}")
        if "bytes" in entry:
            require(path.stat().st_size == entry["bytes"], f"manifest byte length mismatch: {path.relative_to(ROOT)}")
        require(sha256_file(path) == entry["sha256"], f"manifest hash mismatch: {path.relative_to(ROOT)}")


def fixture_files() -> tuple[list[Path], list[Path]]:
    ignored = {"manifest.yaml", "vector-coverage.yaml", "projection-catalog.yaml", "fixture-schema.yaml", "closure-fixture-schema.yaml"}
    ordinary: list[Path] = []
    closure: list[Path] = []
    for path in sorted(FIX.rglob("*.yaml")):
        if path.name in ignored:
            continue
        if path.parent == CLOSURE / "traces":
            continue
        data = load_yaml(path)
        if not isinstance(data, dict) or "vectors" not in data:
            continue
        (closure if path.parent == CLOSURE else ordinary).append(path)
    return ordinary, closure


def validate_schemas(ordinary: list[Path], closure: list[Path]) -> None:
    ordinary_schema = load_yaml(FIX / "fixture-schema.yaml")
    closure_schema = load_yaml(FIX / "closure-fixture-schema.yaml")
    ov = jsonschema.Draft202012Validator(ordinary_schema)
    cv = jsonschema.Draft202012Validator(closure_schema)
    for path in ordinary:
        errors = sorted(ov.iter_errors(load_yaml(path)), key=lambda e: list(e.path))
        require(not errors, f"ordinary schema failure {path.relative_to(ROOT)}: {errors[0].message if errors else ''}")
    for path in closure:
        errors = sorted(cv.iter_errors(load_yaml(path)), key=lambda e: list(e.path))
        require(not errors, f"closure schema failure {path.relative_to(ROOT)}: {errors[0].message if errors else ''}")


def validate_vector_coverage(ordinary: list[Path], closure: list[Path]) -> dict[str, list[str]]:
    actual: dict[str, list[str]] = defaultdict(list)
    for path in ordinary + closure:
        data = load_yaml(path)
        rel = path.relative_to(FIX).as_posix()
        for vector in data["vectors"]:
            actual[vector].append(rel)
    actual = {key: sorted(value) for key, value in sorted(actual.items())}
    declared = load_yaml(FIX / "vector-coverage.yaml")
    require(declared["vectors"] == actual, "vector-coverage.yaml does not match fixture vectors")
    require(declared["vectorCount"] == len(actual), "vector count mismatch")
    require(
        set(v for v in actual if v.startswith("C-CLO-"))
        == {f"C-CLO-{i:02d}" for i in range(1, 34)},
        "closure vectors must be C-CLO-01..33",
    )
    return actual


def validate_blue_ids(closure: list[Path]) -> int:
    checked = 0
    for path in closure:
        data = load_yaml(path)
        for value_path, value in walk_values(data):
            if not value_path:
                continue
            key = value_path[-1]
            parent_key = value_path[-2] if len(value_path) > 1 else ""
            blue_id_context = (
                key == "blueId"
                or key.endswith("BlueId")
                or parent_key.endswith("BlueIds")
                or parent_key in {"memberBlueIds", "providerLoads", "requestedProviderNodes"}
            )
            if blue_id_context and isinstance(value, str):
                if value.startswith("this#"):
                    continue
                require(valid_exact_blue_id(value), f"invalid exact BlueId in {path.name} at {'.'.join(value_path)}: {value}")
                checked += 1
    for path in ORACLES.glob("*.yaml"):
        if path.name == "manifest.yaml":
            continue
        data = load_yaml(path)
        for value_path, value in walk_values(data):
            if not value_path or not isinstance(value, str):
                continue
            key = value_path[-1]
            if value.startswith("this#"):
                require(re.fullmatch(r"this#(?:0|[1-9][0-9]*)", value) is not None, f"invalid this reference in {path.name}: {value}")
            elif key in {"masterBlueId", "memberBlueId"} or key.endswith("BlueId") or key == "memberBlueIdsInSourceOrder":
                # list values are visited under numeric keys, so only named scalar fields are handled here.
                if key != "memberBlueIdsInSourceOrder":
                    require(valid_exact_blue_id(value), f"invalid oracle BlueId in {path.name}: {value}")
                    checked += 1
        for stage_path, stage in walk_values(data):
            if isinstance(stage, dict) and "memberBlueIdsInSourceOrder" in stage:
                for value in stage["memberBlueIdsInSourceOrder"]:
                    require(valid_exact_blue_id(value), f"invalid oracle member BlueId in {path.name}: {value}")
                    checked += 1
    return checked


def validate_document_ids(closure: list[Path]) -> int:
    import unicodedata
    checked = 0
    for path in closure:
        data = load_yaml(path)
        if data["operation"] == "limit-micro":
            continue
        ids = list(data["input"]["documents"])
        require(len(ids) == len(set(ids)), f"duplicate DocumentId in {path.name}")
        for doc_id in ids:
            require(doc_id != "", f"empty DocumentId in {path.name}")
            require("\x00" not in doc_id, f"NUL in DocumentId in {path.name}")
            require(len(doc_id.encode("utf-8")) <= 512, f"DocumentId exceeds 512 UTF-8 bytes in {path.name}")
            require(unicodedata.normalize("NFC", doc_id) == doc_id, f"non-NFC DocumentId in {path.name}: {doc_id!r}")
            require(data["input"]["documents"][doc_id]["documentId"] == doc_id, f"document key/id mismatch in {path.name}: {doc_id}")
            checked += 1
    return checked


def validate_occurrence_identity(path: Path, occurrence: dict[str, Any]) -> str:
    identity = occurrence["occurrenceIdentity"]
    expected_identity = domain_identity("blue-contracts-managed-occurrence-lineage/1.0", {
        "sourceDocumentId": occurrence["sourceDocumentId"],
        "sourcePath": occurrence["sourcePath"],
        "activationGeneration": occurrence["activationGeneration"],
        "targetDocumentId": occurrence["targetDocumentId"],
        "bindingPolicyIdentity": occurrence["bindingPolicyIdentity"],
    })
    require(identity == expected_identity, f"occurrence identity mismatch in {path.name}: {identity}")
    expected_binding = domain_identity("blue-contracts-managed-occurrence/1.0", {
        "sourceDocumentId": occurrence["sourceDocumentId"],
        "sourcePath": occurrence["sourcePath"],
        "activationGeneration": occurrence["activationGeneration"],
        "targetDocumentId": occurrence["targetDocumentId"],
        "expectedTargetBlueId": occurrence["expectedTargetBlueId"],
        "bindingPolicyIdentity": occurrence["bindingPolicyIdentity"],
    })
    require(occurrence["bindingIdentity"] == expected_binding, f"binding identity mismatch in {path.name}: {identity}")
    return identity


def validate_occurrence_set(path: Path, documents: dict[str, Any], occurrences: list[dict[str, Any]], *, allow_invalid: bool) -> int:
    checked = 0
    seen: set[str] = set()
    for occurrence in occurrences:
        identity = validate_occurrence_identity(path, occurrence)
        require(identity not in seen, f"duplicate occurrence identity in {path.name}: {identity}")
        seen.add(identity)
        source_id = occurrence["sourceDocumentId"]
        target_id = occurrence["targetDocumentId"]
        require(source_id in documents and target_id in documents, f"occurrence document missing in {path.name}")
        if not occurrence.get("active"):
            if occurrence["pendingHistoricalEpoch"] is None:
                require(
                    documents[target_id]["blueId"] == occurrence["expectedTargetBlueId"],
                    f"prospective binding target document mismatch in {path.name}: {target_id}",
                )
            checked += 1
            continue
        source_document = documents[source_id]["document"]
        try:
            node = pointer_get(source_document, occurrence["sourcePath"])
        except KeyError:
            if allow_invalid:
                continue
            raise ValidationFailure(f"active occurrence path absent in {path.name}: {source_id}{occurrence['sourcePath']}")
        require(active_declared_path(source_document, occurrence["sourcePath"]), f"active occurrence path is not declared Process Embedded in {path.name}: {source_id}{occurrence['sourcePath']}")
        require(isinstance(node, dict) and set(node) == {"blueId"}, f"active occurrence target is not an exact pure reference in {path.name}: {source_id}{occurrence['sourcePath']}")
        require(node["blueId"] == occurrence["expectedTargetBlueId"], f"occurrence target BlueId mismatch in {path.name}: {source_id}{occurrence['sourcePath']}")
        if occurrence["pendingHistoricalEpoch"] is None:
            require(documents[target_id]["blueId"] == occurrence["expectedTargetBlueId"], f"occurrence binding target document mismatch in {path.name}: {target_id}")
        checked += 1
    return checked


def document_identity_items(documents: dict[str, Any]) -> list[dict[str, Any]]:
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


def occurrence_binding_set_identity(occurrences: list[dict[str, Any]]) -> str:
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
    return domain_identity("blue-contracts-occurrence-binding-set/1.0", items)


def affected_closure_identity(
    graph_generation: int,
    documents: dict[str, Any],
    occurrences: list[dict[str, Any]],
    components: list[dict[str, Any]],
) -> tuple[str, str, list[str]]:
    # Durable closure state excludes cause and direct-delivery evidence;
    # validate_fixture binds both independently through invocationIdentity.
    binding_set = occurrence_binding_set_identity(occurrences)
    public_roots = sorted(
        document_id
        for document_id, record in documents.items()
        if record["publicRoot"]
    )
    identity = domain_identity(
        "blue-contracts-affected-closure/1.0",
        {
            "graphGeneration": graph_generation,
            "documents": document_identity_items(documents),
            "occurrenceBindingSetIdentity": binding_set,
            "components": sorted(
                component["componentStateIdentity"] for component in components
            ),
            "publicRootDocumentIds": public_roots,
        },
    )
    return identity, binding_set, public_roots


def restore_component_placeholders(value: Any, source_index_by_blue_id: dict[str, int]) -> Any:
    if isinstance(value, list):
        return [restore_component_placeholders(child, source_index_by_blue_id) for child in value]
    if isinstance(value, dict):
        if set(value) == {"blueId"} and value["blueId"] in source_index_by_blue_id:
            return {"blueId": f"this#{source_index_by_blue_id[value['blueId']]}"}
        return {
            key: restore_component_placeholders(child, source_index_by_blue_id)
            for key, child in value.items()
        }
    return value


def validate_components(
    path: Path,
    documents: dict[str, Any],
    occurrences: list[dict[str, Any]],
    components: list[dict[str, Any]],
) -> None:
    actual = scc_partition(set(documents), occurrences)
    declared = component_sets(components)
    require(actual == declared, f"SCC partition mismatch in {path.name}: actual={actual}, declared={declared}")
    require(
        len(declared) == len(set(declared)),
        f"duplicate component membership in {path.name}",
    )
    active_pairs = {
        (occurrence["sourceDocumentId"], occurrence["targetDocumentId"])
        for occurrence in occurrences
        if occurrence.get("active")
    }
    for component in components:
        ordered_members = component["orderedMemberDocumentIds"]
        require(
            ordered_members == sorted(ordered_members),
            f"component members are not in canonical DocumentId order in {path.name}",
        )
        require(
            len(ordered_members) == len(set(ordered_members)),
            f"duplicate component member in {path.name}: {ordered_members}",
        )
        require(
            all(member in documents for member in ordered_members),
            f"component member document missing in {path.name}: {ordered_members}",
        )
        members = set(ordered_members)
        self_loop = any(
            source == target and source in members for source, target in active_pairs
        )
        cyclic = len(members) > 1 or self_loop
        require(
            component["kind"] == ("CYCLIC" if cyclic else "ACYCLIC"),
            f"component kind mismatch in {path.name}: {members}",
        )
        require(
            all(
                documents[member]["componentGeneration"]
                == component["componentGeneration"]
                for member in ordered_members
            ),
            f"component/document generation mismatch in {path.name}: {ordered_members}",
        )
        member_states = [
            {
                "documentId": document_id,
                "blueId": documents[document_id]["blueId"],
            }
            for document_id in ordered_members
        ]
        require(
            component["orderedMemberBlueIds"]
            == [state["blueId"] for state in member_states],
            f"component member BlueId mapping mismatch in {path.name}: {ordered_members}",
        )
        component_identity = domain_identity(
            "blue-contracts-component/1.0",
            {
                "kind": component["kind"],
                "generation": component["componentGeneration"],
                "members": ordered_members,
            },
        )
        require(
            component["componentIdentity"] == component_identity,
            f"component identity mismatch in {path.name}: {ordered_members}",
        )
        master_blue_id: str | None = None
        proof_identity: str | None = None
        if cyclic:
            master_blue_id = component["masterBlueId"]
            member_indices: list[int] = []
            for state in member_states:
                member_blue_id = state["blueId"]
                require(
                    member_blue_id.startswith(master_blue_id + "#"),
                    f"cyclic member is not under the declared MASTER in {path.name}: {member_blue_id}",
                )
                member_indices.append(int(member_blue_id.rsplit("#", 1)[1]))
            require(
                sorted(member_indices) == list(range(len(ordered_members))),
                f"cyclic member suffix set is not complete in {path.name}: {member_indices}",
            )
            source_index_by_blue_id = {
                state["blueId"]: index
                for index, state in enumerate(member_states)
            }
            source_documents = [
                restore_component_placeholders(
                    documents[document_id]["document"], source_index_by_blue_id
                )
                for document_id in ordered_members
            ]
            try:
                oracle = cyclic_set_oracle(source_documents)
            except (TypeError, ValueError) as exc:
                raise ValidationFailure(
                    f"cyclic component does not reconstruct as exact Language content in "
                    f"{path.name}: {ordered_members}: {exc}"
                ) from exc
            require(
                oracle.master_blue_id == master_blue_id
                and list(oracle.member_ids_in_source_order())
                == [state["blueId"] for state in member_states],
                f"cyclic documents do not derive their declared member identities in {path.name}: {ordered_members}",
            )
            proof = component["completeCyclicProof"]
            expected_proof = {
                "componentIdentity": component_identity,
                "masterBlueId": master_blue_id,
                "memberStates": member_states,
                "declaredPlaceholderSet": proof["declaredPlaceholderSet"],
            }
            require(
                proof == expected_proof,
                f"cyclic proof evidence mismatch in {path.name}: {ordered_members}",
            )
            require(
                cyclic_canonical_limit_form(oracle)
                == proof["declaredPlaceholderSet"],
                f"cyclic proof placeholder set is not the canonical exact component in {path.name}: {ordered_members}",
            )
            proof_identity = domain_identity(
                "blue-contracts-cyclic-proof-evidence/1.0", proof
            )
            require(
                component["cyclicProofIdentity"] == proof_identity,
                f"cyclic proof identity mismatch in {path.name}: {ordered_members}",
            )
        else:
            for field in (
                "masterBlueId",
                "completeCyclicProof",
                "cyclicProofIdentity",
                "oracleStage",
            ):
                require(
                    field not in component,
                    f"acyclic component carries {field} in {path.name}: {ordered_members}",
                )
            only_member = ordered_members[0]
            require(
                direct_blue_id(documents[only_member]["document"])
                == documents[only_member]["blueId"],
                f"acyclic document BlueId mismatch in {path.name}: {only_member}",
            )
        state_identity = domain_identity(
            "blue-contracts-component-state/1.0",
            {
                "componentIdentity": component_identity,
                "memberStates": member_states,
                "masterBlueId": master_blue_id,
                "cyclicProofIdentity": proof_identity,
            },
        )
        require(
            component["componentStateIdentity"] == state_identity,
            f"component state identity mismatch in {path.name}: {ordered_members}",
        )


def result_documents(expected: dict[str, Any]) -> dict[str, Any]:
    documents: dict[str, Any] = {}
    for item in expected["resultingDocuments"]:
        document_id = item["documentId"]
        require(document_id not in documents, f"duplicate resulting document: {document_id}")
        documents[document_id] = {
            "documentId": document_id,
            "blueId": item["afterBlueId"],
            "document": item["document"],
            "initialized": item["initialized"],
            "terminated": item["terminated"],
            "publicRoot": item["publicRoot"],
            "epoch": item["epoch"],
            "componentGeneration": item["componentGeneration"],
        }
    return documents


def oracle_stage_candidates(value: Any) -> list[dict[str, Any]]:
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


def validate_tentative_finalizations(
    path: Path, fixture: dict[str, Any], expected: dict[str, Any]
) -> None:
    finalizations = expected["tentativeFinalizations"]
    require(
        [item["ordinal"] for item in finalizations]
        == list(range(len(finalizations))),
        f"tentative-finalization ordinals are not contiguous in {path.name}",
    )
    if not finalizations:
        return
    oracle_reference = fixture.get("oracle")
    require(
        isinstance(oracle_reference, str),
        f"tentative finalization lacks an oracle in {path.name}",
    )
    oracle_path = (path.parent / oracle_reference).resolve()
    try:
        oracle_path.relative_to(ORACLES.resolve())
    except ValueError as exc:
        raise ValidationFailure(
            f"cyclic oracle escapes the oracle package in {path.name}: {oracle_reference}"
        ) from exc
    stages = oracle_stage_candidates(load_yaml(oracle_path))
    for finalization in finalizations:
        matches = [
            stage
            for stage in stages
            if stage["name"] == finalization["oracleStage"]
            and {
                document["documentId"]
                for document in stage["sourceDocumentsWithThisReferences"]
            }
            == set(finalization["memberBlueIds"])
        ]
        require(
            len(matches) == 1,
            f"cannot select exact tentative-finalization oracle in {path.name}: "
            f"{finalization['oracleStage']}",
        )
        stage = matches[0]
        oracle = cyclic_set_oracle(stage["sourceDocumentsWithThisReferences"])
        document_ids = [
            document["documentId"]
            for document in stage["sourceDocumentsWithThisReferences"]
        ]
        member_blue_ids = dict(
            zip(document_ids, oracle.member_ids_in_source_order(), strict=True)
        )
        require(
            finalization["masterBlueId"] == oracle.master_blue_id
            and finalization["memberBlueIds"] == member_blue_ids
            and finalization["canonicalBytes"]
            == cyclic_canonical_limit_bytes(oracle),
            f"tentative-finalization oracle mismatch in {path.name}: "
            f"{finalization['oracleStage']}",
        )


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
    require(
        len({(item["sourceDocumentId"], item["sourcePath"]) for item in before})
        == len(before)
        and len({(item["sourceDocumentId"], item["sourcePath"]) for item in after})
        == len(after),
        "occurrence set repeats a source path",
    )
    changes: list[dict[str, Any]] = []
    for source_document_id, source_path in sorted(
        set(before_by_path) | set(after_by_path)
    ):
        old = before_by_path.get((source_document_id, source_path))
        new = after_by_path.get((source_document_id, source_path))
        if old == new:
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
        changes.append(
            {
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
            }
        )
    return changes


def root_subscription_channels(document: Any) -> dict[str, Any]:
    if not isinstance(document, dict) or not isinstance(document.get("contracts"), dict):
        return {}
    result: dict[str, Any] = {}
    for key, contract in document["contracts"].items():
        if isinstance(contract, dict) and type_blue_id(contract) in SUBSCRIPTION_CHANNEL_TYPES:
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
    occurrence_basis = {
        "managedDocumentId": document_id,
        "scopePath": "/",
        "scopeActivationGeneration": 0,
        "rawChannelKey": channel_key,
        "effectiveRuntimeContributionBlueId": contribution_blue_id,
        "subscriptionHeaderBlueId": header_blue_id,
    }
    occurrence_identity = domain_identity(
        "blue-contracts-channel-occurrence/1.0", occurrence_basis
    )
    subscription_identity = domain_identity(
        "blue-contracts-subscription/1.0",
        {
            "channelOccurrenceIdentity": occurrence_identity,
            "documentBlueId": document_record["blueId"],
            "graphGeneration": graph_generation,
            "componentGeneration": document_record["componentGeneration"],
        },
    )
    return {
        "subscriptionIdentity": subscription_identity,
        "channelOccurrence": {
            "channelOccurrenceIdentity": occurrence_identity,
            **occurrence_basis,
        },
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
        for key, contract in root_subscription_channels(record["document"]).items():
            before_states[(document_id, key)] = subscription_state(
                document_id, record, key, contract, before_graph_generation
            )
    for document_id, record in after_documents.items():
        for key, contract in root_subscription_channels(record["document"]).items():
            after_states[(document_id, key)] = subscription_state(
                document_id, record, key, contract, after_graph_generation
            )
    deltas: list[dict[str, Any]] = []
    for document_id, key in sorted(set(before_states) | set(after_states)):
        old = before_states.get((document_id, key))
        new = after_states.get((document_id, key))
        if old == new:
            continue
        operation = "ADD" if old is None else "REMOVE" if new is None else "REPLACE"
        exemplar = new or old
        assert exemplar is not None
        deltas.append(
            {
                "subscriptionDeltaOrdinal": len(deltas),
                "operation": operation,
                "targetManagedScopeIdentity": domain_identity(
                    "blue-contracts-managed-scope-key/1.0",
                    {
                        "documentId": document_id,
                        "scopePath": "/",
                        "activationGeneration": 0,
                    },
                ),
                "channelOccurrenceIdentity": exemplar["channelOccurrence"][
                    "channelOccurrenceIdentity"
                ],
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
            }
        )
    return deltas


def validate_channels_and_handlers(path: Path, data: dict[str, Any]) -> tuple[int, int]:
    documents = data["input"]["documents"]
    channels = 0
    handlers = 0
    for delivery in data["input"].get("directDeliveries", []):
        contracts = contracts_at_scope(documents[delivery["targetDocumentId"]]["document"], delivery["scopePath"])
        contract = contracts.get(delivery["channelKey"])
        require(type_blue_id(contract) == SCRIPTED_EXTERNAL, f"direct delivery does not target ScriptedExternalChannel in {path.name}: {delivery}")
        channels += 1
    for bucket in ("handlers", "initializationHandlers"):
        for qualified in data["input"].get("runtime", {}).get(bucket, {}):
            require("/" in qualified, f"runtime handler key must be document/key in {path.name}: {qualified}")
            document_id, key = qualified.split("/", 1)
            require(document_id in documents, f"runtime handler document missing in {path.name}: {qualified}")
            contracts = documents[document_id]["document"].get("contracts", {})
            contract = contracts.get(key)
            require(type_blue_id(contract) == SCRIPTED_HANDLER, f"runtime handler is not a ScriptedHandler in {path.name}: {qualified}")
            channel_key = contract.get("channel")
            require(isinstance(channel_key, str) and channel_key in contracts, f"runtime Handler channel missing in {path.name}: {qualified}")
            handlers += 1
    return channels, handlers


def validate_fixture_environment(path: Path, fixture_input: dict[str, Any]) -> dict[str, Any]:
    environment = fixture_input["environment"]
    release = load_yaml(RELEASE)
    gas_manifest = load_yaml(GAS)
    require(
        environment["blueLanguageSpecificationIdentity"] == "sha256:" + sha256_file(LANG_SPEC),
        f"Blue Language specification identity mismatch in {path.name}",
    )
    require(
        environment["contractsSpecificationIdentity"] == "sha256:" + sha256_file(SPEC),
        f"Contracts specification identity mismatch in {path.name}",
    )
    require(
        environment["runtimeRegistryIdentity"] == release["contractsRegistry"]["packageIdentity"],
        f"runtime registry identity mismatch in {path.name}",
    )
    require(
        environment["gasManifestIdentity"] == "sha256:" + sha256_file(GAS),
        f"gas-manifest byte identity mismatch in {path.name}",
    )
    require(
        environment["cyclicFinalizerIdentity"]
        == release["languageDependency"]["cyclicSetFinalizerBaselineIdentity"],
        f"cyclic finalizer identity mismatch in {path.name}",
    )
    require(
        environment["cyclicProofVerifierIdentity"]
        == release["languageDependency"]["cyclicSetProofVerifierBaselineIdentity"],
        f"cyclic proof verifier identity mismatch in {path.name}",
    )
    policy_domains = {
        "managedDocumentIdentityPolicy": "blue-contracts-managed-document-identity-policy/1.0",
        "managedBindingPolicy": "blue-contracts-managed-binding-policy/1.0",
        "exactNodeProviderDomain": "blue-contracts-exact-node-provider-domain/1.0",
        "externalOrderPolicy": "blue-contracts-external-order-policy/1.0",
    }
    for field, domain in policy_domains.items():
        policy = environment[field]
        require(
            policy["identity"] == domain_identity(domain, {"label": policy["label"]}),
            f"{field} identity mismatch in {path.name}",
        )
    portable_policy = environment["portableLimitPolicy"]
    expected_limits = [
        {"name": name, "value": value}
        for name, value in sorted(gas_manifest["portableLimits"].items())
    ]
    require(
        portable_policy["limits"] == expected_limits,
        f"portable-limit policy does not bind the complete gas manifest in {path.name}",
    )
    require(
        portable_policy["identity"]
        == domain_identity(
            "blue-contracts-portable-limit-policy/1.0",
            {"label": portable_policy["label"], "limits": expected_limits},
        ),
        f"portable-limit policy identity mismatch in {path.name}",
    )
    return environment


def validate_cause(path: Path, cause: dict[str, Any], environment: dict[str, Any]) -> None:
    if cause["kind"] == "external":
        require(
            cause["externalOrderPolicyIdentity"]
            == environment["externalOrderPolicy"]["identity"],
            f"external cause policy is not the invocation policy in {path.name}",
        )
        expected_cause = domain_identity(
            "blue-contracts-external-cause/1.0",
            {
                "eventBlueId": cause["eventBlueId"],
                "sourceOrder": cause["sourceOrder"],
                "externalOrderPolicyIdentity": cause["externalOrderPolicyIdentity"],
            },
        )
        require(
            direct_blue_id(cause["event"]) == cause["eventBlueId"],
            f"external event BlueId mismatch in {path.name}",
        )
    else:
        expected_policy = domain_identity(
            "blue-contracts-admission-policy/1.0", {"label": cause["label"]}
        )
        require(
            cause["policyIdentity"] == expected_policy,
            f"admission policy identity mismatch in {path.name}",
        )
        expected_cause = domain_identity(
            "blue-contracts-admission-cause/1.0",
            {
                "admissionKind": cause["admissionKind"],
                "label": cause["label"],
                "triggeringEventBlueId": cause["triggeringEventBlueId"],
                "parentTransitionIdentity": cause["parentTransitionIdentity"],
                "policyIdentity": cause["policyIdentity"],
            },
        )
    require(
        cause["causeIdentity"] == expected_cause,
        f"cause identity mismatch in {path.name}",
    )


def validate_gas_policy(path: Path, fixture_input: dict[str, Any]) -> dict[str, Any]:
    gas_policy = fixture_input["gasPolicy"]
    configured_local_limits = gas_policy.get("localLimits", {})
    for document_id, limit in configured_local_limits.items():
        require(
            document_id in fixture_input["documents"],
            f"local gas limit names an unmanaged document in {path.name}: {document_id}",
        )
        require(
            limit <= gas_policy["sharedLimit"],
            f"local gas limit exceeds shared limit in {path.name}: {document_id}",
        )
    local_limits = [
        {"documentId": document_id, "limit": configured_local_limits[document_id]}
        for document_id in sorted(configured_local_limits)
    ]
    expected_policy = domain_identity(
        "blue-contracts-execution-policy/1.0",
        {
            "sharedLimit": gas_policy["sharedLimit"],
            "localLimits": local_limits,
            "label": gas_policy["label"],
        },
    )
    require(
        gas_policy["policyIdentity"] == expected_policy,
        f"execution policy identity mismatch in {path.name}",
    )
    return gas_policy


def occurrence_binding_matches_documents(
    documents: dict[str, Any], occurrence: dict[str, Any]
) -> bool:
    if not occurrence.get("active"):
        return True
    source_id = occurrence["sourceDocumentId"]
    target_id = occurrence["targetDocumentId"]
    if source_id not in documents or target_id not in documents:
        return False
    source_document = documents[source_id]["document"]
    try:
        node = pointer_get(source_document, occurrence["sourcePath"])
    except (KeyError, ValidationFailure):
        return False
    return (
        active_declared_path(source_document, occurrence["sourcePath"])
        and isinstance(node, dict)
        and set(node) == {"blueId"}
        and node["blueId"] == occurrence["expectedTargetBlueId"]
        and documents[target_id]["blueId"] == occurrence["expectedTargetBlueId"]
    )


def validate_admission_candidate(
    path: Path, fixture_input: dict[str, Any]
) -> str | None:
    candidate = fixture_input["admissionCandidate"]
    declared_identity = fixture_input["admissionCandidateIdentity"]
    if candidate is None:
        require(
            declared_identity is None,
            f"null admission candidate carries an identity in {path.name}",
        )
        return None

    require(
        fixture_input["cause"]["kind"] == "admission",
        f"external invocation carries admission-candidate evidence in {path.name}",
    )
    candidate_identity = domain_identity(
        "blue-contracts-admission-candidate/1.0", candidate
    )
    require(
        declared_identity == candidate_identity,
        f"admission-candidate identity mismatch in {path.name}",
    )
    kind = candidate["kind"]
    evidence = candidate["evidence"]
    if kind == "BAD_CYCLIC_PROOF":
        proof = evidence["candidateCyclicProof"]
        require(
            isinstance(proof, dict),
            f"bad-proof candidate is not complete object evidence in {path.name}",
        )
        matching = [
            component
            for component in fixture_input["components"]
            if component["componentIdentity"] == proof.get("componentIdentity")
        ]
        require(
            len(matching) == 1
            and matching[0]["kind"] == "CYCLIC"
            and proof != matching[0]["completeCyclicProof"],
            f"bad-proof candidate does not select one invalid cyclic proof in {path.name}",
        )
    elif kind == "AMBIGUOUS_PRELIMINARY_MEMBERS":
        members = evidence["candidateCyclicMembers"]
        require(
            [member["documentId"] for member in members]
            == sorted(member["documentId"] for member in members)
            and len({member["documentId"] for member in members}) == len(members),
            f"candidate cyclic members are not canonically ordered in {path.name}",
        )
        try:
            cyclic_set_oracle([member["document"] for member in members])
        except ValueError as exc:
            require(
                "Duplicate preliminary cyclic BlueId" in str(exc),
                f"ambiguous-member candidate failed for another reason in {path.name}: {exc}",
            )
        else:
            raise ValidationFailure(
                f"ambiguous-member candidate has unique preliminary identities in {path.name}"
            )
    elif kind == "INVALID_OCCURRENCE_BINDING":
        occurrences = evidence["candidateOccurrenceBindings"]
        require(
            occurrences
            == sorted(
                occurrences,
                key=lambda item: (
                    item["occurrenceIdentity"], item["bindingIdentity"]
                ),
            ),
            f"candidate occurrence bindings are not canonically ordered in {path.name}",
        )
        binding_policy_identity = fixture_input["environment"][
            "managedBindingPolicy"
        ]["identity"]
        for occurrence in occurrences:
            require(
                occurrence["bindingPolicyIdentity"] == binding_policy_identity,
                f"candidate occurrence uses the wrong binding policy in {path.name}",
            )
            validate_occurrence_identity(path, occurrence)
        require(
            any(
                not occurrence_binding_matches_documents(
                    fixture_input["documents"], occurrence
                )
                for occurrence in occurrences
            ),
            f"invalid-occurrence candidate contains no invalid binding in {path.name}",
        )
    else:
        raise ValidationFailure(f"unknown admission-candidate kind in {path.name}: {kind}")
    return candidate_identity


def validate_work_trace(
    path: Path,
    fixture_input: dict[str, Any],
    expected: dict[str, Any],
    ordered_direct: list[tuple[dict[str, Any], str]],
    invocation_identity: str,
) -> set[str]:
    work_trace = expected["workTrace"]
    require(
        [work["ordinal"] for work in work_trace] == list(range(len(work_trace))),
        f"work ordinals not contiguous in {path.name}",
    )
    rejected_work = expected.get("rejectedWorkOccurrence")
    if rejected_work is not None:
        require(
            rejected_work["ordinal"] == len(work_trace),
            f"rejected work ordinal mismatch in {path.name}",
        )
    all_work = list(work_trace)
    if rejected_work is not None:
        all_work.append(rejected_work)
    historical = fixture_input.get("historicalTransitions", [])
    valid_work_ids: set[str] = set()
    for work_item in all_work:
        scope_identity = domain_identity(
            "blue-contracts-managed-scope-key/1.0",
            {
                "documentId": work_item["targetDocumentId"],
                "scopePath": "/",
                "activationGeneration": 0,
            },
        )
        require(
            work_item["targetManagedScopeIdentity"] == scope_identity,
            f"target managed-scope identity mismatch in {path.name}: work {work_item['ordinal']}",
        )
        if work_item["kind"] == "EXTERNAL_DELIVERY":
            source_matches = [
                identity
                for delivery, identity in ordered_direct
                if delivery["targetDocumentId"] == work_item["targetDocumentId"]
                and delivery["channelKey"] == work_item["channelKey"]
            ]
            require(
                len(source_matches) == 1,
                f"external work source is ambiguous in {path.name}: {work_item}",
            )
            source_identity = source_matches[0]
        elif work_item["kind"] in {"TRIGGERED_EVENT", "EMBEDDED_EVENT"}:
            require(
                "eventBlueId" in work_item and "occurrenceOrdinal" in work_item,
                f"event work lacks exact source fields in {path.name}: {work_item}",
            )
            source_identity = domain_identity(
                "blue-contracts-event-occurrence/1.0",
                {
                    "invocationIdentity": invocation_identity,
                    "eventOccurrenceOrdinal": work_item["occurrenceOrdinal"],
                    "eventBlueId": work_item["eventBlueId"],
                },
            )
        elif work_item["kind"] == "HISTORICAL_TRANSITION":
            transition_matches = [
                transition
                for transition in historical
                if transition["transitionIdentity"]
                == work_item["sourceOccurrenceIdentity"]
            ]
            require(
                len(transition_matches) == 1,
                f"historical work does not select one exact transition in {path.name}: "
                f"work {work_item['ordinal']}",
            )
            source_identity = transition_matches[0]["transitionIdentity"]
        else:
            source_identity = fixture_input["cause"]["causeIdentity"]
        require(
            work_item["sourceOccurrenceIdentity"] == source_identity,
            f"work source occurrence identity mismatch in {path.name}: work {work_item['ordinal']}",
        )
        work_identity = domain_identity(
            "blue-contracts-work-occurrence/1.0",
            {
                "invocationIdentity": invocation_identity,
                "workOrdinal": work_item["ordinal"],
                "workKind": work_item["kind"],
                "targetManagedScopeIdentity": scope_identity,
                "sourceOccurrenceIdentity": source_identity,
            },
        )
        require(
            work_item["workIdentity"] == work_identity,
            f"work occurrence identity mismatch in {path.name}: work {work_item['ordinal']}",
        )
        require(
            work_identity not in valid_work_ids,
            f"duplicate work occurrence identity in {path.name}: {work_identity}",
        )
        valid_work_ids.add(work_identity)
    return valid_work_ids


def checkpoint_domain_value(contract: dict[str, Any]) -> dict[str, Any]:
    effective_type = type_blue_id(contract)
    require(
        isinstance(effective_type, str),
        "checkpoint Channel lacks an exact effective runtime type",
    )
    value: dict[str, Any] = {
        "contractsVersion": "1.0",
        "effectiveTypeBlueId": effective_type,
        "sourceContributionNodeBlueIds": [direct_blue_id(contract)],
    }
    dependencies = contract.get("deterministicDependencyNodeBlueIds")
    if dependencies is not None:
        require(
            isinstance(dependencies, list)
            and dependencies
            and all(isinstance(item, str) and valid_exact_blue_id(item) for item in dependencies),
            "checkpoint deterministic dependencies must be a nonempty exact BlueId list",
        )
        value["deterministicDependencyNodeBlueIds"] = list(dependencies)
    discriminator = contract.get("checkpointDomain")
    if discriminator is not None:
        require(
            isinstance(discriminator, str),
            "checkpoint runtime discriminator must be Text",
        )
        if discriminator:
            value["runtimeDiscriminator"] = discriminator
    return value


def checkpoint_entry(
    document: dict[str, Any], raw_channel_key: str
) -> dict[str, Any] | None:
    marker = document.get("contracts", {}).get("checkpoint")
    if marker is None:
        return None
    return marker["entries"].get(raw_channel_key)


def derive_checkpoint_writes(
    path: Path,
    fixture_input: dict[str, Any],
    expected: dict[str, Any],
    output_documents: dict[str, Any],
) -> list[dict[str, Any]]:
    writes = expected["checkpointWrites"]
    require(
        [write["checkpointWriteOrdinal"] for write in writes]
        == list(range(len(writes))),
        f"checkpoint-write ordinals are not contiguous in {path.name}",
    )
    scope_to_document = {
        domain_identity(
            "blue-contracts-managed-scope-key/1.0",
            {
                "documentId": document_id,
                "scopePath": "/",
                "activationGeneration": 0,
            },
        ): document_id
        for document_id in fixture_input["documents"]
    }
    actual_changes: set[tuple[str, str]] = set()
    for document_id in sorted(fixture_input["documents"]):
        before_document = fixture_input["documents"][document_id]["document"]
        after_document = output_documents[document_id]["document"]
        before_marker = before_document.get("contracts", {}).get("checkpoint")
        after_marker = after_document.get("contracts", {}).get("checkpoint")
        before_entries = before_marker["entries"] if before_marker is not None else {}
        after_entries = after_marker["entries"] if after_marker is not None else {}
        scope_identity = domain_identity(
            "blue-contracts-managed-scope-key/1.0",
            {
                "documentId": document_id,
                "scopePath": "/",
                "activationGeneration": 0,
            },
        )
        for raw_key in set(before_entries) | set(after_entries):
            if before_entries.get(raw_key) != after_entries.get(raw_key):
                actual_changes.add((scope_identity, raw_key))

    receipt_keys: set[tuple[str, str]] = set()
    for write in writes:
        scope_identity = write["targetManagedScopeIdentity"]
        require(
            scope_identity in scope_to_document,
            f"checkpoint receipt names an unknown managed scope in {path.name}",
        )
        document_id = scope_to_document[scope_identity]
        raw_key = write["rawChannelKey"]
        key = (scope_identity, raw_key)
        require(
            key not in receipt_keys,
            f"duplicate checkpoint receipt in {path.name}: {document_id}/{raw_key}",
        )
        receipt_keys.add(key)
        before_entry = checkpoint_entry(
            fixture_input["documents"][document_id]["document"], raw_key
        )
        after_entry = checkpoint_entry(
            output_documents[document_id]["document"], raw_key
        )

        def validate_side(side: str, entry: dict[str, Any] | None) -> None:
            present = write[f"{side}Present"]
            domain_blue_id = write[f"{side}DomainBlueId"]
            domain_value = write[f"{side}DomainValue"]
            subject_blue_id = write[f"{side}SubjectBlueId"]
            require(
                present is (entry is not None),
                f"checkpoint {side} presence mismatch in {path.name}: "
                f"{document_id}/{raw_key}",
            )
            if entry is None:
                require(
                    domain_blue_id is None
                    and domain_value is None
                    and subject_blue_id is None,
                    f"absent checkpoint {side} side carries evidence in {path.name}: "
                    f"{document_id}/{raw_key}",
                )
                return
            require(
                isinstance(domain_value, dict)
                and isinstance(domain_value.get("sourceContributionNodeBlueIds"), list)
                and bool(domain_value["sourceContributionNodeBlueIds"])
                and domain_blue_id
                == (
                    pure_blue_reference(entry["domain"])
                    or direct_blue_id(entry["domain"])
                )
                and direct_blue_id(domain_value) == domain_blue_id
                and (
                    pure_blue_reference(entry["domain"]) is not None
                    or domain_value == entry["domain"]
                )
                and subject_blue_id == entry["subject"]["blueId"],
                f"checkpoint {side} value/reference mismatch in {path.name}: "
                f"{document_id}/{raw_key}",
            )

        validate_side("before", before_entry)
        validate_side("after", after_entry)
        require(
            before_entry != after_entry,
            f"checkpoint receipt describes a no-op in {path.name}: {document_id}/{raw_key}",
        )

    require(
        receipt_keys == actual_changes,
        f"checkpoint receipts do not cover the exact direct marker diff in {path.name}",
    )
    if expected["status"] != "success":
        require(
            writes == [],
            f"noncommitting result carries checkpoint receipts in {path.name}",
        )
        return writes

    event_blue_id = fixture_input["cause"].get("eventBlueId")
    for work_item in expected["workTrace"]:
        if work_item["kind"] != "EXTERNAL_DELIVERY":
            continue
        document_id = work_item["targetDocumentId"]
        contract = fixture_input["documents"][document_id]["document"].get(
            "contracts", {}
        ).get(work_item["channelKey"])
        require(
            isinstance(contract, dict) and isinstance(event_blue_id, str),
            f"external checkpoint lacks exact Channel/event evidence in {path.name}",
        )
        matching = [
            write
            for write in writes
            if write["targetManagedScopeIdentity"]
            == work_item["targetManagedScopeIdentity"]
            and write["rawChannelKey"] == work_item["channelKey"]
        ]
        domain_value = checkpoint_domain_value(contract)
        require(
            len(matching) == 1
            and matching[0]["afterPresent"] is True
            and matching[0]["afterDomainValue"] == domain_value
            and matching[0]["afterDomainBlueId"] == direct_blue_id(domain_value)
            and matching[0]["afterSubjectBlueId"] == event_blue_id,
            f"accepted external work is not bound to its frozen checkpoint write "
            f"in {path.name}: work {work_item['ordinal']}",
        )
    return writes


def validate_gas_result(
    path: Path,
    fixture_input: dict[str, Any],
    expected: dict[str, Any],
    gas_policy: dict[str, Any],
    valid_work_ids: set[str],
    components: list[dict[str, Any]],
) -> None:
    trace = expected_gas_trace(path, expected)
    gas_manifest = load_yaml(GAS)
    weights = {
        namespace: payload.get("counters", {})
        for namespace, payload in gas_manifest["namespaces"].items()
    }
    require(
        [entry["sequence"] for entry in trace] == list(range(len(trace))),
        f"gas trace sequence mismatch in {path.name}",
    )
    enqueued_events = [
        entry for entry in trace if entry["counter"] == "internalEventEnqueued"
    ]
    dequeued_events = [
        entry for entry in trace if entry["counter"] == "internalEventDequeued"
    ]
    rejected_counter = (expected.get("rejectedCharge") or {}).get("counter")
    rejected_dequeue = 1 if rejected_counter == "internalEventDequeued" else 0
    require(
        all(entry["quantity"] == 1 for entry in enqueued_events + dequeued_events)
        and len(dequeued_events) + rejected_dequeue == len(enqueued_events),
        f"internal event occurrences are not enqueued/dequeued exactly once in {path.name}",
    )
    dequeue_by_ordinal: dict[int, dict[str, Any]] = {}
    for entry in dequeued_events:
        match = re.fullmatch(
            r"event\.(\d+)\.dequeue(?:-zero-target)?", entry.get("reason", "")
        )
        require(
            match is not None
            and entry.get("workOccurrenceId") is None
            and entry.get("documentId") is None,
            f"internal event dequeue is not invocation-owned in {path.name}: "
            f"sequence {entry['sequence']}",
        )
        occurrence_ordinal = int(match.group(1))
        require(
            occurrence_ordinal not in dequeue_by_ordinal,
            f"event occurrence is dequeued more than once in {path.name}: "
            f"{occurrence_ordinal}",
        )
        dequeue_by_ordinal[occurrence_ordinal] = entry
    for enqueue, dequeue in zip(enqueued_events, dequeued_events):
        require(
            enqueue["sequence"] < dequeue["sequence"],
            f"internal event dequeued before its FIFO admission in {path.name}",
        )
    event_work = [
        item
        for item in expected["workTrace"]
        + ([expected["rejectedWorkOccurrence"]] if expected.get("rejectedWorkOccurrence") else [])
        if item["kind"] in {"TRIGGERED_EVENT", "EMBEDDED_EVENT"}
    ]
    event_ordinals = sorted({item["occurrenceOrdinal"] for item in event_work})
    require(
        all(ordinal < len(enqueued_events) for ordinal in event_ordinals),
        f"event delivery refers to an occurrence never enqueued in {path.name}",
    )
    for item in event_work:
        dequeue = dequeue_by_ordinal.get(item["occurrenceOrdinal"])
        delivery_enqueues = [
            entry
            for entry in trace
            if entry["counter"] == "closureWorkOccurrenceEnqueued"
            and entry.get("workOccurrenceId") == item["workIdentity"]
        ]
        if item in expected["workTrace"]:
            require(
                dequeue is not None
                and len(delivery_enqueues) == 1
                and dequeue["sequence"] < delivery_enqueues[0]["sequence"],
                f"event delivery work is created before its invocation-owned "
                f"dequeue in {path.name}: work {item['ordinal']}",
            )
    for work_item in expected["workTrace"]:
        work_finalization_prefixes = [
            f"work.{work_item['ordinal']}.{finalization['oracleStage']}."
            for finalization in expected["tentativeFinalizations"]
            if finalization["boundary"]
            == {"kind": "WORK", "afterWorkOrdinal": work_item["ordinal"]}
        ]
        finalization_entries_for_work = [
            entry
            for entry in trace
            if any(
                entry.get("reason", "").startswith(prefix)
                for prefix in work_finalization_prefixes
            )
        ]
        event_admission_for_work = [
            entry
            for entry in trace
            if entry.get("reason", "").startswith(
                f"work.{work_item['ordinal']}.event."
            )
        ]
        if finalization_entries_for_work and event_admission_for_work:
            require(
                max(entry["sequence"] for entry in finalization_entries_for_work)
                < min(entry["sequence"] for entry in event_admission_for_work),
                f"emitted event is admitted before patch finalization in {path.name}: "
                f"work {work_item['ordinal']}",
            )

    external_work = [
        item for item in expected["workTrace"] if item["kind"] == "EXTERNAL_DELIVERY"
    ]
    comparisons = [
        entry for entry in trace if entry["counter"] == "checkpointCompared"
    ]
    require(
        len(comparisons) == len(external_work),
        f"checkpoint comparison count does not match external work in {path.name}",
    )
    for work_item in external_work:
        matching_comparisons = [
            entry
            for entry in comparisons
            if entry.get("workOccurrenceId") == work_item["workIdentity"]
        ]
        require(
            len(matching_comparisons) == 1,
            f"external work lacks one exact checkpoint comparison in {path.name}: "
            f"work {work_item['ordinal']}",
        )
        pre_execution_entries = [
            entry
            for entry in trace
            if entry["counter"]
            in {
                "closureWorkOccurrenceEnqueued",
                "closureWorkOccurrenceDequeued",
                "handlerCall",
                "scopeInitialization",
            }
            and entry.get("workOccurrenceId") == work_item["workIdentity"]
        ]
        if pre_execution_entries:
            require(
                matching_comparisons[0]["sequence"]
                < min(entry["sequence"] for entry in pre_execution_entries),
                f"checkpoint comparison occurs after external work begins in {path.name}: "
                f"work {work_item['ordinal']}",
            )
    if comparisons:
        queue_execution_entries = [
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
        require(
            not queue_execution_entries
            or max(entry["sequence"] for entry in comparisons)
            < min(entry["sequence"] for entry in queue_execution_entries),
            f"Phase-B checkpoint comparisons are not complete before queue "
            f"initialization in {path.name}",
        )
    checkpoint_writes = [
        entry for entry in trace if entry["counter"] == "checkpointWritten"
    ]
    require(
        len(checkpoint_writes) == len(expected["checkpointWrites"]),
        f"checkpoint gas writes do not match committed receipts in {path.name}",
    )
    if checkpoint_writes:
        causal_entries = [
            entry
            for entry in trace
            if entry["counter"] in {
                "handlerCall",
                "scopeInitialization",
                "internalEventDequeued",
                "closureWorkOccurrenceDequeued",
            }
        ]
        require(
            not causal_entries
            or min(entry["sequence"] for entry in checkpoint_writes)
            > max(entry["sequence"] for entry in causal_entries),
            f"checkpoint write occurs before the causal queue is quiescent in {path.name}",
        )
    scope_documents = {
        domain_identity(
            "blue-contracts-managed-scope-key/1.0",
            {
                "documentId": document_id,
                "scopePath": "/",
                "activationGeneration": 0,
            },
        ): document_id
        for document_id in fixture_input["documents"]
    }
    external_by_checkpoint_key = {
        (item["targetManagedScopeIdentity"], item["channelKey"]): item
        for item in external_work
    }
    for ordinal, (entry, receipt) in enumerate(
        zip(checkpoint_writes, expected["checkpointWrites"])
    ):
        scope_identity = receipt["targetManagedScopeIdentity"]
        document_id = scope_documents.get(scope_identity)
        source_work = external_by_checkpoint_key.get(
            (scope_identity, receipt["rawChannelKey"])
        )
        require(
            document_id is not None
            and entry["quantity"] == 1
            and entry.get("documentId") == document_id
            and (
                source_work is not None
                or entry.get("contractKey") == receipt["rawChannelKey"]
            ),
            f"checkpoint gas write does not match receipt {ordinal} in {path.name}",
        )
        require(
            entry.get("workOccurrenceId")
            == (source_work["workIdentity"] if source_work is not None else None),
            f"checkpoint gas write has the wrong source owner for receipt {ordinal} "
            f"in {path.name}",
        )

    finalization_positions: list[int] = []
    checkpoint_boundary_seen = False
    work_by_ordinal = {item["ordinal"]: item for item in expected["workTrace"]}
    for finalization in expected["tentativeFinalizations"]:
        boundary = finalization["boundary"]
        kind = boundary["kind"]
        if kind == "WORK":
            require(
                not checkpoint_boundary_seen
                and boundary["afterWorkOrdinal"] in work_by_ordinal,
                f"invalid work finalization boundary in {path.name}: {finalization['ordinal']}",
            )
            prefix = f"work.{boundary['afterWorkOrdinal']}"
            owner_work_id = work_by_ordinal[boundary["afterWorkOrdinal"]]["workIdentity"]
        elif kind == "INITIALIZATION_BATCH":
            initialization_ordinals = [
                item["ordinal"]
                for item in expected["workTrace"]
                if item["kind"] in {"INITIALIZATION", "LIFECYCLE"}
            ]
            require(
                not checkpoint_boundary_seen
                and initialization_ordinals
                and boundary["afterWorkOrdinal"] == max(initialization_ordinals),
                f"initialization finalization is not bound to the last causal "
                f"initialization/lifecycle work in {path.name}",
            )
            prefix = "initialization-batch"
            owner_work_id = None
        elif kind == "CHECKPOINT_SETTLEMENT":
            checkpoint_boundary_seen = True
            prefix = "checkpoint-settlement"
            owner_work_id = None
        else:
            raise ValidationFailure(
                f"unknown finalization boundary in {path.name}: {kind}"
            )
        reason = f"{prefix}.{finalization['oracleStage']}.finalization-boundary"
        matches = [
            entry
            for entry in trace
            if entry["counter"] == "tentativeComponentFinalization"
            and entry.get("reason") == reason
        ]
        require(
            len(matches) == 1
            and matches[0].get("workOccurrenceId") == owner_work_id,
            f"gas trace does not bind finalization {finalization['ordinal']} to its "
            f"exact boundary in {path.name}",
        )
        if kind == "CHECKPOINT_SETTLEMENT" and checkpoint_writes:
            require(
                matches[0]["sequence"]
                > max(entry["sequence"] for entry in checkpoint_writes),
                f"checkpoint component finalized before its batched marker write in {path.name}",
            )
        finalization_positions.append(matches[0]["sequence"])
    require(
        finalization_positions == sorted(finalization_positions),
        f"tentative finalizations are not traced in ordinal order in {path.name}",
    )

    if fixture_input["admissionCandidate"] is not None:
        candidate_kind = fixture_input["admissionCandidate"]["kind"]
        expected_candidate_shape: list[tuple[str, str, int]] = [
            ("processor", "processInvocation", 1),
            ("processor", "closureInvocation", 1),
        ]
        if fixture_input.get("directDeliveries"):
            expected_candidate_shape.append(
                (
                    "processor",
                    "deliverySnapshotEntry",
                    len(fixture_input["directDeliveries"]),
                )
            )
        expected_candidate_shape.extend(
            ("processor", "managedDocumentOpened", 1)
            for _document_id in sorted(fixture_input["documents"])
        )
        ordered_occurrences = sorted(
            fixture_input.get("occurrences", []),
            key=lambda item: (
                item["occurrenceIdentity"],
                item["bindingIdentity"],
            ),
        )
        for occurrence in ordered_occurrences:
            expected_candidate_shape.append(
                ("processor", "managedOccurrenceBindingVerified", 1)
            )
            if occurrence.get("active"):
                expected_candidate_shape.append(
                    ("processor", "processEmbeddedEdgeExamined", 1)
                )
        active_edges = [
            occurrence
            for occurrence in fixture_input.get("occurrences", [])
            if occurrence.get("active")
        ]
        for component in fixture_input["components"]:
            members = set(component["orderedMemberDocumentIds"])
            expected_candidate_shape.extend(
                ("processor", "componentMemberPartitioned", 1)
                for _document_id in component["orderedMemberDocumentIds"]
            )
            expected_candidate_shape.extend(
                ("processor", "componentEdgePartitioned", 1)
                for edge in active_edges
                if edge["sourceDocumentId"] in members
                and edge["targetDocumentId"] in members
            )
        candidate_suffixes: dict[str, tuple[int, list[tuple[str, str, int]]]] = {
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
        frozen_total, candidate_suffix = candidate_suffixes[candidate_kind]
        expected_candidate_shape.extend(candidate_suffix)
        require(
            [
                (entry["namespace"], entry["counter"], entry["quantity"])
                for entry in trace
            ]
            == expected_candidate_shape
            and expected["totalGas"] == frozen_total
            and expected["status"] == "invalid-processing-document"
            and expected["rollbackToInput"] is True
            and expected["workTrace"] == []
            and expected["tentativeFinalizations"] == []
            and expected["checkpointWrites"] == []
            and expected["publicEvents"] == [],
            f"admission-candidate trace is not the exact frozen semantic prefix "
            f"in {path.name}",
        )
    shared_limit = gas_policy["sharedLimit"]
    shared_used = 0
    local_used = {document_id: 0 for document_id in gas_policy.get("localLimits", {})}
    for entry in trace:
        namespace = entry["namespace"]
        counter = entry["counter"]
        require(
            namespace in weights and counter in weights[namespace],
            f"unknown gas counter in {path.name}: {namespace}.{counter}",
        )
        require(
            entry["weight"] == weights[namespace][counter],
            f"gas weight mismatch in {path.name}: {namespace}.{counter}",
        )
        require(
            entry["quantity"] * entry["weight"] == entry["subtotal"],
            f"gas subtotal mismatch in {path.name}: sequence {entry['sequence']}",
        )
        require(
            entry["subtotal"] <= shared_limit - shared_used,
            f"admitted gas exceeds shared cap in {path.name}: sequence {entry['sequence']}",
        )
        document_id = entry.get("documentId")
        if document_id in local_used:
            local_limit = gas_policy["localLimits"][document_id]
            require(
                entry["subtotal"] <= local_limit - local_used[document_id],
                f"admitted gas exceeds local cap in {path.name}: {document_id}",
            )
            local_used[document_id] += entry["subtotal"]
        shared_used += entry["subtotal"]
        if entry.get("workOccurrenceId") is not None:
            require(
                entry["workOccurrenceId"] in valid_work_ids,
                f"gas trace references unknown work occurrence in {path.name}: {entry['workOccurrenceId']}",
            )
    require(
        shared_used == expected["totalGas"],
        f"gas total mismatch in {path.name}",
    )
    require(
        expected["gasTraceIdentity"]
        == domain_identity("blue-contracts-gas-trace/1.0", trace),
        f"gas trace identity mismatch in {path.name}",
    )
    rejected = expected.get("rejectedCharge")
    if expected["status"] != "gas-limit-exceeded":
        require(rejected is None, f"non-gas result carries rejected charge in {path.name}")
        return
    require(isinstance(rejected, dict), f"gas failure lacks rejected charge in {path.name}")
    namespace = rejected["namespace"]
    counter = rejected["counter"]
    require(
        namespace in weights and counter in weights[namespace],
        f"unknown rejected gas counter in {path.name}: {namespace}.{counter}",
    )
    require(
        rejected["weight"] == weights[namespace][counter],
        f"rejected gas weight mismatch in {path.name}",
    )
    require(
        rejected["quantity"] * rejected["weight"] == rejected["subtotal"],
        f"rejected gas subtotal mismatch in {path.name}",
    )
    owner = rejected["owner"]
    if owner["kind"] == "WORK":
        rejected_work = expected.get("rejectedWorkOccurrence")
        require(
            isinstance(rejected_work, dict)
            and owner["workOccurrenceIdentity"] == rejected_work["workIdentity"],
            f"rejected WORK owner/evidence mismatch in {path.name}",
        )
        require(
            owner["workOccurrenceIdentity"] in valid_work_ids,
            f"rejected WORK owner is unknown in {path.name}",
        )
    elif owner["kind"] == "FINALIZATION":
        require(
            isinstance(owner["finalizationOrdinal"], int)
            and not isinstance(owner["finalizationOrdinal"], bool)
            and 0 <= owner["finalizationOrdinal"] <= len(expected["tentativeFinalizations"])
            and
            any(
                component["componentIdentity"] == owner["componentIdentity"]
                and component["componentGeneration"] == owner["componentGeneration"]
                for component in components
            ),
            f"rejected FINALIZATION owner is unknown in {path.name}",
        )
        require(
            "rejectedWorkOccurrence" not in expected,
            f"FINALIZATION rejection carries work evidence in {path.name}",
        )
    else:
        require(owner == {"kind": "INVOCATION"}, f"invalid rejected owner in {path.name}")
        require(
            "rejectedWorkOccurrence" not in expected,
            f"INVOCATION rejection carries work evidence in {path.name}",
        )
    rejected_basis = {
        key: rejected[key]
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
    }
    require(
        rejected["rejectedChargeIdentity"]
        == domain_identity("blue-contracts-rejected-charge/1.0", rejected_basis),
        f"rejected charge identity mismatch in {path.name}",
    )
    shared_remaining = shared_limit - shared_used
    cap = rejected["applicableCap"]
    if cap == {"kind": "SHARED"}:
        remaining = shared_remaining
    else:
        require(
            isinstance(cap, dict)
            and set(cap) == {"kind", "documentId"}
            and cap.get("kind") == "LOCAL"
            and isinstance(cap.get("documentId"), str),
            f"invalid rejected gas cap in {path.name}: {cap}",
        )
        document_id = cap["documentId"]
        require(
            document_id in local_used,
            f"rejected local gas cap is not configured in {path.name}: {document_id}",
        )
        remaining = gas_policy["localLimits"][document_id] - local_used[document_id]
        require(
            remaining < shared_remaining,
            f"local rejected cap does not have precedence in {path.name}: {document_id}",
        )
    require(
        rejected["remainingBeforeCharge"] == remaining,
        f"rejected gas remaining mismatch in {path.name}",
    )
    require(
        rejected["subtotal"] > remaining,
        f"rejected gas charge would fit in {path.name}",
    )


def validate_commit_companion(
    path: Path,
    fixture_input: dict[str, Any],
    expected: dict[str, Any],
    environment: dict[str, Any],
) -> None:
    companion = expected["platformCommitCompanion"]
    expected_input_components = [
        {
            "componentIdentity": component["componentIdentity"],
            "componentStateIdentity": component["componentStateIdentity"],
            "componentGeneration": component["componentGeneration"],
            "masterBlueId": component.get("masterBlueId"),
        }
        for component in fixture_input["components"]
    ]
    result_components = [
        {
            "componentIdentity": component["componentIdentity"],
            "componentStateIdentity": component["componentStateIdentity"],
            "cyclicProofIdentity": component.get("cyclicProofIdentity"),
        }
        for component in expected["resultingComponents"]
    ]
    result_documents = [
        {
            "documentId": item["documentId"],
            "beforeBlueId": item["beforeBlueId"],
            "afterBlueId": item["afterBlueId"],
        }
        for item in expected["resultingDocuments"]
    ]
    basis = {
        "invocationIdentity": expected["invocationIdentity"],
        "inputClosureIdentity": expected["inputClosureIdentity"],
        "outputClosureIdentity": expected["outputClosureIdentity"],
        "expectedInputGraphGeneration": fixture_input["graphGeneration"],
        "expectedInputDocuments": [
            {
                "documentId": item["documentId"],
                "blueId": item["blueId"],
            }
            for item in document_identity_items(fixture_input["documents"])
        ],
        "expectedInputComponents": expected_input_components,
        "inputOccurrenceBindingSetIdentity": fixture_input[
            "occurrenceBindingSetIdentity"
        ],
        "outputGraphGeneration": expected["graphGeneration"],
        "resultingDocuments": result_documents,
        "resultingComponents": result_components,
        "occurrenceBindingSetIdentity": expected["occurrenceBindingSetIdentity"],
        "graphChangesIdentity": expected["graphChangesIdentity"],
        "checkpointWritesIdentity": expected["checkpointWritesIdentity"],
        "subscriptionDeltasIdentity": expected["subscriptionDeltasIdentity"],
        "publicEventsIdentity": expected["publicEventsIdentity"],
        "gasTraceIdentity": expected["gasTraceIdentity"],
        "blueLanguageSpecificationIdentity": environment[
            "blueLanguageSpecificationIdentity"
        ],
        "contractsSpecificationIdentity": environment[
            "contractsSpecificationIdentity"
        ],
        "managedDocumentIdentityPolicyIdentity": environment[
            "managedDocumentIdentityPolicy"
        ]["identity"],
        "managedBindingPolicyIdentity": environment["managedBindingPolicy"][
            "identity"
        ],
        "exactNodeProviderDomainIdentity": environment["exactNodeProviderDomain"][
            "identity"
        ],
        "externalOrderPolicyIdentity": environment["externalOrderPolicy"][
            "identity"
        ],
        "runtimeRegistryIdentity": environment["runtimeRegistryIdentity"],
        "gasManifestIdentity": environment["gasManifestIdentity"],
        "portableLimitPolicyIdentity": environment["portableLimitPolicy"][
            "identity"
        ],
        "cyclicFinalizerIdentity": environment["cyclicFinalizerIdentity"],
        "cyclicProofVerifierIdentity": environment["cyclicProofVerifierIdentity"],
    }
    require(
        companion == {
            "companionIdentity": domain_identity(
                "blue-contracts-platform-commit-companion/1.0", basis
            ),
            **basis,
        },
        f"platform commit companion mismatch in {path.name}",
    )


def validate_closure_fixture(path: Path) -> dict[str, int]:
    data = load_yaml(path)
    if data["operation"] == "limit-micro":
        gas_manifest = load_yaml(GAS)
        fixture_input = data["input"]
        limit_name = fixture_input["limit"]
        configured = gas_manifest["portableLimits"][limit_name]
        require(
            fixture_input["configured"] == configured,
            f"limit configuration mismatch in {path.name}",
        )
        generator = fixture_input["generator"]
        expected_kind = LIMIT_GENERATORS[limit_name]
        require(
            generator["kind"] == expected_kind,
            f"limit/generator mismatch in {path.name}: {limit_name} requires {expected_kind}",
        )
        measured = measure_limit_generator(generator["kind"], generator["parameters"])
        require(
            fixture_input["observed"] == measured,
            f"generated limit measurement mismatch in {path.name}",
        )
        decision = "ACCEPT" if measured <= configured else "REJECT"
        require(
            data["expected"]["limitDecision"] == decision,
            f"limit decision mismatch in {path.name}",
        )
        if decision == "REJECT":
            require(
                measured == configured + 1,
                f"rejected limit fixture is not first-above in {path.name}",
            )
            require(
                data["expected"].get("diagnostic") == LIMIT_DIAGNOSTICS[limit_name],
                f"limit diagnostic mismatch in {path.name}",
            )
            require(
                data["expected"].get("rejectedStepAdmitted") is False,
                f"rejected limit step admitted in {path.name}",
            )
        else:
            require(
                measured == configured,
                f"accepted limit fixture is not at-bound in {path.name}",
            )
        return {"occurrences": 0, "channels": 0, "handlers": 0}

    fixture_input = data["input"]
    input_markers = direct_processor_markers(
        path, fixture_input["documents"], "input"
    )
    environment = validate_fixture_environment(path, fixture_input)
    validate_cause(path, fixture_input["cause"], environment)
    gas_policy = validate_gas_policy(path, fixture_input)
    admission_candidate_identity = validate_admission_candidate(path, fixture_input)
    require(
        data["operation"] == "admit-closure"
        or admission_candidate_identity is None,
        f"non-admission operation carries admission-candidate evidence in {path.name}",
    )

    binding_policy_identity = environment["managedBindingPolicy"]["identity"]
    for occurrence in fixture_input.get("occurrences", []):
        require(
            occurrence["bindingPolicyIdentity"] == binding_policy_identity,
            f"occurrence uses the wrong binding policy in {path.name}",
        )
    for transition in fixture_input.get("historicalTransitions", []):
        transition_identity = domain_identity(
            "blue-contracts-historical-transition/1.0",
            {
                "documentId": transition["documentId"],
                "fromEpoch": transition["fromEpoch"],
                "toEpoch": transition["toEpoch"],
                "beforeBlueId": transition["beforeBlueId"],
                "afterBlueId": transition["afterBlueId"],
            },
        )
        require(
            transition["transitionIdentity"] == transition_identity,
            f"historical transition identity mismatch in {path.name}",
        )
        require(
            direct_blue_id(transition["afterDocument"]) == transition["afterBlueId"],
            f"historical transition after-document BlueId mismatch in {path.name}",
        )

    direct_with_id = [
        (delivery, domain_identity("blue-contracts-direct-delivery/1.0", delivery))
        for delivery in fixture_input.get("directDeliveries", [])
    ]
    ordered_direct = sorted(
        direct_with_id,
        key=lambda item: (
            item[0]["rawOccurrenceOrder"],
            item[0]["targetDocumentId"],
            item[0]["scopePath"],
            item[0]["activationGeneration"],
            item[0]["channelKey"],
            item[0]["logicalDeliveryKey"],
        ),
    )
    direct_snapshot_identity = domain_identity(
        "blue-contracts-direct-delivery-snapshot/1.0",
        [identity for _delivery, identity in ordered_direct],
    )
    require(
        fixture_input["directDeliverySnapshotIdentity"] == direct_snapshot_identity,
        f"direct-delivery snapshot identity mismatch in {path.name}",
    )

    occurrence_count = validate_occurrence_set(
        path,
        fixture_input["documents"],
        fixture_input.get("occurrences", []),
        allow_invalid=False,
    )
    validate_components(
        path,
        fixture_input["documents"],
        fixture_input.get("occurrences", []),
        fixture_input["components"],
    )
    input_closure_identity, input_binding_set_identity, public_roots = (
        affected_closure_identity(
            fixture_input["graphGeneration"],
            fixture_input["documents"],
            fixture_input.get("occurrences", []),
            fixture_input["components"],
        )
    )
    require(
        fixture_input["occurrenceBindingSetIdentity"] == input_binding_set_identity,
        f"input occurrence-binding set identity mismatch in {path.name}",
    )
    require(
        fixture_input["publicRootDocumentIds"] == public_roots,
        f"public Root document list mismatch in {path.name}",
    )
    require(
        fixture_input["closureIdentity"] == input_closure_identity,
        f"input closure identity mismatch in {path.name}",
    )
    invocation_basis = {
        "operation": data["operation"],
        "causeIdentity": fixture_input["cause"]["causeIdentity"],
        "admissionCandidateIdentity": admission_candidate_identity,
        "inputGraphGeneration": fixture_input["graphGeneration"],
        "inputClosureIdentity": input_closure_identity,
        "documents": document_identity_items(fixture_input["documents"]),
        "directDeliverySnapshotIdentity": direct_snapshot_identity,
        "occurrenceBindingSetIdentity": input_binding_set_identity,
        "runtimeRegistryIdentity": environment["runtimeRegistryIdentity"],
        "gasPolicyIdentity": gas_policy["policyIdentity"],
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
        "managedBindingPolicyIdentity": binding_policy_identity,
        "exactNodeProviderDomainIdentity": environment["exactNodeProviderDomain"][
            "identity"
        ],
        "externalOrderPolicyIdentity": environment["externalOrderPolicy"][
            "identity"
        ],
        "gasManifestIdentity": environment["gasManifestIdentity"],
        "portableLimitPolicyIdentity": environment["portableLimitPolicy"][
            "identity"
        ],
    }
    invocation_identity = domain_identity(
        "blue-contracts-invocation/1.0", invocation_basis
    )
    require(
        fixture_input["invocationIdentity"] == invocation_identity,
        f"invocation identity mismatch in {path.name}",
    )

    expected = data["expected"]
    channel_count, handler_count = validate_channels_and_handlers(path, data)
    if expected["attemptOutcome"] == "NeedsResources":
        require(
            expected["requiredBlueIds"] == sorted(set(expected["requiredBlueIds"])),
            f"NeedsResources BlueIds are not sorted and unique in {path.name}",
        )
        return {
            "occurrences": occurrence_count,
            "channels": channel_count,
            "handlers": handler_count,
        }

    require(
        expected["attemptOutcome"] == "Complete",
        f"unknown attempt outcome in {path.name}",
    )
    require(
        expected["status"] in STATUSES,
        f"unknown completed status in {path.name}: {expected['status']}",
    )
    if fixture_input["admissionCandidate"] is not None:
        candidate_diagnostic = {
            "BAD_CYCLIC_PROOF": "CyclicSetProofInvalid",
            "AMBIGUOUS_PRELIMINARY_MEMBERS": "CyclicPreliminaryMemberAmbiguous",
            "INVALID_OCCURRENCE_BINDING": "ManagedOccurrenceBindingMissing",
        }[fixture_input["admissionCandidate"]["kind"]]
        require(
            expected.get("diagnostic") == candidate_diagnostic,
            f"admission candidate does not select its deterministic failure in {path.name}",
        )
    if expected.get("diagnostic") is not None:
        require(
            expected["diagnostic"] in DIAGNOSTICS,
            f"unknown diagnostic in {path.name}: {expected['diagnostic']}",
        )
    require(expected["atomic"] is True, f"closure result is not atomic in {path.name}")
    require(
        expected["invocationIdentity"] == invocation_identity,
        f"result invocation identity mismatch in {path.name}",
    )
    require(
        expected["inputClosureIdentity"] == input_closure_identity,
        f"result input closure identity mismatch in {path.name}",
    )

    output_documents = result_documents(expected)
    output_markers = direct_processor_markers(path, output_documents, "result")
    validate_direct_marker_transition(
        path,
        fixture_input["documents"],
        output_documents,
        input_markers,
        output_markers,
    )
    require(
        [item["documentId"] for item in expected["resultingDocuments"]]
        == sorted(output_documents),
        f"resulting documents are not in canonical order in {path.name}",
    )
    require(
        set(output_documents) == set(fixture_input["documents"]),
        f"resulting document inventory mismatch in {path.name}",
    )
    for item in expected["resultingDocuments"]:
        document_id = item["documentId"]
        require(
            item["beforeBlueId"] == fixture_input["documents"][document_id]["blueId"],
            f"result document before BlueId mismatch in {path.name}: {document_id}",
        )

    output_occurrences = expected["occurrenceBindings"]
    final_occurrence_count = validate_occurrence_set(
        path, output_documents, output_occurrences, allow_invalid=False
    )
    validate_components(
        path,
        output_documents,
        output_occurrences,
        expected["resultingComponents"],
    )
    validate_tentative_finalizations(path, data, expected)
    component_by_document: dict[str, dict[str, Any]] = {}
    for component in expected["resultingComponents"]:
        for document_id in component["orderedMemberDocumentIds"]:
            require(
                document_id not in component_by_document,
                f"resulting document belongs to multiple components in {path.name}: {document_id}",
            )
            component_by_document[document_id] = component
    for item in expected["resultingDocuments"]:
        component = component_by_document[item["documentId"]]
        require(
            item["componentIdentity"] == component["componentIdentity"]
            and item["componentStateIdentity"] == component["componentStateIdentity"]
            and item["componentGeneration"] == component["componentGeneration"],
            f"result document/component evidence mismatch in {path.name}: {item['documentId']}",
        )
        if component["kind"] == "CYCLIC":
            suffix = int(item["afterBlueId"].rsplit("#", 1)[1])
            require(
                item["memberIndex"] == suffix,
                f"result memberIndex is not the exact Blue cyclic suffix in {path.name}: {item['documentId']}",
            )
        else:
            require(
                item["memberIndex"] is None,
                f"acyclic result carries a member index in {path.name}: {item['documentId']}",
            )

    output_closure_identity, output_binding_set_identity, output_public_roots = (
        affected_closure_identity(
            expected["graphGeneration"],
            output_documents,
            output_occurrences,
            expected["resultingComponents"],
        )
    )
    require(
        expected["occurrenceBindingSetIdentity"] == output_binding_set_identity,
        f"result occurrence-binding set identity mismatch in {path.name}",
    )
    require(
        expected["outputClosureIdentity"] == output_closure_identity,
        f"output closure identity mismatch in {path.name}",
    )

    graph_changes = derive_graph_changes(
        fixture_input.get("occurrences", []), output_occurrences
    )
    require(
        expected["graphChanges"] == graph_changes,
        f"graph-change sequence mismatch in {path.name}",
    )
    require(
        expected["graphChangesIdentity"]
        == domain_identity("blue-contracts-graph-changes/1.0", graph_changes),
        f"graph-change identity mismatch in {path.name}",
    )
    subscription_deltas = derive_subscription_deltas(
        fixture_input["documents"],
        output_documents,
        fixture_input["graphGeneration"],
        expected["graphGeneration"],
    )
    require(
        expected["subscriptionDeltas"] == subscription_deltas,
        f"subscription-delta sequence mismatch in {path.name}",
    )
    subscription_identity_basis = [
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
    ]
    require(
        expected["subscriptionDeltasIdentity"]
        == domain_identity(
            "blue-contracts-subscription-deltas/1.0", subscription_identity_basis
        ),
        f"subscription-delta identity mismatch in {path.name}",
    )
    checkpoint_writes = derive_checkpoint_writes(
        path, fixture_input, expected, output_documents
    )
    require(
        expected["checkpointWrites"] == checkpoint_writes,
        f"checkpoint-write sequence mismatch in {path.name}",
    )
    require(
        expected["checkpointWritesIdentity"]
        == domain_identity("blue-contracts-checkpoint-writes/1.0", checkpoint_writes),
        f"checkpoint-write identity mismatch in {path.name}",
    )
    public_event_basis: list[dict[str, Any]] = []
    for ordinal, event in enumerate(expected["publicEvents"]):
        require(
            event["publicEventOrdinal"] == ordinal,
            f"public event ordinals are not contiguous in {path.name}",
        )
        require(
            event["publicRootDocumentId"] in output_public_roots,
            f"public event is not owned by an output public Root in {path.name}",
        )
        require(
            direct_blue_id(event["event"]) == event["eventBlueId"],
            f"public event BlueId mismatch in {path.name}: {ordinal}",
        )
        require(
            event["eventOccurrenceIdentity"]
            == domain_identity(
                "blue-contracts-event-occurrence/1.0",
                {
                    "invocationIdentity": invocation_identity,
                    "eventOccurrenceOrdinal": ordinal,
                    "eventBlueId": event["eventBlueId"],
                },
            ),
            f"public event occurrence identity mismatch in {path.name}: {ordinal}",
        )
        public_event_basis.append(
            {
                key: event[key]
                for key in (
                    "publicEventOrdinal",
                    "publicRootDocumentId",
                    "eventOccurrenceIdentity",
                    "eventBlueId",
                )
            }
        )
    require(
        expected["publicEventsIdentity"]
        == domain_identity("blue-contracts-public-events/1.0", public_event_basis),
        f"public-events identity mismatch in {path.name}",
    )

    valid_work_ids = validate_work_trace(
        path, fixture_input, expected, ordered_direct, invocation_identity
    )
    validate_gas_result(
        path,
        fixture_input,
        expected,
        gas_policy,
        valid_work_ids,
        expected["resultingComponents"],
    )

    if expected["rollbackToInput"]:
        require(
            output_documents == fixture_input["documents"],
            f"rollback documents differ from the literal input snapshot in {path.name}",
        )
        require(
            expected["graphGeneration"] == fixture_input["graphGeneration"],
            f"rollback graph generation differs from input in {path.name}",
        )
        rollback_input_components = [
            {key: value for key, value in component.items() if key != "oracleStage"}
            for component in fixture_input["components"]
        ]
        require(
            expected["resultingComponents"] == rollback_input_components,
            f"rollback components differ from input in {path.name}",
        )
        require(
            output_occurrences == fixture_input.get("occurrences", []),
            f"rollback occurrences differ from input in {path.name}",
        )
        require(
            expected["outputClosureIdentity"] == input_closure_identity,
            f"rollback output closure is not the input closure in {path.name}",
        )
        require(
            expected["graphChanges"] == []
            and expected["subscriptionDeltas"] == []
            and expected["checkpointWrites"] == []
            and expected["publicEvents"] == [],
            f"rollback result contains committed side effects in {path.name}",
        )

    if expected["status"] == "success":
        require(
            expected["rollbackToInput"] is False,
            f"successful fixture is marked rollback in {path.name}",
        )
        validate_commit_companion(path, fixture_input, expected, environment)
    else:
        require(
            "platformCommitCompanion" not in expected,
            f"noncommitting fixture carries a commit companion in {path.name}",
        )

    return {
        "occurrences": occurrence_count + final_occurrence_count,
        "channels": channel_count,
        "handlers": handler_count,
    }


def validate_limits() -> None:
    at = load_yaml(CLOSURE / "c-clo-16-limit-at-bound.yaml")
    above = load_yaml(CLOSURE / "c-clo-17-limit-above-bound.yaml")
    require(len(at["input"]["documents"]) == 128 and len(at["input"]["occurrences"]) == 128, "at-bound fixture is not a real 128-member/edge graph")
    require(len(above["input"]["documents"]) == 129 and len(above["input"]["occurrences"]) == 129, "above-bound fixture is not a real 129-member/edge graph")
    require(at["expected"]["status"] == "success", "at-bound fixture must succeed")
    require(above["expected"]["diagnostic"] == "CyclicComponentMemberLimitExceeded", "above-bound diagnostic mismatch")
    micros = [load_yaml(path) for path in sorted(CLOSURE.glob("c-clo-1[67]-*-*-bound.yaml"))]
    by_limit: dict[str, set[str]] = defaultdict(set)
    for fixture in micros:
        if fixture.get("operation") == "limit-micro":
            by_limit[fixture["input"]["limit"]].add(fixture["expected"]["limitDecision"])
    require(set(by_limit) == set(LIMIT_DIAGNOSTICS), f"closure limit boundary coverage mismatch: {sorted(by_limit)}")
    require(all(decisions == {"ACCEPT", "REJECT"} for decisions in by_limit.values()), "each closure limit requires ACCEPT and REJECT microfixtures")


def validate_static_package_laws() -> None:
    spec = SPEC.read_text()
    normative_text = spec + "\n" + GAS.read_text() + "\n" + IDENTITY_CONSTRUCTORS.read_text() + "\n" + RELEASE.read_text()
    for path in REGISTRY.glob("*.blue"):
        normative_text += "\n" + path.read_text()
    for path in FIX.rglob("*"):
        if path.is_file() and path.suffix in {".yaml", ".md"}:
            normative_text += "\n" + path.read_text()
    require(re.search(r"\bMandate\b|onBehalfOf", normative_text, flags=re.IGNORECASE) is None, "Mandate-specific content found in normative spec/registry/fixtures")
    require("PROCESS_COMPONENT" not in normative_text, "obsolete PROCESS_COMPONENT remains")
    fixture_registry_text = "\n".join(
        path.read_text()
        for base in (REGISTRY, FIX)
        for path in base.rglob("*")
        if path.is_file() and path.suffix in {".yaml", ".blue"}
    )
    require("componentPatches:" not in fixture_registry_text, "hidden componentPatches fixture field remains")
    require(re.search(r"Blue Contracts(?: and Processor)? Specification 1\.1|specificationVersion:\s*['\"]?1\.1", normative_text) is None, "1.1 release label remains")
    require("verified bounded cyclic components pass" in spec, "supported-cycle final soundness rule missing")
    require("numeric weights and portable limits are provisional" not in spec.lower(), "provisional gas/limits wording remains")
    require("finalize only after component quiescence" not in spec.lower(), "obsolete one-finalization-after-quiescence rule remains")
    gas = load_yaml(GAS)
    required_trace_fields = [
        "sequence", "namespace", "counter", "quantity", "weight", "subtotal",
        "documentId", "scopePath", "activationGeneration", "componentGeneration",
        "contractKey", "logicalPath", "workOccurrenceId", "reason",
    ]
    require(gas.get("traceEntryFields") == required_trace_fields, "gas trace field registry does not match Contracts 1.0")
    require(gas.get("status") == "normative", "gas manifest is not normative")
    require(gas.get("numericWeightsStatus") == "frozen for Blue Contracts and Processor Specification 1.0", "gas weights are not frozen")
    require(gas.get("defaultExecutionPolicy", {}).get("defaultGasLimit") == gas.get("maxProcessGas"), "default gas policy/manifest limit mismatch")
    require(not any("bex" in path.name.lower() or "blue-bex" in path.as_posix().lower() for path in ROOT.rglob("*") if path.is_file()), "BEX file included in Contracts package")
    specs = sorted(path.name for path in (ROOT / "specifications").glob("*.md"))
    require(specs == ["blue-contracts-and-processor-specification-1.0.md"], f"unexpected specification documents: {specs}")


def validate_manifests() -> dict[str, Any]:
    registry = verify_manifest(REGISTRY / "manifest.yaml", "packageIdentity", ("fixturePackageIdentity",))
    verify_listed_files(REGISTRY, registry["entries"])
    gas = verify_manifest(GAS, "packageIdentity")
    fixtures = verify_manifest(FIX / "manifest.yaml", "packageIdentity")
    verify_listed_files(FIX, fixtures["files"])
    oracles = verify_manifest(ORACLES / "manifest.yaml", "packageIdentity")
    verify_listed_files(ORACLES, oracles["files"])
    release = verify_manifest(RELEASE, "releaseIdentity")
    require(release["specificationDocument"]["sha256"] == sha256_file(SPEC), "spec hash mismatch in release manifest")
    language = release["languageDependency"]
    require(language["specificationSha256"] == sha256_file(LANG_SPEC), "Language spec hash mismatch")
    baseline_by_path = {entry["path"]: entry for entry in language.get("inputImplementationBaseline", [])}
    finalizer_path = "blue-language-core/src/main/java/blue/language/identity/CircularSetIdentityCalculator.java"
    verifier_paths = [
        "blue-language-core/src/main/java/blue/language/provider/CyclicSetProof.java",
        "blue-language-core/src/main/java/blue/language/provider/CyclicSetProofResult.java",
        "blue-language-core/src/main/java/blue/language/provider/CyclicAwareNodeProvider.java",
    ]
    require(finalizer_path in baseline_by_path, "cyclic finalizer baseline source missing")
    require(all(path in baseline_by_path for path in verifier_paths), "cyclic proof verifier baseline source missing")
    expected_finalizer = domain_identity(
        "blue-language-cyclic-set-finalizer-baseline/1.0",
        {"languageSpecificationSha256": sha256_file(LANG_SPEC), "files": [baseline_by_path[finalizer_path]]},
    )
    expected_verifier = domain_identity(
        "blue-language-cyclic-set-proof-verifier-baseline/1.0",
        {"languageSpecificationSha256": sha256_file(LANG_SPEC), "files": [baseline_by_path[path] for path in verifier_paths]},
    )
    require(language.get("cyclicSetFinalizerBaselineIdentity") == expected_finalizer, "cyclic finalizer baseline identity mismatch")
    require(language.get("cyclicSetProofVerifierBaselineIdentity") == expected_verifier, "cyclic proof verifier baseline identity mismatch")
    require(release["contractsRegistry"]["packageIdentity"] == registry["packageIdentity"], "registry binding mismatch")
    require(release["gasManifest"]["packageIdentity"] == gas["packageIdentity"], "gas binding mismatch")
    require(release["identityConstructors"]["sha256"] == sha256_file(IDENTITY_CONSTRUCTORS), "identity-constructor binding mismatch")
    require(release["fixturePackage"]["packageIdentity"] == fixtures["packageIdentity"], "fixture binding mismatch")
    require(release["oraclePackage"]["packageIdentity"] == oracles["packageIdentity"], "oracle binding mismatch")
    package = verify_manifest(PACKAGE_MANIFEST, "packageIdentity")
    verify_listed_files(ROOT, package["files"])
    require(package["contractsReleaseIdentity"] == release["releaseIdentity"], "package/release binding mismatch")
    return {"registry": registry, "gas": gas, "fixtures": fixtures, "oracles": oracles, "release": release, "package": package}


def validate_checksum_manifest() -> None:
    path = ROOT / "MANIFEST.sha256"
    require(path.is_file(), "MANIFEST.sha256 missing")
    listed: set[str] = set()
    for line in path.read_text().splitlines():
        if not line:
            continue
        digest, rel = line.split("  ", 1)
        target = ROOT / rel
        require(target.is_file(), f"checksum manifest target missing: {rel}")
        require(sha256_file(target) == digest, f"checksum mismatch: {rel}")
        listed.add(rel)
    expected = {
        p.relative_to(ROOT).as_posix()
        for p in ROOT.rglob("*")
        if p.is_file()
        and p.relative_to(ROOT).as_posix() not in {"MANIFEST.sha256", "validation-output.json"}
        and "__pycache__" not in p.parts
        and "build/classes" not in p.relative_to(ROOT).as_posix()
    }
    require(listed == expected, f"checksum manifest inventory mismatch: missing={sorted(expected-listed)[:5]} extra={sorted(listed-expected)[:5]}")


def run_command(command: list[str], cwd: Path | None = None) -> str:
    # Use the package parent by default. This also avoids coupling child tools
    # to a process current directory; every package-owned path passed below is
    # absolute.
    if cwd is None:
        cwd = ROOT.parent
    result = subprocess.run(command, cwd=cwd, text=True, capture_output=True)
    if result.returncode != 0:
        raise ValidationFailure(f"command failed ({' '.join(command)}):\n{result.stdout}\n{result.stderr}")
    return result.stdout.strip()


def validate_java_templates() -> dict[str, Any]:
    roots = [
        ROOT / "java-templates/reference/src/main/java",
        ROOT / "java-templates/coordination/src/main/java",
    ]
    sources = sorted(path for root in roots for path in root.rglob("*.java"))
    require(sources, "Java templates missing")
    with tempfile.TemporaryDirectory(prefix="blue-contracts-java-") as temp:
        output = Path(temp) / "classes"
        output.mkdir()
        run_command(["javac", "--release", "17", "-Xlint:all", "-Werror", "-d", str(output), *map(str, sources)])
        contracts_output = run_command(["java", "-cp", str(output), "blue.contracts.closure.ReferenceCycleMain"])
        coordination_output = run_command(["java", "-cp", str(output), "blue.coordination.closure.ReferenceCoordinationIntegrationMain"])
    require("BLUE_CONTRACTS_CLOSURE_REFERENCE_OK" in contracts_output, "Contracts Java reference main did not pass")
    require("BLUE_COORDINATION_CLOSURE_REFERENCE_OK" in coordination_output, "Coordination Java reference main did not pass")
    return {"sourceFiles": len(sources), "contractsMain": contracts_output, "coordinationMain": coordination_output}


def validate_source_archive(source: Path | None, expected: str) -> dict[str, Any]:
    if source is None:
        return {"provided": False, "expectedSha256": expected}
    require(source.is_file(), f"source archive not found: {source}")
    actual = sha256_file(source)
    require(actual == expected, f"source archive hash mismatch: expected {expected}, got {actual}")
    return {"provided": True, "suppliedName": source.name, "sha256": actual}


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument("--source", type=Path, default=None, help="Optional original spec source archive; verified by content hash, not filename")
    parser.add_argument("--write-output", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    def progress(label: str) -> None:
        if args.verbose:
            print(f"VALIDATING {label}", file=sys.stderr, flush=True)

    progress("static laws")
    validate_static_package_laws()
    progress("manifests")
    manifests = validate_manifests()
    progress("checksums")
    validate_checksum_manifest()
    ordinary, closure = fixture_files()
    progress("schemas")
    validate_schemas(ordinary, closure)
    progress("vector coverage")
    vectors = validate_vector_coverage(ordinary, closure)
    progress("BlueIds and DocumentIds")
    blue_ids = validate_blue_ids(closure)
    document_ids = validate_document_ids(closure)
    counts = {"occurrences": 0, "channels": 0, "handlers": 0}
    for index, path in enumerate(closure):
        if args.verbose and index % 10 == 0:
            progress(f"closure semantics {index + 1}/{len(closure)}")
        current = validate_closure_fixture(path)
        for key, value in current.items():
            counts[key] += value
    validate_limits()

    progress("identity oracle")
    identity_output = run_command([sys.executable, str(ROOT / "tools/blue_identity.py")])
    require("BLUE_IDENTITY_ORACLE_OK" in identity_output, "Blue identity oracle failed")
    progress("semantic reference scenarios")
    reference_output = run_command([sys.executable, str(ROOT / "tools/reference_scenarios.py")])
    reference = json.loads(reference_output)
    require(reference["status"] == "SEMANTIC_REFERENCE_VALID", "semantic reference scenarios failed")
    progress("Java templates")
    java = validate_java_templates()
    progress("source archive")
    source = validate_source_archive(args.source, manifests["release"]["sourceArchiveBaseline"]["expectedSha256"])

    result = {
        "status": "PACKAGE_VALID",
        "referenceStatus": reference["status"],
        "specificationVersion": "1.0",
        "contractsReleaseIdentity": manifests["release"]["releaseIdentity"],
        "packageIdentity": manifests["package"]["packageIdentity"],
        "ordinaryFixtures": len(ordinary),
        "closureFixtures": len(closure),
        "totalFixtures": len(ordinary) + len(closure),
        "vectors": len(vectors),
        "closureVectors": len([v for v in vectors if v.startswith("C-CLO-")]),
        "oracleFiles": manifests["oracles"]["oracleCount"],
        "oracleStages": reference["oracleStages"],
        "blueIdsChecked": blue_ids,
        "documentIdsChecked": document_ids,
        "occurrencesChecked": counts["occurrences"],
        "directChannelsChecked": counts["channels"],
        "runtimeHandlersChecked": counts["handlers"],
        "javaTemplates": java,
        "sourceArchive": source,
        "implementationConformanceClaimed": False,
    }
    text = json.dumps(result, indent=2, sort_keys=True)
    if args.write_output:
        (ROOT / "validation-output.json").write_text(text + "\n")
    print("PACKAGE_VALID")
    print("SEMANTIC_REFERENCE_VALID")
    print(text)


if __name__ == "__main__":
    try:
        main()
    except ValidationFailure as exc:
        print(f"PACKAGE_INVALID: {exc}", file=sys.stderr)
        raise SystemExit(1)
