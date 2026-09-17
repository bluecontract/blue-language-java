# Complete rooted witness snapshot storage: first bounded slice

## Problem and exact example

The public `AffectedClosureSnapshot` constructor retains document bodies, occurrences,
components and public roots, but deliberately cannot grant historical witness roles.
In `RootedHistoricalWitnessTest`, parent P reacts to source S1 while its selected
immutable S3 witness retains a return reference to an older P. A flat public copy
either fails exact graph validation (live return edge) or loses witness authority
(retired return edge). The original complete source snapshots are necessary for the
next historical reaction; current heads or a list of immutable source IDs are not a
substitute.

## Storage boundary and implementation

`AffectedClosureSnapshotStorageCodec` encodes the complete snapshot and the DAG of
original exact witness snapshots. It reuses `ExactNodeStorageCodec` for complete
Node/Schema construction fields, exact text framing, byte/depth bounds and the
versioned checksum envelope. Components retain full ordered cyclic proof members;
occurrences retain inactive rows, numbered historical cursors and all representation
cursor fields. Shared original snapshots use checked backward references, never
Java serialization, reflection, runtime lookup or processing replay.

The only existing production-class change is a package-only immutable map accessor
and verified reconstruction hook in `RootedWitnessFrame.State`. Decoding checks
every complete original snapshot, unchanged witness body/binding/epoch roles, the
normal exact graph/component/proof invariants, and separately recomputes the
occurrence-set and closure identities. The normal snapshot verifier alone does not
compare those outer asserted identities; an initial checksum-valid corruption
control exposed and corrected that omission in this new codec. Canonical re-encoding
rejects alternate framing, duplicate/out-of-order map entries and trailing content.
Canonical framing preserves sharing; it is not a new semantic identity scheme.

Bytes must be selected through an authenticated pinned storage root. A checksum is
not publication or history authority. This API cannot be used as provider evidence
or as a way to invent source ownership. Byte/depth exhaustion and malformed storage
are operational failures: a host storage adapter must classify them as noncommitting,
not return a processor status. The codec's bounded API fails with
`IllegalArgumentException`, matching the underlying pure physical codecs.

## Scope not yet implemented

This is complete **snapshot** storage, not complete `ClosureProcessResult` storage,
rooted publication-projection restoration, invocation/retry continuation transport,
candidate-attributed rejected gas-prefix transport, or durable SDK restoration.
It exposes no owner setter and changes no owner-tracker, processing, gas, order,
publication or source-birth policy. Result restoration still needs complete typed
entry/finalizer/birth provenance, receipts and transition presentation. The old
unrooted result codec must not be treated as covering those missing fields.

## Qualification

Source base: Language `806536457fd2ff284159fe65439973aa0f02ca4f` (`origin/next`).
The rooted snapshot/witness production code is unchanged from the pinned `7ec0fdaa`
source; this branch also consumes core physical codec commits `8e635c48` and
`ce2de4e1` (local cherry-picks `3dbc2875` and `b4977bfb`).

The final focused gate passed **10 tests, zero failures/errors/skips**, plus strict
Contracts Javadoc, in 1m26s. Six codec controls cover exact bodies and caller ownership,
full cyclic proofs/member mapping, representation cursors, detached shared witness
snapshots, checksum-valid semantic corruption, invalid counts/references, byte/depth
bounds, truncation and trailing bytes. Four historical tests include both original
controls and two new storage-backed second invocations using a fresh runtime and
detached snapshots. The cold computations match invocation identity, status, complete
admitted gas/trace identity, companion/receipt identities, owner set and complete
resulting snapshot bytes. The original source S3 and return bindings remain unchanged.

Two earlier ten-test attempts are not passes: the first exposed the missing explicit
outer-identity check plus an invalid test body (sibling fields beside a reference);
the second rejected the fixture's unpaired surrogate through the existing RFC8785
identity rule before storage. The final fixture uses a valid complete Node and a
supplementary Unicode pair. No identity rule or semantic assertion was relaxed.

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
./gradlew :blue-contracts-core:test \
  --tests blue.language.processor.closure.AffectedClosureSnapshotStorageCodecTest \
  --tests blue.language.processor.closure.RootedHistoricalWitnessTest \
  :blue-contracts-core:javadoc --offline --no-daemon --no-parallel --max-workers=1 --console=plain
```

This is a focused library implementation check, not a full corpus, public-API
compatibility, packaged-host, performance or durable-system acceptance claim.
