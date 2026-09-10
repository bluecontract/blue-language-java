#!/usr/bin/env python3
"""Run only against a genuine accepted RUN027 observation; never create a positive fixture."""
import copy,json,sys
from pathlib import Path
from check_resource_continuation import check_resource_continuation
import run_production_adapter as production

def main():
    record=json.loads(Path(sys.argv[1]).read_text());suite=Path(production.__file__).parent
    rule=json.loads((suite/'plans/rcp-run-027.json').read_text())['outputContract']['resourceContinuation']
    weights=production.verified_tariff_weights(suite)
    def check(rec):return check_resource_continuation(rec,rule,weights,production.require,production.exact_equal,production.gas_check)
    check(record)
    def h(r):return r['output']['resourceContinuation']
    def source(r):return next(v for v in h(r)['commandObservations'].values() if v[-1]['command']['commandType']=='SOURCE_HISTORY_EXECUTE')
    def original(r):return h(r)['commandObservations'][h(r)['blockedCommandId']]
    def admission(r):return source(r)[-1]['sourceAdmissions'][0]
    def terminal(r):return original(r)[-1]['actualDrain']['terminals'][-1]
    cases={
      'missing-packaged-app':lambda r:r['host']['applicationBinding']['applicationEntries'].clear(),
      'wrong-dependency':lambda r:r['dependencies'].update({'forged.jar':'0'*64}),
      'wrong-exact-upload-accepted':lambda r:h(r)['wrongUploadException'].update(code='ACCEPTED'),
      'body-upload-wakes-original':lambda r:h(r)['correctUpload'].update(queuedCommandIds=[h(r)['blockedCommandId']]),
      'duplicate-requeues':lambda r:h(r)['duplicateUpload'].update(queuedCommandIds=[h(r)['blockedCommandId']]),
      'duplicate-logical-command':lambda r:h(r)['duplicateSubmission'].update(created=True),
      'missing-capture':lambda r:original(r)[0]['actualOutcome']['sourceHistoryCaptures'].clear(),
      'changed-frozen-cutoff':lambda r:original(r)[0]['actualOutcome']['sourceHistoryCaptures'][0]['selection']['cutoffExclusive'].__setitem__(0,1),
      'wrong-selection-hash':lambda r:original(r)[0]['actualOutcome']['sourceHistoryCaptures'][0]['selection'].update(selectionIdentity='sha256:'+'0'*64),
      'current-head-substitution':lambda r:original(r)[0]['actualOutcome']['sourceHistoryCaptures'][0]['selection'].update(authoredBlueId=h(r)['states']['applied']['sdkRecords'][h(r)['documentIds']['child']]['blueId']),
      'changed-original-entry':lambda r:original(r)[-1]['actualEntries'][0].update(blueId='forged-entry'),
      'missing-provider-ledger':lambda r:source(r)[-1]['actualReplayEvidence'].pop('exactProviderReads'),
      'early-dependency-release':lambda r:h(r)['rawSqlSnapshots']['blocked']['tables']['mini_source_history_dependency']['rows'][0].update(completed=True),
      'missing-source-command':lambda r:h(r)['states']['applied']['commands'].__delitem__(next(i for i,x in enumerate(h(r)['states']['applied']['commands']) if x['commandType']=='SOURCE_HISTORY_EXECUTE')),
      'replay-original-before-source':lambda r:h(r)['states']['applied']['appliedReplayOrder'].reverse(),
      'missing-source-admission':lambda r:source(r)[-1]['sourceAdmissions'].clear(),
      'typed-wait-publishes':lambda r:source(r)[0]['sourceAdmissions'][0].update(published=True),
      'typed-wait-fake-gas':lambda r:source(r)[0]['sourceAdmissions'][0].update(totalGas=1),
      'forged-admission-companion':lambda r:admission(r)['result'].update(platformCommitCompanion=None),
      'missing-admission-gas':lambda r:admission(r)['fullGasTrace'].clear(),
      'changed-source-input':lambda r:admission(r)['retainedInput'].update(invocationIdentity='sha256:'+'0'*64),
      'missing-root-terminal':lambda r:original(r)[-1]['actualDrain']['terminals'].clear(),
      'wrong-root-ownership':lambda r:terminal(r).update(ownedDocumentIds=[]),
      'wrong-rooted-companion':lambda r:terminal(r)['rootedProjection'].update(companionIdentity='sha256:'+'0'*64),
      'false-readiness':lambda r:h(r)['states']['applied']['http'][h(r)['documentIds']['host']]['body'].update(ready=False),
      'replay-loses-history':lambda r:h(r)['states']['completedRestart']['sdkRecords'][h(r)['documentIds']['child']]['receipts'].clear(),
      'source-epoch-invented':lambda r:h(r)['states']['applied']['sdkRecords'][h(r)['documentIds']['child']].update(epoch=999),
      'source-event-duplicated':lambda r:h(r)['states']['applied']['sdkRecords'][h(r)['documentIds']['child']]['events'].append({'forged':True}),
      'restart-mislabeled':lambda r:h(r)['rebuilds']['completed'].update(boundary='FRESH_JVM'),
    }
    rejected=[]
    for name,mutate in cases.items():
        altered=copy.deepcopy(record);mutate(altered)
        altered['host']['output']=altered['output']
        # Preserve the duplicated raw observation envelope, so mutations reach
        # their owning semantic check instead of failing a shallow copy mismatch.
        mapped=h(altered)['commandObservations']
        altered['host']['actualProcessorObservations']=[x for rows in mapped.values() for x in rows]
        try:check(altered)
        except (ValueError,AssertionError,KeyError,TypeError,IndexError):rejected.append(name)
        else:raise AssertionError('Mutation accepted: '+name)
    print(json.dumps({'baseline':'PASS','rejected':rejected,'count':len(rejected)},indent=2))
if __name__=='__main__':main()
