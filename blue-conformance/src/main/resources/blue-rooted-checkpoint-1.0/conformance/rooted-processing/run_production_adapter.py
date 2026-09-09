#!/usr/bin/env python3
"""RCP draft.2 candidate adapter driver. A real revised runtime adapter is required.
The driver pins artifact bytes outside the adapter, requires every named variant,
loads per-variant raw evidence, checks positive anchors and gas arithmetic, and
compares structured observations. It does not cryptographically prove a driver
honestly executed arbitrary code: adapters and semantic extractors remain reviewed
trusted test components. Recipe-only cases cannot receive PASS from this runner.
"""
from __future__ import annotations
import argparse,copy,hashlib,json,re,secrets,subprocess,sys,time,zipfile
from pathlib import Path
HEX=re.compile(r'[0-9a-f]{64}\Z');COMMIT=re.compile(r'[0-9a-f]{40}\Z')

def exact_equal(a,b):
    if type(a) is not type(b):return False
    if isinstance(a,dict):return a.keys()==b.keys() and all(exact_equal(a[k],b[k]) for k in a)
    if isinstance(a,list):return len(a)==len(b) and all(exact_equal(x,y) for x,y in zip(a,b))
    return a==b

def lookup(data,path):
    cur=data
    for key in path.split('.'):
        if isinstance(cur,list):cur=cur[int(key)]
        elif isinstance(cur,dict) and key in cur:cur=cur[key]
        else:raise ValueError('Missing observation: '+path)
    return cur

def evaluate(observations,assertions):
    count=0
    for a in assertions:
        value=lookup(observations,a['actual']);op=a['op']
        if op=='equals':ok=exact_equal(value,a['expected'])
        elif op=='isTrue':ok=value is True
        elif op=='allEqual':ok=isinstance(value,list) and len(value)>=2 and all(exact_equal(v,value[0]) for v in value[1:])
        else:raise ValueError('Unknown assertion '+op)
        if not ok:raise AssertionError(a['actual']+' failed '+op)
        count+=1
    if not count:raise ValueError('Zero assertions is not a pass')
    return count

def require(ok,message):
    if not ok:raise ValueError(message)

def integer(x,minval=0):return type(x) is int and x>=minval

def digest_bytes(b):return hashlib.sha256(b).hexdigest()

def literal_sources(suite,fixture,plan):
    """Load exact declared examples, including maintained pre-iteration2 YAML."""
    steps=plan['setup']+[s for variant in plan['variants'].values() for s in variant]
    names={row['source'] for row in fixture['input']['documents']+steps if 'source' in row}
    sources={}
    for name in sorted(names):
        path=Path(name)
        require(not path.is_absolute() and '..' not in path.parts
                and path.parts[0]=='examples' and path.suffix=='.yaml','LITERAL_SOURCE_PATH')
        full=(suite/path).resolve()
        require(full.is_relative_to((suite/'examples').resolve()) and full.is_file(),'LITERAL_SOURCE_MISSING')
        sources[name]=full.read_text()
    return sources

def verified_tariff_weights(suite):
    """Derive the complete allowed table from three pinned governing manifests."""
    table=json.loads((suite/'tariff-weights.json').read_text())
    sources=table.get('packagedTariffs',[])
    require(len(sources)==3 and {s['role'] for s in sources}=={'contracts','bex','coordination'},'TARIFF_SOURCE_INVENTORY')
    texts={}
    for source in sources:
        path=Path(source['path'])
        require(not path.is_absolute() and '..' not in path.parts,'TARIFF_SOURCE_PATH')
        data=(suite/path).read_bytes()
        require(digest_bytes(data)==source['sha256'],'TARIFF_SOURCE_HASH')
        texts[source['role']]=data.decode()
    expected={}
    def add(namespace,pairs):
        for name,weight in pairs:
            key=namespace+'.'+name
            require(key not in expected,'TARIFF_COUNTER_COLLISION')
            expected[key]=int(weight)
    blocks=re.findall(r'^  (processor|semantic):\n    counterCount: (\d+)\n    counters:\n((?:      \w+: \d+\n)+)',texts['contracts'],re.M)
    require(len(blocks)==2,'CONTRACTS_TARIFF_SHAPE')
    for namespace,count,body in blocks:
        pairs=re.findall(r'^      (\w+): (\d+)$',body,re.M)
        require(len(pairs)==int(count),'CONTRACTS_TARIFF_COUNT');add(namespace,pairs)
    match=re.search(r'^counterCount: (\d+)\ncounters:\n((?:  \w+: \d+\n)+)',texts['bex'],re.M)
    require(match is not None,'BEX_TARIFF_SHAPE')
    pairs=re.findall(r'^  (\w+): (\d+)$',match[2],re.M)
    require(len(pairs)==int(match[1]),'BEX_TARIFF_COUNT');add('bex',pairs);add('runtime',pairs)
    pairs=re.findall(r'^- name: (\w+)\n  weight: (\d+)$',texts['coordination'],re.M)
    require(pairs and len(pairs)==len(re.findall(r'^- name:',texts['coordination'],re.M)),'COORDINATION_TARIFF_SHAPE')
    add('runtime',pairs)
    require(exact_equal(expected,table['weights']),'TARIFF_DERIVATION_MISMATCH')
    return expected

