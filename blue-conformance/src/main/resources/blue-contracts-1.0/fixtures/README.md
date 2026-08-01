# Blue Contracts 1.0 conformance fixtures

This directory is the machine-readable conformance package for Blue Contracts and Processor 1.0. Every prose vector has at least one executable fixture, and every named processor or semantic gas counter has an exact microfixture. The fixture manifest is bound to `../gas-manifest.yaml`; the prose table, gas manifest, and fixture weights must be identical.

Read `HARNESS.md` before implementing a runner. A runner MUST reject unknown fixture fields, operations, assertion operators, or projection names rather than silently skipping them. `deliverySnapshot` is revision-bound evidence derived by the feeder from the exact input Root; it is never a third semantic input to `PROCESS`.
