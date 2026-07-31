# Blue Contracts and Processor Specification 1.0

> **Status.** Final Implementation Baseline. The one-root processing architecture, semantic rules, counter ownership, counter names, formulas, and trace ordering are frozen for implementation. Numerical weights, `MAX_PROCESS_GAS`, and portable limits remain provisional until the calibration corpus is approved. Final public publication MUST bind the calibrated gas manifest, this prose, the canonical runtime registry, machine-readable fixtures, and implementation-conformance evidence in one content-addressed release manifest.

> **Scope.** This document defines deterministic processing for one rooted Blue reality: contracts, channels, handlers, embedded scopes, feeder obligations, external-event ordering, initialization, patches, Document Updates, internal events, checkpoints, lifecycle, termination, gas, and atomic commit behavior. Blue content, BlueId, typing, resolution, expansion, collapse, canonicalization, and minimization are defined by **Blue Language Specification 1.0**. Concrete executable runtimes are separate extensions selected by exact runtime-type BlueId; this specification defines only their generic processor boundary.

Blue Language describes reality. Blue Contracts describe how one exact rooted reality becomes another exact rooted reality when something happens.

## Conventions

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, **MAY**, and **OPTIONAL** are normative requirement levels.

Sections marked **normative** define required behavior. Sections marked **informative** explain intent or implementation guidance.

The term **Language** means Blue Language Specification 1.0.

---

## 0. Overview

### 0.1 One root is one reality

Every invocation has one authoritative root document.

```text
Root
├── Customer
├── Payment
├── Delivery
└── Risk Monitor
    └── External Review
```

Declared embedded documents are owned parts of that rooted reality. They may contain their own contracts, channels, lifecycle state, and internal events, but they are not independently committed sessions. A successful transition creates one new Root. Changed embedded nodes and every changed ancestor on their paths receive new Node BlueIds. Unchanged branches retain their existing Node BlueIds.

An independently evolving or shared business object is modeled as another autonomous root connected by references and events. It is not modeled as one mutable embedded occurrence owned simultaneously by several roots.

### 0.2 Processor boundary

The normative operation is:

```text
PROCESS(document, event) -> ProcessResult
```

where:

- `document` is the exact current Root;
- `event` is the exact next external event selected by the managing feeder;
- `ProcessResult.document` is the exact resulting Root;
- `ProcessResult.events` contains only events emitted by the Root scope;
- `ProcessResult.totalGas` is the deterministic logical work admitted by the invocation;
- every tentative effect either commits in the one Root transition or is discarded.

There is no authored target path, `deliveryOccurrence`, child session, Embedded Child Commit, or public effect log in the processing API.

### 0.3 Feeder and processor

The managing feeder connects external time to deterministic processing.

```text
Feeder:
  observes every active external channel declared by Root and embedded scopes;
  maintains a revision-complete incremental subscription index;
  obtains externally ordered entries and source-completeness evidence;
  orders external events deterministically;
  derives the exact channel-occurrence snapshot for the next event;
  makes the selected graph branches and verified nodes available;
  commits Root, Root outbox, subscription delta, and delivery progress atomically.

Processor:
  revalidates the derived occurrence snapshot;
  opens only selected branches and semantically caused branches;
  recognizes every required effective contract type;
  loads only selected executable bodies and demanded data;
  applies deterministic changes and internal reactions;
  returns one new Root and Root's own events.
```

The feeder snapshot is derived execution metadata, not caller-authored Blue content and not a third semantic event field. For one managed-root revision, exact event, runtime registry, and activation state, the canonical snapshot is unique.

### 0.4 Root-only public events

An embedded scope may emit an event that is handled locally and observed by ancestors. It remains internal unless Root explicitly emits an event.

```text
Emb3 emits A
Emb2 observes A and emits B
Root changes state but emits nothing

ProcessResult.events = []
```

If Root emits `C`:

```text
ProcessResult.events = [C]
```

The input event is not automatically an output event. A child event is not automatically a Root event. A Document Update is not automatically a Root event.

### 0.5 Lazy graph processing

A verified pure reference and its materialization identify the same node:

```yaml
x:
  a: 1
  b: 1
```

```yaml
x:
  blueId: <same-x-node>
```

The processor may open one path while siblings remain collapsed. Contract dispatch fields may be visible while executable bodies remain behind BlueId references. A patch rebuilds the changed direct node and its ancestor spine to Root. Physical prefetch is allowed, but unrelated prefetched content MUST NOT become semantic demand, contract discovery, result content, or portable gas.

### 0.6 Core invariants

A conforming implementation MUST preserve all of these invariants:

1. `PROCESS` has exactly two Blue inputs: Root and external event.
2. One invocation has one authoritative Root and at most one new authoritative Root.
3. Embedded scopes are owned state inside Root, not separately committed document sessions.
4. The feeder derives one complete, revision-bound external-delivery snapshot.
5. `PROCESS` never requires a recursive scan of the complete embedded surface.
6. Inline, referenced, expanded, collapsed, warm, cold, batched, and segmented representations produce the same semantic result and portable gas.
7. Every effective contract type in the initial participating closure is recognized before the first mutation; executable bodies remain lazy.
8. Patches use persistent copy-on-write and preserve unchanged children by exact Node BlueId.
9. Internal Document Updates and emitted events may reach ancestors without becoming public Root output.
10. `ProcessResult.events` contains exactly Root emissions, in order and with multiplicity.
11. Checkpoints bind to channel semantic identity and are written only after complete successful delivery.
12. Gas prices deterministic logical work, not cache state, provider bytes, or unchanged transitive content.
13. Runtime semantics are selected by exact runtime-type BlueId; no document-level version field is required.
14. Deterministic failure, gas exhaustion, or transient resource suspension before commit leaves the old Root authoritative and publishes no events.
15. A successful new Root is committed only when its changed subscription surface is deterministically indexable.

---

## 1. Scope, Versioning, Registry, and Conformance

### 1.1 Goal

Blue Contracts and Processor 1.0 defines:

- feeder-ordered external events;
- revision-complete subscription discovery;
- branch-local processing inside one Root;
- effective inherited application contracts and direct processor state;
- immutable dispatch snapshots and lazy bodies;
- deterministic channel and handler order;
- persistent mutation to Root;
- immediate Document Update cascades;
- internal event propagation and Root-only output;
- exact checkpoint and lifecycle behavior;
- one shared gas budget and canonical counter trace;
- whole-invocation atomicity and revision-bound platform commit.

### 1.2 Out of scope

This specification does not define:

- Blue Language identity or resolution algorithms;
- authentication, signatures, authorization, or mandate eligibility;
- concrete source-provider transport or cryptographic proof formats;
- database schemas, cache layouts, or provider transport;
- user-interface behavior;
- consensus among independent platforms;
- hosted pricing, billing, or service-level policy;
- the implementation of one concrete compute runtime beyond its Contracts boundary.

Concrete external channel and executable runtime types MAY define additional deterministic semantics through exact runtime-type BlueIds. They MUST preserve this specification's one-root, representation, atomicity, output, and gas-boundary rules.

### 1.3 Version selection

This document defines **Blue Contracts and Processor 1.0**, the first public-version Contracts specification.

The first public release begins at 1.0 because internal working drafts did not establish an interoperability or compatibility surface. Implementations MUST treat this specification, its canonical runtime registry, gas manifest, and fixture package as one release unit.

A document does not carry a required `contractsVersion` or `processorVersion`. The managed execution environment selects Contracts 1.0 before processing. Concrete runtime semantics are selected by exact runtime-type BlueId and the separately published specification bound to that type.

After a runtime-type BlueId is published, that exact BlueId MUST never acquire different semantics, dispatch fields, subscription extraction, or gas weights.

A later incompatible change to `PROCESS`, delivery ordering, embedded-scope behavior, event propagation, checkpoints, lifecycle, atomicity, or the core gas schedule requires a new Contracts version.

### 1.4 Runtime registry

The canonical runtime registry is part of the Contracts 1.0 release. For every core or portable runtime type it MUST publish:

- exact canonical Blue node and BlueId;
- runtime role;
- dispatch fields and executable-body fields;
- exact subscription functions for an External Channel;
- checkpoint-domain semantics;
- exact execution semantics or binding to another published specification;
- named runtime counters and weights when executable;
- deterministic limits and error categories;
- conformance fixtures that exercise the type.

Registry source, calculated BlueIds, prose, fixtures, and gas manifest MUST agree. Implementations MUST NOT guess when they conflict.

The implementation-baseline runtime registry package identity is:

```text
sha256:67ce3101449c5bca9e6093b081da239d5d699fdc02182a058d3ad795c6c6120b
```

The machine-readable `blue-contracts/gas/1.0` manifest is normative for counter names, weights, formulas, and portable limits. Its implementation-baseline package identity is:

```text
sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5
```

### 1.5 Conformance

A conforming implementation MUST:

- implement every normative rule in this document;
- use Blue Language 1.0;
- recognize the canonical core runtime BlueIds;
- implement `PROCESS(document, event)` and the platform commit obligations;
- support all processor-managed contracts and events in Appendix A;
- support exact feeder snapshot revalidation;
- produce the canonical named gas trace required by §13 in conformance mode;
- pass every machine-readable Contracts 1.0 fixture;
- report the exact registry, gas-manifest, and fixture-package identities it implements.

A component implementing only the processor library, feeder, node store, or a runtime may describe that component precisely, but MUST NOT claim complete Contracts 1.0 platform conformance unless the combined system satisfies all obligations.

---

## 2. Processing Inputs, Environment, Result, and Atomicity

### 2.1 Processing Document

`document` is an admitted exact Blue node. It MAY be inline, a pure reference, or partially materialized, but its exact Root Node BlueId MUST be established before semantic execution. The logical Root MUST be an object node.

The Processing Document need not be a complete Resolved Form or a closed graph. Contract fields, type contributions, schemas, values, and executable bodies are expanded and resolved on demand.

A higher-level API MAY accept Source syntax and preprocess it before `PROCESS`. That preprocessing is outside the invocation and MUST yield the same admitted Root identity on every conforming platform.

### 2.2 Processing Event

`event` is an admitted exact immutable Blue node. Its exact Node BlueId MUST be established before semantic execution. A higher-level API MAY preprocess Source-event syntax before `PROCESS`.

The event is never rewritten to contain a target path or delivery occurrence. Exact identity, signatures, source-chain links, and checkpoint subjects therefore remain stable.

### 2.3 Processing environment

A managed invocation is evaluated under a fixed environment containing:

```text
Blue Language 1.0 selection
Contracts 1.0 core gas schedule
exact runtime registry and supported runtime BlueIds
verified exact-node provider domain
managed-root session identity and current revision
revision-complete external-channel snapshot and activation intervals
canonical external-delivery plan for this Root revision and event
exact external-order policy identity
exact initial-subscription-frontier policy identity
exact poison-event/quarantine policy identity
exact Language release, Contracts release, runtime-registry, and gas-manifest identities
shared gas limit
```

This environment is not Blue content. It MUST be fixed for the attempt and auditably bound to the managed-root revision.

An implementation MAY pass the canonical delivery plan to an internal processor API. The plan is a derived accelerator. It is conforming only when it equals the unique plan defined by §3. It does not change the two-input semantic operation.

### 2.3.1 Cyclic-member processing boundary

A final cyclic-set member identity `MASTER#index` may appear as an opaque edge inside an ordinary Root or event. It is not independently hash-verifiable and therefore MUST NOT be admitted as the top-level mutable Root or top-level event of `PROCESS`. Those inputs fail before provider demand.

A `Process Embedded` path MUST NOT terminate at or traverse through an opaque cyclic-member edge. Structural access to an ordinary opaque member requires a cyclic-aware provider with complete set proof. Carrying an untouched opaque edge and replacing the whole edge with another admitted exact value remain valid.

### 2.4 ProcessResult

A completed invocation returns:

```text
ProcessResult {
    status
    document
    events
    totalGas
    diagnostic?
}
```

`document` is the exact resulting Root on success. Every noncommitting status returns the exact input Root.

`events` is an out-of-band ordered sequence of exact Blue event nodes emitted by Root during the invocation. It preserves order and multiplicity. The sequence is not itself a Blue List node and has no independent Node BlueId inside `PROCESS`; a platform MAY wrap it in a Blue outbox envelope after processing. It is empty for every noncommitting status.

`totalGas` is the sum of admitted canonical counters. A conformance/debug API MUST be able to expose the exact named trace; an ordinary API MAY omit it.

`diagnostic` is deterministic, non-authoritative explanatory data. It is not part of Root or event identity.

### 2.5 Atomic invocation

All runtime state is tentative until the invocation completes:

- patches and rebuilt nodes;
- processor markers and checkpoints;
- internal queues;
- runtime outputs;
- Root events;
- subscription-delta validation;
- gas trace.

A committing `success` returns the tentative Root and Root events. Every deterministic failure or gas exhaustion discards all tentative state and events and returns the input Root.

Transient acquisition failure does not produce a completed `ProcessResult`; the host suspends the attempt and retries from the exact input Root and event with more verified evidence.

### 2.6 Representation invariance

For graph-equivalent Root and event inputs under the same environment, a conforming implementation MUST return:

- the same status and diagnostic category;
- the same resulting Root Node BlueId;
- the same ordered Root event identities;
- the same exact counter trace and total gas;
- the same semantic provider demands.

