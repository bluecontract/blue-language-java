# Canonical Language Core And BlueId

This document explains the strict canonical language core implemented in this
branch: `schema`, reference-only `blueId`, payload-kind exclusivity, deterministic
numbers, list hashing, and canonical provider ingestion.

## Canonical Node Shape

A canonical Blue node can contain metadata plus exactly one payload kind:

- scalar `value`
- list `items`
- object fields

The parser and serializer reject nodes that mix payload kinds.

Valid scalar node:

```yaml
type:
  blueId: 5WNMiV9Knz63B4dVY5JtMyh3FB4FSGqv7ceScvuapdE1
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
  blueId: 6aehfNAxHLC1PHHoDr3tYtFH3RWNbiWdFancJ1bypXEY
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

Legacy input with `constraints` is migrated:

```yaml
name: Legacy
constraints:
  minLength: 2
```

After parsing, this is represented as:

```yaml
name: Legacy
schema:
  minLength: 2
```

If both `schema` and `constraints` are present, parsing fails because the two
sources of truth would be ambiguous.

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

## Structural And Semantic BlueId APIs

There are now two explicit identity paths:

```java
Blue blue = new Blue(provider);

String structural = blue.calculateBlueId(node);
String semantic = blue.calculateSemanticBlueId(node);
```

`calculateBlueId(node)` hashes the node as provided.

`calculateSemanticBlueId(node)` runs:

```text
preprocess -> resolve -> minimize -> hash canonical
```

Use semantic BlueId when authoring noise should not matter. Use structural
BlueId when the node is already known to be canonical and you want direct Merkle
hashing.

## Provider Ingestion

Provider ingestion now parses and migrates legacy canonical fields before
hashing. For example, `constraints` is migrated to `schema`, and the provider
stores/fetches the migrated content under the corrected hash.

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
