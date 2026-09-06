#!/usr/bin/env python3
"""Rebind Java resource constants after reviewed authoritative input changes.

This does not generate fixture expectations. Run the fixture/aggregate generators
first. Source preprocessing uses only its declared specification/registry payload;
it never depends on generated fixture identities or a source commit.
"""
from argparse import ArgumentParser
from pathlib import Path
import hashlib
import re

import yaml
from jcs import dumps


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parents[4])
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    root = args.repository_root.resolve()
    pending = {}
    sha = lambda path: hashlib.sha256((root / path).read_bytes()).hexdigest()
    read = lambda path: yaml.safe_load((root / path).read_text())

    def replace(path, pattern, value):
        old = pending.get(path, (root / path).read_text())
        new, count = re.subn(pattern, lambda match: match[1] + value + match[2], old)
        if count != 1:
            raise ValueError(f"Expected one binding in {path}: {pattern}")
        pending[path] = new

    def constant(path, name, value):
        replace(path, rf'({name}\s*=\s*")[^"\n]+(";)', value)

    language_spec = "blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md"
    contracts_spec = "blue-contracts-core/src/main/resources/specifications/blue-contracts-and-processor-specification-1.0.md"
    language_registry = "blue-language-core/src/main/resources/registry/blue-language-1.0/manifest.yaml"
    language_fixtures = "blue-conformance/src/main/resources/blue-language-1.0/fixtures/manifest.yaml"
    registry = read(language_registry)
    fixture_identity = read(language_fixtures)["packageIdentity"]
    replace(language_registry, r'(fixturePackageIdentity: )[^\n]+(\n)', fixture_identity)

    baseline_path = "blue-language-core/src/main/resources/provider/blue-language-source-preprocessing-environment-1.0.yaml"
    baseline = read(baseline_path)
    baseline["value"]["languageSpecificationSha256"] = sha(language_spec)
    baseline["value"]["canonicalRegistryIdentity"] = registry["packageIdentity"]
    pending[baseline_path] = yaml.safe_dump(baseline, sort_keys=False, width=120)
    digest = hashlib.sha256(dumps(baseline)).hexdigest()
    environment = "blue-language-core/src/main/java/blue/language/provider/SourceProviderEnvironment.java"
    replace(environment, r'(LANGUAGE_1_0_RELEASE_IDENTITY\s*=\s*"[^"]+"\s*\+ "sha256:)[0-9a-f]{64}(";)', digest)
    replace("blue-language-core/api/public-api.txt",
            r'(field blue\.language\.provider\.SourceProviderEnvironment#LANGUAGE_1_0_RELEASE_IDENTITY .*@sha256:)[0-9a-f]{64}("\n)', digest)
    replace("src/test/java/blue/language/provider/ProviderEvidenceVerifierTest.java",
            r'(SOURCE_PREPROCESSING_BASELINE_IDENTITY\s*=\s*"[^\"]+"\s*\+ "sha256:)[0-9a-f]{64}(";)', digest)

    constant("blue-conformance/src/main/java/blue/language/conformance/api/BlueConformanceReport.java",
             "FIXTURE_PACKAGE_IDENTITY", fixture_identity)

    report = "blue-conformance/src/main/java/blue/language/conformance/api/BlueContractsConformanceReport.java"
    resources = "blue-conformance/src/main/resources/"
    aggregate = read(resources + "release/blue-language-contracts-embedded-modules-collection-paths-1.0/PACKAGE-MANIFEST.yaml")
    closure = resources + "blue-contracts-closure-1.0/"
    replace("blue-contracts-core/src/main/resources/registry/blue-contracts-1.0/manifest.yaml",
            r'(fixturePackageIdentity: )[^\n]+(\n)', read(resources + "blue-contracts-1.0/fixtures/manifest.yaml")["packageIdentity"])
    for name, value in {
        "RELEASE_PACKAGE_IDENTITY": aggregate["packageIdentity"],
        "LANGUAGE_FIXTURE_PACKAGE_IDENTITY": fixture_identity,
        "LANGUAGE_SPECIFICATION_SHA256": sha(language_spec),
        "CONTRACTS_SPECIFICATION_SHA256": sha(contracts_spec),
        "CONTRACTS_FIXTURE_PACKAGE_IDENTITY": read(closure + "fixtures/manifest.yaml")["packageIdentity"],
    }.items():
        constant(report, name, value)
    constant("blue-conformance/src/main/java/blue/language/conformance/api/BlueContractsFixturePackage.java",
             "CONTRACTS_RELEASE_IDENTITY", read(closure + "release-manifest.yaml")["releaseIdentity"])
    constant("blue-conformance/src/main/java/blue/language/conformance/contracts/closure/ClosureFixtureInventory.java",
             "PACKAGE_IDENTITY", read(closure + "fixtures/manifest.yaml")["packageIdentity"])
    constant("blue-conformance/src/test/java/blue/language/conformance/contracts/closure/ClosureConformanceHarnessTest.java",
             "C_CLO_34_FIXTURE_SHA256", sha(closure + "fixtures/closure/c-clo-34-separate-document-steps.yaml"))
    changed = [path for path, value in pending.items() if (root / path).read_text() != value]
    if args.write:
        for path in changed:
            (root / path).write_text(pending[path])
    elif changed:
        raise SystemExit("Stale resource bindings: " + ", ".join(changed))
    print("JAVA_RESOURCE_BINDINGS_" + ("WRITTEN" if args.write else "OK"))
    print("Source preprocessing identity sha256:" + digest)


if __name__ == "__main__":
    main()
