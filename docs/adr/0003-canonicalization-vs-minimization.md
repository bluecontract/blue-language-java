# ADR 0003: Canonicalization and minimization have different outputs

Status: accepted for Blue Language 1.0.

## Context

Both operations start from resolved meaning and may remove redundant authored
material. Only one of them can be an identity input.

## Decision

Canonicalization produces the unique exact BlueId input required by the
specification. Minimization produces a compact ordinary Source overlay that
resolves to the same meaning and may use `$previous`, `$pos`, or `$replace`.

For an inherited append-only list:

```text
Inherited  [A, B]
Resolved   [A, B, C]
Minimized  $previous(id([A, B])) + C
Canonical  [A, B, C]
```

Source Document BlueId calculation therefore canonicalizes and does not
minimize. A minimized result must pass through preprocessing and resolution
again before identity calculation.

## Consequences

- Canonical output is unique and valid direct input.
- More than one valid minimized Source representation may exist.
- Minimization is an authoring/storage optimization, not an identity shortcut.

See [Resolve, canonicalize, and minimize](../guides/expand-collapse-resolve-canonicalize-minimize.md).
