# E2 packages 4 and 5: prospective births and retained epochs

Language base: `26fd05dbd16fa4a59a470aa9d3caf6b64b9fd658`.
BEX base: `e0597120d9a57015c6631a6acd3fadac8376e073`.
See `00-handoff.md` for exact slice commits and final validation.

Added immutable `ManagedDocumentBirth` and
`ClosureEvidenceFactory.withProspectiveBirths`. This is pure preparation of the
closed-evidence replay already specified by Contracts §9.4. It binds exact
birth demands to host-reserved fresh lineages, adds inactive prospective rows,
and preserves existing input heads, epochs, components, public-root status,
direct deliveries, logical cause, environment, and gas policy. Ordinary
Contracts execution verifies activation and performs initialization; no receipt
is invented. The API rejects duplicate/stale/conflicting evidence and direct
prior-processing markers. Durable global lineage uniqueness remains host-owned.

The API is intentionally for new occurrence paths; represented or retired rows
retain their existing evidence/generation protocol. It does not silently
reinterpret a historical retarget as a new lineage.

`ProspectiveBirthRetryTest` covers nested late discovery, no publication on
NeedsResources, equal content with distinct lineages/escaped paths, defensive
copies, stale/conflicting reservations, and atomic failure after a charged
replayed prefix. `RetainedEpochBirthCompositionTest` first obtains a real source
receipt, applies that retained epoch to a consumer, discovers and initializes a
new child, and distinguishes source observation from consumer/birth publication.
It asserts unchanged source head/epoch, no source reinitialization or duplicate
source public emission, and authenticated source receipt linkage.

F2 handoff: `docs/guides/managed-document-composition.md`. F2 supplies retained
history and durable lineage reservations, repeats the unchanged logical cause
under the same cap after resource acquisition, and publishes successful
Contracts results/companions atomically. A suspended attempt has no publishable
gas or partial state; every completed retry charges its full semantic prefix.

API classification: six additive inventory lines (one type, its constructor,
three accessors, one factory); zero removals. No baseline rewriting. No normative
Language/Coordination changes, fixture package regeneration, or release sealing.
Expanded inputs intentionally have new closure/invocation identities; predecessor
heads and receipt identities retain their established meaning. New initialized
heads, occurrences, receipts and output closure identities arise from normal
execution. A2 must regenerate source-bound evidence after integration.
