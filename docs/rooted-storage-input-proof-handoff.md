# Storage-local proof handoff for a complete result's input

## Problem and measured motivation

This isolated POC starts at Language `748ee867bb2fdf760b9cd4d6b83ba5a99df2088c`.
It does not change the frozen baseline, Coordination, MyOS, or any selected artifact.

The closed MyOS worker-phase diagnostic recorded 64.74 seconds inside 234
`SDK_STAGE` phases across the complete 18-case diagnostic. A subsequent combined
phase/CPU recording (`myos-worker-phase-profile-external-02.jfr`, SHA-256
`da90b10091b00eb1bd78206dbf8ecd57c3ae8d6fd157fad7aec7783e134fdba5`)
also retained execution and selection stacks through complete-result decoding and
snapshot verification. Its retained interval is only 07:50:46–07:52:35 UTC on
14 September 2026; the first approximately 78 seconds rolled off. These are
overlapping sampled paths and nested diagnostic durations, not exclusive cost,
call counts for this particular check, or evidence of an acceptance pass.
Coordination's separate staging candidate addresses a different path.

The concrete redundant operation is established by source, not by attributing
those profile totals to it: `ClosureProcessResultStorageCodec.decodeInCall`
decodes its input snapshot using the current `SnapshotStorageCall`. On a cold
decode, that call has already completed snapshot self-verification, the explicit
outer identity checks, and canonical framing. The result's deep constructor then
calls `ClosureEvidenceVerifier.verifySnapshot` on the very same input object.
The constructor's later transition, receipt, gas and companion checks are distinct
and necessary; they are not the optimization target.

## Bounded solution

The existing bounded call-local `IdentityHashMap` remains the only proof inventory.
`hasVerified` requires an open call and exact object identity. Only the existing
successful full snapshot verification inserts into that inventory. No equality
of BlueIds, closure identities, bodies or bytes grants an input proof here.

A package-private result-constructor overload accepts the current storage call.
Only the complete result codec supplies it. All previously existing constructors
delegate without a call and perform their original checks. The overload uses the
existing full proof to omit only the redundant pure input self-verification. On
an unseen, evicted, disabled, or warm-byte-only input it invokes the unchanged
`ClosureEvidenceVerifier.verifySnapshot` directly. It does not call the broader
storage verifier on a miss or add new proof authority after this narrower check.
A closed call fails; neither call nor proof is retained by the constructed result.

The two package-only scalar counters distinguish actual input-check reuse from
fallback attempts, including failed attempts. They add no global observer or
history, and do not count successful semantic results. The existing byte and
entry/reference bounds and disposal remain unchanged.

## Rationale and preserved checks

Pure snapshot self-verification derives its graph, generations, exact bodies,
cyclic evidence and markers from that snapshot. Its temporary providers contain
only the supplied evidence; it consults no host, external provider, clock, gas
budget or publication state. Storage creates ordinary detached Nodes and closed
scalar/container values. Managed documents clone their Nodes, component proofs
are copied on input and output, occurrence values are immutable, and witness maps
retain immutable snapshots without a mutation API. Reusing the proof for this
exact input within one codec call does not merge graphs or change wire aliases.

Input identity validation, result construction, transition finalization and
generation assignment, output reconstruction and byte comparison, canonical
complete-result round trip, receipts, rejected charges, gas trace, companion and
rooted witness/context comparisons all remain in their existing order. A valid
input does not validate a failed or forged enclosing result. Constructor, physical
depth and byte checks are not bypassed. Any failure remains a failure.

The maximum source-predicted saving is **one pure input self-verification per
complete result decode**, and only while its exact proof remains in the call.
Warm accepted-byte decoding intentionally does not establish this call-local
object proof, so this change can save zero checks on that path. There is no
predicted speedup, reduced gas, larger deadline or acceptance claim.

## Alternatives not taken

- A success bit on every public `AffectedClosureSnapshot` would unnecessarily
  affect resident construction. Public construction accepts `Node.clone()`;
  Nodes are subclassable and arbitrary Java payloads are broader than the
  detached storage value domain. This change does not strengthen that contract
  or assume universal per-object immutability for such inputs.
- No global, per-object, cross-call or ThreadLocal verification cache is added.
  There is no decoded-graph interning and no new public certificate API.
- The two separate Coordination-to-Language re-encodes of derived publication
  and retained snapshots remain unchanged. Their pure checks cannot consume the
  current lexical proof without another interface/lifetime change.
- Selection-owner handoff is not an equivalent host optimization: current SDK
  audit capture may populate canonical/provider object overlays that MyOS
  currently discards. Transferring that owner would remove the discard boundary;
  its semantic isolation has not been proved.

## Controls and qualification pending

The existing complete codec/witness test owners retain their assertions. Added
controls cover a real cold complete-result decode with exactly one reused check,
warm and disabled fallback, identical-but-distinct input objects, evicted and
closed calls, same-identity forged bodies and repeated rejection, and a forged
output rejected after the input proof is actually reused. The maintained
A11/D2/D14 witness fixture additionally exercises cold enabled/disabled result
and G-1 rollback bytes, exact aliases/context, gas and receipt parity and bounds.

This source batch has **not been built, tested, exported or committed by its
implementing agent**. Parent qualification must run complete owners, retain native
source/dependency/result receipts, and report actual invocations. Proposed grouped
command, not an executed result:

```sh
./gradlew :blue-contracts-core:test \
  --tests blue.language.processor.closure.AffectedClosureSnapshotStorageCodecTest \
  --tests blue.language.processor.closure.ClosureProcessResultStorageCodecTest \
  --tests blue.language.processor.closure.ClosureExecutionEvidenceStorageCodecTest \
  --tests blue.language.processor.closure.ClosureEvidenceApiTest \
  --tests blue.language.processor.closure.ClosureEvidenceFactoryTest \
  --tests blue.language.processor.closure.ComponentFinalizationKernelTest \
  --tests blue.language.processor.closure.RootedWitnessContextTest \
  --tests blue.language.processor.closure.RootedWitnessSelectionTest \
  :blue-contracts-core:javadoc \
  --offline --no-daemon --no-parallel --max-workers=1 --console=plain
```

Use the maintained Java/toolchain and immutable dependency pins. A focused library
gate is not an application or full-corpus qualification. Any performance claim
requires a subsequently sealed tuple and original paired application oracles.
