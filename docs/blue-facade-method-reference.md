# `Blue` facade: developer overview and complete public-method reference

## Scope and methodology

This document describes the public, class-level surface of
`src/main/java/blue/language/Blue.java` as it exists in this working tree. The
inventory contains **112 declarations**: seven construction paths and 105
methods. Constructors, overloads, the static factory, deprecated methods, and
`AutoCloseable.close()` are counted separately. Private helpers and public
methods on anonymous or nested implementation classes are outside the scope.

The usage notes report direct calls from the currently compiled
`src/test/java` bytecode. Bytecode descriptors were used so overloaded methods
are attributed to the exact signature; lambda bodies are included, and nested
test classes are reported under their outer class. Names are relative to
`src/test/java/blue/language` unless a package prefix is shown. Test-support and
sample classes are identified as such. “No direct test caller found” means
exactly that: a wrapper, suite, or lower-level component may still exercise the
behavior indirectly, and important indirect paths are called out.

## Developer overview

### Blue Language and Blue Contracts are different layers

Blue Language is the deterministic document layer. It defines nodes, types,
references, schema constraints, list controls, canonical content, and BlueId
identity. Its four conceptual transformations are:

```text
expand  <-> collapse
resolve <-> minimize
```

- **Expand/collapse** exchange pure `{blueId: ...}` references and referenced
  content. Expansion materializes references; collapse produces a content
  reference.
- **Resolve/minimize** exchange authored overlays and completed meaning.
  Resolution applies type inheritance, reference resolution, merge processors,
  limits, and validation. Minimization removes state derivable from types while
  preserving an overlay that resolves back to the same meaning.

Canonicalization is related to minimization but is not its synonym.
`canonicalize` retains source provenance needed for strict Content BlueId
identity; `minimize` produces a compact author-facing overlay. Likewise,
`calculateBlueId` hashes already valid structural content, while
`calculateSemanticBlueId` first canonicalizes meaning so redundant authored
forms can converge.

Blue Contracts and Processor 1.0 is a runtime layered on those language
semantics. It selects an immutable resolved Processing Document, recognizes
contracts, routes events through channels and handlers, applies tentative
patches, accounts for gas and portable limits, manages checkpoints and
lifecycle, and commits only a valid result. The facade exposes both layers, but
language operations such as `resolve` do not themselves run contracts, and
`processDocument` is not another spelling of language resolution.

### The transformation and runtime pipeline

```text
authored YAML/JSON
    -> raw parse (`parseSource*`)
    -> preprocessing (`blue` directive, aliases, Default Blue)
    -> resolution (provider references, type merge, schema/list semantics)
    -> canonical overlay + resolved runtime view
    -> immutable `ResolvedSnapshot`
    -> BlueId / canonical patching / contract processing
```

`yamlToNode` and `jsonToNode` combine raw parsing with preprocessing.
`parseSourceYaml` and `parseSourceJson` deliberately stop before preprocessing.
The basic `resolve(Node...)` overloads also do **not** preprocess: callers that
construct raw nodes must invoke `preprocess`, whereas `canonicalize`,
`minimize`, and `resolveToSnapshot` perform preprocessing internally.

A `ResolvedSnapshot` keeps two immutable `FrozenNode` graphs together:

- the **canonical root**, which is the minimized identity/storage source; and
- the **resolved root**, which is the completed runtime read/conformance view.

The snapshot BlueId belongs to the canonical root. Snapshot APIs are therefore
the preferred boundary for repeated processing and patching, while mutable
`Node` remains convenient for parsing and authoring.

### Method groups

| Declarations | Group | What the group owns |
| ---: | --- | --- |
| 1–7 | Runtime construction | Provider, merger, Java type mapping, bounded cache policy, and owned default processor setup |
| 8–32 | Language transformations | Resolve, preserve/select, canonicalize/minimize, expand/collapse, limited operations, and snapshot loading |
| 33–44 | Canonical patches and caches | Immutable patch entry points, authoritative snapshot pinning, bounded derived caches, statistics, and invalidation |
| 45–51 | Conformance | Language/Contracts version metadata, fixture reports, isolated engines, and suite execution |
| 52–59 | Extension, conversion, matching, limits | In-place reference extension, Java conversion, type matching, and global resolution limits |
| 60–85 | Parsing, export, dictionaries, identity | YAML/JSON boundaries, dictionary-aware export, cloning, and structural/semantic BlueIds |
| 86–102 | Preprocessing and Contracts runtime | Aliases, processor/type registration, document initialize/process operations, and object/type bridges |
| 103–112 | Configuration and lifecycle | Runtime dependencies, fluent reconfiguration, defensive configuration views, and close semantics |

### Important operational distinctions

- A `NodeProvider` supplies content-addressed canonical evidence. `Blue` wraps
  it and verifies/materializes references; replacing it invalidates reloadable
  state.
- Explicitly cached snapshots are authoritative and pinned. Derived snapshots,
  aliases, reference materializations, structural interns, and processor plans
  are acceleration data bounded by `BlueCachePolicy`.
- Path-preserving APIs defer or exclude selected resolution work; they are not
  equivalent to deleting fields before resolution.
- `parseBlueIdInput*` validates strict identity input. Ordinary source parsers
  accept source-language constructs intended to be preprocessed.
- Injected `DocumentProcessor` instances are borrowed. Processors created by
  `Blue` are owned and closed by it.
- Closing a runtime releases owned caches and processors and rejects later
  runtime work. Pure serialization helpers that do not enter runtime admission
  remain usable, as documented by `close()`.

## Runtime construction

### 1. `public Blue()`

**Purpose and library role.** Creates a self-contained runtime with an empty
provider, the default merge pipeline, no Java `TypeClassResolver`, bounded
default caches, and an owned default `DocumentProcessor`. It is the simplest
entry point for parsing, identity work, local documents, and processor setup
that does not initially need external references.

**Direct test/test-support callers.** `BlueCacheLifecycleTest`,
`BlueConformanceReportTest`, `BlueIdReferenceValidatorDepthTest`,
`DictionaryExportTest`, `DictionaryProcessorTest`, `LimitedCanonicalPatchTest`,
`ListControlFormsTest`, `ListProcessorTest`, `MergeReverserInlineTypeTest`,
`MergeReverserNestedTypedNodeTest`, `NodeDeserializerTest`,
`NodeToMapListOrValueTest`, `PreprocessorTest`,
`ProcessingSnapshotProviderProvenanceTest`, `RecursiveTypeResolutionTest`,
`ReferenceBlueIdResolutionValidationTest`, `RootReferenceSnapshotTest`,
`RootSchemaPayloadKindTest`, `SelfReferenceTest`,
`SemanticCanonicalizationTest`, `SerializationTest`,
`TrustedProviderResolutionTest`, `conformance.BlueLanguageConformanceFixtureTest`,
`mapping.NodeToObjectConverterNullHandlingTest`,
`mapping.NodeToObjectConverterTest`,
`processor.ProcessingSnapshotProviderPatchTest`,
`processor.ProcessorPhasePrecedenceTest`,
`processor.ResolvedSnapshotPatchTransactionTest`,
`processor.conformance.BlueContractsConformanceReportTest`,
`processor.external.ExternalContractIntegrationTest`,
`processor.registry.BlueRuntimeTypeRegistryTest`,
`provider.BootstrapProviderVerificationTest`,
`provider.ProviderEvidenceVerifierTest`, `samples.ipfs.Sample1Print` (sample),
`snapshot.FrozenNodeStructuralInternerTest`, `snapshot.FrozenNodeTest`,
`snapshot.ResolvedReferenceCacheContractTest`, `snapshot.ResolvedSnapshotTest`,
and `utils.BlueIdCalculatorTest`.

### 2. `public Blue(NodeProvider nodeProvider)`

**Purpose and library role.** Creates the standard runtime around a caller
provider, with the default merger, cache policy, and processor. This is the
normal language-runtime entry point when `{blueId: ...}` references or external
types must be resolved.

