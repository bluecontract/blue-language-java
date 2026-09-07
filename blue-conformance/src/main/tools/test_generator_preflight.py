from pathlib import Path
import tempfile
import unittest
import regenerate_package as generator


class GeneratorPreflightTest(unittest.TestCase):
    def test_missing_lifecycle_argument_rejected_before_generation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            closure = root / "fixtures/closure"
            closure.mkdir(parents=True)
            (closure / "fl-adm-01.yaml").write_text("id: fl-adm-01\n")
            with self.assertRaisesRegex(generator.RegenerationFailure, "--fixture-source-root is required"):
                generator.validate_lifecycle_inputs(root, root, None)

    def test_incomplete_directory_and_malformed_yaml_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            canonical = root / "blue-conformance/src/main/fixture-sources/full-lifecycle"
            canonical.mkdir(parents=True)
            for name in ("source-schema.yaml", "fl-adm-01.yaml"):
                (canonical / name).write_text("id: source\n")
            supplied = root / "supplied"
            supplied.mkdir()
            with self.assertRaisesRegex(generator.RegenerationFailure, "missing lifecycle inputs"):
                generator.validate_lifecycle_inputs(root, root, supplied)
            for path in canonical.glob("*.yaml"):
                (supplied / path.name).write_bytes(path.read_bytes())
            generator.validate_lifecycle_inputs(root, root, supplied)
            (supplied / "fl-adm-01.yaml").write_text("[]\n")
            with self.assertRaisesRegex(generator.RegenerationFailure, "YAML mapping"):
                generator.validate_lifecycle_inputs(root, root, supplied)
