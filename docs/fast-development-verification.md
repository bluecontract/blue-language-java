# Fast development verification

Choose the verification scope deliberately. A focused or development PASS is
not a release PASS. These commands use the existing assertions and packaged
fixtures; they do not regenerate identities, reduce gas checks, or replace
the complete release gates.

## Commands and scope

Run commands from your own worktree. Keep the Gradle daemon and dependency
cache, and avoid `clean` in the edit/test loop. Use one Gradle worker and one
test fork while another checkout is performing release verification.

```bash
# One existing regression, with an explicit selected/executed inventory.
./gradlew focusedTest --tests 'blue.language.processor.SelectedExecutableBodyCapabilityTest.shouldKeepReferencedAuthoredBodySeparateFromInheritedFields' --max-workers=1 --no-parallel

# List the complete manifest inventory, without executing fixture assertions.
./gradlew listLanguageConformanceCases --max-workers=1 --no-parallel

# Execute one dynamic fixture, or list/select a small prefix family.
./gradlew focusedLanguageConformanceTest -PblueFixtureCases=B_empty_list --max-workers=1 --no-parallel
./gradlew listLanguageConformanceCases -PblueFixtureCases='B_empty*' --max-workers=1 --no-parallel
./gradlew focusedLanguageConformanceTest -PblueFixtureCases='B_empty_list,B_empty_placeholder' --max-workers=1 --no-parallel

# Focused components, the common development gate, and development integration.
./gradlew languageComponentTest --max-workers=1 --no-parallel
./gradlew contractsComponentTest --max-workers=1 --no-parallel
./gradlew fastVerify --max-workers=1 --no-parallel
./gradlew developmentIntegrationVerify --max-workers=1 --no-parallel
```

The preflight uses the existing Python aggregate-manifest checker and requires
Python 3 with PyYAML; its JCS implementation is vendored. If the default
`python3` is unsuitable, append `-PbluePythonExecutable=/path/to/python3`.
On the measurement machine `/usr/local/bin/python3` worked, while the default
Conda interpreter was killed with exit 137 even outside the sandbox. Changing
the client PATH did not fix executable resolution in the reused daemon;
the explicit interpreter property did. Its default remains `python3`.

| Level | Task | Exact scope |
| --- | --- | --- |
| Focused regression | `focusedTest --tests ...` | Root test-source regression or Java test pattern; a nonempty selector is required. For module-owned tests, existing `:module:test --tests ...` remains available. |
| Focused dynamic regression | `focusedLanguageConformanceTest` | Only selected Language dynamic cases; uses the original fixture executor with complete suite context. |
| Component | `languageComponentTest` | `blue.language.identity.*Test`, `NodePathTest`, `ParsedJsonPointerTest`, and `LanguageFixtureSelectionTest`; 134 tests in this revision. |
| Component | `contractsComponentTest` | `RuntimeWorkSessionTest`, `PreparedPatchSequenceTest`, and `ImmutableJsonPatchTest`; 54 tests. |
| Development integration | `fastVerify` | Drift preflight plus both component tasks; 188 tests. |
| Broader development integration | `developmentIntegrationVerify` | `fastVerify`, existing `cacheLifecycleTest` (95), `patchSequenceDifferentialTest` (36), and `:examples:test` (20). |
| Full release | Existing release checklist | Complete tests, fixtures, APIs, artifacts, consumers, clean-source/build evidence, documentation and required benchmark smoke. Follow [developer process](developer-process.md); `rcVerify` and `finalQualityVerify` retain their original contracts. |

Component tasks are explicit regression families, not complete verification of
everything in the named production module. Include a regression for the actual
change, and use the full release procedure when preparing a release.

## Selection, reports, and failure behavior

`blueFixtureCases` accepts comma-separated exact IDs or a terminal `*` prefix
wildcard. Every token must match. Empty tokens, unknown tokens, unsupported
wildcards, and duplicate/empty fixture inventories fail. Overlapping selections
are deduplicated and retain manifest order. Omitting the property on the focused
task fails; omitting it on the listing task lists all 184 cases. Listing says
`LIST ONLY` and is not a test result.

The old `BlueLanguageConformanceFixtureTest` factory eagerly called the complete
runner before returning dynamic tests. It now validates the same complete
metadata, selects entries, and defers `runFixture(entry, completeInventory)` to
each dynamic executable. The full factory without a selector still contains
all 184 cases. Production fixture runners and assertions are unchanged.

