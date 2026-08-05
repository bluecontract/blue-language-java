# Runtime projection and indexed delivery

This guide is for a host that keeps Root revisions and an external subscription
index outside the Contracts kernel. It covers four related public services:

- `ProcessorRuntimeAccess` lets a custom `DocumentProcessor` borrow the exact
  Language runtime and snapshot generation of an existing processor;
- `SubscriptionSurfaceProjection` derives the initial or changed persistent
  subscription surface with the processor's configured validator;
- `IndexedDeliveryEvaluator` reopens a complete retained surface, verifies an
  ordered physical-index candidate set, and prepares an exact delivery plan.
- `PlatformProcessInvocation` carries that plan and one strict request-local
  provider into the public platform-commit PROCESS lane.

These services contain no persistence or Coordination policy. The host still
owns transactions, revision allocation, index storage, and event ordering.

## Borrow one runtime generation

A custom processor must not independently combine a provider, snapshot manager,
matcher, and cache policy. Those values can belong to different runtime
generations. Import the processor-owned capability as one unit instead:

<!-- blue-example: examples/src/main/java/blue/language/examples/RuntimeProjectionAndIndexedDeliveryExample.java#borrow-runtime-generation -->
```java
        ProcessorRuntimeAccess runtimeAccess =
                sourceProcessor.administration().runtimeAccess();

        DocumentProcessor customProcessor = DocumentProcessor.builder()
                .runtimeAccess(runtimeAccess)
                .runtimeRegistry(customRegistry)
                .runtimeRegistryIdentity(customRegistryIdentity)
                .gasSchedule(gasSchedule)
                .build();
```

`runtimeAccess(...)` atomically configures the snapshot boundary, Language
runtime, verified provider, cache policy, and `ContractMatchingService`. This
example replaces the Contracts registry, so it also supplies the exact
non-default identity for that executable registry generation. The builder
rejects a custom registry paired with the standard package identity. Wholesale
registry replacement, processor/type registration, resolver replacement, and
package scanning all create a new executable registry generation and therefore
require the non-default identity to be supplied after the final change.

Importing the runtime also makes
`ProcessorExecutionContext.semanticOutputBoundary()` available to
hosted runtimes. The boundary is not a separate host hook: it is created by the
normal PROCESS runtime session and admits output through the same provider,
identity, gas, and memoization environment.

The access value is borrowed and non-closeable. Every direct operation checks
the source processor lifecycle, and a custom processor using it must not outlive
the source processor. It exposes transient resolution and typed exact-reference
materialization, but never exposes mutable loaders, registries, caches, or
matcher sessions.

Use the borrowed operations when host logic needs an exact snapshot or provider
fact without acquiring the processor's mutable snapshot manager:

<!-- blue-example: examples/src/main/java/blue/language/examples/RuntimeProjectionAndIndexedDeliveryExample.java#inspect-borrowed-runtime -->
```java
        ResolvedSnapshot snapshot =
                runtimeAccess.resolveTransient(exactRoot);
        ResolvedSnapshot preserved =
                runtimeAccess.resolveTransientPreservingPaths(
                        exactRoot, Arrays.asList("/contracts"));
        BlueOperationResult<FrozenNode> materialized =
                runtimeAccess.materializeVerifiedExactReference(
                        exactReference);
```

`resolveTransient(...)` resolves a detached clone of the whole input;
`resolveTransientPreservingPaths(...)` leaves the selected authored paths
deferred. Exact-reference materialization has four exhaustive outcomes:

- `ESTABLISHED` contains immutable content independently verified against the
  requested BlueId;
- `ABSENT` is a definitive provider miss;
- `INCOMPLETE` names outstanding exact BlueIds that may be acquired and retried
  with otherwise unchanged input;
- `INVALID` means the reference or returned evidence is inconsistent—including
  content that declares a different root BlueId—and must not be retried as a
  transient miss.

## Project the persistent subscription surface

Obtain the processor-owned service from either composition level:

<!-- blue-example: examples/src/main/java/blue/language/examples/RuntimeProjectionAndIndexedDeliveryExample.java#obtain-subscription-projection -->
```java
        SubscriptionSurfaceProjection projection =
                contracts.subscriptionSurfaceProjection();
        // or: processor.administration().subscriptionSurfaceProjection()
```

