"""The legal detached-retarget amendment approves one exact generated pair."""
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


class LegalDetachedRetargetReleaseTransitionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='legal-detached-retarget-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.before = cls.root / 'before'
        cls.before.mkdir()
        cls.review = json.loads(classifier.ROOTED_LEGAL_DETACHED_RETARGET_REVIEW_INPUT_PATH.read_bytes())
        archive = Path(__file__).parent / 'migration' / cls.review['beforeArchive']['path']
        if hashlib.sha256(archive.read_bytes()).hexdigest() != cls.review['beforeArchive']['sha256']:
            raise AssertionError('Reviewed source archive changed')
        with tarfile.open(archive) as saved:
            for entry in saved:
                if not entry.isfile() or Path(entry.name).is_absolute() or '..' in Path(entry.name).parts:
                    raise AssertionError('Unsafe reviewed archive entry')
                target = cls.before / entry.name
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as output:
                    output.write(saved.extractfile(entry).read())
        manifest = cls.before / 'release-manifest.yaml'
        content = manifest.read_bytes()
        for replacement in cls.review['beforeManifestByteReplacements']:
            old, new = replacement['before'].encode(), replacement['after'].encode()
            if content.count(old) != 1:
                raise AssertionError('Reviewed before-manifest replacement is not unique')
            content = content.replace(old, new)
        manifest.write_bytes(content)
        # Preserve the reviewed 3491 package, not a later checkout's active manifest.
        cls.after = cls.root / 'after'
        cls.after.mkdir()
        archive = Path(__file__).parent / 'migration/classify-legal-detached-retarget-reviewed-after.tar.gz'
        if hashlib.sha256(archive.read_bytes()).hexdigest() != '3042d886644118f82e4014fe73ff245c62de3ca0140e5ac36328fe4d84758342':
            raise AssertionError('Exact reviewed legal detached-retarget after archive changed')
        with tarfile.open(archive) as saved:
            for entry in saved:
                if not entry.isfile() or Path(entry.name).is_absolute() or '..' in Path(entry.name).parts:
                    raise AssertionError('Unsafe reviewed after archive entry')
                target = cls.after / entry.name
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as output:
                    output.write(saved.extractfile(entry).read())

    def test_exact_pair_has_only_four_prose_leaves_and_fixture_identity_rebindings(self):
        before, after = classifier.package_files(self.before), classifier.package_files(self.after)
        for label, files in [('before', before), ('after', after)]:
            actual = {name: classifier.sha256(path) for name, path in files.items()}
            self.assertEqual(383, len(actual))
            self.assertEqual(self.review[label]['files'], actual)
        self.assertEqual(self.review, classifier.reviewed_rooted_legal_detached_retarget_transition(before, after))
        report = classifier.classify(self.before, self.after)
        self.assertEqual(86, report['changedFileCount'])
        self.assertEqual(0, report['unexpectedCount'])
        self.assertEqual(self.review['id'], report['reviewedBaselineTransition']['id'])
        self.assertEqual(2, report['summary'][classifier.SEMANTIC])
        prose = [dict(file=row['path'], **{k:v for k,v in delta.items() if k != 'category'})
                 for row in report['files'] if row['path'] in {'fixtures/closure-fixture-schema.yaml', 'identity-constructors.yaml'}
                 for delta in row['differences']]
        self.assertEqual(self.review['normativeProseChanges'], prose)
        for row in report['files']:
            if row['path'].startswith('fixtures/closure/'):
                self.assertNotIn(classifier.SEMANTIC, row['categories'])
                for delta in row['differences']:
                    for side in ['before', 'after']:
                        self.assertRegex(delta[side], r'^sha256:[0-9a-f]{64}$')

    def test_any_added_missing_renamed_or_changed_inventory_is_not_reviewed(self):
        before, after = classifier.package_files(self.before), classifier.package_files(self.after)
        for side in ['before', 'after']:
            for mutation in ['added', 'missing', 'renamed', 'changed']:
                b, a = dict(before), dict(after)
                files = b if side == 'before' else a
                name = next(iter(files))
                if mutation == 'added': files['unreviewed.yaml'] = files[name]
                elif mutation == 'missing': del files[name]
                elif mutation == 'renamed': files['unreviewed.yaml'] = files.pop(name)
                else: files[name] = self.after / 'release-manifest.yaml'
                with self.subTest(side=side, mutation=mutation):
                    self.assertIsNone(classifier.reviewed_rooted_legal_detached_retarget_transition(b, a))

    def test_forged_review_record_cannot_be_rehashed_by_caller(self):
        changed = deepcopy(self.review)
        changed['after']['files']['release-manifest.yaml'] = '0' * 64
        path = self.root / 'forged-review.json'
        path.write_text(json.dumps(changed))
        with patch.object(classifier, 'ROOTED_LEGAL_DETACHED_RETARGET_REVIEW_INPUT_PATH', path):
            with self.assertRaises(classifier.ClassificationFailure):
                classifier.reviewed_rooted_legal_detached_retarget_transition(
                    classifier.package_files(self.before), classifier.package_files(self.after))

    def test_outcome_gas_prose_and_rehashed_source_mutations_remain_unexpected(self):
        def change_source(value):
            value['languageDependency']['inputImplementationBaseline'][0]['sha256'] = '0' * 64
            value['releaseIdentity'] = classifier.package_identity(value, 'releaseIdentity')
        mutations = [
            ('outcome', 'fixtures/closure/c-evo-18-missing-exact-node.yaml', lambda v: v['expected'].__setitem__('attemptOutcome', 'Complete')),
            ('gas', 'fixtures/closure/c-clo-01-static-cycle-admission.yaml', lambda v: v['expected'].__setitem__('totalGas', v['expected']['totalGas'] + 1)),
            ('prose', 'fixtures/closure-fixture-schema.yaml', lambda v: v['$defs']['occurrence']['properties']['activationGeneration'].__setitem__('description', 'Unreviewed policy')),
            ('source', 'release-manifest.yaml', change_source),
        ]
        for name, relative, mutate in mutations:
            with self.subTest(mutation=name):
                candidate = self.root / name
                shutil.copytree(self.after, candidate)
                path = candidate / relative
                value = yaml.safe_load(path.read_text())
                mutate(value)
                path.write_text(yaml.safe_dump(value, sort_keys=False))
                self.assertIsNone(classifier.reviewed_rooted_legal_detached_retarget_transition(
                    classifier.package_files(self.before), classifier.package_files(candidate)))
                self.assertGreater(classifier.classify(self.before, candidate)['unexpectedCount'], 0)


if __name__ == '__main__':
    unittest.main()