def check_artifact(path,expected):
    require(path.is_file(),'ARTIFACT_MISSING')
    actual=digest_bytes(path.read_bytes())
    require(isinstance(expected,str) and HEX.fullmatch(expected) is not None and expected!='0'*64,'ARTIFACT_PIN_INVALID')
    require(actual==expected,'ARTIFACT_SHA_MISMATCH')
    return actual

def embedded_entries(path):
    """Independent nested JAR byte inventory, not adapter-reported provenance."""
    out={}
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.endswith('.jar'):out[n]=digest_bytes(z.read(n))
    return out

def source_lock(path,expected,artifact,dependencies,specification):
    raw=path.read_bytes();require(isinstance(expected,str) and HEX.fullmatch(expected) and digest_bytes(raw)==expected,'SOURCE_LOCK_HASH')
    lock=json.loads(raw)
    require(isinstance(lock,dict) and set(lock)=={'artifactSha256','sourceCommits','dependencies','specificationSetSha256'},'SOURCE_LOCK_FIELDS')
    require(lock['artifactSha256']==artifact and lock['dependencies']==dependencies and lock['specificationSetSha256']==specification,'SOURCE_LOCK_BINDING')
    cs=lock['sourceCommits'];require(isinstance(cs,dict) and {'language','bex','coordination','myos'}<=set(cs),'SOURCE_LOCK_COMMITS')
    require(all(isinstance(v,str) and COMMIT.fullmatch(v) and v!='0'*40 for v in cs.values()),'SOURCE_LOCK_COMMITS')
    return lock

def literal_steps(plan,variant,transcript):
    """Check semantic completions, not the physical number of calls/retries.

    The supplied literal plans are ordered arrays. A repeat expands in ascending
    repeatIndex order. Retry/low-level records may repeat a step while completed
    is false; exactly one completed=True record is required per semantic step.
    """
    steps=plan['setup']+plan['variants'][variant]
    expected=[];allowed={}
    for step in steps:
        key=step['stepId'];repeat=step.get('repeat',1)
        require(isinstance(key,str) and key and key not in allowed,'DUPLICATE_PLAN_STEP')
        require(type(repeat) is int and repeat>0,'BAD_PLAN_REPEAT')
        allowed[key]=repeat
        expected.extend((key,i) for i in range(repeat))
    require(isinstance(transcript,list),'TRANSCRIPT_REQUIRED')
    completed=[];seen=set()
    for row in transcript:
        require(isinstance(row,dict),'BAD_TRANSCRIPT_ROW')
        key=row.get('planStepId')
        require(key in allowed,'UNKNOWN_LITERAL_STEP')
        i=row.get('repeatIndex',0)
        require(type(i) is int and 0<=i<allowed[key],'STEP_REPEAT_INDEX')
        if 'completed' in row:require(type(row['completed']) is bool,'COMPLETION_FLAG')
        if row.get('completed') is True:
            token=(key,i)
            require(token not in seen,'DUPLICATE_STEP_COMPLETION')
            seen.add(token);completed.append(token)
    require(len(completed)==len(expected),'INCOMPLETE_LITERAL_STEPS')
    require(completed==expected,'LITERAL_STEP_ORDER')


def gas_check(g,budget,weights):
    require(isinstance(g,dict) and set(g)=={'charges','total','rejected'},'GAS_FIELDS')
    require(integer(budget),'BAD_BUDGET');require(integer(g['total']),'BAD_TOTAL')
    require(isinstance(g['charges'],list),'TRACE_REQUIRED')
    used=0
    for i,c in enumerate(g['charges']):
        require(isinstance(c,dict) and set(c)=={'sequence','label','quantity','weight','amount'},'CHARGE_FIELDS')
        require(type(c['sequence']) is int and c['sequence']==i,'CHARGE_ORDER')
        require(c['label'] in weights,'UNKNOWN_COUNTER')
        require(integer(c['quantity'],1) and integer(c['weight'],1) and integer(c['amount'],1),'CHARGE_NUMERIC')
        require(c['weight']==weights[c['label']],'WRONG_TARIFF')
        require(c['quantity']*c['weight']==c['amount'],'CHARGE_ARITHMETIC')
        used+=c['amount'];require(used<=budget,'TRACE_EXCEEDS_BUDGET')
    require(used==g['total'],'TOTAL_MISMATCH')
    rej=g['rejected']
    if rej is not None:
        require(isinstance(rej,dict) and set(rej)=={'sequence','label','quantity','weight','amount','remaining'},'REJECTED_FIELDS')
        require(rej['sequence']==len(g['charges']) and type(rej['sequence']) is int,'REJECTED_ORDER')
        require(rej['label'] in weights and rej['weight']==weights[rej['label']],'REJECTED_TARIFF')
        require(integer(rej['quantity'],1) and integer(rej['weight'],1) and integer(rej['amount'],1),'REJECTED_NUMERIC')
        require(rej['quantity']*rej['weight']==rej['amount'],'REJECTED_ARITHMETIC')
        require(type(rej['remaining']) is int and rej['remaining']==budget-used and rej['amount']>rej['remaining'],'NOT_ACTUAL_GAS_BOUNDARY')
    return used

