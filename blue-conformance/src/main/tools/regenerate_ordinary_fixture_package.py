#!/usr/bin/env python3
"""Regenerate the deterministic ordinary Contracts fixture package overlay.

The command owns exactly the generated ordinary C-EVO tranche, ordinary
vector coverage and fixture manifest, and the reverse fixture binding in the
core registry manifest.  Gas, registry entries, schemas, support documents,
aggregate manifests, and every Blue Language artifact are read-only inputs.
"""
from __future__ import annotations

from argparse import ArgumentParser
from copy import deepcopy
from pathlib import Path
import hashlib
import json
import os
import re
import sys
import tempfile
from typing import Any

sys.dont_write_bytecode = True

import yaml

from generate_contract_evolution_fixtures import render_fixtures
from jcs import dumps as jcs_dumps


TOOLS_ROOT = Path(__file__).resolve().parent
CANONICAL_CLOSURE_EVO_ROOT = (
    TOOLS_ROOT.parent
    / "resources/blue-contracts-closure-1.0/fixtures/evo"
)

EXPECTED_EXECUTABLE_COUNT = 170
EXPECTED_BEHAVIOR_COUNT = 112
EXPECTED_GAS_COUNT = 58
EXPECTED_VECTOR_COUNT = 114
EXPECTED_EVO_NAMES = frozenset(
    {
        *(f"c-evo-{ordinal:02d}.yaml" for ordinal in range(1, 7)),
        "c-evo-07-checkpoint.yaml",
        "c-evo-07-initialized.yaml",
        "c-evo-07-terminated.yaml",
        *(f"c-evo-{ordinal:02d}.yaml" for ordinal in range(8, 11)),
        *(f"c-evo-{ordinal:02d}.yaml" for ordinal in range(14, 18)),
    }
)
EXPECTED_EVO_VECTORS = frozenset(
    {
        *(f"C-EVO-{ordinal:02d}" for ordinal in range(1, 11)),
        *(f"C-EVO-{ordinal:02d}" for ordinal in range(14, 18)),
    }
)
SUPPORT_PATHS = (
    "CONTROL-LANGUAGE.md",
    "HARNESS.md",
    "README.md",
    "TRACE-SCHEMA.md",
    "fixture-schema.yaml",
    "projection-catalog.yaml",
    "vector-coverage.yaml",
)


class RegenerationFailure(RuntimeError):
    """Raised when the narrow ordinary package cannot be rebuilt safely."""


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def load_mapping(content: bytes, label: str) -> dict[str, Any]:
    value = yaml.safe_load(content)
    if not isinstance(value, dict):
        raise RegenerationFailure(f"expected YAML mapping: {label}")
    return value


def yaml_bytes(value: Any) -> bytes:
    return yaml.safe_dump(
        value,
        sort_keys=False,
        allow_unicode=True,
        width=120,
    ).encode("utf-8")


def package_identity(
    value: dict[str, Any], normalized_fields: tuple[str, ...]
) -> str:
    clone = json.loads(json.dumps(value))
    for field in normalized_fields:
        if field not in clone:
            raise RegenerationFailure(
                f"identity-bearing manifest lacks normalized field: {field}"
            )
        clone[field] = None
    return "sha256:" + sha256_bytes(jcs_dumps(clone))


def verified_identity(
    value: dict[str, Any],
    label: str,
    normalized_fields: tuple[str, ...] = ("packageIdentity",),
) -> str:
    expected = value.get("packageIdentity")
    actual = package_identity(value, normalized_fields)
    if expected != actual:
        raise RegenerationFailure(
            f"{label} packageIdentity mismatch: declared={expected!r}, "
            f"computed={actual!r}"
        )
    return actual


def replace_top_level_scalar(content: bytes, field: str, value: str) -> bytes:
    text = content.decode("utf-8")
    pattern = re.compile(rf"^{re.escape(field)}:[^\n]*(?:\n|$)", re.MULTILINE)
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise RegenerationFailure(
            f"registry manifest must contain exactly one top-level {field}"
        )
    replacement = f"{field}: {value}\n"
    return (
        text[: matches[0].start()]
        + replacement
        + text[matches[0].end() :]
    ).encode("utf-8")


