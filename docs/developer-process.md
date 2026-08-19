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
- a clean `blue-spec` checkout whose `latest/` directory matches
  `gradle/blue-spec-inputs.lock.json`.

Start with:

```bash
java -version
./gradlew --version
git status --short
./gradlew help
./gradlew verifyBlueSpecInputs \
  -PblueSpecRoot=/absolute/path/to/blue-spec/latest
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
| Canonical Language/Contracts specifications and fixtures | `blue-spec/latest` |
| Java fixture execution and conformance reports | this repository, conformance module |
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
6. For embedded-scope work, change the immutable `EmbeddedScopePlan` producer
   or a named consumer; do not introduce a second ad-hoc traversal of `paths`
   or `collectionPaths`. Audit admission, evidence, entry snapshots, protected
   state, mutation/cut-off, checkpoints, fragmentation, and subscription
   validation together.
7. Run Contracts package cycles, focused tests, runtime trace, and exact
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
2. Add the smallest deterministic fixture with a stable ID/category under the
   canonical `blue-spec/latest` tree.
3. Update its manifest path, byte count, and SHA-256.
4. If the registry, gas manifest, specification, or fixture package changed,
   regenerate every affected package identity and release binding together.
5. Run the isolated fixture test and `releaseConformanceTest`.
6. Inspect the generated per-fixture evidence and exact totals.

Never edit an embedded runtime mirror merely to justify current code. Change
the canonical `blue-spec/latest` input, review its identity consequences, and
then update the explicit Language input lock.

## Change identity-bearing registry nodes

Registry nodes, manifests, named runtime constants, fixture bindings, and the
release manifest form one identity chain. The repository deliberately has no
task that rewrites canonical nodes or approves new identities. Work from the
tracked inputs:

- Language nodes and manifest:
  `blue-language-core/src/main/resources/registry/blue-language-1.0/`;
- Contracts nodes and manifest:
  `blue-contracts-core/src/main/resources/registry/blue-contracts-1.0/`;
- public identity owners:
  `blue-language-model/src/main/java/blue/language/model/wire/BlueLanguageConstants.java`
  and
  `blue-contracts-core/src/main/java/blue/language/processor/registry/RuntimeBlueIds.java`;
- release binding:
  `blue-conformance/src/main/resources/release/blue-language-contracts-embedded-modules-collection-paths-1.0/PACKAGE-MANIFEST.yaml`.

Use this manual, reviewable workflow:

1. Edit the exact tracked `.blue` canonical-input file. Never rewrite unchanged
   registry nodes or normalize them with an unrelated YAML tool.
2. In a focused Given–When–Then registry test, load the exact bytes, call
   `BlueCodec.parseBlueIdInput(..., BlueFormat.YAML)`, and calculate the direct
   BlueId with `DirectBlueIdCalculator`. Review the parsed exact node and
   proposed identity; do not paste an unexplained value into the manifest.
3. Calculate the edited file's byte SHA-256 with `shasum -a 256 <exact-path>`.
   Update only that entry's `blueId` and `sha256` after reviewing both values.
4. Recalculate `packageIdentity` exactly as described by the
   `packageIdentityAlgorithm` block in that manifest. Review the normalized
   manifest input, then update the manifest, named Java owner, fixture manifest,
   and release-manifest binding together. Use `rg -n '<old-identity>'` to find
   every tracked binding; do not use search-and-replace as proof of correctness.
5. Update affected fixtures and expected constants only when the specification
   change requires them, and review the complete identity-chain diff.
6. Run the registry validators, exact conformance, and semantic baseline:

```bash
./gradlew test \
  --tests 'blue.language.registry.BlueCoreTypeRegistryTest' \
  --tests '*BlueRuntimeTypeRegistryTest'
