#!/usr/bin/env python3
"""Require a genuine RUN025 record to pass, then reject independent evidence mutations."""
import argparse
import copy
import json
from pathlib import Path
import sys


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite',type=Path,required=True)
    parser.add_argument('--record',type=Path,required=True)
    parser.add_argument('--variant',choices=('normal','A-suspended'),required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();sys.path.insert(0,str(args.suite.resolve()))
    import run_production_adapter as P
    from check_source_discovery import check_source_discovery
    fixture=json.loads((args.suite/'fixtures/run/rcp-run-025.json').read_text())
    rule=fixture['expected']['contract']['sourceDiscovery']
    weights=json.loads((args.suite/'tariff-weights.json').read_text())['weights']
    record=json.loads(args.record.read_text())
    def check(value):check_source_discovery(value,rule,weights,args.variant,P.require,P.exact_equal,P.gas_check)
    check(record)
    results=[{'case':'genuine_baseline','passed':True}]
    def mutate(name,edit):
        changed=copy.deepcopy(record);edit(changed)
        try:check(changed)
        except (ValueError,KeyError,TypeError,IndexError) as failure:
            results.append({'case':name,'passed':True,'rejection':str(failure)})
        else:raise AssertionError('Unrejected source-discovery mutation: '+name)
    def op(r,name):return next(x['response'] for x in r['transcript'] if x['request']['op']==name)
    def phase(r,name):return r['phaseRecords'][name]
    mutate('source_already_managed',lambda r:op(r,'prepareAuthoredSource').__setitem__('managedSourcePresent',True))
    mutate('live_source_type_retention',lambda r:op(r,'prepareAuthoredSource').__setitem__('liveExactPresent',True))
    mutate('body_upload_publishes_source',lambda r:op(r,'provideAuthoredSource')['after'].__setitem__('S',{}))
    mutate('saved_original_replaced',lambda r:next(x for x in r['transcript'] if x['request']['op']=='append' and x['request']['capture']=='E10')['response']['exactEntry']['message']['request']['child'].__setitem__('blueId',r['documentIds']['A']))
    mutate('future_input_selected',lambda r:phase(r,'source15')['selection'].__setitem__('entryBlueId',phase(r,'sourceHistoryBeforeRestart')['captures']['E50']))
    mutate('wrong_frozen_cutoff',lambda r:phase(r,'sourceAdmission')['selection']['cutoffExclusive']['components'].__setitem__(0,50))
    mutate('unproven_selection_identity',lambda r:phase(r,'source15')['selection'].__setitem__('selectionIdentity','sha256:'+'0'*64))
    mutate('wrong_source_authority',lambda r:phase(r,'sourceAdmission')['selection']['sourceDocumentId'].__setitem__('value',r['documentIds']['A']))
    mutate('source_step_publishes_parent',lambda r:phase(r,'source15')['after']['B'].__setitem__('epoch',999))
    mutate('forgery_not_rejected',lambda r:phase(r,'source15')['forgedSource'].__setitem__('rejectionClass',None))
    mutate('unrelated_failure_is_not_validation',lambda r:phase(r,'source15')['forgedSource'].__setitem__('rejectionClass','java.lang.IllegalStateException'))
    mutate('admission_companion_unbound',lambda r:phase(r,'sourceAdmission')['admissionResult']['platformCommitCompanion'].__setitem__('bindsManagedTransitionReceipts',False))
    mutate('live_companion_missing',lambda r:phase(r,'source15')['terminals'][0].__setitem__('commitCompanion',None))
    mutate('live_work_identity_detached',lambda r:phase(r,'source15')['terminals'][0].__setitem__('invocationIdentity','sha256:'+'0'*64))
    def fake_historical_source(r):
        app=phase(r,'subject')['applications'][1]
        app['sourceReceipt']['transitionReceiptIdentity']='sha256:'+'0'*64
        app['exactCause']['sourceTransitionReceipt']=copy.deepcopy(app['sourceReceipt'])
        app['exactCause']['sourceRevisionReceiptIdentity']=app['sourceReceipt']['transitionReceiptIdentity']
    mutate('self_consistent_unretained_source_transition',fake_historical_source)
    mutate('wrong_history_consumer',lambda r:phase(r,'subject')['applications'][1]['work']['consumerDocumentId'].__setitem__('value',r['documentIds']['A']))
    mutate('wrong_history_occurrence',lambda r:phase(r,'subject')['applications'][1]['exactCause'].__setitem__('targetOccurrenceIdentity','sha256:'+'0'*64))
    mutate('rejection_changes_source',lambda r:phase(r,'source15')['forgedSource']['after']['S'].__setitem__('epoch',999))
    mutate('admission_wrong_document',lambda r:phase(r,'sourceAdmission')['result']['admission']['documentIds'][0].__setitem__('value',r['documentIds']['A']))
    mutate('admission_gas_discount',lambda r:phase(r,'sourceAdmission')['admissionGas'].__setitem__('total',0))
    mutate('admission_fake_work',lambda r:phase(r,'sourceAdmission')['admissionWork'].__setitem__('invocationIdentity','sha256:'+'0'*64))
    mutate('duplicate_admission_retry',lambda r:phase(r,'sourceAdmission')['repeated'].__setitem__('replayed',False))
    mutate('source_work_hidden',lambda r:phase(r,'source15')['result']['processing'].__setitem__('committedProcessTransitions',0))
    mutate('source_event_duplicate',lambda r:phase(r,'source15')['after']['S']['events'].append(copy.deepcopy(phase(r,'source15')['after']['S']['events'][0])))
    mutate('historical_step_skipped',lambda r:phase(r,'subject')['applications'].pop(0))
    mutate('history_predecessor_mismatch',lambda r:phase(r,'subject')['applications'][1]['exactCause'].__setitem__('beforeBlueId',r['documentIds']['A']))
    mutate('earlier_view_overtakes_frontier',lambda r:phase(r,'sourceHistoryBeforeRestart')['projections']['A']['child'].__setitem__('counter',5))
    mutate('future_entry_disappears',lambda r:phase(r,'sourceHistoryAfterRestart').__setitem__('nextSourceLive',None))
    mutate('restart_changes_history',lambda r:r['restart']['after']['S'].__setitem__('epoch',99))
    args.output.write_text(json.dumps({'scope':'ACTUAL_RECORD_ORACLE_NEGATIVES','variant':args.variant,'cases':results},indent=2)+'\n')
    print(f'PASS genuine baseline and {len(results)-1} source-discovery mutation negatives')

if __name__=='__main__':main()
