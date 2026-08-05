#!/usr/bin/env python3
"""Dependency-free JVM classfile API compatibility check for release smoke tests."""

import argparse
import hashlib
import json
import pathlib
import struct
import sys
import zipfile

PUBLIC = 0x0001
PRIVATE = 0x0002
PROTECTED = 0x0004
STATIC = 0x0008
FINAL = 0x0010
BRIDGE = 0x0040
INTERFACE = 0x0200
ABSTRACT = 0x0400
SYNTHETIC = 0x1000

MIGRATION_LEDGER_SCHEMA = "blue-language-java-api-migration-ledger/1.0"
SHA_256_PREFIX = "sha256:"


class Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, length):
        result = self.data[self.offset:self.offset + length]
        if len(result) != length:
            raise ValueError("truncated class file")
        self.offset += length
        return result

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def u4(self):
        return struct.unpack(">I", self.take(4))[0]


def parse_class(data):
    reader = Reader(data)
    if reader.u4() != 0xCAFEBABE:
        raise ValueError("invalid class magic")
    minor_version = reader.u2()
    major_version = reader.u2()
    count = reader.u2()
    pool = [None] * count
    index = 1
    while index < count:
        tag = reader.u1()
        if tag == 1:
            pool[index] = (tag, reader.take(reader.u2()).decode("utf-8", "replace"))
        elif tag in (3, 4):
            reader.take(4)
        elif tag in (5, 6):
            reader.take(8)
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, reader.u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, reader.u2(), reader.u2())
        elif tag == 15:
            pool[index] = (tag, reader.u1(), reader.u2())
        else:
            raise ValueError("unsupported constant-pool tag {}".format(tag))
        index += 1

    def utf8(cp_index):
        item = pool[cp_index]
        if not item or item[0] != 1:
            raise ValueError("expected UTF-8 constant")
        return item[1]

    def class_name(cp_index):
        if cp_index == 0:
            return None
        item = pool[cp_index]
        if not item or item[0] != 7:
            raise ValueError("expected class constant")
        return utf8(item[1]).replace("/", ".")

    access = reader.u2()
    name = class_name(reader.u2())
    superclass = class_name(reader.u2())
    interfaces = tuple(class_name(reader.u2()) for _ in range(reader.u2()))

    def members():
        result = {}
        for _ in range(reader.u2()):
            member_access = reader.u2()
            member_name = utf8(reader.u2())
            descriptor = utf8(reader.u2())
            for _ in range(reader.u2()):
                reader.u2()
                reader.take(reader.u4())
            if (member_access & (PUBLIC | PROTECTED)) and not (member_access & SYNTHETIC):
                result[(member_name, descriptor)] = member_access
        return result

    fields = members()
    methods = members()
    return {
        "name": name,
        "minor_version": minor_version,
        "major_version": major_version,
        "access": access,
        "superclass": superclass,
        "interfaces": interfaces,
        "fields": fields,
        "methods": methods,
    }


def classes_in_jar(path):
    classes = {}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            name = entry.filename
            if (not name.endswith(".class") or name.startswith("META-INF/versions/")
                    or name.endswith("module-info.class") or name.endswith("package-info.class")):
                continue
            parsed = parse_class(archive.read(entry))
            classes[parsed["name"]] = parsed
    return classes


def classes_in_snapshot(path):
    payload = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    if payload.get("schema") != "blue-language-java-api-baseline/1.0":
        raise ValueError("unsupported API baseline schema")
    classes = {}
    for encoded in payload.get("classes", []):
        parsed = {
            "name": encoded["name"],
            "minor_version": encoded["minorVersion"],
            "major_version": encoded["majorVersion"],
            "access": encoded["access"],
            "superclass": encoded.get("superclass"),
            "interfaces": tuple(encoded.get("interfaces", [])),
            "fields": {
                (member["name"], member["descriptor"]): member["access"]
                for member in encoded.get("fields", [])
            },
            "methods": {
                (member["name"], member["descriptor"]): member["access"]
                for member in encoded.get("methods", [])
            },
        }
        classes[parsed["name"]] = parsed
    return classes