**Direct test/test-support callers.** `BlueCacheLifecycleTest`,
`BlueIdReferenceValidatorDepthTest`, `BlueLimitedOperationTest`,
`CyclicProviderFallbackTest`, `DeferredSnapshotCacheIsolationTest`,
`ListControlFormsTest`, `MaskedResolutionTest`,
`MaterializedSelectedProcessingDocumentFailFirstTest`,
`MergeReverserInlineTypeTest`, `MergeReverserNestedTypedNodeTest`,
`MergeReverserPureReferenceProvenanceTest`, `MergeReverserTest`,
`ProcessingSnapshotProviderProvenanceTest`, `RecursiveTypeResolutionTest`,
`ReferenceBlueIdResolutionValidationTest`, `ResolvedInstanceSchemaValidationTest`,
`ResolvedSchemaValidationLifecycleTest`, `RootReferenceSnapshotTest`,
`RootSchemaPayloadKindTest`,
`SelectedProcessingStateCacheIsolationFailFirstTest`, `SelfReferenceTest`,
`SemanticCanonicalizationTest`, `SyntheticWorkflowProcessingFixture`
(test support), `TrustedProviderResolutionTest`, `TypesTest`,
`VerifiedReferenceMaterializationTest`, `conformance.ConformanceEngineTest`,
`merge.MergerIntegrationTest`, `processor.DocumentProcessorGeneralizationTest`,
`processor.ExternalDeliveryPlanTrustBoundaryTest`,
`processor.HandlerMatchContextDeclaredTypeLineageTest`,
`processor.PatchImpactIncrementalResolutionTest`,
`processor.ProcessingSnapshotProviderPatchTest`, `processor.ProcessorTestSupport`
(test support), `processor.RegisteredContractProviderEvidenceTest`,
`processor.ResolvedSnapshotPatchTransactionTest`,
`processor.SelectedExecutableBodyProviderProvenanceTest`,
`processor.SelectedScopeContentBlueIdFailFirstTest`,
`provider.ProviderCanonicalIngestionTest`, `samples.ipfs.Sample2Resolve`
(sample), `snapshot.FrozenNodeStructuralInternerTest`,
`snapshot.ResolvedReferenceCacheContractTest`, `snapshot.ResolvedSnapshotTest`,
`utils.NodeTypeMatcherTest`, `utils.limits.PathLimitsTest`, and
`utils.limits.TypeSpecificPropertyFilterTest`.

### 3. `public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor)`

**Purpose and library role.** Adds a custom merge pipeline to a provider-backed
runtime. This is the extension point for changing how inherited/source state is
combined while retaining the facade’s provider, cache, snapshot, and lifecycle
coordination.

**Direct test callers.** `BlueCacheLifecycleTest`,
`ResolvedSchemaValidationLifecycleTest`, `RootSchemaPayloadKindTest`, and
`processor.PatchImpactIncrementalResolutionTest`.

### 4. `public Blue(NodeProvider nodeProvider, TypeClassResolver typeClassResolver)`

**Purpose and library role.** Adds Java-class resolution while retaining the
default merger. It supports typed Java object conversion without coupling the
language model itself to application classes.

**Direct test caller.** `mapping.JsonPropertyMappingTest`.

### 5. `public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor, TypeClassResolver typeClassResolver)`

**Purpose and library role.** Configures all three historical runtime
dependencies while using bounded default caches. It is the full compatibility
constructor for hosts that customize reference retrieval, merge semantics, and
Java class mapping.

**Direct test caller.** No direct test caller found in current compiled
`src/test` bytecode.

### 6. `public static Blue withCachePolicy(BlueCachePolicy cachePolicy)`

**Purpose and library role.** Creates an empty-provider default runtime with an
explicit immutable cache policy. It makes the library’s memory/throughput
tradeoff selectable even when no other dependency is customized.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 7. `public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor, TypeClassResolver typeClassResolver, BlueCachePolicy cachePolicy)`

**Purpose and library role.** Constructs the complete runtime: wrapped
provider, selected/default merger, optional Java resolver, bounded derived
caches, reference cache, and owned default processor. All simpler constructors
delegate here, making this the authoritative initialization contract.

**Direct test caller.** `BlueCacheLifecycleTest`.

## Language transformations

### 8. `public Node resolve(Node node)`

**Purpose and library role.** Resolves a node with no per-call limits, using the
current provider, merging processor, global limits, and shared reference cache.
It produces completed language meaning from an already preprocessed node; it
does not itself run preprocessing or Contracts processing.

**Direct test/test-support callers.** `BlueCacheLifecycleTest`,
`CyclicProviderFallbackTest`, `ListControlFormsTest`, `MaskedResolutionTest`,
`MergeReverserTest`, `NodeDeserializerTest`,
`ProcessingSnapshotProviderProvenanceTest`, `RecursiveTypeResolutionTest`,
`ReferenceBlueIdResolutionValidationTest`, `ResolvedInstanceSchemaValidationTest`,
`ResolvedSchemaValidationLifecycleTest`, `RootReferenceSnapshotTest`,
`RootSchemaPayloadKindTest`, `TrustedProviderResolutionTest`,
`conformance.ConformanceEngineTest`, `merge.MergerIntegrationTest`,
`processor.ProcessingSnapshotProviderPatchTest`,
`processor.ScopeSourceProjectionTest`,
`processor.registry.BlueRuntimeTypeRegistryTest`,
`provider.ProviderCanonicalIngestionTest`, `samples.ipfs.Sample1Print` (sample),
`samples.ipfs.Sample2Resolve` (sample),
`snapshot.ResolvedReferenceCacheContractTest`, and
`utils.NodeTypeMatcherTest`.

### 9. `public Node resolve(Node node, Limits limits)`

**Purpose and library role.** Resolves with explicit traversal/merge limits
combined with the runtime’s global limits. It lets callers bound or mask
language work without replacing the merger.

**Direct test callers.** `BlueIdReferenceValidatorDepthTest`,
`ReferenceBlueIdResolutionValidationTest`,
`ResolvedSchemaValidationLifecycleTest`, and `SelfReferenceTest`.

### 10. `public Node resolvePreservingPaths(Node node, Collection<String> preservedPaths)`

**Purpose and library role.** Resolves a clone while excluding the selected
canonical paths from resolution and restoring their exact authored subtrees.
It supports workflows that need completed surrounding meaning but must defer
specific payloads.

**Direct test caller.** `MaskedResolutionTest`.

### 11. `public Node resolvePreservingPaths(Node node, Limits limits, Collection<String> preservedPaths)`

**Purpose and library role.** Adds caller limits to path-preserving resolution;
the preserving exclusions are composed with those limits. Root preservation
returns a clone, and ordinary preserved paths are reinserted from the source.

**Direct test callers.** `BlueCacheLifecycleTest` and `MaskedResolutionTest`.

### 12. `public List<String> selectPaths(Node node, Collection<String> pathPatterns, Predicate<Node> predicate)`

**Purpose and library role.** Selects concrete node paths matching path
patterns and a node predicate. It is the discovery half of conditional
path-preserving resolution and exposes the same selector independently for
tooling.

**Direct test caller.** `MaskedResolutionTest`.

### 13. `public Node resolvePreservingMatchingPaths(Node node, Collection<String> pathPatterns, Predicate<Node> predicate)`

**Purpose and library role.** Finds matching paths and resolves while
preserving them, using no per-call limits. It packages a common selective
materialization pattern without weakening resolution elsewhere.

**Direct test callers.** `BlueCacheLifecycleTest` and `MaskedResolutionTest`.

### 14. `public Node resolvePreservingMatchingPaths(Node node, Limits limits, Collection<String> pathPatterns, Predicate<Node> predicate)`

**Purpose and library role.** The full selective-preservation overload:
selection is followed by path-preserving resolution under explicit limits. It
is the implementation endpoint for the shorter overload.

