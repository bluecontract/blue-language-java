# Blue Contracts and Processor Specification 1.0

> **Status.** Final normative specification. This release defines deterministic processing for ordinary acyclic Roots and bounded finite cyclic `Process Embedded` closures. The machine-readable runtime registry, gas manifest, conformance fixtures, and release manifest distributed with this document are part of the release.

> **Scope.** This document defines contracts, Channels, Handlers, exact processing inputs, managed document boundaries, finite directed `Process Embedded` graphs, deterministic activation initialization, dynamic graph formation, cyclic processing, patches, Document Updates, internal events, checkpoints, lifecycle, termination, gas, exact failure semantics, and atomic publication. Blue content, BlueId, typing, resolution, canonicalization, and cyclic-set identity are defined by **Blue Language Specification 1.0**. Concrete business runtimes and external coordination policies are selected separately by exact identity.

> **Compatibility.** No existing core runtime type node changes in this release. `Process Embedded`, `Channel`, processor-managed Channels, lifecycle types, and their released BlueIds remain unchanged. The Contracts specification, fixture package, gas manifest, and implementation release identities bind the processing behavior defined here.

Blue Language defines exact content and identity. Blue Contracts defines how exact content changes deterministically when one exact cause is processed.

## Conventions

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, **MAY**, and **OPTIONAL** are normative requirement levels.

Sections marked **normative** define required behavior. Sections marked **informative** explain intent or implementation guidance.

The term **Language** means Blue Language Specification 1.0.

---

## 0. Overview

### 0.1 One affected closure is one reality

The ordinary case changes one authoritative object Root:

```text
Root
├── Customer
├── Payment
└── Delivery
```

`Process Embedded` declares processable document boundaries inside that reality. The concrete graph is finite and directed. Most graphs are acyclic and use the ordinary deepest-first fast path.

A graph may also contain a finite strongly connected region:

```text
Order A  ──/payment──▶  Payment B
   ▲                       │
   └──────/order───────────┘
```

A strongly connected region is one **cyclic processing component**. Every member may have its own contracts, local state, Timeline-facing Channels, lifecycle state, and checkpoints, but the members are not independently publishable while one cause is being processed through that component.

One invocation operates on one exact **affected closure**: the directly addressed managed document occurrences, every document and occurrence required by `Process Embedded` causality, every containing document whose exact reference must change, and every dynamically admitted member required before the invocation can finish. The closure may contain one or more acyclic documents and one or more cyclic components connected by an acyclic condensation graph.

A successful invocation publishes one coherent resulting closure. An acyclic changed document receives a new ordinary BlueId. A cyclic component receives one exact cyclic-set `MASTER` and exact `MASTER#index` member identities calculated by Blue Language §15. Every required containing reference and changed ancestor spine is rebuilt before publication. Unchanged branches retain their existing exact identities.

Physical separation, lazy loading, caching, stable application `DocumentId` values, or invocation-local data structures do not create independent semantic commit boundaries.

#### 0.1.1 Separate document execution inside one closure

Contracts distinguishes three units:

```text
execution unit:   one managed document step
scheduling unit:  one affected closure
publication unit: one successful closure result
```

Every application runtime call in an affected closure targets exactly one
managed `DocumentId`. That call receives that document as `$document`, uses `/`
as its managed-document Root scope under the Contracts 1.0 closure profile, and
returns effects for that document only. The call does not receive the documents
that contain it, reverse embedding paths, a count of containing documents, or an
ambient parent/component object.

The closure orchestrator owns graph traversal, work ordering, occurrence
bindings, shared gas, exact identity finalization, and atomic publication. It
may schedule the same document more than once when distinct exact work
occurrences reach it. It stages each document result separately and publishes
the complete required closure only after all work is quiescent and valid.

A cyclic component does not use a different document runtime. A member is
processed by the same one-document step used in an acyclic graph. Cyclicity
changes only the orchestrator's scheduling and the identity boundary: after an
identity-affecting member step, the orchestrator re-finalizes the complete
component before the next document step observes it. There is no
`PROCESS_CYCLIC_DOCUMENT`, no ambient cycle binding, and no Handler-visible SCC
object.

Thus **documents are processed separately, while the required closure is
published together**. This is implementable by an in-memory engine or by a
durable host such as MyOS that stores one current head and epoch history per
managed document and installs all staged heads in one closure commit.

### 0.2 Processor boundary

The ordinary normative operation is:

```text
PROCESS(document, event, environment) -> ProcessResult
```

The normative internal execution decomposition is:

```text
PROCESS_DOCUMENT_STEP(stepInput, sharedMeter) -> LocalDocumentStepResult
```

`PROCESS_DOCUMENT_STEP` is not another authored event protocol and need not be
a public API method. It specifies the required isolation boundary used by
closure processing: one exact target document, one exact work occurrence, one
shared remaining gas allowance, and one local result for that target document. A
conforming implementation may inline or fuse this call physically, but every
Handler-visible value, work trace, gas context, and result MUST be equivalent to
this one-document decomposition.

The complete-closure attempt operation is:

```text
PROCESS_CLOSURE(invocationInput) -> ClosureAttemptResult
```

Deterministic admission and initialization without an external event use:

```text
ADMIT_CLOSURE(invocationInput) -> ClosureAttemptResult
```

`invocationInput` is the one closed §2.2 value. It contains one authoritative
state-only affected-closure snapshot and one set of invocation adjuncts. The
external event is carried exactly once by `ExternalCause`; an admission cause
is carried exactly once by the admission form; a managed-revision invocation
carries exactly one `ManagedRevisionCause` and no event. An API MUST NOT accept
a second cause, event, direct-delivery snapshot, execution policy, or
environment value whose equality with the closed input would be ambiguous.

Both closure operations return exactly the closed §2.5 union. `Complete`
contains one `ClosureProcessResult`; `NeedsResources` contains only the sorted
exact identities still required and is not a completed processor result. No
closure API returns a bare `ClosureProcessResult` across the resource-acquisition
boundary.

`PROCESS` is the optimized one-document special case of the same laws. A conforming implementation MAY internally route an ordinary invocation through the closure engine, but existing ordinary result, gas, and fixture behavior MUST remain unchanged.

For `PROCESS`:

- `document` is the exact current ordinary Root;
- `event` is the original exact external event selected by the managing feeder;
- `ProcessResult.document` is the exact resulting Root;
- `ProcessResult.events` contains only events emitted by that Root;
- all tentative effects either commit together or are discarded.

For `PROCESS_CLOSURE`:

- `invocationInput.snapshot` contains the complete exact managed-document state required for one connected atomic reality, including occurrence bindings, graph generation, current component partition, current complete cyclic proofs where applicable, and public-Root declarations;
- `invocationInput.directDeliveries` is the one frozen direct logical-delivery
  sequence and is empty for a managed-revision invocation;
- `invocationInput.cause` is either the external cause whose event is the
  original exact external event and is never rewritten into an internal
  reaction envelope, or one exact contiguous managed-revision cause with no
  event;
- the invocation may begin acyclic and may form, merge, split, or dissolve cyclic components through ordinary exact document patches;
- direct work already completed before a graph reclassification is not replayed;
- every accepted work occurrence is executed as one isolated managed-document step; the target document is the only `$document` and no reverse-containment evidence is visible to application runtime code;
- acyclic and cyclic members use the same document-step processor; component finalization is orchestrator/identity work between steps, not a different Handler execution mode;
- the invocation owns one shared gas ledger, one deterministic work identity domain, and one atomic publication boundary;
- `ClosureAttemptResult.Complete.result` is the `ClosureProcessResult` containing
  every changed exact document, final component partition, exact cyclic proofs,
  containing-Root results, public Root event occurrences, graph and subscription
  deltas, checkpoints, gas, and deterministic diagnostics; `NeedsResources`
  carries none of those completed-result fields.

For `ADMIT_CLOSURE`:

- there is no external or fabricated Timeline Entry, provider timestamp, source
  cursor, or checkpoint input;
- `invocationInput.cause` is the one exact admission cause identifying the admitted closure and policy;
- `invocationInput.directDeliveries` is the exact empty sequence; zero direct
  external deliveries are required and valid;
- it returns the same closed `ClosureAttemptResult` union as `PROCESS_CLOSURE`;
- initialization, lifecycle, Document Update, application-event, termination,
  graph-formation, and containing-reference work use the same canonical work
  queue, event queue, immediate continuations, identity constructors, shared gas
  ledger, soundness rules, and rollback boundary as `PROCESS_CLOSURE`;
- admission never compares, creates, advances, cleans up, or commits a Timeline
  checkpoint, and its `checkpointWrites` result sequence is empty;
- every initialization-caused patch, event, termination, graph change, marker,
  public event, and finalization is tentative and belongs to the one atomic
  admission result;
- each initializing managed document executes as its own isolated document step and cannot inspect the document or occurrence that caused its admission except through exact content or the explicitly available causal `$processingEvent` rules.

The public Java entry point that claims this full-lifecycle operation is
`admitClosureWithLifecycleQueue(ClosureInvocationInput)`. The existing
`admitClosure(ClosureInvocationInput)` entry point is a bounded compatibility
helper retaining its released low-level behavior. It is not a conforming
substitute for `ADMIT_CLOSURE` when initialization causes Document Updates,
application events, lifecycle termination, or further queued work. Both methods
consume an input whose protocol operation is `ADMIT_CLOSURE`; the compatibility
method does not define another protocol operation, model value, or identity
domain, and the invocation constructor continues to hash the exact literal
`admit-closure`.

The closure snapshot, direct-delivery snapshot, occurrence bindings, proofs,
admission cause, and managed-revision cause are verified platform evidence.
They are not authored Blue business content and are not additional application
events.

### 0.3 Feeder and processor

The managing feeder connects external evidence and deterministic processing.

```text
Feeder:
  discovers active External Channels;
  obtains complete and canonically ordered external evidence;
  evaluates host/profile eligibility and authority;
  freezes exact direct logical deliveries for the selected cause;
  freezes managed occurrence bindings and graph-generation evidence;
  supplies complete cyclic-set proof for every current cyclic component;
  makes exact nodes and deterministic resources available;
  atomically installs a successful processor result.

Processor:
  revalidates the frozen evidence against exact current state;
  recognizes every required runtime type before first mutation;
  opens only the affected closure and demanded data;
  processes acyclic components deepest-first;
  processes cyclic components through exact occurrence work to quiescence;
  tentatively re-finalizes affected identities before later work observes them;
  returns one exact ordinary or closure result.
```

External eligibility, institutional authority, source completeness, provider signatures, and historical entry selection are feeder responsibilities. The processor neither queries providers nor searches external authority state. It receives the original exact event and verified execution evidence.

The feeder snapshot is derived metadata, not caller-authored Blue content. For one exact managed state, event, runtime registry, graph generation, occurrence-binding set, external-order policy, and gas-policy identity, the canonical snapshot is unique.

An occurrence or Channel introduced by the current external event does not become a new direct external recipient of that same event. It may require deterministic activation initialization and may receive caused Document Updates or internal event occurrences before commit.

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

An exact value accepted at a managed occurrence path may therefore be:

- the pure one-member reference `{ blueId: X }`;
- an inline acyclic node whose independently established direct BlueId is `X`;
- a materialized cyclic member whose `MASTER#index` is established by the
  complete verified owning cyclic-set proof.

These forms are semantically identical when they establish the same exact
identity. The processor MUST apply the same binding, graph, Handler, result,
provider-demand, and gas rules to all of them. A syntactic object that contains
`blueId` together with any other member is the invalid mixed reference form; it
is not a materialized exact node and MUST NOT be accepted as one.

The processor may open one path while siblings remain collapsed. Contract dispatch fields may be visible while executable bodies remain behind BlueId references. A patch rebuilds the changed direct node and its ancestor spine to Root. Physical prefetch is allowed, but unrelated prefetched content MUST NOT become semantic demand, contract discovery, result content, or portable gas.

### 0.6 Core invariants

A conforming implementation MUST preserve all of these invariants:

1. `PROCESS` receives one exact ordinary Root and one exact external event.
   `PROCESS_CLOSURE` receives one closed invocation input containing one exact
   finite affected-closure snapshot and either one exact external cause or one
   exact contiguous managed-revision cause. `ADMIT_CLOSURE` receives one closed
   invocation input containing one exact finite snapshot and one exact
   admission cause.
3. A successful closure invocation has one atomic publication boundary. No cyclic member, containing Root, checkpoint, subscription delta, event outbox, occurrence binding, or graph generation from that invocation becomes authoritative independently.
4. Every active `Process Embedded` edge is represented by exact content at the declared source path and one verified managed occurrence binding. The platform evidence does not create a second authored graph.
5. The concrete graph is finite. Acyclic regions use the ordinary fast path. Every supported cycle belongs to a complete verified strongly connected component inside the affected closure.
6. Inline, referenced, expanded, collapsed, warm, cold, batched, segmented, and physically factorized representations produce identical status, exact result identities, event occurrences, checkpoints, subscription deltas, provider demands, and portable gas.
7. Every required effective contract type and occurrence binding in the initial closure, and every dynamically expanded closure region, is recognized and preflighted before its first mutation or Handler execution.
8. Patches use persistent copy-on-write. Unchanged exact children remain reusable by BlueId. An implementation may retain invocation-local handles internally, but application-visible reads always resolve to the latest exact tentatively finalized Blue value.
9. After every identity-affecting patch or processor-managed graph rewrite, the affected component and required ancestor spine are tentatively finalized before the next work occurrence may observe them.
10. Tentative cyclic finalization invokes the unchanged Blue Language cyclic-set algorithm over the complete affected strongly connected set. Temporary `MASTER#index` values are exact but nonauthoritative until commit.
11. Graph discovery does not create deliveries. Direct deliveries arise only from the frozen direct-delivery snapshot. Caused deliveries arise only from exact Document Update, event, lifecycle, or initialization occurrences.
12. Exact event occurrence order and multiplicity are preserved. Equal event values emitted twice remain two occurrences.
13. A node-level visited set MUST NOT suppress a later exact work occurrence addressed to the same document. Work identity, frozen target sets, quiescence, portable limits, and shared gas bound cyclic processing.
14. Checkpoints and lifecycle markers remain tentative and publish only with the
    successful closure. Initialized markers use the whole-component batch, and
    external checkpoints use the post-causal-closure settlement barrier.
    `ADMIT_CLOSURE` never enters that checkpoint barrier and returns the exact
    empty checkpoint-write sequence.
15. Newly activated processable occurrences initialize deterministically before the introducing closure may commit, unless exact valid initialization evidence already exists.
16. The introducing external event is never redelivered as a direct event to a newly activated occurrence.
17. One closure invocation uses one exact finite default gas limit when no lower exact limit is bound. A document-authored or host-supplied limit may lower the available allowance but may not create another independent meter or silently raise the release maximum.
18. Only declared public Roots contribute public result events. Internal member, descendant, Document Update, and propagation events remain internal unless a public Root explicitly emits them.
19. A deterministic failure, invalid proof, gas exhaustion, or portable-limit failure returns the exact authoritative input state, no public events, and the admitted canonical gas prefix.
20. Processing may open the complete affected cyclic component and changed ancestor spine, but MUST NOT semantically scan unrelated documents, components, contracts, or histories.
21. The runtime registry, gas manifest, Language cyclic-set implementation identity, fixture package, and implementation release identity used for an invocation are auditable release evidence.

### 0.7 One external event at a glance (informative)

```text
feeder selects exact event E
      |
      v
freeze direct deliveries and affected-closure evidence
      |
      v
verify exact documents, occurrence bindings, graph and proofs
      |
      v
order direct seeds by condensation order and managed scope key
      |
      v
execute each work occurrence as one isolated document step
      |
      v
process first seed and every caused update/event to quiescence
      |
      v
when a patch changes graph or identity:
  expand/preflight closure if required
  repartition components
  tentatively finalize exact identities
  create exact update occurrences
      |
      v
process remaining direct seeds
      |
      v
validate final documents, proofs, subscriptions, checkpoints and limits
      |
      v
atomically publish complete result or publish nothing
```

For an ordinary acyclic document, this reduces to the familiar deepest-first one-Root algorithm.

### 0.8 Key terms (informative)

| Term | Meaning |
|---|---|
| **Managed document** | One exact object document addressed by a stable platform `DocumentId`; it may be a public Root, an embedded document, or a cyclic member. |
| **Document step** | One isolated execution of one exact work occurrence against one managed document Root. It has no ambient containing-document context and stages one tentative result for that document. |
| **DocumentId** | A platform identifier for continuing document lineage. It is not a BlueId and is not a new Blue Language primitive. |
| **Managed occurrence** | One stable active `Process Embedded` source-path/activation/target-lineage identity plus a separate current exact-target binding identity. |
| **Affected closure** | The finite connected set of managed documents, occurrences, components, and containing Roots that must settle atomically for one cause. |
| **Cyclic processing component** | One strongly connected component of the concrete active `Process Embedded` graph. |
| **Tentative finalization** | Exact nonauthoritative recalculation of changed ordinary and cyclic identities before later work observes them. |
| **Direct seed** | One frozen external logical delivery selected by the feeder. |
| **Caused work** | One exact update, event, lifecycle, initialization, or containing-reference occurrence created by prior work. |
| **Public Root** | A managed document whose explicit Root emissions appear in the returned public event stream. |
| **Graph generation** | A committed monotonically increasing platform generation for the active occurrence set. Ordinary business-state changes do not increment it. |
| **Activation generation** | A committed monotonically increasing lineage for one source-document/path occurrence; remove and later re-add starts a new generation. |
| **Component generation** | A committed generation for one component membership/edge partition. It changes only on formation, merge, split, or dissolution. |
| **Quiescence** | No direct seed, immediate Document Update continuation, lifecycle work, or queued internal event remains for the closure. |

### 0.9 Reusable embedded process modules (informative)

A reusable process document can be embedded under many containing documents. Its exact content may be stored once by BlueId. Stable `DocumentId` evidence determines whether two occurrences denote one continuing managed document or two independent lineages.

```text
same DocumentId + same BlueId
    same managed document and same exact state

same DocumentId + different BlueId
    different exact states of one managed document lineage

different DocumentId + same BlueId
    distinct managed documents that currently have equal exact content
```

The same managed document may appear at several paths. It has one tentative semantic state in one closure, while each containing occurrence has its own source path, activation generation, update delivery, and checkpoint/occurrence evidence.

A cyclic relationship uses ordinary `Process Embedded` paths in both directions. No second authored link protocol is introduced.

## 1. Scope, Versioning, Registry, and Conformance

### 1.1 Goal

Blue Contracts and Processor 1.0 defines:

- exact ordinary Root processing;
- exact finite affected-closure processing;
- deterministic admission and initialization without fabricated external events;
- revision-complete feeder snapshots and exact direct logical delivery grouping;
- managed document, scope, occurrence, graph, and component identity at the platform boundary;
- finite directed `Process Embedded` graphs;
- acyclic deepest-first processing;
- bounded cyclic component processing using Blue Language cyclic-set identity;
- dynamic formation, expansion, merge, split, and dissolution through ordinary exact patches;
- tentative exact finalization at every observation boundary;
- deterministic patch, Document Update, event, lifecycle, and direct-seed order;
- checkpoints, termination, gas, rollback, soundness, and atomic publication;
- exact conformance fixtures for ordinary and closure processing.

The ordinary acyclic path remains the required fast path and retains the existing released runtime type nodes.

### 1.2 Out of scope

This specification does not define:

- Timeline provider protocols, signatures, completeness proof formats, or institutional trust;
- external target eligibility or delegated-authority resolution;
- historical-entry acquisition, readiness APIs, catch-up storage, or operator workflow;
- database schemas, distributed transactions, worker leasing, or multi-region coordination;
- a Blue Language `DocumentId` type;
- arbitrary graph isomorphism for indistinguishable cyclic members;
- cyclic external events;
- BEX syntax or operator semantics;
- business-specific Timeline, Operation, workflow, actor, or authority types.

A higher coordination layer selects external evidence, binds stable managed identity, supplies exact closure evidence, and publishes successful results. Contracts defines the deterministic transition after those inputs are fixed.

### 1.3 Version selection

A document does not carry a required `contractsVersion` or `processorVersion`. The managed execution environment selects this Contracts 1.0 release before processing. Concrete runtime semantics are selected by exact runtime-type BlueId and separately published bindings.

A closure-capable environment additionally binds:

```text
managed-document identity policy identity
managed-occurrence binding policy identity
closure ordering policy identity
cyclic-set finalizer implementation identity
cyclic-set proof verifier identity
default gas-policy identity
```

An implementation MUST NOT silently process a cyclic member under an isolated ordinary `PROCESS` call. Missing complete closure evidence fails closed or returns `NeedsResources` before semantic mutation.

### 1.4 Runtime registry

The canonical runtime registry is part of the Contracts 1.0 release. For every core or portable runtime type it MUST publish:

- exact canonical Blue node and BlueId;
- runtime role;
- dispatch fields and executable-body fields;
- exact subscription functions for an External Channel;
- checkpoint-domain semantics;
- exact execution semantics or binding to another published specification;
- named runtime counters and weights when executable;
- deterministic limits and diagnostic categories;
- conformance fixtures that exercise the type.

Registry source, calculated BlueIds, prose, fixtures, and gas manifest MUST agree. Implementations MUST NOT guess when they conflict.

The core runtime registry package identity remains the identity published in the machine-readable registry manifest distributed with this release. No existing core runtime node is changed by cyclic closure support.

The closure executor and cyclic-set finalizer are processor/platform capabilities, not new application runtime types. The release manifest MUST bind the exact Blue Language cyclic-set implementation and proof verifier used by closure processing.

### 1.5 Conformance

A conforming implementation MUST:

- implement every normative rule in this document;
- use Blue Language 1.0;
- recognize the canonical core runtime BlueIds;
- implement ordinary `PROCESS` and platform commit obligations;
- implement `PROCESS_CLOSURE` and `ADMIT_CLOSURE` to claim cyclic/closure conformance;
- implement normative `ADMIT_CLOSURE` through the full lifecycle queue exposed
  by `admitClosureWithLifecycleQueue`; availability of only the bounded
  compatibility `admitClosure` helper does not satisfy this requirement;
- support every processor-managed type in Appendix A;
- revalidate exact feeder, occurrence-binding, graph, and proof evidence;
- implement every platform identity exactly from `conformance/contracts/identity-constructors.yaml`;
- produce the canonical named gas trace in conformance mode;
- pass every fixture required by the claimed conformance class;
- report the exact Language, Contracts, registry, gas-manifest, cyclic-finalizer, fixture-package, and implementation identities.

The fixture package defines these classes:

```text
contracts-core
    ordinary PROCESS and existing acyclic behavior

contracts-closure
    PROCESS_CLOSURE, ADMIT_CLOSURE, dynamic graph changes, cyclic processing,
    exact tentative finalization, and closure atomicity
```

A combined system MAY claim both classes. A library implementing only one component MUST describe that component precisely and MUST NOT claim full platform conformance.

## 2. Processing Inputs, Environment, Result, and Atomicity

### 2.1 Ordinary Processing Document

For `PROCESS`, `document` is an admitted exact Blue node. It MAY be inline, a pure reference, or partially materialized, but its exact Root BlueId MUST be established before semantic execution. The logical Root MUST be an object node.

The Processing Document need not be a complete Resolved Form or a globally closed graph. Contract fields, type contributions, schemas, values, executable bodies, and unrelated branches are resolved on demand.

### 2.2 Closure Invocation Input and Affected Closure Snapshot

For `PROCESS_CLOSURE` and `ADMIT_CLOSURE`, the authoritative affected-closure
snapshot is immutable verified platform **state**. Cause, routing, resource,
policy, and environment evidence belong to the enclosing invocation input, not
to that durable state identity. The conceptual grouping is:

```text
ClosureInvocationInput {
    snapshot: AffectedClosureSnapshot {
        closureIdentity
        graphGeneration
        managedDocuments[] {
            documentId
            blueId
            document
            initialized
            terminated
            publicRoot
            epoch
            componentGeneration
        }
        occurrences[] {
            occurrenceIdentity
            bindingIdentity
            bindingPolicyIdentity
            sourceDocumentId
            sourcePath
            activationGeneration
            targetDocumentId
            expectedTargetBlueId
            active
            pendingHistoricalEpoch      # required nullable
        }
        occurrenceBindingSetIdentity    # required recomputed assertion
        currentComponents[] {
            componentIdentity
            componentStateIdentity
            componentGeneration
            kind: ACYCLIC | CYCLIC
            orderedMemberDocumentIds[]
            orderedMemberBlueIds[]
            masterBlueId?               # CYCLIC only
            cyclicProofIdentity?        # CYCLIC only
            completeCyclicProof?        # CYCLIC only
        }
        publicRootDocumentIds[]
    }
    directDeliveries[]                  # empty for ADMIT_CLOSURE
    directDeliverySnapshotIdentity      # required recomputed assertion
    cause                              # closed ExternalCause | ManagedRevisionCause | AdmissionCause
    admissionCandidate                # required nullable invocation-only adjunct
    admissionCandidateIdentity        # required nullable invocation-only adjunct
    gasPolicy
    environment
}
```

The conformance fixture wire format flattens these two conceptual groups into
one closed `input` object. Harness-only runtime behavior, shared-limit source,
provider availability/expectations, locality probes, limit generation, and
oracle-stage labels are separate top-level fixture fields. That serialization
convenience does not merge identity boundaries and MUST NOT create a second API
source for any normative invocation adjunct.

Every managed-document record is closed and contains all seven identity-bearing
fields `documentId`, `blueId`, `initialized`, `terminated`, `publicRoot`,
`epoch`, and `componentGeneration`; none is optional. `initialized` and
`terminated` are revalidated against their direct processor markers in the
exact document. They remain explicit snapshot and closure-identity assertions,
but neither Boolean substitutes for a missing or invalid marker.
Managed-document records are ordered by `documentId` under the comparison rule
below.

Every component record carries parallel `orderedMemberDocumentIds` and
`orderedMemberBlueIds` arrays of equal nonzero length. A cyclic record MUST
carry `masterBlueId`, `cyclicProofIdentity`, and the complete proof; an
acyclic record carries none of those three cyclic-only fields. Both component
identities MUST recompute from this closed evidence.

Every occurrence record is closed and contains all ten fields shown above;
`pendingHistoricalEpoch` is required and uses JSON `null` except for the exact
historical prospective-binding case below. The array is the one authoritative
occurrence-row set and contains both current rows (`active: true`) and verified
prospective rows (`active: false`). A feeder or fixture MUST NOT supply a second
staged-binding map outside this set.
The authoritative array, including the production result array, is sorted by
`(occurrenceIdentity, bindingIdentity)` under portable text order. This is the
same order used by `occurrenceBindingSetIdentity`; source-document/path order is
not a second serialization order.

Every active occurrence MUST correspond to an exact value at `sourcePath` in
the source document and to an effective `Process Embedded` declaration covering
that path. The exact value MUST resolve to `expectedTargetBlueId`. An inactive
row reserves verified lineage/binding evidence for a path that may become active
during this invocation; it is not an active edge, component-membership input,
delivery recipient, or initialization trigger. It need not yet exist in the
source Blue content or be covered by a current `Process Embedded` declaration.
If its path is already present, that exact value MUST nevertheless establish
`expectedTargetBlueId`; presence alone does not activate the row. When an exact
mutation activates that path, the processor
first verifies the inserted value and effective declaration against the row and
changes that same row to `active: true`; it does not copy or synthesize a row
from hidden state. Except for the exact inactive retirement successor derived
from an active input row under §5.6, every prospective row that may be used by
the invocation MUST already be present in the exact input snapshot. Deriving
that successor allocates no target or binding evidence: it carries the same
source path, target lineage, and binding policy at exactly the next generation.
Contracts has no dynamic binding acquisition, ambient lookup, inferred
lineage, or hidden staging map.
A patch that introduces an otherwise valid embedded value without one exact
matching input row fails deterministically.

