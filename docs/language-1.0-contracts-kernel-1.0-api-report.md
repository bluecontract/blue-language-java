# Blue Language 1.0 and Contracts Kernel 1.0 final JVM API report

This report records the intentional pre-1.0 Java API cleanup between the
committed implementation at `2cb64cf14c2696aedeef92743788e67b6a2e1fb7` and
the final Language 1.0 / Contracts Kernel 1.0 candidate working tree.

The inventory is based on compiled production class files, not on source names
alone. It covers every externally reachable public or protected class, field,
constructor, and method descriptor. The comparison contains 79 intentionally
incompatible changes and 30 additions. The tables below account for all 79
changes; when an entire type was removed, they also list every public member of
that type even though the class-file comparison reports the type as one change.

This is an API-shape report. It does not report test or conformance outcomes.

## Developer overview

The cleanup closes several preview-era ambiguities before establishing the
first final baseline:

- canonical identity construction and author-facing minimization are separate
  operations;
- a completed `PROCESS` result has exactly five semantic fields;
- channel occurrences come from revision-bound verified feeder evidence, not
  caller-authored delivery carriers;
- provider identity evidence and resolved-graph structural sharing use
  different cache APIs;
- runtime gas uses named, weighted child ledgers;
- submitted child-ledger gas is admitted immediately and survives rollback of
  later application effects;
- subscription validation receives one evidence-rich context;
- composite External Channel functions receive immutable same-scope member and
  filtered effective-type-family context whose dependencies rotate subscription
  intervals and checkpoint domains;
- event-evaluation functions can match inline or referenced candidates through
  a pass-local frozen matcher whose only non-core lookup is the captured
  verified processing-snapshot boundary;
- checkpoint newness receives the exact frozen current subject and exact prior
  subject, including inline subjects smaller than the processing event;
- exact pure-reference Root and Event inputs are admitted through the verified
  processing snapshot boundary without recursive whole-graph expansion;
- accepted-new source occurrences can select and coalesce one same-scope
  logical handler delivery while retaining their own atomic checkpoints;
- fatal runtime failure is atomic and noncommitting, while graceful
  termination remains a successful business transition;
- preview aliases and partial-evidence constructors are absent from the final
  surface; the one released provider compatibility descriptor remains but
  enforces verification and provides no trust bypass.

The final source also treats these decisions as release invariants. Production
code may not declare `@Deprecated`, and it may not reintroduce a bare
`reverse(...)` API or `MergeReverser`.

## Downstream compatibility and excluded Coordination prerequisites

The released
`blue.repo:blue-repo-java:3.0.0-rc.10`
`BlueRepository.configure()` bytecode still invokes
`NodeProviderWrapper.unverified(NodeProvider)`. The descriptor is retained for
binary linkage, but its implementation delegates to
`NodeProviderWrapper.wrap(...)`: every result-producing provider leaf is
verified, and the former host-trust bypass is not restored.

The generic kernel now separates accepted raw source occurrences from a
same-scope logical handler delivery. Runtime-neutral immutable functions can
select a handler channel and a logical coalescing key. Several fresh accepted
sources can execute one delivery while retaining atomic checkpoints under
their original raw source keys. This supplies the generic Coordination
prerequisite without reintroducing caller-authored `ChannelDelivery` state.
Application-specific parsing of `request.channel`, authorization, registry
policy, and source persistence remain outside this repository.

The generic named child-ledger surface is also not a claim that every
downstream runtime can already populate it. BEX 1.1 lacks the required named
live counter stream and needs a coordinated update before it can provide a
Contracts 1.0 runtime ledger.

Event-scoped `matchesPattern(...)` and
`materializeExactReference(...)` close inline/pure-reference acceptance and
finite event-key projection parity. The default `eventKeys(...)` function uses
the latter for referenced `subscriptionKey` and `subscriptionKeys` fragments.
Header-time materialization remains intentionally unavailable, and the exact
application registry rule that maps domain events to source keys remains a
downstream responsibility.
The strict matcher consumes verified exact canonical definitions and follows
their exact type lineage; it does not preprocess or merge definitions whose
constraints require the complete Language resolution pipeline.

