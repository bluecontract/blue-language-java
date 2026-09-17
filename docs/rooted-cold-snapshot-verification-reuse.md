# Reuse of verified complete decoded snapshots

## Problem and example

This isolated POC follow-up builds on Language `13aa99e8` on the existing
`codex/poc-execution-frame-reuse` branch. It does not change the frozen baseline,
other engineers' branches, the specification or logical processing rules.

A restored rooted view can supply the same immutable snapshot to context
derivation, witness binding, invocation validation and later storage encoding.
Each path can repeat expensive component/proof verification even though the
complete decoder already verified that exact owned snapshot.

The preceding ring06 run used Coordination `7c7901c3` and MyOS `90913cd`.
Its second A-ready expectation expired after 600.252978 seconds: A was at
committedEpoch 51 / readyEpoch 23, B at 55 / 27 and C at 8 / 8. Separately, the
matching work inventory contained 50 COMPLETE items and 1 CLAIMED item. No OOM
was observed. Acceptance was incomplete; the timeout does not prove the target
state is unreachable. A writer sample identifies repeated verification but does
not establish its complete-run cost or the benefit of this change.

## Fix

Every actual completed snapshot record in a **complete owned snapshot decode**
receives a private verification certificate, including its nested witness DAG.
A cold decode first verifies each record and completes whole-envelope canonical
equality. A warm decode reconstructs a fresh owned graph from the same codec's
private, previously verified exact canonical bytes; it establishes the same fact.
A temporary identity-only manifest names exactly the parsed Java instances.
Each snapshot retains only a flag, not that manifest, bytes, an owner or a new
cache. `verifySnapshot` can reuse the pure immutable-state fact. No flag is
serialized or exposed through public API; no extra output graph is retained.

Public/factory snapshots and newly constructed role projections remain
uncertified. A nested snapshot qualifies only because it was itself constructed
by this accepted decode, not because it was reachable from a certified value.
The certificate cannot transfer to a different equal-identity object.
Ordinary successful verification or encoding cannot grant this certificate.
Context, cause, policy, current head/index, transition, gas and publication checks
remain unchanged. Storage's additional closure/occurrence identity checks,
physical byte/depth limits and canonical serialization still run.

## Why this boundary

The closed decoder constructs plain Nodes/schemas, owned containers and arrays,
complete copied cyclic proofs, and a local witness DAG. Public Node subclasses
can override clone(), so arbitrary caller-constructed snapshots are deliberately
not eligible. No host trust toggle, claimed-identity key, unbounded memo or new
encoding API is introduced. First-produced and newly derived snapshots still pay
ordinary verification; coverage of the observed write cost remains uncertain.

The initial cold-only and returned-root-only restrictions were unnecessarily
conservative. Cold parsing independently verifies every completed record; private
warm byte acceptance covers that complete DAG too. Backreferences select only
earlier completed owned records. No environment/provider lookup or caller-defined
class is involved. Membership and unchanged-body/epoch/binding checks in witness
role construction still run. Later standalone encoding of a nested value still
enforces its own physical limits. This removes repeated nested verification during
later witness serialization, but does not certify new derived output/role views.
Snapshot and retained-snapshot frames share a codec in Coordination's view
restoration; result frames use a separate codec.

## Verification

Nine focused controls cover exact cold/warm decoded-DAG issuance, wrong-instance
certificates, new derived-view exclusions, public clone-alias mutation, complete
body/proof ownership, disabled retention, malformed/noncanonical envelopes,
smaller-profile encoding/decoding, context restrictions, and actual PROCESS
success/G−1 equality for both cold and warm inputs against ordinary execution:
complete results and ordered gas/event/checkpoint evidence. Warm controls also
mutate caller byte/body/witness copies and verify unchanged canonical output.
Nested multi-level controls preserve shared versus distinct equal witness values,
prove zero repeated pure verifier work for the accepted instances, reject narrower
standalone encoding profiles, and keep independent cold/warm graphs detached.
Existing snapshot, execution, result, historical-witness and tight-gas suites are
also required. Source preparation is not qualification: test results, export,
E2E completion and any measured improvement are recorded by the supervising run.

## Follow-up: owned derived result and retained epoch views

