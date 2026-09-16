"""The witness-context correction approves one exact metadata-only generated pair."""
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


class WitnessContextReleaseTransitionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='witness-context-classifier-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        data = classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_PATH.read_bytes()
        if hashlib.sha256(data).hexdigest() != classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_SHA256:
            raise AssertionError('Exact reviewed witness-context record changed')
        cls.review = json.loads(data)
        cls.before = cls.root / 'before'
        cls.before.mkdir()
        archive = Path(__file__).parent / 'migration' / cls.review['beforeArchive']['path']
        if hashlib.sha256(archive.read_bytes()).hexdigest() != cls.review['beforeArchive']['sha256']:
            raise AssertionError('Exact reviewed witness-context before archive changed')
        with tarfile.open(archive) as saved:
            for entry in saved:
                if not entry.isfile() or Path(entry.name).is_absolute() or '..' in Path(entry.name).parts:
                    raise AssertionError('Unsafe reviewed archive entry')
                target = cls.before / entry.name
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as output:
                    output.write(saved.extractfile(entry).read())
        cls.after = cls.root / 'after'
        shutil.copytree(cls.before, cls.after)
        # Complete supported-generator bytes, not replacement of selected hash literals.
        (cls.after / 'release-manifest.yaml').write_bytes(cls.review['afterReleaseManifestYaml'].encode('utf-8'))

    def test_exact_pair_changes_only_eight_source_hashes_and_release_identity(self):
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
        old_rows = old['languageDependency']['inputImplementationBaseline']
        new_rows = new['languageDependency']['inputImplementationBaseline']
        self.assertEqual(741, len(old_rows))
        self.assertEqual([row['path'] for row in old_rows], [row['path'] for row in new_rows])
        changes = [dict(path=prior['path'], before=prior['sha256'], after=current['sha256'])
                   for prior, current in zip(old_rows, new_rows) if prior != current]
        self.assertEqual(8, len(changes))
        self.assertEqual(self.review['implementationChanges'], changes)
        restored = deepcopy(new)
        restored['releaseIdentity'] = old['releaseIdentity']
        restored['languageDependency']['inputImplementationBaseline'] = old_rows
        self.assertEqual(old, restored, 'No specification, fixture, registry or other binding changes')
        for label, manifest in [('before', old), ('after', new)]:
            self.assertEqual(self.review[label]['releaseIdentity'], manifest['releaseIdentity'])
            self.assertEqual(manifest['releaseIdentity'], classifier.package_identity(manifest, 'releaseIdentity'))
        self.assertEqual(self.review, classifier.reviewed_rooted_witness_context_transition(before, after))
        report = classifier.classify(self.before, self.after)
        self.assertEqual(0, report['unexpectedCount'])
        self.assertEqual(1, report['changedFileCount'])
        self.assertEqual(0, report['summary'][classifier.SEMANTIC])
        self.assertEqual([classifier.SPEC], report['files'][0]['categories'])
        self.assertEqual(295, report['reviewedBaselineTransition']['executableFixturesUnchanged'])
        self.assertEqual(self.review['id'], report['reviewedBaselineTransition']['id'])
        self.assertEqual(classifier.ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_SHA256,
                         report['reviewedBaselineTransition']['reviewInputSha256'])

    def test_changed_inventory_or_reversed_pair_is_not_reviewed(self):
        before, after = classifier.package_files(self.before), classifier.package_files(self.after)
        self.assertIsNone(classifier.reviewed_rooted_witness_context_transition(after, before))
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
                    self.assertIsNone(classifier.reviewed_rooted_witness_context_transition(b, a))

    def test_rehashed_forged_review_record_is_rejected(self):
        changed = deepcopy(self.review)
        changed['after']['files']['release-manifest.yaml'] = '0' * 64
        changed['after']['inventoryIdentity'] = 'sha256:' + hashlib.sha256(
            json.dumps(changed['after']['files'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        path = self.root / 'forged-review.json'
        path.write_text(json.dumps(changed))
        with patch.object(classifier, 'ROOTED_WITNESS_CONTEXT_REVIEW_INPUT_PATH', path):
            with self.assertRaises(classifier.ClassificationFailure):
                classifier.reviewed_rooted_witness_context_transition(
                    classifier.package_files(self.before), classifier.package_files(self.after))

    def test_unreviewed_fixture_inventory_still_fails_strict_classification(self):
        candidate = self.root / 'extra-fixture'
        shutil.copytree(self.after, candidate)
        (candidate / 'fixtures/closure/unreviewed.yaml').write_text('operation: process-closure\n')
        self.assertGreater(classifier.classify(self.before, candidate)['unexpectedCount'], 0)

    def test_outcome_gas_and_order_mutations_stay_unexpected(self):
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
                candidate = self.root / name
                shutil.copytree(self.after, candidate)
                path = candidate / relative
                value = yaml.safe_load(path.read_text())
                original = deepcopy(value)
                mutate(value)
                self.assertNotEqual(original, value, 'Mutation must change actual fixture semantics')
                path.write_text(yaml.safe_dump(value, sort_keys=False))
                self.assertIsNone(classifier.reviewed_rooted_witness_context_transition(
                    classifier.package_files(self.before), classifier.package_files(candidate)))
                self.assertGreater(classifier.classify(self.before, candidate)['unexpectedCount'], 0)

    def test_rehashed_unreviewed_source_digest_stays_unexpected(self):
        candidate = self.root / 'source-hash'
        shutil.copytree(self.after, candidate)
        path = candidate / 'release-manifest.yaml'
        value = yaml.safe_load(path.read_text())
        value['languageDependency']['inputImplementationBaseline'][0]['sha256'] = '0' * 64
        value['releaseIdentity'] = classifier.package_identity(value, 'releaseIdentity')
        path.write_text(yaml.safe_dump(value, sort_keys=False))
        self.assertIsNone(classifier.reviewed_rooted_witness_context_transition(
            classifier.package_files(self.before), classifier.package_files(candidate)))
        self.assertGreater(classifier.classify(self.before, candidate)['unexpectedCount'], 0)


if __name__ == '__main__':
    unittest.main()
