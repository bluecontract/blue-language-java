# Frozen Type Matching

This document explains the current Java matching implementation used by
`NodeTypeMatcher`, `FrozenTypeMatcher`, and the `Blue.nodeMatchesType(...)`
facade methods.

The goal is to support processor-style checks such as channel and handler
matching without fully resolving huge candidate documents when the pattern only
observes a small part of the document. In practice, this is the performance
critical path for contract processing: many handlers may ask "does this event or
document scope match this shape/type?" and most of those checks should be cheap.

## Public API

The public matching surface is:

```java
boolean Blue.nodeMatchesType(Node node, Node type)
boolean Blue.nodeMatchesType(FrozenNode resolvedNode, FrozenNode resolvedType)
boolean Blue.nodeMatchesType(ResolvedSnapshot snapshot, String pointer, FrozenNode resolvedType)
```

`NodeTypeMatcher` also exposes the lower-level compatibility API:

```java
boolean matchesType(Node node, Node targetType)
boolean matchesType(Node node, Node targetType, Limits globalLimits)
boolean matchesResolvedType(FrozenNode resolvedNode, FrozenNode resolvedTargetType)
boolean matchesResolvedType(ResolvedSnapshot snapshot, String pointer, FrozenNode resolvedTargetType)
```

The intended direction is:

1. Existing mutable callers can keep using `Node` inputs.
2. Processor code should move toward `ResolvedSnapshot` and `FrozenNode` inputs.
3. Hot matching paths should avoid mutable traversal and use snapshot path
   indexes plus frozen matching.

## Two Matching Paths

There are two paths because the codebase still accepts mutable `Node` inputs
while the new processor architecture is snapshot-first.

### Mutable Compatibility Path

`NodeTypeMatcher.matchesType(Node node, Node targetType, Limits globalLimits)`
is an adapter around the frozen matcher.

It does this:

1. Clone and preprocess the target pattern.
2. Build `CompositeLimits(globalLimits, TargetPatternLimits(targetPattern))`.
3. Clone and preprocess the candidate.
4. Extend and resolve the candidate using those composite limits.
5. Restore intentionally preserved reference and value structure from the
   target-bounded extended candidate.
6. Freeze the candidate with `FrozenNode.fromResolvedNode(...)`.
7. Freeze the target pattern with `FrozenNode.fromResolvedNode(...)`.
8. Delegate to `FrozenTypeMatcher`.

The important property is that candidate extension is bounded by both:

- the caller's global limits, and
- the target pattern's observed paths.

If the caller provides explicit limits, the adapter disables late candidate
reference expansion inside `FrozenTypeMatcher`. That prevents a late fallback
lookup from bypassing the caller's `Limits`.

### Snapshot/Frozen Path

`matchesResolvedType(FrozenNode resolvedNode, FrozenNode resolvedTargetType)` is
the direct path. It assumes the caller already has a resolved immutable view.

This path does not run `extend(...)`, does not rebuild a mutable document, and
does not traverse unobserved mutable state. It compares immutable nodes and only
uses provider lookups for type/reference definitions that are not already
available in the frozen graph.

`matchesResolvedType(ResolvedSnapshot snapshot, String pointer, FrozenNode target)`
uses the snapshot's resolved path index to get the candidate node by pointer.
That avoids walking or materializing the full tree for repeated scoped reads.

## TargetPatternLimits

`TargetPatternLimits` is the key optimization for mutable compatibility calls.
It replaces the older "derive path limits from the whole target node" approach
with a matcher-specific path policy.

It tracks the current path as literal path segments, not by splitting strings.
That means keys such as `a/b` are treated as one property name during matching.

It has these rules:

### Explicit Properties And Items

If the target pattern explicitly contains a property or list item at a path, the
candidate may be extended and merged at that path.

Example:

```yaml
pattern:
  x:
    y: 1
```

For a candidate:

```yaml
x:
  blueId: <large-document>
```

the matcher may fetch `<large-document>`, but only enough to observe `x.y`.
Branches such as `x.audit`, `x.debug`, or `x.largePayload` are not extended
unless the pattern asks for them.

### Pure Reference Pattern Leaves