def fixture_entry(relative: str, role: str, content: bytes) -> dict[str, Any]:
    return {
        "path": relative,
        "role": role,
        "sha256": sha256_bytes(content),
        "bytes": len(content),
    }


def rendered_evolution() -> dict[str, bytes]:
    rendered = render_fixtures()
    if set(rendered) != EXPECTED_EVO_NAMES:
        raise RegenerationFailure(
            "ordinary C-EVO generator inventory mismatch: "
            f"actual={sorted(rendered)}"
        )
    return rendered


def verify_existing_evolution_inventory(
    fixture_root: Path, rendered: dict[str, bytes]
) -> None:
    evo_root = fixture_root / "evo"
    existing = {path.name for path in evo_root.glob("c-evo-*.yaml")}
    stale = existing - set(rendered)
    if stale:
        raise RegenerationFailure(
            f"unexpected stale ordinary C-EVO fixtures: {sorted(stale)}"
        )


def verify_closure_mirror(
    rendered: dict[str, bytes], closure_evo_root: Path
) -> None:
    if not closure_evo_root.is_dir():
        raise RegenerationFailure(
            f"closure C-EVO mirror does not exist: {closure_evo_root}"
        )
    existing = {path.name for path in closure_evo_root.glob("c-evo-*.yaml")}
    if existing != set(rendered):
        raise RegenerationFailure(
            "closure C-EVO mirror inventory mismatch: "
            f"actual={sorted(existing)}"
        )
    different = [
        name
        for name, content in rendered.items()
        if (closure_evo_root / name).read_bytes() != content
    ]
    if different:
        raise RegenerationFailure(
            f"ordinary/closure C-EVO byte parity failed: {different}"
        )


def candidate_fixture_files(
    fixture_root: Path,
    rendered: dict[str, bytes],
) -> dict[str, tuple[bytes, dict[str, Any]]]:
    overrides = {f"evo/{name}": content for name, content in rendered.items()}
    candidates: dict[str, tuple[bytes, dict[str, Any]]] = {}
    paths = {
        path.relative_to(fixture_root).as_posix(): path
        for path in fixture_root.rglob("*.yaml")
    }
    for relative in sorted(set(paths) | set(overrides)):
        content = overrides.get(relative)
        if content is None:
            content = paths[relative].read_bytes()
        value = load_mapping(content, relative)
        vectors = value.get("vectors")
        if not isinstance(vectors, list):
            continue
        if not vectors or not all(
            isinstance(vector, str) and vector for vector in vectors
        ):
            raise RegenerationFailure(
                f"executable fixture has invalid vectors: {relative}"
            )
        candidates[relative] = (content, value)
    return candidates


def build_vector_coverage(
    fixtures: dict[str, tuple[bytes, dict[str, Any]]]
) -> dict[str, Any]:
    vectors: dict[str, list[str]] = {}
    for relative in sorted(fixtures):
        for vector in fixtures[relative][1]["vectors"]:
            vectors.setdefault(vector, []).append(relative)
    ordered = {key: vectors[key] for key in sorted(vectors)}
    if len(ordered) != EXPECTED_VECTOR_COUNT:
        raise RegenerationFailure(
            f"ordinary vector count={len(ordered)}; "
            f"expected {EXPECTED_VECTOR_COUNT}"
        )
    actual_evolution = {
        vector for vector in ordered if vector.startswith("C-EVO-")
    }
    if actual_evolution != EXPECTED_EVO_VECTORS:
        raise RegenerationFailure(
            "ordinary C-EVO vector inventory mismatch: "
            f"actual={sorted(actual_evolution)}"
        )
    for vector in EXPECTED_EVO_VECTORS:
        if not all(path.startswith("evo/c-evo-") for path in ordered[vector]):
            raise RegenerationFailure(
                f"ordinary {vector} is bound outside the generated C-EVO tranche"
            )
    return {"specification": "blue-contracts/1.0", "vectors": ordered}


