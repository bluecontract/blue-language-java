#!/usr/bin/env python3
"""Negative tests for the package's abstract/adapter harness; not Blue tests."""
import importlib.util, json, sys, unittest
from pathlib import Path
R=Path(__file__).resolve().parents[1];S=R/'conformance/rooted-processing'
sys.path.insert(0,str(S));sys.path.insert(0,str(S/'model'))
import run_fixture_models as F
import run_production_adapter as P
import checkpoint_model as M

class HarnessTests(unittest.TestCase):
    def test_boolean_is_not_integer(self):self.assertFalse(P.exact_equal(True,1))
    def test_nested_boolean_is_not_integer(self):self.assertFalse(P.exact_equal({'x':[True]},{'x':[1]}))
    def test_missing_observation_fails(self):
        with self.assertRaises(ValueError):P.evaluate({},[{'actual':'x','op':'isTrue'}])
    def test_single_variant_is_not_equivalence_proof(self):
        with self.assertRaises(AssertionError):P.evaluate({'x':[1]},[{'actual':'x','op':'allEqual'}])
    def test_zero_assertions_cannot_pass(self):
        with self.assertRaises(ValueError):P.evaluate({},[])
    def test_unknown_assertion_fails(self):
        with self.assertRaises(ValueError):P.evaluate({'x':1},[{'actual':'x','op':'allowAll'}])
    def test_wrong_expected_observation_fails(self):
        with self.assertRaises(AssertionError):P.evaluate({'x':2},[{'actual':'x','op':'equals','expected':1}])
    def test_two_equal_variants_pass(self):self.assertEqual(P.evaluate({'x':[{'a':1},{'a':1}]},[{'actual':'x','op':'allEqual'}]),1)
    def test_different_variants_fail(self):
        with self.assertRaises(AssertionError):P.evaluate({'x':[1,2]},[{'actual':'x','op':'allEqual'}])
    def test_unknown_model_kind_fails(self):
        with self.assertRaises(F.FixtureError):F.execute({'kind':'not-a-test','input':{}})
    def test_source_expected_mutation_is_detected(self):
        f=json.loads((S/'fixtures/sel/rcp-sel-002.json').read_text());actual=F.execute(f)
        f['expected']['trace'][0]['entry']='wrong'
        with self.assertRaises(AssertionError):F.equal(actual,f['expected'],'mutated expected')
    def test_global_root_cursor_mutant_loses_child_work(self):
        bs=[M.Binding('root',(M.entry(10),M.entry(20)),2),M.Binding('child',(M.entry(5),M.entry(10)),1)]
        actual=M.scan_merge(bs);wrong=[x for x in actual if x[0][0]>20]
        self.assertEqual(len(actual),1);self.assertNotEqual(actual,wrong)
    def test_reverse_observer_mutant_expands_scope(self):
        edges=(('R','S'),('X','S'),('X','Y'));forward=M.reachable(edges,'R');undirected=M.reachable(edges+tuple((b,a) for a,b in edges),'R')
        self.assertEqual(forward,{'R','S'});self.assertNotEqual(forward,undirected)
    def test_payload_dedup_mutant_loses_event_occurrence(self):
        obs=M.Observation(0,0);obs.apply(M.Receipt(1,0,0,('x','x')))
        self.assertEqual(obs.log,['x','x']);self.assertNotEqual(obs.log,list(dict.fromkeys(obs.log)))
    def test_failed_history_application_keeps_position(self):
        obs=M.Observation(5,5);self.assertFalse(obs.apply(M.Receipt(6,5,6,('e6',)),fail=True));self.assertEqual((obs.position,obs.child_value,obs.log),(5,5,[]))
    def test_wrong_history_predecessor_rejected(self):
        f={'kind':'history','input':{'position':5,'value':5,'failAt':None,'receipts':[{'position':6,'before':4,'after':6,'events':[]}]}}
        with self.assertRaisesRegex(F.FixtureError,'WRONG_PREDECESSOR'):F.execute(f)
    def test_work_budget_rollback_not_prefix_commit(self):
        result=M.reactions({'A':'B','B':'A'},'A',4,4);self.assertEqual(result.status,'OUT_OF_GAS');self.assertEqual(result.committed,[]);self.assertEqual(len(result.attempted),4)
    def test_duplicate_binding_rejected(self):
        one={'address':'x','entries':[],'cursor':0}
        with self.assertRaisesRegex(F.FixtureError,'DUPLICATE_BINDING'):F.bindings([one,one])

if __name__=='__main__':unittest.main(verbosity=2)
