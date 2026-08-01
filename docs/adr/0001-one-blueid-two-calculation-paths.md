# ADR 0001: One BlueId, two calculation paths

Status: accepted for Blue Language 1.0.

## Context

Blue has one content identifier: the Base58 representation of the normative
SHA-256 identity calculation. Earlier API names made direct calculation and
Source Document calculation look like different identifier kinds.

## Decision

Keep one BlueId format and algorithm. Expose two preparation paths:

```text
exact BlueId input ------------------------------> direct BlueId

Source -> preprocess -> resolve -> canonicalize -> direct BlueId
```

The Source Document path is required for authored conveniences such as names,
imports, transformations, positional overlays, and inherited values. It uses
canonicalization, never minimization. “Content BlueId” is acceptable prose
shorthand for the resulting BlueId, not a second identifier type.

## Consequences

- A direct calculator rejects Source-only syntax rather than guessing intent.
- Both paths produce the same BlueId for the same exact canonical node.
- APIs, diagnostics, and documentation must not revive Node, Content, or
  Semantic BlueId as distinct identifier kinds.
- Conformance vectors can compare both paths against one identity oracle.

See [Nodes, graphs, and BlueIds](../guides/nodes-graphs-and-blueids.md).
