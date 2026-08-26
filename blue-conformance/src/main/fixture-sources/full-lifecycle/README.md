# Full-lifecycle admission fixture sources

This directory contains the identity-free authored inputs for the portable
`FL-ADM-01` through `FL-ADM-10` closure fixtures. These files are generator
inputs, not executable fixture oracles.

The canonical fixture generator must:

1. load registry entries from
   `blue-contracts-closure-1.0/registry/manifest.yaml` and replace each
   `{$registryBlueId: KEY}` with `{blueId: REGISTRY_BLUE_ID}`;
2. calculate each named event value and replace `$eventBlueId` and
   `$eventValue` macros with an exact reference or exact inline value,
   respectively;
3. finalize acyclic and cyclic document graphs, replace `$documentBlueId` and
   `$documentInline` macros, and derive every managed occurrence binding;
4. construct the ordinary `ADMIT_CLOSURE` input with no direct delivery;
5. invoke the normative `admitClosureWithLifecycleQueue` API through the
   released conformance runtime controls in `runtime`;
6. verify every source-level `expect` assertion against the actual result;
7. write complete `blue-contracts-closure-fixture/1.0` YAML, including exact
   input, result, traces, gas, commit companion, and all derived identities;
8. regenerate the manifest, vector coverage, package identities, release
   manifest, and report constants as one deterministic transaction.

Unless a future source-schema version says otherwise, every source uses graph
generation `1`, epoch `0`, uninitialized and unterminated input documents,
component generation derived by the ordinary finalization kernel, an empty
direct-delivery snapshot, `TOP_LEVEL_ADMISSION`, the released environment and
portable-limit policy, and the released fixture provider/registry. The source
file `id` deterministically supplies admission labels; it is not inserted into
any authored document.

The source macros are authoring conveniences only. They cannot modify runtime
semantics, provide an expected result, bypass normal Handler selection, or
write processor state directly. Runtime behavior is limited to the existing
fixture control language: scripted Handler patches, events, deterministic
failure, and graceful termination.

`cases` request representational or repeated-execution companions. Every case
executes the same normative API independently. Cross-case parity is checked
after execution; it is never supplied to the processor.

Before execution, the generator validates the complete authored source graph.
Document wrapper keys must equal authored `/documentId` values; document,
event, occurrence, pointer, Handler, and case references must resolve;
`inputOrder` must be a complete permutation; and every `this#n` placeholder
requires that explicit order and must agree with the declared occurrence edge.
Runtime keys must select existing scripted Handlers, no Handler may appear in
both runtime buckets, processor-owned state may not be patched, failure is
exclusive with all successful Handler output, and expected work kinds are
unique.  Case suffixes are unique, `repeatOf` names an earlier case and reuses
its exact invocation input, representation expansion preserves the verified
exact document identity, and every declared parity projection is compared
after independent executions. No unresolved macro can enter an executable
fixture.

Sources without `cases` emit one executable fixture with the same `id`.
FL-ADM-06 emits `-reference`, `-reversed`, and `-inline` companions. FL-ADM-08
emits `-first` and `-retry` companions, with `repeatOf` requiring the second
execution to reuse the first case's exact invocation input. These are thirteen
executable cases in ten semantic scenario families.
