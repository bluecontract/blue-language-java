#!/usr/bin/env python3
"""Build deterministic fixture, oracle, release, and package manifests."""
from __future__ import annotations

from argparse import ArgumentParser
from pathlib import Path
import hashlib
import json
from typing import Any

import yaml

from jcs import dumps as jcs_dumps
from package_hygiene import release_inventory_files

ROOT = Path(__file__).resolve().parents[1]
FIX = ROOT / "conformance/contracts/fixtures"
ORC = ROOT / "conformance/contracts/oracles"
REG = ROOT / "conformance/contracts/registry"
SPEC = ROOT / "specifications/blue-contracts-and-processor-specification-1.0.md"
LANG_SPEC = ROOT / "reference/blue-language-specification-1.0.md"
GAS = ROOT / "conformance/contracts/gas-manifest.yaml"
IDENTITY_CONSTRUCTORS = ROOT / "conformance/contracts/identity-constructors.yaml"

EXCLUDED_PACKAGE_PATHS = {
    "MANIFEST.sha256",
    "validation-output.json",
    "package-manifest.yaml",
}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def canonical_json(value: Any) -> bytes:
    return jcs_dumps(value)


def package_identity(value: dict[str, Any], field: str) -> str:
    clone = json.loads(json.dumps(value))
    clone[field] = None
    return "sha256:" + sha256_bytes(canonical_json(clone))


def yaml_file(path: Path) -> Any:
    return yaml.safe_load(path.read_text())


def domain_identity(domain: str, value: Any) -> str:
    return "sha256:" + sha256_bytes(canonical_json({"domain": domain, "value": value}))


def build_gas_manifest() -> dict[str, Any]:
    manifest = yaml_file(GAS)
    manifest["packageIdentity"] = None
    manifest["packageIdentity"] = package_identity(manifest, "packageIdentity")
    GAS.write_text(yaml.safe_dump(manifest, sort_keys=False, allow_unicode=True, width=120))
    return manifest


def bind_registry_fixture_identity(fixture_identity: str) -> dict[str, Any]:
    path = REG / "manifest.yaml"
    manifest = yaml_file(path)
    manifest["fixturePackageIdentity"] = fixture_identity
    clone = json.loads(json.dumps(manifest))
    clone["packageIdentity"] = None
    clone["fixturePackageIdentity"] = None
    manifest["packageIdentity"] = "sha256:" + sha256_bytes(canonical_json(clone))
    path.write_text(yaml.safe_dump(manifest, sort_keys=False, allow_unicode=True, width=120))
    return manifest


def fixture_paths() -> tuple[list[Path], list[Path]]:
    ignored = {
        "manifest.yaml",
        "vector-coverage.yaml",
        "projection-catalog.yaml",
        "fixture-schema.yaml",
        "closure-fixture-schema.yaml",
    }
    ordinary: list[Path] = []
    closure: list[Path] = []
    for path in sorted(FIX.rglob("*.yaml")):
        # Gas traces are support artifacts, never executable fixtures.  Avoid
        # materializing their potentially very large entry arrays merely to
        # discover that they have no vector declaration.
        if path.parent == FIX / "closure/traces":
            continue
        if path.name in ignored:
            continue
        data = yaml_file(path)
        if not isinstance(data, dict) or "vectors" not in data:
            continue
        if path.parent.name == "closure":
            closure.append(path)
        else:
            ordinary.append(path)
    return ordinary, closure


def build_vector_coverage(ordinary: list[Path], closure: list[Path]) -> dict[str, Any]:
    mapping: dict[str, list[str]] = {}
    for path in ordinary + closure:
        data = yaml_file(path)
        rel = path.relative_to(FIX).as_posix()
        for vector in data["vectors"]:
            mapping.setdefault(vector, []).append(rel)
    for values in mapping.values():
        values.sort()
    result = {
        "specification": "blue-contracts/1.0",
        "vectorCount": len(mapping),
        "ordinaryVectorCount": len([v for v in mapping if not is_closure_vector(v)]),
        "closureVectorCount": len([v for v in mapping if is_closure_vector(v)]),
        "vectors": {k: mapping[k] for k in sorted(mapping)},
    }
    (FIX / "vector-coverage.yaml").write_text(yaml.safe_dump(result, sort_keys=False, allow_unicode=True, width=120))
    return result


def is_closure_vector(vector: str) -> bool:
    """Return whether a vector belongs to either normative closure family."""
    return vector.startswith(("C-CLO-", "FL-ADM-"))


