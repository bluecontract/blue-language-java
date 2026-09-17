# Cost38: preserve owned encoding evidence

POC-only performance correction. The final Language controls pass 49/49. The
combined PostgreSQL long-graph and cold-restart check also passes; its latency
target does not. This is not a release qualification.

Problem: storage encoded a library-verified privately owned snapshot and then
immediately decoded those bytes as wholly unknown input. Canonical BlueId path
traversal also repeatedly split and normalized every already canonical ancestor.

Changes:

- After complete successful snapshot encoding, retain an independent bounded
  copy of its exact frame only when the snapshot has library-issued state proof
  and a detached representation. This lets an immediate exact readback use the
  codec's existing accepted-frame path. Public mutable copies and incomplete or
  failed encodes cannot issue this capability. A call with reuse disabled also
  disables encoder-side admission. Witness validation remains.
- Inside `NodeToBlueIdInput` only, append an escaped child to the private canonical
  parent directly. The public/general JSON-pointer appender is unchanged.

Neither correction changes BlueIds, canonical bytes, gas, event order, logical
operations or failure policy. Tests cover owned cyclic witness readback, mutable
aliases, byte limits, eviction, tampering, escaped/empty/private indexed paths,
and exact failure paths. The first control run detected missing enforcement of
the disabled-call retention flag; production code was corrected and the original
assertions passed unchanged. Final Language commit: `833aa953`. Coordination's
relevant codec/failure/selection subset also passes 24/24 on that final artifact.
No library is published and no upstream PR is modified.