`F_all_language_vectors_pass` is itself a suite assertion. It intentionally
executes its declared `B_`/`R_` prerequisites through the original executor.
Listing and selection print those prerequisite IDs explicitly. Its one JUnit
case is not one semantic operation; on failure the prerequisite list is the
planned inventory, not a claim that every prerequisite completed. Choose a
leaf fixture for the narrowest feedback.

The four new test tasks put XML, HTML, binary results and `inventory.txt` under:

```text
build/development-verification/<task>/{xml,html,binary,inventory.txt}
```

The inventory records task/filter/case selection, actual test JVM, executed
test IDs and outcomes, skipped IDs, and totals. A nonempty executed inventory
and no skipped selected tests are required. Unknown tests, assertion failures,
disabled-only selections, classes without tests and empty source sets fail.
Early selector rejection removes that task's prior HTML/XML/binary results and
writes a `REJECTED` inventory, preventing an old report from appearing current.

These directories are outside existing release evidence globs. Integration
reuses the existing cache/patch/example tasks, which retain their established
report locations; they do not become release evidence without the existing
complete release checks. Do not combine unrelated attempts or add their counts
to construct a release PASS.

An `UP-TO-DATE` task reused its prior results; it did not execute tests again.
To force only a chosen test task while reusing compilation, use its `--rerun`
option, for example:

```bash
./gradlew focusedLanguageConformanceTest --rerun -PblueFixtureCases=B_empty_list --max-workers=1 --no-parallel
```

## Task graph and preserved release coverage

Primary implementations are in
`build-logic/src/main/java/blue/buildlogic/{RootOrchestrationPlugin,DocumentationQualityOrchestration,ConformancePackagePlugin,ReleaseEvidencePlugin,FinalQualityOrchestration,SemanticEvidenceOrchestration}.java`;
the clean-build contract is in `support/CleanBuildEvidence.java`. New wiring is
in `DevelopmentVerificationOrchestration.java` and `tasks/DevelopmentTest.java`.

Measured dry-run counts below count main-build nodes, including no-source and
lifecycle nodes, but exclude included-build build-logic startup tasks. A dry run
establishes the graph only; it does not verify the work.

| Entry | Main-build nodes | Dependency explanation |
| --- | ---: | --- |
| Selected root unit / `:test` | 35 | Root test classes, compatibility facade, seven modules' required compile/resource/JAR closure, then the chosen Test task. Selection does not launch module tests, release generation, or exporters. New focused tasks have the same compile closure. |
| Selected Language dynamic case | 35 | Same root test runtime closure, then only `BlueLanguageConformanceFixtureTest.shouldPassAllBlueLanguage10Fixtures` with the case property; metadata validation remains complete. |
| Unqualified `test` | 70 | Gradle's recursive task selector chooses root **and** all module/example `test` tasks. Use `:test` for only the root task. |
| `:blue-conformance:releaseConformanceTest` | 20 | Conformance main classes/resources plus model/core/mapping/Contracts dependencies; Java 8 `ReleaseConformanceCli` executes 184 Language + 295 Contracts fixtures. Root `releaseConformanceTest` is an alias. |
| `generateDocumentationReferences` | 43 | API union and module structure inventories **plus full release conformance**, followed by reference generation. Documentation analysis consumes references; `documentationVerify` also runs all Javadocs and `:examples:check`. Updating references additionally copies generated output into tracked docs. |
| `assembleImmutableStagedRepository` | 87 | Prepare staging, publish seven modules, then export the non-overwriting six-artifact development handoff with commit/tree/specification bindings. Conformance is excluded from that six-artifact handoff. |
| `publishedArtifactSmoke` | 89 | Immutable repository assembly → `verifyPublishedRepository` → nested `GradleBuild` in `smoke-tests/published`, running `cleanPublishedSmoke` with dependency refresh. The nested build cleans, verifies coordinates and executes the consumer. |
| `rcVerify` | 258 | Alias of `releaseVerify`; retains all gates described below. |
| `finalQualityVerify` | 267 | Release verification, final quality analysis/enforcement, documentation verification, all Javadocs, examples and three required JMH smoke benchmarks. |
| `fastVerify` | 41 | `developmentPreflight` → both component tests and their compile closure. Preflight checks aggregate manifest bindings, build-script shape and exact specification mirror bytes. Compilation is explicitly ordered after preflight when it is in the graph. |
| `developmentIntegrationVerify` | 51 | Fast graph plus existing cache/patch-differential and example test graphs. |

