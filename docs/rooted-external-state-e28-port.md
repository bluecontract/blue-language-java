# Physical storage port onto the qualified rooted baseline

Latest isolated physical-reuse follow-up: [host-owned complete-result reuse](rooted-complete-result-storage-reuse.md),
following [verified complete decoded snapshot DAGs, cold or warm](rooted-cold-snapshot-verification-reuse.md),
following [decode-local verified execution frames](rooted-execution-frame-reuse.md).
Its qualification is separate from the historical source and results recorded below.

## Scope and provenance

This isolated POC branch starts at qualified Language/Contracts
`e28ce80531663074f780367238785a5cc7f5171c`. It does not modify the frozen
baseline or its CI workflow, specification, generated release identity or
processing rules. Original storage commits are retained with cherry-pick
provenance, not copied over the baseline's source tree:

| Original commit (base `806536457fd2ff284159fe65439973aa0f02ca4f`) | Port | Physical capability |
|---|---|---|
| `3dbc28759e75a91b243fa5f678ff600162709a67` | `4eb773cf` | Complete exact Node/Frozen construction and resolver snapshots. |
| `b4977bfb97fa0094ba0ec78b9a48f23bc573d997` | `f3086c4b` | Explicit operational transport limits for unsupported host Java scalars. |
| `2d917a3bcf0652b718ee3ae8a39c30b47b0f42b8` | `afe05615` | Complete snapshot/witness DAG storage. |
| `d2ce037d3a274d72f7475cba3eefee4e80e58483` | `31486356` | Complete terminal result, ownership and rejected-charge evidence. |
| `bd09c2818b7dd368c1adddb0cda3f2c020877666` | `3cb09f62` | Already-issued exact event evidence, not provider re-admission. |
| `f241be7ee031cace33ba37ae93e6801d9895d2af` | `e567c8a1` | Original inputs, attempts, selected emitted demands and transition receipts. |
| `8542285144a8969f157d73e73292398885105c46` | `f457f523` | Standalone issued demands, prepared retries and historical causes. |

The earlier codec documents and receipts describe their **original** source
and focused tests, not qualification of this port. No old POC closure, commit,
gas or birth policy is imported. The formats are authenticated host-storage
contracts, not input admission or execution/publication proofs. Decoding must
not execute PROCESS or consult a provider; byte/depth bounds are operational
failures and must not become terminal document outcomes. These codecs still
materialize their bounded values; they do not demonstrate bounded runtime
residency, a database adapter or efficient distributed processing.

## Required baseline reconciliation

**Problem.** The older storage stack predates e28's independent immutable
witness contexts and fresh witness selection. A raw replacement of
`RootedWitnessFrame` would lose `withSelectedProofs`/`calculating`, allowing an
A11 proof that contains D2 to be confused with a separately selected D14 primary.

**Correction and reason.** Resolve the only production cherry-pick conflict by
retaining both qualified methods unchanged and adding only the package-private
`storedOriginals`/`fromStoredOriginals` transport hooks. The current immutable
state still consists of its exact source-to-complete-proof map; no new hidden
selection field is omitted or inferred. The codec retains that complete DAG,
including distinct proof objects for the same lineage, and uses current graph
verification. `RootedProcessingContext` keeps its e28 specification and behavior;
its port is only package-private construction/read access. The existing
`FullLifecycleAdmissionTest` changes are storage variants around the maintained
scenario, not replacements of the new source-epoch controls.

`RootedWitnessSelectionTest.selectedIndependentContextsSurviveColdStorageAndExactGasReexecution`
adds the actual e28 fixture: A epoch 11 still refers to D2 while the selected D
primary is epoch 14. After closing the producer it restores the snapshot,
original invocation, complete successful result and G−1 failure. A fresh
processor repeats the restored invocations and compares complete encoded result
bytes, including original ownership, witnesses, events, receipts and gas traces.
The test also explicitly checks the suppressed A→D calculating edge, immutable
A/D positions, root-only publication, rejected charge and rollback. No expected
identity is derived from a mutated or flattened cold outcome.

