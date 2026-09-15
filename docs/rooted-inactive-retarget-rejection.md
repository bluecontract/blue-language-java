# Exact inactive-retarget rejection

> Superseded on 2026-09-12: the blanket inactive-retarget prohibition was a
> specification and implementation bug. A committed detach followed by a later
> attachment of another managed lineage at the same path is legal. The history
> below records the rejected interpretation and its old qualification only;
> it is not current behavior. See `rooted-detached-retarget.md` for the correction.

This is a focused runtime correction on the `806536457fd2ff284159fe65439973aa0f02ca4f`
Language baseline. It changes no authored syntax, public API/schema, binding
identity rule, or activation policy.

## Scenario and contract

An inactive reservation at P's path belongs to B. An actual operation installs
an exact value selected as C. Contracts §5.6 forbids retargeting an inactive
prospective or retirement-successor row; only an active different-lineage
`REBIND` allocates a successor generation. The original input row must remain
unchanged. Previously, the retry verifier could not admit exact foreign evidence
for this invalid effect, so a feeder could only keep requesting evidence or
incorrectly replace the frozen row.

## Solution and reasoning

The existing demand-resolution input can now establish **rejection**, never
activation permission, when all these facts agree: the original inactive,
pending-null row and its source/path; an initialized reserved source; and a
different initialized frozen target. The selected initialized epoch must be
nonnegative and no later than that target's frozen epoch. At the same epoch,
the target BlueId must equal the demand's exact value. Earlier historical
selection uses the existing exact host-resolution contract: the host verifies
source lineage, selected epoch and supplied BlueId in its retained history;
Contracts verifies the resolution's unchanged input/demand binding and consumes
it only at the matching actual effect. The primary C1 body is not claimed to
prove C0 membership. Existing cause, closure, graph-generation, endpoint and
demand-consumption verification remains in force. Future epochs, absent sources,
wrong paths, forged demand identities, equal-epoch wrong values and wrong causes
are rejected as input. Authored epoch −1 is outside this initialized rejection
predicate.

Only consumption of the actual processor-emitted demand recognizes that
contradiction. At the existing post-effect resource/reconciliation boundary it
throws `ManagedOccurrenceBindingMissing`, producing a terminal rolled-back
result with completed gas and no row replacement, publication, checkpoint or
managed receipt. `RootedInputExpansion` and output continuity verification are
unchanged. The original frozen C1 and inactive B reservation remain unchanged;
neither C0 calculation nor a second C witness role is necessary for rejection.
This uses the same historical lineage/epoch resolution boundary already used by
legitimate same-lineage history and active historical replacement. It does not
weaken source-history validation in the host or add an API/schema/evidence role.

The simpler early demand-discovery rejection was rejected: B's saved state can
have the same bytes as another current C view. Bytes alone cannot choose the
lineage. The processor regression uses exactly that alias and proves that
selecting B's saved epoch succeeds while selecting C rejects. Unknown,
ambiguous and same-lineage historical demands still use the unchanged discovery
and retry paths. This is not a feeder-side preflight rejection or an exception
translated into a fabricated processor result.

## Focused evidence

With Java 17, the new direct retry regression on unchanged production was red:
17 tests, 16 passed and the foreign-selection test failed at the old retry input
restriction. The same-lineage alias control already passed. The corrected code
passes 32 tests across four owners, including the existing original-occurrence
read-expansion guard and different-lineage receipt/retry negative controls.
These are focused results, not a full-suite or release-readiness claim.

The follow-up adds C1-already-frozen controls for both forbidden C0 selection
and legitimate same-byte B0 historical selection. Both inline and reference
forms retain all original rows and values. The four focused owners now pass
34/34 tests (the retarget owner grew from two to four tests). Downstream SDK
verification against a new clean immutable tuple remains a separate gate.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew \
  :blue-contracts-core:test \
  --tests blue.language.processor.closure.ManagedOccurrenceDemandDiscoveryTest \
  --tests blue.language.processor.closure.RootedInactiveRetargetTest \
  --tests blue.language.processor.closure.RootedInputExpansionTest \
  --tests blue.language.processor.closure.DifferentLineageRetargetReceiptTest \
  --max-workers=1 --no-parallel --no-daemon --offline --console=plain
