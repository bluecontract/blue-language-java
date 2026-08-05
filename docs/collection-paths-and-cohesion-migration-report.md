# `collectionPaths` and Contracts cohesion migration report

## Decision

The final Contracts amendment is release-verified at commit
`63a9ed6a1a66d47119a80d16ed2ab0beda0d2453`. From a clean detached worktree,
that exact commit passed the ordered clean build, release conformance, semantic
baseline, final quality, RC, and JMH-compilation gates. The Java conformance
runner passes all 153 Language fixtures and all 154 Contracts fixtures without
failures or skips, and final quality reports zero release blockers.

The commit that adds this report is an evidence-only successor. The build,
receipts, and artifact hashes below bind to `63a9ed6`; this report does not
claim that its own successor commit was clean-built.

The machine-readable companion is
[`reports/modernization/phase-collection-paths-final.json`](../reports/modernization/phase-collection-paths-final.json).

## Evidence vocabulary

This report uses four labels deliberately:

- **Executed** means a command or test ran against the current implementation.
- **Static validation** means the claim comes from source, manifests, or a
  generated inventory without implying that a runtime gate passed.
- **Retained previous evidence** is a prior or externally supplied result kept
  for context, not current Java release-gate evidence.
- **Not executed** means a requested evidence item was not run or cannot be
  produced from the available baseline. It is never described as passing.

## Normative package

The implementation is bound to the enum-normalized corrected package:

| Input | Identity |
|---|---|
| Corrected ZIP | `sha256:ba7859cad8eb499fd394d236705d17c48eadb5304526e2ca27a563ee400c5251` |
| Top-level release package | `sha256:0268c0adc8badf0d1ab5cdef4a323117b82253a3695f9125af750437a23014b6` |
| Language specification | `sha256:a234b0b42190a7982809781b5efdaa2e5f1ab4b7f8d870fbd1ffe7020cc7e869` |
| Contracts specification | `sha256:6406153791ed99cf97163726b8d2a272e3f0ca1078dc6c9f69b81855d81e5c81` |
| Language registry | `sha256:b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e` |
| Language fixtures | `sha256:44465973c5c5a8c1e60712fc7970236015d9500e2e9e3fc904e364552ec74a55` |
| Contracts registry | `sha256:46a7744c1cbfa4b00e1d8a99f6ca3f0089ef697de968fee08547894ab02b0ca1` |
| Contracts fixtures | `sha256:16392301655431695df6a7cc142a7e388e426c382bf4e3c5f06ddfafb8efecdc` |
| Contracts gas | `sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5` |
| Process Embedded | `EVJk3e7MLRhtTfMBNyrWYz1pWFXsbDTkPczeTviUuB4e` |

The corrected runtime identities for Document Update, Json Patch Entry,
Scripted External Channel, Contract Execution Result, and Scripted Handler are
recorded in the machine report and in the enum-normalization correction note.
The previous values occur only in that explicit correction note. Previous
package identities remain only in the historical baseline report.

The Language identity algorithm was not weakened. `schema.enum` is a set of
typed scalar identities: order is nonsemantic, duplicates are removed, and the
remaining entries are sorted by canonical typed-scalar identity bytes. Tests
cover authored, reordered, duplicate, and canonical enum forms as well as the
three direct and two transitive corrected registry identities.

## What changed

`ProcessEmbedded` now models two independent declaration lists: `paths` and
`collectionPaths`. A collection declaration points to an object whose direct,
ordinary keys identify stable child occurrences. The container is not itself a
scope merely because it is a collection.

One immutable `EmbeddedScopePlan` is the shared interpretation of both lists.
It records declarations, generated member keys, concrete child paths, and the
origin of every concrete path. The same model feeds discovery, subscription
projection, feeder evidence, processing snapshots, mutation checks,
checkpoints, fragmentation inspection, final indexability, and gas.

The planner provides the protocol boundaries in one place:

- Runtime Pointers are normalized and selector syntax is rejected;
- Language-reserved path segments are rejected;
- member keys use Unicode code-point order and exact RFC 6901 escaping;
- inline objects and verified pure references have equivalent semantics;
- unavailable and invalid provider evidence remain distinct;
- list targets, non-object members, opaque cyclic boundaries, duplicates,
  overlaps, ancestry cycles, and portable-limit overflow fail deterministically;
- identical child BlueIds under two keys remain two independent occurrences.

