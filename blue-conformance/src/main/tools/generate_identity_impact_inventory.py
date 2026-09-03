#!/usr/bin/env python3
"""Generate the reviewable exact-identity migration closure for this release.

The report compares the pre-change release commit with the current worktree.
It deliberately reads published manifests instead of calculating schema-heavy
BlueIds in Python: those identities are produced and verified by the Java
runtime registry pipeline before this inventory is generated.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from typing import Any, Iterable

import yaml


DEFAULT_BASELINE = "be2260217d1dbab0c7b60bcbd28073a5955e2b7b"
EMPTY_OBJECT_BLUE_ID = "5ajuwjHoLj33yG5t5UFsJtUb3vnRaJQEMPqSLz6VyoHK"

LANGUAGE_REGISTRY = (
    "blue-language-core/src/main/resources/registry/"
    "blue-language-1.0/manifest.yaml"
)
CONTRACTS_REGISTRY = (
    "blue-contracts-core/src/main/resources/registry/"
    "blue-contracts-1.0/manifest.yaml"
)
CLOSURE_REGISTRY = (
    "blue-conformance/src/main/resources/"
    "blue-contracts-closure-1.0/registry/manifest.yaml"
)
LANGUAGE_FIXTURES = (
    "blue-conformance/src/main/resources/"
    "blue-language-1.0/fixtures/manifest.yaml"
)
ORDINARY_FIXTURES = (
    "blue-conformance/src/main/resources/"
    "blue-contracts-1.0/fixtures/manifest.yaml"
)
CLOSURE_FIXTURES = (
    "blue-conformance/src/main/resources/"
    "blue-contracts-closure-1.0/fixtures/manifest.yaml"
)
CLOSURE_ORACLES = (
    "blue-conformance/src/main/resources/"
    "blue-contracts-closure-1.0/oracles/manifest.yaml"
)
HISTORICAL_CYCLE_FIXTURE = (
    "blue-conformance/src/main/resources/"
    "blue-contracts-closure-1.0/fixtures/closure/"
    "c-clo-23-05-a9-to-a10.yaml"
)
CONTRACTS_RELEASE = (
    "blue-conformance/src/main/resources/"
    "blue-contracts-closure-1.0/release-manifest.yaml"
)
CONTRACTS_GAS = (
    "blue-conformance/src/main/resources/"
    "blue-contracts-closure-1.0/gas-manifest.yaml"
)
IDENTITY_CONSTRUCTORS = (
    "blue-conformance/src/main/resources/"
    "blue-contracts-closure-1.0/identity-constructors.yaml"
)
MOCK_TYPE_BLUE_IDS = (
    "blue-conformance/src/main/java/blue/language/conformance/"
    "contracts/MockTypeBlueIds.java"
)
AGGREGATE_RELEASE = (
    "blue-conformance/src/main/resources/release/"
    "blue-language-contracts-embedded-modules-collection-paths-1.0/"
    "PACKAGE-MANIFEST.yaml"
)
LANGUAGE_SPEC = (
    "blue-language-core/src/main/resources/specifications/"
    "blue-language-specification-1.0.md"
)
CONTRACTS_SPEC = (
    "blue-contracts-core/src/main/resources/specifications/"
    "blue-contracts-and-processor-specification-1.0.md"
)

REPORT_PATH = "reports/migration/empty-object-identity-impact.json"
SUMMARY_PATH = "docs/empty-object-identity-migration.md"
SELF_PATHS = frozenset(
    (
        REPORT_PATH,
        SUMMARY_PATH,
        "blue-conformance/src/main/tools/"
        "generate_identity_impact_inventory.py",
        "blue-conformance/src/main/tools/"
        "test_generate_identity_impact_inventory.py",
    )
)

# The first semantic edge responsible for each rotated registry identity.  The
# registry manifests remain the authority for the old/new values themselves.
FIRST_CHANGED_DEPENDENCY = {
    "language:Dictionary": "Dictionary identity-bearing empty-object semantics",
    "language:List": "List identity-bearing empty-object element semantics",
    "contracts:ChannelEventCheckpoint": "language:Dictionary",
    "contracts:RuntimeLedger": "language:List",
    "contracts:ContractExecutionResult": "contracts:RuntimeLedger",
    "contracts:ScriptedHandler": "contracts:ContractExecutionResult",
    "contracts:TypeGeneralizationPolicy": "language:List",
    "contracts:ProcessEmbedded": "Process Embedded.collectionPaths",
    "contracts:EmbeddedCollectionEventChannel": None,
    "closure:ScriptedOperation": "contracts:ContractExecutionResult",
}

DIRECTLY_CHANGED = frozenset(
    (
        "language:Dictionary",
        "language:List",
        "contracts:ProcessEmbedded",
        "document:language-specification",
        "document:contracts-specification",
        "document:identity-constructors",
    )
)

DEPENDENCIES = {
    "fixture:c-clo-23-05-a9-to-a10:masterBlueId": (
        "contracts:ProcessEmbedded",
    ),
    "contracts:ChannelEventCheckpoint": ("language:Dictionary",),
    "contracts:RuntimeLedger": ("language:List",),
    "contracts:ContractExecutionResult": (
        "language:List",
        "contracts:RuntimeLedger",
    ),
    "contracts:ScriptedHandler": ("contracts:ContractExecutionResult",),
    "contracts:TypeGeneralizationPolicy": ("language:List",),
    "closure:ScriptedOperation": ("contracts:ContractExecutionResult",),
    "package:language-registry": (
        "language:Dictionary",
        "language:List",
    ),
    "package:language-fixtures": ("package:language-registry",),
    "package:contracts-registry": (
        "contracts:ChannelEventCheckpoint",
        "contracts:ContractExecutionResult",
        "contracts:EmbeddedCollectionEventChannel",
        "contracts:ProcessEmbedded",
        "contracts:RuntimeLedger",
        "contracts:ScriptedHandler",
        "contracts:TypeGeneralizationPolicy",
    ),
    "package:contracts-ordinary-fixtures": (
        "package:contracts-registry",
        "closure:ScriptedOperation",
    ),
    "package:contracts-closure-fixtures": (
        "package:contracts-ordinary-fixtures",
        "closure:ScriptedOperation",
        "fixture:c-clo-23-05-a9-to-a10:masterBlueId",
    ),
    "package:contracts-release": (
        "document:language-specification",
        "document:contracts-specification",
        "implementation:cyclic-set-finalizer",
        "implementation:cyclic-set-proof-verifier",
        "document:identity-constructors",
        "package:contracts-registry",
        "package:contracts-gas",
        "package:contracts-closure-fixtures",
        "package:contracts-oracles",
        "implementation:blue-contracts-core/src/main/java/blue/language/processor/DocumentProcessor.java",
        "implementation:blue-contracts-core/src/main/java/blue/language/processor/ProcessorInvocationOrchestrator.java",
        "implementation:blue-contracts-core/src/main/java/blue/language/processor/ProcessorExecutionContext.java",
    ),
    "package:language-contracts-aggregate": (
        "document:language-specification",
        "document:contracts-specification",
        "package:language-registry",
        "package:language-fixtures",
        "package:contracts-registry",
        "package:contracts-closure-fixtures",
        "package:contracts-gas",
        "contracts:ProcessEmbedded",
    ),
}

HISTORICAL_PREFIXES = (
    "api/semantic-baseline-1.0.json",
    "reports/modernization/",
    "docs/collection-paths-and-cohesion-migration-report.md",
    "docs/language-1.0-contracts-kernel-1.0-migration.md",
    "docs/blue-language-1.0-final-clarifications.md",
    "docs/enum-normalization-registry-correction.md",
)

HISTORICAL_TOP_LEVEL_PREFIXES = (
    "DYNAMIC_",
    "FULL_LIFECYCLE_",
    "dynamic-contract-evolution-",
    "full-lifecycle-",
    "MANAGED_TRANSITION_RECEIPT_",
    "managed-transition-receipt-",
)

TEXT_SUFFIXES = frozenset((
    ".java", ".md", ".yaml", ".yml", ".json", ".txt", ".blue",
    ".properties", ".gradle", ".kts", ".py", ".sha256", ".toml",
))

EXACT_IDENTIFIER = re.compile(
    r"sha256:[0-9a-f]{64}"
    r"|(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])"
    r"|(?<![0-9a-f])[0-9a-f]{40}(?![0-9a-f])"
    r"|(?<![1-9A-HJ-NP-Za-km-z])[1-9A-HJ-NP-Za-km-z]{40,60}"
    r"(?![1-9A-HJ-NP-Za-km-z])"
)


def _git(repository: Path, *args: str) -> bytes:
    completed = subprocess.run(
        ("git",) + args,
        cwd=str(repository),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            "git " + " ".join(args) + " failed: "
            + completed.stderr.decode("utf-8", errors="replace")
        )
    return completed.stdout


def _baseline_bytes(repository: Path, baseline: str, path: str) -> bytes | None:
    completed = subprocess.run(
        ("git", "show", baseline + ":" + path),
        cwd=str(repository),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        return None
    return completed.stdout


def _revision(repository: Path, revision: str) -> str:
    return _git(repository, "rev-parse", "--verify", revision).decode("utf-8").strip()


def _require_ancestor(repository: Path, baseline: str, current: str) -> None:
    completed = subprocess.run(
        ("git", "merge-base", "--is-ancestor", baseline, current),
        cwd=str(repository),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise ValueError(
            "Identity inventory baseline is not an ancestor of HEAD: "
            + baseline + " -> " + current
        )


def _current_bytes(repository: Path, path: str) -> bytes | None:
    candidate = repository / path
    return candidate.read_bytes() if candidate.is_file() else None


def _yaml(data: bytes | None) -> dict[str, Any] | None:
    if data is None:
        return None
    loaded = yaml.safe_load(data)
    if not isinstance(loaded, dict):
        raise ValueError("Expected a YAML mapping")
    return loaded


def _sha256(data: bytes | None) -> str | None:
    return hashlib.sha256(data).hexdigest() if data is not None else None


def _manifest_identity(document: dict[str, Any] | None, field: str) -> str | None:
    if document is None:
        return None
    value = document.get(field)
    return value if isinstance(value, str) else None


def _registry_entries(document: dict[str, Any] | None) -> dict[str, dict[str, Any]]:
    if document is None:
        return {}
    result: dict[str, dict[str, Any]] = {}
    for value in document.get("entries", []):
        if not isinstance(value, dict):
            raise ValueError("Registry manifest entries must be mappings")
        key = value.get("key")
        if not isinstance(key, str) or not key:
            raise ValueError("Registry manifest entry is missing a stable key")
        if key in result:
            raise ValueError("Duplicate registry manifest key: " + key)
        if not isinstance(value.get("path"), str):
            raise ValueError("Registry manifest entry is missing path: " + key)
        if not isinstance(value.get("blueId"), str):
            raise ValueError("Registry manifest entry is missing blueId: " + key)
        result[key] = value
    return result


def _classification(stable_key: str, old: str | None, new: str | None) -> str:
    if old is None and new is None:
        return "unresolved"
    if old is None and new is not None:
        return "newly-introduced"
    if old is not None and new is None:
        return "obsolete-pre-stable-rc"
    if old == new:
        return "unchanged-direct"
    if stable_key == "package:language-contracts-aggregate":
        return "stale-binding-regenerated"
    if stable_key in DIRECTLY_CHANGED:
        return "directly-changed"
    return "transitively-changed"


def _dependents() -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for dependent, requirements in DEPENDENCIES.items():
        for requirement in requirements:
            result.setdefault(requirement, []).append(dependent)
    return {key: sorted(value) for key, value in result.items()}


def _transitive_dependents(stable_key: str) -> list[str]:
    reverse = _dependents()
    result: set[str] = set()
    pending = list(reverse.get(stable_key, []))
    while pending:
        value = pending.pop()
        if value in result:
            continue
        result.add(value)
        pending.extend(reverse.get(value, []))
    return sorted(result)


def _tracked_text_files(repository: Path) -> list[str]:
    raw = _git(repository, "ls-files", "-co", "--exclude-standard")
    result: list[str] = []
    for value in raw.decode("utf-8").splitlines():
        path = value.strip()
        if not path or path in SELF_PATHS:
            continue
        if path.startswith(("build/", ".gradle/")):
            continue
        if Path(path).suffix.lower() in TEXT_SUFFIXES:
            result.append(path)
    return sorted(set(result))


def _is_declared_history(path: str) -> bool:
    if path.startswith(HISTORICAL_PREFIXES):
        return True
    if "/" in path:
        return False
    return path.startswith(HISTORICAL_TOP_LEVEL_PREFIXES)


def _reference_index(
    repository: Path,
    baseline: str,
) -> dict[str, list[dict[str, Any]]]:
    """Indexes every exact identifier once and proves history is immutable."""
    result: dict[str, list[dict[str, Any]]] = {}
    for path in _tracked_text_files(repository):
        data = _current_bytes(repository, path)
        if data is None:
            continue
        declared_history = _is_declared_history(path)
        immutable_history = declared_history and (
            _baseline_bytes(repository, baseline, path) == data
        )
        disposition = (
            "retained-immutable-history"
            if immutable_history
            else "requires-review-or-update"
        )
        text = data.decode("utf-8", errors="replace")
        for number, line in enumerate(text.splitlines(), 1):
            for identity in set(
                match.group(0) for match in EXACT_IDENTIFIER.finditer(line)
            ):
                result.setdefault(identity, []).append(
                    {
                        "path": path,
                        "line": number,
                        "disposition": disposition,
                    }
                )
    return result


def _exact_identifiers(data: bytes | None) -> list[str]:
    if data is None:
        return []
    text = data.decode("utf-8", errors="replace")
    return sorted(set(match.group(0) for match in EXACT_IDENTIFIER.finditer(text)))


def _is_identity_surface(path: str) -> bool:
    lower = path.lower()
    name = Path(lower).name
    if Path(lower).suffix not in TEXT_SUFFIXES:
        return False
    return (
        "manifest" in name
        or "receipt" in name
        or "lock" in name
        or "/specification" in lower
        or "/specifications/" in lower
        or name.startswith("spec-")
        or name.endswith("-spec.md")
        or name.endswith("-specification.md")
    )


def _baseline_paths(repository: Path, baseline: str) -> set[str]:
    return {
        line.strip()
        for line in _git(repository, "ls-tree", "-r", "--name-only", baseline)
        .decode("utf-8")
        .splitlines()
        if line.strip()
    }


def _surface_kind(path: str) -> str:
    lower = path.lower()
    name = Path(lower).name
    if "manifest" in name:
        return "manifest"
    if "receipt" in name:
        return "receipt"
    if "lock" in name:
        return "lock"
    return "specification"


def _cross_repository_surface_inventory(
    repository: Path,
    baseline: str,
    role: str,
    upstream_changes: list[dict[str, str]],
) -> dict[str, Any]:
    repository = repository.resolve()
    baseline_commit = _revision(repository, baseline)
    current_commit = _revision(repository, "HEAD")
    paths = _baseline_paths(repository, baseline_commit) | set(
        _tracked_text_files(repository)
    )
    identity_surface_paths = sorted(
        value for value in paths if _is_identity_surface(value)
    )
    surfaces: list[dict[str, Any]] = []
    for path in identity_surface_paths:
        old_data = _baseline_bytes(repository, baseline_commit, path)
        new_data = _current_bytes(repository, path)
        if old_data == new_data:
            continue
        old_identifiers = _exact_identifiers(old_data)
        new_identifiers = _exact_identifiers(new_data)
        surfaces.append(
            {
                "path": path,
                "surfaceKind": _surface_kind(path),
                "changeKind": (
                    "added"
                    if old_data is None
                    else "deleted" if new_data is None else "modified"
                ),
                "oldFileSha256": _sha256(old_data),
                "newFileSha256": _sha256(new_data),
                "oldExactIdentifiers": old_identifiers,
                "newExactIdentifiers": new_identifiers,
                "removedExactIdentifiers": sorted(
                    set(old_identifiers) - set(new_identifiers)
                ),
                "addedExactIdentifiers": sorted(
                    set(new_identifiers) - set(old_identifiers)
                ),
            }
        )
    bindings: list[dict[str, Any]] = []
    stale_binding_count = 0
    for change in upstream_changes:
        old_references: list[dict[str, Any]] = []
        new_references: list[dict[str, Any]] = []
        for path in identity_surface_paths:
            data = _current_bytes(repository, path)
            if data is None:
                continue
            text = data.decode("utf-8", errors="replace")
            for line_number, line in enumerate(text.splitlines(), 1):
                if change["old"] in line:
                    disposition = (
                        "retained-immutable-history"
                        if _is_declared_history(path)
                        and _baseline_bytes(repository, baseline_commit, path) == data
                        else "requires-review-or-update"
                    )
                    old_references.append(
                        {
                            "path": path,
                            "line": line_number,
                            "disposition": disposition,
                        }
                    )
                if change["new"] in line:
                    new_references.append({"path": path, "line": line_number})
        stale = [
            value
            for value in old_references
            if value["disposition"] == "requires-review-or-update"
        ]
        stale_binding_count += len(stale)
        if old_references or new_references:
            bindings.append(
                {
                    "upstreamStableKey": change["stableKey"],
                    "oldExactIdentity": change["old"],
                    "newExactIdentity": change["new"],
                    "oldReferences": old_references,
                    "newReferences": new_references,
                    "status": "stale-old-binding-present" if stale else "rebound-or-not-consumed",
                }
            )
    status = _git(
        repository, "status", "--porcelain", "--untracked-files=all"
    ).decode("utf-8")
    return {
        "repositoryRole": role,
        "repository": repository.name,
        "baselineRevision": baseline,
        "baselineCommit": baseline_commit,
        "currentCommit": current_commit,
        "worktreeClean": not bool(status.strip()),
        "changedIdentitySurfaceCount": len(surfaces),
        "changedIdentitySurfaces": surfaces,
        "upstreamBindingCount": len(bindings),
        "staleUpstreamBindingCount": stale_binding_count,
        "upstreamBindings": bindings,
    }


def generate_cross_repository_closure(
    repositories: list[tuple[str, Path, str]],
    upstream_report: dict[str, Any],
) -> dict[str, Any]:
    """Generate sibling evidence without making the tracked report depend on it."""
    upstream_changes = [
        {
            "stableKey": artifact["stableKey"],
            "old": artifact["oldExactIdentity"],
            "new": artifact["newExactIdentity"],
        }
        for artifact in upstream_report["artifacts"]
        if artifact["oldExactIdentity"] is not None
        and artifact["newExactIdentity"] is not None
        and artifact["oldExactIdentity"] != artifact["newExactIdentity"]
    ]
    inventories = [
        _cross_repository_surface_inventory(
            repository,
            baseline,
            role,
            upstream_changes,
        )
        for role, repository, baseline in repositories
    ]
    return {
        "schema": "blue-cross-repository-identity-impact/1.0",
        "purpose": (
            "Optional build output for assembling the final BEX and "
            "Coordination identity closure after immutable candidates are bound"
        ),
        "upstreamChangedIdentityCount": len(upstream_changes),
        "upstreamChangedIdentities": upstream_changes,
        "summary": {
            "repositoryCount": len(inventories),
            "dirtyRepositoryCount": sum(
                not value["worktreeClean"] for value in inventories
            ),
            "staleUpstreamBindingCount": sum(
                value["staleUpstreamBindingCount"] for value in inventories
            ),
        },
        "repositories": inventories,
    }


def _references(
    reference_index: dict[str, list[dict[str, Any]]],
    identity: str | None,
) -> list[dict[str, Any]]:
    if not identity:
        return []
    return list(reference_index.get(identity, ()))


def _is_fixture_or_constant_reference(path: str) -> bool:
    name = Path(path).name
    return (
        "/fixtures/" in path
        or "/oracles/" in path
        or name.endswith("Fixture.java")
        or "Fixture" in name
        or name in {
            "BlueConformanceReport.java",
            "BlueContractsConformanceReport.java",
            "ConformanceReportConstants.java",
            "MockTypeBlueIds.java",
            "RuntimeBlueIds.java",
            "RuntimeTypeKey.java",
        }
    )


def _is_manifest_or_receipt_reference(path: str) -> bool:
    name = Path(path).name.lower()
    return (
        "manifest" in name
        or "receipt" in name
        or "sibling-lock" in name
        or "/locks/" in path
        or "/receipts/" in path
    )


def _reference_partitions(
    references: list[dict[str, Any]],
) -> dict[str, list[dict[str, Any]]]:
    active = [
        value
        for value in references
        if value["disposition"] == "requires-review-or-update"
    ]
    historical = [
        value
        for value in references
        if value["disposition"] == "retained-immutable-history"
    ]
    return {
        "active": active,
        "historical": historical,
        "fixtures": [
            value
            for value in active
            if _is_fixture_or_constant_reference(value["path"])
        ],
        "manifests": [
            value
            for value in active
            if _is_manifest_or_receipt_reference(value["path"])
        ],
    }


def _reason(
    stable_key: str,
    classification: str,
    first_dependency: str | None,
) -> str:
    if classification == "unresolved":
        return "Required old/new exact identity could not be read; release sealing is blocked."
    if classification == "unchanged-direct":
        return "Canonical content and all identity-bearing dependencies are unchanged."
    if classification == "newly-introduced":
        return "New canonical release artifact introduced by this specification revision."
    if classification == "obsolete-pre-stable-rc":
        return "The pre-stable RC artifact has no identity in the revised release."
    if classification == "directly-changed":
        return "Identity-bearing canonical content changed for the revised semantics."
    if classification == "stale-binding-regenerated":
        return (
            "The aggregate already contained stale component pins at the "
            "baseline and was regenerated from the complete authoritative "
            "component set; see componentBindingChanges."
        )
    if first_dependency is None:
        raise ValueError(
            "Changed artifact has no reviewed dependency cause: " + stable_key
        )
    return (
        "Rotated because the first reviewed identity-bearing dependency or "
        "generation cause changed: " + first_dependency + "."
    )


def _artifact(
    reference_index: dict[str, list[dict[str, Any]]],
    *,
    stable_key: str,
    kind: str,
    module: str,
    path: str,
    old: str | None,
    new: str | None,
    first_dependency: str | None = None,
    mirrored_paths: list[dict[str, Any]] | None = None,
    component_binding_changes: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    classification = _classification(stable_key, old, new)
    effective_dependency = (
        first_dependency
        if first_dependency is not None
        else FIRST_CHANGED_DEPENDENCY.get(stable_key)
    )
    all_references = _references(reference_index, old if old != new else None)
    reference_partitions = _reference_partitions(all_references)
    return {
        "artifactKind": kind,
        "repositoryModule": module,
        "pathOrStableKey": path,
        "stableKey": stable_key,
        "classification": classification,
        "oldExactIdentity": old,
        "newExactIdentity": new,
        "reason": _reason(stable_key, classification, effective_dependency),
        "firstChangedDependency": effective_dependency,
        "transitiveDependents": _transitive_dependents(stable_key),
        "mirroredPaths": mirrored_paths or [],
        "componentBindingChanges": component_binding_changes or [],
        "storedReferencesRequiringUpdate": reference_partitions["active"],
        "fixtureConstantsRequiringUpdate": reference_partitions["fixtures"],
        "manifestReceiptEntriesRequiringUpdate": reference_partitions["manifests"],
        "historicalReferencesRetained": reference_partitions["historical"],
    }


def _registry_artifacts(
    repository: Path,
    baseline: str,
    reference_index: dict[str, list[dict[str, Any]]],
    *,
    namespace: str,
    module: str,
    manifest_path: str,
    mirror_directory: str | None = None,
) -> list[dict[str, Any]]:
    old_entries = _registry_entries(
        _yaml(_baseline_bytes(repository, baseline, manifest_path))
    )
    new_entries = _registry_entries(
        _yaml(_current_bytes(repository, manifest_path))
    )
    result: list[dict[str, Any]] = []
    for key in sorted(set(old_entries) | set(new_entries)):
        old_entry = old_entries.get(key, {})
        new_entry = new_entries.get(key, {})
        stable_key = namespace + ":" + key
        registry_path = (
            manifest_path.rsplit("/", 1)[0]
            + "/"
            + str(new_entry.get("path", old_entry.get("path", key)))
        )
        mirrors: list[dict[str, Any]] = []
        if mirror_directory is not None:
            mirror_path = mirror_directory + "/" + Path(registry_path).name
            canonical_data = _current_bytes(repository, registry_path)
            mirror_data = _current_bytes(repository, mirror_path)
            mirrors.append(
                {
                    "path": mirror_path,
                    "byteIdentical": (
                        canonical_data is not None
                        and canonical_data == mirror_data
                    ),
                }
            )
        result.append(
            _artifact(
                reference_index,
                stable_key=stable_key,
                kind="canonical-registry-node",
                module=module,
                path=registry_path,
                old=old_entry.get("blueId"),
                new=new_entry.get("blueId"),
                mirrored_paths=mirrors,
            )
        )
    return result


def _package_artifact(
    repository: Path,
    baseline: str,
    reference_index: dict[str, list[dict[str, Any]]],
    *,
    stable_key: str,
    kind: str,
    module: str,
    path: str,
    field: str,
    first_dependency: str,
) -> dict[str, Any]:
    old = _manifest_identity(
        _yaml(_baseline_bytes(repository, baseline, path)), field
    )
    new = _manifest_identity(_yaml(_current_bytes(repository, path)), field)
    return _artifact(
        reference_index,
        stable_key=stable_key,
        kind=kind,
        module=module,
        path=path,
        old=old,
        new=new,
        first_dependency=first_dependency,
    )


def _nested_value(
    document: dict[str, Any] | None,
    *path: str,
) -> str | None:
    value: Any = document
    for segment in path:
        if not isinstance(value, dict):
            return None
        value = value.get(segment)
    return value if isinstance(value, str) else None


def _cyclic_master(document: dict[str, Any] | None) -> str | None:
    if document is None:
        return None
    expected = document.get("expected")
    if not isinstance(expected, dict):
        return None
    components = expected.get("resultingComponents")
    if not isinstance(components, list) or len(components) != 1:
        return None
    component = components[0]
    if not isinstance(component, dict):
        return None
    value = component.get("masterBlueId")
    return value if isinstance(value, str) else None


def _java_string_constant(data: bytes | None, constant: str) -> str | None:
    if data is None:
        return None
    text = data.decode("utf-8", errors="replace")
    pattern = re.compile(
        r"\b" + re.escape(constant)
        + r"\s*=\s*(?:\r?\n\s*)?\"([^\"]+)\"\s*;"
    )
    match = pattern.search(text)
    return match.group(1) if match is not None else None


def _implementation_bindings(
    document: dict[str, Any] | None,
) -> dict[str, str]:
    if document is None:
        return {}
    language = document.get("languageDependency")
    if not isinstance(language, dict):
        raise ValueError("Release manifest is missing languageDependency")
    entries = language.get("inputImplementationBaseline")
    if not isinstance(entries, list):
        raise ValueError("Release manifest is missing inputImplementationBaseline")
    result: dict[str, str] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("Implementation baseline entry must be a mapping")
        path = entry.get("path")
        digest = entry.get("sha256")
        if not isinstance(path, str) or not isinstance(digest, str):
            raise ValueError("Implementation baseline entry requires path and sha256")
        if path in result:
            raise ValueError("Duplicate implementation baseline path: " + path)
        result[path] = digest
    return result


def _aggregate_component_changes(
    old_document: dict[str, Any] | None,
    new_document: dict[str, Any] | None,
) -> list[dict[str, Any]]:
    old_components = (
        old_document.get("components", {}) if old_document is not None else {}
    )
    new_components = (
        new_document.get("components", {}) if new_document is not None else {}
    )
    if not isinstance(old_components, dict) or not isinstance(new_components, dict):
        raise ValueError("Aggregate components must be mappings")
    result: list[dict[str, Any]] = []
    for key in sorted(set(old_components) | set(new_components)):
        old = old_components.get(key)
        new = new_components.get(key)
        if old == new:
            continue
        result.append(
            {
                "component": key,
                "oldValue": old,
                "newValue": new,
            }
        )
    return result


def generate(repository: Path, baseline: str = DEFAULT_BASELINE) -> dict[str, Any]:
    repository = repository.resolve()
    baseline_commit = _revision(repository, baseline)
    head_commit = _revision(repository, "HEAD")
    _require_ancestor(repository, baseline_commit, head_commit)
    reference_index = _reference_index(repository, baseline_commit)
    artifacts: list[dict[str, Any]] = []
    artifacts.append(
        _artifact(
            reference_index,
            stable_key="language:exact-empty-object",
            kind="canonical-value",
            module="blue-language-core",
            path="id({})",
            old=EMPTY_OBJECT_BLUE_ID,
            new=EMPTY_OBJECT_BLUE_ID,
        )
    )
    artifacts.extend(
        _registry_artifacts(
            repository,
            baseline_commit,
            reference_index,
            namespace="language",
            module="blue-language-core",
            manifest_path=LANGUAGE_REGISTRY,
        )
    )
    artifacts.extend(
        _registry_artifacts(
            repository,
            baseline_commit,
            reference_index,
            namespace="contracts",
            module="blue-contracts-core",
            manifest_path=CONTRACTS_REGISTRY,
            mirror_directory=(
                "blue-conformance/src/main/resources/"
                "blue-contracts-closure-1.0/registry"
            ),
        )
    )

    # ScriptedOperation is a fixture-only type. Its constant is checked by the
    # Java harness against DirectBlueIdCalculator over ScriptedOperation.blue;
    # it intentionally does not belong to the runtime registry manifest.
    artifacts.append(
        _artifact(
            reference_index,
            stable_key="closure:ScriptedOperation",
            kind="java-verified-conformance-registry-node",
            module="blue-conformance",
            path=(
                "blue-conformance/src/main/resources/"
                "blue-contracts-closure-1.0/registry/ScriptedOperation.blue"
            ),
            old=_java_string_constant(
                _baseline_bytes(
                    repository, baseline_commit, MOCK_TYPE_BLUE_IDS
                ),
                "MOCK_OPERATION",
            ),
            new=_java_string_constant(
                _current_bytes(repository, MOCK_TYPE_BLUE_IDS),
                "MOCK_OPERATION",
            ),
        )
    )

    old_language_spec = _baseline_bytes(
        repository, baseline_commit, LANGUAGE_SPEC
    )
    new_language_spec = _current_bytes(repository, LANGUAGE_SPEC)
    artifacts.append(
        _artifact(
            reference_index,
            stable_key="document:language-specification",
            kind="specification-sha256",
            module="blue-language-core",
            path=LANGUAGE_SPEC,
            old=_sha256(old_language_spec),
            new=_sha256(new_language_spec),
            first_dependency="exact empty-object normative text",
            mirrored_paths=[
                {
                    "path": (
                        "blue-conformance/src/main/resources/"
                        "language/1.0/spec.md"
                    ),
                    "byteIdentical": (
                        new_language_spec
                        == _current_bytes(
                            repository,
                            "blue-conformance/src/main/resources/"
                            "language/1.0/spec.md",
                        )
                    ),
                }
            ],
        )
    )
    old_contracts_spec = _baseline_bytes(
        repository, baseline_commit, CONTRACTS_SPEC
    )
    new_contracts_spec = _current_bytes(repository, CONTRACTS_SPEC)
    artifacts.append(
        _artifact(
            reference_index,
            stable_key="document:contracts-specification",
            kind="specification-sha256",
            module="blue-contracts-core",
            path=CONTRACTS_SPEC,
            old=_sha256(old_contracts_spec),
            new=_sha256(new_contracts_spec),
            first_dependency="Process Embedded.collectionPaths and Embedded Collection Event Channel normative text",
            mirrored_paths=[
                {
                    "path": (
                        "blue-conformance/src/main/resources/"
                        "contract/1.0/spec.md"
                    ),
                    "byteIdentical": (
                        new_contracts_spec
                        == _current_bytes(
                            repository,
                            "blue-conformance/src/main/resources/"
                            "contract/1.0/spec.md",
                        )
                    ),
                }
            ],
        )
    )

    old_release = _yaml(
        _baseline_bytes(repository, baseline_commit, CONTRACTS_RELEASE)
    )
    new_release = _yaml(_current_bytes(repository, CONTRACTS_RELEASE))
    for stable_key, field, cause in (
        (
            "implementation:cyclic-set-finalizer",
            "cyclicSetFinalizerBaselineIdentity",
            "revised cyclic finalization implementation",
        ),
        (
            "implementation:cyclic-set-proof-verifier",
            "cyclicSetProofVerifierBaselineIdentity",
            "revised cyclic proof verification implementation",
        ),
    ):
        artifacts.append(
            _artifact(
                reference_index,
                stable_key=stable_key,
                kind="implementation-identity",
                module="blue-language-core",
                path=CONTRACTS_RELEASE + "#/languageDependency/" + field,
                old=_nested_value(old_release, "languageDependency", field),
                new=_nested_value(new_release, "languageDependency", field),
                first_dependency=cause,
            )
        )
    artifacts.append(
        _artifact(
            reference_index,
            stable_key="document:identity-constructors",
            kind="normative-input-sha256",
            module="blue-conformance",
            path=IDENTITY_CONSTRUCTORS,
            old=_nested_value(old_release, "identityConstructors", "sha256"),
            new=_nested_value(new_release, "identityConstructors", "sha256"),
            first_dependency="revised exact identity-constructor vectors",
        )
    )
    old_historical_cycle = _yaml(
        _baseline_bytes(repository, baseline_commit, HISTORICAL_CYCLE_FIXTURE)
    )
    new_historical_cycle = _yaml(
        _current_bytes(repository, HISTORICAL_CYCLE_FIXTURE)
    )
    artifacts.append(
        _artifact(
            reference_index,
            stable_key="fixture:c-clo-23-05-a9-to-a10:masterBlueId",
            kind="fixture-cyclic-master",
            module="blue-conformance",
            path=(
                HISTORICAL_CYCLE_FIXTURE
                + "#/expected/resultingComponents/0/masterBlueId"
            ),
            old=_cyclic_master(old_historical_cycle),
            new=_cyclic_master(new_historical_cycle),
            first_dependency="contracts:ProcessEmbedded",
        )
    )
    old_implementations = _implementation_bindings(old_release)
    new_implementations = _implementation_bindings(new_release)
    for path in sorted(set(old_implementations) | set(new_implementations)):
        old = old_implementations.get(path)
        new = new_implementations.get(path)
        if old == new:
            continue
        artifacts.append(
            _artifact(
                reference_index,
                stable_key="implementation:" + path,
                kind="source-sha256",
                module=(
                    "blue-contracts-core"
                    if path.startswith("blue-contracts-core/")
                    else "blue-language-core"
                ),
                path=CONTRACTS_RELEASE
                + "#/languageDependency/inputImplementationBaseline/"
                + path,
                old=old,
                new=new,
                first_dependency="reviewed production source changed: " + path,
            )
        )

    packages = (
        ("package:language-registry", "registry-package", "blue-language-core", LANGUAGE_REGISTRY, "packageIdentity", "language:Dictionary"),
        ("package:language-fixtures", "fixture-package", "blue-conformance", LANGUAGE_FIXTURES, "packageIdentity", "package:language-registry"),
        ("package:contracts-registry", "registry-package", "blue-contracts-core", CONTRACTS_REGISTRY, "packageIdentity", "contracts:ProcessEmbedded"),
        ("package:contracts-ordinary-fixtures", "fixture-package", "blue-conformance", ORDINARY_FIXTURES, "packageIdentity", "package:contracts-registry"),
        ("package:contracts-closure-fixtures", "fixture-package", "blue-conformance", CLOSURE_FIXTURES, "packageIdentity", "package:contracts-ordinary-fixtures"),
        ("package:contracts-oracles", "oracle-package", "blue-conformance", CLOSURE_ORACLES, "packageIdentity", "revised independent oracle vectors"),
        ("package:contracts-gas", "gas-package", "blue-conformance", CONTRACTS_GAS, "packageIdentity", "unchanged normative gas schedule"),
        ("package:contracts-release", "release-identity", "blue-conformance", CONTRACTS_RELEASE, "releaseIdentity", "document:contracts-specification"),
        ("package:language-contracts-aggregate", "release-package", "blue-conformance", AGGREGATE_RELEASE, "packageIdentity", "document:language-specification"),
    )
    for stable_key, kind, module, path, field, dependency in packages:
        artifacts.append(
            _package_artifact(
                repository,
                baseline_commit,
                reference_index,
                stable_key=stable_key,
                kind=kind,
                module=module,
                path=path,
                field=field,
                first_dependency=dependency,
            )
        )

    old_aggregate = _yaml(
        _baseline_bytes(repository, baseline_commit, AGGREGATE_RELEASE)
    )
    new_aggregate = _yaml(_current_bytes(repository, AGGREGATE_RELEASE))
    for artifact in artifacts:
        if artifact["stableKey"] == "package:language-contracts-aggregate":
            artifact["componentBindingChanges"] = _aggregate_component_changes(
                old_aggregate, new_aggregate
            )

    artifacts.sort(key=lambda value: value["stableKey"])
    classifications: dict[str, int] = {
        classification: 0
        for classification in (
            "directly-changed",
            "newly-introduced",
            "obsolete-pre-stable-rc",
            "stale-binding-regenerated",
            "transitively-changed",
            "unchanged-direct",
            "unresolved",
        )
    }
    for artifact in artifacts:
        classification = artifact["classification"]
        classifications[classification] = classifications.get(classification, 0) + 1
    active_reference_count = sum(
        len(value["storedReferencesRequiringUpdate"]) for value in artifacts
    )
    historical_reference_count = sum(
        len(value["historicalReferencesRetained"]) for value in artifacts
    )
    fixture_reference_count = sum(
        len(value["fixtureConstantsRequiringUpdate"]) for value in artifacts
    )
    manifest_reference_count = sum(
        len(value["manifestReceiptEntriesRequiringUpdate"]) for value in artifacts
    )
    unresolved_count = sum(
        1 for value in artifacts if value["classification"] == "unresolved"
    )
    mirror_mismatch_count = sum(
        1
        for value in artifacts
        for mirror in value["mirroredPaths"]
        if not mirror["byteIdentical"]
    )
    no_compatibility_aliases = (
        active_reference_count == 0 and unresolved_count == 0
    )
    return {
        "schema": "blue-identity-impact/1.0",
        "repository": "blue-language-java",
        "baselineRevision": baseline,
        "baselineCommit": baseline_commit,
        # Deliberately avoid embedding HEAD: committing this generated report
        # would otherwise make the checked-in bytes self-referential.  The
        # immutable candidate receipt binds the eventual clean source commit.
        "currentTree": "tracked-and-untracked-worktree",
        "scope": {
            "includedRepositories": ["blue-language-java"],
            "includedSurfaces": [
                "Language canonical registry, fixtures, specification, and release bindings",
                "Contracts canonical registry, ordinary/closure fixtures, oracles, specification, and release bindings",
            ],
            "downstreamClosure": [
                {
                    "repository": "blue-bex-java",
                    "status": "required-after-upstream-candidate-is-sealed",
                    "dependencyCause": "Language/Contracts commit-bound development candidate and exact registry identities",
                },
                {
                    "repository": "blue-coordination-java",
                    "status": "required-after-bex-candidate-is-sealed",
                    "dependencyCause": "Language/Contracts and BEX commit-bound candidates, locks, receipts, fixture packages, and profile identities",
                },
            ],
            "unifiedClosureOwner": "cross-repository implementation report assembled after downstream bindings",
        },
        "identityAuthority": (
            "Registry node BlueIds are read from manifests generated and "
            "verified by DirectBlueIdCalculator; this report does not "
            "recalculate schema-heavy BlueIds in Python."
        ),
        "noCompatibilityAliases": no_compatibility_aliases,
        "summary": {
            "artifactCount": len(artifacts),
            "classifications": dict(sorted(classifications.items())),
            "emptyObjectIdentityUnchanged": True,
            "activeStoredReferenceCount": active_reference_count,
            "historicalReferenceCount": historical_reference_count,
            "fixtureConstantReferenceCount": fixture_reference_count,
            "manifestReceiptReferenceCount": manifest_reference_count,
            "unresolvedArtifactCount": unresolved_count,
            "mirrorMismatchCount": mirror_mismatch_count,
        },
        "artifacts": artifacts,
    }


def render_markdown(report: dict[str, Any]) -> str:
    summary = report["summary"]
    lines = [
        "# Empty-object identity migration",
        "",
        "This document is generated by `generate_identity_impact_inventory.py`.",
        "The machine-readable source is `reports/migration/empty-object-identity-impact.json`.",
        "Its exact old-to-new entries cover the Language/Contracts repository. BEX and Coordination are mandatory downstream companion inventories after their commit-bound candidates are bound; the cross-repository implementation report assembles the unified closure.",
        "",
        "## Result",
        "",
        "The exact empty-object identity remains `" + EMPTY_OBJECT_BLUE_ID + "`.",
        "No old-to-new BlueId aliases or redirects are introduced. Registry BlueIds in this report are read from the Java-verified release manifests.",
        "Active old-identity references remaining: " + str(summary["activeStoredReferenceCount"]) + ". Historical references retained as immutable evidence: " + str(summary["historicalReferenceCount"]) + ".",
        "Unresolved identity surfaces: " + str(summary["unresolvedArtifactCount"]) + ". Canonical/mirror byte mismatches: " + str(summary["mirrorMismatchCount"]) + ".",
        "",
        "| Classification | Count |",
        "| --- | ---: |",
    ]
    for key, count in summary["classifications"].items():
        lines.append("| `" + key + "` | " + str(count) + " |")
    lines.extend(
        [
            "",
            "## Exact identity changes",
            "",
            "| Stable key | Classification | Old | New | First changed dependency |",
            "| --- | --- | --- | --- | --- |",
        ]
    )
    for artifact in report["artifacts"]:
        old = artifact["oldExactIdentity"] or "—"
        new = artifact["newExactIdentity"] or "—"
        dependency = artifact["firstChangedDependency"] or "—"
        lines.append(
            "| `" + artifact["stableKey"] + "` | `"
            + artifact["classification"] + "` | `" + old + "` | `"
            + new + "` | " + dependency.replace("|", "\\|") + " |"
        )
    aggregate = next(
        artifact
        for artifact in report["artifacts"]
        if artifact["stableKey"] == "package:language-contracts-aggregate"
    )
    lines.extend(
        [
            "",
            "## Aggregate component binding changes",
            "",
            "The aggregate package was regenerated from its complete component set; these are the exact changed bindings rather than an inferred single-cause rewrite.",
            "",
            "| Component | Old | New |",
            "| --- | --- | --- |",
        ]
    )
    for change in aggregate["componentBindingChanges"]:
        lines.append(
            "| `" + change["component"] + "` | `"
            + str(change["oldValue"]) + "` | `"
            + str(change["newValue"]) + "` |"
        )
    stale: list[tuple[str, dict[str, Any]]] = []
    historical: list[tuple[str, dict[str, Any]]] = []
    for artifact in report["artifacts"]:
        for reference in artifact["storedReferencesRequiringUpdate"]:
            stale.append((artifact["stableKey"], reference))
        for reference in artifact["historicalReferencesRetained"]:
            historical.append((artifact["stableKey"], reference))
    lines.extend(
        [
            "",
            "## Stored old-identity references",
            "",
            "Every active textual occurrence of a rotated identity is listed here and must reach zero before sealing.",
            "",
            "| Artifact | Reference | Disposition |",
            "| --- | --- | --- |",
        ]
    )
    if not stale:
        lines.append("| — | No remaining old identities found | — |")
    else:
        for stable_key, reference in sorted(
            stale, key=lambda value: (value[1]["path"], value[1]["line"], value[0])
        ):
            lines.append(
                "| `" + stable_key + "` | `" + reference["path"] + ":"
                + str(reference["line"]) + "` | `"
                + reference["disposition"] + "` |"
            )
    lines.extend(
        [
            "",
            "## Retained historical identity references",
            "",
            "These occurrences are deliberately retained in immutable migration or modernization evidence rather than silently rewritten.",
            "",
            "| Artifact | Reference | Disposition |",
            "| --- | --- | --- |",
        ]
    )
    if not historical:
        lines.append("| — | No historical old identities retained | — |")
    else:
        for stable_key, reference in sorted(
            historical,
            key=lambda value: (value[1]["path"], value[1]["line"], value[0]),
        ):
            lines.append(
                "| `" + stable_key + "` | `" + reference["path"] + ":"
                + str(reference["line"]) + "` | `"
                + reference["disposition"] + "` |"
            )
    lines.extend(
        [
            "",
            "## Regeneration order",
            "",
            "1. Finalize Language and Contracts specification bytes.",
            "2. Verify Language core and Contracts runtime registry BlueIds with the Java runtime.",
            "3. Regenerate Language, ordinary Contracts, closure, and oracle fixture packages.",
            "4. Regenerate Contracts and aggregate release manifests.",
            "5. Publish only a clean commit-bound development candidate, then bind downstream repositories to its receipt.",
            "",
            "## Optional downstream closure",
            "",
            "After BEX and Coordination bind their final candidates, generate the untracked cross-repository evidence without making this repository's checked-in report sibling-dependent:",
            "",
            "```bash",
            "python3 blue-conformance/src/main/tools/generate_identity_impact_inventory.py \\",
            "  --repository-root . \\",
            "  --bex-repository-root ../blue-bex-java \\",
            "  --coordination-repository-root ../blue-contract-java \\",
            "  --cross-repository-output build/reports/identity/cross-repository-identity-impact.json",
            "```",
            "",
        ]
    )
    return "\n".join(lines)


def _encoded_json(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _validate_upstream_report(report: dict[str, Any]) -> None:
    summary = report["summary"]
    blockers: list[str] = []
    if summary["unresolvedArtifactCount"]:
        blockers.append(
            "unresolved artifacts=" + str(summary["unresolvedArtifactCount"])
        )
    if summary["mirrorMismatchCount"]:
        blockers.append(
            "mirror mismatches=" + str(summary["mirrorMismatchCount"])
        )
    if summary["activeStoredReferenceCount"]:
        blockers.append(
            "active old-identity references="
            + str(summary["activeStoredReferenceCount"])
        )
    if not report["noCompatibilityAliases"]:
        blockers.append("noCompatibilityAliases is false")
    if blockers:
        raise ValueError(
            "Identity migration inventory is not sealable: "
            + ", ".join(blockers)
        )


def _validate_cross_repository_report(report: dict[str, Any]) -> None:
    summary = report["summary"]
    blockers: list[str] = []
    if summary["dirtyRepositoryCount"]:
        blockers.append(
            "dirty repositories=" + str(summary["dirtyRepositoryCount"])
        )
    if summary["staleUpstreamBindingCount"]:
        blockers.append(
            "stale upstream bindings="
            + str(summary["staleUpstreamBindingCount"])
        )
    if blockers:
        raise ValueError(
            "Cross-repository identity inventory is not sealable: "
            + ", ".join(blockers)
        )


def _write_or_check(path: Path, expected: bytes, check: bool) -> None:
    if check:
        actual = path.read_bytes() if path.is_file() else None
        if actual != expected:
            raise SystemExit("Generated output is stale: " + str(path))
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(expected)


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path.cwd())
    parser.add_argument("--baseline", default=DEFAULT_BASELINE)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--bex-repository-root", type=Path)
    parser.add_argument("--bex-baseline", default="origin/next")
    parser.add_argument("--coordination-repository-root", type=Path)
    parser.add_argument("--coordination-baseline", default="origin/next")
    parser.add_argument(
        "--cross-repository-output",
        type=Path,
        help=(
            "Optional untracked build report. Supplying either sibling root "
            "enables cross-repository spec/manifest/lock/receipt evidence."
        ),
    )
    args = parser.parse_args(list(argv) if argv is not None else None)
    repository = args.repository_root.resolve()
    output = args.output or repository / REPORT_PATH
    summary = args.summary or repository / SUMMARY_PATH
    report = generate(repository, args.baseline)
    if args.check:
        _validate_upstream_report(report)
    _write_or_check(output, _encoded_json(report), args.check)
    _write_or_check(summary, render_markdown(report).encode("utf-8"), args.check)
    siblings: list[tuple[str, Path, str]] = []
    if args.bex_repository_root is not None:
        siblings.append(
            ("bex", args.bex_repository_root, args.bex_baseline)
        )
    if args.coordination_repository_root is not None:
        siblings.append(
            (
                "coordination",
                args.coordination_repository_root,
                args.coordination_baseline,
            )
        )
    if siblings:
        cross_output = args.cross_repository_output or (
            repository
            / "build/reports/identity/cross-repository-identity-impact.json"
        )
        cross_report = generate_cross_repository_closure(siblings, report)
        if args.check:
            _validate_cross_repository_report(cross_report)
        _write_or_check(cross_output, _encoded_json(cross_report), args.check)
        print(
            "CROSS_REPOSITORY_IDENTITY_IMPACT_OK"
            if args.check
            else "CROSS_REPOSITORY_IDENTITY_IMPACT_WRITTEN"
        )
    print("IDENTITY_IMPACT_INVENTORY_OK" if args.check else "IDENTITY_IMPACT_INVENTORY_WRITTEN")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
