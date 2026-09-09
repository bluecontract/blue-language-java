#!/usr/bin/env python3
"""H1-H3 checker regressions. Synthetic records only, not runtime conformance."""
import copy,json,unittest
import test_iteration2 as T
P=T.P

class CompletionTests(unittest.TestCase):
    def setUp(self):
        self.plan=json.loads((T.S/'plans/rcp-run-002.json').read_text());self.variant='no-extra-observer'
        self.steps=self.plan['setup']+self.plan['variants'][self.variant]
        self.records=[{'kind':'SDK_CALL','planStepId':s['stepId'],'completed':True,'request':{},'response':{}} for s in self.steps]
    def check(self,rows,plan=None):P.literal_steps(plan or self.plan,self.variant,rows)
    def test_exact_order_passes(self):self.check(self.records)
    def test_reverse_order_rejects(self):
        with self.assertRaisesRegex(ValueError,'STEP_ORDER'):self.check(self.records[::-1])
    def test_duplicate_completion_rejects(self):
        with self.assertRaisesRegex(ValueError,'DUPLICATE_STEP_COMPLETION'):self.check(self.records+[copy.deepcopy(self.records[-1])])
    def test_missing_completion_rejects(self):
        with self.assertRaisesRegex(ValueError,'INCOMPLETE'):self.check(self.records[:-1])
    def test_retry_before_completion_is_allowed(self):
        retry=copy.deepcopy(self.records[2]);retry['completed']=False
        self.check(self.records[:2]+[retry,copy.deepcopy(retry)]+self.records[2:])
    def test_low_level_call_without_flag_not_a_completion(self):
        call=copy.deepcopy(self.records[2]);call.pop('completed');self.check(self.records[:2]+[call]+self.records[2:])
    def test_integer_true_is_not_completion(self):
        rows=copy.deepcopy(self.records);rows[0]['completed']=1
        with self.assertRaisesRegex(ValueError,'COMPLETION_FLAG'):self.check(rows)
    def test_unknown_step_rejects(self):
        rows=copy.deepcopy(self.records);rows[0]['planStepId']='not-planned'
        with self.assertRaisesRegex(ValueError,'UNKNOWN'):self.check(rows)
    def repeated(self):
        p=copy.deepcopy(self.plan);p['setup'][0]['repeat']=2
        a=copy.deepcopy(self.records[0]);b=copy.deepcopy(a);a['repeatIndex']=0;b['repeatIndex']=1
        return p,[a,b]+self.records[1:]
    def test_repeats_in_order_pass(self):
        p,r=self.repeated();self.check(r,p)
    def test_reversed_repeats_reject(self):
        p,r=self.repeated();r[0],r[1]=r[1],r[0]
        with self.assertRaisesRegex(ValueError,'STEP_ORDER'):self.check(r,p)
    def test_duplicate_repeat_rejects(self):
        p,r=self.repeated();r[1]['repeatIndex']=0
        with self.assertRaisesRegex(ValueError,'DUPLICATE_STEP_COMPLETION'):self.check(r,p)
    def test_bool_repeat_index_rejects(self):
        r=copy.deepcopy(self.records);r[0]['repeatIndex']=False
        with self.assertRaisesRegex(ValueError,'STEP_REPEAT_INDEX'):self.check(r)
    def test_duplicate_plan_id_rejects(self):
        p=copy.deepcopy(self.plan);p['setup'][1]['stepId']=p['setup'][0]['stepId']
        with self.assertRaisesRegex(ValueError,'DUPLICATE_PLAN'):self.check(self.records,p)

class GasTests(unittest.TestCase):
    def check(self,r,cal=None):P.check_runs(T.fixture(20),r,T.WEIGHTS,cal or T.calibration())
    def test_reference_success_passes(self):self.check(T.gas_runs())
    def test_peer_equal_but_wrong_reference_trace_rejects(self):
        r=T.gas_runs()
        for n,x in r.items():
            if not n.endswith('-below'):x['output']['gas']['charges']=T.trace([40,60])
        with self.assertRaisesRegex(ValueError,'SUCCESS_TRACE_DIFFERS'):self.check(r)
    def test_only_one_success_wrong_trace_rejects(self):
        r=T.gas_runs();r['cold-at']['output']['gas']['charges']=T.trace([20,80])
        with self.assertRaisesRegex(ValueError,'SUCCESS_TRACE_DIFFERS'):self.check(r)
    def test_split_reference_charge_rejects(self):
        r=T.gas_runs()
        for n,x in r.items():
            if not n.endswith('-below'):x['output']['gas']['charges']=T.trace([25,25,50])
        with self.assertRaisesRegex(ValueError,'SUCCESS_TRACE_DIFFERS'):self.check(r)
    def test_below_prefix_validation_remains(self):
        r=T.gas_runs();g=r['cold-below']['output']['gas'];g['charges']=T.trace([40]);g['total']=40;g['rejected'].update(quantity=60,amount=60,remaining=59)
        with self.assertRaisesRegex(ValueError,'WRONG_REJECTED_PREFIX'):self.check(r)

class EventTests(unittest.TestCase):
    def check(self,r):P.check_runs(T.fixture(2),r,T.WEIGHTS)
    def test_literal_tick_passes(self):self.check(T.shared_runs())
    def test_same_wrong_kind_rejects(self):
        r=T.shared_runs()
        for x in r.values():x['output']['events'][0]['kind']='NotTick'
        with self.assertRaisesRegex(ValueError,'EVENT_ANCHOR:kind'):self.check(r)
    def test_same_wrong_origin_rejects(self):
        r=T.shared_runs()
        for x in r.values():x['output']['events'][0]['origin']='P'
        with self.assertRaisesRegex(ValueError,'EVENT_ANCHOR:origin'):self.check(r)
    def test_same_wrong_ordinal_rejects(self):
        r=T.shared_runs()
        for x in r.values():x['output']['events'][0]['ordinal']=1
        with self.assertRaisesRegex(ValueError,'EVENT_ANCHOR:ordinal'):self.check(r)
    def test_missing_kind_rejects(self):
        r=T.shared_runs()
        for x in r.values():x['output']['events'][0].pop('kind')
        with self.assertRaisesRegex(ValueError,'EVENT_ANCHOR:kind'):self.check(r)
    def test_extra_authenticated_metadata_not_rejected(self):
        r=T.shared_runs()
        for x in r.values():x['output']['events'][0]['extraEvidence']={'retained':'synthetic test only'}
        self.check(r)
    def test_fixture_plan_contracts_remain_equal(self):
        for n in [1,2]:
            p=json.loads((T.S/f'plans/rcp-run-{n:03d}.json').read_text())
            self.assertEqual(p['outputContract'],T.fixture(n)['expected']['contract'])
    def test_baseline_single_variant_anchors_event(self):
        f=T.fixture(1);r={'baseline':T.run(T.sample())};P.check_runs(f,r,T.WEIGHTS)
        r['baseline']['output']['events'][0]['kind']='NotTick'
        with self.assertRaisesRegex(ValueError,'EVENT_ANCHOR'):P.check_runs(f,r,T.WEIGHTS)

if __name__=='__main__':unittest.main(verbosity=2)
