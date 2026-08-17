# Codex Prompt 02 — Integrate Blue Contracts 1.0 in `../blue-contract-java`

Integrate the final Blue Contracts and Processor Specification 1.0 affected-closure/cyclic processing implementation into the current Coordination engine.

Run this prompt only from the sibling filesystem checkout
`../blue-contract-java`. That checkout's canonical Gradle project, remote, and
artifact name is `blue-coordination-java`; there is no required sibling path
named `../blue-coordination-java`. Production code is Java 17 and the release
test lanes are Java 17 and Java 21.

This prompt does **not** define or generate a public Coordination profile document. It implements the engine behavior needed to exercise the Contracts 1.0 semantics in the existing closed-world in-memory environment, while keeping the architecture suitable for a later durable MyOS adapter.

Use the exact `blue-contracts-core` artifact produced by Prompt 01. Do not duplicate the cyclic BlueId algorithm, Contracts queue, gas ledger, or Handler semantics in Coordination.

Do not begin feature work through an implicit local source substitution. First
stage or publish the clean artifact produced by Prompt 01 under an explicit
non-SNAPSHOT candidate version (the Language build requires
`-PreleaseVersion=<next-non-SNAPSHOT-candidate>`), record its coordinate and JAR
SHA-256, then update every pinned Contracts coordinate, dependency lock,
dependency-preflight check, consumer POM assertion, and published-artifact
lockfile in this checkout. Add a distinct `blueLanguagePublishedRepository`
pointing at that staged repository and restrict it exclusively to group
`blue.language`; the existing `bluePublishedRepository` is exclusive to the
Repository/BEX groups and cannot resolve this artifact. Do not broaden or
replace that existing filter accidentally. An explicit development-only
included build may be added, but it is not release evidence.

## Core invariant

```text
one exact Timeline Entry is appended once
    -> environment derives direct managed-document targets
    -> freeze one exact affected-closure frame
    -> Contracts processes the complete connected required closure
    -> all state caused by that entry publishes atomically for that closure
    -> no member/containing document is exposed ahead of another required member
    -> only then may a later entry overtake it
```

`Process Embedded.paths` and `collectionPaths` remain the only authored dependency graph. Do not add AutonomousLink, observer/coherent modes, a second graph or caller-supplied target list.

The Contracts 1.0 affected-closure API consumed here is Root-scoped. Freeze
only direct deliveries at `scopePath = /`, activation generation `0`; target
every closure WorkOccurrence at the corresponding Root managed-scope identity;
and accept only Root ChannelOccurrence, subscription-delta, checkpoint-receipt,
and scoped gas evidence. Public projection additionally proves that the
emitting work targeted a declared public Root's Root scope. Fail closed before
semantic work on any non-Root closure address. This does not change ordinary
`PROCESS` nested-scope behavior, and Coordination MUST NOT infer a wider
closure profile from the general identity-constructor shapes or from an
embedded pure reference.

## Explicitly out of scope

```text
Mandate eligibility and onBehalfOf resolution
public Coordination profile documentation
database/S3 implementation
parallel processing
distributed transactions
BEX changes
core type changes
```

Authority-bearing entries not supported by the current engine must continue to fail closed. A later Mandate-aware feeder will resolve them before Contracts; no Contracts change will be required.

## Phase 0 — baseline and artifact lock

Before code changes:

1. Record exact Coordination commit/dirty state.
2. Record exact Language/Contracts artifact coordinate and SHA-256.
3. Run all current unit, integration, scenario and consumer tests on Java 17 and Java 21.
4. Capture current outputs for:
   - Counter;
   - nested Root -> A1 -> A11 same-entry ordering;
   - five occurrences / three unique documents;
   - NBA;
   - Wadowice;
   - initialization;
   - historical epoch-4/epoch-7 admission;
   - failure/retry;
   - locality counters.
5. Write tests for dynamic A<->B formation, exact finite reaction,
   default-policy loop, merge/split and A10/A5 reconciliation. Keep a red test
   local until the first implementation that makes it pass can be included in
   the same green commit.

Do not proceed from a red baseline unrelated to this feature.

