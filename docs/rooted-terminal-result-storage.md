# Complete rooted terminal-result storage

## Problem and exact example

A rooted `ClosureProcessResult` is not just its visible heads and gas total.
The result also holds the frozen entry context, the original ordered topology
boundaries, monotone ownership and checkpoint-only predecessor/successor proof
snapshots. A final SCC cannot reconstruct that evidence. Historical calculations
also retain original source witness snapshots that must remain distinct from
later source heads. Finally, a rejected admission can charge candidate-prefix
work for documents outside the admitted input. Previously the result constructor
discarded that candidate after validating the result, so the ordinary public
constructor could not losslessly restore this failure.

For example, the focused rooted cycle is executed at its measured gas `G` and
at `G - 1`: the first result commits, while the latter retains the exact rejected
charge, work and trace prefix and has no rooted publication projection. Both
results must survive byte storage without executing a handler or inventing a
writable-owner list.

## Storage-only solution

`blue.language.processor.closure.ClosureProcessResultStorageCodec` exposes:

```java
new ClosureProcessResultStorageCodec(maximumBytes, maximumDepth);
byte[] encode(ClosureProcessResult result);
byte[] encode(ClosureProcessResult result,
    Function<String, CyclicSetProof> externalProofs);
ClosureProcessResult decode(byte[] bytes);
ClosureProcessResult decode(byte[] bytes, ProcessorRuntimeAccess runtime);
```

The current format binding is `blue-contracts/closure-process-result-storage/2`.
Version 1 is rejected rather than silently re-admitting retained events. Version 2
carries each original issued event capability with its exact Frozen construction;
see [event evidence storage](rooted-event-evidence-storage.md).
The codec uses the exact, bounded Language `ExactNodeStorageCodec` envelope
and `AffectedClosureSnapshotStorageCodec`; it does not flatten snapshot or
mixed Node construction modes. Snapshot storage itself has `decode(byte[])`,
with no runtime argument.

The processor retains the original private result-validation input, witness
state, managed resolutions, rejected candidate and rooted ownership snapshot.
The codec carries all result fields, including receipts, typed presentation,
complete companion/environment, component cyclic proofs, failure and candidate gas
prefixes. Restored demand DTOs do not regain their private emitted capability.
No public mutable owner-list or rooted authority constructor is added.

Decoding runs the existing result/transition validators and the pure component
finalizer, not PROCESS or any handler. It checks exact input/output snapshot
bytes, context-to-entry-snapshot binding, the closed descriptor's entry owners,
the rooted Contracts environment, rooted invocation/companion wrappers and
checkpoint predecessor/successor membership in the retained boundary sequence.
Monotone ownership is checked across **all original boundaries**, not merely the
final SCC. Original checkpoint proof pairs remain explicit retained evidence;
they are not inferred from body similarity. A canonical complete re-encode is
required after decoding. Exact event evidence is retained without another
provider/proof lookup or Source canonicalization. The runtime/proof callback
overloads remain source-compatible but their arguments are not consulted; the
proof callback still must be non-null. Prefer the no-runtime/no-callback methods.

The package-private typed field transport selectively reuses DTO field mappings
from the earlier experimental Coordination codec. It does not reuse the old
unrooted result factory, old snapshot layout, or Node serialization.

## Trust and limits

This is complete **terminal result** storage, not suspended-attempt continuation,
complete invocation-input replay, a complete SDK/store snapshot, or a host
publication/recovery implementation. A checksum detects corruption but does not
authenticate ownership: the host must select these bytes through an authenticated
pinned storage root. The codec must never be used to grant authority to arbitrary
submitted bytes. Host adapters must propagate storage bounds, corruption and
lookup failures as noncommitting physical failures.

The caller chooses complete byte and depth bounds. The whole result materializes
within those operational bounds, including retained witness/boundary snapshots;
this slice makes no lazy-loading, global-memory, caching or performance claim.
Event restoration does not grant fresh invocation admission or republishing
authority. Its original exact capability is selected by the authenticated host
record, not established by a storage checksum. Normal external event verification
still requires its original Source runtime or complete cyclic proof.
The underlying exact codec rejects unsupported host Java Enum shapes without
class loading, as documented in the core codec slice.

## Qualification

Base Language source is `806536457fd2ff284159fe65439973aa0f02ca4f`, with the
qualified core codec commits `8e635c48` and `ce2de4e1` and the complete witness
snapshot slice `2d917a3bcf0652b718ee3ae8a39c30b47b0f42b8`.

On 2026-09-11, the final focused native gate `64404` passed **17 tests**, zero
failures/errors/skips, plus Contracts Javadoc in 22 seconds:

- Complete result codec: 4 controls, including exact `G` / `G - 1`, ordinary
  result authority absence, byte/bound failures, and rechecksummed inconsistent
  private context/entry-owner evidence rejection.
- Rooted historical witnesses: 4 controls, including a detached stored witness
  used by a fresh runtime's second invocation with full result-byte comparison.
- Admission rejection: 3 genuine candidate-prefix failures, including candidate
  gas document ownership outside the original admitted input.
- Rooted checkpoint settlement ownership: 6 controls retaining original positive
  checkpoint proof and negative ownership conditions.

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
./gradlew :blue-contracts-core:test \
  --tests blue.language.processor.closure.ClosureProcessResultStorageCodecTest \
  --tests blue.language.processor.closure.RootedHistoricalWitnessTest \
  --tests blue.language.processor.closure.ClosureAdmissionRejectionProcessorTest \
  --tests 'blue.language.processor.closure.ManagedCheckpointSettlementOwnershipTest.rooted*' \
  :blue-contracts-core:javadoc --offline --no-daemon --no-parallel --max-workers=1 --console=plain
```

The preceding 16-test gate passed before the additional malformed-context
control and corresponding storage guards. These overlapping focused runs are
not additive corpus coverage and do not constitute a full Language or host
acceptance campaign.
