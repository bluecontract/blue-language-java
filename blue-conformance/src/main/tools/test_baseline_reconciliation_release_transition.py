"""Merged-base reconciliation binds two complete historical pairs, never future drift."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import yaml
import classify_fixture_identity_delta as classifier


class BaselineReconciliationReleaseTransitionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='baseline-reconciliation-classifier-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.migration = Path(__file__).parent / 'migration'
        data = classifier.BASELINE_RECONCILIATION_REVIEW_INPUT_PATH.read_bytes()
        if hashlib.sha256(data).hexdigest() != classifier.BASELINE_RECONCILIATION_REVIEW_INPUT_SHA256:
            raise AssertionError('Exact reconciliation review bytes changed')
        cls.review = json.loads(data)
        cls.extract_archive(cls.review['archive'], cls.root)
        cls.after = cls.root / 'after'
        cls.before = {'upstream': cls.root / 'upstream', 'donor': cls.root / 'donor'}

        # Reconstruct the frozen donor from old approved byte images, not HEAD or
        # the active fixtures. The historical records and approvals stay intact.
        historical_data = classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_PATH.read_bytes()
        if hashlib.sha256(historical_data).hexdigest() != classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_SHA256:
            raise AssertionError('Historical witness-selection record changed')
        if cls.review['donorHistoricalReview'] != {
                'path': classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_PATH.name,
                'sha256': classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_SHA256}:
            raise AssertionError('Reconciliation must preserve the frozen donor review')
        selection = json.loads(historical_data)
        context_data = classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_PATH.read_bytes()
        if hashlib.sha256(context_data).hexdigest() != classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_SHA256:
            raise AssertionError('Historical witness-context record changed')
        cls.extract_archive(json.loads(context_data)['beforeArchive'], cls.before['donor'])
        (cls.before['donor'] / 'release-manifest.yaml').write_text(selection['afterReleaseManifestYaml'])
        cls.before_files = {name: classifier.package_files(root) for name, root in cls.before.items()}
        cls.after_files = classifier.package_files(cls.after)
        cls.transitions = {row['id'].split('-')[2]: row for row in cls.review['transitions']}

    @classmethod
    def extract_archive(cls, descriptor, destination):
        archive = cls.migration / descriptor['path']
        if hashlib.sha256(archive.read_bytes()).hexdigest() != descriptor['sha256']:
            raise AssertionError('Reviewed byte archive changed')
        with tarfile.open(archive) as saved:
            for entry in saved:
                if not entry.isfile() or Path(entry.name).is_absolute() or '..' in Path(entry.name).parts:
                    raise AssertionError('Unsafe reviewed archive entry')
                target = destination / entry.name
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as output:
                    output.write(saved.extractfile(entry).read())

    def test_complete_pairs_have_exact_immutable_inventories_and_release_bindings(self):
        after = {name: classifier.sha256(path) for name, path in self.after_files.items()}
        self.assertEqual(383, len(after))
        self.assertEqual(self.review['after']['files'], after)
        self.assertInventoryIdentity(self.review['after'], after)
        for label, before_files in self.before_files.items():
            with self.subTest(baseline=label):
                transition = self.transitions[label]
                before = {name: classifier.sha256(path) for name, path in before_files.items()}
                self.assertEqual(383, len(before))
                self.assertEqual(transition['before']['files'], before)
                self.assertInventoryIdentity(transition['before'], before)
                self.assertEqual(before.keys(), after.keys())
                self.assertEqual(transition['changedFileCount'],
                                 sum(before[name] != after[name] for name in before))
                reviewed = classifier.reviewed_baseline_reconciliation_transition(before_files, self.after_files)
                self.assertEqual(transition['id'], reviewed['id'])
                old = yaml.safe_load(before_files['release-manifest.yaml'].read_text())
                new = yaml.safe_load(self.after_files['release-manifest.yaml'].read_text())
                for expected, manifest in [(transition['before'], old), (self.review['after'], new)]:
                    self.assertEqual(expected['releaseIdentity'], manifest['releaseIdentity'])
                    self.assertEqual(manifest['releaseIdentity'], classifier.package_identity(manifest, 'releaseIdentity'))
                old_rows = {row['path']: row['sha256'] for row in old['languageDependency']['inputImplementationBaseline']}
                new_rows = {row['path']: row['sha256'] for row in new['languageDependency']['inputImplementationBaseline']}
                self.assertEqual(742, len(new_rows))
                changes = [dict(path=path, before=old_rows.get(path), after=new_rows.get(path))
                           for path in sorted(old_rows.keys() | new_rows.keys())
                           if old_rows.get(path) != new_rows.get(path)]
                self.assertEqual(transition['implementationChanges'], changes)
                self.assertEqual(15 if label == 'donor' else 28, len(changes))
                self.assertFalse(old_rows.keys() - new_rows.keys())

    def test_all_executable_deltas_are_bijective_identities_not_outcome_gas_order_or_state(self):
        sha = re.compile(r'^sha256:[0-9a-f]{64}$')
        for label, before in self.before_files.items():
            with self.subTest(baseline=label):
                identities, reverse = {}, {}
                leaves = changed_fixtures = changed_traces = 0
                for name, old_path in before.items():
                    if not name.startswith('fixtures/closure/') or old_path.read_bytes() == self.after_files[name].read_bytes():
                        continue
                    if name.startswith('fixtures/closure/traces/'):
                        changed_traces += 1
                    else:
                        changed_fixtures += 1
                    differences = classifier.leaf_differences(
                        yaml.safe_load(old_path.read_text()), yaml.safe_load(self.after_files[name].read_text()))
                    for delta in differences:
                        old, new = delta['before'], delta['after']
                        self.assertIsInstance(old, str, (name, delta['path']))
                        self.assertIsInstance(new, str, (name, delta['path']))
                        self.assertRegex(old, sha, (name, delta['path']))
                        self.assertRegex(new, sha, (name, delta['path']))
                        self.assertEqual(new, identities.setdefault(old, new))
                        self.assertEqual(old, reverse.setdefault(new, old))
                        leaves += 1
                transition = self.transitions[label]
                self.assertEqual(80, changed_fixtures)
                self.assertEqual(1, changed_traces)
                self.assertEqual(transition['fixtureIdentityLeaves'], leaves)
                self.assertEqual(transition['distinctFixtureIdentitySubstitutions'], len(identities))

    def test_strict_classifier_recognizes_only_reviewed_pairs_and_original_four_prose_leaves(self):
        historical = json.loads(classifier.ROOTED_LEGAL_DETACHED_RETARGET_REVIEW_INPUT_PATH.read_text())
        for label, before in self.before.items():
            with self.subTest(baseline=label):
                report = classifier.classify(before, self.after)
                transition = self.transitions[label]
                self.assertEqual(0, report['unexpectedCount'])
                self.assertEqual(transition['changedFileCount'], report['changedFileCount'])
                self.assertEqual(0 if label == 'donor' else 2, report['summary'][classifier.SEMANTIC])
                binding = report['reviewedBaselineTransition']
                self.assertEqual(transition['id'], binding['id'])
                self.assertEqual(classifier.BASELINE_RECONCILIATION_REVIEW_INPUT_SHA256, binding['reviewInputSha256'])
                self.assertEqual(215, binding['executableFixturesUnchanged'])
                self.assertEqual(295, binding['executableFixtureSemanticsUnchanged'])
                self.assertEqual(self.review['generationInput'], binding['generationInput'])
                prose = [dict(file=row['path'], **{key: value for key, value in delta.items() if key != 'category'})
                         for row in report['files']
                         if row['path'] in {'fixtures/closure-fixture-schema.yaml', 'identity-constructors.yaml'}
                         for delta in row['differences']]
                self.assertEqual([] if label == 'donor' else historical['normativeProseChanges'], prose)

    def test_added_missing_renamed_changed_or_reversed_package_inventory_is_not_reviewed(self):
        for label, before in self.before_files.items():
            self.assertIsNone(classifier.reviewed_baseline_reconciliation_transition(self.after_files, before))
            for side in ['before', 'after']:
                for mutation in ['added', 'missing', 'renamed', 'changed']:
                    b, a = dict(before), dict(self.after_files)
                    files = b if side == 'before' else a
                    name = 'fixtures/closure/c-clo-01-static-cycle-admission.yaml'
                    if mutation == 'added': files['unreviewed.yaml'] = files[name]
                    elif mutation == 'missing': del files[name]
                    elif mutation == 'renamed': files['unreviewed.yaml'] = files.pop(name)
                    else: files[name] = self.after / 'release-manifest.yaml'
                    with self.subTest(baseline=label, side=side, mutation=mutation):
                        self.assertIsNone(classifier.reviewed_baseline_reconciliation_transition(b, a))

    def test_rehashed_forged_review_record_is_rejected(self):
        forged = deepcopy(self.review)
        forged['after']['files']['release-manifest.yaml'] = '0' * 64
        forged['after']['inventoryIdentity'] = 'sha256:' + hashlib.sha256(
            json.dumps(forged['after']['files'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        path = self.root / 'forged-review.json'
        path.write_text(json.dumps(forged))
        with patch.object(classifier, 'BASELINE_RECONCILIATION_REVIEW_INPUT_PATH', path):
            with self.assertRaises(classifier.ClassificationFailure):
                classifier.reviewed_baseline_reconciliation_transition(self.before_files['upstream'], self.after_files)

    def test_unreviewed_runtime_hash_or_inventory_with_recomputed_release_identity_is_rejected(self):
        for mutation in ['hash', 'added', 'removed', 'renamed']:
            with self.subTest(mutation=mutation):
                value = yaml.safe_load(self.after_files['release-manifest.yaml'].read_text())
                rows = value['languageDependency']['inputImplementationBaseline']
                if mutation == 'hash': rows[0]['sha256'] = '0' * 64
                elif mutation == 'added': rows.append(dict(path='unreviewed/Runtime.java', sha256='1' * 64))
                elif mutation == 'removed': rows.pop()
                else: rows[0]['path'] = 'unreviewed/Runtime.java'
                value['releaseIdentity'] = classifier.package_identity(value, 'releaseIdentity')
                self.assertMutationNotReviewed('runtime-' + mutation, 'release-manifest.yaml', value)

    def test_outcome_gas_order_state_and_identity_mutations_are_rejected(self):
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

    def assertMutationNotReviewed(self, name, relative, value):
        path = self.root / (name + '.yaml')
        path.write_text(yaml.safe_dump(value, sort_keys=False))
        after = dict(self.after_files)
        after[relative] = path
        for label, before in self.before_files.items():
            with self.subTest(baseline=label):
                self.assertIsNone(classifier.reviewed_baseline_reconciliation_transition(before, after))

    def assertInventoryIdentity(self, binding, files):
        self.assertEqual(binding['inventoryIdentity'], 'sha256:' + hashlib.sha256(
            json.dumps(files, sort_keys=True, separators=(',', ':')).encode()).hexdigest())


if __name__ == '__main__':
    unittest.main()