Characterize `blue.coordination.api.DocumentId` before reusing it. It must
require input already in NFC and reject rather than silently normalize non-NFC
text, reject empty text and NUL, accept nonempty whitespace, enforce the 512-byte
UTF-8 ceiling, remain case-sensitive, and compare by Unicode scalar sequence—or
use an exact lossless adapter to the Contracts-owned value. Apply the same
reject-before-construction rule to every Contracts portable-order token listed
in §4.7; arbitrary Blue payload Text and RFC 8785/BlueId serialization remain
unchanged.

The current checkout is exactly at its production-line and public-API ceilings
and already uses 109 of the permitted 115 production classes. The suggested
decomposition below therefore cannot fit in the six remaining class slots as
written. Do not minify or hide closure code to pass `validateProductionShape`.
Refactor/retire obsolete one-document machinery and, where the real feature
still requires larger line, API, or class budgets, revise each budget and its
new-candidate release evidence deliberately. Update `CONTRIBUTING.md` clauses
that assume one-document atomicity or immutable Process Embedded topology.

## Phase 1 — replace DAG-only graph snapshot with deterministic component index

Inspect current graph code, including:

```text
blue/coordination/internal/ProcessEmbeddedGraphSnapshot.java
blue/coordination/internal/EmbeddedOnlyLayout.java
blue/coordination/internal/EmbeddedOnlyLayoutBuilder.java
blue/coordination/internal/SequentialDrainCoordinator.java
```

Replace unconditional `validateAcyclic()` and recursive child-first closure assumptions with an immutable graph snapshot containing:

```java
record ManagedGraphSnapshot(
        long graphGeneration,
        Map<DocumentId, ManagedDocumentVertex> vertices,
        List<ManagedOccurrenceBinding> occurrences,
        ComponentIndex components,
        String identity) {
}

record ComponentIndex(
        Map<DocumentId, ComponentId> componentByDocument,
        Map<ComponentId, ProcessingComponent> components,
        List<ComponentId> deepestFirstCondensationOrder,
        String identity) {
}
```

Use a deterministic Tarjan/Kosaraju implementation. The SCC algorithm is internal only; documents still author ordinary `Process Embedded` content.

Canonical order must follow the Contracts 1.0 tuples. It must not depend on map order, admission order, current BlueId, thread scheduling or cache state.

Keep a cheap singleton-acyclic fast path.

## Phase 2 — make managed occurrence identity explicit and exact

The graph resolver must derive active edges from both:

```text
exact value at the declared Process Embedded path
verified managed occurrence binding
```

Add/strengthen immutable values equivalent to:

```java
record ManagedOccurrenceBinding(
        String occurrenceIdentity,
        String bindingIdentity,
        String bindingPolicyIdentity,
        DocumentId sourceDocumentId,
        String sourcePath,
        long activationGeneration,
        DocumentId targetDocumentId,
        String expectedTargetBlueId,
        boolean active,
        Long pendingHistoricalEpoch) {
}
```

Rules:

- `occurrenceIdentity` excludes the expected target BlueId and is stable lineage evidence;
- `bindingIdentity` includes the expected target BlueId and changes with exact state;
- `pendingHistoricalEpoch` is always present as a nullable safe integer and
  binds an admitted historical catch-up cursor when non-null;
- inactive prospective rows remain in the authoritative binding set but are
  not graph/SCC edges;
- when exact authored content activates a prospective row, update that same
  identity-bound input row in place instead of consulting a hidden staging map;
- every prospective row is supplied in the invocation input except the exact
  inactive successor derived when an active row retires; that successor keeps
  source path, target lineage, and policy, advances generation exactly once,
  and receives fresh occurrence/binding identities without target acquisition;
  it is output-only until committed and supplied as a later invocation input;
- a pending-null prospective path may be absent; a row with a present historical
  cursor value remains inactive until final managed-revision reconciliation;
- exact pure references, verified inline acyclic values, and verified
  materialized cyclic members have parity; mixed `blueId` objects are invalid;
- same source/path/target with cyclic BlueId churn retains the same activation generation;
- changing only `MASTER#index` due to component re-finalization does not retire/re-add the occurrence;
- path removal retires it and immediately allocates its inactive successor at
  the previous activation generation plus one with fresh identities;