def structured_output(o,weights,allow_zero_budget=False):
    required={'status','causeKind','budget','before','after','ownedWrites','events','semanticReceipts','sourceBefore','sourceAfter','gas'}
    require(isinstance(o,dict) and required<=set(o),'OUTPUT_FIELDS')
    require(o['status'] in ('SUCCESS','GAS_LIMIT_EXCEEDED','REJECTED','NEEDS_RESOURCES'),'OUTPUT_STATUS')
    require(o['causeKind'] in ('LIVE','ADMISSION','MANAGED_REVISION','MANAGED_REPRESENTATION'),'OUTPUT_KIND')
    require(isinstance(o['before'],dict) and o['before'] and isinstance(o['after'],dict),'STATE_REQUIRED')
    require(isinstance(o['ownedWrites'],list) and all(isinstance(x,str) and x for x in o['ownedWrites']),'OWNERS_REQUIRED')
    require(len(o['ownedWrites'])==len(set(o['ownedWrites'])),'DUPLICATE_WRITE')
    require(isinstance(o['events'],list) and isinstance(o['semanticReceipts'],list),'EVENT_RECEIPT_ARRAYS')
    require(isinstance(o['sourceBefore'],dict) and isinstance(o['sourceAfter'],dict),'SOURCE_RECORDS')
    gas_check(o['gas'],o['budget'],weights)
    if o['status']=='SUCCESS':
        require(o['gas']['rejected'] is None,'SUCCESS_WITH_REJECTED_CHARGE')
        require(len(o['gas']['charges'])>0 and len(o['semanticReceipts'])>0,'EMPTY_SUCCESS_EVIDENCE')
    elif o['status']=='GAS_LIMIT_EXCEEDED':
        require(o['gas']['rejected'] is not None,'GAS_FAILURE_WITHOUT_BOUNDARY')
        require(not o['ownedWrites'] and not o['events'] and not o['semanticReceipts'],'FAILED_PARTIAL_PUBLICATION')
        require(exact_equal(o['before'],o['after']),'FAILED_STATE_CHANGED')
    elif o['status'] in ('REJECTED','NEEDS_RESOURCES'):
        require(not o['ownedWrites'] and not o['events'] and not o['semanticReceipts'],'NONCOMMITTING_EFFECTS')
        require(exact_equal(o['before'],o['after']),'NONCOMMITTING_STATE_CHANGED')
    # Full receipt bytes and exact event records are retained, not success booleans.
    for e in o['events']:require(isinstance(e,dict) and isinstance(e.get('origin'),str) and integer(e.get('ordinal')),'EVENT_NOT_RECORD')
    require(len({(e['origin'],e['ordinal']) for e in o['events']})==len(o['events']),'DUPLICATE_EVENT_OCCURRENCE')
    for r in o['semanticReceipts']:require(isinstance(r,dict) and isinstance(r.get('owner'),str) and r['owner'],'RECEIPT_NOT_RECORD')

def identity_check(o):
    sys.path.insert(0,str(Path(__file__).resolve().parent/'identity'))
    import constructors as I
    e=o.get('identityEvidence')
    require(isinstance(e,dict) and set(e)>={'historyBases','ownerDescriptor','deliveryDescriptor','baseInvocationIdentity','baseCommitCompanionIdentity','entryLiveEdges','requestedRootDocumentId'},'IDENTITY_EVIDENCE_REQUIRED')
    bases={}
    for item in e['historyBases']:
        require(isinstance(item,dict) and set(item)=={'value','identity'},'HISTORY_BASIS_RECORD')
        identity=I.history(item['value']);require(identity==item['identity'],'WRONG_HISTORY_BASIS')
        d=item['value']['documentId'];require(d not in bases,'DUPLICATE_HISTORY_BASIS');bases[d]=identity
    owner,oid=I.owner(e['ownerDescriptor'])
    for m in owner['members']:require(bases.get(m['documentId'])==m['historyBasisIdentity'],'OWNER_HISTORY_MISMATCH')
    edges=e['entryLiveEdges'];require(isinstance(edges,list),'LIVE_EDGES')
    def reach(start,reverse=False):
        seen={start};work=[start]
        while work:
            x=work.pop()
            for edge in edges:
                a,b=edge['parentDocumentId'],edge['childDocumentId']
                if reverse:a,b=b,a
                if a==x and b not in seen:seen.add(b);work.append(b)
        return seen
    root=e['requestedRootDocumentId'];forward=reach(root);require(all(x['parentDocumentId'] in forward for x in edges),'NON_ROOTED_EDGE');members=forward&reach(root,True)
    require(members=={m['documentId'] for m in owner['members']},'WRONG_ENTRY_SCC')
    actual_internal=[x for x in edges if x['parentDocumentId'] in members and x['childDocumentId'] in members]
    require(sorted(actual_internal,key=lambda x:x['occurrenceIdentity'])==owner['internalEdges'],'MISSING_OR_EXTRA_INTERNAL_EDGE')
    ctx,cid=I.context(owner);require(o.get('operationContext')==ctx,'WRONG_OPERATION_CONTEXT')
    d=e['deliveryDescriptor'];require(d['operationOwnerIdentity']==oid and d['kind']==o['causeKind'],'WRONG_DELIVERY_OWNER_OR_KIND')
    _,did=I.delivery(d);require(o.get('deliveryBasisIdentity')==did,'WRONG_DELIVERY_IDENTITY')
    inv=I.wrapper('rootedInvocationIdentity',{'baseInvocationIdentity':e['baseInvocationIdentity'],'rootProcessingContextIdentity':cid,'deliveryBasisIdentity':did})
    require(o.get('rootedInvocationIdentity')==inv,'WRONG_INVOCATION_IDENTITY')
    terminal=I.wrapper('rootedTerminalKey',{'operationOwnerIdentity':oid,'deliveryBasisIdentity':did})
    require(o.get('rootedTerminalKey')==terminal,'WRONG_TERMINAL_IDENTITY')
    companion=I.wrapper('rootedCommitCompanionIdentity',{'baseCommitCompanionIdentity':e['baseCommitCompanionIdentity'],'rootedInvocationIdentity':inv,'rootProcessingContextIdentity':cid})
    require(o.get('rootedCommitCompanionIdentity')==companion,'WRONG_COMPANION_IDENTITY')
    # Base input/companion identities remain verified by the owning real SDK;
    # this wrapper checker does not substitute for Blue's full receipt verifier.