## Canonical identity and minimization are different operations

The former word “reverse” covered two results with different correctness
requirements:

| Intent | Facade API | Low-level API | Required input |
| --- | --- | --- | --- |
| Build strict canonical Content BlueId input | `Blue.canonicalize(Node/Object)` | `CanonicalIdentityInputBuilder.build(Node resolvedNode, Node preprocessedSource)` | Completed resolved content and the exact preprocessed source that retains reference and authored-metadata provenance |
| Build an author-facing overlay that resolves to the same meaning | `Blue.minimize(Node/Object)` | `MinimizedOverlayBuilder.build(Node resolvedNode)` | Completed resolved content |

A minimized overlay can omit derivable content and therefore is not necessarily
valid Content BlueId input. Conversely, canonical identity reconstruction from
a resolved node alone cannot recover pure-reference and explicit-source
provenance. Code must choose the operation that matches its intent.

## Final `DocumentProcessingResult`

The completed Contracts 1.0 semantic projection is:

```text
status
document
events
totalGas
diagnostic?
```

The corresponding accessors are `status()`, `document()`, `events()`,
`totalGas()`, and `diagnostic()`. `events()` contains ordered Root emissions
only. `diagnostic()` is optional. `commits()` remains a Java convenience
derived from `status()`; it is not a sixth result field.

Snapshots, resolved views, Content BlueIds, traces, and platform commit
companions are not semantic result fields. Snapshot-native debug/conformance
calls expose an out-of-band snapshot through
`ProcessingDebugResult.resultingSnapshot()`.

The public construction surface is intentionally constrained:

- `of(Node, List<Node>, long)` creates a successful result;
- `capabilityFailure(...)`, `invalidProcessingDocument(...)`,
  `invalidProcessingEvent(...)`, and `runtimeFatal(...)` create the named
  failure forms;
- `nonCommitting(Node, long, ProcessorStatus, ProcessorDiagnostic)` creates a
  noncommitting result while enforcing an unchanged input document and an
  empty Root event sequence.

The former general factories and snapshot-bearing aliases could construct a
carrier whose shape exceeded the five-field contract, so they have no
one-for-one final replacement.

For a deterministic failure after admission, `document` and `events` still
roll back to the exact input Root and an empty sequence. `totalGas` does not:
gas admitted before the failure remains. In particular,
`ProcessorExecutionContext.submitRuntimeGasLedger(...)` merges a live-bounded
named child ledger immediately, before buffered patches, events, and
termination, so the ledger's ordered trace survives a later runtime-fatal
effect rollback.

## Complete public/protected removal and finalization ledger

The “JVM changes” column is the number of entries contributed to the compiled
79-change comparison. Whole-type removals count as one entry there even though
their public members are enumerated for source migration.

### Language facade, conformance, schema, and reconstruction

