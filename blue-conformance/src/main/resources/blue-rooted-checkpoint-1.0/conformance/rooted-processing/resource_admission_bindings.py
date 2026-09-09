"""Closed RUN027 admission linkage; never treats observer hashes alone as authority."""
import hashlib
import json
import struct

ENV_IDENTITIES = (
    'runtimeRegistryIdentity', 'cyclicFinalizerIdentity', 'cyclicProofVerifierIdentity',
    'blueLanguageSpecificationIdentity', 'contractsSpecificationIdentity',
    'managedDocumentIdentityPolicyIdentity', 'managedBindingPolicyIdentity',
    'exactNodeProviderDomainIdentity', 'externalOrderPolicyIdentity',
    'gasManifestIdentity', 'portableLimitPolicyIdentity',
)
INPUT_FIELDS = ('admissionCandidate','admissionCandidateIdentity','cause','directDeliveries',
                'directDeliverySnapshotIdentity','environment','executionPolicy','invocationIdentity','operation','snapshot')
DOC_FIELDS = ('blueId','componentGeneration','document','documentId','epoch','initialized','publicRoot','terminated')
COMP_FIELDS = ('completeCyclicProof','componentGeneration','componentIdentity','componentStateIdentity',
               'cyclicProofIdentity','kind','masterBlueId','orderedMemberBlueIds','orderedMemberDocumentIds')
OCC_FIELDS = ('activationGeneration','active','bindingIdentity','bindingPolicyIdentity','expectedTargetBlueId',
              'occurrenceIdentity','pendingHistoricalEpoch','pendingRepresentationCursor','sourceAddress',
              'sourceDocumentId','sourcePath','targetDocumentId')


def digest(domain, value):
    # All constructor keys are closed ASCII; values are scalar strings, booleans,
    # nulls and verified safe integers. Therefore sort_keys is JCS-equivalent here.
    def scalar(x):
        if x is None or type(x) is bool: return
        if type(x) is int:
            if abs(x) > 9007199254740991: raise ValueError('RESOURCE_UNSAFE_IDENTITY_INTEGER')
        elif isinstance(x, str):
            x.encode('utf-8')
        elif isinstance(x, list):
            for v in x: scalar(v)
        elif isinstance(x, dict):
            for k,v in x.items():
                if not isinstance(k,str) or not k.isascii(): raise ValueError('RESOURCE_IDENTITY_KEY')
                scalar(v)
        else: raise ValueError('RESOURCE_IDENTITY_VALUE')
    scalar(value)
    encoded=json.dumps({'domain':domain,'value':value},sort_keys=True,ensure_ascii=False,
                       separators=(',',':'),allow_nan=False).encode('utf-8')
    return 'sha256:'+hashlib.sha256(encoded).hexdigest()


def publication_identity(original):
    domain='coordination-contracts-closure-admission-v1';h=hashlib.sha256()
    fields=[(0,domain),(1,original['invocationIdentity']),(2,original['snapshot']['closureIdentity']),
            (3,'FULL_HISTORY'),(4,'3'),(5,'-9007199254740991'),
            (6,'contracts-full-history-admission'),(6,original['invocationIdentity'])]
    for kind,value in fields:
        data=value.encode('utf-8');h.update(bytes([kind])+struct.pack('>I',len(data))+data)
    return domain+':sha256:'+h.hexdigest()


