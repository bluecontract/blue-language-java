# E2 package 2: typed scalar document factories

Language base: `26fd05dbd16fa4a59a470aa9d3caf6b64b9fd658`.
Extraction prerequisite: `328581e627a61f000288083ddee395ed393e202b`.
BEX base: `e0597120d9a57015c6631a6acd3fadac8376e073`, verified project
`/Users/piotr/data/blue-bex-java`; scoped worktree `/private/tmp/codex-r2-contracts-bex`.
Exact final commits and integrated validation are recorded in `00-handoff.md`.

The first violated contract was Contracts output canonicalization. A BEX
function returning a typed child with scalar fields reached the exact output
boundary, which treated its inherited Handler.event pattern as a complete
lifecycle event and required the event's document field. Output canonicalization
now uses the existing runtime exact-field ownership catalog and Source
canonicalization machinery. Business fields still undergo complete validation.
The snapshot-backed processing path also retains inherited runtime fields,
including Node-valued header fields, when the new child is initialized.

Legacy ordinary-field resolution is preserved: whole cold business subtrees
from the selected-scope catalog are not exempted from eager validation in the
legacy or complete-output paths. The evolution acceptance fixture's custom
snapshot adapter now supplies actual exact provider evidence and verifies its
BlueId, instead of using the default recursively resolved fallback.

Owning regression: `HostedDocumentFactoryOutputTest` (inline/reference types,
scalar factory, inherited lifecycle matcher, execution, manual document control,
wrong kind/missing/empty-object business fields, atomic failure).
Actual boundary regression in BEX: `BexScalarDocumentFactoryTest` (Order and
Observation; inline, referenced, document-bound, imported types; two children;
command replay and distinct lineage reservations; child failure rollback).
Existing `BexContractsNullBoundaryAcceptanceTest` supplies explicit null/missing/
empty-object distinctions. No BEX production defect was demonstrated.

Focused verification also includes canonical identity, strict locality,
processor runtime access, collection lifecycle, and contract evolution suites.
No Language declarations, public output API, gas schedule, runtime registry,
normative spec, or exact package bytes were changed by this slice. Valid factory
invocations that previously failed now commit and produce ordinary initialization
and transition evidence. Existing source identity algorithms are reused; output
handles remain invocation-owned. Implementation fingerprints and source-bound
receipts must be rebuilt on A2's integrated commit. This is implementation and
regression evidence, not a release seal.
