# Codex Prompt 01 — Implement Blue Contracts 1.0 Affected-Closure and Cyclic Processing in `blue-language-java`

Implement the final `Blue Contracts and Processor Specification 1.0` shipped in this package.

Run this prompt only in the `blue-language-java` checkout. Its Gradle root is
`blue-language-java-build`; the relevant implementation modules are
`:blue-language-core`, `:blue-contracts-core`, and `:blue-conformance`.
Every main and test Java source set in these three modules must continue to
compile with `--release 8`, including `:blue-conformance`. Do not use records,
sealed types, pattern matching, or newer collection factories. The packaged
reference templates are Java 8-compatible semantic shapes; adapt them to the
existing repository APIs rather than copying them mechanically.

The required production work is primarily in `blue-contracts-core`. Blue Language's existing direct and cyclic BlueId algorithms remain normative and must not be reimplemented in Contracts or Coordination. Make only the smallest additive `blue-language-core` API changes needed to expose complete cyclic finalization/proof results to the processor.

## Non-negotiable scope

### Do not change

```text
Blue Language node semantics
canonical direct BlueId algorithm
Language §15 cyclic-set algorithm
this#n / MASTER#index format
BEX syntax or semantics
existing core .blue registry nodes or their BlueIds
ProcessEmbedded.blue schema
Channel.blue or Handler.blue schema
Mandate or onBehalfOf semantics
```

### Implement

```text
PROCESS_CLOSURE
ADMIT_CLOSURE
complete affected-closure evidence
finite directed Process Embedded graph
SCC partition and condensation ordering
acyclic deepest-first fast path
bounded cyclic component processing
dynamic cycle formation, merge and split
exact temporary whole-component finalization after every identity-affecting step
one work queue and one shared gas meter
component-safe initialization
exact closure result and platform commit companion
all ordinary and closure conformance fixtures
```

The implementation must preserve all existing ordinary `PROCESS` behavior when the affected closure contains one ordinary acyclic Root.

## Authoritative package

Use these exact sources:

```text
specifications/blue-contracts-and-processor-specification-1.0.md
conformance/contracts/gas-manifest.yaml
conformance/contracts/registry/
conformance/contracts/fixtures/
conformance/contracts/oracles/
conformance/contracts/release-manifest.yaml
```

Use the Java templates under:

```text
java-templates/reference/src/main/java/blue/contracts/closure/
```

as semantic shapes and validation guidance. Adapt naming/packages to the real codebase. Do not copy a reference class blindly when the real runtime already owns that concept.

## Phase 0 — exact baseline

Before editing production code:

1. Record the exact source commit and dirty state.
2. Run all existing Language and Contracts tests and conformance gates.
3. Record current Java compatibility gates.
4. Record exact hashes of:
   - `CircularSetIdentityCalculator.java`;
   - `CyclicSetProof.java`;
   - `CyclicSetProofResult.java`;
   - `CyclicAwareNodeProvider.java`;
   - `DocumentProcessor.java`;
   - `ProcessorInvocationOrchestrator.java`;
   - `ProcessorExecutionContext.java`;
   - `EmbeddedScopePlanner.java`;
   - `ProcessGasMeter.java`;
   - `GasSchedule.java`;
   - `PlatformCommitCompanion.java`.
5. Run the package reference validators.
6. Write characterization tests for dynamic cycle formation and exact temporary
   identity visibility before implementing the feature. A test-only commit is
   allowed only when it is green against existing behavior; otherwise keep the
   red test local and co-commit it with the first implementation that makes it
   pass.

Do not proceed when the ordinary baseline is not green.

## Phase 1 — expose exact cyclic-set finalization and proof APIs

Inspect existing Language classes, especially:

```text
blue-language-core/src/main/java/blue/language/identity/CircularSetIdentityCalculator.java
blue-language-core/src/main/java/blue/language/provider/CyclicSetProof.java
blue-language-core/src/main/java/blue/language/provider/CyclicSetProofResult.java
blue-language-core/src/main/java/blue/language/provider/CyclicAwareNodeProvider.java
```

