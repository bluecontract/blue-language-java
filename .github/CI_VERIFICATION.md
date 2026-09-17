# Distributed production verification

The original Build, Release RC and Release workflows run on Java 25. Each starts
one source-preparation job, five independent Python transition owners, and a core
job. Core and owners overlap. The core still runs the normal build and release
verification graph, requiring successful remote receipts in place of repeating
the six Python transition commands. Publication is prohibited in that verification
invocation; the original release publication steps run only after its full gate.

The two `workflow_call` helpers are production implementation details, not extra
experiment pipelines: preparation transfers one exact source commit, and transition
verification assigns each independent Python command to one owner. The shared
setup/core actions avoid divergent checks between Build, RC and Stable.

Preparation exports its attempt along with commit and tree. A failed-job rerun
can import the successful earlier source bundle without preparing a new version.
Each owner receipt and artifact records its own execution attempt. GitHub copies
successful job records into later attempts without rerunning them; matching
execution timestamps identify the original attempt that uploaded the artifact. The consumer
checks the latest owner job for its exact scope/group, accepts successful reused
owners or retried owners, and binds every receipt to the same run, source generation,
commit, tree and command list. A newer queued/running owner prevents reuse of older
success; current owner failure fails the gate. Artifacts use distinct attempt names,
so a failed old receipt is never overwritten or mistaken for a successful retry.

There is no independent 25-minute receipt deadline. Polling follows owner status,
including runner queue time. Core jobs have an explicit 90-minute total runtime
budget; owner jobs have 30 minutes after runner start. An arbitrarily long queue
can still exhaust the core budget. Invalid manual release refs fail a root guard
(RC: `next`, Stable: `master`) rather than silently skipping the workflow.

Both experiment workflows, comparison helpers, fixture preparation modes and
experimental fork settings were removed. The earlier same-source Java 25 result
(59:11.913 serial versus 22:19.406 distributed verification) is retained in PR45
and the run artifacts, not an additional workflow:
https://github.com/bluecontract/blue-language-java/actions/runs/35116555937

Final-head PR CI exercises actual Build and the shared helpers. Earlier isolated
RC/Stable verification passed, but the removed harness is not rerun at the final
head, and no production release or publication is dispatched for validation.

## Maven Central publication visibility

RC and Stable first create the local staging repository, then run `jreleaserDeploy`
with `-PblueMavenCentralSkipPublicationCheck=true`. JReleaser signs/checksums,
uploads, validates and submits the deployment, returning at PUBLISHING or PUBLISHED.
The opt-in property defaults to false for ordinary invocations.

The next **Wait for Maven Central publication** step polls only the persisted
Sonatype deployment ID until PUBLISHED; it never uploads or resubmits. The submission
properties are copied to `maven-central-submitted.properties` so the final JReleaser
invocation cannot overwrite the input bound by the confirmation receipt. Stale
output, submitted properties and confirmation files are removed before deployment. Failure or timeout blocks
the final GitHub release. The final `jreleaserFullRelease --exclude-deployer=mavenCentral`
retains the original release/upload/package/announce lifecycle without redeploying.
Plain `jreleaserRelease` also includes deployment in JReleaser 1.24.0 and is not a
safe wait-only command. This split does not repeat the Gradle verification graph.
