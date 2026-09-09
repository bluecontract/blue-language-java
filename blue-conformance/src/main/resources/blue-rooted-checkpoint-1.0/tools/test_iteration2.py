#!/usr/bin/env python3
"""Candidate-spec/harness tests only. Synthetic observations never count as SDK execution."""
from pathlib import Path
import sys,json,copy,unittest,hashlib,tempfile,itertools
R=Path(__file__).resolve().parents[1];S=R/'conformance/rooted-processing'
sys.path[:0]=[str(S),str(S/'identity'),str(S/'model')]
import constructors as I
import operation_model as M
import run_production_adapter as P
WEIGHTS=json.loads((S/'tariff-weights.json').read_text())['weights']
V=json.loads((S/'identity/vectors.json').read_text())['vectors']

def fixture(n):return json.loads((S/f'fixtures/run/rcp-run-{n:03d}.json').read_text())
def trace(amounts):
    return [{'sequence':i,'label':'bex.expressionEvaluated','quantity':x,'weight':1,'amount':x} for i,x in enumerate(amounts)]
def sample(status='SUCCESS',budget=100):
    before={'S':{'counter':0},'P':{'seen':0,'child':{'counter':0}}};after=copy.deepcopy(before)
    if status=='SUCCESS':after['S']['counter']=1
    return {'status':status,'causeKind':'LIVE','budget':budget,'before':before,'after':after,'ownedWrites':['S'] if status=='SUCCESS' else [],'events':[{'origin':'S','ordinal':0,'kind':'RCP2/Tick'}] if status=='SUCCESS' else [],'semanticReceipts':[{'owner':'S','from':0,'to':1,'cause':'E'}] if status=='SUCCESS' else [],'sourceBefore':{},'sourceAfter':{},'gas':{'charges':trace([50,50]) if status=='SUCCESS' else trace([50]),'total':100 if status=='SUCCESS' else 50,'rejected':None if status=='SUCCESS' else {'sequence':1,'label':'bex.expressionEvaluated','quantity':50,'weight':1,'amount':50,'remaining':budget-50}}}
def run(o):return {'transcript':[{'kind':'SDK_CALL','request':{'operation':'tick'},'response':{'result':'synthetic HARNESS TEST ONLY'}}],'output':o,'restart':{'before':{'exact':'sample'},'after':{'exact':'sample'},'beforeCommandCount':1,'afterCommandCount':1}}
def shared_runs():return {n:run(sample()) for n in fixture(2)['input']['variants']}
def calibration():
    o=sample();o['after']['P']={'seen':1,'child':{'counter':1}};o['ownedWrites']=['P'];o['events']=[];o['semanticReceipts'][0]['owner']='P'
    return {'executionPath':'MATERIALIZED_REFERENCE_UNCACHED','implementationSource':'TEST_ONLY_REFERENCE','transcript':[{'test':'synthetic not production'}],'output':o}
def gas_runs():
    f=fixture(20);out={}
    for name in f['input']['variants']:
        suffix=name.rsplit('-',1)[1];o=sample('GAS_LIMIT_EXCEEDED' if suffix=='below' else 'SUCCESS',{'below':99,'at':100,'above':101}[suffix]);o['events']=[]
        o['sourceBefore']={'S':{'counter':1 if name.startswith('source-committed') else 0,'receipts':['actual-source-placeholder-test-only']}};o['sourceAfter']=copy.deepcopy(o['sourceBefore'])
        o['before']['S']['counter']=o['sourceBefore']['S']['counter'];o['after']['S']['counter']=o['before']['S']['counter']
        if suffix!='below':o['ownedWrites']=['P'];o['after']['P']={'seen':1,'child':{'counter':1}};o['semanticReceipts'][0]['owner']='P'
        out[name]=run(o)
    return out

