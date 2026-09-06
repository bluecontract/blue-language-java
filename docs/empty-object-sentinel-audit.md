# Empty-object sentinel audit

This report records every prompt-mandated production Java search hit, then explicitly narrows the semantic decision set. The complete line-level inventory is `reports/migration/empty-object-sentinel-audit.json`.

## Result

- Production matches: 2102
- Decision-relevant matches: 256
- Context-only broad-search matches: 1846
- Matches in files changed since the RC baseline: 1203
- Matches in unchanged files: 899

Every entry has a resolved disposition. Context-only matches are retained to prove the broad searches ran, but are not presented as semantic evidence and do not claim a covering behavior test. Every decision-relevant entry is selected by an explicit path/symbol rule and distinguishes Source null, exact `{}`, an explicit list placeholder, a temporary fieldless builder/control, host absence, invalid reserved-position output, or nullable identity-header metadata.

The classification prefix follows the required audit taxonomy: **A** exact empty-object value, **B** temporary builder/metadata/control, **C** Source null before preprocessing, **D** host-conversion absence, and **E** invalid or explicit reserved-position list control.

## Search coverage

| Audit | Hits |
| --- | ---: |
| `empty-shape-and-builder` | 457 |
| `empty-to-null-return` | 0 |
| `list-placeholder` | 25 |
| `property-shape` | 52 |
| `raw-blue-id-access` | 98 |
| `schema-presence` | 1470 |

## Classification totals

| Classification | Hits |
| --- | ---: |
| `A-exact-empty-object-comparison` | 1 |
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
| `A/C-source-null-removal-with-empty-object-preservation` | 3 |
| `B-conformance-identity-shape-control` | 9 |
| `B-contracts-identity-evidence-boundary` | 12 |
| `B-empty-reserved-container-shape` | 9 |
| `B-fieldless-metadata-or-control-container` | 10 |
| `B-identity-metadata-versus-value-payload` | 31 |
| `B-nullable-identity-wire-header` | 10 |
| `B-structural-fieldlessness-helper` | 3 |
| `B-temporary-fieldless-builder-or-control-container` | 21 |
| `B-temporary-reconstruction-builder` | 6 |
| `B-unverified-identity-claim-boundary` | 36 |
| `C-source-null-before-preprocessing` | 1 |
| `D-host-absence-from-fieldless-source-control` | 1 |
| `E-explicit-list-hole-control` | 16 |
| `E-invalid-fieldless-list-position` | 5 |
| `context-only-not-empty-sentinel-decision` | 1846 |

## Load-bearing decisions

| Area | Decision | Covering tests |
| --- | --- | --- |
| Mapping | A present empty properties map maps as `{}`; only Source-null/fieldless controls may map to host absence. | `NodeToObjectConverterNullHandlingTest`, `ExactEmptyObjectSemanticsTest` |
| Identity | `{}` is accepted as content and remains distinct from `$empty`; every same-line raw BlueId nullability predicate is classified as identity-header/evidence handling, separate from value presence. General identity consumers are covered by the old-to-new identity inventory. | `BlueIdReferenceValidatorDepthTest`, `CanonicalIdentityProvenanceFailClosedTest`, `ExactEmptyObjectSemanticsTest` |
| Resolution | Omitted/Source-null fields inherit; exact `{}` is a present object payload and conflicts with inherited scalar/list payloads. | `BlueLanguageConformanceFixtureTest`, `ExactEmptyObjectSemanticsTest` |
| Schema | Exact `{}` satisfies `required`; `minFields` is the independent non-empty-object constraint. | `ResolvedInstanceSchemaValidationTest`, `ExactEmptyObjectSemanticsTest` |
| Contracts | Explicit/referenced `{}` collection is present with zero members; unavailable evidence is incomplete. | `EmbeddedScopePlannerTest`, `BlueContractsConformanceFixtureTest` |