def build_fixture_manifest(
    fixture_root: Path,
    fixtures: dict[str, tuple[bytes, dict[str, Any]]],
    vector_coverage_content: bytes,
    registry_package_identity: str,
    gas_manifest: dict[str, Any],
    gas_manifest_content: bytes,
) -> dict[str, Any]:
    behavior_count = sum(
        value.get("category") != "gas" for _, value in fixtures.values()
    )
    gas_count = len(fixtures) - behavior_count
    observed = (len(fixtures), behavior_count, gas_count)
    expected = (
        EXPECTED_EXECUTABLE_COUNT,
        EXPECTED_BEHAVIOR_COUNT,
        EXPECTED_GAS_COUNT,
    )
    if observed != expected:
        raise RegenerationFailure(
            "ordinary fixture counts mismatch: "
            f"actual={observed}, expected={expected}"
        )

    files: list[dict[str, Any]] = []
    for relative in SUPPORT_PATHS:
        if relative == "vector-coverage.yaml":
            content = vector_coverage_content
        else:
            path = fixture_root / relative
            if not path.is_file():
                raise RegenerationFailure(
                    f"ordinary fixture support file is missing: {relative}"
                )
            content = path.read_bytes()
        files.append(fixture_entry(relative, "support", content))
    for relative, (content, value) in fixtures.items():
        role = "gas-fixture" if value.get("category") == "gas" else "behavior-fixture"
        files.append(fixture_entry(relative, role, content))
    files.sort(key=lambda item: item["path"])

    manifest: dict[str, Any] = {
        "fixturePackage": "blue-contracts-conformance",
        "specificationVersion": "1.0",
        "schemaVersion": "blue-contracts-fixture/1.0",
        "registryPackageIdentity": registry_package_identity,
        "vectorCount": EXPECTED_VECTOR_COUNT,
        "behaviorFixtureCount": behavior_count,
        "gasFixtureCount": gas_count,
        "files": files,
        "packageIdentityAlgorithm": {
            "digest": "sha256",
            "encoding": "UTF-8 canonical JSON with sorted keys",
            "normalization": "packageIdentity is null before hashing",
            "lineEndings": "LF",
        },
        "packageIdentity": None,
        "gasSchedule": gas_manifest.get("schedule"),
        "gasManifestPackageIdentity": gas_manifest["packageIdentity"],
        "gasManifestSha256": sha256_bytes(gas_manifest_content),
    }
    manifest["packageIdentity"] = package_identity(
        manifest, ("packageIdentity",)
    )
    return manifest


def build_candidate(
    fixture_root: Path,
    registry_manifest_path: Path,
    gas_manifest_path: Path,
    *,
    closure_evo_root: Path = CANONICAL_CLOSURE_EVO_ROOT,
) -> dict[str, bytes]:
    fixture_root = fixture_root.resolve()
    registry_manifest_path = registry_manifest_path.resolve()
    gas_manifest_path = gas_manifest_path.resolve()
    if not fixture_root.is_dir():
        raise RegenerationFailure(f"fixture root is not a directory: {fixture_root}")
    if not registry_manifest_path.is_file():
        raise RegenerationFailure(
            f"registry manifest does not exist: {registry_manifest_path}"
        )
    if not gas_manifest_path.is_file():
        raise RegenerationFailure(f"gas manifest does not exist: {gas_manifest_path}")

    rendered = rendered_evolution()
    verify_existing_evolution_inventory(fixture_root, rendered)
    verify_closure_mirror(rendered, closure_evo_root.resolve())

    registry_content = registry_manifest_path.read_bytes()
    registry = load_mapping(registry_content, str(registry_manifest_path))
    registry_identity = verified_identity(
        registry,
        "registry",
        ("packageIdentity", "fixturePackageIdentity"),
    )
    gas_content = gas_manifest_path.read_bytes()
    gas = load_mapping(gas_content, str(gas_manifest_path))
    verified_identity(gas, "gas manifest")

    fixtures = candidate_fixture_files(fixture_root, rendered)
    vector_coverage = build_vector_coverage(fixtures)
    vector_coverage_content = yaml_bytes(vector_coverage)
    fixture_manifest = build_fixture_manifest(
        fixture_root,
        fixtures,
        vector_coverage_content,
        registry_identity,
        gas,
        gas_content,
    )
    fixture_manifest_content = yaml_bytes(fixture_manifest)

    rebound_registry = deepcopy(registry)
    rebound_registry["fixturePackageIdentity"] = fixture_manifest["packageIdentity"]
    rebound_registry["packageIdentity"] = None
    rebound_registry["packageIdentity"] = package_identity(
        rebound_registry,
        ("packageIdentity", "fixturePackageIdentity"),
    )
    if rebound_registry["packageIdentity"] != registry_identity:
        raise RegenerationFailure(
            "registry package identity changed after normalizing fixture binding"
        )
    rebound_registry_content = replace_top_level_scalar(
        registry_content,
        "fixturePackageIdentity",
        rebound_registry["fixturePackageIdentity"],
    )
    rebound_registry_content = replace_top_level_scalar(
        rebound_registry_content,
        "packageIdentity",
        rebound_registry["packageIdentity"],
    )
    if load_mapping(rebound_registry_content, "rebound registry") != rebound_registry:
        raise RegenerationFailure(
            "registry binding update changed fields outside the exact manifest values"
        )

    candidate = {
        **{
            f"fixtures/evo/{name}": content
            for name, content in sorted(rendered.items())
        },
        "fixtures/vector-coverage.yaml": vector_coverage_content,
        "fixtures/manifest.yaml": fixture_manifest_content,
        "registry/manifest.yaml": rebound_registry_content,
    }
    if len(candidate) != len(EXPECTED_EVO_NAMES) + 3:
        raise RegenerationFailure("ordinary package candidate write set drifted")
    return candidate


