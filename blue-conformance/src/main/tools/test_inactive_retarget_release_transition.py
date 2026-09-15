"""The inactive-retarget release binding accepts only its exact reviewed pair."""
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


class InactiveRetargetReleaseTransitionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="inactive-retarget-classifier-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.before = cls.root / "before"
        cls.before.mkdir()
        cls.review = json.loads(classifier.ROOTED_INACTIVE_RETARGET_REVIEW_INPUT_PATH.read_bytes())
        # Reuse immutable prior release bytes, not whatever a later checkout calls current.
        archive = Path(__file__).parent / "migration" / cls.review["beforeArchive"]["path"]
        if hashlib.sha256(archive.read_bytes()).hexdigest() != cls.review["beforeArchive"]["sha256"]:
            raise AssertionError("Exact reviewed baseline archive changed")
        with tarfile.open(archive) as saved:
            for entry in saved:
                if not entry.isfile() or Path(entry.name).is_absolute() or '..' in Path(entry.name).parts:
                    raise AssertionError("Unexpected reviewed archive entry")
                target = cls.before / entry.name
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as output:
                    output.write(saved.extractfile(entry).read())
        cls.after = cls.root / "after"
        shutil.copytree(cls.before, cls.after)
        manifest = cls.after / "release-manifest.yaml"
        content = manifest.read_bytes()
        for replacement in cls.review["manifestByteReplacements"]:
            old, new = replacement["before"].encode(), replacement["after"].encode()
            if content.count(old) != 1:
                raise AssertionError("Reviewed manifest replacement is not unique")
            content = content.replace(old, new)
        manifest.write_bytes(content)

    def test_exact_pair_changes_only_three_source_hashes_and_release_identity(self):
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
        expected_paths = ['blue-contracts-core/src/main/java/blue/language/processor/closure/' + name
                          for name in ['ClosureExecutionSession.java', 'ClosureInvocationVerifier.java',
                                       'ManagedOccurrenceEvidenceResolution.java']]
        old_rows = {row['path']: row for row in old['languageDependency']['inputImplementationBaseline']}
        new_rows = {row['path']: row for row in new['languageDependency']['inputImplementationBaseline']}
        self.assertEqual(741, len(old_rows))
        self.assertEqual(set(old_rows), set(new_rows))
        self.assertEqual(expected_paths, [path for path in sorted(old_rows) if old_rows[path] != new_rows[path]])
        restored = deepcopy(new)
        restored['releaseIdentity'] = old['releaseIdentity']
        restored['languageDependency']['inputImplementationBaseline'] = old['languageDependency']['inputImplementationBaseline']
        self.assertEqual(old, restored)
        for manifest in [old, new]:
            self.assertEqual(manifest['releaseIdentity'], classifier.package_identity(manifest, 'releaseIdentity'))
        self.assertEqual(self.review, classifier.reviewed_rooted_inactive_retarget_transition(before, after))
        report = classifier.classify(self.before, self.after)
        self.assertEqual(0, report['unexpectedCount'])
        self.assertEqual(1, report['changedFileCount'])
        self.assertEqual(0, report['summary'][classifier.SEMANTIC])
        self.assertEqual(self.review['id'], report['reviewedBaselineTransition']['id'])
        self.assertEqual(classifier.ROOTED_INACTIVE_RETARGET_REVIEW_INPUT_SHA256,
                         report['reviewedBaselineTransition']['reviewInputSha256'])

    def test_any_added_missing_renamed_or_changed_inventory_is_not_the_reviewed_pair(self):
        before, after = classifier.package_files(self.before), classifier.package_files(self.after)
        for side in ['before', 'after']:
            for mutation in ['added', 'missing', 'renamed', 'changed']:
                b, a = dict(before), dict(after)
                files = b if side == 'before' else a
                name = next(iter(files))
                if mutation == 'added':
                    files['unreviewed.yaml'] = files[name]
                elif mutation == 'missing':
                    del files[name]
                elif mutation == 'renamed':
                    files['unreviewed.yaml'] = files.pop(name)
                else:
                    files[name] = self.after / 'release-manifest.yaml'
                with self.subTest(side=side, mutation=mutation):
                    self.assertIsNone(classifier.reviewed_rooted_inactive_retarget_transition(b, a))

    def test_forged_review_record_cannot_be_rehashed_by_caller(self):
        changed = deepcopy(self.review)
        changed['after']['files']['release-manifest.yaml'] = '0' * 64
        path = self.root / 'forged-review.json'
        path.write_text(json.dumps(changed))
        with patch.object(classifier, 'ROOTED_INACTIVE_RETARGET_REVIEW_INPUT_PATH', path):
            with self.assertRaises(classifier.ClassificationFailure):
                classifier.reviewed_rooted_inactive_retarget_transition(
                    classifier.package_files(self.before), classifier.package_files(self.after))

    def assert_mutation_rejected(self, name, relative, mutate):
        candidate = self.root / name
        shutil.copytree(self.after, candidate)
        path = candidate / relative
        value = yaml.safe_load(path.read_text())
        mutate(value)
        path.write_text(yaml.safe_dump(value, sort_keys=False))
        self.assertIsNone(classifier.reviewed_rooted_inactive_retarget_transition(
            classifier.package_files(self.before), classifier.package_files(candidate)))
        self.assertGreater(classifier.classify(self.before, candidate)['unexpectedCount'], 0)

    def test_changed_resource_outcome_remains_unexpected(self):
        self.assert_mutation_rejected('outcome', 'fixtures/closure/c-evo-18-missing-exact-node.yaml',
                                      lambda value: value['expected'].__setitem__('attemptOutcome', 'Complete'))

    def test_changed_gas_remains_unexpected(self):
        def increase(value):
            value['expected']['totalGas'] += 1
        self.assert_mutation_rejected('gas', 'fixtures/closure/c-clo-01-static-cycle-admission.yaml', increase)

    def test_changed_source_hash_even_with_recomputed_release_identity_is_unexpected(self):
        def rehash(value):
            value['languageDependency']['inputImplementationBaseline'][0]['sha256'] = '0' * 64
            value['releaseIdentity'] = classifier.package_identity(value, 'releaseIdentity')
        self.assert_mutation_rejected('source', 'release-manifest.yaml', rehash)


if __name__ == '__main__':
    unittest.main()