```

Local red XML is retained under `build/retarget-evidence/retry-red/`; final XML
is under `blue-contracts-core/build/test-results/test/`. The downstream
Coordination SDK witness must additionally prove immutable read expansion,
terminal rejection, legal active retarget, source-history isolation and restart.

## Fixes-only release binding

The fixes were transplanted onto the same fresh `origin/next` baseline in
`7596ef862fff42b5b260ae6e457ed8dc3dad7158`, with a tree byte-identical to the
original fixes-only donor. Supported regeneration was staged outside the
repository with the exact full-lifecycle source directory. Its 383-file
inventory differs in only `release-manifest.yaml`: the three changed Java
implementation SHA-256 values and the containing `releaseIdentity`. All 382
other files are byte-identical, including all 295 executable fixtures, gas
traces, events, receipts and oracles. No expected semantic result was updated.

The unmodified strict classifier initially returned exit 2. It classified the
four actual leaves as a source identity rebind, but its historical complete-pair
allowlists did not recognize this newly generated manifest pair. Consequently
the C-EVO integrity guard reported old-baseline drift even for unchanged fixture
bytes. The new reviewed transition follows the existing exact-pair mechanism:
both complete inventories and the review-record bytes are pinned. Independent
manifest integrity checks remain in place. Any changed fixture, gas, source hash
or inventory fails closed; recomputing a release identity does not authorize it.
The tests reuse the already-pinned prior release archive and four explicit
manifest replacements, avoiding a duplicate fixture archive or a dependency on
future mutable checked-in fixture bytes.

The strict classifier now reports one identity-bound file and zero unexpected
or semantic changes. All 34 classifier controls pass: six new exact-pair and
mutation controls, five prior retired-slot controls, and the 23 original
classifier tests. The new six run through
`:blue-conformance:inactiveRetargetReleaseTransitionTest`, required by both
normal `check` and `releaseConformanceTest`. Generated identity-impact JSON and
Markdown change only the same four identity values; their categories, counts,
historical baselines and reasons remain unchanged. The aggregate manifest is
unchanged and verifies successfully.

The fresh focused Java/export gate passed 34/34. An exclusion-free clean build
passed 2,799 root tests, then encountered the macOS Python 3.9 timestamp parser;
the checker was not changed. Resuming with an isolated Python 3.13 environment
and the RC workflow's PyYAML 6.0.3 passed all remaining build tasks (946 module
tests). These incremental results are not the final clean-build attestation.
The final committed candidate still requires one exclusion-free clean build,
then `finalQualityVerify rcVerify` with the same commit epoch. No RC version,
tag, signing, publication or remote action is part of this source correction.

The next clean invocation exposed stale active conformance release pins. It
was interrupted after the diagnosed failures; it is not a completed suite.
The approved follow-up updates the conformance release constant, exactly two
active rooted-baseline release fields, and their four exact test assertions.
The historical baseline and all gas, state, locality, ordering and API evidence
remain unchanged. The affected four Java owners pass 32/32 with zero skips,
including the complete Contracts fixture inventory and the existing historical
baseline preservation proof. The six classifier controls also pass through the
new maintained Gradle task. The build workflow explicitly supplies Python 3.12
and PyYAML 6.0.3, matching RC verification, for that task; no threshold or Java
default changes.

After those tests, the subsequent documentation-generation sequence was stopped
to avoid repeating the same expensive corpus under host contention. The one
generated reference cell naming the release identity was updated mechanically;
all other reference bytes remain unchanged. This is **not** a claim that the
supported regeneration or final clean/quality/RC sequence passed. Those gates
remain required on the final committed source, locally or in CI, before release.
