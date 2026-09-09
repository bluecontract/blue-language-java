"""Independent envelope checks, separate from actual publication authentication."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / "conformance/rooted-processing"
SPEC = importlib.util.spec_from_file_location("checkpoint_constructors", ROOT / "identity/checkpoint_constructors.py")
CONSTRUCTORS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONSTRUCTORS)


class CheckpointIdentityTest(unittest.TestCase):
    def test_exact_vectors_and_closed_type_negatives(self):
        vectors = json.loads((ROOT / "identity/checkpoint-reference-vectors.json").read_text())["vectors"]
        self.assertEqual(2, len(vectors))
        for vector in vectors:
            constructor, value = vector["constructor"], vector["value"]
            self.assertEqual(vector["expected"], CONSTRUCTORS.identity(constructor, value))
            for field in value:
                missing = dict(value)
                del missing[field]
                with self.subTest(constructor=constructor, field=field, mutation="missing"):
                    with self.assertRaises(ValueError):
                        CONSTRUCTORS.identity(constructor, missing)
                wrong_type = dict(value, **{field: True})
                with self.subTest(constructor=constructor, field=field, mutation="boolean"):
                    with self.assertRaises(ValueError):
                        CONSTRUCTORS.identity(constructor, wrong_type)
            with self.assertRaises(ValueError):
                CONSTRUCTORS.identity(constructor, dict(value, verified=True))
            self.assertNotEqual(vector["expected"], CONSTRUCTORS.identity(
                constructor, dict(value, afterBlueId=value["beforeBlueId"])))

    def test_epoch_is_the_existing_contracts_integer_not_an_rcp_decimal_string(self):
        value = json.loads((ROOT / "identity/checkpoint-reference-vectors.json").read_text())["vectors"][1]["value"]
        for invalid in ("0", -1, 0.0, 9007199254740992, None, False):
            with self.subTest(epoch=invalid), self.assertRaises(ValueError):
                CONSTRUCTORS.identity("ROOTED_CHECKPOINT_REPRESENTATION_POSITION", dict(value, epoch=invalid))


if __name__ == "__main__":
    unittest.main()