- later-invocation re-add preserves that committed successor's generation and
  occurrence identity; same-invocation remove-then-re-add is unsupported;
- retargeting an active or reserved path to another DocumentId is unsupported
  in Contracts 1.0 and fails before mutation;
- two DocumentIds may point to identical Blue content without merging lineages;
- one DocumentId may have multiple path occurrences.

Root managed-scope generation is `0`, first embedded reservation/activation is
`1`, and active removal allocates its successor at exactly the previous
generation plus one. The successor may activate only from a later invocation
input. Same-invocation remove-then-re-add and different-lineage retarget are
unsupported. Later re-add of the same lineage and same-lineage BlueId churn
retain the allocated generation.

Never infer logical identity from BlueId alone.

## Phase 3 — freeze an entry-level affected-closure frame

Current append remains storage-only:

```java
TimelineAppendReceipt appendTimelineEntry(Node exactEntry);
```

Drain/dispatch must derive direct targets from the current verified subscription index. The caller must not provide targets.

For each canonical external entry, construct one or more independent connected
closure invocation inputs. Each input owns a state-only
`AffectedClosureSnapshot` plus all invocation adjuncts and binds:

```text
original Timeline Entry BlueId and exact object
canonical external order
frozen route/direct-delivery snapshot identity
current graph generation
all directly targeted managed scopes
all required Process Embedded closure members and containing documents
current exact documents/BlueIds/epochs
the complete authoritative active+inactive managed occurrence-binding set,
including nullable historical catch-up cursors
`occurrenceBindingSetIdentity` recomputed over that complete set, including
every row's `active` and nullable `pendingHistoricalEpoch` fields
state-only `closureIdentity` recomputed over graph generation, exact document
records, occurrence-binding-set identity, component states and public Root set
execution policy identity
Contracts/Language/repository/runtime identities
```

Cause and direct-delivery snapshot identity are not fields of durable
`closureIdentity`; `invocationIdentity` binds them directly. Construct one
closed invocation input and pass it once. Do not pass a second event, cause,
route snapshot, execution policy, or environment alongside it. The external
cause's order-policy identity must equal the policy selected in that input's
environment.

The atomic closure should contain direct targets plus the exact required dependency/containing closure, not the entire weakly connected environment. Do not scan or lock incoming-only unrelated documents.

When two direct target closures are disconnected, they may be separate closure invocations/commits under one append receipt. A gas failure in one disconnected closure must not roll back the other.

## Phase 4 — call `PROCESS_CLOSURE`, not one independent child commit followed by parent catch-up

Replace the current same-entry path that can commit a child revision before all required containing applications.

For one selected external entry:

```text
build one state-only AffectedClosureSnapshot
wrap it and all cause/route/resource/policy/environment adjuncts in one
ClosureInvocationInput
call Contracts PROCESS_CLOSURE once with that closed input
receive one ClosureAttemptResult
if NeedsResources: record the exact request and publish no result/block/commit
if Complete: inspect the ClosureProcessResult
verify the commit companion only for its committing success branch
publish the complete successful closure atomically
```

Do not turn `NeedsResources` into a completed status, blocked entry, empty
result, retryable exception, or partial commit. Discard the attempt, obtain and
verify the exact requested BlueIds, and invoke Contracts again from the exact
input closure and cause. Never resume a suffix, continuation, queue, or
tentative state from the resource-missing attempt. Provider availability is
host/harness evidence, not normative input: changing only availability of an
already requested exact node preserves both `inputClosureIdentity` and
`invocationIdentity`. Any normative input change creates a new invocation.
`NeedsResources` never requests a historical range or performs discovery.

Document/session histories may remain useful storage/index/audit structures, but no normal application read may observe:

```text
child at the new same-entry state
required containing parent still at the old child reference
```

On any Handler, validation, gas, proof or late containing-Root failure:

```text
all documents in that required closure retain their previous authoritative state
all source checkpoints remain unchanged
no public event publishes
no partial component master publishes
```

This replaces current failure-model behavior where a child may survive a required parent failure for the same entry.

Historical synchronization across separate original entries may still span several commits and use readiness barriers. Do not confuse that with same-entry closure atomicity.

