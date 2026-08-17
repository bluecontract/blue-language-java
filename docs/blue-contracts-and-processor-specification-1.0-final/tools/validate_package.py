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
import unicodedata
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
from jcs import dumps as jcs_dumps  # noqa: E402
from blue_identity import (  # noqa: E402
    BASE58_ALPHABET,
    cyclic_canonical_limit_bytes,
    cyclic_canonical_limit_form,
    cyclic_set_oracle,
    direct_blue_id,
)
PROCESS_EMBEDDED = "EVJk3e7MLRhtTfMBNyrWYz1pWFXsbDTkPczeTviUuB4e"
SAFE_INTEGER_MAX = 2**53 - 1
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


NFC_ORDER_TOKEN_FIELDS = {
    "channelKey",
    "checkpointDomain",
    "contractKey",
    "counter",
    "eventKey",
    "handlerKey",
    "limitName",
    "logicalDeliveryKey",
    "managedScopePath",
    "namespace",
    "policyName",
    "rawChannelKey",
    "rawContractKey",
    "runtimeDiscriminator",
    "scopePath",
    "sourcePath",
    "subscriptionKey",
    "targetPath",
}
NFC_ORDER_TOKEN_LIST_FIELDS = {
    "collectionPaths",
    "orderedMemberDocumentIds",
    "paths",
    "publicRootDocumentIds",
}
NFC_ORDER_TOKEN_MAP_FIELDS = {
    "documents",
    "handlers",
    "initializationHandlers",
    "localLimits",
}
NFC_POLICY_LABEL_PARENTS = {
    "cause",
    "exactNodeProviderDomain",
    "externalOrderPolicy",
    "gasPolicy",
    "managedBindingPolicy",
    "managedDocumentIdentityPolicy",
    "portableLimitPolicy",
}


def require_nfc_order_token(value: str, context: str) -> str:
    require(
        unicodedata.normalize("NFC", value) == value,
        f"Contracts portable-order token is not NFC: {context}",
    )
    require(
        not any(0xD800 <= ord(character) <= 0xDFFF for character in value),
        f"Contracts portable-order token contains an unpaired surrogate: {context}",
    )
    return value


def portable_text_order_key(value: str, context: str) -> tuple[int, ...]:
    return tuple(ord(character) for character in require_nfc_order_token(value, context))


def validate_nfc_order_tokens(path: Path, payload: Any) -> None:
    """Reject non-NFC Contracts control/order text without touching Blue payload text."""

    opaque_blue_fields = {
        "afterDomainValue",
        "beforeDomainValue",
        "domain",
        "event",
        "events",
        "observations",
        "provider",
        "subject",
        "val",
    }
    contract_text_fields = {
        "channel",
        "checkpointDomain",
        "eventKey",
        "logicalDeliveryKey",
        "runtimeDiscriminator",
        "sourcePath",
        "subscriptionKey",
    }

    def check_text_tree(value: Any, context: str) -> None:
        if isinstance(value, str):
            require_nfc_order_token(value, context)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                check_text_tree(child, f"{context}[{index}]")
        elif isinstance(value, dict):
            for key, child in value.items():
                if isinstance(key, str):
                    require_nfc_order_token(key, f"{context}.<key>")
                check_text_tree(child, f"{context}.{key}")

    def check_document_contracts(document: Any, context: str) -> None:
        if not isinstance(document, dict):
            return
        contracts = document.get("contracts")
        if not isinstance(contracts, dict):
            return
        for raw_key, contract in contracts.items():
            if isinstance(raw_key, str):
                require_nfc_order_token(raw_key, f"{context}.contracts.<key>")
            if not isinstance(contract, dict):
                continue
            for field in contract_text_fields:
                token = contract.get(field)
                if isinstance(token, str):
                    require_nfc_order_token(
                        token, f"{context}.contracts.{raw_key}.{field}"
                    )
            for field in ("paths", "collectionPaths"):
                tokens = contract.get(field)
                if isinstance(tokens, list):
                    for index, token in enumerate(tokens):
                        if isinstance(token, str):
                            require_nfc_order_token(
                                token,
                                f"{context}.contracts.{raw_key}."
                                f"{field}[{index}]",
                            )
            entries = contract.get("entries")
            if isinstance(entries, dict):
                for entry_key in entries:
                    if isinstance(entry_key, str):
                        require_nfc_order_token(
                            entry_key,
                            f"{context}.contracts.{raw_key}.entries.<key>",
                        )

    def visit(value: Any, parent: str, trail: str) -> None:
        if isinstance(value, dict):
            if parent in NFC_ORDER_TOKEN_MAP_FIELDS:
                for key in value:
                    if isinstance(key, str):
                        require_nfc_order_token(key, f"{path.name}:{trail}.<key>")
            for key, child in value.items():
                child_trail = f"{trail}.{key}" if trail else str(key)
                if key in {"document", "afterDocument"}:
                    check_document_contracts(
                        child, f"{path.name}:{child_trail}"
                    )
                    continue
                if key in opaque_blue_fields:
                    continue
                if isinstance(child, str) and (
                    key in NFC_ORDER_TOKEN_FIELDS
                    or key == "documentId"
                    or key.endswith("DocumentId")
                    or (key == "label" and parent in NFC_POLICY_LABEL_PARENTS)
                ):
                    require_nfc_order_token(child, f"{path.name}:{child_trail}")
                elif key in NFC_ORDER_TOKEN_LIST_FIELDS and isinstance(child, list):
                    for index, token in enumerate(child):
                        if isinstance(token, str):
                            require_nfc_order_token(
                                token, f"{path.name}:{child_trail}[{index}]"
                            )
                elif key == "sourceOrder":
                    check_text_tree(child, f"{path.name}:{child_trail}")
                elif (
                    key == "path"
                    and isinstance(child, str)
                    and isinstance(value.get("op"), str)
                ):
                    require_nfc_order_token(child, f"{path.name}:{child_trail}")
                visit(child, str(key), child_trail)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, parent, f"{trail}[{index}]")

    visit(payload, "", "")


def validate_nfc_order_token_self_check() -> None:
    try:
        require_nfc_order_token("A\u030a", "synthetic decomposed token")
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure("non-NFC Contracts ordering token was accepted")
    require(
        portable_text_order_key("B", "synthetic B")
        < portable_text_order_key("\u00c5", "synthetic precomposed A-ring"),
        "NFC Unicode scalar-order self-check failed",
    )
    require(
        portable_text_order_key("\ue000", "synthetic BMP scalar")
        < portable_text_order_key("\U0001f600", "synthetic supplementary scalar"),
        "Unicode scalar order was replaced by UTF-16 code-unit order",
    )
    ordinary_payload = {
        "input": {
            "documents": {
                "root": {
                    "document": {
                        "business": {"sourcePath": "A\u030a"},
                        "contracts": {"handler": {"channel": "source"}},
                    }
                }
            }
        }
    }
    validate_nfc_order_tokens(
        Path("synthetic-opaque-blue-text.yaml"), ordinary_payload
    )
    bad_control = {
        "input": {
            "directDeliveries": [
                {
                    "channelKey": "A\u030a",
                    "logicalDeliveryKey": "delivery",
                    "scopePath": "/",
                }
            ]
        }
    }
    try:
        validate_nfc_order_tokens(
            Path("synthetic-non-nfc-control.yaml"), bad_control
        )
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure("non-NFC direct Channel key was accepted")


def load_yaml(path: Path) -> Any:
    try:
        return yaml.safe_load(path.read_text())
    except Exception as exc:
        raise ValidationFailure(f"YAML parse failed: {path.relative_to(ROOT)}: {exc}") from exc


def load_yaml_unique(path: Path) -> Any:
    """Load YAML while rejecting duplicate mapping keys.

    PyYAML's default loader silently keeps the last duplicate key.  That is
    unsuitable for a normative counter registry because two textual counter
    declarations could otherwise collapse to one in-memory entry.
    """

    class UniqueKeyLoader(yaml.SafeLoader):
        pass

    def construct_unique_mapping(
        loader: yaml.SafeLoader,
        node: yaml.nodes.MappingNode,
        deep: bool = False,
    ) -> dict[Any, Any]:
        loader.flatten_mapping(node)
        result: dict[Any, Any] = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node, deep=deep)
            if key in result:
                raise ValidationFailure(
                    f"duplicate YAML mapping key in {path.relative_to(ROOT)} "
                    f"at line {key_node.start_mark.line + 1}: {key!r}"
                )
            result[key] = loader.construct_object(value_node, deep=deep)
        return result

    UniqueKeyLoader.add_constructor(
        yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
        construct_unique_mapping,
    )
    try:
        return yaml.load(path.read_text(), Loader=UniqueKeyLoader)
    except ValidationFailure:
        raise
    except Exception as exc:
        raise ValidationFailure(
            f"YAML parse failed: {path.relative_to(ROOT)}: {exc}"
        ) from exc


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
    return jcs_dumps(value)


def canonical_identity(value: dict[str, Any], field: str) -> str:
    copy = json.loads(json.dumps(value))
    copy[field] = None
    return "sha256:" + hashlib.sha256(canonical_json(copy)).hexdigest()


def domain_identity(domain: str, value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json({"domain": domain, "value": value})).hexdigest()


def closure_root_scope_identity(document_id: str) -> str:
    """Return the only managed-scope identity admitted by closure profile 1.0."""
    return domain_identity(
        "blue-contracts-managed-scope-key/1.0",
        {
            "documentId": document_id,
            "scopePath": "/",
            "activationGeneration": 0,
        },
    )


def require_closure_root_address(
    scope_path: Any,
    activation_generation: Any,
    context: str,
) -> None:
    require(
        scope_path == "/" and activation_generation == 0,
        f"Contracts 1.0 closure profile requires Root scope / generation 0: "
        f"{context}",
    )


def require_closure_root_identity(
    document_id: str,
    scope_identity: Any,
    context: str,
) -> None:
    require(
        scope_identity == closure_root_scope_identity(document_id),
        f"Contracts 1.0 closure profile requires the Root managed-scope "
        f"identity: {context}",
    )


