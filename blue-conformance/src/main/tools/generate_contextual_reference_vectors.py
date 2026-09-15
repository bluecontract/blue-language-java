#!/usr/bin/env python3
"""Check/write contextual reference vectors using hand-constructed canonical nodes.

Only the existing independent direct BlueId oracle hashes expected nodes. The
Java resolver is deliberately not called to manufacture expected results.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
import yaml
from blue_identity import (direct_blue_id, TEXT_TYPE_BLUE_ID,
                           INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID)

ROOT = Path(__file__).resolve().parents[4]
FIXTURES = ROOT / "blue-conformance/src/main/resources/blue-language-1.0/fixtures"
MATRIX = ROOT / "blue-language-core/src/test/resources/identity/contextual-reference-matrix.json"
DICTIONARY = "5WQ4tVb4gUUdZa7EfaiUa2XKQwgAurvfYY3ALPauxcAF"
LIST = "85ip88snCGrgUNdi1rUFqqAxcxwVGKV2g4LjsKoyKmXK"


class BlueFixtureDumper(yaml.SafeDumper):
    def ignore_aliases(self, data):
        return True


def reference(blue_id):
    return {"blueId": blue_id}


def build_vectors():
    provider = []

    def publish(node):
        blue_id = direct_blue_id(node)
        provider.append({"requestedBlueId": blue_id, "node": deepcopy(node)})
        return blue_id

    currency_id = publish({"name": "Currency", "type": reference(TEXT_TYPE_BLUE_ID)})
    holder_id = publish({"name": "Holder", "currency": {"type": reference(currency_id)}})
    text = {"type": reference(TEXT_TYPE_BLUE_ID), "value": "PLN"}
    currency = {"type": reference(currency_id), "value": "PLN"}
    text_id, currency_value_id = publish(text), publish(currency)
    holder = lambda value: {"type": reference(holder_id), "currency": value}
    canonical_currency = holder(currency)
    cases = []

    def add(name, source, canonical=None, error=None, resolved=None):
        case = {"name": name, "source": source, "expectedDirectBlueId": direct_blue_id(source)}
        if error:
            case["expectedErrorCategory"] = error
            case["expectedResolutionErrorCategory"] = error
        else:
            case["expectedCanonical"] = canonical
            case["expectedSourceBlueId"] = direct_blue_id(canonical)
            if resolved is not None:
                case["expectedResolvedValues"] = resolved
        cases.append(case)

    resolved_currency = {"/currency/type/blueId": currency_id, "/currency/value": "PLN"}
    add("currency-inline-text", holder(text), canonical_currency, resolved=resolved_currency)
    add("currency-reference-text", holder(reference(text_id)), canonical_currency, resolved=resolved_currency)
    add("currency-inline-custom", holder(currency), canonical_currency, resolved=resolved_currency)
    add("currency-reference-custom", holder(reference(currency_value_id)),
        holder(reference(currency_value_id)), resolved=resolved_currency)
    add("opaque-text", reference(text_id), reference(text_id))

    terms_id = publish({"name": "Terms", "title": {"type": reference(TEXT_TYPE_BLUE_ID)}})
    terms_holder_id = publish({"name": "Holder", "terms": {"type": reference(terms_id)}})
    terms_holder = lambda value: {"type": reference(terms_holder_id), "terms": value}
    for title in ("Coffee", "Tea"):
        raw = {"title": {"type": reference(TEXT_TYPE_BLUE_ID), "value": title}}
        raw_id = publish(raw)
        canonical = {"type": reference(terms_id), "title": raw["title"]}
        resolved = {"/terms/type/blueId": terms_id, "/terms/title/value": title}
        add("terms-inline-" + title.lower(), terms_holder(raw), terms_holder(canonical), resolved=resolved)
        add("terms-reference-" + title.lower(), terms_holder(reference(raw_id)),
            terms_holder(canonical), resolved=resolved)
    add("terms-absent", {"type": reference(terms_holder_id)}, {"type": reference(terms_holder_id)})

    schema_holder_id = publish({"name": "Schema holder", "field": {"schema": {"minLength": 1}}})
    schema = {"schema": {"required": True}}
    schema_id = publish(schema)
    schema_holder = lambda value: {"type": reference(schema_holder_id), "field": value}
    canonical_schema = schema_holder({"schema": {"minLength": 1, "required": True}})
    add("schema-inline", schema_holder(schema), canonical_schema)
    add("schema-reference", schema_holder(reference(schema_id)), canonical_schema)

    label_id = publish({"name": "Label", "type": reference(TEXT_TYPE_BLUE_ID)})
    dictionary = lambda value: {"type": reference(DICTIONARY), "valueType": reference(label_id), "entry": value}
    add("dictionary-invalid-inline", dictionary(text), error="TypeCompatibilityViolation")
    add("dictionary-invalid-reference", dictionary(reference(text_id)), error="TypeCompatibilityViolation")
    label = {"type": reference(label_id), "value": "PLN"}
    label_value_id = publish(label)
    add("dictionary-valid-inline", dictionary(label), dictionary(label), resolved={"/entry/value": "PLN"})
    add("dictionary-valid-reference", dictionary(reference(label_value_id)),
        dictionary(reference(label_value_id)), resolved={"/entry/value": "PLN"})

    typed_list = lambda value: {"type": reference(LIST), "itemType": reference(label_id), "items": [value]}
    add("list-invalid-inline", typed_list(text), error="TypeCompatibilityViolation")
    add("list-invalid-reference", typed_list(reference(text_id)), error="TypeCompatibilityViolation")
    list_holder_id = publish({"name": "List holder", "entries": {
        "type": reference(LIST), "itemType": reference(TEXT_TYPE_BLUE_ID)}})
    list_holder = lambda value: {"type": reference(list_holder_id), "entries": value}
    coffee = {"type": reference(TEXT_TYPE_BLUE_ID), "value": "Coffee"}
    tea = {"type": reference(TEXT_TYPE_BLUE_ID), "value": "Tea"}
    coffee_id, tea_id = publish(coffee), publish(tea)
    items = [coffee, tea, coffee]
    raw_list = {"type": reference(LIST), "items": items}
    raw_list_id = publish(raw_list)
    # The direct oracle consumes exact wire Lists as arrays, not an internal
    # items-only Node wrapper. Source object wrappers would infer a List type.
    canonical_list = list_holder(items)
    resolved_list = {"/entries/0/value": "Coffee", "/entries/1/value": "Tea",
                     "/entries/2/value": "Coffee"}
    add("list-inline", list_holder(raw_list), canonical_list, resolved=resolved_list)
    add("list-item-references", list_holder([reference(coffee_id), reference(tea_id), reference(coffee_id)]),
        canonical_list, resolved=resolved_list)
    add("list-whole-reference", list_holder(reference(raw_list_id)), canonical_list, resolved=resolved_list)
    for name, values in [("list-reordered", [tea, coffee, coffee]), ("list-single", [coffee])]:
        add(name, list_holder(values), list_holder(values))

    empty_holder_id = publish({"name": "Empty holder", "field": {"schema": {"maxFields": 3}}})
    empty_id = publish({})
    empty_holder = lambda value: {"type": reference(empty_holder_id), "field": value}
    add("empty-object-inline", empty_holder({}), empty_holder({}))
    add("empty-object-reference", empty_holder(reference(empty_id)), empty_holder(reference(empty_id)))
    for name, value, core_type in [("empty-string", "", TEXT_TYPE_BLUE_ID),
                                  ("zero", 0, INTEGER_TYPE_BLUE_ID),
                                  ("false", False, BOOLEAN_TYPE_BLUE_ID),
                                  ("empty-list", [], LIST)]:
        holder_id = publish({"name": name + " holder", "field": {"type": reference(core_type)}})
        exact = value if isinstance(value, list) else {"type": reference(core_type), "value": value}
        value_id = publish(exact)
        wrap = lambda field: {"type": reference(holder_id), "field": field}
        add(name + "-inline", wrap(exact), wrap(exact))
        add(name + "-reference", wrap(reference(value_id)), wrap(reference(value_id)))

    # Both the exact input and the canonical oracle are kept in one executable
    # corpus. Providers store exact nodes; no Source publication shortcut exists.
    return {"oracle": "hand-constructed canonical nodes + blue_identity.direct_blue_id",
            "provider": provider, "cases": cases}


def outputs():
    matrix = build_vectors()
    result = {MATRIX: json.dumps(matrix, indent=2, ensure_ascii=False) + "\n"}
    selected = {"currency-reference-text", "currency-reference-custom",
                "terms-reference-coffee", "schema-reference",
                "dictionary-invalid-inline", "dictionary-invalid-reference",
                "list-invalid-inline", "list-invalid-reference",
                "dictionary-valid-reference", "empty-object-reference"}
    by_name = {case["name"]: case for case in matrix["cases"]}
    for case in matrix["cases"]:
        if case["name"] not in selected:
            continue
        name = "R_contextual_" + case["name"].replace("-", "_")
        fixture = {"id": name, "category": "Canonicalization", "operation": "canonicalize",
                   "description": "Contextual exact-reference contract: " + case["name"],
                   "source": case["source"], "provider": matrix["provider"]}
        if "expectedErrorCategory" in case:
            fixture["expectError"] = True
            fixture["expectedErrorCategory"] = case["expectedErrorCategory"]
        else:
            fixture["expectedCanonicalOverlay"] = case["expectedCanonical"]
            fixture["expectedNodeBlueId"] = case["expectedSourceBlueId"]
        # alsoEquivalentTo additionally certifies completed instances. The
        # schema-only case is a valid definition without an invented payload;
        # its inline/reference parity is asserted by the shared matrix.
        if case["name"] in {"currency-reference-text", "terms-reference-coffee"}:
            inline_name = case["name"].replace("reference", "inline")
            fixture["alsoEquivalentTo"] = by_name[inline_name]["source"]
        fixture["note"] = "Expected canonical content is authored independently; IDs use the existing direct oracle."
        result[FIXTURES / "resolver" / (name + ".yaml")] = yaml.dump(fixture, Dumper=BlueFixtureDumper, sort_keys=False, width=100)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    stale = []
    for path, content in outputs().items():
        if not path.exists() or path.read_text() != content:
            stale.append(str(path.relative_to(ROOT)))
            if args.write:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
    if stale and not args.write:
        raise SystemExit("Stale contextual reference vectors: " + ", ".join(stale))
    print("CONTEXTUAL_REFERENCE_VECTORS_" + ("WRITTEN" if args.write else "OK"))


if __name__ == "__main__":
    main()
