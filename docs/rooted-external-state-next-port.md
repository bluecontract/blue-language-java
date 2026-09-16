# POC storage/reuse port onto merged Language next

## Pins and exact scope

- New baseline: merged `origin/next` at `979916fe9c450cbe6344fa1a20a66e382b72f551`
  (published version 3.1.0-rc.27).
- Old POC base: `e28ce80531663074f780367238785a5cc7f5171c`.
- Donor POC head: `7cf583e7` (complete exact storage, invocation/result/witness
  restoration, library-issued verification reuse and compatible result byte caps).
- Isolated delta carrier: `82277e64d071c530560deac4d697309e7ffe0ff4`, whose sole
  parent is e28 and whose tree is the donor head. Applying this delta with
  `cherry-pick --no-commit` onto the new baseline had no conflicts. This is not a
  merge of the old baseline and does not import open-PR source.

Only the net POC difference from e28 to the donor is carried forward. Existing
upstream Source-contribution APIs, retarget rules, witness-selection fixes,
processing-event continuity and release/package bindings remain authoritative.
No workflow, specification, generated conformance fixture or release identity is
changed by this port. New API surface is the additive storage/reuse surface of
the donor, not a new processing policy.

## Explicit C03 exclusion

The following four production files remain byte-identical to the pinned next:

- `blue-language-core/src/main/java/blue/language/identity/CanonicalIdentityInputReconstructor.java`
- `blue-language-core/src/main/java/blue/language/resolve/MinimizedOverlayBuilder.java`
- `blue-language-core/src/main/java/blue/language/resolve/MinimizedOverlayReconstructor.java`
- `blue-language-core/src/main/java/blue/language/runtime/BlueLanguageRuntime.java`

The deferred `NestedValueReferenceIdentityTest` and
`SourceAwareMinimizedOverlayTest` owners are not resurrected. The existing
[C03 deferral](baseline-c03-deferral.md), including its MyOS acceptance exception,
remains in effect. Passing POC storage tests does not close that exception.

## Test reconciliation and PR37 continuity

The donor additions to existing tests are storage variants of the maintained
rejection, lifecycle, checkpoint/representation and witness scenarios. Existing
resident assertions, expected gas, history positions, event order and independent
cyclic-role oracles are retained. The Working Source-contribution storage tests
exercise the upstream occurrence API; they do not restore the C03 minimizer.
All new storage/reuse owners remain controls for the copied POC mechanisms.

PR37's original processing-event identity must survive durable restoration, not
just resident execution. Two added `ProcessingEventClosureTest` cases close the
producer runtime, restore bytes in a fresh consumer and compare complete results:

1. An inline-typed original event adds a child, suspends for birth evidence, then
   survives stored invocation/demand restoration and the child's initialization
   and emitted-event reaction.
2. An inline-typed/list-bearing event delivered to an embedded child survives cold
   invocation restoration, emission to its parent and the parent's update reaction.

Every reaction retains the originally admitted identity and original event value;
internal payloads do not become the processing cause. No handler runs during
decoding. Complete result bytes, including gas/events, match resident execution.
These are intended assertions, not a PASS claim before execution.

## Qualification boundary

The earlier [e28 port](rooted-external-state-e28-port.md) and all donor documents,
test receipts and timings are historical provenance. They do **not** qualify this
new source tuple; their commands naming deferred C03 tests are not the gate for
this port. No prior MyOS acceptance or performance result transfers automatically.

Fresh grouped native verification will cover Core exact/resolver storage,
Contracts invocation/snapshot/result storage and reuse, upstream processing-event
controls, witness/lifecycle/representation controls, pinned minimization guards,
API compatibility, Javadoc and package assembly. Exact clean-source inputs and
unmodified original failures/results are retained outside the repository under
`processing-measurement13/baseline31/lang-*`. Full conformance regeneration is not
part of this storage-only port unless a maintained gate establishes a real need.