def check_calibration(cal,weights):
    require(isinstance(cal,dict),'CALIBRATION_REQUIRED')
    require(cal.get('executionPath')=='MATERIALIZED_REFERENCE_UNCACHED','REFERENCE_PATH_REQUIRED')
    require(isinstance(cal.get('implementationSource'),str) and cal['implementationSource'],'REFERENCE_SOURCE_REQUIRED')
    require(isinstance(cal.get('transcript'),list) and cal['transcript'],'CALIBRATION_TRACE')
    o=cal.get('output');structured_output(o,weights)
    require(o['status']=='SUCCESS' and o['causeKind']=='LIVE','CALIBRATION_NOT_SUCCESS')
    require(o['gas']['total']>0,'CALIBRATION_ZERO')
    return o['gas']['total']

def check_multiple_history(o,contract,weights,document_ids):
    """Exact occurrence suffixes and cross-source ordering, independent of the adapter."""
    hist=o.get('applications');expected=contract['occurrenceSuffixes']
    require(isinstance(hist,list) and len(hist)==contract['requiredApplicationCount'],'MULTI_APPLICATION_COUNT')
    require(expected and sum(len(v['positions']) for v in expected.values())==len(hist),'MULTI_EXPECTED_COUNT')
    require(set(o['sourceBefore'])==set(contract['sourceAliases']),'MULTI_SOURCE_INVENTORY')
    observed={path:[] for path in expected};work_ids=set();receipt_ids=set();order=[]
    for h in hist:
        path=h.get('targetPath');source=h.get('source')
        require(path in expected and source==expected[path]['source'],'MULTI_OCCURRENCE_SOURCE')
        require(h['status']=='SUCCESS' and type(h['from']) is int and h['to']==h['from']+1,'MULTI_EPOCH_STEP')
        work=h['work'];cause=h['exactCause'];receipt=h['receipt'];retained=h['retainedSource'];transition=h['sourceReceipt']
        require(work['workIdentity']==receipt['workIdentity'] and work['workIdentity'] not in work_ids,'MULTI_DUPLICATE_WORK')
        require(receipt['applicationReceiptIdentity'] not in receipt_ids,'MULTI_DUPLICATE_APPLICATION')
        work_ids.add(work['workIdentity']);receipt_ids.add(receipt['applicationReceiptIdentity'])
        require(work['targetPath']==path and work['targetOccurrenceIdentity']==cause['targetOccurrenceIdentity'],'MULTI_OCCURRENCE_BINDING')
        require(work['sourceDocumentId']['value']==document_ids[source]==cause['childDocumentId']['value']==retained['documentId']['value'],'MULTI_SOURCE_ID')
        require(work['sourceEpoch']==h['to']==cause['toEpoch']==retained['epoch'] and cause['fromEpoch']==h['from'],'MULTI_SOURCE_EPOCH')
        require(retained in o['sourceBefore'][source]['receipts'],'MULTI_UNRETAINED_SOURCE')
        require(work['sourceReceiptIdentity']==receipt['sourceReceiptIdentity']==retained['receiptIdentity'],'MULTI_SOURCE_RECEIPT')
        require(cause['sourceRevisionReceiptIdentity']==transition['transitionReceiptIdentity']==retained['contractsTransitionReceiptIdentity'],'MULTI_TRANSITION_RECEIPT')
        require(cause['beforeBlueId']==transition['beforeBlueId']==retained['beforeBlueId'] and cause['afterBlueId']==transition['afterBlueId']==retained['afterBlueId'],'MULTI_EXACT_SUCCESSOR')
        require(cause['originalSourceCauseIdentity']==transition['originalCauseIdentity']==retained['originalCauseIdentity'],'MULTI_ORIGINAL_CAUSE')
        require(h['sourceOrder']==retained['sourceOrder']['components'] and len(h['sourceOrder'])==3,'MULTI_SOURCE_ORDER')
        gas_check(h['gas'],h['budget'],weights);require(h['gas']['charges'] and h['gas']['rejected'] is None,'MULTI_GAS')
        for key,value in contract.get('perApplicationEquals',{}).items():
            require(exact_equal(lookup(h['after'],key),value),'MULTI_OVERTAKE:'+key)
        observed[path].append(h['to']);order.append([source,h['sourceOrder'][0]])
    require(all(observed[p]==v['positions'] for p,v in expected.items()),'MULTI_WRONG_SUFFIX')
    if 'applicationOrder' in contract:require(order==contract['applicationOrder'],'MULTI_WRONG_ORDER')