## Phase 5 — dynamic cycle formation in one invocation

Implement the flagship scenario using real Blue documents and the Contracts closure processor:

```text
Initial:
    A has declared /b but value absent
    B contains /a -> A

External operation on A:
    ordinary patch adds exact B at /b
    emits X

Resulting graph:
    A <-> B
```

Coordination must not pre-reject this as a graph cycle. It provides the available B state/binding to Contracts, which reclassifies the closure without replaying A's direct operation.

The finite test must prove:

```text
A external source Handler
A local X Handler
B embedded X Handler -> emits Y
A embedded Y Handler -> result done
exact temporary masters match the oracle
one atomic commit
```

The engine must persist/return the actual final cyclic-set proof and member mapping from Contracts. Do not calculate `MASTER#index` in Coordination.

## Phase 6 — same-event infinite loop and gas policy

Use the Contracts release-default gas policy when no engine/workspace/document override exists:

```text
maxProcessGas = 100000
```

Allow an exact host/workspace policy to lower it, with its policy identity bound into the frozen frame and receipt. It may never raise the release maximum.

The entire connected closure uses one Contracts gas meter. Coordination must not give A and B separate limits or reset gas between parent/child applications.

Required test:

```text
A emits LOOP
B receives LOOP and emits the same exact LOOP value
A receives it and emits it again
...
```

Expected:

```text
exact GasLimitExceeded status/diagnostic
same accepted gas trace prefix as fixture
same rejected next WorkOccurrenceId
input graph/master/documents unchanged
no checkpoint/subscription/public event commit
same result on retry
entry becomes deterministic blocked/quarantined under engine policy rather than hot-loop retry
```

Wall-clock timeout may interrupt host work but cannot determine semantic success or gas failure.

The loop fixture is WORK-owned, but the general blocked-entry model must also
represent INVOCATION- and FINALIZATION-owned rejected charges with no fabricated
rejected WorkOccurrence. Persist the complete nullable rejected-charge evidence
and expose a rejected work identity only for the WORK branch.

## Phase 7 — use exact temporary identities from Contracts

Coordination must preserve and store enough evidence to audit temporary finalization, but it must not expose internal host handles as Blue content.

For the finite A/B fixture, assert that:

```text
B's X Handler reads A at exact M2 member identity
A's Y Handler reads B at exact M3 member identity
suffix remapping does not change occurrence lineage
outside containing references are updated to the final member IDs atomically
```

Do not optimize by delaying finalization until queue quiescence.

## Phase 8 — initialization and admission

Top-level document admission and dynamically activated embedded documents must use the Contracts `ADMIT_CLOSURE` path when no external Timeline Entry exists.

Requirements:

```text
no fake Timeline Entry
no fake provider timestamp
one exact admission cause identity
one shared gas meter
newly initialized members/markers publish atomically
initialization Handler may add a reciprocal edge and form a cycle
initialization events can cause cross-member reactions
failure/gas rolls all new state back
```

Use `c-clo-08-cycle-during-initialization.yaml` and `c-clo-21-admission-zero-direct.yaml` as exact acceptance tests.

## Phase 9 — several SCCs, merge and split

The engine must pass complete closure results through to storage when one invocation contains:

```text
several cyclic components
acyclic documents between components
an outer containing Root
```

Support:

```text
{A,B} + {C,D} -> {A,B,C,D}
{A,B,C,D} -> {A,B} + {C,D}
{A,B,C} -> {A,B} + C
self-cycle -> acyclic singleton
```

On edge insertion/removal, recompute SCCs only for the affected induced region when possible. Contracts remains the source of final exact identities/proofs; Coordination owns graph indexes and publication.

The result/store model must not assume one master BlueId after a split.

## Phase 10 — frozen-edge occurrence semantics

When an event/update occurrence is created, its eligible stable occurrence
lineages are frozen. The creation-time binding identity and target BlueId are
revalidation evidence; they do not pin the business-state version observed by
the eventual Handler.

Implement exactly:

