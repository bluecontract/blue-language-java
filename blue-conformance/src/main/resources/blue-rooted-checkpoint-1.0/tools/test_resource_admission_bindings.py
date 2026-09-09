"""Synthetic constructor/negative tests; no production fixture execution claimed."""
import copy
import json
import unittest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'conformance/rooted-processing'))
import resource_admission_bindings as H
D=H.digest
C='8uYGrMkDjGz9KCx2NYnt3Tese9FAY2Gv8BdeDCHspkc3';M='ET9aYAUHuu1nyLtKefzba14gm2kGpAkYe5Sy5GbZcTZN'
CE={'name':'synthetic child','peer':{'blueId':M}};ME={'state':{'value':'synthetic resource'}}
E={k:'sha256:'+str(i%10)*64 for i,k in enumerate(H.ENV_IDENTITIES)}
def req(ok,code):
    if not ok:raise ValueError(code)
def inp(expanded):
    ids=sorted([C,M] if expanded else [C]);docs=[];comps=[]
    for id in ids:
        docs.append(dict(documentId={'value':id},blueId=id,document=copy.deepcopy(CE if id==C else ME),initialized=False,
                         terminated=False,publicRoot=id==C,epoch=0,componentGeneration=1))
        ci=D('blue-contracts-component/1.0',{'kind':'ACYCLIC','generation':1,'members':[id]})
        cs=D('blue-contracts-component-state/1.0',{'componentIdentity':ci,'memberStates':[{'documentId':id,'blueId':id}],
             'masterBlueId':None,'cyclicProofIdentity':None})
        comps.append(dict(completeCyclicProof=None,componentGeneration=1,componentIdentity=ci,componentStateIdentity=cs,
            cyclicProofIdentity=None,kind='ACYCLIC',masterBlueId=None,orderedMemberBlueIds=[id],orderedMemberDocumentIds=[{'value':id}]))
    occ=[]
    if expanded:
        ev=dict(sourceDocumentId=C,sourcePath='/peer',activationGeneration=1,targetDocumentId=M,bindingPolicyIdentity=E['managedBindingPolicyIdentity'])
        occ.append(dict(ev,sourceDocumentId={'value':C},targetDocumentId={'value':M},active=True,expectedTargetBlueId=M,
            occurrenceIdentity=D('blue-contracts-managed-occurrence-lineage/1.0',ev),
            bindingIdentity=D('blue-contracts-managed-occurrence/1.0',dict(ev,expectedTargetBlueId=M)),
            pendingHistoricalEpoch=None,pendingRepresentationCursor=None,sourceAddress={'activationGeneration':1,'isRoot':False,'path':'/peer'}))
    bs=D('blue-contracts-occurrence-binding-set/1.0',[{k:o[k] for k in ('occurrenceIdentity','bindingIdentity','active','pendingHistoricalEpoch')} for o in occ])
    dv=[{k:(v['value'] if k=='documentId' else v) for k,v in d.items() if k!='document'} for d in docs]
    ci=D('blue-contracts-affected-closure/1.0',dict(graphGeneration=1,documents=dv,occurrenceBindingSetIdentity=bs,
         components=sorted(c['componentStateIdentity'] for c in comps),publicRootDocumentIds=[C]))
    snapshot=dict(closureIdentity=ci,components=comps,graphGeneration=1,managedDocuments=docs,occurrenceBindingSetIdentity=bs,
                  occurrences=occ,publicRootDocumentIds=[{'value':C}])
    cause=dict(admissionKind='TOP_LEVEL_ADMISSION',label='contracts10-static-process-embedded-admission',
               triggeringEventBlueId=None,parentTransitionIdentity=None,
               policyIdentity=D('blue-contracts-admission-policy/1.0',{'label':'contracts-top-level-admission-v1'}))
    cause['causeIdentity']=D('blue-contracts-admission-cause/1.0',cause);cause['kind']='ADMISSION'
    policy=dict(label='release-default',localLimits={},sharedLimit=100000)
    policy['identity']=D('blue-contracts-execution-policy/1.0',dict(policy,localLimits=[]))
    direct=D('blue-contracts-direct-delivery-snapshot/1.0',[])
    iv=dict(E,operation='admit-closure',causeIdentity=cause['causeIdentity'],admissionCandidateIdentity=None,inputGraphGeneration=1,
            inputClosureIdentity=ci,documents=dv,directDeliverySnapshotIdentity=direct,occurrenceBindingSetIdentity=bs,gasPolicyIdentity=policy['identity'])
    return dict(admissionCandidate=None,admissionCandidateIdentity=None,cause=cause,directDeliveries=[],directDeliverySnapshotIdentity=direct,
            environment=copy.deepcopy(E),executionPolicy=policy,invocationIdentity=D('blue-contracts-invocation/1.0',iv),operation='ADMIT_CLOSURE',snapshot=snapshot)
