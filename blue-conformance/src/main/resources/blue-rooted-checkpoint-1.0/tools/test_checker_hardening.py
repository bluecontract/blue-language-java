#!/usr/bin/env python3
"""H1-H3 checker regressions. Synthetic records only, not runtime conformance."""
import copy,json,unittest,shutil,tempfile
from pathlib import Path
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

class MultipleHistoryTests(unittest.TestCase):
    """Synthetic binding negatives, not production execution evidence."""
    def setUp(self):
        self.contract={'requiredApplicationCount':2,'sourceAliases':['A'],
                       'occurrenceSuffixes':{'/left':{'source':'A','positions':[1,2]}},
                       'applicationOrder':[['A',60],['A',90]],'perApplicationEquals':{'P.marked':False}}
        self.ids={'A':'actual-A'};self.o={'sourceBefore':{'A':{'receipts':[]}},'applications':[]}
        label,weight=next(iter(T.WEIGHTS.items()))
        for i,time in [(1,60),(2,90)]:
            doc={'value':'actual-A'};before='exact-'+str(i-1);after='exact-'+str(i)
            retained={'documentId':doc,'epoch':i,'receiptIdentity':'source-'+str(i),
                      'contractsTransitionReceiptIdentity':'transition-'+str(i),'beforeBlueId':before,
                      'afterBlueId':after,'originalCauseIdentity':'cause-'+str(i),
                      'sourceOrder':{'components':[time,'timeline','entry-'+str(i)]}}
            self.o['sourceBefore']['A']['receipts'].append(retained)
            transition={'transitionReceiptIdentity':retained['contractsTransitionReceiptIdentity'],
                        'beforeBlueId':before,'afterBlueId':after,'originalCauseIdentity':retained['originalCauseIdentity']}
            cause={'targetOccurrenceIdentity':'left-occurrence','childDocumentId':doc,'fromEpoch':i-1,
                   'toEpoch':i,'beforeBlueId':before,'afterBlueId':after,
                   'sourceRevisionReceiptIdentity':transition['transitionReceiptIdentity'],
                   'originalSourceCauseIdentity':retained['originalCauseIdentity']}
            work={'workIdentity':'work-'+str(i),'targetPath':'/left','targetOccurrenceIdentity':'left-occurrence',
                  'sourceDocumentId':doc,'sourceEpoch':i,'sourceReceiptIdentity':retained['receiptIdentity']}
            receipt={'workIdentity':work['workIdentity'],'applicationReceiptIdentity':'app-'+str(i),
                     'sourceReceiptIdentity':retained['receiptIdentity']}
            self.o['applications'].append({'source':'A','targetPath':'/left','status':'SUCCESS',
                'from':i-1,'to':i,'work':work,'receipt':receipt,'retainedSource':copy.deepcopy(retained),
                'sourceReceipt':transition,'exactCause':cause,'sourceOrder':retained['sourceOrder']['components'],
                'after':{'P':{'marked':False}},'budget':weight,
                'gas':{'charges':[{'sequence':0,'label':label,'quantity':1,'weight':weight,'amount':weight}],
                       'total':weight,'rejected':None}})
    def check(self):P.check_multiple_history(self.o,self.contract,T.WEIGHTS,self.ids)
    def test_complete_bound_sequence_passes(self):self.check()
    def test_skipped_application_rejects(self):
        self.o['applications'].pop()
        with self.assertRaisesRegex(ValueError,'MULTI_APPLICATION_COUNT'):self.check()
    def test_reordered_applications_reject(self):
        self.o['applications'].reverse()
        with self.assertRaisesRegex(ValueError,'MULTI_WRONG_SUFFIX'):self.check()
    def test_duplicate_work_rejects(self):
        h=self.o['applications'][1];h['work']['workIdentity']='work-1';h['receipt']['workIdentity']='work-1'
        with self.assertRaisesRegex(ValueError,'MULTI_DUPLICATE_WORK'):self.check()
    def test_occurrence_swap_rejects(self):
        self.o['applications'][0]['work']['targetOccurrenceIdentity']='right-occurrence'
        with self.assertRaisesRegex(ValueError,'MULTI_OCCURRENCE_BINDING'):self.check()
    def test_source_alias_cannot_hide_wrong_lineage(self):
        self.o['applications'][0]['work']['sourceDocumentId']={'value':'wrong-source'}
        with self.assertRaisesRegex(ValueError,'MULTI_SOURCE_ID'):self.check()
    def test_unretained_source_receipt_rejects(self):
        self.o['applications'][0]['retainedSource']['receiptIdentity']='forged-source'
        with self.assertRaisesRegex(ValueError,'MULTI_UNRETAINED_SOURCE'):self.check()
    def test_exact_predecessor_mismatch_rejects(self):
        self.o['applications'][0]['exactCause']['beforeBlueId']='wrong-predecessor'
        with self.assertRaisesRegex(ValueError,'MULTI_EXACT_SUCCESSOR'):self.check()
    def test_wrong_reported_timestamp_rejects(self):
        self.o['applications'][0]['sourceOrder']=[55,'timeline','entry-1']
        with self.assertRaisesRegex(ValueError,'MULTI_SOURCE_ORDER'):self.check()
    def test_later_input_overtaking_history_rejects(self):
        self.o['applications'][0]['after']['P']['marked']=True
        with self.assertRaisesRegex(ValueError,'MULTI_OVERTAKE'):self.check()

class TariffBindingTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.suite=Path(self.temp.name)
        shutil.copy2(T.S/'tariff-weights.json',self.suite/'tariff-weights.json')
        shutil.copytree(T.S/'tariffs',self.suite/'tariffs')
    def change(self,mutate):
        path=self.suite/'tariff-weights.json';value=json.loads(path.read_text())
        mutate(value);path.write_text(json.dumps(value))
    def test_complete_hosted_table_derives_from_exact_manifests(self):
        self.assertEqual(T.WEIGHTS,P.verified_tariff_weights(self.suite))
    def test_unreviewed_counter_cannot_extend_table(self):
        self.change(lambda x:x['weights'].update({'runtime.futureUnreviewedCounter':1}))
        with self.assertRaisesRegex(ValueError,'TARIFF_DERIVATION_MISMATCH'):P.verified_tariff_weights(self.suite)
    def test_existing_weight_cannot_change(self):
        self.change(lambda x:x['weights'].update({'runtime.functionCalled':3}))
        with self.assertRaisesRegex(ValueError,'TARIFF_DERIVATION_MISMATCH'):P.verified_tariff_weights(self.suite)
    def test_modified_source_bytes_fail_before_derivation(self):
        path=self.suite/'tariffs/coordination-gas-1.0.yaml'
        path.write_text(path.read_text().replace('weight: 3','weight: 4',1))
        with self.assertRaisesRegex(ValueError,'TARIFF_SOURCE_HASH'):P.verified_tariff_weights(self.suite)

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

class AuthoredInitialCyclePlanTests(unittest.TestCase):
    def setUp(self):
        self.plan=json.loads((T.S/'plans/rcp-run-023.json').read_text())
        self.variant='view-A'
        self.rows=[{'kind':'SDK_CALL','planStepId':s['stepId'],'completed':True,'request':{},'response':{}}
                   for s in self.plan['setup']+self.plan['variants'][self.variant]]
    def test_saved_original_has_one_explicit_genesis_successor(self):
        self.assertEqual(self.plan['setup'][2]['request']['a'],{'$capture':'A.initialBlueId'})
        step=self.plan['setup'][4]
        self.assertEqual(step['stepId'],'setup-004-genesis')
        self.assertEqual(step['mustConsumePositions'],[-1,0])
        self.assertEqual(step['inputFamily'],'RETAINED_SUCCESSOR')
        P.literal_steps(self.plan,self.variant,self.rows)
    def test_omitted_genesis_completion_rejects(self):
        rows=[r for r in self.rows if r['planStepId']!='setup-004-genesis']
        with self.assertRaisesRegex(ValueError,'INCOMPLETE'):
            P.literal_steps(self.plan,self.variant,rows)
    def test_activation_assertion_before_genesis_rejects(self):
        self.rows[4],self.rows[5]=self.rows[5],self.rows[4]
        with self.assertRaisesRegex(ValueError,'STEP_ORDER'):
            P.literal_steps(self.plan,self.variant,self.rows)

class PhaseEventTests(unittest.TestCase):
    """Synthetic collection event-inventory checks, not a runtime pass."""
    def setUp(self):
        label,weight=next(iter(T.WEIGHTS.items()))
        events=[{'origin':'P','ordinal':0,'kind':'direct-observed'},
                {'origin':'P','ordinal':1,'kind':'descendant-observed'}]
        self.phases={'first':{'status':'SUCCESS','causeKind':'LIVE','budget':weight,
            'before':{'P':{'count':0}},'after':{'P':{'count':1}},'ownedWrites':['P'],
            'events':copy.deepcopy(events),'semanticReceipts':[{'owner':'P','receiptIdentity':'receipt'}],
            'sourceBefore':{},'sourceAfter':{},'gas':{'total':weight,'rejected':None,
              'charges':[{'sequence':0,'label':label,'quantity':1,'weight':weight,'amount':weight}]}}}
        self.anchors={'first':events}
    def check(self):P.check_phase_events(self.phases,self.anchors,T.WEIGHTS)
    def test_exact_phase_inventory_passes(self):self.check()
    def test_missing_phase_rejects(self):
        self.phases.clear()
        with self.assertRaisesRegex(ValueError,'MISSING_EVENT_PHASE'):self.check()
    def test_missing_descendant_rejects(self):
        self.phases['first']['events'].pop()
        with self.assertRaisesRegex(ValueError,'PHASE_EVENT_COUNT'):self.check()
    def test_extra_initialization_or_source_event_rejects(self):
        self.phases['first']['events'].append({'origin':'P','ordinal':2,'kind':'initialized'})
        with self.assertRaisesRegex(ValueError,'PHASE_EVENT_COUNT'):self.check()
    def test_wrong_origin_rejects(self):
        self.phases['first']['events'][0]['origin']='source'
        with self.assertRaisesRegex(ValueError,'PHASE_EVENT_ANCHOR'):self.check()
    def test_reversed_event_order_rejects(self):
        self.phases['first']['events'].reverse()
        with self.assertRaisesRegex(ValueError,'PHASE_EVENT_ANCHOR'):self.check()

