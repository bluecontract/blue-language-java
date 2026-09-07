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

## Preparing definitions (Task D development proposal)

Use `language.resolution().resolveDefinition(source)` to prepare a definition
without supplying example values. For example:

```yaml
name: Gender
type: Text
schema:
  enum: [female, male]
```

Preparation checks schema shape, known kind compatibility, contradictions and
fixed payloads. It retains requirements that depend on a future instance.
`language.identity().canonicalIdentityInput(source)` prepares the exact form
to store; hash that form with `directBlueId` and store it under that exact ID.
Bind imports to that ID before specializing it.

`language.resolution().resolve(instance)` checks completed values. Gender
instances with `female` or `male` pass; other values fail. A successful identity
calculation or definition preparation is not a completed-value certificate.
`ConformanceEngine.check` reports resolver acceptance as data and expects
preprocessed Language input; `BlueResolution` accepts authored Source input.

The validation goal is outside the node and does not enter identity. To compare
a resolved representation's source identity, retain the canonical root and type
evidence from `language.snapshots().resolve(source)`. A detached resolved graph
with materialized `blueId` metadata is not authored Source and must not be fed
back through the Source parser as if it were. An opaque pure reference can be
hashed without fetching its value; explicitly expand it when the payload is
needed.

These declaration and enum membership rules remain proposed pending campaign
review; see the primary specification's §§8.1.1 and 9.2.5–9.9.

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

Type matching compares demanded nominal, structural and schema constraints;
it does not certify the target's entire instance. A warm matching
plan or snapshot can reduce physical work but cannot change the result.
Limited matching distinguishes established false from incomplete evidence.

Run
[`SpecializationExample`](../../examples/src/main/java/blue/language/examples/SpecializationExample.java)
from `:examples`. See [ADR
0002](../adr/0002-specialization-vs-expansion.md).