def destination_paths(
    candidate: dict[str, bytes],
    fixture_root: Path,
    registry_manifest_path: Path,
) -> dict[str, Path]:
    package_root = fixture_root.resolve().parent
    result: dict[str, Path] = {}
    for relative in candidate:
        if relative == "registry/manifest.yaml":
            result[relative] = registry_manifest_path.resolve()
        elif relative.startswith("fixtures/"):
            result[relative] = package_root / relative
        else:
            raise RegenerationFailure(
                f"candidate contains an unowned path: {relative}"
            )
    return result


def compare_candidate(
    candidate: dict[str, bytes], destinations: dict[str, Path]
) -> list[str]:
    differences: list[str] = []
    for relative in sorted(candidate):
        target = destinations[relative]
        if not target.is_file():
            differences.append(f"missing: {relative}")
        elif target.read_bytes() != candidate[relative]:
            differences.append(f"content differs: {relative}")
    return differences


def atomic_write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", dir=path.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        temporary.write_bytes(content)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def publish(
    candidate: dict[str, bytes], destinations: dict[str, Path]
) -> None:
    for relative in sorted(candidate):
        target = destinations[relative]
        if target.is_file() and target.read_bytes() == candidate[relative]:
            continue
        atomic_write(target, candidate[relative])
    differences = compare_candidate(candidate, destinations)
    if differences:
        raise RegenerationFailure(
            f"published ordinary package differs from candidate: {differences}"
        )


def stage(candidate: dict[str, bytes], output_root: Path) -> None:
    output_root = output_root.resolve()
    if output_root.exists():
        if not output_root.is_dir() or any(output_root.iterdir()):
            raise RegenerationFailure(
                "stage output must be nonexistent or an empty directory: "
                f"{output_root}"
            )
    else:
        output_root.mkdir(parents=True)
    for relative in sorted(candidate):
        target = output_root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(candidate[relative])


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-root", type=Path, required=True)
    parser.add_argument("--registry-manifest", type=Path, required=True)
    parser.add_argument("--gas-manifest", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--stage-output", type=Path)
    arguments = parser.parse_args()

    candidate = build_candidate(
        arguments.fixture_root,
        arguments.registry_manifest,
        arguments.gas_manifest,
    )
    destinations = destination_paths(
        candidate,
        arguments.fixture_root,
        arguments.registry_manifest,
    )
    if arguments.stage_output is not None:
        stage(candidate, arguments.stage_output)
        print(f"ORDINARY_FIXTURE_PACKAGE_STAGED {arguments.stage_output.resolve()}")
    elif arguments.write:
        publish(candidate, destinations)
        print("ORDINARY_FIXTURE_PACKAGE_REGENERATED_AND_VALIDATED")
    else:
        differences = compare_candidate(candidate, destinations)
        if differences:
            detail = "\n".join(f"  - {item}" for item in differences)
            raise RegenerationFailure(
                "ordinary fixture package is not regenerated:\n" + detail
            )
        print("ORDINARY_FIXTURE_PACKAGE_CHECK_OK")


if __name__ == "__main__":
    try:
        main()
    except RegenerationFailure as exc:
        print(f"ORDINARY_FIXTURE_PACKAGE_REGENERATION_FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
