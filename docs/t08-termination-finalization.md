# T08 — ordinary PROCESS termination finalization

## Verdict and supported profile

Confirmed and fixed on `aa160af465c9f17f21aa7f157d2aba930f7d35d4` (`next`,
`3.1.0-rc.26-SNAPSHOT`). The task's original audit revision was
`91395481ca95a7f74951f8a6df90c5a8d1a91190` / rc.25; the defect was independently
reproduced on the current checkout. This covers the ordinary PROCESS engine,
both mutable and resolved-snapshot public facades, with exact revision-bound
external delivery and active-subscription evidence.

The first regression run had one intended failure and one passing ordinary
control. Re-running the final regression suite with the original production
file produced 9 failures and 4 passing controls out of 13 cases. The baseline
returned SUCCESS with a termination marker but no retired subscription and no
subscription-validation trace. Child-only termination, ordinary delivery,
runtime-failure rollback, and gas rejection before marker creation remained
passing controls.

## Change

`ProcessingSession` now catches the private run-termination signal at the
logical-delivery and internal-drain boundaries. Successful termination
completes those phases and proceeds to the existing soundness/checkpoint and
subscription validation phases. A previously recorded failure is rethrown to
the existing failure-result boundary. Finalization exceptions still produce
noncommitting results through the ordinary orchestrator.

The fix changes one package-private production class. It does not change
public signatures, dependency declarations, marker encoding, gas weights,
specification/registry identities, or the separate rooted closure engine.
It closes PROC-01 / T08's skipped ordinary finalization, including missing
settlement of already staged descendant checkpoints. It does not claim T13,
T17, T18, or rooted durable publication findings are closed.

`IndependentT08RegressionTest` contains 13 cases covering Root and child
termination, inactive input/replay, one/multiple subscriptions and descendants,
later-handler/delivery suppression, nested queued emissions, exact-once
checkpoint settlement, mutable/snapshot facade rollback and retry, final
validation rejection, failure short-circuiting, and gas rejection before the
termination marker and during final checkpoint settlement. Rejection returns
the exact input identity/document, no public events, no subscription delta,
and an unchanged companion revision. Retained caller input and snapshot
identity are checked as well.

## Observable compatibility changes

The supplemental probe uses the regression fixtures and records complete
semantic traces, gas charges, Root identities, and serialized output.

- Single Root subscription: gas is 234 before and after; Root BlueId remains
  `CLfK7vurhyvgBVEtJPxqwjcH4Y6Wuvwn1RoCGPgzt9pD`. Retirements change from zero
  to one, ending the interval at revision 2. No checkpoint is written.
- Two descendant deliveries completed before Root termination: gas changes
  from 523 to 773; Root BlueId changes from
  `2GNzgJTiRqmgH32zCBp65BcAq6PMB84JhTStPeCU4PvP` to
  `G6c27AMdRMGyf5oqtgAU4d5LF7tAnzJjrQtqxfjnegmY`. The existing finalization
  now writes one staged checkpoint each for `/child` and `/child/grandchild`;
  all three subscriptions retire at revision 2. Earlier business effects
  remain, and later Root handlers stay suppressed.
- The complete pre-fix gas-charge sequence remains an identical prefix in
  both cases. The additional 250 units in the second case are the existing
  marker/checkpoint and semantic identity charges; no schedule was rebaselined.

Hosts consuming `PlatformCommitCompanion` now receive the missing retirement
information. Consumers comparing gas, output BlueIds, or exact traces must
account for completed finalization. Previously accepted invocations can now
correctly reject at final validation or at its gas boundary. Rollout and replay
should use a consistent processor version. Existing stored Roots and host
subscription indexes are not automatically repaired by this library patch.

No downstream BEX or Coordination repository was tested against a published
immutable artifact tuple. Public API compatibility and these local tests do
not establish cross-repository release readiness. Ordinary results also do
not establish rooted receipt/source-epoch or durable host publication claims.
Gas-error classification during the termination marker's own identity write
is the separate T18 concern and is not changed here.

## Toolchain, artifacts, and evidence

