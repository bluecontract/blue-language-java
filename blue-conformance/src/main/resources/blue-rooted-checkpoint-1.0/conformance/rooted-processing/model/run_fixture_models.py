#!/usr/bin/env python3
"""Execute structured abstract fixtures, not production Blue conformance.

Python 3.10+; standard library only. Unknown fixture kinds and unexpected errors
fail closed. Every selected model fixture is checked against its authored expected
observation; selectors/graphs/dynamic traces additionally use independent oracles.
"""
from __future__ import annotations
import argparse, copy, hashlib, json, sys, time
from pathlib import Path
import checkpoint_model as M

class FixtureError(Exception): pass

def binding(obj:dict)->M.Binding:
    es=tuple(M.Entry(x['timestamp'],x['timeline'],x['entry']) for x in obj['entries'])
    cursor=obj['cursor']
    if not isinstance(cursor,int) or isinstance(cursor,bool) or not 0<=cursor<=len(es):raise FixtureError('INVALID_PREFIX')
    if any(es[j].key>=es[j+1].key for j in range(len(es)-1)):raise FixtureError('INVALID_ORDER')
    seen={}
    for e in es:
        if e.timeline in seen and seen[e.timeline]>=e.time:raise FixtureError('INVALID_TIMELINE')
        seen[e.timeline]=e.time
    return M.Binding(obj['address'],es,cursor,obj.get('active',True))

def bindings(values:list)->list[M.Binding]:
    out=[binding(v) for v in values]
    if len({x.address for x in out})!=len(out):raise FixtureError('DUPLICATE_BINDING')
    return out

def trace(xs):return [{'entry':k[2],'receivers':list(a)} for k,a in xs]
def equal(a,b,msg):
    if a!=b:raise AssertionError(f'{msg}\nactual={a!r}\nexpected={b!r}')