def check_equal_occurrences(output,contract,document_ids):
    source=contract['sourceAlias'];consumer=contract['consumerAlias']
    receipts=[r for r in output.get('computedReceipts',[]) if r.get('documentId',{}).get('value')==document_ids[source]]
    require(len(receipts)==1,'EQUAL_SOURCE_RECEIPT')
    events=receipts[0].get('emittedRootEvents',[])
    require(len(events)==2 and [e.get('ordinal') for e in events]==[0,1],'EQUAL_SOURCE_ORDINALS')
    require(all(e.get('sourceDocumentId',{}).get('value')==document_ids[source] for e in events),'EQUAL_SOURCE_LINEAGE')
    require(events[0].get('eventBlueId')==events[1].get('eventBlueId')
            and isinstance(events[0].get('eventBlueId'),str)
            and isinstance(events[0].get('exactEvent'),dict) and events[0]['exactEvent']
            and exact_equal(events[0].get('exactEvent'),events[1].get('exactEvent')),'EQUAL_PAYLOAD_REQUIRED')
    require(isinstance(events[0].get('occurrenceIdentity'),str) and isinstance(events[1].get('occurrenceIdentity'),str)
            and events[0]['occurrenceIdentity']!=events[1]['occurrenceIdentity'],'EQUAL_OCCURRENCES_DISTINCT')
    evidence=output.get('implementationEvidence',{})
    require(evidence.get('complete') is True and isinstance(evidence.get('invocationIdentity'),str)
            and evidence.get('invocationIdentity')==evidence.get('inputInvocationIdentity'),'EQUAL_BOUND_WORK')
    deliveries=[w for w in evidence.get('workTrace',[]) if w.get('kind')=='EMBEDDED_EVENT'
                and w.get('targetDocumentId',{}).get('value')==document_ids[consumer]]
    require(len(deliveries)==2,'EQUAL_DELIVERY_COUNT')
    require([w.get('eventBlueId') for w in deliveries]==[e['eventBlueId'] for e in events],'EQUAL_DELIVERY_PAYLOADS')
    require([w.get('sourceOccurrenceIdentity') for w in deliveries]==[e['occurrenceIdentity'] for e in events],
            'EQUAL_DELIVERY_OCCURRENCES')

def check_silent_middle(output,contract,document_ids):
    evidence=output.get('implementationEvidence',{})
    require(evidence.get('complete') is True,'SILENT_INCOMPLETE_WORK')
    require(evidence.get('invocationIdentity')==evidence.get('inputInvocationIdentity')
            and isinstance(evidence.get('invocationIdentity'),str),'SILENT_WRONG_INVOCATION')
    work=evidence.get('workTrace');steps=evidence.get('documentStepTrace')
    require(isinstance(work,list) and work and isinstance(steps,list) and len(steps)==len(work),'SILENT_WORK_EVIDENCE')
    middle=document_ids[contract['silentMiddle']]
    for ordinal,(item,step) in enumerate(zip(work,steps)):
        require(item.get('ordinal')==ordinal and step.get('workOrdinal')==ordinal
                and step.get('targetDocumentId')==item.get('targetDocumentId'),'SILENT_WORK_ORDER')
        if item['targetDocumentId']['value']==middle:
            require(item.get('kind')=='CONTAINING_REFERENCE_UPDATE','SILENT_MIDDLE_HANDLER_WORK')
    before=output.get('selectedBefore',{});after=output.get('selectedAfter',{})
    for alias in contract['selectedExactAdvanced']:
        require(alias in before and alias in after,'SILENT_SELECTED_VIEW_MISSING')
        a=before[alias];b=after[alias]
        require(a.get('documentId')==b.get('documentId')==document_ids[alias],'SILENT_SELECTED_LINEAGE')
        require(isinstance(a.get('blueId'),str) and isinstance(b.get('blueId'),str)
                and a['blueId']!=b['blueId'] and a.get('exactDocument')!=b.get('exactDocument'),'SILENT_REFERENCE_NOT_ADVANCED')
    anchor=contract['computedSourceEvent']
    events=[e for e in output.get('computedEvents',[]) if e.get('origin')==anchor['origin']]
    require(len(events)==1,'SILENT_SOURCE_EVENT_COUNT')
    require(all(exact_equal(events[0].get(k),v) for k,v in anchor.items()),'SILENT_SOURCE_EVENT_ANCHOR')
    source=anchor['origin'];identity=document_ids[source]
    receipts=[r for r in output.get('computedReceipts',[]) if r.get('documentId',{}).get('value')==identity]
    require(len(receipts)==1,'SILENT_SOURCE_RECEIPT')
    receipt=receipts[0];event=events[0]
    require(receipt.get('beforeBlueId')==before[source]['blueId']
            and receipt.get('afterBlueId')==after[source]['blueId'],'SILENT_SOURCE_RECEIPT_POSITION')
    require(event.get('transitionReceiptIdentity')==receipt.get('transitionReceiptIdentity')
            and isinstance(event.get('transitionReceiptIdentity'),str),'SILENT_SOURCE_RECEIPT_BINDING')
    normalized={k:v for k,v in event.items() if k not in ('origin','kind','transitionReceiptIdentity')}
    require(exact_equal(receipt.get('emittedRootEvents'),[normalized]),'SILENT_SOURCE_RECEIPT_EVENTS')

def check_phase_events(phases,anchors,weights):
    """Bind every named phase to its complete ordered literal event inventory."""
    for name,expected in anchors.items():
        require(name in phases,'MISSING_EVENT_PHASE')
        output=phases[name];structured_output(output,weights)
        require(len(output['events'])==len(expected),'PHASE_EVENT_COUNT:'+name)
        for actual,anchor in zip(output['events'],expected):
            require(isinstance(anchor,dict) and anchor,'PHASE_EVENT_ANCHOR_FIELDS')
            for key,value in anchor.items():
                require(key in actual and exact_equal(actual[key],value),'PHASE_EVENT_ANCHOR:'+name+'.'+key)

