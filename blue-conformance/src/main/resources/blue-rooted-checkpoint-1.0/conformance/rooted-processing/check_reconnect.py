"""RUN019 independent public SDK reconnect evidence checks. No production status inference."""

def reconnect_timeline_identity(timeline, timeline_id, require):
    """Hash the two literal typed Timeline values, never their display names."""
    import hashlib, json
    require(timeline_id in ('rcp/reconnect/A', 'rcp/reconnect/B') and timeline == {
        'type': {'blueId': '5VAQp5thYLkzp3FbvYGmVvmdLqqu6pV5vhNgD14XJwpX'},
        'timelineId': {'type': {'blueId': 'GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC'},
                       'value': timeline_id}}, 'RECONNECT_EXACT_TIMELINE')

    def direct(value):
        if set(value) == {'blueId'}: return value['blueId']
        fields = {k: v if k == 'value' else {'blueId': direct(v)} for k, v in value.items()}
        raw = hashlib.sha256(json.dumps(fields, sort_keys=True, separators=(',', ':')).encode()).digest()
        number = int.from_bytes(raw, 'big'); result = ''
        while number:
            number, index = divmod(number, 58)
            result = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'[index] + result
        return '1' * (len(raw) - len(raw.lstrip(b'\0'))) + result

    return direct(timeline)


def reconnect_retry_identity(terminal, call, ids, variant, captures, require, exact_equal, managed_identity):
    """Verify the literal E400 reservation retry before deriving its execution identity."""
    inp = terminal['input']
    did = lambda value: value['value']
    # Recreating the retired /peers/b occurrence consumes a resolution-bound
    # PROCESS_CLOSURE retry. Its original logical input remains frozen.
    closures = [c for e in call['entries'] for c in e['closures'] if c['closureId'] == terminal['publicationIdentity']]
    require(len(closures) == 1 and closures[0]['automaticRetryCount'] == 1
            and closures[0]['processorAttemptCount'] == 2, 'RECONNECT_EXACT_RETRY_ATTEMPTS')
    resolutions = closures[0]['managedSurfaceEvidence']['resolvedOccurrences']
    require(len(resolutions) == 1, 'RECONNECT_EXACT_RETRY_RESOLUTION')
    resolution = resolutions[0]
    epoch = -1 if variant == 'saved-authored' else captures['B250']['epoch']
    selected_id = ids['B'] if variant == 'saved-authored' else captures['B250']['blueId']
    require(resolution['kind'] == ('EXISTING_AUTHORED_INITIAL' if epoch == -1 else 'EXISTING_RETAINED_EPOCH')
            and resolution['authoredInitial'] is None, 'RECONNECT_RETRY_SELECTION_KIND')
    old_rows = [r for r in inp['snapshot']['occurrences'] if did(r['sourceDocumentId']) == ids['A'] and r['sourcePath'] == '/peers/b']
    new_rows = [r for r in terminal['occurrenceBindings'] if did(r['sourceDocumentId']) == ids['A'] and r['sourcePath'] == '/peers/b']
    require(len(old_rows) == len(new_rows) == 1, 'RECONNECT_RETRY_OCCURRENCE_INVENTORY')
    old_row, new_row = old_rows[0], new_rows[0]
    require(old_row['active'] is False and old_row['pendingHistoricalEpoch'] is None
            and old_row['activationGeneration'] == 2 and did(old_row['targetDocumentId']) == ids['B']
            and new_row['active'] is False and new_row['pendingHistoricalEpoch'] == epoch
            and new_row['activationGeneration'] == 2 and did(new_row['targetDocumentId']) == ids['B']
            and new_row['expectedTargetBlueId'] == selected_id, 'RECONNECT_RETRY_EXACT_CURSOR')
    fields = ('sourceDocumentId','sourcePath','targetDocumentId','activationGeneration','active','expectedTargetBlueId')
    require(exact_equal(resolution['occurrence'], {k:new_row[k] for k in fields})
            and resolution['bindingIdentity'] == new_row['bindingIdentity']
            and resolution['occurrenceIdentity'] == new_row['occurrenceIdentity'], 'RECONNECT_RETRY_BINDING')
    resolution_id = managed_identity('blue-contracts-managed-occurrence-resolution/1.0', {
        'demandIdentity':resolution['demandIdentity'], 'targetDocumentId':ids['B'], 'pendingHistoricalEpoch':epoch})
    resolution_set = managed_identity('blue-contracts-managed-occurrence-resolution-set/1.0', [resolution_id])
    execution_identity = managed_identity('blue-contracts-process-retry-invocation/1.0', {
        'baseInvocationIdentity':inp['invocationIdentity'], 'resolutionSetIdentity':resolution_set})
    return execution_identity


