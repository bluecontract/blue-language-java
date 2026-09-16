"""Fresh witness selection approves one exact generated package pair, not a metadata rule."""
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


class WitnessSelectionReleaseTransitionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='witness-selection-classifier-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        data = classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_PATH.read_bytes()
        if hashlib.sha256(data).hexdigest() != classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_SHA256:
            raise AssertionError('Exact reviewed witness-selection record changed')
        cls.review = json.loads(data)
        prior_data = classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_PATH.read_bytes()
        if hashlib.sha256(prior_data).hexdigest() != classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_SHA256:
            raise AssertionError('Historical witness-context record changed')
        prior = json.loads(prior_data)
        if cls.review['beforeReview'] != dict(path=classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_PATH.name,
                sha256=classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_SHA256):
            raise AssertionError('Witness selection must retain the exact reviewed prior package')
        cls.before = cls.root / 'before'
        cls.before.mkdir()
        archive = Path(__file__).parent / 'migration' / prior['beforeArchive']['path']
        if hashlib.sha256(archive.read_bytes()).hexdigest() != prior['beforeArchive']['sha256']:
            raise AssertionError('Historical before archive changed')
        with tarfile.open(archive) as saved:
            for entry in saved:
                if not entry.isfile() or Path(entry.name).is_absolute() or '..' in Path(entry.name).parts:
                    raise AssertionError('Unsafe reviewed archive entry')
                target = cls.before / entry.name
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as output:
                    output.write(saved.extractfile(entry).read())
        # Reuse the previous actual generated after-image; never the active checkout.
        (cls.before / 'release-manifest.yaml').write_bytes(prior['afterReleaseManifestYaml'].encode('utf-8'))
        cls.after = cls.root / 'after'
        shutil.copytree(cls.before, cls.after)
        (cls.after / 'release-manifest.yaml').write_bytes(cls.review['afterReleaseManifestYaml'].encode('utf-8'))

    def test_complete_pair_changes_only_two_source_digests_one_added_source_and_release_identity(self):
        before, after = classifier.package_files(self.before), classifier.package_files(self.after)
        for label, files in [('before', before), ('after', after)]:
            actual = {name: classifier.sha256(path) for name, path in files.items()}
            self.assertEqual(383, len(actual))
            self.assertEqual(self.review[label]['files'], actual)
            self.assertEqual(self.review[label]['inventoryIdentity'], 'sha256:' + hashlib.sha256(
                json.dumps(actual, sort_keys=True, separators=(',', ':')).encode()).hexdigest())
        self.assertEqual(['release-manifest.yaml'], [name for name in sorted(before)
                         if before[name].read_bytes() != after[name].read_bytes()])
        old = yaml.safe_load(before['release-manifest.yaml'].read_text())
        new = yaml.safe_load(after['release-manifest.yaml'].read_text())
        old_rows = {row['path']: row['sha256'] for row in old['languageDependency']['inputImplementationBaseline']}
        new_rows = {row['path']: row['sha256'] for row in new['languageDependency']['inputImplementationBaseline']}
        prefix = 'blue-contracts-core/src/main/java/blue/language/processor/closure/'
        self.assertEqual(741, len(old_rows))
        self.assertEqual(742, len(new_rows))
        self.assertEqual({prefix + 'RootedWitnessSelection.java'}, new_rows.keys() - old_rows.keys())
        self.assertFalse(old_rows.keys() - new_rows.keys())
        changes = [dict(path=path, before=old_rows.get(path), after=new_rows.get(path))
                   for path in sorted(old_rows.keys() | new_rows.keys()) if old_rows.get(path) != new_rows.get(path)]
        self.assertEqual({prefix + name for name in ['ClosureEvidenceFactory.java', 'RootedWitnessFrame.java',
                         'RootedWitnessSelection.java']}, {row['path'] for row in changes})
        self.assertEqual(self.review['implementationChanges'], changes)
        restored = deepcopy(new)
        restored['releaseIdentity'] = old['releaseIdentity']
        restored['languageDependency']['inputImplementationBaseline'] = old['languageDependency']['inputImplementationBaseline']
        self.assertEqual(old, restored, 'No specification, fixture, gas, registry or other binding changes')
        for label, manifest in [('before', old), ('after', new)]:
            self.assertEqual(self.review[label]['releaseIdentity'], manifest['releaseIdentity'])
            self.assertEqual(manifest['releaseIdentity'], classifier.package_identity(manifest, 'releaseIdentity'))
        self.assertEqual(self.review, classifier.reviewed_rooted_witness_selection_transition(before, after))
        # One full classifier pass; mutation controls below hash exact frozen byte inventories.
        report = classifier.classify(self.before, self.after)
        self.assertEqual(0, report['unexpectedCount'])
        self.assertEqual(1, report['changedFileCount'])
        self.assertEqual(0, report['summary'][classifier.SEMANTIC])
        self.assertEqual([classifier.SPEC], report['files'][0]['categories'])
        self.assertEqual(295, report['reviewedBaselineTransition']['executableFixturesUnchanged'])
        self.assertEqual(self.review['id'], report['reviewedBaselineTransition']['id'])
        self.assertEqual(classifier.ROOTED_WITNESS_SELECTION_REVIEW_INPUT_SHA256,
                         report['reviewedBaselineTransition']['reviewInputSha256'])

    def test_reversed_pair_is_not_reviewed(self):
        self.assertIsNone(classifier.reviewed_rooted_witness_selection_transition(
            classifier.package_files(self.after), classifier.package_files(self.before)))

    def test_added_missing_renamed_or_changed_package_files_on_either_side_are_not_reviewed(self):
        before, after = classifier.package_files(self.before), classifier.package_files(self.after)
        for side in ['before', 'after']:
            for mutation in ['added', 'missing', 'renamed', 'changed']:
                b, a = dict(before), dict(after)
                files = b if side == 'before' else a
                name = 'fixtures/closure/c-clo-01-static-cycle-admission.yaml'
                if mutation == 'added': files['unreviewed.yaml'] = files[name]
                elif mutation == 'missing': del files[name]
                elif mutation == 'renamed': files['unreviewed.yaml'] = files.pop(name)
                else: files[name] = self.after / 'release-manifest.yaml'
                with self.subTest(side=side, mutation=mutation):
                    self.assertIsNone(classifier.reviewed_rooted_witness_selection_transition(b, a))

    def test_rehashed_forged_review_record_is_rejected(self):
        changed = deepcopy(self.review)
        changed['after']['files']['release-manifest.yaml'] = '0' * 64
        changed['after']['inventoryIdentity'] = 'sha256:' + hashlib.sha256(
            json.dumps(changed['after']['files'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        path = self.root / 'forged-review.json'
        path.write_text(json.dumps(changed))
        with patch.object(classifier, 'ROOTED_WITNESS_SELECTION_REVIEW_INPUT_PATH', path):
            with self.assertRaises(classifier.ClassificationFailure):
                classifier.reviewed_rooted_witness_selection_transition(
                    classifier.package_files(self.before), classifier.package_files(self.after))

    def test_unreviewed_source_digest_with_recomputed_release_identity_is_not_reviewed(self):
        value = yaml.safe_load((self.after / 'release-manifest.yaml').read_text())
        value['languageDependency']['inputImplementationBaseline'][0]['sha256'] = '0' * 64
        value['releaseIdentity'] = classifier.package_identity(value, 'releaseIdentity')
        self.assertManifestNotReviewed('source-hash', value)

    def test_outcome_gas_and_order_mutations_are_not_reviewed(self):
        mutations = [
            ('outcome', 'fixtures/closure/c-evo-18-missing-exact-node.yaml',
             lambda value: value['expected'].__setitem__('attemptOutcome', 'Complete')),
            ('gas', 'fixtures/closure/c-clo-01-static-cycle-admission.yaml',
             lambda value: value['expected'].__setitem__('totalGas', value['expected']['totalGas'] + 1)),
            ('order', 'fixtures/closure/c-clo-01-static-cycle-admission.yaml',
             lambda value: value['expected']['gasTrace'].reverse()),
        ]
        for name, relative, mutate in mutations:
            with self.subTest(mutation=name):
                path = self.root / (name + '.yaml')
                value = yaml.safe_load((self.after / relative).read_text())
                original = deepcopy(value)
                mutate(value)
                self.assertNotEqual(original, value)
                path.write_text(yaml.safe_dump(value, sort_keys=False))
                after = classifier.package_files(self.after)
                after[relative] = path
                self.assertIsNone(classifier.reviewed_rooted_witness_selection_transition(
                    classifier.package_files(self.before), after))

    def test_added_removed_or_renamed_runtime_inventory_with_recomputed_identity_is_not_reviewed(self):
        for mutation in ['added', 'removed', 'renamed']:
            with self.subTest(mutation=mutation):
                value = yaml.safe_load((self.after / 'release-manifest.yaml').read_text())
                rows = value['languageDependency']['inputImplementationBaseline']
                new = next(row for row in rows if row['path'].endswith('/RootedWitnessSelection.java'))
                if mutation == 'added': rows.append(dict(path='unreviewed/Runtime.java', sha256='1' * 64))
                elif mutation == 'removed': rows.remove(new)
                else: new['path'] = 'unreviewed/Runtime.java'
                value['releaseIdentity'] = classifier.package_identity(value, 'releaseIdentity')
                self.assertManifestNotReviewed('inventory-' + mutation, value)

    def assertManifestNotReviewed(self, name, value):
        path = self.root / (name + '.yaml')
        path.write_text(yaml.safe_dump(value, sort_keys=False))
        after = classifier.package_files(self.after)
        after['release-manifest.yaml'] = path
        self.assertIsNone(classifier.reviewed_rooted_witness_selection_transition(
            classifier.package_files(self.before), after))


if __name__ == '__main__':
    unittest.main()
