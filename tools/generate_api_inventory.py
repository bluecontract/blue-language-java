#!/usr/bin/env python3
"""Writes a deterministic public/protected JVM API inventory for one JAR."""

import argparse
import json
import pathlib

from check_binary_api import classes_in_jar, externally_reachable_api


def encode_member(identity, access):
    """Returns one stable member descriptor entry."""
    return {
        "name": identity[0],
        "descriptor": identity[1],
        "access": access,
    }


def encode_class(value):
    """Returns one stable class inventory entry."""
    return {
        "name": value["name"],
        "minorVersion": value["minor_version"],
        "majorVersion": value["major_version"],
        "access": value["access"],
        "superclass": value["superclass"],
        "interfaces": list(value["interfaces"]),
        "fields": [
            encode_member(identity, access)
            for identity, access in sorted(value["fields"].items())
        ],
        "methods": [
            encode_member(identity, access)
            for identity, access in sorted(value["methods"].items())
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jar")
    parser.add_argument("output")
    args = parser.parse_args()

    jar = pathlib.Path(args.jar)
    if not jar.is_file():
        parser.error("JAR not found: {}".format(jar))

    api = externally_reachable_api(classes_in_jar(jar))
    payload = {
        "schema": "blue-language-java-api-inventory/1.0",
        "classes": [encode_class(api[name]) for name in sorted(api)],
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8")


if __name__ == "__main__":
    main()