./gradlew releaseConformanceTest semanticBaselineVerify
```

The validators independently recompute file digests, node BlueIds, package
identities, named constants, fixture bindings, and release bindings. A failure
means the chain is incomplete; never capture or weaken a baseline to accept it.

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

<!-- blue-example: examples/src/test/java/blue/language/examples/GraphAndIdentityExamplesTest.java#given-when-then-test -->
```java
    @Test
    void shouldExpandAndCollapseVerifiedProviderContent() {
        // given
        String expectedValue = "provider content";

        // when
        ExpandCollapseProviderExample.Result result =
                ExpandCollapseProviderExample.run();

        // then
        assertEquals(expectedValue, result.getExpanded().getValue());
        assertEquals(result.getBlueId(),
                result.getCollapsed().getBlueId());
        assertTrue(result.getCollapsed().isReferenceOnly());
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
modules. Run one root-owned benchmark with the repository-owned regex filter:

```bash
./gradlew jmh \
  -PblueJmhIncludes='.*DeepGraphPhysicalLocalityBenchmark.*'
```

The deep-graph class deliberately retains three entry points. Run them
independently so the legacy processor path, PROCESS-only platform latency, and
setup-inclusive platform cost are never averaged together:

```bash
# Legacy generic Contracts kernel: 2 body forms x 2 entry modes x 2 cache
# modes x 2 batch modes.
./gradlew --no-daemon jmh \
  -PblueJmhIncludes='.*DeepGraphPhysicalLocalityBenchmark\.processSelectedLeaf.*'

# Public platform-commit boundary: 4 Root representations x 2 cache modes x
# 2 batch modes. Per-invocation fixture setup and close are outside the score.
./gradlew --no-daemon jmh \
  -PblueJmhIncludes='.*DeepGraphPhysicalLocalityBenchmark\.processPlatformCommit$'

# Public platform boundary including fresh scenario, Language/Contracts
# services, provider, plan, Root/Event, optional warming, PROCESS, and close.
./gradlew --no-daemon jmh \
  -PblueJmhIncludes='.*DeepGraphPhysicalLocalityBenchmark\.processPlatformCommitIncludingSetup$'
```

Both public platform methods run all sixteen combinations of:

| Parameter | Values |
| --- | --- |
| `representation` | `INLINE`, `PURE_REFERENCE`, `PARTIAL`, `FRAGMENTED` |
| `cacheMode` | `COLD`, `WARM` |
| `batchMode` | `UNBATCHED`, `BOUNDED_BATCH` |

`processPlatformCommit` times only the public PROCESS call. Its
`PlatformLocalityState` prepares and closes a fresh single-use invocation at
`Level.Invocation`, outside the method score. The method consumes request
count, backend trips, backend bytes, unrelated-provider requests,
selected-body demand, and unselected-body demand. Those counters prevent
benchmark-code elimination and describe locality; none is portable gas or a
semantic input.

`processPlatformCommitIncludingSetup` has parameter-only JMH state. Its timed
method constructs the scenario, Language scope, Contracts service, provider,
plan, Root, and Event; applies the requested warm/cold policy; calls PROCESS;
consumes the same locality counters; and closes the invocation. Use this lane
for complete per-call time and allocation observations. It intentionally
includes setup and close and must not be described as PROCESS-only latency.

The final-quality gate requires the PROCESS-only public platform method, in
addition to the reference-validation and warm-selection smoke benchmarks.
Final-quality JMH smoke uses zero warmup iterations, one 25 ms measurement
iteration, and one fork; it proves that each required benchmark executes, not
that it meets a performance threshold. `benchmarkClasses` still compiles the
setup-inclusive lane, but final quality does not multiply that intentionally
expensive full-fixture allocation campaign into the required release smoke.

For complete per-call allocation observations, build the executable JMH jar
with Gradle and add JMH's GC profiler to the setup-inclusive method:

```bash
./gradlew --no-daemon jmhJar
JMH_JAR="$(find build/libs -maxdepth 1 -type f -name '*-jmh.jar' \
  -print | sort | tail -n 1)"
test -n "$JMH_JAR"
java -jar "$JMH_JAR" \
  '.*DeepGraphPhysicalLocalityBenchmark\.processPlatformCommitIncludingSetup$' \
  -prof gc
```

Use one parameter tuple and deliberately minimal iteration settings only as an
execution smoke while editing the harness:

```bash
java -jar "$JMH_JAR" \
  '.*DeepGraphPhysicalLocalityBenchmark\.processPlatformCommitIncludingSetup$' \
  -p representation=INLINE \
  -p cacheMode=COLD \
  -p batchMode=UNBATCHED \
  -wi 0 -i 1 -f 1 -r 25ms -prof gc -foe true
```

That command proves the entry executes and exposes profiler fields; its single
short iteration is not publishable performance evidence. Omit the three `-p`
restrictions to exercise all sixteen tuples, and use enough warmup, iterations,
and forks for the intended measurement campaign.

Interpret `gc.alloc.rate.norm` as approximate bytes allocated per measured
operation and `gc.alloc.rate` as throughput-dependent allocation per second.
`gc.count` and `gc.time` describe collections observed during the fork; they
are noisy and are not latency or conformance assertions. In the setup-inclusive
lane, `gc.alloc.rate.norm` covers the complete timed lifecycle plus unavoidable
JMH measurement overhead. In the PROCESS-only lane, setup and teardown remain
outside the method score and profiler accounting around invocation hooks may be
harness-dependent. Do not label either value as processor-internal allocation;
report the exact benchmark method and parameter tuple.

There is no retained hidden warm state between measured platform invocations.
Both lanes create and close a fresh scenario, Language scope, Contracts
service, provider, plan, Root, and Event for each single-use call. `WARM`
explicitly primes only permitted provider content, while `COLD` leaves that
invocation's measured provider cold. Preparation and warming are outside the
`processPlatformCommit` score and inside the
`processPlatformCommitIncludingSetup` score; always report the two methods and
warm/cold modes separately.

The collection-path campaign can be run independently at one smoke size with:

```bash
./gradlew :blue-contracts-core:jmh \
  -PblueJmhIncludes='.*EmbeddedCollection.*' \
  -PblueCollectionJmhSize=10
```

Multiple comma-separated regular expressions are accepted. An empty or invalid
expression fails during configuration instead of silently running a different
set. JMH forks fresh benchmark JVMs, performs warmup iterations, then records
measured iterations; its results are performance observations, not semantic
conformance evidence.

In IntelliJ IDEA, importing the repository as a Gradle project is sufficient.
For gutter run actions, install the **JMH Java Microbenchmark Harness** plugin
from *Settings/Preferences → Plugins → Marketplace*, then reload Gradle so
`src/jmh/java` is indexed. IDE runs are convenient while exploring; use the
Gradle commands above for reviewable and release evidence.

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

Generated references are reproducible outputs. Regenerate them with
`./gradlew updateGeneratedDocumentationReferences`, review their diff, and
commit the exact result. Do not edit a generated reference by hand.

## Complete conformance

```bash
./gradlew test
./gradlew releaseConformanceTest
./gradlew runtimeTraceEvidence
./gradlew fragmentedProcessingReport
./gradlew semanticBaselineVerify
```

The Language fixture package contains 153 exact fixtures and the Contracts
package contains 234. Generated fixture coverage is the source for category
subtotals; avoid copying subtotals into authored docs.

`semanticBaselineCapture` is manual and exceptional. Verification never
captures or weakens a baseline automatically.

## Cut an RC

Commit the complete candidate first. Choose one epoch from that commit and use
it for both invocations:

```bash
BLUE_RELEASE_EPOCH="$(git show -s --format=%ct HEAD)"
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew clean build
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew finalQualityVerify rcVerify
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
