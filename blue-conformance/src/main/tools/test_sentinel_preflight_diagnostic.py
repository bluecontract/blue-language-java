import json
from pathlib import Path
import tempfile
import unittest
import generate_empty_sentinel_audit as audit


class SentinelDiagnosticTest(unittest.TestCase):
    def test_stale_location_is_reported_without_rewriting(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "audit.json"
            entry = {"path": "module/src/main/java/Owner.java", "line": 12, "source": "new Node()"}
            original = json.dumps({"entries": [entry]}).encode()
            path.write_bytes(original)
            entry["line"] = 15
            with self.assertRaisesRegex(SystemExit, "Owner.java:15.*previous line 12"):
                audit._write_or_check(path, json.dumps({"entries": [entry]}).encode(), True)
            self.assertEqual(original, path.read_bytes())