`pendingHistoricalEpoch` is non-null only when the row's
`expectedTargetBlueId` is an admitted historical exact state of the named
target lineage. It is the safe-integer epoch of that exact cursor state. Before
the initial historical attachment the declared path may be absent. Once an
external Handler writes the exact historical value, the path is present but the
row MUST remain `active: false` while `pendingHistoricalEpoch` is non-null; this
is the sole exception to immediate activation of a present verified path. The
processor does not apply a transition chain inside that external invocation.
Instead, Coordination supplies one exact contiguous `ManagedRevisionCause`
invocation at a time under §2.3. Each successful invocation rebinds that same
row and advances the cursor by exactly one. Only the invocation that reaches
the authoritative target epoch performs final same-lineage reconciliation,
clears the cursor, and activates the row. The occurrence binding identifies
which stable managed document lineage the exact value denotes; an inactive row
never contributes a graph edge.

`occurrenceBindingSetIdentity` and `directDeliverySnapshotIdentity` are required
derived assertions over their respective complete arrays and MUST be recomputed
before the invocation input is accepted. A supplied mismatch fails closed.
Provider availability is not invocation input. If an exact node already named
by the closed snapshot, cause, direct delivery, or patch value is unavailable,
`NeedsResources` names that exact BlueId only. Retrying with only verified
harness/provider availability changed preserves both `inputClosureIdentity` and
`invocationIdentity`; changing any normative input does not. Contracts never
discovers a historical range, requests a range, or accepts a batch of revision
evidence.

Every `documentId` is an NFC nonempty Unicode string, case-sensitive, unique in
the managed environment identity domain, with no U+0000 and at most 512 UTF-8
bytes. A non-NFC value is rejected; the Contracts processor and its value
objects MUST NOT silently normalize it. A feeder MAY normalize text before
constructing the closed invocation, but the resulting NFC value is the only
admitted identity. Portable comparison is Unicode scalar/code-point sequence
order over that admitted string.

The managed `documentId` is platform lineage evidence. It is not inferred from an arbitrary application property named `documentId`. When an exact document also contains a property with that name, the property is ordinary identity-bearing Blue content unless a separately selected host/profile rule gives it additional meaning. The feeder MUST establish the closure-record `documentId` and MUST reject any mismatch required by its selected identity policy before the processor accepts the snapshot. Contracts scheduling, occurrence binding, checkpoints, and work identity use the verified closure-record value.

A closure MUST be finite and connected under required active occurrence and containing-reference obligations. A host processes disconnected closures as independent invocations.

The initial snapshot MAY be acyclic. Ordinary patches may expand the active occurrence set, merge components, form a new cycle, split a component, or dissolve a cycle. Dynamic expansion uses §5.5 and §7; it does not restart the invocation.

#### 2.2.1 Contracts 1.0 Root-scope closure profile

`PROCESS_CLOSURE` and `ADMIT_CLOSURE` in Contracts 1.0 execute closure work at
the managed Root of each participating `documentId`. This is a versioned
affected-closure profile boundary, not a removal of ordinary nested-scope
processing. Ordinary `PROCESS` retains the non-Root managed-scope, Channel,
Handler, lifecycle, checkpoint, and event semantics defined elsewhere in this
specification.

The closure profile is closed by these rules:

1. Every `directDeliveries` entry has `scopePath = /` and
   `activationGeneration = 0`.
2. Every accepted or rejected `WorkOccurrence` has
   `targetManagedScopeIdentity` equal to the
   `blue-contracts-managed-scope-key/1.0` identity of
   `{ documentId: targetDocumentId, scopePath: /, activationGeneration: 0 }`.
3. Every result `ChannelOccurrence` has `scopePath = /` and
   `scopeActivationGeneration = 0`. Every `SubscriptionDelta` and
   `CheckpointWrite` names the corresponding Root managed-scope identity.
4. Whenever a closure gas-trace context carries `scopePath` or
   `activationGeneration`, it carries both and they are exactly `/` and `0`.
   A work-owned trace entry therefore never substitutes an embedded occurrence
   generation for its target Root execution context.
5. A public event is eligible only when the emitting `WorkOccurrence` targets
   the Root managed scope of a document declared `publicRoot`; a nested
   emission inside such a document remains internal until Root explicitly
   emits it.
6. Any non-Root closure address or mismatched Root-scope identity in invocation
   input, frozen work, gas evidence, result projections, or commit evidence is
   rejected before it can become authoritative.

Closure work may still patch, activate, remove, or observe nested exact content
through managed occurrence and pointer evidence. Those content paths do not
change the work execution scope. A future non-Root closure profile requires a
separately versioned wire contract that closes active-scope inventory,
ownership, subscription, checkpoint, publication, component-generation, and
gas semantics; implementations MUST NOT infer that profile from the general
identity-constructor shapes.

#### 2.2.2 Managed document step boundary

The closure processor MUST be decomposable into a canonical sequence of managed
document steps. Conceptually:

```text
DocumentStepInput {
    invocationIdentity
    workOccurrenceIdentity
    workOrdinal
    targetDocumentId
    beforeBlueId
    exactDocument
    workKind
    exactPayload
    tentativeResolutionContext
    remainingSharedGas
}

LocalDocumentStepResult {
    targetDocumentId
    beforeBlueId
    resultingBody
    orderedEmittedEvents[]
    orderedPatches[]
    gasUsed
    identityAffecting
}
```

The wire representation is implementation-defined unless otherwise exposed by
a host API, but the following laws are normative:

1. `targetDocumentId` names exactly one managed document and `exactDocument` is
   that document's exact current tentative state.
2. `$document` is `exactDocument`; `$scope` is `/` for closure work.
3. `exactPayload` is the one exact event/update/lifecycle/initialization payload
   for the work occurrence. It is not a closure object and does not contain an
   ambient list of containers.
4. One step may read another managed document only through an explicit exact
   field in its own document.
5. One step may patch only its target document under the ordinary mutation
   boundaries.
6. All steps debit the one shared closure meter. A new document step does not
   receive a fresh release limit.
7. `LocalDocumentStepResult` is application-output evidence and a summary of
   the locally staged body. It MUST NOT claim an `afterBlueId`. In particular,
   no local call may invent or expose a cyclic `MASTER#index` before
   complete-set finalization.
8. After every accepted patch, the document-step runtime MUST synchronously
   yield the exact patch and current local body to an orchestrator-owned
   continuation boundary. The orchestrator applies that one patch, reconciles
   affected graph and containing-reference state, finalizes every affected
   exact identity, and drains its immediate Document Update continuation before
   the document step may process the next patch, event, or Handler result. The
   callback is processor-private and does not add reverse-containment data to
   `$document`, `$scope`, the event, or application runtime context.
9. Only an orchestrator finalization boundary creates or updates the
   corresponding final `ResultingDocument` record with its exact
   `afterBlueId`, component state, and cyclic member index when applicable.
   `resultingBody` and `orderedPatches` in the returned local result summarize
   the effects already staged through those synchronous continuations; they do
   not authorize a second application of the patches.
10. A local result is tentative. The host may persist immutable local or
   finalized result content, but no managed-document current head advances
   until the closure commit.
11. The accepted work trace and conformance `documentStepTrace` MUST identify the
   same target document and Root scope for each step.

The absence of parent/reverse-containment context is intentional. The managing
host maintains occurrence and reverse indexes; the document runtime does not.

#### 2.2.3 Exact tentative resolution context

Immediately before every document step, the orchestrator MUST reconstruct this
internal resolver view from the already verified invocation and the latest
exact tentatively finalized closure snapshot:

```text
TentativeResolutionContext {
    closureStateIdentity
    invocationIdentity
    graphGeneration
    targetDocumentId
    targetManagedScope
    targetBeforeBlueId
    componentIdentity
    componentStateIdentity
    cyclicProofIdentity
    stableDocumentIdToCurrentBlueId[]
    exactNodeProviderIdentity
    occurrenceBindingSetIdentity
}
```

`targetManagedScope` is the target document's Root managed-scope key required
by §2.2.1. `cyclicProofIdentity` is null for an acyclic singleton and is the
complete current proof identity for a cyclic component. The document-to-BlueId
map is canonical by `DocumentId` and contains the latest tentatively finalized
identity for every managed document in that exact closure snapshot.

Every field above is either already bound by `invocationIdentity` or is derived
from and rechecked against `closureStateIdentity`, component-state evidence,
the occurrence-binding-set identity, and the selected provider identity. It is
not a new independently supplied identity domain. A context becomes stale
after any identity-affecting local result or graph rewrite and MUST be rebuilt
after the required finalization before another step or exact read.

The context belongs to the resolver, not application runtime code. It may
resolve only references explicitly reachable through the target document; it
MUST NOT expose the map, component membership, reverse occurrences, containing
documents, or host graph metadata through `$document`, `$scope`, BEX, Handler
arguments, events, or patches. Exact reads, `$nodeBlueId`, schema checks,
equality, copied values, and provider demands use the current identities in this
context. The same construction and resolution rules apply to acyclic and cyclic
documents.

### 2.3 Processing Cause

For `PROCESS` and an externally caused `PROCESS_CLOSURE`, `event` is one
admitted exact immutable Blue node. Its exact BlueId MUST be established before
semantic execution. It is the original external event selected by the feeder
and is never rewritten to contain target paths, occurrence identities, or
internal delivery envelopes. Its cause evidence has this closed form:

```text
ExternalCause {
    kind: external
    causeIdentity
    event
    eventBlueId
    sourceOrder
    externalOrderPolicyIdentity
}
```

`PROCESS_CLOSURE` also accepts one exact processor-managed revision cause. It
is not an external event and carries no direct delivery:

```text
ManagedRevisionCause {
    kind: managed-revision
    causeIdentity
    targetOccurrenceIdentity
    childDocumentId
    fromEpoch
    toEpoch
    beforeBlueId
    afterBlueId
    afterDocument
    originalSourceCauseIdentity
    sourceRevisionReceiptIdentity
}
```

This shape proves one already-authenticated child-lineage revision. `fromEpoch`,
`toEpoch`, `beforeBlueId`, and `afterBlueId` describe exactly one contiguous
step; `toEpoch` MUST equal `fromEpoch + 1` within the safe-integer range.
`afterDocument` MUST independently establish `afterBlueId`, and both BlueIds
MUST denote `childDocumentId` under the selected managed-document identity
policy. `targetOccurrenceIdentity` MUST name exactly one input row whose
`targetDocumentId` is `childDocumentId`, whose `active` field is false, whose
`pendingHistoricalEpoch` equals `fromEpoch`, and whose exact source-path value
is `beforeBlueId`.

`originalSourceCauseIdentity` is the exact external or admission cause identity
recorded by the authoritative source system for the child revision; it is audit
evidence and is never replaced with the local catch-up invocation identity.
`sourceRevisionReceiptIdentity` is the closed authenticated receipt constructor
in §2.6. The receipt binds that original cause, both epochs, both exact states,
and the child lineage, providing an unambiguous source-order receipt without an
open or optional tuple. The managed-revision `causeIdentity` then binds the
receipt to the one target occurrence being reconciled.

One `ManagedRevisionCause` is one `PROCESS_CLOSURE` invocation, seeds exactly
one containing-reference update, and has its own admission, meter, gas trace,
rollback, result, compare-and-swap, and commit boundary. Contracts MUST NOT
accept a revision list or traverse an implicit historical range. Coordination
may dispatch the next contiguous cause only after the preceding invocation has
terminally committed and MUST prevent later live work from overtaking the
pending catch-up lineage.

For `ADMIT_CLOSURE`, `admissionCause` is immutable platform evidence:

```text
AdmissionCause {
    kind: admission
    causeIdentity
    admissionKind: TOP_LEVEL_ADMISSION | EMBEDDED_ACTIVATION | IMPORTED_STATE_ADMISSION
    label
    triggeringEventBlueId        # required nullable; exact triggering event or JSON null
    parentTransitionIdentity     # required nullable; exact transition evidence or JSON null
    policyIdentity
}
```

Admission causality is exactly `AdmissionCause.causeIdentity`. Admission has no
external or fabricated Timeline Entry, provider timestamp, source cursor, or
checkpoint evidence and requires the recomputed empty direct-delivery snapshot.
The optional triggering event and parent-transition identities above are exact
policy-authorized causal evidence; neither is a Timeline Entry or timestamp.

The two nullable Admission fields are always serialized. For
`TOP_LEVEL_ADMISSION` both are normally JSON `null`; for a causally embedded or
imported admission each non-null value MUST be the exact corresponding event or
parent-transition evidence selected by its admission policy. An omitted field,
a non-null value without policy-authorized evidence, or a value coupled to a
different admission operation fails closed.

Every closure invocation input also contains the two required nullable fields
`admissionCandidate` and `admissionCandidateIdentity`. For
`PROCESS_CLOSURE`, including a managed-revision invocation, both fields MUST be
JSON `null`. For `ADMIT_CLOSURE`, they are either both `null` or both non-null.
A non-null candidate is one closed
untrusted audit value:

```text
AdmissionCandidate =
    {
        kind: BAD_CYCLIC_PROOF,
        evidence: { candidateCyclicProof }
    }
  | {
        kind: AMBIGUOUS_PRELIMINARY_MEMBERS,
        evidence: { candidateCyclicMembers[] { documentId, document } }
    }
  | {
        kind: INVALID_OCCURRENCE_BINDING,
        evidence: { candidateOccurrenceBindings[] }
    }
```

Each branch contains exactly `kind` and `evidence`; its `evidence` object
contains only the field shown for that branch. Candidate member records contain
exactly `documentId` and the complete candidate placeholder-form `document`.
Candidate occurrence records are the distinct closed nine-field verifier-probe
shape `{ occurrenceIdentity, bindingIdentity, bindingPolicyIdentity,
sourceDocumentId, sourcePath, activationGeneration, targetDocumentId,
expectedTargetBlueId, active }`. They deliberately exclude
`pendingHistoricalEpoch`: a candidate is not an authoritative prospective or
historical catch-up row. Unknown fields and a payload belonging to another
branch fail closed.

The candidate is not part of authoritative closure state and cannot add a
member, proof, occurrence, or edge. The branch selects an independent
verification procedure only. In particular, its negative-sounding `kind` is not
an expected-result flag: the processor MUST derive ambiguity, proof mismatch, or
route/binding invalidity from the exact evidence. A candidate that passes its
selected check has no state-changing effect.

An external processing cause additionally carries the exact
`externalOrderPolicyIdentity` under which its `sourceOrder` tuple was
normalized. The policy identity is cause and invocation evidence; equal event
and tuple values under two different policies MUST NOT produce equal cause
identities. It MUST equal the selected external-order policy identity in the
same closed invocation environment; a mismatch fails before semantic work.
Admission `label` is a nonempty NFC policy label. A non-NFC label is rejected,
not normalized by Contracts.

Cause/operation coupling is closed: `PROCESS_CLOSURE` accepts exactly
`external` or `managed-revision`; `ADMIT_CLOSURE` accepts exactly `admission`;
ordinary `PROCESS` accepts exactly its existing external cause. External causes
may carry nonempty `directDeliveries`; managed-revision and admission causes
require the empty array and its recomputed empty-snapshot identity.

A cyclic-set member identity MUST NOT be used as a top-level external event.

### 2.4 Processing Environment

One attempt is evaluated under a fixed environment containing:

```text
Blue Language 1.0 specification identity
Contracts 1.0 specification identity
exact runtime registry and supported runtime BlueIds
verified exact-node provider domain identity
managed document and occurrence identity policy identities
revision-complete direct-delivery snapshot
canonical external-order policy identity
runtime-registry and gas-manifest identities
exact default gas limit and optional exact lower override
cyclic-set finalizer implementation identity
cyclic-set proof verifier identity
portable-limit policy identity
```

A closure attempt additionally binds the exact `AffectedClosureSnapshot`, its graph generation, component generations, public Root set, and current complete cyclic proofs.

The environment is not Blue application content. It MUST remain fixed for the
attempt and be included in deterministic receipt evidence. The invocation or
commit-companion constructor MUST bind each named identity explicitly or
transitively through another exact constructor; merely locating a release file
or using an unstated host default is not evidence.

### 2.4.1 Managed occurrence identity and exact-state binding

The platform MUST distinguish a stable occurrence lineage from its current exact-state binding.

`occurrenceIdentity` identifies one reserved or active source-path activation
denoting one managed target lineage. Its `active` status and any historical
catch-up cursor are exact state of that lineage, not inputs to the stable
lineage constructor. It is the domain-separated identity of:

```text
domain = "blue-contracts-managed-occurrence-lineage/1.0"
value = {
    sourceDocumentId,
    sourcePath,
    activationGeneration,
    targetDocumentId,
    bindingPolicyIdentity
}
```

`bindingIdentity` identifies the exact target state currently expected for that occurrence. It is the domain-separated identity of:

```text
domain = "blue-contracts-managed-occurrence/1.0"
value = {
    sourceDocumentId,
    sourcePath,
    activationGeneration,
    targetDocumentId,
    expectedTargetBlueId,
    bindingPolicyIdentity
}
```

For both constructors the hashed RFC 8785 value is the uniform object `{ "domain": domain, "value": value }`, encoded as UTF-8. The result is `sha256:<64 lowercase hexadecimal digits>`. The exact constructor and normalization rules are normative in `conformance/contracts/identity-constructors.yaml`.

The historical `blue-contracts-managed-occurrence/1.0` domain remains the exact-state binding domain. The new lineage domain prevents a state-specific hash from being silently reinterpreted as stable continuity evidence.

The binding policy identity is explicit evidence in every occurrence record. It MUST NOT be a generator-only constant or an implicit host default.

A binding is valid only when:

1. `sourcePath` is a concrete normalized prospective or active `Process Embedded` occurrence path;
2. for an active row, the effective declaration covers that exact path and the
   exact source value establishes `expectedTargetBlueId` through any exact form
   admitted by §0.5; for an inactive pending-null row the path may be absent,
   while an inactive historical row may contain its exact cursor value; in all
   cases no graph edge is inferred before activation and the active-only path,
   declaration, exact-identity, and lineage checks are repeated immediately
   before activation;
3. target evidence identifies `targetDocumentId` as that exact state or as an admitted historical state of that lineage;
4. the occurrence and binding identities recompute exactly and do not conflict with another row for the same source/path/generation;
5. `pendingHistoricalEpoch` is JSON `null` unless it exactly identifies the admitted historical target state being caught up under the rules above;
6. the exact row is already in the invocation input before it may activate;
   the inactive retirement successor deterministically derived under §5.6 is
   output-only in the invocation that creates it and may activate only after it
   is committed and supplied as input to a later invocation; the target is
   available in the closure or its already named exact BlueId is returned by
   the deterministic resource boundary before first target work.

Managed occurrence activation generations are positive safe integers. The
managed Root scope generation is `0`; the first embedded occurrence reservation
or activation at a concrete source path is generation `1`. Same-lineage
exact-state or cyclic-`MASTER#index` churn preserves that generation. Removing
an active path deterministically creates its inactive successor reservation at
exactly the preceding generation plus one with fresh occurrence and binding
identities. That successor cannot reactivate in the invocation that creates it.
After it is committed and supplied as an inactive input row to a later
invocation, re-add activates it without another generation or
occurrence-identity allocation. A different managed
lineage cannot replace an active or reserved path in Contracts 1.0. Such a
patch fails before mutation. A future specification may add an explicit atomic
platform rebind operation with its own compare-and-swap evidence; none is
defined here. Overflow fails closed; a generation is never reused after a
successful retirement.

Two different `DocumentId` values MAY currently have the same BlueId. One `DocumentId` MUST NOT simultaneously claim conflicting exact current states in one closure.

### 2.4.2 Cyclic-member processing boundary

A final cyclic-set member identity `MASTER#index` is not independently hash-verifiable.

An ordinary isolated `PROCESS` invocation without complete owning-set evidence MUST reject a cyclic member as top-level mutable Root. Ordinary content may retain an opaque member reference untouched.

Closure processing MAY read and mutate cyclic members only when it has:

1. the complete finite member set or complete cyclic-aware provider proof;
2. the exact current `MASTER` and suffix mapping for an already cyclic component;
3. a unique stable `DocumentId` to member mapping;
4. every active internal occurrence edge needed by the component;
5. current graph and component generations;
6. the exact Language cyclic finalizer and verifier identities;
7. one atomic closure publication boundary.

Missing, unavailable, stale, malformed, ambiguous, or inconsistent evidence fails closed or produces `NeedsResources` before semantic mutation.

### 2.5 ProcessResult, ClosureAttemptResult, and ClosureProcessResult

An ordinary completed invocation returns:

```text
ProcessResult {
    status
    document
    events
    totalGas
    diagnostic?
}
```

A closure attempt returns one closed union:

```text
ClosureAttemptResult =
    Complete {
        result: ClosureProcessResult
    }
  | NeedsResources {
        requiredBlueIds[]
    }
```

`requiredBlueIds` is the duplicate-free list of exact required BlueIds sorted
by canonical BlueId text. `NeedsResources` is not a completed processor result
and has no status, gas trace, rejected-charge evidence, semantic state, or
commit companion.

A completed closure invocation returns:

```text
ClosureProcessResult {
    status
    invocationIdentity
    inputClosureIdentity
    outputClosureIdentity
    graphGeneration
    resultingDocuments[] {
        documentId
        beforeBlueId
        afterBlueId
        document
        initialized
        terminated
        publicRoot
        epoch
        componentGeneration
        componentIdentity
        componentStateIdentity
        memberIndex              # exact numeric suffix for CYCLIC; JSON null for ACYCLIC
    }
    resultingComponents[] {
        componentIdentity
        componentStateIdentity
        componentGeneration
        kind: ACYCLIC | CYCLIC
        masterBlueId?            # CYCLIC only
        orderedMemberDocumentIds[]
        orderedMemberBlueIds[]
        cyclicProofIdentity?     # CYCLIC only
        completeCyclicProof?      # CYCLIC only
    }
    occurrenceBindings[]
    occurrenceBindingSetIdentity
    graphChanges[]
    graphChangesIdentity
    subscriptionDeltas[]
    subscriptionDeltasIdentity
    checkpointWrites[] {
        checkpointWriteOrdinal
        targetManagedScopeIdentity
        rawChannelKey
        beforePresent
        beforeDomainBlueId
        beforeDomainValue
        beforeSubjectBlueId
        afterPresent
        afterDomainBlueId
        afterDomainValue
        afterSubjectBlueId
    }
    checkpointWritesIdentity
    publicEvents[] {
        publicEventOrdinal
        eventOccurrenceOrdinal
        publicRootDocumentId
        eventOccurrenceIdentity
        eventBlueId
        event
    }
    publicEventsIdentity
    totalGas
    gasTrace[]
    gasTraceIdentity
    rejectedCharge? {
        rejectedChargeIdentity
        namespace
        counter
        quantity
        weight
        subtotal
        applicableCap:
            { kind: SHARED }
          | { kind: LOCAL, documentId: DocumentId }
        remainingBeforeCharge
        owner:
            { kind: INVOCATION }
          | { kind: WORK, workOccurrenceIdentity: WorkOccurrenceIdentity }
          | { kind: FINALIZATION, finalizationOrdinal, componentIdentity, componentGeneration }
    }
    rejectedWorkOccurrence?      # present exactly when rejectedCharge.owner.kind = WORK
    platformCommitCompanion?       # required exactly for a committing result
    diagnostic?
}
```

`resultingComponents` represents formation, merge, split, dissolution, and
several components in one condensation closure. Acyclic singleton components
have no `masterBlueId`, `cyclicProofIdentity`, or `completeCyclicProof`; their
required `memberIndex` field is JSON `null`. For a cyclic result document,
`memberIndex` is non-null and exactly the non-negative decimal `n` parsed from
its final `afterBlueId = MASTER#n`; it is not the document's position in a
`DocumentId`-sorted array. Every cyclic component carries its complete proof and
exact proof identity; a proof-stage label or external oracle filename is not
result evidence.

Every changed containing Root appears in `resultingDocuments`. `graphChanges` identifies updated occurrence paths and before/after exact target identities. The processor does not leave containing-reference reconstruction unspecified.

Only explicit emissions from Root-scoped work targeting documents marked
`publicRoot` appear in `publicEvents`. Internal member, descendant, and
non-Root emissions remain causal work only, including an emission whose
containing managed document is public.

The structured `applicableCap` is the closed branch `{ kind: SHARED }` for the
shared closure ceiling or `{ kind: LOCAL, documentId: D }` for the local ceiling
of NFC-normalized `DocumentId` `D`. A branch contains no field belonging only to
the other branch. No colon-concatenated string or unspecified local label is
conforming.

`rejectedCharge.owner` is likewise a closed union. `INVOCATION` contains no
branch-specific field. `WORK` contains exactly `workOccurrenceIdentity` and the
complete `rejectedWorkOccurrence` record is present if and only if this branch
is selected. `FINALIZATION` contains exactly `finalizationOrdinal`,
`componentIdentity`, and `componentGeneration`; the ordinal names the exact
invocation-global tentative-finalization occurrence even when component lineage
and generation are unchanged. Admission, invocation-wide validation, and other work
before the first queue item use `INVOCATION`; a tentative component-finalization
boundary uses `FINALIZATION`.

On `gas-limit-exceeded`, the complete `rejectedCharge` object is required and
its `rejectedChargeIdentity` is the uniform-envelope identity in domain
`blue-contracts-rejected-charge/1.0` of
`{ namespace, counter, quantity, weight, subtotal, applicableCap,
remainingBeforeCharge, owner }`. The complete object and
`rejectedWorkOccurrence` are absent for every other status; the latter is also
absent for an invocation- or finalization-owned rejected charge.

All result collections above are present even when empty. They contain the full
structured `graphChanges`, `subscriptionDeltas`, `checkpointWrites`, public
event occurrences, final occurrence bindings, and component proof evidence;
booleans, counts, stage labels, or hidden harness data cannot replace them. A
result's `occurrenceBindings` is the complete final authoritative row set and
therefore includes every retained inactive prospective row as well as every
active row, with required nullable `pendingHistoricalEpoch` on each. Its
`occurrenceBindingSetIdentity` recomputes over that exact set under §2.6.
Every public-event record carries both ordinals. `publicEventOrdinal` is the
zero-based contiguous index in the public projection. `eventOccurrenceOrdinal`
is the invocation-global emission ordinal used by
`eventOccurrenceIdentity`; it may contain gaps in the public projection because
non-public event occurrences are omitted. Both are required and independently
revalidated.
For `ADMIT_CLOSURE`, initialization and lifecycle effects use these same result
fields. An event explicitly emitted by work at an admitted public Root appears
once in `publicEvents`; a non-public member emission remains internal even when
it causes a containing public Root to react. `checkpointWrites` is the exact
empty sequence and `checkpointWritesIdentity` is the ordinary identity of that
empty sequence. No Timeline entry, timestamp, source cursor, or checkpoint is an
additional result field.
A committing completed result MUST contain the exact
`platformCommitCompanion`. A noncommitting completed result MUST omit it and
MUST return the exact authoritative input documents, graph generation, component
partition and states, occurrence bindings, checkpoints, subscriptions, and
public event state; its `outputClosureIdentity` equals `inputClosureIdentity`.
A committing result's `outputClosureIdentity` recomputes from the complete final
snapshot. A gas failure additionally returns the complete admitted trace, exact
rejected charge, and exact charge owner.

Snapshot flags and fixture receipt flags are derived assertions, never
alternative state. In particular, `initialized` and `terminated` MUST agree
with the validated direct markers in the exact resulting document;
`rollbackToInput`, `checkpointsCommitted`, and analogous fixture booleans MUST
be recomputed from the complete result, exact input/output closure identities,
and structured write sequences. Such a Boolean neither authorizes a marker or
checkpoint write nor substitutes for the exact resulting document or receipt.

### 2.6 Exact internal identities

Contracts internal identities use domain-separated SHA-256 over RFC 8785 canonical JSON and are represented as `sha256:<64 lowercase hexadecimal digits>`.

