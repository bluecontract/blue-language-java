# Nodes, graphs, and BlueIds

`Node` is the Java authoring and wire value for Blue's JSON-shaped data model.
It is mutable and caller-owned. A semantic operation copies or freezes a node
before retaining it; a returned mutable node is a detached value the caller may
change.

## One value can have many document slices

```yaml
name: Team
lead:
  blueId: 8lead...exact
reviewer:
  blueId: 8lead...exact
```

Both properties point to the same exact node. This is why Blue is a graph, not
a tree, even when one YAML representation looks tree-shaped. Pure references,
shared types, exact fragments, and finalized cyclic sets are graph edges.

## Pure references

A pure reference contains only `blueId`. Adding `name`, `type`, a value, list
items, object properties, or schema fields makes it ordinary content instead.
Provider evidence fetched for a pure reference must prove the requested exact
identity before it is admitted.

## Direct and Source paths

Use direct calculation only for exact BlueId input. Use Source Document
calculation for authored Source:

```text
exact node --------------------------------------> direct BlueId
Source -> preprocess -> resolve -> canonicalize -> direct BlueId
```

Both finish with the same algorithm and produce the same BlueId for the same
canonical value. Source calculation fails closed if required provider evidence
cannot be established. It never minimizes the node before hashing.

Source identity preserves a pure value reference as an exact identity leaf when
the surrounding context does not require its content. A list without an effective
`itemType` or other payload-dependent element constraints therefore needs the
BlueIds of its referenced documents, not their bodies. Canonicalization does not
fetch those documents merely to calculate the list's identity, even when the
provider has them available. Folding the list still processes its child IDs.

At these unconstrained paths, inline/reference Source identity parity is
guaranteed for referenced content that is already canonical identity input.
Arbitrary raw publication can produce a different Source identity when written
inline because its own types may change its canonical form. Direct exact
inline/reference identity remains equal. Where an effective type, inherited
payload, or schema requires reference content, that content is materialized and
canonicalized in the same context as an inline value.

The normative boundary is Language §13.2.1. A Dictionary with an effective
`valueType` interprets and validates its entries; it is not an opaque-reference
case. An inherited fixed value or applicable schema may also require content
when a List has no `itemType`.

A present, nonredundant canonical child retains its effective custom type. For
example, `Holder.currency: Currency` with payload `PLN` prepares a child with
`type: Currency` and `value: PLN` (type positions contain exact BlueIds). An
entire field supplied by an inherited fixed value can still be omitted, and
canonical Lists retain their complete payload according to §13.6. Absent
optional fields are not created.

An exact expand/collapse or canonical store/load preserves the established
identity. Feeding expanded bytes through Source preparation starts a new
interpretation, including authoring List overlays. Use the canonical/exact
loading boundary to persist and reload already prepared canonical Lists.

## Identity is not storage

A BlueId says nothing about provider location, cache state, fragment size,
transport availability, authorization, or ownership. Those are host concerns.
The same exact content has the same BlueId in YAML, JSON, memory, IPFS, or an
application-specific provider.

Run
[`ParseAndSerializeExample`](../../examples/src/main/java/blue/language/examples/ParseAndSerializeExample.java),
[`DirectBlueIdExample`](../../examples/src/main/java/blue/language/examples/DirectBlueIdExample.java),
and
[`SourceDocumentBlueIdExample`](../../examples/src/main/java/blue/language/examples/SourceDocumentBlueIdExample.java)
from `:examples` for executable Java versions.
See [ADR 0001](../adr/0001-one-blueid-two-calculation-paths.md).
