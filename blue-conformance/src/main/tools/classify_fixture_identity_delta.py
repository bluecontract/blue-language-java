#!/usr/bin/env python3
"""Classify deterministic Contracts package changes between two candidates."""
from __future__ import annotations

from argparse import ArgumentParser
from collections import Counter
from copy import deepcopy
from pathlib import Path
import hashlib
import json
import os
import re
import sys
import tempfile
from typing import Any, Iterable

sys.dont_write_bytecode = True

import yaml

from gas_reference import gas_trace_identity
from implementation_baseline import IMPLEMENTATION_BASELINE_SOURCE_PATHS
from jcs import dumps as jcs_dumps
from package_hygiene import release_inventory_files


SPEC = "spec-identity-rebind"
INVOCATION = "invocation-identity-rebind"
TRACE = "work-event-trace-identity-rebind"
SEMANTIC = "actual-semantic-state-result-change"
FORMATTING = "fixture-byte-only-formatting"
UNEXPECTED = "unexpected"
CATEGORIES = (SPEC, INVOCATION, TRACE, SEMANTIC, FORMATTING, UNEXPECTED)
LOWERCASE_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

IMPLEMENTATION_BASELINE_INPUT_PATH = (
    Path(__file__).resolve().parent
    / "migration"
    / "classify-fixture-identity-delta-implementation-baseline.json"
)
IMPLEMENTATION_BASELINE_INPUT_SCHEMA = (
    "blue-classifier-implementation-baseline/1.0"
)
IMPLEMENTATION_BASELINE_INPUT_SOURCE = (
    "blue-conformance/src/main/resources/blue-contracts-closure-1.0/"
    "release-manifest.yaml#/languageDependency/inputImplementationBaseline"
)
IMPLEMENTATION_BASELINE_INPUT_PROVENANCE = (
    "42e407c914c7813f327a0ed62e7599bab06a0ecb"
)
IMPLEMENTATION_BASELINE_INPUT_SHA256 = (
    "c4220da1d2f0934c69768623f73c4bdbbd7f17bf0715a3231d82dd6cf2ec2d9d"
)


