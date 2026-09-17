# Host-owned reuse of complete, verified terminal results

## Problem and concrete example

The isolated PostgreSQL ring experiment still timed out during the second historical
attachment. Its bounded stored-frame census found two **different** complete managed
cause records (958,989 and 958,843 bytes) containing the **same** 727,299-byte terminal
Contracts result. Caching only the whole cause cannot reuse that shared result.
The run-09 writer sample also contains 155 nested result-decode samples, including
117 with snapshot verification. These are sampled stack counts, not additive timings,
measured cache-hit rates or proof that every first decode is unnecessary.

Coordination already retains complete results under its one bounded runtime cache,
but nested results inside Language's execution-evidence codec used a separately
constructed result codec. They could therefore repeat complete immutable result
restoration even when the identical result was already retained by the host.

## Correction and justification

Add an optional `ClosureProcessResultStorageCodec.Reuse` port and an overload of
`ClosureExecutionEvidenceStorageCodec` accepting the configured final result codec.
The injected codec must match the execution codec's exact byte/depth profile. Existing constructors keep
their ordinary cold behavior; the stored formats and processing rules do not change.

The port has only two operations:

- `getOrDecode(FrameKey, Supplier<VerifiedFrame>)`: retain or load one exact complete
  terminal result. The supplied loader performs the unchanged raw decode, all result
  and snapshot validation, and complete canonical re-encoding check before issuance.
- Optional `findEncoded(exactResult, maximumBytes, maximumDepth)`: reuse the bytes of
  that **same object** when a retained decode-issued handle exists. A merely equal
  processor-produced result takes the ordinary encoder path; encoding does not issue
  a verification handle.

`FrameKey` and `VerifiedFrame` are immutable, library-created types with private
constructors. The key owns its full bytes and exposes only copies. On a hit, Language
checks the complete bytes, identical depth profile and requested complete-frame byte
bound. The issuance byte bound may differ under the narrowly defined
[compatible result byte-profile rule](rooted-compatible-result-byte-profiles.md). For encoding it additionally
requires the exact decoded result object identity. A host cannot return an arbitrary
result, byte array or public trust flag as a verified handle.

Language does not add a cache, retention budget or Redis dependency. Coordination's
existing host-configured weighted LRU owns this family together with other retained
artifacts. Accounting must include the owned frame bytes and decoded graph. Clear and
eviction must release reverse identity entries too. Nested and standalone readers must
use one family/compatible profile; do not wrap the configured codec in another same-key cache
loader, which could self-join its own in-flight load. The raw supplied decoder does
not recursively request this result family.

## Boundaries preserved

Only complete immutable terminal results are shared. Original invocations, selected
demands, retry associations, mutable sessions and active work ownership are not interned.
Each enclosing cause/input/receipt still runs its ordinary type, canonical, identity
and historical-association checks. Host physical record authentication, current
membership, publication and selected-history checks remain outside this pure reuse.
A valid inner result can be retained even if a separately validated enclosing record
is invalid; it cannot make that enclosing record valid.

No cache entry is issued by a failed result decode. Oversized selections fail before
the retention callback. A handle issued under a wider byte bound is usable only if
its complete frame fits the current bound and its depth profile is identical.
Cold decoding remains the fallback after eviction or when retention is disabled.
Snapshot alias topology within a complete result remains unchanged; no extra snapshot
interning, public result constructor authority, logical gas or failure-policy change
is introduced.

## Verification scope

The new controls cover complete frame and exact-object reuse across codec owners,
defensive byte ownership and public Node copies, corruption/truncation and a validly
checksummed wrong output, substituted handles/profiles/objects, narrow physical
bounds, disabled/evicted/cleared retention and failed host loads. The existing full
historical settlement fixture additionally decodes two different cause frames
with the same real transition result. The second frame changes the receiver occurrence
identity; it is a physical-codec control, not a claim that two complete receiver
invocations were executed. Its target raw reconstruction count is **2
without retention and 1 with retention**, while complete outer bytes remain equal
and invalid outer causes remain rejected.

The existing actual PROCESS control compares complete encoded successful results at
the exact gas budget and rejected results at one unit below it, including ordered
events, gas trace, checkpoints and rollback. It now also compares cold and retained
terminal-result transport without changing its processing oracle.

This follow-up is pending fresh grouped library qualification and MyOS acceptance.
It does not claim that the ring now completes, nor remove first-produced encoding,
unique history work or other first-decode costs. Those remaining costs must be
reported separately from correctness and from this proven duplicate-frame case.
