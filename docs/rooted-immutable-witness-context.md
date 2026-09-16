# Separate immutable witness contexts

This isolated candidate starts from Language
`3491c515362bda55b72c8cc0e3558ad760ea9f86`. It corrects private graph construction
for the existing RCP-SCOPE-04 and RCP-OWN-01/02 witness distinction. It does not
change the specification, public API, identity constructors, source-epoch rule,
or Coordination scheduling and publication authority.

## Confirmed failure

The diamond acquisition diagnostic retains the original B2/C0 input and adds
independently authenticated immutable A11 and D14 primary witnesses. A11's own
complete proof refers to historical D2. Both source proofs are valid; selecting
D14 does not authorize changing A11's `/peers/d` reference.

The previous calculation graph retained every immutable-to-immutable active
edge solely because both lineages were witnesses. Finalization consequently
substituted D14 for D2 inside A11 and correctly failed the unchanged exact-primary
check. Diagnostic D02 confirmed that only A11's exact identity and that one
reference changed; B2, C0, D14 and all component generations stayed unchanged.
The preserved evidence is `diamond-acquisition-02.tar.gz` under
`legal-detached-retarget-evidence.fKnxrU`, SHA-256
`d9ca563c2b8766e5a9e51bc1d0d2a804ccaab5b7adcfccd649536478d4aba45f`.

## Narrow correction

Private calculation graphs retain the existing authenticated witness state,
not just its source-lineage set. An active row from an immutable source
calculates only when its target is also immutable and the row's expected
target BlueId equals that target's authenticated selected primary BlueId.
The complete original row remains present when the target belongs to another
exact witness context; it is not rewritten or rebound to the selected alias.
Live source rows retain their ordinary behavior. Exact same-context edges,
including self-cycles and complete cyclic components, remain calculating.

This is an exact-reference coherence check, not a freshness or generation
policy. Full proof verification and all existing source body, row inventory,
epoch, lifecycle, component generation, exact identity and component proof
checks remain mandatory. No proof-list order, Java object identity or common
publication identity selects authority. Incompatible cyclic evidence still
has to pass the unchanged full component finalizer and exact-primary checks.

Every witness-aware graph reconstruction carries the same private state.
PROCESS component-finalization gas frames use the graph and generations
actually produced by the verified kernel. Full active-row ADD/REMOVE,
graph-generation and topology accounting are unchanged. A birth retry retains
the already authenticated witness state while adding only its ordinary
inactive birth rows; it cannot flatten historical proofs into current views.

Ownership promotion deliberately continues to use the full active graph.
When a real live join reaches a witness, the existing monotone rule releases
that witness role and ordinary finalization/rebinding resumes. The change
does not mint owners, move frozen source heads, relax CAS, skip reactions,
relabel numbered epochs, or add a terminal reconciliation exception.

## Qualification boundary

Eight focused regressions cover mixed contexts and equivalent/reordered
proofs, missing/forged selected evidence and rows, equal-BlueId targets with
independently verified component generations, same-context SCC and self-cycle,
PROCESS/retained-result preservation with low-gas rollback, live-role promotion
with ordinary rebinding and subsequent split, and demand-bound birth retry.
The first focused run on `390fc752` passed 19 of 20 tests. Its only failure was
the new birth fixture's name-only child, which lacked structural object-scope
evidence; the fixture now supplies a direct known lifecycle Contracts envelope
and reports any complete failure diagnostic. No production change accompanies
that correction. The preserved focused-run archive has SHA-256
`95e6fdfce3dcd0afffbf4d610c7979ab74860b8153b1d1b334c0bec168a5d080`.
The corrected source `64f368c0748cdc7fa5afd9615d88d92ff0ccb73b` passes the
complete Contracts module test task: **578/578 tests**, in **35 seconds**, including
the eight regressions. Archive
`legal-detached-retarget-evidence.fKnxrU/immutable-witness-context-contracts-02.tar.gz`,
SHA-256 `0cfd8dbb3ef46d83ec9dd6c35aedc70d6ab05e5f4d7b96413bc8968ca06cff4a`.
This is not a full Language clean/quality or downstream diamond pass.

## Exact implementation binding

Supported `regenerate_package.py --stage-release-output` generation ran from
that clean source, with the canonical full-lifecycle `--fixture-source-root`,
into a new external `witness-context-generation.EjLF8P/release`. The complete
383-file Contracts package differs only in `release-manifest.yaml`: the eight
changed runtime implementation hashes and the derived release identity
`sha256:14a9653062c2b4d456c54313db55d22fe92573bd5bcf4f3e87b50400918116f1`.
All other 382 files are byte-identical, including the 295 executable fixtures,
gas, events, traces, receipts, registries and oracles. Specification and fixture
package identities remain unchanged. No semantic golden is re-derived merely
to accept this correction.

The exact generated manifest and active release consumers are rebound together.
The rooted semantic baseline changes only its two Contracts-release fields;
historical provenance, gas/count fields and the immutable historical baseline
remain intact. Matching conformance assertion literals and the current generated
reference binding change, without removing assertions. Historical migration
reports and classifier transition pairs are unchanged. These metadata changes
have not yet received a post-rebind test run.

The exported 3491 worktree and its artifacts remain unchanged. A new committed
Language export, dependent BEX/Catalog/Coordination bindings, downstream diamond
acquisition and publication/restart verification, and final exclusion-free
clean/quality gates remain required. Accepting this graph preparation alone
does not establish diamond or ring convergence.
