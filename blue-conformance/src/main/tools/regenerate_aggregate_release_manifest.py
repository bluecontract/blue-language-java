#!/usr/bin/env python3
"""Rebuild the aggregate Language/Contracts manifest from authoritative inputs."""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
from pathlib import Path, PurePosixPath
import re
from typing import Any, Iterable, NamedTuple, Sequence

import yaml

from jcs import dumps as jcs_dumps


MANIFEST_PATH = (
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
LANGUAGE_REGISTRY = (
    "blue-language-core/src/main/resources/registry/"
    "blue-language-1.0/manifest.yaml"
)
LANGUAGE_FIXTURES = (
    "blue-conformance/src/main/resources/blue-language-1.0/fixtures/manifest.yaml"
)
CONTRACTS_REGISTRY = (
    "blue-contracts-core/src/main/resources/registry/"
    "blue-contracts-1.0/manifest.yaml"
)
CONTRACTS_FIXTURES = (
    "blue-conformance/src/main/resources/"
    "blue-contracts-closure-1.0/fixtures/manifest.yaml"
)
CONTRACTS_REPRESENTATION = (
    "blue-conformance/src/main/resources/blue-contracts-representation-1.0/manifest.json"
)
CONTRACTS_GAS = (
    "blue-contracts-core/src/main/resources/blue/language/processor/"
    "contracts-gas-1.0.yaml"
)

PACKAGE_NAME = "blue-language-contracts-embedded-modules-collection-paths"
PACKAGE_STATUS = "final-implementation-baseline-amendment"
IDENTITY_ALGORITHM = {
    "digest": "sha256",
    "encoding": "UTF-8 canonical JSON with sorted keys",
    "normalization": "packageIdentity is null before hashing",
}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
PACKAGE_ID_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


class ManifestSource(NamedTuple):
    """An identity-bearing nested package copied into the aggregate."""

    name: str
    manifest_path: str
    logical_prefix: str
    entries_field: str
    identity_null_fields: tuple[str, ...]
    entries_have_sizes: bool
    supplemental_paths: tuple[str, ...] = ()

    @property
    def source_root(self) -> str:
        return str(PurePosixPath(self.manifest_path).parent)


LANGUAGE_REGISTRY_SOURCE = ManifestSource(
    "Language registry",
    LANGUAGE_REGISTRY,
    "conformance/language/registry",
    "entries",
    ("packageIdentity", "fixturePackageIdentity"),
    False,
)
LANGUAGE_FIXTURE_SOURCE = ManifestSource(
    "Language fixtures",
    LANGUAGE_FIXTURES,
    "conformance/language/fixtures",
    "files",
    ("packageIdentity",),
    True,
    # The fixture-local registry manifest is listed in the authenticated support inventory.
)
CONTRACTS_REGISTRY_SOURCE = ManifestSource(
    "Contracts registry",
    CONTRACTS_REGISTRY,
    "conformance/contracts/registry",
    "entries",
    ("packageIdentity", "fixturePackageIdentity"),
    False,
)
CONTRACTS_FIXTURE_SOURCE = ManifestSource(
    "Contracts fixtures",
    CONTRACTS_FIXTURES,
    "conformance/contracts/fixtures",
    "files",
    ("packageIdentity",),
    True,
)
CONTRACTS_REPRESENTATION_SOURCE = ManifestSource(
    "Contracts representation fixtures",
    CONTRACTS_REPRESENTATION,
    "conformance/contracts/representation",
    "files",
    ("packageIdentity",),
    True,
)
MANIFEST_SOURCES = (
    CONTRACTS_FIXTURE_SOURCE,
    CONTRACTS_REPRESENTATION_SOURCE,
    CONTRACTS_REGISTRY_SOURCE,
    LANGUAGE_FIXTURE_SOURCE,
    LANGUAGE_REGISTRY_SOURCE,
)

# These files are aggregate inputs in their own right rather than members of a
# nested package. Missing obsolete archive-only helpers are deliberately absent:
# an aggregate regeneration must not preserve a path merely because an older
# aggregate happened to list it.
DIRECT_FILES = (
    ("conformance/contracts/gas-manifest.yaml", CONTRACTS_GAS),
    (
        "docs/embedded-process-modules-and-collections-summary.md",
        "docs/embedded-process-modules-and-collections-summary.md",
    ),
    (
        "docs/enum-normalization-registry-correction.md",
        "docs/enum-normalization-registry-correction.md",
    ),
    (
        "specifications/blue-contracts-and-processor-specification-1.0.md",
        CONTRACTS_SPEC,
    ),
    (
        "specifications/blue-language-specification-1.0.md",
        LANGUAGE_SPEC,
    ),
)


def _yaml(path: Path) -> dict[str, Any]:
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ValueError("Missing authoritative manifest: " + str(path)) from error
    if not isinstance(value, dict):
        raise ValueError("Expected YAML mapping: " + str(path))
    return value


def _text(value: dict[str, Any], field: str, source: str) -> str:
    result = value.get(field)
    if not isinstance(result, str) or not result:
        raise ValueError(source + " has no non-empty " + field)
    return result


def _count(value: dict[str, Any], field: str, source: str) -> int:
    result = value.get(field)
    if not isinstance(result, int) or isinstance(result, bool) or result < 0:
        raise ValueError(source + " has no non-negative integer " + field)
    return result


def _safe_relative_path(value: Any, source: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(source + " has an empty or non-text path")
    if "\\" in value or "\x00" in value:
        raise ValueError(source + " has a non-portable path: " + repr(value))
    parts = value.split("/")
    candidate = PurePosixPath(value)
    if (
        candidate.is_absolute()
        or any(part in {"", ".", ".."} for part in parts)
        or candidate.as_posix() != value
    ):
        raise ValueError(source + " has an unsafe path: " + value)
    return value


def _contained_file(root: Path, relative: str, source: str) -> Path:
    safe = _safe_relative_path(relative, source)
    root = root.resolve()
    candidate = root.joinpath(*PurePosixPath(safe).parts)
    try:
        resolved = candidate.resolve(strict=True)
    except FileNotFoundError as error:
        raise ValueError(source + " references missing file " + safe) from error
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ValueError(source + " path escapes its source root: " + safe) from error
    if candidate.is_symlink() or not resolved.is_file():
        raise ValueError(source + " does not reference a regular authored file: " + safe)
    return resolved


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _inventory_entry(logical_path: str, source_path: Path) -> dict[str, str | int]:
    return {
        "path": _safe_relative_path(logical_path, "Aggregate inventory"),
        "sha256": _sha256(source_path),
        "bytes": source_path.stat().st_size,
    }


def _path_summary(paths: Sequence[str], limit: int = 10) -> str:
    visible = list(paths[:limit])
    remainder = len(paths) - len(visible)
    if remainder == 0:
        return repr(visible)
    return repr(visible) + " (+" + str(remainder) + " more)"


def _package_identity(
    manifest: dict[str, Any], null_fields: Sequence[str], source: str
) -> str:
    declared = _text(manifest, "packageIdentity", source)
    if PACKAGE_ID_RE.fullmatch(declared) is None:
        raise ValueError(source + " has malformed packageIdentity: " + declared)
    payload = deepcopy(manifest)
    for field in null_fields:
        if field not in payload:
            raise ValueError(source + " lacks identity-normalized field " + field)
        payload[field] = None
    expected = "sha256:" + hashlib.sha256(jcs_dumps(payload)).hexdigest()
    if declared != expected:
        raise ValueError(
            source + " packageIdentity is stale: expected " + expected + ", got " + declared
        )
    return declared


def _declared_paths(
    source: ManifestSource, manifest: dict[str, Any], source_root: Path
) -> tuple[list[str], list[dict[str, str | int]]]:
    raw_entries = manifest.get(source.entries_field)
    if not isinstance(raw_entries, list):
        raise ValueError(source.name + " has no " + source.entries_field + " list")

    declared: list[str] = []
    aggregate_entries: list[dict[str, str | int]] = []
    for ordinal, raw_entry in enumerate(raw_entries):
        label = f"{source.name} {source.entries_field}[{ordinal}]"
        if not isinstance(raw_entry, dict):
            raise ValueError(label + " is not a mapping")
        relative = _safe_relative_path(raw_entry.get("path"), label)
        declared.append(relative)
        path = _contained_file(source_root, relative, label)

        declared_hash = raw_entry.get("sha256")
        if not isinstance(declared_hash, str) or SHA256_RE.fullmatch(declared_hash) is None:
            raise ValueError(label + " has malformed sha256")
        actual_hash = _sha256(path)
        if declared_hash != actual_hash:
            raise ValueError(
                label + " sha256 is stale: expected " + actual_hash + ", got " + declared_hash
            )

        if source.entries_have_sizes:
            declared_size = raw_entry.get("bytes")
            if (
                not isinstance(declared_size, int)
                or isinstance(declared_size, bool)
                or declared_size < 0
            ):
                raise ValueError(label + " has malformed bytes")
            actual_size = path.stat().st_size
            if declared_size != actual_size:
                raise ValueError(
                    label
                    + " byte count is stale: expected "
                    + str(actual_size)
                    + ", got "
                    + str(declared_size)
                )

        aggregate_entries.append(
            _inventory_entry(source.logical_prefix + "/" + relative, path)
        )

    if declared != sorted(declared):
        raise ValueError(source.name + " paths are not sorted")
    if len(declared) != len(set(declared)):
        raise ValueError(source.name + " repeats a path")
    return declared, aggregate_entries


def _source_tree_paths(source_root: Path, source: ManifestSource) -> set[str]:
    result: set[str] = set()
    for path in sorted(source_root.rglob("*")):
        if path.is_symlink():
            raise ValueError(source.name + " source tree contains a symbolic link: " + str(path))
        if not path.is_file():
            continue
        relative = path.relative_to(source_root).as_posix()
        if relative == PurePosixPath(source.manifest_path).name:
            continue
        result.add(_safe_relative_path(relative, source.name + " source tree"))
    return result


def _verified_manifest_source(
    repository: Path, source: ManifestSource
) -> tuple[dict[str, Any], list[dict[str, str | int]]]:
    source_root = repository / source.source_root
    manifest_path = repository / source.manifest_path
    manifest = _yaml(manifest_path)
    _package_identity(manifest, source.identity_null_fields, source.name)
    declared, aggregate_entries = _declared_paths(source, manifest, source_root)

    supplemental: list[str] = []
    for relative in source.supplemental_paths:
        safe = _safe_relative_path(relative, source.name + " supplemental inventory")
        path = _contained_file(source_root, safe, source.name + " supplemental inventory")
        supplemental.append(safe)
        aggregate_entries.append(
            _inventory_entry(source.logical_prefix + "/" + safe, path)
        )

    expected_tree = set(declared) | set(supplemental)
    actual_tree = _source_tree_paths(source_root, source)
    missing = sorted(expected_tree - actual_tree)
    extra = sorted(actual_tree - expected_tree)
    if missing:
        raise ValueError(
            source.name
            + " source tree is missing declared paths: "
            + _path_summary(missing)
        )
    if extra:
        raise ValueError(
            source.name + " source tree has unmanifested paths: " + _path_summary(extra)
        )

    aggregate_entries.append(
        _inventory_entry(source.logical_prefix + "/" + manifest_path.name, manifest_path)
    )
    return manifest, aggregate_entries


def _registry_blue_id(manifest: dict[str, Any], key: str) -> str:
    for entry in manifest.get("entries", []):
        if isinstance(entry, dict) and entry.get("key") == key:
            return _text(entry, "blueId", "Contracts registry entry " + key)
    raise ValueError("Contracts registry has no entry " + key)


def _validated_gas_manifest(repository: Path) -> dict[str, Any]:
    gas = _yaml(repository / CONTRACTS_GAS)
    _package_identity(gas, ("packageIdentity",), "Contracts gas manifest")
    return gas


def _authoritative_state(
    repository: Path,
) -> tuple[dict[str, str | int], list[dict[str, str | int]]]:
    repository = repository.resolve()
    verified: dict[str, dict[str, Any]] = {}
    inventory: list[dict[str, str | int]] = []
    for source in MANIFEST_SOURCES:
        manifest, entries = _verified_manifest_source(repository, source)
        verified[source.name] = manifest
        inventory.extend(entries)

    language_registry = verified[LANGUAGE_REGISTRY_SOURCE.name]
    language_fixtures = verified[LANGUAGE_FIXTURE_SOURCE.name]
    contracts_registry = verified[CONTRACTS_REGISTRY_SOURCE.name]
    contracts_fixtures = verified[CONTRACTS_FIXTURE_SOURCE.name]
    representation = verified[CONTRACTS_REPRESENTATION_SOURCE.name]
    representation_count = _count(representation, "executableFixtureCount", CONTRACTS_REPRESENTATION)
    if representation_count != len(representation["files"]):
        raise ValueError("Contracts representation executable fixture count is stale")
    if representation.get("specificationSha256") != _sha256(repository / CONTRACTS_SPEC):
        raise ValueError("Contracts representation specification identity is stale")
    contracts_gas = _validated_gas_manifest(repository)

    if language_fixtures.get("registryPackageIdentity") != language_registry.get(
        "packageIdentity"
    ):
        raise ValueError("Language fixture registryPackageIdentity is stale")
    if language_registry.get("fixturePackageIdentity") != language_fixtures.get(
        "packageIdentity"
    ):
        raise ValueError("Language registry fixturePackageIdentity is stale")
    if contracts_fixtures.get("registryPackageIdentity") != contracts_registry.get(
        "packageIdentity"
    ):
        raise ValueError("Contracts fixture registryPackageIdentity is stale")

    components: dict[str, str | int] = {
        "languageSpecificationSha256": _sha256(repository / LANGUAGE_SPEC),
        "languageRegistryPackageIdentity": _text(
            language_registry, "packageIdentity", LANGUAGE_REGISTRY
        ),
        "languageFixturePackageIdentity": _text(
            language_fixtures, "packageIdentity", LANGUAGE_FIXTURES
        ),
        "languageVectorCount": _count(
            language_fixtures, "vectorCount", LANGUAGE_FIXTURES
        ),
        "languageBehaviorFixtureCount": _count(
            language_fixtures, "behaviorFixtureCount", LANGUAGE_FIXTURES
        ),
        "contractsSpecificationSha256": _sha256(repository / CONTRACTS_SPEC),
        "contractsRegistryPackageIdentity": _text(
            contracts_registry, "packageIdentity", CONTRACTS_REGISTRY
        ),
        "contractsFixturePackageIdentity": _text(
            contracts_fixtures, "packageIdentity", CONTRACTS_FIXTURES
        ),
        "contractsRepresentationFixturePackageIdentity": _text(
            representation, "packageIdentity", CONTRACTS_REPRESENTATION
        ),
        "contractsRepresentationFixtureCount": representation_count,
        "contractsAggregateExecutableFixtureCount": representation_count + _count(
            contracts_fixtures, "totalExecutableFixtureCount", CONTRACTS_FIXTURES
        ),
        "contractsGasPackageIdentity": _text(
            contracts_gas, "packageIdentity", CONTRACTS_GAS
        ),
        "contractsVectorCount": _count(
            contracts_fixtures, "vectorCount", CONTRACTS_FIXTURES
        ),
        "contractsBehaviorFixtureCount": _count(
            contracts_fixtures,
            "ordinaryBehaviorFixtureCount",
            CONTRACTS_FIXTURES,
        ),
        "contractsGasFixtureCount": _count(
            contracts_fixtures, "ordinaryGasFixtureCount", CONTRACTS_FIXTURES
        ),
        "contractsTotalExecutableFixtureCount": _count(
            contracts_fixtures,
            "totalExecutableFixtureCount",
            CONTRACTS_FIXTURES,
        ),
        "contractsClosureFixtureCount": _count(
            contracts_fixtures, "closureFixtureCount", CONTRACTS_FIXTURES
        ),
        "processEmbeddedBlueId": _registry_blue_id(
            contracts_registry, "ProcessEmbedded"
        ),
    }

    for logical, relative in DIRECT_FILES:
        path = _contained_file(repository, relative, "Direct aggregate inventory")
        inventory.append(_inventory_entry(logical, path))

    inventory.sort(key=lambda entry: str(entry["path"]))
    paths = [str(entry["path"]) for entry in inventory]
    if len(paths) != len(set(paths)):
        duplicates = sorted(path for path in set(paths) if paths.count(path) > 1)
        raise ValueError(
            "Aggregate source inventory repeats paths: " + _path_summary(duplicates)
        )
    return components, inventory


def authoritative_components(repository: Path) -> dict[str, str | int]:
    components, _ = _authoritative_state(repository)
    return components


def build_manifest(repository: Path) -> dict[str, Any]:
    components, files = _authoritative_state(repository)
    manifest: dict[str, Any] = {
        "package": PACKAGE_NAME,
        "specificationVersions": {"language": "1.0", "contracts": "1.0"},
        "status": PACKAGE_STATUS,
        "components": components,
        "identityAlgorithm": dict(IDENTITY_ALGORITHM),
        "files": files,
        "packageIdentity": None,
    }
    manifest["packageIdentity"] = "sha256:" + hashlib.sha256(
        jcs_dumps(manifest)
    ).hexdigest()
    return manifest


def expected_manifest(repository: Path) -> bytes:
    payload = build_manifest(repository.resolve())
    rendered = yaml.safe_dump(
        payload,
        sort_keys=False,
        allow_unicode=True,
        width=120,
    )
    return rendered.encode("utf-8")


def _aggregate_inventory(
    manifest: dict[str, Any], source: str
) -> list[dict[str, str | int]]:
    raw_files = manifest.get("files")
    if not isinstance(raw_files, list):
        raise ValueError(source + " has no files list")
    result: list[dict[str, str | int]] = []
    paths: list[str] = []
    for ordinal, raw_entry in enumerate(raw_files):
        label = f"{source} files[{ordinal}]"
        if not isinstance(raw_entry, dict):
            raise ValueError(label + " is not a mapping")
        path = _safe_relative_path(raw_entry.get("path"), label)
        digest = raw_entry.get("sha256")
        size = raw_entry.get("bytes")
        if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None:
            raise ValueError(label + " has malformed sha256")
        if not isinstance(size, int) or isinstance(size, bool) or size < 0:
            raise ValueError(label + " has malformed bytes")
        paths.append(path)
        result.append({"path": path, "sha256": digest, "bytes": size})
    if paths != sorted(paths):
        raise ValueError(source + " file paths are not sorted")
    if len(paths) != len(set(paths)):
        raise ValueError(source + " repeats a file path")
    return result


def validate_aggregate_manifest(actual: bytes, expected: bytes) -> None:
    """Explain structural staleness without ever using it as generation input."""

    try:
        actual_payload = yaml.safe_load(actual)
        expected_payload = yaml.safe_load(expected)
    except yaml.YAMLError as error:
        raise ValueError("Aggregate manifest is not valid YAML") from error
    if not isinstance(actual_payload, dict) or not isinstance(expected_payload, dict):
        raise ValueError("Aggregate manifest must be a mapping")
    actual_files = _aggregate_inventory(actual_payload, "Aggregate manifest")
    expected_files = _aggregate_inventory(expected_payload, "Expected aggregate manifest")
    actual_by_path = {str(entry["path"]): entry for entry in actual_files}
    expected_by_path = {str(entry["path"]): entry for entry in expected_files}
    missing = sorted(set(expected_by_path) - set(actual_by_path))
    extra = sorted(set(actual_by_path) - set(expected_by_path))
    if missing:
        raise ValueError("Aggregate manifest is missing files: " + _path_summary(missing))
    if extra:
        raise ValueError(
            "Aggregate manifest has stale extra files: " + _path_summary(extra)
        )
    stale = sorted(
        path
        for path in expected_by_path
        if actual_by_path[path] != expected_by_path[path]
    )
    if stale:
        raise ValueError(
            "Aggregate manifest has stale file metadata: " + _path_summary(stale)
        )
    if actual_payload != expected_payload:
        raise ValueError("Aggregate manifest metadata or packageIdentity is stale")


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path.cwd())
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(list(argv) if argv is not None else None)
    repository = args.repository_root.resolve()
    manifest = repository / MANIFEST_PATH
    expected = expected_manifest(repository)
    if args.check:
        try:
            actual = manifest.read_bytes()
        except FileNotFoundError as error:
            raise SystemExit("Aggregate release manifest is missing") from error
        try:
            validate_aggregate_manifest(actual, expected)
        except ValueError as error:
            raise SystemExit(str(error)) from error
        if actual != expected:
            raise SystemExit("Aggregate release manifest serialization is stale")
        print("AGGREGATE_RELEASE_MANIFEST_OK")
    else:
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_bytes(expected)
        print("AGGREGATE_RELEASE_MANIFEST_WRITTEN")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
