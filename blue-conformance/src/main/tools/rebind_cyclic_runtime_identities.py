#!/usr/bin/env python3
"""Bind runtime cyclic identities to the authoritative source/spec projection.

Run before the closure fixture generator: its normative Java exporter verifies
these production bindings before it can execute candidate fixture receipts.
This changes only the two generated constants and their API inventory values.
"""
from argparse import ArgumentParser
from pathlib import Path
import re

from build_release_manifests import baseline_source_hashes, domain_identity, sha256_file
from implementation_baseline import CYCLIC_FINALIZER, CYCLIC_PROOF_VERIFIER, source_paths_for_role


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parents[4])
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    root = args.repository_root.resolve()
    spec = root / "blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md"
    baseline = {entry["path"]: entry for entry in baseline_source_hashes(root)}
    source = root / "blue-contracts-core/src/main/java/blue/language/processor/ClosureRuntimeDescriptor.java"
    api = root / "blue-contracts-core/api/public-api.txt"
    content, inventory = source.read_text(), api.read_text()
    changes = []
    for constant, role, domain in (
        ("CYCLIC_FINALIZER_IDENTITY", CYCLIC_FINALIZER, "blue-language-cyclic-set-finalizer-baseline/1.0"),
        ("CYCLIC_PROOF_VERIFIER_IDENTITY", CYCLIC_PROOF_VERIFIER, "blue-language-cyclic-set-proof-verifier-baseline/1.0"),
    ):
        identity = domain_identity(domain, {
            "languageSpecificationSha256": sha256_file(spec),
            "files": [baseline[path] for path in source_paths_for_role(role)],
        })
        pattern = rf'({constant}\s*=\s*")sha256:[0-9a-f]{{64}}(\";)'
        updated, count = re.subn(pattern, lambda match: match[1] + identity + match[2], content)
        if count != 1:
            raise ValueError(f"Expected exactly one runtime constant: {constant}")
        api_pattern = rf'(field blue\.language\.processor\.ClosureRuntimeDescriptor#{constant} .* constant=")sha256:[0-9a-f]{{64}}(\")'
        updated_api, api_count = re.subn(api_pattern, lambda match: match[1] + identity + match[2], inventory)
        if api_count != 1:
            raise ValueError(f"Expected exactly one API constant: {constant}")
        if updated != content or updated_api != inventory:
            changes.append(constant)
        content, inventory = updated, updated_api
        print(f"{constant}={identity}")
    if args.write:
        source.write_text(content)
        api.write_text(inventory)
    elif changes:
        raise SystemExit("Stale cyclic bindings: " + ", ".join(changes))


if __name__ == "__main__":
    main()