```text
edge removed after occurrence creation:
    old occurrence still delivers once to the frozen eligible lineage set

new occurrence after removal:
    does not use removed edge

edge added after occurrence creation:
    old occurrence does not gain target

new occurrence after addition:
    uses new edge

remove/re-add:
    removal allocates the fresh inactive generation/cursor/lineage
    the successor is output-only until committed and supplied as a later input
    that later re-add preserves generation/occurrence identity; ordinary
    exact-state churn may still rebind bindingIdentity
    same-invocation remove-then-re-add is unsupported
```

Do not use current graph lookup at delivery time to add or remove an
already-created work occurrence's eligible lineages. Do resolve the latest
valid binding and tentatively finalized document state for each frozen lineage,
while rejecting a retarget to a different `DocumentId` lineage.

## Phase 11 — A is at epoch 10; B attaches A at epoch 5

Coordination owns source-revision selection and the sequential no-overtake
barrier. Contracts accepts exactly one contiguous `ManagedRevisionCause` per
`PROCESS_CLOSURE`; it never accepts a revision array.

Scenario:

```text
A authoritative lineage:
    epoch 5 A5
    ...
    epoch 10 A10

B attempts to add /a -> A5
```

Rules:

- never downgrade authoritative A to A5;
- never silently fork a second A with the same DocumentId;
- construct C22 with the exact inactive `B:/a -> A5` row in normative input
  and the Handler patch in the separate fixture runtime harness;
- if the exact A5 provider node is unavailable, require precisely
  `NeedsResources([A5])` and no state/gas commit;
- retry with only provider availability changed using identical normative
  `inputClosureIdentity` and `invocationIdentity`;
- commit that external attachment as B -> A5 with `active=false` and
  `pendingHistoricalEpoch=5`, then raise the Coordination no-overtake barrier;
- dispatch five separate `ManagedRevisionCause` invocations A5->A6 through
  A9->A10, waiting for each terminal commit before constructing/dispatching the
  next; each cause binds `originalSourceCauseIdentity` and a closed
  `sourceRevisionReceiptIdentity`;
- require each invocation to seed one `CONTAINING_REFERENCE_UPDATE` and own its
  gas, failure, CAS and commit independently;
- in the final invocation, after the historical A10 continuation settles,
  explicitly reconcile B's path to the latest authoritative same-lineage A
  BlueId (which may have churned from reciprocal containing updates), clear the
  cursor, activate the edge, repartition and finalize;
- never mark B ready or allow later live/public work to overtake while the
  cursor is non-null;
- retain auditable original source causes/receipts without inventing timestamps.

Implement tests directly from `c-clo-22` and `c-clo-23`.

## Phase 12 — publication and storage transaction

Introduce a storage-neutral closure commit boundary even for the in-memory adapter:

```java
interface ClosureStoreTransaction {
    void verifyExpectedState(ClosureCommitPlan companion);
    void stageDocuments(List<ResultingDocument> documents);
    void stageComponents(List<ResultingComponent> components);
    void stageOccurrences(List<ManagedOccurrenceBinding> occurrences,
                          String occurrenceBindingSetIdentity);
    void stageGraphChanges(List<GraphChange> changes,
                           String graphChangesIdentity);
    void stageCheckpointWrites(List<CheckpointWrite> writes,
                               String checkpointWritesIdentity);
    void stageSubscriptionDeltas(List<SubscriptionDelta> deltas,
                                 String subscriptionDeltasIdentity);
    void stagePublicEvents(List<PublicEventOccurrence> events,
                           String publicEventsIdentity);
    void stageReceipt(ClosureCommitReceipt receipt);
    void commit();
}
```

`verifyExpectedState` must check the companion's input closure identity, input
graph generation, document heads, component
identities/states/generations/nullable masters, and input
occurrence-binding-set identity—not document heads alone. An optimistic adapter
must recheck that complete input atomically at commit; validating a begin-time
copy and later overwriting current state is not compare-and-swap.

The closure identity checked here is state-only. Independently recompute it
from the current persisted graph generation, complete exact document records,
occurrence-binding set, component states and public Root set, then recompute the
output identity from the complete staged state. Do not store the preceding
invocation's cause/direct-delivery-scoped attempt hash as current state and do
not accept a caller-supplied input or output closure digest without this
recomputation.

