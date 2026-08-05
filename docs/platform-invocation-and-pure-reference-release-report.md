# Platform invocation and pure-reference correction report

This report describes the release-candidate correction that adds a public,
provider-scoped platform PROCESS boundary and makes Phase-B classification
representation invariant. It is an authored review record, not a substitute
for the generated release receipt.

## Certification contract

This authored document deliberately does not embed its own source commit or
artifact hashes. Doing so would change the commit that those values identify.
The generated same-commit receipts under `build/reports` are authoritative for
those values:

| Evidence | Authoritative generated receipt | Acceptance rule |
| --- | --- | --- |
| Source commit and `SOURCE_DATE_EPOCH` | `release-evidence/source-input.json` and `fragmented-processing/fragmented-processing.json` | Both identify the clean checked-out commit; the epoch equals that commit's timestamp. |
| Fixture totals | `fragmented-processing/fragmented-processing.json` | Language `153/153`; Contracts behavior `96/96`; gas `58/58`; total `154/154`; zero failed or skipped. |
| Platform representation matrix | locality evidence joined into `fragmented-processing/fragmented-processing.json` | All 16 representation/cache/read-mode cells pass, with identical semantic projections and no unrelated body or sibling demand. |
| Provider isolation and outcomes | test evidence joined into `fragmented-processing/fragmented-processing.json` | Strict invocation provider; all four provider outcomes; zero construction-time deriver calls. |
| Public API | `semantic-baseline/current-api.json` and `binary-api/final-1.0-baseline-to-candidate.txt` | Baseline and additive compatibility gates pass; Java 8 surface is documented. |
| Module JAR SHA-256 values | `fragmented-processing/fragmented-processing.json` and reproducibility receipts | Candidate and replica hashes agree byte for byte. |
| Architecture | `architecture/*.json` joined into the release receipt | Zero module cycles, package cycles, split packages, and undeclared edges. |
| Release gates | `final-quality/verification.json` and `fragmented-processing/verification.json` | `finalQualityVerify`, `rcVerify`, and `releaseEligible` all pass. |

These receipts are regenerated from the exact clean candidate by the official
two-invocation workflow. The earlier
[collection-paths report](collection-paths-and-cohesion-migration-report.md)
remains bound to its recorded implementation and must not be read as evidence
for this successor candidate.

## Public execution boundary

The host prepares one exact plan through
`IndexedDeliveryEvaluator.prepare(...)`, combines it with one borrowed
request-local provider in `PlatformProcessInvocation`, and calls:

<!-- blue-example: examples/src/main/java/blue/language/examples/RuntimeProjectionAndIndexedDeliveryExample.java#platform-process-invocation -->
```java
        IndexedDeliveryPreparation preparation = contracts
                .indexedDeliveryEvaluator()
                .prepare(
                        indexedRoot,
                        indexedEvent,
                        rootRevision,
                        eventOrderKey,
                        completeActiveIntervals,
                        orderedCandidateOccurrenceKeys);

        PlatformProcessInvocation invocation =
                PlatformProcessInvocation.builder()
                        .deliveryPlan(preparation.deliveryPlan())
                        .nodeProvider(requestLocalProvider)
                        .build();

        PlatformProcessingResult result =
                contracts.processForPlatformCommit(
                        rootReference,
                        eventReference,
                        invocation);
```

Root and event are the complete Blue semantic inputs. The plan, revision,
external order, active intervals, runtime-registry generation, provider, and
commit companion are verified execution environment. The supplied evaluator-
bound plan is independently checked against the exact Root and event and then
replayed through the authoritative plan/preselection verifier. This operation
does not invoke the `ExternalDeliveryPlanDeriver` captured when the service was
constructed.

## Strict invocation provider

Language opens a fresh provider/cache scope for the complete attempt. The
supplied provider is authoritative for admission, selected embedded scopes,
contracts and type chains, selected executable content, patch opening, final
soundness, and subscription validation. Provider candidates remain BlueId-
verified and preserve `FOUND`, `NOT_FOUND`, `UNAVAILABLE`, and
`INVALID_EVIDENCE` distinctions.

The strict scope does not append a construction-time provider, bootstrap
provider, or another invocation's provider-derived cache. A caller that needs
several stores must compose them explicitly before constructing the invocation.
Scope closure releases invocation-owned state and does not close the borrowed
provider or Language runtime.

## Pure-reference Phase-B correction

The defect appeared when Phase B pruned the supplied Root syntax before an
opaque `{ blueId: ... }` Root had been materially admitted. Later resolution
then exposed an unpruned dependency surface, producing a false dependency-
drift failure that the equivalent inline Root did not produce.

Classification now starts from exact admitted/materialized selected content,
opens only the selected scope ancestor/header chain, and then applies the
feeder-selected projection. Selected source Channels, declared same-scope
dependencies, processor-owned state, and required `Process Embedded` routing
markers remain visible. Executable bodies and unrelated reference/sibling
branches remain authored and cold until selected. The Phase-B/Phase-C equality
check remains active; real dependency drift still fails closed.

Classification operates on a detached projected Root. Opening selected header
content there never rewrites the authoritative admitted Root that later feeds
initialization, mutation, and publication. This preserves exact collapse/
expand behavior while retaining the effective header identity needed by
pure-reference processing.

## Portable runtime-registry binding

The plan binding uses the immutable runtime-registry generation identity. That
identity is derived from portable registration metadata: registered BlueIds,
processor kind, canonical or provider-required type evidence, declared type
identities, and ordered executable-body field metadata. Java class names,
object identity, allocation address, and processor instance identity are not
part of the binding.

## Required release evidence

The final candidate must demonstrate all of the following in one clean source
lineage:

- direct public processing of an evaluator-produced plan with zero calls to
  the construction-time deriver;
- independent rejection of every forged/stale Root, event, registry,
  revision, order, delivery, contribution, dependency, activation, and
  canonical-order variant;
- exact preservation of all four provider outcomes and absence of hidden
  fallback;
- concurrent invocation-provider and cache isolation;
- semantic equality for `INLINE`, `PURE_REFERENCE`, `PARTIAL`, and
  `FRAGMENTED` Roots across `COLD`/`WARM` and
  `UNBATCHED`/`BOUNDED_BATCH` provider modes;
- zero unrelated sibling/body requests and exactly the selected executable
  demand in the production-shaped deep graph;
- collapse/expand equality for the resulting exact fragmented Root;
- Java 8 compilation, public API/documentation gates, zero architecture
  violations, deterministic artifact replicas, and the complete clean release
  gates.

The companion JMH entry point is
`DeepGraphPhysicalLocalityBenchmark.processPlatformCommit`; its execution
commands and allocation caveats are documented in the
[developer process](developer-process.md#focused-verification). Performance
observations do not replace any semantic or release assertion above.

## Certification procedure

From the clean candidate commit, derive `SOURCE_DATE_EPOCH` from `HEAD`, then
run `clean build` followed by `finalQualityVerify rcVerify` in a separate
Gradle invocation. A reviewer should copy the exact source commit, module JAR
hashes, public API digest, fixture/matrix totals, and release result from the
generated receipts; the command transcript alone is not the machine-readable
record.

Passing these gates certifies the candidate represented by the receipts. It
does not by itself authorize publication or claim that a downstream
Coordination implementation compiles or processes a pure-reference Root.