def validate_closure_root_profile_self_check() -> None:
    """Synthetic negative law: closure admission never widens to nested scope."""
    require_closure_root_address("/", 0, "synthetic valid Root")
    for scope_path, generation in (("/nested", 1), ("/", 1)):
        rejected = False
        try:
            require_closure_root_address(
                scope_path, generation, "synthetic non-Root address"
            )
        except ValidationFailure:
            rejected = True
        require(rejected, "synthetic non-Root closure address was accepted")
    nested_identity = domain_identity(
        "blue-contracts-managed-scope-key/1.0",
        {
            "documentId": "synthetic-document",
            "scopePath": "/nested",
            "activationGeneration": 1,
        },
    )
    rejected = False
    try:
        require_closure_root_identity(
            "synthetic-document",
            nested_identity,
            "synthetic non-Root identity",
        )
    except ValidationFailure:
        rejected = True
    require(rejected, "synthetic non-Root managed-scope identity was accepted")


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
            cause_identity = domain_identity(
                "blue-contracts-limit-generator-cause/1.0",
                {"kind": kind, "ordinal": ordinal},
            )
            work_identities.add(domain_identity(
                "blue-contracts-work-occurrence/1.0",
                {
                    "invocationIdentity": invocation_identity,
                    "workOrdinal": ordinal,
                    "workKind": "CONTAINING_REFERENCE_UPDATE",
                    "targetManagedScopeIdentity": target_scope_identity,
                    "sourceOccurrenceIdentity": cause_identity,
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
            frozen_blue_id = before_documents[document_id]["blueId"]
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
            if not isinstance(collection_path, str):
                continue
            try:
                collection = pointer_get(document, collection_path)
            except (KeyError, ValidationFailure):
                continue
            if not isinstance(collection, dict) or pure_blue_reference(collection) is not None:
                continue
            collection_segments = (
                [] if collection_path == "/" else collection_path.split("/")[1:]
            )
            source_segments = source_path.split("/")[1:]
            if (
                len(source_segments) == len(collection_segments) + 1
                and source_segments[: len(collection_segments)]
                == collection_segments
            ):
                # The direct key may be absent for an explicit inactive
                # prospective/retirement-successor row. Active rows are still
                # required below to resolve to an exact present object value.
                return True
    return False


def validate_process_embedded_declaration_coverage(
    path: Path,
    documents: dict[str, Any],
    occurrences: list[dict[str, Any]],
) -> None:
    """Prove that declarations and managed occurrence rows cover each other.

    A fixed declaration owns one row whether its exact path is present or
    prospectively absent. A collection declaration owns one row for every
    direct object member and cannot be used as an implicit list/wildcard scan.
    The later occurrence-value checks remain representation-neutral and prove
    each row's exact target identity.
    """
    rows_by_path: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for occurrence in occurrences:
        source_document_id = occurrence.get("sourceDocumentId")
        source_path = occurrence.get("sourcePath")
        if isinstance(source_document_id, str) and isinstance(source_path, str):
            rows_by_path[(source_document_id, source_path)].append(occurrence)
    for (source_document_id, source_path), rows in rows_by_path.items():
        require(
            len(rows) == 1,
            f"occurrence set has multiple rows for one source path in "
            f"{path.name}: {source_document_id}{source_path}",
        )

    runtime_pointer = re.compile(r"^/(?:[^~]|~0|~1)*(?:/(?:[^~]|~0|~1)*)*$")

    def pointer_segments(source_path: str) -> tuple[str, ...]:
        if source_path == "/":
            return ()
        return tuple(
            raw.replace("~1", "/").replace("~0", "~")
            for raw in source_path.split("/")[1:]
        )

    def strict_prefix(left: tuple[str, ...], right: tuple[str, ...]) -> bool:
        return len(left) < len(right) and right[: len(left)] == left

    for document_id, record in documents.items():
        document = record["document"]
        contracts = document.get("contracts", {}) if isinstance(document, dict) else {}
        if not isinstance(contracts, dict):
            continue
        concrete_owners: dict[str, str] = {}
        declaration_roots: dict[str, str] = {}

        def reject_contract_traversal(source_path: str, owner: str) -> None:
            segments = pointer_segments(source_path)
            require(
                not segments or segments[0] != "contracts",
                f"Process Embedded declaration traverses reserved /contracts in "
                f"{path.name}: {document_id}{source_path} ({owner})",
            )
            require(
                "*" not in segments,
                f"Process Embedded declaration uses unsupported wildcard syntax "
                f"in {path.name}: {document_id}{source_path} ({owner})",
            )

        def register_declaration_root(source_path: str, owner: str) -> None:
            reject_contract_traversal(source_path, owner)
            segments = pointer_segments(source_path)
            for previous_path, previous_owner in declaration_roots.items():
                previous_segments = pointer_segments(previous_path)
                require(
                    source_path != previous_path
                    and not strict_prefix(segments, previous_segments)
                    and not strict_prefix(previous_segments, segments),
                    f"Process Embedded declarations overlap in {path.name}: "
                    f"{document_id}{previous_path} ({previous_owner}) and "
                    f"{document_id}{source_path} ({owner})",
                )
            declaration_roots[source_path] = owner

        def register(source_path: str, owner: str) -> None:
            reject_contract_traversal(source_path, owner)
            previous = concrete_owners.get(source_path)
            require(
                previous is None,
                f"Process Embedded declarations generate the same source path in "
                f"{path.name}: {document_id}{source_path} ({previous}, {owner})",
            )
            segments = pointer_segments(source_path)
            for previous_path, previous_owner in concrete_owners.items():
                previous_segments = pointer_segments(previous_path)
                require(
                    not strict_prefix(segments, previous_segments)
                    and not strict_prefix(previous_segments, segments),
                    f"Process Embedded concrete paths overlap in {path.name}: "
                    f"{document_id}{previous_path} ({previous_owner}) and "
                    f"{document_id}{source_path} ({owner})",
                )
            concrete_owners[source_path] = owner

        def validate_row_state(
            source_path: str,
            *,
            present: bool,
            owner: str,
        ) -> None:
            rows = rows_by_path.get((document_id, source_path), [])
            require(
                len(rows) == 1,
                f"Process Embedded declaration lacks exactly one occurrence row in "
                f"{path.name}: {document_id}{source_path} ({owner}, rows={len(rows)})",
            )
            row = rows[0]
            if not present:
                require(
                    row.get("active") is False,
                    f"absent Process Embedded path has an active occurrence in "
                    f"{path.name}: {document_id}{source_path}",
                )
                return
            pending_history = row.get("pendingHistoricalEpoch") is not None
            require(
                row.get("active") is (not pending_history),
                f"present Process Embedded path has the wrong active/pending state in "
                f"{path.name}: {document_id}{source_path}",
            )

        for contract_key, value in contracts.items():
            if type_blue_id(value) != PROCESS_EMBEDDED:
                continue
            fixed_paths = value.get("paths", [])
            collection_paths = value.get("collectionPaths", [])
            require(
                isinstance(fixed_paths, list)
                and all(
                    isinstance(source_path, str)
                    and runtime_pointer.fullmatch(source_path) is not None
                    for source_path in fixed_paths
                ),
                f"invalid Process Embedded paths declaration in {path.name}: "
                f"{document_id}/{contract_key}",
            )
            require(
                isinstance(collection_paths, list)
                and all(
                    isinstance(collection_path, str)
                    and runtime_pointer.fullmatch(collection_path) is not None
                    for collection_path in collection_paths
                ),
                f"invalid Process Embedded collectionPaths declaration in "
                f"{path.name}: {document_id}/{contract_key}",
            )

            for source_path in fixed_paths:
                register_declaration_root(source_path, f"{contract_key}.paths")
            for collection_path in collection_paths:
                register_declaration_root(
                    collection_path, f"{contract_key}.collectionPaths"
                )

            for source_path in fixed_paths:
                owner = f"{contract_key}.paths"
                register(source_path, owner)
                try:
                    value_at_path = pointer_get(document, source_path)
                except KeyError:
                    validate_row_state(source_path, present=False, owner=owner)
                    continue
                require(
                    isinstance(value_at_path, dict),
                    f"present Process Embedded fixed path is not an object in "
                    f"{path.name}: {document_id}{source_path}",
                )
                validate_row_state(source_path, present=True, owner=owner)

            for collection_path in collection_paths:
                owner = f"{contract_key}.collectionPaths"
                try:
                    collection = pointer_get(document, collection_path)
                except KeyError as exc:
                    raise ValidationFailure(
                        f"Process Embedded collection is absent in {path.name}: "
                        f"{document_id}{collection_path}"
                    ) from exc
                require(
                    isinstance(collection, dict)
                    and pure_blue_reference(collection) is None,
                    f"Process Embedded collection is not an established object in "
                    f"{path.name}: {document_id}{collection_path}",
                )
                for member_key, member_value in collection.items():
                    require(
                        isinstance(member_key, str),
                        f"Process Embedded collection has a non-text member key in "
                        f"{path.name}: {document_id}{collection_path}",
                    )
                    escaped_key = member_key.replace("~", "~0").replace("/", "~1")
                    source_path = (
                        f"/{escaped_key}"
                        if collection_path == "/"
                        else f"{collection_path}/{escaped_key}"
                    )
                    register(source_path, owner)
                    require(
                        isinstance(member_value, dict),
                        f"Process Embedded collection member is not an object in "
                        f"{path.name}: {document_id}{source_path}",
                    )
                    validate_row_state(source_path, present=True, owner=owner)


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


def canonical_scc_order(
    document_ids: set[str],
    occurrences: list[dict[str, Any]],
    components: list[dict[str, Any]],
) -> list[frozenset[str]]:
    """Return the unique target-before-source condensation order from §4.7."""
    partition = scc_partition(document_ids, occurrences)
    declared_by_members = {
        frozenset(component["orderedMemberDocumentIds"]): component
        for component in components
    }
    require(
        set(partition) == set(declared_by_members),
        "cannot order a declared component list that is not the exact SCC partition",
    )
    owner = {
        document_id: members
        for members in partition
        for document_id in members
    }
    outgoing: dict[frozenset[str], set[frozenset[str]]] = {
        members: set() for members in partition
    }
    active_occurrence_ids: dict[frozenset[str], list[str]] = {
        members: [] for members in partition
    }
    for occurrence in occurrences:
        if not occurrence.get("active"):
            continue
        source_component = owner[occurrence["sourceDocumentId"]]
        target_component = owner[occurrence["targetDocumentId"]]
        active_occurrence_ids[source_component].append(
            occurrence["occurrenceIdentity"]
        )
        if source_component != target_component:
            outgoing[source_component].add(target_component)

    def tie_key(members: frozenset[str]) -> tuple[Any, ...]:
        occurrence_ids = active_occurrence_ids[members]
        return (
            min(members),
            min(occurrence_ids) if occurrence_ids else "",
            declared_by_members[members]["componentGeneration"],
        )

    remaining = set(partition)
    ordered: list[frozenset[str]] = []
    while remaining:
        eligible = [
            members
            for members in remaining
            if not (outgoing[members] & remaining)
        ]
        require(eligible, "SCC condensation graph unexpectedly contains a cycle")
        selected = min(eligible, key=tie_key)
        ordered.append(selected)
        remaining.remove(selected)
    return ordered


def validate_component_order_self_check() -> None:
    """Prove that target z precedes embedding source a despite text order."""
    occurrences = [
        {
            "sourceDocumentId": "a",
            "targetDocumentId": "z",
            "occurrenceIdentity": "occurrence-a-z",
            "active": True,
        }
    ]
    components = [
        {"orderedMemberDocumentIds": ["a"], "componentGeneration": 1},
        {"orderedMemberDocumentIds": ["z"], "componentGeneration": 1},
    ]
    require(
        canonical_scc_order({"a", "z"}, occurrences, components)
        == [frozenset({"z"}), frozenset({"a"})],
        "reverse-topological component-order self-check failed",
    )


def validate_affected_closure_connectivity(
    path: Path,
    documents: dict[str, Any],
    occurrences: list[dict[str, Any]],
    phase: str,
) -> None:
    """Require one weakly connected managed-document closure."""
    document_ids = set(documents)
    require(document_ids, f"{phase} affected closure is empty in {path.name}")
    neighbors: dict[str, set[str]] = {
        document_id: set() for document_id in document_ids
    }
    for occurrence in occurrences:
        if not occurrence.get("active"):
            continue
        source_id = occurrence["sourceDocumentId"]
        target_id = occurrence["targetDocumentId"]
        if source_id in neighbors and target_id in neighbors:
            neighbors[source_id].add(target_id)
            neighbors[target_id].add(source_id)

    pending = [min(document_ids)]
    reached: set[str] = set()
    while pending:
        document_id = pending.pop()
        if document_id in reached:
            continue
        reached.add(document_id)
        pending.extend(sorted(neighbors[document_id] - reached, reverse=True))
    require(
        reached == document_ids,
        f"{phase} affected closure is not weakly connected in {path.name}: "
        f"unreachable={sorted(document_ids - reached)}",
    )


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
    require(isinstance(entries, list), f"manifest files are not an array: {base.relative_to(ROOT)}")
    paths = [entry.get("path") for entry in entries if isinstance(entry, dict)]
    require(len(paths) == len(entries) and all(isinstance(path, str) for path in paths), f"invalid manifest file entry: {base.relative_to(ROOT)}")
    require(len(paths) == len(set(paths)), f"duplicate manifest file path: {base.relative_to(ROOT)}")
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
    validate_closure_root_profile_self_check()
    validate_nfc_order_token_self_check()
    validate_public_handler_order_self_check()
    validate_generation_transition_self_check()
    validate_managed_occurrence_patch_boundary_self_check()
    validate_undeclared_prospective_activation_self_check()
    validate_patch_shape_self_check()
    ordinary_schema = load_yaml(FIX / "fixture-schema.yaml")
    closure_schema = load_yaml(FIX / "closure-fixture-schema.yaml")
    ov = jsonschema.Draft202012Validator(ordinary_schema)
    cv = jsonschema.Draft202012Validator(closure_schema)
    for path in ordinary:
        payload = load_yaml(path)
        errors = sorted(ov.iter_errors(payload), key=lambda e: list(e.path))
        require(not errors, f"ordinary schema failure {path.relative_to(ROOT)}: {errors[0].message if errors else ''}")
    closure_payloads: list[tuple[Path, dict[str, Any]]] = []
    for path in closure:
        payload = load_yaml(path)
        errors = sorted(cv.iter_errors(payload), key=lambda e: list(e.path))
        require(not errors, f"closure schema failure {path.relative_to(ROOT)}: {errors[0].message if errors else ''}")
        validate_nfc_order_tokens(path, payload)
        closure_payloads.append((path, payload))

    # Keep the non-completed attempt branch independently executable.  In
    # particular, Root-profile validation must accept its intentionally absent
    # gas trace, while the schema must reject completed-result evidence being
    # attached to that branch.
    needs_path, needs_payload = next(
        (path, payload)
        for path, payload in closure_payloads
        if payload.get("expected", {}).get("attemptOutcome") == "NeedsResources"
    )
    needs_expected = needs_payload["expected"]
    require(
        set(needs_expected) == {"attemptOutcome", "requiredBlueIds"},
        "NeedsResources fixture carries completed-result evidence",
    )
    validate_closure_root_profile(needs_path, needs_payload)
    illegal_needs_result = json.loads(json.dumps(needs_payload))
    illegal_needs_result["expected"].update(
        {
            "workTrace": [],
            "gasTrace": [],
            "subscriptionDeltas": [],
            "checkpointWrites": [],
            "publicEvents": [],
        }
    )
    require(
        bool(list(cv.iter_errors(illegal_needs_result))),
        "closure schema accepts completed-result evidence on NeedsResources",
    )

    # Synthetic profile probes are deliberately independent of the released
    # fixture inventory.  They prove that widening either closure input or
    # result subscription scope fails schema admission without adding a vector.
    direct_probe: dict[str, Any] | None = None
    channel_probe: dict[str, Any] | None = None
    for _path, payload in closure_payloads:
        deliveries = payload.get("input", {}).get("directDeliveries", [])
        if direct_probe is None and deliveries:
            direct_probe = json.loads(json.dumps(payload))
            direct_probe["input"]["directDeliveries"][0]["scopePath"] = "/nested"
            direct_probe["input"]["directDeliveries"][0]["activationGeneration"] = 1
        if channel_probe is None:
            for delta in payload.get("expected", {}).get("subscriptionDeltas", []):
                for side in ("beforeSubscription", "afterSubscription"):
                    if isinstance(delta.get(side), dict):
                        channel_probe = json.loads(json.dumps(payload))
                        mutated = next(
                            item
                            for item in channel_probe["expected"]["subscriptionDeltas"]
                            if isinstance(item.get(side), dict)
                        )[side]["channelOccurrence"]
                        mutated["scopePath"] = "/nested"
                        mutated["scopeActivationGeneration"] = 1
                        break
                if channel_probe is not None:
                    break
        if direct_probe is not None and channel_probe is not None:
            break
    require(direct_probe is not None, "closure schema Root-scope direct probe unavailable")
    require(
        bool(list(cv.iter_errors(direct_probe))),
        "closure schema accepts a synthetic non-Root direct delivery",
    )
    require(channel_probe is not None, "closure schema Root-scope result probe unavailable")
    require(
        bool(list(cv.iter_errors(channel_probe))),
        "closure schema accepts a synthetic non-Root ChannelOccurrence",
    )

    success_payload = next(
        payload
        for _path, payload in closure_payloads
        if payload.get("expected", {}).get("status") == "success"
    )
    illegal_success_rollback = json.loads(json.dumps(success_payload))
    illegal_success_rollback["expected"]["rollbackToInput"] = True
    require(
        bool(list(cv.iter_errors(illegal_success_rollback))),
        "closure schema accepts rollbackToInput=true on success",
    )
    failure_payload = next(
        payload
        for _path, payload in closure_payloads
        if payload.get("expected", {}).get("attemptOutcome") == "Complete"
        and payload["expected"].get("status") != "success"
    )
    illegal_failure_commit = json.loads(json.dumps(failure_payload))
    illegal_failure_commit["expected"]["rollbackToInput"] = False
    require(
        bool(list(cv.iter_errors(illegal_failure_commit))),
        "closure schema accepts rollbackToInput=false on non-success",
    )

    patch_payload = next(
        payload
        for _path, payload in closure_payloads
        if any(
            result.get("patches")
            for bucket in ("handlers", "initializationHandlers")
            for result in payload.get("runtime", {}).get(bucket, {}).values()
        )
    )
    illegal_root_patch = json.loads(json.dumps(patch_payload))
    patch = next(
        result["patches"][0]
        for bucket in ("handlers", "initializationHandlers")
        for result in illegal_root_patch["runtime"].get(bucket, {}).values()
        if result.get("patches")
    )
    patch["path"] = "/"
    require(
        bool(list(cv.iter_errors(illegal_root_patch))),
        "closure schema accepts a Root-targeted runtime patch",
    )
    illegal_remove_value = json.loads(json.dumps(patch_payload))
    patch = next(
        result["patches"][0]
        for bucket in ("handlers", "initializationHandlers")
        for result in illegal_remove_value["runtime"].get(bucket, {}).values()
        if result.get("patches")
    )
    patch["op"] = "remove"
    patch.setdefault("val", {})
    require(
        bool(list(cv.iter_errors(illegal_remove_value))),
        "closure schema accepts val on a remove patch",
    )



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


def validate_gas_manifest_and_microfixtures(ordinary: list[Path]) -> int:
    """Bind each frozen gas counter to exactly one canonical microfixture."""
    gas = load_yaml_unique(GAS)
    ordinary_set = set(ordinary)
    gas_micro_files = set((FIX / "gas-micro").glob("*.yaml"))
    require(
        gas_micro_files <= ordinary_set,
        "gas-micro directory contains non-fixture YAML: "
        f"{sorted(path.name for path in gas_micro_files - ordinary_set)}",
    )
    namespaces = gas.get("namespaces")
    require(isinstance(namespaces, dict) and namespaces, "gas namespaces are missing")
    portable_limits = gas.get("portableLimits")
    require(
        isinstance(portable_limits, dict) and portable_limits,
        "portable gas limits are missing",
    )
    for limit_name in portable_limits:
        require_nfc_order_token(limit_name, f"gas manifest limit {limit_name!r}")

    expected_weights: dict[tuple[str, str], int] = {}
    for namespace, namespace_entry in namespaces.items():
        require(
            isinstance(namespace, str) and isinstance(namespace_entry, dict),
            f"invalid gas namespace declaration: {namespace!r}",
        )
        require_nfc_order_token(namespace, f"gas namespace {namespace!r}")
        counters = namespace_entry.get("counters")
        require(
            isinstance(counters, dict),
            f"gas counters are missing for namespace {namespace}",
        )
        counter_count = namespace_entry.get("counterCount")
        require(
            isinstance(counter_count, int)
            and not isinstance(counter_count, bool)
            and counter_count == len(counters),
            f"gas counterCount mismatch for namespace {namespace}",
        )
        for counter, weight in counters.items():
            require(
                isinstance(counter, str) and counter,
                f"invalid gas counter name in namespace {namespace}: {counter!r}",
            )
            require_nfc_order_token(
                counter, f"gas counter {namespace}.{counter}"
            )
            require(
                isinstance(weight, int)
                and not isinstance(weight, bool)
                and 0 < weight <= 9_007_199_254_740_991,
                f"invalid gas weight for {namespace}.{counter}: {weight!r}",
            )
            expected_weights[(namespace, counter)] = weight

    actual: dict[tuple[str, str], Path] = {}
    trace_fields = {
        "sequence", "namespace", "counter", "quantity", "weight", "subtotal",
    }
    for path in ordinary:
        fixture = load_yaml(path)
        if fixture.get("operation") != "gas-micro":
            continue
        fixture_input = fixture.get("input")
        if not isinstance(fixture_input, dict):
            continue
        has_namespace = "namespace" in fixture_input
        has_counter = "counter" in fixture_input
        if not has_namespace and not has_counter:
            continue
        require(
            has_namespace and has_counter,
            f"named gas microfixture must declare both namespace and counter: {path.name}",
        )
        require(
            set(fixture_input) == {"namespace", "counter", "quantity", "weightManifest"},
            f"named gas microfixture input fields are not exact in {path.name}",
        )
        namespace = fixture_input["namespace"]
        counter = fixture_input["counter"]
        key = (namespace, counter)
        require(key in expected_weights, f"unknown gas counter microfixture in {path.name}: {namespace}.{counter}")
        previous = actual.get(key)
        require(
            previous is None,
            f"duplicate gas counter microfixture for {namespace}.{counter}: "
            f"{previous.name if previous is not None else path.name}, {path.name}",
        )
        actual[key] = path

        expected_rel = f"gas-micro/{namespace}-{counter}.yaml"
        require(
            path.relative_to(FIX).as_posix() == expected_rel,
            f"gas microfixture path mismatch for {namespace}.{counter}: {path.relative_to(FIX)}",
        )
        require(
            fixture.get("id") == f"gas-{namespace}-{counter}",
            f"gas microfixture id mismatch in {path.name}",
        )
        require(
            fixture.get("vectors") == ["C-GAS-01"]
            and fixture.get("category") == "gas",
            f"gas counter microfixture classification mismatch in {path.name}",
        )
        require(
            fixture_input["weightManifest"] == gas.get("schedule"),
            f"gas schedule binding mismatch in {path.name}",
        )
        quantity = fixture_input["quantity"]
        require(
            isinstance(quantity, int) and not isinstance(quantity, bool) and quantity == 3,
            f"gas counter microfixture quantity must be exactly 3 in {path.name}",
        )
        expected = fixture.get("expected")
        require(
            isinstance(expected, dict) and set(expected) == {"trace", "totalGas"},
            f"gas counter microfixture expected fields are not exact in {path.name}",
        )
        trace = expected["trace"]
        require(
            isinstance(trace, list) and len(trace) == 1 and isinstance(trace[0], dict),
            f"gas counter microfixture must have exactly one trace entry in {path.name}",
        )
        entry = trace[0]
        weight = expected_weights[key]
        subtotal = quantity * weight
        require(
            subtotal <= 9_007_199_254_740_991,
            f"gas counter microfixture subtotal exceeds the safe integer domain "
            f"in {path.name}",
        )
        require(
            set(entry) == trace_fields,
            f"gas counter microfixture trace fields are not exact in {path.name}",
        )
        require(
            entry == {
                "sequence": 0,
                "namespace": namespace,
                "counter": counter,
                "quantity": quantity,
                "weight": weight,
                "subtotal": subtotal,
            },
            f"gas counter microfixture trace does not match the manifest in {path.name}",
        )
        require(
            expected["totalGas"] == subtotal,
            f"gas counter microfixture totalGas mismatch in {path.name}",
        )

    expected_keys = set(expected_weights)
    actual_keys = set(actual)
    require(
        actual_keys == expected_keys,
        "gas manifest/microfixture inventory mismatch: "
        f"missing={sorted(expected_keys - actual_keys)} "
        f"extra={sorted(actual_keys - expected_keys)}",
    )
    return len(actual)


def validate_fixture_manifest_inventory(
    manifest: dict[str, Any],
    ordinary: list[Path],
    closure: list[Path],
    vectors: dict[str, list[str]],
) -> None:
    """Bind the fixture manifest to the exact discovered package inventory."""
    entries = manifest.get("files")
    require(isinstance(entries, list), "fixture manifest files must be an array")
    require(
        all(isinstance(entry, dict) and isinstance(entry.get("path"), str) for entry in entries),
        "fixture manifest contains an invalid file entry",
    )
    declared_paths = [entry["path"] for entry in entries]
    require(declared_paths == sorted(declared_paths), "fixture manifest file entries are not sorted")
    require(
        len(declared_paths) == len(set(declared_paths)),
        "fixture manifest contains duplicate file paths",
    )
    discovered_paths = {
        path.relative_to(FIX).as_posix()
        for path in FIX.rglob("*")
        if path.is_file()
        and path != FIX / "manifest.yaml"
        and "__pycache__" not in path.parts
    }
    declared_set = set(declared_paths)
    require(
        declared_set == discovered_paths,
        "fixture manifest inventory mismatch: "
        f"missing={sorted(discovered_paths - declared_set)} "
        f"extra={sorted(declared_set - discovered_paths)}",
    )

    ordinary_by_path = {path.relative_to(FIX).as_posix(): path for path in ordinary}
    closure_by_path = {path.relative_to(FIX).as_posix(): path for path in closure}
    for entry in entries:
        rel = entry["path"]
        if rel in ordinary_by_path:
            fixture = load_yaml(ordinary_by_path[rel])
            role = "gas-fixture" if fixture.get("category") == "gas" else "behavior-fixture"
            require(
                set(entry) == {"path", "role", "sha256", "bytes", "vectors"}
                and entry["role"] == role
                and entry["vectors"] == fixture["vectors"],
                f"ordinary fixture manifest entry mismatch: {rel}",
            )
        elif rel in closure_by_path:
            fixture = load_yaml(closure_by_path[rel])
            require(
                set(entry) == {"path", "role", "sha256", "bytes", "vectors"}
                and entry["role"] == "closure-fixture"
                and entry["vectors"] == fixture["vectors"],
                f"closure fixture manifest entry mismatch: {rel}",
            )
        else:
            require(
                set(entry) == {"path", "role", "sha256", "bytes"}
                and entry["role"] == "support",
                f"support fixture manifest entry mismatch: {rel}",
            )

    ordinary_gas = sum(
        1 for path in ordinary if load_yaml(path).get("category") == "gas"
    )
    expected_counts = {
        "vectorCount": len(vectors),
        "ordinaryVectorCount": len([v for v in vectors if not v.startswith("C-CLO-")]),
        "closureVectorCount": len([v for v in vectors if v.startswith("C-CLO-")]),
        "ordinaryFixtureCount": len(ordinary),
        "ordinaryBehaviorFixtureCount": len(ordinary) - ordinary_gas,
        "ordinaryGasFixtureCount": ordinary_gas,
        "closureFixtureCount": len(closure),
        "totalExecutableFixtureCount": len(ordinary) + len(closure),
    }
    for field, expected in expected_counts.items():
        require(
            manifest.get(field) == expected,
            f"fixture manifest {field} mismatch: expected {expected}, got {manifest.get(field)!r}",
        )


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


def validate_occurrence_successor_generation(
    path: Path,
    before: dict[str, Any] | None,
    after: dict[str, Any],
    *,
    preserves_lineage: bool,
    transition: str,
) -> None:
    """Validate the generation law when exact predecessor evidence is known."""
    if before is None:
        require(
            after["activationGeneration"] == 1,
            f"first embedded occurrence generation is not 1 in {path.name}: "
            f"{after['sourceDocumentId']}{after['sourcePath']}",
        )
        return

    require(
        before["sourceDocumentId"] == after["sourceDocumentId"]
        and before["sourcePath"] == after["sourcePath"],
        f"occurrence predecessor path mismatch in {path.name}: {transition}",
    )
    if preserves_lineage:
        require(
            after["activationGeneration"] == before["activationGeneration"]
            and after["occurrenceIdentity"] == before["occurrenceIdentity"],
            f"same-lineage occurrence transition changed generation or lineage "
            f"identity in {path.name}: {after['sourceDocumentId']}"
            f"{after['sourcePath']} ({transition})",
        )
        return

    require(
        before["activationGeneration"] < SAFE_INTEGER_MAX
        and after["activationGeneration"]
        == before["activationGeneration"] + 1
        and after["occurrenceIdentity"] != before["occurrenceIdentity"]
        and after["bindingIdentity"] != before["bindingIdentity"],
        f"new occurrence lineage is not predecessor generation plus one with "
        f"fresh occurrence and binding identities in {path.name}: "
        f"{after['sourceDocumentId']}"
        f"{after['sourcePath']} ({transition})",
    )


def validate_occurrence_transition_law(
    path: Path,
    before_occurrences: list[dict[str, Any]],
    after_occurrences: list[dict[str, Any]],
) -> None:
    """Validate every generation transition provable from two closed snapshots.

    Initial activation of an inactive reservation, later-invocation re-add of
    an already committed inactive successor, and same-lineage exact-state
    rebinds preserve the generation and occurrence identity. Active removal
    replaces the row with the sole processor-derived inactive successor at the
    next generation and with fresh occurrence and binding identities. That
    successor cannot activate in the invocation that creates it. A changed
    replacement with another target lineage and same-invocation
    remove-then-re-add are unsupported in Contracts 1.0. A first feeder
    reservation starts at generation 1, while a completed result cannot
    introduce a row without an exact input predecessor.
    """
    before_by_path = {
        (row["sourceDocumentId"], row["sourcePath"]): row
        for row in before_occurrences
    }
    after_by_path = {
        (row["sourceDocumentId"], row["sourcePath"]): row
        for row in after_occurrences
    }
    require(
        len(before_by_path) == len(before_occurrences)
        and len(after_by_path) == len(after_occurrences),
        f"occurrence transition snapshots repeat a source path in {path.name}",
    )
    require(
        set(before_by_path) <= set(after_by_path),
        f"completed result deleted occurrence rows instead of retaining exact "
        f"inactive successor/prospective state in {path.name}: "
        f"{sorted(set(before_by_path) - set(after_by_path))}",
    )

    for key, after in after_by_path.items():
        before = before_by_path.get(key)
        if before is None:
            require(
                False,
                f"completed result introduced an occurrence row that was not "
                f"reserved in the input or derived from an active predecessor "
                f"in {path.name}: {after['sourceDocumentId']}"
                f"{after['sourcePath']}",
            )
        same_lineage = (
            before["targetDocumentId"] == after["targetDocumentId"]
            and before["bindingPolicyIdentity"]
            == after["bindingPolicyIdentity"]
        )
        require(
            same_lineage,
            f"same-invocation active-path retarget is unsupported in "
            f"{path.name}: {after['sourceDocumentId']}{after['sourcePath']}",
        )
        retirement = same_lineage and before["active"] and not after["active"]
        preserves_lineage = same_lineage and not retirement
        if same_lineage:
            if retirement:
                transition = "retirement-successor"
            elif not before["active"] and after["active"]:
                transition = "reserved-activation"
            elif before["bindingIdentity"] != after["bindingIdentity"]:
                transition = "same-lineage-rebind"
            else:
                transition = "same-lineage-continuity"
        validate_occurrence_successor_generation(
            path,
            before,
            after,
            preserves_lineage=preserves_lineage,
            transition=transition,
        )


def validate_occurrence_transition_law_self_check() -> None:
    """Exercise every branch without adding a release fixture or hidden input."""
    path = Path("occurrence-transition-law-self-check.yaml")

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
    retired = row(
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

    validate_occurrence_successor_generation(
        path,
        None,
        reserved,
        preserves_lineage=False,
        transition="first-reservation",
    )
    validate_occurrence_transition_law(path, [reserved], [active])
    validate_occurrence_transition_law(path, [active], [retired])
    validate_occurrence_transition_law(path, [retired], [readded])
    validate_occurrence_transition_law(path, [active], [rebound])
    try:
        validate_occurrence_transition_law(
            path, [active], [illegal_same_invocation_retarget]
        )
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "occurrence transition self-check accepted same-invocation retarget"
        )
    illegal_same_invocation_remove_readd = row(
        2,
        "target-a",
        True,
        "occurrence-2",
        binding_identity="binding-2",
    )
    try:
        validate_occurrence_transition_law(
            path, [active], [illegal_same_invocation_remove_readd]
        )
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "occurrence transition self-check accepted same-invocation "
            "remove-then-re-add"
        )
    illegal_reserved_retarget = row(
        2,
        "target-b",
        False,
        "occurrence-3",
        binding_identity="binding-3",
    )
    try:
        validate_occurrence_transition_law(
            path, [retired], [illegal_reserved_retarget]
        )
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "occurrence transition self-check accepted reserved-path retarget"
        )
    try:
        validate_occurrence_transition_law(path, [active], [])
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "occurrence transition self-check accepted a deleted active row"
        )