| JVM changes | Removed or finalized API | Final replacement or removal rationale |
| ---: | --- | --- |
| 1 | `Blue.registerContractProcessor(String blueId, Node canonicalTypeNode, ContractProcessor<? extends Contract> processor)` | Use `Blue.registerExternalContractType(String, Node, ContractProcessor<? extends Contract>)`. The distinct name makes canonical external type registration—and its verification/cache invalidation effects—explicit. |
| 2 | `Blue.reverse(Node)`; `Blue.reverse(Object)` | Use `Blue.canonicalize(...)` for canonical identity input or `Blue.minimize(...)` for an author-facing minimized overlay. There is deliberately no bare inverse operation. |
| 1 | Removed type `MergeReverser`, including public `MergeReverser()`, `reverse(Node)`, `reverseToMinimizedOverlay(Node)`, `reverseToCanonicalOverlay(Node)`, and `reverseToCanonicalOverlay(Node, Node)` | Use `MinimizedOverlayBuilder.build(resolved)` or `CanonicalIdentityInputBuilder.build(resolved, preprocessedSource)`. The resolved-only canonical overload has no replacement because it lacks required source provenance. Facade callers should prefer `Blue.minimize(...)` or `Blue.canonicalize(...)`. |
| 2 | `BlueConformanceReport.CANDIDATE_FIXTURE_PACKAGE_IDENTITY`; `BlueConformanceReport.CANDIDATE_BLUE_SPEC_SOURCE` | Use `BlueConformanceReport.FIXTURE_PACKAGE_IDENTITY` and `BlueConformanceReport.BLUE_SPEC_SOURCE`. The final package is no longer described as a candidate. |
| 1 | `BlueContractsConformanceReport.BLUE_CONTRACTS_1_0_FIXTURE_PACKAGE_IDENTITY` | Use `BlueContractsConformanceReport.CONTRACTS_FIXTURE_PACKAGE_IDENTITY`. |
| 1 | `BlueContractsConformanceReport(String specVersion, String fixturePackageIdentity, List<String> fixtureIds, List<String> passedFixtureIds, List<String> failedFixtureIds, Map<String, BlueContractsFixtureCategory> fixtureCategories, List<BlueContractsConformanceFailure> failures)` | Use the full constructor and supply `releaseName`, `releasePackageIdentity`, `languageRegistryPackageIdentity`, `languageFixturePackageIdentity`, `contractsRegistryPackageIdentity`, `contractsGasPackageIdentity`, and `fixtureResults` as well. A synthetic report with only a fixture identity is not sufficiently bound to the final release. |
| 1 | `Merger` changed from extensible to `final` | Extend merge behavior through `MergingProcessor`, the supported strategy boundary. Subclassing the stateful resolution engine is not a final extension point. |
| 6 | `Schema.getMinLengthValue()`, `getMaxLengthValue()`, `getMinItemsValue()`, `getMaxItemsValue()`, `getMinFieldsValue()`, `getMaxFieldsValue()` | Use `getMinLengthExact()`, `getMaxLengthExact()`, `getMinItemsExact()`, `getMaxItemsExact()`, `getMinFieldsExact()`, and `getMaxFieldsExact()`. They return `BigInteger` and preserve the interoperable JSON integer range instead of narrowing to `Integer`. |

Language subtotal: **15 JVM changes**.

### Channels, processing results, runtime, and diagnostics

