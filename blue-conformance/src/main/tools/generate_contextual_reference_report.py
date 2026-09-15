#!/usr/bin/env python3
"""Compare commit-pinned executions of one independent contextual identity corpus.

This report classifies measured Language changes. Registry and release-package
dependency identities belong to the separate complete identity-impact inventory.
Unexpected drift, changed exact inputs/IDs, or a failing final oracle aborts it.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


FIELDS = ("sourceBlueId", "sourceError", "canonicalInput", "resolved", "resolutionError")
MODES = ("cold", "warm-forward", "warm-reverse", "pre-resolved")
DEPENDENCY_PACKAGE = "blue-conformance/src/main/resources/blue-contracts-closure-1.0"
REASONS = {
    **{name: "canonical-custom-type" for name in (
        "currency-inline-text", "currency-reference-text", "currency-inline-custom",
        "terms-inline-coffee", "terms-reference-coffee", "terms-inline-tea", "terms-reference-tea")},
    "schema-reference": "schema-materialization-evidence",
    "dictionary-invalid-reference": "dictionary-value-type-validation",
    "dictionary-valid-reference": "dictionary-completed-payload",
    "list-invalid-reference": "list-item-type-validation",
    "list-whole-reference": "complete-referenced-list-payload",
    "empty-object-inline": "present-empty-object",
    "empty-object-reference": "present-empty-object",
}


def load_report(path):
    value = json.loads(path.read_text())
    if not re.fullmatch(r"[0-9a-f]{40}", value["sourceRevision"]):
        raise ValueError("Execution must name a full source commit: " + str(path))
    return value


def indexed(report):
    result = {(row["case"], row["mode"]): row for row in report["rows"]}
    if len(result) != len(report["rows"]):
        raise ValueError("Duplicate matrix row")
    return result


def validate_execution(report, corpus, corpus_digest, harness_digest, final=False):
    rows = indexed(report)
    expected = {(case["name"], mode) for case in corpus["cases"] for mode in MODES}
    if set(rows) != expected or report["provider"] != corpus["provider"]:
        raise ValueError("Execution does not contain the complete maintained corpus and provider")
    if (report.get("corpusSha256") != corpus_digest
            or report.get("harnessSha256") != harness_digest):
        raise ValueError("Execution was produced by a different or unbound corpus/harness")
    for case in corpus["cases"]:
        for mode in MODES:
            row = rows[(case["name"], mode)]
            if row["directBlueId"] != case["expectedDirectBlueId"]:
                raise ValueError("Direct identity differs from the independent oracle")
            if not final:
                continue
            if "expectedErrorCategory" in case:
                if (row.get("sourceError") != case["expectedErrorCategory"]
                        or "sourceBlueId" in row):
                    raise ValueError("Final Source execution accepted an invalid contribution")
            elif row.get("sourceBlueId") != case["expectedSourceBlueId"] or "sourceError" in row:
                raise ValueError("Final Source execution differs from the independent oracle")
            if "expectedResolutionErrorCategory" in case:
                if (row.get("resolutionError") != case["expectedResolutionErrorCategory"]
                        or "resolved" in row):
                    raise ValueError("Final resolution accepted an invalid contribution")


def compare(before, after):
    old, new = indexed(before), indexed(after)
    if old.keys() != new.keys() or before["provider"] != after["provider"]:
        raise ValueError("Executions do not share the same complete corpus")
    changes = []
    for key in sorted(old):
        a, b = old[key], new[key]
        for field in ("exactInput", "directBlueId"):
            if a[field] != b[field]:
                raise ValueError("Exact-content drift: " + repr((key, field)))
        fields = [field for field in FIELDS if a.get(field) != b.get(field)]
        if not fields:
            continue
        reason = REASONS.get(key[0])
        if reason is None:
            raise ValueError("Unexplained semantic drift: " + repr((key, fields)))
        changes.append({"case": key[0], "mode": key[1], "classification": "intended-language-correction",
                        "reason": reason, "changedFields": fields,
                        "before": {field: a.get(field) for field in fields},
                        "after": {field: b.get(field) for field in fields}})
    return {"beforeRevision": before["sourceRevision"], "afterRevision": after["sourceRevision"],
            "directIdentityChanges": 0, "unexplainedChanges": 0,
            "beforeOracleMismatches": len(before["oracleMismatches"]),
            "afterOracleMismatches": len(after["oracleMismatches"]), "changes": changes}


def verified_dependencies(repository, revision, inventory, review):
    def git(*args):
        return subprocess.check_output(["git", "-C", str(repository), *args])

    if git("rev-parse", "HEAD").decode().strip() != revision:
        raise ValueError("Dependency report must use the checked-out implementation commit")
    if git("diff", "HEAD", "--name-only").strip():
        raise ValueError("Commit the reviewed implementation before generating pinned evidence")
    baseline = review["beforeRevision"]
    if not re.fullmatch(r"[0-9a-f]{40}", baseline) or inventory["baselineCommit"] != baseline:
        raise ValueError("Dependency inventory and reviewed file changes use different baselines")
    if review["unexplainedNonIdentityChanges"] or not inventory["noCompatibilityAliases"]:
        raise ValueError("Dependency review is incomplete or aliases remain")
    for label, commit in (("before", baseline), ("after", revision)):
        files = git("ls-tree", "-r", "--name-only", commit, "--", DEPENDENCY_PACKAGE).splitlines()
        if len(files) != review[label + "FileCount"]:
            raise ValueError("Dependency package inventory changed after review")
    summary = inventory["summary"]
    if any(summary[field] for field in (
            "activeStoredReferenceCount", "unresolvedArtifactCount", "mirrorMismatchCount")):
        raise ValueError("Unresolved or stale dependency bindings remain")
    changed = set(git("diff", "--name-only", baseline, revision, "--", DEPENDENCY_PACKAGE)
                  .decode().splitlines())
    reviewed = {DEPENDENCY_PACKAGE + "/" + row["path"]: row for row in review["files"]}
    if len(reviewed) != len(review["files"]) or changed != set(reviewed):
        raise ValueError("Changed dependency files do not match the complete reviewed inventory")
    if len(changed) != review["changedFileCount"]:
        raise ValueError("Dependency change count differs from its reviewed inventory")
    for path, row in reviewed.items():
        if row["classification"] != "required-dependency-consequence" or not row["reason"]:
            raise ValueError("Unclassified dependency change: " + path)
        for label, commit in (("before", baseline), ("after", revision)):
            digest = hashlib.sha256(git("show", commit + ":" + path)).hexdigest()
            if digest != row[label + "Sha256"]:
                raise ValueError("Dependency bytes differ from the independently reviewed change: " + path)
    return {"sourceRevision": revision, "beforeRevision": baseline,
            "unexplainedChanges": 0, "reviewedClosurePackage": review,
            "artifactInventory": inventory}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-report", type=Path, required=True)
    parser.add_argument("--pr-report", type=Path, required=True)
    parser.add_argument("--final-report", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--dependency-inventory", type=Path, required=True)
    parser.add_argument("--dependency-review", type=Path, required=True)
    args = parser.parse_args()
    inputs = {name: load_report(path) for name, path in (
        ("next", args.baseline_report), ("original-pr36", args.pr_report), ("repaired", args.final_report))}
    final = inputs["repaired"]
    repository = Path(__file__).resolve().parents[4]
    harness_paths = (
        "blue-language-core/src/test/java/blue/language/runtime/ContextualReferenceIdentityMatrixTest.java",
        "blue-language-core/src/test/resources/identity/contextual-reference-matrix.json",
    )
    harness = [{"path": path, "sha256": hashlib.sha256((repository / path).read_bytes()).hexdigest()}
               for path in harness_paths]
    corpus = json.loads((repository / harness_paths[1]).read_text())
    for name, report in inputs.items():
        validate_execution(report, corpus, harness[1]["sha256"], harness[0]["sha256"], name == "repaired")
    if final["oracleMismatches"]:
        raise ValueError("Final implementation fails the independent oracle")
    dependencies = verified_dependencies(
        repository, final["sourceRevision"], json.loads(args.dependency_inventory.read_text()),
        json.loads(args.dependency_review.read_text()))
    comparisons = [compare(inputs[name], final) for name in ("next", "original-pr36")]
    output = args.output_directory
    output.mkdir(parents=True, exist_ok=True)
    (output / "dependency-changes.json").write_text(
        json.dumps(dependencies, ensure_ascii=False, indent=2) + "\n")
    files = []
    for name, value in inputs.items():
        content = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()
        filename = name + "-matrix.json"
        (output / filename).write_bytes(content)
        files.append({"path": filename, "sha256": hashlib.sha256(content).hexdigest(),
                      "sourceRevision": value["sourceRevision"]})
    inventory = {"schema": "blue-contextual-reference-identity-comparison/1.0", "executions": files,
                 "sharedHarness": harness,
                 "rowCountPerExecution": len(final["rows"]), "comparisons": comparisons,
                 "miniGate": "deferred-by-user; not executed or qualified"}
    (output / "comparison.json").write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n")
    lines = ["# PR #36: pomiar tożsamości", "", "Wygenerowano z jednego corpus z niezależnym direct oracle.", "",
             "Implementacja końcowa: `" + final["sourceRevision"] + "`.", "",
             "- Liczba wierszy na wykonanie: " + str(len(final["rows"])) + ".",
             "- Zmiany direct ID: **0**. Niewyjaśnione zmiany: **0**.",
             "- Końcowe rozbieżności z oracle: **0**.",
             "- Mini/MyOS: bramka odłożona przez użytkownika, niewykonana.", ""]
    for name, result in zip(("RC.25 / next", "Oryginalny PR #36"), comparisons):
        lines += ["## " + name, "", "Commit: `" + result["beforeRevision"] + "`.", "",
                  "Rozbieżności z oracle przed naprawą: **" + str(result["beforeOracleMismatches"]) + "**.", ""]
        grouped = {}
        for change in result["changes"]:
            grouped.setdefault(change["case"], change)
        for case, change in grouped.items():
            lines.append("- `" + case + "`: " + change["reason"] + "; pola: " + ", ".join(change["changedFields"]) + ".")
        lines.append("")
    lines += ["## Pełne dowody", "", "Każdy raport zawiera dokładne wejście i jego direct ID, canonical input, Source ID,",
              "resolved albo kategorię błędu oraz odczyty providera. Tryby: cold, warm-forward,",
              "warm-reverse, pre-resolved. Pole `resolved` jest wiernym wire view; osobne asercje",
              "testu sprawdzają wymagane efektywne typy i wartości. Historyczne checkouty",
              "otrzymały wyłącznie ten sam test i corpus; kod produkcyjny pozostał przypięty",
              "do wskazanych commitów. Hashe wspólnego harness znajdują się w comparison.json.", ""]
    lines += ["- [" + item["path"] + "](" + item["path"] + ")" for item in files]
    lines += ["- [Klasyfikacja każdego zmienionego wiersza](comparison.json)", "",
              "[Inwentarz zależnych rejestrów, fixture i środowisk](dependency-changes.json) zawiera",
              "także komplet przejrzanych zmian plików pakietu Contracts, związanych hashami bajtów",
              "z commitami przed i po naprawie. Niewyjaśniona zmiana lub aktywne stare wiązanie",
              "przerywa generowanie raportu.",
              "Stare exact ID pozostają związane ze starą treścią; raport nie tworzy aliasów.", ""]
    (output / "README.md").write_text("\n".join(lines))
    print("CONTEXTUAL_IDENTITY_COMPARISON_OK: " + str(len(final["rows"])) + " rows per commit")


if __name__ == "__main__":
    main()