`releaseVerify` still requires module checks, module API baselines and API union,
module/package structure checks, archive inspection and independent replicas,
published consumer verification, complete conformance, runtime trace and
fragmented-processing evidence, source-release archive/checksum verification,
semantic baseline and release evidence verification, the existing audit and
manifest checks, and aggregate receipt verification. The receipt still requires
root `test` plus `identityDifferentialTest`, `patchSequenceDifferentialTest`,
`memoryIntegrationTest`, `cacheLifecycleTest`, and `fragmentedProcessingTest`.
The Contracts 295-fixture inventory still comprises 126 behavior, 71 gas and
98 closure cases. No release dependency, fixture ID, gas schedule, registry or
package identity was changed by this work.

Package regeneration is a separate expensive path. `generateConformancePackageIdentity`
only hashes resources/test resources/fixtures; it does not regenerate packages.
The Python `regenerate_package.py` entry stages a disposable package, performs
evolution and closure generation/refinement, builds manifests, optionally runs
full-lifecycle generation, rebinds managed-transition receipts, then rebuilds
manifests. The optional bridge invokes
`:blue-conformance:exportFullLifecycleFixtures --no-daemon --no-parallel`;
receipt rebinding invokes `:blue-conformance:exportManagedTransitionReceiptFixtures`
with the same flags. Their required source/package/output properties point at
the explicit candidates. The receipt exporter executes all 80 non-limit closure
fixtures and retains all 98 entries, including 18 limit microfixtures. Even a
read-only regeneration check can invoke Gradle/exporters; it is not a cheap
manifest check. See the [package tool instructions](../blue-conformance/src/main/tools/README.md).

Real overlap remains deliberately visible: root tests and the five existing
focused tasks execute overlapping classes in separate JVM tasks; report tests
can invoke complete fixture runners again; package A/B generation starts nested
Gradle/exporters. Gradle deduplicates multiple dependencies on the **same** task
within one invocation, so references and release gates sharing one conformance
task are not two executions of that task. This change avoids these paths in
development rather than deleting release assertions.

## Incrementality, isolation and cache limits

New tests retain Gradle's runtime-classpath, compiled test, resource, test-filter,
JVM/toolchain and JVM-property inputs. Selection and the requirement for a
selector are also explicit inputs; reports are declared outputs. Packaged
fixtures, provider/registry/profile content and fixed random seeds travel in the
runtime classpath or source inputs. No commit-only semantic key was introduced.

Arbitrary selected tests may access files, services or environment outside that
classpath. Declare such inputs on their owning task or force `--rerun` when they
change; ordinary file/classpath up-to-date checks cannot establish freshness of
external state. New development Test tasks deliberately refuse build-cache
restoration even when `--build-cache` is requested. Their ordinary local
up-to-date reuse remains available. No global cache setting was enabled.

Configuration-cache storage and replay were exercised with actual execution of
the selected Language case, not just a no-op. Both produced the one-case inventory.
This establishes that narrow path only; it does not establish compatibility of
the full release lifecycle, Python/exporter tasks, or every external-state test.
Use the normal commands above as the default.

The full clean-build attestation is unchanged: it binds actual clean/build
execution, source snapshot, commit, epoch and task exclusions; a later build
invalidates the previous marker. Neither an up-to-date computation nor a focused
result issues a new clean-commit attestation.

Keep one invocation per worktree. New test-report directories are disjoint, but
existing semantic tests may share static state, caches and report paths. Identity
tests customize shared mappers, and existing locality tests write shared evidence.
No duration-balanced shards or extra concurrent forks were introduced. New tasks
use one reusable 512 MiB test JVM and disable JUnit parallelism; the documented
one-worker/no-parallel commands bound simultaneous test heaps to 512 MiB, in
addition to the existing Gradle daemon/compiler memory. Do not stop another
task's daemon, remove shared caches, or compete with a long release corpus.

## Measurement and validation record

Implemented from `41a29dae6893776ebb3fb927e56dbde8d5fe0939` on
`codex/campaign-fast-feedback` in `/private/tmp/codex-campaign-fast-feedback`.
Initial `pwd`, Git top-level, HEAD, status and worktree inventory established a
clean separate worktree at that base. The original
`/Users/piotr/data/blue-language-java` was only inspected; its HEAD and clean
status were rechecked unchanged. Temporary logs are under
`build/fast-feedback-measurements/`; the implementation commit is recorded in
the task handoff.