Physical fetch count, cache hits, allocation, node batching, and serialized bytes are not portable outputs.

### 2.7 Platform commit

A committing result is installed only through compare-and-swap against the exact Root BlueId and revision from which it was calculated.

The platform transaction MUST atomically persist:

```text
new Root and new revision
Root outbox = ProcessResult.events
validated incremental subscription-index delta
subscription activation and retirement intervals
delivery progress for the external event
```

For a nonmutating terminal result, the platform MUST compare-and-swap delivery progress against the exact unchanged Root BlueId and revision. This prevents a `no-match`, `stale`, or failure decision calculated on an old Root from suppressing an event that a newer Root would handle.

A compare-and-swap conflict commits nothing. It is host contention, not portable Contracts gas; the event is re-derived from the new authoritative revision.

---

## 3. Managing Feeder, Subscriptions, and External Order

### 3.1 Feeder responsibility

The managing feeder MUST:

- derive the active external subscription surface from Root and transitively declared embedded scopes;
- maintain that surface incrementally for each committed Root revision;
- observe every active source identified by that surface;
- obtain the completeness evidence required by each concrete external-source specification;
- select the chronologically next eligible external event;
- derive and retain the canonical delivery snapshot;
- ensure one event reaches a terminal progress record before a later external event begins;
- keep the subscription index at the authoritative Root revision.

The initial admission of a managed Root MAY inspect its complete declared subscription surface once. Later revisions MUST be updated from changed branches and effective dependencies; a complete recursive scan before every event is nonconforming to the locality objective.

### 3.2 External-channel snapshot

For every active External Channel occurrence, the feeder stores a deterministic snapshot:

```text
ExternalChannelSnapshot {
    scopePath
    channelKey
    orderedSourceContributionNodeBlueIds
    effectiveTypeBlueId
    order
    dispatchHeader
    subscriptionKeys
    checkpointDomainBlueId
    declaredSameScopeChannelDependencies
    sameScopeChannelCatalogIdentity?
}
```

The snapshot is derived from the effective channel contract at one Root revision. It does not require an invented BlueId for a merged effective contract. `orderedSourceContributionNodeBlueIds` records exact ancestor-to-descendant contributions.

The dispatch header contains only the bounded immutable fields registered by that channel type. Executable body fields are not part of the subscription snapshot.

### 3.3 Required external-channel functions

Each portable External Channel runtime type MUST define exact deterministic functions:

```text
CHANNEL_KEYS(snapshot) -> finite ordered set of subscription keys
EVENT_KEYS(event) -> finite ordered set of event keys
PRESELECTS(snapshot, event) -> Boolean
ACCEPTS(snapshot, event, context) -> Boolean
PAYLOAD(snapshot, event, context) -> exact channelized Blue node, when accepted
CHECKPOINT_DOMAIN(snapshot, context) -> exact BlueId
CHECKPOINT_SUBJECT(snapshot, event, payload, context) -> exact node identity
DECLARE_CHANNEL_DEPENDENCIES(snapshot, context) -> exact keys or bounded whole-catalog declaration
HANDLER_CHANNEL_KEY(snapshot, event, payload, context) -> same-scope Channel key
LOGICAL_DELIVERY_KEY(snapshot, event, payload, context) -> deterministic Text
```

The following laws are normative:

1. `ACCEPTS(snapshot, event) => PRESELECTS(snapshot, event)`.
2. `PRESELECTS(snapshot, event) => intersection(CHANNEL_KEYS(snapshot), EVENT_KEYS(event)) is non-empty`.
3. `PRESELECTS`, `ACCEPTS`, and `PAYLOAD` depend only on the immutable snapshot, exact event, registered deterministic semantics, and explicitly demanded event content.
4. They MUST NOT depend on mutable Root fields, initialization effects, cache state, wall-clock time, or ambient I/O.
5. Business-state conditions belong in Handler predicates or workflow logic, not External Channel acceptance.
6. The functions are representation-blind and bounded by the portable limits.

A channel that cannot provide finite subscription keys is not a portable External Channel under Contracts 1.0.

#### 3.3.1 Same-scope Channel dependencies

An External Channel may need immutable headers from another same-scope Channel in order to classify an accepted event. This is a generic Contracts capability; it does not imply that the peer Channel is an external source for the event.

During subscription/header evaluation the runtime MUST declare either:

```text
one or more exact same-scope Channel keys
or
one bounded complete same-scope Channel catalog
```

The retained subscription interval records the declared dependency surface and its exact identity. The complete catalog contains the canonical raw-key membership of the effective `contracts` map and read-only header snapshots for every effective same-scope Contract whose runtime role is External Channel or Processor Channel. It does not include executable bodies.

During event classification the runtime receives a read-only context with exact lookup:

```text
LOOKUP_CHANNEL(rawKey) -> CHANNEL(snapshot) | ABSENT | NON_CHANNEL
```

`ABSENT` is valid only when a declared complete catalog establishes that the raw key is semantically absent. `NON_CHANNEL` establishes that an effective Contract exists at the raw key but its runtime role is not a Channel. A lookup outside the declared dependency surface, unavailable evidence, changed contribution identity, or incomplete catalog MUST fail closed; it MUST NOT be converted to `ABSENT`.

A `ChannelMemberSnapshot` contains only:

```text
raw key
order
effective type BlueId
runtime role
ordered source-contribution BlueIds
registered immutable dispatch/header fields
deterministic dependency BlueIds
header identity
```

Reading a peer snapshot MUST NOT evaluate that peer as an External Channel, give it checkpoint authority, run its handlers, or load an executable body.

#### 3.3.2 Source Channel and handler Channel

Every accepted raw External Channel occurrence has two channel identities:

```text
sourceChannelKey
handlerChannelKey
```

The source Channel performed external acceptance and owns checkpoint domain, checkpoint subject, and checkpoint write. `HANDLER_CHANNEL_KEY` defaults to the source key but MAY select another declared same-scope Channel key. The selected target MUST resolve to a `CHANNEL` lookup result. A concrete runtime MAY define ordinary-source fallback for `ABSENT` or `NON_CHANNEL`; the fallback rule is part of that exact runtime type and MUST be deterministic.

The target Channel is not evaluated as another external occurrence and is not checkpointed merely because it is the handler target. Handlers are selected by the frozen `handlerChannelKey`.

#### 3.3.3 Logical delivery grouping

After rejection and stale filtering, accepted-new raw source occurrences are grouped by:

```text
(scopePath, logicalDeliveryKey)
```

The default `logicalDeliveryKey` is the raw source key. Every source in one group MUST agree on:

```text
exact payload identity
handlerChannelKey
logical delivery identity
```

One group executes the target handlers exactly once. Every fresh participating source retains its own checkpoint domain and subject. All participating source checkpoints commit only after the grouped handler execution and caused internal-event drain succeed. Failure, termination before checkpoint, cut-off, gas exhaustion, or rollback commits none of the group's source checkpoints. Rejected and stale sources are not participants.

If fresh sources assigned to one group disagree on payload identity, handler Channel identity, or logical delivery identity, classification fails atomically with `runtime-fatal` and diagnostic category `InconsistentLogicalDelivery`. No initialization, Handler execution, checkpoint, Root event, or document mutation commits.

Logical grouping is run state, not Blue content and not part of `ProcessResult`.

### 3.4 Revision-complete subscription index

Before the feeder selects an event:

```text
subscriptionIndex.indexedRootRevision == managedRoot.revision
subscriptionIndex.indexedRootBlueId   == managedRoot.currentRootBlueId
```

MUST hold.

The index MAY physically over-approximate and return false positives. Before canonical delivery ordering and portable occurrence limits are applied, raw candidates MUST be filtered by exact `PRESELECTS` using the current channel snapshot and event.

The index MUST NOT omit an active snapshot for which `PRESELECTS` is true. Omission is infrastructure nonconformance, not `no-match`.

A direct terminated marker prunes that scope and all declared descendants from later subscription snapshots.

### 3.5 Subscription activation intervals

Feeder state MUST record when one channel occurrence begins and ends observing external order:

```text
SubscriptionInterval {
    scopePath
    channelKey
    orderedSourceContributionNodeBlueIds
    effectiveTypeBlueId
    activationRootRevision
    startAfterExternalOrderKey
    endAtRootRevision?
}
```

For initial Root admission, platform policy MUST explicitly choose one frontier per external source:

```text
full history
from a declared order key
from the admission order key
```

A channel or embedded scope introduced while processing event `E` begins strictly after `E`'s canonical external-order key. It never joins `E`.

Removing and later re-adding a channel starts a new interval unless the exact channel runtime type explicitly defines a deterministic checkpoint/cursor migration. Reusing the same contract key does not silently resume a semantically different channel.

### 3.6 External completeness and canonical order

The feeder MUST not process event `E` until the concrete external-source ecosystem has supplied completeness evidence that no active subscribed source can later produce an eligible event ordered before `E`.

The concrete source specification MUST publish one exact total-order key and completeness rule. Contracts core treats that key as opaque ordered evidence. It does not define clocks, timelines, providers, or source-specific tie-breakers.

No later external event may interleave with the retained deliveries of the current event. The complete canonical delivery set of `E` reaches one terminal progress record before the feeder begins `E2`.

### 3.7 Canonical delivery snapshot

For Root revision `R` and event `E`, the feeder selects every active interval whose snapshot satisfies `PRESELECTS(snapshot, E)`.

It records:

```text
ExternalDelivery {
    scopePath
    channelKey
    orderedSourceContributionNodeBlueIds
    effectiveTypeBlueId
    order
    checkpointDomainBlueId
}
```

Canonical order is:

1. greater `scopePath` depth first;
2. normalized `scopePath` by Unicode code-point order;
3. effective channel `order`, ascending;
4. raw `channelKey`, Unicode code-point order;
5. effective type BlueId as a final deterministic tie-breaker.

The snapshot is retained across retries against the same Root revision. A new Root revision requires a new snapshot.

### 3.8 Revalidation and false positives

The processor revalidates every delivery before use:

- each path segment remains declared by the snapshotted Process Embedded contribution;
- the scope exists as an object and is not under a direct terminated scope;
- the same effective channel contribution identity remains at the same key;
- the channel type and checkpoint domain match the snapshot;
- `PRESELECTS` and `ACCEPTS` are re-evaluated against the exact event.

A stale physical index false positive therefore becomes a deterministic skipped or rejected occurrence. An omitted true occurrence is not harmless and is feeder failure.

### 3.9 Terminal delivery progress and poison events

Every terminal outcome is persisted against the exact Root revision:

```text
success
no-match
stale
terminated
capability-failure
invalid-processing-document
runtime-fatal
gas-limit-exceeded
portable-limit-exceeded
subscription-surface-invalid
```

A completed event is not automatically resubmitted against the same revision. Repeated deterministic failure or gas exhaustion MUST be quarantined or explicitly administratively retried; it MUST NOT block the external-order queue forever through unbounded automatic retry.

---

## 4. Contracts, Runtime Types, and Discovery

### 4.1 `contracts` map

Every scope MAY contain an effective `contracts` object:

```yaml
contracts:
  <key>: <Contract>
```

Contract entries are ordinary identity-bearing Blue content. Application contracts are obtained from the effective Language-resolved contracts map. Processor state at reserved keys is always direct state and is never inherited.

### 4.2 Contract-map key grammar

A contract key MUST:

- be non-empty Text;
- be a legal ordinary Blue child key;
- not equal a Language reserved or reserved-invalid key;
- contain at most 256 Unicode code points and 1,024 UTF-8 bytes;
- be representable as one escaped Runtime Pointer segment.

`/` and `~` are allowed in the raw key and are escaped only for pointers.

### 4.3 Runtime roles

Every effective Contract subtype has one registered runtime role:

| Role | Meaning |
|---|---|
| External Channel | Entry point for the external `PROCESS` event. |
| Processor Channel | Entry point for Document Update, Triggered, Lifecycle, or Embedded delivery. |
| Handler | Deterministic logic bound to one same-scope channel key. |
| Marker | Runtime state or policy; does not execute as a handler. |
| Executable extension | A registered additional role with exact semantics. |

A Contract subtype with an unsupported role or exact type is not inert. It is subject to must-understand failure.

### 4.4 Effective contract snapshot

For every effective contract key demanded by processing, the processor constructs an immutable out-of-band snapshot:

```text
EffectiveContractSnapshot {
    scopePath
    key
    orderedSourceContributionNodeBlueIds
    effectiveTypeBlueId
    role
    order
    resolvedDispatchFields
    executableBodyNodeBlueIds
    deterministicDependencyNodeBlueIds
}
```

The snapshot records exact contributions rather than manufacturing a synthetic merged-contract BlueId.

The runtime implementation for `effectiveTypeBlueId` defines which fields it demands at each stage. The generic processor MUST resolve the type of every effective contract in a participating scope, but MUST NOT load an executable body merely to classify or reject the entry.

### 4.5 Direct processor state first

Before enumerating application contracts in a scope, the processor reads and validates direct reserved state:

```text
contracts/terminated
contracts/initialized
contracts/checkpoint
```

A valid direct terminated marker makes the scope inactive. Unsupported application contracts inside that inactive scope are not recognized for the current invocation.

A type-derived initialized, terminated, or checkpoint marker has no runtime effect.

### 4.6 Must-understand preflight

Before the first mutation, the processor MUST preflight the complete **initial participating closure**:

- every preselected delivery scope that still exists;
- every declared ancestor from Root to those scopes;
- every effective contract type in those scopes;
- direct processor marker shapes;
- Process Embedded path structure;
- handler/channel binding structure;
- portable limits required before execution.

Preflight recognizes types and dispatch fields but not unselected executable bodies.

If an unsupported type, role, or required dispatch rule is found, the invocation returns `capability-failure`, input Root, no events, and admitted gas.

A patch or generated write affecting `/contracts`, `/type`, a type contribution, or another effective-contract dependency MUST repeat must-understand validation for the changed effective closure before processing continues or commit occurs.

### 4.7 Deterministic ordering

Channels and handlers are ordered by:

1. effective `order`, ascending; absent means `0`;
2. raw contract key, Unicode code-point order.

The canonical candidate list begins in contract-key order and is sorted by the stable merge-sort accounting rule in §13.10. Implementations MAY use indexes, but the logical order and trace are fixed.

### 4.8 Dispatch snapshots

For one channel delivery, the handler candidate list is snapshotted immediately before the first handler predicate is tested. The snapshot freezes key, contribution identities, effective type, dispatch fields, order, and body identities.

Changes to contracts during that delivery do not add, remove, reorder, or replace candidates in the current snapshot. They affect later discovery points.

For an accepted external delivery, its channel snapshot, payload, checkpoint domain, and checkpoint subject are frozen before initialization. Initialization may change the current contracts map, but the already accepted delivery continues from its frozen snapshot unless its scope is cut off or terminated. Handler discovery occurs after initialization and therefore observes post-initialization contracts.

### 4.9 Same-scope binding

A Handler binds to exactly one channel key in the same scope through its effective `channel` field. A missing same-scope channel makes the Handler inert unless its exact runtime type declares that shape invalid.

A child event reaches an ancestor only through an Embedded Node Channel. A descendant field change reaches an ancestor through a Document Update Channel.

### 4.10 Effective protected state

The following state is processor-protected:

```text
direct initialized marker identity
direct terminated marker identity
direct checkpoint marker identity
effective Process Embedded type and every non-path field
effective Type Generalization Policy
```

For every application patch and generated type write:

```text
EFFECTIVE_PROTECTED_STATE(before)
    ==
EFFECTIVE_PROTECTED_STATE(after)
```

MUST hold, except that an explicitly permitted patch to `contracts/embedded/paths` may change only `paths` while preserving the exact Process Embedded type and every other effective field.

This comparison catches indirect changes caused by replacing `/type`, `/contracts`, or an ancestor of a protected contribution.

### 4.11 Execution context

A runtime call may receive only deterministic values:

```text
$scope             current scope path
$document          read-only view of current Root
$event             current channelized payload
$processingEvent   original external PROCESS event
$contract          frozen current contract snapshot
$channel           frozen channel snapshot, when applicable
$gas               shared live-bounded meter
```

The context MUST NOT expose wall-clock time, randomness, ambient I/O, host object identity, mutable caches, thread scheduling, or unregistered state.

### 4.12 ContractExecutionResult

A Handler or executable Channel returns:

```text
ContractExecutionResult {
    patches        ordered list, default []
    events         ordered list, default []
    termination    optional
    runtimeLedger  optional only when not debiting the shared meter directly
}
```

Application order is:

1. validate and merge the runtime ledger exactly once;
2. apply patches in list order, each with its complete synchronous Document Update cascade;
3. record emitted events in list order;
4. apply the first termination request.

An invalid result shape fails before any effect from that result is applied. Whole-invocation atomicity still discards earlier tentative effects.

### 4.13 Runtime body demand and meter

A candidate body is demanded only after its matcher succeeds. Passing an already admitted exact node into or out of a runtime preserves its Node BlueId and MUST NOT recursively clone, serialize, or size it.

A runtime either debits the shared meter live or uses a child meter initialized with the exact remaining budget. It MUST NOT do both for the same work. A child ledger is validated and merged exactly once.

---

## 5. Root and Embedded Scopes

### 5.1 Scope

A **scope** is an object node inside Root that owns an effective contracts map and is either:

- Root at `/`; or
- a path declared by the nearest ancestor's effective Process Embedded contract.

The root scope always exists. A declared embedded scope exists only while its path contains an object node.

### 5.2 Process Embedded

The reserved key `contracts/embedded` contains a Process Embedded marker:

```yaml
contracts:
  embedded:
    type: Process Embedded
    paths:
      - /payment
      - /delivery
      - /riskMonitor
```

It defines:

1. owned child contract scopes;
2. mutation boundaries;
3. the recursive feeder subscription surface.

It does not broadcast the current external event to every child.

### 5.3 Embedded path validity

Each immediate path MUST:

- be a normalized Runtime Pointer beginning with `/`;
- not equal `/`;
- use object-member segments only;
- not traverse list positions;
- not pass through `contracts`, `type`, `schema`, `items`, or another Language-reserved field;
- be unique within the marker;
- not overlap another immediate path by ancestor/descendant relation;
- resolve to an object when present.

A missing declared child is permitted and contributes no active scope. A present non-object child is invalid. Traversal MUST reject an embedded ancestry cycle, including revisiting the same exact node on the current declared ancestor chain.

### 5.4 Entry snapshot

When a scope first participates, the processor freezes:

```text
ENTRY_EMBEDDED_PATHS(scope)
ENTRY_SCOPE_ROOT_IDENTITY(scope)
ENTRY_ANCESTOR_CHAIN(scope)
```

The embedded path snapshot is used for current-event path verification, boundaries, and propagation. Changes to `paths` affect later events only.

The entry root identity identifies the active occurrence for cut-off detection. Ordinary persistent writes strictly inside the occurrence create new node identities but preserve the occurrence. A whole-occurrence replacement by an ancestor with a different exact node ends it.

### 5.5 Participating closure

A scope participates when it:

- has an accepted new external delivery;
- is an ancestor required to initialize or observe such a delivery;
- receives a Document Update;
- receives an internal emitted event;
- receives a lifecycle event.

The initial closure is known from the external delivery snapshot and its ancestors. Additional internal participation is recognized at the first caused delivery.

Sibling and unrelated embedded branches remain inactive and MUST NOT be semantically expanded or discovered.

### 5.6 One authoritative Root

An embedded scope has no separate committed current-state record. Its current state is the exact node reachable from the authoritative Root.

An implementation MAY store tentative intermediate nodes by BlueId. Storage does not make them current state. Only the final Root compare-and-swap does.

### 5.7 Mutation boundaries

Let `S` be the executing scope and `E(S)` its immediate child roots from `ENTRY_EMBEDDED_PATHS(S)`.

An application patch from `S` MAY:

- change a strict descendant of `S` that is not strictly inside any child root in `E(S)`;
- add, replace, or remove one immediate child root in `E(S)` as a whole.

It MUST NOT:

- patch document Root `/`;
- replace or remove its own scope root;
- patch strictly inside an immediate child root;
- patch a strict ancestor of an immediate child root;
- cross into a cyclic-set member.

The strict-ancestor rule is intentionally simple. Authors must use an exact child-root operation rather than an ambiguous ancestor replacement.

### 5.8 Active-scope cut-off

When an ancestor removes an active embedded scope root or replaces it with a different exact node:

- that active occurrence and all active descendants are marked cut off;
- pending external deliveries at those paths are skipped;
- no new local handler begins there;
- unapplied patches, events, and termination requests from its current buffered result are discarded;
- no initialization, checkpoint, or termination marker is written into the replacement;
- a currently executing call may return, but the processor checks cut-off before applying each remaining buffered effect;
- events already emitted before cut-off continue along the ancestor chain frozen at emission;
- the Document Update that caused cut-off continues along its frozen receiving chain;
- re-adding the same path does not resurrect the old occurrence during this invocation.

Replacing a child root with the exact same current Node BlueId is a semantic no-op and does not cut off the occurrence.

The processor MUST check cut-off after every nested cascade and before every marker or checkpoint write.

### 5.9 Frozen propagation chains

Every emitted event and every Document Update freezes its source scope and active ancestor chain when the occurrence is created. Later changes to Process Embedded declarations do not redirect an already-created occurrence. A removed or terminated receiving ancestor may stop its own local reaction, but an event that already happened is not silently rewritten to have a different source.


---

## 6. Events and Processor-Managed Channels

### 6.1 Event model

Events are immutable Blue nodes. The processor distinguishes:

- the one external `PROCESS` event;
- lifecycle events;
- Document Update payloads;
- application events emitted by handlers;
- Embedded Event Delivery wrappers used for ancestor observation.

Only application or lifecycle events emitted by Root are included in `ProcessResult.events`.

### 6.2 External Channel

An External Channel is evaluated only for an occurrence in the canonical feeder snapshot.

For one occurrence, the processor:

1. revalidates its path and channel snapshot;
2. evaluates `PRESELECTS` and `ACCEPTS` against the exact event;
3. constructs and freezes the channelized payload;
4. calculates and freezes checkpoint domain and subject;
5. evaluates checkpoint newness;
6. if new, initializes the required scope chain and invokes matching handlers.

External Channel acceptance is immutable for this event and cannot read mutable Root business state. A Channel may accept while no Handler matches; the accepted new occurrence is still checkpointed.

### 6.3 Document Update

Every successful application patch or generated type-generalization write creates one immutable Document Update occurrence:

```yaml
type: Document Update
op: add | replace | remove
path: <path relative to receiving scope>
beforePresent: true | false
before: <exact snapshot when present>
afterPresent: true | false
after: <exact snapshot when present>
sourceScopePath: <path of patch origin relative to receiving scope>
```

`before` and `after` are omitted when the corresponding presence Boolean is false. Null is not used as an absence sentinel.

A Document Update Channel declares a scope-relative watched `path`. It matches when the changed path is equal to or below the watched path.

### 6.4 Immediate Document Update cascade

After one patch has been persistently applied and type soundness restored, its Document Update is delivered synchronously:

```text
origin scope
nearest active ancestor
...
Root
```

At each receiving scope:

1. discover and snapshot current matching Document Update Channels and Handlers;
2. process them in `(order, key)` order;
3. completely apply every matching Handler result before moving to the next receiving scope.

The cascade does not wait for the application-event queue. A nested patch creates and completely processes its own cascade before the enclosing Handler result continues.

The receiving chain is frozen when the update occurs. A handler may cause active-scope cut-off under §5.8; the current update still continues to higher receiving ancestors, but no later buffered effect from the cut-off source is applied.

### 6.5 Application event emission

When a Handler emits an event, the processor:

1. validates the event as an admissible exact Blue node;
2. retains or establishes its exact identity;
3. records an internal EventOccurrence with the source scope and frozen ancestor chain;
4. appends the event to `ProcessResult.events` immediately if and only if the source scope is Root;
5. appends the occurrence to the invocation FIFO.

The FIFO record is run state, not Blue content. It has no BlueId and is never returned.

### 6.6 Triggered Event Channel

When an EventOccurrence is dequeued, it is first delivered to matching Triggered Event Channels in its source scope, provided that source occurrence remains active, nonterminating, and nonterminated.

Every delivery uses fresh channel and Handler snapshots. Events emitted by those handlers are appended to the FIFO after the currently dequeued occurrence.

### 6.7 Embedded Node Channel

After local Triggered handling, the same occurrence is offered to each active receiving ancestor in nearest-first order through Embedded Node Channels.

The processor provides an exact channelized wrapper conceptually equivalent to:

```yaml
type: Embedded Event Delivery
sourcePath: <path from receiving scope to source scope>
event:
  blueId: <exact emitted-event BlueId>
```

The nested event is retained by exact identity. A receiving ancestor's Handler may explicitly emit the nested event or another event. Observation alone does not make it an event emitted by that ancestor.

### 6.8 Lifecycle Event Channel

The processor emits these lifecycle events:

```text
Document Processing Initiated
Document Processing Terminated
```

Lifecycle Channels receive only processor-generated lifecycle events. Lifecycle handlers follow the same snapshot, result, queue, cut-off, and gas rules as other handlers.

A deterministic failure or gas exhaustion rolls back lifecycle events with every other tentative effect. Fatal errors are returned as diagnostics; they are not separately emitted as committed application events.

### 6.9 Event queue order

The canonical queue order is FIFO by emission occurrence. For one occurrence:

```text
source Triggered delivery
then nearest ancestor Embedded delivery
then next ancestor
...
then Root
```

Every caused patch and its full Document Update cascade completes synchronously before that event delivery continues. Events emitted during one delivery are appended to the FIFO and do not interrupt the current occurrence.

The queue is drained in exactly one place: `DRAIN_INTERNAL_EVENTS` in §7.8. External-delivery helpers and lifecycle helpers enqueue events but MUST NOT independently drain the same queue.

### 6.10 Processor-managed writes

Processor-managed writes are classified as follows:

| Write | Creates Document Update? |
|---|---:|
| Application Json Patch | Yes |
| Generated type-generalization write | Yes |
| Whole embedded child-root application patch | Yes |
| Processing Initialized Marker | No |
| External channel checkpoint | No |
| Processing Terminated Marker | No |

Processor marker writes still pay pointer, identity, validation, and fixed processor gas. Lifecycle Channels are the observation mechanism for initialization and termination.

---

## 7. Normative Processing Algorithm

### 7.1 Run state