def check_runs(f,run_records,weights,calibration=None):
    require(f['input'].get('qualification')=='LITERAL_CRITICAL','RECIPE_ONLY_NOT_QUALIFIED')
    require(f['expected'].get('oracleVersion')==2,'ORACLE_VERSION')
    names=f['input']['variants'];require(len(names)==len(set(names)) and names,'FIXTURE_VARIANTS')
    require(isinstance(run_records,dict) and set(run_records)==set(names),'MISSING_OR_EXTRA_VARIANTS')
    contract=f['expected']['contract'];scope=contract['scope'];G=None
    if scope=='GAS_BOUNDARY':G=check_calibration(calibration,weights)
    observations={};count=0
    for name in names:
        rec=run_records[name]
        require(isinstance(rec,dict) and set(rec)>={'transcript','output','restart'},'RUN_RECORD_FIELDS')
        tr=rec['transcript'];require(isinstance(tr,list) and tr,'EMPTY_TRANSCRIPT')
        for t in tr:
            require(isinstance(t,dict) and t.get('kind') in ('SDK_CALL','HTTP','HARNESS_CONTROL') and isinstance(t.get('request'),dict) and isinstance(t.get('response'),dict),'BAD_TRANSCRIPT')
        o=rec['output'];structured_output(o,weights)
        require(o['causeKind']==contract['causeKind'],'WRONG_CAUSE_KIND')
        if contract.get('verifyIdentityEnvelopes'):identity_check(o)
        restart=rec['restart']
        require(isinstance(restart,dict) and set(restart)>={'before','after','beforeCommandCount','afterCommandCount'},'RESTART_REQUIRED')
        require(isinstance(restart['before'],dict) and restart['before'],'EMPTY_RESTART')
        require(exact_equal(restart['before'],restart['after']),'RESTART_STATE_CHANGED')
        require(integer(restart['beforeCommandCount']) and integer(restart['afterCommandCount']) and restart['beforeCommandCount']==restart['afterCommandCount'],'RESTART_NEW_COMMANDS')
        expected_status=contract.get('expectedStatus','SUCCESS')
        writes=contract.get('writes')
        anchors=contract.get('afterEquals',{})
        if scope=='GAS_BOUNDARY':
            suffix=name.rsplit('-',1)[1];expected_status=contract['expectedBySuffix'][suffix]
            require(o['budget']==G+{'below':-1,'at':0,'above':1}[suffix],'WRONG_BOUNDARY_BUDGET')
            if suffix!='below':
                anchors=contract['afterEqualsOnSuccess'];writes=contract['writesOnSuccess']
                require(o['gas']['total']==G,'SUCCESS_GAS_DIFFERS_FROM_REFERENCE')
                require(exact_equal(o['gas']['charges'],calibration['output']['gas']['charges']),
                        'SUCCESS_TRACE_DIFFERS_FROM_REFERENCE')
            else:
                pref=o['gas']['charges'];full=calibration['output']['gas']['charges']
                require(pref==full[:len(pref)],'WRONG_REJECTED_PREFIX')
                require(len(pref)<len(full),'MISSING_REFERENCE_NEXT_CHARGE')
                r=o['gas']['rejected'];n=full[len(pref)]
                require(all(r[k]==n[k] for k in ('sequence','label','quantity','weight','amount')),'WRONG_REJECTED_NEXT_CHARGE')
        require(o['status']==expected_status,'MISSING_EXPECTED_ANCHOR_STATUS')
        if writes is not None:
            require(sorted(o['ownedWrites'])==sorted(writes),'WRONG_OWNED_WRITES')
            require(all(r['owner'] in writes for r in o['semanticReceipts']),'WRONG_RECEIPT_OWNER')
        for path,value in anchors.items():require(exact_equal(lookup(o['after'],path),value),'ANCHOR:'+path)
        if 'requiredEvents' in contract:require(len(o['events'])==contract['requiredEvents'],'EVENT_COUNT')
        # Semantic anchors are authored from the literal program, not supplied
        # as equality verdicts by the implementation under test. Events remain
        # normalized exact observations; extra authenticated fields may exist.
        if 'eventAnchors' in contract:
            expected_events=contract['eventAnchors']
            require(isinstance(expected_events,list) and len(o['events'])==len(expected_events),'EVENT_ANCHOR_COUNT')
            for actual,expected in zip(o['events'],expected_events):
                require(isinstance(expected,dict) and expected,'EVENT_ANCHOR_FIELDS')
                for field,value in expected.items():
                    require(field in actual and exact_equal(actual[field],value),'EVENT_ANCHOR:'+field)

        if contract.get('preserveSource'):require(o['sourceBefore'] and exact_equal(o['sourceBefore'],o['sourceAfter']),'SOURCE_CHANGED')
        if scope=='HISTORY_SEQUENCE':
            hist=o.get('applications');require(isinstance(hist,list) and len(hist)==contract['requiredApplicationCount'],'APPLICATION_COUNT')
            positions=[hist[0]['from']]+[x['to'] for x in hist]
            require(positions==contract['positionSequence'],'WRONG_HISTORY_SEQUENCE')
            for i,h in enumerate(hist):
                require(h['to']==h['from']+1 and h['status']=='SUCCESS','HISTORY_STEP')
                if i:require(hist[i-1]['to']==h['from'],'HISTORY_GAP')
                require(isinstance(h.get('receipt'),dict) and h['receipt'],'APPLICATION_RECEIPT')
                gas_check(h['gas'],h['budget'],weights)
                require(h['gas']['rejected'] is None and h['gas']['charges'],'HISTORY_GAS')
            before=o['sourceBefore'].get('A',{});after=o['sourceAfter'].get('A',{})
            require(isinstance(before.get('receipts'),list) and before['receipts'],'SOURCE_PREFIX_REQUIRED')
            require(after.get('receipts',[])[:len(before['receipts'])]==before['receipts'],'SOURCE_RECEIPT_REWRITE')
            require(after.get('events')==before.get('events') and isinstance(before.get('events'),list),'SOURCE_REEMISSION')
            require(after.get('epoch',-1)>=contract['sourceEpochMinimum'],'SOURCE_REWIND')
            require(sorted(o.get('liveCycle',[]))==sorted(contract['requireLiveCycle']),'LIVE_JOIN_MISSING')
        if scope=='HISTORY_MULTIPLE':check_multiple_history(o,contract,weights,rec['documentIds'])
        if scope=='SILENT_MIDDLE':check_silent_middle(o,contract,rec['documentIds'])
        if scope=='EQUAL_PAYLOAD_OCCURRENCES':check_equal_occurrences(o,contract,rec['documentIds'])
        if contract.get('phaseEventAnchors'):
            check_phase_events(rec.get('phaseRecords',{}),contract['phaseEventAnchors'],weights)
        if contract.get('derivedAfter'):
            for path,rule in contract['derivedAfter'].items():
                require(rule['function']=='canonicalComponentOrder','UNKNOWN_ORACLE')
                ids=rec.get('documentIds',{});members=rule['members']
                require(all(isinstance(ids.get(x),str) and ids[x] for x in members),'CAPTURED_IDS_REQUIRED')
                expected=[x for _,x in sorted(zip([ids[x].encode('utf-8') for x in members],rule['outputLabels']))]
                require(lookup(o['after'],path)==expected,'DIAMOND_ORDER')
        for phase in {path.split('.')[0] for path in contract.get('extraChecks',{}).get(name,{})}:structured_output(rec['phaseRecords'][phase],weights)
        for path,value in contract.get('extraChecks',{}).get(name,{}).items():require(exact_equal(lookup(rec['phaseRecords'],path),value),'EXTRA_PHASE:'+path)
        if contract.get('additionalPhase'):
            split=rec.get('phaseRecords',{}).get('split');require(isinstance(split,dict),'SPLIT_NOT_EXECUTED');structured_output(split,weights)
            require(split.get('status')=='SUCCESS' and sorted(split.get('entryOwners',[]))==['A','B'],'SPLIT_ENTRY_OWNERS')
            require(sorted(split.get('nextComponents',[]))==[['A'],['B']],'SPLIT_PARTITION')
            require(split.get('contextBefore')==split.get('contextAfter') and isinstance(split.get('contextBefore'),dict) and split['contextBefore'],'SPLIT_CONTEXT_RESET')
            require(split.get('subjectReceiptBefore')==split.get('subjectReceiptAfter') and isinstance(split.get('subjectReceiptBefore'),dict),'SPLIT_OLD_RECEIPT')
        observations[name]=o;count+=1+len(anchors)
    def equal_paths(selected,paths):
        nonlocal count
        for path in paths:
            values=[lookup(observations[n],path) for n in selected]
            require(len(values)>=2 and all(exact_equal(x,values[0]) for x in values[1:]),'VARIANT_DIFF:'+path);count+=1
    if contract.get('equalAcrossVariants'):equal_paths(names,contract['equalAcrossVariants'])
    if scope=='GAS_BOUNDARY':
        for suffix in contract['expectedBySuffix']:equal_paths([n for n in names if n.endswith('-'+suffix)],contract['compareWithinBoundary'])
    return count

