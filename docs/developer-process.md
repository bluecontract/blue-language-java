# Developer process

This is the working agreement for changing the Blue Language Java
distribution. The repository owns Blue Language 1.0 and the runtime-neutral
Blue Contracts and Processor 1.0 kernel. It does not own application-specific
BEX or Coordination behavior.

## Local prerequisites

- Git and a clean, reviewable worktree;
- the checked-in Gradle wrapper;
- a JVM capable of running Gradle (the build provisions/uses a Java 8 toolchain
  for production bytecode, tests, Javadocs, examples, and smoke consumers);
- Python 3 for the tracked binary API inventory scripts.

Start with:

```bash
java -version
./gradlew --version
git status --short
./gradlew help
```

All invocations in one checkout share module `build/` directories. Do not run
report-producing or clean builds concurrently, including from a sibling
composite build. Preserve unrelated user changes and ignored local files.

## Module map

| Module | Change it for |
| --- | --- |
| `blue-language-model` | stable values, wire vocabulary, annotations |
| `blue-language-core` | preprocessing, graph/provider, identity, resolution, snapshots, matching, patching |
| `blue-language-mapping` | Java object mapping and optional discovery |
| `blue-language-ipfs` | CID conversion and HTTP-backed IPFS transport |
| `blue-contracts-core` | generic Contracts API, SPI, gas, processor phases, lifecycle, checkpoints |
| `blue-conformance` | exact Language/Contracts fixture engines and release CLI |
| `blue-language-java` | aggregate composition and thin convenience facade only |
| `examples` | executable programs used by documentation tests |
| `build-logic` | typed Gradle conventions, evidence, documentation, and quality gates |

See [modules and dependencies](architecture/modules-and-dependencies.md) for
the enforced edges.

## Which repository owns this?

| Change | Owner |
| --- | --- |
| Blue values, Source pipeline, BlueId, providers, snapshots | this repository, Language modules |
| Runtime-neutral Channel/Handler processing, gas, lifecycle, checkpoints | this repository, Contracts module |
| Exact Language/Contracts conformance packages | this repository, conformance module |
| BEX expressions, BEX-specific types or authorization | `blue-bex-java` |
| Coordination workflows, protocol/application orchestration | Coordination repository |
| Application storage, catalogs, accounts, APIs, persistence | the consuming application/repository |
| Generic provider adapter for a transport such as IPFS | optional integration module here |

Do not add a dependency on a repository product to Language. Applications
supply content through `NodeProvider` and typed evidence outcomes.

## Change a Language feature

1. Identify the specification section and focused service that owns the rule.
2. Write a characterization test before moving or changing an identity-bearing
   algorithm.
3. Preserve the distinction between direct BlueId input and Source Document
   preparation.
4. Preserve `FOUND`, `NOT_FOUND`, `UNAVAILABLE`, and `INVALID_EVIDENCE` at
   provider boundaries.
5. Keep caller `Node` values unchanged and retained runtime values immutable.
6. Add or update the smallest focused tests, then run the owning module's
   package-cycle and API tasks.
7. If normative behavior changes, update the exact fixture and all bound
   identities together.

Useful commands:

```bash
./gradlew :blue-language-core:compileJava
./gradlew test --tests 'blue.language.identity.*Test'
./gradlew :blue-language-core:verifyJavaPackageCycles
./gradlew :blue-language-core:apiBaselineDiff
```

## Change a generic Contracts feature

1. Place the rule in the exact processor phase: admission, evidence,
   preflight, classification, initialization, delivery, internal drain, final
   validation, subscription validation, or result assembly.
2. Define immutable phase input/output and one deterministic failure boundary.
3. Admit gas before corresponding work; rollback cannot erase admitted gas.
4. Keep feeder/platform state outside the two semantic inputs.
5. Test success, rollback, suspension/unavailability, exact diagnostic, gas
   prefix, Root-only events, and representation parity.
6. Run Contracts package cycles, focused tests, runtime trace, and exact
   Contracts fixtures.

```bash
./gradlew :blue-contracts-core:compileJava
./gradlew test --tests 'blue.language.processor.ProcessorEngine*Test'
./gradlew runtimeTraceEvidence
./gradlew releaseConformanceTest
```

Application-specific parsing or policy does not belong in the generic kernel.

## Add a runtime type SPI implementation

1. Create canonical type evidence derived from the relevant runtime base type.
2. Calculate its direct BlueId; never copy an unexplained literal from a test.
3. Implement the focused Channel, Handler, or marker processor/functions.
4. Register BlueId, canonical type, role, and processor in an immutable runtime
   registry builder.
5. Use only invocation-scoped contexts and typed effect/gas boundaries.
6. Test inline/reference, source/target Channel authority, unavailable and
   invalid evidence, rollback, exact gas, and concurrent reuse.

See [Custom runtime types](guides/custom-runtime-types.md) and the generated
[runtime SPI registry](reference/runtime-spi.md).

## Add or change fixtures

Fixture manifests are closed inventories. Unknown operations, fields,
controls, projections, counters, and assertions fail closed; no fixture may be
skipped.

1. Cite the normative specification rule.
2. Add the smallest deterministic fixture with a stable ID/category.
3. Update its manifest path, byte count, and SHA-256.
4. If the registry, gas manifest, specification, or fixture package changed,
   regenerate every affected package identity and release binding together.
5. Run the isolated fixture test and `releaseConformanceTest`.
6. Inspect the generated per-fixture evidence and exact totals.

Never edit a vendored specification merely to justify current code.

## Change identity-bearing registry nodes