Add an immutable result/API only when the existing surface cannot provide all of:

```text
canonical ordered member bodies using this#n references
preliminary member identities
canonical sorted member order
final MASTER BlueId
stable input-member -> final member-index mapping
final MASTER#index identities
complete proof material required for independent verification
exact canonical byte count used by portable limits
```

A suggested semantic shape is two ordinary Java 8 `final` immutable classes:

```java
public final class CyclicSetFinalization {
    // final fields, constructor validation, defensive copies, getters,
    // value equality/hashCode, and no mutable collection exposure
}

public final class CyclicMemberFinalization {
    // stable input key, sorted index, preliminary/final BlueIds,
    // and canonical member body with this#n references
}
```

Requirements:

- stable member key is input mapping evidence, not part of Language identity unless authored in content;
- ambiguity in preliminary members fails closed unless identity-bearing authored content disambiguates them;
- provider/proof verification is independent from finalization;
- no Contracts `DocumentId` concept leaks into Blue Language;
- no cyclic-set implementation cache affects BlueIds, proof or portable gas;
- existing ordinary cyclic API behavior stays binary/source compatible when possible.

Add tests using every exact oracle in `conformance/contracts/oracles/`.

## Phase 2 — introduce a complete affected-closure model

Add immutable platform/runtime values equivalent to the templates:

```text
DocumentId
ManagedScopeKey
ManagedOccurrenceBinding
ComponentSnapshot
AffectedClosureSnapshot
ClosureInvocationInput
DirectLogicalDelivery
ProcessingCause
ExternalEventCause
AdmissionCause
ExecutionPolicy
```

### DocumentId

Normative rules:

```text
nonempty Unicode
NFC normalized before admission
case-sensitive
unique within the selected managed-environment identity domain
compared by Unicode scalar/code-point sequence
not a Blue Language identifier
not included in a node's BlueId unless authored as Blue content
```

### Managed occurrence binding

It must bind:

```text
source DocumentId
sourcePath (the absolute JSON Pointer)
activation generation
target DocumentId
stable occurrence identity
expected exact target BlueId
state-specific binding identity
binding policy identity
active flag
required nullable pending historical epoch
```

The stable occurrence identity excludes `expectedTargetBlueId` and survives
ordinary target-state and `MASTER#index` churn. The binding identity includes
that exact target BlueId and changes with it. Both use the uniform
`{ "domain": ..., "value": ... }` constructor from
`conformance/contracts/identity-constructors.yaml`.

An active edge must also exist in exact Blue content at the source path and be
declared by effective `Process Embedded.paths` or a concrete `collectionPaths`
member. An inactive row is verified prospective binding evidence only; it is
not an SCC edge until the authored path appears and the same row activates in
place. The binding disambiguates two managed lineages with equal BlueIds; it
does not create another authored graph.

### AffectedClosureSnapshot

It is authoritative durable closure state and must contain enough exact
evidence for independent revalidation:

```text
frozen graph generation
all current documents required by the closure
the complete authoritative occurrence-binding set, including active and
inactive prospective rows
the occurrenceBindingSetIdentity recomputed over that complete set
initial SCC partition
complete current cyclic proofs required by cyclic references
public Root declarations
state-only closureIdentity recomputed over graph generation, exact document
records, the occurrence-binding-set identity, component-state identities and
the public Root set
```

`closureIdentity` MUST NOT include cause or direct-delivery evidence. Those are
invocation adjuncts and are bound by `invocationIdentity`.

### ClosureInvocationInput

Use one closed input value that owns the snapshot and every invocation adjunct:

```text
one AffectedClosureSnapshot
all direct logical deliveries
the directDeliverySnapshotIdentity recomputed over those deliveries
one ExternalEventCause or AdmissionCause
the nullable admission candidate and identity
execution policy identity and shared limit
runtime/repository/registry/environment identities
the exact historical transition evidence needed by any non-null
pendingHistoricalEpoch, or the deterministic provider boundary that supplies it
```