The entry snapshot freezes the current member set. A member added by event `E`
does not participate in `E`; it receives a new activation interval only after a
successful commit. Removing and re-adding the same key creates a fresh
occurrence and checkpoint lineage. Replacing an active member as a whole is
allowed under the specification, while patching strictly inside child-owned
state is not.

Revision-bound external processing validates the selected branch and the
feeder-indexed participating closure. It does not recursively reopen unrelated
explicit pure-reference branches. This preserves the specification's locality
rule while collection targets and selected members still receive strict
validation.

## Executed semantic evidence

The release-conformance report records:

| Suite | Passed | Failed | Skipped |
|---|---:|---:|---:|
| Language | 153 / 153 | 0 | 0 |
| Contracts behavior | 96 / 96 | 0 | 0 |
| Contracts gas | 58 / 58 | 0 | 0 |
| Combined | 307 / 307 | 0 | 0 |

The main `:test` inventory contains 2,279 passing tests with no failures or
skips. Final quality aggregates the main, focused, specialized, module, and
example suites as 2,729 / 2,729 tests across 246 suites, with zero failures and
zero skips. The focused `EmbeddedScopePlannerTest` result contains 31 passing
tests. The fragmented-processing lane contains 85 passing tests and the
examples module contains 19; those counts are not added to the 2,279 main count
because the specialized inventories overlap it.

Focused coverage includes model immutability, declaration validation, Unicode
and pointer behavior, provider outcomes, gas equality, preflight, subscription
deltas, protected state, collection-member lifecycle, patch boundaries,
snapshot freezing, update routing, runtime-registry identities, enum
normalization, and deep-graph locality. The exact class inventory is in the
machine report.

## Provider demand, locality, and gas

The executed deep-graph matrix contains 32 representation/provider variants.
No forbidden BlueId was requested or physically loaded. The fragmented matrix
contains eight primary/replay variants with the same zero-forbidden-demand
result. In the Root-only reference event, exactly the three required BlueIds
were requested and loaded; none of the forbidden embedded branches was opened.

Collection-specific tests prove that enumeration does not demand transitive
descendants or executable bodies, one pure-reference collection target costs
one exact target demand, pure-reference members cost one header demand per
member, and selected processing does not demand an unselected handler body.

All 58 Contracts gas fixtures pass. Exact two-member inline and referenced
collection traces pass, and inline, pure-reference-target, and
pure-reference-member representations have equal logical gas. The separate
runtime-work report passes eight scenarios, including a 4,096-entry ordered
trace and exact gas-exhaustion prefix retention.

## Benchmark characterization

An all-size quick JMH campaign ran ten benchmark methods at 10, 100, 1,000,
and 4,096 members: 40 results, OpenJDK 26.0.1, one 100 ms warmup iteration, one
100 ms measurement iteration, one fork, and the GC profiler.

Selected average times in microseconds per operation were:

| Lane | 10 | 100 | 1,000 | 4,096 |
|---|---:|---:|---:|---:|
| Initial projection | 14.155 | 116.426 | 1,215.054 | 5,352.042 |
| Pure-reference target | 13.751 | 116.798 | 1,207.243 | 5,521.648 |
| Pure-reference member headers | 13.342 | 130.548 | 1,216.271 | 5,328.696 |
| Selected member processing | 61,531.938 | 178,910.500 | 1,878,786.583 | 510,560.416 |

The raw file is `/tmp/blue-collection-paths-all-sizes-final.json`, identity
`sha256:74903aa443f33ca7e316f1cf806d580eb539c4c9a0c362656afbf8fbb4c11958`.

This is characterization, not a statistically powered release regression
decision. All 40 `scoreError` values are `NaN` because the quick campaign used
one fork and one measurement iteration; the finite scores are point
characterizations, not statistically bounded estimates. There is no equivalent
pre-amendment `collectionPaths` benchmark, so no honest before/after percentage
can be calculated. Existing baseline benchmarks measure different operations
and are not substitutes. At 4,096 members, some lanes exercise normative
gas-limit or portable-limit rejection; their latency must not be compared with
successful smaller rows.

The separate final required-smoke gate passed at the verified commit:
`ProcessingSelectionCacheBenchmark.processWarmSameNode` measured 627.929 ops/s
and `ReferenceBlueIdValidationBenchmark.resolveDeepValidReferenceDocument`
measured 18.370 ops/s. `jmhClasses` also passed after the full RC gate. These
required-smoke results do not manufacture a pre-amendment collection benchmark.

