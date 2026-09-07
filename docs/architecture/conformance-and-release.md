# Conformance and release architecture

The release gate binds source, specifications, registries, gas manifest,
fixtures, APIs, artifacts, tests, examples, documentation, and benchmark
compilation into one reproducible receipt.

```mermaid
flowchart TD
    Clean["clean build with SOURCE_DATE_EPOCH"] --> Marker["clean-build evidence"]
    Marker --> Verify["releaseVerify / finalQualityVerify"]
    Fixtures["185 Language + 295 Contracts fixtures"] --> Verify
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

Seven normal publications first enter a disposable invocation-owned Maven
repository. The immutable development handoff selects the six runtime modules
used by downstream builds: `blue-language-model`, `blue-language-core`,
`blue-language-mapping`, `blue-language-ipfs`, `blue-contracts-core`, and the
`blue-language-java` aggregate. `blue-conformance` remains part of the normal
publication and source conformance gates, but is not a downstream runtime
dependency.

For those six coordinates the handoff copies only each exact POM and runtime
JAR into a separate non-overwriting repository. Every copied file has a SHA-256
companion, producing twelve manifest records and a closed 26-file repository.
Sources and Javadoc archives remain normal release artifacts; they are not
duplicated in this runtime dependency handoff.

The root `artifact-manifest.json` uses schema
`blue-development-maven-repository/1.0`. It declares `DEVELOPMENT` purpose,
does not claim release readiness, records the actual Java 17 build JVM, and
binds paths and hashes to the exact clean Git commit and tree, Contracts
specification identity, Contracts fixture-package identity, and Contracts
release identity. The manifest deliberately excludes its own byte identity;
the adjacent `artifact-manifest.json.sha256` and downstream receipts bind that
value without self-reference.

A separate consumer build has no `includeBuild`, project substitution or Maven
Local. Gradle resolves the `blue.language` group exclusively from this sealed
repository, with no remote fallback. It enforces Java 8 artifact bytecode and
allowed POM edges, resolves all six coordinates, and exercises the aggregate
parse/write entry points. The 185 Language and 295 Contracts fixture suites
remain mandatory source conformance gates. The disposable publication task may
delete only `build/staging-deploy`; it never deletes or overwrites the
repository already handed to a downstream consumer. From a clean checkout,
create an explicit commit-bound handoff repository with a Java 17 Gradle JVM:

```bash
COMMIT=$(git rev-parse HEAD)
./gradlew assembleImmutableStagedRepository \
  -PreleaseVersion=3.1.0-dev.$COMMIT \
  -PstagedDependencyRepository=/absolute/path/to/blue-development-maven-repository
```

The destination must be outside `build/staging-deploy`. If it already exists,
the task succeeds only when its complete file tree is byte-identical; otherwise
it fails without changing the destination. A downstream build receives that
absolute path and must not invoke the Contracts `clean`, publication or staging
tasks. `publishedArtifactSmoke` applies the same repository property and proves
that all `blue.language` coordinates resolve from the handoff repository only.


A local RC uses the same non-overwriting export task with an explicit
`3.1.0-rc.N` version. It requires the exact clean source commit and Java 17,
and exports all seven published modules with runtime, sources, Javadoc and
POM files. Its manifest uses `blue-local-rc-maven-repository/1.0` and
`stagePurpose: LOCAL_RC`; development exports retain their existing schema
and six-module runtime/POM inventory. The local RC export does not claim
release readiness: conformance, compatibility and downstream product evidence
must still be bound to those exact bytes. No remote publishing task is invoked.

For example, after selecting a fresh RC coordinate:

```bash
./gradlew assembleImmutableStagedRepository \
  -PreleaseVersion=3.1.0-rc.24 \
  -PstagedDependencyRepository=/absolute/path/to/blue-local-rc-maven-repository
```

## Evidence is fail-closed

Reports are generated from declared task outputs, never broad stale build
directory discovery. Missing JUnit XML, skipped fixtures, stale generated
references, dirty source, a changed epoch, or absent artifact evidence makes a
release ineligible. Capturing a semantic or API baseline is a deliberate
manual task and never a dependency of verification.

The contributor commands are in [docs/developer-process.md](../developer-process.md).