def validate_undeclared_prospective_activation_self_check() -> None:
    input_document = {"contracts": {}}
    output_document = {
        "child": {"blueId": "target-blue-id"},
        "contracts": {
            "embedded": {
                "type": {"blueId": PROCESS_EMBEDDED},
                "paths": ["/child"],
            }
        },
    }
    require(
        not active_declared_path(input_document, "/child")
        and active_declared_path(output_document, "/child"),
        "undeclared prospective activation declaration self-check failed",
    )
    inactive = {
        "sourceDocumentId": "source",
        "sourcePath": "/child",
        "activationGeneration": 1,
        "targetDocumentId": "target",
        "bindingPolicyIdentity": "policy",
        "occurrenceIdentity": "occurrence",
        "bindingIdentity": "binding",
        "expectedTargetBlueId": "target-blue-id",
        "active": False,
    }
    active = dict(inactive, active=True)
    validate_occurrence_transition_law(
        Path("undeclared-prospective-activation-self-check.yaml"),
        [inactive],
        [active],
    )


def validate_generation_transition_law(
    path: Path,
    input_graph_generation: int,
    input_components: list[dict[str, Any]],
    input_occurrences: list[dict[str, Any]],
    output_graph_generation: int,
    output_components: list[dict[str, Any]],
    output_occurrences: list[dict[str, Any]],
    status: str,
) -> None:
    """Enforce the exact graph/component successor laws from Contracts §5.7."""

    def active_identities(rows: list[dict[str, Any]]) -> set[str]:
        return {
            row["occurrenceIdentity"] for row in rows if row.get("active")
        }

    active_changed = active_identities(input_occurrences) != active_identities(
        output_occurrences
    )
    if status == "success":
        require(
            not active_changed or input_graph_generation < SAFE_INTEGER_MAX,
            f"graph generation overflow in {path.name}",
        )
        expected_graph_generation = input_graph_generation + int(active_changed)
    else:
        expected_graph_generation = input_graph_generation
    require(
        output_graph_generation == expected_graph_generation,
        f"graph generation does not follow the exact active-occurrence-set "
        f"transition law in {path.name}: expected "
        f"{expected_graph_generation}, got {output_graph_generation}",
    )

    def member_set(component: dict[str, Any]) -> frozenset[str]:
        return frozenset(component["orderedMemberDocumentIds"])

    def internal_edges(
        component: dict[str, Any], rows: list[dict[str, Any]]
    ) -> frozenset[tuple[str, str, str]]:
        members = member_set(component)
        return frozenset(
            (
                row["occurrenceIdentity"],
                row["sourceDocumentId"],
                row["targetDocumentId"],
            )
            for row in rows
            if row.get("active")
            and row["sourceDocumentId"] in members
            and row["targetDocumentId"] in members
        )

    input_shapes = [
        (component, member_set(component), internal_edges(component, input_occurrences))
        for component in input_components
    ]
    for output_component in output_components:
        members = member_set(output_component)
        edges = internal_edges(output_component, output_occurrences)
        exact_predecessors = [
            component
            for component, before_members, before_edges in input_shapes
            if before_members == members and before_edges == edges
        ]
        require(
            len(exact_predecessors) <= 1,
            f"ambiguous exact component predecessor in {path.name}: "
            f"{sorted(members)}",
        )
        if exact_predecessors:
            expected_component_generation = exact_predecessors[0][
                "componentGeneration"
            ]
        else:
            contributing_generations = [
                component["componentGeneration"]
                for component, before_members, _before_edges in input_shapes
                if before_members & members
            ]
            maximum = max(contributing_generations, default=0)
            require(
                maximum < SAFE_INTEGER_MAX,
                f"component generation overflow in {path.name}: "
                f"{sorted(members)}",
            )
            expected_component_generation = maximum + 1
        require(
            output_component["componentGeneration"]
            == expected_component_generation,
            f"component generation does not follow the exact member/internal-"
            f"edge successor law in {path.name}: {sorted(members)} expected "
            f"{expected_component_generation}, got "
            f"{output_component['componentGeneration']}",
        )


