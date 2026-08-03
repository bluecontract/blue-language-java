# Runtime projection and indexed delivery

This guide is for a host that keeps Root revisions and an external subscription
index outside the Contracts kernel. It covers three related public services:

- `ProcessorRuntimeAccess` lets a custom `DocumentProcessor` borrow the exact
  Language runtime and snapshot generation of an existing processor;
- `SubscriptionSurfaceProjection` derives the initial or changed persistent
  subscription surface with the processor's configured validator;
- `IndexedDeliveryEvaluator` reopens a complete retained surface, verifies an
  ordered physical-index candidate set, and prepares an exact delivery plan.

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
manifest, and evidence boundaries. Java class names are API conveniences, not
part of the semantic protocol.

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
