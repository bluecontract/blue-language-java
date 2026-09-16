# Fresh rooted witness selection

This isolated Language candidate starts from `60043c38e692af1e1951fa40d4337b4e684eb5e8`.
It adds one state-construction API; it does not change the rooted specification,
processing tariff, epoch classification, publication rules or admitted-input
retry contract.

## Problem

The real dormant diamond terminal capture has exact B23/C21/A17 primaries but
an immutable D5 primary. D has independently published the required same-cause
D23 prefix. A17's complete historical proof still contains D5 and must remain
unchanged. The mirrored D capture similarly contains B5 while B23 is already
independently published. Existing current-head checks correctly refuse to
publish the older peer as though it were the current owned head.

This is a new terminal input, not an already admitted invocation. Nevertheless,
the existing public state constructors cannot express the fresh selection:
ordinary closure construction has no immutable proof-context state, while
`rootedReadExpansion` deliberately preserves every original primary. Replacing
D5 inside that admitted-input expansion or rewriting A17's own proof is not a
valid solution.

## Solution and reason

`ClosureEvidenceFactory.rootedWitnessSelection(original, selectedWitnessProofs)`
returns an `AffectedClosureSnapshot`, not an invocation or result. Nonempty
selection requires the original library-authenticated immutable witness roles.
Only those existing noncalculating primaries can be selected. A target of a
pending occurrence from a calculating source is protected, so the frozen A17
source anchor cannot be selected through this API.

For each selected lineage, the factory verifies the supplied complete snapshot,
including its closed identity and binding-set identity. The primary body,
BlueId, epoch, lifecycle state, component generation and source-owned rows come
from that proof as one exact tuple. Full proofs for all other witnesses remain
unchanged. Thus selecting D23 does not replace D5 inside A17's proof. Public
roots, owner/live values and rows, unselected witnesses and graph generation
remain unchanged. D23 remains immutable: only the existing full-live-graph
ownership rule can release its witness role at a real join.

The initial supported domain has a fixed primary-document inventory and fixed
source-owned occurrence-identity sets. New or removed occurrences, retargeted
occurrence identities and missing primary endpoints are rejected explicitly.
The map of complete source proofs leaves room for separately reviewed support
for additional closed inventories later; this candidate does not guess missing
documents or silently widen that domain. Same-context cyclic components still
need their complete compatible proof; incompatible selections fail unchanged
component finalization and exact-primary checks.

The factory recomputes the selected component partition and closed state identity
without substituting finalized values for the chosen primaries. It neither
calculates a handler nor advances an epoch, creates a receipt or grants
publication. The host must authenticate same-cause provenance, actual terminal
eligibility, the required peer prefix and current-head CAS before publication.
In particular, arbitrary valid history hashes are not durable authority.

The caller must admit a **fresh** rooted operation on this state. The old entry
context rejects the new closure identity, and `rootedReadExpansion` continues to
reject replacing an original primary. The API is not a route to reset a meter,
change a cause, redeliver work or reopen a completed/failed operation. Ordinary
PROCESS work still uses the same logical meter and finalization frames; proof
construction itself is state preparation, not a semantic processing attempt.

## Qualification boundary

Focused regressions cover mixed old/new proof contexts, exact owner and pending
anchor preservation, forged identities/bodies/positions/rows/components,
unsupported inventories, equivalent proof values and defensive copies,
same-context cyclic proofs, real role promotion, fresh-entry and old-entry
fences, exact gas/rejected-charge replay and birth-retry propagation.

Clean implementation `00b745fea8b83eca4a2cfc7558048cf3ecff600d` passes the
parent-owned focused gate: **27/27 tests**, 17 seconds, including all eight new
regressions plus witness-context, rooted input-expansion and birth-retry controls.
It also passes the complete Contracts module: **586/586 tests**, 33 seconds,
with no failures, errors or skips. The focused archive is
`legal-detached-retarget-evidence.fKnxrU/language-witness-selection-focus-01.tar.gz`,
SHA-256 `76a4e8c215d72295dcd5e1eb08100657732e8e35c575ddff9b77986078ff6b32`;
the full-module archive is `language-witness-selection-contracts-01.tar.gz`,
SHA-256 `d84681617f7fbca48d49ce8cc96d26462ac4e8c460bb127497cc01b781004289`.
These are distinct scopes, not 613 distinct tests.

The maintained implementation-inventory generator adds exactly the new private
`RootedWitnessSelection.java` path, taking the fixed six-module source closure
from 741 to 742 files. Production and test bytes remain those of the tested
commit. The existing public `ClosureEvidenceFactory` gains one static method;
there is no new public class, changed descriptor or removed API. Its generated
API reference must be refreshed from the maintained `generateDocumentationReferences`
task after the release binding is reviewed, not by editing a guessed inventory.

No generated release package or active release binding is refreshed here, and
no exported Language worktree is modified. Supported full staging, exact delta
review, maintained public-API/reference and release binding regeneration,
downstream terminal acquisition and publication tests, and final clean/quality
gates remain required. The state constructor alone does not establish dormant
diamond or ring convergence.

## Reviewed generated binding

The parent-owned supported full generator ran from clean `90874a03` with the
canonical full-lifecycle fixture source, Java 17 and isolated Python 3.13. Its
retained output is `legal-detached-retarget-evidence.fKnxrU/witness-selection-generation.VOkg0r/release`.
All 383 Contracts package paths were compared: only `release-manifest.yaml`
changes. Its SHA-256 is `8e15b0487bc8c464b6f072eadcf1ad2a642744aded957c5e4eb9aaf7f1f23500`.
The two existing implementation digests are `ClosureEvidenceFactory.java` and
`RootedWitnessFrame.java`; the only new path is `RootedWitnessSelection.java`.
All 742 recorded implementation digests match that source. No specification,
fixture, outcome, receipt, gas or ordering bytes change.