| JVM changes | Removed or finalized API | Final replacement or removal rationale |
| ---: | --- | --- |
| 1 | Removed compatibility type `ChannelDelivery`, including `of(Node)`, `of(Node, String, String, Boolean)`, `of(Node, String, String, Boolean, String, String)`, `event()`, `eventId()`, `checkpointKey()`, `shouldProcess()`, `handlerChannelKey()`, and `logicalDeliveryKey()` | There is no caller-submittable delivery carrier in the two-input `PROCESS(document, event)` model. The feeder derives source occurrences in `ExternalDeliveryPlan`; accepted immutable functions select `handlerChannelKey(...)` and `logicalDeliveryKey(...)` inside the kernel while raw sources retain checkpoint ownership. |
| 2 | `ChannelEvaluation.matchDeliveries(List<ChannelDelivery>)`; `ChannelEvaluation.deliveries()` | Return `ChannelEvaluation.match(Node)`, `match(Node, String)`, or `noMatch()`. Read the single payload through `event()` and optional identifier through `eventId()`. |
| 1 | `DirectSubscriptionSurfaceValidator.validate(Node inputRoot, Node tentativeRoot, Set<String> changedPaths, GasSchedule schedule)` | Call `validate(SubscriptionSurfaceValidationContext)`. Build a context with `SubscriptionSurfaceValidationContext.builder(...)` when invoking the validator directly. |
| 1 | `SubscriptionSurfaceValidator.validate(Node inputRoot, Node tentativeRoot, Set<String> changedPaths, GasSchedule schedule)` | Implement the sole final functional method `validate(SubscriptionSurfaceValidationContext)`. The context can also carry exact input/tentative snapshots, active subscription intervals, event order, and committing revision evidence. |
| 1 | `SubscriptionSurfaceValidator.validate(SubscriptionSurfaceValidationContext)` changed from a default bridge to the abstract functional method | Update lambdas and custom implementations to accept the context directly. Removing the bridge prevents validation from silently discarding evidence that Contracts 1.0 needs. |
| 3 | `DocumentProcessingResult.of(ResolvedSnapshot, List<Node>, long)`; `of(ResolvedSnapshot, List<Node>, long, ProcessorStatus, ProcessorErrorCategory, String)`; `withSnapshot(ResolvedSnapshot)` | Construct the semantic result from its `Node` document where host construction is necessary. Keep a runtime-produced snapshot out of band through `ProcessingDebugResult.resultingSnapshot()`; it is not a `ProcessResult` field. |
| 1 | `DocumentProcessingResult.of(Node, List<Node>, long, ProcessorStatus, ProcessorErrorCategory, String)` | Use `of(Node, List<Node>, long)` for success or a named failure/noncommitting factory with `ProcessorDiagnostic`. The unrestricted factory bypassed the closed result invariants. |
| 4 | `DocumentProcessingResult.snapshot()`; `blueId()`; `canonicalDocument()`; `resolvedDocument()` | Use `document()` for the semantic output. For debug snapshot state use `ProcessingDebugResult.resultingSnapshot()` and then `blueId()`, `canonicalRoot()`, or `resolvedRoot()`. If only the semantic output identity is needed, calculate it explicitly from `document()` through `Blue`. |
| 3 | `DocumentProcessingResult.capabilityFailure()`; `failureReason()`; `errorCategory()` | Inspect `status()` directly. Read failure detail from nullable `diagnostic()`, then `ProcessorDiagnostic.message()` or `category()`. To reproduce the old boolean exactly, test both `CAPABILITY_FAILURE` and `INVALID_PROCESSING_DOCUMENT`; final code should normally distinguish them. |
| 1 | `DocumentProcessingResult.triggeredEvents()` | Use `events()`. The final name also reinforces that the list contains Root emissions, not a public transitive event log. |
| 1 | `DocumentProcessingRuntime.addGas(long)` | Create a named ledger with `newRuntimeGasLedger(String, Map<String, Long>)`, charge declared counters on the `GasMeter.ChildGasLedger`, and submit/merge it once. Submission admits the ledger immediately, so its gas and trace survive rollback of later application effects. Anonymous gas units are not part of the Contracts 1.0 accounting vocabulary. |
| 1 | `DocumentProcessingRuntime.calculatePreInitializationScopeContentBlueId(String)` | Use `calculatePreInitializationScopeNodeBlueId(String)`. The value is the direct BlueId of the exact pre-initialization scope node, not Content BlueId after preprocessing or resolution. |
| 1 | `DocumentProcessingRuntime.chargeFatalTerminationOverhead()` | No replacement. Contracts 1.0 has no committed fatal mode and no fixed fatal closeout charge. |
| 2 | `ProcessorExecutionContext.consumeGas(long)`; `terminateFatally(String)` | For gas, use `newRuntimeGasLedger(...)` and `submitRuntimeGasLedger(...)`; submission is immediate and permitted once per handler result. For deterministic atomic runtime failure, use `throwFatal(String)`. Use `terminateGracefully(String)` or `terminate(String cause, String reason)` only for successful business termination. |
| 2 | `MockExternalChannelProcessor(ScriptedContractsRuntime)`; `MockExternalChannelProcessor(ScriptedContractsRuntime, Node)` | Use `MockExternalChannelProcessor()` or `MockExternalChannelProcessor(Node checkpointSubjectOverride)`. The conformance channel behavior is declared by the immutable selected channel; `ScriptedContractsRuntime` is not a constructor dependency. |
| 5 | `ChannelEventCheckpoint.getLastEvents()`; `lastEvents(Map<String, Node>)`; `lastEvent(String)`; `putEvent(String, Node)`; `updateEvent(String, Node)` | Use `getEntries()`, `entries(Map<String, CheckpointEntry>)`, `entry(String rawChannelKey)`, `putEntry(String rawChannelKey, String domainBlueId, String subjectBlueId)`, and `removeEntry(String)`. Every checkpoint is bound to both domain and subject identity. Runtime `checkpointSubject(...)` values may remain exact inline nodes; `ChannelCheckpointContext.currentSubject()` and `lastEvent()` expose the exact pair for newness comparison. |
| 2 | `EmbeddedNodeChannel.getChildPath()`; `setChildPath(String)` | Use `getSourcePath()` and `setSourcePath(String)`. “Source” states the direction of embedded delivery without assuming a child relationship. |
| 1 | `FrozenJsonPatch.getVal()` | Use `getValue()`. The final accessor names the immutable `FrozenNode` value rather than mirroring the mutable `JsonPatch` bean alias. |
| 1 | `RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR` | No replacement runtime type. Runtime failure returns a noncommitting `RUNTIME_FATAL` result with a diagnostic; it does not write or emit a fatal lifecycle contract. |
| 1 | `ProcessorPointerConstants.relativeCheckpointLastEvent(String markerKey, String channelKey)` | Use `relativeCheckpointEntry(String markerKey, String rawChannelKey)`. The target is a domain-bound checkpoint entry, not a last-event map. |