def read_evidence(root,ref):
    require(isinstance(ref,dict) and set(ref)=={'path','sha256'},'EVIDENCE_REF')
    rel=Path(ref['path']);require(not rel.is_absolute() and '..' not in rel.parts,'EVIDENCE_PATH')
    p=(root/rel).resolve();require(p.is_relative_to(root.resolve()) and p.is_file(),'EVIDENCE_MISSING')
    raw=p.read_bytes();require(digest_bytes(raw)==ref['sha256'],'EVIDENCE_HASH')
    return json.loads(raw)

def provenance(rec,request,artifact):
    require(rec.get('requestNonce')==request['requestNonce'],'WRONG_RUN_NONCE')
    require(rec.get('fixtureId')==request['fixture']['id'],'WRONG_FIXTURE')
    require(rec.get('specificationSetSha256')==request['specificationSetSha256'],'WRONG_SPECIFICATION')
    require(rec.get('requestSha256')==request['requestSha256'],'WRONG_REQUEST')
    require(rec.get('artifactSha256')==artifact,'WRONG_ARTIFACT')
    commits=rec.get('sourceCommits');require(isinstance(commits,dict) and {'language','bex','coordination','myos'}<=set(commits),'SOURCE_COMMITS')
    require(all(isinstance(x,str) and COMMIT.fullmatch(x) and x!='0'*40 for x in commits.values()),'INVALID_SOURCE_COMMIT')
    deps=rec.get('dependencies');require(isinstance(deps,dict) and deps,'DEPENDENCIES')
    require(all(isinstance(x,str) and HEX.fullmatch(x) and x!='0'*64 for x in deps.values()),'DEPENDENCY_HASH')
    require(deps==request['embeddedDependencies'],'DEPENDENCY_BYTES_MISMATCH')
    if 'sourceCommits' in request:require(commits==request['sourceCommits'],'SOURCE_COMMIT_MISMATCH')

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--adapter-command');ap.add_argument('--artifact',type=Path);ap.add_argument('--artifact-sha256');ap.add_argument('--source-lock',type=Path);ap.add_argument('--source-lock-sha256');ap.add_argument('--output',type=Path);ap.add_argument('--id',action='append',default=[]);ap.add_argument('--list',action='store_true');ap.add_argument('--timeout',type=int,default=300)
    a=ap.parse_args();suite=Path(__file__).resolve().parent;package=suite.parents[1]
    index=json.loads((suite/'fixture-index.json').read_text())['fixtures'];rows=[x for x in index if x['kind']=='runtime' and (not a.id or x['id'] in a.id)]
    if not rows or set(a.id)-{r['id'] for r in rows}:ap.error('unknown/empty/non-runtime selection')
    if a.list:
        for row in rows:
            f=json.loads((suite/row['path']).read_text());print(f["id"],f['input'].get('qualification','RECIPE_ONLY'))
        return 0
    if not(a.adapter_command and a.artifact and a.artifact_sha256 and a.source_lock and a.source_lock_sha256 and a.output):ap.error('adapter command, independently pinned artifact and output are required; no adapter is NOT_RUN')
    command=json.loads(a.adapter_command);require(isinstance(command,list) and command and all(isinstance(x,str) and x for x in command),'ADAPTER_COMMAND')
    art=check_artifact(a.artifact,a.artifact_sha256);deps=embedded_entries(a.artifact);require(deps,'USE_PACKAGED_MYOS_JAR_WITH_ACTUAL_DEPENDENCIES')
    weights=verified_tariff_weights(suite);spec=digest_bytes((package/'manifests/specification-set.json').read_bytes())
    lock=source_lock(a.source_lock,a.source_lock_sha256,art,deps,spec)
    results=[];evidenceRoot=a.output.parent/(a.output.stem+'-evidence');evidenceRoot.mkdir(parents=True,exist_ok=False)
    for row in rows:
        f=json.loads((suite/row['path']).read_text());rec={'id':f['id'],'status':'NOT_RUN','releaseReadinessClaimed':False}
        if f['input'].get('qualification')!='LITERAL_CRITICAL':rec['reason']='Recipe-level obligation; no complete v2 oracle supplied';results.append(rec);continue
        out=evidenceRoot/f['id'];out.mkdir()
        plan=json.loads((suite/f['input']['literalPlan']).read_text())
        request={'schema':'blue-rooted-adapter-request/1.0-draft.2','fixture':f,'plan':plan,'sourceFiles':literal_sources(suite,f,plan),'artifactPath':str(a.artifact.resolve()),'artifactSha256':art,'embeddedDependencies':deps,'sourceCommits':lock['sourceCommits'],'sourceLockSha256':a.source_lock_sha256,'specificationSetSha256':spec,'requestNonce':secrets.token_hex(16),'evidenceDirectory':str(out.resolve())}
        request['requestSha256']=digest_bytes(json.dumps(request,sort_keys=True,separators=(',',':')).encode())
        (out/'request.json').write_text(json.dumps(request,indent=2)+'\n');start=time.perf_counter()
        try:
            cp=subprocess.run(command,input=json.dumps(request),text=True,capture_output=True,timeout=a.timeout)
            (out/'stdout.txt').write_text(cp.stdout);(out/'stderr.txt').write_text(cp.stderr)
            require(cp.returncode==0,'ADAPTER_EXIT')
            response=json.loads(cp.stdout);provenance(response,request,art)
            require(response.get('status')=='EXECUTED','NOT_EXECUTED')
            require(isinstance(response.get('runs'),dict) and set(response['runs'])==set(f['input']['variants']),'MISSING_OR_EXTRA_VARIANTS')
            runs={name:read_evidence(out,ref) for name,ref in response['runs'].items()}
            for name,run in runs.items():
                provenance(run,request,art);literal_steps(request['plan'],name,run['transcript'])
            cal=read_evidence(out,response['calibration']) if 'calibration' in response else None
            if cal is not None:provenance(cal,request,art)
            rec['assertionsPassed']=check_runs(f,runs,weights,cal);rec['status']='PASS'
        except Exception as e:rec.update(status='FAIL',error=f'{type(e).__name__}: {e}')
        rec['seconds']=time.perf_counter()-start;results.append(rec)
    status='FAIL' if any(r['status']=='FAIL' for r in results) else ('INCOMPLETE' if any(r['status']=='NOT_RUN' for r in results) else 'PASS')
    report={'schema':'blue-rooted-adapter-run/1.0-draft.2','status':status,'selected':len(results),'passed':sum(x['status']=='PASS' for x in results),'failed':sum(x['status']=='FAIL' for x in results),'notRun':sum(x['status']=='NOT_RUN' for x in results),'artifactSha256':art,'specificationSetSha256':spec,'results':results,'releaseReadinessClaimed':False}
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k!='results'},indent=2));return {'PASS':0,'FAIL':1,'INCOMPLETE':3}[status]
if __name__=='__main__':raise SystemExit(main())
