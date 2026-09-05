"""Source-only allowances are restricted to specification bindings."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

import regenerate_aggregate_release_manifest as aggregate
from test_regenerate_aggregate_release_manifest import RepositoryFixture, _write, _write_manifest, _bind_identity
import verify_source_development as source


class SourceDevelopmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        RepositoryFixture(self.root)
        for primary, mirror, _ in source.SPECIFICATIONS:
            _write(self.root / primary, "# Specification\n\n```yaml\nvalue: 1\n```\n")
            _write(self.root / mirror, (self.root / primary).read_text())
        _write_manifest(self.root / aggregate.MANIFEST_PATH, aggregate.build_manifest(self.root))

    def test_matching_and_pending_specifications(self):
        self.assertEqual([], source.verify(self.root))
        for primary, mirror, _ in source.SPECIFICATIONS:
            for relative in (primary, mirror):
                _write(self.root / relative, "# Specification\n\nNew normative rule.\n")
        self.assertEqual(2, len(source.verify(self.root)))
        with self.assertRaises(ValueError):
            aggregate.validate_aggregate_manifest((self.root / aggregate.MANIFEST_PATH).read_bytes(), aggregate.expected_manifest(self.root))

    def test_mirror_and_markdown_syntax_fail_closed(self):
        primary, mirror, _ = source.SPECIFICATIONS[0]
        _write(self.root / primary, "# Changed\n")
        with self.assertRaisesRegex(ValueError, "mirror"):
            source.verify(self.root)
        for relative in (primary, mirror):
            _write(self.root / relative, "# Changed\n```yaml\n")
        with self.assertRaisesRegex(ValueError, "Unclosed"):
            source.verify(self.root)

    def test_unrelated_drift_and_corrupt_identity_fail(self):
        path = self.root / aggregate.MANIFEST_PATH
        manifest = aggregate._yaml(path)
        manifest["components"]["languageVectorCount"] += 1
        _write_manifest(path, manifest)
        with self.assertRaisesRegex(ValueError, "packageIdentity is stale"):
            source.verify(self.root)
        _write_manifest(path, _bind_identity(manifest, ("packageIdentity",)))
        with self.assertRaisesRegex(ValueError, "beyond specification"):
            source.verify(self.root)

    def test_nested_source_integrity_is_not_optional(self):
        _write(self.root / aggregate.LANGUAGE_FIXTURE_SOURCE.source_root / "rogue.yaml", "extra: true\n")
        with self.assertRaisesRegex(ValueError, "unmanifested"):
            source.verify(self.root)


if __name__ == "__main__":
    unittest.main()
