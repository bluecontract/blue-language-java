"""Mutation tests on extracted real command evidence, not a full runtime pass."""
import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'conformance/rooted-processing'))
from resource_followup_bindings import check_followup, timeline_identity
from check_resource_continuation import check_host_transcript, check_waiting_documents, check_public_resource_chain


def require(ok, label):
    if not ok:
        raise ValueError(label)


class FollowupTests(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads((Path(__file__).parent / 'test-data/resource-followup.json').read_text())

    def check(self):
        f = self.fixture
        return check_followup(f['live'], f['followup'], f['command'], f['root'], f['child'],
                              f['cutoff'], f['finalRecords'], require)

    def test_real_retained_command_chain(self):
        self.assertEqual(2, len(self.check()))

    def test_exact_timeline_identity(self):
        self.assertEqual(self.fixture['cutoff'][1], timeline_identity(self.fixture['timeline'], require))
        for key, value in [('timelineId', {'value': 'other'}), ('type', {'blueId': self.fixture['child']})]:
            changed = copy.deepcopy(self.fixture['timeline'])
            changed[key] = value
            with self.assertRaises(ValueError):
                timeline_identity(changed, require)

    def test_host_transcript_preserves_every_response_shape(self):
        host = [{'literal': {'stepId': str(i)}, 'actual': value}
                for i, value in enumerate(({}, [], [{'commandId': 'source'}], None, True))]
        transcript = [{'request': r['literal'], 'response': {'actual': r['actual']}, 'completed': True}
                      for r in host]
        check_host_transcript(transcript, host, require)
        for index in range(len(transcript)):
            with self.subTest(index=index):
                changed = copy.deepcopy(transcript)
                changed[index]['response'] = {'actual': 'substituted'}
                with self.assertRaisesRegex(ValueError, 'RESOURCE_ACTUAL_RESPONSE_TRANSCRIPT'):
                    check_host_transcript(changed, host, require)
        with self.assertRaisesRegex(ValueError, 'RESOURCE_LITERAL_TRANSCRIPT'):
            check_host_transcript(transcript[:-1], host, require)

    def test_actual_progress_restore_keeps_every_semantic_document_field(self):
        f=json.loads((Path(__file__).parent/'test-data/resource-public-operands.json').read_text())
        def check():check_waiting_documents(f['beforeDocuments'],f['waitingDocuments'],f['commands'],require)
        check()
        original=copy.deepcopy(f['waitingDocuments'])
        for key in original[0]:
            with self.subTest(field=key):
                f['waitingDocuments']=copy.deepcopy(original)
                f['waitingDocuments'][0][key]='2099-01-01T00:00:00Z' if key=='updatedAt' else 'unreviewed mutation'
                with self.assertRaises((ValueError,TypeError)):check()

    def test_actual_public_reference_chain_and_mutations(self):
        f=json.loads((Path(__file__).parent/'test-data/resource-public-operands.json').read_text())
        check_public_resource_chain(f['applied'],f['ids'],require)
        for alias in f['ids']:
            for field,value in [('ready',False),('currentBlueId','forged'),('committedEpoch',99),('documentId','foreign')]:
                with self.subTest(alias=alias,field=field):
                    changed=copy.deepcopy(f['applied']);changed['http'][f['ids'][alias]]['body'][field]=value
                    with self.assertRaises(ValueError):check_public_resource_chain(changed,f['ids'],require)
        for alias,path in [('host',['children','one']),('child',['peer']),('missing',['state'])]:
            with self.subTest(alias=alias):
                changed=copy.deepcopy(f['applied']);node=changed['http'][f['ids'][alias]]['body']['projectedCurrent']
                for key in path[:-1]:node=node[key]
                node[path[-1]]={'blueId':'unproved replacement'}
                with self.assertRaises(ValueError):check_public_resource_chain(changed,f['ids'],require)

    def test_unproved_command_or_state_mutations_reject(self):
        occurrences = self.fixture['live']['actualDrain']['terminals'][0]['outputSnapshot']['occurrences']
        pending = next(i for i, row in enumerate(occurrences) if row['sourcePath'] == '/children/one')
        mutations = [
            ('command/attemptCount', 2), ('command/idempotencyKey', 'forged'),
            ('command/status', 'RUNNING'), ('command/requestJson', '{}'),
            ('live/actualDrain/quiescent', True), ('live/actualDrain/paused', False),
            ('followup/actualDrain/quiescent', False), ('followup/actualDrain/paused', True),
            ('followup/actualEntries', [{}]), ('followup/sourceAdmissions', [{}]),
            ('followup/actualDrain/managedAttempts/0/replayed', True),
            ('followup/actualDrain/managedAttempts/0/published', False),
            ('followup/actualDrain/managedAttempts/0/work/sourceEpoch', 1),
            ('followup/actualDrain/managedAttempts/0/work/targetPath', '/different'),
            ('followup/actualDrain/managedAttempts/0/work/workIdentity', 'sha256:' + '0' * 64),
            ('followup/actualDrain/ownedRetainedApplications', []),
            ('followup/actualDrain/managedAttempts/0/receipt/resultingSourceCursor', 2),
            ('followup/actualDrain/managedAttempts/0/receipt/sourceReceiptIdentity', 'sha256:' + '0' * 64),
            ('followup/actualDrain/before/' + self.fixture['root'] + '/epoch', 99),
            ('finalRecords/' + self.fixture['child'] + '/blueId', self.fixture['root']),
            (f'live/actualDrain/terminals/0/outputSnapshot/occurrences/{pending}/active', True),
        ]
        original = copy.deepcopy(self.fixture)
        for path, value in mutations:
            with self.subTest(path=path):
                self.fixture = copy.deepcopy(original)
                parts = path.split('/')
                target = self.fixture
                for part in parts[:-1]:
                    target = target[int(part)] if isinstance(target, list) else target[part]
                target[parts[-1]] = value
                with self.assertRaises(ValueError):
                    self.check()


if __name__ == '__main__':
    unittest.main(verbosity=2)
