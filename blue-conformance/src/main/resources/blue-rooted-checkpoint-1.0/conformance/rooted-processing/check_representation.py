"""RUN017: independent exact-position oracle over real SDK publications and validator calls."""
import hashlib
import json
from identity import constructors as I
from identity import checkpoint_constructors as C
from rooted_graph_checks import graph_checks
from check_successor_carriers import check_call_carriers

def root_scope_identity(document_id):
    value={'domain':'blue-contracts-managed-scope-key/1.0','value':{
        'documentId':document_id,'scopePath':'/','activationGeneration':0}}
    return 'sha256:'+hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()


def check_representation(rec, contract, weights, variant, require, exact_equal, gas_check):
    rule = contract['representationHistory']; phases = rec['phaseRecords']; tr = rec['transcript']
    require(variant in rule['variants'] and all(row['completed'] for row in tr), 'REP_LITERAL_COMPLETION')
    starts = {row['request']['alias']: row['response'] for row in tr if row['request']['op'] == 'start'}
    ids = rec['documentIds']; aliases = list(starts)
    require(set(ids) == set(starts) and len(set(ids.values())) == len(ids), 'REP_EXACT_LINEAGES')
    for alias, start in starts.items():
        require(start['initialBlueId'] == start['documentId'] == ids[alias]
                and start['initialBlueId'] != start['initializedBlueId'], 'REP_SAVED_AUTHORED_ID')
    did, scalar, by_id, receipt_prefix, sccs, snapshot_check, trace_check = graph_checks(
        aliases, ids, starts, weights, require, exact_equal, gas_check)
    alias_of = {v:k for k,v in ids.items()}
    appends = {row['request']['capture']:row for row in tr if row['request']['op'] == 'append'}
    final = phases['final']; require(exact_equal(rec['output']['representationHistory'], final), 'REP_FINAL_BINDING')

    def rejection(value):
        require(value['returnedNormally'] is False and value['exceptionClass'] == 'java.lang.IllegalArgumentException'
                and isinstance(value['message'],str) and value['message'], 'REP_RUNTIME_NEGATIVE_NOT_REJECTED')
        require(exact_equal(value['before'],value['after']) and value['before']['records']
                and value['before']['publicationKeys'] and value['before']['journal'], 'REP_REJECTION_PUBLICATION_OR_HISTORY_CHANGED')

    def accepted(value):
        require(value['returnedNormally'] is True and exact_equal(value['before'],value['after']), 'REP_VALID_AUTHORITY_REJECTED_OR_MUTATED')

    def terminal(t):
        require(t['status'] == 'SUCCESS' and t['rollbackToInput'] is False, 'REP_TERMINAL_STATUS')
        inp, companion, projection = t['input'], t['commitCompanion'], t['rootedProjection']
        before, after = snapshot_check(inp['snapshot']), snapshot_check(t['outputSnapshot'])
        scope_ids={root_scope_identity(document) for document in before}
        require(all(w['targetManagedScopeIdentity'] in scope_ids for w in t['checkpointWrites']),
                'REP_CHECKPOINT_SCOPE_IDENTITY')
        trace_check(t['gas'],t['fullGasTrace'],inp['executionPolicy']['sharedLimit'])
        require(t['gas']['charges'] and t['gas']['total'] > 0, 'REP_ACTUAL_METER_REQUIRED')
        require(t['invocationIdentity'] == inp['invocationIdentity'] == companion['invocationIdentity']
                and companion['inputClosureIdentity'] == inp['snapshot']['closureIdentity']
                and companion['outputClosureIdentity'] == t['outputSnapshot']['closureIdentity'], 'REP_ORIGINAL_COMPANION')
        require(exact_equal(projection['inputSnapshot'],inp['snapshot'])
                and exact_equal(projection['resultingSnapshot'],t['outputSnapshot'])
                and exact_equal(t['occurrenceBindings'],t['outputSnapshot']['occurrences']), 'REP_ORIGINAL_ROOTED_SNAPSHOTS')
        canonical = t['rootedContext']['canonicalRootDocumentId']
        owner_sets = [g for g in sccs(inp['snapshot']) if canonical in g]
        require(len(owner_sets) == 1,'REP_ENTRY_SCC'); owners = owner_sets[0]
        descriptor = {'members':[{'documentId':d,'historyBasisIdentity':I.history(starts[alias_of[d]]['retained']['historyBasis'])} for d in sorted(owners)],
                      'internalEdges':[{'occurrenceIdentity':row['occurrenceIdentity'],'parentDocumentId':did(row['sourceDocumentId']),
                         'sourcePath':row['sourcePath'],'childDocumentId':did(row['targetDocumentId']),'activationGeneration':str(row['activationGeneration'])}
                         for row in inp['snapshot']['occurrences'] if row['active'] and did(row['sourceDocumentId']) in owners and did(row['targetDocumentId']) in owners]}
        context, context_id = I.context(descriptor)
        require(exact_equal(context,t['rootedContext']), 'REP_EXACT_ENTRY_CONTEXT')
        for boundary in projection['topologyBoundaries']:
            snapshot_check(boundary)
            for group in sccs(boundary):
                if group & owners: owners |= group
        require(owners == {did(d) for d in t['ownedDocumentIds']}, 'REP_MONOTONE_OWNERSHIP')
        require(projection['invocationIdentity'] == I.wrapper('rootedInvocationIdentity',{
                'rootProcessingContextIdentity':context_id,'baseInvocationIdentity':t['entryInvocationIdentity'],'deliveryBasisIdentity':projection['deliveryBasisIdentity']})
                and projection['companionIdentity'] == I.wrapper('rootedCommitCompanionIdentity',{
                'rootProcessingContextIdentity':context_id,'baseCommitCompanionIdentity':companion['companionIdentity'],
                'rootedInvocationIdentity':projection['invocationIdentity']}), 'REP_ROOTED_WRAPPERS')
        require({did(x['documentId']):x['blueId'] for x in companion['expectedInputDocuments']} == {d:v['blueId'] for d,v in before.items()}, 'REP_COMPLETE_COMPANION_INPUTS')
        resulting = by_id(t['resultingDocuments'])
        require(set(resulting) == set(after), 'REP_COMPLETE_RESULTING_INVENTORY')
        for d,v in resulting.items():
            require(v['afterBlueId'] == after[d]['blueId'] and exact_equal(v['document'],after[d]['document']), 'REP_RESULTING_EXACT_BYTES')
        require({did(x['documentId']):(x['beforeBlueId'],x['afterBlueId']) for x in companion['resultingDocuments']}
                == {d:(v['beforeBlueId'],v['afterBlueId']) for d,v in resulting.items()}, 'REP_COMPLETE_COMPANION_RESULTS')
        return before,after,context_id

    def chain_check(chain, source, expected_count, checkpoint, negatives):
        require(chain['sourceDocumentId'] == ids[source] and chain['epoch'] == (0 if checkpoint else 2)
                and len(chain['positions']) == expected_count and exact_equal(chain['before'],chain['after']), 'REP_CHAIN_INVENTORY')
        require(exact_equal(chain['records'],chain['after']['records']), 'REP_CHAIN_RECORD_BINDING')
        anchor = chain['anchor']; numbered = chain['records'][source]['receipts']
        require(anchor['receiptIdentity'] == numbered[chain['epoch']]['receiptIdentity'], 'REP_IMMUTABLE_ANCHOR')
        current = anchor['afterBlueId']; predecessor = anchor['receiptIdentity']; identities=[]
        for index,item in enumerate(chain['positions']):
            p, original, operands = item['position'],item['originalPublication'],item['positionOperands']
            before,after,context_id = terminal(original)
            r = p['originalResult']; receipt = p['transitionReceipt']; source_id=ids[source]
            require(exact_equal(p['originalInput'],original['input'])
                    and exact_equal(r['platformCommitCompanion'],original['commitCompanion'])
                    and exact_equal(r['managedTransitionReceipts'],original['managedTransitionReceipts'])
                    and exact_equal(r['resultingDocuments'],original['resultingDocuments'])
                    and exact_equal(r['rootedProjection'],original['rootedProjection']), 'REP_ORIGINAL_DURABLE_BYTES')
            retained = [row for row in chain['progress']['representationRows'][source] if row['originalPublicationIdentity'] == original['publicationIdentity']
                        and row['transitionReceiptIdentity'] == receipt['transitionReceiptIdentity']]
            require(len(retained)==1 and retained[0]['epoch']==chain['epoch'], 'REP_RETAINED_PUBLICATION_MEMBERSHIP')
            require(p['epoch']==chain['epoch'] and did(p['documentId'])==source_id and p['anchorReceiptIdentity']==anchor['receiptIdentity']
                    and p['predecessorPositionIdentity']==predecessor and receipt['beforeBlueId']==current
                    and receipt['beforeBlueId']!=receipt['afterBlueId'] and receipt['emittedRootEvents']==[], 'REP_EXACT_CONTINUITY')
            require(receipt in original['managedTransitionReceipts'] and before[source_id]['epoch']==after[source_id]['epoch']==chain['epoch'], 'REP_NO_INVENTED_EPOCH')
            expected={'documentId':source_id,'epoch':chain['epoch'],'anchorReceiptIdentity':anchor['receiptIdentity'],
                      'predecessorPositionIdentity':predecessor,'beforeBlueId':receipt['beforeBlueId'],'afterBlueId':receipt['afterBlueId'],
                      'transitionReceiptIdentity':receipt['transitionReceiptIdentity'],'originalInvocationIdentity':original['invocationIdentity'],
                      'inputClosureIdentity':original['input']['snapshot']['closureIdentity'],'outputClosureIdentity':original['outputSnapshot']['closureIdentity'],
                      'commitCompanionIdentity':original['commitCompanion']['companionIdentity']}
            proof=p['rootedCheckpointReferenceProofIdentity']
            if checkpoint:
                require(isinstance(proof,str) and original['causeType']=='ExternalEventCause'
                        and source_id in {did(d) for d in original['ownedDocumentIds']}
                        and original['input']['directDeliveries']
                        and all(did(d['targetDocumentId']) not in {did(x) for x in original['ownedDocumentIds']} for d in original['input']['directDeliveries'])
                        and all(w['targetManagedScopeIdentity']!=root_scope_identity(source_id) for w in original['checkpointWrites']), 'REP_CHECKPOINT_CLASS')
                # Independent public snapshot scan locates the sole actual exact-owner change.
                snapshots=[original['input']['snapshot']]+original['rootedProjection']['topologyBoundaries']
                changed=[(a,b) for a,b in zip(snapshots,snapshots[1:]) if by_id(a['managedDocuments'])[source_id]['blueId']!=by_id(b['managedDocuments'])[source_id]['blueId']]
                require(len(changed)==1,'REP_NOT_ONE_FINALIZER_CHANGE'); a,b=changed[0]
                proof_operands={'documentId':source_id,'beforeBlueId':receipt['beforeBlueId'],'afterBlueId':receipt['afterBlueId'],
                        'checkpointInputClosureIdentity':a['closureIdentity'],'checkpointOutputClosureIdentity':b['closureIdentity'],
                        'rootProcessingContextIdentity':context_id,'rootedInvocationIdentity':original['rootedProjection']['invocationIdentity'],
                        'rootedCommitCompanionIdentity':original['rootedProjection']['companionIdentity']}
                require(C.identity('ROOTED_CHECKPOINT_REFERENCE_PROOF',proof_operands)==proof,'REP_CHECKPOINT_PROOF_HASH')
                expected['checkpointReferenceProofIdentity']=proof
                position_id=C.identity('ROOTED_CHECKPOINT_REPRESENTATION_POSITION',expected)
            else:
                require(proof is None and original['causeType'] in ('ManagedRevisionCause','ManagedRepresentationCause'), 'REP_PENDING_CLASS_UNCHANGED')
                position_id='sha256:'+hashlib.sha256(json.dumps({'domain':'blue-managed-representation-position/1','value':expected},sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
            require(exact_equal(operands,expected) and p['positionIdentity']==position_id,'REP_INDEPENDENT_POSITION_IDENTITY')
            if negatives:
                checks=chain['checks'][str(index)]; accepted(checks['valid'])
                for name in ['wrongAnchor','wrongPredecessor','wrongEpoch','wrongLineage','missingTransition','skippedCursor','reorderedBlueId','missingCompanion']:rejection(checks[name])
                if checkpoint:
                    accepted(checks['validRootedAuthority'])
                    for name in ['forgedProof','wrongOwnedSource','baseCopyWithoutRootedAuthority','materializedWithoutRootedAuthority','materializedPosition']:rejection(checks[name])
                    materialized=checks['materializedResult']
                    require(materialized['rootedProjection'] is None and materialized['commits'] is True
                            and materialized['outputClosureIdentity']==r['outputClosureIdentity']
                            and materialized['commitCompanion']['companionIdentity']==r['commitCompanion']['companionIdentity']
                            and materialized['gasTraceIdentity']==r['gasTraceIdentity'], 'REP_BASE_IDENTICAL_WITHOUT_AUTHORITY')
                    if index+1<expected_count:
                        rejection(checks['otherActualRootedPublication']);rejection(checks['wrongCompanion'])
            identities.append(position_id);predecessor=position_id;current=receipt['afterBlueId']
        require(len(set(identities))==len(identities) and chain['targetPositionIdentity']==predecessor and chain['terminalBlueId']==current,'REP_EXACT_TERMINUS')
        next_id=chain['nextRevisionReceiptIdentity']
        if next_id is None:require(chain['records'][source]['blueId']==current and chain['records'][source]['epoch']==chain['epoch'],'REP_TAIL_CURRENT_ENDPOINT')
        else:
            require(len(numbered)>chain['epoch']+1 and numbered[chain['epoch']+1]['contractsTransitionReceiptIdentity']==next_id
                    and numbered[chain['epoch']+1]['beforeBlueId']==current,'REP_NEXT_IMMUTABLE_PREDECESSOR')
        return identities

    if variant in ('local-work','owned-checkpoint'):
        before,after,_=terminal(final['actualPublication']); source=final['sourceDocumentId']
        require(final['proof'] is None, 'REP_REAL_WORK_OBTAINED_CHECKPOINT_PROOF')
        rejection(final['positionRejected']); rejection(final['retainedAuthorityRejected'])
        own='P' if variant=='local-work' else 'S'
        require(source==ids[own] and appends['E100']['request']['operation']==('tick' if own=='P' else 'noop'), 'REP_COUNTEREXAMPLE_LITERAL')
        transition=next(r for r in final['actualPublication']['managedTransitionReceipts'] if did(r['documentId'])==source)
        require(transition['emittedRootEvents']==[], 'REP_EVENTLESS_COUNTEREXAMPLE')
        require(any(w['targetManagedScopeIdentity']==root_scope_identity(ids['S']) for w in final['actualPublication']['checkpointWrites']), 'REP_ACTUAL_CHECKPOINT_REQUIRED')
        require(scalar(final['records']['S']['exactDocument']['counter'])==0, 'REP_INDEPENDENT_SOURCE_CHANGED')
        if own=='P':require(scalar(final['records']['P']['exactDocument']['seen'])==1, 'REP_ACTUAL_LOCAL_WORK_REQUIRED')
        else:require(any(did(d['targetDocumentId'])==ids['S'] for d in final['actualPublication']['input']['directDeliveries']), 'REP_OWN_DIRECT_CHECKPOINT_REQUIRED')
    elif variant.startswith('checkpoint-'):
        for phase_name in ['subject','secondCheckpoint']:
            phase=phases[phase_name]
            require(phase['sourceBefore'] and exact_equal(phase['sourceBefore'],phase['sourceAfter']), 'REP_ORIGINAL_INDEPENDENT_SOURCE_CHANGED')
        require(exact_equal(phases['originalChain']['records']['S'],starts['S']['retained']), 'REP_INDEPENDENT_SOURCE_HISTORY_OR_HEAD_CHANGED')
        require(exact_equal(phases['originalChain']['records']['P']['receipts'][0],starts['P']['epoch0Receipt']), 'REP_NUMBERED_GENESIS_REWRITTEN')
        expected=chain_check(phases['originalChain'],'P',2,True,True)
        require(chain_check(final,'P',2,True,False)==expected,'REP_ORIGINAL_POSITIONS_CHANGED')
        require((phases['originalChain']['nextRevisionReceiptIdentity'] is not None)==(variant=='checkpoint-intermediate'),'REP_TAIL_INTERMEDIATE_DISTINCTION')
        attach=appends['E400']; require(attach['request']['request']=={'child':{'$capture':'P.initialBlueId'}},'REP_CURRENT_SNAPSHOT_SUBSTITUTED')
        driver=next(row for row in tr if row['request']['op']=='drainRootHistory')
        require(driver['request']['maxSelections']==32 and driver['request']['root']=='C' and exact_equal(driver['response'],phases['traversal']),'REP_DRIVER_BINDING_OR_BOUND')
        calls=phases['traversal']['calls']; require(1<=len(calls)<=32 and calls[-1]['quiescent'] is True,'REP_FINITE_32')
        positions=[]; publications=set(); fixed_targets={}; prior=None
        for index,call in enumerate(calls):
            require(call['index']==index and call['selectedRootDocumentId']==ids['C'] and call['resourceFailures']==[],'REP_SELECTED_CONSUMER_OR_RESOURCES')
            require(call['paused'] is (not call['quiescent']) and call['diagnostic']['code'] == ('NONE' if call['quiescent'] else 'PROCESSING_PAUSED'),'REP_BLOCKED_AS_PROGRESS')
            receipt_prefix(call['before'],call['after'])
            check_call_carriers(call, ids, require, exact_equal)
            if prior is not None:require(exact_equal(prior,call['before']),'REP_CALL_CONTINUITY')
            prior=call['after']
            for alias in ['P','S']:
                require(exact_equal(call['before'][alias],call['after'][alias])
                        and exact_equal(call['before'][alias],phases['originalChain']['records'][alias]),'REP_AUTHORITATIVE_SOURCE_CHANGED')
            actual_positions=call['representationPositions'];require(len(actual_positions)<=1,'REP_MULTIPLE_POSITION_WORKS_IN_CALL')
            if actual_positions:
                p=actual_positions[0];positions.append(p['positionIdentity'])
                require(scalar(call['after']['C']['exactDocument']['updates'])==scalar(call['before']['C']['exactDocument']['updates'])+1,'REP_CONSUMER_REACTION_NOT_EXACTLY_ONCE')
            ready=call['progressAfter']['readiness']['C']
            if not ready['ready']:require(call['readyBlueIdBefore']==call['readyBlueIdAfter'],'REP_PREMATURE_READY_VIEW')
            for t in call['terminals']:
                terminal(t);require(t['publicationIdentity'] not in publications,'REP_DUPLICATED_PUBLICATION');publications.add(t['publicationIdentity'])
                if t['causeType']=='ManagedRepresentationCause':
                    cause=t['input']['cause']; p=cause['transition'];
                    require(p['positionIdentity'] in expected and exact_equal(p,next(x['position'] for x in final['positions'] if x['position']['positionIdentity']==p['positionIdentity'])),'REP_UNCOMMITTED_APPLIED_POSITION')
                    token=cause['targetOccurrenceIdentity'];goal=(cause['targetPositionIdentity'],cause['nextRevisionReceiptIdentity'])
                    require(fixed_targets.setdefault(token,goal)==goal and goal==(expected[-1],phases['originalChain']['nextRevisionReceiptIdentity']),'REP_MOVING_POSITION_TARGET')
                    require(cause['fromEpoch']==cause['toEpoch']==0 and cause['beforeBlueId']==p['transitionReceipt']['beforeBlueId']
                            and cause['afterBlueId']==p['transitionReceipt']['afterBlueId'],'REP_POSITION_CAUSE_ENDPOINTS')
                elif t['causeType']=='ManagedRevisionCause':
                    cause=t['input']['cause'];require(cause['toEpoch']==cause['fromEpoch']+1,'REP_REVISION_PLUS_ONE')
                    future=cause['successorRepresentationCause']
                    if future is not None:
                        p=future['transition']
                        require(exact_equal(p,next(x['position'] for x in final['positions'] if x['position']['positionIdentity']==p['positionIdentity']))
                                and p['positionIdentity']==expected[0] and future['targetPositionIdentity']==expected[-1]
                                and future['nextRevisionReceiptIdentity'] is None, 'REP_FUTURE_AUTHENTICATED_FIXED_GOAL')
                        token=cause['targetOccurrenceIdentity'];goal=(future['targetPositionIdentity'],None)
                        require(fixed_targets.setdefault(token,goal)==goal, 'REP_FUTURE_MOVING_GOAL')
                        require(all(item['positionIdentity']!=p['positionIdentity'] for item in call['representationPositions']), 'REP_FUTURE_COUNTED_AS_EXECUTED')
        require(positions==expected,'REP_SKIP_REORDER_DUPLICATE_POSITION')
        require(any(e['entry']['blueId']==attach['response']['entryBlueId'] and e['disposition']=='APPLIED' for e in calls[0]['entries']),'REP_AUTHORED_ATTACH_NOT_APPLIED')
        require(exact_equal(final['records'],calls[-1]['after']),'REP_FINAL_CONSUMER_RECORDS')
        final_ready=final['progress']['readiness']['C'];require(final_ready['ready'] is True and final_ready['activeBarrierIdentities']==[],'REP_FINAL_READY_BARRIER')
        occurrence=[x for x in final['progress']['selectedSnapshots']['C']['occurrences'] if did(x['sourceDocumentId'])==ids['C'] and x['sourcePath']=='/child']
        require(len(occurrence)==1 and occurrence[0]['active'] is True and occurrence[0]['expectedTargetBlueId']==final['records']['P']['blueId'],'REP_FINAL_ACTIVE_EXACT_REFERENCE')
    else:
        require(variant=='pending-target' and rule['pendingPositionCount']==1,'REP_VARIANT')
        # Under rooted publication A stays independent during B's older imports.
        # Only their terminal cyclic join rewrites A at its existing epoch.
        chain_check(phases['pendingTail'],'A',1,False,True)
        expected=chain_check(phases['originalChain'],'A',1,False,True)
        require(chain_check(final,'A',1,False,False)==expected,'REP_OLD_PENDING_POSITIONS_CHANGED')
        creation=[t for call in phases['graph200']['calls'] for t in call['terminals']]
        require(len(creation)==4, 'REP_PENDING_ORIGINAL_APPLICATION_COUNT')
        source=ids['A']
        for index,t in enumerate(creation):
            before,after,_=terminal(t)
            require(before[source]['epoch']==after[source]['epoch']==2, 'REP_PENDING_ORIGINAL_EPOCH')
            if index<3:
                require(exact_equal(before[source],after[source])
                        and source not in {did(d) for d in t['ownedDocumentIds']}, 'REP_PRE_JOIN_SOURCE_CHANGED')
            else:
                require(source in {did(d) for d in t['ownedDocumentIds']}
                        and before[source]['blueId']!=after[source]['blueId']
                        and exact_equal(t,phases['pendingTail']['positions'][0]['originalPublication']),
                        'REP_PENDING_EXACT_JOIN_PUBLICATION')
        traversed=[]
        for phase in ['graph100','graph200','graph300','graph400']:
            calls=phases[phase]['calls']; require(1<=len(calls)<=32 and calls[-1]['quiescent'] is True,'REP_PENDING_GRAPH_BOUND')
            for call in calls:
                receipt_prefix(call['before'],call['after'])
                check_call_carriers(call, ids, require, exact_equal)
                for t in call['terminals']:
                    terminal(t)
                    if phase=='graph400' and t['causeType']=='ManagedRepresentationCause':traversed.append(t['input']['cause']['transition']['positionIdentity'])
        require(all(p in traversed for p in expected) and all(traversed.count(p)==1 for p in expected),'REP_OLD_CHAIN_NOT_ACTUALLY_APPLIED')
        for alias in aliases:require(final['progress']['readiness'][alias]['ready'] is True,'REP_PENDING_GRAPH_NOT_READY')
    require(exact_equal(rec['restart']['before'],final['records']) and exact_equal(rec['restart']['after'],final['records'])
            and rec['restart']['beforeCommandCount']==rec['restart']['afterCommandCount']==len(appends),'REP_RESTART_DUPLICATES_OR_STATE')
    restarts=[r['response'] for r in tr if r['request']['op']=='restart']
    require(len(restarts)==1 and exact_equal(restarts[0]['progressBefore'],restarts[0]['progressAfter']),'REP_RESTART_POSITION_LOSS')
