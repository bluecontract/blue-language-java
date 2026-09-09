"""RUN025 checks actual source admission/history, frozen cutoffs and original inputs."""

def check_source_discovery(rec, rule, weights, variant, require, exact_equal, gas_check):
    import hashlib
    import struct
    from pathlib import Path
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent / 'identity'))
    import constructors as I
    from rooted_graph_checks import graph_checks

    ids = rec['documentIds']; aliases = ['A', 'B', 'S']
    require(set(ids) == set(aliases) and len(set(ids.values())) == 3, 'DISCOVERY_DOCUMENT_INVENTORY')
    require(variant in ('normal', 'A-suspended'), 'DISCOVERY_VARIANT')
    rows = rec['transcript']; phases = rec['phaseRecords']
    require(all(r.get('completed') is True for r in rows), 'DISCOVERY_INCOMPLETE_TRANSCRIPT')
    def ops(name): return [r for r in rows if r['request']['op'] == name]
    def one(name):
        values = ops(name); require(len(values) == 1, 'DISCOVERY_SINGLE_' + name); return values[0]['response']
    starts = {r['request']['alias']:r['response'] for r in ops('start')}
    require(set(starts) == {'A','B'}, 'DISCOVERY_SOURCE_NOT_PRESTARTED')
    prepared = one('prepareAuthoredSource'); supplied = one('provideAuthoredSource')
    require(prepared['initialBlueId'] == prepared['documentId'] == ids['S']
            and prepared['managedSourcePresent'] is False and prepared['liveExactPresent'] is False
            and set(prepared['before']) == {'A','B'}
            and exact_equal(prepared['before'], prepared['after']), 'DISCOVERY_DETACHED_ORIGINAL')
    require(supplied['blueId'] == ids['S'] and exact_equal(supplied['exact'], prepared['initialExact'])
            and exact_equal(supplied['before'], supplied['after']) and set(supplied['after']) == {'A','B'},
            'DISCOVERY_BODY_IS_NOT_HISTORY')
    appends = {r['request']['capture']:r for r in ops('append')}
    require(set(appends) == {'E10','E15','E20','E50'} and len(ops('append')) == 4, 'DISCOVERY_EXACT_ENTRY_INVENTORY')
    entries = {k:r['response']['actualEntry'] for k,r in appends.items()}
    entryids = {k:r['response']['entryBlueId'] for k,r in appends.items()}
    require(len(set(entryids.values())) == 4, 'DISCOVERY_DUPLICATE_ENTRY')
    targets = {'E10':'A','E20':'B','E15':'S','E50':'S'}
    timelines = {'A':'rcp2/discovery-a','B':'rcp2/discovery-b','S':'rcp2/source'}
    for name,row in appends.items():
        response = row['response']; exact = response['exactEntry']; target = targets[name]
        require(entries[name]['blueId'] == entryids[name]
                and entries[name]['timestampMicros'] == int(name[1:])
                and entries[name]['timeline']['id'] == timelines[target], 'DISCOVERY_ENTRY_SOURCE_ORDER')
        def scalar(value): return value['value'] if isinstance(value,dict) and 'value' in value else value
        require(scalar(exact['timestamp']) == int(name[1:])
                and scalar(exact['timeline']['timelineId']) == timelines[target]
                and scalar(exact['actor']['accountId']) == 'alice', 'DISCOVERY_EXACT_ENVELOPE')
        require(exact['message']['document']['blueId'] == (ids['S'] if target == 'S' else starts[target]['initializedBlueId']),
                'DISCOVERY_ENTRY_ORIGINAL_DOCUMENT')
        body = exact['message']['request']
        require(exact_equal(entries[name]['exact'], exact) and exact_equal(entries[name]['request'], body),
                'DISCOVERY_RETAINED_ENTRY_BYTES')
        if target == 'S':
            require(row['request']['authoredSource'] == 'S' and row['request']['request']['counterValue'] == (5 if name == 'E15' else 99),
                    'DISCOVERY_SOURCE_REQUEST')
            require(scalar(body['counterValue']) == (5 if name == 'E15' else 99), 'DISCOVERY_EXACT_COUNTER_REQUEST')
        else:
            require(row['request']['request']['child'] == {'$capture':'S.initialBlueId'}, 'DISCOVERY_SAVED_REFERENCE_SELECTION')
            require(body['child'] == {'blueId':ids['S']}, 'DISCOVERY_EXACT_SAVED_REFERENCE')
    admission = phases['sourceAdmission']; live = phases['source15']
    starts['S'] = admission['admittedSource']
    did,scalar,by_id,prefix,sccs,snapshot_check,trace_check = graph_checks(aliases,ids,starts,weights,require,exact_equal,gas_check)
    def selection(sel, kind, root):
        require(sel['kind'] == kind and did(sel['requestingRoot']) == ids[root]
                and did(sel['sourceDocumentId']) == ids['S'] and sel['authoredBlueId'] == ids['S'], 'DISCOVERY_SELECTION_OWNER')
        cutoff = [10 if root == 'A' else 20, timelines[root], entryids['E10' if root == 'A' else 'E20']]
        require(sel['cutoffExclusive']['components'] == cutoff, 'DISCOVERY_FROZEN_CUTOFF')
        fields = [ids[root],sel['requestingInvocationIdentity'],sel['demandIdentity'],ids['S'],ids['S'],kind,
                  str(sel['sourceEpoch']),sel['sourceBlueId'],sel['workIdentity'],sel['entryBlueId'] or '',
                  str(sel['journalRevision']),str(sel['routeGeneration']),sel['sourceSurfaceIdentity'],sel['diagnostic'] or '']
        h=hashlib.sha256()
        for field in ['blue.coordination/source-history-prerequisite/1']+fields+[str(v) for v in cutoff]:
            encoded=field.encode('utf-8');h.update(struct.pack('>I',len(encoded)));h.update(encoded)
        require(sel['selectionIdentity'] == 'sha256:'+h.hexdigest(), 'DISCOVERY_SELECTION_IDENTITY')
    def bind_source_transition(retained, transitions, companion, invocation):
        matches = [t for t in transitions if did(t['documentId']) == ids['S']
                   and t['transitionReceiptIdentity'] == retained['contractsTransitionReceiptIdentity']]
        require(len(matches) == 1, 'DISCOVERY_SOURCE_COMMITTED_TRANSITION')
        transition = matches[0]
        require(companion['bindsManagedTransitionReceipts'] is True
                and companion['invocationIdentity'] == invocation == transition['sourceInvocationIdentity']
                and retained['commitCompanionIdentity'] == companion['companionIdentity']
                and retained['beforeBlueId'] == transition['beforeBlueId']
                and retained['afterBlueId'] == transition['afterBlueId']
                and retained['originalCauseIdentity'] == transition['originalCauseIdentity'],
                'DISCOVERY_SOURCE_COMMIT_BINDING')
        host, core = retained['emittedEvents'], transition['emittedRootEvents']
        require(len(host) == len(core), 'DISCOVERY_SOURCE_EVENT_TRANSITION_COUNT')
        for h, c in zip(host, core):
            require(h['ordinal'] == c['ordinal'] and h['eventOccurrenceOrdinal'] == c['occurrenceOrdinal']
                    and h['sourceDocumentId'] == c['sourceDocumentId']
                    and h['eventOccurrenceIdentity'] == c['eventOccurrenceIdentity']
                    and h['eventBlueId'] == c['eventBlueId'] and h['publicAtSource'] == c['publicAtSource']
                    and exact_equal(h['exactEvent'], c['exactEvent']), 'DISCOVERY_SOURCE_EVENT_TRANSITION_BYTES')
        return transition
    for response,kind,root in ((admission,'ADMISSION','B' if variant == 'A-suspended' else 'A'),(live,'LIVE','B')):
        sel = response['selection']; result = response['result']; selection(sel,kind,root)
        require(exact_equal(result['selection'],sel) and result['replayed'] is False, 'DISCOVERY_EXECUTION_BINDING')
        for parent in ('A','B'):
            require(exact_equal(response['before'][parent], response['after'][parent]), 'DISCOVERY_SOURCE_CANNOT_PUBLISH_PARENT')
        forged = response['forgedSource']; forged_sel = dict(sel); forged_sel['sourceDocumentId'] = sel['requestingRoot']
        require(exact_equal(forged['selection'],forged_sel) and forged['rejectionClass'] == 'java.lang.IllegalArgumentException'
                and exact_equal(forged['before'],response['before']) and exact_equal(forged['before'],forged['after']),
                'DISCOVERY_ACTUAL_FORGED_DESCRIPTOR_REJECTION')
    require(admission['selection']['sourceEpoch'] == -1 and admission['selection']['entryBlueId'] is None
            and set(admission['before']) == {'A','B'} and set(admission['after']) == set(aliases), 'DISCOVERY_SINGLE_SOURCE_ADMISSION')
    ar = admission['result']['admission']; result = admission['admissionResult']
    require(ar['published'] is True and ar['publicationOutcome'] == 'PUBLISHED' and [did(v) for v in ar['documentIds']] == [ids['S']]
            and admission['result']['processing'] is None and result['status'] == 'SUCCESS' and result['commits'] is True
            and result['rollbackToInput'] is False and result['invocationIdentity'] == admission['selection']['workIdentity'],
            'DISCOVERY_ADMISSION_PUBLICATION')
    require(admission['admissionBudget'] == 100000, 'DISCOVERY_ADMISSION_DEFAULT_POLICY')
    trace_check(admission['admissionGas'],admission['admissionGasTrace'],admission['admissionBudget'])
    require(exact_equal(admission['admissionGasTrace'],result['gasTrace']) and admission['admissionGas']['total'] == result['totalGas']
            and admission['admissionWork']['invocationIdentity'] == result['invocationIdentity'], 'DISCOVERY_ADMISSION_REAL_METER')
    require(len(admission['after']['S']['receipts']) == 1 and admission['after']['S']['epoch'] == 0
            and admission['after']['S']['events'] == [], 'DISCOVERY_GENESIS_NOT_SOURCE_EVENT')
    admission_transition = bind_source_transition(admission['after']['S']['receipts'][0],
            result['managedTransitionReceipts'], result['platformCommitCompanion'], result['invocationIdentity'])
    repeated = admission['repeated']
    require(repeated['replayed'] is True and exact_equal(repeated['selection'],admission['selection'])
            and exact_equal(repeated['admission'],ar) and repeated['processing'] is None
            and exact_equal(admission['afterRepeated'],admission['after']), 'DISCOVERY_NO_DUPLICATE_ADMISSION')
    require(live['selection']['entryBlueId'] == entryids['E15'] and live['selection']['sourceEpoch'] == 0
            and live['result']['admission'] is None, 'DISCOVERY_EARLIER_SOURCE_ONLY')
    process = live['result']['processing']
    require([r['blueId'] for r in process['processedEntries']] == [entryids['E15']]
            and process['committedProcessTransitions'] == 1 and len(live['terminals']) == 1
            and live['remainingPrerequisites'] == [], 'DISCOVERY_ONE_SOURCE_STEP')
    terminal = live['terminals'][0]; inp=terminal['input']; source=live['after']['S']
    require(terminal['status'] == 'SUCCESS' and terminal['rollbackToInput'] is False
            and terminal['causeType'] == 'ExternalEventCause' and inp['cause']['eventBlueId'] == entryids['E15']
            and [did(v) for v in terminal['ownedDocumentIds']] == [ids['S']], 'DISCOVERY_SOURCE_TERMINAL')
    trace_check(terminal['gas'],terminal['fullGasTrace'],inp['executionPolicy']['sharedLimit'])
    require(exact_equal(live['before']['S']['receipts'],source['receipts'][:1])
            and [r['epoch'] for r in source['receipts']] == rule['sourceEpochs']
            and scalar(source['exactDocument']['counter']) == 5 and len(source['events']) == 1,
            'DISCOVERY_SOURCE_HISTORY_EXACT_PREFIX')
    require(source['receipts'][1]['sourceEntry']['blueId'] == entryids['E15']
            and scalar(source['events'][0]['exactEvent']['kind']) == 'RCP2/Tick', 'DISCOVERY_SINGLE_SOURCE_EMISSION')
    live_transition = bind_source_transition(source['receipts'][1], terminal['managedTransitionReceipts'],
            terminal['commitCompanion'], inp['invocationIdentity'])
    require(terminal['invocationIdentity'] == inp['invocationIdentity'] == live['selection']['workIdentity'],
            'DISCOVERY_LIVE_WORK_INVOCATION')
    for row in ops('awaitSourceHistory'):
        response=row['response']; root=row['request']['root']; entry=entryids['E10' if root == 'A' else 'E20']
        require(exact_equal(response['before'],response['after']) and response['drain']['quiescent'] is False,
                'DISCOVERY_WAIT_DID_NOT_COMMIT')
        outcomes=response['drain']['entries']
        require(len(outcomes)==1 and outcomes[0]['entry']['blueId']==entry
                and outcomes[0]['disposition']=='NEEDS_RESOURCES', 'DISCOVERY_REAL_PARENT_WAIT')
        expected=row['request']['expectedPrerequisite']; actual=response['prerequisites']
        require(len(actual)==(0 if expected=='NONE' else 1), 'DISCOVERY_WAIT_DESCRIPTOR_COUNT')
        if actual: selection(actual[0],expected,root)
    require(len(ops('awaitSourceHistory')) == 2, 'DISCOVERY_WAIT_PHASE_COUNT')
    if variant == 'A-suspended':
        require('aMissingExact' in phases and phases['aMissingExact']['prerequisites']==[], 'DISCOVERY_GENUINE_MISSING_BODY')
    else: require('aMissingExact' not in phases, 'DISCOVERY_NORMAL_SCHEDULE')
    for phase,count,root,cutoff in (('aCatchUp',1,'A',10),('subject',2,'B',20)):
        hist=phases[phase]['applications']; require(len(hist)==count, 'DISCOVERY_HISTORICAL_STEP_COUNT')
        for epoch,application in enumerate(hist):
            cause=application['exactCause']; retained=application['retainedSource']; work=application['work']
            require(application['from']==epoch-1 and application['to']==epoch and application['source']=='S'
                    and application['targetPath']=='/child' and application['status']=='SUCCESS'
                    and exact_equal(retained,source['receipts'][epoch]), 'DISCOVERY_IMMUTABLE_SOURCE_RECEIPT')
            require(cause['fromEpoch']==epoch-1 and cause['toEpoch']==epoch and did(cause['childDocumentId'])==ids['S']
                    and cause['beforeBlueId']==retained['beforeBlueId'] and cause['afterBlueId']==retained['afterBlueId']
                    and exact_equal(cause['sourceTransitionReceipt'],application['sourceReceipt'])
                    and work['sourceReceiptIdentity']==retained['receiptIdentity'], 'DISCOVERY_AUTHENTIC_HISTORY_CAUSE')
            original_transition = admission_transition if epoch == 0 else live_transition
            require(exact_equal(application['sourceReceipt'], original_transition)
                    and cause['sourceRevisionReceiptIdentity'] == retained['contractsTransitionReceiptIdentity']
                    and cause['originalSourceCauseIdentity'] == retained['originalCauseIdentity']
                    and did(work['sourceDocumentId']) == ids['S'] and work['sourceEpoch'] == epoch
                    and did(work['consumerDocumentId']) == ids[root] and work['targetPath'] == '/child'
                    and cause['targetOccurrenceIdentity'] == work['targetOccurrenceIdentity'],
                    'DISCOVERY_HISTORY_COMMITTED_SOURCE_AND_OCCURRENCE')
            require(application['sourceOrder'][0] < cutoff and application['ownedEvents']==[], 'DISCOVERY_NO_OVERTAKE_OR_REEMISSION')
            trace_check(application['gas'],application['fullGasTrace'],application['budget'])
    before=phases['sourceHistoryBeforeRestart']; after=phases['sourceHistoryAfterRestart']
    require(exact_equal(before,after) and exact_equal(rec['output']['sourceDiscovery'],after), 'DISCOVERY_RESTART_OBSERVATION')
    require(exact_equal(before['records'],before['after']) and exact_equal(before['sourceHistory'],source['receipts'])
            and exact_equal(before['records']['S'],source) and before['nextSourceLive']==entryids['E50'], 'DISCOVERY_FUTURE_ENTRY_REMAINS_QUEUED')
    prefix({'A':starts['A']['retained'],'B':starts['B']['retained'],'S':admission['after']['S']}, before['records'])
    basis=before['basis']; descriptor=basis['descriptor']
    require(descriptor['documentId']==ids['S'] and descriptor['initialDocumentBlueId']==ids['S']
            and descriptor['admission']=={'mode':'FULL_HISTORY'} and basis['identity']==I.history(descriptor)
            and basis['admissionInvocationIdentity']==result['invocationIdentity']
            and basis['admissionCompanionIdentity']==result['platformCommitCompanion']['companionIdentity'],
            'DISCOVERY_AUTHENTIC_FULL_HISTORY_BASIS')
    require(exact_equal(before['records']['S']['historyBasis'],descriptor)
            and exact_equal(rec['output']['sourceHistoryBasis'],basis)
            and exact_equal(rec['output']['sourceHistoryThrough20'],source['receipts']), 'DISCOVERY_OUTPUT_BASIS_BINDING')
    for alias,counter in (('A',0),('B',5)):
        view=snapshot_check(before['progress']['selectedSnapshots'][alias])
        require(scalar(view[ids['S']]['document']['counter'])==counter
                and before['projections'][alias]['child']['counter']==counter
                and before['projections'][alias]['seen']==counter
                and before['projections'][alias]['log']==([] if alias=='A' else [5]), 'DISCOVERY_ROOT_LOCAL_HISTORICAL_VIEW')
    require(len(before['journal'])==4 and {row['blueId'] for row in before['journal']}==set(entryids.values()), 'DISCOVERY_NO_NEW_ENTRY')
    require(exact_equal(rec['restart']['before'],before['records']) and exact_equal(rec['restart']['after'],after['records'])
            and rec['restart']['beforeCommandCount']==rec['restart']['afterCommandCount']==4, 'DISCOVERY_EXACT_RESTART')
