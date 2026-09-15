"""The release report must reject missing, stale, and falsely passing evidence."""
from copy import deepcopy
import hashlib
from pathlib import Path
import unittest
from unittest.mock import patch

from generate_contextual_reference_report import (
    DEPENDENCY_PACKAGE, MODES, validate_execution, verified_dependencies,
)


class ContextualReferenceReportTest(unittest.TestCase):
    def setUp(self):
        self.corpus = {"provider": [], "cases": [{
            "name": "invalid", "expectedDirectBlueId": "exact-id",
            "expectedErrorCategory": "TypeCompatibilityViolation",
            "expectedResolutionErrorCategory": "TypeCompatibilityViolation",
        }]}
        self.report = {
            "provider": [], "corpusSha256": "corpus", "harnessSha256": "harness",
            "rows": [{"case": "invalid", "mode": mode, "directBlueId": "exact-id",
                      "sourceError": "TypeCompatibilityViolation",
                      "resolutionError": "TypeCompatibilityViolation"} for mode in MODES],
        }

    def validate(self, report):
        validate_execution(report, self.corpus, "corpus", "harness", final=True)

    def test_complete_bound_negative_execution_passes(self):
        self.validate(self.report)

    def test_missing_or_duplicate_rows_reject(self):
        for rows in ([], self.report["rows"][:-1], self.report["rows"] * 2):
            report = deepcopy(self.report)
            report["rows"] = rows
            with self.assertRaises(ValueError):
                self.validate(report)

    def test_stale_or_unbound_harness_rejects(self):
        for field in ("corpusSha256", "harnessSha256"):
            report = deepcopy(self.report)
            report[field] = "stale"
            with self.assertRaises(ValueError):
                self.validate(report)

    def test_source_rejection_does_not_hide_invalid_resolution_success(self):
        report = deepcopy(self.report)
        report["rows"][0].pop("resolutionError")
        report["rows"][0]["resolved"] = {"value": "invalid"}
        with self.assertRaises(ValueError):
            self.validate(report)


class DependencyReviewTest(unittest.TestCase):
    def setUp(self):
        self.before, self.after = "b" * 40, "a" * 40
        self.file = DEPENDENCY_PACKAGE + "/fixture.yaml"
        self.inventory = {"baselineCommit": self.before, "noCompatibilityAliases": True,
                          "summary": dict.fromkeys(("activeStoredReferenceCount",
                                                    "unresolvedArtifactCount", "mirrorMismatchCount"), 0)}
        self.review = {"beforeRevision": self.before, "unexplainedNonIdentityChanges": 0,
                       "beforeFileCount": 1, "afterFileCount": 1, "changedFileCount": 1,
                       "files": [{"path": "fixture.yaml", "classification": "required-dependency-consequence",
                                  "reason": "reviewed canonical checkpoint",
                                  "beforeSha256": hashlib.sha256(b"before").hexdigest(),
                                  "afterSha256": hashlib.sha256(b"after").hexdigest()}]}

    def git(self, command):
        action = command[3:]
        if action == ["rev-parse", "HEAD"]:
            return self.after.encode()
        if action == ["diff", "HEAD", "--name-only"]:
            return b""
        if action[0] in ("ls-tree", "diff"):
            return self.file.encode()
        if action[0] == "show":
            return b"before" if action[1].startswith(self.before) else b"after"
        raise AssertionError(action)

    def validate(self):
        with patch("generate_contextual_reference_report.subprocess.check_output", side_effect=self.git):
            return verified_dependencies(Path("/repository"), self.after, self.inventory, self.review)

    def test_exact_reviewed_dependency_bytes_pass(self):
        self.assertEqual(0, self.validate()["unexplainedChanges"])

    def test_changed_bytes_cannot_reuse_an_earlier_review(self):
        self.review["files"][0]["afterSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            self.validate()

    def test_omitting_a_changed_file_rejects(self):
        self.review["files"] = []
        with self.assertRaisesRegex(ValueError, "complete reviewed inventory"):
            self.validate()

    def test_unresolved_old_binding_rejects(self):
        self.inventory["summary"]["activeStoredReferenceCount"] = 1
        with self.assertRaisesRegex(ValueError, "stale dependency"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
