# Preserve issued event evidence instead of re-admitting it

## Concrete problem

An `ExactEventIdentityEvidence` contains an immutable `FrozenNode` and its admitted
event BlueId. A processor may issue it through `fromAdmitted`, retaining the
original strict/resolved construction, or through its already-verified Source
effect boundary. The old complete-result storage format detached a mutable Node,
BlueId and optional cyclic proof, then called the normal external `verify` path
while decoding. That is a different operation from restoring issued evidence.

Two executable controls fail on the old implementation:

* A typed Source event has a materialized nominal type whose parent is fetched
  during genuine Language admission. Cold event-field restoration with a fresh
  denying provider attempts to fetch that parent again.
* An actual rooted processor invocation emits managed Root events. Restoring its
  complete result changes the original Frozen construction from resolved to
  strict, even though the detached Node and public BlueId appear unchanged.

The latter changes what a later immutable consumer observes. Neither provider
availability nor reconstructing a different Frozen mode belongs in storage replay.

An additional actual rooted handler emits both materialized nominal Source and
the exact capability returned by `semanticOutputBoundary().admit`. The producer's
Language runtime and provider then close. A fresh denying runtime restores the
complete result with zero provider calls, preserving complete result bytes,
gas/trace, rooted companion, receipts, and both public and managed events' original
Frozen constructions. The second event is also compared directly to the Frozen
value captured at the live semantic-output boundary. Normal external verification
of the first Source event still calls the denying provider, proving the original
verification rule has not been bypassed for new input.

## Minimal trusted boundary

`ExactEventIdentityEvidenceStorageCodec(maximumBytes, maximumDepth)` exposes only
`encode(issuedEvidence)` and `decode(authenticatedBytes)`. Its versioned envelope
contains the complete `FrozenNodeStorageCodec` representation plus the original
event BlueId. Both envelopes are bounded and checksummed; decoding requires an
exact canonical re-encode before returning evidence. The package-private
`fromTrustedStorage` factory validates BlueId syntax, pure-reference equality and
ordinary strict canonical identity where it can do so without Source resolution.
It does not re-admit Source, fetch a cyclic proof, invoke BEX, or execute handlers.
Materialized nominal types retain their already-proved Language-owned identity.

The public result format advances to
`blue-contracts/closure-process-result-storage/2`. Its field writer now takes the
original event capability through package-only accessors; the reader restores it
through the trusted codec. Both public events and managed receipt events use the
same path. Old version-1 bytes are rejected, not silently upgraded. The public
`decode(byte[])` method requires no runtime. Compatibility runtime and external
proof-callback overloads remain but explicitly never consult those arguments.

## Authority and rejected alternatives

The host must authenticate the pinned record that selected these bytes. A checksum
is only corruption detection: it does not authenticate BEX execution, Source
identity, publication, a cyclic proof or invocation ownership. This codec is not
a user-input admission endpoint. It restores only already-issued evidence from a
trusted storage boundary; it does not restore a semantic admission-owner token.
There is no new public arbitrary Node/BlueId authority factory.

Normal external `ExactEventIdentityEvidence.verify` is unchanged and still rejects
wrong identities, missing/incorrect cyclic proofs, or typed Source lacking its
runtime/provider. Re-canonicalizing Source, loading providers during restore,
dropping original Frozen modes, or treating checksum-valid hostile bytes as proof
would violate this separation. No Language/BEX semantic policy, gas, source
history, rooted ownership, or result validation rule is changed.

## Verification

The isolated source starts at Language `d2ce037d`. The exact old-path red is
archived under `/Users/kamil/Documents/Projects/Blue/rooted-event-evidence-storage-evidence.SWTU1U/red/`:
two tests, two expected failures, unchanged inputs. It records both the denied
parent lookup and the real rooted result's Frozen-mode mismatch.

Focused green qualification includes the original 17 result/history/admission/
checkpoint controls (including `G` and `G - 1`), unchanged normal event verifier
controls, and new trusted event-storage and denying-provider/mode controls.
The exact command, counts, hashes and source-stability evidence are recorded in
`rooted-event-evidence-storage-evidence.json` after the gate. No full release,
host recovery, artifact export, or general cache/performance claim is made here.