class LiteralSourceTests(unittest.TestCase):
    def setUp(self):
        self.fixture=json.loads((T.S/'fixtures/run/rcp-run-011.json').read_text())
        self.plan=json.loads((T.S/'plans/rcp-run-011.json').read_text())
    def load(self):return P.literal_sources(T.S,self.fixture,self.plan)
    def test_declared_legacy_examples_are_included_exactly(self):
        sources=self.load()
        self.assertEqual(len(sources),4)
        self.assertEqual(sources['examples/empty-orders.yaml'],(T.S/'examples/empty-orders.yaml').read_text())
    def test_traversal_rejects(self):
        self.plan['setup'][0]['source']='examples/../run_production_adapter.py'
        with self.assertRaisesRegex(ValueError,'LITERAL_SOURCE_PATH'):self.load()
    def test_absolute_path_rejects(self):
        self.plan['setup'][0]['source']=str(T.S/'examples/empty-orders.yaml')
        with self.assertRaisesRegex(ValueError,'LITERAL_SOURCE_PATH'):self.load()
    def test_missing_source_rejects(self):
        self.plan['setup'][0]['source']='examples/not-present.yaml'
        with self.assertRaisesRegex(ValueError,'LITERAL_SOURCE_MISSING'):self.load()
    def test_non_yaml_rejects(self):
        self.plan['setup'][0]['source']='examples/not-yaml.json'
        with self.assertRaisesRegex(ValueError,'LITERAL_SOURCE_PATH'):self.load()

class SilentMiddleTests(unittest.TestCase):
    def setUp(self):
        self.ids={'S':'source','M':'middle','P':'parent'}
        self.contract={'silentMiddle':'M','selectedExactAdvanced':['S','M','P'],
                       'computedSourceEvent':{'origin':'S','ordinal':0,'kind':'RCP2/Tick'}}
        self.output={'implementationEvidence':{'complete':True,'invocationIdentity':'actual',
            'inputInvocationIdentity':'actual','workTrace':[{'ordinal':0,'kind':'CONTAINING_REFERENCE_UPDATE',
            'targetDocumentId':{'value':'middle'}}], 'documentStepTrace':[{'workOrdinal':0,'targetDocumentId':{'value':'middle'}}]},
            'selectedBefore':{},'selectedAfter':{},'computedEvents':[copy.deepcopy(self.contract['computedSourceEvent'])]}
        for alias,identity in self.ids.items():
            self.output['selectedBefore'][alias]={'documentId':identity,'blueId':'before-'+alias,'exactDocument':{'counter':0}}
            self.output['selectedAfter'][alias]={'documentId':identity,'blueId':'after-'+alias,'exactDocument':{'counter':1}}
        event=self.output['computedEvents'][0];event['transitionReceiptIdentity']='computed-source'
        self.output['computedReceipts']=[{'documentId':{'value':'source'},'beforeBlueId':'before-S',
            'afterBlueId':'after-S','transitionReceiptIdentity':'computed-source',
            'emittedRootEvents':[{k:v for k,v in event.items() if k not in ('origin','kind','transitionReceiptIdentity')}]}]
    def check(self):P.check_silent_middle(self.output,self.contract,self.ids)
    def test_complete_reference_work_passes(self):self.check()
    def test_incomplete_evidence_rejects(self):
        self.output['implementationEvidence']['complete']=False
        with self.assertRaisesRegex(ValueError,'SILENT_INCOMPLETE'):self.check()
    def test_other_invocation_rejects(self):
        self.output['implementationEvidence']['invocationIdentity']='other'
        with self.assertRaisesRegex(ValueError,'SILENT_WRONG_INVOCATION'):self.check()
    def test_hidden_middle_handler_work_rejects(self):
        self.output['implementationEvidence']['workTrace'][0]['kind']='EMBEDDED_EVENT'
        with self.assertRaisesRegex(ValueError,'SILENT_MIDDLE_HANDLER_WORK'):self.check()
    def test_unadvanced_reference_rejects(self):
        self.output['selectedAfter']['M']=copy.deepcopy(self.output['selectedBefore']['M'])
        with self.assertRaisesRegex(ValueError,'SILENT_REFERENCE_NOT_ADVANCED'):self.check()
    def test_wrong_lineage_rejects(self):
        self.output['selectedAfter']['M']['documentId']='different'
        with self.assertRaisesRegex(ValueError,'SILENT_SELECTED_LINEAGE'):self.check()
    def test_duplicate_source_emission_rejects(self):
        self.output['computedEvents']*=2
        with self.assertRaisesRegex(ValueError,'SILENT_SOURCE_EVENT_COUNT'):self.check()
    def test_unbound_source_receipt_rejects(self):
        self.output['computedReceipts'][0]['transitionReceiptIdentity']='other'
        with self.assertRaisesRegex(ValueError,'SILENT_SOURCE_RECEIPT_BINDING'):self.check()
    def test_wrong_source_position_rejects(self):
        self.output['computedReceipts'][0]['afterBlueId']='other'
        with self.assertRaisesRegex(ValueError,'SILENT_SOURCE_RECEIPT_POSITION'):self.check()
    def test_different_receipted_event_rejects(self):
        self.output['computedReceipts'][0]['emittedRootEvents'][0]['ordinal']=7
        with self.assertRaisesRegex(ValueError,'SILENT_SOURCE_RECEIPT_EVENTS'):self.check()
    def test_wrong_source_event_kind_rejects(self):
        self.output['computedEvents'][0]['kind']='other'
        with self.assertRaisesRegex(ValueError,'SILENT_SOURCE_EVENT_ANCHOR'):self.check()

