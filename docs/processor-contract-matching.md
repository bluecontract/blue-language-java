# Processor Contract Matching

This document describes the Java processor base that contract-specific modules
should build on. The goal is to keep `blue-language-java` responsible for the
deterministic processor runtime while letting concrete contract packages define
their own channel and handler semantics.

## Core Rule

Channel and handler matching is contract-specific.

The engine owns deterministic orchestration:

- scope traversal and embedded-scope isolation
- channel ordering and handler ordering
- checkpoints and duplicate gating
- patch application, cascades, and generalization
- gas accounting
- lifecycle delivery and termination
- must-understand failures for unsupported contract types

Concrete contract processors own:

- whether a channel accepts an incoming event
- how a channel turns that event into the event delivered to handlers
- how a handler derives its channel when the contract type supports an indirect
  binding
- whether a handler should run for a channelized event
- handler execution behavior
- event identity/newness when the channel has stronger rules than canonical
  event signatures

This is intentional. A Conversation operation, a timeline channel, a document
update channel, and a future payment channel do not all have the same matching
logic.

## Channel SPI

`ChannelProcessor` now has a first-class evaluation result:

```java
ChannelEvaluation evaluate(T contract, ChannelEvaluationContext context)
```

`ChannelEvaluation` contains:

- `matches`
- optional channelized event
- optional event id
- optional multi-delivery list

Channels can also reject stale non-duplicate events after checkpoint lookup:

```java
boolean isNewerEvent(T contract, ChannelCheckpointContext context)
```

The default returns `true`. Contract-specific channels should override this
when event order is stronger than "not the same event", for example timeline
sequence numbers or ledger heights.

The compatibility path still supports processors that only implement:

```java
boolean matches(T contract, ChannelEvaluationContext context)
String eventId(T contract, ChannelEvaluationContext context)
```

but new processors should implement `evaluate(...)` directly.

Composite-style channels can return multiple `ChannelDelivery` entries from
`ChannelEvaluation.matchDeliveries(...)`. Each delivery has its own handler
event, optional event id, optional checkpoint key, and optional precomputed
`shouldProcess` decision. This keeps the engine generic while allowing
contract-specific fan-out channels.

`ChannelDelivery` always requires a non-null handler event. Returning a matched
evaluation with no usable deliveries is treated as no match. This makes
fan-out deterministic: a composite channel either provides concrete deliveries
or it does not match.

## Immutable Channel Context

`ChannelEvaluationContext.event()` returns a clone. Mutating it does not affect
the event delivered to handlers.

To normalize or enrich an event, return it:

```java
public ChannelEvaluation evaluate(MyChannel contract, ChannelEvaluationContext context) {
    Node event = context.event();
    if (!accepts(event)) {
        return ChannelEvaluation.noMatch();
    }
    event.properties("kind", new Node().value("channelized"));
    return ChannelEvaluation.match(event, eventId(event));
}
```

This matches the processor model: events passed through the runtime are
effectively read-only unless a channel explicitly returns a new channelized
event.

`ChannelCheckpointContext` is also read-only. It exposes the channelized event,
the current event signature, the previous stored channel event, and the
previous stored signature. It does not let channel processors mutate checkpoint
state directly.

`ChannelEvaluationContext` also exposes same-scope channel bindings:

```java
String bindingKey()
Set<String> channelKeys()
ChannelContract channel(String key)
ChannelProcessor<? extends ChannelContract> channelProcessor(String key)
ChannelEvaluationContext forBindingKey(String bindingKey)
```

This is the base support needed by composite channels. A channel such as
`Conversation/Composite Timeline Channel` can read its child channel contracts,
ask the registry for the child processors, evaluate them with
`forBindingKey(childKey)`, and then return one or more `ChannelDelivery`
entries. The runtime still owns checkpoints, handler dispatch, and gas
accounting.

## Handler SPI

`HandlerProcessor` now has a channel derivation hook:

```java
String deriveChannel(T contract, HandlerRegistrationContext context)
```

The default returns `null`. The loader first uses an explicit `channel`; when it
is absent, it calls `deriveChannel(...)`. The derived value must name a
registered channel in the same `contracts` map. This is the base hook needed by
contract types such as `Conversation/Sequential Workflow Operation`, where the
handler points at an `Operation` and the operation declares the channel.

`HandlerRegistrationContext` exposes the current scope path, handler key, the
same-scope contract keys, each contract's type BlueId, frozen contract nodes,
mutable copies of contract nodes, and typed conversion through
`contractAs(key, Class<T>)`.

`HandlerProcessor` also has a matching hook:

```java
boolean matches(T contract, HandlerMatchContext context)
```

The default implementation returns `true`. This is deliberate: the base
`Handler` contract does not define one universal matching strategy. Contract
packages can opt in to shared shape/type matching:

```java
public boolean matches(MyHandler contract, HandlerMatchContext context) {
    return context.matchesEventPattern(contract.getEvent());
}
```

`HandlerMatchContext` exposes:

- scope path
- immutable event clone
- frozen event view
- markers
- `matchesEventPattern(Node pattern)`

Handlers that do not match are skipped before execution.

## Shared Event Pattern Matcher

`ContractMatchingService` is the shared event-pattern matcher. It wraps the
frozen matcher and supports:

- pure `blueId` identity checks
- shape/property matching
- schema checks
- list and dictionary payload matching
- untyped programmatic scalar events matching core primitive patterns
- optional use of a `Blue` instance for provider-backed reference/type lookups

When `DocumentProcessor` is created through `Blue`, the matching service is
provider-backed. Standalone `DocumentProcessor` instances still support local
frozen matching, but cannot resolve unknown external references unless a
matching service with `Blue` is supplied.

## Runtime Flow

External event processing is now:

1. Load the scope contract bundle.
2. For each non-processor-managed channel in deterministic order:
   - call `ChannelProcessor.evaluate(...)`
   - skip if no match
   - use returned deliveries, returned channelized event, or raw event
   - apply checkpoint duplicate gating against the incoming external event
   - call `ChannelProcessor.isNewerEvent(...)`
   - run only handlers whose `HandlerProcessor.matches(...)` returns true
   - persist the incoming external event after successful channel processing
3. Processor-managed channels still route internally:
   - lifecycle
   - document update
   - triggered events
   - embedded-node bridges

All handler execution still goes through `ProcessorExecutionContext`, so patch
boundaries, gas, emissions, termination, and snapshot updates remain centralized.

## What blue-contract-java Should Implement

`blue-contract-java` should register processors for repository contract types,
for example:

- `Conversation/Timeline Channel`
- `Conversation/Operation`
- `Conversation/Sequential Workflow Operation`
- `Conversation/Update Document`
- `Conversation/JavaScript Code`

For operation/workflow contracts, channel binding should be derived by that
handler processor through `deriveChannel(...)`. The engine only needs the final
handler binding to a channel key.

## Tests Covering This Base

The current test suite covers:

- original external events being stored in checkpoints while channelized events
  are delivered to handlers
- multi-delivery channel evaluation with independent checkpoint keys
- same-scope composite channel evaluation through `ChannelEvaluationContext`
- event ids overriding canonical checkpoint signatures
- channel-specific stale event rejection through `isNewerEvent`
- handler channel derivation from another same-scope contract
- channel context mutation being ignored unless returned in `ChannelEvaluation`
- contract-specific handler matching with `HandlerMatchContext`
- programmatic untyped scalar events matching core primitive patterns
- existing processor-managed channels and embedded/triggered/lifecycle flows

This gives the next project a stable base: implement repository-specific
contracts without changing the processor orchestration rules again.
