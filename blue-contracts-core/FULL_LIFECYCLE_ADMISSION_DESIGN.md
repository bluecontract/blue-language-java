# Full lifecycle admission design

Date: 2026-08-23

## Scope and frozen bases

This phase starts from Blue Language / Contracts commit
`5c4e5c88fa75d6cbc52b2e8772f14f2ac5246f52` and Coordination commit
`3ed3818d4f30b6022e7bc57888cd396a02abdc3a`.

The Blue value model, BlueId and cyclic identity algorithms, canonicalization,
reference equivalence, BEX, Repository types, graph identity constructors,
checkpoint semantics, and gas schedule are frozen. This design changes only
Contracts processor orchestration and, after the Contracts gates pass, the
minimal Coordination admission adapter.

The phase explicitly does not implement existing-lineage attachment, managed
history import or replay, external Timeline injection, dynamic resolution of
unknown managed occurrences, Mandates, or contract-surface mutation.

## Clean baseline

The baseline was run with:

```text
PATH=/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin
```

Results before implementation:

- `blue-contracts-core`: 230 tests passed, 0 failed, 0 errored, 0 skipped.
- release conformance: 153/153 Language and 234/234 Contracts fixtures passed.
- semantic API migration: passed with the approved migration ledger.
- Coordination `clean releaseCheck`: passed at `3ed3818`.

## Current execution paths

### Bounded admission

`BlueClosureContracts.admitClosure` currently delegates to
`DefaultClosureProcessor.admitClosure`, which constructs
`ClosureAdmissionExecutionSession`.

That session:

- verifies `ADMIT_CLOSURE`, `AdmissionCause`, zero direct deliveries, the
  supplied graph/finalization, portable limits, and one shared gas ledger;
- plans initialization statically in target-before-source component order;
- runs isolated initialization/lifecycle steps;
- finalizes patches and writes initialized markers atomically; and
- returns the existing `ClosureAttemptResult` and commit companion.

It deliberately has no causal work queue or event queue. Its continuation
rejects three initialization effects:

```text
INITIALIZATION_DOCUMENT_UPDATE_QUEUE_REQUIRED
INITIALIZATION_EVENT_QUEUE_REQUIRED
INITIALIZATION_TERMINATION_REQUIRED
```

Those callback failures currently pass through the ordinary document runtime
and surface as atomic `RUNTIME_FATAL` rollback results. The complete input
closure remains authoritative and no checkpoint or public event is committed.

The bounded lane has released gas/trace behavior and remains useful for focused
low-level tests. It must not be silently removed or semantically changed.

### Normal closure processing

`DefaultClosureProcessor.processClosure` constructs
`ClosureExecutionSession`. That session already owns the required normal
Contracts mechanics:

- one invocation gas ledger;
- a FIFO work queue with unique work ordinals and identities;
- a FIFO application-event occurrence queue;
- synchronous Document Update continuation;
- canonical route classification and accept-before-deliver behavior;
- frozen containing-occurrence snapshots for an emitted event;
- public-Root-only public event projection;
- component repartition and tentative finalization;
- dynamic initialization batches and initialized-marker barriers;
- exact result, rollback, gas, trace, and commit-companion assembly.

It currently accepts only normal processing causes. It initializes only newly
activated documents with an active incoming binding, may settle a Timeline
checkpoint, and rejects termination through `LIFECYCLE_TERMINATION_REQUIRED`.
It therefore cannot be substituted unchanged for admission.

## Public API distinction

The existing `admitClosure(ClosureInvocationInput)` entry point remains the
bounded compatibility lane. Its released behavior is not silently changed for
existing callers.

The additive
`admitClosureWithLifecycleQueue(ClosureInvocationInput)` entry point selects
the full lifecycle lane. Both Java entry points carry the same normative
`ADMIT_CLOSURE` protocol operation and existing `admit-closure` invocation
identity domain; the distinction selects processor orchestration, not a new
model operation or identity domain.

No Timeline Entry, provider timestamp, source cursor, or checkpoint is added to
the admission input or result.

## Full lifecycle admission mode

The implementation will reuse `ClosureExecutionSession`; it will not copy its
queue into `ClosureAdmissionExecutionSession` and will not create a second
admission event engine.

The session gains an explicit execution mode derived from the verified
invocation:

- normal processing mode retains current behavior;
- admission mode requires `ADMIT_CLOSURE`, `AdmissionCause`, and zero direct
  deliveries;
- admission mode runs admission portable limits and initial finalization
  verification before executing work;
- admission mode never creates or advances a checkpoint; and
- admission mode seeds initialization instead of external deliveries.

The existing bounded session stays behavior-compatible behind the explicit
bounded method.

### Admission initialization seed

Every uninitialized managed document already present in the immutable input is
seeded exactly once. The seed cause identity is
`AdmissionCause.causeIdentity`.

Documents are seeded in canonical target-before-source component order and
canonical DocumentId order within a component. Input map/list insertion order
cannot affect the plan.

Admission does not use the normal process-only predicate that requires an
active incoming binding: an unreferenced admitted public Root must initialize.
Already initialized documents are not initialized again.

Initialization work, lifecycle deliveries, updates, events, and finalization
all consume the same invocation gas ledger and existing work/event identity
constructors.

### Patches and Document Updates

An initialization patch is applied under the existing protected processor
state rules. The session then uses the normal `afterPatch` path:

1. finalize the tentative affected closure;
2. activate only already-declared prospective occurrence rows whose targets
   are present in the immutable input;
3. synchronize exact managed references;
4. classify authored Document Updates against the latest finalized body;
5. derive finalization-caused containing-reference updates; and
6. accept all matching routes before draining their immediate canonical FIFO.

Direct initialized/terminated marker writes remain non-notifying.