Processing subtotal before diagnostic aliases: **35 JVM changes**.

#### Removed `ProcessorErrorCategory` aliases

All 15 preview enum fields below were removed. The former `normative()` method
was also removed because every remaining enum value is already normative.

| Removed enum field | Final category or handling |
| --- | --- |
| `UnsupportedContract` | `UnsupportedRuntimeType` |
| `InvalidReservedMarker` | `InvalidReservedRuntimeState` |
| `ProviderUnavailable` | Normally `PROCESS_ATTEMPT` returns `NeedsResources` before a completed result exists. If the condition is an actual completed execution failure, use the exact final category; the former fallback normalization was `RuntimeExecutionFailure`. |
| `ProviderBlueIdMismatch` | `InvalidProcessingDocument` |
| `BoundaryViolation` | `PatchBoundaryViolation` |
| `ReservedKeyWrite` | `ProtectedProcessorStateMutation` |
| `InvalidPatchValue` | `InvalidPatch` |
| `HandlerExecutionError` | `RuntimeExecutionFailure` |
| `CheckpointError` | `CheckpointPolicyError`, or the more specific `CheckpointDomainError` when the domain binding is invalid |
| `TerminationError` | `RuntimeExecutionFailure` |
| `GasError` | `RuntimeLedgerLimitExceeded`; use `GasLimitExceeded` when the actual final condition is the invocation gas cap |
| `GeneralizationRejected` | `TypeGeneralizationFailure` |
| `GeneralizationNoValidType` | `TypeGeneralizationFailure` |
| `TypeSoundnessViolation` | `TypeCompatibilityViolation` |
| `InternalProcessorError` | `RuntimeExecutionFailure` |

Diagnostic alias subtotal: **16 JVM changes**: 15 fields plus
`ProcessorErrorCategory.normative()`.

Processing and diagnostics subtotal: **51 JVM changes**.

### Providers, frozen snapshots, and reference caching

