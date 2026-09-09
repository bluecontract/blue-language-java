#!/usr/bin/env python3
"""Oracle mutation checks require a real passing checkpoint-tail production record first."""
import argparse
import copy
import importlib.util
import json
import sys
from pathlib import Path


def load(path,name):
    spec=importlib.util.spec_from_file_location(name,path); module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--suite',type=Path,required=True);parser.add_argument('--record',type=Path,required=True)
    args=parser.parse_args();sys.path.insert(0,str(args.suite.resolve()))
    runner=load(args.suite/'run_production_adapter.py','representation_runner');checker=load(args.suite/'check_representation.py','representation_checker')
    contract=json.loads((args.suite/'plans/rcp-run-017.json').read_text())['outputContract'];weights=runner.verified_tariff_weights(args.suite)
    def check(record):checker.check_representation(record,contract,weights,'checkpoint-tail',runner.require,runner.exact_equal,runner.gas_check)
    original=json.loads(args.record.read_text());check(original);print('ORIGINAL_PASS',flush=True)
    def phase(record,change):
        change(record['phaseRecords']['traversal'])
        next(row for row in record['transcript'] if row['request']['op']=='drainRootHistory')['response']=copy.deepcopy(record['phaseRecords']['traversal'])
    def position_call(record,change):
        phase(record,lambda p:change(next(c for c in p['calls'] if c['representationPositions'])))
    tests=[
        ('position-order-reversed',lambda r:r['phaseRecords']['originalChain']['positions'].reverse()),
        ('forged-proof-not-rejected',lambda r:r['phaseRecords']['originalChain']['checks']['0']['forgedProof'].__setitem__('returnedNormally',True)),
        ('wrong-owned-source-not-rejected',lambda r:r['phaseRecords']['originalChain']['checks']['0']['wrongOwnedSource'].__setitem__('returnedNormally',True)),
        ('missing-companion-not-rejected',lambda r:r['phaseRecords']['originalChain']['checks']['0']['missingCompanion'].__setitem__('returnedNormally',True)),
        ('skipped-original-position',lambda r:position_call(r,lambda c:c['representationPositions'].clear())),
        ('duplicate-position',lambda r:position_call(r,lambda c:c['representationPositions'].append(copy.deepcopy(c['representationPositions'][0])))),
        ('source-head-mutated',lambda r:position_call(r,lambda c:c['after']['P'].__setitem__('blueId','mutated-source'))),
        ('original-receipt-rewritten',lambda r:position_call(r,lambda c:c['after']['S']['receipts'][0].__setitem__('afterBlueId','mutated-history'))),
        ('reaction-suppressed',lambda r:position_call(r,lambda c:c['after']['C']['exactDocument'].__setitem__('updates',copy.deepcopy(c['before']['C']['exactDocument']['updates'])))),
        ('runtime-gas-quantity-mutated',lambda r:position_call(r,lambda c:c['terminals'][0]['gas']['charges'][0].__setitem__('quantity',99999))),
        ('blocked-as-progress',lambda r:position_call(r,lambda c:c.update(quiescent=False,paused=False))),
        ('restart-state-changed',lambda r:r['restart']['after']['C'].__setitem__('blueId','mutated-restart')),
    ]
    for name,change in tests:
        value=copy.deepcopy(original);change(value)
        try:check(value)
        except ValueError as error:print('REJECT',name,str(error),flush=True)
        else:raise AssertionError('MUTATION_ACCEPTED: '+name)
    print('ALL_MUTATIONS_REJECTED',len(tests),flush=True)


if __name__=='__main__':main()
