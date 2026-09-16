# Language CI optimization and Java 25 verification

Based on `next`; review branch `codex/ci/language-parallel-experiment`.

## Production topology

Build and validate, Release RC and stable Release share the same source-import,
Java 25 setup and core verification actions. Five independent owner jobs execute
the six Python transition tasks. Core compilation and JVM tests run concurrently,
then each normal Gradle transition task validates the exact successful same-run,
same-attempt, same-commit/tree receipt instead of rerunning Python. No task is
excluded. Missing, failed, incomplete or stale receipts fail verification.

`clean build` and `rcVerify` remain separate commands. Core keeps its workspace,
local Maven staging, source-reproducibility checks and final API baseline checks.
A final receipt/source gate precedes any production publication. Publication
commands do not load the receipt init script; its publication prohibition and
no-task-exclusions rules remain intact.

RC preparation occurs once on `next`. Its unpushed commit and identity manifest
are transferred to all verification jobs through a Git bundle. Import validates
trusted prepare-job SHA/tree outputs, original event parent, workflow run and
attempt. Stable production release remains restricted to `master`.

All compilation, tests, JavaExec conformance tasks and generated JVM evidence use
Java 25. Published class files still target Java 8 with `--release 8`; no Java 8
or Java 17 runtime is installed by these workflows. The immutable repository
contract records Java 25. Stable 3.1.0 has an explicit LOCAL_STABLE verification
schema with the same clean-source/complete-publication checks as LOCAL_RC;
previous published manifests and historical evidence are unchanged.

## Safe integration and paired measurement

`verify-production-topology.yml` runs only on the review branch. It has no
publication commands or release secrets and never pushes a tag or version.
It exercises the real shared implementation for:

- Build: event source, full clean build on Java 25.
- RC: a local .cz.toml-only fixture commit retaining its RC version, full release
  verification on Java 25.
- Stable: a local .cz.toml-only fixture commit selecting stable 3.1.0, full release
  verification on Java 25. This version exists only inside isolated runner builds.

All variants have five Python owner jobs. A separate serial RC baseline uses the
**same prepared source** as the distributed RC candidate and the original four
commands. All measured core, baseline and transition-owner jobs start with cold dependency
caches, while incremental outputs within a job remain available. Java 25 tooling
contracts are also tested in a dedicated job. Production retains normal Gradle
cache behavior.

The final comparison requires all jobs to succeed and validates exact source/run
identity and complete owner commands. It compares baseline command time with the
window from the earliest parallel verification start to the latest finish,
including receipt waits. Setup, final uploads, publication and Maven propagation
are excluded. A green core alone is not the full workflow result. All Java XML
reports now include examples. Rerun the **whole workflow** because old-attempt
receipts are intentionally rejected.

## Historical measurements

The Java 17 build / Java 8 test-runtime A/B result was **58:55.66 → 25:23.93**
(**56.90%**). The following integration run passed full RC in **25:48**. These
remain historical observations, not Java 25 speedup claims. The new paired Java
25 run supplies the current baseline and result after all checks pass.

The older standalone timing/fork workflow is manual-only. It now uses Java 25,
so rerunning it does not reproduce the old runtime configuration. Neither timing
workflow invokes production publication; local staging only tests consumers.

## Four-process JVM experiment

The independent `test-forks` matrix runs **full `clean build`** twice on the same
commit and runner type, with `--max-workers=4 --no-daemon --no-build-cache` and
`-PblueTestMaxParallelForks=1` versus `=4`. It does not exclude any tests or invoke
release verification/publication. Each job records wall time and exit status in
its summary and saves reports. Compare test inventories as well as elapsed time;
a failed run is not evidence of a speedup.

The property changes the maximum concurrent test JVMs for tasks configured by
`Java8LibraryConventionsPlugin` and the root orchestration plugin (including the
main root test suite). The default remains one. JUnit parallel execution
inside each JVM remains disabled. This setting does not parallelize Python `Exec`
tasks or remove dependencies between Gradle tasks. Keep the override scoped to
this experiment; focused development commands continue to use the default.

The original convention was introduced in `1811aaa2` (PR #28), with a test named
`shouldConfigureDeterministicJUnitPlatformExecution`. Later documentation in
`docs/fast-development-verification.md` explicitly cites shared static state,
caches, mappers, report paths, and bounding simultaneous JVM heaps. Separate JVMs
isolate static state but not filesystem paths; passing the full matrix is required
before considering this setting for normal CI. No evidence has yet established
that four forks are safe for every release-verification task.
