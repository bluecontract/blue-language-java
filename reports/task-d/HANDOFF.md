# Task D candidate: definitions, conformance and representation parity

This is a local development candidate. The new declaration/enum clauses are
**proposed pending campaign review**, not an approved release contract.

## Checkout and scope

- Imported project: `/Users/piotr/data/blue-language-java`.
- Actual isolated worktree: `/private/tmp/codex-campaign-type-definitions`.
- Branch: `codex/campaign-type-definitions`.
- Base: `41a29dae6893776ebb3fb927e56dbde8d5fe0939` (expected and actual).
- Initial worktree state: clean, attached to this new branch. The original
  checkout was clean on `codex/empty-objects-embedded-collections-bex-null-alignment`.
- No applicable `AGENTS.md` was found in repository/ancestor locations.
  `CONTRIBUTING.md` and `docs/developer-process.md` were read and followed.
- No other checkout was edited, reset, rebased, merged, pushed, tagged or published.
  No Task B orchestration changes or application endpoint changes were imported.

Primary specification:
`blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md`.
Authoritative mirror:
`blue-conformance/src/main/resources/language/1.0/spec.md`.
They are byte-identical. `specification.patch` contains the exact changes to both.

## Minimal before/after reproductions

| Input/operation | Base result | Candidate result |
|---|---|---|
| Gender: `name: Gender; type: Text; schema: {enum: [female, male]}`, Source canonical preparation | Fails enum scalar-payload check | Prepares/stores without a sample value |
| Completed root with that exact Gender body inline, no value | Syntax exemption accepts | Rejects missing enum payload; reference spelling agrees |
| Gender instance `female` / `male` | Exact Gender scalar identity differs from bare Text enum member | Passes by proved scalar-domain membership |
| Gender instance `other` / incompatible scalar kind | Must reject | Rejects in both equivalent representations |
| Foo: `name: Foo; bar: {type: Integer}` | Valid definition | Valid definition |
| Programmatic Foo `bar.value("bad")`, `ConformanceEngine.check` | False valid result | Invalid; inline/reference results agree |
| Parsed authored Foo with nonnumeric `bar` | Already rejected by preprocessing/resolution | Still rejects; this path was not the original false positive |
| Fixed invalid enum metadata, repeated cached reads | Metadata could skip fixed checks | Repeatedly fails, including warm metadata access |
| Required typed fixed reference whose content is unavailable | No complete evidence | No false valid certificate; later content is validated |

The first two declaration tests failed on the base (2/2). The scalar baseline
had one failing conformance assertion out of three tests. These reproductions
preceded their implementation changes.

## Root causes and implementation

1. `CompletedValueValidator.isRootInlineSchemaDeclaration` skipped all root
   validation based on inline syntax; other roots were forcibly present. The
   exemption is removed. Existing `TYPE_METADATA` infrastructure now registers
   deferred definition candidates and validates supplied fixed payloads. An
   invocation-local definition goal survives reference materialization and
   contracts traversal; it is not Node content.
2. `BasicTypesVerifier` excluded object/list payloads on scalar types but did
   not check every present scalar's primitive kind. It now validates against
   proved core ancestry without rewriting the value's custom type/raw content.
3. `TypeAssigner` proved compatibility of a primitive contribution with an
   inherited custom type, then accidentally assigned the primitive type. It now
   retains the inherited type. Canonical reconstruction recognizes that exact
   inherited identity only with resolver evidence and a proved primitive
   ancestor; it does not hash a materialized type or accept unrelated evidence.
4. `ScalarNodeIdentity` was used as enum membership. Exact identity is retained
   unchanged; `EnumConstraintMembership` separately handles scalar domains.
   Schema merge and mutable/frozen schema matching use compatible membership.
5. `NodeDeserializer` rejected custom-typed and pure-reference enum entry
   shapes despite the model's scalar enum shape contract. Only that entry shape
   predicate changes. Exact reference syntax/content validation remains at the
   existing core boundary; malformed IDs still fail before resolution/identity.
6. Large Integer schema bounds lost their explicit type in `SchemaWireForm`,
   then serialized as quoted Text. Out-of-interoperable-range Integers now retain
   the explicit scalar type. Numeric constraints use proved primitive payloads
   and exact Integer arithmetic rather than arbitrary-Integer float conversion.
