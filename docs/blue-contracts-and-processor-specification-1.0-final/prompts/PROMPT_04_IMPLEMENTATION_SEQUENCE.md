# Codex Prompt 04 — Execute the Two-Repository Contracts 1.0 Rollout

Use this prompt as the orchestration entry point. It does not replace Prompts
00–03; it fixes their order, repository boundary, artifact handoff, and commit
discipline.

## Authority and validation levels

The package containing this prompt is immutable implementation input. Validate
it from its own root before editing production code. `PACKAGE_VALID` and
`SEMANTIC_REFERENCE_VALID` are package/reference-model claims only.
`IMPLEMENTATION_CONFORMANT` remains false until the released Java artifacts
execute the complete generated corpus.

If implementation reveals a genuine normative contradiction, stop and report
it. Do not weaken a fixture, proof, gas charge, diagnostic, or ordinary
regression to force green.

## Repository 1 — `blue-language-java`

Work only in the current `blue-language-java` checkout while executing these
steps. Preserve unrelated dirty files.

1. Run Prompt 00 and record package identities, source baselines, dirty state,
   Java 8/API/package-cycle gates, and the existing ordinary conformance result.
   All main and test sources in `:blue-language-core`, `:blue-contracts-core`,
   and `:blue-conformance` remain Java 8 source sets.
2. In `:blue-language-core`, add a Java 8 immutable complete cyclic-finalization
   result around the existing algorithm. Keep the old API delegating unchanged
   and the proof verifier independent. Verify every packaged oracle.
3. In `:blue-contracts-core`, add the immutable closure evidence model,
   normalized `DocumentId`, dual occurrence/binding identities, deterministic
   SCC index/generations, closed queued work variants, synchronous patch
   continuation frames, and the shared closure gas/limit model. Keep
   `closureIdentity` state-only: graph/documents/bindings/components/public
   Roots. Cause and frozen direct deliveries belong to `invocationIdentity`.
4. Add `PROCESS_CLOSURE` and `ADMIT_CLOSURE` through a separate multi-document
   invocation state and one closed `ClosureInvocationInput`; do not accept
   duplicate event/cause/route/policy/environment arguments. Return the closed
   `Complete | NeedsResources` attempt union. Preserve ordinary one-document
   `PROCESS` and its existing fail-closed cyclic-member boundary.
5. Implement immediate whole-component finalization, incremental acyclic
   containing-spine identity work, dynamic form/merge/split/expansion without
   replay, frozen lineage sets with latest-state resolution, historical
   evidence attempts, whole-component initialized-marker batches, Phase-B
   checkpoint comparison, post-quiescence checkpoint settlement (including
   C-CLO-33 replacement-before-cleanup), closed rejection owners/finalization
   ordinals, rollback, complete result evidence, and the platform commit
   companion. Historical resource supply preserves the logical invocation
   identity only while state/operation/cause/candidate/route/policies and
   environment revalidate unchanged.
6. In `:blue-conformance`, import/bind the exact package assets and execute every
   ordinary and closure fixture through the real implementation. Derive all
   counts from manifests. Migrate the stale 154/307 gates in
   `BuildLogicConstants`, `BlueReleaseConformanceReport`, and
   `BlueContractsFixturePackage` to 207 Contracts / 360 combined fixtures without
   creating a second count authority. Generate the required implementation
   reports.
7. Run the full module, release-conformance, Java 8, API baseline, module-shape,
   package-cycle, and publication gates. From a clean commit, stage/publish the
   exact `blue-contracts-core` and conformance artifacts and record coordinates
   plus JAR SHA-256 values.

Recommended semantic commit sequence:

```text
test(language): characterize cyclic set finalization
feat(language): expose complete cyclic set finalization
feat(contracts): model affected closure evidence
feat(contracts): partition affected closure graph
feat(contracts): queue exact closure work occurrences
feat(contracts): meter affected closure work
test(contracts): characterize dynamic cycle finalization
feat(contracts): process affected closures
feat(contracts): finalize cyclic components tentatively
feat(contracts): reclassify dynamic closure components
feat(contracts): admit affected closures
feat(contracts): return atomic closure commit evidence
feat(conformance): execute affected closure fixtures
docs(contracts): report closure conformance
```

Each commit must contain one coherent behavior and its focused tests, and every
committed revision must be green. A characterization-only commit is valid only
when it passes against existing behavior; otherwise keep the red test local and
co-commit it with the first passing implementation. Never amend/squash earlier
functional commits during this rollout.

## Artifact handoff

Do not proceed to Repository 2 until Repository 1 is green from clean commits
and the exact artifact is available through a staged/published non-SNAPSHOT
coordinate produced with
`-PreleaseVersion=<next-non-SNAPSHOT-candidate>`.
Record the package, spec, registry, fixture, oracle, gas, source commit, artifact
coordinate, and JAR identities in the handoff note. A local included build may
assist development, but cannot substitute for final artifact evidence.

## Repository 2 — `../blue-contract-java`

Change working directory to exactly `../blue-contract-java` and execute Prompt
02 there. This is the canonical `blue-coordination-java` project despite its
filesystem directory name. Do not edit Repository 1 during this phase.

1. Establish the Java 17 and Java 21 baseline and update every pinned Contracts
   coordinate, lock, preflight, consumer-POM assertion, and artifact hash. Add a
   separate `blueLanguagePublishedRepository`, exclusive to group
   `blue.language`, for the staged artifact; the existing Repository/BEX-only
   publication repository is not a Language artifact source.
2. Characterize/fix the Coordination `DocumentId` boundary or add a lossless
   adapter to the Contracts value.
3. Write engine tests for finite dynamic A/B, exact M1–M4 visibility,
   deterministic loop rollback, merge/split, admission, frozen edges, A10/A5,
   local gas, and late outer failure. Keep red tests local and co-commit them
   with their first passing implementation.
4. Replace DAG-only planning with the exact component index and occurrence
   lineage/binding model. Freeze minimal affected-closure evidence from exact
   content and the current route snapshot.
5. Replace independent per-document processing with one Contracts closure call
   and one storage-neutral atomic publication/reconciliation boundary. At that
   boundary independently recompute state-only input and staged-output closure
   identities; never persist an invocation-scoped cause/route hash as current
   closure state.
6. Execute all applicable packaged closure fixtures through the real adapter,
   retain existing Counter/NBA/Wadowice/five-occurrence/readiness/retry suites,
   and run consumer-JAR and both Java lanes.
7. Deliberately update line, public-API, and production-class budgets and
   new-candidate release evidence; the current 109/115 class inventory leaves
   only six slots. Do not minify the feature or overwrite reports belonging to
   an older release.

## Cross-repository release

Return to the package and execute Prompt 03. Revalidate its bytes, rerun both
repositories against the exact pinned artifacts, verify source archives and
consumer JARs, and emit the three distinct claims:

```text
PACKAGE_VALID
SEMANTIC_REFERENCE_VALID
IMPLEMENTATION_CONFORMANT: true|false
```

Only set the last value to true when there are no skipped required fixtures,
all exact gas/oracle comparisons pass, both repository worktrees are clean at
the recorded commits, and every reported artifact hash matches what was tested.
