# ADR 0008: Explicit stable-key embedded collections

## Status

Accepted for Blue Contracts and Processor 1.0.

## Context

One Root can own a dynamic number of reusable process occurrences. Exact
`Process Embedded.paths` can name each child, but updating that declaration for
every new member is cumbersome. Wildcards and List positions would make scope
identity, activation intervals, checkpoints, gas, and audit references depend
on mutable container layout. Implicit parent Channel lookup would make a
child's behavior depend on embedding context rather than its exact content.

## Decision

Dynamic active process collections use explicit stable-key
`collectionPaths`; wildcards, list positions, and live parent-channel
inheritance are intentionally excluded from Contracts 1.0.

Each declaration points to an object-compatible collection. Every present
direct member becomes one concrete owned scope. The processor freezes one
immutable concrete-scope plan at invocation entry and reuses it across
preflight, delivery, mutation, cut-off, fragmentation, checkpoint, and
subscription-delta work.

Local participant Channels are exact child values. They may be inline or pure
references. Reusing an exact Channel or child BlueId does not merge occurrence
state; the stable member key remains part of the owned occurrence identity.

## Consequences

- Collection membership and traversal order are finite and deterministic.
- Adding a member cannot make it participate in the creating event; its
  subscription interval starts after commit.
- Removing and re-adding one key begins a fresh occurrence and checkpoint
  lineage.
- A parent Channel replacement cannot silently rebind existing children.
- External targeting remains an explicit responsibility of each Channel
  runtime and feeder protocol.
- Lists, wildcard traversal, `/contracts/...` embedding, and overlapping exact
  and generated paths fail closed rather than acquiring context-dependent
  interpretations.