Registry nodes, manifests, and generated runtime constants form one identity
chain. Change them only in a dedicated review:

1. edit canonical registry Source;
2. regenerate canonical node files using the repository-owned generator;
3. verify each declared BlueId from the canonical node;
4. update manifest identity and release binding;
5. update affected fixtures and expected runtime constants;
6. run registry integrity, package identity, exact conformance, and semantic
   baseline verification.

Magic BlueId literals are not an acceptable shortcut. Production and tests
use the registry/runtime constant owner when the identity is specification
defined; scenario-specific exact IDs are derived from canonical nodes.

## Comments and named constants

Public APIs and SPIs explain immutability, thread safety, reuse scope,
ownership/close behavior, deterministic failure versus transient
unavailability, and representation invariance. Internal comments explain
ordering, security, identity, gas, and transaction invariants—why the code
exists, not what a visible statement does.

Stable keys, pointer fragments, runtime type IDs, counter names, diagnostic
tokens, and modes have one named owner. Prefer private constants for local
protocol values and public constants only when callers must author or interpret
the exact value. Ordinary test data does not need a global constant.

## Test style

Every ordinary JUnit test has a readable `should...` name and visible sections:

```java
@Test
void shouldRejectInvalidEvidenceWithoutCommit() {
    // given
    Scenario scenario = invalidEvidenceScenario();

    // when
    DocumentProcessingResult result = scenario.process();

    // then
    assertFalse(result.commits());
    assertEquals(scenario.inputRoot(), result.document());
    assertTrue(result.events().isEmpty());
}
```

One test proves one behavior or one tightly coupled atomic outcome. Tests do
not depend on order, wall time, ambient network, shared mutable global state,
or backend call count unless the latter is explicitly a host-locality test.

## Focused verification

Run the smallest useful task while iterating, then the owning module and full
distribution gates:

```bash
./gradlew compileJava compileTestJava
./gradlew test --tests '<fully-qualified-test-class>'
./gradlew identityDifferentialTest
./gradlew patchSequenceDifferentialTest
./gradlew fragmentedProcessingTest
./gradlew cacheLifecycleTest
./gradlew benchmarkClasses
```

JMH compilation is a release gate. To run the complete benchmark set rather
than just compile it:

```bash
./gradlew jmh
```

Use the module-local `:blue-language-core:jmh` or
`:blue-contracts-core:jmh` task for benchmarks physically owned by those
modules. See the README benchmark section for the repository-owned single-
benchmark filter.

## API baselines

Each supported published module owns `api/public-api.txt`. Generate the current
inventory and review the diff:

```bash
./gradlew :blue-language-core:generatePublicApiInventory
./gradlew :blue-language-core:apiBaselineDiff
./gradlew generatePublicApiUnion
./gradlew verifySemanticApiMigration
```

For an intentional next-major change, classify every descriptor in the
tracked migration ledger, review replacements in the migration guide, copy the
reviewed module inventory to its baseline, and rerun all module/API gates.
Never update a baseline only to silence a failure. Baseline capture is a
manual action and cannot be a dependency of verification.

## Documentation and examples

Every public package has `package-info.java`. Every public API/SPI has useful
Javadoc. Runnable examples live in `:examples`; guides link to those canonical
sources rather than maintaining divergent copies.

```bash
./gradlew :examples:test
./gradlew documentationVerify
```

Generated references are reproducible outputs. Regenerate them with the
repository task, review their diff, and commit the exact result. Do not edit a
generated reference by hand.

## Complete conformance

```bash
./gradlew test
./gradlew releaseConformanceTest
./gradlew runtimeTraceEvidence
./gradlew fragmentedProcessingReport
./gradlew semanticBaselineVerify
```

The Language fixture package contains 153 exact fixtures and the Contracts
package contains 140. Generated fixture coverage is the source for category
subtotals; avoid copying subtotals into authored docs.

`semanticBaselineCapture` is manual and exceptional. Verification never
captures or weakens a baseline automatically.

## Cut an RC

Commit the complete candidate first. Choose one epoch from that commit and use
it for both invocations:

```bash
BLUE_RELEASE_EPOCH="$(git show -s --format=%ct HEAD)"
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew clean build
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew finalQualityVerify
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew rcVerify
```

The first command writes clean-build evidence only after an exclusion-free
successful build. The second invocation rejects a changed commit, source
snapshot, epoch, or prior task exclusion.

Review:

- exact fixture totals and zero skips;
- runtime/gas/locality evidence;
- zero package/module cycles and forbidden dependencies;
- final API ledger and module baselines;
- Javadoc/documentation/example status;
- JAR, source JAR, and complete source ZIP replicas/checksums;
- independent staged Maven smoke;
- aggregate release receipt and final quality report;
- clean Git status and unchanged release automation metadata.

Only then authorize tag/signing/publication. Publication credentials and
external release actions are outside ordinary verification.

## Review checklist

- [ ] The change belongs to the correct repository and module.
- [ ] Language does not depend on Contracts, BEX, Coordination, or a repository product.
- [ ] Public contracts document lifecycle, failure, and representation rules.
- [ ] Stable protocol values use named owners.
- [ ] Changed tests use `should...` and Given–When–Then.
- [ ] Focused and complete tests pass.
- [ ] Fixture/spec/registry identities are exactly bound when changed.
- [ ] API changes are classified and documented.
- [ ] Examples, links, generated references, and Javadocs pass.
- [ ] Clean-build, reproducibility, conformance, smoke, and final quality gates pass.
- [ ] No unrelated or sibling-project file entered the diff.
