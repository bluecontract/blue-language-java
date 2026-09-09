"""RUN027: exact source-owned continuation, immutable input, metering and durable replay.

Counts come from the actual frozen capture descriptors and their typed next-step
chain. A missing resource before processor admission has no invented terminal.
"""
import base64,hashlib,json,struct
from pathlib import Path
from resource_admission_bindings import check_admission_inputs
from resource_followup_bindings import check_followup, timeline_identity

def check_resource_continuation(rec,rule,weights,require,exact_equal,gas_check):
    from rooted_graph_checks import graph_checks
    import sys
    suite=Path(gas_check.__globals__['__file__']).resolve().parent
    sys.path.insert(0,str(suite/'identity'));import constructors as I
    host=rec['host'];h=rec['output']['resourceContinuation'];states=h['states'];captures=host['captures']
    require(exact_equal(host['output'],rec['output']) and host['executionStatus']=='COMPLETED'
            and host['productionStatus']=='OBSERVATION_ONLY','RESOURCE_ACTUAL_HOST_COMPLETION')
    require(rule['commandSeconds']==45 and rule['sourceWaitSeconds']==45
            and rule['restartBoundary']=='MYOS_RUNTIME_REBUILDER','RESOURCE_BOUNDS_OR_BOUNDARY')
    require(rec['controller']['returnCode']==0 and rec['controller']['childPid']==host['processPid']
            and host['planSha256']==rec['controller']['planSha256'],'RESOURCE_PROCESS_BINDING')
    require(host['executionPolicy']=='release-default' and int(host['localPort'])>0
            and host['actualDatabaseUrl'].startswith('jdbc:h2:file:'+host['workDirectory']+'/realm'),'RESOURCE_ISOLATION')
    binding=host['applicationBinding']
    require(binding['sha256']==rec['artifactSha256'] and host['sourceLockSha256']==rec['sourceLockSha256']
            and binding['applicationEntries'] and binding['nestedDependencies'],'RESOURCE_ARTIFACT_BINDING')
    require(host['sourceLock']['sourceCommits']==rec['sourceCommits'] and host['sourceLock']['artifactSha256']==rec['artifactSha256']
            and host['sourceLock']['dependencies']==rec['dependencies'] and host['sourceLock']['specificationSetSha256']==rec['specificationSetSha256'],'RESOURCE_EXACT_SOURCE_LOCK')
    for r in binding['applicationEntries']:require(r['packagedSha256']==r['loadedSha256'],'RESOURCE_SHADOWED_APPLICATION')
    require({r['entry']:r['packagedSha256'] for r in binding['nestedDependencies']}==rec['dependencies']
            and all(r['packagedSha256']==r['classpathSha256'] for r in binding['nestedDependencies']),'RESOURCE_DEPENDENCY_BINDING')
    sources={name:(suite/name).read_bytes() for name in rule['sourceFiles']}
    require(set(host['sourceInputs'])==set(sources),'RESOURCE_SOURCE_INVENTORY')
    for name,data in sources.items():
        observed=host['sourceInputs'][name]
        require(base64.b64decode(observed['sourceBytes'])==data and observed['sha256']==hashlib.sha256(data).hexdigest(),'RESOURCE_LITERAL_SOURCE_BYTES')
    require(all(r['completed'] is True for r in rec['transcript']),'RESOURCE_INCOMPLETE_TRANSCRIPT')
    require([r['request'] for r in rec['transcript']]==[r['literal'] for r in host['transcript']],'RESOURCE_LITERAL_TRANSCRIPT')
    awaits=[r['request'] for r in rec['transcript'] if r['request']['op']=='hostAwait']
    require(len(awaits)==1 and awaits[0]['awaitReadiness'] is True,'RESOURCE_EXPLICIT_READY_OBSERVATION')
    names=('beforeOperation','blocked','pendingRestart','wrongUpload','applied','duplicate','completedRestart')
    require(set(states)==set(names),'RESOURCE_STAGE_INVENTORY')
    base,blocked,pending,wrong,applied,duplicate,restarted=[states[n] for n in names]
    ids=h['documentIds'];root=ids['host'];child=ids['child'];missing=ids['missing'];original=h['blockedCommandId']
    require(set(ids)=={'host','child','missing'} and len(set(ids.values()))==3,'RESOURCE_DOCUMENT_IDENTITIES')
    require(root==captures['H']['documentId'] and child==captures['child']['documentId']
            and missing==captures['missing']['blueId']==h['preparedIdentity']['blueId'],'RESOURCE_IDENTITY_CAPTURES')
    require(captures['child']['authoredYaml']==sources['examples/iteration2/resource-child.template.yaml'].decode().replace('<MISSING_BLUE_ID>',missing),'RESOURCE_CHILD_SOURCE')
    require(captures['missing']['authoredYaml']==sources['examples/iteration2/resource-correct.yaml'].decode()
            and captures['wrong']['authoredYaml']==sources['examples/iteration2/resource-wrong.yaml'].decode()
            and captures['wrong']['blueId']!=missing,'RESOURCE_DISTINCT_UPLOADS')
    require(set(base['sdkRecords'])=={root} and set(base['semantic']['sdkDocuments'])=={root}
            and base['liveRetainedMissing'] is None and base['exactContent']==[],'RESOURCE_SOURCE_NOT_PRESTARTED_OR_WARMED')
    require(base['sdkRecords'][root]['exactDocument']['children']=={},'RESOURCE_EMPTY_ORIGINAL_COLLECTION')
    def commands(stage):
        out={r['commandId']:r for r in stage['commands']};require(len(out)==len(stage['commands']),'RESOURCE_DUPLICATE_COMMAND');return out
    before_commands=commands(base);wait_commands=commands(blocked);done=commands(applied)
    require(len(before_commands)==2 and sorted(r['commandType'] for r in before_commands.values())==['CREATE_TIMELINE','START_DOCUMENT'],'RESOURCE_REAL_SETUP')
    require(all(r['status']=='APPLIED' for r in before_commands.values()),'RESOURCE_SETUP_NOT_APPLIED')
    require(wait_commands[original]['commandType']==done[original]['commandType']=='EXECUTE_OPERATION'
            and wait_commands[original]['status']=='BLOCKED' and wait_commands[original]['errorCode']=='NEEDS_RESOURCES'
            and done[original]['status']=='APPLIED','RESOURCE_ORIGINAL_TYPED_WAIT_AND_RESUME')
    immutable=('commandId','globalSequence','idempotencyKey','commandType','requestJson','createdAt')
    for field in immutable:require(wait_commands[original][field]==done[original][field],'RESOURCE_ORIGINAL_CHANGED_'+field)
    observations=h['commandObservations'];flat=host['actualProcessorObservations']
    require(flat and all('captureFailure' not in row for row in flat),'RESOURCE_CAPTURE_FAILURE')
    require(exact_equal(observations,{key:[r for r in flat if r['command']['commandId']==key] for key in observations})
            and {r['command']['commandId'] for r in flat}==set(observations),'RESOURCE_OBSERVATION_INVENTORY')
    initial=observations[original][0];last=observations[original][-1]
    expected_captures=initial['actualOutcome']['sourceHistoryCaptures']
    require(expected_captures and len({r['selection']['sourceDocumentId'] for r in expected_captures})==len(expected_captures),'RESOURCE_FROZEN_CAPTURE_SET')
    frozen=expected_captures[0];entry=json.loads(frozen['entryJson']);request=json.loads(frozen['requestJson']);entry_id=frozen['entryBlueId']
    require(request['child']['peer']=={'blueId':missing} and request['child']['name']=='dynamic child waiting for exact content','RESOURCE_EXACT_MISSING_INPUT')
    def scalar(v):return v['value'] if isinstance(v,dict) and 'value' in v else v
    timeline_label=scalar(entry['timeline']['timelineId'])
    cutoff=[scalar(entry['timestamp']),timeline_identity(entry['timeline'],require),entry_id]
    require(timeline_label=='labs/dynamic-resource/alice' and scalar(entry['actor']['accountId'])=='alice'
            and scalar(entry['message']['operation'])=='attach' and scalar(entry['message']['channel'])=='ownerChannel'
            and entry['message']['document']['blueId']==base['sdkRecords'][root]['blueId'],'RESOURCE_EXACT_ORIGINAL_ENVELOPE')
    # The exact transport can use its correctly bound pure request reference.
    require(entry['message']['request']=={'blueId':frozen['requestBlueId']} or entry['message']['request']==request,'RESOURCE_ENTRY_REQUEST_BINDING')
    for row in expected_captures:
        require(row['schemaVersion']==1 and row['originalCommandId']==original
                and row['originalRequestJson']==done[original]['requestJson']
                and all(row[k]==frozen[k] for k in ('entryBlueId','entryJson','requestBlueId','requestJson')),'RESOURCE_CAPTURE_ORIGINAL_BYTES')
    def selection(sel,anchor=None):
        require(sel['requestingRoot']==root and sel['cutoffExclusive']==cutoff and sel['sourceDocumentId']==child
                and sel['authoredBlueId']==child and sel['sourceEpoch']==-1 and sel['sourceBlueId']==child
                and sel['kind']=='ADMISSION' and sel['entryBlueId'] is None and sel['diagnostic'] is None,'RESOURCE_SOURCE_ADMISSION_AUTHORITY')
        if anchor is not None:
            require(all(sel[k]==anchor[k] for k in ('requestingInvocationIdentity','demandIdentity','requestingRoot','cutoffExclusive','sourceDocumentId','authoredBlueId')),'RESOURCE_FROZEN_SOURCE_AUTHORITY')
        fields=[root,sel['requestingInvocationIdentity'],sel['demandIdentity'],child,child,sel['kind'],str(sel['sourceEpoch']),sel['sourceBlueId'],sel['workIdentity'],sel['entryBlueId'] or '',str(sel['journalRevision']),str(sel['routeGeneration']),sel['sourceSurfaceIdentity'],sel['diagnostic'] or '']
        digest=hashlib.sha256()
        for value in ['blue.coordination/source-history-prerequisite/1']+fields+[str(v) for v in cutoff]:
            b=value.encode();digest.update(struct.pack('>I',len(b)));digest.update(b)
        require(sel['selectionIdentity']=='sha256:'+digest.hexdigest(),'RESOURCE_SELECTION_IDENTITY')
    classification=initial['actualClassification'];require(classification and classification['demands'],'RESOURCE_PARENT_TYPED_DEMAND')
    for cap in expected_captures:
        selection(cap['selection'])
        matching=[d for d in classification['demands'] if d['demandIdentity']==cap['selection']['demandIdentity']]
        require(len(matching)==1 and matching[0]['kind']=='MANAGED_OCCURRENCE_EVIDENCE'
                and matching[0]['blueId']==child and matching[0]['sourceDocumentId']==root
                and matching[0]['sourcePath']=='/children/one','RESOURCE_CAPTURE_ACTUAL_DEMAND')
    capture_commands={k:v for k,v in done.items() if v['commandType']=='SOURCE_HISTORY_CAPTURE'}
    require(len(capture_commands)==len(expected_captures),'RESOURCE_CAPTURE_MULTIPLICITY')
    required_commands=set(before_commands)|{original};source_commands=[]
    replay=[r['commandId'] for r in applied['appliedReplayOrder']]
    require(len(replay)==len(set(replay)) and set(replay)==set(done),'RESOURCE_APPLIED_REPLAY_INVENTORY')
    for capture_id,command in capture_commands.items():
        cap=json.loads(command['requestJson']);require(cap in expected_captures,'RESOURCE_UNREVIEWED_CAPTURE')
        require(command['status']=='APPLIED' and command['idempotencyKey']=='source-history-capture:'+original+':'+cap['selection']['sourceDocumentId'],'RESOURCE_CAPTURE_COMMAND_IDENTITY')
        required_commands.add(capture_id);result=json.loads(command['resultJson'])['result']
        require(result['kind']=='SOURCE_HISTORY_CAPTURE' and result['originalCommandId']==original
                and result['parentDisposition']=='NEEDS_RESOURCES' and result['sourceReady'] is False
                and result['selection']==cap['selection'] and result['nextSourceSelection']==cap['selection'],'RESOURCE_CAPTURE_RETAINED_PROOF')
        previous=capture_id;next_selection=result['nextSourceSelection'];visited=set()
        while next_selection is not None:
            selection(next_selection,cap['selection'])
            matches=[(k,v) for k,v in done.items() if v['commandType']=='SOURCE_HISTORY_EXECUTE'
                     and json.loads(v['requestJson'])=={'schemaVersion':1,'originalCommandId':original,'captureCommandId':capture_id,'selection':next_selection}]
            require(len(matches)==1,'RESOURCE_EXACT_SOURCE_STEP_MULTIPLICITY');source_id,source=matches[0]
            require(source_id not in visited,'RESOURCE_SOURCE_CONTINUATION_LOOP');visited.add(source_id)
            require(source['status']=='APPLIED' and source['idempotencyKey']=='source-history-execute:'+next_selection['selectionIdentity']
                    and replay.index(previous)<replay.index(source_id)<replay.index(original),'RESOURCE_SOURCE_COMPLETION_ORDER')
            required_commands.add(source_id);source_commands.append(source_id)
            source_result=json.loads(source['resultJson'])['result']
            require(source_result['kind']=='SOURCE_HISTORY_EXECUTE' and source_result['originalCommandId']==original
                    and source_result['captureCommandId']==capture_id and source_result['sourceReceipt']['selection']==next_selection
                    and source_result['sourceStepComplete'] is True,'RESOURCE_SOURCE_RESULT_AUTHORITY')
            require(source_result['sourceReady'] is True,'RESOURCE_NO_EXTRA_HISTORY_FOR_TIMELESS_SOURCE')
            require('nextSourceSelection' not in source_result,'RESOURCE_CONTRADICTORY_SOURCE_READY');next_selection=None;previous=source_id
    followups={k:v for k,v in done.items() if v['commandType']=='DRAIN_PROCESSING'}
    require(len(followups)==1 and not set(followups).intersection(wait_commands),'RESOURCE_ONE_SUBSEQUENT_RETAINED_COMMAND')
    followup_id,followup_command=next(iter(followups.items()))
    require(replay.index(original)<replay.index(followup_id),'RESOURCE_RETAINED_REPLAY_ORDER')
    require(set(wait_commands)==required_commands and set(done)==required_commands|set(followups),'RESOURCE_UNACCOUNTED_COMMAND')
    required_commands.update(followups)
    require(source_commands and set(source_commands)=={r['commandId'] for r in captures['sourceWait']},'RESOURCE_ACTUAL_BLOCKED_SOURCE_SET')
    for key in required_commands-set(before_commands):
        rows=observations[key]
        retained=json.loads(done[key]['resultJson'])
        require(retained['replayEvidence']==rows[-1]['actualReplayEvidence'] and isinstance(retained['replayEvidence']['exactProviderReads'],list),'RESOURCE_PROVIDER_REPLAY_LEDGER')
        require(len(rows)==done[key]['attemptCount'] and [r['command']['attemptCount'] for r in rows]==list(range(1,len(rows)+1)),'RESOURCE_MISSING_OR_DUPLICATE_HOST_ATTEMPT')
        for row in rows:
            require(all(row['command'][k]==done[key][k] for k in immutable),'RESOURCE_ATTEMPT_CHANGED_LOGICAL_COMMAND')
    require(initial['actualClassification'] is not None and last['actualClassification'] is None
            and last['actualOutcome']['continuedOriginalCommandId']==original
            and last['actualOutcome']['originalEntryBlueId']==entry_id,'RESOURCE_CONTINUED_SAVED_ENTRY')
    for key in [original,*capture_commands]:
        for row in observations[key]:
            require(len(row['actualEntries'])==1,'RESOURCE_ENTRY_INVENTORY')
            actual=row['actualEntries'][0]
            require(actual['blueId']==entry_id and actual['exact']==entry and actual['request']==request
                    and actual['timestampMicros']==cutoff[0] and actual['timeline']['id']==timeline_label,'RESOURCE_ENTRY_RECREATED')
    def tables(name):return h['rawSqlSnapshots'][name]['tables']
    dependencies=tables('blocked')['mini_source_history_dependency']['rows']
    completed=tables('applied')['mini_source_history_dependency']['rows']
    expected=[{'original_command_id':original,'capture_command_id':key,'source_document_id':json.loads(row['requestJson'])['selection']['sourceDocumentId'],'completed':False} for key,row in capture_commands.items()]
    sort=lambda xs:sorted(xs,key=lambda x:x['capture_command_id'])
    require(sort(dependencies)==sort(expected) and sort(completed)==sort([dict(r,completed=True) for r in expected]),'RESOURCE_DEPENDENCY_FENCE')
    for stage in (blocked,pending,wrong):
        require(stage['sdkRecords']==base['sdkRecords'] and stage['liveRetainedMissing'] is None
                and stage['exactContent']==[],'RESOURCE_PREMATURE_MANAGED_PUBLICATION')
        require(stage['semantic']['sqlDocuments']==base['semantic']['sqlDocuments']
                and stage['semantic']['sqlEpochs']==base['semantic']['sqlEpochs']
                and stage['semantic']['sqlOccurrences']==base['semantic']['sqlOccurrences']
                and stage['semantic']['sqlEntryResults']==[],'RESOURCE_PREMATURE_DURABLE_RESULT')
        require(all(stage['commands'][i]['status'] in ('APPLIED','BLOCKED') for i in range(len(stage['commands']))),'RESOURCE_NOT_STABLE_WAIT')
    require(blocked==pending and pending==wrong,'RESOURCE_PENDING_REPLAY_OR_WRONG_UPLOAD_CHANGED_STATE')
    require(applied==duplicate and applied==restarted,'RESOURCE_DUPLICATE_OR_REPLAY_CHANGED_STATE')
    for stage in states.values():
        require(stage['runtimeWritable'] is True and stage['http'],'RESOURCE_HOST_NOT_WRITABLE')
        for response in stage['http'].values():require(response['status']==200,'RESOURCE_PUBLIC_READ_FAILED')
    public=applied['http'][root]['body']
    require(public['ready'] is True and public['projectedCurrent']['children']['one']['peer']['state']=='unavailable-here','RESOURCE_FINAL_PUBLIC_VALUE_OR_READINESS')
    # A rejected or repeated upload may record its own host event. No other SQL
    # table may change, and the event is independently bound to that upload.
    for a,b in (('blocked','pendingRestart'),('pendingRestart','wrongUpload'),('applied','duplicate'),('duplicate','completedRestart')):
        old,new=tables(a),tables(b);require(set(old)==set(new),'RESOURCE_SQL_TABLE_INVENTORY')
        changed=[name for name in old if old[name]!=new[name]]
        allowed=[] if (a,b) in (('blocked','pendingRestart'),('duplicate','completedRestart')) else ['mini_platform_event']
        require(set(changed)<=set(allowed),'RESOURCE_UNEXPECTED_SQL_MUTATION')
        if changed:
            before=old['mini_platform_event'];after=new['mini_platform_event']
            require(before['columns']==after['columns'] and before['primaryKeys']==after['primaryKeys'],'RESOURCE_SQL_EVENT_SCHEMA')
            retained={json.dumps(r,sort_keys=True) for r in before['rows']};added=[r for r in after['rows'] if json.dumps(r,sort_keys=True) not in retained]
            require(retained<={json.dumps(r,sort_keys=True) for r in after['rows']} and len(added)==1,'RESOURCE_UPLOAD_EVENT_COUNT')
            event=added[0];require(event['event_type'].startswith('content.retention.') and event['command_id'] is None,'RESOURCE_UNRELATED_UPLOAD_EVENT')
            payload=json.loads(event['compact_payload_json'])
            if b=='wrongUpload':require(payload['code']==rule['wrongUploadCode'] and payload['expectedBlueId']==missing,'RESOURCE_UPLOAD_REJECTION_EVENT')
    before_demands={r['demandId']:r for r in blocked['demands']};after_demands={r['demandId']:r for r in applied['demands']}
    require(before_demands and set(before_demands)==set(after_demands),'RESOURCE_DEMAND_INVENTORY')
    exact_demands=[]
    for key,old in before_demands.items():
        new=after_demands[key];require(old['status']=='OPEN' and new['status']=='SATISFIED' and new['satisfiedAt'] is not None,'RESOURCE_DEMAND_NOT_COMPLETED')
        require({k:v for k,v in old.items() if k not in ('status','satisfiedAt')}=={k:v for k,v in new.items() if k not in ('status','satisfiedAt')},'RESOURCE_DEMAND_REWRITTEN')
        if old['kind']=='EXACT_BLUE_NODE':
            require(old['commandId'] in source_commands and old['blueId']==missing and old['sourceDocumentId']==child and old['absolutePath']=='/peer','RESOURCE_MISSING_BODY_OWNER');exact_demands.append(old)
        else:
            require(old['kind']=='MANAGED_OCCURRENCE_EVIDENCE' and old['commandId']==original
                    and json.loads(old['detailsJson'])['coordinationDemandIdentity'] in {c['selection']['demandIdentity'] for c in expected_captures},'RESOURCE_PARENT_DEMAND_OWNER')
    require(exact_demands,'RESOURCE_NO_ACTUAL_MISSING_BODY_WAIT')
    failure=h['wrongUploadException'];require(failure['code']==rule['wrongUploadCode'] and failure['details']['expectedBlueId']==missing
            and failure['details']['actualBlueId']==captures['wrong']['blueId'],'RESOURCE_WRONG_UPLOAD_ACCEPTED')
    require(set(h['correctUpload']['queuedCommandIds'])=={d['commandId'] for d in exact_demands}
            and set(h['correctUpload']['satisfiedDemandIds'])=={d['demandId'] for d in exact_demands},'RESOURCE_UPLOAD_WOKE_WRONG_OWNER')
    require(h['duplicateUpload']['queuedCommandIds']==[] and h['duplicateUpload']['satisfiedDemandIds']==[]
            and h['duplicateSubmission']['created'] is False and h['duplicateSubmission']['command']['commandId']==original,'RESOURCE_DUPLICATE_CREATED_WORK')
    stored=[r for r in applied['exactContent'] if r['blueId']==missing]
    require(len(stored)==1 and stored[0]['canonicalJson']==captures['missing']['canonicalJson'] and applied['liveRetainedMissing'] is not None,'RESOURCE_WRONG_RETAINED_BODY')
    for name,rebuild in h['rebuilds'].items():
        require(rebuild['boundary']==rule['restartBoundary'] and rebuild['generationAfter']==rebuild['generationBefore']+1,'RESOURCE_REPLAY_BOUNDARY')
    counts=h['processorObservationCounts'];require(counts['blocked']==counts['pendingRestart']==counts['wrongUpload']
            and counts['applied']==counts['duplicate']==counts['completedRestart'],'RESOURCE_UNREQUESTED_EXECUTION')
    final_records=applied['sdkRecords'];aliases=list(final_records);mapping={a:a for a in aliases}
    starts={a:{'epoch0Receipt':r['receipts'][0],'retained':{'historyBasis':r['historyBasis']}} for a,r in final_records.items()}
    did,scalar,by_id,prefix,sccs,snapshot_check,trace_check=graph_checks(aliases,mapping,starts,weights,require,exact_equal,gas_check)
    prefix(final_records,final_records)
    def preserved(old,new):
        require(set(old)<=set(new),'RESOURCE_LOST_DOCUMENT')
        for key,r in old.items():
            require(r['historyBasis']==new[key]['historyBasis'] and r['receipts']==new[key]['receipts'][:len(r['receipts'])]
                    and r['events']==new[key]['events'][:len(r['events'])],'RESOURCE_REWRITTEN_HISTORY')
    def result_check(inp,result,gas,trace,snapshot):
        require(result['status']=='SUCCESS' and result['commits'] is True and result['rollbackToInput'] is False,'RESOURCE_PROCESSOR_NOT_COMMITTED')
        companion=result['platformCommitCompanion'];require(companion is not None,'RESOURCE_MISSING_COMPANION')
        require(inp['executionPolicy']['sharedLimit']==100000,'RESOURCE_CHANGED_DEFAULT_BUDGET')
        trace_check(gas,trace,inp['executionPolicy']['sharedLimit'])
        require(trace==result['gasTrace'] and gas['total']==result['totalGas'] and trace,'RESOURCE_ACTUAL_GAS_BINDING')
        require(companion['invocationIdentity']==inp['invocationIdentity']==result['invocationIdentity']
                and companion['inputClosureIdentity']==inp['snapshot']['closureIdentity']==result['inputClosureIdentity']
                and companion['outputClosureIdentity']==result['outputClosureIdentity'],'RESOURCE_BASE_COMMIT_BINDING')
        snapshot_check(inp['snapshot']);snapshot_check(snapshot)
        require(snapshot['closureIdentity']==result['outputClosureIdentity'] and snapshot['occurrences']==result['occurrenceBindings'],'RESOURCE_RESULT_SNAPSHOT')
        for transition in result['managedTransitionReceipts']:
            require(transition['sourceInvocationIdentity']==inp['invocationIdentity'],'RESOURCE_TRANSITION_INVOCATION')
            matches=[d for d in result['resultingDocuments'] if d['documentId']==transition['documentId']]
            require(len(matches)==1 and all(matches[0][f]==transition[f] for f in ('beforeBlueId','afterBlueId')),'RESOURCE_TRANSITION_EXACT_STATES')
        return companion
    admitted=set();successful_admissions=0
    for key in source_commands:
        rows=observations[key];require(wait_commands[key]['status']=='BLOCKED' and wait_commands[key]['errorCode']=='NEEDS_RESOURCES','RESOURCE_SOURCE_NOT_BLOCKED')
        for row in rows:
            require(row['actualEntries']==[] and row['actualEntryResults']==[] and len(row['sourceAdmissions'])==1,'RESOURCE_SOURCE_NO_BUSINESS_INPUT')
            admission=row['sourceAdmissions'][0];source=row['actualOutcome']['sourceReceipt'];sel=source['selection'];selection(sel)
            require(sel==json.loads(done[key]['requestJson'])['selection'] and source['publicationIdentity']==admission['publicationIdentity'],'RESOURCE_SOURCE_OBSERVATION_BINDING')
            require(row['beforeRecords'][root]==row['afterRecords'][root],'RESOURCE_SOURCE_PUBLISHED_PARENT')
            if row['actualClassification'] is not None:
                require(admission['complete'] is False and admission['published'] is False and admission['result'] is None
                        and admission['totalGas'] is None and source['attempt']['totalGas'] is None
                        and source['attempt']['processResult'] is None and row['beforeRecords']==row['afterRecords']
                        and row['actualClassification']['gasUsed']==0,'RESOURCE_RESOURCE_WAIT_PARTIAL_PUBLICATION')
                require(admission['resourceDemands'] and missing in admission['requiredExactBlueIds'],'RESOURCE_UNTYPED_SOURCE_WAIT')
                continue
            successful_admissions+=1;require(admission['complete'] is True and admission['published'] is True,'RESOURCE_SOURCE_NOT_ADMITTED')
            inp=check_admission_inputs(admission,sel,child,missing,request['child'],
                json.loads(captures['missing']['canonicalJson']),last['actualDrain']['terminals'][0]['input']['environment'],require,exact_equal)
            result=admission['result']
            docs={did(d) for d in admission['documentIds']};require(child in docs and docs<={child,missing} and root not in docs and not admitted.intersection(docs),'RESOURCE_SOURCE_ADMISSION_INVENTORY')
            admitted.update(docs);require(set(row['afterRecords'])-set(row['beforeRecords'])==docs,'RESOURCE_UNBOUND_ADMITTED_DOCUMENT')
            require(set(admission['selectedSnapshots'])==docs,'RESOURCE_ADMISSION_SNAPSHOT_INVENTORY')
            input_documents=by_id(inp['snapshot']['managedDocuments'])
            require(child in input_documents and input_documents[child]['blueId']==child
                    and input_documents[child]['document']==request['child'],'RESOURCE_ORIGINAL_SOURCE_ADMISSION_BYTES')
            for view in admission['selectedSnapshots'].values():result_check(inp,result,admission['gas'],admission['fullGasTrace'],view)
            preserved(row['beforeRecords'],row['afterRecords'])
            for document in docs:
                record=row['afterRecords'][document];require(record['epoch']==0 and len(record['receipts'])==1 and record['events']==[],'RESOURCE_DUPLICATE_SOURCE_INITIALIZATION')
                receipt=record['receipts'][0];matches=[t for t in result['managedTransitionReceipts'] if t['transitionReceiptIdentity']==receipt['contractsTransitionReceiptIdentity']]
                require(len(matches)==1 and did(matches[0]['documentId'])==document and receipt['commitCompanionIdentity']==result['platformCommitCompanion']['companionIdentity']
                        and receipt['kind']=='INITIALIZATION' and receipt['beforeBlueId'] is None
                        and matches[0]['beforeBlueId']==document and receipt['afterBlueId']==matches[0]['afterBlueId'],'RESOURCE_ADMISSION_HOST_RECEIPT')
                require(record==final_records[document],'RESOURCE_PARENT_REWROTE_SOURCE')
    require(successful_admissions==len(source_commands) and set(final_records)=={root}|admitted,'RESOURCE_SOURCE_MULTIPLICITY')
    drain=last['actualDrain'];require(drain['resourceFailures']==[]
            and len(drain['entries'])==1 and drain['entries'][0]['entry']['blueId']==entry_id
            and drain['entries'][0]['disposition']=='APPLIED' and drain['terminals'],'RESOURCE_ORIGINAL_NOT_FINISHED')
    require(len(observations[followup_id])==1,'RESOURCE_DUPLICATE_RETAINED_ATTEMPT')
    drains=check_followup(last,observations[followup_id][0],followup_command,root,child,cutoff,final_records,require)
    terminals=[terminal for step in drains for terminal in step['terminals']]
    for terminal in terminals:
        require(terminal['status']=='SUCCESS' and terminal['rollbackToInput'] is False,'RESOURCE_FAILED_ORIGINAL_TERMINAL')
        inp=terminal['input'];projection=terminal['rootedProjection'];companion=terminal['commitCompanion']
        trace_check(terminal['gas'],terminal['fullGasTrace'],inp['executionPolicy']['sharedLimit'])
        require(inp['executionPolicy']['sharedLimit']==100000 and terminal['invocationIdentity']==inp['invocationIdentity']==companion['invocationIdentity'],'RESOURCE_ORIGINAL_COMMIT_INPUT')
        snapshot_check(inp['snapshot']);snapshot_check(terminal['outputSnapshot'])
        require(companion['inputClosureIdentity']==inp['snapshot']['closureIdentity'] and companion['outputClosureIdentity']==terminal['outputSnapshot']['closureIdentity'],'RESOURCE_ORIGINAL_COMMIT_SNAPSHOTS')
        require([did(v) for v in terminal['ownedDocumentIds']]==[root] and terminal['ownedPublicEvents']==[],'RESOURCE_ROOT_ONLY_PUBLICATION')
        descriptor=terminal['rootedContext'];context_id=descriptor['contextIdentity'] if 'contextIdentity' in descriptor else None
        expected,context_id=I.context({'members':[{'documentId':root,'historyBasisIdentity':I.history(base['sdkRecords'][root]['historyBasis'])}],'internalEdges':[]})
        require(descriptor==expected and projection['invocationIdentity']==I.wrapper('rootedInvocationIdentity',{'baseInvocationIdentity':inp['invocationIdentity'],'rootProcessingContextIdentity':context_id,'deliveryBasisIdentity':projection['deliveryBasisIdentity']})
                and projection['companionIdentity']==I.wrapper('rootedCommitCompanionIdentity',{'baseCommitCompanionIdentity':companion['companionIdentity'],'rootedInvocationIdentity':projection['invocationIdentity'],'rootProcessingContextIdentity':context_id}),'RESOURCE_ROOTED_AUTHORITY')
        require(projection['inputSnapshot']==inp['snapshot'] and projection['resultingSnapshot']==terminal['outputSnapshot'],'RESOURCE_ROOTED_SNAPSHOT_BINDING')
        if terminal['causeType']=='ExternalEventCause':require(inp['cause']['eventBlueId']==entry_id,'RESOURCE_CHANGED_ORIGINAL_LIVE_ENTRY')
        else:
            require(terminal['causeType']=='ManagedRevisionCause','RESOURCE_UNEXPECTED_HISTORY_CAUSE')
            cause=inp['cause'];source=final_records[did(cause['childDocumentId'])]['receipts'][cause['toEpoch']]
            require(cause['fromEpoch']+1==cause['toEpoch'] and cause['sourceRevisionReceiptIdentity']==source['contractsTransitionReceiptIdentity']
                    and cause['sourceTransitionReceipt']['transitionReceiptIdentity']==source['contractsTransitionReceiptIdentity']
                    and cause['beforeBlueId']==cause['sourceTransitionReceipt']['beforeBlueId']==child
                    and source['epoch']==0 and source['beforeBlueId'] is None
                    and cause['afterBlueId']==source['afterBlueId'],'RESOURCE_HISTORY_SOURCE_RECEIPT')
    for step in drains:preserved(step['before'],step['after'])
    require(drains[-1]['after']==final_records,'RESOURCE_FINAL_DRAIN_RECORDS')
    appended=final_records[root]['receipts'][len(base['sdkRecords'][root]['receipts']):]
    require(appended,'RESOURCE_ROOT_DID_NOT_ADVANCE')
    for receipt in appended:
        matches=[(terminal,t) for terminal in terminals for t in terminal['managedTransitionReceipts']
                 if t['transitionReceiptIdentity']==receipt['contractsTransitionReceiptIdentity']]
        require(len(matches)==1,'RESOURCE_ROOT_RECEIPT_PUBLICATION')
        terminal,t=matches[0];cause=terminal['input']['cause']
        original_cause=cause['causeIdentity'] if terminal['causeType']=='ExternalEventCause' else cause['originalSourceCauseIdentity']
        require(did(t['documentId'])==root and receipt['commitCompanionIdentity']==terminal['commitCompanion']['companionIdentity']
                and receipt['originalCauseIdentity']==t['originalCauseIdentity']==original_cause
                and receipt['beforeBlueId']==t['beforeBlueId'] and receipt['afterBlueId']==t['afterBlueId'], 'RESOURCE_ROOT_EXACT_RECEIPT_BINDING')
    require(final_records[root]['receipts'][:len(base['sdkRecords'][root]['receipts'])]==base['sdkRecords'][root]['receipts'],'RESOURCE_ROOT_HISTORY_PREFIX')
    require(all(r['events']==[] for r in final_records.values()),'RESOURCE_DUPLICATE_SOURCE_OR_INITIALIZATION_EVENTS')
    entries=applied['semantic']['sqlEntries'];results=applied['semantic']['sqlEntryResults'];journal=applied['semantic']['sdkJournal']
    require(len(entries)==len(results)==len(journal)==1 and entries[0]['entryBlueId']==journal[0]['blueId']==entry_id,'RESOURCE_DUPLICATE_DURABLE_ENTRY')
    require(journal[0]['exact']==entry and journal[0]['request']==request and json.loads(entries[0]['exactEntryJson'])==entry
            and entries[0]['commandId']==results[0]['commandId']==original and results[0]['entryBlueId']==entry_id
            and entries[0]['disposition']==results[0]['disposition']=='APPLIED','RESOURCE_FINAL_ENTRY_BYTES')
    return len(required_commands)+len(flat)+len(terminals)
