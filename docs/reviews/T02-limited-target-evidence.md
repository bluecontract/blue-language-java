# T02 — Limited matching target evidence: verified fix

## PR base and integration verification

The PR targets `next` directly. Only the two T02 commits were transplanted from
the audited revision; the code and regression tests are byte-identical to the
audited fix. The PR contains ten changed files and has no prerequisite on PR #36
or PR #38.

- PR base: `450230c18a41df8f873fb761d516f1bc85d7b5bd` (`next`, `v3.1.0-rc.26`).
- Ported code commit: `845836f83768742d341f958eae00f9f679b6b5d6`.
- Ported code tree: `e3a6007c7d7b3f5a91f628108e5132d9be72473e`.
- Local version: `3.1.0-rc.26-SNAPSHOT`, inherited from `next`.
- Java 8 rerun: **2,823 root tests and 173 core tests**, zero failures/errors/skips.
- All **23 T02 regression cases** pass in that root suite.
- Core `check` passes, including API, package-cycle, and reproducible-archive gates.
- `git diff --check` passes.

The wrapper and JVM versions are the same as in the audit profile below. Build
and specification inputs remain byte-identical to this `next` base. Its Language
specification SHA-256 is
`77b48506ff7b5ddbab26b98ce9e060e943cb3babf1e6f5511085bbb3c31c4144`;
the Contracts specification hash is unchanged from the audit profile.

Exact rerun command from `/private/tmp/blue-language-t02`:

```bash
JAVA_HOME_8_ARM64=/private/tmp/issue22-jdk8/zulu8.96.0.205-ca-jdk8.0.504-macosx_aarch64/Contents/Home bash ./gradlew -I /private/tmp/blue-language-t02-evidence/evidence.init.gradle :clean :blue-language-core:clean t02Evidence :test :blue-language-core:check --offline --continue -Pt02EvidenceDir=/private/tmp/blue-language-t02-next-evidence --no-daemon --max-workers=4 --info > /private/tmp/blue-language-t02-next-evidence/root-core-java8.log 2>&1
```

The next-base log, copied JUnit XML, `test-summary.json`, `dependencies.json`,
`profile.json`, and core gate reports are retained under
`/private/tmp/blue-language-t02-next-evidence`. The tested core JAR SHA-256 is
`347a6068a16a4f9f406eb067bd6d1973448e0ffdece893362348e7b4097185e6`.
The sections below retain the original audit profile and before/after evidence.

## Verdict and audited profile

**Confirmed and fixed** for the supplied Language revision and the public
`BlueLanguage.matching().matchesLimited(...)` service. This closes the scoped
TYPE-01 finding and its TYPE-02 reference-budget duplicate.

The bug treated a failure to obtain the target type definition as proof that a
valid candidate did not match it. The candidate was initially resolved with the
caller's limits, but the service then called ordinary Boolean matching. That
path caught target lookup failures as `false` and performed target reads outside
the operation's reference budget. The limited wrapper labeled the Boolean
`ESTABLISHED`, losing the missing identity and provider outcome.

- Repository: `blue-language-java`, local multi-module Language/Contracts source.
- Baseline commit: `91395481ca95a7f74951f8a6df90c5a8d1a91190`.
- Fix commit: `87258e6e30f9845dc920b91e733d8ac36b3544a7`.
- Fix tree: `f6ece8203ab5d304f02f04ad8815b9779e42d64f`.
- Branch: `fix/t02-limited-target-evidence`.
- Audit fix worktree: `/private/tmp/blue-language-t02` (later reused for the PR base).
- Baseline worktree: `/private/tmp/blue-language-t02-baseline`.
- Version: `3.1.0-rc.25-SNAPSHOT`; no version or dependency selection changes.
- Platform: macOS **26.6.2**, **aarch64**, verified on 2026-09-15.
- Gradle: **9.6.0**, wrapper revision `3f750f03d77e42327c5f9fcb9992110088330a32`.
- Gradle launcher: Temurin **25.0.4.1+1-LTS**; libraries compile with `--release 8`.
- Normal root/core test launcher: native arm64 Zulu **8.96.0.205**,
  `1.8.0_504-b01`. The retained audit's Java 25 runner was used separately.

The governing Language specification SHA-256 remains
`0e2453223fd3dc7f933d17eb6530eb45687c24c58ad58e4d2758cd490af8db24`.
The Contracts specification SHA-256 remains
`e91381c970859a6bafecdd99e46f5115ba033bf0534be84bbd5582531e9e347f`.

The assigned task is retained at
`/Users/przemekkowalewski/Sites/audits/after-review/independent-blue-review/tasks/T02-limited-target-evidence.md`.
The cited requirements are Language §§3.6, 10.6–10.9: unavailable content cannot
establish semantic absence, and operation limits and cache state must preserve
explicit incomplete/invalid outcomes.