Machine: macOS 26.6.2, aarch64; Gradle 9.6.0 launcher/daemon OpenJDK 26.0.1.
Actual semantic test executable:
`/Library/Java/JavaVirtualMachines/jdk1.8.0_201.jdk/Contents/Home/bin/java`
(Java 8u201, x86_64). Build-logic and synthetic TestKit fixture tests used Java 17.
Changing `JAVA_HOME` is not evidence of changing the semantic test toolchain.

All real commands used one worker and no parallelism on the same machine while
Task A had priority. Cold means fresh local outputs/history with existing shared
tools/dependencies, not erased OS/dependency caches. Wall times include Gradle;
JUnit case times exclude configuration, compilation, JVM startup and factory
metadata work. `/usr/bin/time` user/system figures cover the client process tree,
not the already-running daemon's or all test-worker CPU; no total CPU comparison
is claimed.

| Measurement | Wall seconds | Executed versus reused |
| --- | ---: | --- |
| Before changes: selected model regression, fresh worktree | 10.23 | 1 test; 7 actionable tasks executed, including initial build-logic/module compilation. This is a cold-start reference, not a comparison to a different test family. |
| Existing `:test --tests ...` / new `focusedTest --tests ...` | 1.65 / 1.86 | Same unchanged `BlueIdsTest.shouldRecognizePotentialBlueIds` on warm compilation; each executed 1 test and reused 23 tasks. The existing unit path was already narrow; new inventory/guards do not imply a unit-runtime speedup. |
| Language dynamic selection before change | Not rerun | Source inspection: factory eagerly evaluated all 184 entries plus suite prerequisites before returning nodes. A new full baseline was avoided while Task A ran. |
| New `B_empty_list`, first task execution | 3.34 | 1 selected / 1 executed; 1 task executed, 23 reused. |
| Same case, warm `--rerun` | 3.18 | Same 1 case freshly executed; compilation reused. |
| Same case, no-op | 0.56 | 24 tasks up-to-date; no new test execution. |
| List complete Language inventory | 2.51 | 184 listed; zero semantic fixture executions. |
| First fast gate | 14.29 | 134 Language + 54 Contracts tests executed; includes build-tooling recompilation. |
| Fast no-op | 1.29 | Both test tasks reused; mirror and aggregate checks executed, script-shape output reused. |
| Development integration | 27.94 | 151 tests executed (95 cache, 36 patch, 20 examples); 188 fast-gate tests reused. |
| Final fast gate plus selected fixture | 14.34 | 188 component tests and 1 dynamic case executed after report-handling hardening. |
| Configuration cache, store / replay with `--rerun` | 3.65 / 3.21 | One dynamic case executed in each invocation; no claim of material speedup. |
| Final tooling tests and plugin validation | 17.14 | 24 tests passed; Gradle plugin validation passed. |

The synthetic offline TestKit matrix uses the same task implementation with a
fresh fixture project and explicitly enabled local build cache. Its wall times
are not production performance claims:

| Input transition | Wall seconds | Compile main / compile test / resources / test |
| --- | ---: | --- |
| Fresh project outputs | 0.701 | Executed / executed / executed / executed |
| No change | 0.138 | Reused / reused / reused / reused |
| Changed test assertion bytecode | 0.463 | Reused / executed / reused / executed |
| Changed production method body, same public signature | 0.434 | Executed / reused / reused / executed |
| Changed loaded resource content | 0.408 | Reused / reused / executed / executed |
| Deleted development outputs only | 0.417 | Reused / reused / reused / executed, never `FROM-CACHE` |

The matrix also verifies unchanged release XML/HTML/clean-build sentinels;
missing, empty, unknown, skipped-only and zero-source selections; original
assertion failure propagation; and removal of stale reports after rejection.
Eight fixture-selector tests verify the complete 184-ID inventory, ordering,
deferred execution, complete suite context and failure propagation. Actual
unknown and empty dynamic selectors exited nonzero in separate recorded
attempts, with no unselected semantic fixture execution. Graph and mirror tests
verify release dependency preservation, output isolation, worker bounds and
drift rejection without rewriting the source or mirror.

Historical reports available before implementation contained only 30 build-logic
suites / 100 cases (September 4, 05:17:13–05:17:41 UTC), totaling 28.228 seconds
of testcase elapsed time. The largest were archive replica verification (10.254),
JUnit convention configuration (2.295), module inventory derivation (2.134),
Javadoc-warning rejection (1.648) and typed-task registration (1.431). These
reports had no source/attempt binding sufficient to assert a current release
PASS. Root semantic results were still in progress, so no completed historical
semantic timing ranking or full-suite speedup is claimed.

