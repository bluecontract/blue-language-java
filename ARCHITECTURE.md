# Blue Language Java architecture

This repository is a layered Java 8 distribution for two specifications:

- Blue Language 1.0 defines the graph, Source pipeline, BlueId, provider
  evidence, immutable snapshots, matching, and patching semantics.
- Blue Contracts and Processor 1.0 defines deterministic processing of one
  Root and one event, including runtime extension, gas, lifecycle,
  checkpoints, diagnostics, and Root-only output.

The dependency rule is simple: Contracts may use Language public APIs;
Language never imports Contracts. Conformance and examples sit above both.
Optional mapping and IPFS integrations do not leak into the minimal model or
core artifacts.

Start with the [architecture overview](docs/architecture/overview.md). The
following focused documents cover the implementation:

- [modules and dependencies](docs/architecture/modules-and-dependencies.md);
- [Language pipeline](docs/architecture/language-pipeline.md);
- [Contracts pipeline](docs/architecture/contracts-pipeline.md);
- [immutability and runtime state](docs/architecture/immutability-and-runtime-state.md);
- [provider and fragment model](docs/architecture/provider-and-fragment-model.md);
- [conformance and release](docs/architecture/conformance-and-release.md).

Normative decisions are recorded in [docs/adr](docs/adr). Architecture tests
enforce package cycles, module edges, split packages, build shape, visibility,
and source ownership. Generated inventories and the final quality report are
evidence; the specifications and bound fixture packages remain authoritative.
