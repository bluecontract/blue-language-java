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

The pre-1.0 `ChannelDelivery` and
`ChannelEvaluation.matchDeliveries(...)` APIs are deprecated compatibility
stubs. Their values cannot be submitted to PROCESS, and
`matchDeliveries(...)` always rejects the obsolete routed-delivery model.

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

`ContractMatchingService` supplies the shared frozen event-pattern matcher,
including identity, structural, schema, list, dictionary, primitive-scalar,
and provider-backed reference/type matching.

## Execution order

For a verified external occurrence the processor:

1. revalidates the immutable occurrence and activation interval;
2. performs channel preselection and complete acceptance read-only;
3. freezes payload, checkpoint domain, and checkpoint subject;
4. rejects stale delivery before initialization;
5. pre-admits matching handler bodies;
6. initializes the participating Root-to-target closure top-down;
7. executes the one selected delivery;
8. writes its checkpoint only after complete success.

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

All patches, markers, checkpoints, events, ledgers, and subscription changes
are tentative. A deterministic runtime, evidence, portable-limit, gas, or
subscription-surface failure returns the exact input Root and no Root events.
Runtime failure never commits a fatal marker or fatal lifecycle event.
