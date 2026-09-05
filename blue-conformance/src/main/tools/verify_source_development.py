#!/usr/bin/env python3
"""Read-only source checks; specification binding drift is never package certification."""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
from pathlib import Path
import re

import yaml
import regenerate_aggregate_release_manifest as aggregate

SPECIFICATIONS = (
    (aggregate.LANGUAGE_SPEC, "blue-conformance/src/main/resources/language/1.0/spec.md",
     "languageSpecificationSha256"),
    (aggregate.CONTRACTS_SPEC, "blue-conformance/src/main/resources/contract/1.0/spec.md",
     "contractsSpecificationSha256"),
)


def check_specification(path: Path) -> None:
    """Check Markdown structure; examples include intentional non-YAML pseudocode."""
    text = path.read_text(encoding="utf-8")
    if not text.startswith("# ") or "\x00" in text:
        raise ValueError(f"Invalid specification title/text: {path}")
    fence = None
    language = ""
    block: list[str] = []
    for line in text.splitlines():
        if re.match(r"^(<<<<<<< |=======\s*$|>>>>>>> )", line):
            raise ValueError(f"Unresolved conflict in {path}")
        match = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", line)
        if fence is None and match:
            fence, language, block = match[1], match[2].strip(), []
        elif fence is not None and match and match[1][0] == fence[0] and len(match[1]) >= len(fence) and not match[2].strip():
            fence = None
        elif fence is not None:
            block.append(line)
    if fence is not None:
        raise ValueError(f"Unclosed Markdown fence in {path}")


def verify(repository: Path) -> list[str]:
    for primary, mirror, _ in SPECIFICATIONS:
        if (repository / primary).read_bytes() != (repository / mirror).read_bytes():
            raise ValueError(f"Specification mirror drift: {primary} / {mirror}")
        check_specification(repository / primary)
    # Reuse the strict source tree, path, digest, package identity and cross-binding checks.
    expected = aggregate.build_manifest(repository)
    actual = aggregate._yaml(repository / aggregate.MANIFEST_PATH)
    aggregate._aggregate_inventory(actual, "Aggregate manifest")
    aggregate._package_identity(actual, ("packageIdentity",), "Aggregate manifest")
    adjusted = deepcopy(actual)
    pending = []
    expected_files = {entry["path"]: entry for entry in expected["files"]}
    for primary, _, field in SPECIFICATIONS:
        logical = "specifications/" + Path(primary).name
        if actual.get("components", {}).get(field) != expected["components"][field]:
            pending.append(f"{logical}: {actual.get('components', {}).get(field)} -> {expected['components'][field]}")
        adjusted["components"][field] = expected["components"][field]
        for entry in adjusted["files"]:
            if entry["path"] == logical:
                entry.update(expected_files[logical])
    adjusted["packageIdentity"] = None
    adjusted["packageIdentity"] = "sha256:" + hashlib.sha256(aggregate.jcs_dumps(adjusted)).hexdigest()
    if adjusted != expected:
        raise ValueError("Source integrity failure: aggregate drift extends beyond specification bindings")
    return pending


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    try:
        pending = verify(args.repository_root.resolve())
    except (ValueError, OSError, yaml.YAMLError) as error:
        parser.exit(1, f"SOURCE_DEVELOPMENT_REJECTED: {error}\n")
    print("SOURCE_DEVELOPMENT_OK; not candidate/package/consumer/release certification")
    if pending:
        print("PENDING_GENERATED_BINDINGS (strict candidate/release gates still fail):")
        print("\n".join(pending))
    else:
        print("NO_PENDING_SPECIFICATION_BINDINGS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
