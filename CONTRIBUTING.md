# Contributing

Thank you for improving Blue Language Java. Begin with the complete
[developer process](docs/developer-process.md); it contains prerequisites,
module ownership, coding/test conventions, fixture and registry procedures,
API baseline rules, benchmark commands, and the RC checklist.

The short version:

1. Put the change in the owning module and keep the dependency graph acyclic.
2. Preserve exact identity, provider outcomes, deterministic gas, immutable
   runtime state, and atomic Contracts behavior.
3. Add useful comments and named constants for stable protocol values.
4. Write deterministic `should...` tests with Given–When–Then sections.
5. Run focused tests, the owning module gates, examples/documentation, exact
   conformance, and the final release verification appropriate to the change.
6. Never update a fixture identity, API baseline, semantic baseline, or release
   binding merely to silence a failure.

Use the [repository ownership table](docs/developer-process.md#which-repository-owns-this)
before adding ecosystem-specific behavior. BEX and Coordination features do
not belong in this repository.
