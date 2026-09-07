# Cycle rules for stabilization

Reviewed the user-supplied `/Users/piotr/data/blue-spec/latest/specs.zip`; preserved reference documents and cycle vectors with SHA-256 receipts under `build/stabilization/cycle-spec-reference`.

The archive is technical reference material. The stabilization task and approved empty/null semantics remain the active instructions. The archive predates those boundary revisions; importing its complete registries would revert approved changes.

- **Language §§10.2.1, 15:** document-reference cycles are supported; inheritance/type-chain cycles fail deterministically. Cyclic IDs do not provide fixed-point type semantics.
- **Language §§15.2–15.5:** the sentinel is exactly 44 ASCII zeroes. Calculate normalized preliminary IDs, order by those IDs, break ties using unsigned RFC 8785 canonical input bytes, reject indistinguishable members, remap internal edges to `this#index`, then hash the ordered list to obtain `MASTER`. Final member IDs are `MASTER#index`. Return mappings to original input order explicitly.
- **Language §§12.5, 15.5, E.7:** placeholders exist only in explicit cyclic calculation. Providers expose finalized member references and authenticate a member through its complete owning set. Ordinary fragments may retain an opaque member edge, but cannot independently hash or invent its body.
- **Contracts §§2.4.2, 5.3–5.10, 7.7–7.8, 8.12:** mutable cyclic roots require complete current proof, exact member mapping, stable DocumentIds and occurrence lineages, and current graph/component generations. Finalize the entire affected SCC and containing spines before later work reads a changed member. Repartition after graph changes without replaying completed deliveries. Publish all required state and effects atomically; failure rolls the invocation back.
- **Contracts §4.7:** processing order uses stable document/occurrence identities, independently of Language suffix order. A visited-document set cannot replace the occurrence queue. Deterministic gas and portable limits bound repeated reactions.
- **BEX §§4.4, 17.4:** static function-call recursion is forbidden. An already established final cyclic member is an opaque exact value; `$nodeBlueId` returns its identity without fetching or independently hashing the body. Structural access requires proof.
- **Coordination §§18.3, 19, 22:** cyclic/internal reactions do not create provider Timeline entries or timestamps. Imported history requires exact catch-up and completeness evidence; dependent later work cannot overtake it. Checkpoints, state, and public output commit together, including recovery after response loss.

The selected Language cycle algorithm, type-chain rejection, and member-provider verification sections are byte-identical to the supplied archive. Contracts cyclic mutation rules also agree. The selected Contracts §7.7 additionally measures an admitted historical successor witness before hashing it; this is a previously reviewed limit check, not a different cyclic identity algorithm.

Implementation review corrected these TypeScript boundaries:

- Cyclic placeholders are isolated to explicit calculation/parsing/preprocessing paths; ordinary provider requests and Source resolution reject them.
- Cyclic member lookup requires an authenticated owning cyclic set, rather than an ordinary list with an indexed lookup.
- Preliminary ordering includes the canonical byte tie-breaker. Schema constraints remain in Source-derived cyclic identity; shared contract edges are remapped exactly once.
- Ordinary reference expansion and resolution distinguish deeper recursive materialization from same-path inheritance cycles and reject the latter deterministically.
- RFC 8785 serialization rejects lone Unicode surrogates in both keys and values, without additional normalization.

The supplied two-/three-member identity oracles and 15 additional cycle cases pass, alongside 15 maintained self-reference cases, 12 evidence cases, and 34 independently executed Java identity fixtures (78 tests). The generator suite passes 81 tests, including exact executable-body ownership and explicitly reviewed source migration boundaries. The full TypeScript Language run remains red; its separate repair inventory is retained. None of these results certify MyOS reaction-cycle, gas, restart, or atomic-publication acceptance, which remains outstanding.

The catalog graph exhaustion observed earlier was repeated DAG expansion, not evidence that cycle rules should be relaxed. The selected Language specification also requires stale `$previous` prefix rejection; the TypeScript shortcut treating it as an optimization hint has been removed.
