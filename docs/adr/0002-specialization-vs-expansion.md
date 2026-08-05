# ADR 0002: Specialization is not expansion

Status: accepted for Blue Language 1.0.

## Context

Both operations combine type information with a node, but they make different
promises. Treating specialization as a spelling of expansion obscures identity
and mutability rules.

## Decision

Expansion replaces references with their exact content and preserves the
meaning and identity of the input graph. Collapse is its inverse at eligible
exact boundaries. Specialization creates a new node by applying an overlay to
a type; the result can therefore have a different BlueId.

Neither operation mutates caller-owned `Node` values. Expansion needs verified
provider evidence for every opened reference. Specialization needs the exact
type and overlay selected by the caller; it does not silently fetch unrelated
graph branches.

## Consequences

- Use expansion/collapse to change representation without changing meaning.
- Use specialization to construct a new typed value.
- Tests for expansion assert identity preservation; tests for specialization
  assert the newly constructed value and unchanged inputs.
- Documentation must use “specialization” consistently and avoid older
  extension-oriented terminology.

See [Types and specialization](../guides/types-and-specialization.md).
