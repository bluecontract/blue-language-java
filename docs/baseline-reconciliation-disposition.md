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

Source reconciliation and test inventory are in progress. No fresh PASS, release,
merge, remote write, commit or published-dependency readiness is claimed here.
The parent task owns the isolated Gradle/artifact lane and immutable candidate
manifest; generated bindings must describe this combined candidate, never a
copied donor implementation manifest.