def validate_generation_transition_self_check() -> None:
    path = Path("generation-transition-self-check.yaml")
    component_a = {
        "orderedMemberDocumentIds": ["a"],
        "componentGeneration": 4,
    }
    component_b = {
        "orderedMemberDocumentIds": ["b"],
        "componentGeneration": 7,
    }
    merged = {
        "orderedMemberDocumentIds": ["a", "b"],
        "componentGeneration": 8,
    }
    edge = {
        "occurrenceIdentity": "edge-a-b",
        "sourceDocumentId": "a",
        "targetDocumentId": "b",
        "active": True,
    }
    validate_generation_transition_law(
        path, 10, [component_a], [], 10, [component_a], [], "success"
    )
    validate_generation_transition_law(
        path,
        10,
        [component_a, component_b],
        [],
        11,
        [merged],
        [edge],
        "success",
    )
    try:
        validate_generation_transition_law(
            path,
            10,
            [component_a, component_b],
            [],
            10,
            [merged],
            [edge],
            "success",
        )
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure("changed active graph kept its generation")
    bad_merged = dict(merged, componentGeneration=7)
    try:
        validate_generation_transition_law(
            path,
            10,
            [component_a, component_b],
            [],
            11,
            [bad_merged],
            [edge],
            "success",
        )
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure("changed component kept an arbitrary generation")


def reject_mixed_blue_id_wrappers(path: Path, value: Any, context: str) -> None:
    if isinstance(value, list):
        for index, child in enumerate(value):
            reject_mixed_blue_id_wrappers(path, child, f"{context}/{index}")
        return
    if not isinstance(value, dict):
        return
    require(
        "blueId" not in value or set(value) == {"blueId"},
        f"mixed blueId reference wrapper in {path.name}: {context}",
    )
    for key, child in value.items():
        reject_mixed_blue_id_wrappers(path, child, f"{context}/{key}")


def establish_occurrence_value_blue_id(
    path: Path,
    value: Any,
    target_document_id: str,
    documents: dict[str, Any],
    components: list[dict[str, Any]],
    occurrences: list[dict[str, Any]],
    *,
    context: str,
) -> str:
    """Establish one occurrence value's representation-neutral exact identity.

    Cyclic members deliberately have no standalone direct-identity branch.  An
    inline cyclic member is accepted only when replacing that member in the
    complete declared component reproduces the exact proof and member mapping.
    """
    require(
        target_document_id in documents,
        f"occurrence target document missing in {path.name}: {target_document_id}",
    )
    require(
        isinstance(value, dict),
        f"Process Embedded value is not an object in {path.name}: {context}",
    )
    reject_mixed_blue_id_wrappers(path, value, context)
    reference = pure_blue_reference(value)
    if reference is not None:
        return reference

    matching_components = [
        component
        for component in components
        if target_document_id in component.get("orderedMemberDocumentIds", [])
    ]
    require(
        len(matching_components) == 1,
        f"occurrence target does not select one component in {path.name}: "
        f"{target_document_id}",
    )
    component = matching_components[0]
    target_record = documents[target_document_id]
    if component.get("kind") != "CYCLIC":
        try:
            established = direct_blue_id(value)
        except (TypeError, ValueError) as exc:
            raise ValidationFailure(
                f"acyclic inline occurrence value has no exact identity in "
                f"{path.name}: {context}: {exc}"
            ) from exc
        if value == target_record["document"]:
            require(
                established == target_record["blueId"],
                f"complete inline occurrence value does not establish its target "
                f"record in {path.name}: {context}",
            )
            return target_record["blueId"]
        return established

    member_document_ids = component["orderedMemberDocumentIds"]
    member_blue_ids = component["orderedMemberBlueIds"]
    require(
        len(member_document_ids) == len(member_blue_ids),
        f"cyclic component member mapping is incomplete in {path.name}: {context}",
    )
    proof = component.get("completeCyclicProof")
    require(
        isinstance(proof, dict)
        and proof.get("masterBlueId") == component.get("masterBlueId"),
        f"cyclic inline occurrence lacks complete target proof in {path.name}: "
        f"{context}",
    )
    proof_states = proof.get("memberStates")
    require(
        isinstance(proof_states, list)
        and proof_states
        == [
            {"documentId": document_id, "blueId": blue_id}
            for document_id, blue_id in zip(
                member_document_ids, member_blue_ids, strict=True
            )
        ],
        f"cyclic inline occurrence proof/member mapping mismatch in {path.name}: "
        f"{context}",
    )
    source_documents = cyclic_component_source_documents(
        path,
        component,
        documents,
        occurrences,
        candidate_document_id=target_document_id,
        candidate_value=value,
    )
    try:
        oracle = cyclic_set_oracle(source_documents)
    except (TypeError, ValueError) as exc:
        raise ValidationFailure(
            f"cyclic inline occurrence does not reconstruct through its complete "
            f"component in {path.name}: {context}: {exc}"
        ) from exc
    require(
        oracle.master_blue_id == component["masterBlueId"]
        and list(oracle.member_ids_in_source_order()) == member_blue_ids
        and cyclic_canonical_limit_form(oracle) == proof.get("declaredPlaceholderSet"),
        f"cyclic inline occurrence does not match the complete target proof/member "
        f"mapping in {path.name}: {context}",
    )
    target_index = member_document_ids.index(target_document_id)
    established = member_blue_ids[target_index]
    require(
        established == target_record["blueId"],
        f"cyclic inline occurrence target record/member mapping mismatch in "
        f"{path.name}: {context}",
    )
    return established


def validate_occurrence_set(
    path: Path,
    documents: dict[str, Any],
    occurrences: list[dict[str, Any]],
    components: list[dict[str, Any]],
    *,
    allow_invalid: bool,
) -> int:
    validate_process_embedded_declaration_coverage(path, documents, occurrences)
    checked = 0
    seen: set[str] = set()
    for occurrence in occurrences:
        identity = validate_occurrence_identity(path, occurrence)
        require(identity not in seen, f"duplicate occurrence identity in {path.name}: {identity}")
        seen.add(identity)
        source_id = occurrence["sourceDocumentId"]
        target_id = occurrence["targetDocumentId"]
        require(source_id in documents and target_id in documents, f"occurrence document missing in {path.name}")
        source_document = documents[source_id]["document"]
        if not occurrence.get("active"):
            absent = object()
            try:
                prospective_node = pointer_get(
                    source_document, occurrence["sourcePath"]
                )
            except KeyError:
                prospective_node = absent
            if prospective_node is not absent:
                established = establish_occurrence_value_blue_id(
                    path,
                    prospective_node,
                    target_id,
                    documents,
                    components,
                    occurrences,
                    context=(
                        f"prospective/{source_id}{occurrence['sourcePath']}"
                    ),
                )
                require(
                    established == occurrence["expectedTargetBlueId"],
                    f"inactive prospective occurrence value mismatch in "
                    f"{path.name}: {source_id}{occurrence['sourcePath']}",
                )
            if occurrence["pendingHistoricalEpoch"] is None:
                require(
                    documents[target_id]["blueId"]
                    == occurrence["expectedTargetBlueId"],
                    f"prospective binding target document mismatch in "
                    f"{path.name}: {target_id}",
                )
            checked += 1
            continue
        require(
            active_declared_path(source_document, occurrence["sourcePath"]),
            f"active occurrence path is not declared Process Embedded in "
            f"{path.name}: {source_id}{occurrence['sourcePath']}",
        )
        try:
            node = pointer_get(source_document, occurrence["sourcePath"])
        except KeyError:
            if allow_invalid:
                continue
            raise ValidationFailure(f"active occurrence path absent in {path.name}: {source_id}{occurrence['sourcePath']}")
        established = establish_occurrence_value_blue_id(
            path,
            node,
            target_id,
            documents,
            components,
            occurrences,
            context=f"{source_id}{occurrence['sourcePath']}",
        )
        require(established == occurrence["expectedTargetBlueId"], f"occurrence target BlueId mismatch in {path.name}: {source_id}{occurrence['sourcePath']}")
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