## Minimal reproduction and observed results

The public regression is
[src/test/java/blue/language/matching/IndependentT02RegressionTest.java](../../src/test/java/blue/language/matching/IndependentT02RegressionTest.java).

Use the valid inline candidate `new Node().value("ok")`. Construct an exact target
identity with `DirectBlueIdCalculator` from a named Text definition carrying
`minLength: 2`, and match against `new Node().type(new Node().blueId(targetId))`.
The fixture provides typed `NodeProviderResult` outcomes and records every request.

- `UNAVAILABLE`: formerly established false; now `INCOMPLETE`, with the exact
  outstanding identity and `UNAVAILABLE` provider outcome, after one allowed read.
- `NOT_FOUND`: formerly established false; now `INCOMPLETE` with `NOT_FOUND`.
  A provider miss does not prove that a value violates an unknown definition.
- `INVALID_EVIDENCE` or content with a mismatching BlueId: now `INVALID`, with no
  Boolean value and the invalid-evidence provider outcome.
- Found valid definition, budget one: established true for `"ok"`.
- Found valid definition, budget one: established false for `"x"`, which genuinely
  violates the target's minimum length.
- Budget zero: formerly a target read and an established result; now `INCOMPLETE`,
  the outstanding target identity, no provider outcome, and **zero provider calls**.
- Proven absence of every demanded candidate path: `ABSENT`, without target reads.
- Equal pure reference identities: may establish true without materializing content.

The retained original probes reproduced exactly before the fix:
`ESTABLISHED, outstanding=[]` for a missing target, and
`ESTABLISHED, target reads=1` with a zero budget. After the fix they report
`INCOMPLETE` with the missing identity, and `INCOMPLETE, target reads=0`.

## Final implementation

`LanguageMatchingService` delegates limited matching to an operation owned by the
runtime. `LanguageRuntimeLimitedResolution` supplies one budgeted, typed provider
to candidate expansion, candidate resolution, and demanded target materialization.
It uses `NodeTypeMatcher.matchesTypeOrThrow`, retaining evidence failures until
the limited boundary classifies the result.

Candidate preparation runs once and retains its canonical type identity evidence.
Repeated use of the same exact reference shares the operation's retained evidence
and budget charge. Global resolved caches cannot skip current-operation accounting.
Candidate absence stops matching before target evidence is demanded. Existing
pattern-driven traversal and matching short circuits avoid unrelated lookups.

Ordinary Boolean matching retains its documented fail-closed behavior. Genuine
schema mismatches remain established false; invalid candidate/target semantics
remain invalid. No broad exception-to-incomplete conversion was introduced.
Three unused internal matching forwarding methods were removed from
`BlueLanguageRuntime`, keeping the matching owner within the existing architecture
size limit without changing the limit or its allowlist.

## Verification

All counts below are JUnit XML counts, including parameterized/dynamic cases.

- Original retained T02 probes, baseline, Java 25: **2 tests, 2 intended failures**.
- Initial public regression and existing boundary tests, baseline, Java 8:
  **12 tests, 5 intended failures**.
- Final public regression on the unchanged baseline, Java 8:
  **23 tests, 12 failures, 11 passing controls**.
- Final root acceptance suite, Java 8:
  **2,834 tests, zero failures/errors/skips**. This includes all 23 T02 cases,
  the five runtime-boundary cases, existing mutable/frozen matching tests,
  limited-resolution tests, identity tests, and processor regression tests.
- Final Language core suite, Java 8:
  **206 tests, zero failures/errors/skips**.
- Core `check`: **passed**, including public API baseline comparison, package-cycle
  checks, and reproducible archive verification.
- Original retained T02 probes after the fix, Java 25: **2 tests, zero failures**.
- `git diff --check`: **passed**.

The new cases cover typed provider outcomes, genuine false matches, zero/exact/
one-short reference budgets, shared operand accounting, target ancestry, inline/
reference parity, cold/warm snapshots, retry, schema failure, input immutability,
unrelated sibling avoidance, target short-circuiting, exact reference identity,
null patterns, and candidate absence.

This API exposes demanded paths and a reference-expansion bound; it has no separate
maximum-depth parameter. No unsupported depth-budget contract was invented. The
existing ordinary resolution-limit tests run as part of the root suite.

### Exact commands and retained evidence

Evidence root: `/private/tmp/blue-language-t02-evidence`.

Baseline final regression, run from `/private/tmp/blue-language-t02-baseline`:

```bash
JAVA_HOME_8_ARM64=/private/tmp/issue22-jdk8/zulu8.96.0.205-ca-jdk8.0.504-macosx_aarch64/Contents/Home bash ./gradlew :test --offline --tests blue.language.matching.IndependentT02RegressionTest --no-daemon --max-workers=4 > /private/tmp/blue-language-t02-evidence/baseline-regression-java8.log 2>&1
```

