"""Exact reviewed endpoint selection plus independent fail-closed integrity proofs."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import tarfile
import unittest
from unittest.mock import patch

import yaml
import classify_fixture_identity_delta as classifier

REVIEWED_AFTER_ARCHIVE = Path(__file__).parent / "migration/classify-typed-patch-reviewed-after.tar.gz"
REVIEWED_AFTER_ARCHIVE_SHA256 = "de5623c9941e9640d73d3eda9ba39977bc434bfdd056c2a1a4eda41d983da250"


class ReviewedTypedPatchTransitionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="classifier-reviewed-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name).resolve()
        cls.reviewed_after = cls.root / "reviewed-after"
        if hashlib.sha256(REVIEWED_AFTER_ARCHIVE.read_bytes()).hexdigest() != REVIEWED_AFTER_ARCHIVE_SHA256:
            raise AssertionError("reviewed typed-patch endpoint archive bytes changed")
        cls.reviewed_after.mkdir()
        with tarfile.open(REVIEWED_AFTER_ARCHIVE) as archive:
            for member in archive.getmembers():
                target = cls.reviewed_after / member.name
                if not member.isfile() or not target.resolve().is_relative_to(cls.reviewed_after):
                    raise AssertionError("invalid reviewed endpoint archive member")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile(member).read())
        cls.before = cls.root / "before"
        shutil.copytree(cls.reviewed_after, cls.before)
        cls.review = json.loads(classifier.TYPED_PATCH_REVIEW_INPUT_PATH.read_bytes())
        if {path: classifier.sha256(file) for path, file in classifier.package_files(cls.reviewed_after).items()} != cls.review["after"]["files"]:
            raise AssertionError("reviewed endpoint archive inventory is not the approved exact pair")
        reconstruction = classifier.TYPED_PATCH_REVIEW_INPUT_PATH.with_name(
            cls.review["reconstructionPatch"]["path"])
        if hashlib.sha256(reconstruction.read_bytes()).hexdigest() != cls.review["reconstructionPatch"]["sha256"]:
            raise AssertionError("reviewed reconstruction patch bytes changed")
        subprocess.run(["git", "apply", "--reverse", str(reconstruction)],
                       cwd=cls.before, check=True, capture_output=True)
        cls.before_files = classifier.package_files(cls.before)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="classifier-candidate-")
        self.addCleanup(self.temporary.cleanup)
        self.after = Path(self.temporary.name).resolve() / "after"
        shutil.copytree(self.reviewed_after, self.after)

    def assert_not_reviewed(self):
        self.assertIsNone(classifier.reviewed_typed_patch_transition(
            self.before_files, classifier.package_files(self.after)))

    def test_only_exact_closed_endpoints_are_accepted(self):
        report = classifier.classify(self.before, self.after)
        self.assertEqual(383, report["beforeFileCount"])
        self.assertEqual(383, report["afterFileCount"])
        self.assertEqual(0, report["unexpectedCount"])
        self.assertEqual(7, report["changedFileCount"])
        self.assertEqual(4, report["summary"][classifier.FORMATTING])
        self.assertEqual(3, report["summary"][classifier.SPEC])
        self.assertEqual(self.review["id"], report["reviewedBaselineTransition"]["id"])
        self.assertEqual(295, report["reviewedBaselineTransition"]["executableFixturesUnchanged"])

    def test_added_missing_renamed_and_byte_changed_inventory_are_unreviewed(self):
        target = self.after / "fixtures/evo/c-evo-01.yaml"
        original = target.read_bytes()
        extra = self.after / "fixtures/unreviewed.yaml"
        extra.write_text("operation: process-closure\n")
        self.assert_not_reviewed()
        extra.unlink()
        target.unlink()
        self.assert_not_reviewed()
        target.write_bytes(original)
        renamed = target.with_name("c-evo-unreviewed.yaml")
        target.rename(renamed)
        self.assertEqual(383, len(classifier.package_files(self.after)))
        self.assert_not_reviewed()
        renamed.rename(target)
        target.write_bytes(original + b"\n")
        self.assertEqual(383, len(classifier.package_files(self.after)))
        self.assert_not_reviewed()

    def test_unreviewed_baseline_is_rejected_even_with_approved_candidate(self):
        relative = "fixtures/evo/c-evo-01.yaml"
        changed = self.root / "wrong-before.yaml"
        changed.write_bytes(self.before_files[relative].read_bytes() + b"\n")
        wrong_before = dict(self.before_files, **{relative: changed})
        self.assertEqual(383, len(wrong_before))
        self.assertIsNone(classifier.reviewed_typed_patch_transition(
            wrong_before, classifier.package_files(self.after)))

    def test_semantic_mutations_reject_the_review_and_classification(self):
        relative = "fixtures/closure/c-evo-18-missing-exact-node.yaml"
        target = self.after / relative
        original = yaml.safe_load(target.read_text())
        for field, value in (("status", "tampered"), ("gasUsed", 999999),
                             ("event", {"kind": "Tampered"}),
                             ("checkpoint", {"domain": "tampered"})):
            with self.subTest(mutation=field):
                changed = deepcopy(original)
                changed.setdefault("expected", {})[field] = value
                target.write_text(yaml.safe_dump(changed, sort_keys=False))
                self.assert_not_reviewed()
                row = classifier.classify_changed_file(relative, self.before / relative, target)
                self.assertTrue(row["unexpected"])
        changed = deepcopy(original)
        changed["vectors"] = ["C-EVO-99"]
        target.write_text(yaml.safe_dump(changed, sort_keys=False))
        self.assert_not_reviewed()
        self.assertTrue(classifier.classify_changed_file(
            relative, self.before / relative, target)["unexpected"])

    def test_independent_integrity_still_rejects_stale_manifest(self):
        target = self.after / "fixtures/manifest.yaml"
        value = yaml.safe_load(target.read_text())
        value["files"][0]["sha256"] = "0" * 64
        target.write_text(yaml.safe_dump(value, sort_keys=False))
        self.assert_not_reviewed()
        # Even direct use of the reviewed context cannot skip independent hashes.
        violations = classifier.cevo_release_integrity_violations(
            self.before, self.after, classifier.package_files(self.after), self.review)
        self.assertIn("fixture manifest hashes/counts/identity are stale", violations)

    def test_wrong_implementation_baseline_cannot_use_review(self):
        target = self.after / "release-manifest.yaml"
        value = yaml.safe_load(target.read_text())
        value["languageDependency"]["inputImplementationBaseline"][0]["sha256"] = "0" * 64
        value["releaseIdentity"] = classifier.package_identity(value, "releaseIdentity")
        target.write_text(yaml.safe_dump(value, sort_keys=False))
        self.assert_not_reviewed()
        violations = classifier.cevo_release_integrity_violations(
            self.before, self.after, classifier.package_files(self.after), self.review)
        self.assertIn("release manifest bindings or identity are invalid", violations)

    def test_tampered_review_bytes_fail_closed(self):
        tampered = self.after.parent / "tampered-review.json"
        tampered.write_bytes(classifier.TYPED_PATCH_REVIEW_INPUT_PATH.read_bytes() + b"\n")
        with patch.object(classifier, "TYPED_PATCH_REVIEW_INPUT_PATH", tampered):
            with self.assertRaisesRegex(classifier.ClassificationFailure, "baseline bytes changed"):
                classifier.reviewed_typed_patch_transition(
                    self.before_files, classifier.package_files(self.after))


if __name__ == "__main__":
    unittest.main()
