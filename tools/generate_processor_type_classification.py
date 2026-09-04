#!/usr/bin/env python3
"""Validate and canonically render the processor type classification.

The classification itself is an explicit architecture decision.  This tool
does not infer API support from Java visibility: several implementation types
are public only because the processor and closure packages form one runtime.
It does, however, fail closed when the production source tree and the explicit
classification cease to be a one-to-one inventory.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from dataclasses import dataclass


PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_INVENTORY = PROJECT_ROOT / "api/processor-type-classification-1.0.json"
DEFAULT_SOURCE_ROOT = (
    PROJECT_ROOT
    / "blue-contracts-core/src/main/java/blue/language/processor"
)

SCHEMA = "blue-language-java-processor-type-classification/1.0"
CLASSIFICATION_ORDER = (
    "PUBLIC_API",
    "PUBLIC_SPI",
    "PUBLIC_MODEL",
    "INTERNAL_ENGINE",
    "INTERNAL_SUPPORT",
)
PUBLIC_CLASSIFICATIONS = frozenset(
    {"PUBLIC_API", "PUBLIC_SPI", "PUBLIC_MODEL"}
)
SCOPE = (
    "All top-level production Java types declared below "
    "blue.language.processor in blue-contracts-core; package descriptors "
    "are counted separately as source files."
)
POLICY = {
    "supportedApiPackages": [
        "blue.language.processor",
        "blue.language.processor.closure",
        "blue.language.processor.model",
        "blue.language.processor.registry",
        "blue.language.processor.util",
    ],
    "implementationPackages": [
        "blue.language.processor",
        "blue.language.processor.closure",
    ],
    "visibilityIsNotClassification": True,
    "classificationIsExplicit": True,
    "technicalPublicGatewayRule": (
        "A public declaration classified INTERNAL_ENGINE or INTERNAL_SUPPORT "
        "is an implementation gateway, not supported API."
    ),
    "classificationDefinitions": {
        "PUBLIC_API": "Supported concrete gateways and immutable API values.",
        "PUBLIC_SPI": "Supported processor extension points.",
        "PUBLIC_MODEL": "Published processor mapping models.",
        "INTERNAL_ENGINE": "Executable orchestration, planning, and validation.",
        "INTERNAL_SUPPORT": (
            "Implementation evidence, state, descriptors, and focused helpers."
        ),
    },
}

PACKAGE_PATTERN = re.compile(
    r"(?m)^\s*package\s+"
    r"([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;"
)
TYPE_PATTERN = re.compile(
    r"(?m)^\s*"
    r"(public\s+)?"
    r"(?:(?:abstract|final|sealed|non-sealed|strictfp)\s+)*"
    r"(class|interface|enum|@interface|record)\s+"
    r"([A-Za-z_$][\w$]*)\b"
)
NON_CODE_PATTERN = re.compile(
    r"//[^\n]*|/\*.*?\*/|\"(?:\\.|[^\"\\])*\"|"
    r"'(?:\\.|[^'\\])*'",
    re.DOTALL,
)


class ClassificationError(RuntimeError):
    """Raised when source and architecture classification disagree."""


@dataclass(frozen=True)
class TypeDeclaration:
    """One top-level Java declaration discovered in production source."""

    name: str
    kind: str
    is_public: bool
    source: pathlib.Path


@dataclass(frozen=True)
class SourceInventory:
    """Complete source-file and top-level-type inventory."""

    source_files: int
    package_descriptors: int
    declarations: dict[str, TypeDeclaration]


def _without_non_code(source: str) -> str:
    """Mask comments and literals while preserving offsets and line breaks."""

    def mask(match: re.Match[str]) -> str:
        return "".join("\n" if value == "\n" else " " for value in match.group())

    return NON_CODE_PATTERN.sub(mask, source)


def _brace_depths(source: str) -> list[int]:
    """Return lexical brace depth immediately before every character."""

    result: list[int] = []
    depth = 0
    for value in source:
        result.append(depth)
        if value == "{":
            depth += 1
        elif value == "}":
            depth -= 1
            if depth < 0:
                raise ClassificationError("Java source has an unmatched closing brace")
    if depth != 0:
        raise ClassificationError("Java source has unmatched opening braces")
    result.append(depth)
    return result


def discover_source_inventory(source_root: pathlib.Path) -> SourceInventory:
    """Discover every top-level declaration below ``source_root``."""

    if not source_root.is_dir():
        raise ClassificationError("Processor source root not found: {}".format(source_root))

    sources = sorted(source_root.rglob("*.java"))
    declarations: dict[str, TypeDeclaration] = {}
    package_descriptors = 0
    for source_path in sources:
        text = source_path.read_text(encoding="utf-8")
        lexical = _without_non_code(text)
        package_match = PACKAGE_PATTERN.search(lexical)
        if package_match is None:
            raise ClassificationError(
                "Production Java source has no package declaration: {}".format(
                    source_path
                )
            )
        package_name = package_match.group(1)
        if source_path.name == "package-info.java":
            package_descriptors += 1
            continue

        depths = _brace_depths(lexical)
        file_declarations: list[TypeDeclaration] = []
        for match in TYPE_PATTERN.finditer(lexical):
            if depths[match.start()] != 0:
                continue
            declaration = TypeDeclaration(
                name="{}.{}".format(package_name, match.group(3)),
                kind=match.group(2),
                is_public=match.group(1) is not None,
                source=source_path,
            )
            if declaration.name in declarations:
                raise ClassificationError(
                    "Duplicate top-level type {} in {} and {}".format(
                        declaration.name,
                        declarations[declaration.name].source,
                        source_path,
                    )
                )
            declarations[declaration.name] = declaration
            file_declarations.append(declaration)

        expected_name = source_path.stem
        if not any(
            declaration.name.rsplit(".", 1)[-1] == expected_name
            for declaration in file_declarations
        ):
            raise ClassificationError(
                "Production Java source has no top-level declaration matching "
                "its filename: {}".format(source_path)
            )

    return SourceInventory(
        source_files=len(sources),
        package_descriptors=package_descriptors,
        declarations=declarations,
    )


def _classification_groups(payload: dict) -> dict[str, list[str]]:
    raw_groups = payload.get("classifications")
    if not isinstance(raw_groups, list):
        raise ClassificationError("classifications must be an array")
    groups: dict[str, list[str]] = {}
    for raw_group in raw_groups:
        if not isinstance(raw_group, dict):
            raise ClassificationError("classification entry must be an object")
        classification = raw_group.get("classification")
        types = raw_group.get("types")
        if classification not in CLASSIFICATION_ORDER:
            raise ClassificationError(
                "Unknown classification: {}".format(classification)
            )
        if classification in groups:
            raise ClassificationError(
                "Duplicate classification: {}".format(classification)
            )
        if not isinstance(types, list) or not all(
            isinstance(value, str) and value for value in types
        ):
            raise ClassificationError(
                "{} types must be non-empty strings".format(classification)
            )
        groups[classification] = list(types)

    missing_groups = sorted(set(CLASSIFICATION_ORDER) - set(groups))
    if missing_groups:
        raise ClassificationError(
            "Missing classifications: {}".format(", ".join(missing_groups))
        )
    return groups


def canonical_payload(payload: dict, source_root: pathlib.Path) -> dict:
    """Validate explicit decisions and return their canonical current form."""

    if payload.get("schema") != SCHEMA:
        raise ClassificationError("Unexpected schema: {}".format(payload.get("schema")))

    inventory = discover_source_inventory(source_root)
    groups = _classification_groups(payload)
    classified_as: dict[str, str] = {}
    duplicate_types: list[str] = []
    for classification in CLASSIFICATION_ORDER:
        for type_name in groups[classification]:
            if type_name in classified_as:
                duplicate_types.append(type_name)
            classified_as[type_name] = classification
    if duplicate_types:
        raise ClassificationError(
            "Types classified more than once: {}".format(
                ", ".join(sorted(set(duplicate_types)))
            )
        )

    source_types = set(inventory.declarations)
    classified_types = set(classified_as)
    unclassified = sorted(source_types - classified_types)
    stale = sorted(classified_types - source_types)
    if unclassified or stale:
        raise ClassificationError(
            "Classification/source mismatch; unclassified=[{}] stale=[{}]".format(
                ", ".join(unclassified), ", ".join(stale)
            )
        )

    for type_name, classification in classified_as.items():
        declaration = inventory.declarations[type_name]
        if classification in PUBLIC_CLASSIFICATIONS and not declaration.is_public:
            raise ClassificationError(
                "{} is {} but is not declared public".format(
                    type_name, classification
                )
            )
        if classification == "PUBLIC_SPI" and declaration.kind not in {
            "interface",
            "class",
        }:
            raise ClassificationError(
                "{} is PUBLIC_SPI but has unsupported declaration kind {}".format(
                    type_name, declaration.kind
                )
            )
        if classification == "PUBLIC_MODEL" and not type_name.startswith(
            "blue.language.processor.model."
        ):
            raise ClassificationError(
                "{} is PUBLIC_MODEL but is outside the model package".format(
                    type_name
                )
            )

    public_count = sum(
        declaration.is_public
        for declaration in inventory.declarations.values()
    )
    internal_public_count = sum(
        inventory.declarations[type_name].is_public
        for classification in ("INTERNAL_ENGINE", "INTERNAL_SUPPORT")
        for type_name in groups[classification]
    )
    counts = {
        "productionSourceFiles": inventory.source_files,
        "packageDescriptors": inventory.package_descriptors,
        "topLevelTypes": len(inventory.declarations),
        "publicTopLevelTypes": public_count,
        "packagePrivateTopLevelTypes": len(inventory.declarations) - public_count,
        "internallyClassifiedPublicTypes": internal_public_count,
    }
    counts.update(
        {
            classification: len(groups[classification])
            for classification in CLASSIFICATION_ORDER
        }
    )
    return {
        "schema": SCHEMA,
        "artifactRole": "architecture-evidence",
        "identityBearingReleaseManifest": False,
        "scope": SCOPE,
        "policy": POLICY,
        "counts": counts,
        "classifications": [
            {
                "classification": classification,
                "types": sorted(groups[classification]),
            }
            for classification in CLASSIFICATION_ORDER
        ],
    }


def canonical_bytes(payload: dict, source_root: pathlib.Path) -> bytes:
    """Return stable UTF-8 JSON for one validated inventory."""

    return (
        json.dumps(
            canonical_payload(payload, source_root),
            indent=2,
            ensure_ascii=False,
        )
        + "\n"
    ).encode("utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=pathlib.Path, default=DEFAULT_INVENTORY)
    parser.add_argument("--source-root", type=pathlib.Path, default=DEFAULT_SOURCE_ROOT)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--check",
        action="store_true",
        help="verify source completeness and canonical checked-in bytes (default)",
    )
    mode.add_argument(
        "--write",
        action="store_true",
        help="rewrite counts, policy, ordering, and formatting after validation",
    )
    args = parser.parse_args(argv)

    try:
        original = args.inventory.read_bytes()
        payload = json.loads(original.decode("utf-8"))
        rendered = canonical_bytes(payload, args.source_root)
        if args.write:
            args.inventory.write_bytes(rendered)
            return 0
        if original != rendered:
            raise ClassificationError(
                "Processor type classification is not canonical; run {} "
                "--write".format(pathlib.Path(__file__).as_posix())
            )
        return 0
    except (ClassificationError, OSError, UnicodeError, json.JSONDecodeError) as error:
        print("processor type classification: {}".format(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