| JVM changes | Removed or finalized API | Final replacement or removal rationale |
| ---: | --- | --- |
| 1 | `SourceProviderEnvironment(String languageVersion, String preprocessingEnvironmentId)` | Use the five-argument constructor and supply `languageReleaseIdentity`, `canonicalRegistryIdentity`, and `sourceEvidenceIdentity` in addition to version and preprocessing environment. A partially bound environment cannot verify Source-document evidence. |
| 1 | `FrozenNode.fromResolvedNode(Node, FrozenNode.ResolvedReferenceInterner)` | Prefer `ResolvedReferenceCache.freezeResolved(Node)`. For an independent structural interner, use `FrozenNode.fromResolvedNode(Node, FrozenNode.ResolvedStructuralInterner)`. BlueId-keyed graph interning is not evidence verification. |
| 1 | Removed compatibility interface `FrozenNode.ResolvedReferenceInterner`, including `lookup(String)` and `intern(String, FrozenNode)` | Use verified cache publication/retrieval for BlueId identity and `ResolvedStructuralInterner` for exact immutable graph sharing. No single interface should conflate those responsibilities. |
| 3 | `FrozenNode.ResolvedStructuralInterner` no longer extends `ResolvedReferenceInterner`; its inherited/default `lookup(String)` and `intern(String, FrozenNode)` methods were removed | Implement only `intern(FrozenNode.ResolvedStructuralKey, FrozenNode)`. The structural key includes exact representation details that a semantic Content BlueId deliberately omits. |
| 1 | `ResolvedReferenceCache` no longer implements `FrozenNode.ResolvedReferenceInterner` | Use the cache’s explicit verified-canonical, verified-resolved, transient-trusted, and structural-graph operations. There is no generic BlueId interner contract. |
| 6 | `ResolvedReferenceCache.get(String)`; `mutableCopy(String)`; `putIfAbsent(String, FrozenNode)`; `indexResolved(FrozenNode)`; `lookup(String)`; `intern(String, FrozenNode)` | Read through `getVerifiedCanonical(String)` or `getVerifiedResolved(String)`; convert a verified frozen value with `FrozenNode.toNode()` when a mutable copy is required. Publish verified content with `putVerifiedCanonical(...)`, `putVerifiedResolved(VerifiedReferenceResolution)`, or `putPinnedVerifiedResolved(...)`. Use `rememberResolvedGraph(FrozenNode)`/`freezeResolved(Node)` for structural reuse. The removed alias lane never established provider identity and therefore has no final equivalent. |
The released `NodeProviderWrapper.unverified(NodeProvider)` and
`isExplicitlyHostTrusted(NodeProvider)` descriptors remain binary-compatible.
The former delegates to `wrap(...)`; the latter always reports `false`. They
are not trust-bypass APIs.

Provider/snapshot subtotal: **13 JVM changes**.

### Ledger total

| Area | JVM changes |
| --- | ---: |
| Language facade, conformance, schema, reconstruction | 15 |
| Channels, processing results, runtime, diagnostics | 51 |
| Providers, frozen snapshots, reference caching | 13 |
| **Total** | **79** |

## Intentional additions

The same class-file comparison identifies 30 additions:

| JVM additions | Added API | Purpose |
| ---: | --- | --- |
| 1 | `CanonicalIdentityInputBuilder` | Names canonical identity reconstruction and requires `(resolvedNode, preprocessedSource)`. |
| 1 | `MinimizedOverlayBuilder` | Names author-facing minimized-overlay construction and requires only `resolvedNode`. |
| 1 | `ReleaseConformanceCli.main(String[])` | Provides the strict release-report command entry point. |
| 1 | `DocumentProcessingRuntime.calculatePreInitializationScopeNodeBlueId(String)` | Replaces the misleading `...ContentBlueId` name with the exact direct-node operation. |
| 1 | `ProcessingDebugResult.resultingSnapshot()` | Carries snapshot-native debug state outside the five-field semantic result. |
| 1 | `MockExternalChannelProcessor(Node checkpointSubjectOverride)` | Retains the fixture control without a `ScriptedContractsRuntime` constructor dependency. |
| 2 | `ExactNodeGraphFragments` and `ExactNodeGraphFragments.RootRepresentation` | Construct immutable identity-preserving shallow fragments, pure-reference Root forms, exact fragment inventories, and a verified in-memory provider without defining a second graph representation. |
| 7 | `ExternalChannelFunctionContext`, `ExternalChannelMemberSnapshot`, `ExternalChannelMemberEvaluation`, `ExternalChannelDependencySnapshot`, and nested `ExternalChannelDependencySnapshot.Entry`, `.TypeFamily`, and `.Member` | Expose immutable same-scope channel headers/evaluations, event-scoped `matchesPattern(...)` and `materializeExactReference(...)`, and exact member, type-family, and whole-surface dependency identities. |
| 7 | Context-aware `ExternalChannelSubscriptionFunctions.channelKeys(...)`, `eventKeys(...)`, `preselects(...)`, `accepts(...)`, `payload(...)`, `checkpointSubject(...)`, and `checkpointDomainDiscriminator(...)` overloads | Let runtime-neutral composite types derive acceptance, payload, subject, and domain from explicitly captured immutable dependencies. |
| 2 | `ExternalChannelSubscriptionFunctions.handlerChannelKey(...)` and `logicalDeliveryKey(...)` | Select one same-scope handler channel and a deterministic coalescing identity while preserving raw-source eligibility and checkpoint ownership. |
| 1 | `CheckpointDomain.derive(String, List<String>, ExternalChannelDependencySnapshot, String)` | Commits ordered dependency identities into the checkpoint domain. |
| 2 | Dependency-aware `SubscriptionDelta.Entry(...)` constructor and `dependencies()` | Retain dependency evidence across activation/retirement and force a delta when member semantics change at the same key. |
| 2 | Subject-aware `ChannelCheckpointContext.of(...)` overload and `currentSubject()` | Supply the exact current checkpoint subject alongside the exact prior subject to `isNewerEvent(...)`. |
| 1 | `FrozenTypeMatcher.withVerifiedReferenceMaterializer(Function)` | Opens an independent matcher whose non-core reference lookup is supplied by an explicit verified exact-materialization boundary, with no ambient `Blue` fallback. |

