# Processor contract matching

This document describes the Contracts 1.0 Java extension points. The semantic
operation has exactly two inputs:

```text
PROCESS(Root, event) -> ProcessResult
```

There is one authoritative Root. A caller cannot submit a target path,
delivery occurrence, child-processing session, child-commit envelope, or
public effect log.

## Exact external-delivery evidence

External preselection is environmental evidence, not a third semantic input.
An `ExternalDeliveryPlanDeriver` reads a revision-complete feeder snapshot for
the exact Root/event pair and returns an `ExternalDeliveryPlan`. Each
`ExternalDeliverySnapshot` fixes:

- scope path and raw channel key;
- channel order;
- ordered source-contribution BlueIds and effective type BlueId;
- immutable subscription keys;
- checkpoint domain and subject BlueIds;
- activation interval.

The processor binds this plan to the exact Root BlueId, event BlueId, runtime
registry identity, and equal managed/indexed revisions. It independently
revalidates every selected occurrence before execution. Missing, stale, or
inconsistent evidence produces a noncommitting result. Hosts that cannot prove
the complete external surface must use `PROCESS_ATTEMPT` and acquire the exact
resources before retrying.

An exact empty plan is meaningful. It must be explicitly certified with
`ExternalDeliveryPlan.Builder.exactRuntimeState()`; absence of a plan is not
evidence that no channel matches.

## Channel SPI

`ChannelProcessor.evaluate(...)` performs read-only complete acceptance for
the already preselected occurrence:

```java
ChannelEvaluation evaluate(
        T contract,
        ChannelEvaluationContext context)
```

It returns either `ChannelEvaluation.noMatch()` or one accepted, optionally
channelized event with an optional event identity. The evaluation cannot
manufacture more delivery occurrences.

Application channel processors that can appear on the external subscription
surface must also expose deterministic immutable functions through
`externalSubscriptionFunctions()`. Those functions derive the finite
subscription-key set, checkpoint domain, and activation data used by
preselection and changed-surface validation. A registered external type
without supported functions fails closed.

Context-aware functions can consult immutable same-scope channels through
`ExternalChannelFunctionContext`. `member(key)` and `members()` resolve exact
headers and record direct/whole-surface dependencies. Aggregate types such as
All-Timelines should use `membersByEffectiveType(timelineTypeBlueId)`: it
captures that exact type-family membership, including an empty selection,
without resolving peer aggregate or unrelated channel types. Member and
type-family identities are included in checkpoint-domain derivation,
`SubscriptionDelta.Entry`, retained-interval invalidation, and sparse feeder
evidence verification.

Event functions can use `context.matchesPattern(candidate, pattern)` for the
same frozen structural/type matching semantics across inline nodes and pure
references. Each deterministic function-evaluation pass gets an independent
matcher whose only non-core materialization path is the captured
`ProcessingSnapshotManager` verified exact-reference boundary. Nested selected
member evaluation shares that pass-local matcher. Closing the pass clears the
matcher caches and severs its manager-backed materializer; retained contexts
reject later matching calls. Provider failures and identity mismatches
propagate; they are not cached as `false`. Header functions, including their
event-time consistency recomputation, cannot invoke the matcher directly or
through `ExternalChannelMemberSnapshot.evaluate(...)`. The strict callback
supplies exact canonical definitions rather than a fully
preprocessed/merged Language document; exact canonical type lineage is
followed, but definitions requiring broader resolution remain outside this
event-scoped primitive.

Event functions can also use
`context.materializeExactReference(reference)` to obtain one exact direct
fragment through the same verified, event-scoped snapshot boundary. The
default context-aware `eventKeys(...)` uses this operation for referenced
`subscriptionKey` and `subscriptionKeys` fragments. Ambient and header-time
materialization remain forbidden; application-specific registry projections
are not defined by the generic kernel.

After accepted-new classification,
`handlerChannelKey(...)` may select a different frozen same-scope Handler
channel and `logicalDeliveryKey(...)` may coalesce multiple fresh accepted
sources with the same exact payload. Defaults return the raw source key.
Handlers execute once per logical group; every participating raw source keeps
its own checkpoint, committed only after complete success. The target is not
evaluated or checkpointed as another external source unless it independently
appeared in verified delivery evidence.

