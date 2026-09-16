# Compatible byte bounds for verified complete result frames

## Problem and evidence

The batch-29 diagnostic (`readreuse29/warm02/run-before`) observed the same complete
Contracts result bytes through two Coordination readers: a 40 MiB session-view
record profile and a 32 MiB publication-index value profile. The exact selected-work window closed successfully in
98.550 seconds. Of 125 complete-result loads under the 32 MiB family, 124 had the
same frame digest already retained under the 40 MiB family at the window's start.
This is a candidate source of duplicate reconstruction, not a measurement of time
saved. Of those 124 loads, 87 were first-observed 32 MiB profile keys and 37 followed
eviction of an earlier 32 MiB entry. First-observed does not mean a new logical result.
The window reported no incompatible cached publication identities; it does not
justify publication-scope rebinding.

Previously, `ClosureProcessResultStorageCodec.requireHandle` required equality of
the issuance and requesting byte caps. Coordination consequently separated these
otherwise identical verified frames into different cache families.

## Narrow correction

For `blue-contracts/closure-process-result-storage/2` only, a retained
`VerifiedFrame` is compatible when:

1. It is the immutable, library-issued result of a successful complete decode and
   full canonical-byte comparison. There is no new constructor or host trust flag.
2. Its physical depth profile is exactly the requesting codec's depth profile.
3. Its **complete encoded frame length** is at most the requesting byte cap.
4. Decoding matches all selected bytes, not merely a hash. Encoding additionally
   matches the exact decoded result object, not an equal public/produced result.

The issuance key remains unchanged. Both directions are valid: a 32 MiB-issued
frame can serve a 40 MiB reader; a 40 MiB-issued frame can serve a 32 MiB reader if
the actual complete frame fits 32 MiB. A frame one byte larger still fails. Selected
oversize input is rejected before the retention callback.

`requireProfile` stays exact: an execution-evidence codec must still receive a result
codec configured with its own byte and depth caps. Only reuse of a completed result
certificate is compatible across byte caps; codec injection is not relaxed.

Coordination's `StoredClosureResultCodec` therefore retains format and depth in its
cache family and removes only the byte cap. The existing weighted cache, exact-byte
check, single-flight loader and reverse object-identity index remain unchanged.

## Why complete frame length is sufficient for this format

- `ExactNodeStorageCodec` applies its byte cap to the complete encoded envelope and
  its bounded writer. Field/count checks use actual remaining bytes. Canonical
  encoding and semantic checks do not depend on the configured byte cap.
- Complete input/output/ownership/boundary snapshots are embedded as literal
  length-prefixed envelopes. A nested envelope cannot be larger than the enclosing
  complete result. `AffectedClosureSnapshotStorageCodec` backreferences address
  already decoded witnesses within that snapshot; they do not fetch external data.
- `ClosureResultStorageValues` embeds node fields, event-evidence envelopes and
  cyclic-proof placeholder nodes. Event evidence contains its complete frozen-node
  envelope. Frozen-node backreferences remain internal to that envelope. This result
  path has no injected execution codec or external expansion source.
- Snapshot verification, rooted ownership, output derivation, gas/event/result
  identities and complete canonical re-encoding are independent of the byte cap.
  The derived output must equal the embedded asserted output. Snapshot memo budgets
  affect retention and repeated pure verification only, not acceptance or meaning.

Thus successful verification under the issuance profile plus the complete-byte
length and identical-depth checks preserves acceptance under the requested profile.
This claim is specific to the current complete format and its nested codecs; it is
not a rule for arbitrary compressed or externally referenced artifacts.

## Limits and rejected alternatives

- The byte cap bounds physical envelope size, **not** expanded graph size, retained
  heap or processing cost. It is not a new memory-safety guarantee.
- Depth compatibility is deliberately not inferred from frame size: different
  depth profiles stay separated and a mismatched returned handle is rejected.
- Host storage authentication, selected-record membership, invocation binding,
  history position and publication authority remain mandatory outside this reuse.
  A valid nested result cannot validate an invalid enclosing execution/cause frame.
- Blindly dropping every family limit would be unsafe; only this format's byte-cap
  distinction is removed. Other cache families and codec limits are untouched.
- A new observed-bound certificate is unnecessary for the current format. If a
  future format adds a byte-dependent expansion limit or external materialization,
  this proof must be revisited and the compatibility binding versioned accordingly.
- Raising cache capacity or rebinding publication wrappers does not resolve this
  duplicated family key and is not part of the correction.

## Focused verification

`ClosureResultStorageReuseTest` covers independent cold strict/loose decoding, reuse
in both issuance directions, exact byte-cap acceptance and one-byte-below rejection,
ordinary/rooted results, cyclic snapshot and ownership evidence, emitted events and
gas-limit failure results. It retains exact-byte, null/substituted-handle, wrong-depth,
wrong-object, corrupt-frame and strict execution-injection controls.
Its existing nested-history helper, also invoked by
`ManagedCheckpointSettlementOwnershipTest`, now uses differently sized
execution/result codecs; each injected pair still has matching settings. Invalid
enclosing causes remain rejected.

Coordination's `RuntimeDecodedArtifactsTest` checks 32/40 MiB readers in both
directions, the exact frame-size boundary, one retained frame and one full decode,
canonical encoding without a writer fallback, and separate depth families.
Grouped qualification and a fresh batch-30 runtime measurement are still required;
this change alone is not a claim that the long graph scenario is fast enough.