def replace_existing_pointer_value(
    path: Path, value: Any, pointer: str, replacement: Any, context: str
) -> Any:
    segments = decoded_pointer_segments(path, pointer, context)
    if not segments:
        return replacement
    result = json.loads(json.dumps(value))
    current = result
    for segment in segments[:-1]:
        if isinstance(current, dict) and segment in current:
            current = current[segment]
        elif (
            isinstance(current, list)
            and segment.isdigit()
            and int(segment) < len(current)
        ):
            current = current[int(segment)]
        else:
            raise ValidationFailure(
                f"active occurrence path absent while normalizing {path.name}: "
                f"{context}"
            )
    final = segments[-1]
    if isinstance(current, dict) and final in current:
        current[final] = replacement
    elif (
        isinstance(current, list)
        and final.isdigit()
        and int(final) < len(current)
    ):
        current[int(final)] = replacement
    else:
        raise ValidationFailure(
            f"active occurrence path absent while normalizing {path.name}: {context}"
        )
    return result


def cyclic_component_source_documents(
    path: Path,
    component: dict[str, Any],
    documents: dict[str, Any],
    occurrences: list[dict[str, Any]],
    *,
    candidate_document_id: str | None = None,
    candidate_value: Any = None,
) -> list[Any]:
    """Build proof input while collapsing complete inline internal members."""
    member_document_ids = component["orderedMemberDocumentIds"]
    member_set = set(member_document_ids)
    member_blue_ids = component["orderedMemberBlueIds"]
    source_index_by_blue_id = {
        blue_id: index for index, blue_id in enumerate(member_blue_ids)
    }
    source_documents: list[Any] = []
    for source_document_id in member_document_ids:
        source_document = (
            candidate_value
            if source_document_id == candidate_document_id
            else documents[source_document_id]["document"]
        )
        for occurrence in occurrences:
            if (
                not occurrence.get("active")
                or occurrence["sourceDocumentId"] != source_document_id
                or occurrence["targetDocumentId"] not in member_set
            ):
                continue
            try:
                occurrence_value = pointer_get(
                    source_document, occurrence["sourcePath"]
                )
            except KeyError:
                continue
            context = f"{source_document_id}{occurrence['sourcePath']}"
            require(
                isinstance(occurrence_value, dict),
                f"cyclic internal occurrence is not an object in {path.name}: "
                f"{context}",
            )
            reject_mixed_blue_id_wrappers(path, occurrence_value, context)
            if pure_blue_reference(occurrence_value) is not None:
                continue
            target_document_id = occurrence["targetDocumentId"]
            if occurrence_value == documents[target_document_id]["document"]:
                source_document = replace_existing_pointer_value(
                    path,
                    source_document,
                    occurrence["sourcePath"],
                    {"blueId": documents[target_document_id]["blueId"]},
                    context,
                )
        source_documents.append(
            restore_component_placeholders(
                source_document, source_index_by_blue_id
            )
        )
    return source_documents


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
    declared_order = [
        frozenset(component["orderedMemberDocumentIds"])
        for component in components
    ]
    canonical_order = canonical_scc_order(
        set(documents), occurrences, components
    )
    require(
        declared_order == canonical_order,
        f"component sequence is not canonical target-before-source order in "
        f"{path.name}: declared={declared_order}, expected={canonical_order}",
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
            source_documents = cyclic_component_source_documents(
                path, component, documents, occurrences
            )
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


def fixture_oracle_stages(
    path: Path, fixture: dict[str, Any]
) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    route = fixture.get("oracle")
    if route is None:
        return None, []
    require(
        isinstance(route, dict),
        f"oracle harness is not an object in {path.name}",
    )
    oracle_reference = route.get("path")
    require(
        isinstance(oracle_reference, str),
        f"oracle harness lacks a path in {path.name}",
    )
    oracle_path = (path.parent / oracle_reference).resolve()
    try:
        oracle_path.relative_to(ORACLES.resolve())
    except ValueError as exc:
        raise ValidationFailure(
            f"cyclic oracle escapes the oracle package in {path.name}: "
            f"{oracle_reference}"
        ) from exc
    require(
        oracle_path.is_file(),
        f"cyclic oracle does not exist in {path.name}: {oracle_reference}",
    )
    stages = oracle_stage_candidates(load_yaml(oracle_path))
    stage_keys = [
        (
            stage["name"],
            tuple(
                sorted(
                    document["documentId"]
                    for document in stage["sourceDocumentsWithThisReferences"]
                )
            ),
        )
        for stage in stages
    ]
    require(
        len(stage_keys) == len(set(stage_keys)),
        f"cyclic oracle repeats a stage/member route in {path.name}",
    )
    return route, stages


def select_oracle_stage(
    path: Path,
    stages: list[dict[str, Any]],
    stage_name: str,
    member_document_ids: Iterable[str],
) -> dict[str, Any]:
    member_set = set(member_document_ids)
    matches = [
        stage
        for stage in stages
        if stage["name"] == stage_name
        and {
            document["documentId"]
            for document in stage["sourceDocumentsWithThisReferences"]
        }
        == member_set
    ]
    require(
        len(matches) == 1,
        f"cannot select exact cyclic oracle stage in {path.name}: {stage_name}",
    )
    return matches[0]


def validate_component_oracle_routes(
    path: Path,
    fixture: dict[str, Any],
    input_components: list[dict[str, Any]],
    result_components: list[dict[str, Any]] | None,
) -> None:
    route, stages = fixture_oracle_stages(path, fixture)
    cyclic_by_key = {
        (location, component["componentStateIdentity"]): component
        for location, components in (
            ("INPUT", input_components),
            ("RESULT", result_components or []),
        )
        for component in components
        if component["kind"] == "CYCLIC"
    }
    component_routes = [] if route is None else route["componentStages"]
    routed_by_key: dict[tuple[str, str], dict[str, Any]] = {}
    for item in component_routes:
        key = (item["location"], item["componentStateIdentity"])
        require(
            key not in routed_by_key,
            f"duplicate component oracle route in {path.name}: {key}",
        )
        routed_by_key[key] = item
    require(
        set(routed_by_key) == set(cyclic_by_key),
        f"component oracle routes do not exactly cover cyclic input/result "
        f"components in {path.name}",
    )
    for key, item in routed_by_key.items():
        component = cyclic_by_key[key]
        stage = select_oracle_stage(
            path,
            stages,
            item["stage"],
            component["orderedMemberDocumentIds"],
        )
        source_documents = stage["sourceDocumentsWithThisReferences"]
        document_ids = [document["documentId"] for document in source_documents]
        require(
            set(document_ids) == set(component["orderedMemberDocumentIds"])
            and len(document_ids) == len(set(document_ids)),
            f"component oracle stage has the wrong member inventory in "
            f"{path.name}: {item['stage']}",
        )
        oracle = cyclic_set_oracle(source_documents)
        member_blue_ids = dict(
            zip(document_ids, oracle.member_ids_in_source_order(), strict=True)
        )
        require(
            oracle.master_blue_id == component["masterBlueId"]
            and [
                member_blue_ids[document_id]
                for document_id in component["orderedMemberDocumentIds"]
            ]
            == component["orderedMemberBlueIds"],
            f"component oracle stage does not establish the declared cyclic "
            f"state in {path.name}: {item['stage']}",
        )


def finalization_stage_name(
    path: Path, fixture: dict[str, Any], ordinal: int
) -> str:
    route, _stages = fixture_oracle_stages(path, fixture)
    require(
        route is not None,
        f"tentative finalization lacks an oracle harness in {path.name}",
    )
    matches = [
        item for item in route["finalizationStages"] if item["ordinal"] == ordinal
    ]
    require(
        len(matches) == 1,
        f"tentative finalization does not select one oracle stage in "
        f"{path.name}: {ordinal}",
    )
    return matches[0]["stage"]


def validate_tentative_finalizations(
    path: Path, fixture: dict[str, Any], expected: dict[str, Any]
) -> None:
    finalizations = expected["tentativeFinalizations"]
    require(
        [item["ordinal"] for item in finalizations]
        == list(range(len(finalizations))),
        f"tentative-finalization ordinals are not contiguous in {path.name}",
    )
    route, stages = fixture_oracle_stages(path, fixture)
    routed_finalizations = [] if route is None else route["finalizationStages"]
    routed_ordinals = [item["ordinal"] for item in routed_finalizations]
    require(
        len(routed_ordinals) == len(set(routed_ordinals))
        and set(routed_ordinals) == set(range(len(finalizations))),
        f"finalization oracle routes do not exactly cover result finalizations "
        f"in {path.name}",
    )
    for finalization in finalizations:
        stage_name = finalization_stage_name(path, fixture, finalization["ordinal"])
        stage = select_oracle_stage(
            path, stages, stage_name, finalization["memberBlueIds"]
        )
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
            f"{stage_name}",
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
        else:
            require(
                old["targetDocumentId"] == new["targetDocumentId"]
                and old["occurrenceIdentity"] == new["occurrenceIdentity"],
                "different-lineage occurrence retarget is unsupported in "
                "Contracts 1.0",
            )
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


def fixture_runtime(path: Path, data: dict[str, Any]) -> dict[str, Any]:
    """Return the closed, fixture-only top-level runtime harness."""
    runtime = data.get("runtime")
    require(
        isinstance(runtime, dict),
        f"top-level runtime harness is not an object in {path.name}",
    )
    return runtime


def decoded_pointer_segments(path: Path, pointer: str, context: str) -> list[str]:
    require(
        isinstance(pointer, str) and (pointer == "" or pointer.startswith("/")),
        f"invalid Runtime Pointer in {path.name}: {context}",
    )
    if pointer == "":
        return []
    segments: list[str] = []
    for raw in pointer[1:].split("/"):
        require(
            re.search(r"~(?:[^01]|$)", raw) is None,
            f"invalid Runtime Pointer escape in {path.name}: {context}",
        )
        segments.append(raw.replace("~1", "/").replace("~0", "~"))
    return segments


def validate_runtime_patch_shape(
    path: Path, patch: dict[str, Any], context: str
) -> list[str]:
    operation = patch.get("op")
    require(
        operation in {"add", "replace", "remove"},
        f"unsupported runtime patch operation in {path.name}: {context}",
    )
    pointer = patch.get("path")
    require(
        isinstance(pointer, str)
        and pointer not in {"", "/"}
        and not pointer.endswith("/"),
        f"runtime patch target is Root, empty, or has a trailing empty segment "
        f"in {path.name}: {context}",
    )
    segments = decoded_pointer_segments(path, pointer, context)
    require(
        (operation in {"add", "replace"}) == ("val" in patch),
        f"runtime patch val presence disagrees with op in {path.name}: {context}",
    )
    return segments


def validate_list_patch_terminal(
    path: Path,
    segment: str,
    operation: str,
    list_size: int,
    context: str,
) -> None:
    if segment == "-":
        require(
            operation == "add",
            f"'-' list pointer is valid only for add in {path.name}: {context}",
        )
        return
    require(
        re.fullmatch(r"0|[1-9][0-9]*", segment) is not None,
        f"noncanonical list index in {path.name}: {context}",
    )
    index = int(segment)
    require(
        index <= list_size if operation == "add" else index < list_size,
        f"list patch index is out of range in {path.name}: {context}",
    )


def validate_patch_shape_self_check() -> None:
    path = Path("runtime-patch-shape-self-check.yaml")
    for patch in (
        {"op": "add", "path": "/" , "val": 1},
        {"op": "replace", "path": "/a/", "val": 1},
        {"op": "remove", "path": "/a", "val": 1},
        {"op": "add", "path": "/a"},
        {"op": "add", "path": "/bad~2", "val": 1},
    ):
        try:
            validate_runtime_patch_shape(path, patch, "synthetic invalid patch")
        except ValidationFailure:
            pass
        else:
            raise ValidationFailure(f"invalid runtime patch shape accepted: {patch}")
    validate_runtime_patch_shape(
        path, {"op": "replace", "path": "/a//b", "val": 1},
        "synthetic valid empty interior segment",
    )
    for segment, operation, size in (("01", "replace", 2), ("2", "replace", 2), ("-", "remove", 2)):
        try:
            validate_list_patch_terminal(
                path, segment, operation, size, "synthetic invalid list patch"
            )
        except ValidationFailure:
            pass
        else:
            raise ValidationFailure(
                f"invalid list patch terminal accepted: {operation} {segment}"
            )
    validate_list_patch_terminal(
        path, "-", "add", 2, "synthetic append list patch"
    )


def projected_occurrence_value_from_patch(
    path: Path,
    patch: dict[str, Any],
    occurrence_path: str,
    context: str,
) -> tuple[bool, Any]:
    """Project an add/replace patch onto one reserved occurrence path."""
    if patch.get("op") not in {"add", "replace"}:
        return False, None
    require("val" in patch, f"add/replace patch lacks val in {path.name}: {context}")
    patch_segments = decoded_pointer_segments(path, patch.get("path"), context)
    occurrence_segments = decoded_pointer_segments(
        path, occurrence_path, f"{context}/reserved-occurrence"
    )
    if occurrence_segments[: len(patch_segments)] != patch_segments:
        return False, None
    current = patch["val"]
    for segment in occurrence_segments[len(patch_segments) :]:
        if isinstance(current, dict) and segment in current:
            current = current[segment]
        elif (
            isinstance(current, list)
            and segment.isdigit()
            and int(segment) < len(current)
        ):
            current = current[int(segment)]
        else:
            # Replacing an ancestor without this descendant removes or leaves
            # the reserved path absent; it does not activate the row.
            return False, None
    return True, current


def require_not_strict_occurrence_descendant_patch(
    path: Path,
    patch_path: str,
    occurrence_path: str,
    context: str,
) -> None:
    patch_segments = decoded_pointer_segments(path, patch_path, context)
    occurrence_segments = decoded_pointer_segments(
        path, occurrence_path, f"{context}/managed-occurrence"
    )
    require(
        not (
            len(patch_segments) > len(occurrence_segments)
            and patch_segments[: len(occurrence_segments)]
            == occurrence_segments
        ),
        f"Handler patch targets a strict descendant of a separately managed "
        f"occurrence in {path.name}: {patch_path} below {occurrence_path}",
    )


def validate_managed_occurrence_patch_boundary_self_check() -> None:
    path = Path("managed-occurrence-patch-boundary-self-check.yaml")
    try:
        require_not_strict_occurrence_descendant_patch(
            path, "/child/private", "/child", "synthetic strict descendant"
        )
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "strict-descendant managed-occurrence patch was accepted"
        )
    require_not_strict_occurrence_descendant_patch(
        path, "/child", "/child", "synthetic exact occurrence replacement"
    )
    require_not_strict_occurrence_descendant_patch(
        path, "/", "/child", "synthetic containing ancestor replacement"
    )


