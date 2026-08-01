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

## Identity is not storage

A BlueId says nothing about provider location, cache state, fragment size,
transport availability, authorization, or ownership. Those are host concerns.
The same exact content has the same BlueId in YAML, JSON, memory, IPFS, or an
application-specific provider.

Run `ParseAndSerializeExample`, `DirectBlueIdExample`, and
`SourceDocumentBlueIdExample` from `:examples` for executable Java versions.
See [ADR 0001](../adr/0001-one-blueid-two-calculation-paths.md).
