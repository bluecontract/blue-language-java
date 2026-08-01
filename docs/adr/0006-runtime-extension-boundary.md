# ADR 0006: Runtime extensions are explicit and deterministic

Status: accepted for Blue Contracts and Processor 1.0.

## Context

Applications need custom Channel, Handler, and marker types. Classpath scan
order, mutable global registration, ambient I/O, and wall-clock state would
make the same Root and event behave differently across hosts.

## Decision

Bind an immutable runtime registry when building a processor. Every entry
contains an exact type BlueId, canonical type evidence, a declared runtime
role, and its focused processor/functions. Advanced delivery, evidence,
subscription, gas, and observation hooks are supplied explicitly through the
builder.

Runtime callbacks may inspect only the immutable context admitted for that
phase. They return patches, events, checkpoints, or named child-gas work
through typed boundaries. They must not use ambient I/O, time, locale, random
state, process-global mutation, or operational telemetry in semantic choices.

## Consequences

- Built runtimes are immutable; changed configuration creates a new runtime.
- Explicit registration is the portable default. Optional scanning belongs to
  mapping/integration code and cannot influence semantic order.
- Borrowed providers, registries, observers, and mappers are never closed by
  the runtime unless ownership is explicitly transferred.

See [Custom runtime types](../guides/custom-runtime-types.md).