`stageOccurrences` and commit must not trust a claimed
`occurrenceBindingSetIdentity`. The storage adapter must independently recompute
it from the complete authoritative active+inactive rows, including every row's
`active` and nullable `pendingHistoricalEpoch` fields, and reject the transaction
when the recomputed identity differs from the claimed value.

Every staged public event must carry both the contiguous public projection
ordinal and its required invocation-global `eventOccurrenceOrdinal`; recompute
`publicEventsIdentity` over both.

One commit must atomically publish:

```text
all resulting managed document heads/epochs in the closure
all cyclic proofs/member mappings
all containing Root references
component and graph generations
occurrence binding delta
checkpoints
subscription delta
public outbox
entry progress/receipt
```

Immutable exact nodes/proofs may be written before this transaction; unreferenced immutable objects are harmless after rollback.

Add crash/failure injection at every boundary:

```text
before immutable write
after immutable write before CAS
after first staged document
after component proof stage
after subscription stage
before commit
after commit before response/receipt publication
```

Retry after a proven committed transaction must reconcile without rerunning Contracts.

## Phase 13 — readiness and historical entry processing

Same-entry cyclic closure work must complete in the one closure invocation.

Historical attachment/catch-up remains feeder/orchestration work over several
independently committed one-revision invocations. Keep:

```text
source order distinct from document application order
one authenticated source revision receipt per ManagedRevisionCause
attachment/admission cause
READY only when all required original entries and nested admissions through frontier are complete
```

A member at epoch 5 attached to an authoritative epoch-10 lineage is not
ordinary "event replay" and not a hidden Contracts loop. Coordination supplies
one exact cause only after the preceding commit, and keeps later live work
behind the barrier until the final cursor-clearing activation commits.

Do not invent timestamps for initialization, embedded reactions or historical application.

## Phase 14 — remove lossy semantic projections and duplicate host mutation

A parent/containing document must contain exact child/member values, normally by verified BlueId reference. Do not strip type/contracts/identity to prevent duplicate processing.

Execution isolation comes from the Contracts closure schedule and managed occurrence target, not from changing `$document` content.

Delete/retire paths equivalent to:

```text
managedOwnershipProjection(...)
lossy processingRoot
Java direct parent child replacement bypassing Contracts
independently committed same-entry child then parent cursor catch-up
```

All containing updates must be part of the exact Contracts closure result.

## Phase 15 — current class decomposition

Do not grow `SequentialDrainCoordinator` into a larger monolith. Extract focused components after characterization, such as:

```text
ManagedGraphProjector
ComponentIndexBuilder
AffectedClosurePlanner
ExternalEntryClosureExecutor
AdmissionClosureExecutor
ClosureCommitCoordinator
ClosureCommitReconciler
ManagedRevisionCatchUpBarrier
BlockedEntryRegistry
CoordinationExecutionPolicyResolver
```

Use the Coordination integration shapes from this package as value-type references:

```text
java-templates/coordination/src/main/java/
```

Do not copy Contracts orchestration into Coordination. Coordination plans evidence and publishes; Contracts owns semantic execution.
The template mains are dependency-free shape smokes only. Preserve their
`*_TEMPLATE_SHAPE_SMOKE_OK` labels and never treat a successful template main
as fixture execution or implementation conformance.

## Phase 16 — conformance and regression campaign

Create an adapter that runs every closure fixture through the real Coordination environment where applicable.

Consume fixture-only `runtime`, `sharedLimitSource`, `provider`, `locality`,
`limit`, and oracle-stage routing exclusively from the closed top-level harness
fields. Do not add any of them to Contracts production input/result APIs.

Mandatory exact tests:

```text
all closure fixtures declared by the generated Contracts manifest (derive the count)
finite dynamic A/B
exact temporary identity visibility
default-policy and lowered-policy loop
one entry directly targets both members
duplicate equal event occurrences
self-cycle
cycle during initialization
merge/split variants
frozen edge addition/removal
invalid proof/ambiguity
128/129 member limits
1000 unrelated locality
Root-only public event boundary
Root-only affected-closure direct/work/subscription/checkpoint/gas rejection
late outer failure rollback
A10/A5 exact-node retry and five one-step managed-revision causes
occurrence continuity under suffix remap
local member cap
multiple SCCs in one closure
mixed result shape
active edge path validation
invocation- and finalization-owned gas rejection
checkpoint source replacement followed by orphan cleanup (C-CLO-33)
real two-invocation removal/re-add integration: commit the inactive successor,
then supply it as the next invocation input and activate it without changing
its generation or occurrence identity; reject same-invocation remove-then-re-add
```

