#!/usr/bin/env python3
"""Tests for the production empty-sentinel classification inventory."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


TOOLS = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "generate_empty_sentinel_audit",
    TOOLS / "generate_empty_sentinel_audit.py",
)
assert SPEC is not None and SPEC.loader is not None
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


class EmptySentinelAuditTest(unittest.TestCase):

    def test_classifies_mapping_empty_shape_without_erasing_empty_object(self) -> None:
        classification, reason = audit._classification(
            "blue-language-mapping/src/main/java/blue/language/mapping/CollectionConverter.java",
            "if (node == null || Nodes.isEmptyNode(node)) {",
            "empty-shape-and-builder",
        )
        self.assertEqual(
            "D-host-absence-from-fieldless-source-control", classification
        )
        self.assertIn("exact `{}`", reason)

    def test_classifies_frozen_node_empty_as_exact_empty_object(self) -> None:
        classification, reason = audit._classification(
            "blue-language-core/src/main/java/blue/language/snapshot/FrozenNode.java",
            "return FrozenNode.empty();",
            "empty-shape-and-builder",
        )
        self.assertEqual("A-exact-empty-object-value", classification)
        self.assertIn("exact `{}`", reason)
        self.assertIn("never an absence", reason)

    def test_symbol_does_not_misclassify_control_flow_as_a_method(self) -> None:
        lines = [
            "final class Example {",
            "    private void reconcile(Node node) {",
            "        if (node != null) {",
            "            return FrozenNode.empty();",
            "        }",
            "    }",
            "}",
        ]
        self.assertEqual("Example#reconcile", audit._symbol(lines, 4))

    def test_broad_required_vocabulary_is_context_only(self) -> None:
        decision = audit._decision(
            "blue-language-core/src/main/java/blue/language/api/BlueOperationResult.java",
            'throw new IllegalArgumentException("status is required");',
            "",
            "BlueOperationResult#validate",
            "schema-presence",
        )
        self.assertIsNone(decision)
        self.assertIn(
            "rather than an empty-object presence decision",
            audit._context_reason("schema-presence", "required"),
        )

    def test_schema_min_fields_is_decision_relevant(self) -> None:
        decision = audit._decision(
            "blue-language-core/src/main/java/blue/language/merge/processor/SchemaVerifier.java",
            "int minimum = schema.getMinFields();",
            "",
            "SchemaVerifier#verifyMinFields",
            "schema-presence",
        )
        self.assertIsNotNone(decision)
        self.assertEqual(
            "A-required-and-field-count-separation",
            decision.classification,
        )

    def test_populated_builder_is_context_only_but_bare_builder_is_relevant(self) -> None:
        populated = audit._decision(
            "blue-language-model/src/main/java/blue/language/model/Example.java",
            'return new Node().value("x");',
            "",
            "Example#value",
            "empty-shape-and-builder",
        )
        bare = audit._decision(
            "blue-language-model/src/main/java/blue/language/model/NodePathEditor.java",
            "Node builder = new Node();",
            "",
            "NodePathEditor#childAtOrCreate",
            "empty-shape-and-builder",
        )
        self.assertIsNone(populated)
        self.assertIsNotNone(bare)
        self.assertEqual(
            "B-temporary-fieldless-builder-or-control-container",
            bare.classification,
        )

    def test_raw_blue_id_search_is_recorded_without_empty_sentinel_claim(self) -> None:
        decision = audit._decision(
            "blue-language-core/src/main/java/blue/language/identity/Example.java",
            "return node.getBlueId();",
            "",
            "Example#blueId",
            "raw-blue-id-access",
        )
        self.assertIsNone(decision)

    def test_inventory_covers_every_declared_audit_family(self) -> None:
        repository = TOOLS.parents[3]
        report = audit.generate(repository)
        expected = {value[0] for value in audit.AUDITS}
        self.assertEqual(expected, set(report["summary"]["byAudit"]))
        self.assertGreater(report["summary"]["productionHitCount"], 0)
        for entry in report["entries"]:
            self.assertTrue(entry["path"])
            self.assertGreater(entry["line"], 0)
            self.assertTrue(entry["symbol"])
            self.assertIn(entry["relevance"], {"decision-relevant", "context-only"})
            self.assertIn(
                entry["reviewDisposition"],
                {"resolved-explicit-decision", "resolved-context-only"},
            )
            self.assertIn(
                entry["decisionBasis"],
                {
                    "explicit-path-symbol-rule",
                    "explicitly-excluded-broad-search-context",
                },
            )
            self.assertTrue(entry["oldAssumption"])
            self.assertTrue(entry["newClassification"])
            self.assertIsInstance(entry["changed"], bool)
            self.assertTrue(entry["reason"])
            self.assertTrue(entry["coveringTest"])

    def test_every_structural_empty_predicate_has_explicit_disposition(self) -> None:
        repository = TOOLS.parents[3]
        report = audit.generate(repository)
        structural = [
            entry
            for entry in report["entries"]
            if entry["audit"] == "empty-shape-and-builder"
            and (
                "isEmptyNode(" in entry["source"]
                or "FrozenNode.empty()" in entry["source"]
            )
        ]
        self.assertGreater(len(structural), 0)
        self.assertTrue(
            all(
                entry["reviewDisposition"]
                == "resolved-explicit-decision"
                for entry in structural
            )
        )


if __name__ == "__main__":
    unittest.main()
