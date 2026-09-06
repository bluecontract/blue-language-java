# Reviewed local POC fixture deltas — 2026-09-06

These are local implementation-snapshot refinements, not a published release or a new selectable
specification profile. The current POC processing kernel is the single target. Gas weights, limits,
fixture inputs and identity constructors are unchanged.

The 36 report hashes in `local-poc-fixture-delta-approval.json` bind the reviewed runtime results and
their original fixture hashes. The dedicated collector executed each case once without using its
expected output as runtime evidence. A second reviewer independently checked the complete deltas.
The earlier capture was rejected: it exposed sorting by occurrence hash instead of the required
containing-document/path/activation coordinates. The runtime was corrected, and a fresh capture
plus an inverse-hash control restored the original C19/C20 work order before this approval.

## Accepted changes

| Change | Evidence and reason |
|---|---|
| Incremental route/placement gas | The selected kernel charges expansion before additional work is allocated. C02 variants add 20; C23-01 through 04 add 4 each (342 → 346); other exact deltas are in the hash-bound reports. All gas-trace subtotals sum to the result gas. |
| Exact sequential containing placement | FL06 inline, reference and reversed representations all change 2744 → 2758: 4 for edge work and 10 for the actual containing-reference update. Their complete results are identical, not merely their final state or total gas. FL09's rollback prefix includes the same placement work. |
| Gas attribution | Some cases retain their total gas but attribute identity-classification work to its actual owning document instead of leaving it unattributed. Receipt gas and derived hashes change; every other receipt field is unchanged. The sum of receipt gas equals the operation total. |
| Historical cyclic activation | C23-05 changes 473 → 435: 4 for edge work minus 42 for a discarded temporary acyclic ancestor rewrite. The joint SCC finalization follows immediately, before any callback. The actual callback control reads only the final cyclic pin and counter10 through both direct and cyclic paths; no observer can see the omitted temporary identity. Actual source and joint finalization work remains charged. |
| Loop cutoffs | The same fixed budget is exhausted at a different exact charge once route work is metered. The admitted prefix, rejected charge and trace identities change; status and complete rollback do not. FL08 first/retry complete results are identical. The Java `GAS_FAILURE_ORACLE` is refreshed to the independently checked route-metered cutoff, retaining fixed exact assertions and retry checks. |
| Dormant C27 binding | Inactive row3 keeps its original selected target `8Ze…#1`, not the physically current `9ok…#1`. Only that row's target/binding identity and derived closure/companion identities change. No document, epoch, event, checkpoint or topology action changes. |

Across all 36 cases, original attempt outcomes and statuses are unchanged. No business document,
epoch, public event, checkpoint, invocation identity or semantic receipt field changes, except the
explicit dormant-binding correction and its derived identities above. Non-loop work order is
unchanged. C03/C13 custom `observations` assertions are outside the generic serializer's ownership
and must remain intact.

## Installation and verification

### Second reviewed tranche: seven remaining closure fixtures

The full suite exposed seven further cases after package initialization succeeded. They were
captured separately from the already installed 36, with their own original-file/report hashes in
`local-poc-retirement-fixture-delta-approval.json`. The first report set was not replayed against
modified fixtures. Independent review checked complete structural traces and counter deltas; the
supervisor completed the placement and receipt-attribution review before approval.

| Case | Exact total gas | Reason for the additional charge |
|---|---|---|
| C10 | 729 → 733 | Two additional logical edge charges. |
| C11 and C28 mixed | 1151 → 1167 | Eight additional logical edge charges. |
| C12 and C35-00 | 771 → 777 | Three additional logical edge charges. |
| C23-00 | 491 → 505 | Two edge charges and one actual containing-reference update. |
| C28 containing spine | 624 → 656 | Six edge charges and two actual containing-reference updates. |

Each edge charge remains 2 and each containing-reference update 10; no tariff or limit changed.
Formerly unassigned identity-classification work is attributed to its real document. For every
receipt, its admitted gas equals that document's trace subtotal, with remaining common gas assigned
by the existing first-receipt rule. Only admitted gas and its derived receipt identity change in
the receipt bodies. Work order, document-step traces, finalizations, document values/epochs, events
and invocation identities remain unchanged. Incremental route admission can move enqueue/dequeue
gas-trace positions without changing the actual work trace.

Six cases also preserve inactive occurrences at their actual selected pre-removal view instead of
the ambient final source head, as established by the separate actual retirement runtime control.
Binding-set, output-closure and companion identities follow that corrected retained evidence.
C23-00 has no such binding change: its receipt hashes change only because of gas/attribution.

The installer accepts only the exact original and post-36 predecessor identity pairs for these
two reviewed local passes. Its closed source/test binding map and the exact C34 file-hash/byte
assertion are refreshed without touching historical documentation examples. Public API inventory
generation remains a separate existing build task after constant values are rebound.

### Mechanical installation controls

`apply_reviewed_closure_fixture_deltas.rb` performs a bulk mechanical snapshot refresh only from the
explicit approved report hashes. It preserves the bytes before each `expected` section, does not add
serializer-owned fields absent from the old fixture, retains custom assertions, verifies old external
trace contents and updates their exact inventory hashes. Fixture/registry/release links are rebound
locally without changing registry type identity, gas-manifest identity or any fixture input.
Canonical manifest hashing is restricted to the actual ASCII-keyed, integer-only manifest domain and
was first checked against all three existing manifest identities. Every candidate manifest identity
is verified again before any write.

The generated-file update is not a crash-atomic publication. It is confined to this unpublished
worktree; full package consistency and the complete regression must pass afterward. A green report
collector is not acceptance of the library or of Phase3 host integration.

## Local package bindings

After installation, canonical recomputation verifies the combined fixture identity as
`sha256:86e7d82cbd5e806dac84bff03e9a29c00846fdf88dbbbc09d909c37932e071e6`
and the Contracts release-manifest identity as
`sha256:2385edf920fd11dc9651cd0d1a91fe5467bc2d34d3988881848f57ea9777b46b`.
The exact local pins in `BlueContractsConformanceReport`, `BlueContractsFixturePackage` and
`ClosureFixtureInventory`, their report-test assertions and the four corresponding semantic-baseline
binding fields are refreshed to these values. This prevents package verification from comparing the
approved local fixtures with the preceding snapshot's identifiers; verification remains exact.

The Contracts registry identity remains
`sha256:46a7744c1cbfa4b00e1d8a99f6ca3f0089ef697de968fee08547894ab02b0ca1`.
Registry/type values, gas weights and gas-manifest identity, Language identities and specifications
are unchanged. Historical release examples are not rewritten to imply that this local snapshot was
published. No test count or execution result in the semantic baseline is regenerated by this binding
refresh.

The one-off installer also stages those exact three Java symbols, four existing report-test literals
and four named semantic-baseline fields. It requires the original reviewed fixture/release identities,
the original symbol values and exact literal counts before any write; it cannot scan-and-replace
historical documentation or act as a general release generator. The pure binding transformation can
be checked against Git HEAD without invoking the installer or modifying the already updated files.