Final root and core gates, run from `/private/tmp/blue-language-t02`:

```bash
JAVA_HOME_8_ARM64=/private/tmp/issue22-jdk8/zulu8.96.0.205-ca-jdk8.0.504-macosx_aarch64/Contents/Home bash ./gradlew -I /private/tmp/blue-language-t02-evidence/evidence.init.gradle t02Evidence :test :blue-language-core:check --offline --continue -Pt02EvidenceDir=/private/tmp/blue-language-t02-evidence/after-final-java8 --no-daemon --max-workers=4 --info > /private/tmp/blue-language-t02-evidence/final-root-core-java8.log 2>&1
```

The evidence init script adds only an artifact/launcher inventory task. It changes
no dependencies, test sources, test launchers, or production behavior.

Original audit reproduction, run before and after the fix from the fix worktree
(the initial run preceded production edits; its output file was
`retained-before-java25.log`):

```bash
bash ./gradlew -I /Users/przemekkowalewski/Sites/audits/after-review/independent-blue-review/evidence/supplied-audits/blue-language-java/probes/audit.init.gradle :test --offline -PauditProbeSourceDir=/Users/przemekkowalewski/Sites/audits/after-review/independent-blue-review/evidence/supplied-audits/blue-language-java/probes --tests blue.language.audit.TypeAuditProbe.missingTargetEvidenceIsIncompleteRatherThanEstablishedMismatch --tests blue.language.audit.TypeAuditProbe.targetResolutionCannotEscapeZeroReferenceBudget --no-daemon --max-workers=4 > /private/tmp/blue-language-t02-evidence/retained-after-java25.log 2>&1
```

Useful evidence files/directories under the evidence root:

- `retained-before-java25.log` and `.xml`; `retained-after-java25.log` and `.xml`.
- `regression-before-java8.log` and `before-java8/`: initial red public API cases
  and passing boundary controls, including the recorded Java 8 executable.
- `baseline-regression-java8.log` and `baseline-final-regression-java8/`: final
  23-case baseline reproduction, XML and summary.
- `final-root-core-java8.log`, `final-root-java8/`, `final-core-java8/`, and
  `final-test-summary.json`: final passing gates and complete XML copies.
- `profile.json`: source commits/trees, specification/build-input hashes, and
  per-file hashes for the tested fix.
- `baseline-final-regression-java8/dependencies.json`,
  `after-final-java8/dependencies.json`, and `dependency-comparison.json`:
  exact dependency tuples and launcher metadata.
- `api-change.json`: compiled API comparison against the audited revision.
- `T02-fix.patch`: portable patch for the code/test commit.

## Dependency, identity, state, and release implications

The root test classpath contains 33 artifacts. **32 are byte-identical** before
and after; only `blue-language-core-3.1.0-rc.25-SNAPSHOT.jar` changes:

- Before SHA-256: `370d910545665c671cda3e1e1c6dace57a2431daebc7c7bcd5cfbab886217a96`.
- After SHA-256: `54872b6797cf1782e6cbd7397cad958d12b025a4f19fe4273a49930c65f40ea6`.

The full immutable tuples and copies of the local module JARs are retained in the
baseline/after evidence directories. Coordination published JARs were not
substituted for Language/Contracts source. No specification, registry, canonical
identity vector, dependency version, or portable gas schedule was rebaselined.

The public API inventory has **three additions and no removals**: the default
`MatchingRuntime.matchesLimitedForMatching` operation, its concrete runtime
implementation, and the two-argument `LanguageMatchingService` constructor. The
old three-argument constructor remains and is deprecated. A custom legacy
`MatchingRuntime` must implement the new operation to establish limited matches;
otherwise the default returns explicit `INCOMPLETE` with an unsupported-operation
reason. Ordinary Boolean matching is unchanged.

The regression verifies that failed and retried matching leaves candidate and
target inputs unchanged, including values projected from frozen inputs. Limited
matching publishes no processor transition, event, checkpoint, or subscription.
The existing fragmented-processing root test also executed its eight primary/
replay representation cases successfully. These are regression results, not a
new claim about every host's exactly-once publication behavior.

## Scope and residual coverage

This is a fix for the audited local multi-module profile. It does not certify all
Language conformance, every pattern form, other TYPE findings, or release
readiness. No downstream Coordination run against a newly published immutable
artifact was performed; no artifact was published or dependency migrated.

The original verification used the requested audit commit. The PR branch now
uses the separately verified `next` profile recorded above. Existing user
checkouts and their untracked files were preserved. Shared matching-owner
changes from T20 or T13 should be reconciled with this operation boundary when
integrating their branches.
