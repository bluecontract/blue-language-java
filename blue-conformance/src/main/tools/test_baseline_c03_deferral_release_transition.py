"""C03 deferral approves one exact metadata-only pair, never future package drift."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import yaml
import classify_fixture_identity_delta as classifier


class BaselineC03DeferralReleaseTransitionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='baseline-c03-deferral-classifier-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        data = classifier.BASELINE_C03_DEFERRAL_REVIEW_INPUT_PATH.read_bytes()
        if hashlib.sha256(data).hexdigest() != classifier.BASELINE_C03_DEFERRAL_REVIEW_INPUT_SHA256:
            raise AssertionError('Exact reviewed C03-deferral record changed')
        cls.review = json.loads(data)
        prior_data = classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_PATH.read_bytes()
        if hashlib.sha256(prior_data).hexdigest() != classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_SHA256:
            raise AssertionError('Historical witness-selection record changed')
        if cls.review['beforeReview'] != dict(path=classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_PATH.name,
                sha256=classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_SHA256):
            raise AssertionError('C03 deferral must retain the exact frozen donor package')
        prior = json.loads(prior_data)
        context_data = classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_PATH.read_bytes()
        if hashlib.sha256(context_data).hexdigest() != classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_SHA256:
            raise AssertionError('Historical witness-context record changed')
        if cls.review['beforeArchive'] != json.loads(context_data)['beforeArchive']:
            raise AssertionError('C03 deferral must reuse the already reviewed byte archive')
        archive = Path(__file__).parent / 'migration' / cls.review['beforeArchive']['path']
        if hashlib.sha256(archive.read_bytes()).hexdigest() != cls.review['beforeArchive']['sha256']:
            raise AssertionError('Historical before archive changed')
        cls.before = cls.root / 'before'
        cls.before.mkdir()
        with tarfile.open(archive) as saved:
            for entry in saved:
                if not entry.isfile() or Path(entry.name).is_absolute() or '..' in Path(entry.name).parts:
                    raise AssertionError('Unsafe reviewed archive entry')
                target = cls.before / entry.name
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as output:
                    output.write(saved.extractfile(entry).read())
        # Actual frozen donor after-image, never the mutable active package.
        (cls.before / 'release-manifest.yaml').write_bytes(prior['afterReleaseManifestYaml'].encode('utf-8'))
        cls.after = cls.root / 'after'
        shutil.copytree(cls.before, cls.after)
        (cls.after / 'release-manifest.yaml').write_bytes(cls.review['afterReleaseManifestYaml'].encode('utf-8'))
        cls.before_files = classifier.package_files(cls.before)
        cls.after_files = classifier.package_files(cls.after)

    def test_complete_pair_changes_only_ten_source_hashes_and_derived_release_identity(self):
        for label, files in [('before', self.before_files), ('after', self.after_files)]:
            actual = {name: classifier.sha256(path) for name, path in files.items()}
            self.assertEqual(383, len(actual))
            self.assertEqual(self.review[label]['files'], actual)
            self.assertEqual(self.review[label]['inventoryIdentity'], 'sha256:' + hashlib.sha256(
                json.dumps(actual, sort_keys=True, separators=(',', ':')).encode()).hexdigest())
        self.assertEqual(['release-manifest.yaml'], [name for name in sorted(self.before_files)
            if self.before_files[name].read_bytes() != self.after_files[name].read_bytes()])
        old = yaml.safe_load(self.before_files['release-manifest.yaml'].read_text())
        new = yaml.safe_load(self.after_files['release-manifest.yaml'].read_text())
        old_rows = old['languageDependency']['inputImplementationBaseline']
        new_rows = new['languageDependency']['inputImplementationBaseline']
        self.assertEqual(742, len(old_rows))
        self.assertEqual(742, len(new_rows))
        self.assertEqual([row['path'] for row in old_rows], [row['path'] for row in new_rows])
        changes = [dict(path=a['path'], before=a['sha256'], after=b['sha256'])
                   for a, b in zip(old_rows, new_rows) if a != b]
        self.assertEqual(10, len(changes))
        self.assertEqual(self.review['implementationChanges'], changes)
        self.assertTrue(all(row['path'].startswith('blue-contracts-core/') for row in changes))
        restored = deepcopy(new)
        restored['releaseIdentity'] = old['releaseIdentity']
        restored['languageDependency']['inputImplementationBaseline'] = old_rows
        self.assertEqual(old, restored, 'No specification, fixture, gas, registry or other binding changes')
        for label, manifest in [('before', old), ('after', new)]:
            self.assertEqual(self.review[label]['releaseIdentity'], manifest['releaseIdentity'])
            self.assertEqual(manifest['releaseIdentity'], classifier.package_identity(manifest, 'releaseIdentity'))
        self.assertEqual(self.review, classifier.reviewed_baseline_c03_deferral_transition(
            self.before_files, self.after_files))
        # One complete classifier pass; the six negative methods use exact frozen inventories.
        report = classifier.classify(self.before, self.after)
        self.assertEqual(0, report['unexpectedCount'])
        self.assertEqual(1, report['changedFileCount'])
        self.assertEqual(0, report['summary'][classifier.SEMANTIC])
        self.assertEqual([classifier.SPEC], report['files'][0]['categories'])
        binding = report['reviewedBaselineTransition']
        self.assertEqual(295, binding['executableFixturesUnchanged'])
        self.assertEqual(295, binding['executableFixtureSemanticsUnchanged'])
        self.assertEqual(self.review['generationInput'], binding['generationInput'])
        self.assertEqual(self.review['id'], binding['id'])
        self.assertEqual(classifier.BASELINE_C03_DEFERRAL_REVIEW_INPUT_SHA256, binding['reviewInputSha256'])

    def test_reversed_pair_is_not_reviewed(self):
        self.assertIsNone(classifier.reviewed_baseline_c03_deferral_transition(self.after_files, self.before_files))

    def test_added_missing_renamed_or_changed_package_files_on_either_side_are_not_reviewed(self):
        for side in ['before', 'after']:
            for mutation in ['added', 'missing', 'renamed', 'changed']:
                before, after = dict(self.before_files), dict(self.after_files)
                files = before if side == 'before' else after
                name = 'fixtures/closure/c-clo-01-static-cycle-admission.yaml'
                if mutation == 'added': files['unreviewed.yaml'] = files[name]
                elif mutation == 'missing': del files[name]
                elif mutation == 'renamed': files['unreviewed.yaml'] = files.pop(name)
                else: files[name] = self.after / 'release-manifest.yaml'
                with self.subTest(side=side, mutation=mutation):
                    self.assertIsNone(classifier.reviewed_baseline_c03_deferral_transition(before, after))

    def test_rehashed_forged_review_record_is_rejected(self):
        changed = deepcopy(self.review)
        changed['after']['files']['release-manifest.yaml'] = '0' * 64
        changed['after']['inventoryIdentity'] = 'sha256:' + hashlib.sha256(
            json.dumps(changed['after']['files'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        path = self.root / 'forged-review.json'
        path.write_text(json.dumps(changed))
        with patch.object(classifier, 'BASELINE_C03_DEFERRAL_REVIEW_INPUT_PATH', path):
            with self.assertRaises(classifier.ClassificationFailure):
                classifier.reviewed_baseline_c03_deferral_transition(self.before_files, self.after_files)

    def test_unreviewed_source_digest_with_recomputed_release_identity_is_not_reviewed(self):
        value = yaml.safe_load(self.after_files['release-manifest.yaml'].read_text())
        value['languageDependency']['inputImplementationBaseline'][0]['sha256'] = '0' * 64
        value['releaseIdentity'] = classifier.package_identity(value, 'releaseIdentity')
        self.assertMutationNotReviewed('source-hash', 'release-manifest.yaml', value)

    def test_outcome_gas_order_state_and_identity_mutations_are_not_reviewed(self):
        relative = 'fixtures/closure/c-clo-01-static-cycle-admission.yaml'
        mutations = [
            ('outcome', lambda value: value['expected'].__setitem__('attemptOutcome', 'NeedsResources')),
            ('gas', lambda value: value['expected'].__setitem__('totalGas', value['expected']['totalGas'] + 1)),
            ('order', lambda value: value['expected']['gasTrace'].reverse()),
            ('state', lambda value: value['expected']['resultingDocuments'][0].__setitem__('document', {'unreviewed': True})),
            ('identity', lambda value: value['expected'].__setitem__('invocationIdentity', 'sha256:' + '0' * 64)),
        ]
        for name, mutate in mutations:
            with self.subTest(mutation=name):
                value = yaml.safe_load(self.after_files[relative].read_text())
                original = deepcopy(value)
                mutate(value)
                self.assertNotEqual(original, value)
                self.assertMutationNotReviewed(name, relative, value)

    def test_added_removed_or_renamed_runtime_inventory_with_recomputed_identity_is_not_reviewed(self):
        for mutation in ['added', 'removed', 'renamed']:
            with self.subTest(mutation=mutation):
                value = yaml.safe_load(self.after_files['release-manifest.yaml'].read_text())
                rows = value['languageDependency']['inputImplementationBaseline']
                if mutation == 'added': rows.append(dict(path='unreviewed/Runtime.java', sha256='1' * 64))
                elif mutation == 'removed': rows.pop()
                else: rows[0]['path'] = 'unreviewed/Runtime.java'
                value['releaseIdentity'] = classifier.package_identity(value, 'releaseIdentity')
                self.assertMutationNotReviewed('inventory-' + mutation, 'release-manifest.yaml', value)

    def assertMutationNotReviewed(self, name, relative, value):
        path = self.root / (name + '.yaml')
        path.write_text(yaml.safe_dump(value, sort_keys=False))
        after = dict(self.after_files)
        after[relative] = path
        self.assertIsNone(classifier.reviewed_baseline_c03_deferral_transition(self.before_files, after))


if __name__ == '__main__':
    unittest.main()