def check_admission_inputs(admission, selection, child, missing, child_exact, missing_exact,
                           expected_environment, require, exact_equal):
    """Return authentic completed input; caller still verifies result, gas and SQL.

    expected_environment comes from the actual successful original LIVE input,
    independently bound to its saved original entry and result. Exact child and
    missing operands come from the frozen request and verified uploaded source.
    """
    def closed(value, fields, code):
        require(isinstance(value,dict) and set(value)==set(fields),code)
    def did(value):
        closed(value,('value',),'RESOURCE_ADMISSION_DOCUMENT_ID');return value['value']
    def documents(snapshot):
        docs=snapshot['managedDocuments'];ids=[did(v['documentId']) for v in docs]
        require(ids==sorted(ids) and len(set(ids))==len(ids),'RESOURCE_ADMISSION_DOCUMENT_ORDER')
        result={}
        for v in docs:
            closed(v,DOC_FIELDS,'RESOURCE_ADMISSION_DOCUMENT_FIELDS')
            require(type(v['epoch']) is int and v['epoch']==0 and type(v['componentGeneration']) is int
                    and v['componentGeneration']==1 and v['initialized'] is False and v['terminated'] is False
                    and type(v['publicRoot']) is bool,'RESOURCE_ADMISSION_AUTHORED_FLAGS')
            result[did(v['documentId'])]={k:(did(v[k]) if k=='documentId' else v[k])
                                          for k in DOC_FIELDS if k!='document'}
        return result
    def snapshot(value):
        closed(value,('closureIdentity','components','graphGeneration','managedDocuments',
                      'occurrenceBindingSetIdentity','occurrences','publicRootDocumentIds'),'RESOURCE_ADMISSION_SNAPSHOT_FIELDS')
        docs=documents(value);components=value['components'];members=[];states=[]
        require(type(value['graphGeneration']) is int and value['graphGeneration']==1,'RESOURCE_ADMISSION_ORIGINAL_GRAPH')
        for c in components:
            closed(c,COMP_FIELDS,'RESOURCE_ADMISSION_COMPONENT_FIELDS')
            ids=[did(v) for v in c['orderedMemberDocumentIds']];members+=ids
            require(c['kind']=='ACYCLIC' and len(ids)==1 and ids[0] in docs and c['componentGeneration']==1
                    and c['orderedMemberBlueIds']==[docs[ids[0]]['blueId']]
                    and c['masterBlueId'] is None and c['cyclicProofIdentity'] is None
                    and c['completeCyclicProof'] is None,'RESOURCE_ADMISSION_ACYCLIC_COMPONENT')
            identity=digest('blue-contracts-component/1.0',{'kind':'ACYCLIC','generation':1,'members':ids})
            state=digest('blue-contracts-component-state/1.0',{'componentIdentity':identity,
                'memberStates':[{'documentId':ids[0],'blueId':docs[ids[0]]['blueId']}],
                'masterBlueId':None,'cyclicProofIdentity':None})
            require(c['componentIdentity']==identity and c['componentStateIdentity']==state,'RESOURCE_ADMISSION_COMPONENT_IDENTITY');states.append(state)
        require(sorted(members)==sorted(docs),'RESOURCE_ADMISSION_COMPONENT_INVENTORY')
        occurrences=[]
        for o in value['occurrences']:
            closed(o,OCC_FIELDS,'RESOURCE_ADMISSION_OCCURRENCE_FIELDS')
            expected={'sourceDocumentId':child,'sourcePath':'/peer','activationGeneration':1,
                      'targetDocumentId':missing,'bindingPolicyIdentity':expected_environment['managedBindingPolicyIdentity']}
            require(did(o['sourceDocumentId'])==child and did(o['targetDocumentId'])==missing
                    and o['sourcePath']=='/peer' and o['activationGeneration']==1
                    and o['sourceAddress']=={'activationGeneration':1,'isRoot':False,'path':'/peer'}
                    and o['bindingPolicyIdentity']==expected['bindingPolicyIdentity'] and o['expectedTargetBlueId']==missing
                    and o['active'] is True and o['pendingHistoricalEpoch'] is None
                    and o['pendingRepresentationCursor'] is None,'RESOURCE_ADMISSION_EXACT_PROSPECTIVE_EDGE')
            occurrence=digest('blue-contracts-managed-occurrence-lineage/1.0',expected)
            binding=digest('blue-contracts-managed-occurrence/1.0',dict(expected,expectedTargetBlueId=missing))
            require(o['occurrenceIdentity']==occurrence and o['bindingIdentity']==binding,'RESOURCE_ADMISSION_EDGE_IDENTITY')
            occurrences.append({'occurrenceIdentity':occurrence,'bindingIdentity':binding,'active':True,'pendingHistoricalEpoch':None})
        occurrences.sort(key=lambda v:(v['occurrenceIdentity'],v['bindingIdentity']))
        require(len(occurrences)==len({v['occurrenceIdentity'] for v in occurrences}),'RESOURCE_ADMISSION_DUPLICATE_EDGE')
        bindings=digest('blue-contracts-occurrence-binding-set/1.0',occurrences)
        require(value['occurrenceBindingSetIdentity']==bindings,'RESOURCE_ADMISSION_BINDING_SET_IDENTITY')
        roots=[did(v) for v in value['publicRootDocumentIds']]
        require(roots==[child] and all(v['publicRoot']==(k==child) for k,v in docs.items()),'RESOURCE_ADMISSION_PUBLIC_ROOTS')
        identity=digest('blue-contracts-affected-closure/1.0',{'graphGeneration':1,'documents':list(docs.values()),
                         'occurrenceBindingSetIdentity':bindings,'components':sorted(states),'publicRootDocumentIds':roots})
        require(value['closureIdentity']==identity,'RESOURCE_ADMISSION_CLOSURE_IDENTITY')
        return docs
    def invocation(inp):
        closed(inp,INPUT_FIELDS,'RESOURCE_ADMISSION_INPUT_FIELDS')
        require(inp['operation']=='ADMIT_CLOSURE' and inp['admissionCandidate'] is None
                and inp['admissionCandidateIdentity'] is None and inp['directDeliveries']==[], 'RESOURCE_ADMISSION_INPUT_KIND')
        cause=inp['cause'];closed(cause,('admissionKind','causeIdentity','kind','label','parentTransitionIdentity',
                                      'policyIdentity','triggeringEventBlueId'),'RESOURCE_ADMISSION_CAUSE_FIELDS')
        require(cause['kind']=='ADMISSION' and cause['admissionKind']=='TOP_LEVEL_ADMISSION'
                and cause['label']=='contracts10-static-process-embedded-admission'
                and cause['triggeringEventBlueId'] is None and cause['parentTransitionIdentity'] is None
                and cause['policyIdentity']==digest('blue-contracts-admission-policy/1.0',
                    {'label':'contracts-top-level-admission-v1'}),'RESOURCE_ADMISSION_CAUSE_KIND')
        require(cause['causeIdentity']==digest('blue-contracts-admission-cause/1.0',
                {k:v for k,v in cause.items() if k not in ('kind','causeIdentity')}),'RESOURCE_ADMISSION_CAUSE_IDENTITY')
        require(exact_equal(inp['environment'],expected_environment),'RESOURCE_ADMISSION_ENGINE_ENVIRONMENT')
        policy=inp['executionPolicy'];closed(policy,('identity','label','localLimits','sharedLimit'),'RESOURCE_ADMISSION_POLICY_FIELDS')
        require(policy['label']=='release-default' and policy['sharedLimit']==100000 and policy['localLimits']=={}
                and policy['identity']==digest('blue-contracts-execution-policy/1.0',
                     {k:v for k,v in policy.items() if k!='identity'}),'RESOURCE_ADMISSION_EXECUTION_POLICY')
        direct=digest('blue-contracts-direct-delivery-snapshot/1.0',[])
        require(inp['directDeliverySnapshotIdentity']==direct,'RESOURCE_ADMISSION_DIRECT_SNAPSHOT')
        docs=snapshot(inp['snapshot']);s=inp['snapshot'];value={
            'operation':'admit-closure','causeIdentity':cause['causeIdentity'],'admissionCandidateIdentity':None,
            'inputGraphGeneration':s['graphGeneration'],'inputClosureIdentity':s['closureIdentity'],
            'documents':list(docs.values()),'directDeliverySnapshotIdentity':direct,
            'occurrenceBindingSetIdentity':s['occurrenceBindingSetIdentity'],'gasPolicyIdentity':policy['identity']}
        value.update({k:expected_environment[k] for k in ENV_IDENTITIES})
        require(inp['invocationIdentity']==digest('blue-contracts-invocation/1.0',value),'RESOURCE_ADMISSION_INVOCATION_IDENTITY')
        return {did(v['documentId']):v for v in s['managedDocuments']}
    require(admission['selectionPlan'] is None,'RESOURCE_ADMISSION_UNREQUESTED_SELECTOR')
    original=admission['originalInput'];completed=admission['completedInput']
    old=invocation(original);new=invocation(completed)
    require(set(old)=={child} and set(new)=={child,missing} and original['snapshot']['occurrences']==[]
            and len(completed['snapshot']['occurrences'])==1,'RESOURCE_ADMISSION_EXACT_EXPANSION')
    require(exact_equal(old[child],new[child]) and old[child]['blueId']==child
            and exact_equal(old[child]['document'],child_exact) and new[missing]['blueId']==missing
            and exact_equal(new[missing]['document'],missing_exact),'RESOURCE_ADMISSION_AUTHENTIC_SOURCE_BYTES')
    for key in ('operation','cause','admissionCandidate','admissionCandidateIdentity','directDeliveries',
                'directDeliverySnapshotIdentity','executionPolicy','environment'):
        require(exact_equal(original[key],completed[key]),'RESOURCE_ADMISSION_RETRY_CHANGED_'+key)
    require(original['invocationIdentity']==selection['workIdentity'],'RESOURCE_ADMISSION_SELECTED_ORIGINAL_INPUT')
    require(original['invocationIdentity']!=completed['invocationIdentity'],'RESOURCE_ADMISSION_EXPANSION_NOT_REBOUND')
    require(admission['publicationIdentity']==admission['inputPublicationIdentity']==publication_identity(original),
            'RESOURCE_ADMISSION_ORIGINAL_PUBLICATION_IDENTITY')
    require(completed['invocationIdentity']==admission['result']['invocationIdentity']
            ==admission['implementationEvidence']['invocationIdentity'],'RESOURCE_ADMISSION_ACTUAL_COMPLETED_INPUT')
    return completed
