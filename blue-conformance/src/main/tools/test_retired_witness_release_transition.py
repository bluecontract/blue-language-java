"""Exact retired-witness release binding and unchanged fail-closed behavior."""
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


class RetiredWitnessReleaseTransitionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="retired-witness-classifier-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.after = cls.root / "after"
        cls.after.mkdir()
        archive = Path(__file__).parent / "migration/classify-retired-witness-reviewed-after.tar.gz"
        if hashlib.sha256(archive.read_bytes()).hexdigest() != "1d8e2748587f716ce3b9a8468a9a3739c83c51db8cb6ca8f72194f082a19a064":
            raise AssertionError("Exact reviewed retired-witness fixture archive changed")
        with tarfile.open(archive) as saved:
            for entry in saved:
                if not entry.isfile() or Path(entry.name).is_absolute() or '..' in Path(entry.name).parts:
                    raise AssertionError("Unexpected reviewed archive entry")
                target = cls.after / entry.name
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as output: output.write(saved.extractfile(entry).read())
        cls.before = cls.root / "before"
        shutil.copytree(cls.after, cls.before)
        cls.review = json.loads(classifier.ROOTED_RETIRED_WITNESS_REVIEW_INPUT_PATH.read_bytes())
        (cls.before / "release-manifest.yaml").write_text(cls.review['beforeReleaseManifestYaml'])

    def test_exact_complete_pair_and_only_one_source_binding_change(self):
        before, after = classifier.package_files(self.before), classifier.package_files(self.after)
        for label, files in [('before', before), ('after', after)]:
            actual = {name: classifier.sha256(path) for name, path in files.items()}
            self.assertEqual(383, len(actual))
            self.assertEqual(self.review[label]['files'], actual)
            self.assertEqual(self.review[label]['inventoryIdentity'], 'sha256:' + hashlib.sha256(
                json.dumps(actual, sort_keys=True, separators=(',', ':')).encode()).hexdigest())
        self.assertEqual(['release-manifest.yaml'], [name for name in sorted(before) if before[name].read_bytes() != after[name].read_bytes()])
        prior = yaml.safe_load(before['release-manifest.yaml'].read_text())
        successor = yaml.safe_load(after['release-manifest.yaml'].read_text())
        prior_sources = {row['path']: row for row in prior['languageDependency']['inputImplementationBaseline']}
        next_sources = {row['path']: row for row in successor['languageDependency']['inputImplementationBaseline']}
        self.assertEqual(741, len(prior_sources))
        self.assertEqual(set(prior_sources), set(next_sources))
        changed_source = 'blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java'
        self.assertEqual([changed_source], [path for path in sorted(prior_sources) if prior_sources[path] != next_sources[path]])
        reconstructed = deepcopy(successor)
        reconstructed['releaseIdentity'] = prior['releaseIdentity']
        reconstructed['languageDependency']['inputImplementationBaseline'] = prior['languageDependency']['inputImplementationBaseline']
        self.assertEqual(prior, reconstructed, 'No unrelated release binding may change')
        self.assertEqual(self.review, classifier.reviewed_rooted_retired_witness_transition(before, after))
        report = classifier.classify(self.before, self.after)
        self.assertEqual(0, report['unexpectedCount'])
        self.assertEqual(1, report['changedFileCount'])
        self.assertEqual(self.review['id'], report['reviewedBaselineTransition']['id'])

    def test_different_paths_or_content_cannot_match_the_reviewed_pair(self):
        before, after = classifier.package_files(self.before), classifier.package_files(self.after)
        for side in ['before', 'after']:
            for mutation in ['added', 'missing', 'renamed', 'changed']:
                b, a = dict(before), dict(after)
                files = b if side == 'before' else a
                path = next(iter(files))
                if mutation == 'added': files['unreviewed.yaml'] = files[path]
                elif mutation == 'missing': del files[path]
                elif mutation == 'renamed': files['unreviewed.yaml'] = files.pop(path)
                else: files[path] = self.after / 'release-manifest.yaml'
                with self.subTest(side=side, mutation=mutation):
                    self.assertIsNone(classifier.reviewed_rooted_retired_witness_transition(b, a))

    def test_review_record_cannot_be_rehashed_by_a_caller(self):
        changed = deepcopy(self.review)
        changed['after']['files']['release-manifest.yaml'] = '0' * 64
        path = self.root / 'forged-review.json'
        path.write_text(json.dumps(changed))
        with patch.object(classifier, 'ROOTED_RETIRED_WITNESS_REVIEW_INPUT_PATH', path):
            with self.assertRaises(classifier.ClassificationFailure):
                classifier.reviewed_rooted_retired_witness_transition(
                    classifier.package_files(self.before), classifier.package_files(self.after))

    def test_unreviewed_inventory_still_fails_strict_classification(self):
        candidate = self.root / 'extra-file'
        shutil.copytree(self.after, candidate)
        (candidate / 'unreviewed.yaml').write_text('accepted: true\n')
        self.assertGreater(classifier.classify(self.before, candidate)['unexpectedCount'], 0)

    def test_changed_resource_outcome_still_fails_strict_classification(self):
        candidate = self.root / 'semantic-mutation'
        shutil.copytree(self.after, candidate)
        path = candidate / 'fixtures/closure/c-evo-18-missing-exact-node.yaml'
        fixture = yaml.safe_load(path.read_text())
        fixture['expected']['attemptOutcome'] = 'Complete'
        path.write_text(yaml.safe_dump(fixture, sort_keys=False))
        self.assertGreater(classifier.classify(self.before, candidate)['unexpectedCount'], 0)