**Direct test caller.** No exact direct call found. It is reached through the
directly tested three-argument overload in `BlueCacheLifecycleTest` and
`MaskedResolutionTest`.

### 15. `public Node reverse(Node node)`

**Purpose and library role.** Deprecated compatibility entry point that applies
`MergeReverser.reverse` to a supplied node, yielding the legacy minimized
overlay behavior. It preserves older integrations, but new identity code
should call `canonicalize`, and author-facing compaction should call
`minimize`.

**Direct test caller.** `conformance.ConformanceEngineTest`.

### 16. `public Node reverse(Object object)`

**Purpose and library role.** Deprecated object-conversion wrapper around
`reverse(Node)`. It exists for source compatibility and should not be chosen
for new canonical identity work.

**Direct test caller.** No direct test caller found in current compiled
`src/test` bytecode.

### 17. `public Node canonicalize(Node node)`

**Purpose and library role.** Clones and preprocesses source, resolves a second
clone, then reconstructs a strict canonical overlay using both resolved meaning
and source provenance. This is the facade’s canonical Content BlueId input
operation.

**Direct test callers.** `RecursiveTypeResolutionTest`,
`ResolvedInstanceSchemaValidationTest`, `SemanticCanonicalizationTest`, and
`utils.BlueIdCalculatorTest`.

### 18. `public Node canonicalize(Object object)`

**Purpose and library role.** Converts a Java object to a `Node` and delegates
to node canonicalization. It connects application objects to semantic identity
without duplicating the language pipeline.

**Direct test caller.** No direct test caller found in current compiled
`src/test` bytecode.

### 19. `public Node minimize(Node node)`

**Purpose and library role.** Preprocesses and resolves input, then removes
state derivable from its completed type meaning to produce an author-facing
overlay that resolves back to the same result. Unlike `canonicalize`, it is
optimized for concise authored form rather than source-provenance identity.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 20. `public Node minimize(Object object)`

**Purpose and library role.** Java-object wrapper around `minimize(Node)`. It
allows application models to be rendered as compact Blue overlays.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 21. `public Node canonicalize(BlueOperationResult<Node> result)`

**Purpose and library role.** Canonicalizes only an `ESTABLISHED` limited
operation result and rejects absent, incomplete, or invalid outcomes. This
fail-closed boundary prevents partial provider evidence from becoming a
whole-document identity.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 22. `public Node expand(Node node)`

**Purpose and library role.** Recursively materializes pure references across
node metadata, payloads, contracts, and schema without applying type-merge
semantics. It implements the content-materialization side of
expand/collapse.

**Direct test callers.** `BlueCacheLifecycleTest`,
`SelectedProcessingStateCacheIsolationFailFirstTest`, and
`VerifiedReferenceMaterializationTest`.

### 23. `public BlueOperationResult<Node> expandLimited(Node node, BlueOperationLimits limits)`

**Purpose and library role.** Expands only the semantic closure of demanded
paths under a reference-expansion budget and distinguishes `ESTABLISHED`,
`ABSENT`, `INCOMPLETE`, and `INVALID`. It prevents missing or unavailable
provider evidence from being misreported as semantic absence.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 24. `public BlueOperationResult<Node> resolveLimited(Node node, BlueOperationLimits limits)`

**Purpose and library role.** Preprocesses and resolves demanded semantics
through a budgeted provider, returning explicit absence, incomplete evidence,
or invalid-content outcomes instead of collapsing all failures into a missing
node or exception. It is the fail-closed limited form of language resolution.

**Direct test caller.** `BlueLimitedOperationTest`.

### 25. `public Node expand(Object object)`

**Purpose and library role.** Converts a Java object and delegates to recursive
reference expansion. It provides the object-facing half of the expansion API.

**Direct test caller.** No direct test caller found in current compiled
`src/test` bytecode.

### 26. `public Node collapse(Node node)`

**Purpose and library role.** Calculates the node’s structural BlueId and
returns a pure reference node containing that ID. It implements the reference
creation side of expand/collapse; it does not persist the original content.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 27. `public Node collapse(Object object)`

**Purpose and library role.** Converts an object to Blue and collapses it to a
pure content reference. It bridges Java models into Blue’s content-addressed
reference form.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 28. `public ResolvedSnapshot resolveToSnapshot(Node node)`

**Purpose and library role.** Preprocesses source, resolves it through the
current merger/reference cache, freezes canonical and resolved views, and
publishes the result to the derived snapshot cache. It is the primary boundary
from mutable authored data into immutable identity-plus-runtime state.

**Direct test callers.** `BlueCacheLifecycleTest`,
`MaterializedSelectedProcessingDocumentFailFirstTest`,
`MergeReverserInlineTypeTest`, `MergeReverserNestedTypedNodeTest`,
`MergeReverserPureReferenceProvenanceTest`,
`ProcessingDocumentStateInvariantFailFirstTest`,
`ProcessingSnapshotProviderProvenanceTest`, `RecursiveTypeResolutionTest`,
`ResolvedInstanceSchemaValidationTest`,
`ResolvedProcessingSelectionCorrectnessTest`,
`ResolvedSnapshotSelectionCacheTest`, `RootReferenceSnapshotTest`,
`SelectedProcessingStateCacheIsolationFailFirstTest`,
`processor.DocumentProcessingRuntimeBatchPatchTest`,
`processor.DocumentProcessorGeneralizationTest`,
`processor.DocumentProcessorInitializationTest`,
`processor.DocumentProcessorSnapshotTransactionTest`,
`processor.EffectiveSubscriptionSurfaceValidatorTest`,
`processor.ExternalDeliveryPlanTrustBoundaryTest`,
`processor.HandlerMatchContextDeclaredTypeLineageTest`,
`processor.PatchImpactIncrementalResolutionTest`,
`processor.ProcessingSnapshotProviderPatchTest`,
`processor.ProcessorPhasePrecedenceTest`,
`processor.PublishedSnapshotRoundTripTest`,
`processor.ResolvedSnapshotPatchTransactionTest`,
`processor.ScopeSourceProjectionTest`,
`processor.SelectedScopeContentBlueIdFailFirstTest`,
`snapshot.FrozenNodeStructuralInternerTest`,
`snapshot.ResolvedReferenceCacheContractTest`, `snapshot.ResolvedSnapshotTest`,
and `utils.NodeTypeMatcherTest`.

### 29. `public ResolvedSnapshot resolveToSnapshotPreservingPaths(Node node, Collection<String> preservedPaths)`

**Purpose and library role.** Builds a verified snapshot whose canonical lane
still comes from complete source while resolution below selected paths is
deferred and exact authored subtrees are retained. It supports demand-driven
Contracts execution without falsely treating deferred evidence as resolved.

**Direct usage.** No direct compiled test call was found. Production caller
`processor.conformance.ContractsFixtureHarness` uses it, so it is exercised
indirectly by Contracts/release conformance execution.

### 30. `public ResolvedSnapshot resolveToSnapshot(Object object)`

**Purpose and library role.** Converts a Java object and delegates to snapshot
resolution, giving application models the same immutable canonical/resolved
boundary as nodes.

**Direct test caller.** `BlueCacheLifecycleTest` (through its
`BlockingObjectConversionBlue` test subclass).

### 31. `public ResolvedSnapshot loadSnapshot(Node canonical)`

**Purpose and library role.** Treats the input as strict canonical content,
reuses a compatible verified cached snapshot when possible, or verifies and
resolves a new one. It is the storage-ingestion path for canonical content,
not an authored-source parser.

**Direct test callers.** `BlueCacheLifecycleTest`,
`LimitedCanonicalPatchTest`, `MergeReverserNestedTypedNodeTest`,
`ProcessingSnapshotProviderProvenanceTest`,
`ResolvedInstanceSchemaValidationTest`, `processor.DocumentProcessorGasTest`,
`processor.PublishedSnapshotRoundTripTest`, and
`snapshot.ResolvedSnapshotTest`.

### 32. `public ResolvedSnapshot loadSnapshot(String blueId)`