If the pattern leaf is a pure reference:

```yaml
x:
  blueId: <expected>
```

the matcher treats it as an identity check. It does not fetch the candidate's
referenced document just to compare that leaf.

This makes exact blueId matching O(1).

### Nested Pattern With Reference Leaves

For a pattern:

```yaml
x:
  y: 1
  z:
    blueId: <expected-z>
```

and a candidate:

```yaml
x:
  blueId: <large-x>
```

the matcher fetches `<large-x>` so it can inspect `x.y` and `x.z`, but it does
not fetch `x.z` if `x.z` is a pure reference. It only compares the reference id.

### Lists

For explicit target list items, the matcher can extend the corresponding
candidate item paths.

An explicit `items` pattern requires a list-shaped candidate. A scalar or
object candidate does not match just because the requested list positions are
label-only or otherwise optional. A candidate is considered list-shaped when it
has an `items` payload, an `itemType`, or a declared `List` type. That allows an
empty typed list to match optional item patterns, while still rejecting the
wrong payload kind.

Pure reference list items are identity requirements. A target item like
`{ blueId: X }` must be present at that position and must match exactly; it is
not treated as an optional label-only placeholder.

If a candidate list already exposes enough item positions for the explicit
target list pattern, the matcher does not reconstruct or fetch the first item
just to check whether it is a multi-document bundle. It reconstructs only when
the pattern asks for positions that are not visible in the current list surface.
If the visible first item is already the exact pure-reference item requested by
the first target position, reconstruction is also skipped: that first reference
is an item identity, not a possible hidden bundle for this match.

For schema-only list checks, such as:

```yaml
values:
  type: List
  schema:
    minItems: 2
    maxItems: 2
```

the matcher reconstructs the list surface so cardinality can be checked, but it
does not expand every item reference. Item references are only fetched when the
pattern requires concrete item conformance, such as `itemType`.

### itemType

When the target pattern uses `itemType`, each candidate item must conform to
that item type.

If an item is already the exact requested reference, no fetch is needed. If the
item is a different reference, the matcher fetches that item so it can check
whether the concrete item conforms to the requested item type.

This is intentionally stricter than only checking list metadata. A list with
`itemType: Text` can still match a more constrained item type if every concrete
item conforms to that constrained type.

### Dictionaries

An explicit object-property pattern requires a dictionary-shaped candidate. A
scalar or list candidate does not match an object pattern just because the
requested fields are optional labels. A candidate is dictionary-shaped when it
has object fields, a `keyType`, a `valueType`, or a declared `Dictionary` type.

For dictionary `keyType`, the matcher needs only keys, not values.

For dictionary `valueType`, the matcher checks every value. Exact reference
matches do not require a fetch. Non-exact referenced values are fetched only
when needed for concrete conformance.

Collection metadata also implies payload kind when the candidate node exists:
`itemType` requires a list-shaped candidate, while `keyType` and `valueType`
require a dictionary-shaped candidate.

Schema `minFields` and `maxFields` require the dictionary field surface, but do
not require expanding every value.

### Caller Limits Still Win

The compatibility matcher always composes caller limits with pattern limits.
Both must allow a path before the candidate is extended there.

Example:

```yaml
candidate:
  x:
    blueId: <branch>

pattern:
  x:
    y: 1
```

With caller limits restricted to `/other`, the matcher returns false and does
not fetch `<branch>`.

With caller limits allowing `/x/y`, the matcher may fetch `<branch>` and check
`x.y`.

Caller path limits use RFC 6901 JSON Pointer escaping. A key named `a/b` is
bounded as `/a~1b`, and a key named `c~d` is bounded as `/c~0d`. This keeps
global limits and matcher-internal literal path tracking aligned.

## FrozenTypeMatcher

`FrozenTypeMatcher` performs matching over immutable `FrozenNode` objects.

It keeps three per-matcher caches:

- resolved references by blueId,
- unresolved references by blueId,
- subtype checks by candidate/target type identity,
- match results by candidate/target blueId pair.

The caches are local to the matcher instance, which makes repeated matching in a
processor run cheap without mutating the matched nodes.

### Reference Matching

A target pure reference matches when any of these identities matches:

- the candidate is the same pure reference,
- the candidate's computed frozen blueId is the target blueId,
- the candidate's declared type identity is the target blueId.

This lets these common forms match correctly:

```yaml
x:
  blueId: <A>
```

and:

```yaml
x:
  type:
    blueId: <A>
```

### Declared Type Matching

If the target pattern declares a type, matching succeeds when:

1. the candidate's declared type is the same type or a subtype, or
2. the candidate structurally conforms to the resolved type definition.

Type compatibility is label-neutral. `name` and `description` affect BlueId, but
they do not affect matching, conformance, subtype compatibility, or
structural/type equality. A matching display name is not enough to prove type
equality, and a different display name or description is not enough to disprove
it. The matcher compares a label-neutral structural identity for inline type
definitions.

The second case matters for event/request payloads where a node may not carry a
fully explicit declared type, but its payload still conforms to the requested
contract type.

Core payload kinds are also checked:

- `Text` requires a string value when a value is present,
- `Integer` requires `BigInteger`,
- `Double` accepts numeric `BigDecimal` or `BigInteger`,
- `Boolean` requires boolean,
- `List` requires list payload shape,
- `Dictionary` requires object/property payload shape.

Untyped programmatic scalar payloads can match core primitive patterns when the
payload value has the correct Java representation. This matters for processor
events built directly as `Node` objects rather than parsed through the Blue
preprocessor.

Untyped list and dictionary payloads can match core `List` and `Dictionary`
patterns when their payload shape is unambiguous.

### Schema Matching

The frozen matcher verifies the schema keywords currently supported by the Java
schema verifier:

- `required`
- `allowMultiple`
- `minLength`
- `maxLength`
- `pattern`
- `minimum`
- `maximum`
- `exclusiveMinimum`
- `exclusiveMaximum`
- `multipleOf`
- `minItems`
- `maxItems`
- `uniqueItems`
- `minFields`
- `maxFields`
- `enum`

String length is counted by Unicode code points. Pattern matching currently uses
Java regex semantics, consistent with the current Java verifier. Cross-runtime
ECMA-262 regex compatibility remains a broader spec/runtime issue, not a
matcher-specific behavior.

### Presence Semantics

Missing optional target properties or items are allowed when the target pattern
does not contain meaningful value/payload requirements.

A missing property or item fails when the target has:

- `schema.required: true`, or
- a nested value/payload requirement.

This preserves the old "optional unless value-bearing or required" behavior
without resolving target patterns as standalone documents.

### Lazy Reference Resolution

In direct frozen matching, a candidate pure reference may be lazily resolved if
the target requires structural conformance. This supports direct use of
`FrozenTypeMatcher` on frozen nodes that still contain references.

In mutable compatibility calls with explicit caller limits, lazy candidate
reference resolution is disabled. The candidate must already have been extended
through the limit-controlled path. This is what keeps caller limits authoritative.

Target type definitions may still be resolved, because they are part of the
pattern semantics rather than candidate traversal.

Both successful and failed reference resolutions are cached inside the matcher.
This means repeated checks against the same resolved reference do not refetch it,
and repeated checks against the same missing reference fail without repeatedly
hitting the provider.

## Performance Model

The intended cost is:

```text
O(observed_pattern_paths + needed_reference_fetches + local_schema_checks)
```

It is not:

```text
O(full_candidate_document + full_resolved_type_graph)
```

Important cheap cases:

- Exact pure reference pattern: no provider fetch.
- Nested pattern with one observed branch: fetch only that branch's owner.
- List cardinality check: fetch the list surface, not every referenced item.
- Dictionary key type check: fetch keys, not values.
- Snapshot matching: no provider fetch after snapshot resolution if the needed
  graph is already frozen and interned.

This is why the matcher is suitable for channel and handler matching. Most
handlers observe a small shape, and the matcher avoids resolving unrelated
parts of the event/document.

## Example: Large Candidate, Small Pattern

Candidate:

```yaml
order:
  blueId: <Order>
```

`<Order>` contains:

```yaml
customer:
  blueId: <Customer>
lineItems:
  type: List
  items:
    - blueId: <LineItemOne>
    - blueId: <LineItemTwo>
audit:
  blueId: <HugeAudit>
```