Gradle 9.6.0 runs on Temurin 25.0.4.1; acceptance/module tests and the
supplemental probe use Zulu 8u504 (8.96.0.205, macOS aarch64).
The conformance Python prerequisites use the existing Python 3.12 environment
`/private/tmp/pr36-contextual-repair/venv312` with PyYAML 6.0.3.

Current specification SHA-256 values:

- Language: `77b48506ff7b5ddbab26b98ce9e060e943cb3babf1e6f5511085bbb3c31c4144`.
- Contracts: `5cc29e91cd8d4aa4d3dca98214da5ceb49b2daa82554ac561260bd98ce5063b8`.

The local `blue-contracts-core:3.1.0-rc.26-SNAPSHOT` JAR changes from SHA-256
`c71b0f57bd13d97778a60bd07e35417dcbcb67e4847718ea3b33a9019f81c73c` to
`8fb506faff1998394e4708f493bc5d42d8ab2afcc56de6feac620b62f58d42b3`.
All other runtime dependency JARs retain their hashes. These are local build
artifacts, not published coordinates with an implied immutable SNAPSHOT.

Retained evidence is under `/private/tmp/blue-language-t08-evidence/`:

- `baseline.log`, `baseline.xml`: original minimal red/green control.
- `baseline-expanded.log`, `baseline-expanded.xml`: final 13 cases on baseline.
- `focused.log`, `focused-xml/`: 36 passing T08 and neighboring cases.
- `before/` and `after/`: exact outcome JSON and dependency SHA-256 manifests.
- `probes/T08OutcomeEvidence.java`, `evidence.gradle`: supplemental observation
  source and build wiring; they are not production or committed test sources.
- `final-verification.log`, `root-xml/`, `module-xml/`: final verification.
- `commands.md`, `revision-manifest.json`: invocation and revision handoff.

The root public-API baseline task reports 90 additions and zero removals against
its older tracked Contracts inventory. T08 changes no public declarations;
`ProcessingSession` is absent from that public inventory. No historical API
baseline or conformance expected output was rewritten for this change.

## Validation

Final JUnit XML reports contain 2,812 root acceptance cases and 612 Contracts
module cases, with no failures, errors, or skipped cases. The 36 focused
termination cases are included in the root suite. API inventory generation,
package-cycle verification, and runtime trace evidence also passed.

Minimal reproduction on the baseline (with the regression source present):

```sh
JAVA_HOME_8_ARM64=/private/tmp/issue22-jdk8/zulu8.96.0.205-ca-jdk8.0.504-macosx_aarch64/Contents/Home \
  bash ./gradlew :test \
  --tests 'blue.language.processor.IndependentT08RegressionTest.shouldRetireActiveSubscriptionAfterRootTermination' \
  --no-daemon --max-workers=4
```

The final verification command, including supplemental artifact observations,
was:

```sh
PATH=/private/tmp/pr36-contextual-repair/venv312/bin:$PATH \
JAVA_HOME_8_ARM64=/private/tmp/issue22-jdk8/zulu8.96.0.205-ca-jdk8.0.504-macosx_aarch64/Contents/Home \
  bash ./gradlew -I /private/tmp/blue-language-t08-evidence/evidence.gradle \
  :test :blue-contracts-core:test \
  :blue-contracts-core:verifyJavaPackageCycles :blue-contracts-core:apiBaselineDiff \
  runtimeTraceEvidence releaseConformanceTest t08OutcomeEvidence \
  -Pt08EvidenceOutput=/private/tmp/blue-language-t08-evidence/after \
  --continue --no-daemon --max-workers=4 \
  > /private/tmp/blue-language-t08-evidence/final-verification.log 2>&1
```

The complete final command succeeded in 11m 31s. Release conformance passed
185/185 Language and 295/295 Contracts fixtures, with zero failures/skips;
its prerequisite release-transition, historical-representation, and rooted
checker tasks also passed. The unchanged release fixture package identity is
`sha256:b824e1c4873bf952843341290f5fd08cda4bb1f2a1e8f47dcc9ed8ba55b88b20`.
These results cover the selected local gates, not a full release-readiness or
downstream compatibility certification.
