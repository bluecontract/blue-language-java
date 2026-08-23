#!/usr/bin/env python3
"""Classify deterministic Contracts package changes between two candidates."""
from __future__ import annotations

from argparse import ArgumentParser
from collections import Counter
from pathlib import Path
import hashlib
import json
import os
import sys
import tempfile
from typing import Any, Iterable

sys.dont_write_bytecode = True

import yaml

from gas_reference import gas_trace_identity
from package_hygiene import release_inventory_files


SPEC = "spec-identity-rebind"
INVOCATION = "invocation-identity-rebind"
TRACE = "work-event-trace-identity-rebind"
SEMANTIC = "actual-semantic-state-result-change"
FORMATTING = "fixture-byte-only-formatting"
UNEXPECTED = "unexpected"
CATEGORIES = (SPEC, INVOCATION, TRACE, SEMANTIC, FORMATTING, UNEXPECTED)

MANIFEST_PATHS = frozenset(
    {
        "fixtures/manifest.yaml",
        "fixtures/vector-coverage.yaml",
        "oracles/manifest.yaml",
        "registry/manifest.yaml",
        "release-manifest.yaml",
        "gas-manifest.yaml",
    }
)
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
        "rejectedChargeIdentity",
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
        "rootEventOccurrenceId",
        "sourceOccurrenceIdentity",
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
    }
)
class ClassificationFailure(RuntimeError):
    """Raised for malformed inputs or unsafe report destinations."""


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


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
    if relative in MANIFEST_PATHS:
        return True
    if relative.startswith("fixtures/closure/fl-adm-") and relative.endswith(".yaml"):
        return True
    if relative == "fixtures/closure-fixture-schema.yaml":
        return (
            json_pointer.startswith(
                "/$defs/handlerResult/properties/termination"
            )
            or json_pointer == "/properties/vectors/items/pattern"
        )
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
    if result["unexpected"]:
        categories.add(UNEXPECTED)
        result["reason"] = (
            "non-identity change outside generated manifests, new FL-ADM "
            "fixtures, approved fixture support documents, or the approved "
            "schema extensions"
        )
    result["categories"] = sorted(categories)
    result["differences"] = differences
    return result


def classify_added_file(relative: str, path: Path) -> dict[str, Any]:
    allowed = relative.startswith("fixtures/closure/fl-adm-") and relative.endswith(".yaml")
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
