# Cyclic sets

Ordinary direct identity is acyclic. A finalized cyclic set establishes a
master identity for an ordered group and identifies members as:

```text
masterBlueId#0
masterBlueId#1
...
```

During calculation, `this#index` placeholders denote edges inside the same
candidate set. Final output contains only the master/member form.

## Proof boundary

A member does not have a standalone direct hash. A cyclic-aware provider must
prove that:

1. the master identity belongs to an admitted complete set;
2. the requested index is in range;
3. the returned member and ordered set evidence match that proof.

An ordinary node stored under `masterBlueId` cannot counterfeit
`masterBlueId#0`. A plain provider cannot validate a member by independently
hashing it.

## Runtime limits

A finalized member reference is an opaque external edge unless the set proof
is available. Replacing the whole edge is allowed. Patching below it, opening
an embedded processing scope through it, or using a bare member as the top-level
Root/event is rejected when the required set transaction/proof is absent.

Run
[`CyclicSetIdentityExample`](../../examples/src/main/java/blue/language/examples/CyclicSetIdentityExample.java)
from `:examples` for the released two-member vector. See [provider and fragment
architecture](../architecture/provider-and-fragment-model.md).
