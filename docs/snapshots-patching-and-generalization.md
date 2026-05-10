# Snapshots, Patch Planning, And Generalization

This document explains the immutable runtime architecture implemented in this
branch: `FrozenNode`, `ResolvedSnapshot`, resolved type caching, immutable patch
planning, canonical minimization during patches, and dynamic type
generalization.

## Core Representations

### Canonical Root

The canonical root is the stored/minimized overlay form. It is the source of the
snapshot BlueId.

Example:

```yaml
type:
  blueId: ProductTypeBlueId
price:
  amount: 150
```

If `currency: USD` is inherited from the type, it does not need to be stored in
canonical form.

### Resolved Root

The resolved root is the runtime view used for reads and conformance checks. It
contains inherited values and resolved type chains.

Example resolved view:

```yaml
type:
  name: Product
price:
  amount: 150
  currency: USD
```

Resolved roots are derived cache state, not identity state.

### FrozenNode

`FrozenNode` is an immutable node representation.

It provides:

- strict canonical validation
- lenient resolved mode for expanded `blueId` metadata
- cached per-node BlueId
- immutable list/map views
- copy-on-write updates
- path lookup
- materialization back to mutable `Node`

### ResolvedSnapshot

`ResolvedSnapshot` contains:

```java
FrozenNode canonicalRoot;
FrozenNode resolvedRoot;
String blueId;
Map<String, FrozenNode> canonicalIndex;
Map<String, FrozenNode> resolvedIndex;
```

The constructor rejects mismatched BlueIds:

```java
new ResolvedSnapshot(canonicalRoot, resolvedRoot, canonicalRoot.blueId());
```

## Snapshot Cache And Type Reuse

`Blue` maintains two caches:

- `resolvedSnapshotsByBlueId`: canonical BlueId -> `ResolvedSnapshot`
- `ResolvedReferenceCache`: referenced BlueId -> frozen resolved node/type

Loading the same canonical document twice returns the same snapshot object:

```java
ResolvedSnapshot first = blue.loadSnapshot(canonical);
ResolvedSnapshot second = blue.loadSnapshot(canonical.clone());

assertSame(first, second);
```

Different documents that reference the same type reuse the same frozen resolved
type object:

```java
assertSame(first.frozenResolvedRoot().getType(),
           second.frozenResolvedRoot().getType());
```

Preloaded snapshots can be registered at startup:

```java
blue.cacheResolvedSnapshot(precomputed);
ResolvedSnapshot loaded = blue.loadSnapshot(precomputed.blueId());
```

If the snapshot is cached, loading by BlueId does not fetch from the provider.

## Canonical Overlay Patching

`CanonicalOverlayPatchEngine` applies JSON Patch to a frozen canonical root.

Supported operations:

- `add`
- `replace`
- `remove`

Supported structures:

- object properties
- array insert/replace/remove
- array append with `/-`

Rejected:

- patching the root document itself
- traversing into scalars
- invalid array indexes
- append token on object parents

The engine returns a `CanonicalPatchResult`:

```java
CanonicalPatchResult result = snapshot.applyCanonicalPatch(patch);

FrozenNode nextRoot = result.root();
FrozenNode before = result.before();
FrozenNode after = result.after();
String path = result.path();
```

The original root is never mutated.

## Immutable Patch Planner

`ImmutablePatchPlanner` wraps patching for processor transactions.

It computes:

- new frozen root
- before node
- after node
- operation
- normalized path
- origin scope
- cascade scopes

The processor uses two planners:

- canonical planner over `snapshot.frozenCanonicalRoot()`
- resolved planner over `snapshot.frozenResolvedRoot()`

This lets update metadata come from the resolved view while canonical state is
kept minimal.

## Patch-Time Canonical Minimization

When a patch writes a value equal to inherited resolved state, the canonical
override is removed instead of preserved.

Example type:

```yaml
name: Money
currency: USD
```

Canonical instance:

```yaml
type:
  blueId: MoneyBlueId
```

Patch:

```yaml
op: add
path: /currency
val: USD
```

Result:

```yaml
type:
  blueId: MoneyBlueId
```

The resolved view still has `currency: USD`, but the canonical root remains
minimal and the BlueId does not change.

If the patch writes `EUR`, the override remains:

```yaml
type:
  blueId: MoneyBlueId
currency: EUR
```

