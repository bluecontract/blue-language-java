#!/usr/bin/env python3
"""Bind the aggregate Language/Contracts manifest to authoritative inputs."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
from typing import Any, Iterable

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
CONTRACTS_GAS = (
    "blue-contracts-core/src/main/resources/blue/language/processor/"
    "contracts-gas-1.0.yaml"
)


def _yaml(path: Path) -> dict[str, Any]:
    value = yaml.safe_load(path.read_text(encoding="utf-8"))
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


def _registry_blue_id(manifest: dict[str, Any], key: str) -> str:
    for entry in manifest.get("entries", []):
        if isinstance(entry, dict) and entry.get("key") == key:
            return _text(entry, "blueId", "Contracts registry entry " + key)
    raise ValueError("Contracts registry has no entry " + key)


def authoritative_components(repository: Path) -> dict[str, str | int]:
    repository = repository.resolve()
    language_registry = _yaml(repository / LANGUAGE_REGISTRY)
    language_fixtures = _yaml(repository / LANGUAGE_FIXTURES)
    contracts_registry = _yaml(repository / CONTRACTS_REGISTRY)
    contracts_fixtures = _yaml(repository / CONTRACTS_FIXTURES)
    contracts_gas = _yaml(repository / CONTRACTS_GAS)
    return {
        "languageSpecificationSha256": hashlib.sha256(
            (repository / LANGUAGE_SPEC).read_bytes()
        ).hexdigest(),
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
        "contractsSpecificationSha256": hashlib.sha256(
            (repository / CONTRACTS_SPEC).read_bytes()
        ).hexdigest(),
        "contractsRegistryPackageIdentity": _text(
            contracts_registry, "packageIdentity", CONTRACTS_REGISTRY
        ),
        "contractsFixturePackageIdentity": _text(
            contracts_fixtures, "packageIdentity", CONTRACTS_FIXTURES
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
        "contractsClosureFixtureCount": _count(
            contracts_fixtures, "closureFixtureCount", CONTRACTS_FIXTURES
        ),
        "contractsTotalExecutableFixtureCount": _count(
            contracts_fixtures,
            "totalExecutableFixtureCount",
            CONTRACTS_FIXTURES,
        ),
        "processEmbeddedBlueId": _registry_blue_id(
            contracts_registry, "ProcessEmbedded"
        ),
    }


def _replace_component(text: str, field: str, value: str | int) -> str:
    pattern = re.compile(r"^  " + re.escape(field) + r":.*$", re.MULTILINE)
    replacement = "  " + field + ": " + str(value)
    updated, count = pattern.subn(replacement, text)
    if count > 1:
        raise ValueError("Aggregate manifest repeats component " + field)
    if count == 1:
        return updated
    anchor = re.compile(r"^  contractsGasFixtureCount:.*$", re.MULTILINE)
    updated, anchor_count = anchor.subn(lambda match: match.group(0) + "\n" + replacement, text)
    if anchor_count != 1:
        raise ValueError("Cannot insert aggregate component " + field)
    return updated


def expected_manifest(repository: Path) -> bytes:
    repository = repository.resolve()
    manifest = repository / MANIFEST_PATH
    text = manifest.read_text(encoding="utf-8")
    for field, value in authoritative_components(repository).items():
        text = _replace_component(text, field, value)
    without_identity, count = re.subn(
        r"^packageIdentity:.*$",
        "packageIdentity: null",
        text,
        flags=re.MULTILINE,
    )
    if count != 1:
        raise ValueError("Aggregate manifest must have one root packageIdentity")
    payload = yaml.safe_load(without_identity)
    if not isinstance(payload, dict):
        raise ValueError("Aggregate manifest must be a mapping")
    identity = "sha256:" + hashlib.sha256(jcs_dumps(payload)).hexdigest()
    result = re.sub(
        r"^packageIdentity:.*$",
        "packageIdentity: " + identity,
        without_identity,
        flags=re.MULTILINE,
    )
    return (result.rstrip("\n") + "\n").encode("utf-8")


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path.cwd())
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(list(argv) if argv is not None else None)
    repository = args.repository_root.resolve()
    manifest = repository / MANIFEST_PATH
    expected = expected_manifest(repository)
    if args.check:
        if manifest.read_bytes() != expected:
            raise SystemExit("Generated aggregate release manifest is stale")
        print("AGGREGATE_RELEASE_MANIFEST_OK")
    else:
        manifest.write_bytes(expected)
        print("AGGREGATE_RELEASE_MANIFEST_WRITTEN")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
