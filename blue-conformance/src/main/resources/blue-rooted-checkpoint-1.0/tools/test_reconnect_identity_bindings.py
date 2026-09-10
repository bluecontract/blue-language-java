"""Exact operands extracted from RUN019; these tests confer no runtime PASS."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'conformance/rooted-processing'))
from check_reconnect import reconnect_retry_identity, reconnect_timeline_identity


def require(ok, label):
    if not ok:
        raise ValueError(label)


def digest(domain, value):
    return 'sha256:' + hashlib.sha256(json.dumps({'domain': domain, 'value': value},
        sort_keys=True, separators=(',', ':')).encode()).hexdigest()


class ReconnectIdentityTests(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((Path(__file__).parent / 'test-data/reconnect-identities.json').read_text())

    def check(self, variant, value):
        identity = reconnect_retry_identity(value['terminal'], value['call'], value['ids'], variant,
            value['captures'], require, lambda a,b: a == b, digest)
        require(identity == value['expectedExecutionIdentity'], 'RETRY_EXECUTION_IDENTITY')

    def test_real_saved_original_and_retained_retry_identities(self):
        for variant, value in self.data['variants'].items():
            with self.subTest(variant=variant):
                self.check(variant, value)

    def test_changed_resolution_or_attempt_evidence_rejects(self):
        for variant, original in self.data['variants'].items():
            changes = [
                (('automaticRetryCount',), 0), (('processorAttemptCount',), 1),
                (('managedSurfaceEvidence','resolvedOccurrences'), []),
                (('managedSurfaceEvidence','resolvedOccurrences',0,'demandIdentity'), 'sha256:'+'0'*64),
                (('managedSurfaceEvidence','resolvedOccurrences',0,'kind'), 'NEW_AUTHORED'),
                (('managedSurfaceEvidence','resolvedOccurrences',0,'authoredInitial'), {}),
                (('managedSurfaceEvidence','resolvedOccurrences',0,'bindingIdentity'), 'sha256:'+'0'*64),
                (('managedSurfaceEvidence','resolvedOccurrences',0,'occurrenceIdentity'), 'sha256:'+'0'*64),
                (('managedSurfaceEvidence','resolvedOccurrences',0,'occurrence','targetDocumentId'), {'value':'other'}),
            ]
            for path, replacement in changes:
                with self.subTest(variant=variant, path=path):
                    value = copy.deepcopy(original)
                    at = value['call']['entries'][0]['closures'][0]
                    for key in path[:-1]:
                        at = at[key]
                    at[path[-1]] = replacement
                    with self.assertRaises(ValueError):
                        self.check(variant, value)

    def test_wrong_retired_or_result_cursor_rejects(self):
        for variant, original in self.data['variants'].items():
            for location in ('before', 'after'):
                for field, replacement in [('active', True), ('activationGeneration', 99),
                                           ('pendingHistoricalEpoch', 999), ('targetDocumentId', {'value':'other'})]:
                    with self.subTest(variant=variant, location=location, field=field):
                        value = copy.deepcopy(original)
                        terminal = value['terminal']
                        rows = terminal['input']['snapshot']['occurrences'] if location == 'before' else terminal['occurrenceBindings']
                        next(row for row in rows if row['sourcePath'] == '/peers/b')[field] = replacement
                        with self.assertRaises(ValueError):
                            self.check(variant, value)

    def test_changed_base_or_result_identity_rejects(self):
        for variant, original in self.data['variants'].items():
            for target in ('base', 'result'):
                value = copy.deepcopy(original)
                if target == 'base':
                    value['terminal']['input']['invocationIdentity'] = 'sha256:'+'0'*64
                else:
                    value['expectedExecutionIdentity'] = value['terminal']['input']['invocationIdentity']
                with self.subTest(variant=variant, target=target), self.assertRaises(ValueError):
                    self.check(variant, value)

    def test_exact_timeline_blueids(self):
        for item in self.data['timelines']:
            self.assertEqual(item['identity'], reconnect_timeline_identity(item['value'], item['name'], require))
            self.assertNotEqual(item['name'], item['identity'])

    def test_altered_timeline_values_reject(self):
        for item in self.data['timelines']:
            for key, replacement in [('type', {'blueId':'other'}), ('timelineId', {'value':item['name']}), ('extra', {})]:
                value = copy.deepcopy(item['value']); value[key] = replacement
                with self.subTest(key=key), self.assertRaises(ValueError):
                    reconnect_timeline_identity(value, item['name'], require)
            with self.assertRaises(ValueError):
                reconnect_timeline_identity(item['value'], 'other', require)


if __name__ == '__main__':
    unittest.main()
