#!/usr/bin/env python3
"""Deterministically rebuild the Blue Language fixture-package manifest.

The default mode is read-only and fails when the checked-in manifest differs
from the candidate.  ``--write`` is the only mode that changes the manifest.
Fixture content and expected semantic results are inputs: this tool never
manufactures expected values with the implementation under test.
"""
from __future__ import annotations

from argparse import ArgumentParser
from copy import deepcopy
import hashlib
from pathlib import Path
import sys
from typing import Any

sys.dont_write_bytecode = True

import yaml

from blue_identity import canonical_json_bytes


TOOLS_ROOT = Path(__file__).resolve().parent
DEFAULT_FIXTURE_ROOT = (
    TOOLS_ROOT.parent / "resources/blue-language-1.0/fixtures"
)
DEFAULT_REGISTRY_MANIFEST = (
    TOOLS_ROOT.parent.parent.parent.parent
    / "blue-language-core/src/main/resources/registry/blue-language-1.0/manifest.yaml"
)
MANIFEST_NAME = "manifest.yaml"
PACKAGE_IDENTITY_FIELD = "packageIdentity"
SUPPORT_PATHS = frozenset(
    {
        "HARNESS.md",
        "README.md",
        "fixture-schema.yaml",
        "preprocessing/registry/AppendRootTextTransformation.blue",
        "preprocessing/registry/HARNESS.md",
        "preprocessing/registry/RenameRootFieldTransformation.blue",
        "preprocessing/registry/SetRootFieldTransformation.blue",
        "preprocessing/registry/manifest.yaml",
        "vector-coverage.yaml",
    }
)


class RegenerationFailure(RuntimeError):
    """Raised when the fixture package cannot be rebuilt safely."""


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def normalized_bytes(path: Path) -> bytes:
    return path.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")


def load_mapping(path: Path) -> dict[str, Any]:
    value = yaml.safe_load(path.read_bytes())
    if not isinstance(value, dict):
        raise RegenerationFailure(f"expected YAML mapping: {path}")
    return value


def fixture_role(relative: str, previous_roles: dict[str, str]) -> str:
    if relative in SUPPORT_PATHS:
        return "support"
    previous = previous_roles.get(relative)
    if previous is not None:
        if previous not in {"support", "behavior-fixture"}:
            raise RegenerationFailure(
                f"unsupported previous role {previous!r}: {relative}"
            )
        return previous
    if relative.endswith(".yaml"):
        return "behavior-fixture"
    raise RegenerationFailure(
        f"new file needs an explicit support classification: {relative}"
    )


def validate_behavior_fixture(path: Path, seen_ids: set[str]) -> None:
    value = load_mapping(path)
    fixture_id = value.get("id")
    operation = value.get("operation")
    if not isinstance(fixture_id, str) or not fixture_id:
        raise RegenerationFailure(f"behavior fixture has no non-empty id: {path}")
    if not isinstance(operation, str) or not operation:
        raise RegenerationFailure(
            f"behavior fixture has no non-empty operation: {path}"
        )
    if fixture_id in seen_ids:
        raise RegenerationFailure(f"duplicate behavior fixture id: {fixture_id}")
    seen_ids.add(fixture_id)


def registry_package_identity(path: Path) -> str:
    value = load_mapping(path)
    identity = value.get(PACKAGE_IDENTITY_FIELD)
    if not isinstance(identity, str) or not identity.startswith("sha256:"):
        raise RegenerationFailure(
            f"registry manifest has no release-grade package identity: {path}"
        )
    normalized = deepcopy(value)
    normalized[PACKAGE_IDENTITY_FIELD] = None
    normalized["fixturePackageIdentity"] = None
    calculated = "sha256:" + sha256_bytes(canonical_json_bytes(normalized))
    if identity != calculated:
        raise RegenerationFailure(
            "core registry package identity mismatch: "
            f"declared={identity}, calculated={calculated}"
        )
    return identity


def build_manifest(
    fixture_root: Path,
    registry_manifest: Path,
) -> dict[str, Any]:
    current_path = fixture_root / MANIFEST_NAME
    current = load_mapping(current_path)
    previous_roles = {
        str(entry.get("path")): str(entry.get("role"))
        for entry in current.get("files", [])
        if isinstance(entry, dict)
    }

    candidates = sorted(
        path
        for path in fixture_root.rglob("*")
        if path.is_file()
        and path.name not in {"manifest.yaml", "manifest.yml"}
    )
    files: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for path in candidates:
        relative = path.relative_to(fixture_root).as_posix()
        role = fixture_role(relative, previous_roles)
        content = normalized_bytes(path)
        if role == "behavior-fixture":
            validate_behavior_fixture(path, seen_ids)
        files.append(
            {
                "path": relative,
                "role": role,
                "sha256": sha256_bytes(content),
                "bytes": len(content),
            }
        )

    coverage = yaml.safe_load(
        normalized_bytes(fixture_root / "vector-coverage.yaml")
    )
    if not isinstance(coverage, list):
        raise RegenerationFailure("vector-coverage.yaml must contain a list")

    candidate = deepcopy(current)
    candidate["registryPackageIdentity"] = registry_package_identity(
        registry_manifest
    )
    candidate["vectorCount"] = len(coverage)
    candidate["behaviorFixtureCount"] = len(seen_ids)
    candidate["gasFixtureCount"] = 0
    candidate["files"] = files
    candidate[PACKAGE_IDENTITY_FIELD] = None
    candidate[PACKAGE_IDENTITY_FIELD] = (
        "sha256:" + sha256_bytes(canonical_json_bytes(candidate))
    )
    return candidate


def yaml_bytes(value: Any) -> bytes:
    return yaml.safe_dump(
        value,
        sort_keys=False,
        allow_unicode=True,
        width=120,
    ).encode("utf-8")


def main() -> int:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument(
        "--fixture-root",
        type=Path,
        default=DEFAULT_FIXTURE_ROOT,
    )
    parser.add_argument(
        "--registry-manifest",
        type=Path,
        default=DEFAULT_REGISTRY_MANIFEST,
    )
    parser.add_argument(
        "--write",
        action="store_true",
        help="replace the checked-in manifest with the verified candidate",
    )
    args = parser.parse_args()

    fixture_root = args.fixture_root.resolve()
    registry_manifest = args.registry_manifest.resolve()
    candidate = yaml_bytes(build_manifest(fixture_root, registry_manifest))
    manifest_path = fixture_root / MANIFEST_NAME
    current = manifest_path.read_bytes()

    if args.write:
        manifest_path.write_bytes(candidate)
        print(f"WROTE {manifest_path}")
        print(yaml.safe_load(candidate)[PACKAGE_IDENTITY_FIELD])
        return 0
    if current != candidate:
        expected = yaml.safe_load(candidate)[PACKAGE_IDENTITY_FIELD]
        actual = load_mapping(manifest_path).get(PACKAGE_IDENTITY_FIELD)
        raise RegenerationFailure(
            "language fixture manifest is stale: "
            f"declared={actual}, candidate={expected}; rerun with --write"
        )
    print("BLUE_LANGUAGE_FIXTURE_MANIFEST_OK")
    print(yaml.safe_load(candidate)[PACKAGE_IDENTITY_FIELD])
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RegenerationFailure as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        raise SystemExit(1)
