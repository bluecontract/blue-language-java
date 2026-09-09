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
from gas_reference import gas_trace_identity  # noqa: E402
from implementation_baseline import (  # noqa: E402
    CYCLIC_FINALIZER,
    CYCLIC_PROOF_VERIFIER,
    IMPLEMENTATION_BASELINE_SOURCE_PATHS,
    source_paths_for_role,
)
from runtime_surface_projection import (  # noqa: E402
    project_runtime_surface,
)
from package_hygiene import release_inventory_files  # noqa: E402
from release_provenance import (  # noqa: E402
    SourceArchiveProvenanceError,
    source_archive_provenance,
)
PROCESS_EMBEDDED = "9ftzzP6ySLmbJ43bjwTbrm6Ff79FKqsVy5xdA1zWxoQ3"
SAFE_INTEGER_MAX = 2**53 - 1
SCRIPTED_EXTERNAL = "2hesjWGVbvcJSu6woCUTssU9S7A69ep93UzdgvwosDLt"
SCRIPTED_HANDLER = "9Wa77paaHDctnRmgwcXMGUkYeE5EzBcLf2ZRTbBhDA8"
TRIGGERED_EVENT_CHANNEL = "DRxc8GkSGPbdENdB8ZK976i1Jzc6M1QdG8UsVMHcqQcf"
EMBEDDED_NODE_CHANNEL = "7ZgUJxCyokHf84uibaQz138mFRLarykWLewVAn8bibTN"
LIFECYCLE_EVENT_CHANNEL = "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo"
DOCUMENT_UPDATE_CHANNEL = "4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An"
EMBEDDED_COLLECTION_EVENT_CHANNEL = "FodJjjdNVR5gYf8Eiv6X1UmSAg8GZA6tbpKxfkAeDwpg"
SUBSCRIPTION_CHANNEL_TYPES = {
    SCRIPTED_EXTERNAL,
    DOCUMENT_UPDATE_CHANNEL,
    TRIGGERED_EVENT_CHANNEL,
    EMBEDDED_NODE_CHANNEL,
    EMBEDDED_COLLECTION_EVENT_CHANNEL,
    LIFECYCLE_EVENT_CHANNEL,
}
LOWERCASE_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
FULL_LIFECYCLE_FIXTURE_NAMES = {
    "fl-adm-01-root-patch-event.yaml",
    "fl-adm-02-duplicate-equal-events.yaml",
    "fl-adm-03-non-public-containing-route.yaml",
    "fl-adm-04-document-update-continuation.yaml",
    "fl-adm-05-graceful-termination.yaml",
    "fl-adm-06-order-representation-reference.yaml",
    "fl-adm-06-order-representation-reversed.yaml",
    "fl-adm-06-order-representation-inline.yaml",
    "fl-adm-07-finite-cyclic-route.yaml",
    "fl-adm-08-infinite-cycle-gas-retry-first.yaml",
    "fl-adm-08-infinite-cycle-gas-retry-retry.yaml",
    "fl-adm-09-late-member-rollback.yaml",
    "fl-adm-10-unknown-occurrence.yaml",
    "c-evo-18-missing-exact-node.yaml",
    "c-evo-19-missing-occurrence-evidence.yaml",
    "c-evo-20-canonical-demand-order.yaml",
    "c-evo-21-retry-determinism-missing-first.yaml",
    "c-evo-21-retry-determinism-missing-repeat.yaml",
    "c-evo-21-retry-determinism-resolved-first.yaml",
    "c-evo-21-retry-determinism-resolved-repeat.yaml",
    "c-evo-22-low-gas-expanded-evidence-demand.yaml",
    "c-evo-22-low-gas-expanded-evidence-expanded-low-gas.yaml",
    "c-evo-22-low-gas-expanded-evidence-expanded-low-gas-repeat.yaml",
    "c-evo-23-automatic-explicit-retry-parity-automatic-demand.yaml",
    "c-evo-23-automatic-explicit-retry-parity-automatic-resolved.yaml",
    "c-evo-23-automatic-explicit-retry-parity-explicit-resolved.yaml",
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


def is_lowercase_sha256(value: Any) -> bool:
    """Return whether ``value`` is one canonical, unprefixed SHA-256 digest."""
    return (
        isinstance(value, str)
        and LOWERCASE_SHA256_RE.fullmatch(value) is not None
    )


def validate_input_implementation_baseline(
    value: Any,
) -> dict[str, dict[str, str]]:
    """Validate the release's exact closed source inventory and digests."""
    require(
        isinstance(value, list)
        and all(
            isinstance(entry, dict)
            and set(entry) == {"path", "sha256"}
            and isinstance(entry["path"], str)
            and is_lowercase_sha256(entry["sha256"])
            for entry in value
        ),
        "input implementation baseline must be a closed path/hash array",
    )
    baseline_paths = tuple(entry["path"] for entry in value)
    require(
        baseline_paths == IMPLEMENTATION_BASELINE_SOURCE_PATHS,
        "input implementation baseline must exactly match the authoritative "
        "sorted ownership inventory",
    )
    return {entry["path"]: entry for entry in value}


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


def closure_resource_demand_identity(demand: dict[str, Any]) -> str:
    """Independently derive the closed nine-field demand identity."""
    exact = demand["kind"] == "EXACT_NODE"
    value = {
        "kind": demand["kind"],
        "logicalCauseIdentity": None if exact else demand["logicalCauseIdentity"],
        "inputClosureIdentity": None if exact else demand["inputClosureIdentity"],
        "inputGraphGeneration": None if exact else demand["inputGraphGeneration"],
        "sourceDocumentId": demand["sourceDocumentId"],
        "sourcePath": demand["sourcePath"],
        "processEmbeddedDeclarationIdentity": (
            None if exact else demand["processEmbeddedDeclarationIdentity"]
        ),
        "suppliedValueBlueId": demand["suppliedValueBlueId"],
        "demandOrdinal": None if exact else demand["demandOrdinal"],
    }
    return domain_identity(
        "blue-contracts-closure-resource-demand/1.0", value
    )


def validate_resource_demands(path: Path, expected: dict[str, Any]) -> None:
    """Validate identities, closed shapes, ordering and legacy projection."""
    demands = expected["resourceDemands"]
    require(bool(demands), f"NeedsResources has no typed demands in {path.name}")
    identities: set[str] = set()
    exact_blue_ids: set[str] = set()
    order_keys: list[tuple[Any, ...]] = []
    for index, demand in enumerate(demands):
        context = f"{path.name}.resourceDemands[{index}]"
        common = {
            "kind", "demandIdentity", "sourceDocumentId", "sourcePath",
            "suppliedValueBlueId",
        }
        if demand["kind"] == "EXACT_NODE":
            require(
                set(demand) == common | {"blueId", "logicalPath"},
                f"exact demand has an open or incomplete shape: {context}",
            )
            require(
                demand["blueId"] == demand["suppliedValueBlueId"]
                and demand["logicalPath"] == demand["sourcePath"],
                f"exact demand aliases disagree: {context}",
            )
            exact_blue_ids.add(demand["blueId"])
            # The canonical identity field is JSON null for an exact-node
            # demand.  Preserve that ordering distinction explicitly: null
            # sorts before every managed-occurrence integer, including zero.
            ordinal_key = (0, 0)
        else:
            require(
                demand["kind"] == "MANAGED_OCCURRENCE_EVIDENCE",
                f"unknown resource demand kind: {context}",
            )
            require(
                set(demand) == common | {
                    "logicalCauseIdentity", "inputClosureIdentity",
                    "inputGraphGeneration",
                    "processEmbeddedDeclarationIdentity", "demandOrdinal",
                },
                f"occurrence demand has an open or incomplete shape: {context}",
            )
            ordinal_key = (1, demand["demandOrdinal"])
        require(
            demand["demandIdentity"]
            == closure_resource_demand_identity(demand),
            f"resource demand identity mismatch: {context}",
        )
        require(
            demand["demandIdentity"] not in identities,
            f"duplicate resource demand identity: {context}",
        )
        identities.add(demand["demandIdentity"])
        order_keys.append((
            portable_text_order_key(demand["sourceDocumentId"], context),
            portable_text_order_key(demand["sourcePath"], context),
            portable_text_order_key(demand["suppliedValueBlueId"], context),
            ordinal_key,
            portable_text_order_key(demand["demandIdentity"], context),
        ))
    require(
        order_keys == sorted(order_keys),
        f"resource demands are not in canonical source order in {path.name}",
    )
    require(
        expected["requiredBlueIds"] == sorted(
            exact_blue_ids,
            key=lambda value: portable_text_order_key(
                value, f"{path.name}.requiredBlueIds"
            ),
        ),
        f"legacy requiredBlueIds is not the exact-demand projection in {path.name}",
    )


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


def validate_initialization_marker_write_set(
    path: Path,
    before_markers: dict[str, dict[str, Any]],
    after_markers: dict[str, dict[str, Any]],
    expected: dict[str, Any],
    fixture: dict[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    trace = expected_gas_trace(path, expected)
    installed = sorted(
        document_id
        for document_id in before_markers
        if before_markers[document_id]["initialized"] is None
        and after_markers[document_id]["initialized"] is not None
    )
    rows = [
        entry
        for entry in trace
        if entry.get("counter") == "processorMarkerWritten"
        and entry.get("reason", "").startswith("initialization-batch.marker.")
    ]
    require(
        all(
            entry.get("namespace") == "processor"
            and isinstance(entry.get("documentId"), str)
            and entry.get("workOccurrenceId") is None
            and entry.get("quantity") == 1
            and entry.get("reason")
            == f"initialization-batch.marker.{entry['documentId']}"
            for entry in rows
        ),
        f"malformed initialization marker write row in {path.name}",
    )
    row_document_ids = [entry["documentId"] for entry in rows]
    require(
        len(row_document_ids) == len(set(row_document_ids)),
        f"initialization marker write set repeats a document in {path.name}",
    )
    require(
        all(
            document_id in before_markers
            and before_markers[document_id]["initialized"] is None
            for document_id in row_document_ids
        ),
        f"initialization marker write set names a non-managed or already "
        f"initialized document in {path.name}",
    )
    marker_positions = [
        index
        for index, entry in enumerate(trace)
        if entry.get("counter") == "processorMarkerWritten"
        and entry.get("reason", "").startswith("initialization-batch.marker.")
    ]
    owned_rebuild_positions: set[int] = set()
    identity_counters = {
        "nodeIdentityEstablished",
        "objectMemberRebuilt",
        "directIdentityHashBlock",
    }
    failing_work_ids: dict[str, set[str]] = defaultdict(set)
    initialization_work_ids: dict[str, set[str]] = defaultdict(set)
    if fixture is not None:
        work_by_identity: dict[str, dict[str, Any]] = {}
        for work in expected.get("workTrace", []):
            document_id = work.get("targetDocumentId")
            work_identity = work.get("workIdentity")
            if isinstance(work_identity, str):
                work_by_identity[work_identity] = work
            if (
                work.get("kind") == "INITIALIZATION"
                and isinstance(document_id, str)
                and isinstance(work_identity, str)
            ):
                initialization_work_ids[document_id].add(work_identity)
            if any(
                isinstance(result, dict) and result.get("fail") is not None
                for result in handler_results_for_work(path, fixture, work)
            ) and isinstance(document_id, str) and isinstance(
                work_identity, str
            ):
                failing_work_ids[document_id].add(work_identity)
        rejected_work = expected.get("rejectedWorkOccurrence")
        if isinstance(rejected_work, dict):
            rejected_work_identity = rejected_work.get("workIdentity")
            traced_work = work_by_identity.get(rejected_work_identity)
            require(
                traced_work is not None,
                f"rejected work occurrence is absent from work trace in "
                f"{path.name}",
            )
            rejected_document_id = traced_work.get("targetDocumentId")
            require(
                isinstance(rejected_document_id, str)
                and isinstance(rejected_work_identity, str),
                f"rejected work occurrence lacks a traced document in "
                f"{path.name}",
            )
            failing_work_ids[rejected_document_id].add(
                rejected_work_identity
            )
    for marker_position in marker_positions:
        document_id = trace[marker_position]["documentId"]
        if fixture is not None:
            work_ids = initialization_work_ids.get(document_id, set())
            initialization_positions = [
                index
                for index, entry in enumerate(trace)
                if entry.get("workOccurrenceId") in work_ids
            ]
            earlier_failing_positions = [
                index
                for index, entry in enumerate(trace[:marker_position])
                if entry.get("workOccurrenceId")
                in failing_work_ids.get(document_id, set())
            ]
            require(
                work_ids
                and initialization_positions
                and max(initialization_positions) < marker_position
                and not earlier_failing_positions,
                f"initialization marker write is not causally after successful "
                f"initialization work in {path.name}: {document_id}",
            )
        rebuild_positions: list[int] = []
        cursor = marker_position + 1
        while cursor < len(trace):
            entry = trace[cursor]
            if not (
                entry.get("namespace") == "semantic"
                and entry.get("counter") in identity_counters
                and entry.get("documentId") == document_id
                and entry.get("logicalPath") == "/contracts/initialized"
                and entry.get("workOccurrenceId") is None
                and entry.get("reason") == "identity-rebuild"
            ):
                break
            rebuild_positions.append(cursor)
            cursor += 1
        require(
            [trace[index]["counter"] for index in rebuild_positions]
            == [
                "nodeIdentityEstablished",
                "objectMemberRebuilt",
                "directIdentityHashBlock",
            ]
            * 3,
            f"initialization marker write lacks its exact contiguous identity "
            f"rebuild spine "
            f"in {path.name}: {document_id}",
        )
        owned_rebuild_positions.update(rebuild_positions)
    all_rebuild_positions = {
        index
        for index, entry in enumerate(trace)
        if entry.get("namespace") == "semantic"
        and entry.get("counter") in identity_counters
        and entry.get("logicalPath") == "/contracts/initialized"
        and entry.get("workOccurrenceId") is None
        and entry.get("reason") == "identity-rebuild"
    }
    require(
        owned_rebuild_positions == all_rebuild_positions,
        f"initialization-marker identity rebuild evidence is not in exact "
        f"bijection with marker writes in {path.name}",
    )
    if expected.get("status") == "success":
        require(
            sorted(row_document_ids) == installed,
            f"initialization marker write set does not equal the exact newly "
            f"installed marker set in {path.name}",
        )
    else:
        require(
            not installed,
            f"non-successful result committed an initialization marker in "
            f"{path.name}",
        )
    return {entry["documentId"]: entry for entry in rows}


def validate_rollback_marker_prefix_self_check() -> None:
    path = CLOSURE / "fl-adm-09-late-member-rollback.yaml"
    fixture = load_yaml(path)
    expected = fixture["expected"]
    before = direct_processor_markers(
        path, fixture["input"]["documents"], "rollback-self-check-input"
    )
    after = direct_processor_markers(
        path, result_documents(expected), "rollback-self-check-result"
    )
    writes = validate_initialization_marker_write_set(
        path, before, after, expected, fixture
    )
    require(
        sorted(writes) == ["fl-adm-09-a"],
        "FL-ADM-09 rollback marker prefix was not admitted exactly",
    )

    invalid: list[tuple[str, dict[str, Any]]] = []
    missing = json.loads(json.dumps(expected))
    missing["gasTrace"] = [
        row
        for row in missing["gasTrace"]
        if row.get("reason") != "initialization-batch.marker.fl-adm-09-a"
    ]
    invalid.append(("missing completed-prefix marker", missing))

    retargeted = json.loads(json.dumps(expected))
    retargeted_row = next(
        row
        for row in retargeted["gasTrace"]
        if row.get("reason") == "initialization-batch.marker.fl-adm-09-a"
    )
    retargeted_row["documentId"] = "not-a-managed-document"
    retargeted_row["reason"] = (
        "initialization-batch.marker.not-a-managed-document"
    )
    invalid.append(("retargeted marker", retargeted))

    extra = json.loads(json.dumps(expected))
    extra_row = json.loads(json.dumps(
        next(
            row
            for row in extra["gasTrace"]
            if row.get("reason") == "initialization-batch.marker.fl-adm-09-a"
        )
    ))
    extra_row["documentId"] = "fl-adm-09-b"
    extra_row["reason"] = "initialization-batch.marker.fl-adm-09-b"
    extra["gasTrace"].append(extra_row)
    source_marker_position = next(
        index
        for index, row in enumerate(expected["gasTrace"])
        if row.get("reason") == "initialization-batch.marker.fl-adm-09-a"
    )
    for row in expected["gasTrace"][
        source_marker_position + 1 : source_marker_position + 10
    ]:
        fabricated_rebuild = json.loads(json.dumps(row))
        fabricated_rebuild["documentId"] = "fl-adm-09-b"
        extra["gasTrace"].append(fabricated_rebuild)
    invalid.append(("marker from failing component", extra))

    for label, evidence in invalid:
        try:
            validate_initialization_marker_write_set(
                path, before, after, evidence, fixture
            )
        except ValidationFailure:
            continue
        raise ValidationFailure(
            f"rollback marker prefix self-check accepted {label}"
        )


def validate_rejected_work_marker_causality_self_check() -> None:
    path = CLOSURE / (
        "c-evo-22-low-gas-expanded-evidence-expanded-low-gas.yaml"
    )
    fixture = load_yaml(path)
    expected = fixture["expected"]
    before = direct_processor_markers(
        path, fixture["input"]["documents"], "rejected-work-self-check-input"
    )
    after = direct_processor_markers(
        path,
        result_documents(expected),
        "rejected-work-self-check-result",
    )
    require(
        not validate_initialization_marker_write_set(
            path, before, after, expected, fixture
        ),
        "C-EVO-22 low-gas baseline unexpectedly writes a marker",
    )

    forged = json.loads(json.dumps(expected))
    spine_fixture = load_yaml(
        CLOSURE / "fl-adm-09-late-member-rollback.yaml"
    )
    spine_trace = spine_fixture["expected"]["gasTrace"]
    spine_start = next(
        index
        for index, row in enumerate(spine_trace)
        if row.get("reason") == "initialization-batch.marker.fl-adm-09-a"
    )
    for row in spine_trace[spine_start : spine_start + 10]:
        fabricated = json.loads(json.dumps(row))
        fabricated["documentId"] = "c-evo-22-a"
        if fabricated.get("counter") == "processorMarkerWritten":
            fabricated["reason"] = (
                "initialization-batch.marker.c-evo-22-a"
            )
        forged["gasTrace"].append(fabricated)

    try:
        validate_initialization_marker_write_set(
            path, before, after, forged, fixture
        )
    except ValidationFailure:
        return
    raise ValidationFailure(
        "rejected-work marker causality self-check accepted a marker after "
        "gas-rejected work"
    )


def validate_direct_marker_transition(
    path: Path,
    before_documents: dict[str, Any],
    after_documents: dict[str, Any],
    before_markers: dict[str, dict[str, Any]],
    after_markers: dict[str, dict[str, Any]],
    expected: dict[str, Any],
    before_occurrences: list[dict[str, Any]] | None = None,
    after_occurrences: list[dict[str, Any]] | None = None,
    fixture: dict[str, Any] | None = None,
    initialization_marker_writes: dict[str, dict[str, Any]] | None = None,
) -> None:
    reconstructed_markers = reconstruct_event_only_acyclic_markers(path, fixture)
    for document_id in sorted(before_documents):
        before = before_markers[document_id]
        after = after_markers[document_id]
        if before["initialized"] is not None:
            require(
                after["initialized"] == before["initialized"],
                f"protected initialized marker changed in {path.name}: {document_id}",
            )
        elif after["initialized"] is not None:
            invocation_input_blue_id = before_documents[document_id]["blueId"]
            marker_blue_id = after["initialized"]["document"]["blueId"]
            terminal_batch_entry_blue_id = (
                terminal_component_batch_entry_blue_id(
                    path,
                    document_id,
                    after_documents[document_id],
                    before,
                    after,
                    expected,
                )
            )
            rebound_batch_entry_blue_id = (
                stable_rebound_component_batch_entry_blue_id(
                    path,
                    document_id,
                    before_documents,
                    after_documents,
                    before,
                    after,
                    expected,
                    before_occurrences,
                    after_occurrences,
                )
            )
            intermediate_batch_entry_blue_id = (
                intermediate_cyclic_component_batch_entry_blue_id(
                    path,
                    document_id,
                    before_markers,
                    after_markers,
                    expected,
                    fixture,
                    initialization_marker_writes,
                )
            )
            require(
                marker_blue_id == invocation_input_blue_id
                or marker_blue_id == terminal_batch_entry_blue_id
                or marker_blue_id == rebound_batch_entry_blue_id
                or marker_blue_id == intermediate_batch_entry_blue_id
                or marker_blue_id == reconstructed_markers.get(document_id),
                f"initialized marker does not reference the exact invocation-input "
                f"or proven component-batch-entry document in "
                f"{path.name}: {document_id}",
            )
        if before["terminated"] is not None:
            require(
                after["terminated"] == before["terminated"],
                f"protected terminated marker changed in {path.name}: {document_id}",
            )
        elif after["terminated"] is not None:
            validate_new_termination_marker_evidence(
                path, document_id, expected
            )


def validate_new_termination_marker_evidence(
    path: Path,
    document_id: str,
    expected: dict[str, Any],
) -> None:
    """Bind a newly materialized termination marker to retained execution.

    Shape validation alone would allow an invented terminal state.  The exact
    request owner, invocation-global work ordinal, marker write, component
    finalization, and their strict order are all retained by the closure
    result and gas trace, so require that complete causal spine here.
    """
    trace = expected_gas_trace(path, expected)
    work_by_identity = {
        work["workIdentity"]: work for work in expected["workTrace"]
    }
    requests = [
        entry
        for entry in trace
        if entry["counter"] == "terminationRequested"
        and entry.get("documentId") == document_id
        and entry.get("workOccurrenceId") in work_by_identity
        and re.fullmatch(
            r"work\.\d+\.termination-request",
            entry.get("reason", ""),
        )
        is not None
    ]
    require(
        len(requests) == 1,
        f"new terminated marker lacks one exact termination request in "
        f"{path.name}: {document_id}",
    )
    request = requests[0]
    request_match = re.fullmatch(
        r"work\.(\d+)\.termination-request", request["reason"]
    )
    request_work = work_by_identity[request["workOccurrenceId"]]
    require(
        request_match is not None
        and request_work["ordinal"] == int(request_match.group(1))
        and request_work["targetDocumentId"] == document_id,
        f"new terminated marker request has the wrong exact work owner in "
        f"{path.name}: {document_id}",
    )
    terminal_finalizations = [
        finalization
        for finalization in expected["tentativeFinalizations"]
        if finalization["boundary"]["kind"] == "TERMINATION_MARKER"
        and document_id in finalization["memberBlueIds"]
    ]
    require(
        len(terminal_finalizations) == 1,
        f"new terminated marker lacks one exact component finalization in "
        f"{path.name}: {document_id}",
    )
    after_work_ordinal = terminal_finalizations[0]["boundary"][
        "afterWorkOrdinal"
    ]
    prefix = f"termination-marker.after-work.{after_work_ordinal}"
    marker_writes = [
        entry
        for entry in trace
        if entry["counter"] == "processorMarkerWritten"
        and entry.get("documentId") == document_id
        and entry.get("reason") == f"{prefix}.write"
        and entry.get("workOccurrenceId") is None
    ]
    finalization_charges = [
        entry
        for entry in trace
        if entry["counter"] == "tentativeComponentFinalization"
        and entry.get("reason") == f"{prefix}.finalization-boundary"
        and entry.get("workOccurrenceId") is None
    ]
    require(
        len(marker_writes) == 1
        and len(finalization_charges) == 1
        and request["sequence"] < marker_writes[0]["sequence"]
        < finalization_charges[0]["sequence"],
        f"new terminated marker does not follow its exact request/write/"
        f"finalization spine in {path.name}: {document_id}",
    )


def terminal_component_batch_entry_blue_id(
    path: Path,
    document_id: str,
    after_record: dict[str, Any],
    before_markers: dict[str, Any],
    after_markers: dict[str, Any],
    expected: dict[str, Any],
) -> str | None:
    """Prove the one recoverable terminal initialization-batch entry state.

    The final document with its direct initialized marker removed is an
    independent candidate for the state frozen at batch entry only when the
    retained work and gas evidence proves that this document's initialization
    was the terminal work, its marker write followed that work, and nothing
    except exact identity reconstruction followed the marker write.  Any
    missing, ambiguous, or post-batch evidence fails closed to ``None``.
    """
    work_trace = expected.get("workTrace")
    after_blue_id = after_record.get("blueId")
    after_document = after_record.get("document")
    if (
        expected.get("status") != "success"
        or expected.get("rejectedWorkOccurrence") is not None
        or expected.get("checkpointWrites") != []
        or expected.get("tentativeFinalizations") != []
        or before_markers.get("terminated") != after_markers.get("terminated")
        or before_markers.get("checkpoint") != after_markers.get("checkpoint")
        or not isinstance(after_blue_id, str)
        or "#" in after_blue_id
        or not valid_exact_blue_id(after_blue_id)
        or not isinstance(after_document, dict)
        or not isinstance(work_trace, list)
        or not work_trace
    ):
        return None
    try:
        if direct_blue_id(after_document) != after_blue_id:
            return None
    except (TypeError, ValueError):
        return None
    terminal_work = work_trace[-1]
    terminal_ordinal = len(work_trace) - 1
    if (
        not isinstance(terminal_work, dict)
        or terminal_work.get("ordinal") != terminal_ordinal
        or terminal_work.get("kind") != "INITIALIZATION"
        or terminal_work.get("targetDocumentId") != document_id
        or not isinstance(terminal_work.get("workIdentity"), str)
    ):
        return None

    try:
        trace = expected_gas_trace(path, expected)
    except (KeyError, ValidationFailure):
        return None
    if (
        not isinstance(trace, list)
        or [entry.get("sequence") for entry in trace]
        != list(range(len(trace)))
    ):
        return None
    work_identity = terminal_work["workIdentity"]

    def exact_work_charge(counter: str, reason: str) -> list[dict[str, Any]]:
        return [
            entry
            for entry in trace
            if entry.get("counter") == counter
            and entry.get("documentId") == document_id
            and entry.get("workOccurrenceId") == work_identity
            and entry.get("reason") == reason
        ]

    enqueues = exact_work_charge(
        "closureWorkOccurrenceEnqueued",
        f"work.{terminal_ordinal}.initialization-enqueue",
    )
    dequeues = exact_work_charge(
        "closureWorkOccurrenceDequeued",
        f"work.{terminal_ordinal}.dequeue",
    )
    initialization_charges = exact_work_charge(
        "scopeInitialization", "scope-initialization"
    )
    marker_writes = [
        entry
        for entry in trace
        if entry.get("counter") == "processorMarkerWritten"
        and entry.get("documentId") == document_id
        and entry.get("reason") == f"initialization-batch.marker.{document_id}"
        and entry.get("quantity") == 1
        and entry.get("workOccurrenceId") is None
    ]
    if not all(
        len(entries) == 1
        for entries in (enqueues, dequeues, initialization_charges, marker_writes)
    ):
        return None
    marker_sequence = marker_writes[0]["sequence"]
    if not (
        enqueues[0]["sequence"]
        < dequeues[0]["sequence"]
        < initialization_charges[0]["sequence"]
        < marker_sequence
    ):
        return None
    if any(
        entry.get("workOccurrenceId") is not None
        and entry["sequence"] > marker_sequence
        for entry in trace
    ):
        return None

    identity_counters = {
        "nodeIdentityEstablished",
        "objectMemberRebuilt",
        "directIdentityHashBlock",
    }
    for entry in trace[marker_sequence + 1 :]:
        reason = entry.get("reason", "")
        if not (
            entry.get("namespace") == "semantic"
            and entry.get("counter") in identity_counters
            and entry.get("documentId") == document_id
            and entry.get("workOccurrenceId") is None
            and (
                reason == "identity-rebuild"
                or (
                    reason.startswith("initialization-batch.")
                    and "-finalization." in reason
                )
            )
        ):
            return None

    candidate = json.loads(json.dumps(after_document))
    contracts = candidate.get("contracts")
    if not isinstance(contracts, dict) or "initialized" not in contracts:
        return None
    del contracts["initialized"]
    if not contracts:
        return None
    try:
        return direct_blue_id(candidate)
    except (TypeError, ValueError):
        return None


def intermediate_cyclic_component_batch_entry_blue_id(
    path: Path,
    document_id: str,
    before_markers: dict[str, dict[str, Any]],
    after_markers: dict[str, dict[str, Any]],
    expected: dict[str, Any],
    fixture: dict[str, Any] | None,
    marker_writes: dict[str, dict[str, Any]] | None,
) -> str | None:
    """Correlate an already-proven intermediate cyclic batch-entry state."""
    if (
        fixture is None
        or marker_writes is None
        or expected.get("status") != "success"
        or document_id not in marker_writes
        or before_markers.get(document_id, {}).get("initialized") is not None
        or after_markers.get(document_id, {}).get("initialized") is None
    ):
        return None
    marker_blue_id = after_markers[document_id]["initialized"]["document"][
        "blueId"
    ]
    work_trace = expected["workTrace"]
    initialization_work = [
        work
        for work in work_trace
        if work["kind"] == "INITIALIZATION"
        and work["targetDocumentId"] == document_id
    ]
    if len(initialization_work) != 1:
        return None
    initialization = initialization_work[0]
    trace = expected_gas_trace(path, expected)
    initialization_enqueues = [
        entry
        for entry in trace
        if entry["counter"] == "closureWorkOccurrenceEnqueued"
        and entry.get("workOccurrenceId") == initialization["workIdentity"]
    ]
    if (
        len(initialization_enqueues) != 1
        or initialization_enqueues[0].get("namespace") != "processor"
        or initialization_enqueues[0].get("quantity") != 1
        or initialization_enqueues[0].get("documentId") != document_id
        or initialization_enqueues[0].get("contractKey")
        != initialization.get("channelKey")
        or initialization_enqueues[0].get("logicalPath")
        != f"work/{initialization['ordinal']}"
        or initialization_enqueues[0].get("reason")
        != f"work.{initialization['ordinal']}.initialization-enqueue"
    ):
        return None
    initialization_enqueue_sequence = initialization_enqueues[0]["sequence"]
    work_by_ordinal = {work["ordinal"]: work for work in work_trace}

    def gas_key(entry: dict[str, Any]) -> tuple[Any, ...]:
        return tuple(
            entry.get(key)
            for key in (
                "namespace",
                "counter",
                "quantity",
                "documentId",
                "reason",
                "workOccurrenceId",
            )
        )

    def finalization_coverage(
        finalization: dict[str, Any],
        prefix: str,
        owner: str | None,
        owner_document_id: str | None,
    ) -> tuple[dict[str, Any], list[dict[str, Any]]] | None:
        try:
            ordered_members = sorted(
                (int(blue_id.rsplit("#", 1)[1]), member)
                for member, blue_id in finalization["memberBlueIds"].items()
            )
        except (KeyError, TypeError, ValueError, AttributeError):
            return None
        if [index for index, _member in ordered_members] != list(
            range(len(ordered_members))
        ):
            return None
        boundary_reason = finalization_gas_reason(
            path, fixture, finalization, prefix
        )
        boundary_rows = [
            entry
            for entry in trace
            if entry["counter"] == "tentativeComponentFinalization"
            and entry.get("reason") == boundary_reason
        ]
        member_prefix = boundary_reason.removesuffix(
            ".finalization-boundary"
        )
        member_rows = sorted(
            (
                entry
                for entry in trace
                if entry["counter"] == "cyclicMemberFinalized"
                and entry.get("reason", "").startswith(
                    f"{member_prefix}.member-finalized."
                )
            ),
            key=lambda entry: entry["sequence"],
        )
        expected_projection = [
            (
                "processor",
                "cyclicMemberFinalized",
                1,
                member,
                f"{member_prefix}.member-finalized.{index}",
                owner,
            )
            for index, member in ordered_members
        ]
        if (
            len(boundary_rows) != 1
            or gas_key(boundary_rows[0])
            != (
                "processor",
                "tentativeComponentFinalization",
                1,
                owner_document_id,
                boundary_reason,
                owner,
            )
            or [gas_key(entry) for entry in member_rows]
            != expected_projection
            or not member_rows
            or boundary_rows[0]["sequence"] >= member_rows[0]["sequence"]
        ):
            return None
        return boundary_rows[0], member_rows

    prior_finalizations = [
        finalization
        for finalization in expected["tentativeFinalizations"]
        if finalization["boundary"]["kind"] == "WORK"
        and finalization["boundary"]["afterWorkOrdinal"]
        < initialization["ordinal"]
        and document_id in finalization["memberBlueIds"]
    ]
    if not prior_finalizations:
        return None
    latest_boundary = max(
        item["boundary"]["afterWorkOrdinal"] for item in prior_finalizations
    )
    latest = [
        item
        for item in prior_finalizations
        if item["boundary"]["afterWorkOrdinal"] == latest_boundary
    ]
    if len(latest) != 1 or latest[0]["memberBlueIds"][document_id] != marker_blue_id:
        return None
    entry_finalization = latest[0]
    owner_work = work_by_ordinal.get(latest_boundary)
    entry_members = set(entry_finalization["memberBlueIds"])
    prior_component_work = [
        work["ordinal"]
        for work in work_trace
        if work["ordinal"] < initialization["ordinal"]
        and work["targetDocumentId"] in entry_members
    ]
    if (
        owner_work is None
        or not prior_component_work
        or latest_boundary != max(prior_component_work)
    ):
        return None
    entry_coverage = finalization_coverage(
        entry_finalization,
        f"work.{latest_boundary}",
        owner_work["workIdentity"],
        owner_work["targetDocumentId"],
    )
    if (
        entry_coverage is None
        or entry_coverage[1][-1]["sequence"] >= initialization_enqueue_sequence
    ):
        return None

    final_batch_proofs = []
    for finalization in expected["tentativeFinalizations"]:
        boundary = finalization["boundary"]
        members = set(finalization["memberBlueIds"])
        if (
            boundary["kind"] != "INITIALIZATION_BATCH"
            or finalization["ordinal"] <= entry_finalization["ordinal"]
            or document_id not in members
            or boundary["afterWorkOrdinal"] < initialization["ordinal"]
            or not members <= before_markers.keys()
            or not members <= after_markers.keys()
        ):
            continue
        member_work_ids = {
            work["workIdentity"]
            for work in work_trace
            if work["targetDocumentId"] in members
            and work["ordinal"] <= boundary["afterWorkOrdinal"]
        }
        member_work_rows = [
            entry
            for entry in trace
            if entry.get("workOccurrenceId") in member_work_ids
        ]
        if not member_work_rows or {
            entry["workOccurrenceId"] for entry in member_work_rows
        } != member_work_ids:
            continue
        coverage = finalization_coverage(
            finalization, "initialization-batch", None, None
        )
        if coverage is None:
            continue
        boundary_row, final_member_rows = coverage
        if boundary_row["sequence"] >= final_member_rows[0]["sequence"]:
            continue
        last_member_work = max(row["sequence"] for row in member_work_rows)
        batch_rows = sorted(
            (
                entry
                for entry in marker_writes.values()
                if last_member_work < entry["sequence"]
                < boundary_row["sequence"]
            ),
            key=lambda entry: entry["sequence"],
        )
        batch_document_ids = [entry["documentId"] for entry in batch_rows]
        if (
            not batch_document_ids
            or document_id not in batch_document_ids
            or batch_document_ids != sorted(batch_document_ids)
            or not set(batch_document_ids) <= members
        ):
            continue
        first_marker = batch_rows[0]["sequence"]
        later_finalization_rows = [
            entry
            for entry in trace
            if entry.get("counter") == "tentativeComponentFinalization"
            and entry["sequence"] > first_marker
        ]
        if (
            not later_finalization_rows
            or min(entry["sequence"] for entry in later_finalization_rows)
            != boundary_row["sequence"]
        ):
            continue
        final_member_sequence = final_member_rows[-1]["sequence"]
        if any(
            entry.get("workOccurrenceId") is not None
            and last_member_work < entry["sequence"] <= final_member_sequence
            for entry in trace
        ):
            continue
        final_batch_proofs.append(finalization)
    return marker_blue_id if len(final_batch_proofs) == 1 else None


def reconstruct_event_only_acyclic_markers(
    path: Path, fixture: dict[str, Any] | None,
) -> dict[str, str]:
    """Replay all marker/rebind state changes for an event-only acyclic closure.

    No business mutation is admitted by this independent proof. Every managed
    input, initialization enqueue, marker, exact child path and complete final
    document participates. Existing general marker proofs remain unchanged.
    """
    if fixture is None:
        return {}
    expected = fixture["expected"]
    if (expected.get("status") != "success" or expected.get("checkpointWrites") != []
            or expected.get("tentativeFinalizations") != []
            or expected.get("rejectedWorkOccurrence") is not None):
        return {}
    runtime = fixture_runtime(path, fixture)
    for bucket in ("handlers", "initializationHandlers"):
        for result in runtime.get(bucket, {}).values():
            if not isinstance(result, dict) or set(result) - {"events"}:
                return {}
    documents = fixture["input"]["documents"]
    occurrences = fixture["input"]["occurrences"]
    if any(not row["active"] or row["pendingHistoricalEpoch"] is not None
           for row in occurrences):
        return {}
    bodies = {key: json.loads(json.dumps(record["document"]))
              for key, record in documents.items()}
    outgoing: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in occurrences:
        outgoing[row["sourceDocumentId"]].append(row)
        exact_value = pointer_get(bodies[row["sourceDocumentId"]], row["sourcePath"])
        exact_id = pure_blue_reference(exact_value) or direct_blue_id(exact_value)
        if exact_id != row["expectedTargetBlueId"]:
            return {}
    identities: dict[str, str] = {}
    visiting: set[str] = set()
    def rebuild(key: str) -> str:
        if key in identities:
            return identities[key]
        require(key not in visiting, f"event-only marker proof contains a cycle in {path.name}")
        visiting.add(key)
        for row in outgoing[key]:
            child_id = rebuild(row["targetDocumentId"])
            prior_value = pointer_get(bodies[key], row["sourcePath"])
            prior_id = pure_blue_reference(prior_value) or direct_blue_id(prior_value)
            if prior_id != child_id:
                bodies[key] = replace_existing_pointer_value(path, bodies[key], row["sourcePath"],
                                                            {"blueId": child_id}, "marker-reconstruction")
        visiting.remove(key)
        identities[key] = direct_blue_id(bodies[key])
        return identities[key]
    try:
        for key in sorted(bodies): rebuild(key)
    except ValidationFailure:
        return {}
    if any(identities[key] != record["blueId"] for key, record in documents.items()):
        return {}
    trace = expected_gas_trace(path, expected)
    markers = [row for row in trace if row["counter"] == "processorMarkerWritten"]
    if len(markers) != len(documents) or {row.get("documentId") for row in markers} != set(documents):
        return {}
    entries: dict[str, str] = {}
    marker_sequences: dict[str, int] = {}
    for marker in markers:
        key = marker["documentId"]
        works = [work for work in expected["workTrace"]
                 if work["kind"] == "INITIALIZATION" and work["targetDocumentId"] == key]
        if len(works) != 1 or "initialized" in bodies[key].get("contracts", {}):
            return {}
        work = works[0]
        enqueues = [row for row in trace if row["counter"] == "closureWorkOccurrenceEnqueued"
                    and row.get("workOccurrenceId") == work["workIdentity"]
                    and row.get("documentId") == key
                    and row.get("reason") == f"work.{work['ordinal']}.initialization-enqueue"]
        own_work_ids = {item["workIdentity"] for item in expected["workTrace"]
                        if item["targetDocumentId"] == key}
        own_sequences = [row["sequence"] for row in trace if row.get("workOccurrenceId") in own_work_ids]
        if (len(enqueues) != 1 or marker.get("reason") != f"initialization-batch.marker.{key}"
                or marker.get("quantity") != 1 or marker.get("workOccurrenceId") is not None
                or not own_sequences or max(own_sequences) >= marker["sequence"]
                or any(marker_sequences.get(row["targetDocumentId"], marker["sequence"])
                       >= enqueues[0]["sequence"] for row in outgoing[key])):
            return {}
        entries[key] = identities[key]
        bodies[key].setdefault("contracts", {})["initialized"] = {
            "type": {"blueId": registry_blue_id("ProcessingInitializedMarker")},
            "document": {"blueId": identities[key]},
        }
        marker_sequences[key] = marker["sequence"]
        identities.clear()
        for document_id in sorted(bodies): rebuild(document_id)
    after = result_documents(expected)
    if set(after) != set(documents) or any(
            bodies[key] != after[key]["document"] or identities[key] != after[key]["blueId"]
            for key in documents):
        return {}
    return entries


def stable_rebound_component_batch_entry_blue_id(
    path: Path,
    document_id: str,
    before_documents: dict[str, Any],
    after_documents: dict[str, Any],
    before_markers: dict[str, Any],
    after_markers: dict[str, Any],
    expected: dict[str, Any],
    before_occurrences: list[dict[str, Any]] | None,
    after_occurrences: list[dict[str, Any]] | None,
) -> str | None:
    """Recover a parent initialization-batch entry after stable child rebinds.

    A later member of an initialization batch can enter its own initialization
    only after already-finalized children have been rebound into its embedded
    paths.  That batch-entry state is independently reconstructable from the
    invocation input plus the exact, same-lineage REBIND rows.  The helper is
    deliberately fail-closed: any graph ambiguity, unexpected work ordering,
    or incomplete gas evidence returns ``None``.
    """
    if (
        expected.get("status") != "success"
        or expected.get("rejectedWorkOccurrence") is not None
        or expected.get("checkpointWrites") != []
        or expected.get("tentativeFinalizations") != []
        or before_markers.get("terminated") != after_markers.get("terminated")
        or before_markers.get("checkpoint") != after_markers.get("checkpoint")
        or not isinstance(before_occurrences, list)
        or not isinstance(after_occurrences, list)
        or document_id not in before_documents
        or document_id not in after_documents
    ):
        return None

    before_record = before_documents[document_id]
    after_record = after_documents[document_id]
    before_document = before_record.get("document")
    after_document = after_record.get("document")
    if (
        not isinstance(before_document, dict)
        or not isinstance(after_document, dict)
        or not isinstance(before_record.get("blueId"), str)
        or not isinstance(after_record.get("blueId"), str)
        or "#" in before_record["blueId"]
        or "#" in after_record["blueId"]
    ):
        return None
    try:
        if (
            direct_blue_id(before_document) != before_record["blueId"]
            or direct_blue_id(after_document) != after_record["blueId"]
        ):
            return None
    except (TypeError, ValueError):
        return None

    before_by_path = {
        row["sourcePath"]: row
        for row in before_occurrences
        if row.get("sourceDocumentId") == document_id and row.get("active")
    }
    after_by_path = {
        row["sourcePath"]: row
        for row in after_occurrences
        if row.get("sourceDocumentId") == document_id and row.get("active")
    }
    if (
        len(before_by_path)
        != sum(
            1
            for row in before_occurrences
            if row.get("sourceDocumentId") == document_id
            and row.get("active")
        )
        or len(after_by_path)
        != sum(
            1
            for row in after_occurrences
            if row.get("sourceDocumentId") == document_id
            and row.get("active")
        )
        or set(before_by_path) != set(after_by_path)
    ):
        return None

    changed_rows: list[tuple[dict[str, Any], dict[str, Any]]] = []
    for source_path in sorted(before_by_path):
        before_row = before_by_path[source_path]
        after_row = after_by_path[source_path]
        if before_row == after_row:
            continue
        mutable_rebind_fields = {"bindingIdentity", "expectedTargetBlueId"}
        if (
            {
                key: value
                for key, value in before_row.items()
                if key not in mutable_rebind_fields
            }
            != {
                key: value
                for key, value in after_row.items()
                if key not in mutable_rebind_fields
            }
            or before_row.get("expectedTargetBlueId")
            == after_row.get("expectedTargetBlueId")
            or before_row.get("bindingIdentity")
            == after_row.get("bindingIdentity")
        ):
            return None
        target_document_id = after_row.get("targetDocumentId")
        if (
            target_document_id not in before_documents
            or target_document_id not in after_documents
            or before_documents[target_document_id].get("blueId")
            != before_row.get("expectedTargetBlueId")
            or after_documents[target_document_id].get("blueId")
            != after_row.get("expectedTargetBlueId")
        ):
            return None
        try:
            after_pointer = pointer_get(after_document, source_path)
        except (KeyError, ValidationFailure):
            return None
        if pure_blue_reference(after_pointer) != after_row.get(
            "expectedTargetBlueId"
        ):
            return None
        target_before_document = before_documents[target_document_id].get(
            "document"
        )
        target_after_document = after_documents[target_document_id].get(
            "document"
        )
        if (
            not isinstance(target_before_document, dict)
            or not isinstance(target_after_document, dict)
            or "#" in before_documents[target_document_id].get("blueId", "#")
            or "#" in after_documents[target_document_id].get("blueId", "#")
        ):
            return None
        try:
            if (
                direct_blue_id(target_before_document)
                != before_documents[target_document_id]["blueId"]
                or direct_blue_id(target_after_document)
                != after_documents[target_document_id]["blueId"]
            ):
                return None
        except (TypeError, ValueError):
            return None
        target_before_contracts = target_before_document.get("contracts", {})
        target_after_contracts = target_after_document.get("contracts", {})
        if (
            not isinstance(target_before_contracts, dict)
            or not isinstance(target_after_contracts, dict)
            or "initialized" in target_before_contracts
            or "initialized" not in target_after_contracts
        ):
            return None
        changed_rows.append((before_row, after_row))
    if not changed_rows:
        return None

    try:
        derived_changes = derive_graph_changes(
            before_occurrences, after_occurrences
        )
    except (KeyError, ValidationFailure):
        return None
    if expected.get("graphChanges") != derived_changes:
        return None
    source_changes = [
        change
        for change in derived_changes
        if change.get("sourceDocumentId") == document_id
    ]
    if (
        len(source_changes) != len(changed_rows)
        or any(change.get("changeKind") != "REBIND" for change in source_changes)
    ):
        return None

    work_trace = expected.get("workTrace")
    if not isinstance(work_trace, list) or not work_trace:
        return None
    if [work.get("ordinal") for work in work_trace] != list(range(len(work_trace))):
        return None
    initialization_work = [
        work
        for work in work_trace
        if work.get("kind") == "INITIALIZATION"
        and work.get("targetDocumentId") == document_id
    ]
    if len(initialization_work) != 1:
        return None
    initialization = initialization_work[0]
    initialization_ordinal = initialization["ordinal"]
    document_work = [
        work for work in work_trace if work.get("targetDocumentId") == document_id
    ]
    if (
        initialization_ordinal == 0
        or any(work["ordinal"] < initialization_ordinal for work in document_work)
        or any(
            work.get("targetDocumentId") != document_id
            or work.get("kind") != "LIFECYCLE"
            for work in work_trace[initialization_ordinal + 1 :]
        )
        or not any(
            work.get("kind") == "LIFECYCLE"
            and work["ordinal"] > initialization_ordinal
            for work in document_work
        )
    ):
        return None
    target_document_ids = {
        after_row["targetDocumentId"] for _, after_row in changed_rows
    }
    for target_document_id in target_document_ids:
        target_work = [
            work
            for work in work_trace
            if work.get("targetDocumentId") == target_document_id
        ]
        if not target_work or any(
            work["ordinal"] >= initialization_ordinal for work in target_work
        ):
            return None

    try:
        trace = expected_gas_trace(path, expected)
    except (KeyError, ValidationFailure):
        return None
    if (
        not isinstance(trace, list)
        or [entry.get("sequence") for entry in trace]
        != list(range(len(trace)))
    ):
        return None
    initialization_identity = initialization.get("workIdentity")

    def exact_initialization_charge(counter: str, reason: str) -> list[dict[str, Any]]:
        return [
            entry
            for entry in trace
            if entry.get("counter") == counter
            and entry.get("documentId") == document_id
            and entry.get("workOccurrenceId") == initialization_identity
            and entry.get("reason") == reason
        ]

    enqueues = exact_initialization_charge(
        "closureWorkOccurrenceEnqueued",
        f"work.{initialization_ordinal}.initialization-enqueue",
    )
    dequeues = exact_initialization_charge(
        "closureWorkOccurrenceDequeued",
        f"work.{initialization_ordinal}.dequeue",
    )
    scopes = exact_initialization_charge("scopeInitialization", "scope-initialization")
    own_marker_writes = [
        entry
        for entry in trace
        if entry.get("counter") == "processorMarkerWritten"
        and entry.get("documentId") == document_id
        and entry.get("quantity") == 1
        and entry.get("workOccurrenceId") is None
        and entry.get("reason") == f"initialization-batch.marker.{document_id}"
    ]
    if not all(
        len(entries) == 1
        for entries in (enqueues, dequeues, scopes, own_marker_writes)
    ):
        return None
    enqueue_sequence = enqueues[0]["sequence"]
    marker_sequence = own_marker_writes[0]["sequence"]
    if not (
        enqueue_sequence
        < dequeues[0]["sequence"]
        < scopes[0]["sequence"]
        < marker_sequence
    ):
        return None

    work_identities = {work.get("workIdentity") for work in document_work}
    work_gas_sequences = [
        entry["sequence"]
        for entry in trace
        if entry.get("workOccurrenceId") in work_identities
    ]
    if not work_gas_sequences or max(work_gas_sequences) >= marker_sequence:
        return None

    child_marker_sequences: list[int] = []
    for target_document_id in sorted(target_document_ids):
        markers = [
            entry
            for entry in trace
            if entry.get("counter") == "processorMarkerWritten"
            and entry.get("documentId") == target_document_id
            and entry.get("quantity") == 1
            and entry.get("workOccurrenceId") is None
            and entry.get("reason")
            == f"initialization-batch.marker.{target_document_id}"
        ]
        if len(markers) != 1 or markers[0]["sequence"] >= enqueue_sequence:
            return None
        target_work_identities = {
            work.get("workIdentity")
            for work in work_trace
            if work.get("targetDocumentId") == target_document_id
        }
        target_work_sequences = [
            entry["sequence"]
            for entry in trace
            if entry.get("workOccurrenceId") in target_work_identities
        ]
        if (
            not target_work_sequences
            or max(target_work_sequences) >= markers[0]["sequence"]
        ):
            return None
        child_marker_sequences.append(markers[0]["sequence"])
    finalization_reasons = {
        "nodeIdentityEstablished":
            "initialization-batch.acyclic-finalization.node-established",
        "objectMemberRebuilt":
            "initialization-batch.acyclic-finalization.object-members",
        "directIdentityHashBlock":
            "initialization-batch.acyclic-finalization.direct-hash",
    }
    containing_spine_finalizations = []
    for counter, reason in finalization_reasons.items():
        entries = [
            entry
            for entry in trace
            if entry.get("namespace") == "semantic"
            and entry.get("counter") == counter
            and entry.get("documentId") == document_id
            and entry.get("workOccurrenceId") is None
            and entry.get("reason") == reason
            and max(child_marker_sequences)
            < entry["sequence"]
            < enqueue_sequence
        ]
        if len(entries) != 1:
            return None
        containing_spine_finalizations.append(entries[0])
    if [entry["sequence"] for entry in containing_spine_finalizations] != sorted(
        entry["sequence"] for entry in containing_spine_finalizations
    ):
        return None

    identity_counters = {
        "nodeIdentityEstablished",
        "objectMemberRebuilt",
        "directIdentityHashBlock",
    }
    for entry in trace[marker_sequence + 1 :]:
        reason = entry.get("reason", "")
        if not (
            entry.get("namespace") == "semantic"
            and entry.get("counter") in identity_counters
            and entry.get("documentId") == document_id
            and entry.get("workOccurrenceId") is None
            and (
                reason == "identity-rebuild"
                or (
                    reason.startswith("initialization-batch.")
                    and "finalization" in reason
                )
            )
        ):
            return None

    candidate = json.loads(json.dumps(before_document))
    try:
        for _, after_row in changed_rows:
            candidate = replace_existing_pointer_value(
                path,
                candidate,
                after_row["sourcePath"],
                {"blueId": after_row["expectedTargetBlueId"]},
                f"initialization-batch-entry/{document_id}"
                f"{after_row['sourcePath']}",
            )
        candidate_blue_id = direct_blue_id(candidate)
        if candidate_blue_id == before_record["blueId"]:
            return None
        return candidate_blue_id
    except (KeyError, TypeError, ValueError, ValidationFailure):
        return None


def validate_direct_marker_transition_self_check() -> None:
    path = Path("direct-marker-transition-self-check.yaml")
    document_id = "self-check"
    invocation_document = {"documentId": document_id, "state": "input"}
    batch_entry_document = {
        "documentId": document_id,
        "state": "batch-entry",
        "contracts": {"application": {"value": "retained"}},
    }
    invocation_blue_id = direct_blue_id(invocation_document)
    batch_entry_blue_id = direct_blue_id(batch_entry_document)
    result_document = json.loads(json.dumps(batch_entry_document))
    result_document["contracts"]["initialized"] = {
        "type": {"blueId": direct_blue_id({"kind": "initialized-type"})},
        "document": {"blueId": batch_entry_blue_id},
    }
    before_documents = {
        document_id: {
            "blueId": invocation_blue_id,
            "document": invocation_document,
        }
    }
    after_documents = {
        document_id: {
            "blueId": direct_blue_id(result_document),
            "document": result_document,
        }
    }
    before_markers = {
        document_id: {
            "initialized": None,
            "terminated": None,
            "checkpoint": None,
        }
    }
    after_markers = {
        document_id: {
            "initialized": result_document["contracts"]["initialized"],
            "terminated": None,
            "checkpoint": None,
        }
    }
    work_identity = "sha256:" + "1" * 64
    expected = {
        "status": "success",
        "rejectedWorkOccurrence": None,
        "checkpointWrites": [],
        "tentativeFinalizations": [],
        "workTrace": [
            {
                "ordinal": 0,
                "kind": "INITIALIZATION",
                "targetDocumentId": document_id,
                "workIdentity": work_identity,
            }
        ],
        "gasTrace": [
            {
                "sequence": 0,
                "counter": "closureWorkOccurrenceEnqueued",
                "documentId": document_id,
                "workOccurrenceId": work_identity,
                "reason": "work.0.initialization-enqueue",
            },
            {
                "sequence": 1,
                "counter": "closureWorkOccurrenceDequeued",
                "documentId": document_id,
                "workOccurrenceId": work_identity,
                "reason": "work.0.dequeue",
            },
            {
                "sequence": 2,
                "counter": "scopeInitialization",
                "documentId": document_id,
                "workOccurrenceId": work_identity,
                "reason": "scope-initialization",
            },
            {
                "sequence": 3,
                "counter": "processorMarkerWritten",
                "documentId": document_id,
                "quantity": 1,
                "reason": f"initialization-batch.marker.{document_id}",
            },
            {
                "sequence": 4,
                "namespace": "semantic",
                "counter": "directIdentityHashBlock",
                "documentId": document_id,
                "reason": "initialization-batch.acyclic-finalization.direct-hash",
            },
        ],
    }
    validate_direct_marker_transition(
        path,
        before_documents,
        after_documents,
        before_markers,
        after_markers,
        expected,
    )

    invocation_markers = json.loads(json.dumps(after_markers))
    invocation_markers[document_id]["initialized"]["document"] = {
        "blueId": invocation_blue_id
    }
    validate_direct_marker_transition(
        path,
        before_documents,
        after_documents,
        before_markers,
        invocation_markers,
        {},
    )

    invalid_cases: list[
        tuple[dict[str, Any], dict[str, Any], dict[str, Any]]
    ] = []
    arbitrary_markers = json.loads(json.dumps(after_markers))
    arbitrary_markers[document_id]["initialized"]["document"] = {
        "blueId": direct_blue_id({"arbitrary": True})
    }
    invalid_cases.append((arbitrary_markers, expected, after_documents))
    missing_gas = json.loads(json.dumps(expected))
    missing_gas["gasTrace"] = []
    invalid_cases.append((after_markers, missing_gas, after_documents))
    later_work = json.loads(json.dumps(expected))
    later_work["workTrace"].append(
        {
            "ordinal": 1,
            "kind": "EMBEDDED_EVENT",
            "targetDocumentId": "later-document",
            "workIdentity": "sha256:" + "2" * 64,
        }
    )
    invalid_cases.append((after_markers, later_work, after_documents))
    post_marker_work = json.loads(json.dumps(expected))
    post_marker_work["gasTrace"].append(
        {
            "sequence": 5,
            "counter": "handlerCall",
            "documentId": document_id,
            "workOccurrenceId": work_identity,
            "reason": "post-marker-work",
        }
    )
    invalid_cases.append((after_markers, post_marker_work, after_documents))
    checkpoint_settlement = json.loads(json.dumps(expected))
    checkpoint_settlement["checkpointWrites"] = [{"unexpected": True}]
    invalid_cases.append(
        (after_markers, checkpoint_settlement, after_documents)
    )
    changed_checkpoint_markers = json.loads(json.dumps(after_markers))
    changed_checkpoint_markers[document_id]["checkpoint"] = {
        "changed": True
    }
    invalid_cases.append(
        (changed_checkpoint_markers, expected, after_documents)
    )
    cyclic_result_documents = json.loads(json.dumps(after_documents))
    cyclic_result_documents[document_id]["blueId"] = (
        cyclic_result_documents[document_id]["blueId"] + "#0"
    )
    invalid_cases.append(
        (after_markers, expected, cyclic_result_documents)
    )
    empty_batch_entry = {"documentId": document_id, "state": "empty"}
    empty_batch_entry_blue_id = direct_blue_id(empty_batch_entry)
    empty_result = json.loads(json.dumps(empty_batch_entry))
    empty_result["contracts"] = {
        "initialized": {
            "type": {"blueId": direct_blue_id({"kind": "initialized-type"})},
            "document": {"blueId": empty_batch_entry_blue_id},
        }
    }
    empty_result_documents = {
        document_id: {
            "blueId": direct_blue_id(empty_result),
            "document": empty_result,
        }
    }
    empty_result_markers = json.loads(json.dumps(after_markers))
    empty_result_markers[document_id]["initialized"] = empty_result[
        "contracts"
    ]["initialized"]
    invalid_cases.append(
        (empty_result_markers, expected, empty_result_documents)
    )
    changed_result_documents = json.loads(json.dumps(after_documents))
    changed_result_documents[document_id]["document"]["state"] = "later"
    invalid_cases.append(
        (after_markers, expected, changed_result_documents)
    )

    for markers, evidence, candidate_after_documents in invalid_cases:
        try:
            validate_direct_marker_transition(
                path,
                before_documents,
                candidate_after_documents,
                before_markers,
                markers,
                evidence,
            )
        except ValidationFailure:
            continue
        raise ValidationFailure(
            "invalid initialized-marker batch-entry evidence passed the "
            "static self-check"
        )


def validate_intermediate_cyclic_marker_transition_self_check() -> None:
    path = CLOSURE / "c-clo-08-cycle-during-initialization.yaml"
    fixture = load_yaml(path)
    expected = fixture["expected"]
    before = direct_processor_markers(
        path, fixture["input"]["documents"], "self-check-input"
    )
    after = direct_processor_markers(
        path, result_documents(expected), "self-check-result"
    )
    marker_blue_id = after["b"]["initialized"]["document"]["blueId"]

    def clone() -> dict[str, Any]:
        return json.loads(json.dumps(expected))

    def gas_row(evidence: dict[str, Any], reason: str) -> dict[str, Any]:
        matches = [
            row for row in evidence["gasTrace"] if row.get("reason") == reason
        ]
        require(
            len(matches) == 1,
            f"C-CLO-08 self-check mutation has non-unique row: {reason}",
        )
        return matches[0]

    def insert_gas(
        evidence: dict[str, Any],
        after_sequence: int,
        row: dict[str, Any],
    ) -> None:
        for existing in evidence["gasTrace"]:
            if existing["sequence"] > after_sequence:
                existing["sequence"] += 1
        row["sequence"] = after_sequence + 1
        evidence["gasTrace"].append(row)
        evidence["gasTrace"].sort(key=lambda item: item["sequence"])

    def work_charge(document_id: str, digit: str) -> dict[str, Any]:
        return {
            "namespace": "processor",
            "counter": "handlerCall",
            "quantity": 1,
            "documentId": document_id,
            "workOccurrenceId": "sha256:" + digit * 64,
            "reason": "self-check.intervening-work",
        }

    def admitted(evidence: dict[str, Any]) -> str | None:
        try:
            writes = validate_initialization_marker_write_set(
                path, before, after, evidence
            )
        except ValidationFailure:
            return None
        try:
            return intermediate_cyclic_component_batch_entry_blue_id(
                path, "b", before, after, evidence, fixture, writes
            )
        except ValidationFailure:
            return None

    require(
        admitted(expected) == marker_blue_id,
        "C-CLO-08 intermediate cyclic marker proof was not admitted",
    )
    rejected: list[tuple[str, dict[str, Any]]] = []

    wrong_reason = clone()
    gas_row(wrong_reason, "work.1.finalization.finalization-boundary")["reason"] = "wrong.finalization-boundary"
    rejected.append(("incorrect finalization charge reason", wrong_reason))

    wrong_oracle_bytes = clone()
    wrong_oracle_bytes["tentativeFinalizations"][0]["canonicalBytes"] += 1
    rejected.append(("incorrect oracle stage canonical bytes", wrong_oracle_bytes))

    missing_marker = clone()
    missing_marker["gasTrace"] = [
        row
        for row in missing_marker["gasTrace"]
        if row.get("reason") != "initialization-batch.marker.a"
    ]
    rejected.append(("missing marker write", missing_marker))

    malformed_marker = clone()
    duplicate = json.loads(json.dumps(
        gas_row(malformed_marker, "initialization-batch.marker.a")
    ))
    duplicate["quantity"] = 2
    malformed_marker["gasTrace"].append(duplicate)
    rejected.append(("malformed duplicate marker write", malformed_marker))

    final_prefix = "initialization-batch."
    for label, reason, changes in (
        (
            "incorrect initialization enqueue binding",
            "work.2.initialization-enqueue",
            {"logicalPath": "work/99", "contractKey": "wrong"},
        ),
        ("malformed finalization boundary", final_prefix + "finalization-boundary", {"quantity": 2}),
        ("malformed cyclic member finalization", final_prefix + "member-finalized.0", {"quantity": 2}),
    ):
        evidence = clone()
        gas_row(evidence, reason).update(changes)
        rejected.append((label, evidence))

    stale_entry = clone()
    for work in stale_entry["workTrace"]:
        if work["ordinal"] >= 2:
            work["ordinal"] += 1
    extra_work = json.loads(json.dumps(stale_entry["workTrace"][3]))
    extra_work.update(
        ordinal=2,
        workIdentity="sha256:" + "2" * 64,
    )
    stale_entry["workTrace"].insert(2, extra_work)
    stale_entry["tentativeFinalizations"][1]["boundary"][
        "afterWorkOrdinal"
    ] = 4
    initialization_enqueue = gas_row(stale_entry, "work.2.initialization-enqueue")
    initialization_enqueue.update(reason="work.3.initialization-enqueue", logicalPath="work/3")
    insert_gas(
        stale_entry,
        72,
        work_charge("b", "2"),
    )
    rejected.append(("stale state before later component work", stale_entry))

    noncanonical_batch = clone()
    first = gas_row(noncanonical_batch, "initialization-batch.marker.a")
    second = gas_row(noncanonical_batch, "initialization-batch.marker.b")
    first["sequence"], second["sequence"] = second["sequence"], first["sequence"]
    rejected.append(("noncanonical marker batch", noncanonical_batch))

    intervening_work = clone()
    insert_gas(
        intervening_work,
        108,
        work_charge("c", "3"),
    )
    rejected.append(("work inside finalization interval", intervening_work))

    pre_marker_work = clone()
    insert_gas(pre_marker_work, 97, work_charge("c", "6"))
    rejected.append(("work after member work before first marker", pre_marker_work))

    later_entry = clone()
    later_finalization = json.loads(json.dumps(
        later_entry["tentativeFinalizations"][0]
    ))
    later_finalization["ordinal"] = 1
    later_finalization["memberBlueIds"]["b"] = later_entry[
        "tentativeFinalizations"
    ][1]["memberBlueIds"]["b"]
    later_entry["tentativeFinalizations"][1]["ordinal"] = 2
    later_entry["tentativeFinalizations"].insert(1, later_finalization)
    rejected.append(("later changed state containing initialized member", later_entry))

    for label, evidence in rejected:
        require(
            admitted(evidence) is None,
            f"intermediate cyclic marker self-check accepted {label}",
        )

    later_work = clone()
    insert_gas(later_work, 149, work_charge("c", "5"))
    require(
        admitted(later_work) == marker_blue_id,
        "intermediate cyclic marker proof rejected later independent work",
    )


def contracts_at_scope(document: Any, scope_path: str) -> dict[str, Any]:
    scope = pointer_get(document, scope_path)
    require(isinstance(scope, dict), f"scope is not object: {scope_path}")
    contracts = scope.get("contracts", {})
    require(isinstance(contracts, dict), f"contracts is not object at {scope_path}")
    return contracts


def active_declared_path(document: dict[str, Any], source_path: str,
                         provider_nodes: dict[str, Any] | None = None) -> bool:
    contracts = exact_contract_declarations(document, provider_nodes)
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
    *,
    suspended_demand_sites: set[tuple[str, str]] | None = None,
    provider_nodes: dict[str, Any] | None = None,
) -> None:
    """Prove that declarations and managed occurrence rows cover each other.

    A fixed declaration owns one row whether its exact path is present or
    prospectively absent. A collection declaration owns one row for every
    direct object member and cannot be used as an implicit list/wildcard scan.
    The later occurrence-value checks remain representation-neutral and prove
    each row's exact target identity.
    """
    allowed_missing = suspended_demand_sites or set()
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
        contracts = exact_contract_declarations(document, provider_nodes)
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
            source_key = (document_id, source_path)
            rows = rows_by_path.get(source_key, [])
            if not rows and source_key in allowed_missing:
                # NeedsResources is the only state in which the exact typed
                # demand may stand in for the row that the suspended attempt
                # deliberately did not invent or reconcile. The mandatory
                # Java replay proves tentative discovery semantics.
                return
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
                collection = document
                collection_absent = False
                for segment in pointer_segments(collection_path):
                    require(
                        isinstance(collection, dict)
                        and set(collection) != {"blueId"},
                        f"Process Embedded collection path lacks complete object "
                        f"evidence in {path.name}: {document_id}{collection_path}",
                    )
                    if segment not in collection:
                        collection_absent = True
                        break
                    collection = collection[segment]
                if collection_absent:
                    # Section 12: proven absence has zero active occurrences.
                    # Inspect every reserved row; do not synthesize a collection
                    # or confuse unavailable reference content with absence.
                    collection_segments = pointer_segments(collection_path)
                    for (source_id, source_path), rows in rows_by_path.items():
                        if source_id != document_id:
                            continue
                        segments = pointer_segments(source_path)
                        if strict_prefix(collection_segments, segments):
                            require(
                                len(segments) == len(collection_segments) + 1
                                and rows[0].get("active") is False,
                                f"absent Process Embedded collection has an active "
                                f"or non-direct reservation in {path.name}: "
                                f"{document_id}{source_path}",
                            )
                    continue
                require(
                    isinstance(collection, dict)
                    and set(collection) != {"blueId"},
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


def validate_admission_input_membership(
    path: Path,
    documents: dict[str, Any],
    occurrences: list[dict[str, Any]],
) -> None:
    """Prove ADMIT input membership from immutable input graph facts.

    Every uninitialized input document is an ADMIT seed under §7.10.  An
    already-initialized non-Root member needs an active edge or exact retained
    prospective binding evidence. The latter retains an input record without
    adding an active graph edge, delivery recipient or initialization trigger.
    A resource demand cannot invent that evidence.
    """
    document_ids = set(documents)
    require(
        document_ids,
        f"admission input is empty in {path.name}",
    )
    seeds = {
        document_id
        for document_id, record in documents.items()
        if record["initialized"] is False or record["publicRoot"] is True
    }
    require(
        seeds,
        f"admission has no initialization or public-Root seed in "
        f"{path.name}",
    )
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
    pending = sorted(seeds, reverse=True)
    reached: set[str] = set()
    while pending:
        document_id = pending.pop()
        if document_id in reached:
            continue
        reached.add(document_id)
        pending.extend(sorted(neighbors[document_id] - reached, reverse=True))
    # Section 2.1 includes exact inactive prospective rows in the closed
    # invocation. Their target records are retained evidence, not active SCC
    # membership. Require a source in the admitted graph and recheck the
    # binding identity and exact target; never infer membership from a demand.
    retained_targets: set[str] = set()
    for occurrence in occurrences:
        if occurrence.get("active") is not False:
            continue
        if occurrence["sourceDocumentId"] not in reached:
            continue
        target = occurrence["targetDocumentId"]
        require(target in documents, f"prospective target absent in {path.name}")
        validate_occurrence_identity(path, occurrence)
        if occurrence["pendingHistoricalEpoch"] is None:
            require(
                occurrence["expectedTargetBlueId"] == documents[target]["blueId"],
                f"prospective target identity mismatch in {path.name}: {target}",
            )
        retained_targets.add(target)
    require(
        reached | retained_targets == document_ids,
        f"admission contains an initialized non-Root document with "
        f"no active membership or verified prospective evidence in {path.name}: "
        f"unreachable={sorted(document_ids - reached - retained_targets)}",
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
        set(needs_expected)
        == {"attemptOutcome", "requiredBlueIds", "resourceDemands"},
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



def is_closure_vector(vector: str) -> bool:
    """Return whether a vector requires the production closure harness."""
    if vector.startswith(("C-CLO-", "FL-ADM-")):
        return True
    return vector.startswith("C-EVO-") and vector[6:] in {
        "11", "12", "13", "18", "19", "20", "21", "22", "23",
    }


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
        == {f"C-CLO-{i:02d}" for i in range(1, 36)},
        "closure vectors must include C-CLO-01..35",
    )
    full_lifecycle = set(v for v in actual if v.startswith("FL-ADM-"))
    require(
        full_lifecycle == {f"FL-ADM-{i:02d}" for i in range(1, 11)},
        "full-lifecycle vectors must be exactly FL-ADM-01..10",
    )
    contract_evolution = set(
        v for v in actual if v.startswith("C-EVO-")
    )
    require(
        contract_evolution == {f"C-EVO-{i:02d}" for i in range(1, 24)},
        "contract-evolution vectors must be exactly C-EVO-01..23",
    )
    embedded_empty = set(
        v for v in actual if v.startswith("C-EMB-EMPTY-")
    )
    require(
        embedded_empty == {f"C-EMB-EMPTY-{i:02d}" for i in range(1, 6)},
        "empty embedded-object vectors must be exactly C-EMB-EMPTY-01..05",
    )
    parent_updates = set(
        v for v in actual if v.startswith("C-UPD-PARENT-")
    )
    require(
        parent_updates == {f"C-UPD-PARENT-{i:02d}" for i in range(1, 3)},
        "parent-update vectors must be exactly C-UPD-PARENT-01..02",
    )
    collection_events = set(
        v for v in actual if v.startswith("C-EVT-COLLECTION-")
    )
    require(
        collection_events
        == {f"C-EVT-COLLECTION-{i:02d}" for i in range(1, 8)},
        "collection-event vectors must be exactly C-EVT-COLLECTION-01..07",
    )
    require(len(actual) == 182, "final vector count must be 182")
    require(
        len([vector for vector in actual if is_closure_vector(vector)]) == 54,
        "final closure vector count must be 54",
    )
    require(
        len([vector for vector in actual if not is_closure_vector(vector)]) == 128,
        "final ordinary vector count must be 128",
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


def fixture_manifest_inventory(root: Path = FIX) -> set[str]:
    return {
        path.relative_to(root).as_posix()
        for path in release_inventory_files(root, {"manifest.yaml"})
    }


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
    discovered_paths = fixture_manifest_inventory()
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
        "ordinaryVectorCount": len([v for v in vectors if not is_closure_vector(v)]),
        "closureVectorCount": len([v for v in vectors if is_closure_vector(v)]),
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


def full_canonical_placeholder_set(oracle: Any) -> list[Any]:
    return [
        member.sorted_document
        for member in sorted(
            oracle.members, key=lambda item: item.sorted_index
        )
    ]


def cyclic_proof_candidate_authenticates(
    candidate: Any,
    authoritative_master_blue_id: str,
    authoritative_member_blue_ids: list[str],
) -> bool:
    if not isinstance(candidate, list) or not candidate:
        return False
    try:
        candidate_oracle = cyclic_set_oracle(candidate)
    except (TypeError, ValueError):
        return False
    recalculated_member_ids = list(
        candidate_oracle.member_ids_in_source_order()
    )
    return (
        candidate_oracle.master_blue_id == authoritative_master_blue_id
        and len(recalculated_member_ids) == len(authoritative_member_blue_ids)
        and len(set(recalculated_member_ids)) == len(recalculated_member_ids)
        and set(recalculated_member_ids)
        == set(authoritative_member_blue_ids)
        and candidate_oracle.sorted_source_indices
        == tuple(range(len(candidate)))
    )


def validate_cyclic_proof_placeholder_selection(
    path: Path,
    context: str,
    oracle: Any,
    proof: dict[str, Any],
    authoritative_member_blue_ids: list[str],
) -> None:
    declared = proof.get("declaredPlaceholderSet")
    compact = cyclic_canonical_limit_form(oracle)
    full = full_canonical_placeholder_set(oracle)
    master_blue_id = oracle.master_blue_id
    compact_authenticates = cyclic_proof_candidate_authenticates(
        compact, master_blue_id, authoritative_member_blue_ids
    )
    full_authenticates = cyclic_proof_candidate_authenticates(
        full, master_blue_id, authoritative_member_blue_ids
    )
    require(
        full_authenticates,
        f"authoritative full cyclic placeholder set does not reproduce its "
        f"finalization in {path.name}: {context}",
    )
    if declared == compact:
        require(
            compact_authenticates,
            f"collapsed cyclic proof does not reproduce the finalized master "
            f"and complete unique member set in {path.name}: {context}",
        )
        return
    require(
        declared == full and not compact_authenticates,
        f"cyclic proof did not select the deterministic collapsed-or-full "
        f"placeholder representation in {path.name}: {context}",
    )


def validate_cyclic_proof_selection_self_check() -> None:
    compact_sources = [
        {
            "name": "A",
            "next": {"blueId": "this#1"},
            "payload": {"name": "left"},
        },
        {
            "name": "B",
            "next": {"blueId": "this#0"},
            "payload": {"name": "right"},
        },
    ]
    compact_oracle = cyclic_set_oracle(compact_sources)
    compact_member_ids = list(compact_oracle.member_ids_in_source_order())
    validate_cyclic_proof_placeholder_selection(
        Path("compact-proof-probe.yaml"),
        "compact-success",
        compact_oracle,
        {
            "declaredPlaceholderSet": cyclic_canonical_limit_form(
                compact_oracle
            )
        },
        compact_member_ids,
    )
    try:
        validate_cyclic_proof_placeholder_selection(
            Path("compact-proof-probe.yaml"),
            "full-when-compact-authenticates",
            compact_oracle,
            {
                "declaredPlaceholderSet": full_canonical_placeholder_set(
                    compact_oracle
                )
            },
            compact_member_ids,
        )
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "cyclic proof self-check accepted full representation when compact "
            "already authenticated"
        )

    shared_child = {"name": "same"}
    fallback_sources = [
        {
            "next": {"blueId": "this#1"},
            "auxiliary": shared_child,
        },
        {
            "next": {"blueId": "this#0"},
            "auxiliary": {"blueId": direct_blue_id(shared_child)},
        },
    ]
    fallback_oracle = cyclic_set_oracle(fallback_sources)
    fallback_member_ids = list(fallback_oracle.member_ids_in_source_order())
    require(
        not cyclic_proof_candidate_authenticates(
            cyclic_canonical_limit_form(fallback_oracle),
            fallback_oracle.master_blue_id,
            fallback_member_ids,
        ),
        "cyclic proof fallback probe unexpectedly authenticated compact form",
    )
    validate_cyclic_proof_placeholder_selection(
        Path("fallback-proof-probe.yaml"),
        "full-fallback",
        fallback_oracle,
        {
            "declaredPlaceholderSet": full_canonical_placeholder_set(
                fallback_oracle
            )
        },
        fallback_member_ids,
    )
    try:
        validate_cyclic_proof_placeholder_selection(
            Path("fallback-proof-probe.yaml"),
            "unauthenticated-compact",
            fallback_oracle,
            {
                "declaredPlaceholderSet": cyclic_canonical_limit_form(
                    fallback_oracle
                )
            },
            fallback_member_ids,
        )
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "cyclic proof self-check accepted unauthenticated compact form"
        )


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
        and list(oracle.member_ids_in_source_order()) == member_blue_ids,
        f"cyclic inline occurrence does not match the complete target proof/member "
        f"mapping in {path.name}: {context}",
    )
    validate_cyclic_proof_placeholder_selection(
        path, context, oracle, proof, member_blue_ids
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
    suspended_demand_sites: set[tuple[str, str]] | None = None,
    provider_nodes: dict[str, Any] | None = None,
) -> int:
    validate_process_embedded_declaration_coverage(
        path,
        documents,
        occurrences,
        suspended_demand_sites=suspended_demand_sites,
        provider_nodes=provider_nodes,
    )
    occurrence_order = [
        (row["occurrenceIdentity"], row["bindingIdentity"])
        for row in occurrences
    ]
    require(
        occurrence_order == sorted(occurrence_order),
        f"occurrence rows are not in canonical identity order in {path.name}",
    )
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
            active_declared_path(source_document, occurrence["sourcePath"], provider_nodes),
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
            validate_cyclic_proof_placeholder_selection(
                path,
                f"component/{','.join(ordered_members)}",
                oracle,
                proof,
                [state["blueId"] for state in member_states],
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
    if route is None:
        # validate_components() already reconstructs every cyclic component
        # through the independent Language oracle.  Explicit route files add
        # fixture-stage coverage, but are not required for generated vectors.
        return
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


def finalization_gas_reason(
    path: Path,
    fixture: dict[str, Any],
    finalization: dict[str, Any],
    prefix: str,
) -> str:
    if fixture.get("oracle") is not None:
        # The exact oracle transition authenticates identity evidence. Runtime
        # gas reasons bind execution boundaries, independently of fixture labels.
        stage_name = finalization_stage_name(path, fixture, finalization["ordinal"])
        _route, stages = fixture_oracle_stages(path, fixture)
        stage = select_oracle_stage(path, stages, stage_name, finalization["memberBlueIds"])
        oracle = cyclic_set_oracle(stage["sourceDocumentsWithThisReferences"])
        document_ids = [item["documentId"] for item in stage["sourceDocumentsWithThisReferences"]]
        require(
            finalization["masterBlueId"] == oracle.master_blue_id
            and finalization["memberBlueIds"] == dict(zip(
                document_ids, oracle.member_ids_in_source_order(), strict=True))
            and finalization["canonicalBytes"] == cyclic_canonical_limit_bytes(oracle),
            f"finalization gas boundary has invalid exact oracle stage in {path.name}: {stage_name}",
        )
    kind = finalization["boundary"]["kind"]
    component_prefix = f"{prefix}.finalization" if kind == "WORK" else prefix
    if kind == "CHECKPOINT_SETTLEMENT":
        # The retained checkpoint vectors contain one settlement round. Exact
        # member sets below reject ambiguous repeated components in that round.
        component_prefix += ".0"
    group = [item for item in fixture["expected"]["tentativeFinalizations"]
             if item["boundary"] == finalization["boundary"]]
    member_sets = [tuple(sorted(item["memberBlueIds"])) for item in group]
    require(len(set(member_sets)) == len(member_sets),
            f"ambiguous repeated finalization component at one boundary in {path.name}")
    if len(group) > 1:
        ordinals = [item["ordinal"] for item in group]
        require(finalization["ordinal"] in ordinals,
                f"finalization ordinal is absent from its boundary in {path.name}")
        component_prefix += f".component.{ordinals.index(finalization['ordinal'])}"
    return f"{component_prefix}.finalization-boundary"


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
    if route is None:
        validate_route_less_tentative_finalizations(
            path, fixture, expected, finalizations
        )
        return
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


def route_less_cyclic_states(
    path: Path,
    label: str,
    documents: dict[str, Any],
    components: list[dict[str, Any]],
    occurrences: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Independently derive cyclic states without fixture oracle labels."""
    states: list[dict[str, Any]] = []
    for component in components:
        if component["kind"] != "CYCLIC":
            continue
        member_ids = component["orderedMemberDocumentIds"]
        member_set = set(member_ids)
        member_index = {
            document_id: index for index, document_id in enumerate(member_ids)
        }
        source_documents: list[Any] = []
        for source_document_id in member_ids:
            source_document = json.loads(json.dumps(
                documents[source_document_id]["document"]
            ))
            for occurrence in occurrences:
                if (
                    occurrence.get("active")
                    and occurrence["sourceDocumentId"] == source_document_id
                    and occurrence["targetDocumentId"] in member_set
                ):
                    source_document = replace_existing_pointer_value(
                        path,
                        source_document,
                        occurrence["sourcePath"],
                        {
                            "blueId":
                                f"this#{member_index[occurrence['targetDocumentId']]}"
                        },
                        f"route-less/{label}/{source_document_id}"
                        f"{occurrence['sourcePath']}",
                    )
            source_documents.append(source_document)
        try:
            oracle = cyclic_set_oracle(source_documents)
        except (TypeError, ValueError) as exc:
            raise ValidationFailure(
                f"route-less cyclic candidate is not exact Language content "
                f"in {path.name}: {label}/{member_ids}: {exc}"
            ) from exc
        states.append(
            {
                "label": label,
                "masterBlueId": oracle.master_blue_id,
                "memberBlueIds": dict(zip(
                    member_ids,
                    oracle.member_ids_in_source_order(),
                    strict=True,
                )),
                "canonicalBytes": cyclic_canonical_limit_bytes(oracle),
            }
        )
    return states


def documents_without_new_direct_marker(
    before_documents: dict[str, Any],
    after_documents: dict[str, Any],
    marker_key: str,
) -> dict[str, Any] | None:
    candidate = json.loads(json.dumps(after_documents))
    removed = 0
    for document_id in sorted(candidate):
        before_contracts = before_documents[document_id]["document"].get(
            "contracts", {}
        )
        after_contracts = candidate[document_id]["document"].get(
            "contracts", {}
        )
        if (
            isinstance(before_contracts, dict)
            and isinstance(after_contracts, dict)
            and marker_key not in before_contracts
            and marker_key in after_contracts
        ):
            del after_contracts[marker_key]
            removed += 1
    return candidate if removed else None


def validate_route_less_tentative_finalizations(
    path: Path,
    fixture: dict[str, Any],
    expected: dict[str, Any],
    finalizations: list[dict[str, Any]],
) -> None:
    """Prove generated finalizations from a closed exact candidate set."""
    fixture_input = fixture["input"]
    output_documents = result_documents(expected)
    candidates: list[dict[str, Any]] = []
    candidates.extend(route_less_cyclic_states(
        path,
        "INPUT",
        fixture_input["documents"],
        fixture_input["components"],
        fixture_input.get("occurrences", []),
    ))
    candidates.extend(route_less_cyclic_states(
        path,
        "RESULT",
        output_documents,
        expected["resultingComponents"],
        expected["occurrenceBindings"],
    ))
    for marker_key, label in (
        ("initialized", "PRE_INITIALIZATION_MARKER"),
        ("terminated", "PRE_TERMINATION_MARKER"),
    ):
        without_marker = documents_without_new_direct_marker(
            fixture_input["documents"], output_documents, marker_key
        )
        if without_marker is not None:
            candidates.extend(route_less_cyclic_states(
                path,
                label,
                without_marker,
                expected["resultingComponents"],
                expected["occurrenceBindings"],
            ))

    states_by_identity: dict[
        tuple[str, tuple[tuple[str, str], ...], int], dict[str, Any]
    ] = {}
    for candidate in candidates:
        key = (
            candidate["masterBlueId"],
            tuple(sorted(candidate["memberBlueIds"].items())),
            candidate["canonicalBytes"],
        )
        state = states_by_identity.setdefault(
            key, {"labels": set(), "candidate": candidate}
        )
        state["labels"].add(candidate["label"])

    for finalization in finalizations:
        key = (
            finalization["masterBlueId"],
            tuple(sorted(finalization["memberBlueIds"].items())),
            finalization["canonicalBytes"],
        )
        state = states_by_identity.get(key)
        require(
            state is not None,
            f"route-less finalization does not match one independently "
            f"reconstructable exact state in {path.name}: "
            f"{finalization['ordinal']}",
        )
        boundary = finalization["boundary"]
        kind = boundary["kind"]
        after_work = boundary.get("afterWorkOrdinal")
        labels = state["labels"]
        member_document_ids = set(finalization["memberBlueIds"])
        component_work_ordinals = [
            work["ordinal"]
            for work in expected["workTrace"]
            if work["targetDocumentId"] in member_document_ids
        ]
        require(
            component_work_ordinals,
            f"route-less finalization has no causal component work in "
            f"{path.name}: {finalization['ordinal']}",
        )
        first_component_work_ordinal = min(component_work_ordinals)
        last_component_work_ordinal = max(component_work_ordinals)
        permitted = (
            kind == "WORK"
            and after_work == first_component_work_ordinal
            and "INPUT" in labels
        ) or (
            kind == "WORK"
            and after_work == last_component_work_ordinal
            and bool(labels & {
                "PRE_INITIALIZATION_MARKER",
                "PRE_TERMINATION_MARKER",
            })
        ) or (
            kind in {"INITIALIZATION_BATCH", "TERMINATION_MARKER"}
            and after_work == last_component_work_ordinal
            and "RESULT" in labels
        )
        require(
            permitted,
            f"route-less finalization state/boundary pairing is not in the "
            f"closed reconstructable set in {path.name}: "
            f"{finalization['ordinal']}/{kind}/{sorted(labels)}",
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


def exact_contract_declarations(document: Any, provider_nodes: dict[str, Any] | None = None) -> dict[str, Any]:
    if not isinstance(document, dict):
        return {}
    contracts = document.get("contracts", {})
    if provider_nodes is not None:
        contracts = exact_contract_source(contracts, provider_nodes)
    require(isinstance(contracts, dict), "contract declarations must be an object")
    return {key: exact_contract_source(value, provider_nodes) if provider_nodes is not None else value
            for key, value in contracts.items()}


def exact_contract_source(value: Any, provider_nodes: dict[str, Any]) -> Any:
    reference = pure_blue_reference(value)
    if reference is None:
        return value
    require(reference in provider_nodes, f"contract source is unavailable: {reference}")
    source = provider_nodes[reference]
    require(direct_blue_id(source) == reference, f"contract source identity mismatch: {reference}")
    return source


def root_subscription_channels(document: Any, provider_nodes: dict[str, Any] | None = None) -> dict[str, Any]:
    if not isinstance(document, dict) or not isinstance(document.get("contracts"), dict):
        return {}
    result: dict[str, Any] = {}
    for key, contract in document["contracts"].items():
        if provider_nodes is not None:
            contract = exact_contract_source(contract, provider_nodes)
        if isinstance(contract, dict) and type_blue_id(contract) in SUBSCRIPTION_CHANNEL_TYPES:
            result[key] = contract
    return result


def validate_subscription_channel_types_self_check() -> None:
    """Keep the independent subscription oracle's closed type set complete."""
    contracts = {
        "scripted": {"type": {"blueId": SCRIPTED_EXTERNAL}},
        "update": {"type": {"blueId": DOCUMENT_UPDATE_CHANNEL}},
        "triggered": {"type": {"blueId": TRIGGERED_EVENT_CHANNEL}},
        "embedded": {"type": {"blueId": EMBEDDED_NODE_CHANNEL}},
        "collection": {"type": {"blueId": EMBEDDED_COLLECTION_EVENT_CHANNEL}},
        "lifecycle": {"type": {"blueId": LIFECYCLE_EVENT_CHANNEL}},
        "application": {"type": {"blueId": "11111111111111111111111111111111"}},
    }
    require(
        set(root_subscription_channels({"contracts": contracts}))
        == {"scripted", "update", "triggered", "embedded", "collection", "lifecycle"},
        "subscription-channel type-set self-check failed",
    )
    before_document = {
        "value": "before",
        "contracts": {
            "lifecycle": {"type": {"blueId": LIFECYCLE_EVENT_CHANNEL}},
            "sourceUpdates": {
                "type": {"blueId": DOCUMENT_UPDATE_CHANNEL},
                "path": "/value",
            },
        },
    }
    after_document = {**before_document, "value": "after"}
    before_record = {
        "document": before_document,
        "blueId": direct_blue_id(before_document),
        "componentGeneration": 1,
    }
    after_record = {
        "document": after_document,
        "blueId": direct_blue_id(after_document),
        "componentGeneration": 1,
    }
    deltas = derive_subscription_deltas(
        {"root": before_record}, {"root": after_record}, 1, 1
    )
    expected_occurrences = [
        subscription_state(
            "root", before_record, key, before_document["contracts"][key], 1
        )["channelOccurrence"]["channelOccurrenceIdentity"]
        for key in ("lifecycle", "sourceUpdates")
    ]
    require(
        len(deltas) == 2
        and [delta["operation"] for delta in deltas] == ["REPLACE", "REPLACE"]
        and [delta["channelOccurrenceIdentity"] for delta in deltas]
        == expected_occurrences,
        "lifecycle/Document Update subscription-delta self-check failed",
    )


def subscription_state(
    document_id: str,
    document_record: dict[str, Any],
    channel_key: str,
    contract: Any,
    graph_generation: int,
) -> dict[str, Any]:
    surface = project_runtime_surface(contract)
    contribution_blue_id = (
        surface.effective_runtime_contribution_blue_id
    )
    header_blue_id = surface.subscription_header_blue_id
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
    provider_nodes: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    channel_orders: dict[tuple[str, str], int] = {}
    before_states: dict[tuple[str, str], dict[str, Any]] = {}
    after_states: dict[tuple[str, str], dict[str, Any]] = {}
    for document_id, record in before_documents.items():
        for key, contract in root_subscription_channels(record["document"], provider_nodes).items():
            raw_order = contract.get("order", 0)
            order = raw_order.get("value", 0) if isinstance(raw_order, dict) else raw_order
            require(isinstance(order, int) and not isinstance(order, bool), "subscription order must be an integer")
            channel_orders[(document_id, key)] = order
            before_states[(document_id, key)] = subscription_state(
                document_id, record, key, contract, before_graph_generation
            )
    for document_id, record in after_documents.items():
        for key, contract in root_subscription_channels(record["document"], provider_nodes).items():
            raw_order = contract.get("order", 0)
            order = raw_order.get("value", 0) if isinstance(raw_order, dict) else raw_order
            require(isinstance(order, int) and not isinstance(order, bool), "subscription order must be an integer")
            channel_orders[(document_id, key)] = order
            after_states[(document_id, key)] = subscription_state(
                document_id, record, key, contract, after_graph_generation
            )
    deltas: list[dict[str, Any]] = []
    for document_id, key in sorted(set(before_states) | set(after_states),
                                   key=lambda pair: (pair[0], channel_orders[pair], pair[1])):
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


def validate_needs_resources_demand_sites(
    path: Path, data: dict[str, Any]
) -> set[tuple[str, str]]:
    """Gate the exact declaration sites represented by closed typed demands.

    Full tentative demand discovery is proved by the mandatory Java runtime
    replay. This package-level check deliberately does not duplicate Language
    resolution or Contracts patch execution.
    """
    expected = data["expected"]
    require(
        expected.get("attemptOutcome") == "NeedsResources",
        f"typed demand gating used for a completed fixture in {path.name}",
    )
    validate_resource_demands(path, expected)
    fixture_input = data["input"]
    documents = fixture_input["documents"]
    provider_site = ("blue-contracts/exact-node-provider", "/")
    sites: set[tuple[str, str]] = set()

    for demand in expected["resourceDemands"]:
        site = (demand["sourceDocumentId"], demand["sourcePath"])
        if demand["kind"] == "MANAGED_OCCURRENCE_EVIDENCE":
            require(
                demand["logicalCauseIdentity"]
                == fixture_input["cause"]["causeIdentity"]
                and demand["inputClosureIdentity"]
                == fixture_input["closureIdentity"]
                and demand["inputGraphGeneration"]
                == fixture_input["graphGeneration"],
                f"managed-occurrence demand is not bound to the immutable "
                f"invocation input in {path.name}: {site}",
            )
        if site == provider_site:
            require(
                demand["kind"] == "EXACT_NODE",
                f"managed-occurrence demand impersonates the reserved "
                f"exact-node provider in {path.name}",
            )
            continue
        require(
            data.get("operation") == "admit-closure",
            f"declaration-site demand appears outside ADMIT_CLOSURE in "
            f"{path.name}: {site}",
        )
        require(
            site not in sites and site[0] in documents,
            f"typed demand repeats a site or names a document outside the "
            f"admission input in {path.name}: {site}",
        )
        require(
            active_declared_path(
                documents[site[0]]["document"], site[1]
            ),
            f"typed demand does not name a declared Process Embedded site in "
            f"{path.name}: {site[0]}{site[1]}",
        )
        source_document = documents[site[0]]["document"]
        absent = object()
        try:
            supplied_value = pointer_get(source_document, site[1])
        except KeyError:
            # Some released demand fixtures discover a path from the
            # mandatory Java resulting-surface replay.  Do not reproduce its
            # patch engine here; bind every value already present in the
            # immutable input and leave absent resulting values to that replay.
            supplied_value = absent
        if supplied_value is not absent:
            require(
                isinstance(supplied_value, dict),
                f"typed demand source value is not an object in "
                f"{path.name}: {site}",
            )
            reject_mixed_blue_id_wrappers(
                path,
                supplied_value,
                f"{path.name}.resourceDemand[{site[0]}{site[1]}]",
            )
            supplied_reference = pure_blue_reference(supplied_value)
            try:
                supplied_blue_id = (
                    supplied_reference
                    if supplied_reference is not None
                    else direct_blue_id(supplied_value)
                )
            except (TypeError, ValueError) as exc:
                raise ValidationFailure(
                    f"typed demand source value has no direct exact BlueId "
                    f"in {path.name}: {site}"
                ) from exc
            require(
                demand["suppliedValueBlueId"] == supplied_blue_id,
                f"typed demand supplied-value identity does not match its "
                f"immutable source value in {path.name}: {site}",
            )
        sites.add(site)
    return sites


def validate_needs_resources_demand_self_check() -> None:
    """Pin declaration-site, immutable-context and membership gating."""
    fixture_path = CLOSURE / "c-evo-19-missing-occurrence-evidence.yaml"
    fixture = load_yaml(fixture_path)
    sites = validate_needs_resources_demand_sites(fixture_path, fixture)
    require(
        sites == {("c-evo-19-a", "/peer")},
        "NeedsResources demand-site self-check rejected the released fixture",
    )

    invalid = json.loads(json.dumps(fixture))
    demand = invalid["expected"]["resourceDemands"][0]
    demand["sourcePath"] = "/undeclared"
    demand["demandIdentity"] = closure_resource_demand_identity(demand)
    try:
        validate_needs_resources_demand_sites(fixture_path, invalid)
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "NeedsResources demand-site self-check accepted an undeclared path"
        )

    for field, invalid_value in (
        ("logicalCauseIdentity", "sha256:" + "1" * 64),
        ("inputClosureIdentity", "sha256:" + "2" * 64),
        ("inputGraphGeneration", 999),
        (
            "suppliedValueBlueId",
            fixture["input"]["documents"]["c-evo-19-a"]["blueId"],
        ),
    ):
        invalid = json.loads(json.dumps(fixture))
        demand = invalid["expected"]["resourceDemands"][0]
        demand[field] = invalid_value
        demand["demandIdentity"] = closure_resource_demand_identity(demand)
        try:
            validate_needs_resources_demand_sites(fixture_path, invalid)
        except ValidationFailure:
            pass
        else:
            raise ValidationFailure(
                f"NeedsResources demand-site self-check accepted forged "
                f"{field}"
            )

    invalid = json.loads(json.dumps(fixture))
    demand = invalid["expected"]["resourceDemands"][0]
    demand["sourceDocumentId"] = "blue-contracts/exact-node-provider"
    demand["sourcePath"] = "/"
    demand["demandIdentity"] = closure_resource_demand_identity(demand)
    try:
        validate_needs_resources_demand_sites(fixture_path, invalid)
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "NeedsResources demand-site self-check accepted a managed demand "
            "at the reserved exact-node provider site"
        )

    invalid = json.loads(json.dumps(fixture))
    invalid["input"]["documents"]["c-evo-19-a"]["document"]["peer"] = None
    try:
        validate_needs_resources_demand_sites(fixture_path, invalid)
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "NeedsResources demand-site self-check accepted a present null "
            "source value as an absent resulting path"
        )

    membership_documents = json.loads(
        json.dumps(fixture["input"]["documents"])
    )
    membership_documents["c-evo-19-b"]["initialized"] = True
    try:
        validate_admission_input_membership(
            fixture_path, membership_documents, []
        )
    except ValidationFailure:
        pass
    else:
        raise ValidationFailure(
            "NeedsResources membership self-check accepted an unreachable "
            "initialized non-Root document"
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
    provider_nodes = data.get("provider", {}).get("nodes", {})
    channels = 0
    handlers = 0
    for delivery in data["input"].get("directDeliveries", []):
        require_closure_root_address(
            delivery["scopePath"],
            delivery["activationGeneration"],
            f"direct delivery in {path.name}",
        )
        contracts = contracts_at_scope(documents[delivery["targetDocumentId"]]["document"], delivery["scopePath"])
        contract = exact_contract_source(contracts.get(delivery["channelKey"]), provider_nodes)
        require(type_blue_id(contract) == SCRIPTED_EXTERNAL, f"direct delivery does not target ScriptedExternalChannel in {path.name}: {delivery}")
        runtime_logical_key = (
            contract.get("logicalDeliveryKey")
            if isinstance(contract, dict)
            else None
        )
        require(
            isinstance(runtime_logical_key, str) and runtime_logical_key,
            f"direct delivery Channel has no exact runtime logical key in "
            f"{path.name}: {delivery}",
        )
        require(
            delivery["logicalDeliveryKey"] == runtime_logical_key,
            f"direct delivery logical key disagrees with its selected runtime "
            f"Channel in {path.name}: {delivery}",
        )
        channels += 1
    for bucket in ("handlers", "initializationHandlers"):
        for qualified in runtime.get(bucket, {}):
            require("/" in qualified, f"runtime handler key must be document/key in {path.name}: {qualified}")
            document_id, key = qualified.split("/", 1)
            require(document_id in documents, f"runtime handler document missing in {path.name}: {qualified}")
            contracts = documents[document_id]["document"].get("contracts", {})
            contract = exact_contract_source(contracts.get(key), provider_nodes)
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
    physical_fields = {"cache", "batching"}
    if physical_fields & set(provider):
        require(
            physical_fields <= set(provider),
            f"provider physical mode is incomplete in {path.name}",
        )
        require(
            len(nodes) >= 2 and len(provider["expectedLoads"]) >= 2,
            f"provider physical mode is not load-bearing in {path.name}",
        )
        require(
            set(provider["expectedLoads"]) <= set(nodes),
            f"provider physical expectedLoads are not exact backing nodes in {path.name}",
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


def admitted_and_rejected_work_evidence(
    path: Path,
    work_trace: list[dict[str, Any]],
    rejected_work: dict[str, Any] | None,
) -> list[dict[str, Any]]:
    """Return the distinct work occurrences evidenced by a completed attempt.

    A WORK-owned rejected charge can occur in either of two places.  A queue
    admission charge may reject the next candidate before it enters
    ``workTrace``; that candidate has the next ordinal.  A later charge may
    reject while the already admitted current work is executing; in that case
    the complete rejected-work record must exactly repeat the final
    ``workTrace`` row.  Sequential execution permits neither a gap nor a
    reference to an earlier admitted work.
    """
    if rejected_work is None:
        return list(work_trace)
    rejected_ordinal = rejected_work["ordinal"]
    if rejected_ordinal == len(work_trace):
        return [*work_trace, rejected_work]
    require(
        bool(work_trace)
        and rejected_ordinal == len(work_trace) - 1
        and rejected_work == work_trace[-1],
        f"rejected work ordinal/evidence mismatch in {path.name}",
    )
    return list(work_trace)


def validate_rejected_work_evidence_self_check() -> None:
    path = Path("rejected-work-self-check.yaml")
    admitted = [{"ordinal": 0, "workIdentity": "accepted"}]
    require(
        admitted_and_rejected_work_evidence(path, admitted, dict(admitted[-1]))
        == admitted,
        "accepted-current rejected-work self-check failed",
    )
    next_candidate = {"ordinal": 1, "workIdentity": "candidate"}
    require(
        admitted_and_rejected_work_evidence(path, admitted, next_candidate)
        == [*admitted, next_candidate],
        "next-candidate rejected-work self-check failed",
    )
    for invalid in (
        {"ordinal": 2, "workIdentity": "gap"},
        {"ordinal": 0, "workIdentity": "different-current"},
    ):
        try:
            admitted_and_rejected_work_evidence(path, admitted, invalid)
        except ValidationFailure:
            continue
        raise ValidationFailure(
            "invalid rejected-work evidence passed the static self-check"
        )


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
    all_work = admitted_and_rejected_work_evidence(
        path, work_trace, rejected_work
    )
    canonical_direct = canonical_direct_seed_identities(
        path, fixture_input, ordered_direct
    )
    trace = expected_gas_trace(path, expected)
    work_by_identity = {
        item["workIdentity"]: item
        for item in all_work
    }
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
            require(
                work_item.get("eventBlueId")
                == fixture_input["cause"].get("eventBlueId")
                and work_item.get("occurrenceOrdinal") == 0,
                f"external work lacks exact cause-event projection in "
                f"{path.name}: {work_item}",
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
        elif work_item["kind"] == "DOCUMENT_UPDATE":
            enqueue_matches = [
                entry
                for entry in trace
                if entry["counter"] == "closureWorkOccurrenceEnqueued"
                and entry.get("logicalPath")
                == f"work/{work_item['ordinal']}"
                and entry.get("documentId")
                == work_item["targetDocumentId"]
                and entry.get("contractKey") == work_item["channelKey"]
                and entry.get("workOccurrenceId")
                == work_item["workIdentity"]
            ]
            require(
                len(enqueue_matches) == 1,
                f"Document Update work lacks one exact enqueue in "
                f"{path.name}: work {work_item['ordinal']}",
            )
            enqueue = enqueue_matches[0]
            transition_match = re.fullmatch(
                r"transition\.(\d+)\.update\.(\d+)\.enqueue",
                enqueue.get("reason", ""),
            )
            require(
                transition_match is not None
                and work_item.get("occurrenceOrdinal")
                == int(transition_match.group(2)),
                f"Document Update work does not name its exact transition "
                f"occurrence in {path.name}: work {work_item['ordinal']}",
            )
            transition_ordinal = int(transition_match.group(1))
            transition_prefix = f"transition.{transition_ordinal}.update."
            transition_enqueues = [
                entry
                for entry in trace
                if entry["counter"] == "closureWorkOccurrenceEnqueued"
                and entry.get("reason", "").startswith(transition_prefix)
                and entry.get("reason", "").endswith(".enqueue")
            ]
            require(
                transition_enqueues,
                f"Document Update transition has no exact enqueue group in "
                f"{path.name}: transition {transition_ordinal}",
            )
            transition_start = min(
                entry["sequence"] for entry in transition_enqueues
            )
            preceding_owned = [
                entry
                for entry in trace
                if entry["sequence"] < transition_start
                and entry.get("workOccurrenceId") in valid_work_ids
            ]
            require(
                preceding_owned,
                f"Document Update transition has no proven causal work in "
                f"{path.name}: transition {transition_ordinal}",
            )
            causing_work_identity = max(
                preceding_owned, key=lambda entry: entry["sequence"]
            )["workOccurrenceId"]
            causing_work = work_by_identity[causing_work_identity]
            before_document_id = causing_work["targetDocumentId"]
            before_blue_ids = {
                fixture_input["documents"][before_document_id]["blueId"]
            }
            for delta in expected.get("subscriptionDeltas", []):
                before_subscription = delta.get("beforeSubscription")
                if not isinstance(before_subscription, dict):
                    continue
                channel_occurrence = before_subscription.get(
                    "channelOccurrence"
                )
                if (
                    isinstance(channel_occurrence, dict)
                    and channel_occurrence.get("managedDocumentId")
                    == before_document_id
                    and isinstance(delta.get("beforeDocumentBlueId"), str)
                ):
                    before_blue_ids.add(delta["beforeDocumentBlueId"])
            source_candidates = {
                domain_identity(
                    "blue-contracts-transition-occurrence/1.0",
                    {
                        "invocationIdentity": invocation_identity,
                        "transitionOrdinal": transition_ordinal,
                        "targetDocumentId": before_document_id,
                        "beforeBlueId": before_blue_id,
                        "causingWorkOccurrenceIdentity":
                            causing_work_identity,
                    },
                )
                for before_blue_id in before_blue_ids
            }
            matching_sources = [
                candidate
                for candidate in source_candidates
                if candidate == work_item["sourceOccurrenceIdentity"]
            ]
            require(
                len(matching_sources) == 1,
                f"Document Update work source is not independently proven "
                f"by exact transition evidence in {path.name}: "
                f"work {work_item['ordinal']}",
            )
            source_identity = matching_sources[0]
        elif work_item["kind"] == "INITIALIZATION":
            target = work_item["targetDocumentId"]
            incoming = [row for row in fixture_input["occurrences"]
                        if row["targetDocumentId"] == target]
            incident = [row for row in fixture_input["occurrences"]
                        if target in (row["sourceDocumentId"], row["targetDocumentId"])]
            dormant = (bool(incoming)
                       and all(row["active"] is False and row["pendingHistoricalEpoch"] is None
                               for row in incident)
                       and not fixture_input["documents"][target]["publicRoot"]
                       and not any(delivery["targetDocumentId"] == target
                                   for delivery, _ in ordered_direct))
            source_identity = fixture_input["cause"]["causeIdentity"]
            if dormant:
                enqueues = [entry for entry in trace
                            if entry["counter"] == "closureWorkOccurrenceEnqueued"
                            and entry.get("workOccurrenceId") == work_item["workIdentity"]
                            and entry.get("documentId") == target
                            and entry.get("reason") == f"work.{work_item['ordinal']}.initialization-enqueue"]
                require(len(enqueues) == 1,
                        f"dynamic initialization lacks exact enqueue in {path.name}: {target}")
                parents = {row["sourceDocumentId"] for row in incoming}
                causes = {entry["workOccurrenceId"] for entry in trace
                          if entry["counter"] == "managedOccurrenceBindingVerified"
                          and entry.get("documentId") in parents
                          and entry["sequence"] < enqueues[0]["sequence"]
                          and entry.get("workOccurrenceId") in valid_work_ids
                          and entry.get("reason") == "work."
                              + str(work_by_identity[entry["workOccurrenceId"]]["ordinal"])
                              + ".topology-change"}
                require(len(causes) == 1,
                        f"dynamic initialization lacks one proven activation cause in {path.name}: {target}")
                source_identity = next(iter(causes))
        elif work_item["kind"] == "LIFECYCLE":
            termination_enqueues = [
                entry
                for entry in trace
                if entry["counter"] == "closureWorkOccurrenceEnqueued"
                and entry.get("logicalPath")
                == f"work/{work_item['ordinal']}"
                and entry.get("documentId")
                == work_item["targetDocumentId"]
                and entry.get("contractKey") == work_item["channelKey"]
                and entry.get("workOccurrenceId")
                == work_item["workIdentity"]
                and entry.get("reason")
                == (
                    "termination.lifecycle.work."
                    f"{work_item['ordinal']}.enqueue"
                )
            ]
            if termination_enqueues:
                require(
                    len(termination_enqueues) == 1,
                    f"termination lifecycle work has ambiguous enqueue "
                    f"evidence in {path.name}: work {work_item['ordinal']}",
                )
                enqueue = termination_enqueues[0]
                requests = [
                    entry
                    for entry in trace
                    if entry["counter"] == "terminationRequested"
                    and entry["sequence"] < enqueue["sequence"]
                    and entry.get("documentId")
                    == work_item["targetDocumentId"]
                    and entry.get("workOccurrenceId") in valid_work_ids
                    and re.fullmatch(
                        r"work\.\d+\.termination-request",
                        entry.get("reason", ""),
                    )
                    is not None
                ]
                require(
                    len(requests) == 1,
                    f"termination lifecycle work lacks one exact causal "
                    f"request in {path.name}: work {work_item['ordinal']}",
                )
                request = requests[0]
                request_ordinal = int(
                    re.fullmatch(
                        r"work\.(\d+)\.termination-request",
                        request["reason"],
                    ).group(1)
                )
                causal_work = work_by_identity[
                    request["workOccurrenceId"]
                ]
                require(
                    causal_work["ordinal"] == request_ordinal
                    and causal_work["ordinal"] < work_item["ordinal"],
                    f"termination lifecycle request does not name its exact "
                    f"earlier work in {path.name}: work {work_item['ordinal']}",
                )
                source_identity = causal_work["workIdentity"]
            else:
                initialization = [item for item in all_work
                                  if item["kind"] == "INITIALIZATION"
                                  and item["targetDocumentId"] == work_item["targetDocumentId"]
                                  and item["workIdentity"] in valid_work_ids]
                require(len(initialization) == 1,
                        f"lifecycle lacks one proven initialization in {path.name}")
                source_identity = initialization[0]["sourceOccurrenceIdentity"]
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
    ordered_source_keys: list[tuple[str, str]] = []
    seen_source_keys: set[tuple[str, str]] = set()
    for delivery in sorted(
        fixture_input.get("directDeliveries", []),
        key=lambda item: (
            item["rawOccurrenceOrder"],
            item["targetDocumentId"],
            item["scopePath"],
            item["activationGeneration"],
            item["channelKey"],
            item["logicalDeliveryKey"],
        ),
    ):
        scope_identity = domain_identity(
            "blue-contracts-managed-scope-key/1.0",
            {
                "documentId": delivery["targetDocumentId"],
                "scopePath": delivery["scopePath"],
                "activationGeneration": delivery[
                    "activationGeneration"
                ],
            },
        )
        key = (scope_identity, delivery["channelKey"])
        if key in actual_changes and key not in seen_source_keys:
            ordered_source_keys.append(key)
            seen_source_keys.add(key)
    ordered_cleanup_keys = sorted(actual_changes - seen_source_keys)
    require(
        [
            (write["targetManagedScopeIdentity"], write["rawChannelKey"])
            for write in writes
        ]
        == ordered_source_keys + ordered_cleanup_keys,
        f"checkpoint receipts are not globally ordered by frozen source "
        f"occurrence then canonical cleanup in {path.name}",
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


def require_exact_finalization_gas_projection(
    path: Path,
    expected_projection: list[tuple[str, str | None]],
    actual_entries: list[dict[str, Any]],
) -> None:
    actual_projection = [
        (entry.get("reason"), entry.get("workOccurrenceId"))
        for entry in actual_entries
    ]
    require(
        actual_projection == expected_projection,
        f"gas trace finalization projection does not exactly match every "
        f"declared finalization in {path.name}",
    )


def validate_finalization_gas_projection_self_check() -> None:
    path = Path("finalization-gas-projection-self-check.yaml")
    first = ("work.0.finalization.finalization-boundary", "work-0")
    second = ("initialization-batch.finalization-boundary", None)
    entries = [
        {"reason": first[0], "workOccurrenceId": first[1]},
        {"reason": second[0]},
    ]
    require_exact_finalization_gas_projection(
        path, [first, second], entries
    )
    invalid_projections = (
        entries[:1],
        [entries[0], entries[0], entries[1]],
        list(reversed(entries)),
    )
    for invalid in invalid_projections:
        try:
            require_exact_finalization_gas_projection(
                path, [first, second], invalid
            )
        except ValidationFailure:
            continue
        raise ValidationFailure(
            "missing, duplicate, or reordered finalization gas passed the "
            "static self-check"
        )


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
    # The fixture-only Scripted Handler is the concrete executable runtime
    # selected by this closed corpus.  Its registered child-ledger schedule is
    # intentionally not part of the portable processor/semantic manifest;
    # validate its one named runtime counter here as the exact runtime profile
    # rather than treating the open runtime namespace as unmetered.
    weights["runtime"] = {"scriptedResultApplied": 1}
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
        work_finalization_prefixes = []
        for finalization in expected["tentativeFinalizations"]:
            if finalization["boundary"] != {
                "kind": "WORK",
                "afterWorkOrdinal": work_item["ordinal"],
            }:
                continue
            work_finalization_prefixes.append(
                finalization_gas_reason(path, fixture, finalization,
                                        f"work.{work_item['ordinal']}")
                .removesuffix("finalization-boundary")
            )
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
    direct_order_by_key = {
        (delivery["targetDocumentId"], delivery["channelKey"]):
            delivery["rawOccurrenceOrder"]
        for delivery in fixture_input.get("directDeliveries", [])
    }
    expected_comparison_work = sorted(
        external_work,
        key=lambda item: (
            direct_order_by_key[
                (item["targetDocumentId"], item["channelKey"])
            ],
            item["ordinal"],
        ),
    )
    require(
        [entry.get("workOccurrenceId") for entry in comparisons]
        == [item["workIdentity"] for item in expected_comparison_work],
        f"checkpoint comparisons are not in frozen raw-source order in "
        f"{path.name}",
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
                "closureWorkOccurrenceDequeued",
                "handlerCall",
                "scopeInitialization",
            }
        ]
        require(
            not queue_execution_entries
            or max(entry["sequence"] for entry in comparisons)
            < min(entry["sequence"] for entry in queue_execution_entries),
            f"Phase-B checkpoint comparisons are not complete before causal "
            f"queue execution in {path.name}",
        )
    checkpoint_write_charges = [
        entry for entry in trace if entry["counter"] == "checkpointWritten"
    ]
    checkpoint_writes = [
        entry
        for entry in checkpoint_write_charges
        if entry.get("reason", "").startswith("checkpoint-settlement.")
    ]
    if expected["status"] == "success":
        require(
            len(checkpoint_writes) == len(expected["checkpointWrites"]),
            f"checkpoint settlement gas writes do not match committed receipts "
            f"in {path.name}",
        )
    else:
        require(
            checkpoint_writes == []
            and expected["checkpointWrites"] == []
            and not any(
                entry.get("reason", "").startswith(
                    "checkpoint-settlement."
                )
                for entry in trace
            ),
            f"noncommitting attempt crossed the checkpoint-settlement barrier "
            f"in {path.name}",
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
    receipts_by_document_key: dict[tuple[str, str], dict[str, Any]] = {}
    for receipt in expected["checkpointWrites"]:
        scope_identity = receipt["targetManagedScopeIdentity"]
        document_id = scope_documents.get(scope_identity)
        require(
            document_id is not None
            and (document_id, receipt["rawChannelKey"])
            not in receipts_by_document_key,
            f"checkpoint receipts do not have unique Root/key owners in "
            f"{path.name}",
        )
        receipts_by_document_key[
            (document_id, receipt["rawChannelKey"])
        ] = receipt
    matched_receipts: set[tuple[str, str]] = set()
    source_write_addresses: list[tuple[str, str]] = []
    cleanup_write_order_keys: list[tuple[str, str]] = []
    source_write_sequences: list[int] = []
    cleanup_write_sequences: list[int] = []
    for ordinal, entry in enumerate(checkpoint_writes):
        owner_key = (entry.get("documentId"), entry.get("contractKey"))
        receipt = receipts_by_document_key.get(owner_key)
        require(
            receipt is not None and owner_key not in matched_receipts,
            f"checkpoint gas write has no unique Root/key receipt {ordinal} "
            f"in {path.name}",
        )
        matched_receipts.add(owner_key)
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
        # The committed marker write belongs to the invocation-wide
        # checkpoint-settlement barrier, not to the external-delivery work
        # item whose frozen receipt selected the value.  Preserve the causal
        # link above through scope/key matching, but do not leak a completed
        # work owner across this later global boundary.
        require(
            entry.get("workOccurrenceId") is None,
            f"checkpoint gas write has a causal work owner for receipt {ordinal} "
            f"in {path.name}",
        )
        if source_work is not None:
            require(
                receipt["afterPresent"] is True
                and re.fullmatch(
                    r"checkpoint-settlement\.\d+\.write\.\d+",
                    entry.get("reason", ""),
                )
                is not None,
                f"accepted source checkpoint write has the wrong settlement "
                f"class in {path.name}: {owner_key}",
            )
            source_write_addresses.append(owner_key)
            source_write_sequences.append(entry["sequence"])
        else:
            require(
                receipt["beforePresent"] is True
                and receipt["afterPresent"] is False
                and entry.get("reason")
                == (
                    "checkpoint-settlement.0.cleanup."
                    f"{ordinal}"
                ),
                f"orphan checkpoint cleanup is not an exact REMOVE in "
                f"{path.name}: {owner_key}",
            )
            cleanup_write_order_keys.append(
                (scope_identity, receipt["rawChannelKey"])
            )
            cleanup_write_sequences.append(entry["sequence"])
    require(
        matched_receipts == set(receipts_by_document_key),
        f"checkpoint gas writes do not cover every Root/key receipt in "
        f"{path.name}",
    )
    require(
        cleanup_write_order_keys == sorted(cleanup_write_order_keys)
        and (
            not source_write_sequences
            or not cleanup_write_sequences
            or max(source_write_sequences) < min(cleanup_write_sequences)
        ),
        f"orphan checkpoint cleanup is not in canonical post-source order in "
        f"{path.name}",
    )

    finalization_contexts: list[
        tuple[dict[str, Any], str, str, str | None]
    ] = []
    expected_finalization_gas: list[tuple[str, str | None]] = []
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
                and item["targetDocumentId"]
                in finalization["memberBlueIds"]
            ]
            component_work_ordinals = [
                item["ordinal"]
                for item in expected["workTrace"]
                if item["targetDocumentId"]
                in finalization["memberBlueIds"]
            ]
            require(
                not checkpoint_boundary_seen
                and initialization_ordinals
                and component_work_ordinals
                and boundary["afterWorkOrdinal"]
                == max(component_work_ordinals),
                f"initialization finalization is not bound to causal component "
                f"quiescence in {path.name}",
            )
            prefix = "initialization-batch"
            owner_work_id = None
        elif kind == "TERMINATION_MARKER":
            component_work_ordinals = [
                item["ordinal"]
                for item in expected["workTrace"]
                if item["targetDocumentId"]
                in finalization["memberBlueIds"]
            ]
            require(
                not checkpoint_boundary_seen
                and boundary["afterWorkOrdinal"] in work_by_ordinal
                and component_work_ordinals
                and boundary["afterWorkOrdinal"]
                == max(component_work_ordinals),
                f"termination-marker finalization is not bound to causal "
                f"component quiescence in {path.name}",
            )
            prefix = (
                "termination-marker.after-work."
                f"{boundary['afterWorkOrdinal']}"
            )
            owner_work_id = None
        elif kind == "CHECKPOINT_SETTLEMENT":
            require(
                fixture.get("oracle") is not None,
                f"route-less checkpoint finalization is not independently "
                f"reconstructable in {path.name}",
            )
            checkpoint_boundary_seen = True
            prefix = "checkpoint-settlement"
            owner_work_id = None
        else:
            raise ValidationFailure(
                f"unknown finalization boundary in {path.name}: {kind}"
            )
        reason = finalization_gas_reason(
            path, fixture, finalization, prefix
        )
        finalization_contexts.append(
            (finalization, kind, prefix, owner_work_id)
        )
        expected_finalization_gas.append((reason, owner_work_id))

    actual_finalization_entries = [
        entry
        for entry in trace
        if entry["counter"] == "tentativeComponentFinalization"
        and entry.get("reason", "").endswith(".finalization-boundary")
    ]
    require_exact_finalization_gas_projection(
        path,
        expected_finalization_gas,
        actual_finalization_entries,
    )

    finalization_positions: list[int] = []
    for context, match in zip(
        finalization_contexts,
        actual_finalization_entries,
        strict=True,
    ):
        finalization, kind, prefix, _owner_work_id = context
        if kind == "INITIALIZATION_BATCH" and fixture.get("oracle") is None:
            marker_writes = [
                entry
                for entry in trace
                if entry["counter"] == "processorMarkerWritten"
                and entry.get("reason", "").startswith(
                    "initialization-batch.marker."
                )
                and entry.get("workOccurrenceId") is None
            ]
            require(
                marker_writes
                and max(entry["sequence"] for entry in marker_writes)
                < match["sequence"],
                f"initialization component finalized before its complete "
                f"marker batch in {path.name}",
            )
        if kind == "TERMINATION_MARKER":
            marker_writes = [
                entry
                for entry in trace
                if entry["counter"] == "processorMarkerWritten"
                and entry.get("reason")
                == f"{prefix}.write"
                and entry.get("workOccurrenceId") is None
            ]
            require(
                len(marker_writes) == 1
                and marker_writes[0]["sequence"] < match["sequence"],
                f"termination component finalized before its exact marker "
                f"write in {path.name}",
            )
        if kind == "CHECKPOINT_SETTLEMENT" and checkpoint_writes:
            require(
                match["sequence"]
                > max(entry["sequence"] for entry in checkpoint_writes),
                f"checkpoint component finalized before its batched marker write in {path.name}",
            )
        finalization_positions.append(match["sequence"])
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
        expected["gasTraceIdentity"] == gas_trace_identity(trace),
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
        "managedTransitionReceiptsIdentity": expected[
            "managedTransitionReceiptsIdentity"
        ],
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
                "blue-contracts-platform-commit-companion/1.1", basis
            ),
            **basis,
        },
        f"platform commit companion mismatch in {path.name}",
    )