def execute(f):
    i=f['input'];kind=f['kind']
    if kind=='selector':
        bs=bindings(i['bindings']);a=M.scan_merge(bs);h=M.heap_merge(bs);o=M.offline_oracle(bs)
        equal(a,h,'scan/heap disagreement');equal(a,o,'scan/offline oracle disagreement')
        equal(a,M.scan_merge(list(reversed(bs))),'binding enumeration changed result')
        return {'trace':trace(a)}
    if kind=='safe-window':
        bs=bindings(i['bindings']);x=M.select_heads({b.address:b for b in bs})
        if x is None:return {'status':'IDLE','next':None}
        safe=min(i['completeBefore'].values())
        return {'status':'SELECTED','next':x[0][2]} if x[0][0]<safe else {'status':'WAITING_FOR_EVIDENCE','next':None}
    if kind=='terminal-progress':
        pending=[e for e in i['entries'] if e['entry'] not in i['terminalEntries']]
        return {'next':min(pending,key=lambda e:(e['timestamp'],e['timeline'],e['entry']))['entry'] if pending else None,'checkpoint':i['checkpoint']}
    if kind=='graph':
        edges=tuple(tuple(e) for e in i['edges']);root=i['root'];nodes=tuple(i['nodes'])
        a=M.reachable(edges,root);o=M.matrix_reachable(nodes,edges,root)
        equal(a,o,'reachability/matrix disagreement');equal(a,M.reachable(tuple(reversed(edges)),root),'edge enumeration changed scope')
        return {'reachable':sorted(a)}
    if kind=='graph-generated':
        if i['generator']=='observer-fanout':
            edges=tuple((f'order-{n}',i['root']) for n in range(i['observers']));a=M.reachable(edges,i['root'])
            equal(a,{i['root']},'generated incoming fanout contaminated root')
            return {'reachableCount':len(a),'reachable':sorted(a)}
        if i['generator']=='root-fanin':
            edges=tuple((i['root'],f'order-{n}') for n in range(i['members']));a=M.reachable(edges,i['root'])
            equal(len(a),i['members']+1,'generator count mismatch')
            return {'reachableCount':len(a),'limitDecision':'EXCEEDS_LIMIT' if len(a)>i['managedDocumentLimit'] else 'WITHIN_LIMIT'}
        raise FixtureError('UNKNOWN_GENERATOR')
    if kind=='dynamic':
        bs=bindings(i['bindings']);ed={k:tuple(M.Edit(x['kind'],x['address'],tuple(M.Entry(e['timestamp'],e['timeline'],e['entry']) for e in x['entries']),x['cursor']) for x in v) for k,v in i['edits'].items()}
        a=M.run_dynamic(bs,ed,bound=i['maxSteps']);o=M.run_dynamic(bs,ed,selector=M.select_all_remaining,bound=i['maxSteps'])
        equal(a,o,'dynamic head/full-pending oracle disagreement')
        return {'trace':trace(a)}
    if kind=='history':
        state=M.Observation(i['position'],i['value']);status='APPLIED'
        for r in i['receipts']:
            if r['position']!=state.position+1:raise FixtureError('NONCONTIGUOUS_POSITION')
            if r['before']!=state.child_value:raise FixtureError('WRONG_PREDECESSOR')
            fail=r['position']==i['failAt'];before=copy.deepcopy(state)
            ok=state.apply(M.Receipt(r['position'],r['before'],r['after'],tuple(r['events'])),fail=fail)
            if not ok:
                equal(state,before,'failed application changed state');status='APPLICATION_FAILED';break
        return {'position':state.position,'value':state.child_value,'events':state.log,'status':status}
    if kind=='historical-read':return {'result':i['values'][i['selected']]+i['increment'],'wrongLatestResult':i['values']['Bcurrent']+i['increment']}
    if kind=='position-chain':
        current=i['start'];out=[];epoch=int(current.split(':')[0])
        for x in i['steps']:
            if x['from']!=current:raise FixtureError('WRONG_POSITION_PREDECESSOR')
            current=x['to'];out.append(current);epoch=x['epoch']
        return {'positions':out,'finalEpoch':epoch}
    if kind=='reaction':
        if i['costModel']!='ONE_UNIT_PER_DELIVERY_NOT_BLUE_GAS':raise FixtureError('UNSUPPORTED_COST_MODEL')
        r=M.reactions(i['route'],i['start'],i['ttl'],i['budget'])
        return {'status':r.status,'committed':[list(x) for x in r.committed],'gas':r.gas}
    raise FixtureError('UNKNOWN_MODEL_KIND')

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--id',action='append',default=[])
    args=ap.parse_args();suite=Path(__file__).resolve().parents[1];idx=json.loads((suite/'fixture-index.json').read_text())['fixtures']
    selected=[x for x in idx if x['verificationScope']=='ABSTRACT_MODEL' and (not args.id or x['id'] in args.id)]
    unknown=set(args.id)-{x['id'] for x in selected}
    if unknown or not selected:ap.error(f'No model fixtures or unknown/non-model ids: {sorted(unknown)}')
    started=time.perf_counter();results=[]
    for item in selected:
        p=suite/item['path'];f=json.loads(p.read_text());t=time.perf_counter()
        try:
            try: actual=execute(f)
            except FixtureError as ex:actual={'error':str(ex)}
            equal(actual,f['expected'],f['id']);status='PASS';error=None
        except Exception as ex:actual=None;status='FAIL';error=f'{type(ex).__name__}: {ex}'
        results.append({'id':f['id'],'status':status,'scope':'ABSTRACT_MODEL_ONLY','fixtureSha256':hashlib.sha256(p.read_bytes()).hexdigest(),'actual':actual,'error':error,'seconds':time.perf_counter()-t})
    record={'status':'PASS' if all(r['status']=='PASS' for r in results) else 'FAIL','scope':'Structured abstract fixture model; no production Blue execution','implementationConformanceClaimed':False,'modelFixtureCount':len(results),'pass':sum(r['status']=='PASS' for r in results),'fail':sum(r['status']=='FAIL' for r in results),'productionAdapterFixtureCount':sum(x['verificationScope']=='PRODUCTION_ADAPTER_REQUIRED' for x in idx),'productionAdapterStatus':'NOT_RUN','seconds':time.perf_counter()-started,'results':results}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps({k:v for k,v in record.items() if k!='results'},indent=2))
    for r in results:
        if r['status']=='FAIL':print(r['id'],r['error'],file=sys.stderr)
    return 0 if record['status']=='PASS' else 1
if __name__=='__main__':raise SystemExit(main())
