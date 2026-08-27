#!/usr/bin/env python3
"""Schema coverage for cyclic managed-revision successor evidence."""

from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import unittest

import jsonschema
import yaml


TOOLS_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = TOOLS_ROOT.parent
FIXTURE_ROOT = (
    MODULE_ROOT / "resources/blue-contracts-closure-1.0/fixtures"
)
SCHEMA = FIXTURE_ROOT / "closure-fixture-schema.yaml"
FIXTURE = FIXTURE_ROOT / "closure/c-clo-23-05-a9-to-a10.yaml"


def load_yaml(path: Path) -> dict[str, object]:
    value = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AssertionError(f"Expected a YAML object at {path}")
    return value


def only(values: list[object], label: str) -> dict[str, object]:
    if len(values) != 1 or not isinstance(values[0], dict):
        raise AssertionError(f"Expected one {label}")
    return values[0]


def by_document_id(
    values: list[object], document_id: str
) -> dict[str, object]:
    for value in values:
        if isinstance(value, dict) and value.get("documentId") == document_id:
            return value
    raise AssertionError(f"Missing resulting document {document_id}")


class ManagedRevisionCyclicFixtureSchemaTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = load_yaml(SCHEMA)
        cls.fixture = load_yaml(FIXTURE)
        cls.validator = jsonschema.Draft202012Validator(cls.schema)

    def proof_bearing_envelope(self) -> dict[str, object]:
        envelope = deepcopy(self.fixture)
        expected = envelope["expected"]
        assert isinstance(expected, dict)
        components = expected["resultingComponents"]
        documents = expected["resultingDocuments"]
        assert isinstance(components, list)
        assert isinstance(documents, list)
        complete_proof = only(components, "resulting cyclic component")[
            "completeCyclicProof"
        ]
        assert isinstance(complete_proof, dict)
        placeholders = complete_proof["declaredPlaceholderSet"]
        assert isinstance(placeholders, list)
        result_document = by_document_id(documents, "history-a")

        fixture_oracle = self.fixture["oracle"]
        assert isinstance(fixture_oracle, dict)
        oracle_path = (FIXTURE.parent / str(fixture_oracle["path"])).resolve()
        oracle = load_yaml(oracle_path)
        stages = oracle["stages"]
        assert isinstance(stages, list)
        transition = next(
            stage
            for stage in stages
            if isinstance(stage, dict)
            and stage.get("name")
            == "transition-0-managed-revision-9-10"
        )
        self.assertEqual(transition["canonicalLimitForm"], placeholders)

        input_value = envelope["input"]
        assert isinstance(input_value, dict)
        cause = input_value["cause"]
        assert isinstance(cause, dict)
        cause["afterBlueId"] = result_document["afterBlueId"]
        cause["afterDocument"] = deepcopy(result_document["document"])
        cause["afterCyclicProof"] = {
            "declaredPlaceholderSet": deepcopy(placeholders)
        }
        return envelope

    def test_proof_bearing_cyclic_successor_validates(self) -> None:
        self.validator.validate(self.proof_bearing_envelope())

    def test_cyclic_successor_without_proof_is_rejected(self) -> None:
        envelope = self.proof_bearing_envelope()
        input_value = envelope["input"]
        assert isinstance(input_value, dict)
        cause = input_value["cause"]
        assert isinstance(cause, dict)
        del cause["afterCyclicProof"]

        with self.assertRaises(jsonschema.ValidationError):
            self.validator.validate(envelope)

    def test_proof_on_unchanged_acyclic_successor_is_rejected(self) -> None:
        envelope = deepcopy(self.fixture)
        proof_bearing = self.proof_bearing_envelope()
        proof_input = proof_bearing["input"]
        assert isinstance(proof_input, dict)
        proof_cause = proof_input["cause"]
        assert isinstance(proof_cause, dict)
        input_value = envelope["input"]
        assert isinstance(input_value, dict)
        cause = input_value["cause"]
        assert isinstance(cause, dict)
        cause["afterCyclicProof"] = deepcopy(
            proof_cause["afterCyclicProof"]
        )

        with self.assertRaises(jsonschema.ValidationError):
            self.validator.validate(envelope)


if __name__ == "__main__":
    unittest.main()
