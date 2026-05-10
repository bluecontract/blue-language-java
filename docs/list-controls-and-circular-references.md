# List Controls And Circular BlueIds

This document explains the implemented list control forms and circular BlueId
placeholder flow.

## List Merge Policies

A list node can declare:

```yaml
mergePolicy: positional
```

or:

```yaml
mergePolicy: append-only
```

The default is `positional`.

`positional` allows inherited index overlays and appends.

`append-only` preserves the inherited prefix and allows only appends. It rejects
`$pos` overlays and inherited-prefix modification.

## `$previous`

`$previous` anchors an overlay list to a previous/inherited list hash.

Example:

```yaml
type:
  blueId: 6aehfNAxHLC1PHHoDr3tYtFH3RWNbiWdFancJ1bypXEY
items:
  - $previous:
      blueId: BaseListBlueId
  - C
```

The resolver fetches the previous list, validates the BlueId, and appends `C`.
The BlueId calculator uses the `$previous` BlueId as the list hash seed.

Rules:

- `$previous` may appear only as the first list item
- `$previous` must be a single-key control item
- the referenced previous list BlueId must match the inherited list

## `$pos`

`$pos` overlays a specific inherited list index.

Example:

```yaml
type:
  blueId: 6aehfNAxHLC1PHHoDr3tYtFH3RWNbiWdFancJ1bypXEY
items:
  - $previous:
      blueId: BaseListBlueId
  - $pos: 1
    label: updated
  - label: appended
```

Rules:

- duplicate `$pos` values are rejected
- out-of-range `$pos` values are rejected
- `$pos` is not allowed under `mergePolicy: append-only`
- `$pos` items must contain an overlay
- `$pos` is consumed before hashing, so sparse positional controls hash as the
  final normalized list shape

## `$empty`

`$empty: true` is content, not metadata. It is useful as a placeholder that can
later be replaced by a positional overlay.

Example inherited list:

```yaml
items:
  - name: A
  - $empty: true
```

Overlay:

```yaml
items:
  - $previous:
      blueId: BaseListBlueId
  - $pos: 1
    name: B
```

`$empty: false` is not treated as a placeholder; it remains ordinary content.

## Hashing With Controls

List controls are normalized before list hashing:

- `$previous` sets the initial accumulator
- `$pos` items are sorted by position and stripped of `$pos`
- appended items are folded after positioned items
- `$empty: true` remains content

This gives deterministic list BlueIds across equivalent control forms.

## Circular Single-Document References

A single document can reference itself with:

```yaml
name: A
x:
  type:
    blueId: this
```

Provider ingestion computes the BlueId by temporarily replacing `this` with the
ZERO BlueId placeholder:

```text
00000000000000000000000000000000000000000000
```

The provider stores the original content and resolves `this` to the final BlueId
when content is fetched.

Invalid for a single document:

```yaml
blueId: this#0
```

## Circular Multi-Document References

Multi-document cyclic sets use indexed references:

```yaml
- name: A
  next:
    type:
      blueId: this#1
- name: B
  next:
    type:
      blueId: this#0
```

Ingestion flow:

1. Validate that all self references are `this#i`.
2. Validate that every `i` is within the document list.
3. Clone each document and replace all `this#i` references with ZERO BlueId.
4. Compute preliminary BlueIds.
5. Sort documents by preliminary BlueId, with original index as tie-breaker.
6. Rewrite `this#i` references to the sorted positions.
7. Hash the sorted list to get the master BlueId.
8. Store documents under `MASTER#0`, `MASTER#1`, and so on.
9. Resolve `this#i` to final `MASTER#i` on fetch.

This makes the final BlueIds stable across authoring order permutations.

## Reference Locations

`this` references are found and rewritten in:

- `type`
- `itemType`
- `keyType`
- `valueType`
- `blue`
- schema fields, including `schema.enum`
- list items
- object properties

Literal text values like `"this"` are not rewritten.

## Key Tests

- `ListControlFormsTest`
- `BlueIdCalculatorTest`
- `SelfReferenceTest`
