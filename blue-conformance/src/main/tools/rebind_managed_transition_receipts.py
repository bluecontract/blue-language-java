#!/usr/bin/env python3
"""Rebind generated closure fixtures to executed Contracts runtime evidence.

This adapter owns no receipt or identity semantics.  It invokes the normative
Java runtime against an explicit disposable package candidate, validates the
complete report, and injects the resulting identity/gas/managed-transition
surfaces into generated fixture expectations. Authored semantic decisions are
checked rather than rebound.
"""
from __future__ import annotations

from argparse import ArgumentParser
from pathlib import Path
import os
import subprocess
import sys
import tempfile
from typing import Any

sys.dont_write_bytecode = True

import yaml

import generate_closure_fixtures as closure_generator
from gas_reference import gas_trace_identity


SUCCESS_MARKER = "CLOSURE_RUNTIME_FIXTURES_EXPORTED count=80"
REPORT_SCHEMA = (
    "blue-contracts-closure-runtime-fixture-export/1.0"
)
EXPECTED_INVENTORY = 98
EXPECTED_EXECUTED = 80
EXPECTED_LIMITS = 18
EXPECTED_COMPLETE = 71
RUNTIME_WEIGHTS = {"scriptedResultApplied": 1}
REJECTED_CHARGE_FIELDS = (
    "namespace",
    "counter",
    "quantity",
    "weight",
    "subtotal",
    "applicableCap",
    "remainingBeforeCharge",
    "owner",
)


class RebindFailure(RuntimeError):
    """Raised before incomplete runtime evidence can enter a candidate."""


def absolute_directory(path: Path, label: str) -> Path:
    value = path.expanduser().resolve()
    if not value.is_dir():
        raise RebindFailure(f"{label} must be an existing directory: {value}")
    return value


def load_mapping(path: Path, label: str) -> dict[str, Any]:
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError) as exc:
        raise RebindFailure(f"cannot parse {label}: {exc}") from exc
    if not isinstance(value, dict):
        raise RebindFailure(f"{label} must be a mapping")
    return value