Pattern:

```yaml
order:
  customer:
    id:
      type: Text
      schema:
        pattern: '^C-[0-9]{3}$'
    status:
      blueId: <ActiveStatus>
  lineItems:
    type: List
    schema:
      allowMultiple: true
      minItems: 2
      maxItems: 2
```

Expected behavior:

- fetch `<Order>` once,
- fetch `<Customer>` once,
- do not fetch `<ActiveStatus>` because it is an exact reference check,
- do not fetch line items because only cardinality is checked,
- do not fetch audit because the pattern does not observe it.

The test suite asserts exactly this fetch profile.

## Tests And Coverage

The matcher coverage lives in
`src/test/java/blue/language/utils/NodeTypeMatcherTest.java`.

The suite covers the behavioral axes that matter for production matching:

- basic type, value, and shape matching,
- inherited fixed values from referenced target definitions,
- optional and required schema properties,
- provider-backed required type definitions,
- all frozen schema keywords listed above,
- enum identity by canonical node blueId,
- nested lists and property shapes,
- exact blueId references against node identity and node type identity,
- `name` and `description` being ignored by matcher/type compatibility,
- same-named but structurally different type definitions not being treated as
  identical,
- pure reference pattern leaves without candidate expansion,
- nested patterns that expand only required prefixes,
- caller-provided global limits composing with pattern limits,
- literal property keys containing `/`,
- global path limits using JSON Pointer escaping for `/` and `~` in keys,
- list schema cardinality without item-reference expansion,
- explicit three-item list patterns against list references and inline lists
  with reference edges,
- explicit list patterns rejecting scalar/object candidates even when item
  constraints are optional,
- pure-reference list positions being required when the target pattern names
  them,
- three-position list patterns rejecting a candidate that only provides first
  and last references in the wrong positions,
- extra list items being allowed unless schema cardinality constrains them,
- multi-document first-item bundles being reconstructed only when the target
  pattern asks for hidden positions,
- exact first reference items avoiding bundle-reconstruction fetches,
- explicit object patterns rejecting scalar/list candidates even when child
  field constraints are optional,
- collection metadata rejecting wrong payload kinds,
- dictionary key type without value expansion,
- dictionary value type with only needed non-exact value expansion,
- complex multi-level matching with asserted provider fetch counts,
- complex `itemType` conformance with asserted provider fetch counts,
- list item type enforcement across all items,
- narrower concrete item/value conformance despite broader metadata,
- implicit list and dictionary payloads,
- JSON-array-like event request payloads as implicit lists,
- dictionary key/value type enforcement,
- primitive core type payload mismatch rejection,
- untyped programmatic scalar events matching core primitive patterns,
- no mutation of input `Node` objects,
- direct frozen reference matching caching resolved references,
- direct frozen reference matching caching unresolved reference misses,
- direct frozen matching with no fetches after snapshot resolution,
- pointer-based `ResolvedSnapshot` matching through the path index,
- missing snapshot pointers matching only optional target patterns.

These tests are intentionally not only pass/fail semantic checks. The complex
cases assert fetch counts per blueId, which proves the important performance
property: the matcher fetches only the references required by the observed
pattern and does not accidentally expand unrelated branches.

## Final Local Verification

The current local verification commands are:

```bash
./gradlew test --tests blue.language.utils.NodeTypeMatcherTest
./gradlew test
```

Both pass in the current workspace.

## Boundaries

The matcher is ready for snapshot-backed channel and handler matching, but there
are broader runtime/spec concerns outside this class:

- regex semantics are still Java regex semantics;
- cross-language golden fixtures should eventually verify shared matching,
  hashing, and schema behavior;
- `deriveChannel`, `channelize`, and `isNewerEvent` now have Java processor SPI
  hooks, but still need explicit spec treatment;
- the long-term processor path should pass `ResolvedSnapshot`/`FrozenNode`
  values directly instead of using mutable `Node` adapters.

Within the current Java implementation, the matcher now has the needed local
coverage for correctness, immutability, caller-limit enforcement, path handling,
schema/type semantics, and reference-resolution performance.
