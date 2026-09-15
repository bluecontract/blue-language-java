# Language / Contracts baseline reconciliation — 15 September 2026

## Source boundary

The writable candidate starts at freshly fetched merged `origin/next`
`450230c18a41df8f873fb761d516f1bc85d7b5bd` (RC26). Its common ancestor with the
only approved donor, `e28ce80531663074f780367238785a5cc7f5171c`, is
`806536457fd2ff284159fe65439973aa0f02ca4f` (RC25). The upstream interval contains
merged PR37 processing-event continuity, its source-location audit refresh, and
RC26 metadata. PR36/reference-canonical-parity is absent from the pinned base;
its open head and APIs are not inputs to this reconciliation.

The frozen donor is read-only. This document records disposition against actual
merged source, not against promises in historical PR descriptions. Historical
qualification is evidence for why corrections exist; it does not qualify this
combined source. POC branches, artifacts and optimizations are outside scope.

## Correction inventory

| Problem / example | Pinned upstream solution | Baseline disposition | Remaining delta | Regression evidence / required new gate |
| --- | --- | --- | --- | --- |
| Later detach B / attach exact current or historical C at `/agreement` is rejected or loses its reserved generation. | Upstream supports retired same-lineage slot reuse but has no exact inactive different-lineage selection predicate. | Still needed; retain the approved normative amendment. | Demand-bound C selection uses the reserved generation and new C occurrence/binding identities; current C activates with ADD, historical C alone catches up. Preserve same-invocation retirement rejection, immutable inputs and complete receipts. | `RootedInactiveRetargetTest`, `DifferentLineageRetargetReceiptTest`, `ManagedOccurrenceDemandDiscoveryTest`, existing same-lineage/retirement controls. Donor historical focused 94/94; fresh combined run pending. |
| Imported numbered source receipt has a deferred representation tail; reference-only finalization spuriously advances its source epoch. | PR37 preserves admitted processing-event identity and is orthogonal to the source-epoch classifier. | Still needed, with upstream event threading preserved. | Narrowly accept already-reconciled numbered successor carriers under the existing exact imported-event and complete reference-only explanation guards. Real local mutations and ordinary older nonterminal imports remain epoch-advancing. | `FullLifecycleAdmissionTest`, `ManagedCheckpointSettlementOwnershipTest`, and merged `ProcessingEventClosureTest`. Historical focused 62/62; fresh combined run pending. |
| Immutable A11's proof contains D2 while another exact witness selects D14; one calculating graph rewrites A11. | Upstream still filters witness edges only by lineage membership. | Still needed. | Carry complete immutable proof contexts through graph reconstruction, retry and actual kernel finalization/gas frames; only coherent exact witness edges calculate. Keep complete rows and live ownership promotion. | `RootedWitnessContextTest`, full Contracts owner/graph/rollback controls. Historical complete Contracts 578/578 at its checkpoint; fresh combined run pending. |
| A fresh terminal cannot select a completed exact same-cause peer without changing its old admitted input. | No merged equivalent state-construction API. | Still needed. | Add `ClosureEvidenceFactory.rootedWitnessSelection(...)` with complete authenticated proofs and fixed inventories; protect owners and pending anchors; require fresh entry. No publication or durable scheduling authority is added. | `RootedWitnessSelectionTest`, rooted input-expansion, birth-retry and downstream terminal-acquisition controls. Historical complete Contracts 586/586 at its checkpoint; fresh combined run pending. |
| Typed/untyped nested inline children diverge from exact references or lose Source identity after minimized cold reload. | PR36 is not incorporated; the RC25 reconstruction/minimization code is unchanged. | Still needed on this pinned base. | Retain explicit versus inherited custom-type contributions and pass the same preprocessed Source into the additive minimizer overload. Preserve old overload behavior, exact references, logical list positions and removal of derivable fields. | `NestedValueReferenceIdentityTest`, `SourceAwareMinimizedOverlayTest`, existing minimization/reference/selected-scope controls. Fresh combined run pending. |
| Named Compute cannot distinguish exact authored `constants` / `functions` from effective defaults. | No merged working-occurrence provenance API. | Still needed for Coordination consumers. | Add immutable `WorkingDocument.sourceContributionsAt(String)` through invocation-bound verified working/reference/type paths. Exclude own type defaults from direct contributions; expose current patches without synthesizing identity. | `ReferenceTransparentExecutionTest` and Coordination exact-provider named/direct controls. Fresh combined run pending. |
| A valid `$pos` / `$replace` drops the new selector's retained slot-type definition. | Existing upstream list resolution already retains type constraints correctly. | Still needed only in the new provenance selector; not an upstream list-resolution fix. | Preserve slot-type context for deeper paths while discarding replaced ordinary fields; do not inject Dictionary `valueType` defaults. | Four positional replacement controls in `ReferenceTransparentExecutionTest` (historical full owner 40/40). Fresh combined run pending. |
| Added APIs, one private source, approved specification amendment and current source locations change exact inventories and release bindings. | PR37 contributes its own API ledger/baselines, documentation and source audit; ordinary CI still lacks the donor's four PyYAML guard groups. | Adaptation needed. | Retain upstream event API descriptors and ledger entry; union additive baseline descriptors; preserve four exact historical transition controls and CI Python/PyYAML setup. Use maintained generators to bind the combined implementation, specification and fixture set; preserve historical releases and classification evidence. | API, module ownership, static guards, supported staged generation, documentation generation, clean build and final native quality/RC gates pending. |