def file_entry(base: Path, path: Path, role: str, vectors: list[str] | None = None) -> dict[str, Any]:
    entry: dict[str, Any] = {
        "path": path.relative_to(base).as_posix(),
        "role": role,
        "sha256": sha256_file(path),
        "bytes": path.stat().st_size,
    }
    if vectors:
        entry["vectors"] = vectors
    return entry


def build_fixture_manifest(ordinary: list[Path], closure: list[Path], vector_coverage: dict[str, Any]) -> dict[str, Any]:
    registry_manifest = yaml_file(REG / "manifest.yaml")
    support = [
        FIX / "CONTROL-LANGUAGE.md",
        FIX / "HARNESS.md",
        FIX / "README.md",
        FIX / "TRACE-SCHEMA.md",
        FIX / "fixture-schema.yaml",
        FIX / "closure-fixture-schema.yaml",
        FIX / "projection-catalog.yaml",
        FIX / "vector-coverage.yaml",
        FIX / "closure/README.md",
        FIX / "closure/LIMIT-GENERATORS.md",
    ]
    support.extend(sorted((FIX / "closure/traces").glob("*.yaml")))
    files: list[dict[str, Any]] = []
    for path in support:
        files.append(file_entry(FIX, path, "support"))
    for path in ordinary:
        data = yaml_file(path)
        role = "gas-fixture" if data.get("category") == "gas" else "behavior-fixture"
        files.append(file_entry(FIX, path, role, data["vectors"]))
    for path in closure:
        data = yaml_file(path)
        files.append(file_entry(FIX, path, "closure-fixture", data["vectors"]))
    files.sort(key=lambda item: item["path"])
    ordinary_gas = sum(1 for path in ordinary if yaml_file(path).get("category") == "gas")
    manifest: dict[str, Any] = {
        "fixturePackage": "blue-contracts-conformance",
        "specificationVersion": "1.0",
        "schemaVersions": ["blue-contracts-fixture/1.0", "blue-contracts-closure-fixture/1.0"],
        "registryPackageIdentity": registry_manifest["packageIdentity"],
        "vectorCount": vector_coverage["vectorCount"],
        "ordinaryVectorCount": vector_coverage["ordinaryVectorCount"],
        "closureVectorCount": vector_coverage["closureVectorCount"],
        "ordinaryFixtureCount": len(ordinary),
        "ordinaryBehaviorFixtureCount": len(ordinary) - ordinary_gas,
        "ordinaryGasFixtureCount": ordinary_gas,
        "closureFixtureCount": len(closure),
        "totalExecutableFixtureCount": len(ordinary) + len(closure),
        "files": files,
        "packageIdentityAlgorithm": {
            "digest": "sha256",
            "encoding": "RFC 8785 canonical JSON encoded as UTF-8",
            "normalization": "packageIdentity is null before hashing",
        },
        "packageIdentity": None,
    }
    manifest["packageIdentity"] = package_identity(manifest, "packageIdentity")
    (FIX / "manifest.yaml").write_text(yaml.safe_dump(manifest, sort_keys=False, allow_unicode=True, width=120))
    return manifest


def build_oracle_manifest() -> dict[str, Any]:
    oracle_files = sorted(path for path in ORC.glob("*.yaml") if path.name != "manifest.yaml")
    entries = [file_entry(ORC, path, "cyclic-identity-oracle") for path in oracle_files]
    manifest: dict[str, Any] = {
        "oraclePackage": "blue-contracts-cyclic-identity-oracles",
        "specificationVersion": "1.0",
        "oracleCount": len(entries),
        "files": entries,
        "packageIdentityAlgorithm": {
            "digest": "sha256",
            "encoding": "RFC 8785 canonical JSON encoded as UTF-8",
            "normalization": "packageIdentity is null before hashing",
        },
        "packageIdentity": None,
    }
    manifest["packageIdentity"] = package_identity(manifest, "packageIdentity")
    (ORC / "manifest.yaml").write_text(yaml.safe_dump(manifest, sort_keys=False, allow_unicode=True, width=120))
    return manifest