Preserve existing tests:

```text
Counter
paths and collectionPaths
nested same-entry child-before-parent
five occurrences / three documents
initialization multiplicity
NBA
Wadowice
bounded drain
append idempotency
readiness/audit
Java 17/21 consumer artifact
```

Where an existing test asserts child commit survives a required parent failure for the same entry, update it to the final Contracts 1.0 closure-atomic rule. Historical entries that were already separately committed before a later attachment remain valid evidence; do not conflate the cases.

## Phase 17 — performance and locality

Hard structural gates:

```text
one Timeline Entry stored once
zero request fragmentation
zero Timeline Entry fragmentation
zero ordinary-node fragmentation
zero full-environment scans
zero unrelated document loads
zero source replay per containing occurrence
zero duplicate Contracts execution for one shared exact transition/context
zero host-side direct semantic mutation
one shared gas meter per closure
```

For a two-document SCC among 1,000 unrelated documents, load only:

```text
A
B
selected Channel/Handler bodies
required outer containing ancestor spine
exact cyclic proof/provider data
```

Do not promise locality inside a genuinely 128-member cyclic component beyond the frozen cyclic identity/proof algorithm; all members in that exact identity unit may be required.

Measure separately:

```text
append
route lookup
closure planning/provider loads
Contracts frozen processing
cyclic finalization/proof verification
commit publication
end-to-end
```

No millisecond target may justify weaker exactness. Use structural counters as hard gates and same-machine A/B timings as regression evidence.

## Mandate compatibility statement

Do not implement Mandates in this round.

Confirm in the final report:

```text
Blue Language can represent Mandate documents as ordinary exact typed nodes.
Blue Contracts can initialize/process their registered Channels, workflows,
events, checkpoints and lifecycle like any other document.
A later Coordination feeder will resolve exact historical Mandate authority and
construct eligible direct deliveries before Contracts.
No Language/Contracts semantic change is required for that later feature.
```

Unsupported current `onBehalfOf`/authority evidence must fail closed before Contracts processing.

## Required deliverables

Produce:

```text
build/reports/coordination-contracts-1.0-closure.json
build/reports/coordination-contracts-1.0-closure.md
```

Report:

- exact source and dependency commits/hashes;
- files/classes changed;
- all test counts and Java lanes;
- closure fixture matrix;
- exact finite and loop traces;
- exact A10/A5 result;
- publication/failure injection matrix;
- structural locality counters;
- previous/candidate performance spans;
- remaining unsupported features.

## Stop conditions

Stop rather than patch around the spec when:

- Contracts implementation does not yet pass its closure fixtures;
- a Coordination shortcut would produce a different Root, event list, gas trace, proof or failure;
- a cycle would require a second authored graph;
- a host object would become application-visible content;
- a Mandate-specific rule appears necessary inside Contracts;
- one required closure cannot be committed atomically by the selected adapter;
- exact provider/revision evidence is unavailable.

## Commit and release discipline

Preserve unrelated user changes. Make one small semantic unit per commit, run
focused tests before each commit, and never amend or squash earlier functional
commits. Every committed revision must be green: keep a deliberately failing
test uncommitted until its first passing implementation can be committed with
it. Follow the repository's existing concise style, for example:

```text
build: upgrade closure contracts runtime
test(coordination): characterize affected closure processing
fix(coordination): normalize managed document identities
feat(coordination): index processing components
feat(coordination): bind managed graph occurrences
feat(coordination): execute affected closures atomically
feat(coordination): admit managed closures
fix(coordination): preserve frozen occurrence targets
feat(coordination): commit complete closure results
test(coordination): execute closure conformance fixtures
```

Use ordinary test/integration/consumer/scenario gates while implementing. The
current `stageRelease` is bound to the previous candidate's fixed evidence and
must run only after introducing or generalizing evidence for the next candidate;
never rewrite an older release report to claim these results.
