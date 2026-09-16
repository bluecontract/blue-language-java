# Language verification timing experiment

Based on `next`; experimental branch: `codex/ci/language-parallel-experiment`.
The historical `CI timing experiment (no publication)` is now manual-only.
It checks this branch before running; the production-topology verification below runs on branch pushes.

No version preparation, commits, tags, GitHub releases or Maven Central
publication run in this workflow. Its token has only `contents: read` and `actions: read`, checkout
credentials are not persisted, and it receives no release secrets. The timing workflow does not call production release workflows. The proposed
Build/Release RC integration below preserves the normal Gradle verification rules.
Release verification still stages Maven artifacts **locally** to test consumers.

## Comparison

All seven measurement jobs use the same commit, Ubuntu 24.04, Corretto JDK 17,
Java 8 test runtime and PyYAML 6.0.3. Both variants start without a restored Gradle
cache, while incremental outputs within a job remain available. Gradle distribution
bootstrap (`--version`, up to three attempts for network errors) occurs before
timing in every job; build and test failures are never retried automatically.

- **baseline:** the existing sequential `clean build`, `rcVerify`, source-archive
  reproducibility check and API-baseline verification, stopping before publication.
- **core:** the same four separate commands as the baseline. An experiment-only
  init script replaces the six Python command invocations with validation of
  receipts from their owning runners. No Gradle task is excluded. The receipt
  must match the commit, tracked source tree, run ID and attempt, exact complete
  command assignment, and successful exit codes. Tracked source changes fail.
- **five transition groups:** execute those six Python tasks exactly once on
  separate runners; the two short tasks share a runner. Results are uploaded as
  attempt-specific artifacts even on failure.

Core compilation and Java tests overlap the Python jobs. If a result is not ready
when needed, core waits (at most 25 minutes) for that same-attempt artifact. A
failed, stale or incomplete receipt fails the delegated task. The normal build
completion and release-evidence checks run only after these checks succeed.
`build` and `rcVerify` stay separate invocations, preserving their evidence order.
Production clean-build evidence generation and verification code is unchanged;
this distributed execution mode is used only by the isolated workflow and is not
accepted as authorization to publish packages. The init script rejects remote
publication tasks and any task exclusions. Normal release jobs do not load it.

The partition test compares the assignment against task registrations in
`blue-conformance/build.gradle`. The comparison job fails on any missing, failed,
duplicate, incomplete or different-run/source timing evidence, and requires the complete matrix
to pass. A green core job alone does not certify verification.

Timing JSON and test reports are retained for seven days. The summary compares
baseline command wall time with the interval from the first parallel command
start to the last parallel command completion;
this includes core receipt waits but excludes initial provisioning and final
artifact upload. It is not the total duration of the entire A/B workflow. Inspect actual job
start/end times before claiming end-to-end speedup. A single run is an experiment,
not a robust performance benchmark. Publication latency is deliberately unmeasured.

Local checks: `node --test .github/scripts/*.test.js`, actionlint on the new
workflow, and a focused Gradle fixture that exercises successful and failed receipt
imports. Full test execution belongs to this isolated GitHub Actions workflow.

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

The earlier `core` partition experiment failed because task exclusions prevent
clean-build evidence from being issued. Its elapsed time is not a valid speedup.
The corrected core retains the complete task graph and validates remote task
results before normal evidence generation; it no longer uses `-x`. Production workflow migration is described separately below.

For a rerun, rerun **all** jobs. Receipts from earlier attempts are deliberately
rejected. Because receipt importing needs artifacts from the current run, the
core job alone cannot reproduce a distributed run from an earlier attempt.

## Proposed production integration

`verify-production-topology.yml` now exercises the same shared source import,
setup and core verification actions used by Build and validate and Release RC. It runs only on the experiment branch and has no release commands or
publication secrets. The JDK 17 variant creates a local, .cz.toml-only fixture
commit without changing its version or creating a tag; JDK 25 verifies the event
commit and the build-only scope, matching Build and validate. JDK 17 runs the
full release verification scope. Each has five parallel
Python owner jobs, with receipts scoped to that invocation and workflow attempt.

The real RC workflow prepares its version exactly once on `next`, then transfers
that commit in a Git bundle. The core runner and Python owners check the trusted
prepare-job commit/tree outputs, event parent, run and attempt. Core keeps its
own build outputs and local staging repository, so existing publication and
provenance checks still happen in that same workspace after all receipts pass.
Stable Release is unchanged and remains restricted to `master`. Its existing
JDK 25/stable-version setup conflicts with the current immutable repository
contract (JDK 17 and 3.1.0 RC/development versions only); resolving that contract
is outside this optimization. No publication workflow is invoked by this branch.

The historical A/B timing workflow is now manual-only to avoid repeating the
hour-long unchanged baseline on each integration fix. The new branch push runs
only the production-topology verification. Full Java test forking still defaults
to one; its opt-in experiment is unchanged. Report uploads now include examples
JUnit XML. When rerunning verification, rerun the whole workflow: receipts from
an older attempt are intentionally rejected.
