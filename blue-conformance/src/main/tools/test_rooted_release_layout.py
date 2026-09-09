"""Closed layout/schema sensitivity tests; no runtime-conformance claim."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
import jsonschema
import rooted_release_layout as layout

RESOURCE_ROOT = Path(__file__).resolve().parents[1] / 'resources/blue-rooted-checkpoint-1.0'


def require(condition, message):
    if not condition:
        raise ValueError(message)


class RootedReleaseLayoutTest(unittest.TestCase):
    def test_standalone_bex_and_extra_specification_still_reject(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root / 'specifications').mkdir()
            spec = root / 'specifications' / layout.CONTRACTS_SPEC
            spec.write_text('Standalone Contracts specification')
            self.assertIsNone(layout.validate_release_layout(root, spec, require))
            extra = root / 'unexpected-bex.yaml'; extra.write_text('outside: true')
            with self.assertRaisesRegex(ValueError, 'BEX file included'):
                layout.validate_release_layout(root, spec, require)
            extra.unlink(); extra = root / 'specifications/unrelated.md'; extra.write_text('extra')
            with self.assertRaisesRegex(ValueError, 'unexpected specification documents'):
                layout.validate_release_layout(root, spec, require)

    def test_normative_link_without_complete_companion_rejects(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root / 'specifications').mkdir()
            spec = root / 'specifications' / layout.CONTRACTS_SPEC
            spec.write_text(layout.ROOTED_SPEC)
            with self.assertRaisesRegex(ValueError, 'complete separately indexed'):
                layout.validate_release_layout(root, spec, require)

    def test_literal_schema_and_closed_counterexamples(self):
        suite = RESOURCE_ROOT / layout.SUITE
        validator = jsonschema.Draft202012Validator(layout.json_file(suite / 'schemas/fixture.schema.json'))
        fixture = layout.json_file(suite / 'fixtures/run/rcp-run-017.json')
        validator.validate(fixture)
        mutants = []
        value = deepcopy(fixture); value['expected']['contract'] = {}; mutants.append(value)
        value = deepcopy(fixture); value['expected']['oracleVersion'] = 3; mutants.append(value)
        value = deepcopy(fixture); value['input']['literalPlan'] = '../escape.json'; mutants.append(value)
        value = deepcopy(fixture); value['input'].pop('literalPlan'); mutants.append(value)
        value = deepcopy(fixture); value['expected'] = {'assertions': []}; mutants.append(value)
        value = deepcopy(fixture); value['recipeProvenance'] = {'path':'provenance/recipe-inputs/rcp-run-017.json', 'sha256':'0'*64, 'ignoreHash':True}; mutants.append(value)
        for ordinal, value in enumerate(mutants):
            with self.subTest(ordinal=ordinal), self.assertRaises(jsonschema.ValidationError):
                validator.validate(value)

    def test_packaged_fault_matrix_schema_has_exact_closed_lanes_cuts_and_bounds(self):
        suite = RESOURCE_ROOT / layout.SUITE
        validator = jsonschema.Draft202012Validator(layout.json_file(suite / 'schemas/fixture.schema.json'))
        fixture = layout.json_file(suite / 'fixtures/run/rcp-run-028.json')
        validator.validate(fixture)
        edits = [lambda c: c['faultCuts']['lanes'].pop(),
                 lambda c: c['faultCuts']['cuts'].pop(),
                 lambda c: c['faultCuts'].__setitem__('startupSeconds', 600),
                 lambda c: c['faultCuts'].__setitem__('maxConcurrentLanes', 2),
                 lambda c: c['faultCuts'].__setitem__('skipFailedCut', True),
                 lambda c: c.__setitem__('causeKind', 'LIVE')]
        for ordinal, edit in enumerate(edits):
            value = deepcopy(fixture); edit(value['expected']['contract'])
            with self.subTest(ordinal=ordinal), self.assertRaises(jsonschema.ValidationError):
                validator.validate(value)

    def test_duplicate_json_identity_field_rejects(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'manifest.json'; path.write_text('{"files":{},"files":{"unexpected":"x"}}')
            with self.assertRaisesRegex(ValueError, 'Duplicate JSON field'):
                layout.json_file(path)


if __name__ == '__main__':
    unittest.main(verbosity=2)