One invocation maintains tentative state conceptually equivalent to:

```text
RUN.inputRootBlueId
RUN.processingEvent
RUN.deliverySnapshot
RUN.acceptedNewDeliveries
RUN.acceptedStaleDeliveries
RUN.entryEmbeddedPaths
RUN.entryScopeRootIdentities
RUN.initializedScopes
RUN.activeScopes
RUN.cutOffScopes
RUN.terminatingScopes
RUN.terminatedScopes
RUN.eventQueue
RUN.rootEvents
RUN.contractSnapshots
RUN.validationProofs
RUN.openedNodeManifests
RUN.gasTrace
```

Implementation structures may differ. Observable result and canonical trace may not.

### 7.2 Phase A — admission and direct Root state

```text
1. Require admitted exact Root and event identities.
2. Require Root to be an object.
3. Begin the shared gas meter and charge processInvocation.
4. Read the direct Root terminated marker before application contracts.
5. If Root is already terminated, return status terminated, input Root, [], admitted gas.
6. Require the feeder snapshot to be bound to this exact Root revision and event.
```

Invalid provider content or unavailable required nodes are handled before or through the acquisition boundary in §12.4.

### 7.3 Phase B — revalidate and classify external deliveries

For each snapshot entry in canonical order:

1. verify only the declared branch from Root to target;
2. freeze entry scope/path state as needed;
3. skip a path already cut off or under a direct terminated scope;
4. resolve the exact effective channel contribution snapshot;
5. skip when the snapshot no longer exists unchanged;
6. charge and evaluate `PRESELECTS` and `ACCEPTS`;
7. if rejected, record no accepted delivery and continue;
8. construct and freeze payload, checkpoint domain, and subject;
9. evaluate declared same-scope Channel dependencies;
10. freeze `handlerChannelKey` and `logicalDeliveryKey`;
11. compare the source checkpoint;
12. record the accepted raw source occurrence as `new` or `stale`;
13. after all entries are classified, group accepted-new sources under §3.3.3 and reject inconsistent groups before mutation.

This phase is read-only. It does not initialize, execute Handlers, write checkpoints, or mutate Root.

Because acceptance cannot depend on mutable Root state, classification is stable for the invocation. A later scope cut-off may still invalidate a previously classified occurrence.

### 7.4 Phase C — must-understand preflight

If no accepted new occurrence exists, the processor skips mutation and returns under §7.10.

Otherwise, before the first mutation, it builds the initial participating closure from every accepted-new target and every declared ancestor. For each scope in Root-to-descendant order it:

- checks direct terminated state;
- snapshots Process Embedded paths;
- recognizes every effective contract type and role;
- validates channel/Handler binding structure;
- validates required dispatch fields and portable limits;
- verifies that every selected external snapshot remains compatible.

Unsupported or malformed runtime structure produces atomic failure before initialization.

### 7.5 Phase D — process accepted-new deliveries

Process accepted-new logical delivery groups in the canonical order of their first participating source occurrence. Raw source occurrences inside one group retain their original canonical order for checkpoint writes.

Before each delivery:

1. skip if its scope is cut off, removed, or under a terminated scope;
2. initialize every uninitialized active scope on Root-to-target chain in top-down order;
3. re-check cut-off and termination;
4. invoke the frozen logical delivery using its exact payload and frozen handler Channel;
5. apply every Handler result;
6. call `DRAIN_INTERNAL_EVENTS` exactly once to quiescence;
7. if the delivery scope remains active, nonterminating, and nonterminated, write every participating source checkpoint in canonical raw-source order;
8. call `DRAIN_INTERNAL_EVENTS` again only if a registered checkpoint extension legitimately emitted events; core checkpoint writes never do.

If Root terminates, later external deliveries are skipped.

### 7.6 Initialization ordering

For target `/a/b/c`, uninitialized scopes are initialized:

```text
/
/a
/a/b
/a/b/c
```

Each scope's initialization lifecycle and caused internal event processing completes before the next descendant scope initializes. This prevents descendant effects from reaching an uninitialized ancestor.

A scope initialized earlier in the same invocation is not initialized again.

### 7.7 One external delivery

For one accepted-new logical delivery group:

```text
1. Use the frozen payload, handler Channel snapshot, and participating raw source snapshots.
2. Discover current post-initialization same-scope Handlers bound to handlerChannelKey.
3. Sort and freeze candidates.
4. For each candidate:
     a. charge and evaluate its matcher;
     b. if nonmatching, continue;
     c. demand its executable body and declared dependencies;
     d. execute with $event = payload and $processingEvent = original event;
     e. apply its result under §4.12;
     f. after every nested cascade, check active-scope cut-off.
5. Return to Phase D; do not drain the queue here.
```

The accepted channel may have no matching Handler. It is still a successful delivery and may be checkpointed.

### 7.8 Internal event drain

```text
function DRAIN_INTERNAL_EVENTS():
    while RUN.eventQueue is not empty and Root is not cut off:
        occurrence = dequeue FIFO

        if source occurrence is active and not terminating and not terminated:
            DELIVER_TRIGGERED_AT_SOURCE(occurrence)

        for receivingAncestor in occurrence.frozenAncestors nearest-first:
            if receivingAncestor is active and not terminating and not terminated:
                DELIVER_EMBEDDED_EVENT(receivingAncestor, occurrence)

            if Root is terminated:
                break
```

Each delivery performs fresh channel and Handler discovery at that receiving scope, applies results synchronously, and may enqueue later occurrences.

An occurrence emitted before its source is cut off continues to its frozen ancestors. Cut-off only stops new local work and unapplied buffered source effects.

### 7.9 Phase E — final soundness and subscription validation

Before returning success, the processor or its deterministic platform boundary MUST establish:

- Root and every changed node are valid Blue Language nodes;
- the changed Root spine is type- and schema-sound;
- effective protected state was preserved;
- every changed effective contract type is supported;
- Process Embedded ancestry is acyclic and within limits;
- the changed subscription delta is finite, supported, and incrementally constructible;
- new activation intervals begin after the current external-order key;
- Root events satisfy the return limits.

A deterministic failure in this phase rolls back the entire invocation.

Transient inability to persist an already validated index delta is infrastructure suspension and commits nothing.

### 7.10 Result selection

If at least one accepted-new occurrence completed, result status is `success`, even when another candidate rejected, was stale, disappeared, or was cut off.

If no new occurrence completed and at least one accepted occurrence was stale, result status is `stale`.

If no current occurrence accepted, result status is `no-match`.

`no-match` and `stale` return input Root and no events. They do not initialize or write checkpoints.

An invocation that begins with a direct terminated Root returns `terminated`.

### 7.11 Several matching scopes

For:

```text
Root
└── Emb1
    └── Emb2
        └── Emb3
```

canonical external order is:

```text
Emb3
Emb2
Emb1
Root
```

The Emb3 external delivery and all of its caused updates/events complete before the Emb2 external delivery. Emb2 therefore sees Emb3's tentative changes. Root processes the external event last and sees all earlier tentative changes.

The whole set is one atomic Root transition. A late failure rolls back earlier tentative work for the same external event.

### 7.12 Exact locality

Successful processing MUST NOT require semantic expansion or contract discovery of:

- sibling embedded scopes outside selected branches;
- unrelated descendants;
- rejected external-channel bodies;
- nonmatching Handler bodies;
- unchanged descendant bodies needed only as known BlueIds;
- types, schemas, constants, or programs outside the demanded closure.

A host MAY prefetch them, but they cannot alter semantic demands, results, or portable gas.

---

## 8. Runtime Pointers, Patches, and Persistent Mutation

### 8.1 Runtime Pointer

A Blue Runtime Pointer is an RFC 6901 pointer over the current Root's abstract Blue node model.

- `""` denotes Root and is forbidden as an application patch target.
- object segments use RFC 6901 escaping;
- list indices are canonical decimal without leading zero;
- `-` is permitted only for list `add` at the end;
- malformed escapes, empty trailing segments, or out-of-range indices are invalid.

### 8.2 Json Patch Entry

Core supports:

```yaml
op: add | replace | remove
path: <Runtime Pointer>
val: <Blue node>      # required for add/replace; absent for remove
```

Operations are applied in result order. A later patch observes all earlier tentative patches and cascades.

`replace` on an object member is an upsert. `remove` of a missing member is invalid. Intermediate object nodes MAY be materialized only where the patch semantics explicitly permit; arrays are never silently invented.

### 8.3 Insertion normalization

A value inserted by a patch or emitted as an event MUST:

- be valid runtime Blue input with no root `blue` directive or unresolved alias;
- have no mixed `blueId` form;
- have one compatible payload kind;
- normalize list placeholders and scalar wrappers;
- preserve exact identity when it is already admitted;
- pay construction and identity work only when content is actually newly constructed or re-identified.

### 8.4 Persistent copy-on-write

For a patch to `/x/a` where `/x` is reference-backed:

1. open only direct nodes on the path;
2. preserve unchanged siblings by exact child BlueId;
3. create the changed leaf or subtree;
4. rebuild `x`'s direct identity;
5. rebuild each changed ancestor to Root;
6. validate the affected closure;
7. deliver the Document Update.

The old nodes remain immutable. Other references to old `x` are unchanged.

### 8.5 Object operations

A rebuilt object processes its complete direct helper map. One field change in a very wide direct object is therefore real linear direct-container work in every representation.

Object field enumeration uses canonical Unicode code-point key order. Reserved Language and Contracts fields follow their specific rules.

### 8.6 List operations

List identity uses the Language fold:

- append with a verified exact prior list identity recomputes only appended folds;
- replacement at index `i` recomputes the suffix from `i`;
- insertion or removal at `i` recomputes the affected result suffix;
- order and multiplicity are preserved.

### 8.7 Snapshots

Document Update `before` and `after` values are immutable exact-node snapshots. An absent side is represented only by the presence Boolean.

A snapshot may retain a node by exact identity without recursively materializing it. A Handler pays only for content it actually reads.

### 8.8 Boundary and cut-off validation

Before every patch, the processor validates §5.7 against the executing scope's entry snapshot.

After every patch and nested cascade, it checks whether an active scope root was removed or replaced and applies §5.8 before the next buffered effect.

A patch to the same exact child identity is a no-op for occurrence continuity. An ordinary whole-child replacement with a different identity starts a new occurrence for later external events and does not join the current event.

### 8.9 Effective protected-state validation

The processor computes `EFFECTIVE_PROTECTED_STATE` before and after every application patch or generated type write. Pointer nonintersection alone is insufficient.

If protected state changes outside the exact `Process Embedded.paths` exception, the invocation fails atomically with `ProtectedProcessorStateMutation`.

### 8.10 Contract-changing patches

A patch affecting any of these MUST trigger changed-closure recognition before further application execution:

```text
/type
/contracts
an inherited type contribution
contracts/embedded/paths
another runtime-registered dispatch or subscription dependency
```

The processor re-establishes:

- all effective contract types and roles in the changed closure;
- same-scope bindings;
- protected state;
- external subscription extraction;
- portable limits.

Unsupported newly installed contract content cannot be committed and deferred to the next event.

### 8.11 Direct-node limits

A direct-node limit applies to every node that must be enumerated, validated, or rebuilt, including every ancestor on the changed spine.

A larger exact node may still be carried opaquely by BlueId. An operation that needs its direct manifest fails deterministically with `DirectNodeLimitExceeded`.

### 8.12 Cyclic sets

Core runtime patches MUST NOT enter or structurally modify one member of a cyclic-set identity. A complete cyclic set may be replaced atomically as an already admitted new set. Otherwise processing fails with `CyclicSetMutationUnsupported`.

Opaque cyclic-member edges are valid ordinary content and may remain untouched through copy-on-write reconstruction. They are not independent processing roots, external events, or embedded-scope roots. Admission, embedded-boundary validation, and patch planning MUST reject unsupported cyclic access before demanding a member body.

---

## 9. Initialization, Lifecycle, and Termination

### 9.1 Initialization gate

A scope initializes only when an accepted-new delivery requires that scope to participate.

These do not initialize a scope:

```text
preselection false
channel rejection
all accepted occurrences stale
cut-off target
pre-existing terminated scope
capability failure
```

### 9.2 Initialization identity

The Document Processing Initiated event carries the exact scope document as it existed immediately before initialization effects. That node may be carried as a pure reference or verified materialization; both forms are the same document and do not change processing or gas. Content BlueId is not computed.

### 9.3 Initialization algorithm

For one uninitialized active scope:

1. freeze its exact pre-initialization scope document and Node BlueId;
2. mark it `initializing` in run state;
3. create Document Processing Initiated;
4. deliver matching Lifecycle Channels and Handlers;
5. apply their results and enqueue emitted events;
6. call `DRAIN_INTERNAL_EVENTS` to quiescence;
7. re-check cut-off and termination;
8. if still active, nonterminating, and not terminated, Direct Write the Processing Initialized Marker;
9. mark it initialized for this invocation.

The marker write creates no Document Update. If an ancestor replaces the scope during initialization reactions, no marker is written into the replacement.

### 9.4 Initialization snapshot rule

An accepted external channel snapshot remains frozen across initialization. Initialization may add, remove, or replace that channel in the current contracts map, but the already accepted delivery proceeds from its frozen snapshot unless the scope is cut off or terminated.

Handler discovery occurs after initialization and sees the post-initialization effective contracts map.

