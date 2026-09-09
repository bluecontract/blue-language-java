"""Independent RUN028 process/atomicity oracle; observations alone never grant a PASS."""
import hashlib
import json
from pathlib import Path

CUTS=('beforeCommit','afterCommitBeforeAck','beforeNotification','afterCursorWriteAttempt')
LANES=('LIVE','OWNED_RETAINED')

def check_fault_cuts(record,rule,weights,require,exact_equal,gas_check):
    from rooted_graph_checks import graph_checks
    import sys
    sys.path.insert(0,str(Path(gas_check.__globals__['__file__']).resolve().parent/'identity'))
    import constructors as I
    matrix=record['output']['faultCuts']
    suite=Path(gas_check.__globals__['__file__']).resolve().parent
    require(matrix['sourceFiles']=={'source':(suite/'examples/counter.yaml').read_text(),
                                  'consumer':(suite/'examples/parent.yaml').read_text()}, 'FAULT_LITERAL_SOURCE_BYTES')
    require([x['lane'] for x in matrix['lanes']]==list(LANES),'FAULT_LANES')
    require(rule['lanes']==list(LANES) and rule['cuts']==list(CUTS)
            and rule['maxConcurrentLanes']==1 and rule['startupSeconds']==60 and rule['cutSeconds']==60 and rule['commandSeconds']==45,'FAULT_UNCHANGED_BOUNDS')
    process_instances=set()
    def tables(state):return state['sql']['tables']
    def sql_command(state,target):
        rows=tables(state)['mini_command']['rows'];selected=[r for r in rows if r['command_id']==target['commandId']]
        require(len(selected)==1,'FAULT_COMMAND_OWNER');return selected[0]
    def process(evidence,mode):
        p=evidence['process'];handshake=p['handshake'];job=p['job']
        require(job['mode']==mode and p['pid']==handshake['pid'] and job['processToken']==handshake['processToken']
                and p.get('cleanupTermination') is None,'FAULT_PROCESS_KIND_OR_CLEANUP')
        identity=(p['pid'],handshake['processStart'])
        require(identity not in process_instances,'FAULT_SAME_PROCESS_REUSED');process_instances.add(identity)
        binding=handshake['applicationBinding']
        require(binding['sha256']==record['artifactSha256'] and job['artifactSha256']==record['artifactSha256']
                and job['sourceLockSha256']==record['sourceLockSha256'],'FAULT_ARTIFACT_MIX')
        require(binding['applicationEntries'] and binding['nestedDependencies'],'FAULT_MISSING_PACKAGED_ENTRIES')
        for row in binding['applicationEntries']:require(row['packagedSha256']==row['loadedSha256'],'FAULT_SHADOWED_APPLICATION')
        dependency={r['entry']:r['packagedSha256'] for r in binding['nestedDependencies']}
        require(dependency==record['dependencies'] and all(r['packagedSha256']==r['classpathSha256'] for r in binding['nestedDependencies']),'FAULT_DEPENDENCY_BINDING')
        require(exact_equal(handshake['sourceLock'],matrix['sourceLock']),'FAULT_SOURCE_LOCK_BYTES')
        if mode!='CUT':require(p['returnCode']==0,'FAULT_UNSUCCESSFUL_CHILD')
        return p
    for lane in matrix['lanes']:
        kind=lane['lane'];seed=lane['seed']['record'];process(lane['seed'],'SEED');process(lane['control'],'CONTROL')
        require([c['cut'] for c in lane['cases']]==list(CUTS) and len(lane['cases'])==4,'FAULT_MISSING_CUT_CASE')
        target=seed['target'];captures=seed['captures'];aliases=['source','consumer'];ids={a:captures[a]['documentId'] for a in aliases}
        before=seed['beforeInvocation'];control=lane['control']['record'];control_state=control['recovered']
        require(seed['actualInvocations']==[] and target['commandId'] and seed['backupSha256'],'FAULT_SEED_ALREADY_EXECUTED')
        require(sql_command(before,target)['status']!='APPLIED','FAULT_SEED_TERMINAL')
        require(len(control['actualInvocations'])==1,'FAULT_CONTROL_SELECTED_INVOCATION')
        starts={a:{'initialBlueId':captures[a]['initialBlueId'],'epoch0Receipt':captures[a]['epochZeroReceipt'],
                   'retained':{'historyBasis':before['sdkRecords'][a]['historyBasis']}} for a in aliases}
        did,scalar,by_id,prefix,sccs,snapshot_check,trace_check=graph_checks(aliases,ids,starts,weights,require,exact_equal,gas_check)
        for alias in aliases:
            require(captures[alias]['initialBlueId']==ids[alias]
                    and captures[alias]['epochZeroReceipt']['epoch']==0
                    and captures[alias]['epochZeroReceipt']['afterBlueId']==before['sdkRecords'][alias]['receipts'][0]['afterBlueId'], 'FAULT_SAVED_INITIAL_AND_ZERO')
        for alias in aliases:
            source=matrix['sourceFiles'][alias]
            require(captures[alias]['originalSourceSha256']==hashlib.sha256(source.encode()).hexdigest()
                    and captures[alias]['authoredYaml']==source.replace('tutorial/embedding/alice','rcp/fault/'+alias), 'FAULT_AUTHORED_SOURCE_CHANGED')
            require(captures[alias]['initialBlueId']!=captures[alias]['epochZeroReceipt']['afterBlueId'], 'FAULT_INITIAL_SUBSTITUTED_WITH_ZERO')
        require('child' not in captures['consumer']['initialExact'], 'FAULT_PREPOPULATED_CONSUMER')
        selected=seed['selectedAtClaim']
        if kind=='OWNED_RETAINED':
            work=selected['managedEpochApplicationWork']
            require(selected['kind']=='MANAGED_EPOCH_APPLICATION' and selected['rootedRetainedRoot'] is None
                    and work['sourceEpoch']==1 and did(work['sourceDocumentId'])==ids['source']
                    and work['sourceReceiptIdentity']==target['sourceReceiptIdentity'] and work['planIdentity']==target['planIdentity'],'FAULT_NOT_REAL_OWNED_RETAINED')
            request=json.loads(sql_command(before,target)['request_json'])
            require(request['expectedWorkIdentity']==work['workIdentity'] and request.get('rootDocumentId') is None,'FAULT_WRONG_STORAGE_LANE')
        else:
            require(selected['kind']=='JOURNAL' and target['exactEntryBlueId']==captures['source100']['blueId']
                    and target['planIdentity'] is None,'FAULT_NOT_REAL_LIVE')
        def invocation(actual):
            drain=actual['drain'];prefix(drain['before'],drain['after'])
            require(drain['resourceFailures']==[] and drain['terminals'] and len(drain['terminals'])==1,'FAULT_DRAIN_NOT_SUCCESSFUL_SELECTED_WORK')
            terminal=drain['terminals'][0];inp=terminal['input'];companion=terminal['commitCompanion']
            require(terminal['status']=='SUCCESS' and terminal['rollbackToInput'] is False,'FAULT_PROCESSOR_DID_NOT_COMMIT')
            trace_check(terminal['gas'],terminal['fullGasTrace'],inp['executionPolicy']['sharedLimit'])
            inputs=snapshot_check(inp['snapshot']);outputs=snapshot_check(terminal['outputSnapshot'])
            require(terminal['invocationIdentity']==inp['invocationIdentity']==companion['invocationIdentity']
                    and companion['inputClosureIdentity']==inp['snapshot']['closureIdentity']
                    and companion['outputClosureIdentity']==terminal['outputSnapshot']['closureIdentity'],'FAULT_BASE_COMMIT_BINDING')
            owner_alias='source' if kind=='LIVE' else 'consumer';owner_id=ids[owner_alias]
            entry_owner=next(group for group in sccs(inp['snapshot']) if owner_id in group)
            require(entry_owner=={owner_id} and terminal['ownedDocumentIds']==[{'value':owner_id}],'FAULT_ROOTED_WRITE_SCOPE')
            descriptor={'members':[{'documentId':owner_id,'historyBasisIdentity':I.history(starts[owner_alias]['retained']['historyBasis'])}],'internalEdges':[]}
            context,context_hash=I.context(descriptor);rooted=terminal['rootedProjection']
            require(exact_equal(context,terminal['rootedContext']) and rooted['invocationIdentity']==I.wrapper('rootedInvocationIdentity',{
                'baseInvocationIdentity':inp['invocationIdentity'],'rootProcessingContextIdentity':context_hash,'deliveryBasisIdentity':rooted['deliveryBasisIdentity']})
                and rooted['companionIdentity']==I.wrapper('rootedCommitCompanionIdentity',{'baseCommitCompanionIdentity':companion['companionIdentity'],
                'rootedInvocationIdentity':rooted['invocationIdentity'],'rootProcessingContextIdentity':context_hash}),'FAULT_ROOTED_COMMIT_BINDING')
            require(exact_equal(rooted['inputSnapshot'],inp['snapshot']) and exact_equal(rooted['resultingSnapshot'],terminal['outputSnapshot']),'FAULT_ROOTED_EXACT_SNAPSHOTS')
            require(exact_equal(terminal['occurrenceBindings'],terminal['outputSnapshot']['occurrences']),'FAULT_RESULT_OCCURRENCES')
            for receipt in terminal['managedTransitionReceipts']:
                document=did(receipt['documentId']);result=next(r for r in terminal['resultingDocuments'] if did(r['documentId'])==document)
                cause=inp['cause'];original=cause['causeIdentity'] if kind=='LIVE' else cause['originalSourceCauseIdentity']
                require(receipt['sourceInvocationIdentity']==inp['invocationIdentity'] and receipt['originalCauseIdentity']==original
                        and receipt['beforeBlueId']==result['beforeBlueId'] and receipt['afterBlueId']==result['afterBlueId'],'FAULT_TRANSITION_BINDING')
            appended=drain['after'][owner_alias]['receipts'][len(drain['before'][owner_alias]['receipts']):]
            require(len(appended)==1,'FAULT_SELECTED_SUCCESSOR_COUNT')
            published=appended[0];match=[r for r in terminal['managedTransitionReceipts'] if r['transitionReceiptIdentity']==published['contractsTransitionReceiptIdentity']]
            require(len(match)==1 and did(match[0]['documentId'])==owner_id and published['commitCompanionIdentity']==companion['companionIdentity']
                    and published['afterBlueId']==match[0]['afterBlueId'],'FAULT_PUBLISHED_HOST_RECEIPT')
            if kind=='OWNED_RETAINED':
                require(terminal['causeType']=='ManagedRevisionCause' and len(drain['ownedRetainedApplications'])==1
                        and drain['localRetainedApplications']==[] and drain['entries']==[],'FAULT_RETAINED_LANE_SUBSTITUTION')
                require(exact_equal(drain['before']['source'],drain['after']['source']),'FAULT_RETAINED_SOURCE_REWRITTEN')
                source=drain['before']['source']['receipts'][1];cause=inp['cause']
                require(source['receiptIdentity']==target['sourceReceiptIdentity'] and cause['toEpoch']==cause['fromEpoch']+1==1
                        and cause['sourceRevisionReceiptIdentity']==source['contractsTransitionReceiptIdentity']
                        and cause['beforeBlueId']==source['beforeBlueId'] and cause['afterBlueId']==source['afterBlueId']
                        and exact_equal(cause['afterDocument'],source['afterDocument']),'FAULT_EXACT_RETAINED_SOURCE')
                require(terminal['ownedPublicEvents']==[],'FAULT_SOURCE_EVENT_REEMITTED')
            else:
                require(terminal['causeType']=='ExternalEventCause' and inp['cause']['eventBlueId']==target['exactEntryBlueId']
                        and exact_equal(inp['cause']['event'],captures['source100']['exact'])
                        and len(terminal['ownedPublicEvents'])==1,'FAULT_LIVE_INPUT_AND_EVENT')
            return terminal
        control_terminal=invocation(control['actualInvocations'][0])
        prefix(before['sdkRecords'],control_state['sdkRecords'])
        source_before=before['sdkRecords']['source'];source_after=control_state['sdkRecords']['source']
        consumer_before=before['sdkRecords']['consumer'];consumer_after=control_state['sdkRecords']['consumer']
        require(scalar(source_after['exactDocument']['counter'])==1 and len(source_after['events'])==1, 'FAULT_SOURCE_BUSINESS_RESULT')
        require(scalar(consumer_before['exactDocument']['observedCounter'])==-1 and consumer_after['events']==[], 'FAULT_CONSUMER_INITIAL_OR_EMITTED_EVENT')
        if kind=='LIVE':
            require(scalar(source_before['exactDocument']['counter'])==0 and source_before['events']==[]
                    and exact_equal(consumer_before,consumer_after),'FAULT_LIVE_COUNTEREXAMPLE')
        else:
            require(exact_equal(source_before,source_after) and scalar(consumer_after['exactDocument']['observedCounter'])==1,
                    'FAULT_RETAINED_COUNTEREXAMPLE')
        def durable_terminal(state):
            command=sql_command(state,target)
            require(state['selection']['kind']=='NONE' and all(state['sdkDocuments'][a]['readiness']['ready'] is True for a in aliases),
                    'FAULT_RECOVERED_NOT_READY_OR_WORK_DUPLICATED')
            platform=[r for r in tables(state)['mini_platform_event']['rows'] if r['command_id']==target['commandId']]
            require(len(platform)==1 and platform[0]['semantic_key']=='command:'+target['commandId']+':attempt:'+str(command['attempt_count'])+':APPLIED'
                    and platform[0]['command_attempt']==command['attempt_count'],'FAULT_TERMINAL_PLATFORM_EVENT_DUPLICATED')
            public=tables(state)['mini_public_event']['rows']
            source_events=[r for r in public if r['session_id']==captures['source']['sessionId']]
            require(len(source_events)==1 and json.loads(source_events[0]['exact_event_json'])==source_after['events'][0]['exactEvent'], 'FAULT_PUBLIC_SOURCE_EVENT_EXACTLY_ONCE')
            applications=[r for r in tables(state)['mini_managed_epoch_application']['rows'] if r['source_receipt_identity']==target['sourceReceiptIdentity']]
            if kind=='OWNED_RETAINED':
                require(len(applications)==1 and applications[0]['plan_identity']==target['planIdentity']
                        and applications[0]['source_epoch']==1 and applications[0]['consumer_document_id']==ids['consumer'], 'FAULT_DURABLE_RETAINED_APPLICATION')
            else:require(applications==[],'FAULT_LIVE_WRONG_APPLICATION')
        durable_terminal(control_state)
        require(exact_equal(control_state['sdkRecords'],control['duplicate']['sdkRecords'])
                and exact_equal(tables(control_state),tables(control['duplicate'])),'FAULT_CONTROL_DUPLICATE_CHANGED')
        for case in lane['cases']:
            require(case['caseId']==kind+'-'+case['cut'] and case['lane']==kind and case['seedBackupSha256']==seed['backupSha256'],'FAULT_WRONG_SEED_FORK')
            fault=case['fault'];p=process(fault,'CUT');process(case['cold'],'COLD');process(case['recovery'],'RECOVERY');process(case['secondRestart'],'SECOND_RESTART')
            marker=fault['marker'];cut=case['cut']
            require(p['returnCode']==-9 and p['termination']['mechanism']=='OWNED_POPEN_KILL' and p['termination']['signal']==9
                    and p['termination']['pid']==p['pid'] and marker['pid']==p['pid']
                    and marker['processStart']==p['handshake']['processStart'] and marker['processToken']==p['job']['processToken'],'FAULT_NOT_ACTUAL_OWNED_KILL')
            require(marker['target']==target and marker['cut']==cut and marker['registeredWaiter'] is True and marker['waiterCompleted'] is False,'FAULT_WRONG_CUT_OR_WAITER')
            expected_site={'beforeCommit':'TransactionSynchronization.beforeCommit','afterCommitBeforeAck':'TransactionSynchronization.afterCommit',
                           'beforeNotification':'CommandWorkerHook.afterFinalizerReturned','afterCursorWriteAttempt':
                           'CheckpointProjectionRepository.replaceForEpoch:return' if kind=='LIVE' else 'ManagedOccurrenceCatchUpCursorRepository.compareAndSet:return'}[cut]
            require(marker['site']==expected_site and marker['transactionActive'] is (cut!='beforeNotification'),'FAULT_CUT_SITE')
            actual_terminal=invocation(marker['actualInvocation'])
            require(exact_equal(actual_terminal,control_terminal),'FAULT_CONTROL_PROCESSOR_RESULT_DIFFERS')
            cold=case['cold']['record'];require(cold['readOnly'] is True,'FAULT_COLD_NOT_READ_ONLY')
            precommit=cut in ('beforeCommit','afterCursorWriteAttempt')
            expected_tables=marker['actualInvocation']['preFinalizerSql']['tables'] if precommit else tables(marker['atCut'])
            require(exact_equal(cold['sql']['tables'],expected_tables),'FAULT_PARTIAL_OR_LOST_PUBLICATION')
            cold_command=sql_command({'sql':cold['sql']},target)
            require((cold_command['status']=='APPLIED') is (not precommit),'FAULT_COMMAND_COMMIT_BOUNDARY')
            if cut=='beforeCommit':require(sql_command(marker['atCut'],target)['status']=='APPLIED','FAULT_BEFORE_COMMIT_NOT_FULLY_STAGED')
            if cut=='afterCursorWriteAttempt':
                write=marker['actualWrite'];require(write['delegateReturned'] is True and write['replacement'],'FAULT_NO_ACTUAL_CURSOR_WRITE')
                table='mini_checkpoint_projection' if kind=='LIVE' else 'mini_managed_occurrence_catch_up_cursor'
                require(not exact_equal(tables(marker['atCut'])[table],marker['actualInvocation']['preFinalizerSql']['tables'][table]),'FAULT_UNCHANGED_ALLEGED_CURSOR_WRITE')
                if kind=='OWNED_RETAINED':require(write['replacement']['lastSourceReceiptIdentity']==target['sourceReceiptIdentity']
                        and write['replacement']['lastAppliedSourceEpoch']==1 and write['replacement']['nextSourceEpoch']==2,'FAULT_WRONG_CURSOR_SUCCESSOR')
            recovered=case['recovery']['record'];second=case['secondRestart']['record']
            require(len(recovered['actualInvocations'])==(1 if precommit else 0) and second['actualInvocations']==[],'FAULT_TERMINAL_REPROCESSED_OR_RECOVERY_SKIPPED')
            for actual in recovered['actualInvocations']:require(exact_equal(invocation(actual),control_terminal),'FAULT_RETRY_CHANGED_SEMANTICS_OR_GAS')
            for state in (recovered['recovered'],recovered['beforeDuplicate'],recovered['duplicate'],second['recovered']):
                require(state['runtimeWritable'] is True and exact_equal(state['sdkRecords'],control_state['sdkRecords']),'FAULT_RECOVERED_SDK_SEMANTICS')
                durable_terminal(state)
                command=sql_command(state,target)
                require(command['status']=='APPLIED' and command['command_id']==target['commandId']
                        and command['request_json']==sql_command(before,target)['request_json'],'FAULT_REPLACEMENT_COMMAND_OR_REQUEST')
                prefix(before['sdkRecords'],state['sdkRecords'])
                require(all(v['status']==200 for v in state['http'].values()),'FAULT_PUBLIC_READ_FAILED')
            require(exact_equal(tables(recovered['recovered']),tables(recovered['duplicate']))
                    and exact_equal(tables(recovered['duplicate']),tables(second['recovered'])),'FAULT_DUPLICATE_OR_RESTART_DURABLE_ROWS_CHANGED')
            require(exact_equal(recovered['recovered']['http'],recovered['duplicate']['http'])
                    and exact_equal(recovered['duplicate']['http'],second['recovered']['http']),'FAULT_PUBLIC_VIEW_DUPLICATED_OR_CHANGED')
            original_attempt=sql_command({'sql':marker['actualInvocation']['preFinalizerSql']},target)['attempt_count']
            final_command=sql_command(recovered['recovered'],target)
            require(final_command['attempt_count']==original_attempt+(1 if precommit else 0),'FAULT_ATTEMPT_RECOVERY_COUNT')
            require(recovered['duplicateSubmission']['created'] is False,'FAULT_DUPLICATE_CREATED_COMMAND')
    require(len(process_instances)==36,'FAULT_MISSING_FRESH_PROCESS_BOUNDARY')
