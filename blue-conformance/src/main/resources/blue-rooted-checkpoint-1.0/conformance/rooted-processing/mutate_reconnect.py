#!/usr/bin/env python3
"""Test-only mutations of a genuine RUN019 record; never executes or alters a runtime."""
import argparse
import copy
import importlib.util
import json
import sys
from pathlib import Path


def load(path,name):
    spec=importlib.util.spec_from_file_location(name,path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--record',required=True,type=Path)
    parser.add_argument('--suite',required=True,type=Path)
    parser.add_argument('--variant',required=True,choices=['saved-authored','saved-retained'])
    args=parser.parse_args();sys.path.insert(0,str(args.suite.resolve()))
    runner=load(args.suite/'run_production_adapter.py','rooted_reconnect_runner')
    checker=load(args.suite/'check_reconnect.py','rooted_reconnect_checker')
    contract=json.loads((args.suite/'plans/rcp-run-019.json').read_text())['outputContract']
    weights=runner.verified_tariff_weights(args.suite)
    def check(record):
        checker.check_reconnect(record,contract,weights,args.variant,runner.require,runner.exact_equal,
                                runner.gas_check,runner.structured_output,runner.identity_check)
    original=json.loads(args.record.read_text());check(original);print('ORIGINAL_PASS',args.variant,flush=True)
    def phase(rec,name,change,predicate=lambda c:True):
        value=rec['phaseRecords'][name]
        call=next(c for c in value['calls'] if predicate(c));change(call)
        row=next(r for r in rec['transcript'] if r['request'].get('op') == 'drainThroughEntry' and r['request']['capture'] == name)
        row['response']=copy.deepcopy(value)
    def terminal(rec,change,name='reconnect'):
        phase(rec,name,lambda c:change(c['terminals'][0]),lambda c:bool(c['terminals']))
    def stage(rec,name,change):
        value=rec['phaseRecords'][name];change(value)
        row=next(r for r in rec['transcript'] if r['request'].get('op') == 'observeReconnect' and r['request']['capture'] == name)
        row['response']=copy.deepcopy(value)
        if name=='final':
            rec['output']['reconnect']=copy.deepcopy(value)
            rec['phaseRecords']['subject']['reconnect']=copy.deepcopy(value)
    def mutate_selected(rec):
        row=next(r for r in rec['transcript'] if r['request'].get('capture') == 'E400')
        row['response']['exactEntry']['message']['request']['source']['blueId']=rec['phaseRecords']['apartAdvanced']['records']['B']['blueId']
    def mutate_saved(rec):
        row=next(r for r in rec['transcript'] if r['request'].get('op') == 'captureHeadReceipt')
        row['response']['receipt']['afterDocument']['observed']={'value':987654}
    def reconnect_plan(rec):
        def change(c):
            plan=next(p for p in c['progressAfter']['plans']['A'] if p['targetPath']=='/peers/b' and p['activationGeneration']>1)
            plan['requiredThroughSourceEpoch']+=1
        phase(rec,'reconnect',change,lambda c:any(p['targetPath']=='/peers/b' and p['activationGeneration']>1 for p in c['progressAfter']['plans']['A']))
    def generation(rec):
        def change(o):
            edge=next(e for e in o['selectedSnapshots']['A']['occurrences'] if e['sourcePath']=='/peers/b')
            edge['activationGeneration']=1
        stage(rec,'reconnectedAt400',change)
    def old_receipt(rec):
        phase(rec,'reconnect',lambda c:c['after']['B']['receipts'][0]['afterDocument'].__setitem__('name','rewritten-old-receipt'))
    def journal_future(rec):
        phase(rec,'reconnect',lambda c:c['progressBefore']['journal'].remove(next(e for e in c['progressBefore']['journal'] if e['blueId']==rec['output']['reconnect']['captures']['E500'])))
    def barrier(rec):
        def change(c):
            p=next(p for p in c['progressAfter']['plans']['A'] if p['targetPath']=='/peers/b' and p['activationGeneration']>1)
            c['progressAfter']['barriers'][p['barrierIdentity']]['causeOrder']['components'][0]=500
        phase(rec,'reconnect',change,lambda c:any(p['targetPath']=='/peers/b' and p['activationGeneration']>1 for p in c['progressAfter']['plans']['A']))
    def wrong_receipt_domain(rec):
        def change(c):
            t=next(t for t in c['terminals'] if t['causeType']=='ManagedRevisionCause')
            source=rec['documentIds'];alias=next(a for a,d in source.items() if d==t['input']['cause']['childDocumentId']['value'])
            receipt=c['before'][alias]['receipts'][t['input']['cause']['toEpoch']]
            t['input']['cause']['sourceRevisionReceiptIdentity']=receipt['receiptIdentity']
        phase(rec,'reconnect',change,lambda c:any(t['causeType']=='ManagedRevisionCause' for t in c['terminals']))
    def add_source_event(rec):
        stage(rec,'reconnectedAt400',lambda o:o['records']['B']['events'].append(copy.deepcopy(o['records']['B']['events'][-1])))
    def skip_restart(rec):
        index=next(i for i,r in enumerate(rec['transcript']) if r['request']['op']=='restart');rec['transcript'].pop(index)
    def restart_changed(rec):
        r=next(r for r in rec['transcript'] if r['request']['op']=='restart');r['response']['progressAfter']['journal'].pop()
    def raise_bound(rec):
        r=next(r for r in rec['transcript'] if r['request']['op']=='drainThroughEntry');r['request']['maxSelections']=33
    def no_retirement(rec):
        stage(rec,'detached',lambda o:o['selectedSnapshots']['A']['occurrences'].clear())
    def original_cause(rec):
        def change(call):
            t=next(t for t in call['terminals'] if t['causeType']=='ManagedRevisionCause' and t['managedTransitionReceipts'])
            t['managedTransitionReceipts'][0]['originalCauseIdentity']=t['input']['cause']['causeIdentity']
        phase(rec,'reconnect',change,lambda c:any(t['causeType']=='ManagedRevisionCause' and t['managedTransitionReceipts'] for t in c['terminals']))
    tests=[
      ('saved-reference-replaced-by-current',mutate_selected),
      ('saved-retained-body-rewritten',mutate_saved),
      ('immutable-source-receipt-body-rewritten',old_receipt),
      ('future-not-actually-queued',journal_future),
      ('retirement-row-omitted',no_retirement),
      ('old-active-generation-reused',generation),
      ('source-frontier-expanded',reconnect_plan),
      ('barrier-moved-to-future',barrier),
      ('host-receipt-used-as-contracts-identity',wrong_receipt_domain),
      ('duplicate-source-event',add_source_event),
      ('detached-consumer-reacts',lambda r:stage(r,'apartAdvanced',lambda o:o['records']['A']['exactDocument'].__setitem__('observed',{'value':2}))),
      ('marker-consumes-future',lambda r:stage(r,'markedAt450',lambda o:o['records']['A']['exactDocument'].__setitem__('observed',{'value':99}))),
      ('split-owner-shrunk',lambda r:terminal(r,lambda t:t['ownedDocumentIds'].pop(),'split')),
      ('invented-unowned-source-write',lambda r:terminal(r,lambda t:t['ownedDocumentIds'].append({'value':'unrelated-observer'}))),
      ('topology-boundaries-removed',lambda r:terminal(r,lambda t:t['rootedProjection']['topologyBoundaries'].clear())),
      ('rooted-companion-unbound',lambda r:terminal(r,lambda t:t['rootedProjection'].__setitem__('companionIdentity','sha256:'+'0'*64))),
      ('original-cause-replaced-by-managed-cause',original_cause),
      ('ordered-gas-quantity-mutated',lambda r:terminal(r,lambda t:t['gas']['charges'][0].__setitem__('quantity',2))),
      ('raw-meter-subtotal-mutated',lambda r:terminal(r,lambda t:t['fullGasTrace'][0].__setitem__('subtotal',0))),
      ('blocked-converted-to-progress',lambda r:phase(r,'reconnect',lambda c:c.update(quiescent=False,paused=False))),
      ('resource-failure-ignored',lambda r:phase(r,'reconnect',lambda c:c['resourceFailures'].append({'unexpected':'missing exact value'}))),
      ('ready-with-required-barrier',lambda r:stage(r,'reconnectedAt400',lambda o:o['readiness']['A']['activeBarrierIdentities'].append('sha256:'+'0'*64))),
      ('restart-omitted',skip_restart),('restart-journal-changed',restart_changed),('bound-increased',raise_bound),
    ]
    for name,change in tests:
        mutated=copy.deepcopy(original);change(mutated)
        try:check(mutated)
        except ValueError as error:print('REJECT',name,str(error),flush=True)
        else:raise AssertionError('MUTATION_ACCEPTED: '+name)
    print('ALL_MUTATIONS_REJECTED',len(tests),args.variant)


if __name__=='__main__':main()
