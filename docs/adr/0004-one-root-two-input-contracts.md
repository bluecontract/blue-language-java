# ADR 0004: Contracts processes two inputs and one Root

Status: accepted for Blue Contracts and Processor 1.0.

## Context

Embedded scopes, feeder state, delivery evidence, and platform commit metadata
can make processing appear to accept several documents. That model would make
atomicity and cross-language conformance ambiguous.

## Decision

The semantic operation is exactly:

```text
PROCESS(Root, event) -> status, Root, Root events, gas, diagnostic?
```

Root is the only authoritative document. Embedded scopes are owned paths in
that Root. The feeder selects and orders candidate external occurrences but is
not a third semantic input. Verified delivery evidence and provider fragments
are execution evidence for the two exact inputs, not additional authored
state.

Only success publishes one replacement Root and Root-scope events. Every
non-success result retains the input Root and publishes no events. A platform
may atomically commit a separate companion record, but that record does not
enter the semantic result.

## Consequences

- Internal child events drain inside the invocation and are not returned.
- Patches, lifecycle state, checkpoints, and subscription changes commit
  together or not at all.
- Inline and pure-reference representations must produce identical results.

See [Contracts processing](../guides/contracts-processing.md).
