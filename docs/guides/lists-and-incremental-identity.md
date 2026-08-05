# Lists and incremental identity

There is one list identity algorithm: a domain-separated recursive prefix
fold. `H` is the normal direct BlueId hash over RFC 8785 canonical JSON:

```text
L0 = H({"$list":"empty"})
Ln = H({"$listCons":{
       "elem":{"blueId":id(elementN)},
       "prev":{"blueId":Ln-1}
     }})
id([a1, ..., an]) = Ln
```

The exact helper tokens are `$list`, `$listCons`, `elem`, and `prev`. RFC 8785
serializes `elem` before `prev`; host map insertion order is irrelevant.

## Append

Once `id([A, B])` is established, appending C needs exactly that prefix BlueId
and `id(C)`. The bodies of A and B are not inputs to the append step.

```text
prefix = id([A, B])
result = H({"$listCons":{
           "elem":{"blueId":id(C)},
           "prev":{"blueId":prefix}
         }})
result = id([A, B, C])
```

Appending k elements is O(k) fold work after the prefix identity is known.

## Earlier edits

Changing element i invalidates the suffix, not the prefix before i. Reuse the
accumulator before i, calculate the changed element identity, then fold every
following element again. This is deterministic recomputation, not a different
incremental algorithm.

## Identity versus storage

The prefix BlueId proves identity; it does not promise that prior elements are
co-located or available. Inline values and pure references both contribute the
same exact element BlueId. `$previous` and `$empty` are exact list identity
controls at their specified boundaries, not arbitrary authored shortcuts.

Run
[`IncrementalListIdentityExample`](../../examples/src/main/java/blue/language/examples/IncrementalListIdentityExample.java)
from `:examples` and see [lists and incremental
BlueId](../concepts/lists-and-incremental-blueid.md).