def run_exporter(
    repository_root: Path,
    package_root: Path,
    report_path: Path,
) -> None:
    gradlew = repository_root / "gradlew"
    if not gradlew.is_file():
        raise RebindFailure(f"Gradle wrapper is missing: {gradlew}")
    command = [
        str(gradlew),
        ":blue-conformance:exportManagedTransitionReceiptFixtures",
        "--no-daemon",
        "--no-parallel",
        f"-PmanagedTransitionReceiptPackageRoot={package_root}",
        f"-PmanagedTransitionReceiptOutputReport={report_path}",
    ]
    environment = dict(os.environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    # Do not capture Gradle through a PIPE: a daemon may inherit the write end
    # and keep communicate() blocked after the wrapper itself has exited.
    # A regular temporary file preserves exact diagnostics without coupling
    # completion to descendant file-descriptor lifetime.
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as output:
        result = subprocess.run(
            command,
            cwd=repository_root,
            env=environment,
            text=True,
            stdout=output,
            stderr=subprocess.STDOUT,
        )
        output.seek(0)
        combined = output.read()
    if result.returncode != 0:
        raise RebindFailure(
            f"normative Java receipt exporter failed ({result.returncode}):\n"
            f"{combined}"
        )
    if SUCCESS_MARKER not in combined:
        raise RebindFailure(
            "normative Java receipt exporter returned success without its "
            f"exact marker:\n{combined}"
        )


def require_count(report: dict[str, Any], field: str, expected: int) -> None:
    actual = report.get(field)
    if actual != expected:
        raise RebindFailure(
            f"receipt report {field}={actual!r}; expected {expected}"
        )


def require_text(value: dict[str, Any], field: str, label: str) -> str:
    item = value.get(field)
    if not isinstance(item, str) or not item:
        raise RebindFailure(f"{label}.{field} must be non-empty text")
    return item


def identity_free(value: Any) -> Any:
    """Project structure without fields whose value is an exact identity."""
    if isinstance(value, list):
        return [identity_free(item) for item in value]
    if not isinstance(value, dict):
        return value
    result: dict[str, Any] = {}
    for key, item in value.items():
        if (
            key.endswith("Identity")
            or key.endswith("Identities")
            or key.endswith("BlueId")
            or key.endswith("BlueIds")
        ):
            continue
        result[key] = identity_free(item)
    return result


def gas_weights(package_root: Path) -> tuple[dict[str, dict[str, int]], set[str]]:
    manifest = load_mapping(package_root / "gas-manifest.yaml", "gas manifest")
    namespaces = manifest.get("namespaces")
    fields = manifest.get("traceEntryFields")
    if not isinstance(namespaces, dict) or not isinstance(fields, list):
        raise RebindFailure("gas manifest has no namespaces or traceEntryFields")
    result: dict[str, dict[str, int]] = {}
    for namespace, payload in namespaces.items():
        if not isinstance(namespace, str) or not isinstance(payload, dict):
            raise RebindFailure("gas manifest namespace must be a mapping")
        counters = payload.get("counters")
        if not isinstance(counters, dict) or not all(
            isinstance(counter, str)
            and isinstance(weight, int)
            and not isinstance(weight, bool)
            and weight >= 0
            for counter, weight in counters.items()
        ):
            raise RebindFailure(f"gas manifest counters are invalid: {namespace}")
        result[namespace] = counters
    # The closed fixture runtime registers exactly this child-ledger schedule;
    # it is intentionally outside the portable processor/semantic manifest.
    result["runtime"] = RUNTIME_WEIGHTS
    if not all(isinstance(field, str) and field for field in fields):
        raise RebindFailure("gas manifest traceEntryFields must be text")
    return result, set(fields)


def validate_executed_gas(
    package_root: Path,
    fixture_id: str,
    runtime_expected: dict[str, Any],
) -> list[dict[str, Any]]:
    """Validate exact Java-owned gas evidence before copying it to YAML."""
    trace = runtime_expected.get("gasTrace")
    total = runtime_expected.get("totalGas")
    identity = runtime_expected.get("gasTraceIdentity")
    if not isinstance(trace, list):
        raise RebindFailure(f"{fixture_id} executed gasTrace must be an array")
    if not isinstance(total, int) or isinstance(total, bool) or total < 0:
        raise RebindFailure(f"{fixture_id} executed totalGas must be non-negative")
    if not isinstance(identity, str) or not identity:
        raise RebindFailure(f"{fixture_id} executed gasTraceIdentity must be text")
    weights, allowed_fields = gas_weights(package_root)
    required_fields = {
        "sequence", "namespace", "counter", "quantity", "weight", "subtotal"
    }
    calculated_total = 0
    for index, entry in enumerate(trace):
        if not isinstance(entry, dict):
            raise RebindFailure(
                f"{fixture_id} gas trace entry {index} must be a mapping"
            )
        if not required_fields.issubset(entry) or not set(entry).issubset(
            allowed_fields
        ):
            raise RebindFailure(
                f"{fixture_id} gas trace entry {index} has invalid fields"
            )
        if entry["sequence"] != index:
            raise RebindFailure(
                f"{fixture_id} gas trace sequence is not contiguous at {index}"
            )
        namespace = entry["namespace"]
        counter = entry["counter"]
        if (
            not isinstance(namespace, str)
            or not isinstance(counter, str)
            or namespace not in weights
            or counter not in weights[namespace]
        ):
            raise RebindFailure(
                f"{fixture_id} gas trace uses unregistered counter at {index}: "
                f"{namespace}.{counter}"
            )
        for field in ("quantity", "weight", "subtotal"):
            value = entry[field]
            if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                raise RebindFailure(
                    f"{fixture_id} gas trace {field} is invalid at {index}"
                )
        if entry["weight"] != weights[namespace][counter]:
            raise RebindFailure(
                f"{fixture_id} gas trace weight mismatch at {index}"
            )
        if entry["quantity"] * entry["weight"] != entry["subtotal"]:
            raise RebindFailure(
                f"{fixture_id} gas trace subtotal mismatch at {index}"
            )
        if ("scopePath" in entry) != ("activationGeneration" in entry):
            raise RebindFailure(
                f"{fixture_id} gas trace scope context is incomplete at {index}"
            )
        if "scopePath" in entry and (
            entry["scopePath"] != "/" or entry["activationGeneration"] != 0
        ):
            raise RebindFailure(
                f"{fixture_id} gas trace scope context is invalid at {index}"
            )
        for field in (
            "documentId", "contractKey", "logicalPath", "workOccurrenceId", "reason"
        ):
            if field in entry and not isinstance(entry[field], str):
                raise RebindFailure(
                    f"{fixture_id} gas trace {field} must be text at {index}"
                )
        if "componentGeneration" in entry and (
            not isinstance(entry["componentGeneration"], int)
            or isinstance(entry["componentGeneration"], bool)
            or entry["componentGeneration"] < 0
        ):
            raise RebindFailure(
                f"{fixture_id} gas trace componentGeneration is invalid at {index}"
            )
        calculated_total += entry["subtotal"]
    if calculated_total != total:
        raise RebindFailure(
            f"{fixture_id} gas trace total mismatch: {calculated_total} != {total}"
        )
    if gas_trace_identity(trace) != identity:
        raise RebindFailure(f"{fixture_id} gas trace identity mismatch")
    return trace


def validate_rejected_charge(
    fixture_id: str,
    authored: Any,
    executed: Any,
) -> str | None:
    """Return only a verified runtime identity for unchanged charge semantics."""
    if authored is None and executed is None:
        return None
    if not isinstance(authored, dict) or not isinstance(executed, dict):
        raise RebindFailure(
            f"{fixture_id} authored/runtime rejectedCharge presence differs"
        )
    authored_basis = {field: authored.get(field) for field in REJECTED_CHARGE_FIELDS}
    executed_basis = {field: executed.get(field) for field in REJECTED_CHARGE_FIELDS}
    if authored_basis != executed_basis:
        raise RebindFailure(
            f"{fixture_id} rejectedCharge semantics changed during rebind: "
            f"authored={authored_basis!r} executed={executed_basis!r}"
        )
    identity = require_text(executed, "rejectedChargeIdentity", fixture_id)
    calculated = closure_generator.sha_id(
        "blue-contracts-rejected-charge/1.0", executed_basis
    )
    if identity != calculated:
        raise RebindFailure(
            f"{fixture_id} executed rejectedCharge identity mismatch"
        )
    return identity


def existing_gas_trace(
    fixture_path: Path,
    fixture_id: str,
    expected: dict[str, Any],
) -> tuple[list[Any], Path | None, dict[str, Any] | None]:
    route = expected.get("gasTraceFile")
    if route is None:
        trace = expected.get("gasTrace")
        if not isinstance(trace, list):
            raise RebindFailure(f"{fixture_id}.expected.gasTrace must be an array")
        return trace, None, None
    if (
        not isinstance(route, str)
        or not route
        or Path(route).is_absolute()
        or ".." in Path(route).parts
    ):
        raise RebindFailure(f"{fixture_id} has unsafe gasTraceFile: {route!r}")
    trace_path = (fixture_path.parent / route).resolve()
    try:
        trace_path.relative_to(fixture_path.parent.resolve())
    except ValueError as exc:
        raise RebindFailure(
            f"{fixture_id} gasTraceFile escapes its fixture directory"
        ) from exc
    trace_document = load_mapping(trace_path, f"{fixture_id} gas trace")
    if trace_document.get("fixture") != fixture_id:
        raise RebindFailure(f"{fixture_id} gas trace fixture binding mismatch")
    entries = trace_document.get("entries")
    if not isinstance(entries, list):
        raise RebindFailure(f"{fixture_id} gas trace entries must be an array")
    return entries, trace_path, trace_document


def rebind(package_root: Path, report: dict[str, Any]) -> None:
    if report.get("schema") != REPORT_SCHEMA:
        raise RebindFailure(
            f"unexpected receipt report schema: {report.get('schema')!r}"
        )
    require_count(report, "inventoriedFixtureCount", EXPECTED_INVENTORY)
    require_count(report, "executedFixtureCount", EXPECTED_EXECUTED)
    require_count(report, "skippedLimitFixtureCount", EXPECTED_LIMITS)
    entries = report.get("entries")
    if not isinstance(entries, list) or len(entries) != EXPECTED_INVENTORY:
        raise RebindFailure(
            "receipt report does not contain exactly "
            f"{EXPECTED_INVENTORY} entries"
        )

    seen: set[str] = set()
    changed = 0
    for entry in entries:
        if not isinstance(entry, dict):
            raise RebindFailure("receipt report entry must be a mapping")
        relative = require_text(entry, "path", "report entry")
        if (
            not relative.startswith("closure/")
            or ".." in relative
            or relative in seen
        ):
            raise RebindFailure(f"unsafe or duplicate fixture path: {relative}")
        seen.add(relative)
        fixture_path = package_root / "fixtures" / relative
        fixture = load_mapping(fixture_path, relative)
        fixture_id = require_text(fixture, "id", relative)
        if fixture_id != require_text(entry, "id", relative):
            raise RebindFailure(f"fixture/report id mismatch for {relative}")
        operation = require_text(entry, "operation", relative)
        if operation != fixture.get("operation"):
            raise RebindFailure(f"fixture/report operation mismatch for {relative}")
        outcome = require_text(entry, "attemptOutcome", relative)
        if operation == "limit-micro":
            if outcome != "NotApplicable":
                raise RebindFailure(f"limit fixture was unexpectedly executed: {relative}")
            continue

        expected = fixture.get("expected")
        if not isinstance(expected, dict):
            raise RebindFailure(f"{relative}.expected must be a mapping")
        if expected.get("attemptOutcome") != outcome:
            raise RebindFailure(f"fixture/report outcome mismatch for {relative}")
        if outcome == "NeedsResources":
            unexpected = {
                "managedTransitionReceipts",
                "managedTransitionReceiptsIdentity",
                "platformCommitCompanion",
            }.intersection(entry)
            if unexpected:
                raise RebindFailure(
                    f"suspended fixture exported result evidence: {relative} {sorted(unexpected)}"
                )
            continue
        if outcome != "Complete" or expected.get("status") != entry.get("status"):
            raise RebindFailure(f"fixture/report complete status mismatch for {relative}")

        runtime_expected = entry.get("runtimeExpected")
        if not isinstance(runtime_expected, dict):
            raise RebindFailure(f"{relative} has no executed runtime expectation")

        authored_deltas = expected.get("subscriptionDeltas")
        executed_deltas = runtime_expected.get("subscriptionDeltas")
        if identity_free(authored_deltas) != identity_free(executed_deltas):
            raise RebindFailure(
                f"{relative} subscription delta semantics changed during rebind"
            )
        expected["subscriptionDeltas"] = executed_deltas
        expected["subscriptionDeltasIdentity"] = require_text(
            runtime_expected, "subscriptionDeltasIdentity", relative
        )

        _authored_trace, trace_path, trace_document = existing_gas_trace(
            fixture_path, fixture_id, expected
        )
        executed_trace = validate_executed_gas(
            package_root, fixture_id, runtime_expected
        )
        rejected_charge_identity = validate_rejected_charge(
            fixture_id,
            expected.get("rejectedCharge"),
            runtime_expected.get("rejectedCharge"),
        )
        if rejected_charge_identity is not None:
            expected["rejectedCharge"][
                "rejectedChargeIdentity"
            ] = rejected_charge_identity
        expected["totalGas"] = runtime_expected["totalGas"]
        expected["gasTraceIdentity"] = require_text(
            runtime_expected, "gasTraceIdentity", relative
        )
        if trace_path is None:
            expected["gasTrace"] = executed_trace
        else:
            assert trace_document is not None
            trace_document["entries"] = executed_trace
            closure_generator.dump(trace_path, trace_document)

        receipts = entry.get("managedTransitionReceipts")
        aggregate = entry.get("managedTransitionReceiptsIdentity")
        if not isinstance(receipts, list):
            raise RebindFailure(f"{relative} has no executed receipt array")
        if not isinstance(aggregate, str) or not aggregate:
            raise RebindFailure(f"{relative} has no executed receipt aggregate")
        expected["managedTransitionReceipts"] = receipts
        expected["managedTransitionReceiptsIdentity"] = aggregate

        exported_companion = entry.get("platformCommitCompanion")
        fixture_companion = expected.get("platformCommitCompanion")
        if exported_companion is None:
            if fixture_companion is not None:
                raise RebindFailure(
                    f"{relative} retained a companion for a non-committing result"
                )
        else:
            if not isinstance(exported_companion, dict) or not isinstance(
                fixture_companion, dict
            ):
                raise RebindFailure(f"{relative} commit companion shape mismatch")
            if exported_companion.get("bindsManagedTransitionReceipts") is not True:
                raise RebindFailure(
                    f"{relative} companion lacks authenticated receipt binding"
                )
            companion_aggregate = exported_companion.get(
                "managedTransitionReceiptsIdentity"
            )
            if companion_aggregate != aggregate:
                raise RebindFailure(f"{relative} companion aggregate mismatch")
            fixture_companion["managedTransitionReceiptsIdentity"] = aggregate
            fixture_companion["subscriptionDeltasIdentity"] = require_text(
                runtime_expected["platformCommitCompanion"],
                "subscriptionDeltasIdentity",
                relative,
            )
            fixture_companion["gasTraceIdentity"] = require_text(
                runtime_expected["platformCommitCompanion"],
                "gasTraceIdentity",
                relative,
            )
            fixture_companion["companionIdentity"] = require_text(
                exported_companion, "companionIdentity", relative
            )

        closure_generator.dump(fixture_path, fixture)
        changed += 1
    if len(seen) != EXPECTED_INVENTORY or changed != EXPECTED_COMPLETE:
        raise RebindFailure(
            f"incomplete receipt rebind: seen={len(seen)} changed={changed}"
        )


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--package-root", type=Path, required=True)
    parser.add_argument("--repository-root", type=Path, required=True)
    args = parser.parse_args()
    package_root = absolute_directory(args.package_root, "package root")
    repository_root = absolute_directory(args.repository_root, "repository root")
    with tempfile.TemporaryDirectory(
        prefix="managed-transition-receipt-export-",
        dir=package_root.parent,
    ) as temporary:
        report_path = Path(temporary) / "managed-transition-receipts.json"
        run_exporter(repository_root, package_root, report_path)
        report = load_mapping(report_path, "managed transition receipt report")
        rebind(package_root, report)
    print(
        "CLOSURE_RUNTIME_FIXTURES_REBOUND "
        f"complete={EXPECTED_COMPLETE} executed={EXPECTED_EXECUTED}"
    )


if __name__ == "__main__":
    try:
        main()
    except RebindFailure as exc:
        print(f"MANAGED_TRANSITION_RECEIPT_REBIND_FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