7. Typed provider-unavailability exceptions in limited resolution could become
   INVALID through message-text classification. The typed failure now preserves
   INCOMPLETE/UNAVAILABLE and the requested exact ID.
8. Cached type metadata now replays exact canonical declaration evidence to
   register validation candidates. Canonical content can still come from cache;
   cached identity evidence alone cannot bypass fixed-payload checks.

Pure root reference identity and minimization remain cold: after syntax
validation the exact reference can be returned without demanding its content.
Required typed/fixed-reference checks still demand only their necessary content.

## Public contract and Mini handoff

`BlueResolution.resolveDefinition(Node)` is an additive Java default method.
The supplied runtime implements it; alternate implementations fail explicitly
with `UnsupportedOperationException` until they implement this goal. It returns
a resolved definition with constraints retained, not a completed-instance
certificate. `MergingProcessor.validateDefinition` is an additive default SPI
hook; vocabulary/known applicability checks remain merge-time responsibilities.

The owning APIs are:

- `BlueLanguage.codec().parseSource(..., YAML)` for authored input;
- `preprocessing().preprocess` for explicit alias/import binding;
- `resolution().resolveDefinition` for declaration validation/preparation;
- `identity().canonicalIdentityInput` and `directBlueId` for the exact stored
  representation and its ID (Source identity/minimization use definition goal);
- `graph().specialize`, `expand`, `collapse`, and `resolution().minimize` for
  representations, retaining exact references where appropriate;
- `resolution().resolve` for completed Source instance constraints;
- `ConformanceEngine.check` for resolver acceptance as data on preprocessed
  Language input; `matching()` is demand-driven type/shape matching, not whole
  instance certification;
- `snapshots().resolve(source)` for the canonical/resolved pair and same-call
  type identity evidence. Detached resolved graphs with materialized reference
  metadata must not be reparsed as authored Source.

Mini can prepare the payload-free Gender declaration, obtain its canonical
exact body and ID, store **that body under that ID**, bind `Gender` to the ID,
then specialize and call `resolution().resolve(instance)`. Female/male pass;
wrong-kind/out-of-enum values and nonnumeric Foo.bar fail. A successful hash,
store, or `resolveDefinition` is not a completed-value certificate. Missing
required evidence remains non-established, and retrying after provision can
establish the result. No sample payload or human-readable name is required for
the operation goal. No MyOS or Mini endpoint end-to-end success is claimed.

## Proposed normative choices and alternatives

The exact clauses are added as §§8.1.1 and 9.2.5 and replace the affected portions
of §§9.8–9.9. Previous rules used typed-scalar identity membership/intersection
and did not explicitly distinguish definition preparation from certification.
The syntax exemption was an implementation issue, not a sound public contract.

The chosen rule defers obligations only on declaration paths without supplied
payload. Shape, known applicability, supported contradictions, and supplied or
inherited fixed payloads remain checked. A concrete list/object failing a lower
bound is rejected during preparation even if a future specialization could
extend it; omit its payload to defer that obligation. Required declaration-only
descendants remain deferred. This conservative boundary is explicit in §8.1.1.

Alternatives considered:

- Treat every value-free node as a declaration: rejected because completed
  invalid values and required descendants would escape certification.
- Use names or an identity-bearing `isType` marker: rejected because goals
  would depend on spelling or alter the uniform content model and identities.
- Require a sample value to store a definition: rejected because it fixes an
  unintended payload and cannot express the requested Gender definition.
- Erase custom types for enum equality: rejected because custom restrictions
  and exact value identities would be lost.
- Keep enum exact equality only: cannot express the requested self-independent
  constrained primitive with bare scalar entries.
- Remove semantically dominated enum entries: rejected for this candidate;
  normalization keeps exact entry identity and does not fetch ancestry just to
  remove redundant custom restrictions.
- Defer all lower bounds even on supplied lists/objects: possible future
  amendment, but a broader partial-payload contract than this candidate.

## Behavioral matrix

