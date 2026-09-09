"""Additive proposal. Call AFTER normal actual semantic/gas/identity checks.

This checker consumes observations; it never supplies expected runtime output.
Exact field names below are the proposed additive collector contract.
"""
import copy
import json


def check_host_timestamp(rec, rule, require, exact_equal):
    o = rec['output']
    h = o[rule['observationField']]
    ids = rec['documentIds']
    completed = rule['completionTime']
    require(completed == '2026-09-08T12:00:00.123456789Z', 'TIME_LITERAL_CHANGED')
    require(h['recordKind'] == rule['recordKind'] == 'ROOTED_RETAINED_COMMAND', 'TIME_RECORD_KIND')
    require(h['requestedCompletionTime'] == completed, 'TIME_REQUESTED_PRECISION')
    require(h['selectedRootDocumentId'] == ids[rule['root']], 'TIME_SELECTED_ROOT')
    # selectedWork is the real public auditManagedEpochApplicationWork SDK view
    # of the selected identity, captured BEFORE the original callback.
    w = h['selectedWork']
    require(w['consumerDocumentId']['value'] == ids[rule['consumer']], 'TIME_CONSUMER')
    require(w['sourceDocumentId']['value'] == ids[rule['source']], 'TIME_SOURCE')
    require(w['targetPath'] == rule['targetPath'] and w['sourceEpoch'] == rule['sourceEpoch'], 'TIME_SOURCE_POSITION')
    require(w['representationStep'] is None, 'TIME_WRONG_CAUSE_FAMILY')
    selected = h['selectedWorkBefore']
    require(set(selected) == (set(w) - {'representationStep'}) | {'representationCause','isRepresentationApplication','expectedNextSourceEpoch'}, 'TIME_SELECTED_WORK_INVENTORY')
    require(all(exact_equal(selected[k],w[k]) for k in w if k != 'representationStep')
            and selected['representationCause'] is None and selected['isRepresentationApplication'] is False
            and selected['expectedNextSourceEpoch'] == w['sourceEpoch'], 'TIME_SELECTED_WORK_CHANGED')
    require(h['processorCallCount'] == 1, 'TIME_EXTRA_PROCESSOR_CALL')
    require(h['drainCounts'] == {'externalEntries': 0, 'independentApplications': 0, 'localApplications': 1}, 'TIME_WRONG_ACTUAL_LANE')
    require(h['firstCompletionWrote'] is True and h['duplicateCompletionWrote'] is False, 'TIME_COMPLETION_CAS')
    queued, started, before, duplicate = [h[k] for k in ('queued', 'started', 'beforeClose', 'afterDuplicate')]
    fresh = h['freshProcess']
    require(type(h['parentPid']) is int and type(fresh['pid']) is int and h['parentPid'] != fresh['pid'], 'TIME_SAME_PROCESS')
    require(queued['status'] == 'QUEUED' and started['status'] == 'RUNNING' and before['status'] == 'APPLIED', 'TIME_ATTEMPT_STATUSES')
    require(before['attemptCount'] == started['attemptCount'] == 1, 'TIME_ATTEMPT_COUNT')
    require(queued['commandId'] == started['commandId'] == before['commandId'], 'TIME_COMMAND_ID_CHANGED')
    require(queued['createdAt'] == started['createdAt'] == before['createdAt'] == '2026-09-08T11:59:58.123456789Z', 'TIME_CREATED_PRECISION')
    require(started['startedAt'] == before['startedAt'] == '2026-09-08T11:59:59.123456789Z', 'TIME_STARTED_PRECISION')
    require(before['completedAt'] == fresh['record']['completedAt'] == completed, 'TIME_COMPLETED_PRECISION')
    require(exact_equal(before, duplicate) and exact_equal(before, fresh['record']), 'TIME_COMPLETE_RECORD_CHANGED')
    require(queued['requestJson'] == started['requestJson'] == before['requestJson'], 'TIME_REQUEST_BYTES_CHANGED')
    request = json.loads(before['requestJson'])
    require(request['rootDocumentId'] == ids[rule['root']], 'TIME_REQUEST_ROOT')
    for expected, actual in [('expectedWorkIdentity', 'workIdentity'), ('expectedPlanIdentity', 'planIdentity'), ('expectedSourceReceiptIdentity', 'sourceReceiptIdentity')]:
        require(request[expected] == w[actual], 'TIME_REQUEST_' + actual)
    sql = h['sqlBeforeClose']
    require(sql['processingLane'] == 'ROOTED_RETAINED' and sql['commandCount'] == 1, 'TIME_SQL_LANE')
    require(sql['replayOrder'] == [{'commandId': before['commandId'], 'replaySequence': 1}], 'TIME_REPLAY_ORDER')
    require(exact_equal(sql, h['sqlAfterDuplicate']) and exact_equal(sql, fresh['sql']), 'TIME_SQL_REOPEN_CHANGED')
    require(exact_equal(h['sdkRecordsAfterApplication'], h['sdkRecordsAfterReopen']), 'TIME_REOPEN_CHANGED_SDK')
    require(exact_equal(h['sdkRecordsAfterReopen'], rec['restart']['before']), 'TIME_SQL_REOPEN_NOT_BOUND_TO_FINAL_SDK')
    encoded = json.loads(before['resultJson'])
    require(set(encoded) == {'result', 'replayEvidence'}, 'TIME_ENVELOPE_FIELDS')
    actual, replay = h['actualApplication'], h['actualReplayApplication']
    require(encoded['result'] == {'rootedRetainedApplications': [actual]}, 'TIME_STORED_ACTUAL_DIFFERENT')
    require(encoded['replayEvidence'] == {'rootedRetainedApplications': [replay]}, 'TIME_STORED_REPLAY_DIFFERENT')
    require(actual['rootDocumentId'] == ids[rule['root']] and exact_equal(actual['work'], w), 'TIME_RESULT_SELECTION_DIFFERENT')
    require(actual['result']['disposition'] == 'APPLIED', 'TIME_ACTUAL_NOT_APPLIED')
    require(actual['result']['closureId'] == o['publicationIdentity'], 'TIME_ACTUAL_PUBLICATION_DIFFERENT')
    require(actual['result']['stats']['gas'] == o['gas']['total'] and actual['result']['processorAttemptCount'] == 1, 'TIME_ACTUAL_GAS_DIFFERENT')
    require([x['documentId'] for x in actual['result']['changes']] == [ids[rule['root']]], 'TIME_ACTUAL_OWNER_DIFFERENT')
    change = actual['result']['changes'][0]
    owned = o['semanticReceipts']
    require(len(owned) == 1 and owned[0]['owner'] == rule['root'], 'TIME_OWNED_RECEIPT_COUNT')
    require(change['beforeBlueId'] == owned[0]['beforeBlueId'] and change['afterBlueId'] == owned[0]['afterBlueId'], 'TIME_CHANGED_RESULT_IDENTITY')
    head = h['sdkRecordsAfterApplication'][rule['root']]
    require(change['afterBlueId'] == head['blueId'] and change['epoch'] == head['epoch'], 'TIME_RESULT_HEAD_DIFFERENT')
    require([x['blueId'] for x in actual['result']['publicEvents']] == [x['blueId'] for x in o['events']], 'TIME_ACTUAL_EVENTS_DIFFERENT')
    require(w['sourceReceiptIdentity'] == o['retainedSource']['receiptIdentity'], 'TIME_SOURCE_RECEIPT_DIFFERENT')
    require(o['exactCause']['fromEpoch'] == rule['historicalFromEpoch'] and o['exactCause']['toEpoch'] == rule['sourceEpoch'], 'TIME_CAUSE_POSITION')
    require(o['exactCause']['childDocumentId']['value'] == ids[rule['source']] and o['exactCause']['targetOccurrenceIdentity'] == w['targetOccurrenceIdentity'], 'TIME_CAUSE_TARGET')
    # Reconstruct ONLY the documented host replay projection, preserving every
    # semantic field. Complete processor gas/companion evidence remains in o.
    expected_replay = copy.deepcopy(actual)
    result = expected_replay['result']
    result['stats'].pop('elapsedNanos')
    result['changes'] = [{k: change[k] for k in ('documentId', 'epoch', 'afterBlueId')} for change in result['changes']]
    result['publicEvents'] = [event['blueId'] for event in result['publicEvents']]
    require(exact_equal(replay, expected_replay), 'TIME_REPLAY_PROJECTION_CHANGED')
    negatives = h['negativeRejections']
    require(set(negatives) == {'sourceReceiptIdentity', 'workIdentity', 'planIdentity'}, 'TIME_MISSING_RUNTIME_NEGATIVE')
    for field, negative in negatives.items():
        candidate = negative['candidate']
        expected_candidate = copy.deepcopy(before)
        changed = copy.deepcopy(encoded)
        changed['result']['rootedRetainedApplications'][0]['work'][field] = 'unreviewed:' + field
        require(json.loads(candidate['resultJson']) == changed, 'TIME_WRONG_NEGATIVE_MUTATION')
        expected_candidate['resultJson'] = candidate['resultJson']
        require(exact_equal(candidate, expected_candidate), 'TIME_NEGATIVE_CHANGED_OTHER_FIELDS')
        require(negative['exceptionClass'] == 'java.lang.IllegalStateException' and 'differs from its exact selection' in negative['message'], 'TIME_RUNTIME_ACCEPTED_MUTATION')
        require(exact_equal(negative['persistedBefore'], before) and exact_equal(negative['persistedAfter'], before), 'TIME_NEGATIVE_PUBLISHED')
    columns = h['timestampColumns']
    require(columns and all(row['DATETIME_PRECISION'] == 9 for row in columns), 'TIME_SCHEMA_PRECISION')
    actual_columns = {(row['TABLE_NAME'], row['COLUMN_NAME']) for row in columns}
    require(len(actual_columns) == len(columns), 'TIME_SCHEMA_DUPLICATE_COLUMN')
    # expectedTimestampColumns is independently derived from sealed V22 by the
    # outer artifact check, never supplied by the adapter as an expectation.
    expected_columns = {tuple(x) for x in rule['expectedTimestampColumns']}
    require(expected_columns <= actual_columns, 'TIME_SCHEMA_MISSING_MIGRATED_COLUMN')
    migrations = h['flywayRows']
    require(migrations and all(row['success'] is True for row in migrations), 'TIME_MIGRATION_FAILED')
    versions = {str(row['version']) for row in migrations}
    require({'22', '27'} <= versions, 'TIME_REQUIRED_MIGRATIONS_MISSING')
    # Both classOrigins maps are independently compared to the sealed Boot JAR
    # by verify_packaged_application.py. Do not trust this equality alone.
    require(exact_equal(h['classOrigins'], fresh['classOrigins']), 'TIME_REOPEN_CLASS_ORIGINS_CHANGED')