Complete result decoding constructs fresh output/projection snapshots from its
owned decoded fields. Those wrappers were not parsed snapshot records, so their
successful pure verification was previously lost. In Coordination view recovery,
encoding the projection repeats that verification; constructing a retained epoch
view verifies once, then its immediate canonical encoding verifies it again.

The result codec now issues a separate, exact-object derived-state proof only
after its full result validation and outer canonical byte comparison succeed.
Both the derived output and the rooted projection qualify. They do not gain a
parsed-snapshot certificate. A retained epoch view made from that owned projection
still performs its ordinary first full verification, then receives its own proof.
Subsequent pure checks reuse that fact. Each call returns a new wrapper with the
original witness aliases; no snapshots, logical IDs or equal payloads are interned.

Public snapshots and ordinary producer results remain ineligible. Successful
verification alone is insufficient: a public Node subclass can override clone()
and retain a mutable caller alias. Complete decoder ownership is the additional
proof here. No result is trusted before the enclosing acceptance finishes, no
proof transfers to an arbitrary role copy, and transition validation, owner and
epoch bounds, storage identities, byte/depth bounds and canonical bytes remain
unchanged. This saves the repeated projection and retained-view checks, not their
initial verification or the serialization of growing historical payloads.

Focused controls count actual pure verification for decoded projections and
retained epoch wrappers; compare exact cyclic result/snapshot bytes; mutate
returned body/proof copies; reject changed evidence, foreign owners, illegal epoch
advances and smaller storage profiles; and retain the existing malicious-clone,
noncanonical/result-tamper, historical-witness and exact-gas controls.

On 2026-09-15 the following focused invocation passed once: 67 tests across nine
classes, zero failures/errors/skips; Gradle reported 1m 51s. This is source-level
verification, not an E2E timing claim.

The supervising follow-up also passed `:blue-contracts-core:javadoc`,
`:blue-contracts-core:apiBaselineDiff` and
`:blue-contracts-core:verifyJavaPackageCycles` with fresh task execution.
No public API difference or package cycle was introduced.

```sh
./gradlew :blue-contracts-core:test \
  --tests blue.language.processor.closure.DecodedResultDerivedSnapshotVerificationTest \
  --tests blue.language.processor.closure.DecodedSnapshotVerificationCertificateTest \
  --tests blue.language.processor.closure.AffectedClosureSnapshotStorageCodecTest \
  --tests blue.language.processor.closure.ClosureProcessResultStorageCodecTest \
  --tests blue.language.processor.closure.ClosureResultStorageReuseTest \
  --tests blue.language.processor.closure.ExecutionStorageCallTest \
  --tests blue.language.processor.closure.RootedHistoricalWitnessTest \
  --tests blue.language.processor.closure.RootedWitnessContextTest \
  --tests blue.language.processor.closure.RootedWitnessSelectionTest --console=plain
```

## Follow-up: retain decoded output and finish one owned-envelope verification

The next ring still timed out. Its bounded profile located full snapshot checks
inside snapshot readers and writers, including complete-result fallback encoding.
This identifies relevant paths, not a measured speedup or proof that either
correction alone makes the ring finish.

Two source gaps remained. First, the result decoder certified its independently
derived output but returned only the result, discarding that temporary output.
A later full encoder fallback constructed another uncertified wrapper. Second,
one cold snapshot envelope can contain more than the call memo's 32 verification
entries. Reading all records evicted early proofs before canonical serialization;
the writer then verified those same owned records again. Stored witness-role
construction also bypassed that memo when checking already completed children.

The decoder now attaches a private, exact-result-bound output receipt only after
the complete result's outer canonical comparison succeeds. Fallback encoding
reuses that exact derived snapshot, still writing the complete bounded envelope.
Public/producer results and new result wrappers do not inherit the receipt.
This deliberately retains one additional derived output snapshot per decoded
result; it shares immutable result components/witnesses but retains copied body
state. There is no new global cache. Coordination's encoded-frame weight remains
an estimate: the output is already represented on the wire, but its existing
multiplier is not a proved retained-heap bound and this extra retention needs heap
measurement rather than a claim of unchanged memory use.