| Case | Definition preparation | Completed value |
|---|---|---|
| Payload-free constrained scalar | Valid; obligation retained | Payload-dependent check fails |
| Root metadata with only `required: true` | Valid | Valid; root exists |
| Required type-only child | Retained | Fails if child is semantically absent |
| Optional absent parent with required descendant | Retained | Inactive until parent is present |
| Exact empty object / empty list | Real payload | Existing presence/count rules apply |
| Invalid authored/inherited fixed value | Reject | Reject |
| Known Integer + minLength, Text + numeric schema | Reject | Reject |
| Empty enum or disjoint inherited intersection | Reject | Reject |
| Enum entirely in proved incompatible primitive domains | Reject | Reject |
| Unknown kind/opaque enum evidence, no payload yet | Retain obligation | Demand evidence if needed; never assume valid |
| Fixed typed reference, content missing | Cannot establish needed validation | Cannot certify |
| Same reference supplied later | Validate actual content | Validate actual content |

Enum entry `(E,p)` permits canonical primitive payload `p` in domain `E` or a
proved subtype. Explicit core and inferred bare entries agree. Custom entries
retain their narrower restriction; unrelated custom siblings do not match.
Integer/Double are disjoint; quoted numeric Text stays Text. Large integers are
exact. Custom names resembling core names confer no authority. Fixed values and
full-node labels keep their existing exact identity restrictions.

Intersection keeps the narrower comparable domain with the same canonical
payload, then performs exact typed-entry duplicate normalization/stable RFC8785
ordering. Pure value references denote exact scalar singletons, not subtype
domains. Missing needed evidence is not an empty intersection. A deterministic
25-domain-pair property matrix verifies commutativity and intersection membership
against conjunction over five candidates. Exact-only normalization deliberately
retains distinct custom entries even when their accepted sets overlap.

## Identity and integration impact

- No registry/core-type node, release ID, `ScalarNodeIdentity`, direct hash
  algorithm, exact reference verifier, or broad fixture package is rewritten.
- Definition goal is not serialized. Content accepted under both goals has the
  same canonical identity; runtime/source identity comparison uses appropriate
  canonical evidence, not raw authored/resolved bytes.
- Some previously rejected definitions become preparable. Some previously
  falsely certified values now fail. Matching and enum intersections change as
  proposed. Inherited custom field identities are correctly retained, so values
  previously widened to core types can have corrected Source-derived identities.
- Huge Integer schema serialization now emits valid explicit typed scalar
  content instead of losing the type. Any affected expectations require review;
  they are not silently recaptured.
- Normal enum ordering/exact duplicate normalization stays stable. Custom type
  identities are never stripped merely to pass enum membership.
- The primary specification SHA-256 and every release/package binding that
  incorporates it are stale by design on this development branch. Language
  fixture package/release manifests, Contracts bindings to Language, and any
  derived semantic/gas expectations must be reviewed by integration. No registry
  regeneration, campaign A/B generation, or release packaging is claimed here.
- General schema satisfiability is not implemented. Consistency checks cover
  existing bounds, empty enums/intersections and locally provable primitive
  domain contradictions; opaque custom/reference ancestry is not fetched merely
  to prove definition unsatisfiability.

## Review and verification

A separate read-only semantic reviewer inspected the design and negative cases;
it edited no files and ran no builds. Findings about pure-root locality, fixed
references without schema, cached metadata, explicit goal propagation, empty
sets, and numeric matching parity were addressed with focused regressions.

All Gradle commands used the existing wrapper and
`JAVA_HOME=$(/usr/libexec/java_home -v 21)`; production/test target remains Java 8.
The first sandboxed Gradle attempt could not create the existing cache lock;
authorized cache access was then used. No build orchestration was modified.
System Git lacks `branch --show-current` and `status --porcelain=v1`; equivalent
`symbolic-ref --short HEAD` and `status --porcelain` recorded the checkout state.

Final test totals and exact commands are recorded in `verification.md`.
The iteration table includes failed reproductions and corrected test/API probes;
a passing final run is never inferred from intermediate results.

Not run: clean build, all root/module tests, full Language/Contracts conformance,
semantic baseline capture/verification, A/B generation, JMH/full gas/release
execution, packaging, publication, MyOS/Mini endpoint or integration tests.
These remain the campaign owners' integration gates. No infrastructure changes
from Task B are assumed present. The semantic amendment remains pending review.
