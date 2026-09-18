# Language / Coordination sequential integration — 2026-09-17

All six cumulative stages passed. After each merge, the full Language gate ran, a new immutable local Maven repository was built, and Coordination passed its full Java 17 release gate against that exact repository before the next PR was merged. The final combined library also passed the full Coordination gate on Java 21.

Both repositories use the local branch `integration/language-prs-39-46`.

- Language base (`next`): `fb46ae5134a2c82143a8dbc91a02f70ce5478071`.
- Coordination base (`next`): `69cd6db22707028abfbaab04741dc9b61961aa19`.
- Tested Coordination commit: `5ff515d0e3a409f40985d101ceb248d767a0f9f2`.
- Tested final Language commit: `bb474f5a9447a0b948efb95613bebda5db447c5c`.

The documentation commit containing this receipt comes after the tested Language merge. The full input PR heads, cumulative merge commits, artifact manifest hashes and per-suite results are retained in [verification.json](verification.json).

## Stages

Each stage additionally passed 997 module/example tests and the 185 Language + 295 Contracts release fixtures. Every Coordination Java 17 run passed 1,065 unit, 91 integration, 16 JAR-consumer and 14 scenario tests (1,186 total), as well as the extracted-source build and focused smoke tests. Recorded JUnit suites have zero failures, errors or skipped cases.

- [PR #39](https://github.com/bluecontract/blue-language-java/pull/39): `ef9019aaf6b11e9129da2439cb7a70c9fd1488ca`; 2823 Language root tests; all gates PASS.
- [PR #40](https://github.com/bluecontract/blue-language-java/pull/40): `c59b10dc1ce86000799e5fa3a34e9cdbcd572427`; 2901 Language root tests; all gates PASS.
- [PR #42](https://github.com/bluecontract/blue-language-java/pull/42): `6f2589bc258513afeca1d043a4003a6326f9ea1c`; 2914 Language root tests; all gates PASS.
- [PR #43](https://github.com/bluecontract/blue-language-java/pull/43): `41056338b592b54df78943bfdc9068991c881379`; 2938 Language root tests; all gates PASS.
- [PR #44](https://github.com/bluecontract/blue-language-java/pull/44): `4d44355338301d9b79b2f0ce12bdc6b9e4666dc2`; 3011 Language root tests; all gates PASS.
- [PR #46](https://github.com/bluecontract/blue-language-java/pull/46): `bb474f5a9447a0b948efb95613bebda5db447c5c`; 3061 Language root tests; all gates PASS.

Final Java 21 Coordination run: the same 1,186 tests and extracted-source checks passed.

## Compatibility change

No Coordination runtime source changes were required. Its Gradle staged dependency lane previously retained Language `3.1.0-rc.25` in the committed lock template. The integration branch generates a manifest-specific lock file with exactly the six Language coordinates rebound to the explicitly selected version. All other dependency pins remain unchanged, including BEX `1.1.0-rc.6` and blue-repo `3.0.0-rc.22`. The published default lane also passed `verifyActiveDependencyLane`, `verifyDependencyModeIsolation` and `dependencyPreflight` without staged properties.

## Local artifact handoff

Every local version is `3.1.0-dev.<tested-Language-commit>`. Language first runs `verifyPublishedRepository` against its native six-module DEVELOPMENT manifest. Coordination currently consumes the older seven-module staged manifest schema. The local adapter copies the same build's Maven artifacts, adds sources/Javadoc records and the conformance module, retains source/specification/fixture identities, and records the native manifest hash. Native artifact bytes must match exactly. Coordination then verifies all 28 artifact hashes and the resolved dependency graph. Both manifests retain `releaseReadinessClaimed=false`; this run does not publish a release.

Local logs, native/staged repositories, original orchestration scripts and archived test reports are under `/private/tmp/blue-integration-20260917`. These machine-local files are not part of Git and may be removed by temporary-directory cleanup.

## Commands used

Language (Java 17, clean source tree at each cumulative merge):

```sh
bash ./gradlew --no-daemon --no-build-cache --max-workers=4 clean test moduleCheck releaseConformanceTest runtimeTraceEvidence
bash ./gradlew --no-daemon --no-build-cache --max-workers=4 verifyPublishedRepository \
  -PreleaseVersion=3.1.0-dev.<tested-commit> \
  -PstagedDependencyRepository=<stage>/native-repository
```

Coordination (from its repository; select the matching Java home):

```sh
bash ./gradlew --no-daemon --no-build-cache --max-workers=4 clean releaseCheck dependencyPreflight \
  -PtestJavaVersion=17 -PtestMaxParallelForks=4 \
  -PblueDependencyMode=immutable-staged-contracts \
  -PblueContractsVersion=3.1.0-dev.bb474f5a9447a0b948efb95613bebda5db447c5c \
  -PblueContractsRepository=/private/tmp/blue-integration-20260917/stage-06-pr46/coordination-repository \
  -PblueContractsManifestSha256=sha256:eb4bba7616c92e68ee51a61c62a80f0c262fcde8e5fcc3b65166613a836fe94d
```

Repeat the final Coordination command with Java 21 and `-PtestJavaVersion=21` for the second JDK gate. The actual invocations also used a read-only Gradle `afterSuite` progress listener and explicit JDK installation paths; no test filters or weakened assertions were used in the full gates. The archive smoke task has its own pre-existing focused test selection.

## Downstream MyOS qualification

After all library gates passed, MyOS source `1a54a1a563e82e5faad4fc04fb017ee54d757935` passed 1392 application cases with the sole pre-existing C03 skip and no failures/errors. The full source, integration, packaged product and independent HTTP suites used the final tuple. One test-only concurrent Mockito setup race was repaired; no MyOS runtime source change was needed. See the MyOS repository's `docs/language-prs-39-46-integration.md` and its verification JSON.

The final local repositories are retained at `/Users/przemekkowalewski/Sites/space1/blue-language-java/.gradle/integration-prs-39-46/repositories` and the exact tested MyOS JAR at `/Users/przemekkowalewski/Sites/space1/blue-language-java/.gradle/integration-prs-39-46/app/myos-mini.jar`.

A durable local copy of all six stages, manifests, orchestration scripts and MyOS receipts is retained under `/Users/przemekkowalewski/Sites/space1/blue-language-java/.gradle/integration-prs-39-46/evidence`. To rerun the final Coordination gate after temporary-directory cleanup, set `-PblueContractsRepository=/Users/przemekkowalewski/Sites/space1/blue-language-java/.gradle/integration-prs-39-46/repositories/language` and keep the manifest hash above.