### 9.5 Termination request

A ContractExecutionResult may request graceful termination with a deterministic application cause and optional reason. The cause explains why the successful business transition is ending; it is not a `graceful | fatal` execution mode. Runtime failure is represented only by a noncommitting failure status.

The first request for a scope in one invocation wins. Later requests are ignored. A termination request is applied after that result's patches and emitted events have been recorded.

### 9.6 Termination algorithm

For one active nonterminating scope:

1. freeze the first termination request;
2. mark the scope `terminating`;
3. create and deliver Document Processing Terminated;
4. apply lifecycle Handler results;
5. call `DRAIN_INTERNAL_EVENTS` to quiescence; its ordinary-delivery predicate excludes scopes marked `terminating`, so no new local Triggered or Embedded Handler begins in that scope, while event occurrences emitted before or during termination continue to nonterminating frozen ancestors;
6. re-check cut-off;
7. if the scope still exists as the same occurrence, Direct Write the Processing Terminated Marker;
8. mark the scope terminated and stop later local work.

The marker creates no Document Update.

A scope may stop reacting while already-emitted descendant event occurrences continue to higher frozen ancestors.

### 9.7 Root termination

When Root begins termination:

- no later external delivery begins;
- the current result's already ordered patches and emissions complete according to §4.12;
- the termination lifecycle completes once;
- the Root termination marker is written if possible within the normal gas budget;
- the committing status remains `success` because a new Root was produced.

A later invocation on that Root returns `terminated` immediately.

There is no fixed-price emergency closeout. If the marker write cannot fit within gas or violates a deterministic rule, the whole invocation rolls back.

### 9.8 Deterministic failures

A deterministic runtime failure does not gracefully terminate or write a processor marker. It aborts the tentative invocation, returns the input Root, returns no events, and reports the admitted gas and diagnostic.

This keeps failure recovery separate from business termination and avoids partially committed fatal state.

---

## 10. Checkpoints and Idempotency

### 10.1 Checkpoint marker

Each scope MAY contain one direct Channel Event Checkpoint at:

```text
contracts/checkpoint
```

Conceptually:

```yaml
contracts:
  checkpoint:
    type: Channel Event Checkpoint
    entries:
      <raw-channel-key>:
        domain:
          blueId: <checkpoint-domain-blue-id>
        subject:
          blueId: <exact-subject-blue-id>
```

Checkpoint state is direct processor state and is never inherited.

### 10.2 Checkpoint domain

A checkpoint entry is active only when its `domain` equals the current frozen channel's `checkpointDomainBlueId`.

The default domain is the BlueId of a canonical domain node containing:

```text
Contracts version tag
External Channel effective type BlueId
ordered source-contribution Node BlueIds
runtime-registered checkpoint-domain discriminator
```

A concrete channel type may define another exact domain derivation. It MUST be stable, representation-independent, and registered.

Changing a channel's type or effective contributions at the same key therefore does not silently inherit an unrelated prior channel's stale state.

### 10.3 Virtual empty state

An absent checkpoint marker, absent raw key, or domain mismatch is treated as virtual empty state for newness evaluation.

The processor MUST NOT create an empty marker before establishing that a delivery is accepted, new, and successful.

### 10.4 Default exact-node subject

The default checkpoint subject is the exact input event Node BlueId retained as a pure reference.

A channel is stale when the current active entry has the same domain and the registered newness policy says the subject is not new. A concrete channel may use timeline predecessor, sequence, or another deterministic subject, but its policy and work are part of that exact runtime type.

Content BlueId is not the default subject.

### 10.5 Atomic checkpoint write

The checkpoint entry is Direct Written only after:

- accepted Channel delivery;
- all matching external Handlers;
- all caused patches and Document Updates;
- all caused internal event processing;
- successful termination handling, if requested;
- confirmation that the delivery scope remains the same active occurrence.

The checkpoint and every delivery effect commit together with Root. The write creates no Document Update.

### 10.6 Checkpoint cleanup and domain retirement

Checkpoint state is processor-owned and MUST NOT grow indefinitely after channels disappear or change semantic lineage.

At final changed-closure recognition, the processor deterministically compares the direct checkpoint entries of each changed scope with the scope's final effective External Channels:

- an entry whose raw channel key no longer exists is removed;
- an entry whose stored domain is not the current channel checkpoint domain is removed unless that exact runtime type defines an identity-bound migration accepted by this specification;
- an unchanged key with the unchanged domain is retained;
- cleanup is a processor Direct Write, creates no Document Update, and pays normal pointer, changed-direct-identity, validation, and `processorMarkerWritten` work;
- cleanup is tentative and rolls back with the invocation.

A channel removed and later re-added therefore starts with virtual empty checkpoint state unless an exact registered migration rule says otherwise.

### 10.7 Multiple occurrences and retry

The same external event may be accepted by several channels in several scopes. Each `(scope occurrence, raw channel key, checkpoint domain)` has independent newness.

After uncertain platform commit, the feeder reloads authoritative Root and revision:

- if the new Root committed, checkpoints make previously completed occurrences stale;
- if the old Root remains, the event is recomputed from that Root;
- if another Root is current, a new revision-bound delivery snapshot is derived.

The external event is never rewritten for retry.


---

## 11. Type Soundness, Generalization, and Subscription Indexability

### 11.1 Post-write soundness

After every successful patch, generated write, or processor Direct Write, the processor MUST restore the exact soundness obligations applicable to the changed closure before unrelated execution continues.

For application and generated writes, this includes:

- Blue Language node validity;
- fixed-value, type, schema, and collection compatibility;
- root-spine validity through every rebuilt ancestor;
- protected-state equality;
- supported effective contracts in the changed closure;
- valid Process Embedded structure and boundaries.

Processor Direct Writes validate their own marker shape and the rebuilt Root spine but do not execute application Document Update Channels.

### 11.2 Root-spine validation

A deep embedded patch is not valid merely because the local child remains valid. Every changed ancestor from the patch location to Root MUST remain valid under its effective type and schema.

Validation may retain unchanged child nodes by exact BlueId. It does not require transitive expansion of unchanged descendants unless their semantics are actually needed by a changed ancestor constraint.

### 11.3 Type Generalization Policy

A scope MAY contain a direct or inherited Type Generalization Policy at `contracts/generalization`. The effective policy is protected state.

A policy contains ordered rules. Each rule identifies a path, mode, and optional floor type:

```text
mode = nearest-valid-ancestor | reject
mustRemainSubtypeOf = optional exact type BlueId
```

The most specific matching path wins; ties use rule order. If no rule matches, the policy's `defaultMode` applies; absent default is `reject`.

### 11.4 Nearest-valid-ancestor algorithm

When a changed node no longer conforms to its current effective type and policy permits generalization:

1. record the current explicit/effective type as candidate `T0`;
2. validate the changed node against `T0`;
3. if invalid, move to the immediate effective ancestor type `T1`;
4. test candidates upward one at a time;
5. reject a candidate violating `mustRemainSubtypeOf`;
6. choose the first valid candidate;
7. if no valid candidate exists before the floor or root of the chain, fail.

Candidate order is exact type-chain order. A processor MUST NOT search unrelated types or choose a more general type when a nearer valid ancestor exists.

### 11.5 Generated write order

A generated type write is applied immediately after the patch that required it and before that patch's Document Update is delivered.

The generated write:

- is a processor-generated application-visible change;
- creates its own Document Update occurrence;
- is subject to protected-state validation;
- may trigger changed-contract recognition and subscription-delta validation;
- pays ordinary pointer, identity, validation, and update gas.

Generated writes cannot specialize a node or invent a type not on the existing ancestor chain.

### 11.6 Changed contract closure

When type or contract contributions change, the processor MUST resolve every affected effective contract type before commit. An unsupported External Channel, Process Embedded marker, Handler, lifecycle contract, or executable extension makes the new Root invalid for Contracts processing and rolls back the invocation.

Executable bodies remain lazy; recognition does not execute them.

### 11.7 Subscription-delta validation

Before a new Root can commit, the deterministic changed subscription delta MUST prove:

- every changed Process Embedded path is valid;
- every present declared child is an object;
- no declared embedded ancestry cycle exists;
- embedded depth, scope, key, and header limits hold;
- terminated-subtree pruning is deterministic;
- every changed External Channel type has supported subscription functions;
- its snapshot, keys, checkpoint domain, and activation interval can be derived;
- new intervals begin strictly after the current event order key;
- retired intervals are closed at the new Root revision;
- the incremental index delta is finite and canonical.

The validator may examine only changed branches and dependencies plus retained index identities. It MUST NOT require a full recursive Root scan for every event.

A deterministically non-indexable new Root fails with `SubscriptionSurfaceInvalid`. A transient failure to persist a valid delta is infrastructure suspension and commits nothing.

---

## 12. Failure, Resource, Status, and Progress Semantics

### 12.1 Statuses

Core statuses are:

| Status | Commits a new Root? | Meaning |
|---|---:|---|
| `success` | Yes | At least one accepted-new external occurrence completed. |
| `no-match` | No | No current External Channel accepted the event. |
| `stale` | No | At least one Channel accepted, but no accepted occurrence was new. |
| `terminated` | No | Root already had a valid direct terminated marker. |
| `invalid-processing-document` | No | Root or event was invalid before semantic execution. |
| `capability-failure` | No | A required runtime type or role was unsupported. |
| `runtime-fatal` | No | Deterministic processing failed after admission. |
| `gas-limit-exceeded` | No | The next canonical charge could not be admitted. |
| `portable-limit-exceeded` | No | A published portable structural or occurrence limit was exceeded. |
| `subscription-surface-invalid` | No | The input or resulting Root could not have a canonical subscription surface. |

A committing Root termination is still `success`; a later invocation returns `terminated`.

### 12.2 Diagnostic categories

Appendix B defines exact diagnostic categories. A diagnostic MUST include enough deterministic context for conformance, such as scope path, contract key, runtime type, patch path, or limit name, without embedding host stack traces or nonportable messages.

### 12.3 Admission and deterministic failure

Malformed serialized input, a missing exact Root identity, or an invalid event may be rejected before the gas meter begins and therefore reports zero gas.

After `processInvocation` is admitted, every deterministic semantic operation charges before work. A later capability, validation, patch, runtime, or limit failure returns the input Root, no events, and the gas admitted before the failure.

There is no separate zero-gas tentative preflight ledger and no portable `attemptedWork` result. This makes expensive rejected work visible to the same deterministic budget.

### 12.4 Resource acquisition boundary

Core `PROCESS` operates on verified exact-node evidence. Deterministic execution MUST NOT perform ambient network I/O.

An implementation MAY expose an attempt API:

```text
PROCESS_ATTEMPT(root, event, verifiedEvidence)
    -> Complete(ProcessResult)
     | NeedsResources(sortedExactBlueIds)
```

`NeedsResources` is a suspension, not a `ProcessResult`:

- it commits no Root, events, checkpoint, marker, progress, or portable gas;
- the host fetches and verifies direct nodes outside deterministic execution;
- retry starts from the exact input Root and event;
- hidden cache state MUST NOT turn the same explicit evidence set into a different attempt outcome.

Provider transfer, direct-node verification, signatures, storage pages, and retry count are host work. Once an exact node is admitted, semantic inspection and new/changed identity work are charged normally and identically to inline content.

### 12.5 Definitive missing content and invalid evidence

A configured provider domain may report definitive `NotFound`; evidence may fail BlueId verification. These are host acquisition failures unless the exact runtime type deliberately treats one as application data.

No implementation may convert unavailable, incomplete, or invalid evidence into semantic field absence.

### 12.6 Gas exhaustion

Every charge is admitted before the corresponding work. If the next charge would exceed `MAX_PROCESS_GAS`:

- the failing charge is not added;
- no further runtime or lifecycle code runs;
- every tentative mutation, event, marker, checkpoint, and queue item is discarded;
- the result is `gas-limit-exceeded`, input Root, empty events, and already admitted gas.

There is no fixed-price termination closeout.

A repeated attempt against the same Root revision, event, environment, and gas limit produces the same status, trace prefix, and gas.

### 12.7 Portable limits

A limit known before the meter begins may be rejected with zero gas by the feeder or admission layer. A limit discovered after semantic execution begins returns `portable-limit-exceeded` with admitted gas. `NeedsResources` is never encoded as `ProcessResult.status`; it exists only as the alternate result of `PROCESS_ATTEMPT`.

The diagnostic MUST identify the exact limit, such as:

```text
MatchingDeliveryLimitExceeded
ParticipatingScopeLimitExceeded
DirectNodeLimitExceeded
EmbeddedDepthLimitExceeded
InternalEventLimitExceeded
PatchLimitExceeded
RuntimeLedgerLimitExceeded
```

### 12.8 Failure precedence

When several errors are possible, the normative algorithm order decides. In particular:

1. invalid Root/event admission precedes runtime discovery;
2. direct terminated state precedes application contract recognition;
3. delivery revalidation precedes Channel acceptance;
4. checkpoint comparison precedes initialization;
5. cut-off checks precede remaining buffered effects and marker writes;
6. gas exhaustion occurs at the first unadmitted canonical charge.

Fixtures asserting one diagnostic MUST isolate the relevant failure or list acceptable categories explicitly.

### 12.9 Revision-bound progress