**Problem.** The older resolver/Frozen codecs predate e28's exact occurrence
Source API and source-aware minimization. A codec that loses the Source lane,
construction modes or valid list replacement could change named-Compute
validation after reopening even if the resolved body looked the same.

**Correction and proof scope.** Preserve the qualified `WorkingDocument`,
`ReferenceTransparentPathAccess`, identity reconstructor and minimizer bytes.
`WorkingSourceContributionsStorageTest` covers four cold producer/consumer
cases: inherited definition under a local container overlay, explicitly authored
definition versus type defaults, retained slot-type definition after `$replace`,
and replacement that must discard an ordinary old definition. Full resolver and
Frozen contribution bytes round-trip; the fresh working document still yields
the independent expected exact definition and field-presence mask. Decode has
no provider. Subsequent ordinary verified reference reads may use the new
consumer's provider; this test does not claim serialization of an entire warm
reference cache. Existing source-aware minimization controls remain in the
qualification group.

No storage format change was required by source inspection: the existing map,
complete resolver Source/Canonical/Resolved lanes and Frozen construction fields
already represent the newer operands. This is a testable claim, not a recorded
green result before the new group executes.

## First coherent verification package

Parent-owned execution only; **not yet run on this source** when authored.
Run each complete owner, preserving parameterized cases and original adjacent
oracles. The eight storage owners include the new working-contribution owner.
Do not rerun the complete release gate for every physical-codec increment.

```sh
./gradlew :blue-language-core:test \
  --tests blue.language.snapshot.ExactStorageCodecsTest \
  --tests blue.language.merge.ResolvedSnapshotStorageCodecTest \
  --tests blue.language.identity.CanonicalTypeIdentityEvidenceTest \
  --tests blue.language.matching.FrozenTypeIdentityParityTest \
  --tests blue.language.runtime.NestedValueReferenceIdentityTest \
  --tests blue.language.runtime.SourceAwareMinimizedOverlayTest \
  :blue-contracts-core:test \
  --tests blue.language.processor.ExactEventIdentityEvidenceStorageCodecTest \
  --tests blue.language.processor.WorkingSourceContributionsStorageTest \
  --tests blue.language.processor.ReferenceTransparentExecutionTest \
  --tests blue.language.processor.closure.AffectedClosureSnapshotStorageCodecTest \
  --tests blue.language.processor.closure.ClosureProcessResultStorageCodecTest \
  --tests blue.language.processor.closure.ClosureExecutionEvidenceStorageCodecTest \
  --tests blue.language.processor.closure.ClosureEventStorageTest \
  --tests blue.language.processor.closure.RootedHistoricalWitnessTest \
  --tests blue.language.processor.closure.ClosureAdmissionRejectionProcessorTest \
  --tests blue.language.processor.closure.FullLifecycleAdmissionTest \
  --tests blue.language.processor.closure.ManagedCheckpointSettlementOwnershipTest \
  --tests blue.language.processor.closure.RootedWitnessContextTest \
  --tests blue.language.processor.closure.RootedWitnessSelectionTest \
  :blue-language-core:javadoc :blue-contracts-core:javadoc \
  --continue --no-parallel --max-workers=1 --no-build-cache --console=plain
```

The runner must pin the actual clean commit/tree, Java/Python environment and
record fresh complete XML identities plus archives, including failures. The
command above selects 19 whole owners; actual invocations come from the native
test results, not the older receipts or a guessed fixed count. Module/public-API,
generated current-source inventories and final dependency-export/release gates
remain a separate integrated-PoC qualification step; e28's baseline receipts do
not qualify the newly added public storage APIs.

## Per-call snapshot storage reuse (historical 95844a2 slice)

This section records the original call-local change. The accepted-byte successor
below adds a separately bounded byte-only lifetime; it does not extend the
lifetime or alias scope of the decoded-object memo described here.