def validate_runtime_reserved_occurrence_patches(
    path: Path, data: dict[str, Any]
) -> int:
    """Verify exact target identity before a runtime patch can activate a row."""
    fixture_input = data["input"]
    occurrences_by_document: dict[str, list[dict[str, Any]]] = defaultdict(list)
    inactive_by_document: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for occurrence in fixture_input.get("occurrences", []):
        occurrences_by_document[occurrence["sourceDocumentId"]].append(occurrence)
        if not occurrence.get("active"):
            inactive_by_document[occurrence["sourceDocumentId"]].append(occurrence)

    checked = 0
    runtime = fixture_runtime(path, data)
    for bucket_name in ("handlers", "initializationHandlers"):
        bucket = runtime.get(bucket_name, {})
        require(
            isinstance(bucket, dict),
            f"runtime {bucket_name} is not an object in {path.name}",
        )
        for qualified_handler, result in bucket.items():
            require(
                isinstance(qualified_handler, str)
                and "/" in qualified_handler
                and isinstance(result, dict),
                f"invalid runtime Handler result in {path.name}: {qualified_handler!r}",
            )
            source_document_id, _handler_key = qualified_handler.split("/", 1)
            for patch_ordinal, patch in enumerate(result.get("patches", [])):
                require(
                    isinstance(patch, dict),
                    f"runtime Handler patch is not an object in {path.name}: "
                    f"{qualified_handler}/{patch_ordinal}",
                )
                patch_context = (
                    f"{bucket_name}/{qualified_handler}/patch/{patch_ordinal}"
                )
                validate_runtime_patch_shape(path, patch, patch_context)
                for occurrence in occurrences_by_document.get(
                    source_document_id, []
                ):
                    require_not_strict_occurrence_descendant_patch(
                        path,
                        patch["path"],
                        occurrence["sourcePath"],
                        patch_context,
                    )
                for occurrence in inactive_by_document.get(source_document_id, []):
                    creates_or_replaces, candidate_value = (
                        projected_occurrence_value_from_patch(
                            path,
                            patch,
                            occurrence["sourcePath"],
                            patch_context,
                        )
                    )
                    if not creates_or_replaces:
                        continue
                    established = establish_occurrence_value_blue_id(
                        path,
                        candidate_value,
                        occurrence["targetDocumentId"],
                        fixture_input["documents"],
                        fixture_input["components"],
                        fixture_input.get("occurrences", []),
                        context=(
                            f"{patch_context}->{source_document_id}"
                            f"{occurrence['sourcePath']}"
                        ),
                    )
                    require(
                        established == occurrence["expectedTargetBlueId"],
                        f"runtime Handler patch would activate a reserved occurrence "
                        f"with the wrong exact target in {path.name}: {patch_context}",
                    )
                    checked += 1
    return checked


def validate_channels_and_handlers(path: Path, data: dict[str, Any]) -> tuple[int, int]:
    documents = data["input"]["documents"]
    runtime = fixture_runtime(path, data)
    channels = 0
    handlers = 0
    for delivery in data["input"].get("directDeliveries", []):
        require_closure_root_address(
            delivery["scopePath"],
            delivery["activationGeneration"],
            f"direct delivery in {path.name}",
        )
        contracts = contracts_at_scope(documents[delivery["targetDocumentId"]]["document"], delivery["scopePath"])
        contract = contracts.get(delivery["channelKey"])
        require(type_blue_id(contract) == SCRIPTED_EXTERNAL, f"direct delivery does not target ScriptedExternalChannel in {path.name}: {delivery}")
        channels += 1
    for bucket in ("handlers", "initializationHandlers"):
        for qualified in runtime.get(bucket, {}):
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


def validate_closure_root_profile(path: Path, fixture: dict[str, Any]) -> None:
    """Fail closed on every non-Root address in the 1.0 closure wire profile."""
    fixture_input = fixture["input"]
    expected = fixture["expected"]
    document_ids = set(fixture_input["documents"])

    for ordinal, delivery in enumerate(fixture_input.get("directDeliveries", [])):
        require_closure_root_address(
            delivery["scopePath"],
            delivery["activationGeneration"],
            f"direct delivery {ordinal} in {path.name}",
        )

    # NeedsResources is an attempt outcome, not a completed result.  Its
    # invocation input still belongs to the Root-only closure profile, but it
    # deliberately carries no work, gas, subscriptions, checkpoints, or
    # public-result evidence to inspect below.
    if expected.get("attemptOutcome") == "NeedsResources":
        return

    work_items = list(expected.get("workTrace", []))
    rejected_work = expected.get("rejectedWorkOccurrence")
    if rejected_work is not None:
        work_items.append(rejected_work)
    for work_item in work_items:
        document_id = work_item["targetDocumentId"]
        require(
            document_id in document_ids,
            f"closure work targets an unknown document in {path.name}: "
            f"{document_id}",
        )
        require_closure_root_identity(
            document_id,
            work_item["targetManagedScopeIdentity"],
            f"work {work_item['ordinal']} in {path.name}",
        )

    for delta in expected.get("subscriptionDeltas", []):
        delta_documents: set[str] = set()
        for side in ("beforeSubscription", "afterSubscription"):
            state = delta.get(side)
            if state is None:
                continue
            occurrence = state["channelOccurrence"]
            document_id = occurrence["managedDocumentId"]
            require(
                document_id in document_ids,
                f"subscription {side} names an unknown document in "
                f"{path.name}: {document_id}",
            )
            require_closure_root_address(
                occurrence["scopePath"],
                occurrence["scopeActivationGeneration"],
                f"subscription {delta['subscriptionDeltaOrdinal']} {side} "
                f"in {path.name}",
            )
            delta_documents.add(document_id)
        require(
            len(delta_documents) == 1,
            f"subscription delta crosses managed documents in {path.name}: "
            f"{delta['subscriptionDeltaOrdinal']}",
        )
        require_closure_root_identity(
            next(iter(delta_documents)),
            delta["targetManagedScopeIdentity"],
            f"subscription delta {delta['subscriptionDeltaOrdinal']} in "
            f"{path.name}",
        )

    root_scope_documents = {
        closure_root_scope_identity(document_id): document_id
        for document_id in document_ids
    }
    for write in expected.get("checkpointWrites", []):
        require(
            write["targetManagedScopeIdentity"] in root_scope_documents,
            f"checkpoint write is not Root-scoped in {path.name}: "
            f"{write['checkpointWriteOrdinal']}",
        )

    for entry in expected_gas_trace(path, expected):
        has_scope_path = entry.get("scopePath") is not None
        has_generation = entry.get("activationGeneration") is not None
        require(
            has_scope_path == has_generation,
            f"closure gas scope context is incomplete in {path.name}: "
            f"sequence {entry['sequence']}",
        )
        if has_scope_path:
            require_closure_root_address(
                entry["scopePath"],
                entry["activationGeneration"],
                f"gas sequence {entry['sequence']} in {path.name}",
            )


def handler_results_for_work(
    path: Path, fixture: dict[str, Any], work_item: dict[str, Any]
) -> list[dict[str, Any]]:
    document_id = work_item["targetDocumentId"]
    document = fixture["input"]["documents"][document_id]["document"]
    contracts = document.get("contracts", {}) if isinstance(document, dict) else {}
    runtime = fixture_runtime(path, fixture)
    candidates: list[tuple[int, tuple[int, ...], tuple[int, ...], dict[str, Any]]] = []
    for bucket_name in ("handlers", "initializationHandlers"):
        for qualified, result in runtime.get(bucket_name, {}).items():
            prefix = document_id + "/"
            if not qualified.startswith(prefix):
                continue
            handler_key = qualified[len(prefix) :]
            contract = contracts.get(handler_key, {})
            if (
                isinstance(contract, dict)
                and contract.get("channel") == work_item["channelKey"]
            ):
                handler_order = contract.get("order", 0)
                runtime_type = type_blue_id(contract)
                require(
                    isinstance(handler_order, int)
                    and not isinstance(handler_order, bool)
                    and isinstance(runtime_type, str),
                    f"public Handler lacks exact order/type evidence in "
                    f"{path.name}: {qualified}",
                )
                candidates.append(
                    (
                        handler_order,
                        portable_text_order_key(
                            handler_key,
                            f"{path.name}:public Handler key {qualified}",
                        ),
                        portable_text_order_key(
                            runtime_type,
                            f"{path.name}:public Handler runtime type {qualified}",
                        ),
                        result,
                    )
                )
    candidates.sort(key=lambda item: item[:3])
    return [result for _order, _key, _type, result in candidates]