class IdentityTests(unittest.TestCase):
    def test_frozen_independently_authored_vectors(self):
        for v in V:
            with self.subTest(v=v['id']):
                method=v['constructor']
                if method=='wrapper':actual=I.wrapper(v['name'],v['input'])
                else:
                    actual=getattr(I,method)(v['input'])
                    if isinstance(actual,tuple):
                        self.assertEqual(actual[0],v['canonicalValue']);actual=actual[1]
                self.assertEqual(actual,v['expected'])
    def test_cyclic_anchor_does_not_depend_on_view(self):
        d=next(v['input'] for v in V if v['id']=='OWNER-ordered')
        self.assertEqual(I.context(d),I.context({'members':d['members'][::-1],'internalEdges':d['internalEdges'][::-1]}))
        self.assertEqual(I.context(d)[0]['canonicalRootDocumentId'],'A')
    def test_same_members_new_occurrence_is_new_owner(self):
        x=copy.deepcopy(next(v['input'] for v in V if v['id']=='OWNER-ordered'));old=I.owner(x)[1];x['internalEdges'][0]['activationGeneration']='2';self.assertNotEqual(old,I.owner(x)[1])
    def test_distinct_actual_cause_not_coalesced(self):
        x=copy.deepcopy(next(v['input'] for v in V if v['id']=='DELIVERY'));a=I.delivery(x)[1];x['causeIdentity']='sha256:'+'f'*64;self.assertNotEqual(a,I.delivery(x)[1])
    def test_distinct_admission_is_distinct_history(self):
        ids={v['expected'] for v in V if v['id'].startswith('HISTORY-')};self.assertEqual(len(ids),6)
    def test_extra_history_field_rejected(self):
        x=copy.deepcopy(V[0]['input']);x['worker']='ignored'
        with self.assertRaises(ValueError):I.history(x)
    def test_non_nfc_document_id_rejected(self):
        x=copy.deepcopy(V[0]['input']);x['documentId']='e\u0301'
        with self.assertRaises(ValueError):I.history(x)
    def test_lone_surrogate_rejected(self):
        with self.assertRaises(ValueError):I.canonical({'x':'\ud800'})
    def test_duplicate_members_rejected(self):
        x=copy.deepcopy(next(v['input'] for v in V if v['id']=='OWNER-ordered'));x['members'].append(x['members'][0])
        with self.assertRaises(ValueError):I.owner(x)
    def test_reverse_observer_cannot_be_internal_edge(self):
        x=copy.deepcopy(next(v['input'] for v in V if v['id']=='OWNER-ordered'));x['internalEdges'][0]['parentDocumentId']='outside'
        with self.assertRaises(ValueError):I.owner(x)
    def test_live_cannot_supply_history_position(self):
        x=copy.deepcopy(next(v['input'] for v in V if v['id']=='DELIVERY'));x['sourcePositionIdentity']={'kind':'POSITION','identity':'sha256:'+'a'*64}
        with self.assertRaises(ValueError):I.delivery(x)
    def test_historical_needs_exact_position(self):
        x=copy.deepcopy(next(v['input'] for v in V if v['id']=='DELIVERY'));x['kind']='MANAGED_REVISION'
        with self.assertRaises(ValueError):I.delivery(x)
    def test_decimal_boundary_and_no_numeric_json(self):
        for x in ['01','-1','1e2',1,'9007199254740992']:
            with self.subTest(x=x),self.assertRaises(ValueError):I.decimal(x)
        with self.assertRaises(ValueError):I.canonical({'x':1})
    def test_bad_pointer_rejected(self):
        with self.assertRaises(ValueError):I.pointer('/a~2b')

class OwnershipTests(unittest.TestCase):
    def test_chain_owner_is_selected_root(self):self.assertEqual(M.owners([('A','B'),('B','C')],'A'),[['A']])
    def test_cycle_owner_and_external_observer(self):self.assertEqual(M.owners([('A','B'),('B','A'),('X','A')],'A'),[['A','B']])
    def test_root_outside_child_cycle_not_replaced(self):self.assertEqual(M.owners([('X','A'),('A','B'),('B','A')],'X'),[['X']])
    def test_new_cycle_expands_but_split_does_not_shrink_step(self):self.assertEqual(M.owners([('A','B')],'A',[[('A','B'),('B','A')],[('A','B')]]),[['A'],['A','B'],['A','B']])
    def test_provisional_birth_owned_without_sibling(self):self.assertEqual(M.owners([], 'P', [[('P','N'),('Q','P')]], [('P','N')]),[['P'],['N','P']])
    def test_parent_first_does_not_publish_source(self):
        r=M.shared_schedule(['PARENT']);self.assertEqual(r['state']['S']['counter'],0);self.assertEqual(r['state']['P']['embeddedS'],1);self.assertEqual(r['state']['sourceOutbox'],[])
    def test_source_then_parent_and_parent_then_source(self):
        a=M.shared_schedule(['SOURCE','PARENT']);b=M.shared_schedule(['PARENT','SOURCE']);self.assertEqual(a['state'],b['state']);self.assertEqual(a['parentResults'][0]['trace'],b['parentResults'][0]['trace']);self.assertEqual(a['state']['sourceOutbox'],['S:E1:0'])
    def test_cache_and_observer_order_cannot_change_parent(self):
        expected=M.shared_schedule(['PARENT','SOURCE'])['state']
        for actions in itertools.permutations(['PARENT','SOURCE','CACHE','EVICT','OBSERVER_FAIL']):self.assertEqual(M.shared_schedule(actions)['state'],expected)
    def test_failure_does_not_publish_local_or_undo_source(self):
        r=M.shared_schedule(['SOURCE','PARENT'],90);self.assertEqual(r['state']['S']['counter'],1);self.assertEqual(r['state']['P']['seen'],0);self.assertEqual(r['parentResults'][0]['ownedWrites'],[])
    def test_live_kind_independent_of_cache(self):
        for c in ['cold','cached','evicted','source-committed']:self.assertEqual(M.cause_kind({'mode':'LIVE'},15,c),'LIVE')
    def test_historical_kind_is_semantic_interval(self):
        for c in ['cold','cached','evicted','source-committed']:
            self.assertEqual(M.cause_kind({'mode':'HISTORICAL','anchor':20},15,c),'MANAGED_REVISION');self.assertEqual(M.cause_kind({'mode':'HISTORICAL','anchor':20},25,c),'LIVE')
    def test_illustrative_budget_not_blue_tariff(self):
        state=M.shared_schedule([])['state']
        for budget,status in [(99,'GAS_LIMIT_EXCEEDED'),(100,'SUCCESS'),(101,'SUCCESS')]:self.assertEqual(M.reference_parent(state,budget,'cold')['status'],status)