Each snapshot parse also owns a private completed-record identity index alongside
its required backward-reference list. Cold records enter it only after ordinary
pure-state and identity verification; warm records come from the codec's private,
previously accepted exact bytes. This local evidence serves only stored-witness
construction and the same envelope's canonical writer. The complete byte bound
already bounds the number of parsed records. Failure discards the local index;
durable snapshot certificates still issue only after whole-envelope acceptance.
The generic call memo remains capped at 32 entries. All source membership,
unchanged-witness, constructor, alias/backreference, byte/depth and cold canonical
checks remain. Transition, gas, failure-receipt and outer result checks are
unchanged. No equality-based interning or generic successful-verification flag is
introduced.

New controls exercise actual full-verifier observations for 43 wide records,
late parent rejection after 42 verified children, valid retry after failure,
warm reconstruction, and full public result encoding with reuse absent, disabled
or evicted. They preserve exact bytes, independent envelope identities, public
mutation defenses and narrower physical bounds. The existing malicious clone,
canonical alias, transition, gas and receipt owners remain in the focused gate.
The counter observes codec-routed pure verification, not every other constructor
or identity check, and is not production profiling telemetry.

The follow-up passed its first grouped run on 2026-09-15: the same nine complete
owners listed above now contain 70 tests, with zero failures/errors/skips; Gradle
reported 21 seconds. The command also included
`:blue-contracts-core:apiBaselineDiff`, `:blue-contracts-core:javadoc` and
`:blue-contracts-core:verifyJavaPackageCycles`. Public API inventory generation,
Javadoc and package-cycle verification executed; the API comparison was
up-to-date because the newly generated inventory was unchanged. All five changed
production sources and both changed test sources had identical SHA-256 hashes
before and after the gate. Only this qualification paragraph changed afterward.
This is focused source qualification, not combined-host or ring acceptance.

## Follow-up: one accepted output encoding and an exact epoch-only transform

Ring 18 still failed after 879.236 seconds. Its bounded sample separated remaining
snapshot verification from Coordination lineage-membership work. This Language
follow-up removes two source-proven redundant tasks; it does not claim to fix
whole-history payload amplification or establish a ring speedup.

During cold result decoding, the independently derived output was already
verified, encoded and compared with the accepted stored output. Those bytes were
discarded before outer canonical encoding. The outer writer visits input first,
which can evict the same derived output from the 32-entry call memo. A private,
exact-result/output-bound `DecodedResult` now retains that one successful output
encoding through the outer byte comparison. Its bytes never escape or become an
early durable certificate. The result envelope is still fully written and
compared, and ordinary encoding still verifies its output. This extends the
lifetime of one already-produced bounded byte array only through this decode;
it adds no cross-call byte cache or extra retained result payload.

The retained-view factory changes only owner epochs. The pure verifier depends
on document bodies and marker flags, the binding graph and original witnesses,
component generations, BlueIds and complete proofs; it does not read document
epochs. When the exact source already has library-owned verification, a private
epoch-transform certificate can therefore establish the fresh wrapper's same
pure-state facts. The factory still enforces the exact owner set and unchanged
or +1 positions, runs both normal snapshot constructors (including witness body,
role and epoch checks), and recomputes the closure identity. Unowned public or
producer sources still undergo ordinary full verification. Fresh wrappers remain
distinct and preserve their original witness aliases.

Projection matching, producer trust flags, generic verification memoization and
returning the source object for unchanged epochs are excluded. Public Node
subclasses can override clone(), so successful producer verification still does
not prove immutable ownership. Cold untrusted records, transitions, receipts,
gas, context, canonical bytes and operational bounds retain their existing
checks. These two changes form one focused follow-up, not separate E2E claims.

Controls force output eviction with a one-entry/no-payload call memo, reject a
late checksummed malformed outer frame, and verify valid retry. Existing cyclic
result controls cover unchanged/+1 ordinary-versus-owned retained bytes. Actual
independent historical-witness processing additionally checks zero repeated
pure work, detached public mutations, rejection of forged witness body/epoch and
foreign owners, cold round trips and smaller physical profiles. Existing
malicious clone, malformed snapshot/result, alias, tight-gas and failure-receipt
owners remain part of the grouped gate.

Qualification: the same nine complete test owners passed together (71 tests,
zero failures/errors/skips) on 2026-09-15, with API baseline, Javadoc and package
cycle guards in the same successful 17-second Gradle invocation. The changed
observer expectations count only eliminated pure-state verification; ordinary
source verification and all byte, mutation, malformed-input, ownership, bounds,
transition and gas assertions remain. This is a focused semantic gate, not a
full-suite or end-to-end performance result.
