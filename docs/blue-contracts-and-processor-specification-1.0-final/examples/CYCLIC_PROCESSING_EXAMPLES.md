# Cyclic `Process Embedded` Processing — Exact Worked Examples

This document is informative. The normative rules are in
`specifications/blue-contracts-and-processor-specification-1.0.md`, and the
machine-readable expectations are in `conformance/contracts/fixtures/closure`.

## 1. The model in one paragraph

`Process Embedded` defines one finite directed graph of processable documents.
An acyclic region is processed deepest-first. A mutually reachable region is a
cyclic processing component. One external cause owns one affected-closure
invocation, one work identity domain, one shared gas ledger, and one atomic
publication boundary. After every identity-affecting patch, the complete
changed cyclic component is re-finalized tentatively with the ordinary Blue
Language cyclic-set algorithm before later work may read it. Temporary
`MASTER#index` values are exact Blue values but are not authoritative until the
whole closure commits.

No second authored relationship graph is introduced. Stable `DocumentId`
values are platform lineage evidence; BlueIds remain exact immutable content
identities.

---

## 2. Dynamic `A -> B -> A` that finishes cleanly

### 2.1 Initial state

Initially only `B -> A` is active:

```text
B
└── /a -> A

A declares Process Embedded /b, but /b is absent.
```

The external operation directly targets A. A's Handler performs one ordinary
JSON patch that adds B at `/b`, and emits X.

A also has:

```text
Triggered Event Channel X -> onLocalX
Embedded Node Channel /b + Y -> onY
```

B has:

```text
Embedded Node Channel /a + X -> onX
```

The exact fixture is:

```text
conformance/contracts/fixtures/closure/
  c-clo-02-dynamic-finite-cycle.yaml
```

The independent cyclic identity oracle is:

```text
conformance/contracts/oracles/finite-dynamic-a-b.yaml
```

### 2.2 Direct work and dynamic reclassification

The frozen direct delivery is:

```text
work 0: external operation -> A/source
```

A's patch adds the exact admitted B state at `/b`. The graph changes from:

```text
B -> A
```

to:

```text
A <-> B
```

The already completed external work on A is not replayed. The processor:

1. validates the new value and managed occurrence binding;
2. expands the closure when required;
3. recomputes the component partition;
4. recognizes `{A,B}` as one SCC;
5. tentatively finalizes the complete component.

The first temporary exact cyclic identity is:

```text
M1 = 5oub2rWPSHndMagtR42NVVGKPF8QkZcbqb3eJkmXnPa6
A  = M1#0
B  = M1#1
```

Only after this exact finalization is event X created. Application code never
sees an identity-less handle.

### 2.3 A listens to X locally

The queue first delivers X to A's local Triggered Event Channel:

```text
work 1: X -> A/localX
```

A changes `localXSeen` from `0` to `1`. That changes exact content, so the whole
component is finalized again before B handles X:

```text
M2 = CigwiW2HdoYoYnnxyWaTG2AnnrjAT8LFQydzqMMKsXQP
A  = M2#0
B  = M2#1
```

### 2.4 B listens to X and emits Y

B receives the same X occurrence through its `/a` embedded occurrence:

```text
work 2: X -> B/fromA
```

At this point B reads `/a` as the exact value `M2#0`. B changes `xHandled` to
`1` and emits Y. The complete component is finalized a third time:

```text
M3 = 3N9KnGUMrRPk66Csi4m1Q1cort4jBK1CPbJVY6DChVbB
A  = M3#1
B  = M3#0
```

The suffix order changes. This does not retire either occurrence: occurrence
continuity is identified by source `DocumentId`, path, target `DocumentId`, and
activation generation—not by the current suffix.

### 2.5 A listens to Y and finishes

A receives Y through `/b`:

```text
work 3: Y -> A/fromB
```

A reads `/b` as exact `M3#0`, changes `result` to `done`, and emits nothing.
The final temporary identity is:

```text
M4 = 6hewmRNB9pFxdLs2LeLvDNaW6C7qS92kba2QSh67kjTH
A  = M4#0
B  = M4#1
```

