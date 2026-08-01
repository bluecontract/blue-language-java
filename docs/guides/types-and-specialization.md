# Types and specialization

Types are ordinary exact Blue nodes. An instance points to a type with a pure
reference and contributes its own overlay.

```yaml
type:
  blueId: 5person...type
name: Ada
active: true
```

Resolution establishes the ordered type chain, merges inherited and local
values, validates fixed values and schemas, and produces complete meaning.
Type traversal uses verified references and rejects cycles that are not the
specification's finalized cyclic-set mechanism.

## Specialization creates a new node

Specialization applies an overlay to a selected type:

```text
specialize(type, overlay) -> new typed node
```

The type and overlay remain unchanged. The result may have a different BlueId
because it is a new value.

Expansion has a different contract:

```text
expand(reference-bearing node) -> same value in a materialized form
```

Expansion preserves identity; specialization constructs. Documentation and
APIs use “specialize,” not extension terminology.

## Matching

Type matching compares complete nominal and schema meaning. A warm matching
plan or snapshot can reduce physical work but cannot change the result.
Limited matching distinguishes established false from incomplete evidence.

Run
[`SpecializationExample`](../../examples/src/main/java/blue/language/examples/SpecializationExample.java)
from `:examples`. See [ADR
0002](../adr/0002-specialization-vs-expansion.md).