**Purpose and library role.** Loads by content identity: checks snapshot caches,
fetches provider content when needed, removes a provider root identity wrapper,
and verifies/resolves the canonical result. It connects persistent
content-addressed storage to immutable runtime state.

**Direct test callers.** `BlueCacheLifecycleTest`, `BlueLimitedOperationTest`,
`RootReferenceSnapshotTest`, `snapshot.ResolvedReferenceCacheContractTest`, and
`snapshot.ResolvedSnapshotTest`.

## Canonical patches and caches

### 33. `public CanonicalOverlayPatchEngine canonicalPatchEngine(Node canonical)`

**Purpose and library role.** Freezes a strict canonical node and returns an
immutable overlay patch engine rooted at it. It exposes Blue-aware JSON Patch
semantics without resolving or mutating the original node.

**Direct test caller.** No direct Blue-facade call found in current compiled
`src/test` bytecode; `snapshot.CanonicalOverlayPatchEngineTest` tests the
underlying engine directly.

### 34. `public CanonicalPatchResult applyCanonicalPatch(Node canonical, JsonPatch patch)`

**Purpose and library role.** Creates a canonical patch engine and applies one
patch, returning the new frozen root plus before/after/path metadata. It is the
one-shot patch API when the caller needs canonical change data but not a
resolved snapshot.

**Direct test caller.** No direct Blue-facade call found in current compiled
`src/test` bytecode.

### 35. `public ResolvedSnapshot applyCanonicalPatch(ResolvedSnapshot snapshot, JsonPatch patch)`

**Purpose and library role.** Patches a snapshot’s canonical root, rebuilds its
verified resolved companion, and removes a newly written override when its
effective value is identical to inherited state. It keeps patched identity
minimal and resolved meaning synchronized.

**Direct test callers.** `LimitedCanonicalPatchTest`,
`MaterializedSelectedProcessingDocumentFailFirstTest`,
`MergeReverserNestedTypedNodeTest`,
`processor.DocumentProcessorGeneralizationTest`, and
`snapshot.ResolvedSnapshotTest`.

### 36. `public Blue cacheResolvedSnapshot(ResolvedSnapshot snapshot)`

**Purpose and library role.** Explicitly pins a verified authoritative snapshot
by canonical representation and BlueId. Pinned content is not evicted by the
bounded derived-cache policy and remains until clear or close.

**Direct test callers.** `BlueCacheLifecycleTest`,
`DeferredSnapshotCacheIsolationTest`, `processor.DocumentProcessorGasTest`,
`processor.DocumentProcessorGeneralizationTest`,
`processor.ProcessingSnapshotProviderPatchTest`,
`snapshot.ResolvedReferenceCacheContractTest`, and
`snapshot.ResolvedSnapshotTest`.

### 37. `public Blue cacheResolvedSnapshots(Collection<ResolvedSnapshot> snapshots)`

**Purpose and library role.** Pins a collection of authoritative snapshots and
returns the facade for fluent startup configuration. It supports registry or
bootstrap preload without changing individual pin semantics.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 38. `public Optional<ResolvedSnapshot> cachedResolvedSnapshot(String blueId)`

**Purpose and library role.** Looks up a pinned or live derived snapshot by
BlueId without consulting the provider. It exposes cache reuse while making a
miss explicit.

**Direct test callers.** `BlueCacheLifecycleTest`, `RootReferenceSnapshotTest`,
`snapshot.ResolvedReferenceCacheContractTest`, and
`snapshot.ResolvedSnapshotTest`.

### 39. `public int resolvedSnapshotCacheSize()`

**Purpose and library role.** Returns the combined entry count of pinned and
derived canonical-representation snapshot caches. It provides a lightweight
observability hook for snapshot retention.

**Direct test callers.** `RootReferenceSnapshotTest`,
`processor.ProcessingSnapshotProviderPatchTest`,
`snapshot.ResolvedReferenceCacheContractTest`, and
`snapshot.ResolvedSnapshotTest`.

### 40. `public int resolvedReferenceCacheSize()`

**Purpose and library role.** Reports the resolved-reference cache’s logical
entry count. It makes provider/materialization reuse visible for lifecycle and
isolation checks.

**Direct test callers.** `BlueCacheLifecycleTest`,
`ProcessingSnapshotProviderProvenanceTest`,
`ReferenceBlueIdResolutionValidationTest`, `RootReferenceSnapshotTest`,
`processor.ProcessingSnapshotProviderPatchTest`,
`snapshot.ResolvedReferenceCacheContractTest`, and
`snapshot.ResolvedSnapshotTest`.

### 41. `public int resolvedStructuralCacheSize()`

**Purpose and library role.** Reports the size of the resolved structural graph
interner. The metric reflects immutable subtree sharing, one of the library’s
main memory and hot-path optimizations.

**Direct test callers.** `processor.ProcessingSnapshotProviderPatchTest` and
`snapshot.FrozenNodeStructuralInternerTest`.

### 42. `public void clearResolvedSnapshotCache()`

**Purpose and library role.** Coordinates an invalidation barrier, clears an
owned processor’s caches, then clears all runtime caches, including pinned
snapshots and reference/interner state. It provides deterministic release and
reconfiguration without racing admitted facade operations.

**Direct test callers.** `BlueCacheLifecycleTest`,
`ResolvedInstanceSchemaValidationTest`,
`processor.ProcessingSnapshotProviderPatchTest`, and
`snapshot.ResolvedSnapshotTest`.

### 43. `public BlueCachePolicy cachePolicy()`

**Purpose and library role.** Returns the immutable policy selected at
construction. It lets hosts inspect the per-runtime acceleration bounds that
govern derived, but not explicitly pinned, state.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 44. `public BlueCacheStats cacheStats()`

**Purpose and library role.** Returns region-by-region approximate weights,
high-water marks, counts, eviction/rejection counters, pinned status, processor
plan weight, and runtime closed state. It is the detailed observability surface
for bounded cache ownership.

**Direct test callers.** `BlueCacheLifecycleTest` and
`DeferredSnapshotCacheIsolationTest`.

## Conformance

### 45. `public ConformanceEngine conformanceEngine()`

**Purpose and library role.** Creates a caller-owned conformance handle bound
to the current provider/merger generation, seeded with pinned verified
references but using otherwise isolated bounded caches. This prevents a
retained engine from contaminating a later runtime configuration.

**Direct test callers.** `BlueCacheLifecycleTest`,
`conformance.ConformanceEngineTest`,
`processor.DocumentProcessorGeneralizationTest`,
`processor.DocumentProcessorSnapshotTransactionTest`,
`processor.ExecutableBodyFieldMetadataTest`,
`processor.PatchImpactIncrementalResolutionTest`, and
`processor.ResolvedSnapshotPatchTransactionTest`.

### 46. `public String languageVersion()`

**Purpose and library role.** Returns the implemented Blue Language version,
currently `"1.0"`. Reports and provider-evidence checks use it to bind behavior
to the correct specification generation.

**Direct test callers.** `BlueConformanceReportTest`,
`TrustedProviderResolutionTest`, and `provider.ProviderEvidenceVerifierTest`.

### 47. `public BlueConformanceReport conformanceReport()`

**Purpose and library role.** Builds an unexecuted Language conformance report
containing version, core registry BlueIds, fixture package identity, closed
fixture inventory, and categories. It is the metadata/report seed, not the
suite runner.

**Direct test callers.** `BlueConformanceReportTest` and
`provider.BootstrapProviderVerificationTest`.

### 48. `public BlueConformanceReport runConformanceSuite()`

**Purpose and library role.** Executes the exact Blue Language fixture package
through the current facade and returns the populated machine-readable report.
It verifies the deterministic language layer independently of Contracts.

**Direct test callers.** `BlueConformanceReportTest` and
`conformance.BlueLanguageConformanceFixtureTest`.

### 49. `public BlueContractsConformanceReport contractsConformanceReport()`