## Dynamic Type Generalization

Generalization keeps processor output type-sound after mutations.

Example type chain:

```yaml
name: Price
amount:
  type: Integer
currency:
  type: Text
```

```yaml
name: Price in EUR
type:
  blueId: PriceBlueId
currency: EUR
```

Document:

```yaml
type:
  blueId: PriceInEurBlueId
amount: 150
currency: EUR
```

Patch:

```yaml
op: replace
path: /currency
val: USD
```

The node no longer conforms to `Price in EUR`. The planner moves its declared
type upward to `Price`:

```yaml
type:
  blueId: PriceBlueId
amount: 150
currency: USD
```

If a parent type required `Price in EUR`, the parent is checked next and may also
generalize.

## Generalization Algorithm

For a changed path:

1. Apply the patch to tentative frozen canonical and resolved roots.
2. Find the deepest existing changed node.
3. Check conformance.
4. If it fails, generalize one metadata field upward:
   - `type`
   - `itemType`
   - `keyType`
   - `valueType`
5. Re-check.
6. Repeat until conformant or no parent type exists.
7. Replace only the affected path in the frozen root.
8. Move to the parent path and repeat up to `/`.
9. Return a `ConformancePlan`.
10. Commit only if the full plan succeeds.

The plan returns:

```java
FrozenNode canonicalRoot();
FrozenNode root();
boolean generalized();
List<CanonicalGeneralizationPatch> canonicalPatches();
List<String> changedPaths();
boolean fullSnapshotRebuildAvoidable();
```

## List And Dictionary Metadata Generalization

List example:

```yaml
prices:
  type: List
  itemType:
    blueId: PriceInEurBlueId
  items:
    - type:
        blueId: PriceInEurBlueId
      currency: EUR
    - type:
        blueId: PriceInEurBlueId
      currency: EUR
```

Patch:

```yaml
op: replace
path: /prices/1/currency
val: USD
```

The second item generalizes from `Price in EUR` to `Price`. Then the list itself
may generalize `itemType` from `Price in EUR` to `Price`.

Changed paths include:

```text
/prices/1/type
/prices/itemType
```

Dictionary example:

```yaml
prices:
  type: Dictionary
  keyType: Text
  valueType:
    blueId: PriceInEurBlueId
```

If one dictionary value generalizes to `Price`, the dictionary may generalize
`valueType` to `Price`.

Changed paths include:

```text
/prices/sku2/type
/prices/valueType
```

## Processor Transaction Flow

`DocumentProcessingRuntime.applyPatch(...)` now works roughly like this:

```text
rollback = current mutable materialized view
baseSnapshot = current snapshot or snapshotManager.fromDocument(...)
canonicalPlan = ImmutablePatchPlanner(baseSnapshot.canonical).plan(...)
resolvedPlan = ImmutablePatchPlanner(baseSnapshot.resolved).plan(...)
conformancePlan = ConformanceEngine.planGeneralization(...)
if generalized:
    commit generalized snapshot
else:
    commit normal canonical patch snapshot
on failure:
    restore rollback and previous snapshot
```

The mutable `Node` view is now a compatibility adapter generated from the
canonical snapshot. Snapshot state is authoritative.

## Gas And Caching

Resolved snapshot/type caches affect CPU and provider fetches, not gas.

Tests cover both paths:

- processing with cold caches
- processing with preloaded snapshots and resolved types
- embedded processing that shares resolved type cache across scopes

Expected behavior:

- gas is based on processor work and event/patch sizes
- gas does not decrease because a cache was preloaded
- preloading can still make processing much faster by avoiding repeated
  resolution and cloning

## Current Limitations

The architecture is immutable at the snapshot boundary, but not every internal
algorithm is fully frozen-native yet.

Still missing:

- conformance checks directly over `FrozenNode`
- no `Node` materialization in the conformance hot path
- persistent collection data structures optimized for many edits
- incremental index maintenance for new snapshots
- canonical-plus-bundle transport format

## Key Tests

- `ResolvedSnapshotTest`
- `CanonicalOverlayPatchEngineTest`
- `ImmutablePatchPlannerTest`
- `ConformanceEngineTest`
- `DocumentProcessorSnapshotTransactionTest`
- `DocumentProcessorGeneralizationTest`
- `DocumentProcessorGasTest`
