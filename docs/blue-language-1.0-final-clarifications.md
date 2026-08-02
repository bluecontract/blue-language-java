# Blue Language 1.0 Final Clarifications

The final Language 1.0 package clarifies preprocessing, terminology, and the
identity pipeline without changing SHA-256, Base58, RFC 8785, list folding,
cyclic-set identities, schema rules, or the six canonical core-type BlueIds.

The normative source is
[`blue-language-specification-1.0.md`](../blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md).
This page is an implementation-oriented guide to the revised surface.

## Expansion And Specialization

Expansion and collapse change how much of the same exact node is materialized:

```text
expand   -> reveal verified content; preserve BlueId
collapse -> replace verified content with its pure reference; preserve BlueId
```

Specialization creates a new node by naming another node as its `type` and
adding a compatible overlay. It normally creates a different BlueId.
Opening a referenced type is expansion; creating a more specific instance of
that type is specialization.

## One BlueId, Two Calculation Paths

Direct calculation identifies one exact immutable Blue node:

```text
valid exact BlueId Input -> BlueId algorithm -> BlueId
```

A Source Document may contain aliases, preprocessing configuration, inherited
content, and authoring controls. Its BlueId therefore follows the full Source
Document pipeline:

```text
Source Document
  -> preprocess
  -> complete resolve
  -> canonicalize
  -> Canonical Identity Input
  -> BlueId algorithm
  -> BlueId
```

“Content BlueId” is permitted shorthand for the result of this path, not a
second identifier kind or algorithm. Directly hashing a Source Document, a
noncanonical Resolved Form, or a Minimized Overlay does not establish that
Source Document's BlueId.

## Canonicalization And Minimization

Canonicalization and minimization both start from resolved meaning but serve
different purposes:

| | Canonicalization | Minimization |
| --- | --- | --- |
| Result | Unique Canonical Identity Input | One convenient Source overlay |
| Direct BlueId input | Yes | Not necessarily |
| May contain `$previous`, `$pos`, `$replace` | No | Yes |
| Part of Source Document BlueId calculation | Yes | No |

A Minimized Overlay reaches the same BlueId only after it is processed again
through preprocessing, complete resolution, canonicalization, and direct
BlueId calculation.

Blue semantic canonicalization determines which exact node is hashed. RFC 8785
canonical JSON serialization determines deterministic bytes for helper values
inside the BlueId algorithm. Sorting JSON keys is not a replacement for
semantic canonicalization.

For an append-only list, the distinction is visible:

```text
Inherited: [A, B]
Resolved:  [A, B, C]
Minimized: $previous(id([A, B])) + C
Canonical: [A, B, C]
```

The Minimized Overlay retains an authoring shortcut. The Canonical Identity
Input contains the final list payload that is directly hashed.

## Incremental List Identity

Lists use one recursive fold, both for full calculation and incremental append:

```text
L0 = id([])
Ln = fold(Ln-1, id(elementN))
id(prefix + [x]) = fold(id(prefix), id(x))
```

When the exact prefix BlueId is already established, appending one element does
not require the earlier element bodies. Replacing, inserting, or removing an
element at index `i` changes the accumulator at that position, so the suffix
from `i` onward must be folded again. This identity rule does not prescribe how
or where earlier list content is stored.

## Unconstrained Fields

A field declaration with descriptive metadata but no `type` accepts any valid
Blue node when the field is present:

```yaml
payload:
  description: Optional application-defined Blue value.
```

That includes scalar, list, object, specialized, and pure-reference values. An
omitted type does not mean `Dictionary`, and Blue Language 1.0 does not define
an `Any` type. To require a Dictionary-compatible value, declare it explicitly:

```yaml
payload:
  type: Dictionary
```

`schema.required: true` controls presence independently of whether the value is
otherwise unconstrained.

## Final `blue` Directive

Mandatory baseline preprocessing always runs. Omitting `blue` means that the
document has no document-specific directive; it does not disable preprocessing.

The portable directive may be inline:

```yaml
blue:
  imports: ...
  transformations: ...
```

or a pure reference to the same exact directive:

```yaml
blue:
  blueId: <DirectiveBlueId>
```

A configured string alias may resolve to one exact directive BlueId. An
unbound alias fails deterministically. Arbitrary URL contents do not define
portable preprocessing semantics.

The exact processing order is:

1. Parse the Source Document and retain the root directive for planning.
2. Resolve and verify the directive, imports, transformation list, individual
   transformation nodes, and supported processors.
3. Freeze the effective imports and declared transformation order.
4. Remove the root `blue` field.
5. Execute each declared transformation exactly once in list order.
6. Normalize wrappers and list placeholders.
7. Substitute built-in and document aliases only in `type`, `itemType`,
   `keyType`, and `valueType` positions.
8. Infer primitive scalar types and validate the Preprocessed Document.

All required provider content is verified against its requested BlueId before
the first transformation runs. Unsupported transformations, invalid evidence,
nested or transformation-produced `blue`, `blue.profile`, legacy `blue.items`,
and rebinding a built-in alias fail closed.

## Conformance Bindings

The closed Language package contains 153 behavior fixtures, 126 vector
mappings, and no gas fixtures. Its exact identities are:

```text
Language fixture package:
sha256:44465973c5c5a8c1e60712fc7970236015d9500e2e9e3fc904e364552ec74a55

Language core registry package:
sha256:b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e

Language specification SHA-256:
a234b0b42190a7982809781b5efdaa2e5f1ab4b7f8d870fbd1ffe7020cc7e869
```

The fixture-only transformation types under
`src/test/resources/blue-language-1.0/fixtures/preprocessing/registry` test the
generic directive mechanism. They are not canonical Language core types.

Run the Language fixture suite with:

```bash
./gradlew test --tests '*BlueLanguageConformanceFixtureTest'
```

Run the combined exact release gate with:

```bash
./gradlew releaseConformanceTest
```