**Purpose and library role.** Builds an unexecuted Contracts 1.0 report seed
with package identity, fixture inventory, and categories. It keeps the
Contracts target’s evidence separate from the Language report.

**Direct test caller.**
`processor.conformance.BlueContractsConformanceReportTest`.

### 50. `public BlueContractsConformanceReport runContractsConformanceSuite()`

**Purpose and library role.** Executes the exact Blue Contracts and Processor
fixture package and returns its populated report. This is the dedicated runtime
conformance entry point rather than a language-resolution method.

**Direct test caller.** No exact direct call found. It is reached through
`runReleaseConformanceSuites()`, which is directly tested by
`processor.conformance.BlueContractsConformanceReportTest`.

### 51. `public BlueReleaseConformanceReport runReleaseConformanceSuites()`

**Purpose and library role.** Runs the Language and Contracts suites and
combines their reports into one release-level artifact. It provides a single
machine-readable check while retaining the two layers’ distinct result sets.

**Direct test caller.**
`processor.conformance.BlueContractsConformanceReportTest`.

## Extension, conversion, matching, and limits

### 52. `public void extend(Node node, Limits limits)`

**Purpose and library role.** Mutates a node in place by recursively replacing
eligible references with provider content under combined global/per-call
limits, including list reconstruction where requested. It is a legacy
materialization utility, distinct from merge-based `resolve`.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 53. `public Node objectToNode(Object object)`

**Purpose and library role.** Serializes a Java object through Jackson, parses
that JSON as Blue, and preprocesses it. It is the common Java-to-language
bridge used by object overloads and contract test/application models.

**Direct test callers.** `BlueCacheLifecycleTest`,
`mapping.JsonPropertyMappingTest`, `processor.ChannelRunnerTest`,
`processor.ContractBundleCacheTest`,
`processor.DocumentProcessorCapabilityTest`,
`processor.DocumentProcessorGasTest`,
`processor.DocumentProcessorSnapshotTransactionTest`,
`processor.DocumentProcessorTerminationTest`, `processor.ProcessEmbeddedTest`,
and `processor.TestEventChannelTest`.

### 54. `public <T> T convertObject(Object object, Class<T> clazz)`

**Purpose and library role.** Converts an object to a preprocessed `Node` and
then maps that node to the requested Java class. It offers a Blue-normalizing
object-to-object conversion path using configured class resolution.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 55. `public boolean nodeMatchesType(Node node, Node type)`

**Purpose and library role.** Matches mutable nodes through `NodeTypeMatcher`
using the facade as resolver and the current global limits. It exposes Blue’s
type/shape conformance semantics at authoring boundaries.

**Direct test callers.** `BlueCacheLifecycleTest` and
`utils.NodeTypeMatcherTest`.

### 56. `public boolean nodeMatchesType(FrozenNode resolvedNode, FrozenNode resolvedType)`

**Purpose and library role.** Matches already resolved immutable nodes and
types, avoiding mutable conversion and repeated language resolution. It is the
hot-path form for snapshot-backed processing.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 57. `public boolean nodeMatchesType(ResolvedSnapshot snapshot, String pointer, FrozenNode resolvedType)`

**Purpose and library role.** Matches the resolved node selected by a pointer
inside a snapshot against an already resolved type. It aligns localized
contract matching with the authoritative snapshot view.

**Direct test callers.** `BlueCacheLifecycleTest` and
`utils.NodeTypeMatcherTest`.

### 58. `public void setGlobalLimits(Limits globalLimits)`

**Purpose and library role.** Replaces the runtime-wide limit policy (`null`
means `NO_LIMITS`) under coordinated invalidation, refreshing owned processor
state and clearing configuration-dependent caches. It applies one host policy
consistently to later resolution and processing.

**Direct test callers.** `BlueCacheLifecycleTest` and
`LimitedCanonicalPatchTest`.

### 59. `public Limits getGlobalLimits()`

**Purpose and library role.** Returns the current runtime-wide limit policy.
It is the compatibility getter paired with `setGlobalLimits`.

**Direct test caller.** No direct test caller found in current compiled
`src/test` bytecode.

## Parsing, export, dictionaries, and identity

### 60. `public Node yamlToNode(String yaml)`

**Purpose and library role.** Parses Blue YAML as source and immediately
preprocesses it, including `blue` directives, aliases, and Default Blue. It is
the normal authored-YAML ingestion API.

**Direct test callers.** `BlueCacheLifecycleTest`, `ListControlFormsTest`,
`MaskedResolutionTest`, `MaterializedSelectedProcessingDocumentFailFirstTest`,
`MergeReverserInlineTypeTest`, `MergeReverserNestedTypedNodeTest`,
`MergeReverserPureReferenceProvenanceTest`, `MergeReverserTest`,
`NodeToMapListOrValueTest`, `PreprocessorTest`,
`ReferenceBlueIdResolutionValidationTest`,
`SelectedProcessingStateCacheIsolationFailFirstTest`, `SelfReferenceTest`,
`SerializationTest`, `TypesTest`,
`mapping.NodeToObjectConverterNullHandlingTest`,
`mapping.NodeToObjectConverterTest`, `merge.MergerIntegrationTest`,
`processor.ChannelRunnerTest`, `processor.ContractBundleCacheTest`,
`processor.ContractMappingIntegrationTest`,
`processor.DocumentProcessorBatchPatchTest`,
`processor.DocumentProcessorCapabilityTest`,
`processor.DocumentProcessorEventImmutabilityTest`,
`processor.DocumentProcessorGasTest`,
`processor.DocumentProcessorHandlerFailureTest`,
`processor.DocumentProcessorInitializationTest`,
`processor.DocumentProcessorTerminationTest`,
`processor.DocumentUpdateChannelTest`, `processor.ProcessEmbeddedTest`,
`processor.ProcessorProcessEventContextTest`,
`processor.PublishedSnapshotRoundTripTest`,
`processor.ScopeSourceProjectionTest`, `processor.TerminationConformanceTest`,
`processor.TestEventChannelTest`,
`processor.external.ExternalContractIntegrationTest`,
`processor.registry.BlueRuntimeTypeRegistryTest`, `snapshot.FrozenNodeTest`,
`utils.BlueIdCalculatorTest`, `utils.NodeTypeMatcherTest`,
`utils.limits.PathLimitsTest`, and
`utils.limits.TypeSpecificPropertyFilterTest`.

### 61. `public Node jsonToNode(String json)`

**Purpose and library role.** Parses Blue JSON as source and immediately
preprocesses it. It gives JSON callers the same source-language normalization
as `yamlToNode`.

**Direct test callers.** `BlueCacheLifecycleTest`,
`MaterializedSelectedProcessingDocumentFailFirstTest`,
`MergeReverserInlineTypeTest`, `MergeReverserNestedTypedNodeTest`,
`MergeReverserPureReferenceProvenanceTest`,
`ProcessingDocumentStateInvariantFailFirstTest`,
`SelectedProcessingStateCacheIsolationFailFirstTest`,
`processor.DocumentProcessorInitializationTest`, and
`processor.PublishedSnapshotRoundTripTest`.

### 62. `public Node parseSourceYaml(String yaml)`

**Purpose and library role.** Performs raw YAML-to-`Node` parsing without
preprocessing. It is the correct boundary when a caller must inspect or control
source directives before applying the language’s Default Blue step.

**Direct test caller.** No exact direct call found. It is reached by the heavily
tested `yamlToNode()` wrapper and by Language conformance execution.

### 63. `public Node parseSourceJson(String json)`

**Purpose and library role.** Performs raw JSON-to-`Node` parsing without
preprocessing. It separates syntax ingestion from semantic source
normalization.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 64. `public Node parseBlueIdInputYaml(String yaml)`

**Purpose and library role.** Parses YAML intended as direct BlueId input,
validates pure-reference rules, and runs BlueId calculation to force full
canonical identity validation before returning the node. It prevents source
directives or malformed identity shapes from entering structural hashing.