def validate_managed_receipt_original_cause_self_check() -> None:
    path = CLOSURE / "c-clo-23-01-a5-to-a6.yaml"
    fixture = load_yaml(path)
    validate_managed_transition_receipts(path, fixture["input"], fixture["expected"])
    wrong = json.loads(json.dumps(fixture["expected"]))
    wrong["managedTransitionReceipts"][0]["originalCauseIdentity"] = fixture["input"]["cause"]["causeIdentity"]
    try:
        validate_managed_transition_receipts(path, fixture["input"], wrong)
    except ValidationFailure:
        return
    raise ValidationFailure("managed receipt accepted wrapper identity as original source cause")


def validate_managed_transition_receipts(
    path: Path,
    fixture_input: dict[str, Any],
    expected: dict[str, Any],
) -> None:
    receipts = expected["managedTransitionReceipts"]
    require(
        isinstance(receipts, list),
        f"managed-transition receipts are not an array in {path.name}",
    )
    resulting_by_document = {
        item["documentId"]: item for item in expected["resultingDocuments"]
    }
    receipt_documents: list[str] = []
    aggregate_basis: list[dict[str, Any]] = []
    public_from_receipts: list[dict[str, Any]] = []
    admitted_gas = 0
    for ordinal, receipt in enumerate(receipts):
        require(
            receipt["transitionOrdinal"] == ordinal,
            f"managed-transition receipt ordinals are not contiguous in {path.name}",
        )
        document_id = receipt["documentId"]
        require(
            document_id in resulting_by_document
            and document_id not in receipt_documents,
            f"managed-transition receipt names an unknown or duplicate document "
            f"in {path.name}: {document_id}",
        )
        receipt_documents.append(document_id)
        result_document = resulting_by_document[document_id]
        require(
            receipt["sourceInvocationIdentity"]
            == expected["invocationIdentity"]
            and receipt["originalCauseIdentity"]
            == (fixture_input["cause"]["originalSourceCauseIdentity"]
                if fixture_input["cause"]["kind"] == "managed-revision"
                else fixture_input["cause"]["causeIdentity"])
            and receipt["beforeBlueId"] == result_document["beforeBlueId"]
            and receipt["afterBlueId"] == result_document["afterBlueId"],
            f"managed-transition receipt source/state mismatch in {path.name}: "
            f"{document_id}",
        )
        occurrence_identity = domain_identity(
            "blue-contracts-managed-transition-occurrence/1.0",
            {
                "sourceInvocationIdentity": receipt[
                    "sourceInvocationIdentity"
                ],
                "transitionOrdinal": ordinal,
                "documentId": document_id,
                "originalCauseIdentity": receipt[
                    "originalCauseIdentity"
                ],
            },
        )
        require(
            receipt["transitionOccurrenceIdentity"] == occurrence_identity,
            f"managed-transition occurrence identity mismatch in {path.name}: "
            f"{ordinal}",
        )
        events_basis: list[dict[str, Any]] = []
        previous_occurrence = -1
        for event_ordinal, event in enumerate(receipt["emittedRootEvents"]):
            require(
                event["ordinal"] == event_ordinal
                and event["sourceDocumentId"] == document_id
                and event["occurrenceOrdinal"] > previous_occurrence,
                f"managed Root event order/source mismatch in {path.name}: "
                f"{ordinal}/{event_ordinal}",
            )
            previous_occurrence = event["occurrenceOrdinal"]
            require(
                direct_blue_id(event["exactEvent"]) == event["eventBlueId"],
                f"managed Root event BlueId mismatch in {path.name}: "
                f"{ordinal}/{event_ordinal}",
            )
            require(
                event["occurrenceIdentity"]
                == domain_identity(
                    "blue-contracts-event-occurrence/1.0",
                    {
                        "invocationIdentity": expected[
                            "invocationIdentity"
                        ],
                        "eventOccurrenceOrdinal": event[
                            "occurrenceOrdinal"
                        ],
                        "eventBlueId": event["eventBlueId"],
                    },
                ),
                f"managed Root event occurrence identity mismatch in "
                f"{path.name}: {ordinal}/{event_ordinal}",
            )
            event_basis = {
                key: event[key]
                for key in (
                    "ordinal",
                    "occurrenceOrdinal",
                    "sourceDocumentId",
                    "occurrenceIdentity",
                    "eventBlueId",
                    "publicAtSource",
                )
            }
            events_basis.append(event_basis)
            if event["publicAtSource"]:
                public_from_receipts.append(event)
        events_identity = domain_identity(
            "blue-contracts-managed-root-events/1.0", events_basis
        )
        require(
            receipt["emittedRootEventsIdentity"] == events_identity,
            f"managed Root event aggregate mismatch in {path.name}: {ordinal}",
        )
        receipt_basis = {
            "sourceInvocationIdentity": receipt[
                "sourceInvocationIdentity"
            ],
            "transitionOrdinal": ordinal,
            "transitionOccurrenceIdentity": occurrence_identity,
            "documentId": document_id,
            "originalCauseIdentity": receipt["originalCauseIdentity"],
            "beforeBlueId": receipt["beforeBlueId"],
            "afterBlueId": receipt["afterBlueId"],
            "emittedRootEvents": events_basis,
            "emittedRootEventsIdentity": events_identity,
            "admittedGas": receipt["admittedGas"],
        }
        require(
            receipt["transitionReceiptIdentity"]
            == domain_identity(
                "blue-contracts-managed-document-transition-receipt/1.0",
                receipt_basis,
            ),
            f"managed-transition receipt identity mismatch in {path.name}: "
            f"{ordinal}",
        )
        require(
            receipt["beforeBlueId"] != receipt["afterBlueId"]
            or len(receipt["emittedRootEvents"]) > 0,
            f"managed-transition receipt has neither state nor event change "
            f"in {path.name}: {ordinal}",
        )
        admitted_gas += receipt["admittedGas"]
        aggregate_basis.append(
            {
                "transitionOrdinal": ordinal,
                "transitionReceiptIdentity": receipt[
                    "transitionReceiptIdentity"
                ],
            }
        )
    require(
        receipt_documents == sorted(receipt_documents),
        f"managed-transition receipts are not in canonical document order in "
        f"{path.name}",
    )
    require(
        expected["managedTransitionReceiptsIdentity"]
        == domain_identity(
            "blue-contracts-managed-document-transition-receipts/1.0",
            aggregate_basis,
        ),
        f"managed-transition receipt aggregate mismatch in {path.name}",
    )
    if receipts:
        require(
            admitted_gas == expected["totalGas"],
            f"managed-transition receipt gas partition mismatch in {path.name}",
        )
    expected_public = expected["publicEvents"]
    require(
        len(public_from_receipts) == len(expected_public),
        f"managed-transition receipt public subset size mismatch in {path.name}",
    )
    for ordinal, (event, public) in enumerate(
        zip(public_from_receipts, expected_public, strict=True)
    ):
        require(
            event["occurrenceOrdinal"] == public["eventOccurrenceOrdinal"]
            and event["sourceDocumentId"] == public["publicRootDocumentId"]
            and event["occurrenceIdentity"]
            == public["eventOccurrenceIdentity"]
            and event["eventBlueId"] == public["eventBlueId"]
            and event["exactEvent"] == public["event"],
            f"managed-transition receipt public subset mismatch in {path.name}: "
            f"{ordinal}",
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

    suspended_demand_sites: set[tuple[str, str]] = set()
    if expected["attemptOutcome"] == "NeedsResources":
        suspended_demand_sites = validate_needs_resources_demand_sites(
            path, data
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
        suspended_demand_sites=suspended_demand_sites,
        provider_nodes=data.get("provider", {}).get("nodes", {}),
    )
    if data["operation"] == "admit-closure":
        validate_admission_input_membership(
            path,
            fixture_input["documents"],
            fixture_input.get("occurrences", []),
        )
    else:
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
    require(
        [item["documentId"] for item in expected["resultingDocuments"]]
        == sorted(output_documents),
        f"resulting documents are not in canonical order in {path.name}",
    )
    require(
        set(output_documents) == set(fixture_input["documents"]),
        f"resulting document inventory mismatch in {path.name}",
    )
    output_markers = direct_processor_markers(path, output_documents, "result")
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
        provider_nodes=data.get("provider", {}).get("nodes", {}),
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
        data.get("provider", {}).get("nodes", {}),
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
    validate_managed_transition_receipts(path, fixture_input, expected)

    valid_work_ids = validate_work_trace(
        path, fixture_input, expected, ordered_direct, invocation_identity
    )
    document_steps = expected["documentStepTrace"]
    require(
        len(document_steps) == len(expected["workTrace"]),
        f"document-step/work count mismatch in {path.name}",
    )
    for step_ordinal, (step, work_item) in enumerate(
        zip(document_steps, expected["workTrace"], strict=True)
    ):
        require(
            step == {
                "stepOrdinal": step_ordinal,
                "workOrdinal": work_item["ordinal"],
                "targetDocumentId": work_item["targetDocumentId"],
                "executionRootDocumentId": work_item["targetDocumentId"],
                "scopePath": "/",
                "executionMode": "ISOLATED_DOCUMENT",
                "ambientContainingDocumentIds": [],
            },
            f"document step is not an isolated one-document execution in "
            f"{path.name}: {step_ordinal}",
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
    initialization_marker_writes = validate_initialization_marker_write_set(
        path, input_markers, output_markers, expected, data
    )
    validate_direct_marker_transition(
        path,
        fixture_input["documents"],
        output_documents,
        input_markers,
        output_markers,
        expected,
        fixture_input.get("occurrences", []),
        expected["occurrenceBindings"],
        data,
        initialization_marker_writes,
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
            and expected["publicEvents"] == []
            and expected["managedTransitionReceipts"] == [],
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


def validate_remove_readd_fixture_sequence() -> int:
    first_path = CLOSURE / "c-clo-35-00-remove-and-create-successor.yaml"
    second_path = CLOSURE / "c-clo-35-01-readd-committed-successor.yaml"
    require(first_path.is_file() and second_path.is_file(), "remove/re-add fixture pair is incomplete")
    first = load_yaml(first_path)
    second = load_yaml(second_path)
    require(first["expected"]["status"] == "success", "remove invocation failed")
    require(second["expected"]["status"] == "success", "re-add invocation failed")

    first_documents = {
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
        for item in first["expected"]["resultingDocuments"]
    }
    require(second["input"]["documents"] == first_documents, "re-add input document heads are not the exact committed predecessor")
    require(second["input"]["occurrences"] == first["expected"]["occurrenceBindings"], "re-add input occurrence set is not the exact committed predecessor")
    require(second["input"]["components"] == first["expected"]["resultingComponents"], "re-add input component set is not the exact committed predecessor")
    require(second["input"]["graphGeneration"] == first["expected"]["graphGeneration"], "re-add graph generation is not continuous")

    old = next(item for item in first["input"]["occurrences"] if item["sourceDocumentId"] == "a" and item["sourcePath"] == "/b")
    successor = next(item for item in first["expected"]["occurrenceBindings"] if item["sourceDocumentId"] == "a" and item["sourcePath"] == "/b")
    supplied = next(item for item in second["input"]["occurrences"] if item["sourceDocumentId"] == "a" and item["sourcePath"] == "/b")
    active = next(item for item in second["expected"]["occurrenceBindings"] if item["sourceDocumentId"] == "a" and item["sourcePath"] == "/b")
    require(old["activationGeneration"] == 1 and old["active"], "retired input lineage mismatch")
    require(successor["activationGeneration"] == 2 and not successor["active"], "remove did not commit one inactive generation-plus-one successor")
    require(supplied == successor, "re-add did not supply the exact committed successor")
    require(active["activationGeneration"] == 2 and active["occurrenceIdentity"] == successor["occurrenceIdentity"] and active["active"], "re-add did not activate the committed successor in place")
    require(active["occurrenceIdentity"] != old["occurrenceIdentity"], "re-add reused the retired generation-1 lineage")
    return 2


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
    validate_subscription_channel_types_self_check()
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
    constructors = load_yaml(IDENTITY_CONSTRUCTORS)
    demand_constructor = constructors.get("constructors", {}).get(
        "closureResourceDemandIdentity", {}
    )
    require(
        demand_constructor.get("domain")
        == "blue-contracts-closure-resource-demand/1.0",
        "closure resource-demand constructor domain is missing",
    )
    require(
        demand_constructor.get("value", {}).get("fields")
        == [
            "kind",
            "logicalCauseIdentity",
            "inputClosureIdentity",
            "inputGraphGeneration",
            "sourceDocumentId",
            "sourcePath",
            "processEmbeddedDeclarationIdentity",
            "suppliedValueBlueId",
            "demandOrdinal",
        ],
        "closure resource-demand constructor must expose the exact closed "
        "nine-field identity value",
    )
    require(
        set(demand_constructor.get("value", {}).get("fieldRules", {}))
        == {
            "kind",
            "logicalCauseIdentity",
            "inputClosureIdentity",
            "inputGraphGeneration",
            "sourceDocumentId",
            "sourcePath",
            "processEmbeddedDeclarationIdentity",
            "suppliedValueBlueId",
            "demandOrdinal",
        },
        "closure resource-demand constructor field rules are incomplete",
    )
    require(
        "Kind is never an ordering prefix"
        in demand_constructor.get("value", {}).get("order", ""),
        "closure resource-demand registry does not forbid kind ordering",
    )
    from rooted_release_layout import validate_release_layout
    validate_release_layout(ROOT, SPEC, require)


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
    baseline = language.get("inputImplementationBaseline")
    baseline_by_path = validate_input_implementation_baseline(baseline)
    finalizer_paths = source_paths_for_role(CYCLIC_FINALIZER)
    verifier_paths = source_paths_for_role(CYCLIC_PROOF_VERIFIER)
    expected_finalizer = domain_identity(
        "blue-language-cyclic-set-finalizer-baseline/1.0",
        {"languageSpecificationSha256": sha256_file(LANG_SPEC), "files": [baseline_by_path[path] for path in finalizer_paths]},
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
    require(
        "sourceArchiveBaseline" not in release,
        "source archive provenance must not participate in release identity",
    )
    package = verify_manifest(PACKAGE_MANIFEST, "packageIdentity")
    verify_listed_files(ROOT, package["files"])
    require(package["contractsReleaseIdentity"] == release["releaseIdentity"], "package/release binding mismatch")
    return {"registry": registry, "gas": gas, "fixtures": fixtures, "oracles": oracles, "release": release, "package": package}


def checksum_manifest_inventory(root: Path = ROOT) -> set[str]:
    return {
        path.relative_to(root).as_posix()
        for path in release_inventory_files(
            root, {"MANIFEST.sha256", "validation-output.json"}
        )
    }


def validate_checksum_manifest() -> None:
    path = ROOT / "MANIFEST.sha256"
    require(path.is_file(), "MANIFEST.sha256 missing")
    try:
        text = path.read_bytes().decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ValidationFailure("checksum manifest is not strict UTF-8") from exc
    require("\r" not in text, "checksum manifest must use LF line endings")
    require(text.endswith("\n"), "checksum manifest must end with LF")
    lines = text.splitlines()
    require(all(lines), "checksum manifest contains a blank line")
    expected = [
        target.relative_to(ROOT).as_posix()
        for target in release_inventory_files(
            ROOT, {"MANIFEST.sha256", "validation-output.json"}
        )
    ]
    listed: list[str] = []
    for line in lines:
        parts = line.split("  ", 1)
        require(len(parts) == 2, "malformed checksum manifest line")
        digest, rel = parts
        require(is_lowercase_sha256(digest), f"malformed checksum digest: {rel}")
        require(bool(rel) and "  " not in rel, "malformed checksum manifest path")
        require(rel in expected, f"unexpected checksum manifest path: {rel}")
        target = ROOT / rel
        require(target.is_file(), f"checksum manifest target missing: {rel}")
        require(sha256_file(target) == digest, f"checksum mismatch: {rel}")
        listed.append(rel)
    require(
        listed == expected,
        "checksum manifest inventory/order mismatch: "
        f"expected={expected[:5]} actual={listed[:5]}",
    )


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
        separate_document_output = run_command(["java", "-cp", str(output), "blue.coordination.closure.ReferenceSeparateDocumentExecutionMain"])
    require("BLUE_CONTRACTS_CLOSURE_TEMPLATE_SHAPE_SMOKE_OK" in contracts_output, "Contracts Java reference main did not pass")
    require("BLUE_COORDINATION_CLOSURE_TEMPLATE_SHAPE_SMOKE_OK" in coordination_output, "Coordination Java reference main did not pass")
    require("BLUE_SEPARATE_DOCUMENT_EXECUTION_TEMPLATE_SHAPE_SMOKE_OK" in separate_document_output, "Separate-document/MyOS Java reference main did not pass")
    return {"sourceFiles": len(sources), "contractsMain": contracts_output, "coordinationMain": coordination_output, "separateDocumentMain": separate_document_output}


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--source",
        type=Path,
        default=None,
        help=(
            "Optional source archive recorded by filename and content hash in "
            "the non-semantic validation receipt"
        ),
    )
    parser.add_argument("--write-output", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    def progress(label: str) -> None:
        if args.verbose:
            print(f"VALIDATING {label}", file=sys.stderr, flush=True)

    progress("static laws")
    validate_cyclic_proof_selection_self_check()
    validate_rejected_work_evidence_self_check()
    validate_finalization_gas_projection_self_check()
    validate_direct_marker_transition_self_check()
    validate_intermediate_cyclic_marker_transition_self_check()
    validate_rollback_marker_prefix_self_check()
    validate_rejected_work_marker_causality_self_check()
    validate_needs_resources_demand_self_check()
    validate_managed_receipt_original_cause_self_check()
    validate_static_package_laws()
    progress("manifests")
    manifests = validate_manifests()
    progress("checksums")
    validate_checksum_manifest()
    ordinary, closure = fixture_files()
    require(len(ordinary) == 197, "final ordinary fixture count must be 197")
    require(len(closure) == 98, "final closure fixture count must be 98")
    require(
        len(ordinary) + len(closure) == 295,
        "final Contracts fixture count must be 295",
    )
    actual_full_lifecycle_names = {
        path.name
        for path in closure
        if path.name.startswith(("fl-adm-", "c-evo-"))
    }
    require(
        actual_full_lifecycle_names == FULL_LIFECYCLE_FIXTURE_NAMES,
        "final full-lifecycle fixture inventory must contain exactly the "
        "twenty-six released FL-ADM/C-EVO cases",
    )
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
    progress("runtime surface vectors")
    run_command([
        sys.executable,
        str(ROOT / "tools/test_runtime_surface_projection.py"),
    ])
    counts = {"occurrences": 0, "channels": 0, "handlers": 0}
    for index, path in enumerate(closure):
        if args.verbose and index % 10 == 0:
            progress(f"closure semantics {index + 1}/{len(closure)}")
        current = validate_closure_fixture(path)
        for key, value in current.items():
            counts[key] += value
    progress("managed-revision sequence")
    managed_revision_fixtures = validate_managed_revision_fixture_sequence()
    progress("remove/re-add sequence")
    remove_readd_fixtures = validate_remove_readd_fixture_sequence()
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
    progress("rooted companion package/checker/model validation")
    from rooted_release_layout import validate_rooted_companion_checks
    rooted_companion = validate_rooted_companion_checks(ROOT, run_command, require)
    progress("Java templates")
    java = validate_java_templates()
    progress("source archive provenance")
    try:
        source = source_archive_provenance(args.source)
    except SourceArchiveProvenanceError as exc:
        raise ValidationFailure(str(exc)) from exc

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
        "closureVectors": len([v for v in vectors if is_closure_vector(v)]),
        "oracleFiles": manifests["oracles"]["oracleCount"],
        "oracleStages": reference["oracleStages"],
        "blueIdsChecked": blue_ids,
        "documentIdsChecked": document_ids,
        "occurrencesChecked": counts["occurrences"],
        "directChannelsChecked": counts["channels"],
        "runtimeHandlersChecked": counts["handlers"],
        "managedRevisionSequenceFixtures": managed_revision_fixtures,
        "removeReaddFixtures": remove_readd_fixtures,
        "javaTemplates": java,
        "rootedCompanion": rooted_companion,
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
