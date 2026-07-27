#!/usr/bin/env python3
"""Write a deterministic public/protected JVM API baseline from a release JAR."""

import argparse
import json
import pathlib

from check_binary_api import classes_in_jar, externally_reachable_api


def encoded_member(identity, access):
    return {
        "name": identity[0],
        "descriptor": identity[1],
        "access": access,
    }


def encoded_class(value):
    return {
        "name": value["name"],
        "minorVersion": value["minor_version"],
        "majorVersion": value["major_version"],
        "access": value["access"],
        "superclass": value["superclass"],
        "interfaces": list(value["interfaces"]),
        "fields": [
            encoded_member(identity, access)
            for identity, access in sorted(value["fields"].items())
        ],
        "methods": [
            encoded_member(identity, access)
            for identity, access in sorted(value["methods"].items())
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("release_jar")
    parser.add_argument("output_file")
    args = parser.parse_args()

    release_jar = pathlib.Path(args.release_jar)
    if not release_jar.is_file():
        parser.error("JAR not found: {}".format(release_jar))

    api = externally_reachable_api(classes_in_jar(release_jar))
    payload = {
        "schema": "blue-language-java-api-baseline/1.0",
        "classes": [
            encoded_class(api[name])
            for name in sorted(api)
        ],
    }
    output = pathlib.Path(args.output_file)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print("Wrote {} API classes to {}".format(len(api), output))


if __name__ == "__main__":
    main()
