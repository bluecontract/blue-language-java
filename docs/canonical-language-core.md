# Canonical Language Core And BlueId

This document explains the strict canonical language core in the final
implementation: `schema`, reference-only `blueId`, payload-kind exclusivity,
deterministic numbers, list hashing, and canonical provider ingestion.

## Canonical Node Shape

A canonical Blue node can contain metadata plus exactly one payload kind:

- scalar `value`
- list `items`
- object fields

The parser and serializer reject nodes that mix payload kinds.

Valid scalar node:

```yaml
type:
  blueId: E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq
value: 42
```

Valid object node:

```yaml
name: Product
price:
  amount: 10
  currency: USD
```

Valid list node:

```yaml
type:
  blueId: 8DSFoWG9MqRSUhStqoPLrwVQiYByRh18NWbDEarN8MKF
items:
  - A
  - B
```

Invalid because it mixes object fields and `items`:

```yaml
name: Bad
items:
  - A
extra: value
```

## Reference-Only BlueId

In canonical documents, a node with `blueId` is a reference and nothing else.

Valid:

```yaml
type:
  blueId: GoRz2f9bGLjn4ZvbKgHuLYiBoYcgiJy7pV5xRiKQTiMp
```

Invalid:

```yaml
type:
  blueId: GoRz2f9bGLjn4ZvbKgHuLYiBoYcgiJy7pV5xRiKQTiMp
  name: Price
```

This removes the old ambiguity where an object could both assert identity and
carry sibling content. Computed hashes live in `FrozenNode`, `ResolvedSnapshot`,
and sidecar indexes, not in serialized canonical content as `blueId`.

## Schema Replaces Constraints

The canonical field is `schema`.

```yaml
name: Positive Score
type: Integer
schema:
  minimum: 0
```

Input with `constraints` is rejected:

```yaml
name: Invalid Constraints
constraints:
  minLength: 2
```

Use `schema` directly. This keeps canonical ingestion strict and avoids a
second schema vocabulary in source documents.

## Deterministic Numbers

BlueId hashing uses RFC 8785 canonical JSON input.

Integer behavior:

- integers within JavaScript safe integer range are kept as JSON numbers
- integers outside `[-9007199254740991, 9007199254740991]` are represented as
  strings in hash input
- this prevents cross-language loss of precision

Double behavior:

- values explicitly typed as `Double` are canonicalized through binary64-compatible
  decimal text
- non-finite values are rejected
- equivalent authored forms such as `1`, `1.0`, and computed binary64 results
  converge when they are typed as `Double`

Example:

```yaml
x:
  type: Double
  value: 1
```

If processor code divides that value by `3`, the stored value is the canonical
binary64 result of `1.0 / 3.0`, not an arbitrary decimal expansion.

## BlueId Hashing Rules

Implemented core rules:

- object keys are sorted before hashing
- nulls and empty maps are removed
- empty lists are preserved
- pure reference nodes return their referenced BlueId directly
- lists use explicit list/list-cons domains
- child nodes are represented by child BlueIds
- scalar values are canonical JSON values

Important distinctions:

```yaml
items: []
```

does not hash like a missing field.

```yaml
items:
  - A
```

does not hash like scalar `A`.

```yaml
items:
  - items:
      - A
      - B
  - C
```

does not hash like:

```yaml
items:
  - A
  - B
  - C
```

## One BlueId, Two Calculation Paths

Both paths return the same BlueId representation and use the same direct
algorithm:

```java
Blue blue = new Blue(provider);

String direct = blue.calculateBlueId(exactBlueIdInput);
String fromSource = blue.calculateSourceDocumentBlueId(sourceDocument);
```

`blue` is a preprocessing directive, not semantic content. It is not valid
BlueId input.

`calculateBlueId(node)` hashes a node that is already valid BlueId input. It
rejects nodes containing `blue` because silently dropping the directive would
hash unprocessed authored content. It also rejects `blueId` with sibling
content; resolved runtime metadata must be minimized before canonical hashing.

`calculateSourceDocumentBlueId(sourceDocument)` runs:

```text
preprocess -> complete resolve -> canonicalize -> direct BlueId
```

Use the Source Document path for authored input. Use direct calculation only
when the node is already valid exact BlueId Input. “Content BlueId” may describe
the result of the Source Document path, but it is not a second identifier kind.

The BlueId algorithm removes nulls and empty maps at any depth. Empty lists are
preserved. If a list element normalizes to an empty map, that element is removed.
Use `$empty: true` when a placeholder must remain as content.

A leading `$previous` list-control item is a list accumulator seed in the BlueId
algorithm. The hash algorithm itself does not verify the seed against an
inherited prefix. Resolution validates that the inherited list prefix hashes to
`$previous.blueId`; if it does not, resolution fails.

## Provider Ingestion

Provider ingestion parses canonical fields strictly before hashing. `constraints`
input is rejected; provider content must use `schema` directly.

Provider ingestion does not yet resolve and semantically minimize arbitrary
authoring input by default. If that becomes the intended language rule, provider
ingestion should switch to the semantic canonicalization pipeline.

## Key Tests

- `NodeDeserializerTest`
- `NodeToMapListOrValueTest`
- `BlueIdCalculatorTest`
- `FrozenNodeTest`
- `ProviderCanonicalIngestionTest`
- `SemanticCanonicalizationTest`
