# Standalone retained execution values

## Integration problem

An exact Coordination cohort can outlive more than its current attempt:

- Accumulated automatic expansion retains resolved demands from earlier issued
  attempts. Their original nullable processor marker is still part of the
  retained value, even though result validation only reads descriptive fields.
- A captured cohort may hold a prepared retry before that retry has executed.
- A selected historical work row can hold a representation cause or a numbered
  step's future representation successor before a consumer input is captured.

These values cannot honestly be wrapped in a fabricated attempt. In particular,
the current expanded input is not the issuer of a consumed earlier demand.
Rebuilding the public demand fields would lose the original marker, while
stamping the expanded invocation would create authority that never existed.

## Minimal boundary

The existing `ClosureExecutionEvidenceStorageCodec` adds three typed envelope
pairs: `encodeResourceDemand` / `decodeResourceDemand`, `encodeRetry` /
`decodeRetry`, and `encodeProcessingCause` / `decodeProcessingCause`.

They use the existing closed field mappings, byte/depth bounds, exact Frozen
event construction, checksum framing and canonical re-encoding. No new restore
constructor or semantic model is added. A standalone demand retains its exact
original nullable issued marker, never a marker inferred from another input.
The retry keeps its complete base, exact resolutions and canonical identities.
The historical cause keeps all original recursive transition provenance.

These are authenticated pinned host-storage operations, not untrusted admission
formats. A checksum is not a processor signature. The outer retained row must
preserve its original associations; normal birth/history guards still run at
use. For an active suspended continuation, the associated attempt envelope
remains preferable because it verifies the producer and restores the selected
demand as the actual member of its demand list.

The existing invocation, attempt, receipt and result formats are unchanged.
The new standalone formats each use a distinct `/1` domain. No provider,
PROCESS, re-admission or host-history lookup occurs during decode.

## Focused proof

The actual nested-birth fixture proves a restored standalone issued demand can
authenticate its original input but cannot be reused as if the expanded input
issued it. An equal public copy remains unissued, including absent inline body.
Typed exact-node demands, wrong envelope kinds, corrupt/truncated/trailing and
over-bound bytes are also covered.

The actual historical retry controls now restore the prepared retry before its
next PROCESS, both with and without optional inline demand evidence. They keep
the original invocation, retry and resolution-set identities, then retain the
unchanged complete-result and gas assertions. No fake suspension is introduced.

The existing rooted numbered-tail fixture separately restores its future
representation successor before binding it to the numbered cause, then restores
external, numbered and representation causes. Its unchanged original guards
still reject wrong occurrence, skipped predecessor and nonterminal successor
packets after storage. The complete history, ordering, gas and rollback controls
remain in the same fixture. This is component qualification, not a complete
Coordination or MyOS recovery claim.