### Application events

Initialization events use the existing event occurrence queue and identity
constructor. Equal event values emitted twice receive distinct global event
occurrence ordinals and identities. Every delivery of one emission retains the
same occurrence identity.

Events emitted while handling an event wait behind the current event's
accepted delivery batch. Triggered routes use the latest source state;
embedded/containing routes use the frozen canonical occurrence snapshot taken
when the event was emitted.

Only an event emitted by a public Root under the existing public-output rule is
added to `ClosureProcessResult.publicEvents()`. A non-public member's event can
cause internal or containing reactions without becoming public automatically.

### Initialization marker barrier

Initialized markers retain the frozen pre-initialization BlueId in their
`document` reference. A component's marker batch is written only after all
initialization, lifecycle, Document Update, and event work caused for that
component is quiescent. Marker writes and their finalization remain tentative
until the complete admission commits.

### Termination

Termination is implemented in the closure orchestrator using the existing
lifecycle work kind, Root-scope rules, terminated marker type, shared gas
charges, and result fields. It does not call the ordinary runtime's independent
termination queue.

The session tracks pending, terminating, and terminated documents:

1. the first valid request starts termination; repeated requests are
   deterministic and do not duplicate work;
2. eligible terminated-lifecycle handlers are accepted in canonical order;
3. their patches/events run through the same closure queues;
4. terminating or terminated targets are excluded where existing lifecycle
   rules require;
5. after causal quiescence, `contracts/terminated` is written directly once;
6. the closure is finalized under the closed
   `{ kind: TERMINATION_MARKER, afterWorkOrdinal: N }` receipt boundary, where
   `N` is the last accepted causal work completed before the marker write;
7. the snapshot's terminated flag becomes true;
8. no marker, public event, graph change, or document state is authoritative
   until the complete admission commits.

`TERMINATION_MARKER` is a Contracts implementation-evidence branch only. It
does not enter `ClosureProcessResult`, `ClosureCommitCompanion`, an invocation
identity constructor, checkpoint state, or the gas schedule. It reuses the
existing finalization counter, weights, component identity/generation owner,
and rollback behavior. It cannot be represented as `WORK` because the Direct
Write follows causal quiescence, or as `INITIALIZATION_BATCH` because that
branch is reserved for initialized-marker installation and may not occur for a
member cut off by termination.

If existing Contracts ordering does not determine one of these steps, the
phase stops instead of inventing an order.

## Closed graph boundary

The lifecycle lane may activate only managed documents and occurrence rows
already represented in the immutable admission input. If initialization
creates an effective `Process Embedded` occurrence whose lineage is unknown to
that input, the attempt remains noncommitting with an explicit capability or
resource boundary.

The implementation must not query MyOS, fabricate a child, mutate after commit,
or add the later dynamic graph-expansion handshake.

## Gas, failure, and retry

All work uses the one admission gas ledger and the existing weights and limits.
The full path retains admission-specific verification/charging that is not
present in the normal processing seed, including initial component edge and
cyclic proof/finalization evidence.

Gas exhaustion, portable-limit rejection, invalid evidence, runtime failure,
or unsupported unknown graph expansion produces the existing atomic rollback:

- literal input documents and graph remain authoritative;
- no checkpoint is written;
- public events and committed sequences are empty;
- no marker or tentative component is published; and
- no commit companion is present.

Retrying identical input must reproduce status, diagnostic, accepted trace
prefix, rejected next charge/work where applicable, and invocation identity.

## Specification delta

The Contracts 1.0 operation, queue, initialization, event, Document Update,
termination, gas, rollback, result, commit, and conformance sections will state
coherently that:

- normative `ADMIT_CLOSURE` uses the normal Contracts lifecycle/work/event
  queue and may have zero direct external deliveries;
- its causality is the exact `AdmissionCause` and it carries no external
  Timeline Entry or timestamp;
- initialization effects are part of the one atomic admission result; and
- a bounded low-level implementation helper is compatibility-only and is not a
  conforming substitute for normative full lifecycle admission.

The canonical specification and released conformance mirror remain
byte-identical. No Blue Language specification text changes.

## Verification plan

Independent Contracts tests cover, at minimum:

1. Root initialization patch.
2. One initialization event and local reaction.
3. Two equal initialization event occurrences.
4. Non-public emitter to containing public Root without public leakage.
5. Public Root initialization event exactly once.
6. Initialization-caused Document Update and handler.
7. Deterministic valid termination.
8. Canonical multi-document initialization independent of input order.
9. Finite cyclic initialization event route.
10. Infinite cyclic route shared-gas rollback.
11. Whole-closure rollback after a later member fails.
12. Inline versus pure-reference parity.
13. Cold versus warm exact-node parity.
14. Retry evidence identity.
15. No Timeline checkpoint.
16. Unknown initialization-created managed occurrence remains noncommitting.
17. Explicit bounded-lane compatibility.

The release corpus adds ten `FL-ADM` semantic vectors represented by thirteen
executable cases, for 153 Language and 247 Contracts fixtures in total. Their
complete exact oracles are generated from identity-free sources by independent
normative executions; unit-test expected values are never copied into them.

Only after all Contracts tests and complete gates pass will Coordination select
the full path and add its seven focused admission tests. MyOS and browser
workflows are not part of this phase.

## Permitted implementation footprint

Production changes are restricted to `blue-contracts-core`, the Contracts
specification and its released mirror, directly related conformance code and
fixtures/manifests, and generated Contracts/API documentation required by those
changes. Coordination later receives only the minimal adapter and its tests.

Any need to change Blue model/core/identity, BEX, cyclic proof verification,
checkpoint semantics, gas weights, MyOS, or dynamic unknown-occurrence
resolution is a stop condition.