def baseline_source_hashes(language_root: Path | None) -> list[dict[str, str]]:
    if language_root is None:
        return []
    relative = [
        "blue-language-core/src/main/java/blue/language/identity/CircularSetIdentityCalculator.java",
        "blue-language-core/src/main/java/blue/language/identity/CyclicMemberFinalization.java",
        "blue-language-core/src/main/java/blue/language/identity/CyclicSetFinalization.java",
        "blue-language-core/src/main/java/blue/language/provider/NodeContentHandler.java",
        "blue-language-core/src/main/java/blue/language/provider/CyclicSetProof.java",
        "blue-language-core/src/main/java/blue/language/provider/CyclicSetProofResult.java",
        "blue-language-core/src/main/java/blue/language/provider/CyclicAwareNodeProvider.java",
        "blue-language-core/src/main/java/blue/language/provider/VerifyingNodeProvider.java",
        "blue-language-core/src/main/java/blue/language/provider/CyclicProofMemberComparator.java",
        "blue-contracts-core/src/main/java/blue/language/processor/DocumentProcessor.java",
        "blue-contracts-core/src/main/java/blue/language/processor/ProcessorInvocationOrchestrator.java",
        "blue-contracts-core/src/main/java/blue/language/processor/ProcessorExecutionContext.java",
        "blue-contracts-core/src/main/java/blue/language/processor/EmbeddedScopePlanner.java",
        "blue-contracts-core/src/main/java/blue/language/processor/ProcessGasMeter.java",
        "blue-contracts-core/src/main/java/blue/language/processor/GasSchedule.java",
        "blue-contracts-core/src/main/java/blue/language/processor/PlatformCommitCompanion.java",
    ]
    result: list[dict[str, str]] = []
    for rel in relative:
        path = language_root / rel
        if path.exists():
            result.append({"path": rel, "sha256": sha256_file(path)})
    return result


def build_release_manifest(fixture_manifest: dict[str, Any], oracle_manifest: dict[str, Any], language_root: Path | None) -> dict[str, Any]:
    registry_manifest = yaml_file(REG / "manifest.yaml")
    gas_manifest = yaml_file(GAS)
    baseline = baseline_source_hashes(language_root)
    by_path = {entry["path"]: entry for entry in baseline}
    finalizer_paths = [
        "blue-language-core/src/main/java/blue/language/identity/CircularSetIdentityCalculator.java",
        "blue-language-core/src/main/java/blue/language/identity/CyclicMemberFinalization.java",
        "blue-language-core/src/main/java/blue/language/identity/CyclicSetFinalization.java",
    ]
    verifier_paths = [
        "blue-language-core/src/main/java/blue/language/provider/CyclicSetProof.java",
        "blue-language-core/src/main/java/blue/language/provider/CyclicSetProofResult.java",
        "blue-language-core/src/main/java/blue/language/provider/CyclicAwareNodeProvider.java",
        "blue-language-core/src/main/java/blue/language/provider/VerifyingNodeProvider.java",
        "blue-language-core/src/main/java/blue/language/provider/CyclicProofMemberComparator.java",
    ]
    finalizer_baseline = [by_path[path] for path in finalizer_paths if path in by_path]
    verifier_baseline = [by_path[path] for path in verifier_paths if path in by_path]
    release: dict[str, Any] = {
        "manifestType": "blue-contracts-release",
        "specification": "Blue Contracts and Processor Specification",
        "specificationVersion": "1.0",
        "status": "final-normative-implementation-target",
        "specificationDocument": {
            "path": "../../specifications/blue-contracts-and-processor-specification-1.0.md",
            "sha256": sha256_file(SPEC),
        },
        "languageDependency": {
            "specification": "Blue Language Specification 1.0",
            "referencePath": "../../reference/blue-language-specification-1.0.md",
            "specificationSha256": sha256_file(LANG_SPEC),
            "cyclicSetSemanticAlgorithm": "Blue Language 1.0 section 15",
            "cyclicMemberIdentityFormat": "MASTER#index",
            "cyclicSetFinalizerBaselineIdentity": domain_identity(
                "blue-language-cyclic-set-finalizer-baseline/1.0",
                {"languageSpecificationSha256": sha256_file(LANG_SPEC), "files": finalizer_baseline},
            ),
            "cyclicSetProofVerifierBaselineIdentity": domain_identity(
                "blue-language-cyclic-set-proof-verifier-baseline/1.0",
                {"languageSpecificationSha256": sha256_file(LANG_SPEC), "files": verifier_baseline},
            ),
            "inputImplementationBaseline": baseline,
        },
        "contractsRegistry": {
            "path": "registry/manifest.yaml",
            "packageIdentity": registry_manifest["packageIdentity"],
            "unchangedCoreTypeNodes": True,
        },
        "gasManifest": {
            "path": "gas-manifest.yaml",
            "packageIdentity": gas_manifest["packageIdentity"],
            "maxProcessGas": gas_manifest["maxProcessGas"],
            "numericWeightsStatus": gas_manifest["numericWeightsStatus"],
        },
        "identityConstructors": {
            "path": "identity-constructors.yaml",
            "sha256": sha256_file(IDENTITY_CONSTRUCTORS),
            "status": "normative",
        },
        "fixturePackage": {
            "path": "fixtures/manifest.yaml",
            "packageIdentity": fixture_manifest["packageIdentity"],
            "vectorCount": fixture_manifest["vectorCount"],
            "fixtureCount": fixture_manifest["totalExecutableFixtureCount"],
        },
        "oraclePackage": {
            "path": "oracles/manifest.yaml",
            "packageIdentity": oracle_manifest["packageIdentity"],
            "oracleCount": oracle_manifest["oracleCount"],
        },
        "normativeExclusions": [
            "delegated-authority eligibility and authority-evidence resolution",
            "Timeline provider completeness and Coordination scheduling policy",
            "BEX specification, operators, fixtures and gas schedule",
            "database, queue, network and storage implementation",
        ],
        "implementationBindingRequirements": [
            "Blue Language release identity",
            "cyclic-set finalizer implementation identity",
            "cyclic-set proof verifier implementation identity",
            "Blue Contracts implementation artifact identity",
            "Contracts runtime registry identity",
            "Contracts gas-manifest identity",
            "fixture-package identity",
            "host execution-policy identity when lower than the release default",
        ],
        "conformanceLevels": {
            "PACKAGE_VALID": "Schemas, manifests, exact identities and static fixture laws pass.",
            "SEMANTIC_REFERENCE_VALID": "Independent cyclic identity, finite, loop, history, identity-constructor and limit checks pass.",
            "IMPLEMENTATION_CONFORMANT": "The implementation executes every required fixture under this exact release.",
        },
        "sourceArchiveBaseline": {
            "expectedSha256": "7be5116d8e7a64bccf471c11a93127d4924a36686e23bbbf634fc0713d6d33c9",
            "filenameIsNonNormative": True,
        },
        "releaseIdentityAlgorithm": {
            "digest": "sha256",
            "encoding": "RFC 8785 canonical JSON encoded as UTF-8",
            "normalization": "releaseIdentity is null before hashing",
        },
        "releaseIdentity": None,
    }
    release["releaseIdentity"] = package_identity(release, "releaseIdentity")
    path = ROOT / "conformance/contracts/release-manifest.yaml"
    path.write_text(yaml.safe_dump(release, sort_keys=False, allow_unicode=True, width=120))
    return release