The normative constructor registry is
`conformance/contracts/identity-constructors.yaml`. Every domain-separated
platform `sha256:` constructor hashes the uniform RFC 8785 envelope:

```text
{
    "domain": <exact constructor domain>,
    "value": <exact constructor value>
}
```

For those platform constructors, fields listed by a constructor are never
implicitly omitted. A constructor that permits an absent semantic value
represents it as JSON `null`. Dynamic maps are first converted to the ordered
entry arrays specified by the registry. The separately marked
`checkpointDomainBlueId` entry is a direct Blue Language object-identity
constructor and follows its explicit required/optional Blue field rules in
§10.2 instead. Implementations MUST NOT substitute a flat platform object, a
different property spelling, insertion order, an implementation class name, or
an unspecified host default.

Every integer entering a platform identity constructor is a JSON integer in the
inclusive range `0..9007199254740991`. This is the portable RFC 8785/I-JSON
safe-integer range. This release defines no decimal-string alternative: a larger
epoch, ordinal, generation, count, or policy value MUST be rejected before
identity construction and MUST NOT be emitted as an out-of-range Java `long` or
`BigInteger`, or rounded through an IEEE-754 value or JSON library.

Cause constructors are closed and exact:

```text
externalCauseIdentity
    domain = "blue-contracts-external-cause/1.0"
    value = {
        eventBlueId,
        sourceOrder,
        externalOrderPolicyIdentity
    }

admissionCauseIdentity
    domain = "blue-contracts-admission-cause/1.0"
    value = {
        admissionKind,
        label,
        triggeringEventBlueId,
        parentTransitionIdentity,
        policyIdentity
    }

sourceRevisionReceiptIdentity
    domain = "blue-contracts-source-revision-receipt/1.0"
    value = {
        childDocumentId,
        fromEpoch,
        toEpoch,
        beforeBlueId,
        afterBlueId,
        originalSourceCauseIdentity
    }

managedRevisionCauseIdentity
    domain = "blue-contracts-managed-revision-cause/1.0"
    value = {
        targetOccurrenceIdentity,
        childDocumentId,
        fromEpoch,
        toEpoch,
        beforeBlueId,
        afterBlueId,
        originalSourceCauseIdentity,
        sourceRevisionReceiptIdentity
    }
```

`sourceOrder` is the already normalized canonical external-order tuple and
retains tuple order. Serialized cause `kind` is the closed-union discriminator
`external`, `managed-revision`, or `admission`; it is not silently substituted for
`admissionKind`. The two optional admission fields are always present in the
constructor value and use JSON `null` when absent.

For both revision constructors, `toEpoch` is exactly `fromEpoch + 1` and all
integers are safe. The receipt is recomputed first from authenticated source
revision evidence. The managed cause repeats and revalidates its receipt fields,
adds the one containing occurrence that will be changed, and hashes the exact
receipt identity. `afterDocument` is required cause evidence and MUST establish
`afterBlueId`; it is not duplicated in either constructor because its BlueId
already binds the complete semantic node. A receipt mismatch, a different
original cause, a noncontiguous epoch, or a before/after state mismatch fails
before processor-managed mutation.

Admission-candidate evidence uses this exact closed constructor:

```text
admissionCandidateIdentity
    domain = "blue-contracts-admission-candidate/1.0"
    value = {
        kind,
        evidence
    }
```

The `value` is exactly one `AdmissionCandidate` branch from §2.3 without an
identity field. `candidateCyclicMembers` MUST already be sorted by `documentId`;
each complete placeholder-form `document`, including every submitted `this#n`,
is hashed as exact evidence without provider expansion or semantic repair.
`candidateCyclicProof` is likewise the complete exact candidate proof value as
submitted, even when its claimed master or mapping will fail verification.
`candidateOccurrenceBindings` contains the exact closed nine-field verifier
records listed in §2.3, sorted by `(occurrenceIdentity, bindingIdentity)`; it
MUST NOT contain `pendingHistoricalEpoch` or any other authoritative-row-only
field. The platform constructor only applies the uniform RFC 8785 encoding to
that closed ordered value. These rules make the candidate identity constructible
before semantic validity is known; they do not supply Blue Language preliminary
order or certify the evidence.

Component continuity and exact component state use two different constructors:

```text
componentIdentity
    domain = "blue-contracts-component/1.0"
    value = {
        kind,
        generation,
        members
    }

componentStateIdentity
    domain = "blue-contracts-component-state/1.0"
    value = {
        componentIdentity,
        memberStates,
        masterBlueId,
        cyclicProofIdentity
    }
```

`members` is the array of `DocumentId` values sorted by Unicode scalar-value
sequence. `memberStates` is the array of objects
`{ documentId, blueId }` in that same order. For an acyclic component,
`masterBlueId` and `cyclicProofIdentity` are JSON `null`; for a cyclic component
both are non-null. `componentIdentity` therefore remains stable while the same
partition/generation undergoes exact member or `MASTER` churn, while
`componentStateIdentity` changes for every exact component-state change.

`cyclicProofIdentity` is the uniform-envelope SHA-256 identity in domain
`blue-contracts-cyclic-proof-evidence/1.0` of
`{ componentIdentity, masterBlueId, memberStates, declaredPlaceholderSet }`.
`declaredPlaceholderSet` is the complete proof in canonical Blue Language §15
member order with canonical `this#n` remapping. Contracts first constructs the
canonical direct-node materialization pattern: literal identity fields remain
complete, the cycle-bearing path remains complete through its `this#n`
reference, and every complete off-cycle child is collapsed to the pure
reference containing that child's independently established BlueId. The
processor MUST independently apply the complete Blue Language cyclic-set
calculation to that candidate. It uses the collapsed candidate as
`declaredPlaceholderSet` only when the recalculated `masterBlueId` and complete
unique member-BlueId set exactly equal the authoritative Language finalization.
Otherwise `declaredPlaceholderSet` is the authoritative finalization's full
canonical placeholder member set. The proof is represented as semantic Blue
values rather than host objects. It is never an oracle filename, stage label,
Java serialization, proof-object address, or an unauthenticated working-body
claim.

The fixture-only `TentativeFinalization.canonicalBytes` is always the RFC 8785
UTF-8 byte length of the ordered collapsed direct-node projection, regardless
of which complete proof representation passed the exact identity check. It is
the portable Contracts limit input; it is deliberately distinct from proof
wire size and from any larger internal byte sequence that a Language
implementation may allocate while calculating the same `MASTER`. The
processor still calculates the `MASTER` and final member BlueIds through the
complete Blue Language cyclic-set algorithm. A collapsed proof candidate is
authenticated by exact recalculation, never by byte-for-byte comparison with
an expanded member or by assuming that collapse preserves identity.

The complete authoritative occurrence-row set uses this exact constructor:

```text
occurrenceBindingSetIdentity
    domain = "blue-contracts-occurrence-binding-set/1.0"
    value = [{
        occurrenceIdentity,
        bindingIdentity,
        active,
        pendingHistoricalEpoch
    }, ...]
    order = occurrenceIdentity, bindingIdentity
```

`pendingHistoricalEpoch` is present in every item and is either JSON `null` or
the safe integer governed by §2.2. Both active and inactive rows participate.
Consequently activating a prospective row, advancing or completing its
historical catch-up, rebinding its expected exact target state, adding a row, or
removing a row changes `occurrenceBindingSetIdentity`. Only a change to the set
of rows whose `active` field is true changes the active graph and advances
`graphGeneration` under §5.7.

The affected-closure constructor uses domain
`blue-contracts-affected-closure/1.0` and binds
`{ graphGeneration, documents, occurrenceBindingSetIdentity, components,
publicRootDocumentIds }`.
`documents` contains the seven-field managed-document records from §2.2 sorted
by `documentId`. `components` contains exact `componentStateIdentity` strings
sorted lexicographically as lowercase identity text; each state identity
transitively binds its stable `componentIdentity`. `publicRootDocumentIds` uses
Unicode scalar-value order.
Consequently a change to `initialized`, `terminated`, `publicRoot`, `epoch`,
component generation, any member BlueId, cyclic master, or cyclic proof changes
`closureIdentity`. Cause and direct-delivery evidence deliberately do not: they
describe why and how this state is attempted, not the durable state itself.

The invocation identity binds:

```text
operation
causeIdentity
admissionCandidateIdentity
inputClosureIdentity
inputGraphGeneration
documents
directDeliverySnapshotIdentity
occurrenceBindingSetIdentity
blueLanguageSpecificationIdentity
contractsSpecificationIdentity
managedDocumentIdentityPolicyIdentity
managedBindingPolicyIdentity
exactNodeProviderDomainIdentity
externalOrderPolicyIdentity
runtimeRegistryIdentity
gasManifestIdentity
gasPolicyIdentity
portableLimitPolicyIdentity
cyclicFinalizerIdentity
cyclicProofVerifierIdentity
```

Thus `invocationIdentity`, not `closureIdentity`, is the exact boundary that
binds the cause and frozen direct-delivery snapshot. Two invocations over equal
closure state but different causes or delivery snapshots have equal
`inputClosureIdentity` and different `invocationIdentity` values.

`documents` contains objects
`{ documentId, blueId, initialized, terminated, publicRoot, epoch,
componentGeneration }` sorted by `documentId`.

`operation` is the exact lowercase literal `process-closure` for
`PROCESS_CLOSURE` and `admit-closure` for `ADMIT_CLOSURE`. API enum or method
names, including the uppercase spellings above, MUST NOT be hashed in its place.

`admissionCandidateIdentity` is always present in the constructor value. It is
JSON `null` when the two invocation-input candidate fields are null, and is the
exact recomputed identity above otherwise. A non-null candidate is permitted
only for `ADMIT_CLOSURE`. It is deliberately absent from `closureIdentity`
because it is an untrusted invocation probe rather than authoritative closure
state; `invocationIdentity` is the boundary that binds it.

The two specification identities are `sha256:` identities of the exact
normative specification artifacts, not package/release identities whose value
would depend on fixtures containing the invocation identity. This avoids a
manifest/fixture hash cycle. A Contracts implementation-artifact identity is
separate conformance metadata; it is not an input to the portable invocation
or commit-companion identity.

This invocation constructor is used by `PROCESS_CLOSURE` and `ADMIT_CLOSURE`;
`inputClosureIdentity` is non-null for both. Ordinary `PROCESS` retains its
existing ordinary input/result identity boundary and does not fabricate a
closure identity.

The work occurrence identity binds:

```text
invocation identity
work ordinal
work kind
target managed-scope occurrence key
source occurrence identity: direct-delivery, admission cause, managed-revision
cause, transition, or event occurrence as applicable
```

The invocation identity already binds the input graph, and the target managed
scope key binds its activation generation. A work occurrence separately carries
its complete frozen graph/target evidence for revalidation; duplicating those
fields inside the work digest is neither required nor permitted by the closed
constructor registry.

For the Contracts 1.0 affected-closure profile, the target managed-scope key in
every work occurrence is the Root key of `targetDocumentId`, exactly as closed
in §2.2.1. The general managed-scope and work identity constructors remain
available to ordinary `PROCESS`; their wider shape does not authorize a
non-Root closure work occurrence.

A transition occurrence identity binds:

```text
invocation identity
transition ordinal
target DocumentId
before BlueId
causing work occurrence identity
```

An event occurrence identity binds:

```text
invocation identity
zero-based invocation-global event ordinal allocated in canonical Handler-result emission order
exact event BlueId
```

The event occurrence evidence additionally records its source transition,
emitting scope, Handler key, and Handler-local emission ordinal. Those fields
are independently validated and determine allocation of the invocation-global
ordinal; they are not duplicated in the digest constructor. All frozen target
deliveries created from one event occurrence reuse its identity.

Subscription evidence uses two closed constructors:

```text
channelOccurrenceIdentity
    domain = "blue-contracts-channel-occurrence/1.0"
    value = {
        managedDocumentId, scopePath, scopeActivationGeneration,
        rawChannelKey, effectiveRuntimeContributionBlueId,
        subscriptionHeaderBlueId
    }

subscriptionIdentity
    domain = "blue-contracts-subscription/1.0"
    value = {
        channelOccurrenceIdentity, documentBlueId,
        graphGeneration, componentGeneration
    }
```

`subscriptionHeaderBlueId` is the ordinary exact BlueId of the normalized
subscription header that binds the declared dependency surface of the exact
effective Channel. `effectiveRuntimeContributionBlueId` is likewise the exact
BlueId of the normalized effective runtime contribution. Neither constructor
hashes a host query, index row, or cache object.

Closure subscription evidence is Root-scoped under §2.2.1: every
`channelOccurrenceIdentity` is constructed with `scopePath = /` and
`scopeActivationGeneration = 0`, and its enclosing subscription delta repeats
the matching Root `targetManagedScopeIdentity`. Ordinary `PROCESS` subscription
indexing remains scope-aware.

Commit-result sequence identities use these exact domains and normalized
records:

```text
graphChangesIdentity
    domain = "blue-contracts-graph-changes/1.0"
    value = [{
        graphChangeOrdinal, changeKind,
        sourceDocumentId, sourcePath,
        beforeActivationGeneration,
        beforeOccurrenceIdentity, beforeBindingIdentity,
        beforeTargetDocumentId, beforeTargetBlueId,
        afterActivationGeneration,
        afterOccurrenceIdentity, afterBindingIdentity,
        afterTargetDocumentId, afterTargetBlueId
    }, ...]
    order = graphChangeOrdinal

checkpointWritesIdentity
    domain = "blue-contracts-checkpoint-writes/1.0"
    value = [{
        checkpointWriteOrdinal, targetManagedScopeIdentity, rawChannelKey,
        beforePresent, beforeDomainBlueId, beforeDomainValue,
        beforeSubjectBlueId,
        afterPresent, afterDomainBlueId, afterDomainValue,
        afterSubjectBlueId
    }, ...]
    order = checkpointWriteOrdinal

subscriptionDeltasIdentity
    domain = "blue-contracts-subscription-deltas/1.0"
    value = [{
        subscriptionDeltaOrdinal, operation,
        targetManagedScopeIdentity, channelOccurrenceIdentity,
        beforeSubscriptionIdentity, afterSubscriptionIdentity,
        beforeDocumentBlueId, afterDocumentBlueId,
        beforeGraphGeneration, afterGraphGeneration,
        beforeComponentGeneration, afterComponentGeneration
    }, ...]
    order = subscriptionDeltaOrdinal

publicEventsIdentity
    domain = "blue-contracts-public-events/1.0"
    value = [{
        publicEventOrdinal, eventOccurrenceOrdinal, publicRootDocumentId,
        eventOccurrenceIdentity, eventBlueId
    }, ...]
    order = publicEventOrdinal
```

Every projection ordinal begins at zero and is contiguous in the corresponding
canonical result order. `eventOccurrenceOrdinal` retains the invocation-global
emission ordinal, MUST recompute with `eventOccurrenceIdentity`, and may have
gaps in `publicEvents`. An absent before or after side uses JSON `null` for every identity,
BlueId, and generation on that side. The checkpoint presence Boolean controls
the closed side shape: its domain BlueId, complete domain value, and subject
BlueId are all JSON `null` exactly when it is false and are all non-null exactly
when it is true. Each non-null domain value independently recomputes to the
adjacent domain BlueId under §10.2; the value is therefore complete result
evidence rather than an unattached label.
Graph `changeKind` is the closed value `ADD`, `REMOVE`, or `REBIND`
and agrees with its nullable sides. `REBIND` records same-lineage exact-state
binding churn; it does not change the active edge set, increment graph
generation, or consume `closureGraphChangesPerInvocation`. Subscription
`operation` is the closed value
`ADD`, `REMOVE`, or `REPLACE` and likewise agrees with its sides. Each
`beforeSubscriptionIdentity` or `afterSubscriptionIdentity` is the exact
`subscriptionIdentity` above; the delta constructor never hashes an opaque host
subscription object. Empty sequences hash the empty JSON array in their
respective domains.

These identities remain constructible before a final after-BlueId exists.
Source revision receipts additionally bind their exact after-BlueId.

Failed or rolled-back work does not advance authoritative graph, component, or activation generations.

The registry also defines exact constructors for closure, direct-delivery
snapshot, occurrence-binding set, managed scope, component continuity and state,
cyclic proof evidence, external/admission/managed-revision cause, source
revision receipt, execution policy, Channel/subscription evidence,
rejected-charge evidence, gas trace,
all four commit-result sequences above, and the platform commit-companion
identity. It additionally records the §10.2 `checkpointDomainBlueId` direct Blue
Language constructor as an explicit non-`sha256:` exception; that value is Blue
content identity, not a platform envelope identity. A conformance fixture MUST
contain every non-release input needed to recompute each identity it asserts.

### 2.7 Atomic invocation

All invocation state is tentative until final success:

- ordinary and member patches;
- dynamically expanded closure members and occurrence bindings;
- graph partition and generations;
- temporary exact ordinary BlueIds and cyclic `MASTER#index` values;
- lifecycle markers and checkpoints;
- update and event occurrence queues;
- subscription deltas;
- public events;
- containing-reference changes;
- gas trace.

A committing `success` or successful admission returns and publishes the complete result atomically. Admission atomicity includes all initialization, lifecycle, termination, update, event, graph, marker, and finalization effects caused before quiescence. Every deterministic failure, invalid proof, gas exhaustion, portable-limit failure, unsupported graph expansion, schema failure, or finalization failure discards all tentative state and public events.

Transient missing exact resources returns `NeedsResources` from an attempt API and commits no portable gas or semantic state.

### 2.8 Representation invariance

For graph-equivalent ordinary Roots or closure snapshots under the same environment, a conforming implementation MUST return:

- the same status and diagnostic category;
- the same final ordinary Root BlueId or closure document, component, and component-state identities;
- the same final component partition and occurrence bindings;
- the same public event occurrence identities and order;
- the same checkpoints and subscription deltas;
- the same exact counter trace and total gas;
- the same semantic provider demands.

Physical fetch count, cache hits, allocation, storage layout, batching, and temporary handle representation are nonportable.

### 2.9 Platform commit

A committing result is installed only through compare-and-swap against every exact input document state, graph generation, stable component identity, component-state identity, and current cyclic `MASTER` on which the result depends.

The transaction MUST atomically persist every applicable item below:

```text
all resulting exact documents and public Root heads
final component and component-state identities, generations, MASTERs, proofs and member mappings
managed occurrence bindings and activation generations
checkpoints and lifecycle markers
subscription deltas
public Root outboxes
graph changes and containing-reference updates
terminal progress for the original cause
commit companion and gas trace identity
```

For every operation, including `ADMIT_CLOSURE`, `terminal progress for the
original cause` is host-internal idempotency evidence keyed exclusively by the
exact `causeIdentity`. The compare-and-swap dependency set fences installation
but does not become part of that key. This terminal record is never Timeline
position, source-occurrence progress, delivery progress, a Channel checkpoint,
a checkpoint domain or subject, or lifecycle-marker state, and a platform MUST
NOT derive or advance any such state from it.

For `ADMIT_CLOSURE`, the checkpoint item is inapplicable: the committed
`checkpointWrites` sequence is empty and the adapter MUST NOT create, advance,
clean up, or otherwise mutate checkpoint state while installing the admission.
All applicable initialization-caused items, including lifecycle markers and
public Root outbox events, are nevertheless installed in the same transaction.

`platformCommitCompanion` is not an opaque host object. Its exact constructor is:

```text
domain = "blue-contracts-platform-commit-companion/1.0"
value = {
    invocationIdentity,
    inputClosureIdentity,
    outputClosureIdentity,
    expectedInputGraphGeneration,
    expectedInputDocuments,
    expectedInputComponents,
    inputOccurrenceBindingSetIdentity,
    outputGraphGeneration,
    resultingDocuments,
    resultingComponents,
    occurrenceBindingSetIdentity,
    graphChangesIdentity,
    checkpointWritesIdentity,
    subscriptionDeltasIdentity,
    publicEventsIdentity,
    gasTraceIdentity,
    blueLanguageSpecificationIdentity,
    contractsSpecificationIdentity,
    managedDocumentIdentityPolicyIdentity,
    managedBindingPolicyIdentity,
    exactNodeProviderDomainIdentity,
    externalOrderPolicyIdentity,
    runtimeRegistryIdentity,
    gasManifestIdentity,
    portableLimitPolicyIdentity,
    cyclicFinalizerIdentity,
    cyclicProofVerifierIdentity
}
```

`expectedInputDocuments` contains objects `{ documentId, blueId }` sorted
by `documentId`. `expectedInputComponents` contains objects
`{ componentIdentity, componentStateIdentity, componentGeneration, masterBlueId }`
in canonical condensation order, with `masterBlueId` JSON `null` for acyclic
components. These fields and `inputOccurrenceBindingSetIdentity` are the explicit
compare-and-swap expectations.

The storage adapter MUST independently recompute both the stored input and
staged output `occurrenceBindingSetIdentity` values from the complete
authoritative occurrence rows, including `active` and nullable
`pendingHistoricalEpoch`, before compare-and-swap and publication. A claimed
identity mismatch commits nothing; a caller-supplied digest is never accepted as
a substitute for the rows or their canonical recomputation.

The adapter MUST likewise recompute the state-only input `closureIdentity` from
the current stored graph, complete document records, occurrence-binding set,
component states, and public Root set, and recompute the output identity from
the complete staged state. It compares those derived values to
`inputClosureIdentity` and `outputClosureIdentity` respectively. It MUST NOT
persist a cause- or delivery-scoped attempt hash as the current closure state,
and MUST NOT accept either closure identity merely because the companion named
it.

`resultingDocuments` contains objects
`{ documentId, beforeBlueId, afterBlueId }` sorted by `documentId`.
`resultingComponents` contains objects
`{ componentIdentity, componentStateIdentity, cyclicProofIdentity }` in canonical
condensation order; the proof identity is JSON `null` for acyclic components.
The original cause is bound transitively by `invocationIdentity`. The before
graph, document heads, component states, and binding set are bound both by the
explicit compare-and-swap fields and `inputClosureIdentity`; exact output
document flags, epochs, component states, bindings, and public Roots are bound by
`outputClosureIdentity`. Every result sequence is bound
through its exact §2.6 sequence identity, including an empty sequence. The
companion contains the compare-and-swap expectations necessary to prove that
all effects were installed together; a schema MUST NOT replace any identity
with a count, Boolean, stage name, or host object.

A platform MUST NOT publish only a subset of closure documents or component members.

For a nonmutating terminal result, progress is compare-and-swapped against the exact unchanged state. A conflict commits nothing and requires re-derivation. Host contention and persistence retry are not portable Contracts gas.

## 3. Managing Feeder, Subscriptions, and External Order

### 3.1 Feeder responsibility

The managing feeder MUST:

1. maintain a revision-complete index of active external Channel occurrences across every managed document in the current Root reality;
2. retain exact activation/retirement intervals for concrete `Process Embedded` occurrences;
3. obtain provider completeness and canonical external order;
4. derive the complete direct logical delivery set for the selected original event;
5. freeze the current graph/component generation and direct seed order;
6. supply complete cyclic-set evidence when a selected occurrence is cyclic;
7. avoid deriving another direct delivery merely because graph traversal revisits a document;
8. invoke ordinary or component processing;
9. atomically commit the complete result and terminal progress.

The feeder decides which original external event is next. Internal Document Updates, lifecycle occurrences, and emitted events remain processor work and are never new Timeline Provider facts.

### 3.2 External-channel snapshot

One ordinary external snapshot entry identifies one concrete managed scope
occurrence:

```text
ExternalChannelSnapshot {
    managedDocumentId
    scopePath
    scopeActivationGeneration
    channelKey
    channelRuntimeBlueId
    channelOccurrenceIdentity
    channelContributionIdentity
    sourceIdentity
    activationStartExclusive
    activationEndExclusive?
    order
}
```

`scopePath` is relative to the managed document root. Two managed documents may both have `scopePath = /` and the same raw Channel key; they remain distinct occurrences because `managedDocumentId` and `channelOccurrenceIdentity` differ.

The general snapshot model in this subsection applies to ordinary `PROCESS`.
When the snapshot is frozen into a Contracts 1.0 affected-closure invocation,
§2.2.1 narrows it to the managed Root: `scopePath = /` and
`scopeActivationGeneration = 0`.

The snapshot is bound to the exact managed document BlueId, graph generation, activation generation, and runtime registry. It is not accepted when any binding is stale.

The Channel occurrence identity is a domain-separated hash of the normalized managed document ID, local scope path, activation generation, raw contract key, effective runtime contribution BlueId, and subscription-header BlueId.

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

Informative example:

```text
sourceChannel accepts an externally attributed message
message payload names operationsChannel as the effective target
handlers bound to operationsChannel execute
sourceChannel owns the checkpoint
operationsChannel is not separately accepted or checkpointed
```

This supports delegated or routed operation protocols without rewriting the external event or adding a third `PROCESS` input.

#### 3.3.3 Logical delivery grouping

Several raw source Channel occurrences may represent one logical delivery. Grouping MUST use the complete key:

```text
managedDocumentId
local scopePath
scope activation generation
handlerChannelKey
logicalDeliveryKey
```

No group may cross a managed document boundary or activation generation.

For ordinary `PROCESS`, every field in this full key may distinguish a managed
scope occurrence. In the Contracts 1.0 affected-closure profile, `local
scopePath` and `scope activation generation` are invariant `/` and `0`; they
remain explicit identity fields and MUST be validated rather than omitted or
inferred.

Within one group, raw source occurrences retain canonical snapshot order for checkpoint writes. The processor rejects a group when accepted occurrences disagree about channelized payload, target operation, handler Channel, checkpoint domain, or other identity-bearing delivery evidence.

Two groups with equal raw contract keys but different managed documents are independent direct seeds.

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

Every concrete Channel and managed occurrence has an activation interval in canonical external order.

A Channel or occurrence introduced by external event `E`:

- does not receive `E` as a direct external delivery;
- may initialize through the processor-managed activation phase of the same tentative closure;
- may receive exact caused updates or internal events created after it becomes active in that tentative state;
- has its ordinary direct external interval begin strictly after `E`.

A feeder or coordination layer may later select historical entries for an imported process according to its own exact frontier and completeness rules. Those entries are ordinary exact processing causes when supplied; Contracts does not discover or fetch them.

When an external attachment commits an inactive occurrence with non-null
`pendingHistoricalEpoch`, Coordination establishes a catch-up barrier for that
occurrence lineage. It dispatches at most one authenticated contiguous
`ManagedRevisionCause` after the previous invocation terminally commits. It
does not submit a batch to Contracts and does not permit a later public/live
cause whose readiness depends on that closure to overtake the barrier. The
barrier ends only when a committed managed-revision result clears the cursor and
activates the occurrence. A terminally blocked acquisition leaves that
occurrence unavailable and does not authorize dependent live/public work to
overtake it.

A removed occurrence remains eligible for exact work occurrences whose frozen target set was created before removal, unless termination or active-scope cut-off independently forbids that work. Work created after removal uses the new graph. Contracts 1.0 realizes remove and re-add at the same source/path across a committed invocation boundary: removal creates the inactive successor, and a later invocation may activate it. This creates a new activation generation and never reuses the old occurrence identity or checkpoint lineage; same-invocation remove-then-re-add is unsupported.

### 3.6 External completeness and canonical order

The feeder MUST not process event `E` until the concrete external-source ecosystem has supplied completeness evidence that no active subscribed source can later produce an eligible event ordered before `E`.

Each concrete external-source specification MUST publish:

```text
source-local order key
source-local completeness rule
stable source identity used by the external-order policy
```

The managed execution environment binds one exact **external-order policy identity**. That policy MUST define a strict total order over eligible events from all active sources and satisfy all of these laws:

