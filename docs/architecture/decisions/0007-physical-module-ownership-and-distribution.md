# ADR 0007: Physical module ownership and distribution

- Status: accepted
- Date: 2026-08-01
- Decision owners: Blue Language Java maintainers
- Physical extraction: `1e9985f6bd8fa0bc93811814c99d565935133d25`
- Package-cycle preparation: `1f799962ef715c9488ae5bde77338993a114022a`
- Machine-readable ownership: [`architecture/module-ownership-1.0.json`](../../../architecture/module-ownership-1.0.json)
- Dependency ownership: [`architecture/dependency-ownership-1.0.json`](../../../architecture/dependency-ownership-1.0.json)
- API relocation evidence: [`api/module-api-relocation-ledger-1.0.json`](../../../api/module-api-relocation-ledger-1.0.json)

## Context

The former single project compiled Language semantics, Contracts processing,
optional mapping and IPFS integrations, conformance fixtures, and the public
compatibility facade from one root source tree. Package boundaries could not
prove artifact boundaries, optional dependencies leaked into the one runtime,
and a package cycle could become a module cycle during extraction.

Phase 04 physically moved production code and resources into conventional
module-local `src/main/java` and `src/main/resources` roots. The checked-in
evidence must describe that resulting repository, rather than the obsolete
pre-move plan. At this decision there are exactly 521 production Java files and
356 production resources across the seven published projects.

## Decision

The direct project graph is:

| Project | Direct project dependencies |
| --- | --- |
| `:blue-language-model` | none |
| `:blue-language-core` | model |
| `:blue-language-mapping` | model, core |
| `:blue-language-ipfs` | core |
| `:blue-contracts-core` | model, core, mapping |
| `:blue-conformance` | model, core, mapping, Contracts |
| `:blue-language-java` | model, core, mapping, IPFS, Contracts |
| `:examples` | aggregate, conformance |
| `:build-logic` | none |

This graph is acyclic. Dependencies are direct when the module's compiled
source or public metadata needs the target; transitive availability is not
used to hide a source-level edge.

### Aggregate and conformance are separate

`blue-language-java` remains the one-dependency compatibility artifact for the
supported runtime. It re-exports model, Language core, Contracts, mapping, and
IPFS, and owns only the thin `blue.language.Blue` facade. It deliberately does
not depend on `blue-conformance`. Fixture runners, fixture packages, validators,
release reports, and the conformance CLI are tooling and must be selected
explicitly. This keeps normal runtime consumers free of fixture payloads and
prevents conformance from becoming a semantic dependency.

### Ownership means the physical path

Each production file has exactly one owner: the project whose conventional
source or resource root contains it. In the ownership manifest, `currentPath`
and `targetPath` are therefore identical. Root `src/main/**` is empty and
module build scripts may not redirect their source sets back to it. Published
projects may not split an exact Java package.

The ownership manifest is generated from the physical module roots in sorted
path order. It records the source and resource counts and SHA-256 identities of
the newline-delimited paths. Regeneration fails if a public API inventory names
a type without a physical source owner.

### Public API relocation evidence

The API relocation ledger is generated from the compiled public inventories of
the published modules and the 1.0 aggregate baseline. Each current public type
records its physical source, owning module and published coordinate. The four
review classifications remain:

- `compatible-relocation-through-aggregate-facade`;
- `internal-type-removed-from-public-surface`;
- `new-supported-api-spi`;
- `intentional-next-major-break`.

Package changes in `1f79996` are explicit history, not accidental additions.
This includes `NodeProviderOutcome` moving to `blue.language.api`, snapshot
resolution/cache types moving to `blue.language.merge`, runtime access moving
to `blue.language.runtime`, and the immutable patch API moving to
`blue.language.snapshot`. Nested public types inherit the same recorded move.
Earlier 1.0 names are also associated by their unique binary simple name so a
supported relocation remains distinguishable from a genuinely new SPI.

The classification does not require an internal implementation type to remain
public. It records the reviewed migration intent while module-local API
baselines enforce the final binary surface.

### External dependency ownership

All root, module, and included-build Gradle scripts are discovered on every
generation. Typed convention sources that add dependencies programmatically
are discovered as well. Every directly declared external library and every
versioned plugin has one reviewed owner, version, target configuration,
rationale, and a sorted list of its actual declaration sites.

- Jackson databind belongs to the model wire boundary.
- YAML and RFC 8785 implementations belong to Language core.
- classpath discovery belongs to mapping and is not a semantic input;
- Apache HTTP belongs only to IPFS;
- fixture-manifest SnakeYAML belongs only to conformance;
- JReleaser, JMH, ASM, Mockito, and build-logic test dependencies belong to the
  included build or root verification scope.

The report separately records direct runtime allowlists per published module.
HTTP, reflection scanning, and fixture-manifest YAML are forbidden in Language
core. A new or removed declaration makes generation and the architecture gate
fail until ownership is reviewed.

### Build shape is an architecture boundary

The root build applies orchestration only and remains at most 200 lines. Every
module build remains at most 150 lines, no checked build script may reach 1,000
lines, and modules use their conventional local roots. Domain logic for
evidence, archive inspection, publication, and conformance orchestration lives
in tested typed build logic.

## Enforcement

`PhaseFourModuleOwnershipArchitectureTest` verifies:

- exact, unique, deterministic ownership of all 521 sources and 356 resources;
- physical target existence, declared package accuracy, and no split packages;
- the exact acyclic Gradle graph and absence of undeclared production-import
  edges;
- coverage and validity of current public top-level types and every explicit
  `1f79996` relocation;
- complete dependency/plugin discovery, unique ownership, and per-module
  runtime allowlists;
- root/module build-size budgets and absence of root-source redirection.

The generator and test both discover current module and included-build scripts.
They are rerun after build-logic changes so checked evidence cannot describe an
earlier build shape.

## Consequences

Minimal Language consumers no longer receive Contracts, mapping, IPFS, or
conformance by accident. Aggregate users retain the supported runtime entry
points without fixture tooling. Direct module edges and external dependencies
are reviewable machine-readable facts. Adding or moving a source, resource,
public type, project dependency, external component, plugin, or build script
requires deterministic evidence regeneration and an architecture review.

No Language or Contracts semantic rule changes as a result of this decision.
