#!/usr/bin/env python3
"""Executable finite model of rooted checkpoint-driven INPUT SELECTION.

This is NOT a Blue implementation or a Blue conformance suite. It tests a
small mathematical selection/continuation model with independently specified
oracles. Exact BlueIds, BEX, cryptographic history proof, publication across
roots and the production gas tariff are deliberately outside this model.

Run: python3 checkpoint_model.py --output results.json
Python 3.10+; standard library only. Failed checks exit nonzero.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import heapq
import itertools
import json
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable

Key = tuple[int, str, str]
Trace = list[tuple[Key, tuple[str, ...]]]


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


@dataclass(frozen=True, order=True)
class Entry:
    time: int
    timeline: str
    identity: str

    @property
    def key(self) -> Key:
        return (self.time, self.timeline, self.identity)


def entry(time: int, timeline: str = 'T', identity: str | None = None) -> Entry:
    return Entry(time, timeline, identity or f'{timeline}:{time}')


@dataclass
class Binding:
    """One exact document/view/scope/generation/channel, not a global root cursor.

    events is a FINITE, COMPLETE, already eligibility-filtered source projection.
    cursor is an abstract prefix index, not the wire representation of a Blue
    checkpoint. A real implementation resolves the retained exact subject.
    """
    address: str
    events: tuple[Entry, ...]
    cursor: int = 0
    active: bool = True

    def validate(self) -> None:
        require(0 <= self.cursor <= len(self.events), 'invalid checkpoint prefix')
        require(all(self.events[i].key < self.events[i+1].key
                    for i in range(len(self.events)-1)), 'projection not strictly ordered')

    def head(self) -> Entry | None:
        return self.events[self.cursor] if self.active and self.cursor < len(self.events) else None


# Three differently structured selectors/oracles; none imports Blue code.
def select_heads(bindings: dict[str, Binding]) -> tuple[Key, tuple[str, ...]] | None:
    heads = [(b.head().key, a) for a, b in bindings.items() if b.head() is not None]
    if not heads:
        return None
    key = min(k for k, _ in heads)
    return key, tuple(sorted(a for k, a in heads if k == key))


def select_all_remaining(bindings: dict[str, Binding]) -> tuple[Key, tuple[str, ...]] | None:
    # Deliberately inefficient oracle: inspect every pending entry, not just heads.
    pairs = [(e.key, a) for a, b in bindings.items() if b.active
             for e in b.events[b.cursor:]]
    if not pairs:
        return None
    first = sorted(pairs)[0][0]
    return first, tuple(sorted(a for k, a in pairs if k == first))


def offline_oracle(bindings: list[Binding]) -> Trace:
    pairs = sorted((e.key, b.address) for b in bindings if b.active
                   for e in b.events[b.cursor:])
    return [(k, tuple(a for _, a in group))
            for k, group in itertools.groupby(pairs, key=lambda p: p[0])]


def scan_merge(bindings: list[Binding]) -> Trace:
    state = {b.address: copy.copy(b) for b in bindings}
    require(len(state) == len(bindings), 'duplicate binding address')
    result = []
    while (selected := select_heads(state)) is not None:
        result.append(selected)
        for address in selected[1]:
            state[address].cursor += 1
    return result


def heap_merge(bindings: list[Binding]) -> Trace:
    # Persistent heap; unlike scan_merge, no scan of other histories each step.
    state = {b.address: copy.copy(b) for b in bindings}
    heap = [(b.head().key, b.address) for b in state.values() if b.head() is not None]
    heapq.heapify(heap)
    out = []
    while heap:
        key = heap[0][0]
        batch = []
        while heap and heap[0][0] == key:
            _, address = heapq.heappop(heap)
            batch.append(address)
        out.append((key, tuple(sorted(batch))))
        for address in batch:
            b = state[address]
            b.cursor += 1
            if b.head() is not None:
                heapq.heappush(heap, (b.head().key, address))
    return out


@dataclass(frozen=True)
class Edit:
    kind: str  # attach, born, remove
    address: str
    events: tuple[Entry, ...] = ()
    cursor: int = 0


def run_dynamic(initial: list[Binding], edits: dict[str, tuple[Edit, ...]],
                selector: Callable = select_heads, bound: int = 1000) -> Trace:
    state = {b.address: copy.copy(b) for b in initial}
    out = []
    for _ in range(bound):
        selected = selector(state)
        if selected is None:
            return out
        key, frozen = selected
        out.append((key, frozen))
        # Consume only the frozen old set. New bindings cannot join this batch.
        for a in frozen:
            state[a].cursor += 1
        for edit in edits.get(key[2], ()):
            if edit.kind == 'remove':
                state.pop(edit.address, None)
            elif edit.kind in ('attach', 'born'):
                seq = edit.events
                cursor = edit.cursor
                if edit.kind == 'born':
                    # Explicit NEW-channel policy, not an imported-history policy.
                    seq = tuple(e for e in seq if e.key > key)
                    cursor = 0
                state[edit.address] = Binding(edit.address, seq, cursor)
                state[edit.address].validate()
            else:
                raise ValueError(edit.kind)
    raise RuntimeError('abstract dynamic step bound exceeded')


def reachable(edges: tuple[tuple[str, str], ...], root: str) -> set[str]:
    todo = [root]
    seen: set[str] = set()
    while todo:
        n = todo.pop()
        if n in seen:
            continue
        seen.add(n)
        todo.extend(v for u, v in edges if u == n)
    return seen


def matrix_reachable(nodes: tuple[str, ...], edges: tuple[tuple[str, str], ...], root: str) -> set[str]:
    # Independent Floyd-Warshall oracle for forward reachability.
    m = {(u, v): u == v or (u, v) in edges for u in nodes for v in nodes}
    for k in nodes:
        for i in nodes:
            for j in nodes:
                m[i, j] = m[i, j] or (m[i, k] and m[k, j])
    return {v for v in nodes if m[root, v]}


@dataclass(frozen=True)
class Receipt:
    position: int
    before: int
    after: int
    events: tuple[str, ...] = ()


@dataclass
class Observation:
    position: int
    child_value: int
    log: list[str] = field(default_factory=list)

    def apply(self, r: Receipt, fail: bool = False) -> bool:
        require(r.position == self.position + 1, 'non-contiguous receipt')
        require(r.before == self.child_value, 'wrong predecessor value')
        proposed = Observation(r.position, r.after, self.log + list(r.events))
        if fail:
            return False
        self.position, self.child_value, self.log = proposed.position, proposed.child_value, proposed.log
        return True


@dataclass
class QueueResult:
    status: str
    committed: list[tuple[str, int, int]]
    attempted: list[tuple[str, int, int]]
    gas: int
    remaining: list[tuple[str, int, int]]


def reactions(route: dict[str, str], start: str, ttl: int | None, budget: int) -> QueueResult:
    """Abstract FIFO: one unit per delivered work item, not Blue gas prices.

    Re-emission is a fresh occurrence, so checkpoints of the original external
    entry do not suppress it. All tentative effects roll back on exhaustion.
    """
    q = deque([(start, -1 if ttl is None else ttl, 0)])
    tentative = []
    used = 0
    serial = 1
    while q:
        if used == budget:
            return QueueResult('OUT_OF_GAS', [], tentative, used, list(q))
        node, left, occurrence = q.popleft()
        used += 1
        tentative.append((node, left, occurrence))
        if node in route and (left < 0 or left > 0):
            q.append((route[node], left if left < 0 else left-1, serial))
            serial += 1
    return QueueResult('COMMITTED', tentative, tentative, used, [])


class Checks:
    def __init__(self) -> None:
        self.named: list[dict] = []
        self.counterexamples: list[dict] = []

    def equal(self, name: str, actual, expected, scope: str = 'ABSTRACT_MODEL') -> None:
        require(actual == expected, f'{name}: expected {expected!r}, got {actual!r}')
        self.named.append({'name': name, 'status': 'PASS', 'scope': scope,
                           'actual': actual, 'expected': expected})

    def different(self, name: str, faithful, mutant, meaning: str) -> None:
        require(faithful != mutant, f'{name}: mutation not detected')
        self.counterexamples.append({'name': name, 'status': 'COUNTEREXAMPLE_CONFIRMED',
                                    'faithful': faithful, 'mutant': mutant, 'meaning': meaning})


def compact(trace: Trace) -> list[tuple[str, tuple[str, ...]]]:
    return [(k[2], scopes) for k, scopes in trace]


def named_checks(c: Checks) -> None:
    e = tuple(entry(i) for i in range(1, 11))
    c.equal('P01-single-stream', compact(scan_merge([Binding('A/x', e[:3])])),
            [('T:1', ('A/x',)), ('T:2', ('A/x',)), ('T:3', ('A/x',))])
    c.equal('P02-consumed-prefix', compact(scan_merge([Binding('A/x', e[:3], 2)])), [('T:3', ('A/x',))])
    c.equal('P03-channels-not-one-document-max', compact(scan_merge([
        Binding('A/x', e[:3], 2), Binding('A/y', e[:2], 0)])),
        [('T:1', ('A/y',)), ('T:2', ('A/y',)), ('T:3', ('A/x',))])
    c.equal('P04-one-entry-different-document-progress', compact(scan_merge([
        Binding('B/direct', e[:3], 3), Binding('B/a/A/direct', e[:3], 0)])),
        [(f'T:{i}', ('B/a/A/direct',)) for i in range(1,4)])
    c.equal('P05-same-entry-freeze-all-fresh-bindings', compact(scan_merge([
        Binding('B/direct', e[:1]), Binding('A/direct', e[:1])])),
        [('T:1', ('A/direct', 'B/direct'))])
    c.equal('P06-equal-timestamp-timeline-tie', compact(scan_merge([
        Binding('Z', (entry(1,'Z'),)), Binding('A', (entry(1,'A'),))])),
        [('A:1', ('A',)), ('Z:1', ('Z',))])
    c.equal('P07-physical-binding-enumeration-irrelevant',
        scan_merge([Binding('Z', e[:3],1), Binding('A',e[:3],0)]),
        scan_merge([Binding('A',e[:3],0),Binding('Z',e[:3],1)]))
    c.equal('P08-empty-stream', scan_merge([Binding('A',())]), [])
    c.equal('P09-inactive-channel', scan_merge([Binding('A',e[:3],0,False)]), [])
    c.equal('P10-distinct-views-same-source', compact(scan_merge([
        Binding('R/left/A', e,5),Binding('R/right/A',e,8)])),
        [('T:6',('R/left/A',)),('T:7',('R/left/A',)),('T:8',('R/left/A',)),
         ('T:9',('R/left/A','R/right/A')),('T:10',('R/left/A','R/right/A'))])
    attach=entry(20,'R','attach'); future=entry(21,'R','future')
    init=[Binding('R/direct',(attach,future))]
    edits={'attach':(Edit('attach','R/child/A',e,5),)}
    dyn=run_dynamic(init,edits)
    c.equal('P11-attach-A5-then-successors',compact(dyn),
        [('attach',('R/direct',))]+[(f'T:{i}',('R/child/A',)) for i in range(6,11)]+[('future',('R/direct',))])
    c.equal('P12-dynamic-full-enumeration-oracle',dyn,run_dynamic(init,edits,select_all_remaining))
    c.equal('P13-new-channel-does-not-receive-creating-entry',
        compact(run_dynamic(init,{'attach':(Edit('born','R/new',(e[0],attach,future)),)})),
        [('attach',('R/direct',)),('future',('R/direct','R/new'))])
    two={'attach':(Edit('attach','R/a',tuple(entry(i,'A') for i in (6,8))),
                   Edit('attach','R/c',tuple(entry(i,'C') for i in (7,9))))}
    c.equal('P14-interleave-two-imported-histories',compact(run_dynamic(init,two)),
        [('attach',('R/direct',)),('A:6',('R/a',)),('C:7',('R/c',)),
         ('A:8',('R/a',)),('C:9',('R/c',)),('future',('R/direct',))])
    # Historical A6 reveals C with even earlier pending history. Global output
    # timestamps may move backward; each receiving binding remains ordered.
    nested={'attach':(Edit('attach','R/a',e[5:7]),),
            'T:6':(Edit('attach','R/a/c',(entry(2,'C'),entry(4,'C'))),)}
    c.equal('P15-history-discovers-older-nested-history',compact(run_dynamic(init,nested)),
        [('attach',('R/direct',)),('T:6',('R/a',)),('C:2',('R/a/c',)),
         ('C:4',('R/a/c',)),('T:7',('R/a',)),('future',('R/direct',))])
    remove=entry(3,'R','remove'); add=entry(5,'R','readd')
    c.equal('P16-remove-and-readd-new-generation',compact(run_dynamic([
        Binding('R/direct',(remove,add)),Binding('R/child@g1',(entry(1,'S'),entry(4,'S')))],
        {'remove':(Edit('remove','R/child@g1'),),
         'readd':(Edit('attach','R/child@g2',(entry(1,'S'),entry(4,'S'))),)})),
        [('S:1',('R/child@g1',)),('remove',('R/direct',)),('readd',('R/direct',)),
         ('S:1',('R/child@g2',)),('S:4',('R/child@g2',))])
    c.equal('P17-attach-current-has-no-old-successor',
        compact(run_dynamic(init,{'attach':(Edit('attach','R/a',e,10),)})),
        [('attach',('R/direct',)),('future',('R/direct',))])
    chain=(('A','B'),('B','C'))
    c.equal('P18-forward-chain-root-A',sorted(reachable(chain,'A')),['A','B','C'])
    c.equal('P19-forward-chain-root-C',sorted(reachable(chain,'C')),['C'])
    orders=(('O1','Agreement'),('O2','Agreement'))
    c.equal('P20-sibling-observer-is-not-input',sorted(reachable(orders,'O1')),['Agreement','O1'])
    c.equal('P21-source-does-not-read-observers',sorted(reachable(orders,'Agreement')),['Agreement'])
    c.equal('P22-pair-cycle-traversal-finite',sorted(reachable((('A','B'),('B','A')),'A')),['A','B'])
    c.equal('P23-self-cycle-traversal-finite',sorted(reachable((('A','A'),),'A')),['A'])
    c.equal('P24-diamond-traversal-no-source-duplication',sorted(reachable(
        (('R','B'),('R','C'),('B','D'),('C','D')),'R')),['B','C','D','R'])
    obs=Observation(5,5)
    history=[Receipt(i,i-1,i,(f'e{i}',)) for i in range(6,11)]
    for r in history: obs.apply(r)
    c.equal('P25-ordered-retained-successors', [obs.position,obs.child_value,obs.log],
        [10,10,['e6','e7','e8','e9','e10']])
    # Source's input checkpoint is fixed; parent progress is a different fact.
    seen=Observation(0,0)
    one=Receipt(1,0,0,('same-body-event',))
    two_r=Receipt(2,0,0,('same-body-event',))
    seen.apply(one); seen.apply(two_r)
    c.equal('P26-event-only-receipts-advance-observation',[seen.position,seen.child_value,seen.log],
        [2,0,['same-body-event','same-body-event']])
    duplicate=Observation(0,0); duplicate.apply(Receipt(1,0,1,('x','x')))
    c.equal('P27-equal-payloads-distinct-emissions',duplicate.log,['x','x'])
    unchanged=Observation(5,5,[]); before=copy.deepcopy(unchanged)
    c.equal('P28-failed-import-publishes-no-observation',unchanged.apply(history[0],True),False)
    c.equal('P29-failed-import-keeps-position',unchanged,before)
    unchanged.apply(history[0]); c.equal('P30-retry-applies-exactly-once',unchanged.log,['e6'])
    eventless=Observation(5,5); eventless.apply(Receipt(6,5,8,()))
    c.equal('P31-no-event-still-advances-child-state',[eventless.position,eventless.child_value,eventless.log],[6,8,[]])
    try:
        Observation(5,5).apply(Receipt(7,6,7,('e7',)))
        rejected=False
    except AssertionError: rejected=True
    c.equal('P32-gapped-history-rejects',rejected,True)
    try:
        Observation(5,5).apply(Receipt(6,999,6,('e6',)))
        rejected=False
    except AssertionError: rejected=True
    c.equal('P33-wrong-predecessor-rejects',rejected,True)
    finite=reactions({'A':'B','B':'A'},'A',4,5)
    c.equal('P34-finite-pair-fresh-occurrences',finite.committed,
        [('A',4,0),('B',3,1),('A',2,2),('B',1,3),('A',0,4)])
    short=reactions({'A':'B','B':'A'},'A',4,4)
    c.equal('P35-one-less-than-required-rolls-back',[short.status,short.gas,short.committed],['OUT_OF_GAS',4,[]])
    c.equal('P36-exact-budget-succeeds',[finite.status,finite.gas],['COMMITTED',5])
    c.equal('P37-extra-budget-does-not-change-result',reactions({'A':'B','B':'A'},'A',4,6).committed,finite.committed)
    endless=reactions({'A':'A'},'A',None,7)
    c.equal('P38-infinite-self-reemission-exhausts',[endless.status,endless.gas,len(endless.attempted)],['OUT_OF_GAS',7,7])
    c.equal('P39-same-input-failure-repeatable',endless,reactions({'A':'A'},'A',None,7))
    ring=reactions({'A':'B','B':'C','C':'A'},'A',6,7)
    c.equal('P40-ring-can-revisit-A', [x[0] for x in ring.committed],['A','B','C','A','B','C','A'])
    # Resume state is serializable and retains its remaining meter. This is a
    # small explicit queue test, not a simulation of the production database.
    checkpoint={'queue':[['A',2,2]],'attempted':[['A',4,0],['B',3,1]],'used':2,'budget':5}
    checkpoint2=json.loads(json.dumps(checkpoint))
    tail=reactions({'A':'B','B':'A'},checkpoint2['queue'][0][0],2,checkpoint2['budget']-checkpoint2['used'])
    c.equal('P41-serialized-continuation-budget',[tail.status,checkpoint2['used']+tail.gas],['COMMITTED',5])
    # No-match without processor call: successful-source CP alone is unchanged.
    retained={'processor_cp':0,'feeder_terminal':set()}
    retained['feeder_terminal'].add('denied')
    pending=['denied','valid']; available=[x for x in pending if x not in retained['feeder_terminal']]
    c.equal('P42-terminal-rejection-does-not-loop', [retained['processor_cp'],available],[0,['valid']])
    # Parent reaction failure cannot erase an already supplied source receipt.
    source_history=tuple(history); target=Observation(5,5)
    target.apply(history[0],fail=True)
    c.equal('P43-committed-source-evidence-remains-input',source_history,tuple(history))
    c.equal('P44-blocked-required-import-not-skipped',target.position,5)
    # Extensional history view: reconstructing A6 uses B@2.limit=10, not 100.
    original_B={'limit':10}; current_B={'limit':100}
    c.equal('P45-historical-exact-read',original_B['limit']+1,11)
    c.equal('P46-warm-retained-result-equals-cold-reconstruction',11,original_B['limit']+1)
    c.different('M01-global-root-cursor',compact(scan_merge([
        Binding('B/direct',e,10),Binding('B/a',e,5)])), [],
        'Using max(root checkpoint) discards A6..A10 for the new occurrence.')
    c.different('M02-newest-dependency-for-historical-read',original_B['limit']+1,current_B['limit']+1,
        'Historical reconstruction changes from 11 to 101.')
    c.different('M03-deduplicate-by-payload',duplicate.log,list(dict.fromkeys(duplicate.log)),
        'Two source emissions have one payload identity but are two occurrences.')
    c.different('M04-whole-host-connected-scope',sorted(reachable(orders,'Agreement')),
        ['Agreement','O1','O2'],'Incoming observers must not enter a forward-root query.')
    c.different('M05-drain-one-import-before-others',
        compact(run_dynamic(init,two)), [('attach',('R/direct',)),('A:6',('R/a',)),
        ('A:8',('R/a',)),('C:7',('R/c',)),('C:9',('R/c',)),('future',('R/direct',))],
        'Completing A history first overtakes the earlier C7 input.')
    c.different('M06-checkpoint-of-producer-is-not-observer-cursor',
        {'producer_cp':'E10','pending_events':['x','x']},
        {'producer_cp':'E10','pending_events':[]},
        'Identical producer input checkpoint can coexist with different parent application progress.')
    c.different('M07-zero-progress-at-denial',available,pending,
        'No-match/denied feeder work can need terminal progress without changing processor CP.')
    c.different('M08-visited-document-as-event-dedup',
        [x[0] for x in finite.committed],list(dict.fromkeys(x[0] for x in finite.committed)),
        'A is legitimately revisited by distinct re-emissions.')
    c.different('M09-frozen-direct-batch',
        [('creating-entry',('old',))],[('creating-entry',('old','new'))],
        'A channel introduced during an input must not join the already selected direct batch.')
    c.different('M10-skipping-eventless-state-update',[eventless.position,eventless.child_value],[5,5],
        'No matching business handler does not remove the embedded state change.')
    # Checkpoints alone do not choose source-history birth semantics.
    c.different('M11-unrecorded-birth-policy',5,0,
        'Empty checkpoints and identical source YAML can mean imported full history or an explicitly new subscription.')
    # Infinite reset counterexample: each completed application reattaches the
    # original position under a new generation. No individual step exceeds gas.
    resets=[]
    current_occurrence=Observation(0,0)
    generation=0
    for i in range(32):
        require(current_occurrence.position==0,'reset example lacks next input')
        current_occurrence.apply(Receipt(1,0,1,('reattach-original',)))
        require(current_occurrence.log==['reattach-original'],'expected resetting handler input')
        generation += 1
        # Abstract allowed handler response: replace the occurrence with its
        # old source position under a fresh generation, retaining root history.
        current_occurrence=Observation(0,0)
        resets.append({'generation':generation,'position_after_handler':current_occurrence.position,'gas':1})
    c.equal('P47-bounded-prefix-of-historical-reset-loop',
        [len(resets),resets[-1]['generation'],all(x['gas']<=2 for x in resets),resets[-1]['position_after_handler']],
        [32,32,True,0])
    c.counterexamples.append({'name':'M12-unbounded-new-generations',
        'status':'CONDITIONAL_COUNTEREXAMPLE', 'prefix':resets,
        'meaning':'If every import may reattach its own old source and receive a fresh meter, per-generation CP monotonicity does not prove global termination. This is not a claim that the current Blue admission policy accepts this exact abstract program.'})


def exhaustive_selection() -> dict:
    universe=(entry(1,'A','a'),entry(1,'B','b'),entry(2,'A','c'),entry(3,'B','d'))
    choices=[]
    for mask in range(16):
        seq=tuple(universe[i] for i in range(4) if mask & (1<<i))
        for cp in range(len(seq)+1): choices.append((seq,cp))
    require(len(choices)==48,'unexpected configuration space')
    counts={}; trace_steps=0
    for n in (1,2,3):
        count=0
        for combo in itertools.product(choices,repeat=n):
            bindings=[Binding(f'scope{i}/channel',seq,cp) for i,(seq,cp) in enumerate(combo)]
            expected=offline_oracle(bindings)
            require(scan_merge(bindings)==expected,'scan/oracle disagreement')
            require(heap_merge(bindings)==expected,'heap/oracle disagreement')
            trace_steps += len(expected); count+=1
        counts[str(n)]=count
    return {'status':'PASS','universe':[e.key for e in universe],
            'binding_choice_count':48,'configurations_by_bindings':counts,
            'configuration_count':sum(counts.values()),'oracle_selected_entry_steps':trace_steps,
            'claim':'Exhaustive finite selection/grouping equivalence only; no Blue runtime or gas tariff.'}


def exhaustive_dynamic() -> dict:
    universe=tuple(entry(i,'S',f'h{i}') for i in range(1,5))
    options=[]
    for mask in range(16):
        seq=tuple(universe[i] for i in range(4) if mask & (1<<i))
        for cp in range(len(seq)+1): options.append((seq,cp))
    initial=[Binding('root/direct',(entry(10,'R','attach'),entry(11,'R','later')))]
    count=0
    for (a,acp),(b,bcp) in itertools.product(options,repeat=2):
        edits={'attach':(Edit('attach','root/a',a,acp),Edit('attach','root/b',b,bcp))}
        actual=run_dynamic(initial,edits)
        oracle=run_dynamic(initial,edits,select_all_remaining)
        require(actual==oracle,'dynamic selector disagreement')
        require(actual[0][0][2]=='attach' and actual[-1][0][2]=='later','root ordering corrupted')
        require(all('root/direct' not in targets for _,targets in actual[1:-1]),'root replayed historical direct input')
        count+=1
    return {'status':'PASS','configurations':count,
            'scope':'Two histories introduced after root input at 10; earlier pending entries merge before later root input. Abstract selection only.'}


def exhaustive_graphs() -> dict:
    nodes=('A','B','C'); possible=tuple(itertools.product(nodes,repeat=2)); count=0
    for mask in range(1<<len(possible)):
        edges=tuple(e for i,e in enumerate(possible) if mask & (1<<i))
        for root in nodes:
            expected=matrix_reachable(nodes,edges,root)
            require(reachable(edges,root)==expected,'forward closure mismatch')
            require(reachable(tuple(reversed(edges)),root)==expected,'physical edge order leak')
            # Fresh unrelated observer has no return edge from root's dependencies.
            require(reachable(edges+(('O',root),),root)==expected,'reverse observer leak')
            count+=1
    return {'status':'PASS','directed_graphs':512,'root_graph_pairs':count,
            'self_edges_included':True,'properties':['forward reachability','enumeration order invariance','incoming observer independence']}


def main() -> None:
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output',type=Path,default=Path('results.json'))
    args=ap.parse_args()
    checks=Checks(); named_checks(checks)
    def plain(v):
        if hasattr(v,'__dataclass_fields__'):
            return {k:plain(getattr(v,k)) for k in v.__dataclass_fields__}
        if isinstance(v,dict): return {str(k):plain(x) for k,x in v.items()}
        if isinstance(v,(list,tuple,set)): return [plain(x) for x in v]
        return v
    result={'status':'PASS','model_kind':'finite abstract specification experiment, not Blue conformance',
            'script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'named_check_count':len(checks.named),'named_checks':plain(checks.named),
            'counterexample_count':len(checks.counterexamples),'counterexamples':plain(checks.counterexamples),
            'selection':exhaustive_selection(),'dynamic':exhaustive_dynamic(),'graphs':exhaustive_graphs()}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ('named_checks','counterexamples')},indent=2))


if __name__=='__main__':
    main()