The package-private `OverlayReconstruction` implementation is not a JVM API
addition.

## Non-public deprecated shims removed by the source gate

The class-file ledger intentionally excludes package-private and private
members. The zero-`@Deprecated` source invariant also removes these internal
compatibility remnants:

- package-private `CheckpointManager.findCheckpoint(ContractBundle, String)`;
- package-private `GasMeter.add(long)`;
- package-private `GasMeter.chargeFatalTerminationOverhead()`;
- private serialized compatibility field `EmbeddedNodeChannel.childPath`.

Their final behavior is already represented by the public migrations above:
domain-bound checkpoint lookup, named child-ledger charging, no fatal closeout,
and `sourcePath`.

## Release gates and checked-in JVM baseline

### Source-shape gates

`verifyNoDeprecatedProductionApi` scans every
`src/main/java/**/*.java` line and rejects any occurrence of `@Deprecated`.
This prevents preview aliases from accumulating after the cleanup.

`verifyNoAmbiguousReverseApi` scans the same production source set and rejects
non-comment occurrences of either a bare `reverse(` call/declaration or the
name `MergeReverser`. This preserves the canonicalization/minimization split.

### Deterministic JSON baseline

The final post-cleanup JVM surface is stored at:

```text
api/blue-language-java-1.0.json
```

`tools/write_api_baseline.py` generates that file from the final release JAR:

```bash
python3 tools/write_api_baseline.py \
  build/libs/<final-release>.jar \
  api/blue-language-java-1.0.json
```

The JSON uses schema `blue-language-java-api-baseline/1.0` and deterministically
sorts every externally reachable public/protected class. For each class it
stores class-file version, access flags, superclass, interfaces, and every
public/protected field and method name, JVM descriptor, and access flags.
Synthetic members and classes hidden behind a non-public enclosing type are
excluded.

The baseline is generated only after the intentional preview cleanup. It is the
forward compatibility floor; the pre-1.0 comparison commit is audit evidence,
not the compatibility baseline.

### Candidate comparison and report

`verifyFinalApiBaseline` depends on `jar` and invokes:

```text
python3 tools/check_binary_api.py \
  api/blue-language-java-1.0.json \
  <candidate-jar> \
  build/reports/binary-api/final-1.0-baseline-to-candidate.txt
```

The checker accepts either a JSON snapshot or a JAR as its baseline. It
compares externally reachable public/protected classes and descriptors,
including:

- removed classes, fields, constructors, and methods;
- reduced visibility and static-modifier changes;
- newly final classes or members;
- newly abstract classes or methods;
- class/interface-kind, superclass, and implemented-interface changes.

Additions are listed separately in the text report. The same check rejects
candidate classes with a major version above 52, preserving Java 8 bytecode.

The Gradle `check` lifecycle depends on `verifyNoDeprecatedProductionApi`,
`verifyNoAmbiguousReverseApi`, and `verifyFinalApiBaseline`, so source-shape and
binary-surface drift are evaluated together.