## Architecture, API, and cohesion

The generated module graph remains valid with seven published modules, zero
module cycles, zero split packages, and zero undeclared edges. The ownership
inventory covers 585 production sources and 370 resources. The package-cycle
architecture test passes with zero cycles.

The JVM API gate reports 327 baseline and 380 current API classes, Java 8 class
major version 52, 337 approved incompatible changes, 300 approved additive
changes, zero unapproved changes, and zero missing approvals. The collection
phase adds the immutable plan view and processor-administration surfaces and
records the collection diagnostics and model accessors. Final quality's broader
published-API union contains 387 public types; this is a different inventory
from the binary baseline comparison, not a conflicting test count.

One compatibility caveat is worth making explicit: the descriptor of
`SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY` did not change, but
Java clients may have inlined the former `public static final String`. Such
clients must recompile to observe the corrected package identity.

The façade and conformance monoliths became materially smaller:

| Class or surface | Before | After |
|---|---:|---:|
| `DocumentProcessor` | 1,043 lines | 745 lines |
| `ContractsFixtureHarness` | 4,378 lines | 180 lines |
| `BlueConformanceSuiteRunner` | 3,319 lines | 186 lines |
| Direct processor package sources | 232 | 244 |
| Direct public processor types | 89 | 91 |

The last two numbers intentionally do not claim the aspirational goals of 110
files and 70 public types. Seventy-six surviving baseline public types already
exceed the public-type goal. The package-private dependency graph has a
148-type main component with dependencies in both directions through the stable
root API. Moving it now would require public technical bridges, a package
cycle, or incompatible public API moves. The evidence-backed decision is to
preserve visibility and the acyclic graph. The detailed classification and
exception are in `api/processor-type-classification-1.0.json` and
`reports/modernization/phase-06-processor-cohesion.json`.

The facade extraction brought `DocumentProcessor` to 641 lines at commit
`a7adcb3`, but final collection lifecycle integration raised the report-bearing
source to 745 lines. That is below the ordinary 800-line class ceiling but not
the requested 650-line facade target. Processing mechanics remain delegated to
focused collaborators; this report records the numerical miss instead of
compressing comments or creating forwarding types merely to satisfy a count.

## Final verification and artifact evidence

The ordered gate ran from clean detached worktree
`/tmp/blue-language-final-parent.jw5uk2/worktree` with `CI=true` and
`SOURCE_DATE_EPOCH=1785685523`, the timestamp of verified commit `63a9ed6`.
The environment put `/usr/bin/python3` first on `PATH` because the discovered
Anaconda `python3` executable was broken. That workaround changed only tool
discovery; it did not change source or generated semantics.

| Order | Gate | Result | Recorded work |
|---:|---|---|---:|
| 1 | `clean build` | Passed in 3m49s | 134 tasks |
| 2 | `releaseConformanceTest` | Passed, 307 / 307 fixtures | — |
| 3 | `semanticBaselineVerify` | Passed, 337 approved incompatible, 300 additive, 0 unapproved | — |
| 4 | `finalQualityVerify` | Passed, 0 blockers | 197 tasks |
| 5 | `rcVerify` | Passed | 188 tasks |
| 6 | `jmhClasses` | Passed | — |

The clean marker binds 1,557 source files to source-input identity
`sha256:0ae0cef00f7de69733b179fe75226249b84c67c4d3af398ebdaec8631c2f2a20`.
Final quality reports Java 8 bytecode, zero module/package cycles, zero split
packages, zero undeclared module edges, valid documentation and Javadocs,
compiled and tested examples, a green required benchmark smoke, and zero
release blockers.

The seven release JARs are:

| Module | JAR SHA-256 |
|---|---|
| `blue-conformance` | `7c45ff6bcd31266bd54b73dbf3d1f4ead81d603249ee8c1afd9d817504704fcf` |
| `blue-contracts-core` | `ec45224ffee3e0c47246869d89c002657c9d1f348af8c553be3b6c0874bf7bae` |
| `blue-language-core` | `a7d3c72640ab8ac5832feaad576cd1a56457cb87eaf07323fe04a88ae5730740` |
| `blue-language-ipfs` | `bec7355f39a109c4fe6dfc5f9970232dc0a75cd8e5b4ab055abc311314d24c8e` |
| `blue-language-java` | `0de1584be094515ddd27938819464dc024a993c7eb06e4145cac129ad5bbfed0` |
| `blue-language-mapping` | `d9141d5c611bde7eb6a21bce3dc4bc0df7d8167f013eeaef2a365dd0a6af329b` |
| `blue-language-model` | `ef55be8331147442b858474add4782489d993568effe30202a9c4a8b014d5bd8` |

