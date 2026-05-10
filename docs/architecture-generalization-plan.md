# Architecture Notes: Canonical Snapshots And Generalization

This document records the direction that was chosen and the current state after
implementation. For a more operational guide, see
`snapshots-patching-and-generalization.md`.

## Chosen Direction

The implemented architecture follows these rules:

1. Canonical documents are the identity source.
2. Resolved documents are derived runtime/cache state.
3. Processor mutation is transactional.
4. Generalization is allowed only upward through type metadata.
5. Cached resolved snapshots/types speed processing but do not change gas.

## Identity Rule

The snapshot BlueId is:

```text
BlueId = hash(strict canonical root)
```

There are two public identity APIs:

```java
blue.calculateBlueId(node)         // structural hash of the given node
blue.calculateSemanticBlueId(node) // preprocess -> resolve -> minimize -> hash
```

`ResolvedSnapshot` always stores a canonical root, a resolved root, and a BlueId
that must equal the canonical root hash.

## Canonical Versus Resolved

Canonical root:

- minimized overlay
- strict reference-only `blueId`
- no redundant inherited overrides
- hash source of truth

Resolved root:

- inherited values available for runtime reads
- resolved type chains available for conformance/generalization
- stored only as cache/runtime state

## Implemented Snapshot Flow

```text
authoring Node
  -> preprocess
  -> resolve
  -> reverse/minimize
  -> FrozenNode canonicalRoot
  -> FrozenNode resolvedRoot
  -> ResolvedSnapshot
```

Loading an already canonical node skips semantic re-minimization:

```text
canonical Node
  -> FrozenNode canonicalRoot
  -> resolve
  -> FrozenNode resolvedRoot
  -> ResolvedSnapshot
```

## Generalization

A patch may violate the current declared type.

Example:

```yaml
type: Price in EUR
currency: EUR
```

Patch:

```yaml
op: replace
path: /currency
val: USD
```

The node can no longer claim `Price in EUR`, so it generalizes to `Price`.

Current implementation:

- starts at the deepest changed path
- checks conformance
- generalizes `type`, then `itemType`, `keyType`, or `valueType` as needed
- replaces only touched frozen paths
- records canonical generalization patches
- records changed metadata paths
- checks ancestors up to root
- commits only after the full plan succeeds

## Processor Transaction

```text
base snapshot
  -> immutable canonical patch plan
  -> immutable resolved patch plan
  -> conformance/generalization plan
  -> commit new snapshot
```

On failure:

- materialized mutable view is rolled back
- previous snapshot is restored
- no cache update is committed

## Cache Model

`Blue` keeps:

- `resolvedSnapshotsByBlueId`
- `ResolvedReferenceCache`

This means:

- resolving the same canonical document can return the same `ResolvedSnapshot`
- resolving two documents with the same type graph can reuse the same frozen type
  nodes
- preloaded snapshots can be added at startup
- gas is unchanged by cache hits

## What Is Implemented

Implemented:

- strict canonical core
- BlueId/list/circular hashing rules
- semantic canonicalization API
- `FrozenNode`
- `ResolvedSnapshot`
- resolved snapshot cache
- resolved type/reference cache
- canonical overlay patch engine
- immutable patch planner
- snapshot-backed processor runtime
- snapshot-native contract loading
- patch-time minimization
- dynamic generalization
- gas/cache tests

## What Is Still Not Finished

Still missing:

- fully frozen-native conformance checks
- provider ingestion defaulting to semantic minimized identity, if that is the
  desired product rule
- persistent collection internals optimized for large edit streams
- incremental path-index maintenance
- ECMA-262 regex compatibility
- Appendix B canonical type catalog
- canonical-plus-bundle transport
- external conformance corpus

## Why This Architecture Is Valuable

It removes the expensive old pattern of repeatedly resolving and cloning mutable
type graphs. Resolved types can now be reused by object identity. Patch updates
can path-copy frozen roots. Processor reads can use indexes. Generalization keeps
every committed document type-sound.

The result is better correctness first, then better performance:

- fewer full resolves
- fewer provider fetches
- less cloning
- deterministic rollback
- smaller canonical state
- stable BlueIds for minimized documents