1. **Per-source consistency.** If one source's final order places `A` before `B`, the cross-source policy MUST also place `A` before `B`.
2. **Totality.** For any two distinct eligible event occurrences, exactly one orders before the other.
3. **Determinism.** The result depends only on identity-bound source evidence and policy fields, never arrival order, query order, cache state, locale, or host scheduling.
4. **Stable tie-breaking.** Equal source-neutral time values or other primary keys are resolved by exact identity-bound tie-break fields published by the policy.
5. **Policy stability.** The policy identity is fixed for the managed-root session or changed only through an explicit migration that defines progress continuity.
6. **Completeness compatibility.** Before selecting `E`, the feeder has evidence from every active source interval that no still-eligible event can later appear with a global order key less than `E`.

Contracts core treats concrete order-key components as opaque evidence. It does not define clocks, timelines, providers, or one universal tie-break tuple.

An informative feeder loop is:

```text
repeat:
    assert subscription index matches authoritative Root revision
    obtain each active source's next known event and completeness frontier
    choose the least globally ordered candidate E
    wait until every active source proves no eligible event precedes E
    derive the complete preselected delivery snapshot for E
    process and persist one revision-bound terminal result for E
```

No later external event may interleave with the retained deliveries of the current event. The complete canonical delivery set of `E` reaches one terminal progress record before the feeder begins `E2`.

### 3.7 Canonical delivery snapshot

For one selected original event, the canonical snapshot binds at least:

```text
managed Root/session revision or component generation
exact event BlueId and canonical external-order key
ordered raw External Channel occurrences
logical-delivery grouping and target Handler Channels
activation interval identities
concrete managed document and scope identities
current Process Embedded graph generation
strongly connected component membership
current component MASTER/member mapping when applicable
canonical direct-seed order
profile/runtime/gas identities
```

The snapshot is derived from the pre-event authoritative state. It is immutable for retry. Edges or Channels added by the event do not become direct recipients of the same event.

For an ordinary acyclic Root, direct deliveries retain the existing deeper-first/path/order/key rules. For direct deliveries inside one cyclic component, §4.7 defines canonical member ordering.

### 3.8 Revalidation and false positives

The processor MUST revalidate that every raw direct occurrence remains valid for the frozen Root/component state and event.

A physical index may return false positives. It MUST NOT omit a valid occurrence.

Before cyclic work begins, revalidation also proves:

```text
complete-set proof matches the frozen component generation
member mapping is unchanged and unambiguous
all frozen direct deliveries belong to active component members
component and occurrence limits are satisfied
```

Dynamic graph changes caused by the event are tentative caused work. They do not rewrite the original direct-recipient snapshot. If the authoritative Root/component changed before invocation, the plan is stale and processing does not begin.

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

For a cyclic component, terminal progress and every touched source checkpoint commit only with the complete component publication. A gas-killed or deterministic nonquiescent component leaves all source progress unchanged.

The processor returns the deterministic failure. The selected Coordination profile defines whether the exact offending entry is quarantined, operator-blocked, or otherwise terminally prevented from infinite automatic retry. Later ordered work MUST NOT overtake it when profile readiness requires the blocked component.

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
- already be NFC; a non-NFC raw key is rejected rather than normalized;
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

Every Text value consumed as a Contracts portable-order token MUST already be
NFC. A non-NFC token is invalid at admission or typed value construction;
Contracts MUST NOT silently normalize it. Callers MAY normalize before that
boundary, in which case only the resulting NFC token is authoritative. This
rule covers `DocumentId`, managed/local/receiving/source and patch Runtime
Pointer paths, raw Contract/Channel/Handler keys, subscription and logical-
delivery keys, admission/order/gas/portable-limit policy labels and names,
gas namespace/counter and limit names, checkpoint runtime discriminators,
Text elements of an external `sourceOrder`, and any registered extension Text
field declared to participate in portable ordering. Identity strings with a
closed ASCII grammar are inherently NFC.

This constraint does not normalize or otherwise change arbitrary Text or
object keys inside ordinary Blue payload content that are not consumed as
Contracts control/order tokens. RFC 8785/BlueId serialization remains byte-for-
byte over the supplied Blue value. Portable comparison of admitted tokens uses
Unicode scalar/code-point sequence order and is case-sensitive; it never uses
UTF-16 code-unit order.

Within one managed scope, contracts are ordered by:

```text
runtime-defined order Integer ascending
raw contract key in Unicode code-point order
runtime-type BlueId in canonical text order
```

Handlers are ordered by:

```text
Handler order Integer ascending
raw Handler key in Unicode code-point order
Handler runtime-type BlueId
```

Acyclic component order is the unique reverse topological order of the condensation graph: every target component precedes every source component that embeds it. Incomparable components are ordered by:

```text
minimum normalized member DocumentId
minimum active occurrence identity
component generation
```

Inside one cyclic component, direct seed order is:

```text
target DocumentId
local scope path
scope activation generation
Channel runtime order
raw Channel key
logical delivery key
first raw source occurrence order
```

Under the Contracts 1.0 affected-closure profile, the local-scope and
activation-generation positions above are always `/` and `0`. They remain in
the canonical tuple so the frozen direct-delivery shape is complete; they do
not authorize non-Root closure seeds.

For one internal update or event occurrence with several target occurrence edges, target order is:

```text
target DocumentId
target local receiving path
target activation generation
source DocumentId
source transition occurrence identity
event/update occurrence ordinal
Channel runtime order
raw Channel key
Handler order
raw Handler key
```

Blue Language cyclic member suffix order remains independently governed by preliminary BlueId sorting. Processing order and `MASTER#index` order are not interchangeable.

No semantic order may depend on author map insertion, Java collection iteration, database row order, thread scheduling, cache state, current mutable member BlueId, or document admission order.

### 4.8 Dispatch snapshots

For one channel delivery, the handler candidate list is snapshotted immediately before the first handler predicate is tested. The snapshot freezes key, contribution identities, effective type, dispatch fields, order, and body identities.

Changes to contracts during that delivery do not add, remove, reorder, or replace candidates in the current snapshot. They affect later discovery points.

For an accepted external delivery, its channel snapshot, payload, checkpoint domain, and checkpoint subject are frozen before initialization. Initialization may change the current contracts map, but the already accepted delivery continues from its frozen snapshot unless its scope is cut off or terminated. Handler discovery occurs after initialization and therefore observes post-initialization contracts.

### 4.9 Same-scope binding

A Handler binds to exactly one channel key in the same scope through its effective `channel` field. A missing same-scope channel makes the Handler inert unless its exact runtime type declares that shape invalid.

The effective contracts of an embedded scope are resolved from that scope's own content, type chain, and overlays. Embedding does not import, inherit, or alias contracts from a parent or ancestor scope. A contract key in an ancestor has no same-scope effect in the child merely because the raw key is equal.

An exact Channel node may be reused in several scopes. These are representation-equivalent bindings:

```yaml
teacherChannel:
  blueId: <ExactChannelBlueId>
```

```yaml
teacherChannel:
  type: <ConcreteChannelType>
  # exact materialized content whose BlueId is ExactChannelBlueId
```

The two forms identify the same Channel value. They do not create a live link to another contract-map key. If a parent later replaces its own Channel, an existing child reference still identifies the old exact Channel until the child occurrence is explicitly changed.

Contracts 1.0 defines no informal `Parent Channel`, ancestor-key lookup, nearest-parent lookup, or context-dependent channel port. A future runtime may define an explicit cross-scope binding type only through a separately published exact runtime-type BlueId and complete dependency, subscription, checkpoint, invalidation, cycle, and gas semantics. Implementations MUST NOT infer such behavior from ordinary embedding or raw key equality.

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

MUST hold, except that an explicitly permitted patch to the declaration fields `contracts/embedded/paths` or `contracts/embedded/collectionPaths` may change only those declaration fields while preserving the exact Process Embedded type and every other effective field.

This comparison catches indirect changes caused by replacing `/type`, `/contracts`, or an ancestor of a protected contribution.

### 4.11 Execution context

A runtime call may receive only deterministic values:

```text
$scope             current local scope path within the managed document
$document          read-only exact latest tentative managed document
$event             current channelized payload
$processingEvent   original external event; absent for admission and managed revision
$contract          frozen current contract snapshot
$channel           frozen Channel snapshot, when applicable
$gas               one shared live-bounded closure meter
```

Each managed document executes with itself as the document Root. An embedded managed document does not receive an ambient containing document, parent, sibling, reverse occurrence, list of containers, count of containers, or external occurrence path. It may read another managed document only when that exact document is explicitly present through one of its own Blue fields, including an active `Process Embedded` path.

The fact that a document is embedded by zero, one, or many other documents is host graph metadata and MUST NOT change its Handler selection, `$document`, `$scope`, BEX values, patches, events, or portable gas for the same exact input.

Inside a cyclic component, explicit member references resolve to the latest exact tentatively finalized member value. `$nodeBlueId`, exact reference reads, schema validation, event construction, patch copying, equality, and provider access observe those exact temporary values. Invocation-local handles MAY implement resolution internally but are never exposed as Blue values.

Those reads use the exact `TentativeResolutionContext` from §2.2.3. The
resolver rebuilds that context after every required tentative finalization; it
never serves a later runtime call from a pre-finalization document-to-BlueId
map. The context itself remains processor-private and therefore does not give a
document ambient visibility of its component or containers.

A value copied into an event or patch is frozen at the exact tentative identity visible when the runtime emits or returns it.

The context MUST NOT expose wall-clock time, randomness, ambient I/O, host object identity, mutable caches, thread scheduling, unregistered state, or hidden parent bindings.

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

A candidate body is demanded only after its matcher succeeds. Passing an already admitted exact node into or out of a runtime preserves its BlueId and MUST NOT recursively clone, serialize, or size it.

A runtime either debits the shared meter live or uses a child meter initialized with the exact remaining budget. It MUST NOT do both for the same work. A child ledger is validated and merged exactly once.

---

## 5. Root and Embedded Scopes

### 5.1 Managed document scope

A managed document is one object Root for runtime execution. Local Runtime Pointers and `$scope` are relative to that document.

A containing document may include another managed document as exact content, normally as a pure BlueId reference. The reference and materialized value remain semantically equivalent.

A managed document may be a public Root, an acyclic embedded document, or a cyclic-set member. Stable `DocumentId` evidence does not change Blue Language identity.

Every managed document is evaluated separately as one document step. This does
not create an independent same-cause commit boundary: the closure orchestrator
stages the resulting exact document and publishes it with every other required
document in the closure.

### 5.2 Process Embedded

An effective `Process Embedded` declaration contains:

```yaml
paths:
  - /fixed/path
collectionPaths:
  - /map/of/direct/members
```

Each active concrete path identifies one processable managed occurrence.

For `paths`, each entry MUST be a normalized absolute Runtime Pointer relative
to the managed document Root. When the path is present, it MUST resolve to an
object value and MUST have exactly one verified active occurrence row, except
for the closed pending-historical case below. The path MAY be absent only when
exactly one verified inactive prospective occurrence row reserves it. An
absent path contributes no graph edge, component membership, initialization,
event target, or delivery.

For `collectionPaths`, the collection itself MUST be present and resolve to an
object. An empty object represents zero active occurrences. Every direct member
value is one concrete occurrence. An exact inactive prospective or retirement-
successor row MAY reserve one absent direct member key immediately below that
collection path; it contributes no active occurrence, and deeper absent paths
remain invalid. Lists, wildcards, recursive selectors, and implicit descendant
enumeration are unsupported.

The declaration-to-row requirements above are complete in the declaration
direction, but do not exhaust the authoritative inactive row set. The input MAY
also carry verified inactive prospective or previously committed retirement-
successor rows whose paths are not covered by a current declaration. Such a row
contributes no edge or work. If its path is present, the exact value must still
establish its `expectedTargetBlueId`. It can become active only after the same
completed output contains an effective declaration covering that exact path and
the ordinary exact-value checks pass; activation preserves its generation and
occurrence identity. This permits declaration introduction in a later
invocation and retention of a successor after declaration removal without an
ambient staging map.

The same source path MUST NOT be generated by two declarations. One declaration MUST NOT be a strict ancestor or descendant of another immediate declaration in the same managed document.

`Process Embedded` is the only authored dependency graph. Active platform
occurrence rows identify continuing managed lineage for the exact values already
present at those paths. An inactive prospective row reserves evidence for a
possible later exact value but does not add an edge. Once an accepted mutation
makes its declared concrete path resolve to the verified exact value, that same
row MUST become active at that mutation boundary; the processor MUST NOT delay
activation while treating the resulting content as authoritative. The one
closed exception is an inactive row with non-null `pendingHistoricalEpoch`: its
exact historical cursor value may be present while the row remains inactive
until the final managed-revision reconciliation in §7.5 clears the cursor.

### 5.3 Concrete graph and component partition

The concrete active graph is:

```text
vertices:
    managed DocumentIds

edges:
    active managed occurrence bindings
    sourceDocumentId -> targetDocumentId
```

The graph MUST be finite and within portable limits.

A strongly connected component with one vertex and no self-edge is acyclic. A component with more than one vertex, or one vertex with a self-edge, is cyclic and requires complete cyclic-set evidence.

The processor calculates a deterministic strongly connected component partition and condensation graph. The condensation graph is acyclic and is processed in the order defined by §4.7.

A cycle through an opaque member for which complete owning-set proof is unavailable remains unsupported and fails closed.

### 5.4 Entry graph snapshot

Before semantic mutation, the processor freezes:

```text
input graph generation
input active and inactive occurrence rows and activation generations
input component partition, component identities, and component-state identities
current exact document and member identities
current complete cyclic proofs
direct-delivery target occurrences
```

Graph discovery alone creates no work.

Direct external deliveries are frozen from pre-event state. A new edge created by the current event does not add another direct external delivery of that event.

### 5.5 Participating and dynamically expanding closure

The initial affected closure contains:

- every direct-delivery target managed document;
- every component required to process those targets;
- every active containing document and ancestor component whose exact reference may change;
- every scope required for initialization, lifecycle, update, event, checkpoint, validation, or public Root publication.

A Handler patch, initialization result, or processor-managed write may add,
remove, or rebind same-lineage `Process Embedded` content. After each such
identity-affecting change, the processor MUST:

1. derive the exact occurrence-row delta from resulting Blue content;
2. verify exact managed occurrence binding evidence from an explicit matching
   `active: false` row already present in the invocation input; never acquire,
   infer, or stage target/binding evidence during Contracts execution. The row
   need not be covered by the input's current declaration, but activation
   requires the resulting exact value and effective declaration to cover it;
3. expand the closure only to newly required targets and containers;
4. preflight every newly expanded document, runtime type, proof, and limit before its first semantic work;
5. recompute the affected strongly connected partition;
6. preserve already completed direct work, tentative state, queues, event ordinals, gas trace, and invocation identity;
7. continue under the new partition without replaying the external delivery.

Closure expansion may merge two existing cyclic components, create a new cycle from an acyclic region, split a component, or dissolve a cycle.

An expansion that would join a separately executing closure or exceed a portable limit fails the entire invocation before new joined work begins.

Replacing an active path with a value bound to a different managed
`DocumentId` in the same invocation is unsupported and fails before graph
repartition. The active row and a different-lineage inactive candidate cannot
coexist in the one-row-per-source-path snapshot. Contracts 1.0 defines no
different-lineage retarget operation; a BlueId alone never selects managed
lineage.

### 5.6 Occurrence continuity

An `occurrenceIdentity` is stable under ordinary target state changes and cyclic `MASTER#index` churn. It is defined by:

```text
source DocumentId
source path
target DocumentId
activation generation
binding policy identity
```

A changed target BlueId alone does not retire and reactivate the occurrence.

The associated `bindingIdentity` does change whenever `expectedTargetBlueId` changes. The stable identity is used for activation intervals, queue/frozen-target lineage, checkpoints, occurrence ordering, and continuity. The binding identity is used for exact-state revalidation, closure snapshots, compare-and-swap evidence, and commit receipts. A conforming implementation MUST NOT use the state-specific binding identity as the stable queue or checkpoint lineage key.

An inactive prospective row already owns its reserved activation generation.
When the exact path is introduced and verifies, activation changes only
`active: false` to `active: true` on that row; it does not allocate another
generation, occurrence identity, or binding identity. Removing an active path
retires that activation and, at the same accepted mutation boundary, replaces
its row with exactly one inactive successor reservation for the same source
path, target DocumentId, and binding policy. The successor uses the preceding
generation plus one and fresh occurrence and binding identities. It carries the
target lineage's exact current BlueId and has `pendingHistoricalEpoch: null`.
This deterministic successor derivation is the sole exception to the rule that
prospective rows are invocation input; it does not acquire or infer a target or
policy. The successor is output-only and cannot activate in the invocation
that creates it. After it is committed and supplied as an inactive row in a
later invocation input, re-add activates it without another generation or
occurrence-identity allocation. Subsequent exact-state or cyclic finalization
churn may still update `bindingIdentity` under the ordinary rebind rule above.
Changes to
`pendingHistoricalEpoch` during deterministic catch-up likewise preserve
`occurrenceIdentity` while exact rebinds update `bindingIdentity` as required.

An active or inactive reserved path MUST NOT retarget to another managed
`DocumentId`. The attempted invocation fails before mutation. The inactive
successor remains bound to the same managed lineage and only that lineage may
re-add. Contracts 1.0 defines no ambient or feeder-side mutation of the
authoritative occurrence row outside an atomic closure result.

The activation generation changes when the active path is removed, allocating
its inactive successor. Later-invocation activation/re-add of that committed
same-lineage successor does not increment it again. An input-active path that
is also active in the completed output therefore preserves its generation and
occurrence identity; same-invocation remove-then-re-add is unsupported in
Contracts 1.0. Failed or rolled-back changes do not advance it.

Generation numbering is exact: the managed Root scope uses generation `0`; a
first embedded occurrence reservation or activation uses generation `1`; and
every successful retirement-successor allocation at that source path uses
exactly the preceding generation plus one. Later-invocation activation or
re-add of an existing same-lineage reservation does not increment it.
Same-lineage BlueId changes,
including managed-revision and cyclic-finalization churn, retain the generation.
Overflow beyond the portable safe-integer range fails closed.

### 5.7 Component and graph generations

The authoritative graph generation increments by exactly one on a successful invocation when the active occurrence set changes. It remains unchanged for ordinary business-state changes.

A component generation remains unchanged while its member set and internal directed edge set remain unchanged.

`componentIdentity` is the stable partition/generation identity over component
kind, generation, and canonical ordered members. It does not include a current
member BlueId, cyclic master, or proof. `componentStateIdentity` is the exact
current state identity over that stable component identity, the sorted
`(DocumentId, blueId)` member states, nullable cyclic master, and nullable
cyclic proof identity. Ordinary member or `MASTER#index` churn therefore changes
`componentStateIdentity` while preserving `componentIdentity`. A partition or
internal-edge change advances the generation under the rule below and therefore
changes both identities.

When components form, merge, split, dissolve, or change their internal directed
edge set while retaining the same members, each resulting changed component
receives:

```text
1 + maximum input component generation contributing any of its members
```

A newly admitted component with no predecessor generation starts at `1`. A rolled-back invocation advances no generation.

#### 5.7.1 Managed-document epochs

The managed-document `epoch` is durable host revision evidence. It is bound
into closure state and commit fencing, but it is never available to a Handler
or ordinary document execution context.

For one successful `PROCESS_CLOSURE` invocation, every document whose body
changes at any `WORK` boundary advances by exactly one from its input epoch,
regardless of how many work occurrences or finalization boundaries changed it.
This includes processor-owned exact-identity reconstruction of ordinary managed
references, cyclic `MASTER#index` values, and containing spines. A work that
emits an event but changes no document, and a no-op work, do not advance an
epoch.

There is one narrow finalizer-only exception. When the current work target is
the source document of an inactive occurrence with non-null
`pendingHistoricalEpoch`, and that work locally reconciles the exact source
path of that row, or performs that selected row's final activation after the
path is already exact, the row's authoritative target document does not advance
if its only body change at that boundary is finalizer-owned same-lineage
representation churn. The exception is representation neutral and covers the
closed historical attach/reconciliation sequence whether the exact path value
is direct, pure-reference, materialized, or cyclic. It does not exempt the
source document's local rewrite, an unrelated containing-spine change, any
other finalizer-changed document, or the authoritative target when that target
also has a locally staged change.

Processor Direct Writes performed solely by an initialization-marker batch or
checkpoint-settlement batch, together with identity/reference re-encoding
caused solely by that Direct Write, do not independently advance a
managed-document epoch. Admission and initialization establish the admitted
epoch supplied by the host. On rollback no epoch advances.

For one `ManagedRevisionCause`, `fromEpoch` and `toEpoch` advance only the
inactive occurrence's historical-reconciliation cursor. They do not replace or
downgrade the current authoritative child head carried by the input closure.
The complete `afterDocument` proves the exact historical step being reconciled;
it is not installed as the child's current durable document. The named child
therefore retains its authoritative input epoch only under the narrow
pending-historical target exception above. The selected source document's
receipt-directed reference rewrite is its locally staged
`CONTAINING_REFERENCE_UPDATE` result and advances that source once. Every other
document changed by local work or derived finalization advances once. No
inferred epoch, hidden global counter, or closure-wide shared epoch exists.
Overflow beyond the portable safe-integer range fails closed.

### 5.8 Mutation boundaries

A Handler may mutate only within its current managed document and permitted local scope boundaries.

A containing document does not directly patch strict descendants of a separately managed `Process Embedded` occurrence. Child state changes enter the containing document through exact processor-managed occurrence updates and normal Contracts handling.

Within a cyclic closure, each member Handler still mutates only its own document. A member may influence another member through an explicit occurrence update or event caused by exact content changes.

A whole occurrence may be added, removed, or replaced with another exact state
of the same managed lineage through an allowed patch at its immediate source
path. Exact binding evidence and graph reclassification are then required.
Replacement by another managed lineage is unsupported in Contracts 1.0 and
fails before mutation.

### 5.9 Frozen target sets, removal, and cut-off

Every created update or event occurrence freezes its exact eligible stable
`occurrenceIdentity` values. It also records the corresponding creation-time
`bindingIdentity` and target BlueId as evidence that each frozen lineage was
eligible at creation. That binding evidence prevents later work from being
retargeted to another `DocumentId`; it does not pin the business-state value
that the target Handler reads.

At delivery, the processor revalidates the creation-time evidence and resolves
the latest valid exact binding and tentatively finalized document state for the
frozen occurrence lineage. Thus already-created work retains its original
eligible target lineage across same-lineage BlueId and cyclic-master churn, but
observes the latest exact state of that lineage when it runs. A missing or
conflicting historical binding fails closed; it is never treated as field
absence or silently redirected.

Removing an edge does not cancel work already created through that edge. New work created after removal uses the new graph.

Receiver termination and active-scope cut-off remain distinct from edge retirement. They stop already-frozen work only where their existing exact rules require it.

Removing and re-adding the same path across the required committed invocation
boundary creates a new activation generation. Same-invocation
remove-then-re-add is unsupported. Old work, checkpoints, occurrence
identities, and binding identities are never transferred to the new generation.

### 5.10 One authoritative closure

No cyclic member or changed containing document is separately authoritative during one closure invocation. A successful result publishes every required changed document, component proof, occurrence binding, checkpoint, subscription delta, and public event together.

This atomic publication rule does not permit multi-document Handler execution.
Each document has already been processed in isolation; only the staged commit is
closure-wide. A durable implementation may prewrite each immutable document or
epoch object separately and then atomically advance all affected heads and
commit evidence.

An implementation may store tentative exact values by BlueId before commit. Unreferenced immutable content is not authoritative state.

### 5.11 Participant bindings in reusable process occurrences (normative boundary; informative pattern)

Reusable document modules SHOULD carry participant, role, and policy inputs explicitly inside their own exact content. They MUST NOT depend on ambient containing-document bindings.

A containing document may read the complete exact child it contains. The child may read the containing document only when the containing document is itself explicitly embedded in the child's own content, as in a supported cyclic component.

### 5.12 Addressing dynamic collection members (normative boundary; informative example)

A collection member is addressed by its concrete source member key and normalized Runtime Pointer. The member key participates in the occurrence path and therefore in occurrence identity.

Map insertion order is not semantic. Concrete member paths are ordered by normalized path text under §4.7.

## 6. Events and Processor-Managed Channels

### 6.1 Event and work occurrence model

Events are immutable exact Blue nodes. The processor distinguishes:

- the one external processing event;
- lifecycle events;
- Document Update payloads;
- application events emitted by Handlers;
- Embedded Event Delivery wrappers used by containing occurrences.

Every delivery is an exact **work occurrence** with the identity rules of §2.6. Graph traversal does not itself create a delivery.

Closed work kinds are `EXTERNAL_DELIVERY`, `INITIALIZATION`, `DOCUMENT_UPDATE`,
`TRIGGERED_EVENT`, `EMBEDDED_EVENT`, `LIFECYCLE`, and
`CONTAINING_REFERENCE_UPDATE`. Every normative schema, fixture work record, and
implementation enum MUST admit exactly these seven values, including
`LIFECYCLE`; omission of a value or acceptance of an open string is
nonconforming. A managed revision is a processing cause, not a distinct queued
work kind; it seeds one ordinary `CONTAINING_REFERENCE_UPDATE`.
The executable conformance corpus MUST contain at least one `LIFECYCLE` work
occurrence and its queue/gas trace. A Handler-result patch continuation and a
tentative-finalization boundary are synchronous control frames owned by the
currently executing work occurrence; they are not separately enqueued/dequeued
and do not pay closure-work queue counters. A Document Update becomes a work
occurrence only for an actual matching delivery target. This distinction
prevents implementation-stack shape from changing portable gas.

Only application or lifecycle events explicitly emitted by work executing at
the managed Root of a declared public Root are included in the public result.

### 6.2 External Channel

An External Channel is evaluated only for a frozen direct snapshot occurrence.

For one occurrence, the processor:

1. revalidates its managed document, local path, activation generation, Channel contribution, and graph generation;
2. evaluates `PRESELECTS` and `ACCEPTS` against the exact original event;
3. constructs and freezes the channelized payload;
4. calculates and freezes checkpoint domain and subject;
5. evaluates checkpoint newness;
6. groups accepted-new raw sources under §3.3.3;
7. if new, initializes required managed documents and invokes matching Handlers.

Acceptance is immutable for the invocation and cannot read mutable business state. A Channel may accept while no Handler matches; the accepted source may still be checkpointed on successful closure publication.

### 6.3 Document Update

Every successful application patch, generated type-generalization write, or processor-managed containing-reference rewrite creates one immutable update occurrence after exact tentative finalization.

This rule applies without exception to a patch returned by an initialization or
termination lifecycle Handler. Such a patch crosses the same orchestrator-owned
`afterPatch` continuation: tentative finalization and exact managed-reference
synchronization complete first, authored and finalization-caused containing
updates are classified against the latest finalized bodies, and all matching
routes are accepted in canonical order before their immediate FIFO is drained.

The occurrence freezes:

```text
source transition occurrence identity
source managed DocumentId
absolute local changed path
patch-origin local scope path
before/after exact snapshots and presence
before/after exact BlueIds when present
frozen target occurrence identities
semantic update operation
zero-based update ordinal in the source transition
```

For each target managed document, the processor renders one target-local Document Update payload with paths relative to that managed document.

`before` and `after` are omitted when absence is indicated. Null is not an absence sentinel.

The semantic operation is derived from presence and exact identity:

```text
before absent, after present  -> add
before present, after present -> replace
before present, after absent  -> remove
same exact before/after BlueId -> no update occurrence
```

### 6.4 Immediate Document Update continuation

Handler result patches are processed in list order. For each patch:

1. apply the patch persistently;
2. restore type and protected-state soundness;
3. derive staged graph changes and expand/repartition the closure when required;
4. tentatively finalize exact affected identities under §7.7;
5. create exact update occurrences;
6. completely process the update continuation, including nested patches and their updates, before the next patch from the original Handler result.

This is a deterministic continuation rule, not implementation recursion. A conforming implementation MAY use an explicit deque/stack, but the observable order MUST equal complete nested update continuation before the next sibling patch.

