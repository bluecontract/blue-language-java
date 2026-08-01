# Expand, collapse, resolve, canonicalize, and minimize

These operations are related but not interchangeable.

| Operation | Question | Identity contract |
| --- | --- | --- |
| expand | What exact content does this reference edge denote? | preserves node identity |
| collapse | Which exact subtree can be represented by a pure reference? | preserves node identity |
| resolve | What is the complete type-derived meaning? | establishes meaning, not direct input |
| canonicalize | What unique exact value is hashed? | produces direct BlueId input |
| minimize | What compact ordinary Source resolves the same way? | may have several valid forms |

## Object example

If a type supplies `active: true` and Source supplies `name: Ada`, resolution
contains both. Canonicalization includes every identity-bearing value in its
unique exact location. Minimization may keep only the type reference and
`name`, because resolution can recover `active`.

## List example

```text
Inherited  [A, B]
Resolved   [A, B, C]
Minimized  $previous(id([A, B])) + C
Canonical  [A, B, C]
```

The minimized form is Source. It must be preprocessed and resolved again.
Canonical form is exact and can be passed directly to the BlueId calculator.

## Strict and exhaustive APIs

Strict methods require completion and throw deterministic failures for invalid
or incomplete evidence. Limited methods return exhaustive outcomes such as
established, absent, incomplete, and invalid. Incomplete never means absent.

Run
[`ExpandCollapseProviderExample`](../../examples/src/main/java/blue/language/examples/ExpandCollapseProviderExample.java)
and
[`SemanticFormsExample`](../../examples/src/main/java/blue/language/examples/SemanticFormsExample.java)
from `:examples`. See [ADR
0003](../adr/0003-canonicalization-vs-minimization.md).