def check_reconnect(rec, contract, weights, variant, require, exact_equal, gas_check, structured_output, identity_check):
    from pathlib import Path
    import sys
    sys.path.insert(0, str(Path(gas_check.__globals__['__file__']).resolve().parent / 'identity'))
    import constructors as I
    from rooted_graph_checks import graph_checks
    from check_successor_carriers import check_call_carriers
    rule = contract['reconnect']; aliases = rule['aliases']; ids = rec['documentIds']
    require(aliases == ['A','B'] and set(ids) == set(aliases) and len(set(ids.values())) == 2, 'RECONNECT_DOCUMENT_INVENTORY')
    require(variant in rule['selectionByVariant'], 'RECONNECT_VARIANT')
    alias_of = {v:k for k,v in ids.items()}
    rows = rec['transcript']; require(all(r.get('completed') is True for r in rows), 'RECONNECT_INCOMPLETE_TRANSCRIPT')
    starts = {r['request']['alias']:r['response'] for r in rows if r['request']['op'] == 'start'}
    appends = {r['request']['capture']:r for r in rows if r['request']['op'] == 'append'}
    require(len(starts) == 2 and set(starts) == set(aliases), 'RECONNECT_START_INVENTORY')
    require(len(appends) == len([r for r in rows if r['request']['op'] == 'append']) == 8, 'RECONNECT_ENTRY_INVENTORY')
    observation = rec['output']['reconnect']; phases = rec['phaseRecords']; captures = observation['captures']
    require(exact_equal(observation,phases['final']), 'RECONNECT_FINAL_OBSERVATION_BINDING')
    require(set(captures) == set(starts) | set(appends) | {'B250'}, 'RECONNECT_CAPTURE_INVENTORY')
    did,scalar,by_id,receipt_prefix,sccs,snapshot_check,trace_check = graph_checks(
        aliases,ids,starts,weights,require,exact_equal,gas_check)
    entry_by_id = {r['response']['entryBlueId']:r for r in appends.values()}
    allowed_emits = {appends[e]['response']['entryBlueId'] for e in ('E250','E350','E500')}
    all_calls = []
    published_originals = {}
    plan_descriptors = {}
    position_targets = {}
    future_goals = {}
    executed_positions = set()

    def managed_identity(domain,value):
        # Coordination's established 1.0 domains use portable JCS values,
        # including integers/bools/null; rooted draft2 I.digest is string-only.
        import hashlib,json
        def portable(v):
            if v is None or type(v) is bool: return
            if type(v) is int:
                require(abs(v) <= 9007199254740991,'RECONNECT_MANAGED_INTEGER'); return
            if isinstance(v,str):
                require(not any(0xD800 <= ord(c) <= 0xDFFF for c in v),'RECONNECT_MANAGED_UNICODE'); return
            if isinstance(v,list):
                for item in v: portable(item)
                return
            require(isinstance(v,dict) and all(isinstance(k,str) and k.isascii() for k in v),'RECONNECT_MANAGED_OBJECT')
            for item in v.values(): portable(item)
        envelope={'domain':domain,'value':value};portable(envelope)
        encoded=json.dumps(envelope,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode('utf-8')
        return 'sha256:'+hashlib.sha256(encoded).hexdigest()

    def source_events(terminal, receipt):
        events = receipt['emittedRootEvents']
        if not events: return
        require(terminal['causeType'] == 'ExternalEventCause'
                and terminal['input']['cause']['eventBlueId'] in allowed_emits
                and did(receipt['documentId']) == ids['B'] and len(events) == 1,
                'RECONNECT_UNAUTHORIZED_SOURCE_EVENT')
        for event in events:
            require(did(event['sourceDocumentId']) == ids['B'] and event['ordinal'] == 0
                    and event['publicAtSource'] is True
                    and scalar(event['exactEvent']['kind']) == 'Tutorial/Graph Token'
                    and scalar(event['exactEvent']['to']) == 'A'
                    and scalar(event['exactEvent']['next']) == 'stop', 'RECONNECT_SOURCE_EVENT_PAYLOAD')

    def terminal_events(terminal, owners):
        expected = [e for r in terminal['managedTransitionReceipts'] if did(r['documentId']) in owners
                    for e in r['emittedRootEvents'] if e['publicAtSource']]
        actual = terminal['ownedPublicEvents']
        require(len(actual) == len(expected), 'RECONNECT_OWNED_EVENT_INVENTORY')
        for public,source in zip(actual,expected):
            require(did(public['publicRootDocumentId']) == did(source['sourceDocumentId'])
                    and public['eventOccurrenceIdentity'] == source['eventOccurrenceIdentity']
                    and public['eventOccurrenceOrdinal'] == source['occurrenceOrdinal']
                    and public['eventBlueId'] == source['eventBlueId']
                    and exact_equal(public['event'],source['exactEvent']), 'RECONNECT_PUBLIC_SOURCE_BINDING')

    def bind_events(host, core):
        require(len(host) == len(core), 'RECONNECT_RETAINED_EVENT_INVENTORY')
        for h,c in zip(host,core):
            require(h['ordinal'] == c['ordinal'] and h['eventOccurrenceOrdinal'] == c['occurrenceOrdinal']
                    and h['sourceDocumentId'] == c['sourceDocumentId']
                    and h['eventOccurrenceIdentity'] == c['eventOccurrenceIdentity']
                    and h['eventBlueId'] == c['eventBlueId'] and h['publicAtSource'] == c['publicAtSource']
                    and exact_equal(h['exactEvent'],c['exactEvent']), 'RECONNECT_RETAINED_EVENT_BYTES')
            payload = {k:h[k] for k in ('ordinal','eventOccurrenceOrdinal','eventOccurrenceIdentity','eventBlueId','publicAtSource')}
            payload['sourceDocumentId'] = did(h['sourceDocumentId'])
            require(h['managedEventIdentity'] == managed_identity('blue-coordination-managed-event-occurrence/1.0',payload),
                    'RECONNECT_RETAINED_EVENT_IDENTITY')

    def progress_check(progress, records):
        require(set(progress['selectedSnapshots']) == set(progress['readiness']) == set(progress['plans'])
                == set(progress['representationRows']) == set(aliases), 'RECONNECT_PROGRESS_INVENTORY')
        journal_ids = [entry['blueId'] for entry in progress['journal']]
        require(len(journal_ids) == len(set(journal_ids)) and set(progress['journalOrders']) == set(journal_ids), 'RECONNECT_PROGRESS_JOURNAL')
        for entry in progress['journal']:
            require(entry['blueId'] in entry_by_id
                    and exact_equal(entry,entry_by_id[entry['blueId']]['response']['actualEntry']), 'RECONNECT_PROGRESS_EXACT_ENTRY')
            key = progress['journalOrders'][entry['blueId']]['components']
            require(len(key) == 3 and key[0] == entry['timestampMicros'] and key[1] == reconnect_timeline_identity(entry['exact']['timeline'], entry['timeline']['id'], require) and key[2] == entry['blueId'], 'RECONNECT_SOURCE_ORDER_KEY')
        for alias in aliases:
            snapshot = progress['selectedSnapshots'][alias]; documents = snapshot_check(snapshot)
            require(ids[alias] in documents and documents[ids[alias]]['blueId'] == records[alias]['blueId']
                    and exact_equal(documents[ids[alias]]['document'],records[alias]['exactDocument'])
                    and exact_equal(snapshot['occurrences'],records[alias]['occurrences'])
                    and exact_equal(snapshot['components'],records[alias]['components']), 'RECONNECT_SELECTED_CURRENT_BINDING')
            ready = progress['readiness'][alias]
            require(did(ready['documentId']) == ids[alias]
                    and ready['committedEpoch'] == records[alias]['epoch']
                    and ready['committedBlueId'] == records[alias]['blueId'], 'RECONNECT_COMMITTED_READINESS')
            for plan in progress['plans'][alias]:
                require(did(plan['consumerDocumentId']) == ids[alias], 'RECONNECT_PLAN_OWNER')
                barrier = progress['barriers'][plan['barrierIdentity']]
                require(barrier['barrierIdentity'] == plan['barrierIdentity']
                        and plan['planIdentity'] in barrier['planIdentities']
                        and barrier['consumerDocumentId'] == plan['consumerDocumentId']
                        and barrier['causedByIdentity'] == plan['causedByIdentity'], 'RECONNECT_PLAN_BARRIER')
                fields = ('barrierIdentity','consumerDocumentId','targetOccurrenceIdentity','targetPath',
                          'activationGeneration','sourceDocumentId','admittedSourceEpoch','admittedSourceBlueId',
                          'requiredThroughSourceEpoch','causedByIdentity')
                descriptor = {k:plan[k] for k in fields}
                definition = {k:v for k,v in descriptor.items() if k != 'requiredThroughSourceEpoch'}
                definition['consumerDocumentId'] = did(definition['consumerDocumentId'])
                definition['sourceDocumentId'] = did(definition['sourceDocumentId'])
                require(plan['planIdentity'] == managed_identity('blue-coordination-managed-catch-up-plan/1.0',definition),
                        'RECONNECT_PLAN_DEFINITION_IDENTITY')
                snapshot_fields = ('planIdentity','barrierIdentity','nextSourceEpoch','requiredThroughSourceEpoch','status','waitingCode','waitingMessage')
                require(plan['snapshotIdentity'] == managed_identity('blue-coordination-managed-catch-up-plan-snapshot/1.0',
                        {k:plan[k] for k in snapshot_fields}), 'RECONNECT_PLAN_SNAPSHOT_IDENTITY')
                barrier_definition = {'consumerDocumentId':did(barrier['consumerDocumentId']),
                                      'causedByIdentity':barrier['causedByIdentity'],'causeOrder':barrier['causeOrder']['components']}
                require(barrier['barrierIdentity'] == managed_identity('blue-coordination-managed-catch-up-barrier/1.0',barrier_definition),
                        'RECONNECT_BARRIER_DEFINITION_IDENTITY')
                barrier_fields = ('barrierIdentity','planIdentities','status','waitingCode','waitingMessage')
                require(barrier['planIdentities'] == sorted(set(barrier['planIdentities']))
                        and barrier['snapshotIdentity'] == managed_identity('blue-coordination-managed-catch-up-barrier-snapshot/1.0',
                        {k:barrier[k] for k in barrier_fields}), 'RECONNECT_BARRIER_SNAPSHOT_IDENTITY')
                previous = plan_descriptors.setdefault(plan['planIdentity'],descriptor)
                require(exact_equal(previous,descriptor), 'RECONNECT_FROZEN_PLAN_CHANGED')
                require(plan['status'] not in ('WAITING_FOR_HISTORY','BLOCKED'), 'RECONNECT_PLAN_WAITING')
            for row in progress['representationRows'][alias]:
                original = progress['representationPublications'][row['originalPublicationIdentity']]
                require(original['publicationIdentity'] == row['originalPublicationIdentity']
                        and original['status'] == 'SUCCESS', 'RECONNECT_REPRESENTATION_PUBLICATION')
                previous = published_originals.setdefault(original['publicationIdentity'],original)
                require(exact_equal(previous,original), 'RECONNECT_ORIGINAL_PUBLICATION_CHANGED')
                transitions = [r for r in original['managedTransitionReceipts']
                               if did(r['documentId']) == ids[alias] and r['transitionReceiptIdentity'] == row['transitionReceiptIdentity']]
                require(len(transitions) == 1 and transitions[0]['beforeBlueId'] == row['beforeBlueId']
                        and transitions[0]['afterBlueId'] == row['afterBlueId']
                        and transitions[0]['emittedRootEvents'] == [], 'RECONNECT_REPRESENTATION_ORIGINAL_ROW')

    def owned_work_check(call):
        for app in call['ownedRetainedApplications']:
            work = call['selection']['managedEpochApplicationWork']
            require(work and work['workIdentity'] == app['workIdentity']
                    and work['planIdentity'] == app['planIdentity']
                    and work['sourceReceiptIdentity'] == app['sourceReceiptIdentity']
                    and app['resultingSourceCursor'] == work['sourceEpoch'] + 1, 'RECONNECT_OWNED_SELECTED_WORK')
            key = (did(app['consumerDocumentId']),app['workIdentity'])
            require(key not in source_applications,'RECONNECT_DUPLICATE_OWNED_APPLICATION'); source_applications.add(key)
            terminal = next(t for t in call['terminals'] if t['invocationIdentity'] == app['contractsInvocationIdentity'])
            cause = terminal['input']['cause']; source = alias_of[did(work['sourceDocumentId'])]
            require(cause['targetOccurrenceIdentity'] == work['targetOccurrenceIdentity']
                    and did(cause['childDocumentId']) == ids[source], 'RECONNECT_OWNED_CAUSE_TARGET')
            occurrence = [r for r in terminal['input']['snapshot']['occurrences'] if r['occurrenceIdentity'] == work['targetOccurrenceIdentity']]
            require(len(occurrence) == 1 and occurrence[0]['activationGeneration'] == work['activationGeneration']
                    and occurrence[0]['sourcePath'] == work['targetPath']
                    and occurrence[0]['expectedTargetBlueId'] == cause['beforeBlueId'], 'RECONNECT_OWNED_OCCURRENCE')
            receipts = [r for r in call['before'][source]['receipts'] if r['receiptIdentity'] == work['sourceReceiptIdentity']]
            require(len(receipts) == 1 and receipts[0]['epoch'] == work['sourceEpoch'], 'RECONNECT_OWNED_RETAINED_RECEIPT')
            if work['representationCause'] is None:
                receipt = receipts[0]
                require(terminal['causeType'] == 'ManagedRevisionCause'
                        and cause['toEpoch'] == cause['fromEpoch'] + 1 == work['sourceEpoch']
                        and cause['sourceRevisionReceiptIdentity'] == receipt['contractsTransitionReceiptIdentity']
                        and cause['beforeBlueId'] == (starts[source]['initialBlueId'] if receipt['epoch'] == 0 else receipt['beforeBlueId'])
                        and cause['afterBlueId'] == receipt['afterBlueId']
                        and exact_equal(cause['afterDocument'],receipt['afterDocument']), 'RECONNECT_OWNED_REVISION_EXACT')
            else:
                require(terminal['causeType'] == 'ManagedRepresentationCause'
                        and work['representationCause']['causeIdentity'] == cause['causeIdentity'], 'RECONNECT_OWNED_REPRESENTATION')

    def historical_check(call):
        for terminal in call['terminals']:
            if terminal['causeType'] not in ('ManagedRevisionCause','ManagedRepresentationCause'): continue
            cause = terminal['input']['cause']
            works = [l['work'] for l in call['localRetainedApplications'] if l['result']['closureId'] == terminal['publicationIdentity']]
            if not works:
                selected = call['selection']['managedEpochApplicationWork']
                require(selected is not None, 'RECONNECT_MISSING_SELECTED_HISTORICAL_WORK'); works = [selected]
            require(len(works) == 1,'RECONNECT_AMBIGUOUS_HISTORICAL_WORK'); work = works[0]
            source = alias_of[did(work['sourceDocumentId'])]
            anchor = next(r for r in call['before'][source]['receipts'] if r['receiptIdentity'] == work['sourceReceiptIdentity'])
            if call['lane'] == 'RETAINED':
                cutoff = call['progressBefore']['journalOrders'][call['cutoffEntryBlueId']]['components']
                require(anchor['sourceOrder']['components'] <= cutoff, 'RECONNECT_FUTURE_RETAINED_INPUT')
            # Position traversal is additionally authenticated against the actual,
            # durably retained original publication inventory, not its supplied hash.
            positional = cause if terminal['causeType'] == 'ManagedRepresentationCause' else cause['successorRepresentationCause']
            if positional is not None:
                cause = positional
                tr = cause['transition']
                originals = dict(call['progressBefore']['representationPublications'])
                originals.update(call['progressAfter']['representationPublications'])
                matched = [p for p in originals.values() if p['input']['invocationIdentity'] == tr['originalInput']['invocationIdentity']
                           and p['commitCompanion']['companionIdentity'] == tr['originalResult']['platformCommitCompanion']['companionIdentity']]
                require(len(matched) == 1, 'RECONNECT_UNAUTHENTICATED_POSITION')
                original = matched[0]
                require(exact_equal(original['input'],tr['originalInput'])
                        and exact_equal(original['commitCompanion'],tr['originalResult']['platformCommitCompanion'])
                        and exact_equal(original['managedTransitionReceipts'],tr['originalResult']['managedTransitionReceipts'])
                        and exact_equal(original['resultingDocuments'],tr['originalResult']['resultingDocuments'])
                        and exact_equal(original['rootedProjection'],tr['originalResult']['rootedProjection']), 'RECONNECT_POSITION_ORIGINAL_BYTES')
                require(tr['epoch'] == cause['fromEpoch'] == cause['toEpoch'] == work['sourceEpoch']
                        and tr['anchorReceiptIdentity'] == anchor['receiptIdentity']
                        and tr['beforeBlueId'] == cause['beforeBlueId'] and tr['afterBlueId'] == cause['afterBlueId'], 'RECONNECT_POSITION_ENDPOINTS')
                token = (did(work['consumerDocumentId']),work['targetOccurrenceIdentity'],work['activationGeneration'],anchor['receiptIdentity'])
                target = (cause['targetPositionIdentity'],cause['nextRevisionReceiptIdentity'])
                if terminal['causeType'] == 'ManagedRevisionCause':
                    require(future_goals.setdefault(token,target[0]) == target[0], 'RECONNECT_FUTURE_MOVING_GOAL')
                else:
                    executed_positions.add((token,tr['positionIdentity']))
                require(position_targets.setdefault(token,target) == target, 'RECONNECT_POSITION_MOVING_TARGET')
                next_id = cause['nextRevisionReceiptIdentity']
                if next_id is not None:
                    successors = [r for r in call['before'][source]['receipts'] if r['receiptIdentity'] == next_id]
                    require(len(successors) == 1 and successors[0]['epoch'] == anchor['epoch'] + 1, 'RECONNECT_POSITION_NEXT_RECEIPT')
                source_rows = call['progressBefore']['representationRows'][source]
                matches = [r for r in source_rows if r['originalPublicationIdentity'] == original['publicationIdentity']
                           and r['transitionReceiptIdentity'] == tr['transitionReceipt']['transitionReceiptIdentity']]
                require(len(matches) == 1 and matches[0]['epoch'] == tr['epoch'], 'RECONNECT_POSITION_HISTORY_MEMBERSHIP')
    publications = set()
    source_applications = set()
    live_applications = set()

    def terminal_check(terminal, retained_before, call):
        publication = terminal['publicationIdentity']
        require(publication not in publications, 'GRAPH_DUPLICATE_TERMINAL'); publications.add(publication)
        require(terminal['status'] == 'SUCCESS' and terminal['rollbackToInput'] is False, 'GRAPH_TERMINAL_STATUS')
        inp, companion = terminal['input'], terminal['commitCompanion']
        cause = inp['cause']
        if terminal['causeType'] in ('ManagedRevisionCause','ManagedRepresentationCause'):
            source_transition = cause['sourceTransitionReceipt']
            require(cause['originalSourceCauseIdentity'] == source_transition['originalCauseIdentity']
                    and cause['sourceRevisionReceiptIdentity'] == source_transition['transitionReceiptIdentity']
                    and cause['afterBlueId'] == source_transition['afterBlueId'], 'GRAPH_MANAGED_SOURCE_CAUSE_BINDING')
            if terminal['causeType'] == 'ManagedRevisionCause':
                source = retained_before[alias_of[did(cause['childDocumentId'])]]
                retained = [r for r in source['receipts'] if r['epoch'] == cause['toEpoch']]
                require(len(retained) == 1 and retained[0]['contractsTransitionReceiptIdentity'] == source_transition['transitionReceiptIdentity']
                        and retained[0]['originalCauseIdentity'] == cause['originalSourceCauseIdentity']
                        and retained[0]['afterBlueId'] == cause['afterBlueId']
                        and exact_equal(retained[0]['afterDocument'],cause['afterDocument']), 'GRAPH_MANAGED_RETAINED_SOURCE')
        execution_identity = inp['invocationIdentity']
        if terminal['causeType'] == 'ExternalEventCause' and cause['eventBlueId'] == appends['E400']['response']['entryBlueId']:
            execution_identity = reconnect_retry_identity(terminal, call, ids, variant, captures, require, exact_equal, managed_identity)
        require(execution_identity == terminal['invocationIdentity'] == companion['invocationIdentity'], 'GRAPH_INVOCATION_BINDING')
        trace_check(terminal['gas'], terminal['fullGasTrace'], inp['executionPolicy']['sharedLimit'])
        before, after = inp['snapshot'], terminal['outputSnapshot']
        before_docs, after_docs = snapshot_check(before), snapshot_check(after)
        require(companion['inputClosureIdentity'] == before['closureIdentity']
                and companion['outputClosureIdentity'] == after['closureIdentity'], 'GRAPH_CLOSURE_BINDING')
        require(companion['expectedInputGraphGeneration'] == before['graphGeneration']
                and companion['outputGraphGeneration'] == after['graphGeneration'], 'GRAPH_GENERATION_BINDING')
        require(companion['inputOccurrenceBindingSetIdentity'] == before['occurrenceBindingSetIdentity']
                and companion['outputOccurrenceBindingSetIdentity'] == after['occurrenceBindingSetIdentity'], 'GRAPH_OCCURRENCE_BINDING')
        require(exact_equal(terminal['occurrenceBindings'], after['occurrences']), 'GRAPH_OUTPUT_OCCURRENCES')
        require({did(v['documentId']):v['blueId'] for v in companion['expectedInputDocuments']}
                == {d:v['blueId'] for d,v in before_docs.items()}, 'GRAPH_COMPANION_INPUT_DOCUMENTS')
        resulting = by_id(terminal['resultingDocuments'])
        require(set(resulting) == set(after_docs), 'GRAPH_RESULT_DOCUMENT_INVENTORY')
        require({did(v['documentId']):(v['beforeBlueId'],v['afterBlueId']) for v in companion['resultingDocuments']}
                == {d:(v['beforeBlueId'],v['afterBlueId']) for d,v in resulting.items()}, 'GRAPH_COMPANION_OUTPUT_DOCUMENTS')
        for owner, value in resulting.items():
            require(value['afterBlueId'] == after_docs[owner]['blueId']
                    and exact_equal(value['document'], after_docs[owner]['document']), 'GRAPH_RESULT_EXACT_DOCUMENT')
        canonical = terminal['rootedContext']['canonicalRootDocumentId']
        entry_components = [s for s in sccs(before) if canonical in s]
        require(len(entry_components) == 1, 'GRAPH_ENTRY_OWNER_COMPONENT')
        entry_owners = entry_components[0]
        owner_descriptor = {'members':[{'documentId':d,'historyBasisIdentity':I.history(starts[alias_of[d]]['retained']['historyBasis'])}
                                       for d in sorted(entry_owners)],
                            'internalEdges':[{'occurrenceIdentity':r['occurrenceIdentity'], 'parentDocumentId':did(r['sourceDocumentId']),
                                              'sourcePath':r['sourcePath'], 'childDocumentId':did(r['targetDocumentId']),
                                              'activationGeneration':str(r['activationGeneration'])}
                                             for r in before['occurrences'] if r['active']
                                             and did(r['sourceDocumentId']) in entry_owners and did(r['targetDocumentId']) in entry_owners]}
        require(exact_equal(I.context(owner_descriptor)[0],terminal['rootedContext']), 'GRAPH_OPERATION_CONTEXT_IDENTITY')
        # RUN019 removes and re-adds edges: ownership is monotone across every
        # actual finalizer boundary, so a split cannot shrink the original owner.
        projection = terminal['rootedProjection']
        require(exact_equal(projection['inputSnapshot'], before)
                and exact_equal(projection['resultingSnapshot'], after), 'RECONNECT_ROOTED_SNAPSHOTS')
        expected_owners = set(entry_owners)
        require(projection['topologyBoundaries'], 'RECONNECT_MISSING_TOPOLOGY_BOUNDARIES')
        for boundary in projection['topologyBoundaries']:
            snapshot_check(boundary)
            for group in sccs(boundary):
                if group & expected_owners: expected_owners |= group
        require(projection['deliveryBasisIdentity']
                and projection['invocationIdentity'] == I.wrapper('rootedInvocationIdentity', {
                    'rootProcessingContextIdentity':I.context(owner_descriptor)[1],
                    'baseInvocationIdentity':terminal['entryInvocationIdentity'],
                    'deliveryBasisIdentity':projection['deliveryBasisIdentity']})
                and projection['companionIdentity'] == I.wrapper('rootedCommitCompanionIdentity', {
                    'rootProcessingContextIdentity':I.context(owner_descriptor)[1],
                    'baseCommitCompanionIdentity':companion['companionIdentity'],
                    'rootedInvocationIdentity':projection['invocationIdentity']}), 'RECONNECT_ROOTED_WRAPPERS')
        owners = {did(v) for v in terminal['ownedDocumentIds']}
        require(len(owners) == len(terminal['ownedDocumentIds']) and owners == expected_owners, 'GRAPH_ROOTED_OWNERSHIP')
        terminal_events(terminal, owners)
        transitions = terminal['managedTransitionReceipts']
        require(len({r['transitionReceiptIdentity'] for r in transitions}) == len(transitions), 'GRAPH_DUPLICATE_TRANSITION')
        for receipt in transitions:
            owner = did(receipt['documentId'])
            require(receipt['sourceInvocationIdentity'] == execution_identity
                    and receipt['originalCauseIdentity'] == (inp['cause']['originalSourceCauseIdentity']
                        if terminal['causeType'] in ('ManagedRevisionCause','ManagedRepresentationCause')
                        else inp['cause']['causeIdentity']), 'GRAPH_TRANSITION_CAUSE')
            require(owner in resulting and receipt['beforeBlueId'] == resulting[owner]['beforeBlueId']
                    and receipt['afterBlueId'] == resulting[owner]['afterBlueId'], 'GRAPH_TRANSITION_ENDPOINTS')
            source_events(terminal, receipt)
        return owners

    def call_check(call):
        before, after = call['before'], call['after']
        receipt_prefix(before, after)
        check_call_carriers(call, ids, require, exact_equal)
        require(type(call['quiescent']) is bool and call['paused'] is (not call['quiescent'])
                and call['diagnostic']['code'] == ('NONE' if call['quiescent'] else 'PROCESSING_PAUSED')
                and call['resourceFailures'] == [], 'GRAPH_DRAIN_BLOCKED_OR_FLAGS')
        selection = call['selection']
        if selection['kind'] == 'MANAGED_EPOCH_APPLICATION':
            selected = selection['managedEpochApplicationWork']
            if selection['rootedRetainedRoot'] is not None:
                require(any(a['work']['workIdentity'] == selected['workIdentity']
                            and a['rootDocumentId'] == selection['rootedRetainedRoot']
                            for a in call['localRetainedApplications']), 'GRAPH_SELECTED_LOCAL_WORK')
            else:
                require(any(a['workIdentity'] == selected['workIdentity']
                            for a in call['ownedRetainedApplications']), 'GRAPH_SELECTED_OWNED_WORK')
        elif selection['kind'] == 'NONE':
            require(not call['entries'] and not call['localRetainedApplications']
                    and not call['ownedRetainedApplications'] and call['quiescent'], 'GRAPH_NONE_SELECTION_WORK')
        else:
            require(selection['kind'] == 'JOURNAL'
                    and (call['entries'] or call['lane'] == 'JOURNAL_CUTOFF' and call['quiescent']), 'GRAPH_JOURNAL_SELECTION')
        require(all(a['published'] and a['publicationFailure'] is None and a['receipt'] is not None
                    for a in call['managedAttempts']), 'GRAPH_MANAGED_ATTEMPT_FAILURE')
        require([a['receipt']['applicationReceiptIdentity'] for a in call['managedAttempts']]
                == [a['applicationReceiptIdentity'] for a in call['ownedRetainedApplications']], 'GRAPH_MANAGED_ATTEMPT_INVENTORY')
        owners = set()
        for terminal in call['terminals']: owners |= terminal_check(terminal, before, call)
        for entry in call['entries']:
            if entry['disposition'] != 'APPLIED': continue
            source_rows = [r for r in appends.values() if r['response']['entryBlueId'] == entry['entry']['blueId']]
            require(len(source_rows) == 1 and entry['closures'], 'GRAPH_LIVE_ENTRY_SOURCE')
            for closure in entry['closures']:
                terminal = next(t for t in call['terminals'] if t['publicationIdentity'] == closure['closureId'])
                key = (entry['entry']['blueId'],terminal['rootedContext']['operationOwnerIdentity'])
                require(key not in live_applications, 'GRAPH_DUPLICATE_RECEIVING_VIEW'); live_applications.add(key)
                require(terminal['causeType'] == 'ExternalEventCause'
                        and terminal['input']['cause']['eventBlueId'] == entry['entry']['blueId']
                        and exact_equal(terminal['input']['cause']['event'],source_rows[0]['response']['exactEntry']), 'GRAPH_LIVE_EXACT_INPUT')
        expected_publications = {c['closureId'] for e in call['entries'] for c in e['closures']}
        expected_publications |= {a['result']['closureId'] for a in call['localRetainedApplications']}
        for app in call['ownedRetainedApplications']:
            matches = [t for t in call['terminals'] if t['invocationIdentity'] == app['contractsInvocationIdentity']
                       and t['commitCompanion']['companionIdentity'] == app['commitCompanionIdentity']]
            require(len(matches) == 1, 'GRAPH_OWNED_APPLICATION_TERMINAL')
            expected_publications.add(matches[0]['publicationIdentity'])
        require(expected_publications == {t['publicationIdentity'] for t in call['terminals']}, 'GRAPH_TERMINAL_INVENTORY')
        for local in call['localRetainedApplications']:
            work = local['work']; key = (did(local['rootDocumentId']), work['workIdentity'])
            require(key not in source_applications, 'GRAPH_DUPLICATE_LOCAL_APPLICATION'); source_applications.add(key)
            source = alias_of[did(work['sourceDocumentId'])]
            terminal = next(t for t in call['terminals'] if t['publicationIdentity'] == local['result']['closureId'])
            cause = terminal['input']['cause']
            require(cause['targetOccurrenceIdentity'] == work['targetOccurrenceIdentity']
                    and did(cause['childDocumentId']) == ids[source], 'GRAPH_LOCAL_TARGET_BINDING')
            target_rows = [r for r in terminal['input']['snapshot']['occurrences']
                           if r['occurrenceIdentity'] == work['targetOccurrenceIdentity']]
            require(len(target_rows) == 1 and did(target_rows[0]['sourceDocumentId']) == did(work['consumerDocumentId'])
                    and target_rows[0]['sourcePath'] == work['targetPath']
                    and target_rows[0]['activationGeneration'] == work['activationGeneration']
                    and target_rows[0]['expectedTargetBlueId'] == cause['beforeBlueId'], 'GRAPH_LOCAL_OCCURRENCE_BINDING')
            if work['representationStep'] is None:
                receipts = [r for r in before[source]['receipts'] if r['receiptIdentity'] == work['sourceReceiptIdentity']]
                require(len(receipts) == 1 and receipts[0]['epoch'] == work['sourceEpoch'], 'GRAPH_LOCAL_SOURCE_RECEIPT')
                terminal = next(t for t in call['terminals'] if t['publicationIdentity'] == local['result']['closureId'])
                cause = terminal['input']['cause']; source_receipt = receipts[0]
                require(terminal['causeType'] == 'ManagedRevisionCause' and cause['toEpoch'] == cause['fromEpoch'] + 1
                        and cause['toEpoch'] == source_receipt['epoch'], 'GRAPH_LOCAL_EPOCH_STEP')
                require(cause['sourceRevisionReceiptIdentity'] == source_receipt['contractsTransitionReceiptIdentity']
                        and cause['afterBlueId'] == source_receipt['afterBlueId']
                        and exact_equal(cause['afterDocument'], source_receipt['afterDocument'])
                        and cause['beforeBlueId'] == (starts[source]['initialBlueId'] if source_receipt['epoch'] == 0 else source_receipt['beforeBlueId']), 'GRAPH_LOCAL_SOURCE_EXACT')
            else:
                step = work['representationStep']; transition = cause['transition']
                require(terminal['causeType'] == 'ManagedRepresentationCause' and cause['fromEpoch'] == cause['toEpoch'] == work['sourceEpoch']
                        and step['causeIdentity'] == cause['causeIdentity'] and step['beforeBlueId'] == cause['beforeBlueId']
                        and step['afterBlueId'] == cause['afterBlueId'], 'GRAPH_REPRESENTATION_WORK_BINDING')
                require(step['before']['anchorReceiptIdentity'] == step['after']['anchorReceiptIdentity'] == transition['anchorReceiptIdentity'] == work['sourceReceiptIdentity']
                        and step['before']['positionIdentity'] == transition['predecessorPositionIdentity']
                        and step['after']['positionIdentity'] == transition['positionIdentity']
                        and step['before']['positionIdentity'] != step['after']['positionIdentity'], 'GRAPH_REPRESENTATION_POSITION')
                require(step['before']['targetPositionIdentity'] == step['after']['targetPositionIdentity'] == cause['targetPositionIdentity']
                        and step['before']['nextRevisionReceiptIdentity'] == step['after']['nextRevisionReceiptIdentity'] == cause['nextRevisionReceiptIdentity'], 'GRAPH_REPRESENTATION_FIXED_TARGET')
                anchors = [r for r in before[source]['receipts'] if r['receiptIdentity'] == transition['anchorReceiptIdentity']]
                require(len(anchors) == 1 and anchors[0]['epoch'] == work['sourceEpoch'], 'GRAPH_REPRESENTATION_ANCHOR')
                original, committed = transition['originalInput'], transition['originalResult']
                original_source = by_id(original['snapshot']['managedDocuments'])[ids[source]]
                committed_source = by_id(committed['resultingDocuments'])[ids[source]]
                proof_receipt = transition['transitionReceipt']
                require(committed['status'] == 'SUCCESS' and original['invocationIdentity'] == committed['invocationIdentity']
                        == committed['platformCommitCompanion']['invocationIdentity']
                        and original_source['epoch'] == committed_source['epoch'] == work['sourceEpoch']
                        and original_source['blueId'] == cause['beforeBlueId'] == proof_receipt['beforeBlueId']
                        and committed_source['afterBlueId'] == cause['afterBlueId'] == proof_receipt['afterBlueId']
                        and proof_receipt['emittedRootEvents'] == [] and proof_receipt in committed['managedTransitionReceipts']
                        and exact_equal(committed_source['document'], cause['afterDocument']), 'GRAPH_REPRESENTATION_ORIGINAL_COMMIT')
        owned_work_check(call)
        for alias in aliases:
            old, new = before[alias], after[alias]
            if ids[alias] not in owners:
                require(exact_equal(old, new), 'GRAPH_UNOWNED_INDEPENDENT_STATE')
            appended = new['receipts'][len(old['receipts']):]
            for receipt in appended:
                matches = [(t,r) for t in call['terminals'] for r in t['managedTransitionReceipts']
                           if did(r['documentId']) == ids[alias]
                           and any(did(owner) == ids[alias] for owner in t['ownedDocumentIds'])
                           and r['transitionReceiptIdentity'] == receipt['contractsTransitionReceiptIdentity']]
                require(len(matches) == 1, 'GRAPH_PUBLISHED_RECEIPT_TRANSITION')
                terminal, transition = matches[0]
                require(receipt['commitCompanionIdentity'] == terminal['commitCompanion']['companionIdentity']
                        and receipt['originalCauseIdentity'] == transition['originalCauseIdentity']
                        and receipt['afterBlueId'] == transition['afterBlueId'], 'GRAPH_PUBLISHED_RECEIPT_BINDING')
                bind_events(receipt['emittedEvents'], transition['emittedRootEvents'])
        progress_check(call['progressBefore'], before)
        progress_check(call['progressAfter'], after)
        historical_check(call)
        return after

    current = {}
    for alias in aliases:
        start = starts[alias]
        require(set(captures[alias]) == {'initialBlueId','initializedBlueId','documentId','initialExact','epoch0Receipt'}
                and exact_equal(captures[alias],{k:start[k] for k in captures[alias]}), 'RECONNECT_START_CAPTURE')
        require(start['documentId'] == ids[alias] == start['initialBlueId']
                and start['initialBlueId'] != start['initializedBlueId'], 'RECONNECT_SAVED_AUTHORED_ID')
        require(start['initialExact']['peers'] == {} and scalar(start['initialExact']['observed']) == 0
                and scalar(start['initialExact']['touches']) == 0, 'RECONNECT_EMPTY_START')
        require(start['retained']['historyBasis']['initialDocumentBlueId'] == start['initialBlueId']
                and start['epoch0Receipt']['epoch'] == 0 and start['epoch0Receipt']['beforeBlueId'] is None
                and start['epoch0Receipt']['afterBlueId'] == start['initializedBlueId'], 'RECONNECT_START_HISTORY')
        current[alias] = start['retained']
    receipt_prefix(current,current)
    require([r['request']['op'] for r in rows[:2]] == ['start','start']
            and all(r['request']['op'] != 'start' for r in rows[2:]), 'RECONNECT_SEPARATE_START_ORDER')
    expected_appends = ['E100','E200','E250','E300','E350','E500','E400','E450']
    require(list(appends) == expected_appends, 'RECONNECT_APPEND_ORDER')
    final_journal = observation['journal']
    require([e['blueId'] for e in final_journal] == [captures[k] for k in expected_appends], 'RECONNECT_JOURNAL_ORDER')
    expected = {'E100':('A','attach',100,{'edge':'b','source':{'blueId':starts['B']['initialBlueId']}}),
                'E200':('B','attach',200,{'edge':'a','source':{'blueId':starts['A']['initialBlueId']}}),
                'E250':('B','emit',250,{'to':'A','next':'stop'}),
                'E300':('A','detach',300,{'edge':'b'}),
                'E350':('B','emit',350,{'to':'A','next':'stop'}),
                'E500':('B','emit',500,{'to':'A','next':'stop'}),
                'E400':('A','attach',400,{'edge':'b','source':{'blueId':starts['B']['initialBlueId'] if variant == 'saved-authored' else captures['B250']['blueId']}}),
                'E450':('A','touch',450,{})}
    previous_by_timeline = {}
    def plain(value):
        if isinstance(value,list): return [plain(v) for v in value]
        if not isinstance(value,dict): return value
        if 'value' in value: return value['value']
        return {k:plain(v) for k,v in value.items()}
    for key,entry in zip(expected_appends,final_journal):
        row=appends[key]; request,response=row['request'],row['response']; owner,operation,time,payload=expected[key]
        require(request['target'] == owner and request['operation'] == operation and request['channel'] == 'owner'
                and request['timestampUs'] == str(time) and request['exactDocumentPrecondition'] is False,
                'RECONNECT_LITERAL_REQUEST')
        request_exact = {'description':'Operation-specific request payload.'}
        for field,value in payload.items():
            request_exact[field] = value if isinstance(value,dict) else {
                'type':starts[owner]['initialExact']['contracts'][operation]['request'][field]['type'], 'value':value}
        require(captures[key] == response['entryBlueId'] == entry['blueId']
                and exact_equal(entry,response['actualEntry']) and exact_equal(entry['exact'],response['exactEntry'])
                and entry['timestampMicros'] == time and scalar(entry['exact']['timestamp']) == time
                and exact_equal(entry['request'],request_exact)
                and exact_equal(entry['exact']['message']['request'],request_exact)
                and scalar(entry['exact']['message']['operation']) == operation
                and scalar(entry['exact']['message']['channel']) == 'owner'
                and scalar(entry['exact']['message']['requireExactDocumentVersion']) is False,
                'RECONNECT_EXACT_REQUEST_BYTES')
        # Compare actual per-timeline predecessor links; global registration order
        # intentionally differs from cross-timeline source order near E400.
        timeline = str(entry['timeline'])
        require(entry['previousEntryBlueId'] == previous_by_timeline.get(timeline), 'RECONNECT_TIMELINE_PREDECESSOR')
        previous_by_timeline[timeline] = entry['blueId']
    require(appends['E100']['request']['request']['source'] == {'$capture':'B.initialBlueId'}
            and appends['E200']['request']['request']['source'] == {'$capture':'A.initialBlueId'}
            and appends['E400']['request']['request']['source'] == {'$capture':rule['selectionByVariant'][variant]}, 'RECONNECT_SAVED_SELECTION_EXPRESSION')

    progress = None; current_stage = None; observed_stages = []; restarts = []; seen_captures = set(aliases)
    phase_order = []; applied_owner_entries = []; capture_count = 0
    for row in rows[2:]:
        request,response = row['request'],row['response']; op=request['op']
        if op == 'append':
            require(response['exactEntry']['message']['document'] == {'blueId':current[request['target']]['blueId']},
                    'RECONNECT_ACTUAL_REQUEST_TARGET')
            seen_captures.add(request['capture']); continue
        if op == 'drainThroughEntry':
            phase = phases[request['capture']]; phase_order.append(request['capture'])
            require(exact_equal(phase,response), 'RECONNECT_PHASE_RESPONSE_BINDING')
            require(request['maxSelections'] == rule['maxSelectionsPerPhase'] == 32
                    and request['budgetPerCall'] == {'maxEntries':1,'maxManagedApplications':1}
                    and 1 <= len(phase['calls']) <= 32, 'RECONNECT_UNCHANGED_BOUND')
            cutoff_id=captures[request['through']['$capture']]; required=captures[request['mustApplyEntry']['$capture']]
            required_applied=[]
            for index,call in enumerate(phase['calls']):
                require(call['index'] == index and exact_equal(current,call['before'])
                        and call['cutoffEntryBlueId'] == cutoff_id, 'RECONNECT_CALL_SEQUENCE')
                require(call['lane'] == ('RETAINED' if call['selection']['kind'] == 'MANAGED_EPOCH_APPLICATION' else 'JOURNAL_CUTOFF'), 'RECONNECT_DRIVER_SELECTION_LANE')
                stop = call['quiescent']
                require(stop is (index == len(phase['calls'])-1), 'RECONNECT_CUTOFF_COMPLETION_BOUNDARY')
                require({e['blueId'] for e in call['progressBefore']['journal']} == {captures[k] for k in seen_captures if k in appends}, 'RECONNECT_REGISTERED_ENTRY_INVENTORY')
                cutoff = call['progressBefore']['journalOrders'][cutoff_id]['components']
                for entry in call['entries']:
                    require(call['progressBefore']['journalOrders'][entry['entry']['blueId']]['components'] <= cutoff, 'RECONNECT_FUTURE_LIVE_INPUT')
                    if entry['disposition'] == 'APPLIED':
                        parent = ids[entry_by_id[entry['entry']['blueId']]['request']['target']]
                        for closure in entry['closures']:
                            terminal = next(t for t in call['terminals'] if t['publicationIdentity'] == closure['closureId'])
                            canonical=terminal['rootedContext']['canonicalRootDocumentId']
                            entry_owners=next(s for s in sccs(terminal['input']['snapshot']) if canonical in s)
                            if parent in entry_owners:
                                required_applied.append(entry['entry']['blueId']); applied_owner_entries.append(entry['entry']['blueId'])
                    else: require(entry['disposition'] == 'NO_MATCH' and not entry['closures'], 'RECONNECT_ENTRY_DISPOSITION')
                current=call_check(call); progress=call['progressAfter']; all_calls.append(call)
            require(required_applied.count(required) == 1, 'RECONNECT_REQUIRED_ENTRY_APPLIED_ONCE')
        elif op == 'captureHeadReceipt':
            capture_count += 1; require(request['capture'] == 'B250' and 'E300' not in seen_captures, 'RECONNECT_RETAINED_CAPTURE_TIMING')
            require(exact_equal(response['before'],current) and exact_equal(response['after'],current), 'RECONNECT_CAPTURE_MUTATION')
            saved=response['receipt']; actual=current['B']['receipts'][-1]
            require(saved['blueId'] == actual['afterBlueId'] and exact_equal({k:v for k,v in saved.items() if k != 'blueId'},actual)
                    and actual['sourceEntry']['blueId'] == captures['E250']
                    and actual['epoch'] == current['B']['epoch'] and exact_equal(saved,captures['B250']), 'RECONNECT_EXACT_RETAINED_CAPTURE')
            seen_captures.add('B250')
        elif op == 'observeReconnect':
            stage=request['capture']; observed_stages.append(stage); current_stage=stage
            require(exact_equal(response,phases[stage]) and exact_equal(current,response['records'])
                    and exact_equal(current,response['before']) and exact_equal(current,response['after']), 'RECONNECT_OBSERVATION_MUTATION')
            progress_check(response,current)
            require(set(response['captures']) == seen_captures
                    and all(exact_equal(value,captures[key]) for key,value in response['captures'].items()), 'RECONNECT_CAPTURE_LIFETIME')
            progress=response
        elif op == 'restart':
            restarts.append(current_stage)
            require(request['submitNewEntries'] is False and request['assertExactRetainedState'] is True
                    and exact_equal(current,response['before']) and exact_equal(current,response['after'])
                    and response['beforeCommandCount'] == response['afterCommandCount'] == len([k for k in seen_captures if k in appends])
                    and exact_equal(response['progressBefore'],response['progressAfter']), 'RECONNECT_RESTART_EXACT_STATE')
            for key in response['progressBefore']:
                require(exact_equal(progress[key],response['progressBefore'][key]), 'RECONNECT_RESTART_PROGRESS_BINDING')
            progress_check(response['progressAfter'],current); progress=response['progressAfter']
        elif op == 'processNext':
            require(request['capture'] == 'subject' and request['root'] == 'A'
                    and request['mustSelect'] == {'$capture':'E500'}, 'RECONNECT_FUTURE_OWNER')
            output=response['output']; structured_output(output,weights); identity_check(output)
            require(exact_equal(output,phases['subject']), 'RECONNECT_SUBJECT_PHASE')
            call=response['actualDrain']; require(call['lane'] == 'DIRECT_ROOT'
                    and exact_equal(call['before'],current), 'RECONNECT_FUTURE_CALL_BINDING')
            current=call_check(call); all_calls.append(call); progress=call['progressAfter']
            require(exact_equal(current,response['retainedRecords']) and len(call['terminals']) == 1
                    and call['terminals'][0]['publicationIdentity'] == response['closureId'] == output['publicationIdentity']
                    and call['terminals'][0]['causeType'] == 'ExternalEventCause'
                    and call['terminals'][0]['input']['cause']['eventBlueId'] == captures['E500']
                    and exact_equal(call['terminals'][0]['gas'],output['gas']), 'RECONNECT_FUTURE_LIVE_TERMINAL')
            applied_owner_entries.append(captures['E500'])
        elif op == 'assertQuiescent':
            require(request['root'] in aliases and response.get('quiescent') is True and response['entries'] == []
                    and exact_equal(response['before'],current) and exact_equal(response['after'],current), 'RECONNECT_FINAL_QUIESCENCE')
        else: require(False,'RECONNECT_UNEXPECTED_PRIMITIVE')
    require(phase_order == ['firstAttachment','firstCycle','oldEvent','split','missedEvent','reconnect','marker450']
            and observed_stages == ['joined','detached','apartAdvanced','reconnectedAt400','markedAt450','final']
            and restarts == rule['restarts'] == ['detached','reconnectedAt400','final'] and capture_count == 1, 'RECONNECT_REQUIRED_SEQUENCE')
    require(all(applied_owner_entries.count(captures[e]) == 1 for e in expected_appends), 'RECONNECT_OWNED_ENTRY_EXACTLY_ONCE')
    require(exact_equal(current,observation['records']), 'RECONNECT_FINAL_RECORDS')

    def occurrence(stage):
        snapshot=phases[stage]['selectedSnapshots']['A']
        rows=[r for r in snapshot['occurrences'] if did(r['sourceDocumentId']) == ids['A'] and r['sourcePath'] == rule['path']]
        require(len(rows) == 1,'RECONNECT_OCCURRENCE_INVENTORY'); return rows[0]
    joined,detached,readded = (occurrence(s) for s in ('joined','detached','reconnectedAt400'))
    require(joined['active'] is True and joined['activationGeneration'] == 1
            and detached['active'] is False and detached['pendingHistoricalEpoch'] is None
            and detached['pendingRepresentationCursor'] is None
            and detached['activationGeneration'] == joined['activationGeneration'] + 1
            and detached['occurrenceIdentity'] != joined['occurrenceIdentity']
            and readded['activationGeneration'] == detached['activationGeneration']
            and readded['occurrenceIdentity'] == detached['occurrenceIdentity']
            and all(did(r['targetDocumentId']) == ids['B'] for r in (joined,detached,readded)), 'RECONNECT_RETIREMENT_GENERATION')
    reconnect_calls=phases['reconnect']['calls']
    new_rows=[]; reconnect_plans=[]; imported=[]
    for call in reconnect_calls:
        for terminal in call['terminals']:
            for row in terminal['outputSnapshot']['occurrences']:
                if row['occurrenceIdentity'] == readded['occurrenceIdentity'] and row['pendingHistoricalEpoch'] is not None: new_rows.append(row)
        for progress in (call['progressBefore'],call['progressAfter']):
            for plan in progress['plans']['A']:
                if plan['targetOccurrenceIdentity'] != readded['occurrenceIdentity']: continue
                reconnect_plans.append((plan,progress['barriers'][plan['barrierIdentity']]))
        for terminal in call['terminals']:
            cause=terminal['input']['cause']
            if terminal['causeType'] == 'ManagedRevisionCause' and cause['targetOccurrenceIdentity'] == readded['occurrenceIdentity']:
                imported.append(cause)
    initial_epoch = -1 if variant == 'saved-authored' else captures['B250']['epoch']
    require(new_rows and new_rows[0]['pendingHistoricalEpoch'] == initial_epoch
            and new_rows[0]['expectedTargetBlueId'] == expected['E400'][3]['source']['blueId'], 'RECONNECT_NEW_GENERATION_INITIAL_CURSOR')
    require(reconnect_plans and imported, 'RECONNECT_MISSING_REAL_CATCHUP')
    source_before=phases['apartAdvanced']['records']['B']
    cutoff_order=phases['reconnectedAt400']['journalOrders'][captures['E400']]['components']
    eligible=[r for r in source_before['receipts'] if r['sourceOrder']['components'] <= cutoff_order]
    frontier=max(r['epoch'] for r in eligible)
    for plan,barrier in reconnect_plans:
        require(plan['sourceDocumentId'] == {'value':ids['B']} and plan['consumerDocumentId'] == {'value':ids['A']}
                and plan['targetPath'] == rule['path'] and plan['activationGeneration'] == readded['activationGeneration']
                and plan['admittedSourceEpoch'] == initial_epoch
                and plan['requiredThroughSourceEpoch'] == frontier
                and barrier['causeOrder']['components'] == cutoff_order, 'RECONNECT_FROZEN_FRONTIER')
    require([c['toEpoch'] for c in imported] == list(range(initial_epoch+1,frontier+1)), 'RECONNECT_MISSED_SUCCESSORS_ONCE')
    require(all(c['beforeBlueId'] == (starts['B']['initialBlueId'] if c['toEpoch'] == 0 else source_before['receipts'][c['toEpoch']]['beforeBlueId'])
                and c['sourceRevisionReceiptIdentity'] == source_before['receipts'][c['toEpoch']]['contractsTransitionReceiptIdentity'] for c in imported), 'RECONNECT_IMPORTED_ORIGINAL_RECEIPTS')
    for stage in observed_stages:
        observed=phases[stage]; records=observed['records']
        expected_observed={'joined':1,'detached':1,'apartAdvanced':1,
                           'reconnectedAt400':rule['observedAt400ByVariant'][variant],
                           'markedAt450':rule['observedAt400ByVariant'][variant],
                           'final':rule['observedAt500ByVariant'][variant]}[stage]
        expected_events={'joined':1,'detached':1,'apartAdvanced':2,'reconnectedAt400':2,'markedAt450':2,'final':3}[stage]
        require(scalar(records['A']['exactDocument']['observed']) == expected_observed
                and scalar(records['A']['exactDocument']['touches']) == (1 if stage in ('markedAt450','final') else 0)
                and len(records['B']['events']) == expected_events and records['A']['events'] == [], 'RECONNECT_STAGE_EVENTS_AND_STATE')
        for alias in aliases:
            ready=observed['readiness'][alias]
            require(ready['ready'] is True and ready['status'] == 'READY'
                    and ready['committedEpoch'] == ready['readyEpoch'] == records[alias]['epoch']
                    and ready['committedBlueId'] == ready['readyBlueId'] == records[alias]['blueId']
                    and ready['activeBarrierIdentities'] == [] and ready['waitingCode'] is None, 'RECONNECT_STAGE_READINESS')
        groups={frozenset(g) for snap in observed['selectedSnapshots'].values() for g in sccs(snap)}
        expected_groups={frozenset(ids.values())} if stage in ('joined','reconnectedAt400','markedAt450','final') else {frozenset([ids[a]]) for a in aliases}
        require(groups == expected_groups,'RECONNECT_STAGE_COMPONENTS')
        if stage in ('reconnectedAt400','markedAt450'):
            require(all(r['sourceEntry'] is None or r['sourceEntry']['blueId'] != captures['E500']
                        for record in records.values() for r in record['receipts']), 'RECONNECT_FUTURE_RECEIPT_EXCLUSION')
    require(exact_equal(phases['apartAdvanced']['records']['B']['events'],phases['markedAt450']['records']['B']['events']), 'RECONNECT_NO_DUPLICATE_SOURCE_EVENTS')
    for alias in aliases:
        receipt_prefix(phases['apartAdvanced']['records'],current)
        for edge in observation['selectedSnapshots'][alias]['occurrences']:
            if edge['active']:
                docs=by_id(observation['selectedSnapshots'][alias]['managedDocuments'])
                require(edge['pendingHistoricalEpoch'] is None and edge['pendingRepresentationCursor'] is None
                        and edge['expectedTargetBlueId'] == docs[did(edge['targetDocumentId'])]['blueId'], 'RECONNECT_FINAL_EXACT_ACTIVE_EDGE')
    events=current['B']['events']
    require(len({e['eventOccurrenceIdentity'] for e in events}) == 3, 'RECONNECT_DUPLICATE_ORIGINAL_EVENT')
    output_events=rec['output']['events']
    require(len(output_events) == 1 and output_events[0]['origin'] == 'B'
            and output_events[0]['occurrenceIdentity'] == events[-1]['eventOccurrenceIdentity']
            and output_events[0]['blueId'] == events[-1]['eventBlueId']
            and exact_equal(output_events[0]['exactEvent'],events[-1]['exactEvent']), 'RECONNECT_FUTURE_EVENT_BINDING')

    require(all((token,goal) in executed_positions for token,goal in future_goals.items()), "RECONNECT_FUTURE_GOAL_NOT_ACTUALLY_TRAVERSED")
