"""Finite ownership/cause reference projection for RCP draft.2.
This is NOT Blue: its values are integer counters, its two charge weights are
explicit illustrative units, and it has no cryptographic receipt verification.
"""
from __future__ import annotations
from copy import deepcopy

def reachable(edges,start):
    seen={start};todo=[start]
    while todo:
        cur=todo.pop()
        for a,b in edges:
            if a==cur and b not in seen:seen.add(b);todo.append(b)
    return seen

def scc(edges,start):
    forward=reachable(edges,start)
    return {n for n in forward if start in reachable(edges,n)}

def owners(initial_edges,selected,topology_boundaries=(),births=()):
    owned=scc(initial_edges,selected);snapshots=[sorted(owned)]
    for edges in topology_boundaries:
        changed=True
        while changed:
            before=set(owned)
            for n in tuple(owned):owned.update(scc(edges,n))
            for parent,child in births:
                if parent in owned:owned.add(child)
            changed=before!=owned
        snapshots.append(sorted(owned))
    return snapshots

def cause_kind(occurrence,external_order,cache_state):
    # Cache is intentionally ignored; all represented conditions are physical.
    if cache_state not in ('cold','cached','evicted','source-committed'):raise ValueError('CACHE_VARIANT')
    if occurrence['mode']=='LIVE':return 'LIVE'
    if occurrence['mode']=='HISTORICAL' and external_order<=occurrence['anchor']:
        return 'MANAGED_REVISION'
    return 'LIVE'

def reference_parent(state,budget,cache_state,kind='LIVE'):
    if type(budget) is not int or budget<0:raise ValueError('BUDGET')
    if kind not in ('LIVE','MANAGED_REVISION'):raise ValueError('KIND')
    before=deepcopy(state);out=deepcopy(state)
    # These are illustrative weights, not the Blue tariff.
    charges=([('source-reference-step',80),('parent-reaction',20)] if kind=='LIVE'
             else [('retained-proof/application',5),('parent-reaction',20)])
    trace=[];used=0;rejected=None
    for label,cost in charges:
        if used+cost>budget:
            rejected={'label':label,'amount':cost,'remaining':budget-used};break
        trace.append({'label':label,'amount':cost});used+=cost
    if rejected:
        return {'status':'GAS_LIMIT_EXCEEDED','ownedWrites':[],'state':before,'trace':trace,'gas':used,'rejected':rejected,'kind':kind}
    out['P']={'seen':1,'embeddedS':1,'checkpoint':'E1'}
    # Never writes S; child data is part of P's exact root result.
    return {'status':'SUCCESS','ownedWrites':['P'],'state':out,'trace':trace,'gas':used,'rejected':None,'kind':kind}

def source_step(state):
    out=deepcopy(state)
    if out['S']['checkpoint']=='E1':return out
    out['S']={'counter':1,'checkpoint':'E1'};out['sourceOutbox'].append('S:E1:0')
    return out

def shared_schedule(actions,budget=100):
    state={'S':{'counter':0,'checkpoint':'E0'},'P':{'seen':0,'embeddedS':0,'checkpoint':'E0'},'sourceOutbox':[]}
    results=[]
    for action in actions:
        if action=='SOURCE':state=source_step(state)
        elif action=='PARENT':
            r=reference_parent(state,budget,'source-committed' if state['S']['counter'] else 'cold');state=r['state'];results.append(r)
        elif action in ('CACHE','EVICT','OBSERVER_FAIL'):pass
        else:raise ValueError(action)
    return {'state':state,'parentResults':results}
