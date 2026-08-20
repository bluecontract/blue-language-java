# Conformance and release architecture

The release gate binds source, specifications, registries, gas manifest,
fixtures, APIs, artifacts, tests, examples, documentation, and benchmark
compilation into one reproducible receipt.

```mermaid
flowchart TD
    Clean["clean build with SOURCE_DATE_EPOCH"] --> Marker["clean-build evidence"]
    Marker --> Verify["releaseVerify / finalQualityVerify"]
    Fixtures["153 Language + 234 Contracts fixtures"] --> Verify
    Tests["unit, integration, locality, gas traces"] --> Verify
    API["module API baselines + migration ledger"] --> Verify
    Archives["JAR/source replicas + source ZIP"] --> Verify
    Docs["Javadocs, links, examples, generated references"] --> Verify
    Smoke["independent staged Maven consumer"] --> Verify
    Verify --> Receipt["machine-readable receipt and quality report"]
```

## Exact package binding

The conformance artifact contains the released specifications, registry/gas
packages, and exact fixture manifests. Every manifest entry binds path, bytes,
and SHA-256. Release conformance fails on missing, extra, changed, or skipped
fixtures. Runtime modules do not contain fixture harnesses.

## Reproducibility

The first invocation runs an exclusion-free `clean build` and records the Git
commit, complete source identity, task paths, and normalized
`SOURCE_DATE_EPOCH`. A second invocation verifies the marker against the same
source and epoch. JARs and source archives use normalized timestamps and stable
entry order; independent replicas must be byte-identical. The complete source
release ZIP is checked against an exact entry manifest and checksum.

## Published behavior

Seven publications are staged into one invocation-owned Maven repository. A
separate consumer build has no `includeBuild` or project substitution. It
resolves module coordinates, enforces Java 8 bytecode and allowed POM edges,
and exercises the aggregate and conformance entry points.

## Evidence is fail-closed

Reports are generated from declared task outputs, never broad stale build
directory discovery. Missing JUnit XML, skipped fixtures, stale generated
references, dirty source, a changed epoch, or absent artifact evidence makes a
release ineligible. Capturing a semantic or API baseline is a deliberate
manual task and never a dependency of verification.

The contributor commands are in [docs/developer-process.md](../developer-process.md).
