# Immutable snapshots

`ResolvedSnapshot` binds the exact canonical Root, its complete resolved
meaning, resolution provenance, and BlueId. Its retained graph uses immutable
`FrozenNode` values.

## Ownership

- Creating a snapshot never mutates Source.
- Frozen roots and path values are safe to share.
- Accessors returning mutable `Node` values materialize detached copies.
- Changing a detached copy cannot change the snapshot or its BlueId.
- Runtime caches may retain snapshots under a bounded policy; cache presence
  cannot affect semantic results.

## Selected and transient views

Contracts can open path-preserving or deferred views for the exact
participating closure. Invocation-local transient caches and forks are not
published until the processing transaction commits. A failed or suspended
attempt releases them without changing the shared cache.

## Lifecycle

A snapshot remains valid independently of a caller's mutable input. Closing
the owning runtime clears runtime caches and rejects new admitted operations;
it does not mutate snapshot values already returned to a caller unless their
documented handle is runtime-scoped.

Run `ImmutableSnapshotExample` from `:examples`. See
[immutability-and-runtime-state.md](../architecture/immutability-and-runtime-state.md).
