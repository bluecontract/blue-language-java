#!/usr/bin/env python3
"""Rebind generated closure fixtures to executed Contracts receipt evidence.

This adapter owns no receipt or identity semantics.  It invokes the normative
Java runtime against an explicit disposable package candidate, validates the
complete report, and injects only the resulting managed-transition receipt
surface and its commit-companion binding into generated fixture expectations.
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


SUCCESS_MARKER = "MANAGED_TRANSITION_RECEIPT_FIXTURES_EXPORTED count=75"
REPORT_SCHEMA = (
    "blue-contracts-managed-transition-receipt-fixture-export/1.0"
)
EXPECTED_INVENTORY = 93
EXPECTED_EXECUTED = 75
EXPECTED_LIMITS = 18
EXPECTED_COMPLETE = 66


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
        "--no-parallel",
        f"-PmanagedTransitionReceiptPackageRoot={package_root}",
        f"-PmanagedTransitionReceiptOutputReport={report_path}",
    ]
    environment = dict(os.environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    result = subprocess.run(
        command,
        cwd=repository_root,
        env=environment,
        text=True,
        capture_output=True,
    )
    combined = result.stdout + "\n" + result.stderr
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
        raise RebindFailure("receipt report does not contain exactly 93 entries")

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
        "MANAGED_TRANSITION_RECEIPT_FIXTURES_REBOUND "
        f"complete={EXPECTED_COMPLETE} executed={EXPECTED_EXECUTED}"
    )


if __name__ == "__main__":
    try:
        main()
    except RebindFailure as exc:
        print(f"MANAGED_TRANSITION_RECEIPT_REBIND_FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
