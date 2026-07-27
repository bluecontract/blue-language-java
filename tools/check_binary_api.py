#!/usr/bin/env python3
"""Dependency-free JVM classfile API compatibility check for release smoke tests."""

import argparse
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
    args = parser.parse_args()
    for candidate in (args.baseline_jar, args.current_jar):
        if not pathlib.Path(candidate).is_file():
            parser.error("JAR not found: {}".format(candidate))

    baseline_api, current_api, incompatible, additions = compare(
        classes_in(args.baseline_jar), classes_in(args.current_jar))
    current_classes = classes_in(args.current_jar)
    incompatible.extend(
        "Java 8 bytecode exceeded: {} has class major {}".format(
            name, value["major_version"])
        for name, value in sorted(current_classes.items())
        if value["major_version"] > 52
    )
    current_majors = sorted({value["major_version"] for value in current_classes.values()})
    lines = [
        "Blue Language JVM binary API compatibility",
        "baseline={}".format(args.baseline_jar),
        "current={}".format(args.current_jar),
        "baselineApiClasses={}".format(len(baseline_api)),
        "currentApiClasses={}".format(len(current_api)),
        "currentClassMajorVersions={}".format(
            ",".join(str(value) for value in current_majors)),
        "incompatibleChanges={}".format(len(incompatible)),
        "additiveChanges={}".format(len(additions)),
    ]
    if incompatible:
        lines.extend(["", "Incompatible changes:"] + ["  " + item for item in incompatible])
    if additions:
        lines.extend(["", "Additive changes:"] + ["  " + item for item in additions])
    report = pathlib.Path(args.report_file)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if incompatible:
        print("FAIL: {} incompatible JVM API change(s).".format(len(incompatible)))
        print("Report: {}".format(report))
        return 1
    print("PASS: baseline public/protected JVM classes and descriptors remain compatible.")
    print("Additive changes: {}".format(len(additions)))
    print("Report: {}".format(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