def classes_in(path):
    candidate = pathlib.Path(path)
    if candidate.suffix.lower() == ".json":
        return classes_in_snapshot(candidate)
    return classes_in_jar(candidate)


def sha256(path):
    """Returns the prefixed SHA-256 identity of one required file."""
    digest = hashlib.sha256()
    with pathlib.Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return SHA_256_PREFIX + digest.hexdigest()


def require_text(value, label):
    """Returns a non-empty string or raises a deterministic ledger error."""
    if not isinstance(value, str) or not value:
        raise ValueError("{} must be a non-empty string".format(label))
    return value


def approved_changes(value, label):
    """Validates and returns one sorted, duplicate-free change list."""
    if not isinstance(value, list) or any(not isinstance(item, str) or not item
                                          for item in value):
        raise ValueError("{} must be an array of non-empty strings".format(label))
    if value != sorted(value):
        raise ValueError("{} must be sorted".format(label))
    if len(value) != len(set(value)):
        raise ValueError("{} must not contain duplicates".format(label))
    return value


def load_migration_ledger(path, baseline_path, baseline_api_classes):
    """Loads a strict ledger and verifies its immutable baseline binding."""
    ledger_path = pathlib.Path(path)
    payload = json.loads(ledger_path.read_text(encoding="utf-8"))
    if set(payload) != {"schema", "baseline", "approvals"}:
        raise ValueError("migration ledger has unexpected or missing root fields")
    if payload.get("schema") != MIGRATION_LEDGER_SCHEMA:
        raise ValueError("unsupported migration ledger schema")

    baseline = payload.get("baseline")
    required_baseline_fields = {
        "binaryApiSnapshot",
        "binaryApiSnapshotSha256",
        "semanticApiInventorySha256",
        "apiClasses",
    }
    if not isinstance(baseline, dict) or set(baseline) != required_baseline_fields:
        raise ValueError("migration ledger baseline fields are incomplete")
    recorded_path = ledger_path.parent / require_text(
        baseline.get("binaryApiSnapshot"), "baseline.binaryApiSnapshot")
    if recorded_path.resolve() != pathlib.Path(baseline_path).resolve():
        raise ValueError("migration ledger selects a different binary API baseline")
    recorded_hash = require_text(
        baseline.get("binaryApiSnapshotSha256"),
        "baseline.binaryApiSnapshotSha256")
    if recorded_hash != sha256(baseline_path):
        raise ValueError("migration ledger binary API baseline SHA-256 does not match")
    semantic_hash = require_text(
        baseline.get("semanticApiInventorySha256"),
        "baseline.semanticApiInventorySha256")
    if not semantic_hash.startswith(SHA_256_PREFIX) or len(semantic_hash) != 71:
        raise ValueError("baseline.semanticApiInventorySha256 is not a SHA-256 identity")
    if baseline.get("apiClasses") != baseline_api_classes:
        raise ValueError("migration ledger baseline API class count does not match")

    approvals = payload.get("approvals")
    if not isinstance(approvals, list) or not approvals:
        raise ValueError("migration ledger approvals must be a non-empty array")
    approval_ids = []
    incompatible = []
    additive = []
    required_approval_fields = {
        "id",
        "requirement",
        "rationale",
        "incompatibleChanges",
        "additiveChanges",
    }
    for index, approval in enumerate(approvals):
        label = "approvals[{}]".format(index)
        if not isinstance(approval, dict) or set(approval) != required_approval_fields:
            raise ValueError("{} has unexpected or missing fields".format(label))
        approval_ids.append(require_text(approval.get("id"), label + ".id"))
        require_text(approval.get("requirement"), label + ".requirement")
        require_text(approval.get("rationale"), label + ".rationale")
        incompatible.extend(approved_changes(
            approval.get("incompatibleChanges"),
            label + ".incompatibleChanges"))
        additive.extend(approved_changes(
            approval.get("additiveChanges"),
            label + ".additiveChanges"))
    if approval_ids != sorted(approval_ids) or len(approval_ids) != len(set(approval_ids)):
        raise ValueError("migration ledger approval ids must be sorted and unique")
    if len(incompatible) != len(set(incompatible)):
        raise ValueError("an incompatible change is approved more than once")
    if len(additive) != len(set(additive)):
        raise ValueError("an additive change is approved more than once")
    return {
        "path": str(ledger_path),
        "sha256": sha256(ledger_path),
        "incompatible": sorted(incompatible),
        "additive": sorted(additive),
    }