A cyclic path may revisit a managed document through a later distinct update occurrence. A node-level visited set is forbidden.

### 6.5 Application event emission

After all patches from one Handler result and their update continuations complete, emitted events are processed in list order.

For each event, the processor:

1. validates it as an admissible exact Blue node;
2. freezes the exact event BlueId and any copied temporary member values visible at emission time;
3. constructs `EventOccurrenceId` under §2.6;
4. freezes exact eligible local and containing occurrence targets from the current active graph;
5. appends it to the public result immediately only when the emitting work
   targets that document's Root managed-scope identity and the managed document
   is a declared public Root;
6. appends the occurrence to the invocation FIFO.

Equal event BlueIds emitted twice have different occurrence IDs and remain two deliveries.
The allocation and queue rules are identical during admission initialization
and termination lifecycle work. One emission is appended to the public
projection at most once, at its creation boundary; delivering or observing that
same occurrence never appends it again.

### 6.6 Triggered Event Channel

When an event occurrence is dequeued, it is first delivered to matching Triggered Event Channels in its source managed document and local source scope, when active and not terminating or terminated.

Handlers are freshly discovered under the exact latest tentative source document. Events emitted by them are appended to the FIFO after the current occurrence.

### 6.7 Embedded Node Channel

After local Triggered handling, the same occurrence is offered through every frozen active containing occurrence target in canonical order.

The target receives an exact wrapper conceptually equivalent to:

```yaml
type: Embedded Event Delivery
sourcePath: <local path from receiving document to source document occurrence>
event:
  blueId: <exact event BlueId>
```

The target managed document sees its own latest exact `$document`, including the latest tentatively finalized source member reference.

Observation does not make the nested event public. The receiving public Root must explicitly emit an event for it to appear in the public result.

### 6.8 Lifecycle Event Channel

The processor emits:

```text
Document Processing Initiated
Document Processing Terminated
```

Lifecycle Channels receive only processor-generated lifecycle events. Lifecycle work follows the same exact context, patch, update, event, graph, finalization, gas, and rollback rules.

### 6.9 Event queue order

The canonical application-event queue is FIFO by emission occurrence.

For one event occurrence:

```text
source Triggered delivery
then containing occurrence targets in §4.7 order
```

Every caused patch and complete immediate Document Update continuation finishes before that event delivery continues. Events emitted during one delivery are appended to the FIFO and do not interrupt the current occurrence.

The queue is drained only by the normative event-drain step. External, lifecycle, and update helpers enqueue but do not independently double-drain it.

Normative `ADMIT_CLOSURE` uses this queue and the ordinary closure work queue;
it does not run initialization events through a bounded side channel. Events
emitted while handling one occurrence wait behind the complete delivery batch
already accepted for that occurrence.

### 6.10 Processor-managed writes

| Write | Creates Document Update? |
|---|---:|
| Application Json Patch | Yes |
| Generated type-generalization write | Yes |
| Whole managed occurrence reference rewrite | Yes |
| Cyclic internal reference re-finalization | Yes, once per active occurrence whose target exact BlueId changed |
| Processing Initialized Marker | No |
| External Channel checkpoint | No |
| Processing Terminated Marker | No |

Cyclic reference rewrites performed by one tentative finalization do not recursively trigger another finalization merely because suffix strings changed. They create update occurrences comparing the previous exact finalized component state with the new exact finalized component state.

The three `No` rows are origin rules for the complete processor Direct Write,
not merely for its innermost object member. Exact component re-finalization and
containing-reference propagation caused solely by an initialized, checkpoint,
or terminated marker write remain part of that non-notifying Direct Write and
create no application Document Update. A reference rewrite caused by an
application patch, generated application-visible write, or ordinary occurrence
state transition retains the `Yes` behavior above. This distinction prevents a
processor marker from recursively invoking application logic while still
requiring its exact changed component and containing-spine identities.

## 7. Normative Processing Algorithm

### 7.1 Run state

One closure invocation maintains tentative state conceptually equivalent to:

```text
RUN.operationKind
RUN.cause
RUN.admissionCandidate
RUN.admissionCandidateIdentity
RUN.invocationIdentity
RUN.inputGraphGeneration
RUN.currentTentativeGraphGeneration
RUN.managedDocuments
RUN.occurrenceBindings
RUN.componentPartition
RUN.componentGenerations
RUN.componentIdentities
RUN.componentStateIdentities
RUN.currentExactDocumentBlueIds
RUN.currentCyclicProofs
RUN.directSeedQueue
RUN.workQueue
RUN.admissionInitializationPlan
RUN.updateContinuations
RUN.eventQueue
RUN.publicEvents
RUN.completedDirectDeliveries
RUN.initializedDocuments
RUN.initializationBatches
RUN.pendingTerminations
RUN.terminatingDocuments
RUN.terminatedDocuments
RUN.checkpointWrites
RUN.pendingCheckpointSettlements
RUN.subscriptionDeltas
RUN.graphChanges
RUN.transitionOrdinal
RUN.workOrdinal
RUN.documentStepOrdinal
RUN.documentStepTrace
RUN.stagedDocumentResults
RUN.nextFinalizationOrdinal
RUN.eventOrdinalsByTransition
RUN.providerDemands
RUN.gasTrace
```

Implementation structures may differ. Observable state, exact identities, order, and canonical trace may not.

### 7.2 Phase A — admission and exact evidence

For `PROCESS`:

```text
1. Require exact Root and event identities.
2. Require Root to be an object.
3. Begin the shared meter and charge processInvocation with rejected-charge owner `INVOCATION`.
4. Check direct Root terminated state.
5. Verify the direct-delivery snapshot is bound to this exact Root and event.
```

For closure operations:

```text
1. Require exact closure and cause identities and enforce the closed
   operation/cause/direct-delivery coupling from §2.3.
2. Require the nullable candidate pair, recompute a non-null candidate identity,
   and reject a null/non-null mismatch or a candidate on PROCESS_CLOSURE.
3. Begin one shared meter and charge, in order, one processInvocation and then
   one closureInvocation, both with rejected-charge owner `INVOCATION`.
4. Validate canonical DocumentIds and uniqueness.
5. Verify every exact document BlueId.
6. Verify every active and inactive occurrence row's closed shape, occurrence
   identity, binding identity, binding policy, uniqueness, and target-lineage
   evidence. For active rows additionally verify the exact source path,
   declaration, and value; repeat those active-only checks immediately before
   an inactive row activates.
7. Verify graph generation and component generations.
8. Verify current SCC partition.
9. Verify every current cyclic MASTER and member proof.
10. Verify public Root declarations.
11. For a non-null ADMIT_CLOSURE candidate, independently execute the verifier
    selected by its closed branch before any initialization or Handler work.
12. Check terminated public/direct target state.
13. For ManagedRevisionCause, additionally recompute the source revision
    receipt and managed-revision cause identities; prove one contiguous safe
    epoch; establish afterDocument -> afterBlueId; verify the named inactive
    occurrence, cursor, before path value, target lineage, and current
    authoritative target epoch; reject a stale, skipped, duplicated, future, or
    already-active step before mutation.
```

Candidate verification never trusts the branch label as a verdict.
`BAD_CYCLIC_PROOF` verifies the complete submitted proof against the exact
component evidence; `AMBIGUOUS_PRELIMINARY_MEMBERS` runs the unchanged Blue
Language §15 preliminary calculation on the complete submitted member set; and
`INVALID_OCCURRENCE_BINDING` recomputes every occurrence identity and binding
and proves each active path from exact source content plus the effective
`Process Embedded` declaration. The ordinary proof, ambiguity, and
route/binding diagnostics are derived from those checks. Candidate evidence is
never copied into `RUN.managedDocuments`, `RUN.occurrenceBindings`, or
`RUN.componentPartition`.

The closed candidate shape, candidate constructor, null pairing, invocation
binding, and availability and provider verification of referenced exact nodes
are admission structure. A failure of that structure may precede the meter. A
structurally valid, invocation-bound non-null candidate is not a pre-meter
failure: it first admits the exact two-charge prefix in step 3, pays the ordinary
authoritative-closure admission trace in steps 4–10, and then pays the following
invocation-owned, short-circuit semantic verifier trace. This ordering proves
the current closure before using it as comparison evidence. The first failed
comparison ends that branch; work after it is neither performed nor charged.

`COMPARE_IDENTITY_TOKEN(a,b)` means one `scalarComparison` followed by the
`textBlockExamined` quantities for both operands from §13.8. It compares the
complete NFC token lexicographically; equality therefore reads both complete
tokens. Candidate-branch metering is:

1. `BAD_CYCLIC_PROOF` charges one `validationMemberExamined` for the submitted
   proof record, then compares its `componentIdentity` and `masterBlueId`, in
   that order, with `COMPARE_IDENTITY_TOKEN`. If both pass, it compares the
   member-state cardinality with the §13.9 integer-equality quantity, then
   visits member states in their required `DocumentId` order, charging one
   `validationMemberExamined` per state and comparing `documentId` then
   `blueId` with `COMPARE_IDENTITY_TOKEN`. Only if those checks pass does it run
   the complete unchanged Language §15 placeholder-set/proof verification,
   charging its ordinary semantic identity, sorting, canonical-text, and
   validation work. Thus, after its ordinary 190-gas authoritative two-member
   admission trace, C-CLO-14 charges one `validationMemberExamined`, the equal
   component-identity comparison, and the first-code-point-different master
   comparison, then stops. With the frozen 1.0 weights and tokens its total is
   `190 + 1 + (1 + 4) + (1 + 2) = 199` gas: the equal 71-code-point `sha256:`
   identity reads two blocks from each operand, while the two master BlueIds
   differ in their first code point.
2. `AMBIGUOUS_PRELIMINARY_MEMBERS` visits the submitted members in their
   required `DocumentId` order and charges one `validationMemberExamined` per
   member. For each member it performs the unchanged Language §15 ZERO_BLUEID
   substitution and preliminary BlueId calculation, charging §§13.12–13.13
   exactly and reusing an already established identical preliminary node under
   the run-local identity ledger. It then uses §13.10 stable bottom-up merge
   sort. Each comparator charges one `sortComparison`, compares preliminary
   BlueId text with `COMPARE_IDENTITY_TOKEN`, and, only on equality, compares
   the two RFC 8785 byte sequences of the complete normalized preliminary
   BlueId inputs from Language §§14.2–14.4 with another
   `COMPARE_IDENTITY_TOKEN`. This is never the raw submitted fixture form. For
   that tie-break meter the canonical UTF-8 JSON is decoded as its Unicode
   scalar sequence; UTF-8 byte order and Unicode scalar order agree for valid
   JSON. A byte-identical tie rejects immediately.
   Thus C-CLO-15 performs exactly two member examinations, the ordinary
   preliminary-identity trace (with second-node identity reuse), one merge
   comparison, one equal preliminary-identity comparison, and one equal
   canonical-input comparison before rejection. Concretely, its authoritative
   singleton acyclic admission prefix is 162 gas; the first zeroed member adds
   `nodeIdentityEstablished=1`, `objectMemberRebuilt=2`, and
   `directIdentityHashBlock=3`, the identical second zeroed member reuses that
   node. The normalized preliminary input is 154 code points, so the two
   comparisons add the §13.8 quantities `1+2` and `1+6`.
   Together with two member examinations and one sort comparison, C-CLO-15 is
   exactly `162 + 2 + 6 + 1 + 3 + 7 = 181` gas.
3. `INVALID_OCCURRENCE_BINDING` visits submitted rows in required
   `(occurrenceIdentity,bindingIdentity)` order. Immediately before each row it
   charges one `managedOccurrenceBindingVerified`, attributed to that row's
   `sourceDocumentId`; that counter owns the two platform-constructor
   recomputations and fixed binding-field checks. It then resolves
   `sourcePath` left-to-right, charging one `pointerSegmentTraversed` per
   attempted segment and stopping on the first absent or non-container
   segment. Only a resolved path proceeds to effective `Process Embedded`
   coverage in canonical declaration order, paying one
   `embeddedPathEntryRead` per entry and one `embeddedPathSegmentValidated` per
   compared segment, followed by ordinary exact target, activation, lineage,
   and policy comparisons. Thus, after its ordinary 190-gas authoritative
   two-member admission trace, C-CLO-29 charges one
   `managedOccurrenceBindingVerified` and one `pointerSegmentTraversed` for
   `/missing`, then stops, for exactly `190 + 5 + 1 = 196` gas under the frozen
   1.0 weights.

The common base prefix has quantities `1,1`; the authoritative admission between
that prefix and the candidate branch uses the ordinary counters and canonical
ordering already required for a candidate-free invocation. All branch
quantities above are per item, row, comparison, or traversed segment as stated.
These negative branches enqueue no work, run no initialization or Handler,
compare or write no checkpoint, and perform no tentative component
finalization. Once the first charge is admitted, all other deterministic
verification follows the ordinary live-meter rules.

For `ADMIT_CLOSURE`, Phase A also verifies the admission portable limits and all
initial edge, component, cyclic-proof, and finalization evidence required before
the first initialization seed. These are invocation-owned admission operations
on the one shared meter; switching from bounded compatibility execution to the
normative lifecycle queue does not omit or move them to another ledger.

### 7.3 Phase B — revalidate and classify direct deliveries

For each frozen external snapshot in canonical order:

1. verify its managed document, Channel occurrence, and graph generation, and
   reject unless its local path and activation generation are exactly `/` and
   `0` under the Contracts 1.0 closure profile;
2. skip a removed, cut-off, terminating, or terminated occurrence;
3. resolve the exact effective Channel contribution;
4. evaluate `PRESELECTS` and `ACCEPTS`;
5. construct and freeze payload, checkpoint domain, subject, handler Channel,
   and logical delivery key, and reject unless the recomputed handler Channel
   and logical delivery key equal the values frozen by the feeder for this
   direct delivery;
6. for each source occurrence for which `ACCEPTS` is true, charge exactly one
   `checkpointCompared`, attributed to that source managed `documentId` and
   with rejected-charge owner `INVOCATION`, then perform the registered
   subject-policy work and compare the source checkpoint;
7. record the raw occurrence as new or stale;
8. group accepted-new occurrences using §3.3.3;
9. reject inconsistent groups before mutation.

This phase is read-only. Graph changes later in the invocation do not add direct recipients of the same event.

The `checkpointCompared` charge in step 6 is admitted immediately before the
checkpoint lookup/newness comparison, whether the result is new, stale, or
virtual empty. A source that does not accept pays no checkpoint comparison.
Raw sources are charged independently in the frozen canonical source order;
later logical-delivery grouping never coalesces these charges. Phase B completes
before Phase C initialization or any Handler call, so rejection of this charge
or failure of its subject policy cannot leave initialization or Handler work in
the trace.

`ADMIT_CLOSURE` and a managed-revision `PROCESS_CLOSURE` skip direct
classification. Admission also creates no checkpoint comparison or settlement
candidate.

### 7.4 Phase C — initial closure and must-understand preflight

Before first mutation:

1. derive the initial affected closure;
2. compute its SCC partition and condensation order;
3. verify exact cyclic proof for every cyclic component;
4. recognize every effective contract type and runtime role required by direct work, initialization, propagation, validation, and publication;
5. validate Channel/Handler bindings, occurrence bindings, schemas, protected state, and portable limits;
6. ensure exact resources for first possible work are available.

Unsupported or malformed structure fails atomically before initialization or Handler work.

### 7.5 Phase D — canonical direct seeds

Every seed and every caused work occurrence is executed through the managed
document step boundary in §2.2.2. The orchestrator loads the target document's
latest tentative exact state, invokes only that document's Channels/Handlers,
records one document-step evidence row, stages the result, and then performs any
required graph reconciliation or identity finalization before selecting the
next work occurrence.

There is no cyclic-specific Handler path. A work occurrence targeting a member
of a cyclic component is executed by the same document-step processor as work
targeting an acyclic document.

For `ADMIT_CLOSURE`, Phase D first constructs one immutable initialization plan.
It contains every managed document present in the input snapshot whose valid
initialized marker is absent, including an admitted public Root with no active
incoming managed occurrence. Components are ordered target-before-source using
the canonical reverse topological condensation order; members within one
component are ordered by `DocumentId`. Input map, list, provider, cache, and
admission order cannot change the plan.

Each planned document is seeded exactly once with
`sourceOccurrenceIdentity = AdmissionCause.causeIdentity`. Its `INITIALIZATION`
work, generated `LIFECYCLE` work, patches, Document Updates, application events,
terminations, graph changes, containing-reference work, and finalizations all
use the same normal work/event queues and shared invocation meter. The complete
causal closure for the selected component reaches quiescence before its marker
barrier and before the next component begins. A document already initialized in
the input or by an earlier completed admission batch is not seeded again.

For an external cause, every accepted direct seed is materialized as one `EXTERNAL_DELIVERY`
`WorkOccurrence`, charged once for `closureWorkOccurrenceEnqueued`, enqueued,
then charged once for `closureWorkOccurrenceDequeued` immediately before its
delivery. The same enqueue-once/dequeue-once rule applies to each actual
occurrence of all seven closed `WorkKind` values. Synchronous patch frames and
tentative-finalization boundaries retain the non-work treatment in §6.9.
Every such work occurrence, including initialization, lifecycle, event,
managed-revision, and containing-reference work, targets the Root
managed-scope identity required by §2.2.1. Embedded occurrence identity is
source/routing evidence and never replaces that target scope.

For a `ManagedRevisionCause`, Phase D instead performs exactly this sequence:

1. construct one `CONTAINING_REFERENCE_UPDATE` work occurrence targeted at the
   source managed scope of `targetOccurrenceIdentity`, with
   `sourceOccurrenceIdentity = causeIdentity`;
2. charge `closureWorkOccurrenceEnqueued`, enqueue it, then charge
   `closureWorkOccurrenceDequeued` immediately before execution;
3. execute that source rewrite through the same isolated managed-document step
   runtime as every other work occurrence. The runtime charges its ordinary
   Root-scope opening, participating contract headers, managed-path validation,
   patch boundary/application, and exact semantic identity reconstruction in
   their real execution order while rewriting the selected path from an exact
   value establishing `beforeBlueId` to the policy-selected exact form
   establishing `afterBlueId` (normally the pure reference
   `{ blueId: afterBlueId }`). `afterDocument` is the complete cause evidence
   that independently establishes that identity; it need not be inlined at the
   source path;
4. at that step's immediate after-patch reconciliation boundary, charge
   `containingReferenceUpdated`, update `expectedTargetBlueId`, recompute
   `bindingIdentity`, set `pendingHistoricalEpoch = toEpoch`, retain
   `active: false` and the same occurrence and activation generation, and
   charge the exact binding verification. Then perform the exact
   containing-spine reconstruction, affected acyclic-component boundaries,
   any semantic identity work not already established by the ordinary source
   step, and immediate Document Update continuation before any later work
   occurrence;
5. when `toEpoch` is below the authoritative target epoch, finish this caused
   work and return one independently committable result with the cursor still
   pending;
6. when `toEpoch` equals the authoritative target epoch, wait until step 4 is
   quiescent, then re-read the latest tentative authoritative BlueId for
   `childDocumentId`. That identity may have changed because the first rewrite
   updated a containing document that the child itself contains. Prove the same
   managed lineage. If the source-path value does not already establish that
   latest identity, immediately charge a second `containingReferenceUpdated`, rewrite
   it, and rebind the row. If the identities are already equal, do not
   fabricate a rewrite charge;
7. after that exact same-lineage reconciliation, clear
   `pendingHistoricalEpoch`, set `active: true`, verify the activated binding,
   increment graph generation exactly once for the newly active edge,
   repartition the affected graph, and
   perform the ordinary incremental semantic identity work and one immediate
   tentative-finalization boundary for the reconciled path, newly active edge,
   changed component, and ancestor spine before any caused work can observe
   them. Activation itself is not a Handler patch. Create the ordinary Document
   Update only when the exact source value changed, drain all resulting
   update/event work to quiescence, then return the one independently
   committable result.

The acyclic finalization boundary in step 4 is real orchestration work even
when the ordinary isolated step already established the source Root's exact
identity. In that case the boundary remains charged, but the same semantic
identity is not charged a second time. Changed containing ancestors retain
their own boundary, semantic-identity, and reference-rewrite charges. An
implementation MUST NOT replace the ordinary step ledger with a synthetic
managed-revision trace, append obsolete duplicate identity work, or tune gas
to a previously published total.

All charges in steps 3–7 occur at these immediate work/change boundaries and
belong to this invocation's shared meter and the work/finalization owners
defined in §§7.7 and 13. No transition gas block is appended later. A failure or
gas rejection at any point rolls back this complete one-revision invocation;
it never commits the cursor, path, binding, graph, or component independently.
The processor never dequeues a second historical step in this invocation.

For an externally caused `PROCESS_CLOSURE`, accepted-new logical delivery groups are ordered by:

1. reverse topological component order under §4.7;
2. cyclic member/direct seed order under §4.7;
3. first raw source occurrence order.

For each direct seed:

1. skip if its target occurrence is no longer active or is cut off/terminated;
2. ensure every required managed document is initialized under §9;
3. invoke the exact logical delivery;
4. apply Handler results under §7.6;
5. drain internal events to quiescence under §7.9;
6. record each participating raw source as eligible for the later checkpoint
   settlement barrier; do not mutate a checkpoint marker here;
7. finish every caused graph, update, initialization, and containing-reference obligation before beginning the next direct seed.

A cyclic component can be revisited by caused work after a direct seed. The next direct seed begins only when the prior seed's complete causal closure is quiescent.

After the final direct seed, the processor drains the external cause's complete
queued causal closure. Only when no initialization, update continuation, event
occurrence, delivery work, graph expansion, or containing-reference obligation
caused by that external event remains does it cross the single checkpoint
settlement barrier in §7.11. A checkpoint is therefore not visible to a later
direct seed of the same external event.

If a public Root terminates, later direct deliveries to that Root are skipped as specified by lifecycle rules.

#### 7.5.1 Execute one document step

For the next canonical work occurrence `W` targeting document `D`:

```text
1. Load D's latest tentative exact document and BlueId.
2. Reconstruct `TentativeResolutionContext` from the bound invocation and the
   latest tentatively finalized closure snapshot, then construct one
   `DocumentStepInput` bound to W, that context, and the remaining shared gas.
3. Establish $document = D and $scope = /.
4. Select and execute only D's exact Channels and Handlers for W.
5. For each D-local patch, synchronously cross the processor-private
   continuation boundary from §2.2.2. Apply and finalize that patch's complete
   closure effect and drain its immediate D-local update continuation before
   resuming the same document step.
6. Freeze D's ordered emitted events.
7. Stage one `LocalDocumentStepResult` for D. It contains the resulting body
   and the ordered local effects already staged through the continuation
   boundaries, but no `afterBlueId`.
8. Append one documentStepTrace row bound to W.
9. Reconcile any remaining non-patch local identity effect before another
   document step observes the result, then produce or update the
   orchestrator-owned finalized `ResultingDocument`. A patch already handled
   by step 5 is not applied or charged a second time.
10. Enqueue exact caused work for target documents derived from frozen
    occurrence evidence.
```

A document step never enumerates documents that contain `D`. Reverse delivery
is performed by the orchestrator from the verified occurrence index after the
step returns.

### 7.6 Applying one Handler result

A Handler result is validated completely before its first effect.

Application order is:

```text
1. validate and merge runtime ledger once;
2. for each patch in list order:
       APPLY_PATCH_AND_CONTINUE_UPDATE(patch);
3. for each emitted event in list order:
       RECORD_EVENT_OCCURRENCE(event);
4. apply the first termination request;
```

`APPLY_PATCH_AND_CONTINUE_UPDATE` performs:

```text
1. charge and validate patch boundary;
2. apply persistent patch to the target managed document;
3. restore local and changed-spine type/protected-state soundness;
4. derive occurrence/graph delta from exact resulting content;
5. expand and preflight the closure when required;
6. repartition affected components and update tentative generations;
7. TENTATIVELY_FINALIZE_AFFECTED_IDENTITIES;
8. create exact Document Update occurrences comparing the previous and new
   exact finalized state;
9. process the complete immediate update continuation, including nested Handler
   results, before returning to the next original patch.
```

This boundary is per patch, not per Handler result. If one Handler returns two edge-removing patches, the processor applies, reclassifies, finalizes, emits updates, and drains the complete immediate continuation for the first patch before it begins the second. It MUST NOT batch the two graph deltas or append their finalization gas after all Handler effects.

A graph-changing patch is an ordinary Json Patch Entry. No second `componentPatches` or hidden graph-mutation protocol exists.

### 7.7 Tentative identity finalization

Tentative finalization is identity/orchestration work between isolated document
steps. It is not a Handler call and does not grant a cyclic member access to the
other members except through exact fields already present in that member's own
document.

Before any later work occurrence may read a changed managed document or embedded reference, the processor MUST finalize exact tentative identities for the affected region.

The affected identity region contains:

- every changed managed document;
- every member of an SCC containing a changed document or changed internal edge;
- every acyclic containing document whose exact child/member reference changes;
- every ancestor component on changed containing spines.

Finalization proceeds in reverse topological component order.

Tentative-finalization occurrences have one invocation-global zero-based
ordinal. `RUN.nextFinalizationOrdinal` starts at `0`. Immediately before the
first charge belonging to a selected component-finalization boundary, allocate
that value as `finalizationOrdinal` and increment the run counter. Every charge
inside that boundary has rejected-charge owner
`{ kind: FINALIZATION, finalizationOrdinal, componentIdentity,
componentGeneration }`. A later boundary, including another boundary for the
same stable component and generation, receives the next ordinal. Allocation is
in the exact reverse-topological/component order and occurs even when the first
boundary charge is rejected, so retry and rejected-charge evidence name the
same attempted occurrence.

For an acyclic document:

1. install latest exact target references;
2. calculate the ordinary BlueId using Blue Language 1.0.

For a cyclic component:

1. materialize the complete latest tentative member bodies;
2. mark every active internal occurrence reference explicitly;
3. invoke the unchanged Blue Language §15 cyclic-set calculation;
4. verify deterministic preliminary ordering, final `MASTER`, suffix mapping, and complete proof;
5. install final exact `MASTER#index` references throughout the component;
6. expose those exact temporary values to subsequent runtime reads.

Temporary exact identities are nonauthoritative until commit.

If A changes and B later reads A, the processor finalizes the complete component after A's change before B runs. If B then changes and A later reads B, the processor finalizes the complete component again before A runs. Implementations MAY optimize unchanged calculations but MUST produce the same temporary exact values, gas, and provider demands.

One finalization step creates at most one exact update occurrence per active
occurrence whose target exact BlueId changed since the preceding finalized
state, except for the non-notifying processor Direct Write origins in §6.10.
Installing final suffix references is part of the finalization and does not
recursively trigger another finalization by itself.

Identity-gas ownership is exact:

1. all Language identity work needed for one cyclic member transition is owned by that transition's complete tentative cyclic-component finalization;
2. a changed acyclic document is re-identified incrementally: changed/new leaf or exact patch value, changed direct container, and changed ancestor spine only;
3. an unchanged exact child is carried by BlueId and is not recursively charged;
4. a patch value whose exact identity was already established in the invocation-local identity ledger is not charged again when its parent is rebuilt;
5. a newly constructed event value pays semantic identity admission exactly once before `internalEventEnqueued`; each distinct event and work occurrence still pays its own processor orchestration charges;
6. `tentativeComponentFinalization`, its complete Language semantic identity trace, `cyclicMemberFinalized`, and affected containing-reference reconstruction appear immediately after the work occurrence that changed component state identity.

The invocation-local established-exact-node ledger begins empty. A node becomes established only after its complete canonical identity charge has been admitted. Rollback discards the ledger with the attempt. Hidden caches, a previous invocation, and earlier rejected work never seed it.