Every occurrence row carries `pendingHistoricalEpoch` explicitly as a safe
integer or `null`. A non-null value binds admitted historical catch-up state.
There is no hidden staged-binding map outside this snapshot. Recompute
`occurrenceBindingSetIdentity` over the complete active-and-inactive row set,
including `pendingHistoricalEpoch`, and reject a claimed mismatch before
semantic work. Likewise recompute `directDeliverySnapshotIdentity` over the
complete direct-delivery sequence. A non-null `pendingHistoricalEpoch` requires
the contiguous, identity-verified historical transition chain before the row
can catch up and clear the field; missing evidence returns `NeedsResources`.
The snapshot and invocation identities bind the complete row set. The API must
not separately accept another event, cause, route snapshot, execution policy,
or environment that could disagree with this closed input. For an external
cause, its external-order-policy identity must equal the one selected by the
input environment.

Do not allow caller-authored arbitrary direct targets. The host may construct
the invocation input's direct-delivery sequence only from a verified
route/subscription result.

## Phase 3 — add the closure processor API

Preserve the ordinary API:

```java
ProcessResult process(Node document, Node event, ProcessingEnvironment environment);
```

Add an exact platform API conceptually equivalent to:

```java
ClosureAttemptResult processClosure(ClosureInvocationInput input);

ClosureAttemptResult admitClosure(ClosureInvocationInput input);
```

Names may differ to match code style, but the semantics may not.
`ClosureAttemptResult` is the closed Java 8 union `Complete(result)` or
`NeedsResources(requiredBlueIds)`. Do not collapse `NeedsResources` into a
completed status, an exception, an empty result, or a commit/block receipt.

`ADMIT_CLOSURE`:

- has no fabricated Timeline Entry;
- has no provider timestamp;
- permits zero direct external deliveries;
- uses one exact admission cause identity;
- runs initialization, graph changes, cyclic finalization, gas, validation and rollback normally;
- returns `success` when required admission/initialization work commits.

`PROCESS_CLOSURE`:

- receives the original exact event;
- may start with an acyclic partition;
- may form cycles, merge components, split components and expand the closure;
- never reruns a completed direct logical delivery after reclassification;
- has one shared gas ledger and one atomic result.

## Phase 4 — deterministic graph and SCC planning

Implement a deterministic graph projection from exact `Process Embedded`
content plus the complete managed occurrence-binding set. Use only rows whose
`active` flag is true as graph/SCC edges. Retain inactive prospective rows as
identity-bound evidence and activate the same row in place when the authored
path becomes valid; do not consult a hidden staging table.

Use Tarjan or Kosaraju internally, but the result must not depend on traversal order. Canonicalize:

```text
members by DocumentId code-point order
occurrences by source DocumentId, path, activation generation, target DocumentId
components by condensation depth (deepest first), then minimum member DocumentId,
then minimum occurrence identity
```

The acyclic singleton path must remain efficient and preserve existing deepest-first behavior.

Do not use a global `visitedDocument` set as the work-deduplication mechanism. A document may legitimately receive several distinct work occurrences during one invocation.

## Phase 5 — exact work occurrence model and queue

Add closed work variants, not a map with optional strings:

```text
ExternalDelivery
InitializationDelivery
DocumentUpdateDelivery
TriggeredEventDelivery
EmbeddedEventDelivery
LifecycleDelivery
HistoricalTransitionDelivery
ContainingReferenceUpdate
```

`PatchContinuationFrame` is synchronous/LIFO control owned by the current
Handler execution. It has no `WorkOccurrenceId` and pays no closure work queue
charge. A patch creates an immutable Document Update occurrence; only each
actual matching delivery becomes queued `DocumentUpdateDelivery` work.

Work identity must use the exact closed constructor registry tuple:

```text
invocation identity
work ordinal
work kind
target managed-scope identity
source occurrence identity
```