## Alternatives and compatibility

The retained nested-identity path is necessary because the preferred alternative
is not merged into the selected base. No open PR implementation is copied. A
whole-Source hash/fallback minimizer would hide provenance loss and increase work;
the donor instead threads the already available Source occurrence.

Existing processing-event evidence added upstream remains the last argument to
`DocumentStepInput` in `ClosureExecutionSession`. The source-epoch correction
does not replace the event identity, suppress imported events, relabel a result,
or change gas tariffs. Immutable-witness changes retain the upstream full proof,
owner/CAS and input/retry boundaries.

The only normative change is the user-approved later detached-path retarget.
Other runtime changes correct identity, graph preparation, exact evidence or
epoch classification under retained contracts. Added APIs remain additive.

## Qualification status

The first full staged regeneration stopped at the managed-receipt exporter:
the candidate fixtures bound the current Language source projection, while the
runtime still declared upstream cyclic implementation identities. The unchanged
`DefaultClosureProcessor.verifyRuntimeBinding` rejected that mismatch. The
full-lifecycle exporter had compiled and run; no complete release stage or new
conformance PASS resulted from that attempt.

The maintained `rebind_cyclic_runtime_identities.py` explicitly requires running
before closure generation. Its read-only check confirmed stale bindings; its
supported write mode updates only two `ClosureRuntimeDescriptor` constants and
their existing module API inventory values. Both roles project exactly 250
Language model/core sources, excluding the Contracts descriptor itself, so this
binding has no self-referential hash dependency. The independent check after the
write returns the same values:

- Cyclic finalizer: `sha256:d4f8934f82e2330a165a612e4d9d72e0ceff1c7c0df37b0d08772e66629344c7`.
- Cyclic proof verifier: `sha256:bd14a60f67cbc9a19fadc4b0d5a4adc8ed7578151f0c14c2591835dd8b9f73a0`.

No algorithm, identity constructor, runtime guard or fixture assertion changes
in that bootstrap correction. The parent retains the failed run and captures
the updated source inputs before the second full regeneration attempt.

## Reviewed generation and exact-byte import

Generation02 completed through both maintained Java exporters using the isolated
baseline Python/Gradle environment. Its input is the pinned merged base plus the
captured tracked patch and untracked archive, not a claim that generation ran on
an already clean commit. The staged release remains immutable.

Before publication, complete comparisons against both the frozen donor and
pinned upstream found the same 383 package paths with no additions or removals:

| Comparison | Changed files | Executable identity leaves | Distinct bijective substitutions | Nonidentity executable changes |
| --- | ---: | ---: | ---: | ---: |
| Frozen donor to generation02 | 84 | 30,275 | 2,508 | 0 |
| Pinned upstream to generation02 | 86 | 30,410 | 2,509 | 0 |

Both comparisons change 80 executable fixtures and one trace through consistent
SHA identity propagation only. Every outcome, document/body/state value, numeric
gas value, checkpoint, list ordering and cardinality remains unchanged. All
295 executable fixture semantics are unchanged; 215 executable fixture files
are byte-identical. The two additional upstream changes contain exactly the
four legal detached-retarget prose leaves already approved in the frozen donor.
Registry and fixture manifests carry their derived fixture-package binding.
The donor release manifest changes 15 existing source digests; the upstream
manifest changes 27 existing source digests and adds `RootedWitnessSelection`.
All 742 implementation paths and all 742 hashes equal the current runtime
source inventory. No donor release manifest was adopted as current evidence.

The original strict classifier rejected both unapproved inventory pairs; those
raw reports are preserved. Only after independent review and root approval was
a separate hash-pinned two-pair record added. It freezes complete upstream and
generated bytes and reconstructs the donor from unchanged historical evidence.
The added guard rejects reversed, added, missing, renamed or changed inventories,
forged/rehashed review records, changed/rehashed source manifests, and altered
outcome, gas, ordering, state or identity expectations. No generic source-hash
allowance or automatic oracle approval was introduced.

Publication reused `regenerate_package.publish(candidate, destination)`, the
maintained atomic publication function, without rerunning exporters. The import
audit verifies exact equality across all 383 files and that staging was not
modified. The representation package changed only its specification hash and
derived identity; both executable JSON fixtures remain unchanged. Maintained
aggregate and Java resource-binding generators then refreshed current resource
constants, and exactly six rooted semantic-baseline fields were rebound while
all historical gas, locality, API and provenance evidence remained unchanged.

Current derived identities:

- Contracts release: `sha256:06ca8273a28f9fb0298ec6aae672c2558b613582b3e61d4c651e86399fe67631`.
- Contracts fixtures: `sha256:7bd3a699d1649bd20c715ef001d61a39547942fa7db3ab65c434a480e025db47`.
- Aggregate release: `sha256:4ff4aca93baf241f3141626e05d5a58d99aa5373b0654957ca81ee3e9a0df139`.
- Complete Contracts inventory: `sha256:fbdfa128fa447b06c97d72a13bc1b0952f69903f647ee1dc19b805113452da1b`.
- Exact reviewed transition record: `sha256:5b51d316637f06600efb3b2ae50e4d81e69dce00e6c18b7000a0cf59eb25445f`.

Independent static package validation passed all maintained pre-Java checks:
98 closure and 197 ordinary fixtures, 15,594 BlueIds, 424 DocumentIds, manifests,
checksums, schemas, gas, runtime/JCS vectors, managed revision/remove-readd
sequences, representation parity, limits, identity oracles, semantic reference
scenarios, and rooted companion checker/model validation. The receipt explicitly
does not claim full `PACKAGE_VALID`, Java template smoke or implementation
conformance. Resource-binding, aggregate, implementation-inventory and
identity-impact checks passed after import; the current package still exactly
matches immutable staging. Historical qualification records remain unchanged.

The parent committed the coherent candidate as
`5b2a1629ccb55a693b5c8391016ede4c26d508c5` and owns subsequent clean-source
Java module, native quality, immutable artifact and integration qualification.
This review does not itself claim release, merge, remote publication, or
published-dependency readiness.

The new exact-transition suite passed all seven controls, including both full
strict classifications and corruption negatives; the independent maintained
source-pin test also passed. The first clean-source affected-module batch ran
802 tests: Language Core 190/190 passed, and Contracts 609/612 passed. The three
Contracts failures were the lifecycle requirements 03, 10 and 14, all stopped by
one test helper's old reviewed cyclic-finalizer identity before reaching its
independent expected-value construction. Only that helper's two `REVIEWED_*`
role constants were updated to the approved generated bindings. Its original
`OLD_*` role constants, frozen duplicate-event/gas expectations and independent
invocation/event/work/charge/rejection constructors remain byte-identical, as
do outcome, rollback, gas, failure, ordering and retry assertions. No expected
runtime output was copied from a failure report; existing independent
constructors derive the new identities from the fixed inputs.

A whole-tree old-role scan found no other active Java consumer. The old pair in
five historical transition records and the rooted checker's self-contained
`resource-admission-real.json` fixture is retained as historical evidence; that
checker verifies the captured input against its own captured environment and
does not claim current production execution. The generated public API reference
still requires the maintained documentation refresh. The parent owns the
unchanged-source Contracts rerun and root controls before final qualification.

## Build and source-distribution follow-up

The unchanged-source rerun at `70d8d9b06572f5cc906a842c4ef486c5d1802e24`
passed all 612 Contracts tests and all 480 release fixtures (185 Language and
295 Contracts, no skips). The selected root controls passed 16/17; the sole
failure was the conformance build script's 160 lines exceeding the unchanged
150-line policy. Its five transition task registrations now share one
configuration block, reducing the script to 133 lines while retaining every
task name, Python command, input directory and both check/release hooks. No
test, threshold or release prerequisite was removed.

Source-distribution review found that the existing blanket archive exclusion
omitted three tracked byte images required by those mandatory transition
checks. A shared case-sensitive exact-path inclusion set now covers only
`classify-retired-slot-reviewed-after.tar.gz`,
`classify-legal-detached-retarget-reviewed-after.tar.gz` and
`classify-baseline-reconciliation-reviewed-inputs.tar.gz` under
`blue-conformance/src/main/tools/migration/`. Archive extension detection is
case-insensitive, while the approved paths remain case-sensitive. All other
archive/debris exclusions remain; generated copies and wrong-directory,
wrong-name or wrong-case archives are not admitted. The same rule applies to
clean-source projection and source-ZIP debris validation.

Four bounded build-logic tests cover those input sets, case/extension negatives,
after-marker archive mutation/removal, retained ZIP bytes and missing/unexpected
ZIP entries. The mandatory transition checks still own initial required-file
presence and exact reviewed-hash authentication. ZIP validation compares the
declared entry set; it neither authenticates review hashes nor proves that an
initially absent source input was present. Clean evidence binds changes after
its marker. The four other historical-only archives remain excluded; this is
not standalone rebuild coverage for every optional historical tool. No runtime,
specification, API, fixture or cyclic-role identity changes in this packaging
correction; new final clean-build evidence must follow the source commit.

The maintained documentation generator completed in the same rerun. All eight
registered outputs were reviewed; only `reference/public-api.md` and
`reference/conformance-fixtures.md` differed and were imported byte-for-byte.
They expose the three approved additive methods and current role/package/spec
bindings. Their complete API blocks match all seven compiled inventories; the
fixture reference matches the fresh 480-result report. Gas, host metrics,
packages, SPI, statuses and module graph outputs are unchanged, as is the
hand-authored `reference/processing-observations.md`. The generated directory,
declared input reports, hashes and exact diffs are retained externally. The
configuration/packaging regression rerun and final ordered native quality/RC
qualification remain parent-owned and pending; prior failure receipts remain.