The queue is empty. Final validation checks documents, component proof,
occurrence bindings, subscriptions, checkpoints, public event boundaries,
portable limits, and gas. One commit publishes A, B, M4, and every required
containing reference.

### 2.6 Exact gas calculation

The fixture freezes the complete processor-counter trace. For this fixture the
current exact total is:

```text
945 gas
```

The total is not “one budget for A plus another budget for B.” It is one
closure meter. Representative charges include:

```text
processInvocation
closureInvocation
managedDocumentOpened x2
managedOccurrenceBindingVerified
componentMemberPartitioned
closureWorkOccurrenceEnqueued / Dequeued
scopeOpened
contractHeaderRecognized
channelCandidateTested / Accepted
handlerCandidateTested / handlerCall
patchBoundaryChecked / patchAddOrReplace
componentPartitionChanged
tentativeComponentFinalization x4
cyclicMemberFinalized x8
internalEventEnqueued / Dequeued
triggeredEventDelivered
embeddedEventDelivered
checkpointWritten
```

Each charge is admitted before its work. Runtime/BEX ledgers, when used by the
actual Handler runtime, compose into this same shared meter.

The exact trace is in the fixture under:

```yaml
expected:
  totalGas: 945
  gasTrace: ...
  gasTraceIdentity: ...
```

---

## 3. The same event creates a nonterminating loop

The static component starts as:

```text
A <-> B
```

A's direct Handler emits `LOOP`. B receives `LOOP` and emits the exact same
`LOOP` value. A receives it and emits the same value again:

```text
A -> LOOP -> B -> LOOP -> A -> LOOP -> ...
```

Equal event BlueIds do not collapse occurrences. Every emission has a distinct
`EventOccurrenceId` and work ordinal.

The fixtures are:

```text
c-clo-04-same-event-gas-loop.yaml     # small exact test override
c-clo-04-default-policy-loop.yaml     # release default, no authored policy
```

### 3.1 Mandatory default

A document-authored gas policy is optional. The Contracts release default is
not optional. In this release:

```text
maxProcessGas = 100000
```

A host or document may bind a lower exact limit. It may not silently raise the
release maximum, and an embedded member never receives a fresh independent
meter.

### 3.2 Failure behavior

When the next canonical charge cannot be admitted:

```text
status = gas-limit-exceeded
rejected charge is absent from the trace
rejected work does not begin
A and B remain at the input MASTER
no checkpoint commits
no subscription delta commits
no public event commits
```

The short override fixture uses an exact limit of `816` and accepts five work
occurrences before rejecting the next B delivery. The release-default fixture
accepts work until total admitted gas is `99992`, then rejects the next charge.
Retry with identical evidence and policy produces the same trace prefix and
rejected work identity.

A wall-clock timeout may stop a worker operationally, but it never defines the
semantic result.

### 3.3 Root has no local policy; an embedded member has a lower cap

Assume the Contracts 1.0 release default is `100000`, Root authors no lower
policy, and embedded B has an exact local cap of `5000`. The invocation still
has one shared meter:

```text
shared closure allowance = 100000
B local ceiling          = 5000
```

While B executes, a charge is admitted only when it fits both the remaining
shared allowance and B's remaining local allowance. B does not receive a fresh
independent meter. If B's local cap is the first one exceeded, the result is a
deterministic gas failure for the same connected closure:

```text
Root unchanged
B unchanged
all other required members unchanged
checkpoints unchanged
subscriptions unchanged
public events empty
accepted gas trace retained in the failure result
```

If the shared allowance is lower, the shared cap fails first. A tie uses the
shared-cap precedence defined by the specification. See
`c-clo-26-embedded-local-cap.yaml`.

Disconnected affected closures are separate processor invocations. A gas
failure in one disconnected closure does not roll back a successful invocation
for another disconnected Root.

---

## 4. A is authoritative at epoch 10; B attaches A at epoch 5

This case distinguishes stable lineage from immutable state:

```text
DocumentId A
  epoch 5  -> A5
  epoch 6  -> A6
  ...
  epoch 10 -> A10 (authoritative current state)

B tries to add /a -> A5
```

Contracts must not:

```text
downgrade authoritative A to A5
silently fork a second A lineage
publish B as current while it still references stale A5
```

### 4.1 Missing evidence

