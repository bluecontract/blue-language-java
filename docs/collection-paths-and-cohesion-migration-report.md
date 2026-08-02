# `collectionPaths` and Contracts cohesion migration report

## Decision

The final Contracts amendment is implemented at commit
`f7d03ac3db4a0400db240a35da06813a9c148bae`. The Java conformance runner passes
all 153 Language fixtures and all 154 Contracts fixtures without failures or
skips. The implementation is ready for the mandated final clean verification
sequence; this report does not call it release-ready until that sequence and
the final artifact hashes exist for the report-bearing commit.

The machine-readable companion is
[`reports/modernization/phase-collection-paths-final.json`](../reports/modernization/phase-collection-paths-final.json).

## Evidence vocabulary

This report uses four labels deliberately:

- **Executed** means a command or test ran against the current implementation.
- **Static validation** means the claim comes from source, manifests, or a
  generated inventory without implying that a runtime gate passed.
- **Retained previous evidence** is a prior or externally supplied result kept
  for context, not current Java release-gate evidence.
- **Not executed** means the final evidence has not yet been produced. It is
  never described as passing.

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

The latest root `:test` result contains 2,279 passing tests with no failures or
skips. The focused `EmbeddedScopePlannerTest` result contains 31 passing tests.
The fragmented-processing lane contains 85 passing tests and the examples
module contains 19; those counts are reported separately because the
fragmented lane overlaps root test classes.

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
| Initial projection | 12.724 | 116.312 | 1,175.778 | 5,230.000 |
| Pure-reference target | 13.574 | 115.106 | 1,167.372 | 5,298.246 |
| Pure-reference member headers | 13.231 | 120.317 | 1,175.615 | 4,899.566 |
| Selected member processing | 54,345.084 | 184,845.584 | 2,330,691.333 | 520,840.500 |

The raw file is `/tmp/blue-collection-paths-all-sizes.json`, identity
`sha256:aa314a49138d897722160500535e0683af44345a50b4cf6dd59d247f1b236f75`.

This is characterization, not a statistically powered release regression
decision. There is no equivalent pre-amendment `collectionPaths` benchmark, so
no honest before/after percentage can be calculated. Existing baseline
benchmarks measure different operations and are not substitutes. At 4,096
members, some lanes exercise normative gas-limit or portable-limit rejection;
their latency must not be compared with successful smaller rows.

## Architecture, API, and cohesion

The generated module graph remains valid with seven published modules, zero
module cycles, zero split packages, and zero undeclared edges. The ownership
inventory covers 585 production sources and 370 resources. The package-cycle
architecture test passes with zero cycles.

The JVM API gate reports 327 baseline and 380 current API classes, Java 8 class
major version 52, 337 approved incompatible changes, 300 approved additive
changes, zero unapproved changes, and zero missing approvals. The collection
phase adds the immutable plan view and processor-administration surfaces and
records the collection diagnostics and model accessors.

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

## Artifact evidence and remaining gate

The intermediate JMH JAR is
`sha256:206d1bf224fa511086db707527b9ae61167476a7e5b09113a93f541c0ebce7e8`.
An intermediate source archive and its independently built replica were
byte-identical at
`sha256:1d54cabedfbad2a0d84a8fa9284e5fee395ace4a54c3bbdc71d80aabdc1123d1`.
That source-archive hash is not final: adding this report changes the archive.

The last `semanticBaselineVerify` attempt completed its semantic, conformance,
locality, runtime-trace, ordinary-test, source-replica, reproducibility, and API
work before reaching `fragmentedProcessingReport`. It stopped there because
`build/reports/release-evidence/clean-build.json` was absent: the task was run
without the required preceding clean build. This is an invocation-order gap,
not a claimed pass for the final gate.

The final report-bearing commit must therefore be verified, in order, from a
clean worktree:

```bash
export CI=true
export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"

./gradlew --no-daemon clean build
./gradlew --no-daemon releaseConformanceTest
./gradlew --no-daemon semanticBaselineVerify
./gradlew --no-daemon finalQualityVerify
./gradlew --no-daemon rcVerify
./gradlew --no-daemon jmhClasses
```

Only after that run should final candidate JAR and source-archive hashes be
written into the machine report and the release decision change to green.

## Remaining limitations

- The final ordered clean gate and final report-bearing artifact hashes are not
  yet executed and are not presented as passing.
- A direct collection benchmark regression percentage is unavailable because
  the old implementation had no equivalent benchmark.
- The direct processor-package numeric goals have an evidence-backed exception;
  no public bridges or cycles were introduced merely to reach a file count.
- `DocumentProcessor` is 745 lines after final semantic integration, so the
  650-line facade target is not claimed even though its mechanics are delegated.
- Some 4,096-member benchmark lanes hit the normative gas or portable limit.
- The supplied implementation-baseline specification still calls numerical gas
  weights and portable limits provisional pending calibration.

The task stayed within `blue-language-java`; BEX and Coordination were not
modified, `.cz.toml` was preserved, and the unrelated user-owned `LICENSE`
change was excluded.