**Direct test callers.** `ReferenceBlueIdResolutionValidationTest`,
`SelfReferenceTest`, and `utils.BlueIdCalculatorTest`.

### 65. `public Node parseBlueIdInputJson(String json)`

**Purpose and library role.** JSON counterpart to
`parseBlueIdInputYaml`: parse, validate reference form, and prove that the node
is valid structural BlueId input.

**Direct test caller.** `ReferenceBlueIdResolutionValidationTest`.

### 66. `public String nodeToYaml(Node node)`

**Purpose and library role.** Serializes a node to official Blue YAML through
the canonical map/list/value representation, including required type inference
for untyped scalar output. It is the ordinary YAML egress boundary.

**Direct test callers.** `MaterializedSelectedProcessingDocumentFailFirstTest`,
`MergeReverserInlineTypeTest`,
`SelectedProcessingStateCacheIsolationFailFirstTest`,
`processor.DocumentUpdateChannelTest`, and `processor.ProcessEmbeddedTest`.

### 67. `public String nodeToYaml(Node node, ExportContext exportContext)`

**Purpose and library role.** Dictionary-transforms the node for a target export
environment and then emits official Blue YAML. It supports versioned type-ID
translation or safe inlining across dictionary boundaries.

**Direct test caller.** `DictionaryExportTest`.

### 68. `public String nodeToSimpleYaml(Node node)`

**Purpose and library role.** Emits the simple representation, collapsing
scalar and list payload nodes to plain YAML values/lists where possible. It is
for consumer-friendly data output rather than lossless Blue metadata exchange.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 69. `public String nodeToJson(Node node)`

**Purpose and library role.** Serializes a node to official Blue JSON through
the canonical map/list/value representation. It is the normal JSON egress
boundary and preserves Blue language metadata.

**Direct test callers.** `BlueCacheLifecycleTest`,
`MaterializedSelectedProcessingDocumentFailFirstTest`,
`MergeReverserInlineTypeTest`, `MergeReverserNestedTypedNodeTest`,
`MergeReverserPureReferenceProvenanceTest`,
`ProcessingDocumentStateInvariantFailFirstTest`,
`ResolvedProcessingSelectionCorrectnessTest`,
`ResolvedSnapshotSelectionCacheTest`,
`SelectedProcessingStateCacheIsolationFailFirstTest`,
`VerifiedReferenceMaterializationTest`, `merge.MergerIntegrationTest`,
`processor.DocumentProcessorCapabilityTest`,
`processor.DocumentProcessorInitializationTest`,
`processor.DocumentProcessorSnapshotTransactionTest`,
`processor.PatchImpactIncrementalResolutionTest`,
`processor.PublishedSnapshotRoundTripTest`, and
`processor.ResolvedSnapshotPatchTransactionTest`.

### 70. `public String nodeToJson(Node node, ExportContext exportContext)`

**Purpose and library role.** Applies dictionary-aware export and emits official
Blue JSON. It is the JSON transport API for environments with negotiated type
dictionaries.

**Direct test caller.** `DictionaryExportTest`.

### 71. `public String nodeToSimpleJson(Node node)`

**Purpose and library role.** Emits the simple payload-oriented JSON
representation, collapsing scalar and list nodes where possible. It serves
plain-data consumers that do not require a lossless Blue document.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 72. `public String objectToYaml(Object object)`

**Purpose and library role.** Converts a Java object to preprocessed Blue and
emits official YAML. It is the object convenience wrapper for the normal Blue
serialization path.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 73. `public String objectToSimpleYaml(Object object)`

**Purpose and library role.** Converts a Java object to Blue and emits the
simple payload-oriented YAML representation. It is intended for data-style
output where Blue metadata can be collapsed.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 74. `public String objectToJson(Object object)`

**Purpose and library role.** Converts a Java object to preprocessed Blue and
emits official JSON. It gives application objects the same language-normalized
JSON boundary as nodes.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 75. `public String objectToJson(Object object, ExportContext exportContext)`

**Purpose and library role.** Converts an object, applies dictionary-aware type
export, and emits official JSON. It combines Java mapping with cross-dictionary
transport negotiation.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 76. `public String objectToSimpleJson(Object object)`

**Purpose and library role.** Converts an object to Blue and emits simple
payload-oriented JSON. It is the convenience path for ordinary JSON data
consumers.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 77. `public Node exportNode(Node node, ExportContext exportContext)`

**Purpose and library role.** Returns a transformed clone whose non-core type
references are mapped to requested dictionary versions or safely inlined when
unsupported. It isolates transport compatibility from canonical runtime state.

**Direct test caller.** `DictionaryExportTest`.

### 78. `public Blue registerTypeDictionary(TypeDictionary dictionary)`

**Purpose and library role.** Registers one named/versioned type dictionary
under lifecycle coordination and returns the facade. Registered ownership and
translations drive dictionary-aware export.

**Direct test caller.** `DictionaryExportTest`.

### 79. `public Blue registerTypeDictionaries(Collection<? extends TypeDictionary> dictionaries)`

**Purpose and library role.** Registers multiple type dictionaries as one
configuration action and returns the facade. It supports bootstrap of complete
transport vocabularies.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 80. `public DictionaryRegistry dictionaryRegistry()`

**Purpose and library role.** Returns the runtime’s dictionary registry handle.
It exposes advanced inspection/integration beyond the fluent registration
methods.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 81. `public <T> T clone(T object)`

**Purpose and library role.** Clones `Node` directly, returns `null` for null,
and otherwise round-trips an object through Blue mapping before conversion back
to its runtime class. It provides a language-aware deep-copy convenience.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 82. `public String calculateBlueId(Node node)`

**Purpose and library role.** Calculates the structural BlueId of already valid
canonical identity input. It is sensitive to authored structure and rejects
invalid reference/source forms rather than silently canonicalizing them.

**Direct test callers.** `BlueCacheLifecycleTest`,
`MergeReverserNestedTypedNodeTest`,
`ProcessingSnapshotProviderProvenanceTest`,
`ResolvedInstanceSchemaValidationTest`, `RootReferenceSnapshotTest`,
`SelectedProcessingStateCacheIsolationFailFirstTest`,
`SemanticCanonicalizationTest`, `TrustedProviderResolutionTest`,
`VerifiedReferenceMaterializationTest`,
`snapshot.FrozenNodeStructuralInternerTest`,
`snapshot.ResolvedReferenceCacheContractTest`, and
`utils.BlueIdCalculatorTest`.

### 83. `public String calculateBlueId(Object object)`

**Purpose and library role.** Converts an object to a Blue node and calculates
its structural identity. It extends content addressing to Java models while
retaining the structural—not semantic-equivalence—contract.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

### 84. `public String calculateSemanticBlueId(Node node)`

**Purpose and library role.** Canonicalizes the node’s completed meaning and
hashes that canonical overlay. It lets different authored forms share identity
when preprocessing, inheritance, and redundant overrides make them
semantically equivalent.

**Direct test callers.** `DictionaryProcessorTest`, `ListProcessorTest`,
`MaterializedSelectedProcessingDocumentFailFirstTest`, `MergeReverserTest`,
`ResolvedInstanceSchemaValidationTest`,
`ResolvedProcessingSelectionCorrectnessTest`, `SemanticCanonicalizationTest`,
`TrustedProviderResolutionTest`, `processor.CheckpointIdentityCalculatorTest`,
`processor.DocumentProcessorInitializationTest`,
`processor.ResolvedSnapshotPatchTransactionTest`,
`processor.ScopeSourceProjectionTest`,
`provider.ProviderEvidenceVerifierTest`, and
`utils.BlueIdCalculatorTest`.

### 85. `public String calculateSemanticBlueId(Object object)`

**Purpose and library role.** Converts a Java object and calculates identity
from its canonicalized Blue meaning. It is the object-facing semantic identity
API.

**Direct test caller.** No direct Blue-facade test caller found in current
compiled `src/test` bytecode.

## Preprocessing and Contracts runtime