The feeder MUST record every terminal outcome only by compare-and-swap against the exact Root revision on which it was calculated. A progress-only terminal record (`no-match`, `stale`, failure, or gas exhaustion) cannot be committed after Root has changed.

A Root-mutating `success` commits Root, Root events, subscription delta, and progress together. A failed compare-and-swap records nothing and triggers recomputation.

### 12.10 External-event liveness

A deterministic poison event MUST NOT cause unbounded automatic retries or permanently block all later external events.

After one revision-bound terminal failure, the platform MUST either:

- quarantine the event and advance according to declared platform policy;
- require explicit administrative retry;
- or change the Root/environment before retrying.

The policy is audited outside Root but may not silently reinterpret a failed event as success.

---

## 13. Canonical Gas Accounting

### 13.0 Schedule status

The counter vocabulary, ownership, formulas, and canonical trace order are normative for this implementation baseline. The numeric weights and portable-limit values are provisional pending calibration and are loaded from the bound gas manifest. Implementations MUST load or generate them from that artifact rather than scatter duplicated constants through runtime code. Final public Contracts 1.0 publication freezes the calibrated values once and regenerates every dependent fixture and package identity.


### 13.1 Governing principle

Gas prices deterministic logical work, never the chosen materialization.

For the same exact node `X`:

```yaml
x:
  a: 1
  b: 1
```

and:

```yaml
x:
  blueId: X
```

must produce the same trace when the same logical fields are inspected and the same transition is performed.

An existing exact node is cheap to carry. Content costs gas when it is inspected, compared, constructed, normalized, validated, or re-identified.

### 13.2 One disjoint ledger

```text
totalGas =
    weighted processor counters
  + weighted semantic counters
  + weighted runtime counters
```

One logical unit increments one named counter. A reason tag never adds another numeric category. The same work MUST NOT be charged once as “admission” and again as “changed identity.”

### 13.3 Canonical trace record

In conformance mode, every admitted charge is appended before work as:

```text
GasTraceEntry {
    sequence
    namespace
    counter
    quantity
    weight
    subtotal
    scopePath?
    contractKey?
    logicalPath?
    reason
}
```

Entries are ordered by the normative algorithm. `sequence` begins at zero and increases by one per trace entry. A charge with quantity greater than one remains one trace entry unless the rule explicitly requires per-occurrence entries.

An ordinary API may return only `totalGas`, but a conforming implementation MUST be able to produce the exact trace for the fixture harness.

### 13.4 Shared live-bounded meter

Processor work, semantic Language work, external channels, Handlers, workflows, executable runtimes, and registered intrinsics share one meter.

A runtime child meter receives the exact remaining budget. It admits every child charge live. Its ledger is merged once in original order. A runtime-local gas limit may only lower the available budget; it cannot replenish it.

### 13.5 Processor counters and weights

| Counter | Weight |
|---|---:|
| `processInvocation` | 50 |
| `deliverySnapshotEntry` | 5 |
| `scopeOpened` | 10 |
| `contractHeaderRecognized` | 2 |
| `channelCandidateTested` | 5 |
| `channelAccepted` | 5 |
| `handlerCandidateTested` | 5 |
| `handlerCall` | 50 |
| `scopeInitialization` | 1000 |
| `embeddedPathEntryRead` | 1 |
| `embeddedPathSegmentValidated` | 1 |
| `pointerSegmentTraversed` | 1 |
| `patchBoundaryChecked` | 2 |
| `patchAddOrReplace` | 20 |
| `patchRemove` | 10 |
| `documentUpdateDelivered` | 10 |
| `internalEventEnqueued` | 20 |
| `internalEventDequeued` | 10 |
| `triggeredEventDelivered` | 10 |
| `embeddedEventDelivered` | 10 |
| `rootEventRecorded` | 5 |
| `lifecycleDelivered` | 30 |
| `checkpointCompared` | 5 |
| `checkpointWritten` | 20 |
| `processorMarkerWritten` | 20 |
| `terminationRequested` | 10 |

Rules:

- `deliverySnapshotEntry` is charged once per retained entry revalidated by the processor.
- `scopeOpened` is charged once per distinct active scope occurrence in one invocation.
- `contractHeaderRecognized` is charged once per `(scopePath, key, ordered contribution identities)`.
- a Channel or Handler candidate pays its test charge even when it rejects;
- a delivery counter (`documentUpdateDelivered`, `triggeredEventDelivered`, `embeddedEventDelivered`, `lifecycleDelivered`) is charged only for a matching Channel delivery, in addition to candidate tests;
- `rootEventRecorded` is charged only for Root emissions, not child emissions.

### 13.6 Semantic counters and weights

| Counter | Weight |
|---|---:|
| `nodeManifestOpened` | 1 |
| `objectMemberRead` | 1 |
| `listItemRead` | 1 |
| `textBlockExamined` | 1 |
| `textBlockConstructed` | 1 |
| `scalarComparison` | 1 |
| `integerLimbOperation` | 1 |
| `sortComparison` | 1 |
| `typeEdgeFollowed` | 1 |
| `schemaPredicateEvaluated` | 1 |
| `validationMemberExamined` | 1 |
| `validationProofReused` | 1 |
| `subtypeCandidateTested` | 5 |
| `nodeIdentityEstablished` | 1 |
| `objectMemberRebuilt` | 1 |
| `listFoldStepRecomputed` | 1 |
| `directIdentityHashBlock` | 1 |

### 13.7 Manifest and immutable-read rules

Opening the direct manifest of an exact node for the first semantic use in one invocation charges `nodeManifestOpened` once for that exact Node BlueId. A second semantic operation may reuse the retained immutable manifest without another manifest-open charge.

Known-key object access charges `objectMemberRead` each time the normative algorithm examines that member, unless the value was explicitly bound and reused within the same algorithmic step. Complete enumeration charges once per direct member in canonical key order.

List access charges `listItemRead` per position examined.

Hidden caches from earlier invocations never reduce the canonical first-use trace.

Provider-side BlueId verification is outside portable gas. Establishing the identity of new or changed content inside the invocation is charged under §§13.12–13.13.

### 13.8 Text and scalar work

One text block contains up to 64 Unicode code points.

A full scan of Text `t` charges:

```text
textBlockExamined += ceil(codePointLength(t) / 64)
```

A newly constructed Text charges the same block formula as `textBlockConstructed`.

Lexicographic comparison examines code points until the first difference or the end of the shorter Text. Let `k` be the number of code points whose values are read from each operand, including the differing position when present. It charges:

```text
scalarComparison += 1
textBlockExamined += ceil(k / 64) for the left operand
textBlockExamined += ceil(k / 64) for the right operand
```

Length-only comparison after a fully equal prefix does not reread content.

Exact Blue node identity equality may compare known Node BlueIds without scanning transitive content. Runtime value equality that is not exact Blue identity follows the runtime specification.

### 13.9 Integer work

Integers use a canonical unsigned base-`2^32` magnitude and separate sign. `L(x)` is at least 1 and otherwise the number of limbs.

| Operation | `integerLimbOperation` quantity |
|---|---:|
| equality or ordering | `L(a) + L(b)` |
| addition or subtraction | `max(L(a), L(b)) + 1` |
| multiplication | `L(a) * L(b)` |
| division or remainder | `L(a) * L(b)` |
| GCD or `multipleOf` | `L(a) * L(b)` |
| LCM | GCD quantity plus multiplication quantity |

The formula defines portable work, not a required host algorithm.

### 13.10 Canonical sorting

When processor semantics require sorting a candidate set, canonical gas is calculated as if using stable bottom-up merge sort:

1. input order is canonical contract-key order or another explicitly defined order;
2. runs begin at width 1;
3. adjacent runs merge left-to-right;
4. run width doubles after each pass;
5. equal comparisons select the left element;
6. every comparator call charges `sortComparison` plus content work for compared fields.

Implementations may use another physical algorithm but MUST report this canonical trace.

External event ordering and subscription-index lookup are feeder work and do not use this processor counter.

### 13.11 Type, contract, and validation work

Effective contracts are merged ancestor-to-descendant:

- charge `typeEdgeFollowed` for each traversed type edge;
- enumerate demanded contribution maps;
- inspect only registered dispatch fields;
- charge one `contractHeaderRecognized` for the effective snapshot.

Validation charges:

- `schemaPredicateEvaluated` per predicate;
- `validationMemberExamined` per collection member examined by `itemType`, `keyType`, `valueType`, `uniqueItems`, enum search, or another member-wise rule;
- `subtypeCandidateTested` per generalization/subtype candidate;
- Text and Integer work for scalar content examined.

Within one invocation, an exact successful proof for:

```text
(nodeBlueId, effectiveTypeBlueId, effectiveConstraintIdentity)
```

is charged in full once. Later logical reuse increments `validationProofReused` once and does not repeat predicate/member counters. Cross-invocation caches are physical optimization only and do not remove the current invocation's first full proof.

### 13.12 Identity establishment

Every new exact node, including an empty list, charges:

```text
nodeIdentityEstablished += 1
```

For a new or rebuilt non-list node:

```text
objectMemberRebuilt += direct helper-map members processed
directIdentityHashBlock += ceil((N + 9) / 64)
```

`N` is the UTF-8 byte length of the exact RFC 8785 canonical direct identity input hashed for that node. Transitive child bodies are replaced by their exact bounded canonical Base58 child BlueId strings before `N` is measured. Direct keys, `name`, `description`, and inline scalar `value` contribute because the Language BlueId algorithm hashes them directly.

This is actual changed/new identity work. Carrying an existing exact node never pays it again.

### 13.13 List identity

For a new or changed list:

- full construction charges one `listFoldStepRecomputed` per result element;
- append from a verified prior exact list identity charges appended steps only;
- replacement at index `i` charges the result suffix from `i`;
- insertion/removal at `i` charges the affected result suffix.

The fixed list-cons hash input is represented by the fold counter and is not charged again as `directIdentityHashBlock`.

### 13.14 Runtime ledger composition

Each executable runtime type publishes exact named counters and weights in its own specification and runtime registry.

Runtime construction work and semantic identity admission are distinct:

```text
A concrete compute runtime creates a 100-member object:
  that runtime charges members produced.

The value crosses a Blue output/patch boundary:
  Contracts/Language charges node identity and direct-container work.
```

Passing an existing exact Blue node charges only the runtime access/carry work actually defined by that runtime; it does not recursively size or reconstruct the node.

### 13.15 Patch trace

A successful patch charges, in order:

```text
patchBoundaryChecked
pointerSegmentTraversed for each segment
patchAddOrReplace or patchRemove
runtime construction, when the value was newly built
identity establishment for changed leaf and every rebuilt ancestor
post-write type/schema/generalization work
Document Update candidate tests and matching deliveries
downstream Handler/runtime work
```

It does not charge unchanged transitive descendants behind known child BlueIds.

### 13.16 Event and checkpoint trace

Emitting an existing exact event has no recursive size charge. A newly constructed event pays runtime construction and semantic identity admission before `internalEventEnqueued`.

A Root emission additionally pays `rootEventRecorded`.

Checkpoint comparison pays `checkpointCompared` and the exact subject policy work. A checkpoint write pays `checkpointWritten`, marker pointer work, direct changed identity, and validation. It creates no Document Update.

### 13.17 Zero-gas physical work

The following consume zero portable Contracts gas:

```text
provider lookup and transfer
provider BlueId verification
cache hit, miss, fill, or eviction
storage page/chunk access
physical prefetch
allocation and host copying
hash-cache lookup
transport serialization
subscription-index maintenance/query
external-source completeness queries
external event sorting
failed compare-and-swap and recomputation
```

Hosts may meter, bill, or quota them separately.

### 13.18 Representation example

Suppose:

```yaml
x:
  a: 1
  archive:
    blueId: <25-MiB-archive>
```

and an equivalent Root has `x` collapsed to its BlueId. For:

```yaml
op: replace
path: /x/a
val: 2
```

both forms perform and charge the same semantic trace:

1. open Root direct manifest;
2. open `x` direct manifest;
3. traverse `/x/a`;
4. admit scalar `2`;
5. rebuild `x` using the unchanged archive BlueId;
6. rebuild ancestors to Root;
7. validate changed closure;
8. deliver caused updates and events.

The archive body is neither demanded nor charged. A one-million-field direct `x` remains expensive in both forms because its direct manifest is real identity work.

---

## 14. Determinism, Security, and Portable Limits

### 14.1 Deterministic execution

Contract behavior MUST NOT depend on:

- wall-clock time;
- randomness;
- ambient network reads;
- CPU speed or thread scheduling;
- host object identity;
- cache warmth;
- database row order;
- locale-sensitive comparison;
- noncanonical map iteration;
- unspecified numeric behavior.

External time and actor attribution enter only through the immutable event and feeder evidence fixed before processing.

### 14.2 Read-only values

Event nodes, snapshots, dispatch snapshots, and runtime context are read-only. All application mutation occurs through Json Patch Entries. All application event output occurs through the normalized result.

A host MUST NOT require recursive cloning to enforce read-only behavior. Immutable identity-preserving values are sufficient.

### 14.3 Trust boundary

The processor trusts the managing feeder to supply a complete revision-bound snapshot and correct external-order evidence. It revalidates every selected branch and channel identity but does not independently rescan the complete subscription surface.

Authorization and mandate eligibility belong to the feeder/provider layer unless an exact runtime type defines additional deterministic checks.

### 14.4 Portable limits