The work evidence still carries the complete frozen eligible occurrence
lineages and creation-time binding data required for revalidation; do not
silently add or omit digest fields. Delivery resolves the latest valid binding
and tentatively finalized state for each frozen lineage without adding a new
lineage or retargeting to another `DocumentId`. Event occurrence
ordinal is allocated invocation-globally in canonical Handler-result emission
order and remains distinct when two emitted event values have the same BlueId.
All frozen-lineage deliveries from one emitted occurrence reuse its event identity.

### Queue/continuation rule

Implement exactly:

1. Dequeue one work occurrence.
2. Resolve exact target document/scope and Channel.
3. Execute one Handler.
4. Apply the Handler result in canonical patch order.
5. After each patch, run its required immediate Document Update continuation/cascade before applying the next patch from that result.
6. When an identity-affecting patch changes a cyclic member or component edge, re-finalize the complete changed component and required containing reference spine before any later work or emitted event is materialized/observed.
7. Materialize event occurrences using the exact post-finalization state.
8. Enqueue events in canonical order.
9. Continue until direct seeds and caused work are exhausted.

For each event occurrence, charge its single FIFO dequeue, materialize every
actual Triggered and frozen-containing Embedded delivery in canonical
source-then-containing order, and enqueue all of those delivery work
occurrences before dequeuing the first. Dequeue/deliver them in the same order;
finish each delivery's synchronous patch/update/finalization continuation
before dequeuing the next. Every actual occurrence of each of the eight closed
work kinds pays exactly one closure-work enqueue and one dequeue charge.

This must eliminate the prior ambiguity between "apply all patches then updates" and "patch/update continuation".

## Phase 6 — exact temporary finalization after every identity-affecting step

This is the most important semantic rule.

Do not implement:

```text
mutate A with identity-less handles
mutate B with identity-less handles
finalize one MASTER only when queue empties
```

Implement:

```text
A changes
    -> build complete tentative component member bodies
    -> replace internal edges with this#n markers
    -> call the exact Language cyclic-set finalizer
    -> install exact temporary MASTER#index values in the invocation snapshot
    -> later work may now read/copy/hash/compare those exact values

B changes
    -> repeat for the complete component
```

Temporary finalization is private/non-authoritative but is a real exact Blue state.

Keep the unchanged Language semantic finalization result separate from
Contracts orchestration evidence. The Language-facing API returns the exact
master, member mapping/proof and canonical-byte result; it does not receive a
fixture `oracleStage`. Contracts wraps each call with one invocation-global
`finalizationOrdinal` and one closed boundary:

```text
{ kind: WORK, afterWorkOrdinal }
{ kind: INITIALIZATION_BATCH, afterWorkOrdinal }
{ kind: CHECKPOINT_SETTLEMENT }
```

Allocate the ordinal immediately before the first charge belonging to that
boundary, including when that first charge is rejected. A rejected charge in
the boundary uses the same ordinal in its `FINALIZATION` owner.

Every application-visible operation must see exact values:

```text
$nodeBlueId / node identity
pointer reads
schema/type validation
equality/comparison
patch values
event payload construction
Document Update before/after values
provider demand keys
source transition identities
```

Invocation-local stable handles are allowed only as implementation references below this boundary.

Use the exact finite fixture stages and oracle constants. The test must prove B observes A at the second temporary master and A observes B at the third temporary master.

## Phase 7 — dynamic graph reclassification without replay

A Handler may add an ordinary Blue value at a declared Process Embedded path and close a cycle.

The processor must:

```text
retain the original frozen cause
retain accepted gas trace
retain completed direct-delivery set
retain queue and event ordinals
apply the ordinary patch
validate the new managed occurrence binding
expand the closure if exact required members/evidence are available
recompute only the affected SCC region
merge/split components
continue under the same invocation
```