class EqualOccurrenceTests(unittest.TestCase):
    def setUp(self):
        self.ids={'S':'source','P':'parent'};self.contract={'sourceAlias':'S','consumerAlias':'P'}
        self.events=[{'ordinal':i,'sourceDocumentId':{'value':'source'},'eventBlueId':'same-blueid',
                      'exactEvent':{'kind':{'value':'RCP2/Tick'}},'occurrenceIdentity':'occurrence-'+str(i)} for i in range(2)]
        self.work=[{'kind':'EMBEDDED_EVENT','targetDocumentId':{'value':'parent'},'eventBlueId':'same-blueid',
                    'sourceOccurrenceIdentity':'occurrence-'+str(i)} for i in range(2)]
        self.output={'computedReceipts':[{'documentId':{'value':'source'},'emittedRootEvents':self.events}],
                     'implementationEvidence':{'complete':True,'invocationIdentity':'actual',
                     'inputInvocationIdentity':'actual','workTrace':self.work}}
    def check(self):P.check_equal_occurrences(self.output,self.contract,self.ids)
    def test_two_exact_distinct_deliveries_pass(self):self.check()
    def test_missing_emission_rejects(self):
        self.events.pop()
        with self.assertRaisesRegex(ValueError,'EQUAL_SOURCE_ORDINALS'):self.check()
    def test_duplicate_ordinal_rejects(self):
        self.events[1]['ordinal']=0
        with self.assertRaisesRegex(ValueError,'EQUAL_SOURCE_ORDINALS'):self.check()
    def test_other_source_lineage_rejects(self):
        self.events[1]['sourceDocumentId']={'value':'other'}
        with self.assertRaisesRegex(ValueError,'EQUAL_SOURCE_LINEAGE'):self.check()
    def test_different_payload_rejects(self):
        self.events[1]['exactEvent']={'kind':{'value':'different'}}
        with self.assertRaisesRegex(ValueError,'EQUAL_PAYLOAD_REQUIRED'):self.check()
    def test_same_occurrence_identity_rejects(self):
        self.events[1]['occurrenceIdentity']=self.events[0]['occurrenceIdentity']
        with self.assertRaisesRegex(ValueError,'EQUAL_OCCURRENCES_DISTINCT'):self.check()
    def test_dropped_delivery_rejects(self):
        self.work.pop()
        with self.assertRaisesRegex(ValueError,'EQUAL_DELIVERY_COUNT'):self.check()
    def test_reordered_delivery_rejects(self):
        self.work.reverse()
        with self.assertRaisesRegex(ValueError,'EQUAL_DELIVERY_OCCURRENCES'):self.check()
    def test_reused_first_delivery_rejects(self):
        self.work[1]=copy.deepcopy(self.work[0])
        with self.assertRaisesRegex(ValueError,'EQUAL_DELIVERY_OCCURRENCES'):self.check()

if __name__=='__main__':unittest.main(verbosity=2)