def public_root_emissions(
    path: Path, fixture: dict[str, Any], work_trace: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    emissions: list[dict[str, Any]] = []
    event_occurrence_ordinal = 0
    for work_item in work_trace:
        for handler_result in handler_results_for_work(path, fixture, work_item):
            for event in handler_result.get("events", []):
                document_id = work_item["targetDocumentId"]
                if fixture["input"]["documents"][document_id]["publicRoot"]:
                    require_closure_root_identity(
                        document_id,
                        work_item["targetManagedScopeIdentity"],
                        f"public emission from work {work_item['ordinal']} in "
                        f"{path.name}",
                    )
                    emissions.append(
                        {
                            "publicRootDocumentId": document_id,
                            "eventOccurrenceOrdinal": event_occurrence_ordinal,
                            "event": event,
                        }
                    )
                event_occurrence_ordinal += 1
    return emissions


def validate_public_handler_order_self_check() -> None:
    document_id = "synthetic-public-root"
    fixture = {
        "runtime": {
            "handlers": {
                document_id + "/later": {"events": [{"id": "later"}]},
                document_id + "/earlier": {"events": [{"id": "earlier"}]},
            },
            "initializationHandlers": {},
        },
        "input": {
            "documents": {
                document_id: {
                    "publicRoot": True,
                    "document": {
                        "contracts": {
                            "source": {},
                            "later": {
                                "type": {"blueId": "handler-type-b"},
                                "channel": "source",
                                "order": 1,
                            },
                            "earlier": {
                                "type": {"blueId": "handler-type-a"},
                                "channel": "source",
                                "order": 0,
                            },
                        }
                    },
                }
            }
        },
    }
    work = {
        "ordinal": 0,
        "targetDocumentId": document_id,
        "channelKey": "source",
        "targetManagedScopeIdentity": closure_root_scope_identity(document_id),
    }
    emissions = public_root_emissions(
        Path("synthetic-multiple-public-handlers.yaml"), fixture, [work]
    )
    require(
        [emission["event"]["id"] for emission in emissions]
        == ["earlier", "later"]
        and [emission["eventOccurrenceOrdinal"] for emission in emissions]
        == [0, 1],
        "multiple public Handler emissions are not complete and canonical",
    )


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
    elif cause["kind"] == "admission":
        require_nfc_order_token(cause["label"], f"{path.name}:admission label")
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
    elif cause["kind"] == "managed-revision":
        require(
            cause["toEpoch"] == cause["fromEpoch"] + 1,
            f"managed revision is not one consecutive epoch step in {path.name}",
        )
        require(
            direct_blue_id(cause["afterDocument"]) == cause["afterBlueId"],
            f"managed-revision after-document BlueId mismatch in {path.name}",
        )
        receipt_value = {
            "childDocumentId": cause["childDocumentId"],
            "fromEpoch": cause["fromEpoch"],
            "toEpoch": cause["toEpoch"],
            "beforeBlueId": cause["beforeBlueId"],
            "afterBlueId": cause["afterBlueId"],
            "originalSourceCauseIdentity": cause["originalSourceCauseIdentity"],
        }
        require(
            cause["sourceRevisionReceiptIdentity"]
            == domain_identity(
                "blue-contracts-source-revision-receipt/1.0", receipt_value
            ),
            f"managed source-revision receipt identity mismatch in {path.name}",
        )
        expected_cause = domain_identity(
            "blue-contracts-managed-revision-cause/1.0",
            {
                "targetOccurrenceIdentity": cause["targetOccurrenceIdentity"],
                **receipt_value,
                "sourceRevisionReceiptIdentity": cause[
                    "sourceRevisionReceiptIdentity"
                ],
            },
        )
    else:
        raise ValidationFailure(
            f"unknown cause kind in {path.name}: {cause['kind']}"
        )
    require(
        cause["causeIdentity"] == expected_cause,
        f"cause identity mismatch in {path.name}",
    )


def validate_managed_revision_binding(
    path: Path, fixture_input: dict[str, Any]
) -> None:
    cause = fixture_input["cause"]
    if cause["kind"] != "managed-revision":
        return
    matches = [
        occurrence
        for occurrence in fixture_input.get("occurrences", [])
        if occurrence["occurrenceIdentity"] == cause["targetOccurrenceIdentity"]
    ]
    require(
        len(matches) == 1,
        f"managed revision does not select one occurrence in {path.name}",
    )
    occurrence = matches[0]
    require(
        occurrence["targetDocumentId"] == cause["childDocumentId"]
        and occurrence["active"] is False
        and occurrence["pendingHistoricalEpoch"] == cause["fromEpoch"]
        and occurrence["expectedTargetBlueId"] == cause["beforeBlueId"],
        f"managed revision does not match its inactive occurrence cursor in "
        f"{path.name}",
    )
    source_document = fixture_input["documents"][occurrence["sourceDocumentId"]][
        "document"
    ]
    try:
        source_value = pointer_get(source_document, occurrence["sourcePath"])
    except KeyError as exc:
        raise ValidationFailure(
            f"managed revision source path is absent in {path.name}: "
            f"{occurrence['sourceDocumentId']}{occurrence['sourcePath']}"
        ) from exc
    established = establish_occurrence_value_blue_id(
        path,
        source_value,
        occurrence["targetDocumentId"],
        fixture_input["documents"],
        fixture_input["components"],
        fixture_input.get("occurrences", []),
        context=(
            f"managed-revision/{occurrence['sourceDocumentId']}"
            f"{occurrence['sourcePath']}"
        ),
    )
    require(
        established == cause["beforeBlueId"],
        f"managed revision source value does not establish beforeBlueId in "
        f"{path.name}: {occurrence['sourceDocumentId']}"
        f"{occurrence['sourcePath']}",
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


def validate_fixture_harness(
    path: Path,
    fixture: dict[str, Any],
    gas_policy: dict[str, Any],
    expected: dict[str, Any],
) -> None:
    # Accessing the runtime also validates the authoritative top-level shape
    # used by the Handler checks below.
    fixture_runtime(path, fixture)
    gas_manifest = load_yaml(GAS)

    limit_source = fixture["sharedLimitSource"]
    if limit_source["kind"] == "RELEASE_DEFAULT":
        require(
            gas_policy["sharedLimit"] == gas_manifest["maxProcessGas"],
            f"release-default shared limit does not match the gas manifest in "
            f"{path.name}",
        )
    elif limit_source["kind"] == "FIXTURE_OVERRIDE":
        require(
            limit_source["sharedLimit"] == gas_policy["sharedLimit"],
            f"fixture shared-limit override does not match the normative gas "
            f"policy in {path.name}",
        )
    else:
        raise ValidationFailure(
            f"unknown shared-limit source in {path.name}: {limit_source['kind']}"
        )

    provider = fixture["provider"]
    nodes = provider["nodes"]
    require(
        list(nodes) == sorted(nodes),
        f"provider node keys are not canonically ordered in {path.name}",
    )
    for blue_id, node in nodes.items():
        require(
            direct_blue_id(node) == blue_id,
            f"provider node does not establish its key in {path.name}: {blue_id}",
        )
    for field in ("expectedRequiredBlueIds", "expectedLoads"):
        values = provider[field]
        require(
            values == sorted(set(values)),
            f"provider {field} is not sorted and unique in {path.name}",
        )
    required = (
        expected["requiredBlueIds"]
        if expected["attemptOutcome"] == "NeedsResources"
        else []
    )
    require(
        provider["expectedRequiredBlueIds"] == required,
        f"provider required-BlueId observation does not match the result in "
        f"{path.name}",
    )
    require(
        set(required) <= set(provider["expectedLoads"]),
        f"provider does not record a load for every required BlueId in {path.name}",
    )

    locality = fixture["locality"]
    require(
        locality["expectedUnrelatedDocumentsOpened"]
        <= locality["unrelatedDocumentCount"],
        f"locality observation exceeds its fixture setup in {path.name}",
    )

    probe = fixture["limit"]["probe"]
    if probe is None:
        return
    limit_name = probe["limit"]
    require(
        limit_name in gas_manifest["portableLimits"],
        f"invocation limit probe names an unknown portable limit in {path.name}: "
        f"{limit_name}",
    )
    configured = gas_manifest["portableLimits"][limit_name]
    environment_limits = {
        item["name"]: item["value"]
        for item in fixture["input"]["environment"]["portableLimitPolicy"][
            "limits"
        ]
    }
    require(
        environment_limits.get(limit_name) == configured,
        f"invocation limit probe is not bound to the normative portable policy "
        f"in {path.name}: {limit_name}",
    )
    diagnostic = LIMIT_DIAGNOSTICS[limit_name]
    if probe["value"] > configured:
        require(
            expected["attemptOutcome"] == "Complete"
            and expected["status"] == "portable-limit-exceeded"
            and expected.get("diagnostic") == diagnostic,
            f"above-bound invocation limit probe does not select its exact "
            f"failure in {path.name}",
        )
    else:
        require(
            expected["attemptOutcome"] != "Complete"
            or expected.get("status") != "portable-limit-exceeded",
            f"at-or-below-bound invocation limit probe is rejected in {path.name}",
        )


def occurrence_binding_matches_documents(
    path: Path,
    documents: dict[str, Any],
    components: list[dict[str, Any]],
    occurrences: list[dict[str, Any]],
    occurrence: dict[str, Any],
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
        established = establish_occurrence_value_blue_id(
            path,
            node,
            target_id,
            documents,
            components,
            occurrences,
            context=f"candidate/{source_id}{occurrence['sourcePath']}",
        )
    except (KeyError, TypeError, ValueError, ValidationFailure):
        return False
    return (
        active_declared_path(source_document, occurrence["sourcePath"])
        and established == occurrence["expectedTargetBlueId"]
        and (
            occurrence.get("pendingHistoricalEpoch") is not None
            or documents[target_id]["blueId"] == occurrence["expectedTargetBlueId"]
        )
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
                    path,
                    fixture_input["documents"],
                    fixture_input["components"],
                    occurrences,
                    occurrence,
                )
                for occurrence in occurrences
            ),
            f"invalid-occurrence candidate contains no invalid binding in {path.name}",
        )
    else:
        raise ValidationFailure(f"unknown admission-candidate kind in {path.name}: {kind}")
    return candidate_identity


def canonical_direct_seed_identities(
    path: Path,
    fixture_input: dict[str, Any],
    ordered_direct: list[tuple[dict[str, Any], str]],
) -> list[str]:
    """Derive Phase-D order independently of raw snapshot serialization."""
    component_rank: dict[str, int] = {}
    for rank, component in enumerate(fixture_input["components"]):
        for document_id in component["orderedMemberDocumentIds"]:
            require(
                document_id not in component_rank,
                f"document repeats across input components in {path.name}: "
                f"{document_id}",
            )
            component_rank[document_id] = rank

    def seed_key(item: tuple[dict[str, Any], str]) -> tuple[Any, ...]:
        delivery, _identity = item
        document_id = delivery["targetDocumentId"]
        require(
            document_id in component_rank,
            f"direct seed targets a document outside the input component "
            f"sequence in {path.name}: {document_id}",
        )
        channel = fixture_input["documents"][document_id]["document"].get(
            "contracts", {}
        ).get(delivery["channelKey"])
        require(
            isinstance(channel, dict),
            f"direct seed lacks an exact Channel in {path.name}: "
            f"{document_id}/{delivery['channelKey']}",
        )
        channel_order = channel.get("order", 0)
        require(
            isinstance(channel_order, int) and not isinstance(channel_order, bool),
            f"direct seed Channel order is not an Integer in {path.name}: "
            f"{document_id}/{delivery['channelKey']}",
        )
        return (
            component_rank[document_id],
            portable_text_order_key(
                document_id, f"{path.name}:direct target DocumentId"
            ),
            portable_text_order_key(
                delivery["scopePath"], f"{path.name}:direct scope path"
            ),
            delivery["activationGeneration"],
            channel_order,
            portable_text_order_key(
                delivery["channelKey"], f"{path.name}:direct Channel key"
            ),
            portable_text_order_key(
                delivery["logicalDeliveryKey"],
                f"{path.name}:direct logical-delivery key",
            ),
            delivery["rawOccurrenceOrder"],
        )

    return [identity for _delivery, identity in sorted(ordered_direct, key=seed_key)]


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
    canonical_direct = canonical_direct_seed_identities(
        path, fixture_input, ordered_direct
    )
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
    actual_direct = [
        work_item["sourceOccurrenceIdentity"]
        for work_item in all_work
        if work_item["kind"] == "EXTERNAL_DELIVERY"
    ]
    require(
        actual_direct == canonical_direct[: len(actual_direct)],
        f"external work is not the canonical Phase-D direct-seed prefix in "
        f"{path.name}",
    )
    if expected["status"] == "success":
        require(
            actual_direct == canonical_direct,
            f"successful result omits a canonical direct seed in {path.name}",
        )
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
            value["runtimeDiscriminator"] = require_nfc_order_token(
                discriminator, "checkpoint runtimeDiscriminator"
            )
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
    fixture: dict[str, Any],
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
            f"work.{work_item['ordinal']}."
            f"{finalization_stage_name(path, fixture, finalization['ordinal'])}."
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
        stage_name = finalization_stage_name(
            path, fixture, finalization["ordinal"]
        )
        reason = f"{prefix}.{stage_name}.finalization-boundary"
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
        require(
            data["input"] == {},
            f"limit microfixture carries normative invocation input in {path.name}",
        )
        fixture_input = data["limit"]
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
    expected = data["expected"]
    validate_closure_root_profile(path, data)
    input_markers = direct_processor_markers(
        path, fixture_input["documents"], "input"
    )
    environment = validate_fixture_environment(path, fixture_input)
    validate_cause(path, fixture_input["cause"], environment)
    validate_managed_revision_binding(path, fixture_input)
    gas_policy = validate_gas_policy(path, fixture_input)
    validate_fixture_harness(path, data, gas_policy, expected)
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
    direct_with_id = [
        (delivery, domain_identity("blue-contracts-direct-delivery/1.0", delivery))
        for delivery in fixture_input.get("directDeliveries", [])
    ]
    require(
        len({identity for _delivery, identity in direct_with_id})
        == len(direct_with_id),
        f"direct delivery snapshot repeats a complete delivery key in {path.name}",
    )
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
    require(
        fixture_input.get("directDeliveries", [])
        == [delivery for delivery, _identity in ordered_direct],
        f"direct deliveries are not in frozen canonical order in {path.name}",
    )
    direct_snapshot_identity = domain_identity(
        "blue-contracts-direct-delivery-snapshot/1.0",
        [identity for _delivery, identity in ordered_direct],
    )
    require(
        fixture_input["directDeliverySnapshotIdentity"] == direct_snapshot_identity,
        f"direct-delivery snapshot identity mismatch in {path.name}",
    )

    validate_components(
        path,
        fixture_input["documents"],
        fixture_input.get("occurrences", []),
        fixture_input["components"],
    )
    occurrence_count = validate_occurrence_set(
        path,
        fixture_input["documents"],
        fixture_input.get("occurrences", []),
        fixture_input["components"],
        allow_invalid=False,
    )
    validate_affected_closure_connectivity(
        path,
        fixture_input["documents"],
        fixture_input.get("occurrences", []),
        "input",
    )
    validate_runtime_reserved_occurrence_patches(path, data)
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

    channel_count, handler_count = validate_channels_and_handlers(path, data)
    if expected["attemptOutcome"] == "NeedsResources":
        validate_component_oracle_routes(
            path, data, fixture_input["components"], None
        )
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
    validate_components(
        path,
        output_documents,
        output_occurrences,
        expected["resultingComponents"],
    )
    validate_component_oracle_routes(
        path,
        data,
        fixture_input["components"],
        expected["resultingComponents"],
    )
    final_occurrence_count = validate_occurrence_set(
        path,
        output_documents,
        output_occurrences,
        expected["resultingComponents"],
        allow_invalid=False,
    )
    validate_occurrence_transition_law(
        path,
        fixture_input.get("occurrences", []),
        output_occurrences,
    )
    validate_generation_transition_law(
        path,
        fixture_input["graphGeneration"],
        fixture_input["components"],
        fixture_input.get("occurrences", []),
        expected["graphGeneration"],
        expected["resultingComponents"],
        output_occurrences,
        expected["status"],
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
    derived_public_events = public_root_emissions(
        path, data, expected["workTrace"]
    )
    if expected["status"] == "success":
        require(
            len(expected["publicEvents"]) == len(derived_public_events),
            f"successful result does not contain every accepted public-Root "
            f"emission in {path.name}",
        )
        for ordinal, (event, derived) in enumerate(
            zip(expected["publicEvents"], derived_public_events, strict=True)
        ):
            require(
                event["publicEventOrdinal"] == ordinal
                and event["publicRootDocumentId"]
                == derived["publicRootDocumentId"]
                and event["eventOccurrenceOrdinal"]
                == derived["eventOccurrenceOrdinal"]
                and event["event"] == derived["event"],
                f"public event does not match its accepted explicit emission in "
                f"{path.name}: {ordinal}",
            )
    else:
        require(
            expected["publicEvents"] == [],
            f"noncommitting result publishes staged public events in {path.name}",
        )

    public_event_basis: list[dict[str, Any]] = []
    event_occurrence_ordinals: set[int] = set()
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
            event["eventOccurrenceOrdinal"] not in event_occurrence_ordinals,
            f"public event repeats an invocation-global occurrence ordinal in "
            f"{path.name}: {event['eventOccurrenceOrdinal']}",
        )
        event_occurrence_ordinals.add(event["eventOccurrenceOrdinal"])
        require(
            event["eventOccurrenceIdentity"]
            == domain_identity(
                "blue-contracts-event-occurrence/1.0",
                {
                    "invocationIdentity": invocation_identity,
                    "eventOccurrenceOrdinal": event["eventOccurrenceOrdinal"],
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
                    "eventOccurrenceOrdinal",
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
        data,
        fixture_input,
        expected,
        gas_policy,
        valid_work_ids,
        expected["resultingComponents"],
    )

    successful = expected["status"] == "success"
    require(
        expected["rollbackToInput"] is (not successful),
        f"completed result rollback flag disagrees with status in {path.name}",
    )

    if not successful:
        require(
            output_documents == fixture_input["documents"],
            f"rollback documents differ from the literal input snapshot in {path.name}",
        )
        require(
            expected["graphGeneration"] == fixture_input["graphGeneration"],
            f"rollback graph generation differs from input in {path.name}",
        )
        require(
            expected["resultingComponents"] == fixture_input["components"],
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

    if successful:
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


def semantic_input_parity_projection(fixture: dict[str, Any]) -> dict[str, Any]:
    fixture_input = fixture["input"]
    projection = json.loads(json.dumps(fixture_input))
    projection["documents"] = document_identity_items(fixture_input["documents"])
    return projection


def semantic_result_parity_projection(
    path: Path, fixture: dict[str, Any]
) -> dict[str, Any]:
    expected = fixture["expected"]
    projection = json.loads(json.dumps(expected))
    projection.pop("gasTraceFile", None)
    projection["gasTrace"] = expected_gas_trace(path, expected)
    if "resultingDocuments" in projection:
        projection["resultingDocuments"] = [
            {key: value for key, value in item.items() if key != "document"}
            for item in projection["resultingDocuments"]
        ]
    if "publicEvents" in projection:
        projection["publicEvents"] = [
            {key: value for key, value in item.items() if key != "event"}
            for item in projection["publicEvents"]
        ]
    return projection


def selected_fields(value: dict[str, Any], fields: Iterable[str]) -> dict[str, Any]:
    return {field: value.get(field) for field in fields}


def validate_representation_parity_fixtures() -> int:
    pairs = [
        (
            "cyclic",
            CLOSURE / "c-clo-01-cyclic-pure-reference-parity.yaml",
            CLOSURE / "c-clo-01-cyclic-materialized-parity.yaml",
            [("simple-a", "/b", "simple-b")],
        ),
        (
            "acyclic",
            CLOSURE / "c-clo-02-acyclic-pure-reference-parity.yaml",
            CLOSURE / "c-clo-02-acyclic-materialized-parity.yaml",
            [("b", "/a", "a")],
        ),
    ]
    required_paths = [
        path
        for _label, reference_path, materialized_path, _expansions in pairs
        for path in (reference_path, materialized_path)
    ]
    require(
        all(path.is_file() for path in required_paths),
        "representation-parity fixture set is incomplete: "
        f"missing={[path.name for path in required_paths if not path.is_file()]}",
    )
    output_identity_fields = (
        "invocationIdentity",
        "inputClosureIdentity",
        "outputClosureIdentity",
        "occurrenceBindingSetIdentity",
        "graphChangesIdentity",
        "checkpointWritesIdentity",
        "subscriptionDeltasIdentity",
        "publicEventsIdentity",
        "gasTraceIdentity",
    )
    work_fields = ("workTrace", "rejectedWorkOccurrence", "tentativeFinalizations")
    gas_fields = ("gasTrace", "gasTraceIdentity", "totalGas", "rejectedCharge")
    graph_fields = (
        "graphGeneration",
        "occurrenceBindings",
        "resultingComponents",
        "graphChanges",
        "graphChangesIdentity",
    )
    for label, reference_path, materialized_path, expansions in pairs:
        reference = load_yaml(reference_path)
        materialized = load_yaml(materialized_path)
        for source_document_id, source_path, target_document_id in expansions:
            pure_value = pointer_get(
                reference["input"]["documents"][source_document_id]["document"],
                source_path,
            )
            materialized_value = pointer_get(
                materialized["input"]["documents"][source_document_id]["document"],
                source_path,
            )
            expected_target = reference["input"]["documents"][target_document_id][
                "blueId"
            ]
            require(
                pure_blue_reference(pure_value) == expected_target,
                f"{label} pure-reference parity half is not a pure reference at "
                f"{source_document_id}{source_path}",
            )
            require(
                pure_blue_reference(materialized_value) is None,
                f"{label} materialized parity half remains a pure reference at "
                f"{source_document_id}{source_path}",
            )
            established = establish_occurrence_value_blue_id(
                materialized_path,
                materialized_value,
                target_document_id,
                materialized["input"]["documents"],
                materialized["input"]["components"],
                materialized["input"].get("occurrences", []),
                context=f"parity/{source_document_id}{source_path}",
            )
            require(
                established == expected_target,
                f"{label} materialized parity half does not establish the exact "
                f"target at {source_document_id}{source_path}",
            )
        require(
            semantic_input_parity_projection(reference)
            == semantic_input_parity_projection(materialized),
            f"{label} representation parity changes semantic input evidence",
        )
        harness_fields = (
            "runtime",
            "sharedLimitSource",
            "provider",
            "locality",
            "limit",
            "oracle",
        )
        require(
            selected_fields(reference, harness_fields)
            == selected_fields(materialized, harness_fields),
            f"{label} representation parity changes harness behavior or oracle "
            f"routing evidence",
        )
        reference_result = semantic_result_parity_projection(
            reference_path, reference
        )
        materialized_result = semantic_result_parity_projection(
            materialized_path, materialized
        )
        require(
            selected_fields(reference_result, output_identity_fields)
            == selected_fields(materialized_result, output_identity_fields),
            f"{label} representation parity changes semantic output identities",
        )
        require(
            selected_fields(reference_result, work_fields)
            == selected_fields(materialized_result, work_fields),
            f"{label} representation parity changes work evidence",
        )
        require(
            selected_fields(reference_result, gas_fields)
            == selected_fields(materialized_result, gas_fields),
            f"{label} representation parity changes gas evidence",
        )
        require(
            selected_fields(reference_result, graph_fields)
            == selected_fields(materialized_result, graph_fields),
            f"{label} representation parity changes graph evidence",
        )
        require(
            reference_result == materialized_result,
            f"{label} representation parity changes result evidence",
        )
    return len(pairs)


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
            by_limit[fixture["limit"]["limit"]].add(
                fixture["expected"]["limitDecision"]
            )
    require(set(by_limit) == set(LIMIT_DIAGNOSTICS), f"closure limit boundary coverage mismatch: {sorted(by_limit)}")
    require(all(decisions == {"ACCEPT", "REJECT"} for decisions in by_limit.values()), "each closure limit requires ACCEPT and REJECT microfixtures")


def validate_managed_revision_fixture_sequence() -> int:
    missing_path = CLOSURE / "c-clo-22-a10-attach-a5-needs-resources.yaml"
    retry_path = CLOSURE / "c-clo-23-00-attach-a5-retry.yaml"
    revision_paths = [
        CLOSURE / f"c-clo-23-{index:02d}-a{epoch}-to-a{epoch + 1}.yaml"
        for index, epoch in enumerate(range(5, 10), start=1)
    ]
    paths = [missing_path, retry_path, *revision_paths]
    require(
        all(path.is_file() for path in paths),
        "managed-revision fixture sequence is incomplete: "
        f"missing={[path.name for path in paths if not path.is_file()]}",
    )
    missing = load_yaml(missing_path)
    retry = load_yaml(retry_path)
    require(
        missing["operation"] == retry["operation"] == "process-closure"
        and missing["input"] == retry["input"],
        "C-CLO-22 missing-resource attempt and C-CLO-23 retry do not have "
        "byte-equivalent normative invocation input",
    )
    require(
        missing["input"]["invocationIdentity"]
        == retry["input"]["invocationIdentity"],
        "C-CLO-22 retry changes invocation identity",
    )
    for field in ("runtime", "sharedLimitSource", "locality", "limit"):
        require(
            missing[field] == retry[field],
            f"C-CLO-22 retry changes {field} harness evidence",
        )
    require(
        missing["expected"]["attemptOutcome"] == "NeedsResources"
        and retry["expected"]["attemptOutcome"] == "Complete",
        "C-CLO-22/C-CLO-23-00 do not form a NeedsResources/retry pair",
    )
    required = missing["expected"]["requiredBlueIds"]
    require(
        missing["provider"]["expectedRequiredBlueIds"] == required
        and retry["provider"]["expectedRequiredBlueIds"] == []
        and missing["provider"]["expectedLoads"]
        == retry["provider"]["expectedLoads"]
        and set(required).isdisjoint(missing["provider"]["nodes"])
        and set(required) <= set(retry["provider"]["nodes"]),
        "C-CLO-22 retry differs by more than exact provider availability and "
        "the resulting resource demand",
    )

    previous_expected = retry["expected"]
    previous_after_blue_id: str | None = None
    final_master: str | None = None
    for offset, revision_path in enumerate(revision_paths):
        fixture = load_yaml(revision_path)
        fixture_input = fixture["input"]
        expected = fixture["expected"]
        cause = fixture_input["cause"]
        from_epoch = 5 + offset
        to_epoch = from_epoch + 1
        require(
            fixture["operation"] == "process-closure"
            and cause["kind"] == "managed-revision"
            and (cause["fromEpoch"], cause["toEpoch"])
            == (from_epoch, to_epoch),
            f"managed-revision fixture has the wrong epoch step: "
            f"{revision_path.name}",
        )
        if previous_after_blue_id is not None:
            require(
                cause["beforeBlueId"] == previous_after_blue_id,
                f"managed-revision BlueId chain is discontinuous in "
                f"{revision_path.name}",
            )
        previous_after_blue_id = cause["afterBlueId"]
        require(
            result_documents(previous_expected) == fixture_input["documents"]
            and previous_expected["occurrenceBindings"]
            == fixture_input["occurrences"]
            and previous_expected["resultingComponents"]
            == fixture_input["components"]
            and previous_expected["graphGeneration"]
            == fixture_input["graphGeneration"],
            f"managed-revision state is not commit-to-next-input continuous in "
            f"{revision_path.name}",
        )
        require(
            expected["attemptOutcome"] == "Complete"
            and expected["status"] == "success"
            and len(expected["workTrace"]) == 1,
            f"managed-revision step is not one successful invocation in "
            f"{revision_path.name}",
        )
        work_item = expected["workTrace"][0]
        require(
            work_item["ordinal"] == 0
            and work_item["kind"] == "CONTAINING_REFERENCE_UPDATE"
            and work_item["sourceOccurrenceIdentity"] == cause["causeIdentity"],
            f"managed-revision step does not own one containing-reference work "
            f"occurrence in {revision_path.name}",
        )
        target_rows = [
            occurrence
            for occurrence in expected["occurrenceBindings"]
            if occurrence["occurrenceIdentity"]
            == cause["targetOccurrenceIdentity"]
        ]
        require(
            len(target_rows) == 1,
            f"managed-revision result does not preserve one target binding in "
            f"{revision_path.name}",
        )
        row = target_rows[0]
        if to_epoch < 10:
            require(
                row["expectedTargetBlueId"] == cause["afterBlueId"]
                and row["active"] is False
                and row["pendingHistoricalEpoch"] == to_epoch
                and expected["graphGeneration"] == 1
                and all(
                    component["kind"] == "ACYCLIC"
                    for component in expected["resultingComponents"]
                )
                and expected["tentativeFinalizations"] == [],
                f"intermediate managed revision publishes premature live/cyclic "
                f"state in {revision_path.name}",
            )
        else:
            resulting_child = next(
                document
                for document in expected["resultingDocuments"]
                if document["documentId"] == cause["childDocumentId"]
            )
            cyclic_components = [
                component
                for component in expected["resultingComponents"]
                if component["kind"] == "CYCLIC"
            ]
            require(
                row["expectedTargetBlueId"] == resulting_child["afterBlueId"]
                and row["expectedTargetBlueId"] != cause["afterBlueId"]
                and row["active"] is True
                and row["pendingHistoricalEpoch"] is None
                and expected["graphGeneration"] == 2
                and len(cyclic_components) == 1
                and len(expected["tentativeFinalizations"]) == 1
                and expected["tentativeFinalizations"][0]["ordinal"] == 0
                and finalization_stage_name(
                    revision_path, fixture, 0
                ).endswith("managed-revision-9-10"),
                f"final managed revision does not atomically activate and form "
                f"the exact cycle in {revision_path.name}",
            )
            final_master = cyclic_components[0]["masterBlueId"]
        previous_expected = expected
    require(final_master is not None, "managed-revision sequence has no final cycle")
    return len(revision_paths)


def validate_static_package_laws() -> None:
    validate_occurrence_transition_law_self_check()
    validate_component_order_self_check()
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
    require(
        "historicalTransitions:" not in fixture_registry_text
        and "HISTORICAL_TRANSITION" not in fixture_registry_text,
        "obsolete aggregate historical-transition evidence remains in fixtures/registry",
    )
    require(
        "oracleStage:" not in fixture_registry_text,
        "fixture-only oracle stage label remains in a normative fixture record",
    )
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
    require(release["fixturePackage"]["vectorCount"] == fixtures["vectorCount"], "fixture vector-count binding mismatch")
    require(release["fixturePackage"]["fixtureCount"] == fixtures["totalExecutableFixtureCount"], "fixture count binding mismatch")
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
        run_command(["javac", "--release", "8", "-Xlint:all,-options", "-Werror", "-d", str(output), *map(str, sources)])
        contracts_output = run_command(["java", "-cp", str(output), "blue.contracts.closure.ReferenceCycleMain"])
        coordination_output = run_command(["java", "-cp", str(output), "blue.coordination.closure.ReferenceCoordinationIntegrationMain"])
    require("BLUE_CONTRACTS_CLOSURE_TEMPLATE_SHAPE_SMOKE_OK" in contracts_output, "Contracts Java reference main did not pass")
    require("BLUE_COORDINATION_CLOSURE_TEMPLATE_SHAPE_SMOKE_OK" in coordination_output, "Coordination Java reference main did not pass")
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
    progress("gas manifest and microfixtures")
    validate_gas_manifest_and_microfixtures(ordinary)
    progress("vector coverage")
    vectors = validate_vector_coverage(ordinary, closure)
    progress("fixture manifest inventory")
    validate_fixture_manifest_inventory(manifests["fixtures"], ordinary, closure, vectors)
    progress("BlueIds and DocumentIds")
    blue_ids = validate_blue_ids(closure)
    document_ids = validate_document_ids(closure)
    progress("JCS vectors")
    run_command([sys.executable, str(ROOT / "tools/test_jcs.py")])
    counts = {"occurrences": 0, "channels": 0, "handlers": 0}
    for index, path in enumerate(closure):
        if args.verbose and index % 10 == 0:
            progress(f"closure semantics {index + 1}/{len(closure)}")
        current = validate_closure_fixture(path)
        for key, value in current.items():
            counts[key] += value
    progress("managed-revision sequence")
    validate_managed_revision_fixture_sequence()
    progress("representation parity")
    validate_representation_parity_fixtures()
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
