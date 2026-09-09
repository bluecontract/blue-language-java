"""Observe every independently admitted incoming owner; never select source semantics by its count."""

def check_incoming_fanout(rec, rule, variant, require, exact_equal):
    ids = rec['documentIds']
    source = rule['source']
    count = rule['observerCounts'][variant]
    require(rule['observerCounts'] == {'standalone':0,'5000-observers':5000}, 'FANOUT_REQUIRED_SCALE')
    aliases = [f'P{i:04d}' for i in range(count)]
    require(set(ids) == {source,*aliases} and len(set(ids.values())) == count+1, 'FANOUT_REAL_DOCUMENT_COUNT')
    rows = [r for r in rec['transcript'] if r.get('completed') is True]
    starts = [r for r in rows if r['request']['op'] == 'start']
    require([r['request']['alias'] for r in starts] == [source,*aliases], 'FANOUT_ADMISSION_INVENTORY')
    require([r['request']['op'] for r in rows] == ['start']*(count+1)+['observeFanout','append','processNext','observeFanout','restart','observeFanout'], 'FANOUT_EXACT_PUBLIC_CALLS')
    initial = starts[0]['response']
    phases = rec['phaseRecords']
    before, after, restarted = [phases[k] for k in ('observersBefore','observersAfter','observersRestart')]
    require(exact_equal(after,restarted), 'FANOUT_RESTART_CHANGED')
    for view in (before,after,restarted):
        require(len(view['observers']) == count and [o['alias'] for o in view['observers']] == aliases, 'FANOUT_OBSERVER_INVENTORY')
        require(set(view['sourceRecords']) == {source}, 'FANOUT_SOURCE_RECORD_SCOPE')
        selected = view['sourceSelectedSnapshot']
        require([d['documentId']['value'] for d in selected['managedDocuments']] == [ids[source]]
                and selected['occurrences'] == [] and len(selected['components']) == 1
                and selected['components'][0]['orderedMemberDocumentIds'] == [{'value':ids[source]}], 'FANOUT_SOURCE_MEMBERSHIP')
    entry = next(r['response'] for r in rows if r['request']['op'] == 'append')
    require(before['journal'] == [] and after['journal'] == [entry['actualEntry']], 'FANOUT_EXTRA_ENTRY')
    for start, old, new in zip(starts[1:],before['observers'],after['observers']):
        name = old['alias']
        authored, actual = start['request'], start['response']
        require(authored['admission'] == 'FULL_HISTORY' and authored['source'] == 'examples/iteration2/parent.yaml'
                and authored['bindings'] == {'/name':'RCP2 Fanout '+name,'/contracts/owner/timeline/timelineId':'rcp2/fanout/'+name,'/child':{'$capture':source+'.initializedBlueId'}}, 'FANOUT_LITERAL_OBSERVER')
        require(actual['documentId'] == ids[name] == actual['initialBlueId']
                and actual['initialExact']['child'] == {'blueId':initial['initializedBlueId']}, 'FANOUT_ACTUAL_ADMISSION')
        retained = actual['retained']
        require(retained['epoch'] == old['epoch'] == new['epoch'] == 0
                and retained['blueId'] == old['blueId'] == new['blueId'] == actual['initializedBlueId'], 'FANOUT_OBSERVER_ADVANCED')
        require(old['receiptIdentities'] == [actual['epoch0Receipt']['receiptIdentity']]
                and retained['receipts'] == [actual['epoch0Receipt']], 'FANOUT_INITIAL_RECEIPT')
        for field, retained_field in [('documentId','documentId'),('exactDocument','exactDocument'),('historyBasis','historyBasis'),('occurrences','occurrences')]:
            require(exact_equal(old[field],retained[retained_field]), 'FANOUT_START_BINDING:'+field)
        for field in ('documentId','blueId','epoch','exactDocument','historyBasis','receiptIdentities','readyThrough','occurrences'):
            require(exact_equal(old[field],new[field]), 'FANOUT_SOURCE_PUBLISHED_OBSERVER:'+field)
        require(old['nextLiveInput'] is None and new['nextLiveInput'] == entry['entryBlueId'], 'FANOUT_REQUIRED_WORK_LOST')
        edges = old['occurrences']
        require(len(edges) == 1 and edges[0]['active'] is True and edges[0]['sourcePath'] == '/child'
                and edges[0]['sourceDocumentId'] == {'value':ids[name]}
                and edges[0]['targetDocumentId'] == {'value':ids[source]}
                and edges[0]['expectedTargetBlueId'] == initial['initializedBlueId'], 'FANOUT_INCOMING_EDGE')
        for observed in (old,new):
            ready = observed['readiness']
            require(ready['committedBlueId'] == observed['blueId'] and ready['committedEpoch'] == 0, 'FANOUT_FALSE_COMMITTED_STATE')
            if ready['ready']:
                require(ready['readyBlueId'] == observed['blueId'] and ready['readyEpoch'] == 0, 'FANOUT_FALSE_READY_POSITION')
    output = rec['output']
    require(set(output['before']) == set(output['after']) == set(output['selectedBefore']) == set(output['selectedAfter']) == {source}, 'FANOUT_SELECTED_SOURCE_ONLY')
    require(output['sourceBefore'] == output['sourceAfter'] == {}, 'FANOUT_COMPACT_SCOPE')
    require(all(r['documentId'] == {'value':ids[source]} for r in output['computedReceipts']), 'FANOUT_COMPUTED_INCOMING_WORK')
    require(exact_equal(after['sourceRecords'],rec['restart']['before'])
            and exact_equal(rec['restart']['before'],rec['restart']['after']), 'FANOUT_SOURCE_RESTART')
    require(before['sourceRecords'][source]['receipts'] == after['sourceRecords'][source]['receipts'][:1], 'FANOUT_SOURCE_HISTORY_PREFIX')
    require(rec['restart']['beforeCommandCount'] == rec['restart']['afterCommandCount'] == 1, 'FANOUT_RESTART_EXECUTED_ENTRIES')