`c-clo-22-a10-attach-a5-needs-resources.yaml` supplies the intended exact A5
binding, but the already-named A5 node is unavailable from the selected exact-
node provider. The attempt returns:

```text
NeedsResources
```

No semantic gas or state commits. The retry changes provider availability only;
the normative closure and invocation identity remain byte-for-byte unchanged.

### 4.2 Provider-only retry and one-step managed revisions

`c-clo-23-00-attach-a5-retry.yaml` retries that same invocation with exact A5
available. It commits B's inactive historical reference at cursor 5; it does
not discover or replay a transition range.

Coordination then constructs five separate, sequential invocations:

```text
c-clo-23-01-a5-to-a6.yaml
c-clo-23-02-a6-to-a7.yaml
c-clo-23-03-a7-to-a8.yaml
c-clo-23-04-a8-to-a9.yaml
c-clo-23-05-a9-to-a10.yaml
```

Each file is one exact contiguous `ManagedRevisionCause`, with one processor
invocation, gas/failure boundary, result, CAS commit, and durable cursor advance.
Only after one result commits may Coordination construct the next cause from
the new committed snapshot. Later live work cannot overtake the non-null
historical cursor. If final activation closes a cycle, exact re-finalization
occurs at that last invocation's identity-changing boundary before caused work
can observe it.

The final exact component master in the fixture is:

```text
B4s6BMi4HbXS48DC1GTuozEfbSdbBepRnpP5TrsJTdkE
```

Contracts does not discover Timeline history. The coordination/feeder layer
selects and proves one next transition only; Contracts verifies and applies
that one supplied revision. No invocation accepts a transition array or commits
an intermediate batch.

---

## 5. One external entry directly targets both cyclic members

`c-clo-05-direct-both-members.yaml` freezes two direct deliveries. The total
order is not admission order, map order, thread order, or current BlueId order.
It is the exact tuple defined by the specification, starting with stable
`DocumentId` and complete managed scope occurrence identity.

The first direct seed and all caused work reach quiescence before the next
seed begins.

---

## 6. Dynamic cycle during initialization

`ADMIT_CLOSURE` is a first-class operation. It does not fabricate a Timeline
Entry or provider timestamp. A newly admitted A may initialize while B already
contains A; A's initialization Handler may add B and close the cycle. The
processor preserves completed initialization work, reclassifies the graph,
continues with one shared gas meter, and publishes no member or marker early.

See:

```text
c-clo-08-cycle-during-initialization.yaml
c-clo-21-admission-zero-direct.yaml
```

---

## 7. Merge, split, and frozen edges

The final result is a closure result, not one `masterBlueId` field:

```text
{A,B} + {C,D} -> {A,B,C,D}
{A,B,C,D} -> {A,B} + {C,D}
{A,B} -> A + B
```

Every final component, ordinary member, cyclic master, containing document,
occurrence binding, and graph generation is explicit.

An already-created event/update freezes its target occurrences. Removing an
edge does not cancel that occurrence. Adding an edge does not retroactively add
a target. Remove and later re-add starts a new activation generation.

See fixtures C-CLO-09 through C-CLO-13.

---

## 8. Public event boundary

A and B can exchange internal events under an outer public Root. Those events
remain internal. They enter `publicEvents` only when the outer declared public
Root explicitly emits a Root event. A deterministic late failure in the outer
Root rolls the complete closure back.

See:

```text
c-clo-19-public-event-boundary.yaml
c-clo-20-late-outer-failure.yaml
```

---

## 9. Large graphs and limits

The release allows large overall graphs but binds finite semantic limits. The
fixtures contain real exact ring components:

```text
128 members -> accepted
129 members -> CyclicComponentMemberLimitExceeded
```

A two-member cycle among one thousand unrelated documents must open only the
two members and required containing spine. The engine must know graph metadata;
it must not materialize unrelated document bodies.

---

## 10. Why no authority/delegation logic appears here

The Contracts processor receives one original external event plus exact feeder
evidence. External eligibility and delegated authority are feeder concerns.
A higher coordination layer can resolve an authority document at the event's
source order and either include or exclude a direct logical delivery. The
Contracts algorithm needs no authority-specific type, status, or special input.
