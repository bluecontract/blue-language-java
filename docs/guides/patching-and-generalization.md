# Patching and generalization

Canonical patching applies an immutable add, replace, or remove operation to an
exact snapshot. The engine validates the pointer and payload before creating a
new graph.

## Persistent changed-spine rebuild

```text
old Root
  left  -----------------------> unchanged frozen subtree
  right -> old value

replace /right

new Root
  left  -----------------------> same frozen subtree instance
  right -> new value
```

The old snapshot remains unchanged. The new snapshot re-establishes canonical
and resolved meaning and receives its own BlueId. A patch below an opaque
cyclic member fails before provider demand; replacing the complete member edge
is allowed.

## Contracts patch boundary

Contracts collects handler/update patches inside an invocation transaction.
It preflights paths, protects processor-owned state, applies patches in exact
order, cuts off replaced active scopes, and generalizes effective types only
through the Language conformance planner. All mutations are tentative until
final soundness, checkpoint, and subscription validation pass.

Generalization chooses the specification-valid common type representation; it
does not erase fixed values or schema obligations merely to make a patch fit.

Run
[`PersistentPatchingExample`](../../examples/src/main/java/blue/language/examples/PersistentPatchingExample.java)
from `:examples`. See
[transactional-state.md](../architecture/transactional-state.md).