In the first fast run, Language testcase elapsed totaled 6.685 seconds and
Contracts 1.478, versus 14.29 seconds build wall time. The slowest Language case
was `Base58Sha256ProviderTest.shouldMatchLegacyStringPipelineWithOptimizedWriterForGeneratedIdentityCorpus`
at 1.856 seconds; fixture inventory/prerequisite validation took 1.418 seconds.
A selected `B_empty_list` assertion took about 0.03 seconds, so its remaining
cost is largely JVM/fixture metadata/build overhead. The small family goals are
met without production runtime changes. Full release duplication, exporter work,
and the expensive closure corpus remain separate owning-layer work.

Not run here: full root/module semantic suites, complete release conformance,
package A/B regeneration, clean builds, executed `rcVerify`/`finalQualityVerify`,
or immutable artifact/consumer release verification. Their graphs were inspected
without executing them, to preserve Task A's priority and frozen evidence. The
existing development consumer/example tests are not a substitute for those
release checks.
# Campaign B2: source and candidate boundaries

Additional case selection (all development XML/HTML/counter reports are isolated):

```bash
./gradlew listContractsConformanceCases
./gradlew focusedContractsConformanceTest -PblueContractsCases=c-init-01,c-gas-01
./gradlew focusedContractsConformanceTest '-PblueContractsCases=c-evo-*'
./gradlew :blue-conformance:listClosureConformanceCases
./gradlew :blue-conformance:focusedClosureConformanceTest -PblueClosureCases=fl-adm-01-root-patch-event
./gradlew listFragmentedProcessingCases
./gradlew focusedFragmentedProcessingTest -PblueFragmentedCases=D
```

Selectors are exact manifest IDs or terminal prefix wildcards; every comma-separated
token must match. Missing/empty selectors fail selected execution tasks. Listing with
no selector lists the full inventory without running it. Contracts validates the full
295-entry metadata and gas-counter coverage before scheduling only selected entries.
Each selected entry includes its authored execution variants. Gas coverage is a metadata
prerequisite, not evidence that unselected microfixtures executed. The original full
Contracts suite and its assertions remain unchanged on the default/release path.

Closure selection runs the existing independent public-facade executor and all its
result assertions. Its complete default inventory remains 98 cases, including the
18 limit micros (independent limit checks, not closure invocations). Lifecycle cases
use their original `fl-adm-*` identities and declared lifecycle queues. Development
closure JSON is under the task's own `evidence/closure.json`, removed before an attempt.
An optional `-PblueClosurePackageRoot=/absolute/package` is fingerprinted as an input;
development tasks do not inherit an ambient external package environment variable.

Fragmented cases are A–H from the existing matrix. A is the explicit inline baseline
prerequisite for each selected comparison. Each variant executes primary and replay;
one JUnit method therefore does **not** mean one invocation. Selection occurs before
scenario creation and expensive execution. The full default still compares all eight
variants. Provider requests/bytes are retained in task-local locality evidence.

`developmentPreflight` and `fastVerify` now check authoritative source inventories,
digests, nested package identities, YAML manifest syntax, Markdown title/fence/conflict
structure and primary/mirror agreement. Markdown examples contain pseudocode and are
not all parsed as executable YAML. These gates allow only specification hash/size
bindings in an otherwise valid aggregate to be pending. They print
`PENDING_GENERATED_BINDINGS` and never certify a candidate, consumer tuple or release.

Run `./gradlew developmentPreflight -PbluePythonExecutable=/usr/local/bin/python3`
for source work. Run `./gradlew candidatePreflight
-PbluePythonExecutable=/usr/local/bin/python3` for strict cheap candidate consistency.
The existing `verifyAggregateReleaseManifest`, `releaseVerify`, `rcVerify` and
`finalQualityVerify` remain strict and complete; candidate preflight does not replace
their tests. No command regenerates or captures golden artifacts implicitly.

The source allowance was exercised with the actual Language specification and mirror
from Task D commit `cc5e06ef9dbe782bf4d667368db1a9954ecb86df` in a disposable copy:
source verification succeeded and reported the exact pending digest; strict aggregate
verification rejected that same copy. Registry/fixture drift remains an error.
