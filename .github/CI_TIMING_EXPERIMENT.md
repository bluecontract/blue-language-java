# Language verification timing experiment

Based on `next`; experimental branch: `codex/ci/language-parallel-experiment`.
Only this branch triggers `CI timing experiment (no publication)` on push.
The workflow also checks the branch before running a manual dispatch.

No version preparation, commits, tags, GitHub releases or Maven Central
publication run in this workflow. Its token has only `contents: read`, checkout
credentials are not persisted, and it receives no release secrets. The existing
build and release workflows and the normal Gradle task graph are unchanged.
Release verification still stages Maven artifacts **locally** to test consumers.

## Comparison

All seven measurement jobs use the same commit, Ubuntu 24.04, Corretto JDK 17,
Java 8 test runtime and PyYAML 6.0.3. Both variants start without a restored Gradle
cache, while incremental outputs within a job remain available.

- **baseline:** the existing sequential `clean build`, `rcVerify`, source-archive
  reproducibility check and API-baseline verification, stopping before publication.
- **core:** one `clean build rcVerify verifyFinalApiBaseline` task graph, followed
  by the same source-archive reproducibility check. Gradle executes shared tasks
  once. Six independent Python transition tasks are excluded only here.
- **five transition groups:** execute those six excluded Gradle tasks exactly
  once on separate runners; the two short tasks share a runner.

The partition test compares the assignment against task registrations in
`blue-conformance/build.gradle`. The comparison job fails on any missing, failed,
duplicate or different-commit timing evidence, and requires the complete matrix
to pass. A green core job alone does not certify verification.

Timing JSON and test reports are retained for seven days. The summary compares
baseline command wall time with the longest parallel group's command wall time;
this excludes provisioning, queues and artifact transfer. Inspect actual job
start/end times before claiming end-to-end speedup. A single run is an experiment,
not a robust performance benchmark. Publication latency is deliberately unmeasured.

Local checks: `node --test .github/scripts/*.test.js`, actionlint on the new
workflow, and Gradle dry-run comparison of the complete and partitioned task
graphs. Full test execution belongs to this isolated GitHub Actions workflow.