For a newly admitted Root, project the complete surface with the revision and
order boundary at which it becomes active:

<!-- blue-example: examples/src/main/java/blue/language/examples/RuntimeProjectionAndIndexedDeliveryExample.java#project-initial-subscriptions -->
```java
        SubscriptionDelta initial = projection.projectInitial(
                exactRoot,
                1L,
                ExternalOrderKey.of(Arrays.asList(100L, "root-created")));

        List<SubscriptionDelta.Entry> activeIntervals = initial.added();
```

Every initial entry is returned in `added()`. Its `activationRootRevision` is
the supplied revision and `startAfterExternalOrderKey` is the supplied exclusive
order boundary. Therefore an event at that same order cannot observe a
subscription created by the event.

After a successful Root transition, supply the complete previously active
surface, the exact changed Runtime Pointers, and the resulting commit boundary:

<!-- blue-example: examples/src/main/java/blue/language/examples/RuntimeProjectionAndIndexedDeliveryExample.java#project-updated-subscriptions -->
```java
        Set<String> changedPointers = new LinkedHashSet<>(Arrays.asList(
                "/contracts/inbox/subscriptionKey",
                "/lessons/lesson-7/contracts"));

        SubscriptionDelta update = projection.projectUpdate(
                resultingExactRoot,
                activeIntervals,
                changedPointers,
                2L,
                ExternalOrderKey.of(Arrays.asList(140L, "event-42")));
```

The host atomically retires `update.removed()`, installs `update.added()`, the
new Root revision, Root outbox, and delivery progress. Unaffected retained
intervals remain unchanged. The service clones mutable inputs, resolves through
the processor-owned snapshot generation, and invokes the configured
`SubscriptionSurfaceValidator`; callers cannot substitute semantic matching
functions. Because this operation receives the resulting Root rather than a
structural before-Root, retained intervals are the authoritative prior surface.
The validator can detect that mode with
`usesRetainedIntervalInputSurface()`. Its `inputRoot()` and `inputSnapshot()` are
then detached compatibility views of the resulting state, not prior structural
evidence. The changed-path set visible to the validator contains the exact
caller paths plus any canonically ordered retained descendant scopes needed for
conservative route invalidation; the caller-owned set remains unchanged.

## Prepare a delivery from indexed candidates

There are two different collections and they must not be conflated:

1. `completeActiveIntervals` is the complete retained subscription surface for
   the indexed Root revision. It is needed for completeness proof and for the
   eventual post-commit subscription delta.
2. `orderedCandidateOccurrenceKeys` is the exact result returned by the host's
   physical subscription-key lookup for this event.

Create occurrence keys without serializing private delimiter strings:

<!-- blue-example: examples/src/main/java/blue/language/examples/RuntimeProjectionAndIndexedDeliveryExample.java#prepare-indexed-delivery -->
```java
        List<ExternalSubscriptionOccurrenceKey> candidates = Arrays.asList(
                ExternalSubscriptionOccurrenceKey.of(
                        "/lessons/lesson-7", "lesson-events"),
                ExternalSubscriptionOccurrenceKey.of(
                        "/", "incoming-orders"));

        IndexedDeliveryPreparation prepared = contracts
                .indexedDeliveryEvaluator()
                .prepare(
                        exactRoot,
                        exactEvent,
                        2L,
                        ExternalOrderKey.of(Arrays.asList(141L, "event-43")),
                        completeActiveIntervals,
                        candidates);

        ExternalDeliveryPlan plan = prepared.deliveryPlan();
        List<IndexedDeliveryDiagnostic> diagnostics = prepared.diagnostics();
```

The evaluator reopens every active occurrence, not only the supplied
candidates. It resolves the effective Channel header, re-evaluates channel and
event keys, PRESELECTS, ACCEPTS, targeting, dependency capture, and checkpoint
evidence, and repeats registered functions to prove equal values and gas trace.
An eligible occurrence is a physical candidate when its evaluated channel and
event keys intersect. The supplied list must equal that complete set in
canonical delivery order; an omission, extra key, duplicate, or order mismatch
is rejected as invalid evidence.

A physical candidate may still return `preselects() == false`. It appears in
the immutable diagnostics but not in `plan.deliveries()`. A true PRESELECTS is
included in the plan. ACCEPTS controls the evaluated target and checkpoint
subject; a PRESELECTS-only occurrence uses the exact event identity as the
stable checkpoint-subject placeholder required by delivery evidence.

