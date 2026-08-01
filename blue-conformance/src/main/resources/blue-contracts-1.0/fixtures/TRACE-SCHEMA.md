# Blue Contracts 1.0 conformance projection and trace schema

## 1. Closed projection surface

Fixtures may assert only paths listed in `projection-catalog.yaml`. Adding a projection requires updating this file, the catalog, the fixture schema package identity, and the validator.

The public semantic result is limited to:

```text
result.status
result.document
result.events
result.totalGas
result.diagnostic
```

Everything under `trace`, `demands`, `feeder`, `commit`, `attempt`, `platform`, and `variants` is conformance evidence, not additional `PROCESS` output.

The catalog may expose exact suffixes of `result.document` only when a fixture needs to assert a normative state invariant. The corrected package includes explicit suffixes for embedded replacement/cut-off, Process Embedded paths, and retained exact-node references; arbitrary uncatalogued document traversal remains forbidden.

## 2. Canonical named trace entry

`trace.namedEntries` is an ordered sequence. Each entry has:

```yaml
sequence: <zero-based integer>       # MAY be omitted in fixture expectations when list position supplies it
namespace: processor | semantic | runtime
counter: <name from the bound gas manifest>
quantity: <positive integer>
weight: <non-negative integer>
subtotal: <quantity * weight>
scopePath: <absolute runtime pointer, optional>
contractKey: <raw contract key, optional>
logicalPath: <absolute or value-local pointer, optional>
reason: <registered diagnostic reason, optional>
```

The sum of subtotals is `result.totalGas`. A failed next charge is absent. Reuse of a previously counted semantic proof is represented by its dedicated counter, not by silently omitting required evidence.

## 3. Logical demand record

`demands.semantic` is the ordered sequence of semantic paths or exact identities first demanded by canonical execution. It excludes provider pages, cache keys, transport chunks, and speculative prefetch. The same logical run has the same demand sequence across physical variants.

## 4. Derived projections

The catalog defines exact paths and result types. Derived projections are deterministic folds over the canonical run record. Examples:

- `trace.externalDeliveryOrder`: `scopePath:channelKey` for retained deliveries in execution order;
- `trace.eventOccurrenceOrder`: source path and event identity for each internal dequeue;
- `trace.documentUpdates`: ordered local Document Update values;
- `trace.checkpointWrites`: ordered direct checkpoint writes after successful deliveries;
- `trace.discardedEffects`: buffered effects discarded by cut-off or rollback;
- `commit.*`: one revision-bound persistence decision;
- `feeder.*`: canonical preselection, interval, order, and snapshot derivations.

A runner MUST derive these from canonical semantic records. It MUST NOT expose host object identities, thread order, cache hits, or implementation-specific stack traces.