The pre-1.0 `ChannelDelivery` carrier and
`ChannelEvaluation.matchDeliveries(...)` multi-delivery API have been removed.
External occurrences can enter the kernel only through verified,
revision-bound `ExternalDeliveryPlan` evidence.

## Handler SPI

`HandlerProcessor` retains three contract-specific hooks:

```java
String deriveChannel(
        T contract,
        HandlerRegistrationContext context)

boolean matches(
        T contract,
        HandlerMatchContext context)

void execute(
        T contract,
        ProcessorExecutionContext context)
```

The loader prefers an explicit handler channel and otherwise invokes
`deriveChannel(...)`. The result must identify a channel in the same effective
contracts map. Matching is read-only and runs against the frozen delivery
snapshot. Execution uses `ProcessorExecutionContext`, so patches, Root
emissions, internal events, termination requests, gas, and runtime child
ledgers remain under the processor's atomic run state.

Runtime ledger submission is the exception to application-effect rollback:
`submitRuntimeGasLedger(...)` immediately admits one live-bounded named child
ledger to the invocation meter before buffered effects are applied. A later
failure discards patches, events, termination, markers, checkpoints, and
subscription changes, but reports that admitted gas and its ordered trace.

`ContractMatchingService` supplies the shared frozen event-pattern matcher,
including identity, structural, schema, list, dictionary, primitive-scalar,
and provider-backed reference/type matching.

## Execution order

For a verified external occurrence the processor:

1. revalidates the immutable occurrence and activation interval;
2. performs channel preselection and complete acceptance read-only;
3. freezes payload, checkpoint domain, checkpoint subject, Handler target, and
   logical-delivery identity;
4. rejects stale delivery before initialization;
5. groups fresh accepted sources by same-scope logical-delivery identity and
   validates target/payload agreement;
6. pre-admits matching target-handler bodies;
7. initializes the participating Root-to-target closure top-down;
8. executes each logical delivery once;
9. writes every participating source checkpoint only after complete success.

The checkpoint subject is an exact node. A Timeline runtime can freeze an inline
minimal `{timeline, timestamp}` subject and compare
`ChannelCheckpointContext.currentSubject()` with the exact prior
`lastEvent()` in `isNewerEvent(...)`. Their BlueIds are available from
`eventSignature()` and `lastEventSignature()`. The feeder `eventOrderKey`
orders occurrence activation; it is not a replacement for per-Timeline
timestamp newness. Composite/All functions can delegate the selected member's
subject unchanged.

Triggered and embedded-node events use the invocation-local deterministic
queue. Root emissions are appended to `ProcessResult.events` immediately and
also participate in local delivery. Non-Root emissions remain internal unless
Root explicitly emits them.

## Changed subscription surface

Before commit, `SubscriptionSurfaceValidator` compares affected branches of
the exact input and tentative Root and constructs a deterministic
`SubscriptionDelta`. Validation covers effective channels, embedded paths,
present child objects, ancestry cycles, portable limits, immutable
subscription functions, activation data, source contributions, and checkpoint
domains. Persistent index storage and feeder queries belong to the host, not
this repository.

Hosts that persist Root revisions and the external subscription index should
use `processDocumentForPlatformCommit(...)`. It returns a
`PlatformProcessingResult` containing the ordinary semantic
`DocumentProcessingResult` and a separate `PlatformCommitCompanion`. The
companion binds the expected Root identity and revision, event identity and
order key, resulting revision, and the exact validator-produced
`SubscriptionDelta`. These values are committed together with compare-and-swap;
the companion is platform metadata, not a sixth `ProcessResult` field or a
public effect log. Rejected, stale, and already-terminated deliveries carry
progress-only companions and never carry a subscription delta.

## Atomic failure behavior

All patches, markers, checkpoints, events, termination requests, and
subscription changes are tentative. A deterministic runtime, evidence,
portable-limit, gas, or subscription-surface failure returns the exact input
Root and no Root events. Gas already admitted to the live invocation meter,
including a submitted runtime child ledger, remains in the total and ordered
trace. Runtime failure never commits a fatal marker or fatal lifecycle event.