The plan retains the complete active interval surface, Root revision, event
order, exact Channel identities, dependencies, activation bounds, and a
complete-runtime-state certificate. It is independently revalidated before the
service returns it.

The evaluator is bound to the released Contracts 1.0 gas-package identity. A
processor configured with a different gas package is rejected before function
evaluation, preventing one call from mixing configured limits with 1.0
selection and embedded-routing limits.

## Process an already prepared plan

Use the explicit platform lane when the host already holds the exact plan and
has assembled the complete provider graph for this request. The following is a
complete method body. It deliberately prepares from an indexed materialized
view and processes pure references; each pair must identify the same exact Root
or event. These are two physical representations of the same two semantic
inputs, not four semantic values.

<!-- blue-example: examples/src/main/java/blue/language/examples/RuntimeProjectionAndIndexedDeliveryExample.java#platform-process-invocation -->
```java
        IndexedDeliveryPreparation preparation = contracts
                .indexedDeliveryEvaluator()
                .prepare(
                        indexedRoot,
                        indexedEvent,
                        rootRevision,
                        eventOrderKey,
                        completeActiveIntervals,
                        orderedCandidateOccurrenceKeys);

        PlatformProcessInvocation invocation =
                PlatformProcessInvocation.builder()
                        .deliveryPlan(preparation.deliveryPlan())
                        .nodeProvider(requestLocalProvider)
                        .build();

        PlatformProcessingResult result =
                contracts.processForPlatformCommit(
                        rootReference,
                        eventReference,
                        invocation);
```

Preparation runs in the evaluator's processor-owned runtime generation. The
`requestLocalProvider` becomes authoritative only when the platform PROCESS
call opens its invocation scope. In the example it must establish
`rootReference`, `eventReference`, and every exact reference demanded by the
selected processing path.

`PlatformProcessInvocation` accepts a plan returned by the public indexed
evaluator. A separately assembled `ExternalDeliveryPlan.Builder` value has no
evaluator-established Root/event/registry binding and is rejected by the
invocation builder. The context retains one immutable plan and one borrowed
provider; it does not ask callers to assemble a potentially inconsistent plan
and `VerifiedExecutionEvidence` pair.

This overload has exactly the same two Blue semantic inputs as every other
PROCESS call:

```text
PROCESS(Root, event) -> ProcessResult
```

The plan, managed revision, active intervals, event order, provider, and commit
companion are host execution environment and evidence. Different physical
representations of the same Root/event therefore cannot choose different Blue
semantics. Before execution, Contracts checks the evaluator binding against the
Root BlueId, event BlueId, equal managed/indexed revision, event order, and the
active immutable runtime-registry identity. It then verifies the supplied plan
directly with the core delivery-plan and preselection verifier, including its
complete interval surface, exact-runtime-state certificate, active bounds,
delivery identities, dependency catalog, completeness, and canonical order.
Omitted, extra, duplicate, stale, inactive, wrong-order, wrong-revision, or
wrong-registry evidence fails closed.

Direct verification is distinct from derivation. This overload never invokes
the `ExternalDeliveryPlanDeriver` captured when `BlueContracts` was built. The
existing `process(root, event)`, evidence overload, and current-Root
compatibility deriver keep their established behavior.

The runtime-registry generation in that binding is also portable metadata,
not a Java implementation fingerprint. It is derived from the released
runtime package identity plus lexically ordered registered BlueIds, processor
kind, canonical-versus-provider type-evidence mode, declared type identities,
and ordered executable-body field names. Java class names, processor object
identity, and allocation identity are excluded. Equivalent registrations can
therefore establish the same evidence boundary in another runtime language.

### One strict provider domain

The supplied provider is used for every provider-backed read in the attempt:

- Root/event and selected embedded-scope materialization;
- referenced contracts, schemas, type chains, Channel headers, declared
  Channel dependencies, and selected Handler bodies;
- runtime value reads and patch-path opening;
- final soundness and subscription-surface validation.

Language verifies every returned candidate against its requested BlueId. It
does not append the construction-time provider or bootstrap registry, consult
provider-derived state retained by another invocation, or publish discovered
provider content into the service's shared cache. Every call receives fresh
invocation-owned cache state; child processing sequences remain inside that
same provider domain. Concurrent calls on one `BlueContracts` generation can
therefore use different providers without cross-provider reads or cache
contamination.