### 86. `public void addPreprocessingAliases(Map<String, String> aliases)`

**Purpose and library role.** Adds aliases to a defensive copy of the current
preprocessing map, then invalidates configuration-dependent caches and refreshes
owned processor state. Aliases let friendly `blue` directive values resolve to
stable BlueIds without changing canonical language identity.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 87. `public Blue registerContractProcessor(ContractProcessor<? extends Contract> processor)`

**Purpose and library role.** Registers a typed contract processor with the
active `DocumentProcessor` under a mutation barrier and returns the facade. It
is the normal extension point for adding application channel, handler, marker,
or other contract behavior to the Contracts runtime.

**Direct test callers.** `processor.ChannelRunnerTest`,
`processor.ContractBundleCacheTest`,
`processor.DocumentProcessorBatchPatchTest`,
`processor.DocumentProcessorCapabilityTest`,
`processor.DocumentProcessorEventImmutabilityTest`,
`processor.DocumentProcessorGasTest`,
`processor.DocumentProcessorHandlerFailureTest`,
`processor.DocumentProcessorInitializationTest`,
`processor.DocumentProcessorSnapshotTransactionTest`,
`processor.DocumentProcessorTerminationTest`,
`processor.DocumentUpdateChannelTest`,
`processor.EffectiveSubscriptionSurfaceValidatorTest`,
`processor.InternalEventOccurrenceFifoTest`, `processor.ProcessEmbeddedTest`,
`processor.ProcessorProcessEventContextTest`,
`processor.TerminationConformanceTest`, and
`processor.TestEventChannelTest`.

### 88. `public Blue registerContractProcessor(String blueId, ContractProcessor<? extends Contract> processor)`

**Purpose and library role.** Binds a processor to an explicit contract-type
BlueId without supplying type content; the configured provider must already
return verified canonical content for that ID. This keeps executable dispatch
bound to Blue identity rather than synthesized Java class-name nodes.

**Direct test callers.** `BlueCacheLifecycleTest`,
`processor.ExecutableBodyFieldMetadataTest`,
`processor.RegisteredContractProviderEvidenceTest`, and
`processor.external.ExternalContractIntegrationTest`.

### 89. `public Blue registerContractProcessor(String blueId, Node canonicalTypeNode, ContractProcessor<? extends Contract> processor)`

**Purpose and library role.** Compatibility/convenience overload that delegates
to external contract-type registration. It binds executable Java behavior and
its canonical type evidence in one call.

**Direct test caller.** `processor.InternalEventOccurrenceFifoTest`.

### 90. `public Blue registerExternalContractType(String blueId, Node canonicalTypeNode, ContractProcessor<? extends Contract> processor)`

**Purpose and library role.** Validates that supplied canonical type content
matches the declared BlueId, registers its processor, publishes the type to the
processor’s extension provider, and clears stale reloadable caches. It permits
application contract types without weakening provider-evidence or identity
checks.

**Direct test/test-support callers.** `BlueCacheLifecycleTest`,
`MaterializedSelectedProcessingDocumentFailFirstTest`,
`ProcessingSnapshotProviderProvenanceTest`,
`SyntheticWorkflowProcessingFixture` (test support),
`processor.DocumentProcessorInitializationTest`,
`processor.SelectedScopeContentBlueIdFailFirstTest`, and
`processor.external.ExternalContractIntegrationTest`.

### 91. `public DocumentProcessingResult processDocument(Node document, Node event)`

**Purpose and library role.** Admits one lifecycle-coordinated Contracts
operation over a mutable document and read-only event, runs the active
processor, attaches/remembers an authoritative snapshot when appropriate, and
records timing. It is the primary `PROCESS(document,event)` facade.

**Direct test callers.** `BlueCacheLifecycleTest`,
`ProcessingDocumentStateInvariantFailFirstTest`,
`ProcessingSnapshotProviderProvenanceTest`,
`processor.ContractBundleCacheTest`,
`processor.DocumentProcessorCapabilityTest`,
`processor.DocumentProcessorEventImmutabilityTest`,
`processor.DocumentProcessorGasTest`,
`processor.DocumentProcessorHandlerFailureTest`,
`processor.DocumentProcessorInitializationTest`,
`processor.DocumentProcessorTerminationTest`,
`processor.InternalEventOccurrenceFifoTest`, `processor.ProcessEmbeddedTest`,
and `processor.TestEventChannelTest`.

### 92. `public DocumentProcessingResult processDocument(ResolvedSnapshot snapshot, Node event)`

**Purpose and library role.** Processes the snapshot’s resolved root as the
selected Processing Document while preserving the immutable canonical root as
its identity companion. This prevents the runtime from selecting authored or
stale state when an authoritative snapshot is already available.

**Direct test callers.** `processor.DocumentProcessorSnapshotTransactionTest`,
`processor.DocumentProcessorTerminationTest`, and
`processor.PublishedSnapshotRoundTripTest`.

### 93. `public DocumentProcessor getDocumentProcessor()`

**Purpose and library role.** Returns the active processor after open-state and
invalidation checks, creating the default one if needed. Direct operations on
the retained handle are outside `Blue`’s operation-admission accounting, so the
caller must coordinate them before reconfiguration or close.

**Direct test/test-support callers.** `BlueCacheLifecycleTest`,
`DeferredSnapshotCacheIsolationTest`,
`MaterializedSelectedProcessingDocumentFailFirstTest`,
`ProcessingSnapshotProviderProvenanceTest`, `processor.ChannelRunnerTest`,
`processor.ContractBundleCacheTest`,
`processor.DocumentProcessorExactFeederSupport` (test support),
`processor.DocumentProcessorInitializationTest`,
`processor.DocumentProcessorTerminationTest`,
`processor.EffectiveSubscriptionSurfaceValidatorTest`,
`processor.ExecutableBodyFieldMetadataTest`,
`processor.ExternalDeliveryPlanTrustBoundaryTest`,
`processor.PatchImpactIncrementalResolutionTest`,
`processor.ProcessingSnapshotProviderPatchTest`,
`processor.ProcessorPhasePrecedenceTest`,
`processor.ProcessorProcessEventContextTest`,
`processor.PublishedSnapshotRoundTripTest`,
`processor.ScopeSourceProjectionTest`,
`processor.SelectedScopeContentBlueIdFailFirstTest`, and
`processor.TerminationConformanceTest`.

### 94. `public Blue documentProcessor(DocumentProcessor documentProcessor)`

**Purpose and library role.** Replaces the active processor under a cache
invalidation barrier, closes the previous processor only if `Blue` owned it,
and treats the injected processor as borrowed. It supports host-composed
Contracts runtimes without transferring ownership unexpectedly.

**Direct test/test-support callers.** `BlueCacheLifecycleTest`,
`MaterializedSelectedProcessingDocumentFailFirstTest`, and
`processor.DocumentProcessorExactFeederSupport` (test support).

### 95. `public DocumentProcessingResult initializeDocument(Node document)`

**Purpose and library role.** Runs the Contracts initialization lifecycle over
a mutable document, attaches an authoritative snapshot to successful results
when needed, and coordinates cache publication with the active runtime
generation. It establishes initialized processing state without requiring an
application event.

**Direct test callers.** `BlueCacheLifecycleTest`,
`ProcessingDocumentStateInvariantFailFirstTest`,
`ProcessingSnapshotProviderProvenanceTest`,
`ReferenceBlueIdResolutionValidationTest`,
`ResolvedInstanceSchemaValidationTest`, `processor.ContractBundleCacheTest`,
`processor.DocumentProcessorBatchPatchTest`,
`processor.DocumentProcessorCapabilityTest`,
`processor.DocumentProcessorEventImmutabilityTest`,
`processor.DocumentProcessorGasTest`,
`processor.DocumentProcessorInitializationTest`,
`processor.DocumentProcessorSnapshotTransactionTest`,
`processor.DocumentProcessorTerminationTest`,
`processor.DocumentUpdateChannelTest`,
`processor.ExecutableBodyFieldMetadataTest`,
`processor.InternalEventOccurrenceFifoTest`, `processor.ProcessEmbeddedTest`,
`processor.ProcessorProcessEventContextTest`,
`processor.PublishedSnapshotRoundTripTest`,
`processor.RegisteredContractProviderEvidenceTest`,
`processor.ScopeSourceProjectionTest`,
`processor.SelectedScopeContentBlueIdFailFirstTest`,
`processor.TerminationConformanceTest`, `processor.TestEventChannelTest`, and
`processor.external.ExternalContractIntegrationTest`.