def visibility(access):
    if access & PUBLIC:
        return 2
    if access & PROTECTED:
        return 1
    return 0


def modifiers_changed(kind, baseline, current):
    problems = []
    if visibility(current) < visibility(baseline):
        problems.append("visibility reduced")
    if bool(current & STATIC) != bool(baseline & STATIC):
        problems.append("static modifier changed")
    if not (baseline & FINAL) and (current & FINAL):
        problems.append("final modifier added")
    if kind == "method" and not (baseline & ABSTRACT) and (current & ABSTRACT):
        problems.append("abstract modifier added")
    return problems


def compare(baseline, current):
    incompatible = []
    additions = []
    baseline_api = externally_reachable_api(baseline)
    current_api = externally_reachable_api(current)

    for name in sorted(baseline_api):
        old = baseline_api[name]
        new = current.get(name)
        if new is None:
            incompatible.append("class removed: {}".format(name))
            continue
        if visibility(new["access"]) < visibility(old["access"]):
            incompatible.append("class visibility reduced: {}".format(name))
        if bool(old["access"] & INTERFACE) != bool(new["access"] & INTERFACE):
            incompatible.append("class/interface kind changed: {}".format(name))
        if not (old["access"] & FINAL) and (new["access"] & FINAL):
            incompatible.append("class made final: {}".format(name))
        if not (old["access"] & ABSTRACT) and (new["access"] & ABSTRACT):
            incompatible.append("class made abstract: {}".format(name))
        if old["superclass"] != new["superclass"]:
            incompatible.append("superclass changed: {} ({} -> {})".format(
                name, old["superclass"], new["superclass"]))
        for interface in old["interfaces"]:
            if interface not in new["interfaces"]:
                incompatible.append("implemented interface removed: {} :: {}".format(
                    name, interface))
        for member_kind in ("fields", "methods"):
            for identity, old_access in sorted(old[member_kind].items()):
                new_access = new[member_kind].get(identity)
                label = "{} :: {}{}".format(name, identity[0], identity[1])
                if new_access is None:
                    incompatible.append("{} removed/descriptor changed: {}".format(
                        member_kind[:-1], label))
                    continue
                for problem in modifiers_changed(member_kind[:-1], old_access, new_access):
                    incompatible.append("{} {}: {}".format(member_kind[:-1], problem, label))

    for name in sorted(set(current_api) - set(baseline_api)):
        additions.append("public/protected class added: {}".format(name))
    for name in sorted(set(current_api) & set(baseline_api)):
        old = baseline_api[name]
        new = current_api[name]
        for interface in sorted(set(new["interfaces"]) - set(old["interfaces"])):
            additions.append("implemented interface added: {} :: {}".format(
                name, interface))
        for member_kind in ("fields", "methods"):
            for identity in sorted(set(new[member_kind]) - set(old[member_kind])):
                access = new[member_kind][identity]
                category = "default interface method" if (
                    member_kind == "methods" and new["access"] & INTERFACE
                    and not access & (ABSTRACT | STATIC)) else member_kind[:-1]
                additions.append("{} added: {} :: {}{}".format(
                    category, name, identity[0], identity[1]))
    return baseline_api, current_api, incompatible, additions


