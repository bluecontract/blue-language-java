#!/usr/bin/env python3
"""Focused negative and independent-evidence checks for the Contracts validator."""
from __future__ import annotations

import importlib.util
from copy import deepcopy
from pathlib import Path
import hashlib
import sys
import tempfile
import types
import unittest

if importlib.util.find_spec("jsonschema") is None:
    # This focused unit does not execute schema validation.  Keep it runnable
    # in the lightweight tool-test environment where jsonschema is optional.
    sys.modules["jsonschema"] = types.ModuleType("jsonschema")

import validate_package


class ValidatePackageBaselineTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.original_paths = {name: getattr(validate_package, name)
                              for name in ("FIX", "CLOSURE", "REGISTRY", "GAS", "ORACLES")}
        if not validate_package.CLOSURE.is_dir():
            package = Path(__file__).resolve().parents[1] / "resources/blue-contracts-closure-1.0"
            validate_package.FIX = package / "fixtures"
            validate_package.CLOSURE = package / "fixtures/closure"
            validate_package.REGISTRY = package / "registry"
            validate_package.GAS = package / "gas-manifest.yaml"
            validate_package.ORACLES = package / "oracles"

    @classmethod
    def tearDownClass(cls) -> None:
        for name, value in cls.original_paths.items():
            setattr(validate_package, name, value)

    def test_referenced_declaration_requires_available_hash_verified_source(self) -> None:
        declaration = {"type": {"blueId": validate_package.PROCESS_EMBEDDED},
                       "collectionPaths": ["/orders"]}
        blue_id = validate_package.direct_blue_id(declaration)
        document = {"orders": {}, "contracts": {"embedded": {"blueId": blue_id}}}
        def check(nodes):
            validate_package.validate_process_embedded_declaration_coverage(
                Path("exact-declaration.yaml"), {"root": {"document": document}}, [], provider_nodes=nodes)
        check({blue_id: declaration})
        with self.assertRaises(validate_package.ValidationFailure): check({})
        with self.assertRaises(validate_package.ValidationFailure):
            check({blue_id: dict(declaration, collectionPaths=["/forged"])})

    def test_dynamic_initialization_requires_the_exact_activating_work(self) -> None:
        path = validate_package.CLOSURE / "c-evo-21-retry-determinism-resolved-first.yaml"
        fixture = validate_package.load_yaml(path)
        def check(expected):
            return validate_package.validate_work_trace(path, fixture["input"], expected, [],
                                                       expected["invocationIdentity"])
        self.assertEqual(3, len(check(fixture["expected"])))
        forged = deepcopy(fixture["expected"])
        forged["workTrace"][2]["sourceOccurrenceIdentity"] = fixture["input"]["cause"]["causeIdentity"]
        with self.assertRaises(validate_package.ValidationFailure): check(forged)
        forged = deepcopy(fixture["expected"])
        for entry in forged["gasTrace"]:
            if entry["counter"] == "managedOccurrenceBindingVerified" and entry.get("reason") == "work.1.topology-change":
                entry["workOccurrenceId"] = forged["workTrace"][0]["workIdentity"]
        with self.assertRaises(validate_package.ValidationFailure): check(forged)

    def test_active_outgoing_edge_preserves_the_admission_initialization_cause(self) -> None:
        path = validate_package.CLOSURE / "c-clo-08-cycle-during-initialization.yaml"
        fixture = validate_package.load_yaml(path)
        def check(expected):
            return validate_package.validate_work_trace(path, fixture["input"], expected, [],
                                                       expected["invocationIdentity"])
        self.assertEqual(4, len(check(fixture["expected"])))
        forged = deepcopy(fixture["expected"])
        forged["workTrace"][2]["sourceOccurrenceIdentity"] = forged["workTrace"][1]["workIdentity"]
        with self.assertRaises(validate_package.ValidationFailure): check(forged)

    def test_absent_collection_requires_complete_evidence_and_inactive_direct_rows(self) -> None:
        document = {"contracts": {"embedded": {
            "type": {"blueId": validate_package.PROCESS_EMBEDDED},
            "collectionPaths": ["/orders"],
        }}}
        row = {"sourceDocumentId": "root", "sourcePath": "/orders/a~1b", "active": False}
        def check(body, rows):
            validate_package.validate_process_embedded_declaration_coverage(
                Path("absent-collection.yaml"), {"root": {"document": body}}, rows)
        check(document, [])
        check(document, [row])
        check(dict(document, orders={}), [])
        for bad_row in (dict(row, active=True), dict(row, sourcePath="/orders/a/deeper")):
            with self.subTest(row=bad_row), self.assertRaises(validate_package.ValidationFailure):
                check(document, [bad_row])
        for incompatible in ([], 1, None, {"blueId": "unavailable"},
                             {"blueId": "6nSgJdnSWrZjkzcDc9pBd2vXhx63Q1YVKPmXViwhDp4D"}):
            with self.subTest(collection=incompatible), self.assertRaises(validate_package.ValidationFailure):
                check(dict(document, orders=incompatible), [])
        nested = deepcopy(document)
        nested["contracts"]["embedded"]["collectionPaths"] = ["/container/orders"]
        nested["container"] = {"blueId": "unavailable"}
        with self.assertRaises(validate_package.ValidationFailure):
            check(nested, [])

    def test_prospective_admission_requires_exact_retained_binding(self) -> None:
        path = validate_package.CLOSURE / "c-emb-empty-05-prospective-activation.yaml"
        fixture = validate_package.load_yaml(path)["input"]
        check = validate_package.validate_admission_input_membership
        check(path, fixture["documents"], fixture["occurrences"])
        with self.assertRaises(validate_package.ValidationFailure):
            check(path, fixture["documents"], [])
        for field, value in (("bindingIdentity", "sha256:" + "0" * 64),
                             ("expectedTargetBlueId", "6fQvUYSTKJRZkg3SMdif8W34YqrFgTzoFNaY7wfCvXYS")):
            rows = deepcopy(fixture["occurrences"])
            rows[0][field] = value
            with self.subTest(field=field), self.assertRaises(validate_package.ValidationFailure):
                check(path, fixture["documents"], rows)

    def test_event_only_marker_reconstruction_checks_every_final_state_and_order(self) -> None:
        path = validate_package.CLOSURE / "c-evt-collection-07-closure-work-order-inline-cold.yaml"
        fixture = validate_package.load_yaml(path)
        derive = lambda value: validate_package.reconstruct_event_only_acyclic_markers(path, value)
        result = derive(fixture)
        self.assertEqual(set(fixture["input"]["documents"]), set(result))
        after = validate_package.result_documents(fixture["expected"])
        for key in result:
            self.assertEqual(after[key]["document"]["contracts"]["initialized"]["document"]["blueId"], result[key])
        forged = deepcopy(fixture)
        forged["expected"]["resultingDocuments"][0]["document"]["forged"] = True
        self.assertEqual({}, derive(forged))
        forged = deepcopy(fixture)
        forged["runtime"]["handlers"]["c-evt-collection-07-root/receiveExactOrder"] = {"patches": []}
        self.assertEqual({}, derive(forged))
        forged = deepcopy(fixture)
        marker = next(row for row in forged["expected"]["gasTrace"]
                      if row.get("reason") == "initialization-batch.marker.c-evt-collection-07-leaf")
        marker["sequence"] = 100000
        self.assertEqual({}, derive(forged))

    def test_checksum_manifest_requires_exact_canonical_lines(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="blue-checksum-manifest-"
        ) as temporary:
            root = Path(temporary)
            for name in ("a.txt", "b.txt"):
                (root / name).write_text(name + "\n", encoding="utf-8")
            canonical_lines = [
                f"{hashlib.sha256((root / name).read_bytes()).hexdigest()}  {name}"
                for name in ("a.txt", "b.txt")
            ]
            manifest = root / "MANIFEST.sha256"
            previous_root = validate_package.ROOT
            validate_package.ROOT = root
            try:
                manifest.write_text(
                    "\n".join(canonical_lines) + "\n", encoding="utf-8"
                )
                validate_package.validate_checksum_manifest()

                invalid_values = {
                    "duplicate": canonical_lines + [canonical_lines[-1]],
                    "reordered": list(reversed(canonical_lines)),
                    "malformed separator": [canonical_lines[0].replace("  ", " ")],
                    "uppercase digest": [canonical_lines[0].upper()],
                    "unexpected traversal": ["0" * 64 + "  ../outside"],
                    "blank line": canonical_lines + [""],
                }
                for name, lines in invalid_values.items():
                    with self.subTest(name=name):
                        manifest.write_text(
                            "\n".join(lines) + "\n", encoding="utf-8"
                        )
                        with self.assertRaises(
                            validate_package.ValidationFailure
                        ):
                            validate_package.validate_checksum_manifest()

                manifest.write_bytes(
                    ("\r\n".join(canonical_lines) + "\r\n").encode("utf-8")
                )
                with self.assertRaises(validate_package.ValidationFailure):
                    validate_package.validate_checksum_manifest()
            finally:
                validate_package.ROOT = previous_root

    @staticmethod
    def baseline() -> list[dict[str, str]]:
        return [
            {"path": path, "sha256": "0" * 64}
            for path in validate_package.IMPLEMENTATION_BASELINE_SOURCE_PATHS
        ]

    def test_only_lowercase_unprefixed_sha256_is_accepted(self) -> None:
        self.assertTrue(validate_package.is_lowercase_sha256("0" * 64))
        for invalid in (
            "A" * 64,
            "0" * 63,
            "0" * 65,
            "sha256:" + "0" * 64,
            "g" * 64,
            None,
            0,
        ):
            with self.subTest(invalid=invalid):
                self.assertFalse(validate_package.is_lowercase_sha256(invalid))

    def test_exact_checked_in_inventory_is_accepted(self) -> None:
        baseline = self.baseline()
        by_path = validate_package.validate_input_implementation_baseline(
            baseline
        )
        self.assertEqual(
            validate_package.IMPLEMENTATION_BASELINE_SOURCE_PATHS,
            tuple(by_path),
        )

    def test_inventory_or_digest_drift_is_rejected(self) -> None:
        cases: dict[str, list[dict[str, str]]] = {}
        missing = self.baseline()
        missing.pop()
        cases["missing"] = missing
        added = self.baseline()
        added.append({"path": "unreviewed/Added.java", "sha256": "0" * 64})
        cases["added"] = added
        reordered = self.baseline()
        reordered[0], reordered[1] = reordered[1], reordered[0]
        cases["reordered"] = reordered
        uppercase = self.baseline()
        uppercase[0]["sha256"] = "A" * 64
        cases["uppercase digest"] = uppercase
        extra_key = deepcopy(self.baseline())
        extra_key[0]["bytes"] = "1"
        cases["extra entry key"] = extra_key

        for name, baseline in cases.items():
            with self.subTest(name=name), self.assertRaises(
                validate_package.ValidationFailure
            ):
                validate_package.validate_input_implementation_baseline(
                    baseline
                )


if __name__ == "__main__":
    unittest.main()