For `cyclicCanonicalBytesPerComponent`, the measured byte sequence is the UTF-8 RFC 8785 encoding of a representation-normalized cyclic limit form. Begin with the final member order and canonical `this#n` remapping used by the unchanged Blue Language §15 calculation. Within each member, retain each internal `this#n` reference, every object/list container on a path to such a reference, and every complete direct literal member (`name`, `description`, or `value`). Replace each complete non-literal child subtree that is not on an internal-reference path with the pure exact reference `{ "blueId": CHILD }`, where `CHILD` is that subtree's ordinary exact BlueId. Canonically encode the resulting ordered member list.

This byte-limit form is identical for semantically equal inline and exact-reference representations. It is a Contracts accounting projection only: it does not change the actual Blue Language cyclic input or identity result. Count it once per tentative cyclic-component finalization. Preliminary ZERO forms, final member suffix strings, materialized `MASTER#index` substitutions, proof serialization, and containing-document reconstruction are excluded. They remain subject to gas and other direct-node limits.

### 7.8 Dynamic graph reclassification

When exact resulting content changes active occurrences:

1. calculate the concrete occurrence delta from Blue content and binding evidence;
2. activate a matching verified inactive row in place; for every removed active
   occurrence allocate its exact inactive successor at generation plus one;
   reject replacement of an active or reserved path by another target lineage;
3. identify only components reachable through the changed region;
4. recompute SCCs in that induced region;
5. merge with unaffected partition entries;
6. assign tentative graph/component generations under §5.7;
7. preflight newly added members and exact proofs before their first work;
8. preserve all completed direct deliveries, transition/event ordinals, queues, and gas;
9. continue without replaying already applied work.

During `ADMIT_CLOSURE`, activation is limited to an exact prospective occurrence
row already declared in the immutable input and a target managed document
already present there. If initialization creates an effective managed occurrence
without that closed evidence, the attempt returns noncommitting
`SUBSCRIPTION_SURFACE_INVALID` with diagnostic category
`SubscriptionSurfaceInvalid`. It MUST NOT discover, fabricate, append, or
initialize a new managed-document lineage dynamically.

A dynamic change may transform:

```text
acyclic A -> B
into A <-> B
```

or:

```text
one cyclic {A,B,C,D}
into cyclic {A,B} + cyclic {C,D}
```

or into acyclic singletons. The final result model represents every resulting component.

### 7.9 Internal event drain

```text
function DRAIN_INTERNAL_EVENTS():
    while RUN.eventQueue is not empty:
        occurrence = peek FIFO
        CHARGE(processor, internalEventDequeued, 1,
               owner = { kind: INVOCATION })
        dequeue FIFO

        deliveries = []

        if source scope is active and not terminating/terminated
           and a Triggered Event delivery actually matches:
            append MATERIALIZE_EVENT_DELIVERY_WORK(
                TRIGGERED_EVENT, occurrence) to deliveries

        for targetOccurrence in occurrence.frozenContainingTargets in canonical order:
            if target receiving scope is active and not terminating/terminated
               and an Embedded Event delivery actually matches:
                append MATERIALIZE_EVENT_DELIVERY_WORK(
                    EMBEDDED_EVENT, occurrence, targetOccurrence) to deliveries

        for work in deliveries in canonical source-then-containing order:
            CHARGE(processor, closureWorkOccurrenceEnqueued, 1,
                   owner = { kind: WORK,
                             workOccurrenceIdentity: work.identity })
            enqueue work

        for work in deliveries in that same order:
            dequeue that canonical event-delivery work
            CHARGE(processor, closureWorkOccurrenceDequeued, 1,
                   owner = { kind: WORK,
                             workOccurrenceIdentity: work.identity })
            DELIVER_EVENT_WORK(work, occurrence)
            finish its complete synchronous patch/update/finalization
            continuation before dequeuing the next delivery
```

Every delivery uses the latest exact tentatively finalized target document. Handler patches run through §§7.6–7.8 and may enqueue later events.

`internalEventDequeued` is charged exactly once for each event occurrence removed
from the FIFO, not once per target delivery. This charge is incurred even when
the occurrence has zero matching or still-active targets. All actual Triggered
and Embedded deliveries for the occurrence are admitted to the work queue
before the first is dequeued. Each delivery is separately one `WorkOccurrence` and pays exactly one
`closureWorkOccurrenceEnqueued`, one `closureWorkOccurrenceDequeued`, and its
ordinary delivery counter. A skipped or nonmatching target creates no delivery
work occurrence. This separates event-FIFO ownership from delivery-work
ownership without making implementation stack shape observable.

For a frozen containing target, “actually matches” resolves the frozen stable
lineage against its latest exact valid binding under §5.9. It does not require
the originating edge to remain active and does not revert to the creation-time
business-state BlueId. Edge retirement alone therefore neither redirects nor
cancels already-created event work.

An event occurrence created before edge removal retains its frozen targets. Edge retirement alone does not cancel it. Termination and cut-off follow their own exact rules.

The queue continues until empty or the shared gas/portable work limit stops the invocation.

### 7.10 Activation initialization

A processable managed document initializes when:

- accepted-new direct work first requires it; or
- `ADMIT_CLOSURE` admits it; or
- a successful tentative patch introduces a new active processable occurrence.

For `ADMIT_CLOSURE`, “admits it” includes every uninitialized managed document
already present in the immutable input snapshot; an active incoming binding is
not required. The initial plan is the canonical plan from §7.5, not a scan of
documents reached opportunistically while work executes.

A newly introduced occurrence does not receive the introducing external event directly.

Initialization order is by reverse topological component order. Initialization
of one selected component is a whole-component atomic batch, including an
acyclic singleton. Inside one cyclic component, missing members run lifecycle
work by normalized `DocumentId`, then local initialization scope order.

For one component initialization batch:

1. verify every existing initialized marker and identify every member selected
   by the applicable initialization cause whose marker is missing; for the
   initial `ADMIT_CLOSURE` plan this includes every such input member, while
   patch-caused activation retains the ordinary active-member rule;
2. before any such member's lifecycle work begins, freeze that member's exact
   pre-initialization BlueId and exact initialization-contract snapshot;
3. for each missing member in canonical order, enqueue and dequeue its exact
   `INITIALIZATION` work and generated `Document Processing Initiated`
   `LIFECYCLE` work under the normal queue rules, invoke lifecycle handlers, and
   apply all patches, graph changes, updates, events, terminations, and tentative
   identity finalizations under the ordinary closure algorithm;
4. drain all work caused by the batch to quiescence; if graph expansion or
   reclassification brings another missing active member into a current
   component required by the batch, freeze it before its lifecycle work and
   extend the batch until closed. Record the `workTrace.ordinal` of the last
   accepted causal work occurrence as the batch's `afterWorkOrdinal`;
5. revalidate active-scope cut-off for every missing member and prepare one
   direct `Processing Initialized Marker` per still-required exact member, whose
   `document` is the member-specific exact pre-initialization document frozen in
   step 2, normally represented by its equivalent pure BlueId reference;
6. preflight and charge the marker writes in canonical member order, then install
   all prepared missing markers as one logical direct-write batch, with no work
   occurrence allowed to observe a partially marked component;
7. immediately run one exact tentative-finalization pass in reverse
   topological order, finalizing each resulting changed component exactly once
   and rebuilding every changed containing spine before any later work runs.

If initialization formed, merged, split, or dissolved a component, step 7 uses
the resulting current partition; it does not finalize once per marker. A member
cut off or replaced before step 5 is not marked through the old occurrence, and
any newly required replacement receives its own verified initialization work.
A valid existing marker is never rewritten. Failure at any step discards the
entire tentative batch and all its caused work.

The marker batch and the exact identity/reference propagation caused solely by
it are processor Direct Writes and create no application Document Update. They
do update exact document/component state, occurrence bindings, containing
references, and derived subscription evidence. The `initialized` run/result
flag becomes true only as a derived assertion after the exact marker and
post-batch finalization agree; setting the flag is not initialization evidence.
The marker batch is a synchronous processor boundary, not another
`WorkOccurrence`: every actual marker pays its ordinary pointer, identity,
validation, and `processorMarkerWritten` charges, while each actually changed
component pays exactly one post-batch `tentativeComponentFinalization` (plus its
ordinary Language/member/containing-reference charges). It pays no synthetic
closure-work enqueue/dequeue pair.

The closed finalization boundary for every cyclic finalization caused by this
marker batch is
`{ kind: INITIALIZATION_BATCH, afterWorkOrdinal: N }`, where `N` is the exact
last accepted causal work ordinal recorded in step 4. It is not the first
initialization member, the work that first formed a cycle, or the last work
known when the batch began. Marker charges and the immediately following
finalization charges occur after work `N` completes and before any later work
occurrence. Several changed components finalized by the same marker batch carry
the same boundary and remain ordered by their finalization ordinals.

### 7.11 Checkpoint settlement

Every accepted raw external source occurrence retains its own exact checkpoint subject.

This phase applies only to an externally caused processing invocation.
`ADMIT_CLOSURE` never enters the settlement barrier, including for cleanup, and
returns empty `checkpointWrites` plus the exact empty-sequence identity.

Successful direct logical delivery records settlement eligibility, but does not
write or stage a mutated checkpoint document at the delivery boundary. The
processor settles the external cause exactly once, after all of its direct seeds
and complete queued causal closure are quiescent.

The barrier is entered only for a post-quiescence commit candidate: the
operation has a success-producing delivery or processor-managed action, every
non-marker-dependent soundness check has passed, and no noncommitting status or
diagnostic has already been selected. `no-match`, `stale`, `terminated`, an
admission-candidate rejection, and every failure before this gate bypass
settlement completely. They admit no `checkpointWritten` charge and perform no
checkpoint-caused component finalization.

The settlement barrier performs this canonical sequence:

1. collect every accepted-new raw source whose logical delivery completed, in
   the original canonical raw-source order;
2. revalidate the final active source occurrence, Channel lineage, frozen domain
   and subject, and active-scope cut-off; a source that no longer denotes the
   validated occurrence is not written through a replacement;
3. combine those writes with deterministic final checkpoint cleanup from §10.6,
   derive every exact before/after marker value, reject conflicting writes, and
   discard no-op entries. Accepted-source mutations retain original canonical
   raw-source order; remaining cleanup removals follow in lexical
   `(targetManagedScopeIdentity,rawChannelKey)` order. This is
   `checkpointWriteOrdinal` order;
4. preflight the complete batch, then visit each actual add/replace/removal in
   that order: charge exactly one `checkpointWritten` attributed to the target
   managed `documentId`, with rejected-charge owner `INVOCATION`, and
   immediately apply that tentative mutation. No work occurrence may observe
   the intermediate entries; together they are one logical atomic direct-marker
   batch;
5. immediately run one exact tentative-finalization pass, finalizing every
   changed component exactly once and rebuilding every changed containing spine
   before final soundness or commit evidence is derived;
6. derive `checkpointWrites`, `checkpointWritesIdentity`, final occurrence
   bindings, and any resulting subscription deltas from the exact batch and
   post-batch identities.

There is no Handler or delivery interleaving among steps 4–5. A checkpoint
Direct Write and identity/reference propagation caused solely by its batch
create no application Document Update and do not redispatch the original event.
Every write remains tentative until complete closure success. A later failure,
including the settlement finalization itself, discards the whole batch.
Settlement is likewise a synchronous processor boundary. Each actual source
entry add/replace and each cleanup removal pays the same single
`checkpointWritten`. That counter owns the processor-side checkpoint-address
traversal, entry add/replace/removal, and direct checkpoint-marker shape check.
The same mutation MUST NOT additionally charge `pointerSegmentTraversed`,
`patchBoundaryChecked`, `patchAddOrReplace`, `patchRemove`, or
`processorMarkerWritten`. New or changed Blue identity, any semantically needed
schema proof, and changed component/containing-spine work remain separate: each
actually changed component pays exactly one post-batch
`tentativeComponentFinalization` with its Language and containing-spine charges.
The batch itself is not a work occurrence and pays no synthetic closure-work
enqueue/dequeue pair.

A failure first discovered while preflighting the batch performs no marker
mutation. A gas rejection or deterministic failure arising inside the
tentative barrier publishes no checkpoint and no finalization evidence; the
ordinary admitted trace prefix and rejected-charge evidence remain observable,
while every tentative marker and component state rolls back. This rollback rule
does not turn attempted metered work into a committed checkpoint write.

The same source event directly accepted by two managed documents has two managed occurrence checkpoint domains when their Channel semantics differ. Logical grouping never merges across managed documents.

### 7.12 Phase E — final soundness

Before success, the processor MUST establish:

- every resulting document is a valid exact Blue node;
- every changed spine is type- and schema-sound;
- effective protected state is preserved;
- every effective runtime type is supported;
- every active occurrence is represented in exact source content and one valid binding;
- the graph is finite and within limits;
- every supported cycle belongs to a complete verified final component;
- final SCC partition and component generations are correct;
- every cyclic `MASTER` and member mapping verifies independently;
- no initialization, update continuation, event occurrence, delivery work,
  graph expansion, checkpoint settlement, or containing-reference obligation remains;
- subscription deltas are finite, canonical, and incrementally constructible;
- public events satisfy limits and are emitted only by declared public Roots;
- checkpoint and marker writes are valid;
- the canonical gas trace is within the exact bound.

For `ADMIT_CLOSURE`, final soundness additionally requires that the canonical
initialization plan is exhausted, every initialization-caused lifecycle/event/
update/termination obligation is quiescent, no unknown managed occurrence was
adopted, `checkpointWrites` is the exact empty sequence, and
`checkpointWritesIdentity` is its recomputed empty-sequence identity.

A charge owned by this invocation-wide final-soundness phase uses rejected-charge
owner `INVOCATION`. A charge inside an explicitly identified tentative
component-finalization boundary uses `FINALIZATION` with that boundary's exact
`finalizationOrdinal`, stable component identity, and component generation.

The obsolete unconditional rule “no declared embedded ancestry cycle exists” does not apply. Unsupported, incomplete, malformed, unverified, or over-limit cycles fail; verified bounded cyclic components pass.

### 7.13 Result selection

`success` means at least one accepted-new external logical delivery or one
processor-managed admission, initialization, or managed-revision action
completed and the full result is commit-ready.

For external processing:

- `stale` means at least one current Channel accepted but no accepted occurrence was new;
- `no-match` means no current occurrence accepted;
- `terminated` means every relevant direct public Root was already terminated under the applicable operation.

`no-match`, `stale`, and noncommitting statuses return exact input state and no
public events. A status selected before the settlement gate does not initialize,
admit `checkpointWritten`, mutate a checkpoint, or perform checkpoint-caused
finalization. If a noncommitting failure first arises inside the tentative
settlement/finalization boundary, all of that state is discarded as required by
§7.11; only its already admitted gas prefix and rejected-charge evidence, when
applicable, remain.

### 7.14 Several matching documents and scopes

When one exact cause affects several managed documents, the processor executes
one isolated step per logical delivery/document target. The host may execute
those steps sequentially; Contracts 1.0 requires no parallelism. Each step has
its own before identity and local-result evidence. The orchestrator supplies
the exact after identity only after ordinary or complete-set cyclic
finalization, while the closure retains one shared gas ledger and one
publication boundary.

For an acyclic chain:

```text
Root -> Emb1 -> Emb2 -> Emb3
```

direct seed order is:

```text
Emb3, Emb2, Emb1, Root
```

Each earlier seed and all caused work reaches quiescence before the next seed. A parent direct Handler therefore sees the exact updated child state.

Inside one cyclic component there is no deepest member. Use the canonical member/direct seed order in §4.7. Each seed's causal closure reaches quiescence before the next seed.

When an event directly targets a cyclic component and an acyclic containing Root, the component settles and exact containing-reference updates are processed before the containing Root's direct seed.

### 7.15 Exact locality

Successful processing may require:

- every member and internal edge of an affected cyclic component;
- every directly affected acyclic document;
- every changed containing ancestor spine;
- selected Channel and Handler headers;
- selected executable bodies and data they demand.

It MUST NOT require semantic expansion, contract discovery, or business-state reading of unrelated components or branches.

A graph containing 1,000 unrelated documents and one two-member cyclic component opens the two members and required containing spine only, subject to exact provider demand.

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

`replace` on an object member is an upsert: the final member may be absent before the operation. `remove` of a missing member is invalid.

The parent container of the final path segment MUST already exist and have the required object or list kind. Core patch semantics do not synthesize missing intermediate objects or lists. A runtime that wants to create a nested structure must add or replace an admitted complete subtree at an existing parent, or issue earlier patches that create each required parent explicitly. Arrays are never silently invented.

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

Before every patch, the processor validates the mutation boundaries of §5.8 against the executing scope's entry snapshot.

After every patch and nested cascade, it checks whether an active scope root was removed or replaced and applies the frozen-target and cut-off rules of §5.9 before the next buffered effect.

A patch retaining the same exact child identity is a no-op for occurrence
continuity. A whole-child replacement whose new exact BlueId still denotes the
same verified target `DocumentId` lineage preserves `occurrenceIdentity` and
activation generation and changes only `bindingIdentity`. Replacement by a
different managed `DocumentId` lineage fails before mutation. Removal allocates
the same-lineage inactive successor for later events, and a later same-lineage
add emits `ADD`. It does not join the current external event.

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

Ordinary isolated `PROCESS` MUST NOT structurally mutate one opaque cyclic-set member. It may preserve the member reference untouched or replace the entire opaque edge with another admitted exact value.

`PROCESS_CLOSURE` and `ADMIT_CLOSURE` MAY construct and mutate complete verified cyclic sets.

After every identity-affecting member transition and before the next distinct work occurrence observes the component, the processor MUST:

1. assemble complete latest tentative member content;
2. encode active internal references as explicit cyclic-set markers;
3. invoke Blue Language §15;
4. verify preliminary identities, deterministic member order, `MASTER`, suffix map, and proof;
5. install the exact temporary member identities;
6. rebuild required outside references.

Partial member publication is forbidden. Invocation-local handles are permitted only as an optimization and MUST resolve to the same exact temporary values.

A final graph split may produce several cyclic components and/or acyclic documents. The closure result reports each final component separately. A final graph merge produces one new complete cyclic set.

The following remain invalid:

- isolated mutation without complete set proof;
- a cycle through an unsupported opaque edge;
- missing or ambiguous member mapping;
- a cyclic set with indistinguishable preliminary members lacking identity-bearing content;
- a component exceeding exact limits;
- a `this#n` or ZERO_BLUEID placeholder escaping the explicit Language cyclic calculation API.

## 9. Initialization, Lifecycle, and Termination

### 9.1 Initialization gate

A managed document initializes when one of these occurs:

1. accepted-new external work requires its participation;
2. `ADMIT_CLOSURE` admits it;
3. a tentative graph change activates a new processable occurrence.

Preselection false, Channel rejection, stale-only processing, invalid occurrence evidence, or failed preflight do not initialize.

A newly activated occurrence does not directly receive the event that introduced it. Initialization is processor-managed work caused by the admission or activating transition.

A valid existing initialized marker is verified and reused. A state claiming processed business progress without valid initialization evidence fails closed rather than being silently repaired.

The snapshot/result `initialized` flag is true exactly when that valid direct
marker exists and verifies against the lifecycle evidence. A feeder assertion,
receipt Boolean, or cached initialization bit cannot replace the marker.

### 9.2 Initialization identity

Initialization work identity binds:

```text
invocation identity
admission or activating transition cause identity
target DocumentId
component generation
occurrence activation generation, when embedded
initial exact document BlueId
```

Retry against unchanged exact evidence reproduces the same initialization work identity. No provider timestamp is invented.

### 9.3 Initialization algorithm

For one selected current component:

1. verify existing markers and determine the complete missing-member batch;
2. freeze every missing member's exact pre-initialization BlueId and initialization
   contract snapshot before the first member lifecycle occurrence;
3. emit one `Document Processing Initiated` lifecycle occurrence and run the
   frozen lifecycle Channels and Handlers for each missing member in canonical
   order;
4. apply every result through the complete patch/update/event/finalization
   algorithm and process caused graph expansion or cyclic formation;
5. drain all initialization-caused work to quiescence and extend the batch under
   §7.10 when newly required missing members enter its current components;
   retain the last accepted causal `workTrace.ordinal` as
   `afterWorkOrdinal`;
6. after exact active-scope revalidation, prepare every missing initialized
   marker from the corresponding frozen pre-initialization BlueId;
7. charge in canonical member order and install all prepared markers in one
   direct-write batch;
8. immediately exact-finalize each changed resulting component once and rebuild
   every affected containing spine;
9. derive initialized flags, occurrence bindings, and subscription evidence
   from that finalized exact state, then return to the enclosing algorithm.

Each member initialization remains an exact work occurrence, but all members of
a cyclic component share one queue and gas ledger and no member work may observe
a partially installed marker batch. Initialization may revisit members through
exact caused work. No marker or member publishes before closure success.

Steps 7–8 occur immediately after the work named by the initialization batch's
required `afterWorkOrdinal`. Consequently every initialization and
`Document Processing Initiated` lifecycle occurrence caused by the batch,
including work for a member added by dynamic expansion, precedes every marker
write and post-marker finalization belonging to that batch.

### 9.4 Initialization snapshot rule

At batch entry, initialization freezes every then-missing member's exact
pre-initialization document and initialization contract. A member added by
dynamic expansion is frozen when it joins the batch and before its lifecycle
work. Lifecycle execution does not receive ambient containing-document state;
ordinary later reads caused by lifecycle work still use the latest exact
tentatively finalized state under §5.9. The initialized marker records the
frozen pre-initialization document, not a later post-lifecycle state.

When initialization activates a prospective managed occurrence already declared
in the immutable invocation input, its already-present target is initialized
under the same closure before commit when required. An occurrence or target
lineage unknown to that input crosses the noncommitting subscription-surface
validation boundary: status `SUBSCRIPTION_SURFACE_INVALID`, diagnostic category
`SubscriptionSurfaceInvalid`; admission does not expand the closed graph dynamically.

A document already initialized in the authoritative input or earlier in the same invocation is not initialized again.

### 9.5 Termination request

A ContractExecutionResult may request graceful termination with a deterministic application cause and optional reason. The cause explains why the successful business transition is ending; it is not a `graceful | fatal` execution mode. Runtime failure is represented only by a noncommitting failure status.

The first request for a scope in one invocation wins. Later requests are ignored. A termination request is applied after that result's patches and emitted events have been recorded.

The closure orchestrator records the accepted request as pending and owns its
transition through pending, terminating, and terminated state. It uses the
existing `LIFECYCLE` work kind and normal queue; it MUST NOT delegate admission
termination to an independent document-runtime termination queue.

### 9.6 Termination algorithm

For one active nonterminating scope:

1. freeze the first termination request;
2. mark the scope `terminating`;
3. create, enqueue, dequeue, and deliver `Document Processing Terminated` as
   canonical `LIFECYCLE` work;
4. apply lifecycle Handler results;
5. call `DRAIN_INTERNAL_EVENTS` to quiescence; its ordinary-delivery predicate excludes scopes marked `terminating`, so no new local Triggered or Embedded Handler begins in that scope, while event occurrences emitted before or during termination continue to nonterminating frozen ancestors;
6. re-check cut-off;
7. if the scope still exists as the same occurrence, Direct Write the Processing Terminated Marker;
8. immediately exact-finalize every changed component and containing spine
   caused by that Direct Write, without creating an application Document Update;
9. derive the terminated flag from the exact finalized marker state and stop
   later local work.

The marker and its synchronous finalization create no Document Update or
synthetic closure-work queue occurrence, but pay their ordinary marker,
identity, component-finalization, and containing-spine charges.

Let `N` be the ordinal of the last accepted causal work occurrence completed
before step 7. If the termination drain creates no later work, `N` is the work
whose Handler result made the accepted termination request. Every cyclic
finalization caused by the marker Direct Write carries the closed conformance
boundary `{ kind: TERMINATION_MARKER, afterWorkOrdinal: N }`. Marker and
finalization charges occur after work `N` completes and before any later work.
Several changed components from the same marker remain ordered by their
invocation-global finalization ordinals and share this boundary.

A scope may stop reacting while already-emitted descendant event occurrences continue to higher frozen ancestors.

### 9.7 Root termination

When Root begins termination:

- no later external delivery begins;
- the current result's already ordered patches and emissions complete according to §4.12;
- the termination lifecycle completes once;
- the Root termination marker write is attempted and metered under the normal rules; a committing termination requires it to complete;
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

A checkpoint domain is defined by the exact Channel runtime and MUST include every semantic dimension required to prevent unrelated occurrences from consuming one another.

The Contracts 1.0 default runtime domain value is this exact Blue object:

```text
CheckpointDomain {
    contractsVersion: "1.0"
    effectiveTypeBlueId
    sourceContributionNodeBlueIds[]
    deterministicDependencyNodeBlueIds[]?  # omitted exactly when empty
    runtimeDiscriminator?                  # omitted when absent or empty
}
```

`effectiveTypeBlueId` is the exact effective Channel runtime type identity.
`sourceContributionNodeBlueIds` preserves the registered effective-runtime
source-contribution order; it is never lexically resorted. When the Channel
consults an identity-bearing same-scope dependency surface,
`deterministicDependencyNodeBlueIds` is present and preserves the exact
deterministic consultation order published by that dependency snapshot. An
optional `runtimeDiscriminator` is a nonempty NFC runtime-selected Text value.
A non-NFC discriminator is rejected rather than normalized. The optional
fields are omitted under the rules shown rather than
encoded as JSON null, matching the exact Blue object shape.

The stored `checkpointDomainBlueId` is the ordinary Blue Language 1.0 BlueId of
that exact object. It is not a `sha256:` platform-constructor identity, a Java
class name, or an opaque label. The object itself or the equivalent pure
`{ blueId: checkpointDomainBlueId }` reference is the `domain` value in the
checkpoint entry, and the processor MUST independently verify the BlueId. A
registered non-default Channel runtime MAY define another exact domain value
only when its bound runtime specification defines the complete deterministic
constructor and binds every consulted semantic dependency.

For ordinary scope-aware processing, the complete checkpoint address includes:

```text
managed DocumentId
local scope path
scope activation generation
Channel occurrence identity
checkpointDomainBlueId
```

For Contracts 1.0 closure processing, the address is narrowed by §2.2.1:
`local scope path` is `/`, `scope activation generation` is `0`, and the
`Channel occurrence identity` and every checkpoint receipt MUST recompute for
that same Root scope. A checkpoint nested inside ordinary exact content has no
independent closure authority merely because it is reachable from a managed
Root.

Two documents both using scope `/` and Channel key `owner` do not share a checkpoint domain merely because the text matches.

A cyclic component does not replace member-local source checkpoint identity. It changes only the atomic publication boundary.

### 10.3 Virtual empty state

An absent checkpoint marker, absent raw key, or domain mismatch is treated as virtual empty state for newness evaluation.

The processor MUST NOT create an empty marker before establishing that a delivery is accepted, new, and successful.

### 10.4 Default exact-node subject

The default checkpoint subject is the exact input event BlueId retained as a pure reference.

A channel is stale when the current active entry has the same domain and the registered newness policy says the subject is not new. A concrete channel may use timeline predecessor, sequence, or another deterministic subject, but its policy and work are part of that exact runtime type.

Checkpointing uses the exact input event BlueId by default; it does not run Source Document BlueId calculation.

### 10.5 Atomic checkpoint write

Checkpoint comparison occurs in Phase B before initialization and Handler
execution for the direct source occurrence. Immediately before each accepted
raw source comparison, the processor charges exactly one `checkpointCompared`
even when the entry is absent, the domain differs, or the result is stale. That
counter owns the processor-side checkpoint-address lookup and newness-policy
dispatch; exact subject-policy semantic/runtime work remains separately metered.

Comparison freezes domain and subject but does not mutate checkpoint state.
After the external cause's final direct seed and complete queued causal closure
drain, the processor crosses the single §7.11 settlement barrier. It batches
every still-valid accepted-new raw-source write together with final cleanup,
installs the direct marker mutations, and immediately exact-finalizes every
changed component and containing spine. `ADMIT_CLOSURE` does not enter this
barrier at all: it performs neither accepted-source writes nor cleanup, and it
does not mutate checkpoint state as a side effect of admission.

