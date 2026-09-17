# Retaining original invocation and suspended execution evidence

## Problem and concrete example

The terminal-result codec preserves a completed result. That is insufficient
for a cold engine whose parent stopped before it could acquire a child source.
The retained continuation includes the complete original `ClosureInvocationInput`,
an optional `ClosureProcessRetryInput`, the ordered typed resource demands and
the selected **instance** in that list. A managed-occurrence demand also carries
an immutable processor-issued invocation marker, separate from its public
canonical demand identity. Rebuilding equal public fields does not restore that
marker, and must not grant prospective-birth authority.

For example, parent R first installs a child and suspends. After authenticating
the child, the expanded input suspends for a grandchild. Both continuations
still carry the original rooted entry context; the second additionally carries
the authenticated child-to-parent map. Recomputing the owner from the expanded
graph or replaying the parent to rediscover a demand is not exact restoration.

There are two related nonsuspended consumers: a terminal publication retains
its complete original input for provenance, and an independently supplied source
history may retain a standalone `ManagedDocumentTransitionReceipt` without the
whole original result. Such a receipt must keep its original typed event Frozen
construction, rather than asking a provider to re-admit its events.

## Minimal physical boundary

`ClosureExecutionEvidenceStorageCodec(maximumBytes, maximumDepth)` provides
three closed typed pairs:

- `encodeInvocation` / `decodeInvocation` retain complete original inputs.
- `encodeAttempt(input, retryOrNull, attempt, selectedDemandOrNull)` /
  `decodeAttempt` retain the associated continuation. The returned immutable
  `StoredAttempt` exposes `input`, nullable `retry`, `attempt`, and nullable
  `selectedDemand`.
- `encodeTransitionReceipt` / `decodeTransitionReceipt` retain standalone
  transition receipts and their original event capabilities.

The implementation reuses the bounded exact Node/snapshot/event/result codecs
and the existing closed field mappings. Each new operation has a versioned
physical envelope. Existing complete result format `/2` is unchanged. The
invocation transport covers all four cause kinds, including complete recursive
representation-transition provenance, direct deliveries, policy, environment,
admission candidates and the private `RootedInvocationBinding`.

The selected demand is stored by checked position and returned from the restored
attempt's list. Encode uses identity (`==`), not canonical demand equality, to
identify the caller's selected member. Existing nullable emitted markers are
retained exactly by a package-private trusted factory; storage never calls
`emittedBy` to upgrade a public demand. An unissued managed demand may be retained
as unselected data, but cannot be selected as continuation authority.

`DefaultClosureProcessor.processClosureRetry` rekeys its execution copy to the
retry identity before `resourceSuspension` attaches the producer marker. The
envelope therefore checks the ordinary identity or the actual retry identity,
not the base identity for both. It also checks the complete serialized retry
base, emitted cause/closure/generation, complete-result input snapshot and
committing rooted owner binding. The retry constructor independently verifies
its canonical resolution-set and retry identities.

`originalRootedBinding(input)` is a read-only projection of the existing input:
original context, delivery basis and entry invocation identity. This lets the
host check its separately retained rooted invocation record without serializing
a redundant context or re-deriving one from expanded state. The projection has
no public constructor and exposes no birth-parent map or mutation.

A successful rooted input must have a rooted publication projection, and an
ordinary input must not acquire one. This presence check is symmetric. It does
not require a projection for an early failure: those paths return the original
rollback evidence without publication. Review reproduced the reverse pairing
(rooted input plus ordinary successful result sharing the base invocation ID)
as a failing negative before adding this check; the G−1 control still restores.

## Trust and rejected shortcuts

These are authenticated **host-retained** storage bytes, not user-admission
formats. The host must select them through its pinned record and retain its
existing publication/history authority. A checksum is not a processor signature
or proof of a source publication. A DB administrator forging an authenticated
record is outside this boundary.

Decode performs bounded structural/identity consistency checks and canonical
re-encoding. It has no provider, runtime, handler or PROCESS argument. It does
not run the ordinary invocation verifier: that verifier can authenticate typed
Source through provider access. Standalone input transport also does not guess
that every input has an ordinary rather than internal retry identity. The
existing normal semantic validators still run when the restored evidence is
used for actual processing, birth acquisition or historical publication.

Rejected alternatives are flattening demands to BlueIds, public-constructor
copies of emitted demand authority, re-deriving the rooted owner after expansion,
replaying the parent during restore, provider re-admission of receipt events,
and requiring a whole result to exist for every standalone source receipt.

## Focused proof

Tests exercise a genuine nested rooted birth suspension after its producer has
closed; the restored selected member passes the unchanged birth guard and the
actual resumed full result, receipt/event ordering, gas and rooted companion
match the resident continuation. The second suspension retains the original
entry identity and authenticated birth map. Additional controls cover public
lookalikes, wrong gas/producer/rooted binding, mixed ordered demands, defensive
ownership, bounds and malformed/rechecksummed frames.

The existing actual managed-revision historical-retarget fixture now has a
separate storage control: its real demand survives retention, a new retry runs
only after restoration, and the complete retry result and exact retry identities
survive a second roundtrip. The unchanged checkpoint-history fixture has a
separate control restoring external, numbered and recursive representation inputs
before execution while retaining all original history assertions.

An additional actual retry control omits the demand's optional inline body while
preserving its canonical identity. Processing succeeds, but the old result `/2`
reader incorrectly invoked the constructor requiring a non-null inline value.
The red reproduced `NullPointerException: suppliedExactValue` during result
decode. The reader now selects the existing no-inline constructor on absence;
the format is unchanged, and no emitted marker is invented for that public
resolution value. Both inline and absent-inline complete retry controls remain.

Standalone receipt coverage uses an actual materialized nominal Source whose
original admission needs an external parent type. After the producer closes,
restoration under a denying provider preserves the original Frozen bytes; normal
external verification still calls and is rejected by that provider. Completed
rooted G and G−1 results retain their full result bytes and rollback evidence.

This is Language component qualification, not a complete durable SDK or MyOS
cold-start claim. Focused gate receipts and source archives are recorded in the
adjacent compact evidence manifest when qualification completes.

### Integration-discovered result-context regression

The first 59-control gate passed, but adding an actual final-result decode to
the nested birth control reproduced a second issue in the earlier result codec:
`readOwnership` unconditionally required the executed input snapshot to equal
the rooted context's original entry snapshot. `RootedOwnershipTracker` actually
retains the original binding together with the later executed input snapshot;
prospective-birth expansion intentionally changes only the latter. A separate
Coordination reciprocal pending-join fixture reproduced the same result-only
failure without session or index restoration.

The exact failing Language control is retained as `expanded-result-red` under
`/Users/kamil/Documents/Projects/Blue/rooted-execution-evidence-storage-evidence.7KQVQH`.
The correction passes the actual executed invocation identity into the existing
ownership reader. Only when it equals the original entry invocation identity
does the reader require that original entry snapshot to equal the executed
snapshot. It always checks the exact ownership input against the result input,
the context descriptor, reconstructed rooted companion, calculated owners,
finalizer boundaries and checkpoint pairs. The existing original-entry
wrong-context negative remains; the real expanded result and associated complete
attempt now exercise the intended distinction.

This restores the original private capability selected by the authenticated host
record; it does not independently authenticate changed, self-consistent private
fields in a forged record. The original context actually retains its entry
closure identity and owner descriptor, not a second hidden original snapshot.
Inventing one or recomputing owners from the expanded graph would change the
retained model rather than transport it. Final qualification supersedes the
earlier gate and includes this regression; no SDK E2E result is implied.
