# Reuse the processor's already validated output snapshot

## Problem

The processor result constructor builds and validates a complete output snapshot,
then discards it. Rooted projection and result/view serialization rebuild and
re-finalize the same graph. In the real historical-reaction fixture, separately
encoding the result, output view and retained view performed nine full snapshot
verifications, although the processor had already validated the output.

## Solution and scope

Retain the exact validated output from successful **internal** result construction,
after proving ownership of its complete representation. The existing private
pure-state verification mechanism then lets projection and serialization reuse
that state without another component finalization. Rooted projection reuses it
only when its witness-role state is the identical required state; otherwise it
keeps the original reconstruction and verification path.

There is no public API, wire-format, cache policy, logical gas, event ordering,
history, failure or publication change. Every original transition, marker,
receipt, context and companion check during result construction remains. This
does not certify an entire serialized result or make it authoritative for a
different invocation. Decoder-issued frame reuse remains a separate mechanism.

## Ownership and security invariants

- Only internal construction with completed reusable finalization can issue the
  private output certificate. Public result constructors and decoder construction
  cannot issue this processor certificate.
- The exact standard `Node` class must own document ingress. A custom `clone()`
  can return a caller-retained standard node and therefore cannot establish this
  ownership.
- The retained graph must contain standard `Node`/`Schema` structural edges,
  detached containers and known immutable scalar classes. Node/schema subclasses,
  custom numbers, unknown mutable values, non-string map keys and retained custom
  comparators fall back to ordinary verification.
- Raw payloads are checked separately: a raw Node/Schema, even nested inside a
  list/map/array, is not a copied structural edge and cannot qualify.
- Qualification includes cyclic placeholder proofs and every historical witness
  original, not just the current document bodies. Proofs attach to the exact
  qualified snapshot, not an equal identity or another role wrapper.
- Canonical encoding, byte/depth limits, outer-envelope validation and all
  publication checks remain enforced. No host-controlled trust flag is added.

A global identity memo for arbitrary public results was rejected: public Node
subclasses can retain mutable aliases despite defensive `clone()` calls.

## Measured work and tests

At both owner epochs 2 and 3 of the real historical-reaction fixture:

| Full snapshot verifications for result + output + retained view | Before | After |
|---|---:|---:|
| Ordinary separate calls | 9 | 6 |
| Existing package-only shared-call experiment | 7 | 4 |

Each omitted full verification would invoke component finalization. A separate
equivalent unowned snapshot control requires three full verifications for three
encodings versus zero for the exact processor-owned output, with identical
bytes. These are work counts, not an E2E latency claim; the shared-call experiment
does not introduce a public API.

Focused native verification: **22/22 PASS**, 14-second build. Owners:

- `ProcessorOwnedSnapshotVerificationTest`: adversarial clone/schema/scalar/raw
  payload/cyclic-proof/witness aliases, public-constructor fallback and work counts.
- `DecodedResultDerivedSnapshotVerificationTest`: projection/retained roles,
  exact bytes, mutation isolation and physical bounds.
- `ClosureResultStorageReuseTest`: cyclic results, tight-budget rollback,
  complete-frame parity, corrupt/substituted frames and profile controls.
- `RootedHistoricalWitnessTest`: actual historical reactions and cold restart,
  including the preserved package-only measurement experiment.

This POC follow-up starts after the [merged-next port](rooted-external-state-next-port.md).
It does not extend that earlier qualification or claim the full MyOS long graph
now passes; that unchanged E2E gate is evaluated separately.