When resources are missing, return `NeedsResources` with only the sorted exact
required BlueIds and no committed semantic gas/state. Discard that attempt;
after obtaining and verifying the requested nodes, invoke again from the exact
input closure and cause. Never resume an execution suffix, continuation, queue,
or tentative state. Additional exact historical evidence may produce the same
logical `invocationIdentity` on the fresh attempt only if the state-only input closure,
operation, cause, admission candidate, direct-delivery snapshot, execution
policy, and
environment identities all revalidate unchanged. Otherwise construct a new
invocation. Bind every consumed
transition through its transition identity, historical WorkOccurrence identity,
gas trace, and completed result/commit evidence; do not add resource evidence to
durable `closureIdentity`.

Do not introduce `componentPatches` or another graph mutation protocol.

## Phase 8 — initialization and dynamic cycles

Update initialization so `ADMIT_CLOSURE` and embedded activation can initialize a closure/component safely.

Requirements:

- no fabricated external event;
- exact admission cause available as processing cause;
- initialization markers publish only with the complete closure;
- one initialization Handler may add a reciprocal edge and form a cycle;
- preserve work/gas and reclassify within the same invocation;
- initialize members in the exact canonical order;
- caused cross-member events can revisit initialized/initializing members;
- gas exhaustion or one member failure rolls all new initialization state/markers back.

For each component initialization batch, freeze every then-missing member's
exact pre-initialization document and lifecycle contract before the first
member runs. Extend that frozen set when dynamic expansion adds another missing
member. After the batch's complete caused work drains, record the last accepted
causal `workTrace.ordinal` as `afterWorkOrdinal`, then install every still-needed
marker together in canonical member order. The exact marker is:

```text
{
    type: { blueId: ProcessingInitializedMarkerBlueId },
    document: { blueId: frozenPreInitializationDocumentBlueId }
}
```

No work may observe a partially marked component. Finalize each changed
resulting component once, in reverse topological order, under
`{kind: INITIALIZATION_BATCH, afterWorkOrdinal}` and rebuild its containing
spine before later work. Marker installation is a synchronous Direct Write: it
creates no application Document Update and no synthetic enqueue/dequeue pair.

Execute `c-clo-08-cycle-during-initialization.yaml` and `c-clo-21-admission-zero-direct.yaml`.

### Checkpoint comparison and settlement

For each accepted raw external source, compare the checkpoint in frozen Phase B
before initialization or Handler work and charge exactly one
`checkpointCompared`. Do not mutate the marker there. After the final direct
seed and the complete caused closure are quiescent, one settlement barrier:

The default domain is the direct BlueId of this exact Blue object—not a
Contracts `sha256:` envelope or host label:

```text
{
    contractsVersion: "1.0",
    effectiveTypeBlueId,
    sourceContributionNodeBlueIds,
    deterministicDependencyNodeBlueIds?,  # omit exactly when empty
    runtimeDiscriminator?                  # omit when absent/empty
}
```

Preserve both registered contribution/dependency orders. Independently verify
every stored present domain value against its claimed direct BlueId before
using it for comparison or retirement.

1. orders accepted-source add/replacements in original raw-source order;
2. follows them with orphan/domain-retirement removals in canonical scope/key
   order;
3. charges one `checkpointWritten` per actual add, replace, or removal;
4. installs the whole direct-marker batch without application updates; and
5. performs one immediate changed-component/containing-spine finalization under
   `{kind: CHECKPOINT_SETTLEMENT}`.

`checkpointWritten` already owns checkpoint address traversal, marker shape and
entry mutation. Do not also charge pointer, patch, or generic
`processorMarkerWritten` work for the same write. Separately meter changed Blue
identity, applicable semantic validation, component finalization and containing
spines. Every present receipt side carries the complete exact checkpoint-domain
value plus its independently recomputed direct BlueId; every absent side emits
the required null value/BlueId/subject fields. Execute C-CLO-33 and prove source
replacement precedes orphan cleanup and both roll back together on failure.

## Phase 9 — shared gas and local caps

Use one `ProcessGasMeter`/shared ledger for the complete closure.

The exact release default is from `gas-manifest.yaml`:

```text
maxProcessGas = 100000
```

Rules:

```text
no authored/host limit -> release default
host/document limit -> may lower only
member local limit -> ceiling on work while that member executes, using the same meter
no nested fresh meter
next charge admitted before work
rejected charge absent from admitted trace and returned as exact evidence
wall-clock timeout nonsemantic
```

Preserve exact trace order and counter ownership. `rejectedCharge.owner` is one
closed branch: `INVOCATION`, `WORK(workOccurrenceIdentity)`, or
`FINALIZATION(finalizationOrdinal, componentIdentity, componentGeneration)`.
Return `rejectedWorkOccurrence` exactly for `WORK`; never fabricate one for an
invocation or finalization rejection. Add closure counters and portable-limit
checks at their exact increment/admission points. Execute C-CLO-31 and C-CLO-32
in addition to the work-owned gas failures.

Run the short override loop, default-policy loop, local-cap fixture and all gas microfixtures.

## Phase 10 — complete closure result and commit companion

Return a Java 8 immutable completed-result branch equivalent in semantics to:

```java
public final class ClosureProcessResult {
    // final defensively copied fields for the complete result shape below
}
```

Expose it only through the `Complete` branch of the attempt union; resource
suspension remains the separate `NeedsResources` branch with no completed
result, gas trace, semantic state or commit companion.

It must represent:

```text
invocation identity and exact input/output closure identities
several final cyclic components
acyclic singleton components
component merge and split
all changed containing Roots
before/after exact BlueIds and exact documents
updated occurrences and activation generations
closed graph-change, checkpoint-write, subscription-delta and public-event sequences with identities
retired old masters/proofs where applicable
one public Root event sequence
one shared gas trace identity and structured rejected-charge owner when applicable
one atomic commit proof
```

Public events follow the existing Root-only rule. Internal member events may cause work but are not public merely because a member executed as a processing Root.

`PlatformCommitCompanion` must bind:

```text
invocationIdentity
inputClosureIdentity
outputClosureIdentity
expectedInputGraphGeneration
expectedInputDocuments
expectedInputComponents
inputOccurrenceBindingSetIdentity
outputGraphGeneration
resultingDocuments
resultingComponents
occurrenceBindingSetIdentity
graphChangesIdentity
checkpointWritesIdentity
subscriptionDeltasIdentity
publicEventsIdentity
gasTraceIdentity
blueLanguageSpecificationIdentity
contractsSpecificationIdentity
managedDocumentIdentityPolicyIdentity
managedBindingPolicyIdentity
exactNodeProviderDomainIdentity
externalOrderPolicyIdentity
runtimeRegistryIdentity
gasManifestIdentity
portableLimitPolicyIdentity
cyclicFinalizerIdentity
cyclicProofVerifierIdentity
```

Use the exact constructor field names and ordering from
`conformance/contracts/identity-constructors.yaml`; do not replace a sequence
identity with a count/Boolean or treat an implementation artifact identity as a
portable invocation field.

At publication, independently recompute the state-only input closure identity
from the current persisted graph/documents/occurrences/components/public Roots
and the output identity from the complete staged state. Compare both with the
companion. Never persist the prior cause/route attempt hash as current closure
state, and never trust caller-supplied closure or binding-set digests in place
of canonical recomputation.

## Phase 11 — proof, soundness and ordinary-path protection

Final validation must accept a verified, bounded supported cycle and reject only:

```text
undeclared active edge
missing/forged occurrence binding
incomplete or wrong cyclic proof
unsupported opaque member access
ambiguous preliminary members
over-limit component/closure
invalid schema/type/protected state
unindexed resulting subscription surface
pending work or unfinalized identity
```

Rename/clarify old tests so ordinary isolated mutation of an opaque cyclic member remains rejected, while a complete closure mutation is accepted.

The ordinary `PROCESS` API without complete closure/proof must continue to fail closed on unsupported cyclic-member mutation/traversal.

## Phase 12 — conformance runner

Extend the existing conformance harness to execute all closure fixtures rather than merely parse them.

The runner must:

- load the exact frozen release manifest;
- verify real BlueIds/proofs independently;
- reject active edges absent from document content;
- derive SCCs instead of trusting expected component labels;
- execute direct deliveries through real External Channels;
- apply ordinary patches;
- calculate work/event/transition identities;
- compare exact temporary masters, final documents/components, public events, gas trace, diagnostic and rollback;
- execute cold, warm, inline and reference-backed parity modes;
- distinguish package validation from implementation conformance.

All existing ordinary fixture families must remain green.

Migrate the existing exact-inventory gates from the historical 154 Contracts
fixtures / 307 combined fixtures to the manifest-derived 207 Contracts fixtures
(154 ordinary + 53 closure) / 360 combined fixtures (including 153 Language).
In particular, update or refactor:

```text
build-logic/src/main/java/blue/buildlogic/BuildLogicConstants.java
blue-conformance/src/main/java/blue/language/conformance/api/BlueReleaseConformanceReport.java
blue-conformance/src/main/java/blue/language/conformance/api/BlueContractsFixturePackage.java
```

The manifests, not duplicated literals, must be authoritative for fixture-role
counts wherever the current public/reporting API permits that migration.

## Phase 13 — performance without semantic shortcuts

The acyclic singleton path is the common fast path. Do not make every ordinary Root pay for full cyclic materialization.

Required structural targets:

```text
no full environment scan
no unrelated document load
no unrelated workflow-body load
no rehash of unchanged exact nodes when verified identity can be reused
SCC recomputation limited to the changed induced region on edge insert/remove
cyclic finalization limited to the changed component and required ancestor spine
cache state never changes gas/trace/result
```

Add metrics for:

```text
managed documents opened
occurrence bindings verified
SCC members/edges partitioned
closure expansions
work occurrences
component finalizations
members finalized
provider loads
unchanged branches reused
```

Do not weaken verification, skip finalization, or replace exact values with host objects to meet a timing target.

## Required final verification

Run at minimum:

```text
all Language tests
all existing Contracts tests
all ordinary Contracts fixtures
all closure fixtures declared by the generated manifest (derive the count; do not hard-code it)
all exact oracle tests
Java 8 compatibility where the module currently promises it
all publication/API compatibility gates
cold/warm/reference/inline parity
```

Produce:

```text
build/reports/contracts-1.0-closure-implementation.json
build/reports/contracts-1.0-closure-implementation.md
```

The report must include:

- exact source commit;
- exact spec, registry, gas, fixture and oracle identities;
- finalizer/verifier implementation identities;
- test counts/failures/skips;
- fixture-by-fixture status;
- exact finite and loop traces;
- API changes;
- performance structural counters;
- any unsupported behavior.

## Stop conditions

Stop and report instead of inventing semantics when:

- a normative contradiction is found;
- exact current Language cyclic finalization cannot represent a required fixture;
- one old ordinary fixture changes result or gas unexpectedly;
- a new API would require changing an existing core `.blue` type or BlueId;
- Mandate-specific logic appears necessary inside Contracts;
- a proposed optimization changes exact temporary identity visibility, work order, gas or rollback.

Do not modify the final Contracts specification merely to fit the implementation.

## Commit and handoff discipline

Preserve unrelated user changes. Make one small semantic unit per commit, run
focused tests before each commit, and never amend or squash earlier functional
commits. Every committed revision must be green: keep a deliberately failing
test uncommitted until its first passing implementation can be committed with
it. Follow the repository's concise conventional history, for example:

```text
test(language): characterize cyclic set finalization
feat(language): expose complete cyclic set finalization
feat(contracts): model affected closure evidence
feat(contracts): partition affected closure graph
feat(contracts): queue exact closure work occurrences
feat(contracts): process affected closures
feat(contracts): finalize cyclic components tentatively
feat(contracts): share closure gas accounting
feat(conformance): execute affected closure fixtures
```

Do not claim `IMPLEMENTATION_CONFORMANT` until the real released artifacts—not
the Python reference model—execute the complete generated corpus exactly.