The generated Contracts release is
`sha256:433838a407fb8dffab7ef4f84cb47a2a0cf95595ad0632bbb54462f266a1accc`.
The fixture package remains
`sha256:9323cd0b2b4202c08d8165a99102aa6d8a52f3e718fc33647e34ba211859a60f`.
The actual manifest, active release consumer and corresponding exact test/baseline
bindings are imported together. Historical characterization bytes and both prior
transition approvals are retained unchanged. The separate new exact-pair review
record has SHA-256 `1d87d1a6110b47ae82f93c4b932f9857879a13058d5ed236476a76ff87e24409`.

This binding candidate is suitable for the next diagnostic DEVELOPMENT export,
not a release qualification claim. The parent-owned new exact-pair classifier
tests pass **7/7**, 10.738 seconds; the strict classifier on the actual generated
package reports one changed manifest and zero unexpected changes. Maintained
generated API/documentation references and final gates remain outstanding.

## Qualification-only audit refresh

The first parent-owned `releasePreflight` on clean binding `05bb6f31` stopped
after eight seconds because the tracked empty-sentinel audit was stale. It did
not run the documentation generator. The retained log is
`legal-detached-retarget-evidence.fKnxrU/language-reference-generation-01.log`.

Both maintained static generators were run with isolated Python 3.13 into
`legal-detached-retarget-evidence.fKnxrU/witness-selection-static-reports.NYf04j`,
and their complete differences were reviewed before importing the report bytes.
The fixed RC baseline remains `be2260217d1dbab0c7b60bcbd28073a5955e2b7b`.

The sentinel JSON changes only 26 source-line fields: two unchanged factory
Javadoc hits move by 29 lines after the new public method, and 24 unchanged
`ClosureExecutionSession` hits move back one line after the earlier witness-context
graph-call change. Every source string, classification, reason, covering-test
declaration and summary count is unchanged; all moved hits remain context-only.
The generated sentinel Markdown is byte-identical. The failure printer's
`BlueConformanceGraphOperations` locations are not actual report differences:
its diagnostic index collapses repeated equal source strings within a symbol.
Neither that diagnostic implementation nor any sentinel rule is changed here.

Separately, the identity-impact inventory still described implementation hashes
from before the reviewed witness-context and witness-selection changes. Its
refresh updates nine existing source hashes and the current Contracts release
identity, and adds the one new private selection helper. The artifact count
therefore changes from 819 to 820. All 742 implementation hashes were checked
against the actual source. Existing classification, historical evidence,
specification, fixture, oracle, gas and ordering identities are unchanged;
active stale references, unresolved artifacts and mirror mismatches remain zero.
The generated Markdown changes only the corresponding rows and count.

These are current-source audit updates, not replacement historical baselines or
new semantic approvals. The aggregate release manifest already passes its
maintained static check and is not rewritten. No runtime, test, generated
package, classifier rule or API baseline is changed by this qualification-only
refresh. The static `--check` commands validate both installed audit reports;
Gradle, documentation generation and fresh final qualification remain separate
parent-owned gates.

## Generated reference refresh

Clean qualification source `3718c63d` passes the parent-owned `releasePreflight`
and `generateDocumentationReferences` invocation in 478.341 seconds. The actual
release conformance report is 480/480 passed, zero failed/skipped. The existing
transition and checker prerequisites also execute; their counts are separate
from that 480-case report.

All eight generated reference files were compared. Only two change: the
conformance reference's active Contracts release identity and the public API
reference's new `rootedWitnessSelection` method plus derived counts (one added
method, no removed descriptor or new public type). The maintained reference
copy task preserves the generator's exact bytes, including trailing blank
lines; all eight tracked references then match their generated files exactly.
This updates documentation, not an API compatibility baseline.

Generation evidence is retained as
`legal-detached-retarget-evidence.fKnxrU/language-reference-generation-02-complete.tar.gz`,
SHA-256 `a1bbb90f4fe29ed4f0e3276c9b3f478c44901e1fd9a76e571357a5aacce554e0`.
Final clean build, quality/RC, binary API and complete downstream qualification
remain required on the newly frozen source. No runtime or fixture bytes change
in this reference-only successor.

## Physical ownership qualification correction

The parent-owned clean build of `53279085` exposed a stale current inventory:
`PhaseFourModuleOwnershipArchitectureTest` compares all physical production
paths with `architecture/module-ownership-1.0.json`, but the manifest omitted
the new private `RootedWitnessSelection.java` helper. There are 817 physical
sources and only 816 recorded assignments; all 1,123 resources already match.
This isolated successor preserves the running qualification worktree.

ADR 0007 requires the phase-labelled ownership manifest to describe the current
physical tree. Its extraction commit and original ADR counts are historical
evidence, not permission to omit a later file. The correction adds exactly the
helper's conventional `:blue-contracts-core` owner record, updates the source
count to 817 and recomputes the sorted newline-delimited source-path digest.
The test's exact expected source count becomes 817. Source/resource equality,
unique sorted ownership, package exclusivity, import boundaries, the exact DAG,
resource count and all other assertions remain unchanged.

No production source, resource, semantic fixture, API relocation ledger,
dependency-ownership ledger or historical evidence changes. Static checks
confirm the corrected complete ownership map and digest against the physical
tree. No Gradle or JVM work is run for this correction; the parent-owned full
gate and subsequent exact-source qualification remain required.