Only a successful post-quiescence commit candidate crosses that barrier. A
pre-barrier noncommitting classification or failure performs no checkpoint
mutation, admits no `checkpointWritten`, and performs no checkpoint-caused
finalization. At the barrier, each actual add, replace, or cleanup removal pays
one `checkpointWritten` under the closed ownership rule in §7.11; a no-op pays
none. The direct marker batch pays no patch, pointer, or generic processor-marker
counter in addition to `checkpointWritten`.

Ordinary `PROCESS` publishes its settled marker with the Root. Closure processing
publishes every touched member checkpoint, exact post-batch component state, and
changed containing Root with the complete successful closure. A write receipt is
derived from the exact before/after marker entries after finalization; a staged
Boolean or successful-delivery flag is not checkpoint state.

Gas exhaustion, invalid final proof, containing Root failure, graph-finalization failure, or any other noncommitting result publishes no checkpoint.

A retry against unchanged state reproduces the same comparison, work identity, trace prefix, and result.

### 10.6 Checkpoint cleanup and domain retirement

Checkpoint state is processor-owned and MUST NOT grow indefinitely after channels disappear or change semantic lineage.

At the checkpoint settlement barrier, the processor deterministically compares
the direct checkpoint entries of each changed scope with the scope's final
effective External Channels:

- an entry whose raw channel key no longer exists is removed;
- an entry whose stored domain is not the current channel checkpoint domain is removed unless that exact runtime type defines an identity-bound migration accepted by this specification;
- an unchanged key with the unchanged domain is retained;
- cleanup joins the same logical direct-write batch as accepted-source writes,
  creates no Document Update, and pays exactly one `checkpointWritten` for each
  actual removal, plus only the separately applicable changed-identity,
  semantic-validation, component-finalization, and containing-spine work;
- cleanup is tentative and rolls back with the invocation.

A channel absent from one committed result and re-added by a later invocation
therefore starts with virtual empty checkpoint state unless an exact registered
migration rule says otherwise.

### 10.7 Multiple occurrences and retry

Several raw source occurrences may group into one logical delivery only under §3.3.3. Every raw occurrence retains its own checkpoint write.

The same managed document appearing at several containing paths does not multiply its direct external delivery. Containing update/event deliveries remain occurrence-specific.

A failed or rolled-back closure retry uses the same input graph/component generations, direct snapshot, work identity domain, event ordinals, and gas policy. A successful commit makes later redelivery stale under the exact source Channel checkpoints.

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

Before publication, the processor/platform MUST prove that the resulting managed document and closure subscription surfaces are canonical and indexable.

Validation requires:

- every active ordinary and cyclic managed document is valid;
- every active `Process Embedded` occurrence has exact content and one valid binding;
- every supported cyclic component has a complete verified final proof;
- no undeclared, malformed, incomplete, unsupported, ambiguous, or over-limit cycle exists;
- Channel occurrence keys include managed document and activation identity;
- removed/re-added occurrences have distinct activation generations;
- every subscription delta is bound to the exact resulting document BlueId, graph generation, and component generation;
- the union of managed document subscription surfaces equals the exact active closure surface without duplicate or missing concrete occurrences.

A valid bounded cyclic component is not rejected merely because it contains a cycle.

A deterministic failure rolls back the complete invocation.

## 12. Failure, Resource, Status, and Progress Semantics

### 12.1 Statuses

Core statuses are:

| Status | Commits semantic state? | Meaning |
|---|---:|---|
| `success` | Yes | At least one accepted-new external logical delivery or processor-managed admission, initialization, or managed-revision action completed and the full result is commit-ready. |
| `no-match` | No | No current External Channel accepted the external event. |
| `stale` | No | At least one Channel accepted, but no accepted occurrence was new. |
| `terminated` | No | The relevant direct public Root was already terminated. |
| `invalid-processing-document` | No | A document, closure, occurrence binding, graph, or proof was invalid before semantic execution. |
| `capability-failure` | No | A required runtime type, role, finalizer, or verifier was unsupported. |
| `runtime-fatal` | No | Deterministic processing failed after admission. |
| `gas-limit-exceeded` | No | The next canonical charge could not be admitted. |
| `portable-limit-exceeded` | No | A published structural or occurrence limit was exceeded. |
| `subscription-surface-invalid` | No | The input or result could not have a canonical subscription surface. |

A committing termination request is still `success`; a later invocation may return `terminated`.

`NeedsResources` is an alternate attempt outcome, not a completed status.

### 12.2 Diagnostic categories

Appendix B defines exact diagnostic categories. A diagnostic MUST include enough deterministic context for conformance, such as scope path, contract key, runtime type, patch path, or limit name, without embedding host stack traces or nonportable messages.

### 12.3 Admission and deterministic failure

Malformed serialized input, a missing exact Root identity, or an invalid event may be rejected before the gas meter begins and therefore reports zero gas.

After `processInvocation` is admitted, every deterministic semantic operation charges before work. A later capability, validation, patch, runtime, or limit failure returns the input Root, no events, and the gas admitted before the failure.

There is no separate zero-gas tentative preflight ledger and no portable `attemptedWork` result. This makes expensive rejected work visible to the same deterministic budget.

Invalid or unavailable complete cyclic-set evidence, ambiguous member mapping, unsupported component runtime, and component limit violations fail before mutation. A failure after tentative member work rolls back the complete component and containing closure.

For full-lifecycle `ADMIT_CLOSURE`, a failure after any number of tentative
initialization steps returns the literal authoritative input closure. It
publishes no initialized or terminated marker, graph/component change, public
event, checkpoint, committed result sequence, or platform commit companion.
An initialization-created managed occurrence outside the immutable input is
rejected at the noncommitting subscription-surface validation boundary with
status `SUBSCRIPTION_SURFACE_INVALID` and diagnostic category
`SubscriptionSurfaceInvalid`; it never produces partial publication or an
implicit graph-expansion request. Retrying identical exact input and environment
reproduces the same status, diagnostic, invocation identity, accepted trace
prefix, and rejected next charge/work evidence where applicable.

### 12.4 Resource acquisition boundary

Core `PROCESS` operates on verified exact-node evidence. Deterministic execution MUST NOT perform ambient network I/O.

`PROCESS_CLOSURE` and `ADMIT_CLOSURE` expose the required
`ClosureAttemptResult` resource boundary directly as specified in §§0.2 and
2.5. An implementation MAY additionally expose an equivalent attempt wrapper
for ordinary `PROCESS`:

```text
PROCESS_ATTEMPT(root, event, verifiedEvidence)
    -> Complete(ProcessResult)
     | NeedsResources(sortedExactBlueIds)
```

`NeedsResources` is a suspension, neither a `ProcessResult` nor a
`ClosureProcessResult`:

- it commits no Root, events, checkpoint, marker, progress, or portable gas;
- `requiredBlueIds` contains only exact already-named missing nodes, sorted and
  duplicate-free; it never contains an epoch range, history query, cursor,
  receipt range, or discovery request;
- the host fetches and verifies those exact direct nodes outside deterministic execution;
- a closure retry starts from the exact state-only input closure and exact
  cause; an ordinary `PROCESS_ATTEMPT` retry starts from its exact input Root
  and event;
- supplying additional exact resource evidence preserves the logical
  `invocationIdentity` only when the recomputed input closure, operation, cause,
  admission candidate, direct-delivery snapshot, execution policy, and
  environment identities are unchanged; otherwise the host constructs a new
  invocation;
- provider availability, provider loads, retry count, and cache state are
  harness/host evidence and are excluded from both `inputClosureIdentity` and
  `invocationIdentity`; changing only availability of a requested exact node
  therefore retries the identical normative invocation;
- every managed revision is instead a new one-step invocation bound by its
  exact source receipt, managed-revision cause identity, containing-reference
  WorkOccurrence, gas trace, result, and commit evidence;
- hidden cache state MUST NOT alter the demand set derived from the same closed
  normative input. A harness MUST expose an availability change explicitly
  rather than treating a warm cache as different semantics.

Provider transfer, direct-node verification, signatures, storage pages, and retry count are host work. Once an exact node is admitted, semantic inspection and new/changed identity work are charged normally and identically to inline content.

### 12.5 Definitive missing content and invalid evidence

A configured provider domain may report definitive `NotFound`; evidence may fail BlueId verification. These are host acquisition failures unless the exact runtime type deliberately treats one as application data.

No implementation may convert unavailable, incomplete, or invalid evidence into semantic field absence.

### 12.6 Gas exhaustion

Every charge is admitted before corresponding work. If the next charge exceeds the effective shared closure allowance or an applicable lower local allowance:

- the rejected charge is absent from the admitted trace and present only as rejected-charge evidence;
- its work does not begin;
- no further runtime, initialization, lifecycle, update, event, finalization, or validation work runs;
- every tentative document, component, occurrence binding, checkpoint, subscription delta, event, and generation change is discarded;
- the result is `gas-limit-exceeded`, exact input state, empty public events, and the already admitted canonical trace.

The result also identifies the rejected charge owner and the complete rejected
charge tuple `(namespace, counter, quantity, weight, subtotal, structured
applicable cap, remaining before charge)`. A queue-owned charge uses
`WORK`; admission and invocation-wide validation charges use
`INVOCATION`; a tentative component-finalization boundary uses `FINALIZATION`
with its required invocation-global `finalizationOrdinal`, `componentIdentity`,
and `componentGeneration`.
The rejected charge is evidence, not an admitted trace entry.

When both shared and local caps could fail, the cap with less remaining allowance fails. A tie uses shared-closure failure precedence.

Retry against identical exact evidence and limits returns the same rejected
charge owner, rejected charge, status, trace prefix, and total gas.

### 12.7 Portable limits

A limit known before the meter begins may be rejected with zero gas. A limit discovered after semantic execution begins returns `portable-limit-exceeded` with admitted gas.

The diagnostic identifies the exact limit and observed value. Closure-specific limits include:

```text
ManagedDocumentsPerClosureExceeded
ProcessEmbeddedEdgesPerClosureExceeded
CyclicComponentMemberLimitExceeded
CyclicComponentEdgeLimitExceeded
CyclicComponentCanonicalBytesExceeded
ClosureGraphChangeLimitExceeded
ClosureExpansionLimitExceeded
ClosureWorkOccurrenceLimitExceeded
ClosureTentativeFinalizationLimitExceeded
```

`NeedsResources` is never encoded as a completed status.

### 12.8 Failure precedence

When several errors are possible, the normative algorithm order decides. In particular:

1. invalid Root/event admission precedes runtime discovery;
2. direct terminated state precedes application contract recognition;
3. delivery revalidation precedes Channel acceptance;
4. checkpoint comparison precedes initialization;
5. cut-off checks precede remaining buffered effects and marker writes;
6. gas exhaustion occurs at the first unadmitted canonical charge.

For a non-null `ADMIT_CLOSURE` candidate, closed-shape/constructor/invocation
binding precedes the meter; once that structural gate passes,
`processInvocation`, `closureInvocation`, authoritative-closure admission, and
the selected §7.2 candidate verifier occur in exactly that order. Candidate
rejection precedes initialization, Handler work, Phase B checkpoint comparison,
and every settlement/finalization boundary.

Fixtures asserting one diagnostic MUST isolate the relevant failure or list acceptable categories explicitly.

### 12.9 Revision-bound progress

Every terminal result or successful commit is compare-and-swapped against the complete exact input dependency set:

```text
all input document BlueIds
input graph generation
input component generations
input cyclic MASTERs
occurrence-binding set identity
direct-delivery snapshot identity
```

A progress-only result cannot be recorded after any dependency changed. A
successful closure commits documents, public events, subscriptions,
checkpoints, graph/component state, and progress together. In this section,
`progress` is exactly the host-internal terminal idempotency record keyed only
by `causeIdentity` from §2.9. Its storage key contains no Timeline identity,
source-occurrence identity, delivery identity, or checkpoint address, and
recording it MUST NOT advance Timeline, source, or checkpoint progress.

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

The gas counter names, ownership, formulas, weights, default limit, and portable limits in the machine-readable Contracts 1.0 gas manifest are normative and frozen for this release.

### 13.0.1 Mandatory default execution limit

Contracts does not require a document-authored gas field.

Every invocation MUST nevertheless bind one exact finite default maximum from the Contracts release gas manifest. Absence of an authored or local limit means use that default; it never means unbounded execution.

A host or document-specific policy MAY lower the maximum only when the lowered policy has an exact identity included in invocation and receipt evidence. It MUST NOT silently raise the release maximum.

All work in one `PROCESS_CLOSURE` or `ADMIT_CLOSURE` invocation shares one meter. A member-local cap is a lower ceiling over that same ledger, not a new meter.

Full-lifecycle admission retains every admission-specific initial edge,
component, proof-verification, and finalization charge before its queued work.
Initialization, lifecycle, update, event, termination-marker, containing-spine,
and finalization work then debit that same ledger in normative execution order.
The bounded compatibility helper's released trace is not a normative gas
substitute for an invocation that requires this queued work.

A charge is debited to a member-local ceiling exactly when its canonical trace context names that member's `documentId`. Global charges without a `documentId` debit only the shared ceiling. Per-document and per-member admission, partition, work, semantic-identity, and finalization units therefore use separate deterministic entries when local attribution differs; they MUST NOT be aggregated across different documents. A charge associated with several members is emitted as the canonical per-member units named by its counter rather than debiting an invisible second ledger.

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

One logical unit of work is charged by exactly one owning namespace.

Ordinary `PROCESS` retains the existing trace and total for unchanged acyclic fixtures.

Closure processing adds processor-owned orchestration counters for:

```text
closureInvocation
managedDocumentOpened
managedOccurrenceBindingVerified
processEmbeddedEdgeExamined
componentMemberPartitioned
componentEdgePartitioned
closureWorkOccurrenceEnqueued
closureWorkOccurrenceDequeued
closureExpanded
componentPartitionChanged
tentativeComponentFinalization
cyclicMemberFinalized
containingReferenceUpdated
```

Semantic identity establishment, canonicalization, schema, pointer, and runtime work remains charged by existing semantic/runtime counters and is not double-charged by the orchestration counters.

For a cyclic transition, `tentativeComponentFinalization` is charged immediately, followed by the complete Language semantic identity trace for that finalization, one `cyclicMemberFinalized` charge per finalized member in canonical member order, and any acyclic containing-spine identity/rewrite work. No implementation may move those charges to a quiescence-time block. Ordinary acyclic identity work remains at its exact patch/update continuation point.

Cache reuse, physical storage, and batching never change the canonical ledger.

### 13.3 Canonical trace record

Every admitted trace record has:

```text
sequence
namespace
counter
quantity
weight
subtotal
documentId?
scopePath?
activationGeneration?
componentGeneration?
contractKey?
logicalPath?
workOccurrenceId?
reason?
```

Optional fields are present only when semantically applicable. Text fields use their canonical normalized representation.

`reason` is observable diagnostic text. It MAY explain the implementation's
charge site, but it is not semantic evidence and is excluded from
`gasTraceIdentity`. Changing only `reason` therefore does not change the trace
identity. All other fields shown above are identity-bearing when present:
`sequence`, the exact counter calculation, and semantically applicable
attribution such as `documentId`, component context, `contractKey`,
`logicalPath`, and `workOccurrenceId`. In particular, `logicalPath` is the
exact semantic attribution of the charge; implementations MUST NOT use it for
a source-file location or other diagnostic-only path.

In a Contracts 1.0 closure trace, `scopePath` and `activationGeneration` are a
pair. When present they are exactly `/` and `0`, including for work caused by
an embedded occurrence whose source occurrence generation is positive. The
source generation remains occurrence evidence and is not gas execution
context.

The trace sequence is the exact algorithm order. A rejected next charge is
absent. A trace identity is the SHA-256 domain-separated canonical JSON
identity of the complete admitted record sequence after removing only the
`reason` field from every entry. The observable trace retains that field.

### 13.4 Shared live-bounded meter

The meter is live and shared across:

- direct delivery classification after the meter begins;
- every managed document and cyclic member;
- initialization;
- Handler and executable runtime work;
- patches and immediate update continuations;
- event occurrences;
- graph expansion and partitioning;
- every tentative ordinary/cyclic finalization;
- final validation.

A child runtime may debit the shared meter directly or return one exact child ledger initialized with the exact remaining allowance. It MUST NOT receive a fresh full limit.

Physical pause/resume or worker continuation preserves the exact ledger and rejected-next-work semantics. Wall-clock timeout is operational only and cannot determine semantic success or failure.

### 13.5 Processor counters and weights

The machine-readable manifest is authoritative. Core processor counters include the existing ordinary counters and these closure counters:

| Counter | Weight | Quantity |
|---|---:|---|
| `closureInvocation` | 100 | once per `PROCESS_CLOSURE` or `ADMIT_CLOSURE` |
| `managedDocumentOpened` | 10 | each managed document first opened semantically |
| `managedOccurrenceBindingVerified` | 5 | each exact binding verification |
| `processEmbeddedEdgeExamined` | 2 | each concrete active edge examined by closure planning/replanning |
| `componentMemberPartitioned` | 2 | each member visited by deterministic SCC partitioning |
| `componentEdgePartitioned` | 1 | each edge visited by deterministic SCC partitioning |
| `closureWorkOccurrenceEnqueued` | 5 | each unique exact work occurrence enqueued |
| `closureWorkOccurrenceDequeued` | 5 | each exact work occurrence dequeued |
| `closureExpanded` | 20 | each deterministic expansion step that adds a managed document to the closure |
| `componentPartitionChanged` | 20 | each staged formation, merge, split, or dissolution result |
| `tentativeComponentFinalization` | 20 | each affected component finalization boundary |
| `cyclicMemberFinalized` | 10 | each member included in one cyclic finalization |
| `containingReferenceUpdated` | 10 | each exact containing occurrence reference rewritten |

Existing ordinary counters retain their released weights:

```text
processInvocation
deliverySnapshotEntry
scopeOpened
contractHeaderRecognized
channelCandidateTested
channelAccepted
handlerCandidateTested
handlerCall
scopeInitialization
embeddedPathEntryRead
embeddedPathSegmentValidated
pointerSegmentTraversed
patchBoundaryChecked
patchAddOrReplace
patchRemove
documentUpdateDelivered
internalEventEnqueued
internalEventDequeued
triggeredEventDelivered
embeddedEventDelivered
rootEventRecorded
lifecycleDelivered
checkpointCompared
checkpointWritten
processorMarkerWritten
terminationRequested
```

The complete exact table is in the gas manifest.

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

Opening the direct manifest of an exact node for the first semantic use in one invocation charges `nodeManifestOpened` once for that exact BlueId. A second semantic operation may reuse the retained immutable manifest without another manifest-open charge.

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

Exact Blue node identity equality may compare known BlueIds without scanning transitive content. Runtime value equality that is not exact Blue identity follows the runtime specification.

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

This is actual changed/new identity work. Carrying an existing exact node never pays it again. The run-local `establishedNewNodeIds` ledger is keyed by exact BlueId after successful establishment. Reusing that exact node as a patch value or child of a rebuilt parent does not repeat its identity charge; the rebuilt parent and each rebuilt ancestor still pay their own direct work.

For a changed acyclic document, the canonical incremental plan is bottom-up: establish the changed/new leaf value if not already established, rebuild its direct container, then rebuild each changed ancestor through the managed document Root or required containing Root. Each unchanged exact sibling is represented only by its already known BlueId and is not recursively opened or charged.

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

Emitting an existing exact event has no recursive size charge. A newly
constructed event pays runtime construction and semantic identity admission
once before `internalEventEnqueued`. Every emitted event occurrence pays exactly
one `internalEventEnqueued` and, when the FIFO owner removes it, exactly one
`internalEventDequeued`, including an occurrence with zero actual recipients.
Two emissions of the same exact event value therefore pay two event-FIFO
enqueue/dequeue paths, but the second emission reuses the established exact
event BlueId and does not repeat semantic identity establishment in the same
invocation.

Event delivery is metered separately from event-FIFO ownership. Every actual
Triggered or Embedded Event `WorkOccurrence` pays one
`closureWorkOccurrenceEnqueued`, one `closureWorkOccurrenceDequeued`, and its
ordinary `triggeredEventDelivered` or `embeddedEventDelivered` work. A
nonmatching, cut-off, or terminated target creates no delivery work occurrence;
it never causes another `internalEventDequeued` charge.

A Root emission additionally pays `rootEventRecorded`.

For every accepted raw source in frozen Phase B order, checkpoint comparison
pays exactly one `checkpointCompared` immediately before lookup/newness work,
followed by the exact subject-policy semantic/runtime work. This is before any
initialization or Handler work and is not coalesced by logical-delivery grouping.

At the later successful post-quiescence settlement barrier, every actual source
entry add/replace and every actual cleanup removal pays exactly one
`checkpointWritten` in §7.11 order. `checkpointWritten` owns all processor-side
checkpoint path traversal and marker-shape/add/replace/removal work; none of
`pointerSegmentTraversed`, the patch counters, or `processorMarkerWritten` is
charged for that same direct checkpoint mutation. Direct changed-identity and
semantic validation work remain separately metered. The batched marker mutation
then pays exactly one tentative finalization path for each actually changed
component plus every changed containing spine. No-op entries and a noncommitting
classification selected before the barrier pay none of these settlement
charges. The settlement frame is not queued work and creates no Document Update.

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

### 13.19 Worked processor subtotal (informative)

Assume one already admitted external event has:

```text
one retained raw delivery
two participating scopes
four effective contract headers
one Channel candidate that accepts
one Handler candidate that executes
one two-segment patch path /x/a
no initialization in this example
```

The processor-counter subtotal before semantic reads, runtime work, identity rebuilding, validation, updates, checkpoints, or sorting is:

```text
processInvocation             1 * 50 = 50
deliverySnapshotEntry        1 *  5 =  5
scopeOpened                  2 * 10 = 20
contractHeaderRecognized     4 *  2 =  8
channelCandidateTested       1 *  5 =  5
channelAccepted              1 *  5 =  5
handlerCandidateTested       1 *  5 =  5
handlerCall                  1 * 50 = 50
pointerSegmentTraversed      2 *  1 =  2
patchBoundaryChecked         1 *  2 =  2
patchAddOrReplace            1 * 20 = 20
                                        ----
processor subtotal                       172
```

`172` is deliberately only a subtotal. The complete gas also includes the exact semantic and runtime counters actually caused by the concrete nodes and handler. Conformance fixtures, not this illustrative example, define complete exact traces.

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

The trust boundary is:

| Input or claim | Core treatment |
|---|---|
| Root, event, type, body, and demanded node content | Must have verified exact BlueId evidence. |
| Delivery path and channel contribution identity | Revalidated against the admitted Root and retained snapshot. |
| Completeness of the preselected occurrence set | Feeder/platform obligation; an omission is nonconformance. |
| Cross-source external order | Bound by the exact policy identity and completeness evidence under §3.6. |
| Runtime semantics | Selected by exact runtime-type BlueId and registry binding. |
| External eligibility or delegated-authority evaluation | Feeder/provider responsibility unless a runtime type adds deterministic checks. |
| Cache, provider transport, database order, host scheduling | Never trusted as semantic input. |

The processor fails closed on invalid or incomplete evidence. It does not reinterpret unavailable content as absence and does not silently broaden its trust in a warm cache or provider.

### 14.4 Portable limits

The gas manifest defines exact limits and counter scopes. Closure-specific frozen values are:

```text
managedDocumentsPerClosure:              4096
processEmbeddedEdgesPerClosure:         16384
cyclicMembersPerComponent:                128
cyclicEdgesPerComponent:                 1024
cyclicCanonicalBytesPerComponent:    16777216
closureGraphChangesPerInvocation:        4096
closureExpansionsPerInvocation:          4096
closureWorkOccurrencesPerInvocation:     8192
closureTentativeFinalizationsPerInvocation: 8192
```

Existing ordinary limits remain unchanged.

For each closure-specific limit, the conformance package contains: (a) an exact boundary microfixture proving `observed == configured` is admitted by that named guard and `observed == configured + 1` is rejected with its named diagnostic before the disallowed step; and (b) at least one executable closure fixture proving the counter's owner and increment point. Passing an at-bound guard does not imply that the enclosing invocation can complete before another independently applicable guard or the shared gas limit. A boundary microfixture reports its limit decision separately from any later invocation status and MUST NOT pretend that a numeric `limitProbe` is a successfully executed closure.

Generated structural cases are normative input constructions, not trusted numeric assertions: the harness expands the declared generator and independently measures the resulting semantic structure. Counts are based on unique semantic occurrences after deterministic deduplication and before the disallowed work begins.

For `cyclicCanonicalBytesPerComponent`, the measured bytes are exactly the ordered collapsed limit form defined in §7.7 and the gas manifest, independently of whether the collapsed or full canonical placeholder set is retained as complete proof. The byte limit is checked after canonical `this#n` remapping and before hashing or any temporary state becomes visible.

A remove followed by add is two graph changes. One SCC merge or split result is one `componentPartitionChanged` charge but its constituent edge changes retain individual graph-change counts. Rejected work does not increment a counter whose charge was not admitted.

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
- use separate top-level Roots only when the states are not one atomic `Process Embedded` reality; physical separation or BlueId reuse alone does not imply autonomy;
- use stable object keys for dynamic embedded collections;
- avoid list positions as process-occurrence identities;
- instantiate reusable process modules with explicit local Channel bindings rather than implicit parent lookup.

A useful lower-bound estimate before type, schema, text, sorting, runtime, mutation, and identity work is:

```text
base scan gas ~=
    50                                      # processInvocation
  + 5  * preselected raw occurrences
  + 10 * distinct participating scopes
  + 2  * recognized effective contract headers
  + 5  * Channel and Handler candidates tested
```

The exact trace is defined by §13 and the bound manifest. This estimate is authoring guidance only, but it makes clear that the structural maxima are not practical per-event targets.

Mutual `Process Embedded` edges should be authored only when documents are genuinely one atomic mutually dependent reality. A large strongly connected region necessarily has a larger identity and commit unit. Authors should avoid accidental cycles and unbounded feedback loops.

### 14.7 Locality conformance

A conforming implementation MUST demonstrate:

- one selected acyclic leaf opens only the selected path, required contracts, and changed ancestor spine;
- one selected cyclic member opens the complete affected component proof and members, but not unrelated components;
- dynamic expansion opens only newly required documents and containers;
- a two-member cycle among 1,000 unrelated managed documents does not read the unrelated 998 documents;
- warm/cold, inline/reference, and batched/unbatched variants return identical exact result and gas.

The exact provider-demand set is a conformance output.

## 15. Conformance Vectors

The machine-readable vector and fixture manifests are authoritative for the exact inventory. The following vector families are normative.

### 15.1 Existing ordinary core families

All ordinary Contracts 1.0 vector families remain required, including:

```text
C-REP      representation and locality
C-FEED     feeder snapshot and external order
C-DISC     runtime discovery and binding
C-EMB      acyclic Process Embedded scopes and collections
C-UPD      patches and Document Updates
C-EVT      internal and public events
C-CHK      checkpoints and idempotency
C-INIT     initialization
C-LIFE     lifecycle and termination
C-PROT     protected state
C-SND      soundness and diagnostics
C-FAIL     failure and retry
C-GAS      gas and limits
C-E2E      end-to-end ordinary results
```

### 15.2 Closure and cyclic families