def fixture():
    a,b=inp(False),inp(True)
    return dict(originalInput=a,completedInput=b,selectionPlan=None,publicationIdentity=H.publication_identity(a),
                inputPublicationIdentity=H.publication_identity(a),result={'invocationIdentity':b['invocationIdentity']},
                implementationEvidence={'invocationIdentity':b['invocationIdentity']}),{'workIdentity':a['invocationIdentity']}
def check(a,s):return H.check_admission_inputs(a,s,C,M,CE,ME,E,req,lambda a,b:a==b)
class ProofTests(unittest.TestCase):
    def test_actual_packaged_admission_inputs(self):
        f=json.loads((Path(__file__).parent/'test-data/resource-admission-real.json').read_text())
        def verify():return H.check_admission_inputs(f['admission'],f['selection'],f['child'],f['missing'],
                f['childExact'],f['missingExact'],f['environment'],req,lambda a,b:a==b)
        self.assertIs(verify(),f['admission']['completedInput'])
        f['admission']['originalInput']['executionPolicy']['identity']='sha256:'+'0'*64
        with self.assertRaisesRegex(ValueError,'RESOURCE_ADMISSION_EXECUTION_POLICY'):verify()
    def test_typed_policy_map_uses_canonical_limit_rows(self):
        a,s=fixture();policy=a['originalInput']['executionPolicy']
        self.assertEqual('sha256:06be3c4e41fbbc52cf8289ff95cc52264b2f5093d75c590501173942490c1685',policy['identity'])
        policy['identity']=D('blue-contracts-execution-policy/1.0',{k:v for k,v in policy.items() if k!='identity'})
        with self.assertRaisesRegex(ValueError,'RESOURCE_ADMISSION_EXECUTION_POLICY'):check(a,s)
    def test_positive_exact_objects(self):
        a,s=fixture();self.assertIs(check(a,s),a['completedInput'])
    def test_closed_negative_operands(self):
        cases=[('originalInput/invocationIdentity','sha256:'+'0'*64),('completedInput/invocationIdentity','sha256:'+'0'*64),
          ('result/invocationIdentity','sha256:'+'0'*64),('implementationEvidence/invocationIdentity','sha256:'+'0'*64),
          ('publicationIdentity','sha256:'+'0'*64),('inputPublicationIdentity','sha256:'+'0'*64),('selectionPlan',{}),
          ('completedInput/cause/label','forged'),('completedInput/cause/admissionKind','EMBEDDED_ACTIVATION'),
          ('completedInput/environment/gasManifestIdentity','sha256:'+'a'*64),('completedInput/executionPolicy/sharedLimit',100001),
          ('completedInput/admissionCandidate',{}),('completedInput/directDeliveries',[{}]),
          ('completedInput/snapshot/closureIdentity','sha256:'+'0'*64),('completedInput/snapshot/occurrenceBindingSetIdentity','sha256:'+'0'*64),
          ('completedInput/snapshot/graphGeneration',0),('completedInput/snapshot/publicRootDocumentIds',[])]
        for path,value in cases:
            with self.subTest(path=path):
                a,s=fixture();parts=path.split('/');target=a
                for p in parts[:-1]:target=target[p]
                target[parts[-1]]=value
                with self.assertRaises(ValueError):check(a,s)
    def test_missing_or_changed_source_evidence(self):
        for mode in ['child-body','resource-body','missing-resource','duplicate-resource','edge-target','edge-active','edge-cursor','component','unknown-field','selected-work']:
            with self.subTest(mode=mode):
                a,s=fixture();i=a['completedInput'];snap=i['snapshot']
                if mode=='child-body':snap['managedDocuments'][0]['document']['name']='forged'
                elif mode=='resource-body':snap['managedDocuments'][1]['document']['state']={'value':'forged'}
                elif mode=='missing-resource':snap['managedDocuments'].pop()
                elif mode=='duplicate-resource':snap['managedDocuments'].append(copy.deepcopy(snap['managedDocuments'][-1]))
                elif mode=='edge-target':snap['occurrences'][0]['targetDocumentId']={'value':C}
                elif mode=='edge-active':snap['occurrences'][0]['active']=False
                elif mode=='edge-cursor':snap['occurrences'][0]['pendingRepresentationCursor']={}
                elif mode=='component':snap['components'][0]['componentIdentity']='sha256:'+'0'*64
                elif mode=='unknown-field':i['unreviewed']=True
                else:s['workIdentity']='sha256:'+'0'*64
                with self.assertRaises(ValueError):check(a,s)
    def test_canonical_subset(self):
        for v in [1.0,9007199254740992,{'nonAsciié':0},'\ud800']:
            with self.assertRaises((ValueError,UnicodeEncodeError)):D('domain',v)
if __name__=='__main__':unittest.main(verbosity=2)
