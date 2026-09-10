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
        self.records=[{'kind':'SDK_CALL','planStepId':s['stepId'],'completed':True,'request':copy.deepcopy(s),'response':{}} for s in self.steps]
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
        a['request']=copy.deepcopy(p['setup'][0]);b['request']=copy.deepcopy(p['setup'][0])
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
    def test_completed_step_cannot_substitute_or_omit_its_request(self):
        for index,step in enumerate(self.steps):
            for field in step:
                rows=copy.deepcopy(self.records);rows[index]['request'][field]='substituted'
                with self.subTest(step=index,field=field), self.assertRaisesRegex(ValueError,'LITERAL_REQUEST_CHANGED'):
                    self.check(rows)
        rows=copy.deepcopy(self.records);del rows[0]['request']
        with self.assertRaisesRegex(ValueError,'LITERAL_REQUEST_CHANGED'):self.check(rows)

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
        self.rows=[{'kind':'SDK_CALL','planStepId':s['stepId'],'completed':True,'request':copy.deepcopy(s),'response':{}}
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

class ChannelCheckpointHistoryTests(unittest.TestCase):
    def setUp(self):
        self.rule=dict(owner='P',channel='owner',before='attachment',after='subject',entryCapture='P20',timestamp=20)
        checkpoint={'domain':{'blueId':'domain'},'subject':{'entryBlueId':{'value':'entry-20'},'timestamp':{'value':20}}}
        self.rows=[]
        for phase in ['attachment','subject']:
            self.rows.append(dict(completed=True,request=dict(op='processNext',root='P',capture=phase),response=dict(
                retainedRecords={'P':dict(documentId='parent',exactDocument={'contracts':{'checkpoint':{'entries':{'owner':copy.deepcopy(checkpoint)}}}})})))
        self.record=dict(documentIds={'P':'parent'},transcript=[dict(completed=True,request=dict(op='append',target='P',capture='P20',timestampUs='20'),response=dict(entryBlueId='entry-20'))]+self.rows)
    def checkpoint(self,index):return self.rows[index]['response']['retainedRecords']['P']['exactDocument']['contracts']['checkpoint']['entries']['owner']
    def check(self):P.check_channel_checkpoint_history(self.record,self.rule)
    def test_unchanged_exact_direct_checkpoint_passes(self):self.check()
    def test_missing_checkpoint_rejects(self):
        self.rows[1]['response']['retainedRecords']['P']['exactDocument']={}
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_REQUIRED'):self.check()
    def test_rewound_checkpoint_rejects(self):
        self.checkpoint(1)['subject']['timestamp']['value']=10
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_DIRECT_TIMESTAMP'):self.check()
    def test_identically_wrong_entry_before_and_after_rejects(self):
        for i in [0,1]:self.checkpoint(i)['subject']['entryBlueId']['value']='entry-10'
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_EXACT_DIRECT_ENTRY'):self.check()
    def test_changed_domain_rejects(self):
        self.checkpoint(1)['domain']['blueId']='other-domain'
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_CHANGED_BY_HISTORY'):self.check()
    def test_duplicate_completion_rejects(self):
        self.record['transcript'].append(copy.deepcopy(self.rows[1]))
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_COMPLETION'):self.check()
    def test_incomplete_history_step_rejects(self):
        self.rows[1]['completed']=False
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_COMPLETION'):self.check()
    def test_foreign_root_rejects(self):
        self.rows[1]['request']['root']='A'
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_ROOT'):self.check()
    def add_selection(self):
        self.rule['selectedSource']=dict(source='A',capture='sourceAt5',epoch=1,path='child',afterDocumentEquals={'counter.value':1})
        self.record['documentIds']['A']='source'
        receipt=dict(documentId={'value':'source'},afterBlueId='saved-5',afterDocument={'counter':{'value':1}})
        self.record['transcript'].append(dict(completed=True,request=dict(op='captureEpoch',capture='sourceAt5',target='A',epoch=1),response=receipt))
        before=self.rows[0]['response']['retainedRecords'];before['A']={'receipts':[copy.deepcopy(receipt)]}
        before['P']['exactDocument']['child']={'blueId':'saved-5'}
        receipt['blueId']='saved-5'
        return receipt,before
    def test_exact_saved_selection_passes(self):self.add_selection();self.check()
    def test_current_head_substitution_rejects(self):
        _,before=self.add_selection();before['P']['exactDocument']['child']={'blueId':'current-10'}
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_SELECTED_EXACT_REFERENCE'):self.check()
    def test_unretained_selection_rejects(self):
        _,before=self.add_selection();before['A']['receipts']=[]
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_SELECTED_RECEIPT'):self.check()
    def test_wrong_saved_value_rejects(self):
        receipt,before=self.add_selection();receipt['afterDocument']['counter']['value']=2
        before['A']['receipts']=[{k:copy.deepcopy(v) for k,v in receipt.items() if k!='blueId'}]
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_SELECTED_VALUE'):self.check()
    def test_wrong_capture_alias_rejects(self):
        receipt,_=self.add_selection();receipt['blueId']='other'
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_SELECTED_CAPTURE'):self.check()
    def test_wrong_selected_epoch_rejects(self):
        self.add_selection();self.record['transcript'][-1]['request']['epoch']=2
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_SELECTED_EPOCH'):self.check()

class RecreatedOccurrenceTests(unittest.TestCase):
    def setUp(self):
        self.ids={'P':'parent','S':'source'}
        self.rule={'owner':'P','source':'S','path':'/orders/same','first':'first','removed':'removed','readded':'new','later':'later'}
        def row(generation,active):return {'sourcePath':'/orders/same','sourceDocumentId':{'value':'parent'},
            'targetDocumentId':{'value':'source'},'activationGeneration':generation,'active':active,
            'occurrenceIdentity':'occurrence-'+str(generation),'expectedTargetBlueId':'saved-source'}
        self.phases={}
        for phase,rows in [('first',[row(1,True)]),('removed',[row(2,False)]),('new',[row(2,False)]),('later',[row(2,True)])]:
            self.phases[phase]={'selectedAfter':{'P':{'documentId':'parent','occurrences':rows},
                'S':{'documentId':'source','blueId':'current-source'}}}
        self.rows('removed')[0]['expectedTargetBlueId']='current-source'
        self.rows('new')[0]['pendingHistoricalEpoch']=0
        self.output={'applications':[{'work':{'targetOccurrenceIdentity':'occurrence-2'}}]}
    def rows(self,phase):return self.phases[phase]['selectedAfter']['P']['occurrences']
    def check(self):P.check_recreated_occurrence(self.output,self.phases,self.rule,self.ids)
    def test_new_generation_and_bound_history_pass(self):self.check()
    def test_missing_retirement_reservation_rejects(self):
        self.rows('removed').clear()
        with self.assertRaisesRegex(ValueError,'RECREATED_OCCURRENCE_INVENTORY'):self.check()
    def test_retirement_keeps_old_active_row_rejects(self):
        self.rows('removed')[0]=copy.deepcopy(self.rows('first')[0])
        with self.assertRaisesRegex(ValueError,'RECREATED_RETIRED_RESERVATION'):self.check()
    def test_readd_allocates_an_extra_generation_rejects(self):
        self.rows('new')[0]['activationGeneration']=3
        with self.assertRaisesRegex(ValueError,'RECREATED_GENERATION'):self.check()
    def test_retired_expected_target_is_not_current_rejects(self):
        self.rows('removed')[0]['expectedTargetBlueId']='saved-source'
        with self.assertRaisesRegex(ValueError,'RECREATED_RETIRED_TARGET'):self.check()
    def test_readd_inherits_retired_cursor_rejects(self):
        self.rows('new')[0]['pendingHistoricalEpoch']=1
        with self.assertRaisesRegex(ValueError,'RECREATED_INITIALIZED_CURSOR'):self.check()
    def test_reused_generation_rejects(self):
        self.rows('new')[0]['activationGeneration']=1
        with self.assertRaisesRegex(ValueError,'RECREATED_GENERATION'):self.check()
    def test_substituted_current_source_rejects(self):
        self.rows('new')[0]['expectedTargetBlueId']='current-source'
        with self.assertRaisesRegex(ValueError,'RECREATED_SAVED_SOURCE'):self.check()
    def test_history_applied_to_retired_occurrence_rejects(self):
        self.output['applications'][0]['work']['targetOccurrenceIdentity']='occurrence-1'
        with self.assertRaisesRegex(ValueError,'RECREATED_HISTORY_BINDING'):self.check()
    def test_later_delivery_uses_old_occurrence_rejects(self):
        self.rows('later')[0]['occurrenceIdentity']='occurrence-1'
        with self.assertRaisesRegex(ValueError,'RECREATED_LATER_BINDING'):self.check()

class RetainedRollbackRetryTests(unittest.TestCase):
    def setUp(self):
        retained={'A':{'blueId':'A0','receipts':['A0'],'checkpoint':20},'B':{'blueId':'B0','receipts':['B0']}}
        self.output=dict(status='GAS_LIMIT_EXCEEDED',entryOwners=['A','B'],retainedBefore=copy.deepcopy(retained),
            retainedAfter=copy.deepcopy(retained),rollbackToInput=True,commitCompanion=None,checkpointWrites=[],
            publicationIdentity='terminal',gas={'charges':[1,2],'rejected':{'sequence':2},'total':3})
        self.response=dict(before=copy.deepcopy(retained),after=copy.deepcopy(retained),originalPublication='terminal',
            returnedPublication='terminal',originalGas=copy.deepcopy(self.output['gas']),returnedGas=copy.deepcopy(self.output['gas']),
            outcome=dict(disposition='GAS_LIMIT_EXCEEDED',closures=[{'closureId':'terminal'}]))
        self.row=dict(completed=True,request={'op':'retry'},response=self.response)
        self.record={'transcript':[self.row]}
    def check(self):P.check_retained_rollback_retry(self.record,self.output)
    def test_complete_atomic_failure_and_retained_retry_pass(self):self.check()
    def test_one_owner_is_missing_rejects(self):
        self.output['entryOwners']=['A']
        with self.assertRaisesRegex(ValueError,'CYCLE_FROZEN_OWNERS'):self.check()
    def test_checkpoint_write_on_failure_rejects(self):
        self.output['checkpointWrites']=[{'forged':1}]
        with self.assertRaisesRegex(ValueError,'CYCLE_ROLLBACK_EVIDENCE'):self.check()
    def test_failure_receipt_mutation_rejects(self):
        self.output['retainedAfter']['B']['receipts'].append('B1')
        with self.assertRaisesRegex(ValueError,'CYCLE_FAILED_RETAINED_MUTATION'):self.check()
    def test_failure_companion_rejects(self):
        self.output['commitCompanion']={'identity':'forged'}
        with self.assertRaisesRegex(ValueError,'CYCLE_ROLLBACK_EVIDENCE'):self.check()
    def test_unexecuted_retry_rejects(self):
        self.row['completed']=False
        with self.assertRaisesRegex(ValueError,'CYCLE_RETRY_REQUIRED'):self.check()
    def test_duplicate_retry_completion_rejects(self):
        self.record['transcript'].append(copy.deepcopy(self.row))
        with self.assertRaisesRegex(ValueError,'CYCLE_RETRY_REQUIRED'):self.check()
    def test_retry_new_publication_rejects(self):
        self.response['returnedPublication']='another'
        with self.assertRaisesRegex(ValueError,'CYCLE_RETRY_PUBLICATION'):self.check()
    def test_retry_checkpoint_change_rejects(self):
        self.response['after']['A']['checkpoint']=21
        with self.assertRaisesRegex(ValueError,'CYCLE_RETRY_MUTATION'):self.check()
    def test_retry_reordered_trace_rejects(self):
        self.response['returnedGas']['charges'].reverse()
        with self.assertRaisesRegex(ValueError,'CYCLE_RETRY_TRACE'):self.check()
    def test_retry_different_result_rejects(self):
        self.response['outcome']['disposition']='APPLIED'
        with self.assertRaisesRegex(ValueError,'CYCLE_RETRY_OUTCOME'):self.check()

class DistinctChannelProgressTests(unittest.TestCase):
    def setUp(self):
        self.rule={'owner':'P','channels':['first','second'],'phases':[
            {'capture':'one','entries':{'second':'E1'}},
            {'capture':'two','entries':{'second':'E2'}},
            {'capture':'subject','entries':{'first':'E3','second':'E2'}}]}
        self.record={'documentIds':{'P':'parent'},'transcript':[]};self.states={}
        for n,channel in [(3,'first'),(1,'second'),(2,'second')]:
            self.record['transcript'].append({'completed':True,'request':{'op':'append','capture':f'E{n}',
                'target':'P','channel':channel,'timestampUs':str(n)},'response':{'entryBlueId':f'exact-{n}'}})
        for phase in self.rule['phases']:
            entries={channel:{'domain':{'blueId':f'domain-{channel}'},'subject':{
                'entryBlueId':{'value':'exact-'+entry[-1]},'timestamp':{'value':int(entry[-1])}}}
                for channel,entry in phase['entries'].items()}
            self.states[phase['capture']]=entries
            self.record['transcript'].append({'completed':True,'request':{'op':'processNext',
                'root':'P','capture':phase['capture']},'response':{'retainedRecords':{'P':{
                    'documentId':'parent','exactDocument':{'contracts':{'checkpoint':{'entries':entries}}}}}}})
    def check(self):P.check_distinct_channel_progress(self.record,self.rule)
    def test_independent_checkpoints_pass(self):self.check()
    def test_document_maximum_cannot_replace_two_entries(self):
        self.states['subject']['second']['subject']=copy.deepcopy(self.states['subject']['first']['subject'])
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_ENTRY'):self.check()
    def test_unhandled_channel_has_no_successful_checkpoint(self):
        self.states['one']['first']=copy.deepcopy(self.states['one']['second'])
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_INVENTORY'):self.check()
    def test_missing_checkpoint_rejects(self):
        self.states['subject'].pop('second')
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_INVENTORY'):self.check()
    def test_wrong_checkpoint_timestamp_rejects(self):
        self.states['two']['second']['subject']['timestamp']['value']=3
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_TIME'):self.check()
    def test_missing_domain_rejects(self):
        self.states['one']['second'].pop('domain')
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_DOMAIN'):self.check()
    def test_changed_domain_rejects(self):
        self.states['two']['second']['domain']['blueId']='changed'
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_DOMAIN_CHANGED'):self.check()
    def test_shared_domain_rejects(self):
        self.states['subject']['first']['domain']['blueId']='domain-second'
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_DOMAIN_COLLAPSE'):self.check()
    def test_changed_unrelated_checkpoint_bytes_rejects(self):
        self.states['subject']['second']['extra']='unaccounted-change'
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_UNRELATED_ADVANCE'):self.check()
    def test_wrong_target_channel_rejects(self):
        self.record['transcript'][0]['request']['channel']='second'
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_INPUT'):self.check()
    def test_duplicate_completion_rejects(self):
        self.record['transcript'].append(copy.deepcopy(self.record['transcript'][-1]))
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_COMPLETION'):self.check()
    def test_unexecuted_phase_rejects(self):
        self.record['transcript'][-1]['completed']=False
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_COMPLETION'):self.check()
    def test_wrong_root_rejects(self):
        self.record['transcript'][-1]['request']['root']='another'
        with self.assertRaisesRegex(ValueError,'CHANNEL_PROGRESS_ROOT'):self.check()

class InitialHistoryTests(unittest.TestCase):
    """Epoch zero keeps both host absence and the exact authored Contracts predecessor."""
    def setUp(self):
        base=MultipleHistoryTests();base.setUp();self.o=base.o;self.ids=base.ids;self.contract=base.contract
        self.o['applications']=self.o['applications'][:1];self.contract['requiredApplicationCount']=1
        self.contract['occurrenceSuffixes']['/left']['positions']=[0]
        self.contract['applicationOrder']=[['A',60]]
        h=self.o['applications'][0];h['from']=-1;h['to']=0;h['exactCause']['fromEpoch']=-1;h['exactCause']['toEpoch']=0
        h['work']['sourceEpoch']=0;h['retainedSource'].update(epoch=0,beforeBlueId=None,kind='INITIALIZATION',sourceEntry=None)
        self.o['sourceBefore']['A']['receipts']=[copy.deepcopy(h['retainedSource'])]
        self.references={'A':h['exactCause']['beforeBlueId']}
    def check(self):P.check_multiple_history(self.o,self.contract,T.WEIGHTS,self.ids,self.references)
    def test_complete_initialization_passes(self):self.check()
    def test_missing_authored_reference_rejects(self):
        self.references={}
        with self.assertRaisesRegex(ValueError,'MULTI_INITIAL_REFERENCE_REQUIRED'):self.check()
    def test_wrong_authored_reference_rejects(self):
        self.references['A']='current-state-substitution'
        with self.assertRaisesRegex(ValueError,'MULTI_EXACT_INITIAL_REFERENCE'):self.check()
    def change_retained(self,key,value):
        self.o['applications'][0]['retainedSource'][key]=value
        self.o['sourceBefore']['A']['receipts'][0][key]=value
    def test_invented_prior_managed_state_rejects(self):
        self.change_retained('beforeBlueId','invented')
        with self.assertRaisesRegex(ValueError,'MULTI_INITIAL_RECEIPT'):self.check()
    def test_non_initialization_kind_rejects(self):
        self.change_retained('kind','PROCESSING')
        with self.assertRaisesRegex(ValueError,'MULTI_INITIAL_RECEIPT'):self.check()
    def test_forged_external_source_entry_rejects(self):
        self.change_retained('sourceEntry',{'blueId':'invented'})
        with self.assertRaisesRegex(ValueError,'MULTI_INITIAL_RECEIPT'):self.check()
    def test_changed_contracts_predecessor_rejects(self):
        self.o['applications'][0]['sourceReceipt']['beforeBlueId']='wrong'
        with self.assertRaisesRegex(ValueError,'MULTI_EXACT_INITIAL_REFERENCE'):self.check()
    def test_changed_initial_successor_rejects(self):
        self.o['applications'][0]['sourceReceipt']['afterBlueId']='wrong'
        with self.assertRaisesRegex(ValueError,'MULTI_EXACT_SUCCESSOR'):self.check()

class LaggingReadinessTests(unittest.TestCase):
    """Synthetic verifier negatives; actual SDK execution is recorded separately."""
    def setUp(self):
        self.rule=T.fixture(34)['expected']['contract']['laggingObserver']
        self.record={'documentIds':{'P':'parent','S':'source'},'transcript':[]}
        def state(target,epoch,time,next_input):
            return {'documentId':self.record['documentIds'][target],'blueId':target+str(epoch),
                    'epoch':epoch,'exactDocument':{'counter':epoch},'receipts':[str(n) for n in range(epoch+1)],
                    'readyStatus':'READY','readyThrough':{'components':[time,'timeline','entry'+str(time)]},'nextLiveInput':next_input}
        def read(capture,target,value):
            self.record['transcript'].append({'completed':True,'request':{'op':'read','target':target,'recordReadiness':True,'capture':capture},'response':value})
        read('oldP','P',state('P',0,0,None))
        for time in [100,150]:self.record['transcript'].append({'completed':True,'request':{'op':'append','capture':'E'+str(time)},'response':{'entryBlueId':'entry'+str(time)}})
        for name in ['lag1P','lag2P','restartP']:read(name,'P',state('P',0,0,'entry100'))
        read('source1','S',state('S',1,100,'entry150'));read('source2','S',state('S',2,150,None))
        read('after1P','P',state('P',1,100,'entry150'));read('finalP','P',state('P',2,150,None))
        read('finalS','S',state('S',2,150,None))
    def value(self,name):return next(r['response'] for r in self.record['transcript'] if r['request'].get('capture')==name)
    def check(self):P.check_lagging_observer(self.record,self.rule)
    def test_exact_old_ready_then_ordered_progress_passes(self):self.check()
    def test_false_unapplied_frontier_rejects(self):
        self.value('lag2P')['readyThrough']=copy.deepcopy(self.value('source2')['readyThrough'])
        with self.assertRaisesRegex(ValueError,'READY_UNAPPLIED_FRONTIER'):self.check()
    def test_missing_pending_input_rejects(self):
        self.value('restartP')['nextLiveInput']=None
        with self.assertRaisesRegex(ValueError,'READY_MISSING_PENDING'):self.check()
    def test_changed_old_exact_bytes_rejects(self):
        self.value('lag1P')['exactDocument']['counter']=1
        with self.assertRaisesRegex(ValueError,'READY_OLD_VIEW_CHANGED'):self.check()
    def test_skipping_first_observer_entry_rejects(self):
        self.value('after1P')['nextLiveInput']=None
        with self.assertRaisesRegex(ValueError,'READY_FIRST_PROGRESS'):self.check()
    def test_final_lag_rejects(self):
        self.value('finalP')['readyThrough']=copy.deepcopy(self.value('source1')['readyThrough'])
        with self.assertRaisesRegex(ValueError,'READY_FINAL_PROGRESS'):self.check()
    def test_source_receipt_rewrite_rejects(self):
        self.value('finalS')['receipts'][0]='forged'
        with self.assertRaisesRegex(ValueError,'READY_SOURCE_REWRITTEN'):self.check()
    def test_wrong_document_rejects(self):
        self.value('lag1P')['documentId']='source'
        with self.assertRaisesRegex(ValueError,'READY_DOCUMENT_BINDING'):self.check()
    def test_source_frontier_uses_wrong_entry_rejects(self):
        self.value('source1')['readyThrough']['components'][2]='wrong'
        with self.assertRaisesRegex(ValueError,'READY_SOURCE_FRONTIER'):self.check()

class IncomingFanoutTests(unittest.TestCase):
    """Synthetic structural negatives; actual 5k execution is a separate gate."""
    def record(self, count):
        names = [f'P{i:04d}' for i in range(count)]
        ids = {'S':'source', **{name:'initial-'+name for name in names}}
        entry = {'actualEntry':{'blueId':'entry'}, 'entryBlueId':'entry'}
        rows = [{'request':{'op':'start','alias':'S'}, 'response':{'initializedBlueId':'source-zero'}}]
        before = {'sourceRecords':{'S':{'receipts':['zero']}}, 'journal':[], 'observers':[],
                  'sourceSelectedSnapshot':{'managedDocuments':[{'documentId':{'value':'source'}}],
                    'occurrences':[], 'components':[{'orderedMemberDocumentIds':[{'value':'source'}]}]}}
        for name in names:
            edge = {'active':True, 'sourcePath':'/child', 'sourceDocumentId':{'value':ids[name]},
                    'targetDocumentId':{'value':'source'}, 'expectedTargetBlueId':'source-zero'}
            receipt = {'receiptIdentity':'receipt-'+name}
            old = {'alias':name, 'documentId':ids[name], 'epoch':0, 'blueId':'zero-'+name,
                   'exactDocument':{'child':{'blueId':'source-zero'}}, 'historyBasis':{'identity':'basis-'+name},
                   'receiptIdentities':[receipt['receiptIdentity']], 'readyThrough':None,
                   'occurrences':[edge], 'nextLiveInput':None,
                   'readiness':{'ready':True, 'committedBlueId':'zero-'+name, 'committedEpoch':0,
                                'readyBlueId':'zero-'+name, 'readyEpoch':0}}
            before['observers'].append(old)
            rows.append({'request':{'op':'start', 'alias':name, 'admission':'FULL_HISTORY',
                'source':'examples/iteration2/parent.yaml', 'bindings':{'/name':'RCP2 Fanout '+name,
                '/contracts/owner/timeline/timelineId':'rcp2/fanout/'+name,
                '/child':{'$capture':'S.initializedBlueId'}}}, 'response':{'documentId':ids[name],
                'initialBlueId':ids[name], 'initializedBlueId':old['blueId'],
                'initialExact':{'child':{'blueId':'source-zero'}}, 'epoch0Receipt':receipt,
                'retained':{**old, 'receipts':[receipt]}}})
        after = copy.deepcopy(before)
        after['journal'] = [entry['actualEntry']]
        after['sourceRecords']['S']['receipts'].append('one')
        for observer in after['observers']:
            observer['nextLiveInput']='entry'
            observer['readiness']['ready']=False
        for op in ('observeFanout','append','processNext','observeFanout','restart','observeFanout'):
            rows.append({'request':{'op':op}, 'response':entry if op=='append' else {}})
        for row in rows:row['completed']=True
        return {'documentIds':ids, 'transcript':rows,
                'phaseRecords':{'observersBefore':before,'observersAfter':after,'observersRestart':copy.deepcopy(after)},
                'output':{'before':{'S':{}},'after':{'S':{}},'selectedBefore':{'S':{}},'selectedAfter':{'S':{}},
                          'sourceBefore':{},'sourceAfter':{},'computedReceipts':[{'documentId':{'value':'source'}}]},
                'restart':{'before':after['sourceRecords'],'after':copy.deepcopy(after['sourceRecords']),
                           'beforeCommandCount':1,'afterCommandCount':1}}
    def check(self, record, variant='5000-observers'):
        rule=T.fixture(32)['expected']['contract']['incomingFanout']
        P.check_incoming_fanout(record,rule,variant,P.require,P.exact_equal)
    def test_both_complete_inventories_and_empty_external_sources_pass(self):
        self.check(self.record(0),'standalone')
        self.check(self.record(5000))
    def test_unrelated_external_source_cannot_be_hidden(self):
        record=self.record(5000);record['output']['sourceBefore']={'other':{}}
        with self.assertRaisesRegex(ValueError,'FANOUT_COMPACT_SCOPE'):self.check(record)
    def test_last_observer_inventory_receipt_state_and_pending_work_reject(self):
        for field,value,reason in [('epoch',1,'OBSERVER_ADVANCED'),('exactDocument',{},'START_BINDING'),
                                  ('receiptIdentities',[],'INITIAL_RECEIPT'),('nextLiveInput','bad','REQUIRED_WORK_LOST')]:
            record=self.record(5000);record['phaseRecords']['observersBefore']['observers'][-1][field]=value
            with self.subTest(field=field),self.assertRaisesRegex(ValueError,reason):self.check(record)
    def test_missing_observer_is_not_smaller_successful_workload(self):
        record=self.record(5000);record['phaseRecords']['observersBefore']['observers'].pop()
        with self.assertRaisesRegex(ValueError,'OBSERVER_INVENTORY'):self.check(record)
    def test_incoming_observer_cannot_be_selected_source_member(self):
        record=self.record(5000)
        record['phaseRecords']['observersBefore']['sourceSelectedSnapshot']['managedDocuments'].append({'documentId':{'value':'initial-P4999'}})
        with self.assertRaisesRegex(ValueError,'SOURCE_MEMBERSHIP'):self.check(record)
    def test_source_history_prefix_cannot_be_rewritten(self):
        record=self.record(5000);record['phaseRecords']['observersBefore']['sourceRecords']['S']['receipts'][0]='forged'
        with self.assertRaisesRegex(ValueError,'SOURCE_HISTORY_PREFIX'):self.check(record)

if __name__=='__main__':unittest.main(verbosity=2)
