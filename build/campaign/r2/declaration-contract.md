# D2 declaration and certification contract

Development candidate; no sealed Language 1.0 release is claimed.

## Public entrypoints

| API | Established by success | Not established |
| --- | --- | --- |
| `BlueCodec.parseSource` | Authored wire shape and lexical scalar rules | Type ancestry, fixed-value compatibility, completed presence |
| `BluePreprocessing.preprocess` | Admitted transformations and alias/import binding in the configured environment | A completed value or universally satisfiable definition |
| `BlueResolution.resolveDefinition` | Definition vocabulary, known kind compatibility, fixed content and locally provable contradictions; retained absent-payload obligations | Existence of any completing instance; required presence at declaration-only paths |
| `BlueResolution.resolve` | Completed merge and schema obligations in its semantic closure | Unfetched pure-reference target conformance |
| `BlueResolution.resolveLimited` | `ESTABLISHED` covers the demanded semantic closure; `ABSENT` covers absent demands | Whole graph certification outside demands; target certification from cold root-reference acceptance |
| `resolvePreservingPaths` | Surrounding resolved graph with explicit authored subtrees preserved | Validation/completeness of preserved subtrees |
| `BlueIdentity.canonicalIdentityInput`, `sourceDocumentBlueId` | Canonical Source identity using definition preparation; pure root references remain cold | A completed instance or fetched target certificate |
| `directBlueId` | Identity of strict exact input | Type or schema conformance |
| `CanonicalIdentityInputBuilder` | Projection using the supplied invocation's complete type evidence and exact source | Payload certification beyond that invocation's goal; it is not a validator |
| `BlueResolution.minimize` | A smaller authored definition-compatible overlay | Completion of deferred payload/required obligations |
| `BlueGraph.specialize` | A new authored node with definition-compatible overlay and valid supplied fixed payload | Completed instance presence. The focused runtime preprocesses the assembled authored source; low-level NodeSpecializer takes preprocessed nodes |
| `BlueGraph.expand` / `expandLimited` | Verified exact content, limited to demanded closure when bounded | Type/schema validation; a changed content identity |
| `BlueGraph.collapse` | Exact content identity hidden behind a reference | Target validation or permission to hash a resolved runtime representation as exact input |
| Mutable/frozen `BlueMatching.matches` | Directional type/shape/predicate match on relevant evidence | A general whole-value certificate. Absent optional payload defers valid schema predicates; malformed schema still fails |
| `BlueSnapshots.resolve` / `load(Node)` | Immutable canonical/resolved pair for resolution's closure | Conformance of undemanded pure-reference targets; constructors themselves do not manufacture resolver evidence |
| `BlueSnapshots.load(String)` | Verified target content followed by completed resolution | Completion of its undemanded inner references |
| `ConformanceEngine.check` | Compatibility/conformance of the preprocessed input's resolved closure | Target conformance of cold references. Its legacy false result also includes unavailable evidence, so use limited resolution for structured diagnostics |
| `isSubtype` / `ConformanceEngine.isSubtypeOf` | Verified type ancestry relation (definition goal for inline ancestry) | Completed-value presence or payload validity of a distinct instance |

No name classification, `isType` field, fabricated sample, or identity-affecting operation flag is used. A custom type named Integer that derives from Text stays Text-compatible.

## Partial-payload rule

Definition admission is conservative: once payload is supplied or inherited, all applicable bounds are checked now. Prefixes below a minimum are rejected even if a later specialization could extend them. Omitted payload retains its obligations. This is an intentional compatibility change for callers that previously stored underfilled fixed prefixes.

| Input at constrained path | Prepare definition | Complete instance |
| --- | --- | --- |
| Omitted Text/List payload with minimum | Retain constraint | Reject at active root/required path |
| Optional absent child with required descendants | Retain constraints | Descendants remain inactive until parent has semantic payload |
| Empty object `{}` or empty list `[]` | Present fixed payload; enforce min/max | Present; enforce min/max |
| Supplied Text/List/object prefix below minimum | Reject | Reject |
| Supplied or inherited fixed payload within bounds | Accept if other rules hold | Accept if active required descendants are satisfied |
| Supplied payload above maximum | Reject | Reject |
| Required declaration-only child | Retain obligation | Reject when parent is active and no child payload exists |
| Crossed or same-kind numeric bound contradiction | Reject even without payload | Reject |
| Empty effective enum / known primitive-domain mismatch | Reject even without payload | Reject |
| Required declaration-only field plus maxFields: 0 | Retain; no object payload supplied yet | Cannot complete with the required field under maxFields: 0 |

The last row is an explicit local-consistency boundary, not a satisfiability claim. No general schema solver was added. Wire syntax has no separate empty-object payload property: the explicit `{}` case is exercised as the actual child contribution, including inherited empty objects.

`PartialPayloadMatrixTest` exercises 18 distinct rows across direct, inline-type, referenced-type, nested direct, nested reference and imported forms. Further tests pin empty-object presence, required/optional descendants, fixed inherited values and specialization. These dimensions are semantic cases, not duplicate test-count loops.

## Evidence, limits and cache ownership

Caches retain exact identity or representation evidence, never a substitute for current definition validation. Schema-bearing reference types replay verified canonical content, and exact schema structural keys prevent sharing across different retained keyword type/provenance representations. Limited resolution owns its evidence memo and unique-ID expansion budget for one call, including detached enum/type probes. Repeated warm calls cannot consume a previous call's limited certificate.

Outcome handling for Mini/Contracts adapters:

- `ESTABLISHED`: consume only the requested closure; a pure reference value certifies that reference, not the remote target.
- `INCOMPLETE`: expose outstanding exact BlueIds and provider outcome; retry when evidence/budget changes. Provider throws and wrapped budget failures preserve this outcome.
- `INVALID`: malformed content/contradiction or invalid evidence; provider `INVALID_EVIDENCE` is preserved independently of message text.
- `ABSENT`: demanded paths are absent after completed demanded resolution.

For storing definitions, call `resolveDefinition` for preparation and `canonicalIdentityInput` for exact stored content, then derive its ID. For invocation payloads, call `resolve` or `resolveLimited` on the actual supplied instance. For target certification from a pure reference, demand/verify the target with graph expansion or load-by-ID, then complete it. Use matching as a predicate only. No Mini endpoint or Contracts executor was edited, and SDK success is not HTTP or dispatch success.

## Normative changes and integration

Primary: `blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md`; mirror: `blue-conformance/src/main/resources/language/1.0/spec.md` (byte equal).

- Proposed §8.1.1: definition preparation versus completed certification.
- Proposed §9.2.5: explicit partial-payload admission, required/maxFields local boundary, cold references, and cache/limit conclusions.
- §9.6: exact Integer/binary64 comparisons, divisibility and deterministic effective mixed/Double LCM normalization.
- §9.8/§9.8.1: subtype-domain enum membership versus exact scalar identity; exact-reference singleton; only exact duplicate removal/canonical ordering.
- §9.9: all lower/upper pair contradictions; exact enum intersection and multiples.

The released conformance package's two payload-free normalization cases still request `resolve`; they require `resolveDefinition` in the adopted direction. `fixture-definition-goal.patch` is a concrete patch for the fixture adapter, closed operation vocabulary, schema, and those two inputs. It deliberately does not regenerate authoritative digests. Applying the fixture changes without regeneration was tested and the package correctly refused its mismatched file digests; the packaged files were restored, and A2 owns applying this patch with regeneration. Simplified schema serialization now retains explicit large Integer and custom enum types (and no longer silently discards invalid keyword labels); emitted source can preserve those constraints on reparse. No broad identity/gas golden was recaptured.