For the aggregate artifact, the sources JAR is
`sha256:68d1069c56f754c2e76f208a4126a967533cc91059062c2e86b70e098f33a518`
and the Javadoc JAR is
`sha256:f6c714c5d06d4b718ab909b36eb541927a182e96d203c6961ea5bdfe512e6597`.
The 3,309,181-byte source release is
`sha256:e79bb7a12b4de7c2e0d1e68daf5f426ea1fefab3609d97e0a786508ec2b059fa`;
its independently generated replica is byte-identical and its 1,557 entries
have normalized timestamps.

The aggregate receipt identity is
`sha256:747df2d486c07259dbc04ed05b00106a48593e787f25f52a44fd51dd108bee1e`.
It verifies 37 staged artifact files, 272 test-result files, all fixture
reports, all seven API inventories, and the release receipts. The staged Maven
repository validates all seven `blue.language:*:3.1.0-rc.18` coordinates, and
the independent published-artifact smoke resolves all seven successfully.

These hashes and receipts are final for verified source commit `63a9ed6`.
Because this report is committed afterward, its evidence-only successor has a
different source tree and is deliberately not described as clean-built.

## Remaining limitations

- The evidence-only successor containing this report was not clean-built; all
  release claims and artifact hashes intentionally bind to verified commit
  `63a9ed6a1a66d47119a80d16ed2ab0beda0d2453`.
- A direct collection benchmark regression percentage is unavailable because
  the old implementation had no equivalent benchmark.
- The direct processor-package numeric goals have an evidence-backed exception;
  no public bridges or cycles were introduced merely to reach a file count.
- `DocumentProcessor` is 745 lines after final semantic integration, so the
  650-line facade target is not claimed even though its mechanics are delegated.
- Some 4,096-member benchmark lanes hit the normative gas or portable limit.
- Every quick-campaign `scoreError` is `NaN` under the one-fork,
  one-measurement setup, so its finite scores are not statistically bounded.
- The supplied implementation-baseline specification still calls numerical gas
  weights and portable limits provisional pending calibration.

The task stayed within `blue-language-java`; BEX and Coordination were not
modified, `.cz.toml` was preserved, and the unrelated user-owned `LICENSE`
change was excluded.

## Successor candidate: indexed invocation and pure-reference Phase B

This section records the scope of the later platform-invocation candidate; it
does not retroactively change the executed evidence, commit, counts, or hashes
above. Those values remain bound only to `63a9ed6` until a clean successor
release receipt says otherwise.

The successor adds one immutable public `PlatformProcessInvocation` and an
additive `BlueContracts.processForPlatformCommit(...)` overload. A host can
prepare a plan through the public indexed evaluator, supply a strict
request-local provider, and process that exact plan without invoking the
construction-time plan deriver. Root and event remain the only semantic inputs.
The supplied plan remains evidence: its Root, event, revision, order, registry,
activation, dependency, completeness, and canonical-delivery bindings are
independently checked by Contracts.

Language supplies a strict invocation scope with fresh provider-derived state.
There is no implicit construction-provider, bootstrap-provider, or shared-cache
fallback, and closing the scope does not close the caller's provider. The
Phase-B classifier now materializes the admitted selected scope/header chain
before pruning it, preserving selected dependency headers and processor state
while leaving unrelated siblings and executable bodies cold. This corrects the
pure-reference ordering defect without removing the dependency-drift check or
introducing a whole-Root scan.

Only a clean successor release receipt can bind an exact commit to the complete
Language/Contracts fixture totals, platform-plan forgery matrix, provider
outcome and concurrent-isolation tests, inline/pure-reference/partial/fragmented
matrix, Java 8 and API gates, zero-cycle architecture report, reproducible
artifact hashes, and passing `finalQualityVerify` and `rcVerify`. This authored
section is a reviewed change inventory rather than executed release evidence;
the generated receipt remains authoritative whenever that certification runs.
