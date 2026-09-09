#!/usr/bin/env python3
"""Mutate a real RUN028 matrix after its unchanged original passes; never run a JVM."""
import argparse,copy,importlib.util,json,sys
from pathlib import Path

def load(path,name):
    spec=importlib.util.spec_from_file_location(name,path);result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--record',type=Path,required=True);parser.add_argument('--suite',type=Path,required=True)
    args=parser.parse_args();sys.path.insert(0,str(args.suite.resolve()))
    runner=load(args.suite/'run_production_adapter.py','fault_runner');checker=load(args.suite/'check_fault_cuts.py','fault_checker')
    rule=json.loads((args.suite/'plans/rcp-run-028.json').read_text())['outputContract']['faultCuts'];weights=runner.verified_tariff_weights(args.suite)
    def check(record):checker.check_fault_cuts(record,rule,weights,runner.require,runner.exact_equal,runner.gas_check)
    original=json.loads(args.record.read_text());check(original);print('ORIGINAL_PASS',flush=True)
    def lane(rec,index=0):return rec['output']['faultCuts']['lanes'][index]
    def case(rec,index=0,cut=0):return lane(rec,index)['cases'][cut]
    def terminal(rec,index=0,cut=0):return case(rec,index,cut)['fault']['marker']['actualInvocation']['drain']['terminals'][0]
    def sql_change(rec):
        state=case(rec)['cold']['record']['sql']['tables'];state['mini_document_session']['rows'][0]['current_epoch']+=1
    def receipt_change(rec):
        case(rec)['recovery']['record']['recovered']['sdkRecords']['source']['receipts'][0]['afterDocument']['name']='rewritten immutable receipt'
    def duplicate_event(rec):
        table=case(rec)['recovery']['record']['duplicate']['sql']['tables']['mini_public_event']['rows'];table.append(copy.deepcopy(table[0]))
    def second_restart(rec):
        state=case(rec)['secondRestart']['record']['recovered'];state['sdkRecords']['source']['events'].append(copy.deepcopy(state['sdkRecords']['source']['events'][0]))
    def bad_platform(rec):
        state=case(rec)['recovery']['record']['recovered'];target=lane(rec)['seed']['record']['target']['commandId']
        row=next(x for x in state['sql']['tables']['mini_platform_event']['rows'] if x['command_id']==target);row['semantic_key']='forged terminal key'
    def bad_checkpoint(rec):
        rows=case(rec,0,3)['fault']['marker']['actualWrite']['replacement'];rows[0]['subjectEntryBlueId']='wrong exact entry'
        case(rec,0,3)['fault']['marker']['atCut']['sql']['tables']['mini_checkpoint_projection']=copy.deepcopy(case(rec,0,3)['fault']['marker']['actualInvocation']['preFinalizerSql']['tables']['mini_checkpoint_projection'])
    tests=[
        ('missing-lane',lambda r:r['output']['faultCuts']['lanes'].pop()),
        ('missing-cut',lambda r:lane(r)['cases'].pop()),
        ('timeout-credited-as-cut',lambda r:case(r)['fault']['process'].__setitem__('returnCode',71)),
        ('cleanup-credited-as-cut',lambda r:case(r)['fault']['process'].__setitem__('cleanupTermination',True)),
        ('wrong-owned-pid',lambda r:case(r)['fault']['marker'].__setitem__('pid',0)),
        ('wrong-process-token',lambda r:case(r)['fault']['marker'].__setitem__('processToken','forged')),
        ('same-jvm-restart',lambda r:case(r)['secondRestart'].__setitem__('process',copy.deepcopy(case(r)['recovery']['process']))),
        ('not-cold-readonly',lambda r:case(r)['cold']['record'].__setitem__('readOnly',False)),
        ('partial-publication',sql_change),
        ('wrong-cut-site',lambda r:case(r)['fault']['marker'].__setitem__('site','exception thrown after restart')),
        ('missing-real-waiter',lambda r:case(r)['fault']['marker'].__setitem__('registeredWaiter',False)),
        ('already-delivered-notification',lambda r:case(r,0,2)['fault']['marker'].__setitem__('waiterCompleted',True)),
        ('wrong-cursor-replacement',bad_checkpoint),
        ('retained-local-lane-substitution',lambda r:lane(r,1)['seed']['record']['selectedAtClaim'].__setitem__('rootedRetainedRoot',{'value':'local'})),
        ('retained-source-receipt-substitution',lambda r:terminal(r,1)['input']['cause'].__setitem__('sourceRevisionReceiptIdentity',lane(r,1)['seed']['record']['target']['sourceReceiptIdentity'])),
        ('retained-no-cursor-write',lambda r:case(r,1,3)['fault']['marker']['actualWrite'].__setitem__('delegateReturned',False)),
        ('retained-skipped-cursor-epoch',lambda r:case(r,1,3)['fault']['marker']['actualWrite']['replacement'].__setitem__('nextSourceEpoch',3)),
        ('immutable-receipt-rewritten',receipt_change),
        ('duplicate-public-emission',duplicate_event),
        ('restart-duplicates-source-event',second_restart),
        ('terminal-platform-event-forged',bad_platform),
        ('zero-gas-on-precommit-crash',lambda r:terminal(r)['gas'].__setitem__('total',0)),
        ('lost-companion',lambda r:terminal(r).__setitem__('commitCompanion',{})),
        ('forged-rooted-wrapper',lambda r:terminal(r)['rootedProjection'].__setitem__('invocationIdentity','forged')),
        ('unowned-source-written',lambda r:terminal(r,1)['ownedDocumentIds'].append({'value':lane(r,1)['seed']['record']['captures']['source']['documentId']})),
        ('changed-loaded-class',lambda r:case(r)['fault']['process']['handshake']['applicationBinding']['applicationEntries'][0].__setitem__('loadedSha256','0'*64)),
        ('changed-source-lock',lambda r:case(r)['fault']['process']['handshake']['sourceLock']['sourceCommits'].__setitem__('myos','0'*40)),
        ('saved-original-substituted',lambda r:lane(r)['seed']['record']['captures']['source'].__setitem__('initialBlueId',lane(r)['seed']['record']['captures']['source']['epochZeroReceipt']['afterBlueId'])),
        ('postcommit-reprocessed',lambda r:case(r,0,1)['recovery']['record']['actualInvocations'].append(copy.deepcopy(lane(r)['control']['record']['actualInvocations'][0]))),
        ('precommit-recovery-skipped',lambda r:case(r)['recovery']['record'].__setitem__('actualInvocations',[])),
        ('duplicate-command-created',lambda r:case(r)['recovery']['record']['duplicateSubmission'].__setitem__('created',True)),
    ]
    for name,mutate in tests:
        record=copy.deepcopy(original);mutate(record)
        try:check(record)
        except (ValueError,KeyError,AssertionError,TypeError,IndexError,StopIteration) as failure:print('REJECT',name,type(failure).__name__,str(failure)[:180],flush=True)
        else:raise AssertionError('MUTATION_ACCEPTED '+name)
    print('ALL_MUTATIONS_REJECTED',len(tests),flush=True)
if __name__=='__main__':main()
