# Empty-object sentinel audit

This report records every prompt-mandated production Java search hit, then explicitly narrows the semantic decision set. The complete line-level inventory is `reports/migration/empty-object-sentinel-audit.json`.

## Result

- Production matches: 2338
- Decision-relevant matches: 172
- Context-only broad-search matches: 2166
- Matches in files changed since the RC baseline: 1143
- Matches in unchanged files: 1195

Every entry has a resolved disposition. Context-only matches are retained to prove the broad search ran, but are not presented as semantic evidence and do not claim a covering behavior test. Every decision-relevant entry is selected by an explicit path/symbol rule and distinguishes Source null, exact `{}`, an explicit list placeholder, a temporary fieldless builder/control, host absence, or invalid reserved-position output.

The classification prefix follows the required audit taxonomy: **A** exact empty-object value, **B** temporary builder/metadata/control, **C** Source null before preprocessing, **D** host-conversion absence, and **E** invalid or explicit reserved-position list control.

## Search coverage

| Audit | Hits |
| --- | ---: |
| `empty-shape-and-builder` | 425 |
| `empty-to-null-return` | 0 |
| `list-placeholder` | 25 |
| `property-shape` | 48 |
| `raw-blue-id-access` | 426 |
| `schema-presence` | 1414 |

## Classification totals

| Classification | Hits |
| --- | ---: |
| `A-exact-empty-object-value` | 2 |
| `A-identity-and-structure-preservation` | 13 |
| `A-model-object-payload-presence-bit` | 5 |
| `A-object-field-count` | 3 |
| `A-preserve-recursively-emptied-object` | 1 |
| `A-reconstruction-preserves-present-empty-object` | 5 |
| `A-required-and-field-count-separation` | 16 |
| `A-resolution-payload-kind` | 4 |
| `A-semantic-presence-provenance` | 32 |
| `A-wire-empty-object-presence` | 1 |
| `A/C-source-null-removal-with-empty-object-preservation` | 2 |
| `B-empty-reserved-container-shape` | 10 |
| `B-fieldless-metadata-or-control-container` | 13 |
| `B-structural-fieldlessness-helper` | 2 |
| `B-temporary-fieldless-builder-or-control-container` | 32 |
| `B-temporary-reconstruction-builder` | 6 |
| `C-source-null-before-preprocessing` | 1 |
| `D-host-absence-from-fieldless-source-control` | 2 |
| `E-explicit-list-hole-control` | 17 |
| `E-invalid-fieldless-list-position` | 5 |
| `context-only-not-empty-sentinel-decision` | 2166 |

## Load-bearing decisions

| Area | Decision | Covering tests |
| --- | --- | --- |
| Mapping | A present empty properties map maps as `{}`; only Source-null/fieldless controls may map to host absence. | `NodeToObjectConverterNullHandlingTest`, `ExactEmptyObjectSemanticsTest` |
| Identity | `{}` is accepted as content and remains distinct from `$empty`; raw `getBlueId()` is never treated as verified identity evidence. | `DirectBlueIdCalculatorTest`, `FrozenNodeTest` |
| Resolution | Omitted/Source-null fields inherit; exact `{}` is a present object payload and conflicts with inherited scalar/list payloads. | `BlueLanguageConformanceFixtureTest`, `ExactEmptyObjectSemanticsTest` |
| Schema | Exact `{}` satisfies `required`; `minFields` is the independent non-empty-object constraint. | `ResolvedInstanceSchemaValidationTest`, `ExactEmptyObjectSemanticsTest` |
| Contracts | Explicit/referenced `{}` collection is present with zero members; unavailable evidence is incomplete. | `EmbeddedScopePlannerTest`, `BlueContractsConformanceFixtureTest` |