### 96. `public DocumentProcessingResult initializeDocument(ResolvedSnapshot snapshot)`

**Purpose and library role.** Initializes the snapshot’s resolved root while
retaining its canonical identity companion and remembering the resulting
snapshot. It is the immutable, selection-safe initialization path.

**Direct test callers.** `processor.DocumentProcessorInitializationTest`,
`processor.DocumentProcessorSnapshotTransactionTest`,
`processor.PublishedSnapshotRoundTripTest`,
`processor.ScopeSourceProjectionTest`, and
`processor.SelectedScopeContentBlueIdFailFirstTest`.

### 97. `public boolean isInitialized(Node document)`

**Purpose and library role.** Asks the active processor whether a mutable
document carries effective Contracts initialization state. It centralizes the
runtime’s marker semantics rather than making callers inspect fields directly.

**Direct test callers.** `BlueCacheLifecycleTest`,
`processor.DocumentProcessorGasTest`, and
`processor.DocumentProcessorInitializationTest`.

### 98. `public boolean isInitialized(ResolvedSnapshot snapshot)`

**Purpose and library role.** Checks effective initialization against the
authoritative resolved snapshot view. It avoids ambiguity between canonical
storage omissions and inherited/effective marker state.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 99. `public Node preprocess(Node node)`

**Purpose and library role.** Applies the current source preprocessing
environment: resolves a configured alias or potential BlueId in the `blue`
directive and applies Default Blue through the active provider. It converts
authored source into the form expected by resolution and identity operations.

**Direct test callers.** `BlueCacheLifecycleTest`, `MergeReverserTest`,
`NodeDeserializerTest`, `PreprocessorTest`, `RecursiveTypeResolutionTest`,
`ResolvedInstanceSchemaValidationTest`,
`ResolvedTypeCacheHistoryRegressionTest`, and `SelfReferenceTest`.

### 100. `public Optional<Class<?>> determineClass(Node node)`

**Purpose and library role.** Delegates to the configured `TypeClassResolver`,
if any, and returns an optional Java class. It keeps application type binding
optional and outside the deterministic core language model.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 101. `public <T> T nodeToObject(Node node, Class<T> clazz)`

**Purpose and library role.** Converts a Blue node to the requested Java class
using `NodeToObjectConverter` and the currently configured class resolver. It
is the language-to-application object bridge.

**Direct test callers.** `BlueCacheLifecycleTest` and
`mapping.JsonPropertyMappingTest`.

### 102. `public boolean isNodeSubtypeOf(Node candidateNode, Node superTypeNode)`

**Purpose and library role.** Evaluates Blue type-lineage subtyping through the
active provider. It exposes nominal/derived type relationships needed by
mapping and runtime selection without running full document processing.

**Direct test caller.** `BlueCacheLifecycleTest`.

## Configuration and lifecycle

### 103. `public NodeProvider getNodeProvider()`

**Purpose and library role.** Returns the active wrapped provider used by
language operations. It supports integrations that must share the facade’s
current verified/reference-aware provider boundary.

**Direct test callers.** `BlueCacheLifecycleTest`,
`MaterializedSelectedProcessingDocumentFailFirstTest`,
`TrustedProviderResolutionTest`,
`processor.PatchImpactIncrementalResolutionTest`, and
`processor.registry.BlueRuntimeTypeRegistryTest`.

### 104. `public MergingProcessor getMergingProcessor()`

**Purpose and library role.** Returns the current merge pipeline. It lets
snapshot/conformance integrations use exactly the same resolution semantics as
the facade.

**Direct test callers.** `MaterializedSelectedProcessingDocumentFailFirstTest`,
`ResolvedTypeCacheHistoryRegressionTest`,
`processor.PatchImpactIncrementalResolutionTest`,
`processor.ProcessingSnapshotProviderPatchTest`, and
`snapshot.ResolvedReferenceCacheContractTest`.

### 105. `public TypeClassResolver getTypeClassResolver()`

**Purpose and library role.** Returns the optional Java class resolver. It is
the compatibility accessor for application mapping configuration.

**Direct test caller.** No direct test caller found in current compiled
`src/test` bytecode.

### 106. `public Map<String, String> getPreprocessingAliases()`

**Purpose and library role.** Returns an unmodifiable defensive snapshot of the
current preprocessing aliases. This prevents callers from bypassing the
invalidation required when preprocessing semantics change.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 107. `public Blue nodeProvider(NodeProvider nodeProvider)`

**Purpose and library role.** Replaces and wraps the provider under coordinated
invalidation, clears reloadable evidence derived from the previous provider,
refreshes processor integration, and returns the facade. It prevents stale
content from crossing provider generations.

**Direct test callers.** `BlueCacheLifecycleTest`,
`ProcessingSnapshotProviderProvenanceTest`,
`processor.DocumentProcessorGasTest`,
`processor.ProcessingSnapshotProviderPatchTest`,
`processor.external.ExternalContractIntegrationTest`,
`snapshot.ResolvedReferenceCacheContractTest`, and
`snapshot.ResolvedSnapshotTest`.

### 108. `public Blue mergingProcessor(MergingProcessor mergingProcessor)`

**Purpose and library role.** Replaces the merge pipeline under the same
generation/invalidation discipline and returns the facade. Resolution,
snapshots, conformance, and owned processing then share the new semantics.

**Direct test callers.** `BlueCacheLifecycleTest` and
`snapshot.ResolvedReferenceCacheContractTest`.

### 109. `public Blue typeClassResolver(TypeClassResolver typeClassResolver)`

**Purpose and library role.** Replaces the optional Java class resolver and
returns the facade. This changes only application mapping, not Blue canonical
identity or provider/merge evidence.

**Direct test caller.** No direct test caller found in current compiled
`src/test` bytecode.

### 110. `public Blue preprocessingAliases(Map<String, String> preprocessingAliases)`

**Purpose and library role.** Replaces the entire alias map (`null` becomes an
empty map), invalidates configuration-dependent caches, refreshes owned
processor state, and returns the facade. It is the replace-all counterpart to
`addPreprocessingAliases`.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 111. `public boolean isClosed()`

**Purpose and library role.** Reports whether the runtime has released its
owned state. It provides a non-mutating lifecycle check for hosts and tests.

**Direct test caller.** `BlueCacheLifecycleTest`.

### 112. `public void close()`

**Purpose and library role.** Idempotently stops new runtime work, waits for
admitted provider/processor/cache operations, releases pinned and derived
caches, closes owned reference/processor resources, and emits final cache
metrics; reentrant close from active runtime work is rejected. It is the
ownership boundary that makes long-lived Blue runtimes safe and bounded.

**Direct test callers.** `BlueCacheLifecycleTest`,
`processor.EffectiveSubscriptionSurfaceValidatorTest`,
`processor.ExecutableBodyFieldMetadataTest`,
`processor.ExternalDeliveryPlanTrustBoundaryTest`,
`processor.InternalEventOccurrenceFifoTest`,
`processor.ProcessorPhasePrecedenceTest`, and
`processor.RegisteredContractProviderEvidenceTest`.

## Internal-method appendix (placeholder)

> **Placeholder for a future internal-method appendix.** This document’s
> verified 112-entry inventory intentionally covers only `Blue`’s class-level
> public facade. Internal lifecycle, cache-publication, snapshot-construction,
> limited-operation, and provider-composition helpers can be documented here
> without changing that public count.