class HarnessV2Tests(unittest.TestCase):
    def test_valid_synthetic_positive_is_only_harness_test(self):self.assertGreater(P.check_runs(fixture(2),shared_runs(),WEIGHTS),0)
    def test_all_error_variants_do_not_pass(self):
        r=shared_runs()
        for x in r.values():x['output']['status']='REJECTED'
        with self.assertRaises(ValueError):P.check_runs(fixture(2),r,WEIGHTS)
    def test_equal_rejected_unmodified_states_still_reject(self):
        r=shared_runs()
        for x in r.values():
            o=x['output'];o.update(status='REJECTED',after=copy.deepcopy(o['before']),ownedWrites=[],events=[],semanticReceipts=[],gas={'charges':[],'total':0,'rejected':None})
        with self.assertRaisesRegex(ValueError,'ANCHOR_STATUS'):P.check_runs(fixture(2),r,WEIGHTS)
    def test_missing_named_variant_rejected(self):
        r=shared_runs();r.pop(next(iter(r)))
        with self.assertRaisesRegex(ValueError,'VARIANTS'):P.check_runs(fixture(2),r,WEIGHTS)
    def test_extra_variant_rejected(self):
        r=shared_runs();r['unexpected']=run(sample())
        with self.assertRaisesRegex(ValueError,'VARIANTS'):P.check_runs(fixture(2),r,WEIGHTS)
    def test_noop_success_rejected_by_positive_anchor(self):
        r=shared_runs()
        for x in r.values():x['output']['after']['S']['counter']=0
        with self.assertRaisesRegex(ValueError,'ANCHOR'):P.check_runs(fixture(2),r,WEIGHTS)
    def test_dropped_event_rejected(self):
        r=shared_runs()
        for x in r.values():x['output']['events']=[]
        with self.assertRaisesRegex(ValueError,'EVENT_COUNT'):P.check_runs(fixture(2),r,WEIGHTS)
    def test_empty_success_trace_rejected(self):
        r=shared_runs()
        for x in r.values():x['output']['gas']={'charges':[],'total':0,'rejected':None}
        with self.assertRaisesRegex(ValueError,'EMPTY_SUCCESS'):P.check_runs(fixture(2),r,WEIGHTS)
    def test_empty_receipts_rejected(self):
        r=shared_runs()
        for x in r.values():x['output']['semanticReceipts']=[]
        with self.assertRaisesRegex(ValueError,'EMPTY_SUCCESS'):P.check_runs(fixture(2),r,WEIGHTS)
    def test_true_not_integer_anchor(self):
        r=shared_runs()
        for x in r.values():x['output']['after']['S']['counter']=True
        with self.assertRaisesRegex(ValueError,'ANCHOR'):P.check_runs(fixture(2),r,WEIGHTS)
    def test_recipe_cannot_be_passed(self):
        with self.assertRaisesRegex(ValueError,'RECIPE'):P.check_runs(fixture(4),{},WEIGHTS)
    def test_gas_boundary_positive_synthetic_harness_only(self):self.assertGreater(P.check_runs(fixture(20),gas_runs(),WEIGHTS,calibration()),0)
    def test_all_success_gas_bug_rejected(self):
        r=gas_runs()
        for n,x in r.items():
            if n.endswith('below'):x['output']=copy.deepcopy(r['cold-at']['output'])
        with self.assertRaises(ValueError):P.check_runs(fixture(20),r,WEIGHTS,calibration())
    def test_gas_empty_traces_cannot_pass(self):
        r=gas_runs()
        for x in r.values():x['output']['gas']={'charges':[],'total':0,'rejected':None}
        with self.assertRaises(ValueError):P.check_runs(fixture(20),r,WEIGHTS,calibration())
    def test_wrong_tariff_rejected(self):
        g=sample()['gas'];g['charges'][0]['weight']=2
        with self.assertRaisesRegex(ValueError,'TARIFF'):P.gas_check(g,100,WEIGHTS)
    def test_wrong_arithmetic_rejected(self):
        g=sample()['gas'];g['charges'][0]['amount']=49
        with self.assertRaisesRegex(ValueError,'ARITHMETIC'):P.gas_check(g,100,WEIGHTS)
    def test_wrong_trace_order_rejected(self):
        g=sample()['gas'];g['charges'][0]['sequence']=1
        with self.assertRaisesRegex(ValueError,'ORDER'):P.gas_check(g,100,WEIGHTS)
    def test_failure_partial_state_rejected(self):
        o=sample('GAS_LIMIT_EXCEEDED',99);o['after']['S']['counter']=1
        with self.assertRaisesRegex(ValueError,'STATE_CHANGED'):P.structured_output(o,WEIGHTS)
    def test_changed_source_on_consumer_success_rejected(self):
        r=gas_runs();r['cold-at']['output']['sourceAfter']['S']['counter']=8
        with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):P.check_runs(fixture(20),r,WEIGHTS,calibration())
    def test_wrong_restart_rejected(self):
        r=shared_runs();r['cold-repeat']['restart']['after']={'exact':'changed'}
        with self.assertRaisesRegex(ValueError,'RESTART_STATE'):P.check_runs(fixture(2),r,WEIGHTS)
    def test_new_commands_on_restart_rejected(self):
        r=shared_runs();r['cold-repeat']['restart']['afterCommandCount']=2
        with self.assertRaisesRegex(ValueError,'RESTART_NEW'):P.check_runs(fixture(2),r,WEIGHTS)
    def test_zero_artifact_and_wrong_artifact_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'a.jar';p.write_bytes(b'not a claimed runtime')
            for sha in ['0'*64,'f'*64]:
                with self.subTest(sha=sha),self.assertRaises(ValueError):P.check_artifact(p,sha)
            self.assertEqual(P.check_artifact(p,hashlib.sha256(p.read_bytes()).hexdigest()),hashlib.sha256(p.read_bytes()).hexdigest())
    def test_bad_evidence_hash_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/'x.json').write_text('{}')
            with self.assertRaisesRegex(ValueError,'EVIDENCE_HASH'):P.read_evidence(p,{'path':'x.json','sha256':'a'*64})
    def test_evidence_path_escape_rejected(self):
        with self.assertRaisesRegex(ValueError,'EVIDENCE_PATH'):P.read_evidence(Path('.'),{'path':'../x','sha256':'a'*64})
    def test_bad_run_nonce_rejected(self):
        with self.assertRaisesRegex(ValueError,'NONCE'):P.provenance({}, {'requestNonce':'new'},'a'*64)