def parse_implementation_baseline_input(data: bytes) -> dict[str, Any]:
    """Load the exact reviewed pre-transition implementation snapshot."""
    def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(
                    "duplicate key in classifier implementation baseline: "
                    + key
                )
            result[key] = value
        return result

    try:
        document = json.loads(
            data.decode("utf-8"), object_pairs_hook=strict_object
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ValueError(
            "invalid classifier implementation baseline JSON"
        ) from exception
    if not isinstance(document, dict) or set(document) != {
        "schema",
        "sourcePath",
        "provenanceCommit",
        "files",
    }:
        raise ValueError(
            "classifier implementation baseline must have the exact reviewed shape"
        )
    if document["schema"] != IMPLEMENTATION_BASELINE_INPUT_SCHEMA:
        raise ValueError("unexpected classifier implementation baseline schema")
    if document["sourcePath"] != IMPLEMENTATION_BASELINE_INPUT_SOURCE:
        raise ValueError("unexpected classifier implementation baseline source path")
    if (
        document["provenanceCommit"]
        != IMPLEMENTATION_BASELINE_INPUT_PROVENANCE
    ):
        raise ValueError(
            "unexpected classifier implementation baseline provenance commit"
        )
    files = document["files"]
    if not isinstance(files, list) or len(files) != 16:
        raise ValueError(
            "classifier implementation baseline must contain exactly 16 files"
        )
    paths: set[str] = set()
    for entry in files:
        if not isinstance(entry, dict) or set(entry) != {"path", "sha256"}:
            raise ValueError(
                "classifier implementation baseline file must contain path and sha256"
            )
        path = entry["path"]
        digest = entry["sha256"]
        if not isinstance(path, str) or not path or path in paths:
            raise ValueError(
                "classifier implementation baseline paths must be unique and non-empty"
            )
        if (
            not isinstance(digest, str)
            or LOWERCASE_SHA256_RE.fullmatch(digest) is None
        ):
            raise ValueError(
                "classifier implementation baseline sha256 must be lowercase 64-hex"
            )
        paths.add(path)
    if hashlib.sha256(data).hexdigest() != IMPLEMENTATION_BASELINE_INPUT_SHA256:
        raise ValueError(
            "classifier implementation baseline bytes do not match the reviewed input"
        )
    return document

APPROVED_SUPPORT_DOCUMENTS = frozenset(
    {
        "fixtures/README.md",
        "fixtures/HARNESS.md",
        "fixtures/closure/README.md",
    }
)
INVOCATION_FIELDS = frozenset(
    {
        "invocationIdentity",
        "inputClosureIdentity",
        "outputClosureIdentity",
        "occurrenceBindingSetIdentity",
        "graphChangesIdentity",
        "subscriptionDeltasIdentity",
        "checkpointWritesIdentity",
        "publicEventsIdentity",
        "commitCompanionIdentity",
        "companionIdentity",
        "emittedRootEventsIdentity",
        "managedTransitionReceiptsIdentity",
        "rejectedChargeIdentity",
        "sourceInvocationIdentity",
        "transitionReceiptIdentity",
    }
)
TRACE_FIELDS = frozenset(
    {
        "gasTraceIdentity",
        "workTraceIdentity",
        "documentStepTraceIdentity",
        "workOccurrenceId",
        "workOccurrenceIdentity",
        "workIdentity",
        "eventOccurrenceId",
        "eventOccurrenceIdentity",
        "occurrenceIdentity",
        "rootEventOccurrenceId",
        "sourceOccurrenceIdentity",
        "transitionOccurrenceIdentity",
    }
)
ADMISSION_CONTEXT_FIELDS = frozenset(
    {"scopePath", "activationGeneration", "componentGeneration"}
)
SPEC_FIELDS = frozenset(
    {
        "specificationSha256",
        "specificationIdentity",
        "releaseIdentity",
        "packageIdentity",
        "fixturePackageIdentity",
        "registryPackageIdentity",
        "oraclePackageIdentity",
        "contractsReleaseIdentity",
        "contractsSpecificationIdentity",
        "blueLanguageSpecificationIdentity",
        "cyclicFinalizerIdentity",
        "cyclicProofVerifierIdentity",
    }
)

# This classifier compares the accepted 03db5ee package with the bounded
# dynamic-contract-evolution release.  Added fixtures have no "before" value,
# so their canonical structured content is pinned here.  This is deliberately
# stricter than accepting a filename prefix: changing status, gas, events,
# checkpoints, or any other fixture evidence requires a new reviewed release.
APPROVED_CEVO_FIXTURE_IDENTITIES = {
    "fixtures/evo/c-evo-01.yaml": "720bff1e2ae3edbf3ba56265b408f0be802ee2ef39dada2e897b491b7fecaf0a",
    "fixtures/evo/c-evo-02.yaml": "fe117d8d31132da476ac8368512fbe7aadc0b0b054d21aeb9c991304fe8620e1",
    "fixtures/evo/c-evo-03.yaml": "f8510758436577219438aae69e91d6a9756c33de0dfbedd404de2cf67229f2a5",
    "fixtures/evo/c-evo-04.yaml": "7e7ccf0ca63ddcb0acb53c5cba7af5d59f52e4bf2707f10a09727085899af62e",
    "fixtures/evo/c-evo-05.yaml": "347612b325b46e36bf18c43721988e57c07e596e324ae911536e5b0e6a0d9ae4",
    "fixtures/evo/c-evo-06.yaml": "c7195e10a1b438f8cf61aca931aeb31baa086d3c4cd2f40b1376636a86138819",
    "fixtures/evo/c-evo-07-checkpoint.yaml": "b41fa87114c12349e7ac6f420ca2b72e58e47e01f93d548a4535797c1e833bb5",
    "fixtures/evo/c-evo-07-initialized.yaml": "b0ec1ea7a91d7db161c79af06b738432a44dbc12156c8930a6ab646c5b2e166c",
    "fixtures/evo/c-evo-07-terminated.yaml": "801f6c3d341a185ef03e1cb711a2fe4026ad1c0c36ac9f26ff9752b55f06c8c2",
    "fixtures/evo/c-evo-08.yaml": "a532b91d9bd35fb46432a2437fa5397491bef9ae7cc6492b5dfcdcb636f860be",
    "fixtures/evo/c-evo-09.yaml": "57c1ebceba0600340eee675cf1ab2925c4fe7330b05f19c600f72b55fed22546",
    "fixtures/evo/c-evo-10.yaml": "cc1d445e4b2773164d2468948e531529c7c23369f4c6be51e5be4fec70febfe0",
    "fixtures/evo/c-evo-14.yaml": "4005bbc00e4822a1fa040d5f21ac12f537dd97a918c049eb788b07009e2cc1e3",
    "fixtures/evo/c-evo-15.yaml": "4f97850da9597a5309e6d9d624b04f6c8930299ee25c8b6b2b2038cc35be8e83",
    "fixtures/evo/c-evo-16.yaml": "2a3949c61b15be5e47f5c23465c8923bb99ed80b827e8023b85fd091b8b9e70b",
    "fixtures/evo/c-evo-17.yaml": "5a9e57473811a43b4f477e307c57737810af82517b7f13e7d9b60c8632909202",
    "fixtures/closure/c-evo-18-missing-exact-node.yaml": "171f6c844691da5d454b187a11d91668a339104bc941cf96771d08a239c19b9b",
    "fixtures/closure/c-evo-19-missing-occurrence-evidence.yaml": "df6f15e14677b2955606bfb56229a1802b062195636d502f770740f3c6c5756e",
    "fixtures/closure/c-evo-20-canonical-demand-order.yaml": "e0f1640c2ae1f6e5586d5dff58105810b80b901cf9ffa2c896e906e8311b41b3",
    "fixtures/closure/c-evo-21-retry-determinism-missing-first.yaml": "2ca0af7b83b73940e8b3cc8ed8befbd40b92d919b7a1c212b786aae228df4f39",
    "fixtures/closure/c-evo-21-retry-determinism-missing-repeat.yaml": "cbddadbcf5b3981c5f93affc2298c2887a2f08a829c32cdb041d85091a5809eb",
    "fixtures/closure/c-evo-21-retry-determinism-resolved-first.yaml": "1a7fddf477c76b723e2fd868273d74760d93b14914571b2f35fcd237098377be",
    "fixtures/closure/c-evo-21-retry-determinism-resolved-repeat.yaml": "36626d53116307233f5a5e2aeb6909250b8c8aa17a2497cdbf6ca84dffa4603e",
    "fixtures/closure/c-evo-22-low-gas-expanded-evidence-demand.yaml": "189466429083b47f7923f9a61765b3545fb3f63ddea84e881b8c876bfd8e0881",
    "fixtures/closure/c-evo-22-low-gas-expanded-evidence-expanded-low-gas-repeat.yaml": "2f14181f25076c59430f5ffadddc620356836240883e416e22afb749d441aa8c",
    "fixtures/closure/c-evo-22-low-gas-expanded-evidence-expanded-low-gas.yaml": "1c4c6603bbcd92cef9f34ba4e44bdbd123bad678d5c8da7b18442f6791c494b5",
    "fixtures/closure/c-evo-23-automatic-explicit-retry-parity-automatic-demand.yaml": "545f9dc5aeef1cdb8e3362c1a571f22772879fc4789cec59edc476b58f568584",
    "fixtures/closure/c-evo-23-automatic-explicit-retry-parity-automatic-resolved.yaml": "a99dbdfd962cd0c902eb722209086161b8e5fddec7eab884fb6c55a7e05f12d4",
    "fixtures/closure/c-evo-23-automatic-explicit-retry-parity-explicit-resolved.yaml": "70921a351dc2f981c940d6d8c22b30e9bfb54aec2e038521688af1e57c5d9639",
}

APPROVED_CONTRACTS_SPECIFICATION_SHA256 = (
    "99445f8ad407c146804ae3bcad1e060a2c7bac3d32492808ea6fd7caf2fe7bdd"
)
APPROVED_REMOVED_SOURCE_ARCHIVE_BASELINE = {
    "expectedSha256": (
        "7be5116d8e7a64bccf471c11a93127d4924a36686e23bbbf634fc0713d6d33c9"
    ),
    "filenameIsNonNormative": True,
}
APPROVED_LANGUAGE_DEPENDENCY_TRANSITION = {
    "specificationSha256": (
        "019a436c6266400710bca7f49905c2c53d62434762850236ca0f86d99dff1b37",
        "0dc2942bfbabbe994debb1038fe4d1c7a4ddefa9b25bc26cb0f68a7380cfbd06",
    ),
    "cyclicSetFinalizerBaselineIdentity": (
        "sha256:d71ec19247a32f7f40107f512e4eb567b4cb41ae8e73c16d2ebe10b0e8517c76",
        "sha256:dfaee4ddc131ec000bd1e46692ffb6a840bd645f3b9db79a3babd684cad468fb",
    ),
    "cyclicSetProofVerifierBaselineIdentity": (
        "sha256:0768d22420c5bb01109861eb9e090b758aa66b25c2df7b4708dff36a969cefdd",
        "sha256:2eeab32b181001a872bb6f91c65405b7612cdc612bb381e32d57cf72fb570f53",
    ),
}

# The old release carried this exact, order-sensitive sixteen-file snapshot.
# It is strict review data rather than an active source binding, so its bytes
# live in the migration baseline file and are pinned above.  The new release
# carries the entire closed six-module inventory.  Its 722 digests are
# approved through one canonical aggregate.  Array order remains significant.
APPROVED_IMPLEMENTATION_BASELINE_DOCUMENT = (
    parse_implementation_baseline_input(
        IMPLEMENTATION_BASELINE_INPUT_PATH.read_bytes()
    )
)
APPROVED_IMPLEMENTATION_BASELINE_BEFORE = tuple(
    (entry["path"], entry["sha256"])
    for entry in APPROVED_IMPLEMENTATION_BASELINE_DOCUMENT["files"]
)
APPROVED_IMPLEMENTATION_BASELINE_BEFORE_ORDER = tuple(
    path for path, _ in APPROVED_IMPLEMENTATION_BASELINE_BEFORE
)
APPROVED_IMPLEMENTATION_BASELINE_BEFORE_BY_PATH = dict(
    APPROVED_IMPLEMENTATION_BASELINE_BEFORE
)
IMPLEMENTATION_BASELINE_AGGREGATE_DOMAIN = (
    "blue-contracts-implementation-source-baseline/1.0"
)


def implementation_baseline_aggregate_identity(
    order: tuple[str, ...],
    by_path: dict[str, str],
) -> str:
    """Bind exact path order and digests into one reviewed approval identity."""
    value = {
        "domain": IMPLEMENTATION_BASELINE_AGGREGATE_DOMAIN,
        "files": [
            {"path": path, "sha256": by_path[path]} for path in order
        ],
    }
    return "sha256:" + hashlib.sha256(jcs_dumps(value)).hexdigest()


# Recompute deliberately when production source bytes or inventory membership
# changes.  This pin approves one exact after-snapshot, not arbitrary hashes.
APPROVED_IMPLEMENTATION_BASELINE_AGGREGATE_IDENTITY = (
    "sha256:c4f2d27f85b5ca92f9e15fe4340813c1beda9daf924969da6dcae5722f107842"
)

if any(
    LOWERCASE_SHA256_RE.fullmatch(digest) is None
    for _, digest in APPROVED_IMPLEMENTATION_BASELINE_BEFORE
):
    raise RuntimeError(
        "approved pre-transition implementation baseline has a malformed digest"
    )
if len(APPROVED_IMPLEMENTATION_BASELINE_BEFORE_BY_PATH) != len(
    APPROVED_IMPLEMENTATION_BASELINE_BEFORE
):
    raise RuntimeError(
        "approved pre-transition implementation baseline has duplicate paths"
    )
if not APPROVED_IMPLEMENTATION_BASELINE_AGGREGATE_IDENTITY.startswith(
    "sha256:"
) or LOWERCASE_SHA256_RE.fullmatch(
    APPROVED_IMPLEMENTATION_BASELINE_AGGREGATE_IDENTITY.removeprefix(
        "sha256:"
    )
) is None:
    raise RuntimeError(
        "approved implementation-baseline aggregate identity is malformed"
    )

APPROVED_SCRIPTED_OPERATION_SHA256 = (
    "804c979ff5d97065aa3cbd75bd6a699a3ee42da139246d6ef93006473c64837d"
)

# The two larger normative documents are pinned by canonical structured
# identity.  List ordering remains semantic, which protects constructor field
# order and the schema's oneOf ordering while ignoring YAML presentation.
APPROVED_STRUCTURED_TRANSITIONS = {
    "fixtures/closure-fixture-schema.yaml": (
        "eeb167d13cc2088d3d5760613e2d1eb395fe0d0a354b8f9c511952cafb35f693",
        "ab5fc2e2484d5926c2238f1e79573db618f361395766396e635d3c05ea4bfb2f",
    ),
    "identity-constructors.yaml": (
        "d4fd3263fcf40c3dbae2a11f5e05573b9b88063b2ef33c9065e5dc481fc73f89",
        "2191db64b2258378e53324b8c02f13c176a94d0735bd0bfff7951d64adb6a9f1",
    ),
}

GENERATED_RELEASE_MANIFESTS = frozenset(
    {
        "fixtures/manifest.yaml",
        "fixtures/vector-coverage.yaml",
        "registry/manifest.yaml",
        "release-manifest.yaml",
    }
)

APPROVED_VECTOR_ALIASES = {
    "fixtures/closure/c-clo-02-dynamic-finite-cycle.yaml": "C-EVO-13",
    "fixtures/closure/c-clo-11-split-into-two-cycles.yaml": "C-EVO-12",
    "fixtures/closure/c-clo-12-frozen-edge-removal.yaml": "C-EVO-11",
}

APPROVED_EXACT_NODE_DEMAND = {
    "kind": "EXACT_NODE",
    "demandIdentity": "sha256:8c1e312399e706565572986d131bdf829e57666285b6486fb931cfe9dd41920f",
    "sourceDocumentId": "blue-contracts/exact-node-provider",
    "sourcePath": "/",
    "suppliedValueBlueId": "2kpAUcknjsY6eoij8s6u3vKHKNzFBWyweaekTpMkK7E8",
    "blueId": "2kpAUcknjsY6eoij8s6u3vKHKNzFBWyweaekTpMkK7E8",
    "logicalPath": "/",
}

APPROVED_PROJECTION_ADDITIONS = {
    "commit.newIntervals.0.channelKey": ("scalar-or-node", "Contract key of the first newly activated subscription interval."),
    "commit.retiredIntervals.0.channelKey": ("scalar-or-node", "Contract key of the first retired subscription interval."),
    "input.root.contracts.initialized": ("value", "Exact protected initialization marker in the input Root."),
    "input.root.contracts.initialized.directBlueId": ("scalar-or-node", "Direct BlueId of the protected input initialization marker."),
    "input.root.contracts.initialized.document": ("value", "Exact pre-initialization witness document in the protected input marker; inline and pure-reference forms compare by canonical Blue identity."),
    "result.document.child.payload": ("value", "Passive child payload retained across Process Embedded surface evolution."),
    "result.document.contracts.embedded": ("value", "Exact resulting Process Embedded contract, or absence after removal."),
    "result.document.contracts.in": ("value", "Exact input Channel contract, or absence after whole-surface replacement."),
    "result.document.contracts.initialized.directBlueId": ("scalar-or-node", "Direct BlueId of the protected resulting initialization marker."),
    "result.document.contracts.newOperation": ("value", "Scripted Operation introduced by the current transition for later entries."),
    "result.document.contracts.next.subscriptionKey": ("scalar-or-node", "Subscription key on a Channel introduced by whole contracts replacement."),
    "result.document.contracts.operation": ("value", "Distinct Scripted Operation contract, or absence after self-removal."),
    "result.document.contracts.replaceContracts": ("value", "Whole-contracts replacement Workflow, or absence after its transition."),
    "result.document.contracts.requiredWorkflow": ("value", "Subtype-required Workflow, or absence after successful type widening."),
    "result.document.contracts.terminated": ("value", "Exact protected termination marker, or absence after a rejected application write."),
    "result.document.contracts.workflow": ("value", "Selected Workflow contract, or absence after self-removal."),
}
class ClassificationFailure(RuntimeError):
    """Raised for malformed inputs or unsafe report destinations."""


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def structured_identity(value: Any) -> str:
    """Return a presentation-independent identity for JSON-shaped YAML."""
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def package_identity(value: dict[str, Any], field: str) -> str:
    normalized = deepcopy(value)
    normalized[field] = None
    return "sha256:" + hashlib.sha256(jcs_dumps(normalized)).hexdigest()


def package_files(root: Path) -> dict[str, Path]:
    return {
        path.relative_to(root).as_posix(): path
        for path in release_inventory_files(root)
    }


def load_structured(path: Path) -> Any | None:
    if path.suffix.casefold() in {".yaml", ".yml"}:
        return yaml.safe_load(path.read_text(encoding="utf-8"))
    if path.suffix.casefold() == ".json":
        return json.loads(path.read_text(encoding="utf-8"))
    return None


def pointer(parts: Iterable[str]) -> str:
    encoded = [part.replace("~", "~0").replace("/", "~1") for part in parts]
    return "/" + "/".join(encoded) if encoded else ""


def leaf_differences(before: Any, after: Any, parts: tuple[str, ...] = ()) -> list[dict[str, Any]]:
    if type(before) is not type(after):
        return [{"path": pointer(parts), "before": before, "after": after}]
    if isinstance(before, dict):
        result: list[dict[str, Any]] = []
        for key in sorted(set(before) | set(after), key=str):
            child = parts + (str(key),)
            if key not in before:
                result.append({"path": pointer(child), "before": None, "after": after[key]})
            elif key not in after:
                result.append({"path": pointer(child), "before": before[key], "after": None})
            else:
                result.extend(leaf_differences(before[key], after[key], child))
        return result
    if isinstance(before, list):
        result = []
        for index in range(max(len(before), len(after))):
            child = parts + (str(index),)
            if index >= len(before):
                result.append({"path": pointer(child), "before": None, "after": after[index]})
            elif index >= len(after):
                result.append({"path": pointer(child), "before": before[index], "after": None})
            else:
                result.extend(leaf_differences(before[index], after[index], child))
        return result
    if before != after:
        return [{"path": pointer(parts), "before": before, "after": after}]
    return []


def path_parts(json_pointer: str) -> tuple[str, ...]:
    if not json_pointer:
        return ()
    return tuple(
        part.replace("~1", "/").replace("~0", "~")
        for part in json_pointer[1:].split("/")
    )


def identity_category(json_pointer: str) -> str | None:
    parts = path_parts(json_pointer)
    fields = set(parts)
    leaf = parts[-1] if parts else ""
    if fields & INVOCATION_FIELDS:
        return INVOCATION
    if fields & TRACE_FIELDS:
        return TRACE
    if fields & SPEC_FIELDS:
        return SPEC
    if leaf == "sha256" and (
        "specificationDocument" in fields or "languageDependency" in fields
    ):
        return SPEC
    return None


def fixture_operation(value: Any | None) -> str | None:
    return value.get("operation") if isinstance(value, dict) else None


def approved_cevo_added_fixture(relative: str, path: Path) -> bool:
    expected_identity = APPROVED_CEVO_FIXTURE_IDENTITIES.get(relative)
    if expected_identity is None:
        return False
    value = load_structured(path)
    if not isinstance(value, dict):
        return False
    expected_id = Path(relative).stem
    expected_vector = f"C-EVO-{expected_id[6:8]}"
    expected_operation = (
        "process" if relative.startswith("fixtures/evo/") else "admit-closure"
    )
    return (
        value.get("id") == expected_id
        and value.get("vectors") == [expected_vector]
        and value.get("operation") == expected_operation
        and structured_identity(value) == expected_identity
    )


def approved_identity_rebinding_transition(
    relative: str,
    before_value: Any,
    after_value: Any,
) -> bool:
    """Accept only already-classified identity changes around an exact delta."""
    return all(
        identity_category(difference["path"]) is not None
        or approved_admission_context_removal(
            relative,
            before_value,
            after_value,
            difference["path"],
        )
        for difference in leaf_differences(before_value, after_value)
    )


def approved_vector_alias_transition(
    relative: str,
    before_value: Any,
    after_value: Any,
) -> bool:
    alias = APPROVED_VECTOR_ALIASES.get(relative)
    if alias is None or not isinstance(after_value, dict):
        return False
    normalized = deepcopy(after_value)
    vectors = normalized.get("vectors")
    if not isinstance(vectors, list) or not vectors or vectors[-1] != alias:
        return False
    vectors.pop()
    return approved_identity_rebinding_transition(
        relative, before_value, normalized
    )


def approved_exact_node_demand_transition(
    relative: str,
    before_value: Any,
    after_value: Any,
) -> bool:
    if relative != (
        "fixtures/closure/c-clo-22-a10-attach-a5-needs-resources.yaml"
    ) or not isinstance(after_value, dict):
        return False
    normalized = deepcopy(after_value)
    try:
        expected = normalized["expected"]
        demands = expected.pop("resourceDemands")
    except (KeyError, TypeError):
        return False
    return demands == [APPROVED_EXACT_NODE_DEMAND] and (
        approved_identity_rebinding_transition(
            relative, before_value, normalized
        )
    )


def approved_fixture_schema_transition(
    relative: str,
    before_value: Any,
    after_value: Any,
) -> bool:
    if relative != "fixtures/fixture-schema.yaml" or not isinstance(
        after_value, dict
    ):
        return False
    normalized = deepcopy(after_value)
    try:
        properties = normalized["$defs"]["runtime"]["properties"]
        addition = properties.pop("generalizationSubtypeContracts")
    except (KeyError, TypeError):
        return False
    return addition == {"$ref": "#/$defs/blueValue"} and normalized == before_value


def projection_entries(value: Any) -> tuple[dict[str, dict[str, Any]], Any] | None:
    if not isinstance(value, dict) or not isinstance(value.get("entries"), list):
        return None
    entries: dict[str, dict[str, Any]] = {}
    for entry in value["entries"]:
        if (
            not isinstance(entry, dict)
            or set(entry) not in (
                {"path", "definition"},
                {"path", "type", "definition"},
            )
            or not isinstance(entry.get("path"), str)
            or entry["path"] in entries
        ):
            return None
        entries[entry["path"]] = entry
    envelope = deepcopy(value)
    envelope.pop("entries")
    return entries, envelope


def approved_projection_catalog_transition(
    relative: str,
    before_value: Any,
    after_value: Any,
) -> bool:
    if relative != "fixtures/projection-catalog.yaml":
        return False
    before_projection = projection_entries(before_value)
    after_projection = projection_entries(after_value)
    if before_projection is None or after_projection is None:
        return False
    before_entries, before_envelope = before_projection
    after_entries, after_envelope = after_projection
    if before_envelope != after_envelope:
        return False
    if set(after_entries) != set(before_entries) | set(APPROVED_PROJECTION_ADDITIONS):
        return False
    if any(after_entries[path] != entry for path, entry in before_entries.items()):
        return False
    for path, (value_type, definition) in APPROVED_PROJECTION_ADDITIONS.items():
        if after_entries[path] != {
            "path": path,
            "type": value_type,
            "definition": definition,
        }:
            return False
    return True


def approved_pinned_structured_transition(
    relative: str,
    before_value: Any,
    after_value: Any,
) -> bool:
    transition = APPROVED_STRUCTURED_TRANSITIONS.get(relative)
    return transition == (
        structured_identity(before_value),
        structured_identity(after_value),
    )


def approved_cevo_structured_transition(
    relative: str,
    before_value: Any,
    after_value: Any,
) -> bool:
    return (
        approved_vector_alias_transition(relative, before_value, after_value)
        or approved_exact_node_demand_transition(
            relative, before_value, after_value
        )
        or approved_fixture_schema_transition(relative, before_value, after_value)
        or approved_projection_catalog_transition(
            relative, before_value, after_value
        )
        or approved_pinned_structured_transition(
            relative, before_value, after_value
        )
    )


def bounded_cevo_transition_path(relative: str) -> bool:
    return (
        relative in APPROVED_VECTOR_ALIASES
        or relative
        == "fixtures/closure/c-clo-22-a10-attach-a5-needs-resources.yaml"
        or relative in {
            "fixtures/fixture-schema.yaml",
            "fixtures/projection-catalog.yaml",
        }
        or relative in APPROVED_STRUCTURED_TRANSITIONS
    )


def is_closure_vector(vector: str) -> bool:
    if vector.startswith(("C-CLO-", "FL-ADM-")):
        return True
    return vector.startswith("C-EVO-") and vector[6:] in {
        "11", "12", "13", "18", "19", "20", "21", "22", "23",
    }


def fixture_manifest_entry(
    base: Path,
    path: Path,
    role: str,
    vectors: list[str] | None = None,
) -> dict[str, Any]:
    entry: dict[str, Any] = {
        "path": path.relative_to(base).as_posix(),
        "role": role,
        "sha256": sha256(path),
        "bytes": path.stat().st_size,
    }
    if vectors:
        entry["vectors"] = vectors
    return entry


def expected_fixture_release_documents(
    package_root: Path,
    registry_package_identity: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    fixture_root = package_root / "fixtures"
    ignored = {
        "manifest.yaml",
        "vector-coverage.yaml",
        "projection-catalog.yaml",
        "fixture-schema.yaml",
        "closure-fixture-schema.yaml",
    }
    ordinary: list[tuple[Path, dict[str, Any]]] = []
    closure: list[tuple[Path, dict[str, Any]]] = []
    for path in sorted(fixture_root.rglob("*.yaml")):
        if path.parent == fixture_root / "closure/traces" or path.name in ignored:
            continue
        value = load_structured(path)
        if not isinstance(value, dict) or "vectors" not in value:
            continue
        vectors = value["vectors"]
        if not isinstance(vectors, list) or not all(
            isinstance(vector, str) and vector for vector in vectors
        ):
            raise ClassificationFailure(
                f"invalid executable fixture vectors: {path}"
            )
        item = (path, value)
        (closure if path.parent.name == "closure" else ordinary).append(item)

    vector_map: dict[str, list[str]] = {}
    for path, value in ordinary + closure:
        relative = path.relative_to(fixture_root).as_posix()
        for vector in value["vectors"]:
            vector_map.setdefault(vector, []).append(relative)
    for paths in vector_map.values():
        paths.sort()
    vector_coverage = {
        "specification": "blue-contracts/1.0",
        "vectorCount": len(vector_map),
        "ordinaryVectorCount": sum(
            not is_closure_vector(vector) for vector in vector_map
        ),
        "closureVectorCount": sum(
            is_closure_vector(vector) for vector in vector_map
        ),
        "vectors": {vector: vector_map[vector] for vector in sorted(vector_map)},
    }

    support = [
        fixture_root / "CONTROL-LANGUAGE.md",
        fixture_root / "HARNESS.md",
        fixture_root / "README.md",
        fixture_root / "TRACE-SCHEMA.md",
        fixture_root / "fixture-schema.yaml",
        fixture_root / "closure-fixture-schema.yaml",
        fixture_root / "projection-catalog.yaml",
        fixture_root / "vector-coverage.yaml",
        fixture_root / "closure/README.md",
        fixture_root / "closure/LIMIT-GENERATORS.md",
        *sorted((fixture_root / "closure/traces").glob("*.yaml")),
    ]
    if any(not path.is_file() for path in support):
        missing = [str(path) for path in support if not path.is_file()]
        raise ClassificationFailure(f"fixture support inventory is incomplete: {missing}")
    entries = [
        fixture_manifest_entry(fixture_root, path, "support")
        for path in support
    ]
    for path, value in ordinary:
        role = "gas-fixture" if value.get("category") == "gas" else "behavior-fixture"
        entries.append(
            fixture_manifest_entry(fixture_root, path, role, value["vectors"])
        )
    for path, value in closure:
        entries.append(
            fixture_manifest_entry(
                fixture_root, path, "closure-fixture", value["vectors"]
            )
        )
    entries.sort(key=lambda entry: entry["path"])
    ordinary_gas = sum(value.get("category") == "gas" for _, value in ordinary)
    manifest: dict[str, Any] = {
        "fixturePackage": "blue-contracts-conformance",
        "specificationVersion": "1.0",
        "schemaVersions": [
            "blue-contracts-fixture/1.0",
            "blue-contracts-closure-fixture/1.0",
        ],
        "registryPackageIdentity": registry_package_identity,
        "vectorCount": vector_coverage["vectorCount"],
        "ordinaryVectorCount": vector_coverage["ordinaryVectorCount"],
        "closureVectorCount": vector_coverage["closureVectorCount"],
        "ordinaryFixtureCount": len(ordinary),
        "ordinaryBehaviorFixtureCount": len(ordinary) - ordinary_gas,
        "ordinaryGasFixtureCount": ordinary_gas,
        "closureFixtureCount": len(closure),
        "totalExecutableFixtureCount": len(ordinary) + len(closure),
        "files": entries,
        "packageIdentityAlgorithm": {
            "digest": "sha256",
            "encoding": "RFC 8785 canonical JSON encoded as UTF-8",
            "normalization": "packageIdentity is null before hashing",
        },
        "packageIdentity": None,
    }
    manifest["packageIdentity"] = package_identity(manifest, "packageIdentity")
    return vector_coverage, manifest


def approved_registry_manifest_transition(
    before: dict[str, Any],
    after: dict[str, Any],
    fixture_package_identity: str,
) -> bool:
    if after.get("fixturePackageIdentity") != fixture_package_identity:
        return False
    normalized = deepcopy(after)
    normalized["fixturePackageIdentity"] = before.get("fixturePackageIdentity")
    normalized["packageIdentity"] = before.get("packageIdentity")
    if normalized != before:
        return False
    identity_input = deepcopy(after)
    identity_input["packageIdentity"] = None
    identity_input["fixturePackageIdentity"] = None
    actual = "sha256:" + hashlib.sha256(jcs_dumps(identity_input)).hexdigest()
    return actual == after.get("packageIdentity") == before.get("packageIdentity")


def implementation_baseline_snapshot(
    value: Any,
) -> tuple[tuple[str, ...], dict[str, str]] | None:
    """Parse one closed ordered path/digest baseline without positional meaning."""
    if not isinstance(value, list):
        return None
    order: list[str] = []
    by_path: dict[str, str] = {}
    for entry in value:
        if not isinstance(entry, dict) or set(entry) != {"path", "sha256"}:
            return None
        path = entry.get("path")
        digest = entry.get("sha256")
        if (
            not isinstance(path, str)
            or path in by_path
            or not isinstance(digest, str)
            or LOWERCASE_SHA256_RE.fullmatch(digest) is None
        ):
            return None
        order.append(path)
        by_path[path] = digest
    return tuple(order), by_path


def approved_release_manifest_transition(
    package_root: Path,
    before: dict[str, Any],
    after: dict[str, Any],
    fixture_manifest: dict[str, Any],
    registry_manifest: dict[str, Any],
) -> bool:
    try:
        fixture = after["fixturePackage"]
        constructors = after["identityConstructors"]
        before_implementation = before["languageDependency"][
            "inputImplementationBaseline"
        ]
        after_implementation = after["languageDependency"][
            "inputImplementationBaseline"
        ]
    except (KeyError, TypeError):
        return False
    before_snapshot = implementation_baseline_snapshot(before_implementation)
    after_snapshot = implementation_baseline_snapshot(after_implementation)
    expected_before = (
        APPROVED_IMPLEMENTATION_BASELINE_BEFORE_ORDER,
        APPROVED_IMPLEMENTATION_BASELINE_BEFORE_BY_PATH,
    )
    if before_snapshot != expected_before or after_snapshot is None:
        return False
    after_order, after_by_path = after_snapshot
    if (
        after_order != IMPLEMENTATION_BASELINE_SOURCE_PATHS
        or implementation_baseline_aggregate_identity(
            after_order, after_by_path
        )
        != APPROVED_IMPLEMENTATION_BASELINE_AGGREGATE_IDENTITY
    ):
        return False
    before_language = before.get("languageDependency", {})
    after_language = after.get("languageDependency", {})
    if not all(
        before_language.get(field) == transition[0]
        and after_language.get(field) == transition[1]
        for field, transition in APPROVED_LANGUAGE_DEPENDENCY_TRANSITION.items()
    ):
        return False
    if (
        before.get("sourceArchiveBaseline")
        != APPROVED_REMOVED_SOURCE_ARCHIVE_BASELINE
        or "sourceArchiveBaseline" in after
    ):
        return False
    if fixture != {
        "path": "fixtures/manifest.yaml",
        "packageIdentity": fixture_manifest["packageIdentity"],
        "vectorCount": fixture_manifest["vectorCount"],
        "fixtureCount": fixture_manifest["totalExecutableFixtureCount"],
    }:
        return False
    if after.get("contractsRegistry", {}).get("packageIdentity") != registry_manifest.get(
        "packageIdentity"
    ):
        return False
    if constructors.get("sha256") != sha256(package_root / "identity-constructors.yaml"):
        return False
    if after.get("specificationDocument", {}).get("sha256") != (
        APPROVED_CONTRACTS_SPECIFICATION_SHA256
    ):
        return False
    if package_identity(after, "releaseIdentity") != after.get("releaseIdentity"):
        return False
    normalized = deepcopy(after)
    normalized["fixturePackage"] = deepcopy(before["fixturePackage"])
    normalized["identityConstructors"]["sha256"] = before[
        "identityConstructors"
    ]["sha256"]
    normalized["languageDependency"]["inputImplementationBaseline"] = deepcopy(
        before_implementation
    )
    for field in APPROVED_LANGUAGE_DEPENDENCY_TRANSITION:
        normalized["languageDependency"][field] = before_language[field]
    normalized["specificationDocument"]["sha256"] = before[
        "specificationDocument"
    ]["sha256"]
    normalized["sourceArchiveBaseline"] = deepcopy(
        before["sourceArchiveBaseline"]
    )
    normalized["releaseIdentity"] = before["releaseIdentity"]
    return normalized == before



TYPED_PATCH_REVIEW_INPUT_PATH = (
    Path(__file__).resolve().parent / "migration" / "classify-typed-patch-transition.json"
)
TYPED_PATCH_REVIEW_INPUT_SHA256 = "b644d1544bec0ad74150fbfc66cedbdb887dd6b234c5e79d64aa7fef75436969"


def reviewed_typed_patch_transition(
    before_files: dict[str, Path], after_files: dict[str, Path]
) -> dict[str, Any] | None:
    """Select only the reviewed pair of complete immutable package inventories."""
    data = TYPED_PATCH_REVIEW_INPUT_PATH.read_bytes()
    if hashlib.sha256(data).hexdigest() != TYPED_PATCH_REVIEW_INPUT_SHA256:
        raise ClassificationFailure("reviewed typed-patch baseline bytes changed")
    review = json.loads(data)
    before_inventory = {path: sha256(file) for path, file in before_files.items()}
    after_inventory = {path: sha256(file) for path, file in after_files.items()}
    if (before_inventory != review["before"]["files"]
            or after_inventory != review["after"]["files"]):
        return None
    return review


REPRESENTATION_REVIEW_INPUT_PATH = (Path(__file__).resolve().parent / "migration" / "classify-historical-representation-transition.json")
REPRESENTATION_REVIEW_INPUT_SHA256 = "117fa2e267efc605eef9f22718dd5f8e476c312dd5120369b5ac6066082e244f"


def reviewed_representation_transition(before_files, after_files):
    """Proposed closed pair only; no acceptance of future inventory changes."""
    data = REPRESENTATION_REVIEW_INPUT_PATH.read_bytes()
    if hashlib.sha256(data).hexdigest() != REPRESENTATION_REVIEW_INPUT_SHA256:
        raise ClassificationFailure("reviewed representation baseline bytes changed")
    review = json.loads(data)
    if ({path: sha256(file) for path, file in before_files.items()} != review["before"]["files"]
            or {path: sha256(file) for path, file in after_files.items()} != review["after"]["files"]):
        return None
    return review


def cevo_release_integrity_violations(
    before_root: Path,
    after_root: Path,
    after_files: dict[str, Path],
    reviewed_transition: dict[str, Any] | None = None,
) -> list[str]:
    release_present = bool(set(after_files) & set(APPROVED_CEVO_FIXTURE_IDENTITIES))
    if not release_present:
        return []
    violations: list[str] = []
    expected_added = set(APPROVED_CEVO_FIXTURE_IDENTITIES)
    actual_added = {
        relative
        for relative in after_files
        if (
            relative.startswith("fixtures/evo/c-evo-")
            or relative.startswith("fixtures/closure/c-evo-")
        )
        and relative.endswith(".yaml")
    }
    if actual_added != expected_added:
        violations.append(
            "C-EVO fixture inventory mismatch: "
            f"missing={sorted(expected_added - actual_added)}, "
            f"unexpected={sorted(actual_added - expected_added)}"
        )
    for relative in sorted(expected_added & set(after_files)):
        reviewed_fixture = (
            reviewed_transition is not None
            and sha256(after_files[relative])
            == reviewed_transition["after"]["files"].get(relative)
        )
        if not reviewed_fixture and not approved_cevo_added_fixture(relative, after_files[relative]):
            violations.append(f"C-EVO fixture content drifted: {relative}")
    scripted = after_files.get("registry/ScriptedOperation.blue")
    if scripted is None or sha256(scripted) != APPROVED_SCRIPTED_OPERATION_SHA256:
        violations.append("ScriptedOperation.blue is missing or changed")

    try:
        before_registry = load_structured(before_root / "registry/manifest.yaml")
        after_registry = load_structured(after_root / "registry/manifest.yaml")
        actual_coverage = load_structured(
            after_root / "fixtures/vector-coverage.yaml"
        )
        actual_fixture_manifest = load_structured(
            after_root / "fixtures/manifest.yaml"
        )
        before_release = load_structured(before_root / "release-manifest.yaml")
        after_release = load_structured(after_root / "release-manifest.yaml")
        if not all(
            isinstance(value, dict)
            for value in (
                before_registry,
                after_registry,
                actual_coverage,
                actual_fixture_manifest,
                before_release,
                after_release,
            )
        ):
            raise ClassificationFailure("release manifest is not a mapping")
        expected_coverage, expected_fixture_manifest = (
            expected_fixture_release_documents(
                after_root, after_registry["packageIdentity"]
            )
        )
        if actual_coverage != expected_coverage:
            violations.append("vector coverage is not the exact physical fixture reverse map")
        if actual_fixture_manifest != expected_fixture_manifest:
            violations.append("fixture manifest hashes/counts/identity are stale")
        if not approved_registry_manifest_transition(
            before_registry,
            after_registry,
            expected_fixture_manifest["packageIdentity"],
        ):
            violations.append("registry reverse fixture binding is invalid")
        reviewed_release = (
            reviewed_transition is not None
            and before_release.get("releaseIdentity")
            == reviewed_transition["before"]["releaseIdentity"]
            and after_release.get("releaseIdentity")
            == reviewed_transition["after"]["releaseIdentity"]
            and package_identity(after_release, "releaseIdentity")
            == after_release.get("releaseIdentity")
            and after_release.get("fixturePackage", {}).get("packageIdentity")
            == expected_fixture_manifest["packageIdentity"]
            and after_release.get("contractsRegistry", {}).get("packageIdentity")
            == after_registry["packageIdentity"]
        )
        if not reviewed_release and not approved_release_manifest_transition(
            after_root, before_release, after_release,
            expected_fixture_manifest, after_registry,
        ):
            violations.append("release manifest bindings or identity are invalid")
    except (ClassificationFailure, KeyError, OSError, TypeError, ValueError) as failure:
        violations.append(f"release integrity could not be evaluated: {failure}")
    return violations


def approved_admission_context_removal(
    relative: str,
    before_value: Any,
    after_value: Any,
    json_pointer: str,
) -> bool:
    """Recognize only the bounded-to-normative admission attribution rebase."""
    if not (
        relative.startswith("fixtures/closure/c-clo-")
        and relative.endswith(".yaml")
        and fixture_operation(before_value) == "admit-closure"
        and fixture_operation(after_value) == "admit-closure"
    ):
        return False
    parts = path_parts(json_pointer)
    if (
        len(parts) != 4
        or parts[0:2] != ("expected", "gasTrace")
        or parts[3] not in ADMISSION_CONTEXT_FIELDS
    ):
        return False
    try:
        index = int(parts[2])
        before_expected = before_value["expected"]
        after_expected = after_value["expected"]
        before_trace = before_expected["gasTrace"]
        after_trace = after_expected["gasTrace"]
        before_entry = before_trace[index]
        after_entry = after_trace[index]
    except (KeyError, IndexError, TypeError, ValueError):
        return False
    if not isinstance(before_entry, dict) or not isinstance(after_entry, dict):
        return False
    if not (
        before_entry.get("scopePath") == "/"
        and before_entry.get("activationGeneration") == 0
        and isinstance(before_entry.get("componentGeneration"), int)
        and before_entry["componentGeneration"] >= 0
        and all(field not in after_entry for field in ADMISSION_CONTEXT_FIELDS)
        and isinstance(after_entry.get("documentId"), str)
        and before_entry.get("documentId") == after_entry.get("documentId")
        and before_expected.get("gasTraceIdentity")
            == gas_trace_identity(before_trace)
        and after_expected.get("gasTraceIdentity")
            == gas_trace_identity(after_trace)
        and before_expected["gasTraceIdentity"]
            != after_expected["gasTraceIdentity"]
    ):
        return False
    reason = after_entry.get("reason")
    planning = isinstance(reason, str) and reason.startswith((
        "admission.document.",
        "admission.binding.",
        "admission.edge.",
        "admission.component-",
    ))
    marker_write = isinstance(reason, str) and reason.startswith(
        "initialization-batch.marker."
    )
    marker_identity = (
        reason == "identity-rebuild"
        and after_entry.get("logicalPath") == "/contracts/initialized"
        and "workOccurrenceId" not in after_entry
    )
    return planning or marker_write or marker_identity


def approved_initialization_cycle_correction(
    relative: str,
    before_value: Any,
    after_value: Any,
    json_pointer: str,
) -> str | None:
    """Classify the exact C-CLO-08 lifecycle/marker evidence correction.

    The lifecycle work that creates the first cycle owns its topology and
    finalization evidence.  B enters initialization only after that work, so
    its initialized marker records the intermediate cyclic member identity.
    All permitted downstream changes are deterministic consequences of those
    two facts; unrelated edits remain fail-closed.
    """
    if relative == "fixtures/closure/c-clo-08-cycle-during-initialization.yaml":
        try:
            before_expected = before_value["expected"]
            after_expected = after_value["expected"]
            lifecycle = next(
                item
                for item in after_expected["workTrace"]
                if item["ordinal"] == 1 and item["kind"] == "LIFECYCLE"
            )
            first = after_expected["tentativeFinalizations"][0]
            second = after_expected["tentativeFinalizations"][1]
            before_b = next(
                item
                for item in before_expected["resultingDocuments"]
                if item["documentId"] == "b"
            )
            after_b = next(
                item
                for item in after_expected["resultingDocuments"]
                if item["documentId"] == "b"
            )
            before_marker = before_b["document"]["contracts"]["initialized"][
                "document"
            ]["blueId"]
            after_marker = after_b["document"]["contracts"]["initialized"][
                "document"
            ]["blueId"]
            final_master = after_expected["resultingComponents"][0][
                "masterBlueId"
            ]
        except (KeyError, IndexError, StopIteration, TypeError):
            return None
        if not (
            fixture_operation(before_value) == "admit-closure"
            and fixture_operation(after_value) == "admit-closure"
            and first["boundary"] == {
                "kind": "WORK",
                "afterWorkOrdinal": lifecycle["ordinal"],
            }
            and after_marker == first["memberBlueIds"]["b"]
            and before_marker != after_marker
            and second["masterBlueId"] == final_master
        ):
            return None
        trace_prefixes = (
            "/expected/gasTrace/",
            "/expected/tentativeFinalizations/0/boundary/",
        )
        semantic_prefixes = (
            "/expected/graphChanges/",
            "/expected/occurrenceBindings/",
            "/expected/platformCommitCompanion/",
            "/expected/resultingComponents/",
            "/expected/resultingDocuments/",
            "/expected/subscriptionDeltas/",
            "/expected/tentativeFinalizations/1/",
            "/oracle/componentStages/0/componentStateIdentity",
        )
        if json_pointer.startswith(trace_prefixes):
            return TRACE
        if json_pointer.startswith(semantic_prefixes):
            return SEMANTIC
        return None

    if relative == "oracles/c-clo-08-cycle-during-initialization.yaml":
        try:
            stages = after_value["stages"]
            intermediate_b = stages[0]["memberBlueIdsInSourceOrder"][1]
            final_master = stages[1]["masterBlueId"]
            final_b = stages[1]["sourceDocumentsWithThisReferences"][1]
            result_b = stages[2]["sourceDocumentsWithThisReferences"][1]
            final_marker = final_b["contracts"]["initialized"]["document"][
                "blueId"
            ]
            result_marker = result_b["contracts"]["initialized"]["document"][
                "blueId"
            ]
        except (KeyError, IndexError, TypeError):
            return None
        if not (
            len(stages) == 3
            and final_marker == intermediate_b
            and result_marker == intermediate_b
            and stages[2]["masterBlueId"] == final_master
        ):
            return None
        if json_pointer.startswith(("/stages/1/", "/stages/2/")):
            return SEMANTIC
    return None


def allowed_semantic_change(relative: str, json_pointer: str) -> bool:
    if relative.startswith("fixtures/closure/fl-adm-") and relative.endswith(".yaml"):
        return True
    return False


def classify_changed_file(relative: str, before_path: Path, after_path: Path) -> dict[str, Any]:
    before_value = load_structured(before_path)
    after_value = load_structured(after_path)
    result: dict[str, Any] = {
        "path": relative,
        "change": "modified",
        "beforeSha256": sha256(before_path),
        "afterSha256": sha256(after_path),
        "categories": [],
        "differences": [],
        "unexpected": False,
    }
    if before_value == after_value and before_value is not None:
        result["categories"] = [FORMATTING]
        return result
    if before_value is None or after_value is None:
        approved_support_document = relative in APPROVED_SUPPORT_DOCUMENTS
        result["categories"] = [SEMANTIC] if approved_support_document else [UNEXPECTED]
        result["unexpected"] = not approved_support_document
        if not approved_support_document:
            result["reason"] = "changed file is not a supported structured package artifact"
        return result

    categories: set[str] = set()
    operation = fixture_operation(before_value) or fixture_operation(after_value)
    differences = leaf_differences(before_value, after_value)
    if approved_cevo_structured_transition(
        relative, before_value, after_value
    ):
        for difference in differences:
            category = identity_category(difference["path"]) or SEMANTIC
            difference["category"] = category
            categories.add(category)
        result["categories"] = sorted(categories)
        result["differences"] = differences
        return result

    for difference in differences:
        category = approved_initialization_cycle_correction(
            relative,
            before_value,
            after_value,
            difference["path"],
        )
        if category is None:
            category = (
                TRACE
                if approved_admission_context_removal(
                    relative,
                    before_value,
                    after_value,
                    difference["path"],
                )
                else identity_category(difference["path"])
            )
        if category is None:
            category = SEMANTIC
            if not allowed_semantic_change(relative, difference["path"]):
                result["unexpected"] = True
            if operation == "process-closure":
                result["unexpected"] = True
        difference["category"] = category
        categories.add(category)
    if (
        bounded_cevo_transition_path(relative)
        and not approved_identity_rebinding_transition(
            relative, before_value, after_value
        )
    ):
        result["unexpected"] = True
    if result["unexpected"]:
        categories.add(UNEXPECTED)
        result["reason"] = (
            "non-identity change outside the bounded lifecycle/C-EVO "
            "release transitions and approved fixture support documents"
        )
    result["categories"] = sorted(categories)
    result["differences"] = differences
    return result


def classify_added_file(relative: str, path: Path) -> dict[str, Any]:
    full_lifecycle = (
        relative.startswith("fixtures/closure/fl-adm-")
        and relative.endswith(".yaml")
    )
    cevo = approved_cevo_added_fixture(relative, path)
    scripted_operation = (
        relative == "registry/ScriptedOperation.blue"
        and sha256(path) == APPROVED_SCRIPTED_OPERATION_SHA256
    )
    allowed = full_lifecycle or cevo or scripted_operation
    return {
        "path": relative,
        "change": "added",
        "afterSha256": sha256(path),
        "categories": [SEMANTIC] if allowed else [UNEXPECTED],
        "differences": [],
        "unexpected": not allowed,
        **({} if allowed else {"reason": "unrecognized package file was added"}),
    }


def classify_removed_file(relative: str, path: Path) -> dict[str, Any]:
    return {
        "path": relative,
        "change": "removed",
        "beforeSha256": sha256(path),
        "categories": [UNEXPECTED],
        "differences": [],
        "unexpected": True,
        "reason": "package file was removed",
    }


def approved_corrections(
    rows: list[dict[str, Any]], after_files: dict[str, Path]
) -> list[dict[str, Any]]:
    """Return machine-readable rationale for each bounded semantic correction."""
    relative = "fixtures/closure/c-clo-08-cycle-during-initialization.yaml"
    row = next((item for item in rows if item["path"] == relative), None)
    if row is None or SEMANTIC not in row["categories"]:
        return []
    try:
        expected = load_structured(after_files[relative])["expected"]
        lifecycle = next(
            item
            for item in expected["workTrace"]
            if item["ordinal"] == 1 and item["kind"] == "LIFECYCLE"
        )
        intermediate_b = expected["tentativeFinalizations"][0][
            "memberBlueIds"
        ]["b"]
        final_master = expected["resultingComponents"][0]["masterBlueId"]
    except (KeyError, IndexError, StopIteration, TypeError) as failure:
        raise ClassificationFailure(
            "approved C-CLO-08 correction lacks exact lifecycle evidence"
        ) from failure
    return [
        {
            "id": "c-clo-08-initialization-cycle-evidence",
            "classification": "approved-semantic-correction",
            "lifecycleWorkOrdinal": lifecycle["ordinal"],
            "intermediateBMemberBlueId": intermediate_b,
            "finalMasterBlueId": final_master,
            "rationale": (
                "Lifecycle work ordinal 1 owns the topology and first "
                "finalization; B initializes afterward, so its marker binds "
                "to the intermediate cyclic member before the final component."
            ),
            "failClosedTest": (
                "ClassifyFixtureIdentityDeltaTest."
                "test_initialization_cycle_exception_rejects_unrelated_change"
            ),
        }
    ]


def classify(before_root: Path, after_root: Path) -> dict[str, Any]:
    before_files = package_files(before_root)
    after_files = package_files(after_root)
    rows: list[dict[str, Any]] = []
    for relative in sorted(set(before_files) | set(after_files)):
        if relative not in before_files:
            rows.append(classify_added_file(relative, after_files[relative]))
        elif relative not in after_files:
            rows.append(classify_removed_file(relative, before_files[relative]))
        elif before_files[relative].read_bytes() != after_files[relative].read_bytes():
            rows.append(
                classify_changed_file(
                    relative, before_files[relative], after_files[relative]
                )
            )
    cevo_release_present = bool(
        set(after_files) & set(APPROVED_CEVO_FIXTURE_IDENTITIES)
    )
    reviewed_transition = reviewed_typed_patch_transition(before_files, after_files)
    if reviewed_transition is None:
        reviewed_transition = reviewed_representation_transition(before_files, after_files)
    integrity_violations = cevo_release_integrity_violations(
        before_root, after_root, after_files, reviewed_transition
    )
    if cevo_release_present and not integrity_violations:
        for row in rows:
            if row["path"] not in GENERATED_RELEASE_MANIFESTS:
                continue
            row["unexpected"] = False
            row["categories"] = [
                category
                for category in row["categories"]
                if category != UNEXPECTED
            ]
            row.pop("reason", None)
            if reviewed_transition is not None:
                row["categories"] = [SPEC]
                for difference in row["differences"]:
                    difference["category"] = SPEC
                row["reviewedTransition"] = reviewed_transition["id"]
    elif integrity_violations:
        rows.append(
            {
                "path": "<C-EVO release integrity>",
                "change": "validation",
                "categories": [UNEXPECTED],
                "differences": [],
                "unexpected": True,
                "reason": "; ".join(integrity_violations),
            }
        )
    counts: Counter[str] = Counter()
    for row in rows:
        counts.update(row["categories"])
    return {
        "schema": "blue-contracts-full-lifecycle-identity-delta/1.0",
        "beforePackage": str(before_root),
        "afterPackage": str(after_root),
        "beforeFileCount": len(before_files),
        "afterFileCount": len(after_files),
        "changedFileCount": len(rows),
        "summary": {category: counts[category] for category in CATEGORIES},
        "unexpectedCount": sum(1 for row in rows if row["unexpected"]),
        "approvedCorrections": approved_corrections(rows, after_files),
        "reviewedBaselineTransition": None if reviewed_transition is None else {
            "id": reviewed_transition["id"],
            "rationale": reviewed_transition["rationale"],
            "reviewInputSha256": (REPRESENTATION_REVIEW_INPUT_SHA256
                if reviewed_transition["id"] == "historical-representation-exact-proposed-transition"
                else TYPED_PATCH_REVIEW_INPUT_SHA256),
            "beforeSourceCommit": reviewed_transition["before"]["sourceCommit"],
            "afterSourceCommit": reviewed_transition["after"]["sourceCommit"],
            "closedInventoryFiles": len(after_files),
            "executableFixturesUnchanged": reviewed_transition.get(
                "executableFixturesUnchanged", reviewed_transition["executableFixtureCount"]),
        },
        "files": rows,
    }


def apply_report_context(
    report: dict[str, Any],
    *,
    before_reference: str | None,
    after_reference: str | None,
    baseline_commit: str | None,
    baseline_tree: str | None,
    baseline_package_identity: str | None,
) -> dict[str, Any]:
    """Replace disposable paths with durable, fail-closed provenance."""
    baseline_values = (
        baseline_commit,
        baseline_tree,
        baseline_package_identity,
    )
    if any(value is not None for value in baseline_values) and not all(
        value is not None and value.strip() for value in baseline_values
    ):
        raise ClassificationFailure(
            "baseline commit, tree, and package identity must be supplied together"
        )
    result = dict(report)
    if before_reference:
        result["beforePackage"] = before_reference
    if after_reference:
        result["afterPackage"] = after_reference
    if baseline_commit is not None:
        if len(baseline_commit) != 40 or any(
            character not in "0123456789abcdef" for character in baseline_commit
        ):
            raise ClassificationFailure("baseline commit must be a lowercase Git SHA-1")
        if len(baseline_tree or "") != 40 or any(
            character not in "0123456789abcdef"
            for character in (baseline_tree or "")
        ):
            raise ClassificationFailure("baseline tree must be a lowercase Git SHA-1")
        if not (baseline_package_identity or "").startswith("sha256:") or len(
            baseline_package_identity or ""
        ) != 71:
            raise ClassificationFailure(
                "baseline package identity must be a prefixed SHA-256"
            )
        result["baseline"] = {
            "commit": baseline_commit,
            "tree": baseline_tree,
            "fixturePackageIdentity": baseline_package_identity,
        }
    return result


def markdown_report(report: dict[str, Any]) -> str:
    lines = [
        "# Full-lifecycle identity delta",
        "",
        f"- Before files: {report['beforeFileCount']}",
        f"- After files: {report['afterFileCount']}",
        f"- Changed files: {report['changedFileCount']}",
        f"- Unexpected files: {report['unexpectedCount']}",
    ]
    baseline = report.get("baseline")
    if baseline:
        lines.extend(
            [
                f"- Baseline commit: `{baseline['commit']}`",
                f"- Baseline tree: `{baseline['tree']}`",
                "- Baseline fixture package: "
                f"`{baseline['fixturePackageIdentity']}`",
            ]
        )
    lines.extend(
        [
            f"- Before reference: `{report['beforePackage']}`",
            f"- After reference: `{report['afterPackage']}`",
            "",
            "## Classification summary",
            "",
            "| Category | Changed files |",
            "|---|---:|",
        ]
    )
    for category in CATEGORIES:
        lines.append(f"| `{category}` | {report['summary'][category]} |")
    reviewed = report.get("reviewedBaselineTransition")
    if reviewed:
        lines.extend(["", "## Reviewed baseline transition", "", reviewed["rationale"],
                      "", f"- Exact review input: `{reviewed['reviewInputSha256']}`",
                      f"- Before source: `{reviewed['beforeSourceCommit']}`",
                      f"- After source: `{reviewed['afterSourceCommit']}`"])
    corrections = report.get("approvedCorrections", [])
    if corrections:
        lines.extend(["", "## Approved bounded corrections", ""])
        for correction in corrections:
            lines.extend(
                [
                    f"### `{correction['id']}`",
                    "",
                    correction["rationale"],
                    "",
                    f"- Lifecycle work ordinal: `{correction['lifecycleWorkOrdinal']}`",
                    "- Intermediate B member/marker: "
                    f"`{correction['intermediateBMemberBlueId']}`",
                    f"- Final MASTER: `{correction['finalMasterBlueId']}`",
                    f"- Fail-closed guard: `{correction['failClosedTest']}`",
                    "",
                ]
            )
    lines.extend(["", "## Changed files", ""])
    if not report["files"]:
        lines.append("No byte differences.")
    for row in report["files"]:
        categories = ", ".join(f"`{item}`" for item in row["categories"])
        lines.append(f"- `{row['path']}` — {row['change']} — {categories}")
        if row.get("reason"):
            lines.append(f"  - {row['reason']}")
    lines.append("")
    return "\n".join(lines)


def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", dir=path.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        temporary.write_text(content, encoding="utf-8")
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--before-package", type=Path, required=True)
    parser.add_argument("--after-package", type=Path, required=True)
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    parser.add_argument("--before-reference")
    parser.add_argument("--after-reference")
    parser.add_argument("--baseline-commit")
    parser.add_argument("--baseline-tree")
    parser.add_argument("--baseline-package-identity")
    parser.add_argument("--fail-on-unexpected", action="store_true")
    args = parser.parse_args()
    before = args.before_package.expanduser().resolve()
    after = args.after_package.expanduser().resolve()
    if not before.is_dir() or not after.is_dir():
        raise ClassificationFailure(
            f"both package roots must be directories: before={before}, after={after}"
        )
    json_output = args.json.expanduser().resolve()
    markdown_output = args.markdown.expanduser().resolve()
    if json_output == markdown_output:
        raise ClassificationFailure(
            "--json and --markdown must identify distinct report files"
        )
    for output in (json_output, markdown_output):
        if output.is_relative_to(before) or output.is_relative_to(after):
            raise ClassificationFailure(
                "report output must not mutate either classified package: "
                f"{output}"
            )
    report = apply_report_context(
        classify(before, after),
        before_reference=args.before_reference,
        after_reference=args.after_reference,
        baseline_commit=args.baseline_commit,
        baseline_tree=args.baseline_tree,
        baseline_package_identity=args.baseline_package_identity,
    )
    atomic_write(
        json_output,
        json.dumps(report, indent=2, sort_keys=True) + "\n",
    )
    atomic_write(
        markdown_output, markdown_report(report)
    )
    print(
        "FULL_LIFECYCLE_IDENTITY_DELTA "
        f"changed={report['changedFileCount']} "
        f"unexpected={report['unexpectedCount']}"
    )
    if args.fail_on_unexpected and report["unexpectedCount"]:
        raise SystemExit(2)


if __name__ == "__main__":
    try:
        main()
    except ClassificationFailure as exc:
        print(f"IDENTITY_DELTA_CLASSIFICATION_FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