| Limit | Value |
|---|---:|
| `MAX_PROCESS_GAS` | 100,000 |
| Effective contracts in one participating scope | 8,192 |
| External Channels in one scope | 2,048 |
| Handlers bound to one delivery | 4,096 |
| Subscription keys from one Channel | 256 |
| Preselected external occurrences for one event | 1,024 |
| Participating scopes for one event | 4,096 |
| Process Embedded paths in one scope | 4,096 |
| Embedded depth | 256 |
| Runtime Pointer segments | 256 |
| Normalized Runtime Pointer UTF-8 bytes | 4,096 |
| Contract-key Unicode code points | 256 |
| Contract-key UTF-8 bytes | 1,024 |
| Direct object entries materialized/rebuilt | 16,384 |
| Direct list items materialized/rebuilt | 16,384 |
| Direct canonical identity input bytes | 1,048,576 |
| Type-chain edges | 256 |
| Patches in one ContractExecutionResult | 1,024 |
| Events in one ContractExecutionResult | 1,024 |
| Internal EventOccurrences in one invocation | 8,192 |
| Root events returned | 4,096 |
| Nested Document Update cascade depth | 256 |
| Runtime child-ledger counter kinds | 256 |
| Direct object-key Unicode code points | 4,096 |
| Direct inline identity Text code points | 262,144 |

These are structural bounds, not promises that maximum-size valid structures fit under `MAX_PROCESS_GAS`. Gas is the operative work ceiling.

The direct-container limit applies to every rebuilt ancestor. A larger exact node can be carried opaquely, but an operation requiring its direct manifest fails.

### 14.5 Bounded feeder work

The feeder MUST also bound:

```text
active index entries per managed Root
subscription-key bytes
external event-header demand
preselection work
activation intervals
retained delivery snapshot size
```

Hosted numeric quotas may be stricter than the portable processor limits. They MUST be declared before admission and must not change the semantic result of an admitted event.

### 14.6 Authoring guidance

Authors SHOULD:

- use bounded-fanout structures for large mutable collections;
- place large workflow bodies, constants, and templates behind BlueId references;
- keep External Channel headers and subscription keys small;
- put mutable business conditions in Handlers, not External Channel acceptance;
- avoid broad events matching thousands of scopes;
- preserve event/gas headroom for ancestor reactions;
- model independent shared objects as autonomous roots.

### 14.7 Locality conformance

A processor is not conforming to the locality rules merely because it returns correct gas while still requiring a complete graph materialization. Conformance locality fixtures record exact semantic node demands. A processor MUST be able to complete them without demanding listed unrelated sibling bodies.

An implementation may physically prefetch those bodies, but they must remain outside the semantic-demand report and cannot be required for success.

---

## 15. Conformance Vectors

The Contracts 1.0 prose, runtime registry, gas schedule, and machine-readable fixture package jointly define conformance. The fixture package includes a complete vector coverage map and exact gas microfixtures.

### 15.1 Representation and locality

- **C-REP-01.** Inline and pure-reference forms of the same Root produce the same status, resulting Root, Root events, semantic demands, counter trace, and gas.
- **C-REP-02.** A patch inside a collapsed branch demands only nodes on the path and semantic dependencies, not sibling bodies.
- **C-REP-03.** Warm/cold cache, batching, prefetch, and physical segmentation do not change portable results or gas.
- **C-REP-04.** Existing large exact values can be carried, emitted, and checkpointed without recursive size work.
- **C-REP-05.** Newly constructed large values pay runtime construction and semantic identity work.
- **C-REP-06.** A wide direct ancestor is charged and limited in every representation.
- **C-REP-07.** An early list edit pays the recomputed suffix; append pays only the delta when prior identity is available.

### 15.2 Feeder and subscription

- **C-FEED-01.** The subscription index is revision-complete before event selection.
- **C-FEED-02.** `ACCEPTS => PRESELECTS` and `PRESELECTS => key intersection` hold for every portable External Channel.
- **C-FEED-03.** External Channel acceptance cannot depend on mutable Root state.
- **C-FEED-04.** Physical index false positives are filtered before canonical ordering and limits.
- **C-FEED-05.** An omitted true preselection is feeder nonconformance, not `no-match`.
- **C-FEED-06.** A new Channel begins strictly after the event that introduced it.
- **C-FEED-07.** Removed and re-added semantic Channel contributions create a new activation interval.
- **C-FEED-08.** All deliveries of one event complete before a later external event begins.
- **C-FEED-09.** Nonmutating terminal progress is compare-and-swap bound to the exact Root revision.
- **C-FEED-10.** Repeated deterministic poison events are quarantined rather than retried forever.

### 15.3 Discovery, snapshots, and initialization

- **C-DISC-01.** Direct terminated state is checked before application contract recognition.
- **C-DISC-02.** Every effective contract type in the initial participating closure is recognized before first mutation.
- **C-DISC-03.** Unselected executable bodies remain collapsed.
- **C-DISC-04.** Effective contracts use ordered contribution identities rather than a synthetic merged BlueId.
- **C-DISC-05.** A Handler snapshot survives same-delivery contract mutation.
- **C-DISC-06.** Contract/type changes are re-recognized before commit.
- **C-INIT-01.** `no-match` and all-stale processing do not initialize.
- **C-INIT-02.** Ancestors initialize Root-to-target before descendant processing.
- **C-INIT-03.** Accepted Channel/payload/checkpoint snapshot remains frozen across initialization.
- **C-INIT-04.** Handler discovery after initialization sees post-initialization contracts.
- **C-INIT-05.** Initialization marker writes do not create Document Updates.

### 15.4 Embedded scopes, updates, and events

- **C-EMB-01.** External deliveries are ordered deeper-first, then path, order, and key.
- **C-EMB-02.** One external event produces one atomic Root transition across all selected scopes.
- **C-EMB-03.** Unrelated embedded branches are not semantically demanded.
- **C-EMB-04.** A parent may replace an immediate child root but may not patch inside it.
- **C-EMB-05.** Strict-ancestor patches intersecting child roots are rejected.
- **C-EMB-06.** Active-scope replacement cuts off remaining buffered effects and marker/checkpoint writes.
- **C-EMB-07.** Re-adding a path does not resurrect the old occurrence in the current invocation.
- **C-UPD-01.** Every successful application patch creates one origin-to-Root Document Update cascade.
- **C-UPD-02.** Presence Booleans preserve add/remove identity without null sentinels.
- **C-UPD-03.** Current update propagation continues on its frozen chain after source cut-off.
- **C-EVT-01.** Source Triggered handling precedes nearest-to-farthest ancestor Embedded handling.
- **C-EVT-02.** Events emitted during delivery are appended FIFO and do not interrupt the current occurrence.
- **C-EVT-03.** Child emissions are not returned unless Root explicitly emits.
- **C-EVT-04.** Duplicate equal event nodes remain distinct occurrences and Root outputs.
- **C-EVT-05.** The internal queue is drained exactly once by the normative owner.

### 15.5 Checkpoints, lifecycle, and protected state

- **C-CHK-01.** Checkpoint newness is evaluated before initialization.
- **C-CHK-02.** Absent checkpoint state is virtual and no empty marker is created for stale/rejected delivery.
- **C-CHK-03.** Checkpoint entries bind raw key, domain, and subject.
- **C-CHK-04.** Replacing a Channel at the same key changes the active checkpoint domain.
- **C-CHK-05.** Checkpoint write commits only after complete delivery and queue processing.
- **C-CHK-06.** Retry after uncertain commit is idempotent against authoritative Root.
- **C-CHK-07.** Removed channels and changed checkpoint domains are deterministically cleaned from processor checkpoint state without a Document Update.
- **C-LIFE-01.** Initiated lifecycle precedes initialized marker.
- **C-LIFE-02.** First termination request wins and lifecycle/marker occur at most once.
- **C-LIFE-03.** Scope replacement during lifecycle prevents marker write into replacement.
- **C-LIFE-04.** Gas failure during termination rolls back the entire invocation.
- **C-PROT-01.** Application patches cannot directly or indirectly alter protected state.
- **C-PROT-02.** Only `Process Embedded.paths` may change under its exact exception.

### 15.6 Soundness, failure, and indexability

- **C-SND-01.** Every changed ancestor to Root is type- and schema-validated.
- **C-SND-02.** Nearest-valid type generalization is deterministic and bounded by policy.
- **C-SND-03.** Generated type writes create Document Updates and are re-recognized.
- **C-SND-04.** Cyclic-set member mutation is rejected.
- **C-CYC-01.** A pure cyclic-set member is rejected as an independently mutable processing Root before provider demand.
- **C-CYC-02.** A pure cyclic-set member is rejected as a top-level processing event before provider demand.
- **C-CYC-03.** `Process Embedded` cannot terminate at or traverse through an opaque cyclic-member edge.
- **C-CYC-04.** An ordinary Root can preserve an untouched opaque cyclic-member edge while unrelated selected processing succeeds without opening it.
- **C-IDX-01.** A new Root with invalid embedded path, cycle, unsupported subscription extraction, or excess limit rolls back.
- **C-IDX-02.** Valid subscription delta is incremental and new intervals start after the current event.
- **C-FAIL-01.** Deterministic failure returns input Root, no events, and admitted gas.
- **C-FAIL-02.** Transient resource suspension commits no state, progress, events, or portable gas.
- **C-FAIL-03.** Gas exhaustion returns the canonical trace prefix and is deterministic on retry.
- **C-FAIL-04.** Compare-and-swap conflict commits nothing and is outside portable gas.
- **C-FAIL-05.** `PROCESS_ATTEMPT` may return `NeedsResources`, but no completed `ProcessResult` uses `needs-resources` as a status.

### 15.7 Gas and runtime

- **C-GAS-01.** Every processor and semantic counter has an exact weight and microfixture.
- **C-GAS-02.** Charges are admitted before work and the failing charge is absent on exhaustion.
- **C-GAS-03.** Manifest opening and validation proof reuse follow run-local canonical memo rules.
- **C-GAS-04.** Text comparison, Integer limbs, and canonical sorting produce exact traces.
- **C-GAS-05.** Direct identity blocks charge only new/changed direct identity, never unchanged transitive content.
- **C-GAS-06.** Runtime child ledgers are live-bounded and merged exactly once.
- **C-GAS-07.** Executable-runtime representation state is unobservable and recursive boundary-size charging is absent.
- **C-ROUTE-01.** The default handler Channel equals the accepted source Channel and preserves existing one-source behavior.
- **C-ROUTE-02.** A declared peer same-scope Channel may be frozen as handler target without being externally evaluated or checkpointed.
- **C-ROUTE-03.** Exact absent and present-non-Channel target lookups remain distinguishable; unavailable or undeclared evidence fails closed.
- **C-ROUTE-04.** Several fresh sources with the same logical delivery key, target, and payload execute handlers once and checkpoint every source only after success.
- **C-ROUTE-05.** A stale source does not piggyback on a fresh source in the same logical group.
- **C-ROUTE-06.** Group target or payload disagreement fails atomically before mutation.
- **C-INIT-06.** The initialization marker and initiated event carry the exact initial scope document; inline and pure-reference forms yield the same Root, lifecycle behavior, gas, and trace.
- **C-LOOP-01.** An internal event cycle is stopped by the shared gas limit and rolls back Root and Root events.
- **C-GAS-08.** Provider verification and transport are outside portable gas.
- **C-E2E-01.** A complete successful Root transition fixture asserts exact status, resulting document, Root event order, named trace, total gas, and semantic demands.
- **C-E2E-02.** A deep embedded delivery fixture asserts the same complete result dimensions and returns an empty public event sequence when Root emits nothing.
- **C-E2E-03.** An inline/reference representation matrix produces the exact same complete end-to-end result and trace.

### 15.8 Machine-readable fixture package

The implementation-baseline fixture package is bound to the exact runtime registry manifest and the exact `blue-contracts/gas/1.0` manifest. It publishes:

- 69 executable behavior fixtures covering all 78 vectors in §§15.1–15.7;
- feeder/platform and revision-bound commit fixtures;
- locality semantic-demand assertions;
- 58 exact gas microfixtures and composite gas fixtures;
- a vector-to-fixture coverage map;
- a fixture schema and scripted runtime registry bindings;
- deterministic file digests and package identity.

The fixture envelope is:

```yaml
schema: blue-contracts-fixture/1.0
id: <unique fixture id>
vectors: [C-...]
category: <category>
operation: process | process-attempt | platform | gas-micro
input:
  root: <Blue node, for process fixtures>
  event: <Blue node, for process fixtures>
  feeder: <revision-bound derived snapshot and platform state>
  provider: <exact provider evidence and expected semantic demand boundary>
  runtime: <scripted exact runtime behavior>
expected:
  assertions: <ordered machine assertions>
```

`input.feeder.deliverySnapshot` is derived environment evidence. It is not caller-authored Blue content and is not a third semantic input to `PROCESS`. The harness independently verifies that it equals the canonical snapshot for the supplied Root revision, event, activation intervals, and runtime registry.

The implementation-baseline fixture-package identity is:

```text
sha256:de65cf1ba53e5408f804513691434102b41cb33a95cbf8412ae890d8e28ad982
```

The package contains 78 normative vectors, 69 behavior fixtures, and 58 gas fixtures. The behavior-fixture count is not required to equal the vector count because one executable fixture may cover several inseparable normative assertions.