**Problem and observed example.** Cold stored-result verification repeatedly
decodes the same complete snapshot envelopes in input, rooted ownership,
boundary and checkpoint fields. It then semantically verifies and encodes those
immutable snapshots again for exact cross-field and final canonical-byte
comparisons. The parent-owned ring diagnostic on the earlier MyOS/Coordination
POC tuple retained `myos-poc-568-jfr-ring.3te0ff/deep-execution-events.jsonl` in the
external resumption evidence directory. Among 2,049 command-writer samples,
1,361 contain `ClosureEvidenceVerifier`, 1,056 the result codec and 1,369 the
snapshot codec. These overlapping stack counts locate repeated work; they are
not additive wall-time attribution, a pristine performance measurement, or
proof that this change satisfies an application deadline.

**Correction.** The isolated `codex/poc-storage-codec-reuse` branch starts at
`b8fd6f484deed71e4f0d283a33b16fddf15fafef`. Only
`AffectedClosureSnapshotStorageCodec`, `ClosureProcessResultStorageCodec` and
the package-private `SnapshotStorageCall` implement the optimization. One
explicit scope belongs to one public encode/decode invocation and is cleared
on success or failure. A result call shares this scope across its nested
snapshot operations, comparisons and complete canonical re-encoding:

- A successful semantic snapshot verification may be reused only for that
  exact immutable Java object, never merely its closure, component or head ID.
- A repeated snapshot envelope may reuse a decoded snapshot only after a full
  successful decode, all typed/semantic checks, checksum/framing/depth checks
  and exact canonical-byte comparison. The key comparison includes every byte;
  no digest or semantic-ID equality substitutes for exact bytes. Entries created
  by encode alone cannot authorize a decode hit.
- Canonical snapshot encodes may reuse bytes for the same immutable object.
  A fresh output snapshot derived from the restored result is still constructed,
  independently verified and compared with the asserted stored output. Only
  then is that same derived object reused in final result encoding. Every
  original input/ownership, boundary/predecessor/successor, companion and whole
  wrapper comparison remains in place.

The default scope retains at most 32 canonical entries and
`min(maximumBytes, 8 MiB)` of detached canonical-envelope bytes, plus at most
32 successful-verification identity references. LRU entries are evicted before
another byte array is retained; oversized entries and exhausted identity slots
fall back to ordinary validation/encoding. The identity set is independently
FIFO-bounded. These are payload-byte and entry/reference bounds, **not** a bound
on decoded object graphs, caller copies, total heap, semantic graph size or gas.
An identity reference can reach an entire immutable witness DAG, so the
existing codec byte/depth limits still matter. There is no additional retention
after the call. Standalone snapshot calls do not retain a useless final envelope
copy immediately before closing their scope. A package-only 0/0 control disables
reuse; it is a deterministic verification control, not a new public setting.

**Why this is safe and deliberately narrow.** `AffectedClosureSnapshot` retains
immutable copied collections. `ManagedDocumentSnapshot.document()` and cyclic
proof accessors return defensive copies; `RootedWitnessFrame.State` retains an
immutable exact-original map. Supported Node storage values are detached by the
existing codec. Same-closure snapshots with different witness roles remain
different storage records. Returned encoded arrays are never the memo's own
arrays. Witness factories, snapshot constructors, transition/finalization and
publication validators are unchanged. They may still perform their own checks;
the counters measure only codec-directed semantic verification calls, not all
semantic work in those constructors.

At this stage there was no ThreadLocal, shared codec-instance cache, cross-call/cross-owner
cache, mutable Node cache, provider lookup, handler invocation, logical result
reuse, gas adjustment or wire/public API change. Formats remain snapshot `/1`
and result `/2`. Host authentication is still mandatory; checksums are not
publication authority. No baseline source, CI workflow or semantic-core rule
is changed. A generic persistent cache or a semantic-validator bypass would
broaden ownership and invalidation assumptions; this call-local physical reuse
does neither. Remaining first-load validation is intentionally not removed.

