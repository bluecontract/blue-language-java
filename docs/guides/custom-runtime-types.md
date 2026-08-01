# Custom runtime types

A runtime extension gives deterministic behavior to one exact Contracts type.
Use a Channel processor for external sources, a Handler processor for selected
execution, or a marker processor for recognized non-executable contracts.

## Define exact type evidence

Derive the custom type BlueId from a canonical type node whose base is the
appropriate specification/runtime type. Do not copy a test fixture's literal
BlueId into production code. Register both the exact ID and canonical type
evidence so matching can verify the declared role.

```text
canonical custom type node -> direct BlueId
BlueId + type evidence + role processor -> immutable registry entry
```

Use the runtime type constants published by the Contracts registry rather than
magic strings.

## Implement focused behavior

A Channel extension supplies deterministic subscription, acceptance, payload,
checkpoint, dependency, and optional target-selection functions. A Handler
extension receives an invocation-scoped execution context and returns effects
through typed patch, event, termination, and child-gas boundaries.

Callbacks must not:

- perform ambient I/O;
- inspect wall-clock time, locale, random state, or thread scheduling;
- retain invocation contexts after return;
- mutate global registration or caller-owned nodes;
- make semantic choices from provider call counts, cache hits, or telemetry.

## Build an immutable generation

Create the registry and processor/runtime through builders. The builder is
single-threaded. Building freezes registration and configuration; later
changes require a new generation. Borrowed providers, registries, mappers, and
observers remain owned by the caller.

## Test the boundary

For every custom runtime type, cover:

- inline and pure-reference forms;
- accepted, rejected, stale, and unavailable evidence;
- source versus target Channel authority;
- exact patches, Root-only events, and rollback;
- exact child-gas trace and gas exhaustion;
- concurrent calls through one immutable generation.

Run
[`CustomExternalChannelExample`](../../examples/src/main/java/blue/language/examples/CustomExternalChannelExample.java)
from `:examples`. The lower-level extension guide is
[Adding a Contract runtime](adding-a-contract-runtime.md), and the SPI inventory
is [runtime-spi.md](../reference/runtime-spi.md).