- **C-CLO-01.** Static two-member cyclic closure admission verifies exact current proof and performs deterministic initialization without an external event.
- **C-CLO-02.** Dynamic `B -> A` to `A <-> B` formation uses an ordinary patch, preserves already completed direct work, and produces exact temporary and final cyclic identities.
- **C-CLO-03.** A finite A/X/B/Y/A reaction reaches quiescence, preserves exact event occurrence order, and commits one closure.
- **C-CLO-04.** A same-event cyclic feedback loop stops at the exact shared gas charge and rolls back the closure.
- **C-CLO-05.** One external event directly targets both cyclic members in canonical managed-scope order independent of admission order.
- **C-CLO-06.** Two identical event values remain two occurrences and two deliveries.
- **C-CLO-07.** A self-cycle is verified, processed, and finalized under the same complete-set rules.
- **C-CLO-08.** Initialization may form a cycle and continues without replaying initialization work. Its canonical work order is initialization `0`, lifecycle `1`, initialization `2`, lifecycle `3`; only after lifecycle work `3` completes may the initialized-marker batch and cyclic finalization run. The `INITIALIZATION_BATCH` receipt therefore carries `afterWorkOrdinal: 3`, and no marker or marker-caused finalization charge may appear after work `1` but before works `2`–`3`.
- **C-CLO-09.** Two existing cyclic components merge through an ordinary patch and return one exact final component.
- **C-CLO-10.** One cycle splits into acyclic singletons and returns complete ordinary identities and retired proof state.
- **C-CLO-11.** One larger cycle splits into two smaller cyclic components with exact independent MASTERs.
- **C-CLO-12.** Work created before edge removal retains the old frozen target exactly once; later work uses the new graph.
- **C-CLO-13.** Work created before edge addition excludes the new edge; later caused work may use the new edge after activation.
- **C-CLO-14.** An invocation-bound `BAD_CYCLIC_PROOF` admission candidate is independently recomputed and its missing, stale, malformed, or mismatched cyclic proof fails before initialization or Handler mutation.
- **C-CLO-15.** An invocation-bound `AMBIGUOUS_PRELIMINARY_MEMBERS` admission candidate runs the real preliminary calculation; indistinguishable members fail unless identity-bearing document content disambiguates them.
- **C-CLO-16.** For every closure-specific portable limit, the named guard admits the exact at-bound observation; `limitDecision` is `ACCEPT` even when a separately identified later phase would gas-fail.
- **C-CLO-17.** For every closure-specific portable limit, the named guard rejects the first-above observation before the disallowed step, with its exact diagnostic and with no state or charge for that step.
- **C-CLO-18.** A two-member component among 1,000 unrelated documents opens only the component and containing spine.
- **C-CLO-19.** Internal member events remain nonpublic under an outer Root unless the outer public Root explicitly emits.
- **C-CLO-20.** A late containing Root failure rolls back every component member, checkpoint, graph change, and public event.
- **C-CLO-21.** Admission with zero direct external deliveries succeeds and has no fabricated Timeline Entry.
- **C-CLO-22.** An external attachment offering exact A5 for a known epoch-10
  lineage requests only the missing A5 node; an availability-only retry has
  identical closure/invocation identity and commits the explicit row inactive
  with cursor 5 without downgrading or forking A.
- **C-CLO-23.** Five separately committed one-step managed-revision invocations
  advance A5→A10 behind Coordination's no-overtake barrier; the final invocation
  reconciles to latest authoritative same-lineage A, clears the cursor,
  activates the edge, and finalizes the resulting partition.
- **C-CLO-24.** Cyclic `MASTER#index` churn does not retire/re-add unchanged occurrence identities or activation generations.
- **C-CLO-25.** An exact host-lowered gas policy is receipt-bound; absent local policy uses the frozen default.
- **C-CLO-26.** A member-local cap exceeded inside an embedded member kills and rolls back the complete required closure.
- **C-CLO-27.** Several SCCs in one connected condensation closure share one gas ledger and one atomic result.
- **C-CLO-28.** Final result represents mixed cyclic and acyclic components plus every changed containing Root.
- **C-CLO-29.** An invocation-bound `INVALID_OCCURRENCE_BINDING` admission candidate is independently checked; every admitted active edge must exist at the exact source path and no candidate or hidden relationship list can create an edge.
- **C-CLO-30.** Real Language-generated cyclic proof constants independently verify current, temporary, and final MASTER/member mappings.
- **C-CLO-31.** A shared ceiling that rejects `processInvocation` before any WorkOccurrence exists returns exact `INVOCATION`-owned rejected-charge evidence and the literal input closure.
- **C-CLO-32.** A ceiling that admits initialization work but rejects the next whole-component tentative finalization returns exact `FINALIZATION` ownership including `finalizationOrdinal`, and rolls back the complete closure.
- **C-CLO-33.** One accepted external source begins with a present entry whose independently verified exact domain value/BlueId differs from the frozen current source domain, so §10.3 treats it as virtual empty and the delivery is new. After that external cause's complete queued causal closure drains successfully, the single §7.11 settlement batch first replaces the accepted source entry with the current domain and subject, then removes a second orphan raw-key entry in cleanup order. The two `checkpointWrites` retain source-add/replace-before-cleanup-removal `checkpointWriteOrdinal` order. The replacement receipt carries complete, independently recomputable non-null before and after domain values beside their matching BlueIds; the removal receipt carries a complete recomputable before domain value/BlueId and the required null after-domain value/BlueId/subject fields. Both mutations, their changed identities, and their one immediate changed-component/containing-spine finalization boundary commit atomically, or none does.
- **C-CLO-34.** Every accepted closure work occurrence executes as one isolated managed-document step. The document-step trace matches the work trace one-for-one, uses the target document as the execution Root, exposes no ambient containing documents, and uses the same execution mode for acyclic and cyclic members.
- **C-CLO-35.** Cross-invocation remove/re-add commits an inactive generation-plus-one successor, supplies that exact successor in the next invocation, activates it without another increment, never reuses the old occurrence/checkpoint lineage, and rejects same-invocation remove/re-add.

#### 15.2.1 Full-lifecycle admission evidence

An implementation claiming `ADMIT_CLOSURE` conformance MUST independently
demonstrate all of the following through the explicit
`admitClosureWithLifecycleQueue` entry point:

1. Root initialization applies its patch.
2. A Root initialization emission is one event occurrence and its local Handler reacts.
3. Two equal initialization emissions remain two distinct occurrences and deliveries.
4. A non-public member emission can cause its containing public Root to react without itself becoming public.
5. A public Root initialization emission is public exactly once.
6. An initialization patch produces and routes its Document Update.
7. A valid initialization termination completes deterministically.
8. Two documents initialize in canonical order independent of input order.
9. A finite cyclic A/B initialization-event route reaches quiescence.
10. An infinite cyclic route exhausts the shared gas ledger and rolls back completely.
11. Failure after an earlier tentative initialization rolls back the whole closure.
12. Inline and equivalent pure-reference inputs return the same result.
13. Cold and warm exact-node availability return the same result, gas, and trace.
14. Retry of identical evidence returns identical deterministic evidence.
15. Admission creates no Timeline checkpoint.
16. An initialization-created unknown managed occurrence returns noncommitting `SUBSCRIPTION_SURFACE_INVALID` with diagnostic category `SubscriptionSurfaceInvalid` and no partial publication.
17. The bounded behavior remains available only through the explicit legacy
    `admitClosure` compatibility API and is not reported as the normative result.

The portable `FL-ADM-01` through `FL-ADM-10` fixture family supplies thirteen
executable cases for the full-lifecycle claims above.  Reversed-order and
inline/reference cases are independent executions, and retry cases reuse the
exact invocation input and compare the complete deterministic rejection
evidence.  Claims 13 and 17 additionally have mandatory Java parity and
architecture gates because node-cache temperature and public API source use are
not portable fixture inputs.  Fixture oracles are generated from identity-free
authored sources through the normative entry point; they are never copied from
unit-test expected values or produced by a second semantic implementation.

The canonical executable inventories contain 153 Language fixtures and 247
Contracts fixtures, including 80 closure fixtures.  Existing
`PROCESS_CLOSURE` business results remain frozen; specification, invocation,
work, event, and trace identities may be mechanically rebound only when the
identity-delta audit classifies them and reports zero unexpected changes.

### 15.3 Gas and execution fixtures

Every named gas counter has one exact microfixture. Composite closure fixtures assert:

- exact accepted trace prefix;
- exact rejected next charge and its structured owner, returned as evidence and
  absent only from the admitted trace;
- shared versus local cap precedence;
- retry identity;
- warm/cold and inline/reference trace equality;
- unchanged ordinary PROCESS traces.

### 15.4 Machine-readable fixture package

The package contains:

```text
blue-contracts-fixture/1.0
    existing ordinary fixtures

blue-contracts-closure-fixture/1.0
    closure/admission/cyclic fixtures
```

Unknown fields fail closed. Exact Blue values are validated by the Blue Language
fixture validator. Every closure-fixture input carries the required nullable
`admissionCandidate` and `admissionCandidateIdentity` pair; non-null candidates
use exactly one §2.3 branch, recompute under §2.6, and are bound into the
invocation identity. Closure fixtures additionally validate active edge paths,
managed occurrence bindings, direct Channel keys, status/diagnostic vocabulary,
real cyclic proof oracles, and expected final component partition. Fixture-only
flags and summary receipts are checked as derived assertions against the full
structured result and never replace exact marker, write, proof, or closure
evidence.

Every `tentativeFinalizations[]` receipt carries one closed `boundary` branch:

```text
{ kind: WORK, afterWorkOrdinal }
{ kind: INITIALIZATION_BATCH, afterWorkOrdinal }
{ kind: TERMINATION_MARKER, afterWorkOrdinal }
{ kind: CHECKPOINT_SETTLEMENT }
```

The three branches shown with `afterWorkOrdinal` require exactly that non-negative
safe integer and the branch without it MUST NOT contain it. For `WORK`, the
ordinal identifies the accepted `workTrace` occurrence immediately after which
the ordinary work-caused finalization runs. For `INITIALIZATION_BATCH`, it
identifies the last accepted work occurrence in the complete initialization and
lifecycle causal drain; the whole marker batch and all of its immediately
caused finalizations follow that occurrence and precede the next work ordinal.
It cannot name merely the work that opened the batch or first formed the
component. `CHECKPOINT_SETTLEMENT` has no `afterWorkOrdinal`: its position is
proved instead by external-cause quiescence, the ordered checkpoint-write batch,
and the immediately following settlement finalization under §7.11. A branch
contains no field belonging only to another branch.

For `TERMINATION_MARKER`, the ordinal is the `N` defined by §9.6: the last
accepted causal work completed before the terminated-marker Direct Write. The
marker and all immediately caused finalizations follow that occurrence. This
branch remains distinct even when the termination request arose during an
initialization batch; it MUST NOT be relabeled `INITIALIZATION_BATCH`, `WORK`, or
`CHECKPOINT_SETTLEMENT`.

Package validation, semantic fixture execution, and implementation conformance are distinct results:

```text
PACKAGE_VALID
SEMANTIC_REFERENCE_VALID
IMPLEMENTATION_CONFORMANT
```

`PACKAGE_VALID` proves schemas, exact constructor recomputation, manifests, package hashes, and static closure laws. `SEMANTIC_REFERENCE_VALID` proves the independent Language oracle, generated limit measurements, flagship state transitions, and canonical trace arithmetic; it is still not execution by either Java implementation. `IMPLEMENTATION_CONFORMANT` may be claimed only after the exact released artifacts execute the complete fixture corpus. A schema/hash or reference-model pass alone is not implementation conformance.

## 16. Worked Examples

### 16.1 Lazy selected workflow

The ordinary lazy locality example remains unchanged: open the selected Root-to-scope path, relevant Channel and Handler headers, selected executable body, and data actually read. Unrelated exact branches remain references.

### 16.2 Acyclic deepest-first delivery

For:

```text
Root -> A1 -> A11
```

when one event directly targets all three:

```text
A11 direct delivery and caused work to quiescence
A1 receives exact A11 update
A1 direct delivery and caused work to quiescence
Root receives exact A1 update
Root direct delivery
```

One late failure rolls the full invocation back.

### 16.3 Dynamic A/B cycle formation and finite reaction

Initial state:

```text
A has no /b occurrence.
B contains /a -> A.
```

One external operation directly targets A. Its Handler:

1. adds exact B at `/b` using an ordinary patch;
2. emits event X.

After the patch, the staged graph changes from `B -> A` to `A <-> B`. The processor expands/reclassifies the closure without replaying A's external delivery. It tentatively finalizes the complete `{A,B}` cyclic set to exact temporary `M1#index` identities before any later work observes it.

Event X is then handled:

```text
A local Triggered Handler observes X and changes A
    -> complete tentative component finalization M2

B observes X through its /a occurrence, changes B and emits Y
    -> complete tentative component finalization M3

A observes Y through its /b occurrence and changes final result
    -> complete tentative component finalization M4
```

The event queue becomes empty and the external cause's entire queued causal
closure drains. The processor then batches its eligible checkpoint Direct
Writes and immediately exact-finalizes each changed component and containing
spine. Final validation verifies that post-settlement state, all occurrence
bindings, checkpoints, subscriptions, and public events. One atomic commit
publishes A, B, the final component proof, and every containing reference.

No temporary master is authoritative before commit, but each is an exact Blue identity visible to subsequent work in the same invocation.

### 16.4 Same-event nonterminating cycle

Start with a verified `A <-> B` component.

```text
A direct Handler emits PING
B receives PING and emits PING
A receives PING and emits PING
...
```

Every emission is a distinct event occurrence even though the event BlueId is equal. All work shares the exact finite default gas meter or a lower exact override.

When the next canonical charge cannot be admitted:

```text
status = gas-limit-exceeded
rejected charge absent from admitted trace; exact rejected-charge evidence returned
A and B remain at the input MASTER
no checkpoint commits
no subscription delta commits
no public event commits
retry produces the same trace prefix, rejected charge, and structured owner
```

### 16.5 One event directly targets both members

For one `A <-> B` component, an external event may be accepted by Channels in both A and B. The feeder supplies both direct deliveries. The processor sorts them by the exact member/scope/Channel tuple from §4.7.

The first direct seed and all caused reactions reach quiescence before the second begins. Reversing document admission order or map insertion order does not change the result.

### 16.6 Cycle formation during initialization

Suppose B initially contains A and A's initialization Handler adds B.
`ADMIT_CLOSURE` begins with an acyclic graph. Before component lifecycle work,
the processor freezes each then-missing member's exact pre-initialization
BlueId. The ordinary initialization patch closes the cycle. The processor
preserves completed initialization work, expands and reclassifies the closure,
and continues initialization under one shared meter. Once the whole batch and
its caused work are quiescent, it installs all missing initialized markers
together and performs one exact finalization pass over the resulting component
and containing spine. No member, marker, or derived initialized flag publishes
early.

### 16.7 Component merge and split

Two existing cycles:

```text
{A,B}    {C,D}
```

may merge when an ordinary patch adds an edge that makes all four mutually reachable. The final result contains one cyclic component and one exact `MASTER`.

Removing an edge may split one component into:

```text
acyclic A + acyclic B
```

or:

```text
cyclic {A,B} + cyclic {C,D}
```

The result reports every final component and exact identity. Old proof state is retired atomically. Unrelated components are not recomputed.

### 16.8 Frozen edge behavior

An event/update occurrence freezes its target occurrence set when created.

```text
created before edge removal:
    old target remains eligible exactly once

created after edge removal:
    removed target absent

created before edge addition:
    new target absent

created after edge addition and activation:
    new target eligible
```

Cross-invocation remove/re-add creates a new activation generation;
same-invocation remove-then-re-add is unsupported in Contracts 1.0.

### 16.9 Known A at epoch 10; B attaches A at epoch 5

`DocumentId` and BlueId are distinct. A current managed lineage may be at epoch 10 while B contains an exact historical state from epoch 5.

Contracts does not fetch history. The closure binding MUST NOT:

```text
downgrade authoritative A from epoch 10 to epoch 5
create a second A lineage silently
declare B current with a stale A reference
```

The initial C22 external attachment's normative input already contains the
exact inactive `B:/a` row for A5; the fixture's separate runtime harness
supplies the Handler patch that writes A5. If the provider lacks the exact A5
node, the result is precisely `NeedsResources([A5])`; no range is requested. A
retry in which only harness provider availability changes has the same
`inputClosureIdentity` and `invocationIdentity`. Its successful commit writes
B -> A5 but keeps the row inactive with
`pendingHistoricalEpoch = 5`.

Coordination then establishes its no-overtake barrier and dispatches five
separate invocations:

```text
ManagedRevisionCause(A5 -> A6)
ManagedRevisionCause(A6 -> A7)
ManagedRevisionCause(A7 -> A8)
ManagedRevisionCause(A8 -> A9)
ManagedRevisionCause(A9 -> A10)
```

Each cause contains one authenticated source revision receipt and each
`PROCESS_CLOSURE` invocation performs one `CONTAINING_REFERENCE_UPDATE`, has one
independent gas/failure/commit boundary, advances the same row's pending cursor
by one, and fully drains its immediate identity, finalization, and Document
Update continuation before it can commit. No invocation owns a transition list
or hidden loop.

During these containing rewrites, authoritative managed A may itself acquire a
new exact BlueId because A contains B. Therefore the final A9 -> historical-A10
step does not compare only with the A10 receipt BlueId. After its first
continuation is quiescent, the processor explicitly resolves the latest
authoritative same-lineage A BlueId, reconciles B's `/a` value and binding to
that identity, clears `pendingHistoricalEpoch`, activates the edge, increments
the graph generation, repartitions, and finalizes the resulting A/B component
and containing spine in the exact order of §7.5. This reconciliation is neither
a Handler patch nor a hidden history traversal. Coordination accepts no later
live/public work that can overtake the barrier before this terminal commit.

### 16.10 Public events under an outer Root

For:

```text
Public Root R -> A <-> B
```

A and B may emit internal events and react mutually. Those events are absent from `publicEvents` unless R explicitly emits an event while processing their exact updates. The result tags each returned event with R's `DocumentId` and exact occurrence identity.

### 16.11 Order and Payment

Order contains Payment; Payment contains Order. Cancellation on Order updates the exact Order state, re-finalizes the component, reaches Payment through its `/order` occurrence, and can set `captureBlocked`. Payment may emit `Capture Blocked`, which reaches Order through `/payment` and updates fulfillment state. The component commits only after the queue is empty and the final cyclic proof verifies.

A later capture operation directly targeting Payment reads the exact embedded Order state from Payment's own `$document` and deterministically refuses capture when Order is cancelled.

### 16.12 Representation and locality

All preceding examples produce identical exact results when members are inline, pure references with complete cyclic proof, warm, cold, or physically stored separately. A two-member component in a graph of one thousand unrelated documents opens the two members and required containing spine only.

### 16.13 Durable MyOS decomposition

A durable host may persist one session/head and epoch history per managed
document while preserving one closure publication boundary. For `A -> B -> A`:

```text
step 0: PROCESS_DOCUMENT_STEP(A, external entry)
        stage local A body/effects with no afterBlueId
        re-finalize {A,B}, yielding A1

step 1: PROCESS_DOCUMENT_STEP(B, exact event/update from A)
        stage local B body/effects with no afterBlueId
        re-finalize {A,B}, yielding B1

step 2: PROCESS_DOCUMENT_STEP(A, exact event/update from B)
        stage local A body/effects with no afterBlueId
        re-finalize {A,B}, yielding A2

commit: atomically publish A2, B1, component proof, checkpoints, subscriptions,
        occurrence bindings, and public Root events
```

A and B are evaluated by the same one-document processor. A does not know how
many documents contain it. B does not receive an ambient parent. Each can read
the other only because the other is explicitly embedded in its own exact
content.

In MyOS, immutable document/epoch objects may be written before the database
transaction. The transaction then verifies every expected input head and
atomically inserts epoch metadata, advances every affected current head, writes
occurrence/checkpoint/subscription deltas, and publishes the outbox/commit
companion. A failed transaction leaves all authoritative heads unchanged;
unreferenced immutable objects are harmless.

Historical catch-up remains one exact managed-revision cause per closure
invocation. MyOS may commit A5->A6, then A6->A7, and so on, while blocking later
live work until the feeder-owned barrier is complete. Each such invocation
still processes every affected document separately.

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

The existing canonical `Process Embedded` type is unchanged and retains its exact BlueId.

Its `paths` and `collectionPaths` fields define immediate owned managed-document occurrences, mutation boundaries, propagation edges, and the recursive subscription surface.

Under Contracts 1.0, the concrete directed occurrence graph may contain a finite verified cycle. The type itself does not contain a cycle mode, component ID, consistency mode, or scheduling field. Component membership is derived from the exact graph and complete cyclic-set evidence.

Acyclic occurrences use the ordinary `PROCESS` fast path. Cyclic or dynamically repartitioned regions are handled by `PROCESS_CLOSURE` or `ADMIT_CLOSURE`. No new authored relationship type is introduced.

### A.8 Processing Initialized Marker

Direct processor state at `contracts/initialized`:

```yaml
name: Processing Initialized Marker
document:
  description: >
    Exact member-specific pre-initialization scope document frozen before any
    member lifecycle work in the whole-component initialization batch. This is
    the initial document for the scope's processing lifecycle. It may be
    materialized inline or represented as an equivalent pure { blueId: ... }
    reference.
```

All missing markers for one component batch are installed together and are
followed immediately by the single exact component/containing-spine
finalization pass in §§7.10 and 9.3. A marker or `initialized` flag cannot be
published member by member.

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
      description: >
        Exact checkpoint-domain node from §10.2 or its equivalent pure
        reference; its BlueId is independently verified.
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

`$processingEvent` remains the original external event when one exists and is
absent for admission and managed-revision invocations.

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
InvalidClosureSnapshot
InvalidAdmissionCause
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
ManagedDocumentIdInvalid
ManagedDocumentIdentityConflict
ManagedOccurrenceBindingMissing
ManagedOccurrenceBindingConflict
ManagedOccurrenceStateMismatch
EmbeddedRouteNotFound
EmbeddedScopeNotObject
EmbeddedCollectionMustBeObject
EmbeddedCollectionMemberMustBeObject
InvalidEmbeddedCollectionPath
EmbeddedPathSelectorUnsupported
OverlappingEmbeddedDeclaration
UnsupportedOpaqueEmbeddedCycle
CyclicProcessingContextRequired
CyclicSetEmbeddedBoundaryUnsupported
CyclicSetProofUnavailable
CyclicSetProofInvalid
CyclicComponentIdentityMismatch
CyclicMemberMappingMismatch
CyclicPreliminaryMemberAmbiguous
CyclicComponentFinalizationFailed
DynamicClosureExpansionFailed
DynamicComponentReclassificationFailed
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
DirectNodeLimitExceeded
MatchingDeliveryLimitExceeded
ParticipatingScopeLimitExceeded
InternalEventLimitExceeded
PatchLimitExceeded
RuntimeLedgerLimitExceeded
ManagedDocumentsPerClosureExceeded
ProcessEmbeddedEdgesPerClosureExceeded
CyclicComponentMemberLimitExceeded
CyclicComponentEdgeLimitExceeded
CyclicComponentCanonicalBytesExceeded
ClosureGraphChangeLimitExceeded
ClosureExpansionLimitExceeded
ClosureWorkOccurrenceLimitExceeded
ClosureTentativeFinalizationLimitExceeded
SubscriptionSurfaceInvalid
RuntimeExecutionFailure
GasLimitExceeded
```

`ActiveScopeCutOff` is normally an internal reason for discarding buffered effects rather than a top-level failure.

Diagnostic free text is informative. Category, relevant `DocumentId`, component generation, occurrence identity, scope/key/path, expected/actual exact identity, and numeric limit values are normative when applicable.

## Appendix C — Canonical Gas Trace Pseudocode

```text
function CHARGE(namespace, counter, quantity, context):
    require quantity is an Integer in 0..9007199254740991
    if quantity == 0:
        return

    weight = GAS_MANIFEST[namespace, counter]
    subtotal = quantity * weight

    sharedRemaining = RUN.sharedLimit - RUN.totalGas
    localRemaining = applicableLocalLimit(context) - RUN.localGas(context)
        # positive infinity when no lower local cap applies

    if subtotal > sharedRemaining or subtotal > localRemaining:
        if sharedRemaining == localRemaining:
            failingCap = { kind: SHARED }
        else if localRemaining < sharedRemaining:
            failingCap = { kind: LOCAL, documentId: context.documentId }
        else:
            failingCap = { kind: SHARED }
        record rejected-charge and owner evidence outside RUN.gasTrace
        throw GasLimitExceeded without adding the entry or beginning the work

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
    debit the same subtotal to every applicable local-ceiling ledger
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

### D.1 Process documents separately; do not publish a required closure partially

Every managed document MUST execute in its own isolated document step. Do not
run a Handler against a synthetic multi-document `$document`, do not inject
parent/reverse-containment context, and do not use a different Handler runtime
for cyclic members.

Separate execution is not separate same-cause publication. Do not publish one
cyclic component member, changed containing document, checkpoint, or occurrence
binding independently when it belongs to the same required closure. One closure
transition has one shared gas ledger, complete-set proof where cyclic, event
result, and atomic publication boundary.

For acyclic embedded documents, physical storage by BlueId or a separate
document-step evaluation does not authorize an early current-head commit.

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

Only the normative queue owner drains. Helpers enqueue and return. Charge
`internalEventDequeued` once per event occurrence removed, even when it has no
actual recipient; do not charge it once per delivery. Actual Triggered and
Embedded deliveries are separate work occurrences and each retains its exact
closure-work enqueue/dequeue charges.

### D.15 Do not confuse hosted work with portable gas

Provider bytes, signatures, storage, index maintenance, and CAS retries are host resources, not portable Contracts counters.

### D.16 Do not treat BlueId derivation paths as different identifier types

Contracts uses exact BlueIds for Root, event, checkpoints, bodies, and snapshots. The Language may derive a BlueId directly from an exact node or through the Source Document pipeline. The resulting identifier is the same BlueId kind.

### D.17 Do not copy an authored upsert operation into Document Update blindly

An authored `replace` on an absent object member is an upsert, but the resulting Document Update has semantic `op: add` because the member was absent before and present afterward.

### D.18 Do not merge independent external sources by arrival order

Cross-source order must satisfy the totality, per-source consistency, stable tie-break, and completeness laws in §3.6. Network arrival order, query order, and database insertion order are not semantic evidence.

### D.19 Do not embed contract entries

`Process Embedded` declarations must not traverse `/contracts`. Contract entries are runtime declarations of their containing scope, not child scopes.

### D.20 Do not interpret lists or wildcards as embedded collections

`/lessons/*` has no wildcard meaning, and `collectionPaths: [/lessons]` requires an object-compatible collection with stable direct keys. Contracts 1.0 does not implicitly turn list positions into scope identities.

### D.21 Do not invent live parent-channel inheritance

An embedded scope does not search parent or ancestor contract maps. Reuse exact Channel nodes by inline content or BlueId reference, and change bindings explicitly. A context-dependent parent binding requires a separately specified runtime type.

---

### D.22 Do not use a visited-document set as the work queue

A document may legitimately receive several distinct direct/update/event occurrences. Deduplicate exact work identity, not the target document.

### D.23 Do not order cyclic work by current BlueId

Member BlueIds change with component state. Use stable profile-managed identity and exact occurrence/Channel order.

### D.24 Do not defer exact cyclic identity until quiescence

After every identity-affecting member transition or graph rewrite, tentatively re-finalize the complete affected cyclic component before later work observes it. Temporary exact `MASTER#index` values remain nonauthoritative until commit. Never publish or finalize one member independently.

### D.25 Do not expose `this#n` or ZERO_BLUEID

Those values exist only inside the Language cyclic-set algorithm.

### D.26 Do not use wall-clock timeout as loop semantics

Only the shared deterministic gas and structural limits decide success or failure.

### D.27 Do not make a document aware of reverse containment

A managed document may explicitly contain and read another managed document. It
must not receive the identities, paths, states, or count of documents that
contain it. Reverse indexes belong to the feeder/orchestrator. The same exact
document and input must produce the same document-step result regardless of
how many containing occurrences exist.

*End of Blue Contracts and Processor Specification 1.0.*