**Tests and pending qualification.** The two codec owners retain all ten
original cases and add six plain cases (16 total). The existing
`RootedWitnessSelectionTest.selectedIndependentContextsSurviveColdStorageAndExactGasReexecution`
also compares cached/disabled whole-result bytes and positive reuse counters in
its real A11/D2 + independent D14 historical fixture, including G-1 failure.
This checks nested witness aliases without a new scenario or weakened oracle.
Controls compare cached and
disabled canonical result bytes/counters; preserve exact receipts/gas and no
handler calls; exercise entry/byte eviction and oversized/disabled fallback;
mutate caller arrays and Node clones; check close/separate-call isolation;
reject repeated wrong-body verification, wrong heads, checksums, framing,
valid-but-unsorted witness encoding, wrong derived output and existing forged
rooted contexts. Existing cyclic proofs, exact gas/G-1 rollback and independently
selected historical witness contexts remain whole-owner controls. No JVM or
test run is claimed for this new source before the parent-owned gate.

Proposed coherent group (retain the maintained pinned Java/Python environment,
clean source/tree and complete fresh native XML/archive guards):

```sh
./gradlew :blue-contracts-core:test \
  --tests blue.language.processor.closure.AffectedClosureSnapshotStorageCodecTest \
  --tests blue.language.processor.closure.ClosureProcessResultStorageCodecTest \
  --tests blue.language.processor.closure.ClosureExecutionEvidenceStorageCodecTest \
  --tests blue.language.processor.closure.ClosureEventStorageTest \
  --tests blue.language.processor.closure.RootedWitnessContextTest \
  --tests blue.language.processor.closure.RootedWitnessSelectionTest \
  --tests blue.language.processor.closure.RootedHistoricalWitnessTest \
  --tests blue.language.processor.closure.ManagedCheckpointSettlementOwnershipTest \
  :blue-contracts-core:javadoc \
  --continue --no-parallel --max-workers=1 --no-build-cache --console=plain
```

This is an affected storage/semantic-control package, not full release or paired
MyOS acceptance. Export, downstream cold-storage controls and the exact frozen
application diagnostics remain separately source-bound follow-ups.

## Accepted snapshot bytes with fresh parsing (candidate, not qualified)

**Current problem and evidence.** On the already qualified Language `95844a2` /
Coordination `3fd0d5e` tuple, the MyOS `12489d7` reciprocal-cycle diagnostic still
exceeded its unchanged completion deadline. The current recording is
`rooted-external-resumption-evidence.c0VVLS/myos-source-124-jfr.MPEqyp/on-stop.jfr`
(SHA-256 `5666510827463e5d99903be8829dc2f94231e0de4be92633f6d235a0f5c08c19`).
Its bound cycle writer72 has 2,567 execution samples: 1,437 contain
`ClosureEvidenceVerifier.verifySnapshot`, 1,139 `SnapshotStorageCall.verify`,
723 snapshot `encodeInCall`, 655 `DocumentSessionStorage.decodeView`, and 202
`encodeView`. These overlapping, often truncated stack-presence counts are not
invocation counts or wall-time shares. They locate remaining storage work but
do not measure exact-byte hit rates or prove a speedup. This is the current124
recording, not the earlier568 profile described above.

**Correction.** `codex/poc-snapshot-accepted-bytes` starts at exact `95844a2`.
Only `AffectedClosureSnapshotStorageCodec` and `SnapshotStorageCall` change
production behavior. A private per-codec LRU retains exact envelope bytes only
after a successful complete snapshot decode, semantic validation and canonical
roundtrip. Encoding alone, failed decoding and partially validated snapshots
cannot create a certificate. A full-byte comparison, not a semantic identity or
digest alone, selects a hit. Null/truncated/over-bound input is refused before
allocating a detached caller copy. Misses validate that private copy; hits parse
the private previously accepted bytes, so caller mutation after selection cannot
change the parsed evidence.

The existing same-call memo also receives the privately decoded envelope bytes.
It must not clone the caller's array after decoding: a caller changing that array
during validation could otherwise associate changed bytes with the original
valid snapshot. A package-private decoded-envelope pair keeps the memo key and
parsed graph bound without a second complete input copy or extra canonical
encoding. Cold/warm handoff controls cover this through the actual memo path.

