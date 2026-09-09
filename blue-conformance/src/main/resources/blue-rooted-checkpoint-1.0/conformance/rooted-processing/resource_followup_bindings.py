"""RUN027's one LIVE input and separately persisted owned-retained continuation."""
import hashlib
import json
from resource_admission_bindings import digest


def timeline_identity(timeline, require):
    """The closed literal Timeline value, using Language's direct object formula."""
    require(timeline == {
        'type': {'blueId': '5VAQp5thYLkzp3FbvYGmVvmdLqqu6pV5vhNgD14XJwpX'},
        'timelineId': {'type': {'blueId': 'GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC'},
                       'value': 'labs/dynamic-resource/alice'}}, 'RESOURCE_EXACT_TIMELINE_VALUE')

    def direct(value):
        if set(value) == {'blueId'}:
            return value['blueId']
        fields = {k: v if k == 'value' else {'blueId': direct(v)} for k, v in value.items()}
        raw = hashlib.sha256(json.dumps(fields, sort_keys=True, separators=(',', ':')).encode()).digest()
        number = int.from_bytes(raw, 'big')
        result = ''
        while number:
            number, index = divmod(number, 58)
            result = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'[index] + result
        return '1' * (len(raw) - len(raw.lstrip(b'\0'))) + result

    return direct(timeline)


def check_followup(live, followup, command, root, child, cutoff, final_records, require):
    """Authenticate the exact persisted next work and its complete state chain.

    The caller additionally validates every terminal's input/output, gas trace,
    rooted companion and immutable source transition, plus all restart snapshots.
    """
    from identity import managed_successor_constructors as M
    first = live['actualDrain']
    last = followup['actualDrain']
    require(first['quiescent'] is False and first['paused'] is True
            and first['resourceFailures'] == [] and first['ownedRetainedApplications'] == []
            and first['localRetainedApplications'] == [] and first['managedAttempts'] == []
            and len(first['terminals']) == 1, 'RESOURCE_LIVE_ONE_INPUT_BOUNDARY')
    require(last['quiescent'] is True and last['paused'] is False and last['resourceFailures'] == []
            and last['entries'] == [] and last['localRetainedApplications'] == []
            and len(last['ownedRetainedApplications']) == len(last['managedAttempts'])
            == len(last['terminals']) == 1, 'RESOURCE_RETAINED_ONE_INPUT_BOUNDARY')
    require(followup['actualEntries'] == [] and followup['actualEntryResults'] == []
            and followup['actualClassification'] is None and followup['sourceAdmissions'] == [],
            'RESOURCE_RETAINED_UNREQUESTED_BUSINESS_INPUT')
    require(first['before'] == live['beforeRecords'] and first['after'] == live['afterRecords']
            == followup['beforeRecords'] == last['before']
            and last['after'] == followup['afterRecords'] == final_records,
            'RESOURCE_SEPARATE_COMMAND_STATE_CHAIN')
    live_terminal = first['terminals'][0]
    terminal = last['terminals'][0]
    require(live_terminal['causeType'] == 'ExternalEventCause'
            and terminal['causeType'] == 'ManagedRevisionCause', 'RESOURCE_COMMAND_CAUSE_SEQUENCE')
    pending = [r for r in live_terminal['outputSnapshot']['occurrences']
               if r['sourceDocumentId'] == {'value': root} and r['sourcePath'] == '/children/one']
    require(len(pending) == 1, 'RESOURCE_ONE_PENDING_OCCURRENCE')
    pending = pending[0]
    require(pending['active'] is False and pending['pendingHistoricalEpoch'] == -1
            and pending['pendingRepresentationCursor'] is None and pending['expectedTargetBlueId'] == child
            and pending['targetDocumentId'] == {'value': child}, 'RESOURCE_AUTHORED_PENDING_POSITION')
    cause = live_terminal['input']['cause']['causeIdentity']
    barrier = digest('blue-coordination-managed-catch-up-barrier/1.0', {
        'consumerDocumentId': root, 'causedByIdentity': cause, 'causeOrder': cutoff})
    plan = digest('blue-coordination-managed-catch-up-plan/1.0', {
        'barrierIdentity': barrier, 'consumerDocumentId': root,
        'targetOccurrenceIdentity': pending['occurrenceIdentity'], 'targetPath': '/children/one',
        'activationGeneration': pending['activationGeneration'], 'sourceDocumentId': child,
        'admittedSourceEpoch': -1, 'admittedSourceBlueId': child, 'causedByIdentity': cause})
    source = final_records[child]['receipts'][0]
    before = first['after'][root]
    expected = dict(planIdentity=plan, barrierIdentity=barrier, sourceReceiptIdentity=source['receiptIdentity'],
        sourceDocumentId=child, sourceEpoch=0, consumerDocumentId=root,
        targetOccurrenceIdentity=pending['occurrenceIdentity'], targetPath='/children/one',
        activationGeneration=pending['activationGeneration'], expectedConsumerCommittedEpoch=before['epoch'],
        expectedConsumerCommittedBlueId=before['blueId'], expectedGraphGeneration=live_terminal['outputSnapshot']['graphGeneration'])
    work_id = M.identity(M.WORK, expected)
    expected_wire = dict(expected, sourceDocumentId={'value': child}, consumerDocumentId={'value': root},
                         representationStep=None, successorRepresentationStep=None, workIdentity=work_id)
    attempt = last['managedAttempts'][0]
    require(attempt['work'] == expected_wire and attempt['published'] is True and attempt['replayed'] is False
            and attempt['publicationFailure'] is None and attempt['receipt'] == last['ownedRetainedApplications'][0],
            'RESOURCE_AUTHENTIC_RETAINED_WORK')
    require(command['commandType'] == 'DRAIN_PROCESSING' and command['status'] == 'APPLIED'
            and command['attemptCount'] == 1 and command['idempotencyKey'] == 'managed-epoch:' + work_id
            and json.loads(command['requestJson']) == dict(expectedPlanIdentity=plan,
                expectedSourceReceiptIdentity=source['receiptIdentity'], expectedWorkIdentity=work_id,
                maxCommittedProcessTransitions=1, maxSelectedEntries=1), 'RESOURCE_PERSISTED_RETAINED_REQUEST')
    receipt = attempt['receipt']
    after = final_records[root]
    value = dict(workIdentity=work_id, planIdentity=plan, sourceReceiptIdentity=source['receiptIdentity'],
        contractsInvocationIdentity=terminal['invocationIdentity'], contractsResultIdentity=terminal['outputSnapshot']['closureIdentity'],
        commitCompanionIdentity=terminal['commitCompanion']['companionIdentity'], consumerDocumentId=root,
        consumerRevisionEpoch=after['epoch'], consumerRevisionReceiptIdentity=after['receipts'][-1]['receiptIdentity'],
        consumerCommittedBlueId=after['blueId'], resultingSourceCursor=1)
    require(receipt == dict(value, consumerDocumentId={'value': root},
        applicationReceiptIdentity=M.identity(M.RECEIPT, value), representationCauseIdentity=None,
        successorRepresentationCauseIdentity=None, resultingRepresentationCursor=None), 'RESOURCE_RETAINED_RECEIPT_BINDING')
    require(after['epoch'] == before['epoch'] + 1
            and after['receipts'][:-1] == before['receipts'], 'RESOURCE_RETAINED_EXACTLY_ONE_REVISION')
    for document in (set(final_records) - {root}):
        require(first['before'][document] == first['after'][document] == final_records[document],
                'RESOURCE_RETAINED_REWROTE_SOURCE')
    return [first, last]
