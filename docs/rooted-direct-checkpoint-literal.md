# Direct progress during historical application

RCP-RUN-004 starts A, processes entries 5 and 10 and saves its exact state after 5.
P attaches that saved state with its own direct entry 20. One actual retained
revision application imports A's entry 10 while P's direct checkpoint stays at 20.
P emits one Direct event and then one Embedded event; its direct handler runs once.
The final child counter is 2, and restart preserves the exact retained state.

The checker compares both complete checkpoint records, including domain and the
exact appended entry identity. It checks the attached BlueId against the captured
source receipt and verifies that receipt's counter is 1. The pending child is an
exact reference, not a request to substitute A's current counter 2. All immutable
source receipts, +1 revision cause fields, application identity, source order and
actual gas remain independently checked.

The original recipe bytes are preserved at
`blue-conformance/src/main/resources/blue-rooted-checkpoint-1.0/conformance/rooted-processing/provenance/recipe-inputs/rcp-run-004.json`.
The recipe qualification negative reads that original; it still rejects recipe-only
input even though the current RUN-004 fixture now has an executable literal plan.

Production result: PASS on application SHA-256
`168d8913dc90258294b8fdbc23bf01d76437336a6687ae6a697ff63e3165ed01`,
Language d49ab0ba / Coordination b1baaad / BEX 925d7f0 / catalog 4ba5f6f.
Evidence: `rooted-direct-checkpoint-bound-capture-168d8913dc90.json` in the MyOS
campaign. An earlier seventeen-case invocation preserved 16 passes and failed this
new case's receipt extraction; it is not reported as a seventeen-case pass.
Historical event-kind extraction now reads the actual exact emitted event. The
saved-receipt comparison removes only the separately verified capture alias field.

All 98 hardening, 64 Iteration 2 and 18 original harness tests pass. New negatives
reject checkpoint rewind, wrong entry/domain, incomplete/duplicate steps, current
snapshot substitution, unretained/wrong-epoch/wrong-value source captures and changed
capture aliases. These checker tests do not themselves count as runtime execution.
No production runtime, cause, tariff, signature or specification change is included.
