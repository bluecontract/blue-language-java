# Blue Language 1.0 Final Clarifications

The final Language 1.0 package clarifies preprocessing, terminology, and the
identity pipeline without changing SHA-256, Base58, RFC 8785, list folding,
cyclic-set identities, schema rules, or the six canonical core-type BlueIds.

The normative source is
[`blue-language-specification-1.0.md`](../src/main/resources/specifications/blue-language-specification-1.0.md).
This page is an implementation-oriented guide to the revised surface.

## Expansion And Specialization

Expansion and collapse change how much of the same exact node is materialized:

```text
expand   -> reveal verified content; preserve Node BlueId
collapse -> replace verified content with its pure reference; preserve Node BlueId
```

Specialization creates a new node by naming another node as its `type` and
adding a compatible overlay. It normally creates a different Node BlueId.
Opening a referenced type is expansion; creating a more specific instance of
that type is specialization.

## Node BlueId And Content BlueId

A Node BlueId identifies one exact immutable Blue node:

```text
valid exact BlueId Input -> Node BlueId algorithm -> Node BlueId
```

A Source Document may contain aliases, preprocessing configuration, inherited
content, and authoring controls. Its Content BlueId therefore follows the full
semantic pipeline:

```text
Source Document
  -> preprocess
  -> complete resolve
  -> canonicalize
  -> Canonical Identity Input
  -> Node BlueId algorithm
  -> Content BlueId
```

Content BlueId is not another digest format. It is the Node BlueId of the
unique Canonical Identity Input. Directly hashing a Source Document, a
noncanonical Resolved Form, or a Minimized Overlay does not establish its
Content BlueId.

## Canonicalization And Minimization

Canonicalization and minimization both start from resolved meaning but serve
different purposes:

| | Canonicalization | Minimization |
| --- | --- | --- |
| Result | Unique Canonical Identity Input | One convenient Source overlay |
| Direct BlueId input | Yes | Not necessarily |
| May contain `$previous`, `$pos`, `$replace` | No | Yes |
| Part of Content BlueId calculation | Yes | No |

A Minimized Overlay reaches the same Content BlueId only after it is processed
again through preprocessing, complete resolution, canonicalization, and Node
BlueId calculation.

Blue semantic canonicalization determines which exact node is hashed. RFC 8785
canonical JSON serialization determines deterministic bytes for helper values
inside the Node BlueId algorithm. Sorting JSON keys is not a replacement for
semantic canonicalization.

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
41291e52f520870bd3cc0665cdb085df8f10238853531a9e99d4409b6b63c92e
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
