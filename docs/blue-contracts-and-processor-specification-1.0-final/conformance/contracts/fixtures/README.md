# Blue Contracts 1.0 conformance fixtures

This directory is the machine-readable conformance package for Blue Contracts and Processor 1.0. Every prose vector has at least one executable fixture, and every named processor or semantic gas counter has an exact microfixture. The fixture manifest is bound to `../gas-manifest.yaml`; the prose table, gas manifest, and fixture weights must be identical.

Read `HARNESS.md` before implementing a runner. A runner MUST reject unknown fixture fields, operations, assertion operators, or projection names rather than silently skipping them. `deliverySnapshot` is revision-bound evidence derived by the feeder from the exact input Root; it is never a third semantic input to `PROCESS`.

Affected-closure fixtures additionally use the closed top-level `runtime`,
`sharedLimitSource`, `provider`, `locality`, `limit`, and `oracle` harness fields
defined by `closure-fixture-schema.yaml`. They are not production
`ClosureInvocationInput` or `ClosureProcessResult` fields. Historical catch-up
is represented as one `ManagedRevisionCause` fixture per invocation; there is
no Contracts-owned transition array or historical work kind.
