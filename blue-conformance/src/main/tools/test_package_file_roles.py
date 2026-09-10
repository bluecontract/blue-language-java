"""Outer-package classification negatives; these do not assert runtime conformance."""
from copy import deepcopy
import unittest
import build_release_manifests as build
import validate_package as validate


class PackageFileRolesTest(unittest.TestCase):
    def test_normative_operands_and_informative_provenance_stay_distinct(self):
        expected = {
            'specifications/rooted-checkpoint-processing-1.0-draft.md': 'normative',
            'conformance/contracts/gas-manifest.yaml': 'normative',
            'conformance/rooted-processing/tariffs/bex-gas-1.0.yaml': 'normative',
            'companions/rooted-checkpoint/specifications/blue-bex-specification-2.0.md': 'normative',
            'companions/rooted-checkpoint/conformance/rooted-processing/fixture-index.json': 'normative',
            'companions/rooted-checkpoint/conformance/rooted-processing/schemas/fixture.schema.json': 'normative',
            'companions/rooted-checkpoint-manifest.json': 'normative',
            'companions/rooted-checkpoint/manifests/specification-set.json': 'normative',
            'companions/rooted-checkpoint/IMPORT.json': 'informative',
            'companions/rooted-checkpoint/historical/specifications/old.md': 'informative',
            'companions/rooted-checkpoint/tools/test_harness.py': 'informative',
            'companions/rooted-checkpoint/manifests/unrelated.json': 'informative',
            'companions/rooted-checkpoint-other/specifications/unrelated.md': 'informative',
            'conformance/rooted-processing-extra/fixture-index.json': 'informative',
            'reference/blue-language-specification-1.0.md': 'informative',
        }
        for path, role in expected.items():
            with self.subTest(path=path):
                self.assertEqual(role, build.package_file_role(path))
        package = {'files': [{'path': path, 'role': role} for path, role in expected.items()]}
        validate.validate_package_file_roles(package)
        for index in range(len(package['files'])):
            changed = deepcopy(package)
            changed['files'][index]['role'] = 'informative' if changed['files'][index]['role'] == 'normative' else 'normative'
            # Even a newly self-consistent manifest hash cannot authorize a wrong role.
            changed['packageIdentity'] = build.package_identity(changed, 'packageIdentity')
            with self.subTest(path=changed['files'][index]['path']), self.assertRaisesRegex(validate.ValidationFailure, 'file role mismatch'):
                validate.validate_package_file_roles(changed)

    def test_missing_role_rejected_independently(self):
        with self.assertRaisesRegex(validate.ValidationFailure, 'file role mismatch'):
            validate.validate_package_file_roles({'files': [{'path': 'conformance/contracts/gas-manifest.yaml'}]})


if __name__ == '__main__':
    unittest.main(verbosity=2)