---

## 16. Worked Examples

### 16.1 Lazy selected workflow

```yaml
contracts:
  buyerChannel:
    type: Example External Channel
    source:
      blueId: <buyer-source>

  approve:
    type: Example Lazy Operation Handler
    channel: buyerChannel
    operation: approve
    steps:
      blueId: <approve-steps>

  cancel:
    type: Example Lazy Operation Handler
    channel: buyerChannel
    operation: cancel
    steps:
      blueId: <cancel-steps>
```

For an `approve` event, the processor recognizes every effective contract type and the relevant dispatch fields. It opens `<approve-steps>` only after the approve Handler matches. `<cancel-steps>` remains collapsed.

### 16.2 One deep external delivery

```text
Root
├── unrelatedA
├── Emb1
│   ├── unrelatedB
│   └── Emb2
│       ├── unrelatedC
│       └── Emb3
└── unrelatedD
```

The feeder index identifies one preselected Channel at `/Emb1/Emb2/Emb3`. The processor demands:

```text
Root direct manifest
Emb1 direct manifest
Emb2 direct manifest
Emb3 direct manifest
required effective type/contract headers on that chain
selected Handler body and data it reads
changed nodes on the path back to Root
```

It does not semantically demand `unrelatedA`, `unrelatedB`, `unrelatedC`, or `unrelatedD` bodies.

### 16.3 Root-only events

Suppose:

```text
Emb3 receives external X
Emb3 emits A
Emb2 observes A and emits B
Emb1 observes B and emits C
Root observes C, patches /status, and emits nothing
```

The successful result is:

```text
ProcessResult.document = Root'
ProcessResult.events   = []
```

If Root explicitly emits `D`, the result is:

```text
ProcessResult.events = [D]
```

### 16.4 Several selected scopes

If the same event is preselected at:

```text
/Emb1/Emb2/Emb3
/Emb1/Emb2
/Emb1
/
```

the external order is:

```text
Emb3 -> Emb2 -> Emb1 -> Root
```

The complete Emb3 delivery, update cascades, and internal event propagation reach quiescence before Emb2 receives the original event. Root receives the original event last. One late failure rolls the complete Root transition back.

### 16.5 Reference-backed patch

Initial logical content:

```yaml
x:
  blueId: <X>
```

where `X` directly contains:

```yaml
a: 1
archive:
  blueId: <HUGE>
```

Patch:

```yaml
op: replace
path: /x/a
val: 2
```

The processor opens Root and `X`, preserves `<HUGE>` by BlueId, creates `X2`, rebuilds Root, and never demands the archive body.

### 16.6 Active-scope cut-off

A child Handler returns:

```text
patch /child/value
patch /child/other
emit ChildCompleted
```

The first patch causes a Root Document Update Handler to replace `/child` as a whole. The old child occurrence is cut off. The replacement and already applied first patch/cascade remain tentative, but the old child's second patch, `ChildCompleted`, checkpoint, and later marker writes are discarded.

An event that the old child had already emitted before replacement still continues through its frozen ancestor chain.

### 16.7 Checkpoint domain

Channel version A at key `buyer` processes event `E`:

```text
entries.buyer.domain  = domain(A)
entries.buyer.subject = E
```

A later Root replaces the effective channel contributions at `buyer` with semantically different version B. `domain(B) != domain(A)`, so B sees virtual empty checkpoint state. It does not accidentally inherit A's stale subject.

### 16.8 New subscription frontier

Event `A@100` adds a new external-source Channel while that source already contains `B@50`.

The new interval begins strictly after `A@100`. `B@50` is not delivered retroactively. An initial Root admission that intends historical replay must declare a historical frontier explicitly.

### 16.9 Autonomous linked Root

If two managed documents must observe one independently evolving object, that object is another managed Root:

```text
SharedRoot processes and commits its own events.
RootA observes SharedRoot events later.
RootB observes SharedRoot events later.
```

It is not duplicated as one owned embedded occurrence that magically mutates under both parents.

---

## Appendix A — Core Runtime Type Catalog

The canonical runtime registry is the authority for exact source nodes and BlueIds. The definitions below state required semantics and intended identity-bearing fields.

### A.1 Contract

Base type for all runtime declarations under `contracts`.

Required semantics:

```text
order: optional Integer, default 0
```

A concrete subtype declares one exact runtime role.

### A.2 Channel

Base Contract subtype that produces one channelized delivery or rejects an event.

Processor-managed Channel subtypes receive only their processor event family. External Channel subtypes define the functions in §3.3.

### A.3 Handler

Base Contract subtype with:

```text
channel: required Text raw same-scope channel key
order: optional Integer
```

A concrete subtype defines matcher, executable body, and runtime counter schedule.

### A.4 Marker

Base Contract subtype for deterministic processor state or policy. Marker values do not execute as ordinary Handlers.

### A.5 Json Patch Entry

```yaml
name: Json Patch Entry
op:
  type: Text
  schema:
    enum: [add, replace, remove]
path:
  type: Text
val:
  description: Required for add/replace; absent for remove.
```

### A.6 Contract Execution Result

```yaml
name: Contract Execution Result
patches:
  type: List
  itemType: Json Patch Entry
events:
  type: List
termination:
  description: Optional deterministic termination request.
runtimeLedger:
  description: Optional named child ledger when the runtime did not debit the shared meter directly.
```

### A.7 Process Embedded

Marker at `contracts/embedded`:

```yaml
name: Process Embedded
paths:
  type: List
  itemType: Text
  schema:
    uniqueItems: true
```

Only `paths` is application-changeable, under the protected-state exception.

### A.8 Processing Initialized Marker

Direct processor state at `contracts/initialized`:

```yaml
name: Processing Initialized Marker
document:
  description: >
    Exact pre-initialization scope document. This is the initial document for
    the scope's processing lifecycle. It may be materialized inline or
    represented as an equivalent pure { blueId: ... } reference.
```

### A.9 Processing Terminated Marker

Direct processor state at `contracts/terminated`:

```yaml
name: Processing Terminated Marker
cause:
  type: Text
reason:
  type: Text
```

The marker is written only by graceful termination.

### A.10 Channel Event Checkpoint

Direct processor state at `contracts/checkpoint`:

```yaml
name: Channel Event Checkpoint
entries:
  type: Dictionary
  valueType:
    domain:
      description: Exact checkpoint-domain node or pure reference.
    subject:
      description: Exact checkpoint subject, normally a pure reference.
```

Raw contract keys remain raw dictionary keys. Pointer escaping is used only to address them.

### A.11 Type Generalization Rule

```yaml
name: Type Generalization Rule
path:
  type: Text
mode:
  type: Text
  schema:
    enum: [nearest-valid-ancestor, reject]
mustRemainSubtypeOf:
  description: Optional exact type node or pure reference.
```

### A.12 Type Generalization Policy

Marker at `contracts/generalization`:

```yaml
name: Type Generalization Policy
defaultMode:
  type: Text
  schema:
    enum: [nearest-valid-ancestor, reject]
rules:
  type: List
  itemType: Type Generalization Rule
```

### A.13 External Channel

Channel subtype with registered immutable dispatch header, subscription keys, event keys, preselection, acceptance, payload, checkpoint-domain, and checkpoint-subject functions.

Core requires acceptance to be independent of mutable Root state.

### A.14 Document Update Channel

Processor Channel with:

```yaml
name: Document Update Channel
path:
  type: Text
```

It receives Document Update payloads for equal-or-descendant changed paths relative to its scope.

### A.15 Triggered Event Channel

Processor Channel receiving application events emitted in the same scope.

A concrete subtype may declare an event pattern or type discriminator.

### A.16 Lifecycle Event Channel

Processor Channel receiving Document Processing Initiated or Document Processing Terminated.

### A.17 Embedded Node Channel

Processor Channel receiving Embedded Event Delivery for descendant emissions. It may declare:

```text
sourcePath: optional relative source-scope pattern
 event: optional event pattern
```

### A.18 Document Update

Processor event type with:

```text
op
path
beforePresent
before when present
afterPresent
after when present
sourceScopePath
```

### A.19 Embedded Event Delivery

Processor channelized payload with:

```text
sourcePath
event exact node
```

It is not automatically emitted by the receiving scope.

### A.20 Document Processing Initiated

Lifecycle event with:

```text
document exact pre-initialization scope document
```

The document may be inline or an equivalent pure reference.

`$processingEvent` remains the original external event.

### A.21 Document Processing Terminated

Lifecycle event with:

```text
cause
reason optional
```

### A.22 Reserved keys

```text
embedded       Process Embedded
initialized    Processing Initialized Marker
terminated     Processing Terminated Marker
checkpoint     Channel Event Checkpoint
generalization Type Generalization Policy
```

Processor marker types MUST appear only at their reserved keys. Application Contracts may not impersonate them elsewhere.

---

## Appendix B — Status and Diagnostic Categories

### B.1 Statuses

The status names and commit behavior are defined in §12.1.

### B.2 Diagnostics

A conforming implementation MUST classify deterministic failures into at least these categories:

```text
InvalidProcessingDocument
InvalidProcessingEvent
InvalidRuntimePointer
InvalidPatch
PatchBoundaryViolation
ProtectedProcessorStateMutation
InvalidReservedRuntimeState
UnsupportedRuntimeType
UnsupportedRuntimeRole
InvalidContractKey
InvalidContractBinding
InvalidExternalChannelSnapshot
ExternalSubscriptionLawViolation
EmbeddedRouteNotFound
EmbeddedScopeNotObject
EmbeddedScopeCycle
ActiveScopeCutOff
CheckpointDomainError
CheckpointPolicyError
FixedValueConflict
TypeCompatibilityViolation
SchemaViolation
TypeGeneralizationFailure
CyclicSetMutationUnsupported
CyclicMemberProcessingRootUnsupported
CyclicMemberProcessingEventUnsupported
CyclicSetEmbeddedBoundaryUnsupported
DirectNodeLimitExceeded
MatchingDeliveryLimitExceeded
ParticipatingScopeLimitExceeded
InternalEventLimitExceeded
PatchLimitExceeded
RuntimeLedgerLimitExceeded
SubscriptionSurfaceInvalid
RuntimeExecutionFailure
GasLimitExceeded
```

`ActiveScopeCutOff` is normally an internal reason for discarding buffered effects rather than a top-level failure.

Diagnostic strings are informative. Category, relevant scope/key/path, and numeric limit values are normative for fixtures.

---

## Appendix C — Canonical Gas Trace Pseudocode

```text
function CHARGE(namespace, counter, quantity, context):
    require quantity is a non-negative Integer
    if quantity == 0:
        return

    weight = GAS_MANIFEST[namespace, counter]
    subtotal = quantity * weight

    if RUN.totalGas + subtotal > MAX_PROCESS_GAS:
        throw GasLimitExceeded without adding the entry

    append GasTraceEntry(
        sequence = RUN.gasTrace.length,
        namespace = namespace,
        counter = counter,
        quantity = quantity,
        weight = weight,
        subtotal = subtotal,
        context = deterministic subset of context
    )

    RUN.totalGas += subtotal
```

Canonical processor algorithms call `CHARGE` immediately before the work described by the counter. Runtime child ledgers use the same rule and remaining budget.

Run-local reuse maps are semantic parts of the trace algorithm:

```text
openedManifestIds
recognizedContractSnapshots
validationProofKeys
establishedNewNodeIds
```

They are initialized empty on every invocation. Hidden caches do not seed them.

Provider acquisition and verification happen before an exact node is inserted into these semantic maps and are not portable charges.

---

## Appendix D — Common Implementer Mistakes

### D.1 Do not process children as separate authoritative sessions

There is one Root. Deep changes are tentative nodes on the path to one tentative new Root.

### D.2 Do not return child events

Child emissions are internal unless Root explicitly emits.

### D.3 Do not build a public effect log

The event FIFO and update cascades are run state. They are not a semantic output.

### D.4 Do not rescan every embedded branch

The feeder maintains the complete incremental index. The processor revalidates selected paths only.

### D.5 Do not make the feeder snapshot caller-authored Blue content

It is revision-bound derived environment metadata, not a third event field.

### D.6 Do not let External Channel acceptance read mutable Root state

Business conditions belong in Handlers. Otherwise preselection cannot be stable and complete.

### D.7 Do not expose reference wrappers

Runtime access is representation-blind. Exact identity uses an explicit identity operation.

### D.8 Do not charge recursive payload size

Existing exact nodes are cheap to carry. Charge construction, inspection, validation, and changed direct identity.

### D.9 Do not skip ancestor validation

A deep patch must leave every rebuilt ancestor and Root sound.

### D.10 Do not initialize on rejection or stale-only processing

Acceptance and checkpoint newness precede initialization.

### D.11 Do not write markers into replacement scopes

Check active-scope cut-off after every nested cascade and before every marker/checkpoint write.

### D.12 Do not key checkpoint semantics by raw key alone

Checkpoint domain binds the key to the effective Channel semantics.

### D.13 Do not commit a Root that cannot be indexed

Validate the changed subscription delta before returning success.

### D.14 Do not double-drain the event queue

Only the normative queue owner drains. Helpers enqueue and return.

### D.15 Do not confuse hosted work with portable gas

Provider bytes, signatures, storage, index maintenance, and CAS retries are host resources, not portable Contracts counters.

---

*End of Blue Contracts and Processor Specification 1.0.*