Every public decode still builds fresh objects and its own inline backward-reference
table. The hit omits only `call.verify` and the snapshot canonical comparison for
that exact lexical parse; it cannot disable verification for another envelope,
new derived output or later encode. Existing same-result-call object reuse stays
unchanged. In particular, two separate equal-proof decodes remain two separate
objects when later composed as B/C witnesses; an explicitly shared proof retains
its different canonical back-reference encoding.

**Checks deliberately retained.** Checksum, framing, depth, back-reference and
constructor checks still execute on a hit. `RootedWitnessFrame.State.fromStoredOriginals`
continues independently verifying every original witness. Result constructors,
finalization, gas/receipt/owner/checkpoint/companion checks, newly derived output
validation and complete result/view comparisons are unchanged. An individually
valid nested snapshot may remain certified when its enclosing result later fails;
the result must fail again, and receives no certificate of its own. No provider,
handler, current-head lookup or publication authority is reused by this cache.

**Bounds, concurrency and lifecycle.** The additional cache retains at most32
entries and `min(maximumBytes, 8 MiB)` of payload bytes per codec instance, with
eviction before retaining another array and oversized fallback. It retains no
decoded graphs. Lookup/admission/LRU statistics are synchronized; parsing uses
private local state outside the lock. Concurrent misses may independently validate
the same bytes and admit one entry. Package-only tighter/0-0 bounds are test
controls, not new public settings. A disabled `SnapshotStorageCall` also bypasses
certificate lookup **and admission**, so existing wholly uncached comparisons
remain meaningful.

This is additional instance-lifetime memory, not the previous call's8MiB aggregate
budget, total heap, or a per-runtime/owner bound. Active parser copies, call-local
bytes/graphs and multiple codecs remain separate costs. Codec instances have no
close method: bytes remain reachable with the codec until eviction or collection,
including through a retained closed Coordination scope. No explicit owner-close
clear or cross-attempt persistence is claimed. Counters saturate rather than
affect processing; they expose only hit/full-decode-attempt and retained/peak
byte-entry totals. The package-only handoff control receives no bytes or graphs.

**Rationale and alternatives.** Persistent decoded-object interning could change
identity-based witness aliases. Accepted bytes plus fresh parsing avoid that
change. A codec-only certificate does not eliminate constructor verification or
fresh derived snapshots. Coordination also owns separate result and standalone
snapshot codecs, so they do not share certificates within one `decodeView`.
Owner-local unchanged-view staging reuse is a separate, narrower alternative for
the smaller encode path; it is not implemented in this candidate. Keeping these
changes separate permits useful attribution. No API signature, format (snapshot
`/1`, result `/2`), semantic rule, gas, timeout, storage capacity, provider call,
baseline or workflow changes are included. No application deadline result or
performance improvement is claimed.

**Controls and pending gate.** Four new snapshot-owner tests cover decode-only
admission/copies, byte-entry eviction/oversize/disabled behavior, pre-copy bounds,
and deterministic two-thread cold/warm handoffs with caller mutation. Existing
controls additionally retain distinct/shared witness aliases, repeated warmed
checksum/head/body/framing/canonical corruption rejection, and repeated rejection
of invalid outer contexts/derived outputs despite valid cached nested snapshots.
The real A11/D2 plus independent D14 storage scenario explicitly observes warm
certificate hits for its snapshot, complete result and G-1 result, with unchanged
canonical bytes, witness selection, gas/rollback and cold re-execution oracles.

The changed complete owners contain30 plain tests: snapshot codec15, result
codec6, rooted witness selection9. The eight-owner command in the previous
section now selects71 plain tests (15+6+9+3+8+9+4+17) plus Contracts Javadoc;
it remains the proposed coherent storage/witness group. Parent-owned source,
dependency, XML and archive guards must bind the eventual frozen candidate.
No JVM, build, export or new test result was produced while authoring this change.