Provider batching, fragment count, cache temperature, call count, and latency
remain host metrics. They cannot change the semantic result, named portable-gas
trace, or admitted-gas total for equivalent exact evidence.

The provider is borrowed. Closing the invocation scope clears invocation-owned
state but does not close the provider or the borrowed Language runtime. If the
request needs application, registry, or transport fallback, compose that
fallback into `requestLocalProvider` before the call.

### Phase-B classification across representations

Phase B classifies the exact feeder-selected source Channels and their declared
same-scope dependencies. For a pure-reference or fragmented Root, the
classification projection now materializes the admitted Root and selected
scope ancestor chain before pruning contracts. It retains selected headers,
processor-owned checkpoint and termination state, and required
`Process Embedded` routing markers. Selected executable-body paths and
unrelated reference branches remain authored and cold until a later phase
selects them.

This order makes inline, pure-reference, partial, and fragmented Root forms
expose the same selected dependency surface without turning classification into
a whole-Root scan. The Phase-B/Phase-C dependency-equality check remains in
place: a real header, contribution, catalog, ordering, or dependency change is
still rejected as stale or invalid evidence.

### Use the result atomically

`PlatformProcessingResult.processResult()` is the five-field semantic result.
`commitCompanion()` carries the expected Root/event identity, expected and
resulting revision, external order, and verified subscription delta. Persist
both in one compare-and-swap transaction. A committing success advances the
Root revision and installs the returned Root/outbox; a noncommitting terminal
result retains the revision and advances only revision-bound delivery progress.

## Current-Root compatibility deriver

When a compatibility API requires `ExternalDeliveryPlanDeriver`, create one
from the same evaluator and the same complete active surface:

<!-- blue-example: examples/src/main/java/blue/language/examples/RuntimeProjectionAndIndexedDeliveryExample.java#current-root-deriver -->
```java
        ExternalDeliveryPlanDeriver deriver = contracts
                .currentRootDeliveryPlanDeriver(
                        2L,
                        ExternalOrderKey.of(Arrays.asList(141L, "event-43")),
                        completeActiveIntervals);
```

The returned deriver evaluates every active occurrence and computes the
physical candidate set internally. It is a convenience for an already indexed
current Root, not a recovery mechanism for lost activation history. A Root
scan cannot reconstruct historical activation boundaries.

## Why the result is deterministic

For the same exact Root, event, runtime registry generation, revision, order
key, active intervals, and candidate keys, the result is fixed because:

- occurrence order is deeper scope first, then normalized RFC 6901 scope by
  Unicode code points, contract order, channel key, and effective type;
- effective contracts retain exact type and ordered Source identities;
- provider content is verified against its requested BlueId;
- evaluation has an authoritative pass and an isolated full replay, and each
  pass performs its own authoritative/diagnostic-twin function comparison;
  registered host functions are therefore invoked four times per occurrence,
  while only the designated authoritative meter admits call gas;
- activation uses explicit revision and total-order bounds;
- delivery and diagnostic collections are immutable and canonically ordered;
- no wall clock, randomness, thread schedule, cache state, or host object
  identity enters the semantic inputs.

JavaScript and other implementations reproduce the same result by implementing
the same Language/Contracts specification, fixture package, ordering rules, gas
manifest, portable registration metadata, and evidence boundaries. Java class
names and object identities are API/runtime conveniences, not part of the
semantic protocol.

## Failure boundaries

These operations fail closed. Important outcomes include:

- `ExecutionEvidenceUnavailableException`: exact provider evidence is not
  currently available; acquire the named BlueIds and retry unchanged input;
- `InvalidExecutionEvidenceException`: revision, candidate, header, dependency,
  activation, or checkpoint evidence is inconsistent;
- `SubscriptionSurfaceInvalidException`: the Root cannot produce one finite,
  canonical subscription surface;
- `PortableLimitExceededException` or `GasLimitExceededException`: the exact
  manifest or invocation budget rejected the work;
- `IllegalStateException`: a borrowed runtime/service owner was closed or the
  required verified snapshot generation is absent.

Do not translate unavailable evidence into absence, retry deterministic invalid
evidence as though it were transient, or rebuild matching from private kernel
objects.