def externally_reachable_api(classes):
    result = {}
    for name, value in classes.items():
        if visibility(value["access"]) == 0:
            continue
        outer = name
        reachable = True
        while "$" in outer:
            outer = outer.rsplit("$", 1)[0]
            enclosing = classes.get(outer)
            if enclosing is not None and visibility(enclosing["access"]) == 0:
                reachable = False
                break
        if reachable:
            result[name] = value
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline_jar")
    parser.add_argument("current_jar")
    parser.add_argument("report_file", nargs="?",
                        default="build/reports/binary-api/compatibility.txt")
    parser.add_argument("migration_ledger", nargs="?")
    args = parser.parse_args()
    for candidate in (args.baseline_jar, args.current_jar):
        if not pathlib.Path(candidate).is_file():
            parser.error("JAR not found: {}".format(candidate))

    baseline_api, current_api, incompatible, additions = compare(
        classes_in(args.baseline_jar), classes_in(args.current_jar))
    current_classes = classes_in(args.current_jar)
    bytecode_problems = [
        "Java 8 bytecode exceeded: {} has class major {}".format(
            name, value["major_version"])
        for name, value in sorted(current_classes.items())
        if value["major_version"] > 52
    ]
    current_majors = sorted({value["major_version"] for value in current_classes.values()})

    ledger = None
    unapproved_incompatible = list(incompatible)
    unapproved_additive = []
    missing_incompatible = []
    missing_additive = []
    if args.migration_ledger:
        ledger = load_migration_ledger(
            args.migration_ledger, args.baseline_jar, len(baseline_api))
        approved_incompatible = set(ledger["incompatible"])
        approved_additive = set(ledger["additive"])
        actual_incompatible = set(incompatible)
        actual_additive = set(additions)
        unapproved_incompatible = sorted(
            actual_incompatible - approved_incompatible)
        unapproved_additive = sorted(actual_additive - approved_additive)
        missing_incompatible = sorted(
            approved_incompatible - actual_incompatible)
        missing_additive = sorted(approved_additive - actual_additive)

    blocking_changes = (
        bytecode_problems
        + ["unapproved incompatible: " + item
           for item in unapproved_incompatible]
        + ["unapproved additive: " + item
           for item in unapproved_additive]
        + ["approved incompatible no longer present: " + item
           for item in missing_incompatible]
        + ["approved additive no longer present: " + item
           for item in missing_additive]
    )
    lines = [
        "Blue Language JVM binary API compatibility",
        "baseline={}".format(args.baseline_jar),
        "current={}".format(args.current_jar),
        "baselineApiClasses={}".format(len(baseline_api)),
        "currentApiClasses={}".format(len(current_api)),
        "currentClassMajorVersions={}".format(
            ",".join(str(value) for value in current_majors)),
        "incompatibleChanges={}".format(len(blocking_changes)),
        "additiveChanges={}".format(len(additions)),
    ]
    if ledger:
        unapproved_count = (len(unapproved_incompatible)
                            + len(unapproved_additive))
        missing_count = len(missing_incompatible) + len(missing_additive)
        lines.extend([
            "migrationLedger={}".format(ledger["path"]),
            "migrationLedgerSha256={}".format(ledger["sha256"]),
            "migrationLedgerVerified={}".format(
                str(not blocking_changes).lower()),
            "actualIncompatibleChanges={}".format(len(incompatible)),
            "approvedIncompatibleChanges={}".format(
                len(ledger["incompatible"])),
            "approvedAdditiveChanges={}".format(len(ledger["additive"])),
            "unapprovedChanges={}".format(unapproved_count),
            "missingApprovedChanges={}".format(missing_count),
        ])
        if incompatible:
            lines.extend(
                ["", "Actual incompatible changes:"]
                + ["  " + item for item in incompatible])
        if blocking_changes:
            lines.extend(
                ["", "Migration ledger violations:"]
                + ["  " + item for item in blocking_changes])
    elif incompatible or bytecode_problems:
        lines.extend(
            ["", "Incompatible changes:"]
            + ["  " + item for item in incompatible + bytecode_problems])
    if additions:
        lines.extend(["", "Additive changes:"] + ["  " + item for item in additions])
    report = pathlib.Path(args.report_file)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if blocking_changes:
        print("FAIL: {} unapproved or missing JVM API migration change(s).".format(
            len(blocking_changes)))
        print("Report: {}".format(report))
        return 1
    if ledger:
        print("PASS: current JVM API diff exactly matches the approved migration ledger.")
        print("Approved incompatible changes: {}".format(len(incompatible)))
    else:
        print("PASS: baseline public/protected JVM classes and descriptors remain compatible.")
    print("Additive changes: {}".format(len(additions)))
    print("Report: {}".format(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