class EvidenceBindingTests(unittest.TestCase):
    def test_complete_step_set_passes(self):
        p={'setup':[{'stepId':'s1'}],'variants':{'v':[{'stepId':'v1','repeat':2}]}}
        P.literal_steps(p,'v',[{'planStepId':'s1','completed':True},{'planStepId':'v1','completed':True,'repeatIndex':0},{'planStepId':'v1','completed':True,'repeatIndex':1}])
    def test_omitted_action_does_not_pass(self):
        p={'setup':[{'stepId':'s1'}],'variants':{'v':[{'stepId':'v1'}]}}
        with self.assertRaisesRegex(ValueError,'INCOMPLETE_LITERAL'):P.literal_steps(p,'v',[{'planStepId':'s1','completed':True}])
    def test_missing_repeated_application_does_not_pass(self):
        p={'setup':[],'variants':{'v':[{'stepId':'v1','repeat':5}]}}
        with self.assertRaisesRegex(ValueError,'INCOMPLETE_LITERAL'):P.literal_steps(p,'v',[{'planStepId':'v1','completed':True,'repeatIndex':i} for i in range(4)])
    def test_source_lock_is_checked_outside_driver(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'lock.json';lock={'artifactSha256':'a'*64,'dependencies':{'lib.jar':'b'*64},'sourceCommits':{k:'c'*40 for k in ('language','bex','coordination','myos')},'specificationSetSha256':'d'*64};p.write_text(json.dumps(lock));sha=hashlib.sha256(p.read_bytes()).hexdigest()
            self.assertEqual(P.source_lock(p,sha,'a'*64,{'lib.jar':'b'*64},'d'*64),lock)
            with self.assertRaisesRegex(ValueError,'SOURCE_LOCK_BINDING'):P.source_lock(p,sha,'e'*64,{'lib.jar':'b'*64},'d'*64)
    def test_source_lock_zero_commits_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'lock.json';lock={'artifactSha256':'a'*64,'dependencies':{},'sourceCommits':{k:'0'*40 for k in ('language','bex','coordination','myos')},'specificationSetSha256':'d'*64};p.write_text(json.dumps(lock));sha=hashlib.sha256(p.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError,'SOURCE_LOCK_COMMITS'):P.source_lock(p,sha,'a'*64,{},'d'*64)

class IdentityEvidenceCheckerTests(unittest.TestCase):
    def output(self):
        by={v['id']:v for v in V};owner=copy.deepcopy(by['OWNER-ordered']['input']);o=sample()
        e={'historyBases':[{'value':by['HISTORY-'+d]['input'],'identity':by['HISTORY-'+d]['expected']} for d in ['A','B']],
           'ownerDescriptor':owner,'deliveryDescriptor':copy.deepcopy(by['DELIVERY']['input']),
           'baseInvocationIdentity':by['rootedInvocationIdentity']['input']['baseInvocationIdentity'],
           'baseCommitCompanionIdentity':by['rootedCommitCompanionIdentity']['input']['baseCommitCompanionIdentity'],
           'entryLiveEdges':copy.deepcopy(owner['internalEdges']),'requestedRootDocumentId':'A'}
        o.update(identityEvidence=e,operationContext=by['CONTEXT']['canonicalValue'],deliveryBasisIdentity=by['DELIVERY']['expected'],
                 rootedInvocationIdentity=by['rootedInvocationIdentity']['expected'],rootedTerminalKey=by['rootedTerminalKey']['expected'],
                 rootedCommitCompanionIdentity=by['rootedCommitCompanionIdentity']['expected'])
        return copy.deepcopy(o)
    def test_both_view_entrypoints_recompute_same_context(self):
        o=self.output();P.identity_check(o);o['identityEvidence']['requestedRootDocumentId']='B';P.identity_check(o)
    def test_forged_context_rejected(self):
        o=self.output();o['operationContext']={'canonicalRootDocumentId':'B','operationOwnerIdentity':'sha256:'+'f'*64}
        with self.assertRaises(ValueError):P.identity_check(o)
    def test_wrong_history_operand_rejected(self):
        o=self.output();o['identityEvidence']['historyBases'][0]['value']['documentId']='C'
        with self.assertRaisesRegex(ValueError,'HISTORY'):P.identity_check(o)
    def test_reverse_edge_not_part_of_rooted_proof(self):
        o=self.output();edge=copy.deepcopy(o['identityEvidence']['entryLiveEdges'][0]);edge['parentDocumentId']='X';edge['occurrenceIdentity']='sha256:'+'f'*64;o['identityEvidence']['entryLiveEdges'].append(edge)
        with self.assertRaisesRegex(ValueError,'NON_ROOTED'):P.identity_check(o)
    def test_old_invocation_wrapper_cannot_be_reused_for_new_cause(self):
        o=self.output();o['identityEvidence']['deliveryDescriptor']['causeIdentity']='sha256:'+'f'*64
        with self.assertRaisesRegex(ValueError,'DELIVERY'):P.identity_check(o)
    def test_wrong_companion_wrapper_rejected(self):
        o=self.output();o['rootedCommitCompanionIdentity']='sha256:'+'f'*64
        with self.assertRaisesRegex(ValueError,'COMPANION'):P.identity_check(o)
    def test_duplicate_event_occurrence_rejected(self):
        o=sample();o['events'].append(copy.deepcopy(o['events'][0]))
        with self.assertRaisesRegex(ValueError,'DUPLICATE_EVENT'):P.structured_output(o,WEIGHTS)
    def test_wrong_receipt_owner_rejected(self):
        r=shared_runs()
        for x in r.values():x['output']['semanticReceipts'][0]['owner']='Unrelated'
        with self.assertRaisesRegex(ValueError,'RECEIPT_OWNER'):P.check_runs(fixture(2),r,WEIGHTS)

if __name__=='__main__':unittest.main(verbosity=2)