def package_files(root: Path = ROOT) -> list[Path]:
    return release_inventory_files(root, EXCLUDED_PACKAGE_PATHS)


def build_package_manifest(release: dict[str, Any]) -> dict[str, Any]:
    files = [file_entry(ROOT, path, "normative" if path.relative_to(ROOT).as_posix().startswith(("specifications/", "conformance/contracts/")) else "informative") for path in package_files()]
    manifest: dict[str, Any] = {
        "manifestType": "blue-contracts-specification-package",
        "packageName": "blue-contracts-and-processor-specification-1.0-final",
        "specificationVersion": "1.0",
        "contractsReleaseIdentity": release["releaseIdentity"],
        "fileCount": len(files),
        "files": files,
        "packageIdentityAlgorithm": {
            "digest": "sha256",
            "encoding": "RFC 8785 canonical JSON encoded as UTF-8",
            "normalization": "packageIdentity is null and package-manifest.yaml is excluded before hashing",
        },
        "packageIdentity": None,
    }
    manifest["packageIdentity"] = package_identity(manifest, "packageIdentity")
    (ROOT / "package-manifest.yaml").write_text(yaml.safe_dump(manifest, sort_keys=False, allow_unicode=True, width=120))
    return manifest



def write_checksum_manifest(root: Path = ROOT) -> None:
    lines = [
        f"{sha256_file(path)}  {path.relative_to(root).as_posix()}"
        for path in release_inventory_files(
            root, {"MANIFEST.sha256", "validation-output.json"}
        )
    ]
    (root / "MANIFEST.sha256").write_text("\n".join(lines) + "\n")

def main() -> None:
    parser = ArgumentParser()
    parser.add_argument("--language-source-root", type=Path, default=None)
    args = parser.parse_args()
    build_gas_manifest()
    ordinary, closure = fixture_paths()
    vector = build_vector_coverage(ordinary, closure)
    fixtures = build_fixture_manifest(ordinary, closure, vector)
    bind_registry_fixture_identity(fixtures["packageIdentity"])
    oracles = build_oracle_manifest()
    release = build_release_manifest(fixtures, oracles, args.language_source_root)
    package = build_package_manifest(release)
    write_checksum_manifest()
    print(json.dumps({
        "status": "MANIFESTS_BUILT",
        "ordinaryFixtures": len(ordinary),
        "closureFixtures": len(closure),
        "vectors": vector["vectorCount"],
        "fixturePackageIdentity": fixtures["packageIdentity"],
        "oraclePackageIdentity": oracles["packageIdentity"],
        "contractsReleaseIdentity": release["releaseIdentity"],
        "packageIdentity": package["packageIdentity"],
    }, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
