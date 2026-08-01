package blue.language;

import blue.language.model.NodeWireForm;

import blue.language.model.wire.JsonPointer;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageMatchingService;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.runtime.LanguageRuntimeServices;
import blue.language.runtime.WeightedLruCache;
import blue.language.model.wire.BlueLanguageConstants;

import blue.language.mapping.BlueMapper;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.mapping.TypeClassResolver;
import blue.language.conformance.ConformanceEngine;
import blue.language.dictionary.DictionaryAwareExporter;
import blue.language.dictionary.DictionaryRegistry;
import blue.language.dictionary.ExportContext;
import blue.language.dictionary.TypeDictionary;
import blue.language.graph.StandardBlueGraph;
import blue.language.graph.NodeExpander;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.StandardBlueIdentity;
import blue.language.merge.Merger;
import blue.language.merge.IncrementalMergingProcessorCapability;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.merge.processor.*;
import blue.language.matching.MatchingRuntime;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ContractProcessor;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.NoOpProcessingObserver;
import blue.language.processor.ProcessingMetricId;
import blue.language.processor.ProcessingObservation;
import blue.language.processor.ProcessingObservationContext;
import blue.language.processor.ProcessingObservationDimension;
import blue.language.processor.ProcessingObserver;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.model.Contract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeAliases;
import blue.language.snapshot.BluePatch;
import blue.language.snapshot.BluePatchOperation;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.StandardBluePreprocessing;
import blue.language.registry.BootstrapProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.provider.NodeProvider;
import blue.language.registry.NodeProviderWrapper;
import blue.language.api.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.PotentialBlueIdNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.SourceContentVerificationRuntime;
import blue.language.provider.VerifiedNodeProvider;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.provider.Types;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.BlueIdReferenceValidator;
import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalIdentityInputBuilder;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.NodePathEditor;
import blue.language.resolve.MinimizedOverlayBuilder;
import blue.language.resolve.ResolutionLimits;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.resolve.ResolutionLimits.NO_LIMITS;

/**
 * Primary facade for parsing, resolving, canonicalizing, matching, snapshotting,
 * and processing Blue documents.
 *
 * <p>A facade owns its provider configuration, bounded derived caches, and any
 * document processor it creates. Callers that inject a processor retain
 * ownership of that processor. {@link #close()} releases facade-owned runtime
 * state and prevents subsequent admitted runtime operations. Unless a method
 * is explicitly described as a pure serialization helper, admitted operations
 * throw {@link IllegalStateException} after close.</p>
 */
public class Blue implements NodeResolver, LanguageRuntimeAccess,
        SourceContentVerificationRuntime, MatchingRuntime, AutoCloseable {

    private static final int RECENT_PROCESSING_DOCUMENT_SNAPSHOT_LIMIT = 32;
    private static final BlueMapper DEFAULT_OBJECT_MAPPER =
            BlueMapper.builder().build();
    private static final String PINNED_SNAPSHOT_CACHE = "pinnedAuthoritativeSnapshots";
    private static final String DERIVED_SNAPSHOT_CACHE = "derivedResolvedSnapshots";
    private static final String CANONICAL_ALIAS_CACHE = "canonicalAliases";
    private static final String RECENT_PROCESSING_CACHE = "recentProcessingSnapshots";
    private static final String VERIFIED_REFERENCE_CACHE = "verifiedReferences";
    private static final String TRANSIENT_REFERENCE_CACHE = "transientTrustedReferences";
    private static final String STRUCTURAL_INTERNER_CACHE = "resolvedStructuralInterner";
    private static final String PROCESSOR_PLAN_CACHE = "processorPlans";
    private static final ReferenceCacheAdmissionPolicy
            PROCESSOR_REFERENCE_CACHE_ADMISSION = blueId ->
            !BlueRuntimeTypeRegistry.getDefault()
                    .isProcessorManagedTypeBlueId(blueId);

    private NodeProvider nodeProvider;
    private NodeProvider originalNodeProvider;
    private MergingProcessor mergingProcessor;
    private TypeClassResolver typeClassResolver;
    private Map<String, String> preprocessingAliases = new HashMap<>();
    private ResolutionLimits globalLimits = NO_LIMITS;
    private DocumentProcessor documentProcessor;
    private boolean documentProcessorOwned;
    private final BlueCachePolicy cachePolicy;
    private final ConcurrentMap<String, ResolvedSnapshot> pinnedSnapshotsByBlueId = new ConcurrentHashMap<>();
    private final ConcurrentMap<FrozenNode.ResolvedStructuralKey, ResolvedSnapshot>
            pinnedSnapshotsByCanonicalRepresentation = new ConcurrentHashMap<>();
    private final WeightedLruCache<FrozenNode.ResolvedStructuralKey, ResolvedSnapshot>
            derivedSnapshotsByCanonicalRepresentation;
    private final WeightedLruCache<String, WeakReference<ResolvedSnapshot>>
            derivedSnapshotsByBlueId;
    private final ConcurrentMap<String, Node> externalContractTypeNodes = new ConcurrentHashMap<>();
    private final WeightedLruCache<FrozenNode.ResolvedStructuralKey, ResolvedSnapshot>
            recentProcessingDocumentSnapshots;
    private final ResolvedReferenceCache resolvedReferenceCache;
    private final DictionaryRegistry dictionaryRegistry = new DictionaryRegistry();
    private final Set<ConformanceEngine> managedProcessorConformanceEngines =
            Collections.newSetFromMap(new WeakHashMap<ConformanceEngine, Boolean>());
    private final Object lifecycleLock = new Object();
    private final ThreadLocal<CacheGenerationStamp> activeProcessingCacheStamp =
            new ThreadLocal<>();
    private final ThreadLocal<Integer> directCacheOperationDepth = new ThreadLocal<>();
    private volatile ProcessingObserver lifecycleObserver =
            NoOpProcessingObserver.INSTANCE;
    private volatile boolean closed;
    private volatile boolean closeInProgress;
    private Thread closingThread;
    private Throwable lifecycleCloseFailure;
    private long pinnedSnapshotWeightBytes;
    private long pinnedSnapshotHighWaterBytes;
    private long processorPlanCacheHighWaterBytes;
    /** Guarded by lifecycleLock. Advances whenever runtime-owned caches are invalidated. */
    private long runtimeCacheGeneration;
    /** Guarded by lifecycleLock. Replaced whenever the active processor/configuration changes. */
    private Object processorOwnerToken = new Object();
    /** Guarded by lifecycleLock; excludes provider/merger invalidation from direct resolution. */
    private int activeDirectCacheOperations;
    /** Guarded by lifecycleLock; counts Blue wrapper calls through their final cache publication. */
    private int activeProcessingOperations;
    /** Guarded by lifecycleLock; prevents new work from entering an invalidation handoff. */
    private boolean cacheInvalidationInProgress;
    /** Guarded by lifecycleLock; identifies unsupported same-thread invalidation reentry. */
    private Thread cacheInvalidationThread;



    /**
     * Creates a runtime with bootstrap/runtime providers, default merging and
     * type mapping, and bounded default caches.
     */
    public Blue() {
        this(node -> null, null, null, BlueCachePolicy.boundedDefaults());
    }

    /**
     * Creates a runtime with one caller provider and default merging/caches.
     *
     * <p>The provider is retained as a borrowed dependency and wrapped with
     * bootstrap, runtime-type, and evidence-verification boundaries.</p>
     *
     * @param nodeProvider non-null provider for external BlueId content
     */
    public Blue(NodeProvider nodeProvider) {
        this(nodeProvider, null, null, BlueCachePolicy.boundedDefaults());
    }

    /**
     * Creates a runtime with explicit provider and optional merging strategy.
     *
     * @param nodeProvider non-null borrowed external-content provider
     * @param mergingProcessor merging strategy, or {@code null} for the default
     */
    public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor) {
        this(nodeProvider, mergingProcessor, null, BlueCachePolicy.boundedDefaults());
    }

    /**
     * Creates a runtime with explicit provider and optional Java type registry.
     *
     * @param nodeProvider non-null borrowed external-content provider
     * @param typeClassResolver Java type resolver, or {@code null} to disable
     *                          automatic class lookup
     */
    public Blue(NodeProvider nodeProvider, TypeClassResolver typeClassResolver) {
        this(nodeProvider, null, typeClassResolver, BlueCachePolicy.boundedDefaults());
    }

    /**
     * Creates a runtime with explicit provider, merging strategy, and Java
     * type registry under bounded default cache policy.
     *
     * @param nodeProvider non-null borrowed external-content provider
     * @param mergingProcessor merging strategy, or {@code null} for the default
     * @param typeClassResolver Java type resolver, or {@code null}
     */
    public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor, TypeClassResolver typeClassResolver) {
        this(nodeProvider, mergingProcessor, typeClassResolver, BlueCachePolicy.boundedDefaults());
    }

    /**
     * Creates a default runtime with explicit acceleration-cache bounds.
     *
     * @param cachePolicy immutable non-null cache policy
     * @return a runtime using bootstrap/runtime providers and default merging
     * @throws NullPointerException if {@code cachePolicy} is null
     */
    public static Blue withCachePolicy(BlueCachePolicy cachePolicy) {
        return new Blue(node -> null, null, null, cachePolicy);
    }

    /**
     * Additive constructor for hosts that need explicit per-runtime cache bounds.
     * Existing constructors continue to use {@link BlueCachePolicy#boundedDefaults()}.
     *
     * <p>Provider, merger, and resolver dependencies are borrowed. A
     * {@code null} merger selects the default pipeline and a {@code null}
     * resolver disables automatic Java class lookup.</p>
     *
     * @param nodeProvider non-null external-content provider
     * @param mergingProcessor merging strategy, or {@code null} for the default
     * @param typeClassResolver Java type resolver, or {@code null}
     * @param cachePolicy immutable non-null cache policy
     * @throws NullPointerException if {@code cachePolicy} is null
     */
    public Blue(NodeProvider nodeProvider,
                MergingProcessor mergingProcessor,
                TypeClassResolver typeClassResolver,
        BlueCachePolicy cachePolicy) {
        this.originalNodeProvider = nodeProvider;
        this.nodeProvider = wrapRuntimeProvider(nodeProvider);
        this.mergingProcessor = mergingProcessor != null ? mergingProcessor : createDefaultNodeProcessor();
        this.typeClassResolver = typeClassResolver;
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        this.derivedSnapshotsByCanonicalRepresentation = new WeightedLruCache<>(
                cachePolicy.derivedSnapshotMaxEntries(),
                cachePolicy.derivedSnapshotMaxWeightBytes(),
                cachePolicy.maximumDerivedEntryWeightBytes(),
                Blue::approximateSnapshotWeightBytes);
        this.derivedSnapshotsByBlueId = new WeightedLruCache<>(
                cachePolicy.canonicalAliasMaxEntries(),
                cachePolicy.canonicalAliasMaxWeightBytes(),
                Math.min(cachePolicy.maximumDerivedEntryWeightBytes(), 512L),
                ignored -> 64L);
        this.recentProcessingDocumentSnapshots = new WeightedLruCache<>(
                Math.min(RECENT_PROCESSING_DOCUMENT_SNAPSHOT_LIMIT,
                        cachePolicy.derivedSnapshotMaxEntries()),
                cachePolicy.derivedSnapshotMaxWeightBytes(),
                cachePolicy.maximumDerivedEntryWeightBytes(),
                Blue::approximateSnapshotWeightBytes);
        this.resolvedReferenceCache = new ResolvedReferenceCache(cachePolicy);
        this.documentProcessor = createDefaultDocumentProcessor();
        this.documentProcessorOwned = true;
    }

    /** Creates a Language merger under the host's cache-safety boundary. */
    private Merger languageMerger(
            MergingProcessor processor,
            NodeProvider provider,
            ResolvedReferenceCache referenceCache) {
        return new Merger(
                processor,
                provider,
                referenceCache,
                PROCESSOR_REFERENCE_CACHE_ADMISSION);
    }

    /** Composes the aggregate Contracts registry before Language verification. */
    private static NodeProvider wrapRuntimeProvider(
            NodeProvider callerProvider) {
        return NodeProviderWrapper.wrap(new SequentialNodeProvider(
                BootstrapProvider.INSTANCE,
                new VerifiedNodeProvider(
                        BlueRuntimeTypeRegistry.getDefault()
                                .asProcessorSnapshotProvider()),
                callerProvider));
    }

    /**
     * Resolves a node under the current global limits.
     *
     * @param node non-null source; it is not mutated
     * @return a newly materialized resolved node
     */
    public Node resolve(Node node) {
        return resolve(node, NO_LIMITS);
    }

    /**
     * Resolves a node under the intersection of method and global limits.
     *
     * @param node non-null source; it is not mutated
     * @param limits non-null per-call traversal limits
     * @return a newly materialized resolved node
     */
    @Override
    public Node resolve(Node node, ResolutionLimits limits) {
        beginDirectCacheOperation();
        try {
            ResolutionLimits effectiveLimits = combineWithGlobalLimits(limits);
            Merger merger = languageMerger(
                    mergingProcessor, nodeProvider, resolvedReferenceCache);
            return merger.resolve(node.clone(), effectiveLimits);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Resolves a defensive copy while restoring authored subtrees at selected
     * RFC 6901 paths.
     *
     * @param node non-null authored source
     * @param preservedPaths paths to retain; null or empty preserves none
     * @return an independent partially resolved graph
     */
    public Node resolvePreservingPaths(Node node, Collection<String> preservedPaths) {
        return resolvePreservingPaths(node, NO_LIMITS, preservedPaths);
    }

    /**
     * Resolves a defensive copy under caller limits while restoring authored
     * subtrees at selected RFC 6901 paths.
     *
     * @param node non-null authored source
     * @param limits non-null per-call traversal limits
     * @param preservedPaths paths to retain; null or empty preserves none
     * @return an independent partially resolved graph
     */
    public Node resolvePreservingPaths(Node node, ResolutionLimits limits, Collection<String> preservedPaths) {
        beginDirectCacheOperation();
        try {
            if (node == null) {
                throw new IllegalArgumentException("node must not be null");
            }
            Set<String> canonicalPreservedPaths = canonicalPreservedPaths(preservedPaths);
            if (canonicalPreservedPaths.isEmpty()) {
                return resolve(node.clone(), limits);
            }
            if (canonicalPreservedPaths.contains(JsonPointer.ROOT)) {
                return node.clone();
            }

            ResolutionLimits preservingLimits = limits == NO_LIMITS
                    ? ResolutionLimits.excluding(canonicalPreservedPaths)
                    : ResolutionLimits.allOf(
                    limits, ResolutionLimits.excluding(canonicalPreservedPaths));
            Node resolved = resolve(node.clone(), preservingLimits);
            for (String path : canonicalPreservedPaths) {
                Node preserved = NodePathEditor.getOrNull(node, path);
                if (preserved != null) {
                    NodePathEditor.put(resolved, path, preserved.clone());
                }
            }
            return resolved;
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Selects canonical RFC 6901 paths matching both path patterns and a node
     * predicate.
     *
     * @param node graph to inspect; null yields an empty result
     * @param pathPatterns selector patterns understood by
     *                     {@link NodePathEditor#select(Node, Collection, Predicate)};
     *                     null or empty yields no paths
     * @param predicate non-null additional node predicate
     * @return matching paths in deterministic traversal order
     * @throws IllegalArgumentException if a non-empty selection has a null predicate
     */
    public List<String> selectPaths(Node node, Collection<String> pathPatterns, Predicate<Node> predicate) {
        return NodePathEditor.select(node, pathPatterns, predicate);
    }

    /**
     * Resolves while preserving every authored path selected by pattern and
     * predicate.
     *
     * @param node non-null authored source
     * @param pathPatterns selector patterns
     * @param predicate additional node predicate
     * @return an independent partially resolved graph
     */
    public Node resolvePreservingMatchingPaths(Node node,
                                               Collection<String> pathPatterns,
                                               Predicate<Node> predicate) {
        return resolvePreservingMatchingPaths(node, NO_LIMITS, pathPatterns, predicate);
    }

    /**
     * Resolves under caller limits while preserving every authored path
     * selected by pattern and predicate.
     *
     * @param node non-null authored source
     * @param limits non-null per-call traversal limits
     * @param pathPatterns selector patterns
     * @param predicate additional node predicate
     * @return an independent partially resolved graph
     */
    public Node resolvePreservingMatchingPaths(Node node,
                                               ResolutionLimits limits,
                                               Collection<String> pathPatterns,
                                               Predicate<Node> predicate) {
        beginDirectCacheOperation();
        try {
            return resolvePreservingPaths(
                    node, limits, selectPaths(node, pathPatterns, predicate));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Reconstructs strict canonical identity input from authored provenance
     * and completed resolution.
     *
     * @param node non-null authored source; it is not mutated
     * @return a new canonical node suitable for strict BlueId calculation
     */
    public Node canonicalize(Node node) {
        beginDirectCacheOperation();
        try {
            Node preprocessed = preprocess(node.clone());
            Node resolved = resolve(preprocessed.clone());
            return new CanonicalIdentityInputBuilder().build(resolved, preprocessed);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Maps an object to Blue and returns its strict canonical identity input.
     *
     * @param object non-null serializable object
     * @return a new canonical node
     */
    public Node canonicalize(Object object) {
        beginDirectCacheOperation();
        try {
            return canonicalize(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Produces an author-facing overlay which resolves back to the same
     * completed meaning. This is the inverse Language operation to
     * {@link #resolve(Node)}; it is deliberately distinct from canonicalization.
     *
     * @param node non-null authored source; it is not mutated
     * @return a new minimized overlay
     */
    public Node minimize(Node node) {
        beginDirectCacheOperation();
        try {
            Node resolved = resolve(preprocess(node.clone()));
            return new MinimizedOverlayBuilder().build(resolved);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Maps an object to Blue and returns a minimized author-facing overlay.
     *
     * @param object non-null serializable object
     * @return a new minimized overlay
     */
    public Node minimize(Object object) {
        beginDirectCacheOperation();
        try {
            return minimize(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Creates a validated specialization by using {@code type} as the new
     * node's type and applying {@code overlay} as authored instance content.
     *
     * <p>Specialization creates a new node; it is distinct from
     * {@link #expand(Node)}, which only reveals verified content of an existing
     * exact node. The supplied nodes are never mutated. The overlay must not
     * already declare a type because replacing one authored type silently
     * would make the operation ambiguous.</p>
     *
     * @param type non-null type node or pure type reference
     * @param overlay non-null compatible authored overlay without a type
     * @return an independent authored specialization
     * @throws IllegalArgumentException when the overlay already has a type or
     *                                  does not resolve compatibly
     */
    public Node specialize(Node type, Node overlay) {
        return graphService().specialize(type, overlay);
    }

    /**
     * Canonicalization is valid only for an established, complete operation
     * result. Absence, incomplete evidence, and invalid content fail closed.
     *
     * @param result non-null operation result
     * @return canonical identity input for the established value
     * @throws IllegalStateException if the result is not established
     */
    public Node canonicalize(BlueOperationResult<Node> result) {
        Objects.requireNonNull(result, "result");
        if (!result.isEstablished()) {
            throw new IllegalStateException("Canonicalization requires an established complete result; outcome was "
                    + result.outcome() + ".");
        }
        return canonicalize(result.requireEstablished());
    }

    /**
     * Recursively replaces every resolvable reference without applying type
     * inheritance or merge semantics.
     *
     * @param node non-null source; it is not mutated
     * @return a new expanded graph
     * @throws IllegalArgumentException if required content is unavailable
     */
    public Node expand(Node node) {
        beginDirectCacheOperation();
        try {
            return graphService().expand(node);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Expands only references on the semantic closure of the demanded paths.
     * Provider absence or unavailability never turns into a definitive field
     * absence.
     *
     * @param node non-null source; it is defensively copied
     * @param limits non-null demanded-path and expansion-budget policy
     * @return an explicit established, absent, incomplete, or invalid outcome
     */
    public BlueOperationResult<Node> expandLimited(Node node, BlueOperationLimits limits) {
        beginDirectCacheOperation();
        try {
            return graphService().expandLimited(node, limits);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Resolves with a provider-expansion budget and reports semantic absence
     * separately from missing evidence.
     *
     * @param node non-null authored source; it is not mutated
     * @param limits non-null demanded-path and expansion-budget policy
     * @return an explicit established, absent, incomplete, or invalid outcome
     */
    public BlueOperationResult<Node> resolveLimited(Node node, BlueOperationLimits limits) {
        beginDirectCacheOperation();
        try {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(limits, "limits");
            ReferenceBudget budget = new ReferenceBudget(limits.maxReferenceExpansions());
            NodeProvider budgetedProvider = new NodeProvider() {
                @Override
                public List<Node> fetchByBlueId(String blueId) {
                    NodeProviderResult result = fetchResultByBlueId(blueId);
                    if (result.outcome() == NodeProviderOutcome.FOUND) {
                        return result.nodes();
                    }
                    if (result.outcome() == NodeProviderOutcome.INVALID_EVIDENCE) {
                        throw new IllegalArgumentException(result.diagnostic().orElse(
                                "Provider returned invalid evidence for " + blueId));
                    }
                    if (result.outcome() == NodeProviderOutcome.UNAVAILABLE) {
                        throw new IllegalStateException(result.diagnostic().orElse(
                                "Provider unavailable for " + blueId));
                    }
                    return null;
                }

                @Override
                public NodeProviderResult fetchResultByBlueId(String blueId) {
                    if (!budget.tryAcquire(blueId)) {
                        throw new ReferenceExpansionLimitException(blueId);
                    }
                    NodeProviderResult result = nodeProvider.fetchResultByBlueId(blueId);
                    budget.providerOutcome = result.outcome();
                    if (result.outcome() != NodeProviderOutcome.FOUND) {
                        budget.outstandingBlueIds.add(blueId);
                    }
                    return result;
                }
            };

            Node resolved;
            try {
                Node preprocessed = preprocess(node.clone());
                ResolutionLimits demandLimits = new SemanticDemandLimits(limits.demandedSegments());
                resolved = languageMerger(
                        mergingProcessor, budgetedProvider, null)
                        .resolve(preprocessed, demandLimits);
            } catch (ReferenceExpansionLimitException limitReached) {
                return BlueOperationResult.incomplete(null, budget.outstandingBlueIds,
                        null, limitReached.getMessage());
            } catch (RuntimeException failure) {
                BlueLanguageErrorCategory category = BlueLanguageErrorClassifier.classify(failure);
                if (category == BlueLanguageErrorCategory.ProviderUnavailable) {
                    return BlueOperationResult.incomplete(null, budget.outstandingBlueIds,
                            budget.providerOutcome, failure.getMessage());
                }
                if (category == BlueLanguageErrorCategory.ProviderBlueIdMismatch) {
                    return BlueOperationResult.invalid(failure.getMessage(),
                            NodeProviderOutcome.INVALID_EVIDENCE);
                }
                return BlueOperationResult.invalid(failure.getMessage(), null);
            }

            boolean found = false;
            for (String path : limits.demandedPaths()) {
                if (!semanticPathExists(resolved, path)) {
                    continue;
                }
                found = true;
            }
            if (!found) {
                return BlueOperationResult.absent("Demanded paths are absent from the completed resolved value.");
            }
            return BlueOperationResult.established(resolved);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Maps an object to Blue and recursively expands references without merge
     * semantics.
     *
     * @param object non-null serializable object
     * @return a new expanded graph
     */
    public Node expand(Object object) {
        beginDirectCacheOperation();
        try {
            return expand(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Replaces canonical node content with a pure reference to its strict
     * Content BlueId.
     *
     * @param node non-null strict BlueId input; it is not mutated
     * @return a new reference-only node
     */
    public Node collapse(Node node) {
        return graphService().collapse(node);
    }

    /** Creates a calculation-only graph service for the admitted generation. */
    private StandardBlueGraph graphService() {
        return new StandardBlueGraph(nodeProvider, this);
    }

    /**
     * Maps an object to Blue and collapses it to a strict Content BlueId
     * reference.
     *
     * @param object non-null serializable object
     * @return a new reference-only node
     */
    public Node collapse(Object object) {
        beginDirectCacheOperation();
        try {
            return collapse(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Preprocesses and completely resolves a source into immutable canonical
     * and resolved lanes, reusing or publishing bounded cache state.
     *
     * @param node non-null authored source; it is not mutated
     * @return a complete immutable snapshot
     */
    public ResolvedSnapshot resolveToSnapshot(Node node) {
        beginDirectCacheOperation();
        try {
            Node preprocessed = preprocess(node.clone());
            ResolutionLimits limits = combineWithGlobalLimits(NO_LIMITS);
            Merger merger = languageMerger(
                    mergingProcessor, nodeProvider, resolvedReferenceCache);
            return cacheSnapshot(ResolvedSnapshot.fromResolverResult(
                    merger.resolveSnapshot(preprocessed, limits)));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Builds a verified snapshot while retaining exact authored subtrees for
     * a later semantic demand. The canonical lane is still derived from the
     * complete input; only resolution below the supplied paths is deferred.
     *
     * @param node non-null authored source; it is not mutated
     * @param preservedPaths paths whose resolution is deferred
     * @return an invocation-local snapshot that may be resolution-incomplete
     */
    public ResolvedSnapshot resolveToSnapshotPreservingPaths(
            Node node,
            Collection<String> preservedPaths) {
        beginDirectCacheOperation();
        ResolvedReferenceCache oneShot =
                resolvedReferenceCache.transientChild();
        try {
            return resolveProcessingSnapshot(
                    node,
                    oneShot,
                    nodeProvider,
                    preprocessingAliases,
                    nodeProvider,
                    mergingProcessor,
                    combineWithGlobalLimits(NO_LIMITS),
                    preservedPaths);
        } finally {
            oneShot.close();
            endDirectCacheOperation();
        }
    }

    /**
     * Maps an object to Blue and returns a complete immutable snapshot.
     *
     * @param object non-null serializable object
     * @return a complete immutable snapshot
     */
    public ResolvedSnapshot resolveToSnapshot(Object object) {
        beginDirectCacheOperation();
        try {
            return resolveToSnapshot(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Resolves already-canonical input, reusing verified cached evidence when
     * available.
     *
     * @param canonical non-null strict canonical node; it is defensively frozen
     * @return a complete immutable snapshot
     */
    public ResolvedSnapshot loadSnapshot(Node canonical) {
        beginDirectCacheOperation();
        try {
            FrozenNode canonicalRoot = FrozenNode.fromNode(canonical);
            ResolvedSnapshot cached = cachedSnapshotByCanonical(
                    canonicalRoot.resolvedStructuralKey());
            if (cached != null && cached.verifiedReferenceResolution() != null) {
                return cached;
            }
            return snapshotFromVerifiedCanonical(canonicalRoot);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Loads verified provider content for a BlueId and resolves it as a
     * complete immutable snapshot.
     *
     * @param blueId canonical plain or cyclic-member BlueId
     * @return a cached or newly resolved complete snapshot
     * @throws IllegalArgumentException if provider content is absent or invalid
     */
    public ResolvedSnapshot loadSnapshot(String blueId) {
        beginDirectCacheOperation();
        try {
            ResolvedSnapshot cached = cachedSnapshotByBlueId(blueId);
            if (cached != null) {
                return cached;
            }
            List<Node> nodes = nodeProvider.fetchByBlueId(blueId);
            if (nodes == null || nodes.isEmpty()) {
                throw new IllegalArgumentException("No content found for blueId: " + blueId);
            }
            Node canonical = nodes.size() == 1
                    ? providerContentWithoutRootIdentity(nodes.get(0))
                    : new Node().items(providerContentWithoutRootIdentity(nodes));
            return snapshotFromVerifiedCanonical(FrozenNode.fromNode(canonical));
        } finally {
            endDirectCacheOperation();
        }
    }

    private Node providerContentWithoutRootIdentity(Node node) {
        Node canonical = node.clone();
        if (canonical.getBlueId() != null && !canonical.isReferenceOnly()) {
            canonical.blueId(null);
        }
        return canonical;
    }

    private List<Node> providerContentWithoutRootIdentity(List<Node> nodes) {
        List<Node> canonical = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            canonical.add(providerContentWithoutRootIdentity(node));
        }
        return canonical;
    }

    private boolean semanticPathExists(Node root, String path) {
        try {
            return BlueViewPath.select(root, path) != null;
        } catch (IllegalArgumentException absent) {
            return false;
        }
    }

    /**
     * Strictly freezes canonical content for immutable overlay patching.
     *
     * @param canonical non-null strict canonical root; it is not retained mutably
     * @return a new patch engine rooted at the frozen content
     */
    public CanonicalOverlayPatchEngine canonicalPatchEngine(Node canonical) {
        return new CanonicalOverlayPatchEngine(FrozenNode.fromNode(canonical));
    }

    /**
     * Applies one patch to strict canonical content without resolving the
     * resulting graph.
     *
     * @param canonical non-null strict canonical root
     * @param patch non-null patch operation
     * @return immutable patched root plus before/after evidence
     */
    public CanonicalPatchResult applyCanonicalPatch(Node canonical, JsonPatch patch) {
        return canonicalPatchEngine(canonical).apply(patch);
    }

    /**
     * Applies one Language-owned patch to strict canonical content.
     *
     * @param canonical non-null strict canonical root
     * @param patch non-null Language patch operation
     * @return immutable patched root plus before/after evidence
     */
    public CanonicalPatchResult applyCanonicalPatch(
            Node canonical, BluePatch patch) {
        return applyCanonicalPatch(canonical, toJsonPatch(patch));
    }

    /**
     * Applies a patch to a snapshot's canonical lane and re-resolves the
     * resulting canonical root under the current runtime configuration.
     *
     * @param snapshot non-null snapshot whose canonical lane is patchable
     * @param patch non-null patch operation
     * @return a complete immutable snapshot for the patched identity
     */
    public ResolvedSnapshot applyCanonicalPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
        beginDirectCacheOperation();
        try {
            return applyCanonicalPatch(snapshot, patch, this::snapshotFromVerifiedCanonical);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Applies one Language-owned patch and re-resolves the resulting snapshot.
     *
     * @param snapshot non-null snapshot whose canonical lane is patchable
     * @param patch non-null Language patch operation
     * @return complete immutable snapshot for the patched identity
     */
    public ResolvedSnapshot applyCanonicalPatch(
            ResolvedSnapshot snapshot, BluePatch patch) {
        return applyCanonicalPatch(snapshot, toJsonPatch(patch));
    }

    private JsonPatch toJsonPatch(BluePatch patch) {
        Objects.requireNonNull(patch, "patch");
        BluePatchOperation operation = Objects.requireNonNull(
                patch.operation(), "patch operation");
        switch (operation) {
            case ADD:
                return JsonPatch.add(patch.path(), patch.value());
            case REPLACE:
                return JsonPatch.replace(patch.path(), patch.value());
            case REMOVE:
                return JsonPatch.remove(patch.path());
            default:
                throw new IllegalArgumentException(
                        "Unsupported patch operation: " + operation);
        }
    }

    /**
     * Pins a complete snapshot until explicit cache clearing or runtime close.
     * Attached verified reference provenance, when present, is pinned with it.
     *
     * @param snapshot non-null resolution-complete snapshot
     * @return this runtime
     * @throws IllegalArgumentException if resolution is deferred
     */
    public Blue cacheResolvedSnapshot(ResolvedSnapshot snapshot) {
        beginDirectCacheOperation();
        try {
            pinSnapshot(snapshot);
            return this;
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Pins each complete snapshot in iteration order. The operation is not
     * atomic: earlier entries remain pinned if a later entry fails.
     *
     * @param snapshots non-null collection of resolution-complete snapshots
     * @return this runtime
     */
    public Blue cacheResolvedSnapshots(Collection<ResolvedSnapshot> snapshots) {
        beginDirectCacheOperation();
        try {
            snapshots.forEach(this::cacheResolvedSnapshot);
            return this;
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Looks up a pinned or bounded derived snapshot by canonical BlueId.
     * BlueId aliases exist only for snapshots carrying verified resolution
     * provenance.
     *
     * @param blueId canonical snapshot identity
     * @return the cached immutable snapshot, if present
     */
    public Optional<ResolvedSnapshot> cachedResolvedSnapshot(String blueId) {
        beginDirectCacheOperation();
        try {
            return Optional.ofNullable(cachedSnapshotByBlueId(blueId));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Counts canonical snapshots retained by both runtime cache tiers.
     *
     * @return the number of pinned and derived canonical snapshot entries
     */
    public int resolvedSnapshotCacheSize() {
        return pinnedSnapshotsByCanonicalRepresentation.size()
                + derivedSnapshotsByCanonicalRepresentation.size();
    }

    /**
     * Counts verified reference identities retained by the runtime.
     *
     * @return the number of verified reference entries retained by the runtime
     */
    public int resolvedReferenceCacheSize() {
        return resolvedReferenceCache.size();
    }

    /**
     * Counts exact resolved structures retained for graph sharing.
     *
     * @return the number of exact resolved structures retained by the interner
     */
    public int resolvedStructuralCacheSize() {
        return resolvedReferenceCache.resolvedGraphSize();
    }

    /**
     * Clears all runtime-owned snapshot, reference, structural, processor-plan,
     * and recent-processing cache state while preserving configuration.
     */
    public void clearResolvedSnapshotCache() {
        DocumentProcessor ownedProcessor;
        ProcessingObserver observer;
        CacheGaugeSnapshot gauges;
        synchronized (lifecycleLock) {
            beginCacheInvalidation();
            ownedProcessor = documentProcessorOwned ? documentProcessor : null;
        }
        try {
            if (ownedProcessor != null) {
                ownedProcessor.clearCaches();
            }
            synchronized (lifecycleLock) {
                ensureOpen();
                clearAllRuntimeCaches();
                observer = processingObserver();
                gauges = captureCacheGauges();
                endCacheInvalidation();
            }
        } catch (RuntimeException | Error exception) {
            synchronized (lifecycleLock) {
                endCacheInvalidation();
            }
            throw exception;
        }
        gauges.emit(observer);
    }

    /**
     * Returns the immutable cache policy selected when this runtime was created.
     *
     * @return the runtime-owned immutable policy
     */
    public BlueCachePolicy cachePolicy() {
        return cachePolicy;
    }

    /**
     * Returns approximate retained weights and ownership counters by cache region.
     *
     * @return a point-in-time immutable statistics snapshot
     */
    public BlueCacheStats cacheStats() {
        Map<String, BlueCacheStats.Region> regions = new LinkedHashMap<>();
        synchronized (lifecycleLock) {
            regions.put(PINNED_SNAPSHOT_CACHE, new BlueCacheStats.Region(
                    pinnedSnapshotsByCanonicalRepresentation.size(),
                    pinnedSnapshotWeightBytes,
                    pinnedSnapshotHighWaterBytes,
                    0L,
                    0L,
                    0L,
                    0L,
                    true));
            regions.put(DERIVED_SNAPSHOT_CACHE, cacheRegion(
                    derivedSnapshotsByCanonicalRepresentation, false));
            regions.put(CANONICAL_ALIAS_CACHE, cacheRegion(
                    derivedSnapshotsByBlueId, false));
            regions.put(RECENT_PROCESSING_CACHE, cacheRegion(
                    recentProcessingDocumentSnapshots, false));
            ResolvedReferenceCache.CacheStats reference = resolvedReferenceCache.cacheStats();
            regions.put(VERIFIED_REFERENCE_CACHE, new BlueCacheStats.Region(
                    reference.verifiedEntries(),
                    reference.verifiedCurrentWeightBytes(),
                    reference.verifiedHighWaterWeightBytes(),
                    0L,
                    0L,
                    reference.verifiedEvictions(),
                    reference.verifiedOversizedRejections(),
                    reference.pinnedVerifiedEntries() > 0));
            regions.put(TRANSIENT_REFERENCE_CACHE, new BlueCacheStats.Region(
                    reference.transientTrustedEntries(),
                    reference.transientTrustedCurrentWeightBytes(),
                    reference.transientTrustedHighWaterWeightBytes(),
                    0L,
                    0L,
                    reference.transientTrustedEvictions(),
                    reference.transientTrustedOversizedRejections(),
                    false));
            regions.put(STRUCTURAL_INTERNER_CACHE, new BlueCacheStats.Region(
                    reference.structuralEntries(),
                    reference.structuralCurrentWeightBytes(),
                    reference.structuralHighWaterWeightBytes(),
                    0L,
                    0L,
                    reference.structuralEvictions(),
                    reference.structuralOversizedRejections(),
                    false));
            int processorEntries = documentProcessorOwned && documentProcessor != null
                    ? documentProcessor.cacheEntryCount() : 0;
            long processorWeight = documentProcessorOwned && documentProcessor != null
                    ? documentProcessor.cacheWeightBytes() : 0L;
            processorPlanCacheHighWaterBytes = Math.max(
                    processorPlanCacheHighWaterBytes, processorWeight);
            regions.put(PROCESSOR_PLAN_CACHE, new BlueCacheStats.Region(
                    processorEntries,
                    processorWeight,
                    processorPlanCacheHighWaterBytes,
                    0L,
                    0L,
                    0L,
                    0L,
                    false));
            return new BlueCacheStats(regions, closed);
        }
    }

    /**
     * Returns a conformance handle bound to the provider and merger generation
     * current at creation time. The handle sees a snapshot of currently pinned
     * verified references and owns an otherwise independent bounded cache, so
     * retaining it across later runtime reconfiguration cannot contaminate this
     * Blue instance; callers should close it when no longer needed.
     *
     * @return an independently closeable conformance engine
     */
    public ConformanceEngine conformanceEngine() {
        beginDirectCacheOperation();
        try {
            // A caller may retain this handle across provider or merger replacement.
            // Its cache snapshots pinned authoritative evidence, but otherwise is
            // deliberately independent from Blue's current generation so stale
            // evidence can never be published into runtime state.
            return ConformanceEngine.withIsolatedCache(
                    nodeProvider, mergingProcessor, resolvedReferenceCache);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Reports the implemented Blue Language specification version.
     *
     * @return the implemented Blue Language specification version
     */
    public String languageVersion() {
        return "1.0";
    }

    /**
     * Returns the frozen alias snapshot used by Source-content verification.
     *
     * @return immutable point-in-time alias mapping
     */
    @Override
    public Map<String, String> preprocessingAliases() {
        return getPreprocessingAliases();
    }

    /**
     * Applies the released Source identity strategy independently of custom
     * merger and limit configuration.
     *
     * @param source exact authored Source content
     * @return canonical direct BlueId input
     */
    @Override
    public Node canonicalizeSourceContent(Node source) {
        Objects.requireNonNull(source, "source");
        try (Blue sourceBlue = new Blue(
                getNodeProvider(),
                createDefaultNodeProcessor(),
                null,
                cachePolicy())) {
            sourceBlue.preprocessingAliases(
                    getPreprocessingAliases());
            return sourceBlue.canonicalize(source);
        }
    }

    /** Returns the canonical core-registry identity used by this runtime. */
    @Override
    public String canonicalRegistryIdentity() {
        return BlueCoreTypeRegistry.INSTANCE.packageIdentity();
    }

    /** Returns matcher-owned cache bounds for this runtime generation. */
    @Override
    public BlueCachePolicy matchingCachePolicy() {
        return cachePolicy();
    }

    /** Applies this runtime's exact preprocessing environment for matching. */
    @Override
    public Node preprocessForMatching(Node source) {
        return preprocess(source);
    }

    /** Expands only paths admitted by the target-driven matching limits. */
    @Override
    public void expandForMatching(Node source, ResolutionLimits limits) {
        expand(source, limits);
    }

    /** Resolves a matching candidate under target-driven limits. */
    @Override
    public Node resolveForMatching(Node source, ResolutionLimits limits) {
        return resolve(source, limits);
    }

    /**
     * Materializes a type reference through verified snapshots, with the
     * released raw-definition compatibility fallback.
     */
    @Override
    public FrozenNode materializeTypeReferenceForMatching(
            FrozenNode reference) {
        Objects.requireNonNull(reference, "reference");
        if (!reference.isReferenceOnly()
                || reference.getReferenceBlueId() == null) {
            throw new IllegalArgumentException(
                    "Matching materialization requires a pure reference");
        }
        String blueId = reference.getReferenceBlueId();
        try {
            return loadSnapshot(blueId).frozenResolvedRoot();
        } catch (RuntimeException unavailableSnapshot) {
            try {
                List<Node> nodes = getNodeProvider()
                        .fetchByBlueId(blueId);
                if (nodes == null || nodes.size() != 1) {
                    return null;
                }
                Node sourceProjection = NodeToBlueIdInput
                        .stripResolvedBlueIdMetadata(
                                nodes.get(0).clone());
                return FrozenNode.fromResolvedNode(
                        preprocess(sourceProjection));
            } catch (RuntimeException unavailableDefinition) {
                return null;
            }
        }
    }

    /**
     * Expands eligible references directly in a mutable graph under the
     * intersection of method and global limits.
     *
     * <p>This limited overload mutates {@code node} in place. The one-argument
     * {@link #expand(Node)} overload instead returns a fully expanded copy.</p>
     *
     * @param node mutable graph to modify in place
     * @param limits non-null per-call traversal limits
     */
    public void expand(Node node, ResolutionLimits limits) {
        beginDirectCacheOperation();
        try {
            ResolutionLimits effectiveLimits = combineWithGlobalLimits(limits);
            new NodeExpander(nodeProvider).expand(node, effectiveLimits);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Serializes an object through the Language JSON model and applies
     * preprocessing.
     *
     * @param object non-null serializable object
     * @return a new preprocessed node graph
     */
    public Node objectToNode(Object object) {
        beginDirectCacheOperation();
        try {
            return preprocess(DEFAULT_OBJECT_MAPPER.toNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Round-trips an object through preprocessed Blue mapping into another
     * Java type.
     *
     * @param object non-null serializable source
     * @param clazz non-null target class
     * @param <T> target type
     * @return a newly mapped target instance
     */
    public <T> T convertObject(Object object, Class<T> clazz) {
        beginDirectCacheOperation();
        try {
            return nodeToObject(objectToNode(object).clone(), clazz);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Resolves and fail-closed matches a mutable candidate against a type
     * pattern under current global limits.
     *
     * @param node candidate node
     * @param type target type/shape pattern; null imposes no constraint
     * @return whether matching completed successfully and matched
     */
    public boolean nodeMatchesType(Node node, Node type) {
        beginDirectCacheOperation();
        try {
            return matchingService().matches(node, type);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Matches two already-resolved immutable nodes without another resolve.
     *
     * @param resolvedNode resolved candidate
     * @param resolvedType resolved target pattern
     * @return whether the candidate matches
     */
    public boolean nodeMatchesType(FrozenNode resolvedNode, FrozenNode resolvedType) {
        beginDirectCacheOperation();
        try {
            return matchingService().matches(
                    resolvedNode, resolvedType);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Matches one resolved snapshot path against an immutable target pattern.
     *
     * @param snapshot resolved snapshot
     * @param pointer RFC 6901 path in the resolved lane
     * @param resolvedType resolved target pattern
     * @return whether the selected candidate matches
     */
    public boolean nodeMatchesType(ResolvedSnapshot snapshot, String pointer, FrozenNode resolvedType) {
        beginDirectCacheOperation();
        try {
            return matchingService().matches(
                    snapshot, pointer, resolvedType);
        } finally {
            endDirectCacheOperation();
        }
    }

    /** Creates the focused matcher for the current runtime generation. */
    private LanguageMatchingService matchingService() {
        return new LanguageMatchingService(
                this, globalLimits, this::resolveLimited);
    }

    /**
     * Replaces runtime-wide traversal limits, invalidating configuration-bound
     * caches and Blue-owned processor state. An injected borrowed processor is
     * not replaced. Null restores {@link ResolutionLimits#NO_LIMITS}.
     *
     * @param globalLimits new limits, or {@code null}
     */
    public void setGlobalLimits(ResolutionLimits globalLimits) {
        ConfigurationRefresh refresh = refreshRuntimeConfiguration(() ->
                this.globalLimits = globalLimits != null ? globalLimits : NO_LIMITS,
                false);
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
    }

    /**
     * Returns the active limits instance. Stateful implementations remain
     * caller-owned and are not copied.
     *
     * @return active global limits
     */
    public ResolutionLimits getGlobalLimits() {
        return globalLimits;
    }

    /**
     * Parses strict YAML source and applies the configured preprocessing
     * pipeline.
     *
     * @param yaml YAML source
     * @return a new preprocessed node graph
     */
    public Node yamlToNode(String yaml) {
        beginDirectCacheOperation();
        try {
            return preprocess(parseSourceYaml(yaml));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Parses strict JSON source and applies the configured preprocessing
     * pipeline.
     *
     * @param json JSON source
     * @return a new preprocessed node graph
     */
    public Node jsonToNode(String json) {
        beginDirectCacheOperation();
        try {
            return preprocess(parseSourceJson(json));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Parses strict YAML into its authored node shape without preprocessing.
     *
     * @param yaml YAML source
     * @return a newly parsed node graph
     */
    public Node parseSourceYaml(String yaml) {
        return YAML_MAPPER.readValue(yaml, Node.class);
    }

    /**
     * Parses strict JSON into its authored node shape without preprocessing.
     *
     * @param json JSON source
     * @return a newly parsed node graph
     */
    public Node parseSourceJson(String json) {
        return JSON_MAPPER.readValue(json, Node.class);
    }

    /**
     * Parses YAML as direct strict BlueId input and validates reference and
     * canonical identity rules without preprocessing.
     *
     * @param yaml YAML identity input
     * @return the validated newly parsed graph
     * @throws IllegalArgumentException if the graph is not valid BlueId input
     */
    public Node parseBlueIdInputYaml(String yaml) {
        Node node = YAML_MAPPER.readValue(yaml, Node.class);
        BlueIdReferenceValidator.validate(node);
        DirectBlueIdCalculator.calculateBlueId(node);
        return node;
    }

    /**
     * Parses JSON as direct strict BlueId input and validates reference and
     * canonical identity rules without preprocessing.
     *
     * @param json JSON identity input
     * @return the validated newly parsed graph
     * @throws IllegalArgumentException if the graph is not valid BlueId input
     */
    public Node parseBlueIdInputJson(String json) {
        Node node = JSON_MAPPER.readValue(json, Node.class);
        BlueIdReferenceValidator.validate(node);
        DirectBlueIdCalculator.calculateBlueId(node);
        return node;
    }

    /**
     * Serializes the official normalized node representation as YAML.
     *
     * @param node node to serialize; it is not mutated
     * @return YAML text
     */
    public String nodeToYaml(Node node) {
        return YAML_MAPPER.writeValueAsString(NodeWireForm.get(node));
    }

    /**
     * Applies dictionary export rules to a copy and serializes normalized YAML.
     *
     * @param node node to export; it is not mutated
     * @param exportContext export policy; null uses {@link ExportContext#empty()}
     * @return YAML text
     */
    public String nodeToYaml(Node node, ExportContext exportContext) {
        return YAML_MAPPER.writeValueAsString(NodeWireForm.get(exportNode(node, exportContext)));
    }

    /**
     * Serializes YAML using bare scalar/list sugar where possible.
     *
     * @param node node to serialize; it is not mutated
     * @return simplified YAML text
     */
    public String nodeToSimpleYaml(Node node) {
        return YAML_MAPPER.writeValueAsString(NodeWireForm.get(node, NodeWireForm.Strategy.SIMPLE));
    }

    /**
     * Serializes the official normalized node representation as JSON.
     *
     * @param node node to serialize; it is not mutated
     * @return JSON text
     */
    public String nodeToJson(Node node) {
        return JSON_MAPPER.writeValueAsString(NodeWireForm.get(node));
    }

    /**
     * Applies dictionary export rules to a copy and serializes normalized JSON.
     *
     * @param node node to export; it is not mutated
     * @param exportContext export policy; null uses {@link ExportContext#empty()}
     * @return JSON text
     */
    public String nodeToJson(Node node, ExportContext exportContext) {
        return JSON_MAPPER.writeValueAsString(NodeWireForm.get(exportNode(node, exportContext)));
    }

    /**
     * Serializes JSON using bare scalar/list sugar where possible.
     *
     * @param node node to serialize; it is not mutated
     * @return simplified JSON text
     */
    public String nodeToSimpleJson(Node node) {
        return JSON_MAPPER.writeValueAsString(NodeWireForm.get(node, NodeWireForm.Strategy.SIMPLE));
    }

    /**
     * Maps and preprocesses an object, then serializes normalized YAML.
     *
     * @param object non-null serializable object
     * @return YAML text
     */
    public String objectToYaml(Object object) {
        beginDirectCacheOperation();
        try {
            return nodeToYaml(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Maps and preprocesses an object, then serializes simplified YAML.
     *
     * @param object non-null serializable object
     * @return simplified YAML text
     */
    public String objectToSimpleYaml(Object object) {
        beginDirectCacheOperation();
        try {
            return nodeToSimpleYaml(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Maps and preprocesses an object, then serializes normalized JSON.
     *
     * @param object non-null serializable object
     * @return JSON text
     */
    public String objectToJson(Object object) {
        beginDirectCacheOperation();
        try {
            return nodeToJson(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Maps and preprocesses an object, applies dictionary export, and
     * serializes normalized JSON.
     *
     * @param object non-null serializable object
     * @param exportContext export policy; null uses {@link ExportContext#empty()}
     * @return JSON text
     */
    public String objectToJson(Object object, ExportContext exportContext) {
        beginDirectCacheOperation();
        try {
            return nodeToJson(objectToNode(object), exportContext);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Maps and preprocesses an object, then serializes simplified JSON.
     *
     * @param object non-null serializable object
     * @return simplified JSON text
     */
    public String objectToSimpleJson(Object object) {
        beginDirectCacheOperation();
        try {
            return nodeToSimpleJson(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Exports a defensive graph using registered type dictionaries and the
     * supplied policy.
     *
     * @param node node to export; it is not mutated
     * @param exportContext export policy; null uses {@link ExportContext#empty()}
     * @return a newly exported node graph
     */
    public Node exportNode(Node node, ExportContext exportContext) {
        return new DictionaryAwareExporter(dictionaryRegistry, exportContext).export(node);
    }

    /**
     * Registers a borrowed type dictionary by its unique name.
     *
     * @param dictionary non-null dictionary retained by reference
     * @return this runtime
     */
    public Blue registerTypeDictionary(TypeDictionary dictionary) {
        synchronized (lifecycleLock) {
            ensureOpen();
            dictionaryRegistry.register(dictionary);
        }
        return this;
    }

    /**
     * Registers borrowed type dictionaries in iteration order.
     *
     * @param dictionaries dictionaries to retain; null is a no-op
     * @return this runtime
     */
    public Blue registerTypeDictionaries(Collection<? extends TypeDictionary> dictionaries) {
        synchronized (lifecycleLock) {
            ensureOpen();
            dictionaryRegistry.registerAll(dictionaries);
        }
        return this;
    }

    /**
     * Returns the live runtime-owned mutable dictionary registry. Coordinate
     * direct mutations with runtime use; registration helpers are preferred.
     *
     * @return the live dictionary registry
     */
    public DictionaryRegistry dictionaryRegistry() {
        return dictionaryRegistry;
    }

    /**
     * Deep-clones a Node directly or round-trips another object through Blue
     * mapping into the same runtime class.
     *
     * @param object source object, or null
     * @param <T> source/result type
     * @return an independent clone, or null for null input
     */
    public <T> T clone(T object) {
        if (object == null) {
            return null;
        }

        if (object instanceof Node) {
            return (T) ((Node) object).clone();
        }

        beginDirectCacheOperation();
        try {
            Class<T> clazz = (Class<T>) object.getClass();
            Node node = objectToNode(object);
            Node clonedNode = node.clone();
            return nodeToObject(clonedNode, clazz);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Calculates a strict Content BlueId from direct canonical node input.
     * This overload does not preprocess, resolve, or canonicalize.
     *
     * @param node non-null strict canonical identity input
     * @return canonical Base58 SHA-256 BlueId
     */
    public String calculateBlueId(Node node) {
        return identityService().directBlueId(node);
    }

    /**
     * Maps an object and calculates its direct strict Content BlueId without
     * preprocessing, resolution, or canonicalization.
     *
     * <p>Source-only constructs remain visible to strict identity validation
     * and are rejected. Use {@link #calculateSourceDocumentBlueId(Object)}
     * when the object is an authored Source Document.</p>
     *
     * @param object non-null serializable direct BlueId input
     * @return canonical Base58 SHA-256 BlueId
     */
    public String calculateBlueId(Object object) {
        beginDirectCacheOperation();
        try {
            return calculateBlueId(DEFAULT_OBJECT_MAPPER.toNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Calculates the BlueId of a Source Document through the complete
     * Language identity pipeline.
     *
     * <p>The input is preprocessed, completely resolved, and canonicalized.
     * The resulting Canonical Identity Input is then passed to
     * {@link #calculateBlueId(Node)}. Minimization is deliberately not part
     * of this path.</p>
     *
     * @param node non-null authored Source Document; it is not mutated
     * @return canonical Base58 SHA-256 BlueId of the Source Document
     */
    public String calculateSourceDocumentBlueId(Node node) {
        return identityService().sourceDocumentBlueId(node);
    }

    /** Creates the focused identity service over the current generation. */
    private StandardBlueIdentity identityService() {
        return new StandardBlueIdentity(this::canonicalize);
    }

    /**
     * Maps an object and calculates its Source Document BlueId through the
     * complete Language identity pipeline.
     *
     * @param object non-null serializable object
     * @return canonical Base58 SHA-256 Source Document BlueId
     */
    public String calculateSourceDocumentBlueId(Object object) {
        beginDirectCacheOperation();
        try {
            return calculateSourceDocumentBlueId(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Adds aliases to a defensive copy of current preprocessing configuration,
     * invalidating configuration-bound caches and processor state.
     *
     * @param aliases non-null alias-to-BlueId mappings
     */
    public void addPreprocessingAliases(Map<String, String> aliases) {
        ConfigurationRefresh refresh = refreshRuntimeConfiguration(() -> {
            Map<String, String> nextAliases = new HashMap<>(preprocessingAliases);
            nextAliases.putAll(aliases);
            preprocessingAliases = nextAliases;
        }, false);
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
    }

    /**
     * Registers a borrowed annotated contract processor and invalidates
     * processor matching/plan state.
     *
     * @param processor non-null processor whose contract type supplies identity
     * @return this runtime
     */
    public Blue registerContractProcessor(ContractProcessor<? extends Contract> processor) {
        ensureOpen();
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        ConfigurationRefresh refresh = refreshDocumentProcessorGeneration(
                builder -> builder.registerContractProcessor(processor),
                () -> { });
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
        return this;
    }

    /**
     * Registers a processor mapping for {@code blueId} without supplying type
     * content. The configured provider must already be able to return verified
     * content for that BlueId; no Java class-name node is synthesized.
     *
     * @param blueId exact contract type identity
     * @param processor non-null borrowed processor
     * @return this runtime
     */
    public Blue registerContractProcessor(String blueId, ContractProcessor<? extends Contract> processor) {
        ensureOpen();
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        ConfigurationRefresh refresh = refreshDocumentProcessorGeneration(
                builder -> builder.registerContractProcessor(blueId, processor),
                () -> { });
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
        return this;
    }

    /**
     * Registers a borrowed processor together with exact canonical external
     * type content.
     *
     * <p>The type node is cloned, strictly hashed, and retained only when its
     * calculated identity equals {@code blueId}; dependent caches are then
     * invalidated.</p>
     *
     * @param blueId declared external contract type identity
     * @param canonicalTypeNode non-null strict canonical type definition
     * @param processor non-null borrowed processor
     * @return this runtime
     * @throws IllegalArgumentException if the declared identity does not match
     */
    public Blue registerExternalContractType(String blueId,
                                             Node canonicalTypeNode,
                                             ContractProcessor<? extends Contract> processor) {
        // Preserve the lifecycle contract even when the supplied registration
        // arguments are invalid: closed runtimes reject all runtime work first.
        ensureOpen();
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        Node validatedCanonicalType = validatedExternalTypeNode(blueId, canonicalTypeNode);
        ConfigurationRefresh refresh = refreshDocumentProcessorGeneration(
                builder -> builder.registerContractProcessor(
                        blueId, validatedCanonicalType, processor),
                () -> {
                externalContractTypeNodes.put(blueId, validatedCanonicalType);
                });
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
        return this;
    }

    /**
     * Processes an authored document/event pair under one admitted runtime
     * configuration and publishes any complete authoritative snapshot.
     *
     * <p>Neither input is mutated. Transient execution-evidence unavailability
     * may propagate; invalid evidence yields a non-committing result.</p>
     *
     * @param document non-null Processing Document
     * @param event non-null read-only Processing Event
     * @return processing result and authoritative snapshot
     */
    public DocumentProcessingResult processDocument(Node document, Node event) {
        ProcessingOperation operation = beginProcessingOperation();
        DocumentProcessor processor = operation.processor;
        CacheGenerationStamp previousStamp = activeProcessingCacheStamp.get();
        activeProcessingCacheStamp.set(operation.stamp);
        long start = System.nanoTime();
        try {
            return rememberPublishedProcessingSnapshot(
                    operation, processor.processDocument(document, event));
        } finally {
            try {
                recordObservation(
                        processor.processingObserver(),
                        ProcessingMetricId.BLUE_PROCESS_DOCUMENT_NANOS,
                        System.nanoTime() - start);
            } finally {
                finishProcessingOperation(previousStamp);
            }
        }
    }

    /**
     * Processes the snapshot's resolved root as the selected Processing Document.
     * The canonical root remains the immutable identity companion.
     *
     * @param snapshot verified canonical and resolved document views
     * @param event read-only Processing Event
     * @return the processing result and its authoritative snapshot
     */
    public DocumentProcessingResult processDocument(ResolvedSnapshot snapshot, Node event) {
        ProcessingOperation operation = beginProcessingOperation();
        DocumentProcessor processor = operation.processor;
        CacheGenerationStamp previousStamp = activeProcessingCacheStamp.get();
        activeProcessingCacheStamp.set(operation.stamp);
        long start = System.nanoTime();
        try {
            return rememberPublishedProcessingSnapshot(
                    operation,
                    processor.processDocument(snapshot, event));
        } finally {
            try {
                recordObservation(
                        processor.processingObserver(),
                        ProcessingMetricId.BLUE_PROCESS_DOCUMENT_NANOS,
                        System.nanoTime() - start);
            } finally {
                finishProcessingOperation(previousStamp);
            }
        }
    }

    /**
     * Returns the active processor handle. Operations invoked directly on this
     * handle are outside Blue's operation-admission accounting; callers must
     * finish and externally coordinate such work before reconfiguring or closing
     * this runtime. Prefer the processing methods on {@code Blue} when lifecycle
     * coordination is required.
     *
     * @return the live processor handle
     */
    public DocumentProcessor getDocumentProcessor() {
        synchronized (lifecycleLock) {
            awaitCacheInvalidation();
            ensureOpen();
            return ensureDocumentProcessor();
        }
    }

    /**
     * Installs an observer on a new immutable processor generation.
     *
     * <p>The observer is operational only: its failures are isolated and it
     * cannot affect processing results, diagnostics, gas, or cache admission.</p>
     *
     * @param observer non-null typed processing observer
     * @return this runtime
     */
    public Blue processingObserver(ProcessingObserver observer) {
        Objects.requireNonNull(observer, "observer");
        ConfigurationRefresh refresh = refreshDocumentProcessorGeneration(
                builder -> builder.observer(observer),
                () -> { });
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
        return this;
    }

    /**
     * Replaces the active processor with a borrowed instance.
     *
     * <p>The runtime never closes the injected processor. Any previously owned
     * processor is closed and configuration-bound caches are invalidated.</p>
     *
     * @param documentProcessor non-null borrowed processor
     * @return this runtime
     */
    public Blue documentProcessor(DocumentProcessor documentProcessor) {
        if (documentProcessor == null) {
            throw new IllegalArgumentException("documentProcessor must not be null");
        }
        DocumentProcessor processorToClose;
        synchronized (lifecycleLock) {
            ensureOpen();
            if (this.documentProcessor == documentProcessor) {
                return this;
            }
            beginCacheInvalidation();
            try {
                processorToClose = documentProcessorOwned
                        ? this.documentProcessor : null;
                processorOwnerToken = new Object();
                clearReloadableRuntimeCaches();
                this.documentProcessor = documentProcessor;
                // Public injection is a borrowed dependency. Preserve the historical
                // setter contract: replacing or closing Blue must not close a
                // processor that may be shared by another runtime.
                this.documentProcessorOwned = false;
            } finally {
                endCacheInvalidation();
            }
        }
        closeProcessor(processorToClose);
        return this;
    }

    /**
     * Initializes an authored Processing Document without mutating the caller's
     * node and publishes any complete authoritative snapshot.
     *
     * @param document non-null Processing Document
     * @return initialization result and authoritative snapshot
     */
    public DocumentProcessingResult initializeDocument(Node document) {
        ProcessingOperation operation = beginProcessingOperation();
        CacheGenerationStamp previousStamp = activeProcessingCacheStamp.get();
        activeProcessingCacheStamp.set(operation.stamp);
        try {
            return rememberPublishedProcessingSnapshot(
                    operation, operation.processor.initializeDocument(document));
        } finally {
            finishProcessingOperation(previousStamp);
        }
    }

    /**
     * Initializes the snapshot's resolved root as the selected Processing Document.
     * The canonical root remains the immutable identity companion.
     *
     * @param snapshot verified canonical and resolved document views
     * @return the initialization result and its authoritative snapshot
     */
    public DocumentProcessingResult initializeDocument(ResolvedSnapshot snapshot) {
        ProcessingOperation operation = beginProcessingOperation();
        CacheGenerationStamp previousStamp = activeProcessingCacheStamp.get();
        activeProcessingCacheStamp.set(operation.stamp);
        try {
            return rememberPublishedProcessingSnapshot(
                    operation,
                    operation.processor.initializeDocument(snapshot));
        } finally {
            finishProcessingOperation(previousStamp);
        }
    }

    /**
     * Validates and inspects the direct initialization marker.
     *
     * @param document Processing Document to inspect
     * @return whether the document is initialized under current configuration
     */
    public boolean isInitialized(Node document) {
        beginDirectCacheOperation();
        try {
            return ensureDocumentProcessor().isInitialized(document);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Snapshot-native initialization check.
     *
     * @param snapshot snapshot to inspect
     * @return whether its resolved document is initialized
     */
    public boolean isInitialized(ResolvedSnapshot snapshot) {
        beginDirectCacheOperation();
        try {
            return ensureDocumentProcessor().isInitialized(snapshot);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Applies the mandatory baseline and declared preprocessing transformations to a
     * defensive clone.
     *
     * @param node non-null authored source
     * @return a newly preprocessed graph with the {@code blue} directive removed
     */
    public Node preprocess(Node node) {
        beginDirectCacheOperation();
        try {
            return preprocess(node, nodeProvider, preprocessingAliases);
        } finally {
            endDirectCacheOperation();
        }
    }

    private Node preprocess(Node node,
                            NodeProvider preprocessingNodeProvider,
                            Map<String, String> aliases) {
        Preprocessor configured = new Preprocessor(
                Preprocessor.getStandardProvider(),
                preprocessingNodeProvider,
                aliases,
                RuntimeTypeAliases.NAME_TO_BLUE_ID);
        return new StandardBluePreprocessing(
                configured,
                LanguageRuntimeServices
                        .preprocessingEnvironmentIdentity(aliases))
                .preprocess(node);
    }

    /**
     * Resolves the effective node type through the optional Java type registry.
     *
     * @param node node whose effective type should be inspected
     * @return registered Java class, or empty when unavailable/disabled
     */
    public Optional<Class<?>> determineClass(Node node) {
        beginDirectCacheOperation();
        try {
            TypeClassResolver capturedResolver;
            synchronized (lifecycleLock) {
                capturedResolver = typeClassResolver;
            }
            if (capturedResolver != null) {
                Class<?> clazz = capturedResolver.resolveClass(node);
                if (clazz != null)
                    return Optional.of(clazz);
            }
            return Optional.empty();
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Maps a node graph to a newly created Java object.
     *
     * @param node source graph; it is not mutated
     * @param clazz non-null target class
     * @param <T> target type
     * @return newly mapped object
     */
    public <T> T nodeToObject(Node node, Class<T> clazz) {
        beginDirectCacheOperation();
        try {
            TypeClassResolver capturedResolver;
            synchronized (lifecycleLock) {
                capturedResolver = typeClassResolver;
            }
            return new NodeToObjectConverter(capturedResolver).convert(node, clazz);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Traverses verified provider-backed type ancestry.
     *
     * @param candidateNode candidate type
     * @param superTypeNode requested base type
     * @return whether the candidate is identical to or derives from the base
     */
    public boolean isNodeSubtypeOf(Node candidateNode, Node superTypeNode) {
        beginDirectCacheOperation();
        try {
            return Types.isSubtype(candidateNode, superTypeNode, nodeProvider);
        } finally {
            endDirectCacheOperation();
        }
    }

    /**
     * Returns the active composed provider, including bootstrap/runtime and
     * evidence-verification boundaries.
     *
     * @return active provider view
     */
    public NodeProvider getNodeProvider() {
        return nodeProvider;
    }

    /**
     * Returns the currently configured merging strategy.
     *
     * @return the active merging strategy
     */
    public MergingProcessor getMergingProcessor() {
        return mergingProcessor;
    }

    /**
     * Returns the currently configured Java type resolver.
     *
     * @return the active Java type resolver, or {@code null} when disabled
     */
    public TypeClassResolver getTypeClassResolver() {
        return typeClassResolver;
    }

    /**
     * Snapshots the preprocessing aliases configured on this facade.
     *
     * @return an unmodifiable point-in-time copy of preprocessing aliases
     */
    public Map<String, String> getPreprocessingAliases() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableMap(new HashMap<>(preprocessingAliases));
        }
    }

    /**
     * Replaces the borrowed external provider, rebuilds verified provider
     * composition, and invalidates configuration-bound caches/processor state.
     *
     * @param nodeProvider non-null borrowed provider
     * @return this runtime
     */
    public Blue nodeProvider(NodeProvider nodeProvider) {
        ConfigurationRefresh refresh = refreshRuntimeConfiguration(() -> {
            this.originalNodeProvider = nodeProvider;
            this.nodeProvider = wrapRuntimeProvider(nodeProvider);
        }, true);
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
        return this;
    }

    /**
     * Replaces the borrowed merging strategy and invalidates
     * configuration-bound caches/processor state.
     *
     * @param mergingProcessor non-null merging strategy
     * @return this runtime
     */
    public Blue mergingProcessor(MergingProcessor mergingProcessor) {
        ConfigurationRefresh refresh = refreshRuntimeConfiguration(() ->
                this.mergingProcessor = mergingProcessor, true);
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
        return this;
    }

    /**
     * Replaces Java type lookup without taking ownership.
     *
     * @param typeClassResolver resolver, or {@code null} to disable lookup
     * @return this runtime
     */
    public Blue typeClassResolver(TypeClassResolver typeClassResolver) {
        synchronized (lifecycleLock) {
            ensureOpen();
            this.typeClassResolver = typeClassResolver;
            return this;
        }
    }

    /**
     * Replaces preprocessing aliases with a defensive copy and invalidates
     * configuration-bound caches/processor state.
     *
     * @param preprocessingAliases mappings to copy; null clears all aliases
     * @return this runtime
     */
    public Blue preprocessingAliases(Map<String, String> preprocessingAliases) {
        ConfigurationRefresh refresh = refreshRuntimeConfiguration(() ->
                this.preprocessingAliases = preprocessingAliases != null
                        ? new HashMap<>(preprocessingAliases)
                        : new HashMap<>(), false);
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
        return this;
    }

    private DocumentProcessor ensureDocumentProcessor() {
        synchronized (lifecycleLock) {
            ensureOpen();
            if (documentProcessor == null) {
                documentProcessor = createDefaultDocumentProcessor();
                documentProcessorOwned = true;
            }
            return documentProcessor;
        }
    }

    private DocumentProcessor beginDocumentProcessorMutation() {
        synchronized (lifecycleLock) {
            beginCacheInvalidation();
            try {
                return ensureDocumentProcessor();
            } catch (RuntimeException | Error exception) {
                endCacheInvalidation();
                throw exception;
            }
        }
    }

    private void endDocumentProcessorMutation() {
        synchronized (lifecycleLock) {
            endCacheInvalidation();
        }
    }

    /**
     * Builds and atomically installs one immutable processor successor while
     * runtime work is excluded from the configuration handoff.
     */
    private ConfigurationRefresh refreshDocumentProcessorGeneration(
            Consumer<DocumentProcessor.Builder> configurationMutation,
            Runnable runtimeMutation) {
        DocumentProcessor previous = beginDocumentProcessorMutation();
        boolean previousOwned;
        synchronized (lifecycleLock) {
            previousOwned = documentProcessorOwned;
        }
        try {
            DocumentProcessor.Builder builder =
                    DocumentProcessor.Builder.from(previous);
            configurationMutation.accept(builder);
            DocumentProcessor replacement = builder
                    .withMatchingService(new ContractMatchingService(this))
                    .build();
            synchronized (lifecycleLock) {
                runtimeMutation.run();
                documentProcessor = replacement;
                documentProcessorOwned = true;
                clearReloadableRuntimeCaches();
                return new ConfigurationRefresh(
                        previousOwned ? previous : null,
                        replacement.processingObserver(),
                        captureCacheGauges());
            }
        } finally {
            endDocumentProcessorMutation();
        }
    }

    private ProcessingOperation beginProcessingOperation() {
        synchronized (lifecycleLock) {
            CacheGenerationStamp activeStamp = activeProcessingCacheStamp.get();
            if (activeStamp == null) {
                awaitCacheInvalidation();
            }
            ensureOpen();
            DocumentProcessor processor = ensureDocumentProcessor();
            activeProcessingOperations++;
            return new ProcessingOperation(processor,
                    activeStamp != null
                            ? activeStamp
                            : new CacheGenerationStamp(
                            processorOwnerToken, runtimeCacheGeneration));
        }
    }

    private void finishProcessingOperation(CacheGenerationStamp previousStamp) {
        restoreProcessingCacheStamp(previousStamp);
        synchronized (lifecycleLock) {
            activeProcessingOperations--;
            lifecycleLock.notifyAll();
        }
    }

    private void beginDirectCacheOperation() {
        synchronized (lifecycleLock) {
            Integer depth = directCacheOperationDepth.get();
            if (depth == null || depth == 0) {
                awaitCacheInvalidation();
                ensureOpen();
                activeDirectCacheOperations++;
                directCacheOperationDepth.set(1);
            } else {
                ensureOpen();
                directCacheOperationDepth.set(depth + 1);
            }
        }
    }

    private void endDirectCacheOperation() {
        synchronized (lifecycleLock) {
            Integer depth = directCacheOperationDepth.get();
            if (depth == null || depth <= 0) {
                throw new IllegalStateException("Direct cache operation was not active");
            }
            if (depth == 1) {
                directCacheOperationDepth.remove();
                activeDirectCacheOperations--;
                lifecycleLock.notifyAll();
            } else {
                directCacheOperationDepth.set(depth - 1);
            }
        }
    }

    /** Caller holds lifecycleLock. */
    private void beginCacheInvalidation() {
        if (activeProcessingCacheStamp.get() != null
                || directCacheOperationDepth.get() != null) {
            throw new IllegalStateException(
                    "Blue caches cannot be invalidated during active runtime work");
        }
        awaitCacheInvalidation();
        ensureOpen();
        cacheInvalidationInProgress = true;
        cacheInvalidationThread = Thread.currentThread();
        try {
            while (activeProcessingOperations > 0 || activeDirectCacheOperations > 0) {
                try {
                    lifecycleLock.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while waiting to invalidate Blue caches", exception);
                }
            }
            ensureOpen();
        } catch (RuntimeException | Error exception) {
            cacheInvalidationInProgress = false;
            cacheInvalidationThread = null;
            lifecycleLock.notifyAll();
            throw exception;
        }
    }

    /** Caller holds lifecycleLock. */
    private void endCacheInvalidation() {
        cacheInvalidationInProgress = false;
        cacheInvalidationThread = null;
        lifecycleLock.notifyAll();
    }

    /** Caller holds lifecycleLock. */
    private void awaitCacheInvalidation() {
        while (cacheInvalidationInProgress) {
            if (cacheInvalidationThread == Thread.currentThread()) {
                throw new IllegalStateException(
                        "Blue runtime work cannot reenter cache invalidation");
            }
            try {
                lifecycleLock.wait();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for Blue cache invalidation", exception);
            }
        }
    }

    private void restoreProcessingCacheStamp(CacheGenerationStamp previousStamp) {
        if (previousStamp == null) {
            activeProcessingCacheStamp.remove();
        } else {
            activeProcessingCacheStamp.set(previousStamp);
        }
    }

    private CacheGenerationStamp currentCacheStamp(Object expectedOwnerToken) {
        synchronized (lifecycleLock) {
            if (closed || processorOwnerToken != expectedOwnerToken) {
                return CacheGenerationStamp.invalid(expectedOwnerToken);
            }
            return new CacheGenerationStamp(expectedOwnerToken, runtimeCacheGeneration);
        }
    }

    private boolean isCurrentCacheStampLocked(CacheGenerationStamp stamp) {
        return !closed
                && stamp != null
                && stamp.ownerToken == processorOwnerToken
                && stamp.generation == runtimeCacheGeneration;
    }

    private boolean isCurrentCacheStamp(CacheGenerationStamp stamp) {
        synchronized (lifecycleLock) {
            return isCurrentCacheStampLocked(stamp);
        }
    }

    private DocumentProcessor createDefaultDocumentProcessor() {
        Object ownerToken = processorOwnerToken;
        NodeProvider capturedPreprocessingProvider = nodeProvider;
        NodeProvider capturedSnapshotProvider = processorSnapshotNodeProvider();
        MergingProcessor capturedMergingProcessor = mergingProcessor;
        Map<String, String> capturedAliases = Collections.unmodifiableMap(
                new HashMap<>(preprocessingAliases));
        ResolutionLimits capturedLimits = globalLimits;
        return DocumentProcessor.builder()
                .withConformanceEngine(processorConformanceEngine(
                        capturedSnapshotProvider, capturedMergingProcessor))
                .withSnapshotManager(new BlueProcessingSnapshotManager(
                        ownerToken,
                        capturedPreprocessingProvider,
                        capturedSnapshotProvider,
                        capturedMergingProcessor,
                        capturedAliases,
                        capturedLimits,
                        null,
                        null))
                .withMatchingService(new ContractMatchingService(this))
                .build();
    }

    private ConformanceEngine processorConformanceEngine(NodeProvider snapshotNodeProvider,
                                                         MergingProcessor snapshotMergingProcessor) {
        ConformanceEngine engine = new ConformanceEngine(
                snapshotNodeProvider, snapshotMergingProcessor, resolvedReferenceCache);
        synchronized (managedProcessorConformanceEngines) {
            managedProcessorConformanceEngines.add(engine);
        }
        return engine;
    }

    private DocumentProcessingResult rememberPublishedProcessingSnapshot(
            ProcessingOperation operation,
            DocumentProcessingResult result) {
        if (result == null
                || result.status() == blue.language.processor.ProcessorStatus.CAPABILITY_FAILURE
                || result.status() == blue.language.processor.ProcessorStatus.INVALID_PROCESSING_DOCUMENT) {
            return result;
        }
        ResolvedSnapshot snapshot =
                publishedProcessingSnapshot(
                        result.document(), operation.stamp);
        if (snapshot != null) {
            rememberProcessingSnapshot(
                    result.document(), snapshot, operation.stamp);
        }
        return result;
    }

    /**
     * Returns an exact snapshot already published by the processing runtime.
     * A result-cache update must never resolve an additional reference: doing
     * so would turn an undemanded executable body into semantic work after the
     * invocation had already completed.
     */
    private ResolvedSnapshot publishedProcessingSnapshot(
            Node document,
            CacheGenerationStamp stamp) {
        FrozenNode.ResolvedStructuralKey key;
        try {
            key = FrozenNode.fromNode(document).resolvedStructuralKey();
        } catch (RuntimeException exception) {
            return null;
        }
        synchronized (lifecycleLock) {
            if (!isCurrentCacheStampLocked(stamp)) {
                return null;
            }
            ResolvedSnapshot pinned =
                    pinnedSnapshotsByCanonicalRepresentation.get(key);
            return pinned != null
                    ? pinned
                    : derivedSnapshotsByCanonicalRepresentation.peek(key);
        }
    }

    private ResolvedSnapshot cachedProcessingSnapshotFor(Node document,
                                                         ProcessingObserver observer,
                                                         CacheGenerationStamp stamp) {
        if (document == null) {
            return null;
        }
        long start = System.nanoTime();
        try {
            FrozenNode.ResolvedStructuralKey selectedKey = selectedStructuralKey(document);
            if (selectedKey == null) {
                recordObservation(
                        observer,
                        ProcessingMetricId.PROCESSING_SNAPSHOT_CACHE_MISSES,
                        1L);
                return null;
            }
            ResolvedSnapshot cached = recentProcessingSnapshot(selectedKey, stamp);
            if (cached != null) {
                recordObservation(
                        observer,
                        ProcessingMetricId.PROCESSING_SNAPSHOT_CACHE_HITS,
                        1L);
                return cached;
            }
            recordObservation(
                    observer,
                    ProcessingMetricId.PROCESSING_SNAPSHOT_CACHE_MISSES,
                    1L);
            return null;
        } finally {
            recordObservation(
                    observer,
                    ProcessingMetricId.PROCESSING_SNAPSHOT_CACHE_LOOKUP_NANOS,
                    System.nanoTime() - start);
        }
    }

    private FrozenNode.ResolvedStructuralKey selectedStructuralKey(Node document) {
        try {
            return FrozenNode.fromResolvedNode(document).resolvedStructuralKey();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ResolvedSnapshot recentProcessingSnapshot(
            FrozenNode.ResolvedStructuralKey selectedKey,
            CacheGenerationStamp stamp) {
        synchronized (lifecycleLock) {
            return isCurrentCacheStampLocked(stamp)
                    ? recentProcessingDocumentSnapshots.get(selectedKey)
                    : null;
        }
    }

    private void rememberProcessingSnapshot(Node document,
                                            ResolvedSnapshot snapshot,
                                            CacheGenerationStamp stamp) {
        if (snapshot == null || !snapshot.isResolutionComplete()) {
            return;
        }
        FrozenNode.ResolvedStructuralKey selectedKey = selectedStructuralKey(document);
        if (selectedKey == null) {
            return;
        }
        CacheMutationMetrics mutation;
        ProcessingObserver observer;
        synchronized (lifecycleLock) {
            if (!isCurrentCacheStampLocked(stamp)) {
                return;
            }
            long evictionsBefore = recentProcessingDocumentSnapshots.evictions();
            long oversizedBefore = recentProcessingDocumentSnapshots.oversizedRejections();
            recentProcessingDocumentSnapshots.put(selectedKey, snapshot);
            mutation = captureCacheMutation(RECENT_PROCESSING_CACHE,
                    recentProcessingDocumentSnapshots,
                    evictionsBefore,
                    oversizedBefore);
            observer = processingObserver();
        }
        mutation.emit(observer);
    }

    /** Swaps the processor while holding lifecycleLock and returns only owned state to close. */
    private DocumentProcessor refreshDocumentProcessorConformanceEngine() {
        if (documentProcessor != null) {
            DocumentProcessor previous = documentProcessor;
            boolean previousOwned = documentProcessorOwned;
            Object ownerToken = processorOwnerToken;
            NodeProvider capturedPreprocessingProvider = nodeProvider;
            NodeProvider capturedSnapshotProvider = processorSnapshotNodeProvider();
            MergingProcessor capturedMergingProcessor = mergingProcessor;
            Map<String, String> capturedAliases = Collections.unmodifiableMap(
                    new HashMap<>(preprocessingAliases));
            ResolutionLimits capturedLimits = globalLimits;
            documentProcessor = DocumentProcessor.Builder.from(previous)
                    .withConformanceEngine(processorConformanceEngine(
                            capturedSnapshotProvider, capturedMergingProcessor))
                    .withSnapshotManager(new BlueProcessingSnapshotManager(
                            ownerToken,
                            capturedPreprocessingProvider,
                            capturedSnapshotProvider,
                            capturedMergingProcessor,
                            capturedAliases,
                            capturedLimits,
                            null,
                            null))
                    .withMatchingService(new ContractMatchingService(this))
                    .build();
            documentProcessorOwned = true;
            return previousOwned ? previous : null;
        }
        return null;
    }

    private ConfigurationRefresh refreshRuntimeConfiguration(
            Runnable mutation,
            boolean replaceBorrowedProcessor) {
        synchronized (lifecycleLock) {
            beginCacheInvalidation();
            try {
                mutation.run();
                processorOwnerToken = new Object();
                clearReloadableRuntimeCaches();
                DocumentProcessor processorToClose = documentProcessor != null
                        && (documentProcessorOwned || replaceBorrowedProcessor)
                        ? refreshDocumentProcessorConformanceEngine()
                        : null;
                return new ConfigurationRefresh(
                        processorToClose, processingObserver(), captureCacheGauges());
            } finally {
                endCacheInvalidation();
            }
        }
    }

    /**
     * Processor-facing snapshot boundary captured from one exact
     * {@link Blue} runtime configuration generation.
     *
     * <p>Ordinary instances borrow the facade's shared verified-reference
     * cache and use an owner/generation stamp to reject stale work after
     * reconfiguration. Sequence instances own an isolated transient child
     * cache: callers may fork or retain that state during planning, but must
     * eventually invoke {@link #releaseTransientState()}. Captured providers,
     * merge behavior, aliases, and limits never drift to a newer facade
     * configuration mid-operation.</p>
     */
    private final class BlueProcessingSnapshotManager
            implements ProcessingSnapshotManager {
        private final Object ownerToken;
        private final NodeProvider preprocessingNodeProvider;
        private final NodeProvider snapshotNodeProvider;
        private final MergingProcessor snapshotMergingProcessor;
        private final Map<String, String> aliases;
        private final ResolutionLimits limits;
        private final ResolvedReferenceCache sequenceReferenceCache;
        private final CacheGenerationStamp fixedStamp;
        private final ThreadLocal<CacheGenerationStamp> directOperationStamp = new ThreadLocal<>();

        private BlueProcessingSnapshotManager(Object ownerToken,
                                              NodeProvider preprocessingNodeProvider,
                                              NodeProvider snapshotNodeProvider,
                                              MergingProcessor snapshotMergingProcessor,
                                              Map<String, String> aliases,
                                              ResolutionLimits limits,
                                              ResolvedReferenceCache sequenceReferenceCache,
                                              CacheGenerationStamp fixedStamp) {
            this.ownerToken = ownerToken;
            this.preprocessingNodeProvider = preprocessingNodeProvider;
            this.snapshotNodeProvider = snapshotNodeProvider;
            this.snapshotMergingProcessor = snapshotMergingProcessor;
            this.aliases = aliases;
            this.limits = limits;
            this.sequenceReferenceCache = sequenceReferenceCache;
            this.fixedStamp = fixedStamp;
        }

        private CacheGenerationStamp operationStamp() {
            if (fixedStamp != null) {
                return fixedStamp;
            }
            CacheGenerationStamp active = activeProcessingCacheStamp.get();
            if (active != null) {
                return active.ownerToken == ownerToken
                        ? active
                        : CacheGenerationStamp.invalid(ownerToken);
            }
            CacheGenerationStamp local = directOperationStamp.get();
            if (local == null || !isCurrentCacheStamp(local)) {
                local = currentCacheStamp(ownerToken);
                directOperationStamp.set(local);
            }
            return local;
        }

        private ProcessingObserver processingObserver() {
            synchronized (lifecycleLock) {
                return processorOwnerToken == ownerToken && documentProcessor != null
                        ? documentProcessor.processingObserver()
                        : NoOpProcessingObserver.INSTANCE;
            }
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            CacheGenerationStamp stamp = operationStamp();
            ResolvedSnapshot cached = cachedProcessingSnapshotFor(
                    document, processingObserver(), stamp);
            if (cached != null) {
                return cached;
            }
            if (sequenceReferenceCache != null) {
                return resolveProcessingSnapshot(document,
                        sequenceReferenceCache,
                        preprocessingNodeProvider,
                        aliases,
                        snapshotNodeProvider,
                        snapshotMergingProcessor,
                        limits);
            }
            ResolvedReferenceCache oneShot = resolvedReferenceCache.transientChild();
            try {
                ResolvedSnapshot resolved = resolveProcessingSnapshot(document,
                        oneShot,
                        preprocessingNodeProvider,
                        aliases,
                        snapshotNodeProvider,
                        snapshotMergingProcessor,
                        limits);
                return publishProcessingSnapshot(resolved, oneShot, stamp);
            } finally {
                oneShot.close();
            }
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            CacheGenerationStamp stamp = operationStamp();
            ResolvedSnapshot cached = cachedProcessingSnapshotFor(
                    document, processingObserver(), stamp);
            if (cached != null) {
                return cached;
            }
            if (sequenceReferenceCache != null) {
                return resolveProcessingSnapshot(document,
                        sequenceReferenceCache,
                        preprocessingNodeProvider,
                        aliases,
                        snapshotNodeProvider,
                        snapshotMergingProcessor,
                        limits);
            }
            ResolvedReferenceCache oneShot = resolvedReferenceCache.transientChild();
            try {
                return resolveProcessingSnapshot(document,
                        oneShot,
                        preprocessingNodeProvider,
                        aliases,
                        snapshotNodeProvider,
                        snapshotMergingProcessor,
                        limits);
            } finally {
                oneShot.close();
            }
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            if (preservedPaths == null || preservedPaths.isEmpty()) {
                return fromDocument(document);
            }
            operationStamp();
            if (sequenceReferenceCache != null) {
                return resolveProcessingSnapshot(
                        document,
                        sequenceReferenceCache,
                        preprocessingNodeProvider,
                        aliases,
                        snapshotNodeProvider,
                        snapshotMergingProcessor,
                        limits,
                        preservedPaths);
            }
            ResolvedReferenceCache oneShot =
                    resolvedReferenceCache.transientChild();
            try {
                return resolveProcessingSnapshot(
                        document,
                        oneShot,
                        preprocessingNodeProvider,
                        aliases,
                        snapshotNodeProvider,
                        snapshotMergingProcessor,
                        limits,
                        preservedPaths);
            } finally {
                oneShot.close();
            }
        }

        @Override
        public ResolvedSnapshot fromDocumentTransientPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            if (preservedPaths == null || preservedPaths.isEmpty()) {
                return fromDocumentTransient(document);
            }
            return fromDocumentPreservingPaths(
                    document, preservedPaths);
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            FrozenNode checked =
                    Objects.requireNonNull(
                            reference, "reference");
            if (!checked.isReferenceOnly()) {
                return checked;
            }
            operationStamp();
            String blueId =
                    checked.getReferenceBlueId();
            ResolvedReferenceCache activeCache =
                    sequenceReferenceCache != null
                            ? sequenceReferenceCache
                            : resolvedReferenceCache;
            FrozenNode cached =
                    activeCache
                            .getVerifiedCanonical(
                                    blueId)
                            .orElse(null);
            if (cached != null) {
                return cached;
            }
            NodeProviderResult providerResult =
                    snapshotNodeProvider
                            .fetchResultByBlueId(blueId);
            if (providerResult.outcome()
                    == NodeProviderOutcome.NOT_FOUND) {
                return null;
            }
            if (providerResult.outcome()
                    == NodeProviderOutcome.UNAVAILABLE) {
                throw new ExecutionEvidenceUnavailableException(
                        providerResult.diagnostic().orElse(
                                "Exact provider content is unavailable for "
                                        + blueId),
                        Collections.singleton(blueId));
            }
            if (providerResult.outcome()
                    == NodeProviderOutcome.INVALID_EVIDENCE) {
                throw new InvalidExecutionEvidenceException(
                        providerResult.diagnostic().orElse(
                                "Provider returned invalid exact evidence for "
                                        + blueId));
            }
            List<Node> nodes = providerResult.nodes();
            Node canonical =
                    nodes.size() == 1
                            ? providerContentWithoutRootIdentity(
                                    nodes.get(0))
                            : new Node().items(
                                    providerContentWithoutRootIdentity(
                                            nodes));
            FrozenNode exact =
                    FrozenNode.fromNode(canonical);
            if (BlueIds.hasCyclicMemberSeparator(blueId)) {
                /*
                 * snapshotNodeProvider has already required the delegate's
                 * complete cyclic-set proof for this member identity.
                 * A member has no independently hashable ordinary BlueId, so
                 * it must not enter the canonical cache keyed by MASTER#index
                 * and must never be checked by hashing the member alone.
                 */
                return exact;
            }
            if (!blueId.equals(exact.blueId())) {
                throw new IllegalArgumentException(
                        "Provider content BlueId mismatch for "
                                + blueId);
            }
            return activeCache.putVerifiedCanonical(
                    blueId, exact);
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            if (sequenceReferenceCache != null) {
                return new BlueProcessingSnapshotManager(
                        ownerToken,
                        preprocessingNodeProvider,
                        snapshotNodeProvider,
                        snapshotMergingProcessor,
                        aliases,
                        limits,
                        sequenceReferenceCache.transientChild(),
                        fixedStamp);
            }
            synchronized (lifecycleLock) {
                CacheGenerationStamp active = activeProcessingCacheStamp.get();
                if (active == null) {
                    awaitCacheInvalidation();
                }
                ensureOpen();
                Object currentOwnerToken = processorOwnerToken;
                CacheGenerationStamp currentStamp = active != null
                        && active.ownerToken == currentOwnerToken
                        ? active
                        : new CacheGenerationStamp(currentOwnerToken, runtimeCacheGeneration);
                return new BlueProcessingSnapshotManager(
                        currentOwnerToken,
                        nodeProvider,
                        processorSnapshotNodeProvider(),
                        mergingProcessor,
                        Collections.unmodifiableMap(new HashMap<>(preprocessingAliases)),
                        globalLimits,
                        resolvedReferenceCache.transientChild(),
                        currentStamp);
            }
        }

        @Override
        public ProcessingSnapshotManager forkTransientSequence() {
            if (sequenceReferenceCache == null) {
                return transientSequence();
            }
            return new BlueProcessingSnapshotManager(
                    ownerToken,
                    preprocessingNodeProvider,
                    snapshotNodeProvider,
                    snapshotMergingProcessor,
                    aliases,
                    limits,
                    sequenceReferenceCache.forkTransient(),
                    fixedStamp);
        }

        @Override
        public void retainTransientState(FrozenNode canonicalRoot, FrozenNode resolvedRoot) {
            if (sequenceReferenceCache != null) {
                sequenceReferenceCache.retainOnlyReachableFrom(canonicalRoot, resolvedRoot);
            }
        }

        @Override
        public void releaseTransientState() {
            if (sequenceReferenceCache != null) {
                sequenceReferenceCache.close();
            }
        }

        @Override
        public boolean isTransientStateCurrent() {
            return isCurrentCacheStamp(operationStamp())
                    && (sequenceReferenceCache == null
                    || sequenceReferenceCache.isCurrentGeneration());
        }

        @Override
        public boolean supportsIncrementalValueResolution() {
            return snapshotMergingProcessor instanceof IncrementalMergingProcessorCapability
                    && ((IncrementalMergingProcessorCapability) snapshotMergingProcessor)
                    .supportsIncrementalValueResolution();
        }

        @Override
        public boolean supportsIncrementalValueResolution(IncrementalValueResolutionRequest request) {
            return snapshotMergingProcessor instanceof IncrementalMergingProcessorCapability
                    && ((IncrementalMergingProcessorCapability) snapshotMergingProcessor)
                    .supportsIncrementalValueResolution(request);
        }

        @Override
        public ConformanceEngine transientConformanceEngine(ConformanceEngine conformanceEngine) {
            if (conformanceEngine == null) {
                return null;
            }
            synchronized (managedProcessorConformanceEngines) {
                if (managedProcessorConformanceEngines.contains(conformanceEngine)) {
                    return new ConformanceEngine(
                            snapshotNodeProvider,
                            snapshotMergingProcessor,
                            sequenceReferenceCache != null
                                    ? sequenceReferenceCache
                                    : resolvedReferenceCache);
                }
            }
            return sequenceReferenceCache != null
                    ? conformanceEngine.transientView(sequenceReferenceCache)
                    : conformanceEngine.transientView();
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            operationStamp();
            if (sequenceReferenceCache != null) {
                return applyProcessingCanonicalPatch(snapshot,
                        patch,
                        snapshotNodeProvider,
                        snapshotMergingProcessor,
                        limits,
                        sequenceReferenceCache);
            }
            ResolvedReferenceCache oneShot = resolvedReferenceCache.transientChild();
            try {
                return applyProcessingCanonicalPatch(snapshot,
                        patch,
                        snapshotNodeProvider,
                        snapshotMergingProcessor,
                        limits,
                        oneShot);
            } finally {
                oneShot.close();
            }
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            return publishProcessingSnapshot(
                    snapshot, sequenceReferenceCache, operationStamp());
        }
    }

    private ResolvedSnapshot resolveProcessingSnapshot(
            Node node,
            ResolvedReferenceCache resolutionCache,
            NodeProvider preprocessingNodeProvider,
            Map<String, String> aliases,
            NodeProvider snapshotNodeProvider,
            MergingProcessor snapshotMergingProcessor,
            ResolutionLimits limits) {
        Node preprocessed = preprocess(node.clone(), preprocessingNodeProvider, aliases);
        Node resolved = languageMerger(snapshotMergingProcessor,
                snapshotNodeProvider,
                resolutionCache)
                .resolve(preprocessed.clone(), limits);
        FrozenNode canonicalRoot = FrozenNode.fromNode(
                new CanonicalIdentityInputBuilder().build(
                        resolved.clone(), preprocessed));
        FrozenNode resolvedRoot = resolutionCache.freezeResolved(resolved);
        return new ResolvedSnapshot(canonicalRoot, resolvedRoot, canonicalRoot.blueId());
    }

    private ResolvedSnapshot resolveProcessingSnapshot(
            Node node,
            ResolvedReferenceCache resolutionCache,
            NodeProvider preprocessingNodeProvider,
            Map<String, String> aliases,
            NodeProvider snapshotNodeProvider,
            MergingProcessor snapshotMergingProcessor,
            ResolutionLimits limits,
            Collection<String> preservedPaths) {
        Set<String> canonicalPaths =
                canonicalPreservedPaths(preservedPaths);
        if (canonicalPaths.isEmpty()) {
            return resolveProcessingSnapshot(
                    node,
                    resolutionCache,
                    preprocessingNodeProvider,
                    aliases,
                    snapshotNodeProvider,
                    snapshotMergingProcessor,
                    limits);
        }
        Node preprocessed = preprocess(
                node.clone(), preprocessingNodeProvider, aliases);
        ResolutionLimits preservingLimits = ResolutionLimits.allOf(
                limits,
                ResolutionLimits.deferringReferencesAt(
                        canonicalPaths));
        Node resolved = languageMerger(
                snapshotMergingProcessor,
                snapshotNodeProvider,
                resolutionCache)
                .resolve(preprocessed.clone(), preservingLimits);
        restorePreservedPaths(
                resolved, preprocessed, canonicalPaths);
        FrozenNode canonicalRoot = FrozenNode.fromNode(
                new CanonicalIdentityInputBuilder().build(
                        resolved.clone(), preprocessed));
        FrozenNode resolvedRoot =
                resolutionCache.freezeResolved(resolved);
        return ResolvedSnapshot.withDeferredResolution(
                canonicalRoot,
                resolvedRoot);
    }

    private ResolvedSnapshot applyProcessingCanonicalPatch(
            ResolvedSnapshot snapshot,
            JsonPatch patch,
            NodeProvider snapshotNodeProvider,
            MergingProcessor snapshotMergingProcessor,
            ResolutionLimits limits,
            ResolvedReferenceCache resolutionCache) {
        return applyCanonicalPatch(snapshot, patch,
                canonicalRoot -> snapshotFromCanonical(
                        canonicalRoot,
                        snapshotNodeProvider,
                        snapshotMergingProcessor,
                        limits,
                        resolutionCache));
    }

    private ResolvedSnapshot applyCanonicalPatch(
            ResolvedSnapshot snapshot,
            JsonPatch patch,
            Function<FrozenNode, ResolvedSnapshot> snapshotResolver) {
        CanonicalPatchResult patched = new CanonicalOverlayPatchEngine(
                snapshot.frozenCanonicalRoot()).apply(patch);
        ResolvedSnapshot patchedSnapshot = snapshotResolver.apply(patched.root());
        if (!canMinimizePatchedOverride(patch)) {
            return patchedSnapshot;
        }

        CanonicalPatchResult withoutOverride;
        try {
            withoutOverride = new CanonicalOverlayPatchEngine(patched.root()).apply(JsonPatch.remove(patched.path()));
        } catch (RuntimeException ignored) {
            return patchedSnapshot;
        }

        ResolvedSnapshot inheritedSnapshot = snapshotResolver.apply(withoutOverride.root());
        FrozenNode patchedEffective = patchedSnapshot.resolvedAt(patched.path());
        FrozenNode inheritedEffective = inheritedSnapshot.resolvedAt(patched.path());
        if (patchedEffective != null
                && inheritedEffective != null
                && patchedEffective.blueId().equals(inheritedEffective.blueId())) {
            return inheritedSnapshot;
        }
        return patchedSnapshot;
    }

    private ResolvedSnapshot snapshotFromVerifiedCanonical(FrozenNode canonicalRoot) {
        ResolvedSnapshot cached = cachedSnapshotByCanonical(
                canonicalRoot.resolvedStructuralKey());
        if (cached != null && cached.verifiedReferenceResolution() != null) {
            return cached;
        }
        Merger merger = languageMerger(
                mergingProcessor, nodeProvider, resolvedReferenceCache);
        return cacheSnapshot(ResolvedSnapshot.fromResolverResult(
                merger.resolveSnapshot(canonicalRoot, combineWithGlobalLimits(NO_LIMITS))));
    }

    private ResolvedSnapshot snapshotFromCanonical(FrozenNode canonicalRoot,
                                                   NodeProvider snapshotNodeProvider) {
        ResolvedSnapshot cached = cachedSnapshotByCanonical(
                canonicalRoot.resolvedStructuralKey());
        if (cached != null) {
            return cached;
        }
        Merger merger = languageMerger(
                mergingProcessor, snapshotNodeProvider,
                resolvedReferenceCache);
        Node canonical = canonicalRoot.toNode();
        Node resolved = merger.resolve(canonical.clone(), combineWithGlobalLimits(NO_LIMITS));
        return snapshotFromResolved(canonical, resolved, canonicalRoot);
    }

    private ResolvedSnapshot snapshotFromCanonical(
            FrozenNode canonicalRoot,
            NodeProvider snapshotNodeProvider,
            MergingProcessor snapshotMergingProcessor,
            ResolutionLimits limits,
            ResolvedReferenceCache resolutionCache) {
        Merger merger = languageMerger(
                snapshotMergingProcessor, snapshotNodeProvider, resolutionCache);
        Node canonical = canonicalRoot.toNode();
        Node resolved = merger.resolve(canonical.clone(), limits);
        FrozenNode resolvedRoot = resolutionCache.freezeResolved(resolved);
        return new ResolvedSnapshot(canonicalRoot, resolvedRoot, canonicalRoot.blueId());
    }

    private ResolvedSnapshot snapshotFromResolved(Node preprocessedSource,
                                                  Node resolved,
                                                  FrozenNode authoritativeCanonicalRoot) {
        return snapshotFromResolved(preprocessedSource, resolved, authoritativeCanonicalRoot, true);
    }

    private ResolvedSnapshot snapshotFromResolved(Node preprocessedSource,
                                                  Node resolved,
                                                  FrozenNode authoritativeCanonicalRoot,
                                                  boolean publish) {
        return snapshotFromResolved(preprocessedSource,
                resolved,
                authoritativeCanonicalRoot,
                publish,
                resolvedReferenceCache);
    }

    private ResolvedSnapshot snapshotFromResolved(Node preprocessedSource,
                                                  Node resolved,
                                                  FrozenNode authoritativeCanonicalRoot,
                                                  boolean publish,
                                                  ResolvedReferenceCache resolutionCache) {
        FrozenNode canonicalRoot = authoritativeCanonicalRoot;
        if (canonicalRoot == null) {
            Node canonical = new CanonicalIdentityInputBuilder().build(
                    resolved.clone(), preprocessedSource);
            canonicalRoot = FrozenNode.fromNode(canonical);
        }
        FrozenNode resolvedRoot = publish
                ? resolvedReferenceCache.freezeResolved(resolved)
                : resolutionCache.freezeResolved(resolved);
        ResolvedSnapshot snapshot = new ResolvedSnapshot(
                canonicalRoot,
                resolvedRoot,
                canonicalRoot.blueId());
        return publish ? cacheSnapshot(snapshot) : snapshot;
    }

    private Set<String> processorContractPaths(Node root) {
        Set<String> paths = new LinkedHashSet<>();
        collectProcessorContractPaths(root, new ArrayList<>(), paths);
        return paths;
    }

    private void collectProcessorContractPaths(Node node, List<String> path, Set<String> paths) {
        if (node == null) {
            return;
        }
        if (node.getContracts() != null) {
            List<String> contractsPath = new ArrayList<>(path);
            contractsPath.add(BlueLanguageConstants.OBJECT_CONTRACTS);
            paths.add(JsonPointer.toPointer(contractsPath));
            collectProcessorContractPaths(node.getContracts(), contractsPath, paths);
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
                path.add(entry.getKey());
                collectProcessorContractPaths(entry.getValue(), path, paths);
                path.remove(path.size() - 1);
            }
        }
        if (node.getItems() != null) {
            for (int i = 0; i < node.getItems().size(); i++) {
                path.add(String.valueOf(i));
                collectProcessorContractPaths(node.getItems().get(i), path, paths);
                path.remove(path.size() - 1);
            }
        }
    }

    private void restorePreservedPaths(Node resolved, Node source, Set<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        for (String path : paths) {
            Node preserved = NodePathEditor.getOrNull(source, path);
            if (preserved != null) {
                NodePathEditor.put(resolved, path, preserved.clone());
            }
        }
    }

    private boolean canMinimizePatchedOverride(JsonPatch patch) {
        if (patch == null || patch.getOp() == JsonPatch.Op.REMOVE) {
            return false;
        }
        String path = patch.getPath();
        if (path == null || path.isEmpty()
                || JsonPointer.ROOT.equals(path)) {
            return false;
        }
        List<String> segments = JsonPointer.split(path);
        for (String segment : segments) {
            if (JsonPointer.isArrayIndexSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    private Set<String> canonicalPreservedPaths(Collection<String> preservedPaths) {
        if (preservedPaths == null || preservedPaths.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> canonicalPaths = new HashSet<>();
        for (String preservedPath : preservedPaths) {
            canonicalPaths.add(JsonPointer.canonicalize(preservedPath));
        }
        return canonicalPaths;
    }

    private NodeProvider processorSnapshotNodeProvider() {
        return new SequentialNodeProvider(
                BootstrapProvider.INSTANCE,
                BlueRuntimeTypeRegistry.getDefault().asProcessorSnapshotProvider(),
                registeredExtensionTypeProvider(),
                new PotentialBlueIdNodeProvider(nodeProvider));
    }

    private NodeProvider registeredExtensionTypeProvider() {
        return blueId -> {
            if (!BlueIds.isPotentialBlueId(blueId)
                    || BlueRuntimeTypeRegistry.getDefault().isProcessorManagedTypeBlueId(blueId)) {
                return null;
            }
            Node typeNode = externalContractTypeNodes.get(blueId);
            return typeNode != null ? Collections.singletonList(typeNode.clone()) : null;
        };
    }

    private Node validatedExternalTypeNode(String blueId, Node canonicalTypeNode) {
        if (blueId == null || blueId.isEmpty()) {
            throw new IllegalArgumentException("blueId must not be empty");
        }
        Objects.requireNonNull(canonicalTypeNode, "canonicalTypeNode");
        Node canonical = canonicalTypeNode.clone();
        String calculated = DirectBlueIdCalculator.calculateBlueId(canonical);
        if (!blueId.equals(calculated)) {
            throw new IllegalArgumentException("External contract type node hashes to " + calculated
                    + ", not declared BlueId " + blueId);
        }
        return canonical;
    }

    private ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
        if (snapshot != null && !snapshot.isResolutionComplete()) {
            return snapshot;
        }
        ResolvedSnapshot publishable = publishableCacheSnapshot(snapshot);
        CacheSnapshotPublication publication;
        synchronized (lifecycleLock) {
            ensureOpen();
            publication = cacheSnapshotLocked(publishable);
        }
        publication.emit();
        return publication.result;
    }

    /** Caller holds lifecycleLock, which linearizes publication with invalidation. */
    private CacheSnapshotPublication cacheSnapshotLocked(ResolvedSnapshot snapshot) {
        if (!snapshot.isResolutionComplete()) {
            throw new IllegalArgumentException(
                    "Deferred-resolution snapshots cannot enter shared resolved snapshot caches");
        }
        snapshot = publishableCacheSnapshot(snapshot);
        if (snapshot.verifiedReferenceResolution() != null) {
            resolvedReferenceCache.putVerifiedResolved(snapshot.verifiedReferenceResolution());
        }
        resolvedReferenceCache.rememberResolvedGraph(snapshot.frozenResolvedRoot());
        FrozenNode.ResolvedStructuralKey key =
                snapshot.frozenCanonicalRoot().resolvedStructuralKey();

        ResolvedSnapshot result;
        boolean promoteVerifiedEvidenceToPinned = false;
        CacheMutationMetrics derivedMutation = null;
        CacheMutationMetrics aliasMutation = null;
        CacheGaugeSnapshot gauges = null;
        ResolvedSnapshot pinned = pinnedSnapshotsByCanonicalRepresentation.get(key);
        if (pinned != null) {
            ResolvedSnapshot selected = preferVerified(pinned, snapshot);
            if (selected != pinned) {
                replacePinnedSnapshot(key, pinned, selected);
                gauges = captureCacheGauges();
            }
            promoteVerifiedEvidenceToPinned = selected.verifiedReferenceResolution() != null;
            result = selected;
        } else {
            ResolvedSnapshot existing = derivedSnapshotsByCanonicalRepresentation.peek(key);
            ResolvedSnapshot selected = existing != null
                    ? preferVerified(existing, snapshot)
                    : snapshot;
            long evictionsBefore = derivedSnapshotsByCanonicalRepresentation.evictions();
            long oversizedBefore = derivedSnapshotsByCanonicalRepresentation.oversizedRejections();
            derivedSnapshotsByCanonicalRepresentation.put(key, selected);
            ResolvedSnapshot retained = derivedSnapshotsByCanonicalRepresentation.peek(key);
            derivedMutation = captureCacheMutation(DERIVED_SNAPSHOT_CACHE,
                    derivedSnapshotsByCanonicalRepresentation,
                    evictionsBefore,
                    oversizedBefore);
            if (retained != null && retained.verifiedReferenceResolution() != null) {
                aliasMutation = putDerivedBlueIdAlias(retained);
            }
            result = retained != null ? retained : selected;
        }
        if (promoteVerifiedEvidenceToPinned && result.verifiedReferenceResolution() != null) {
            resolvedReferenceCache.putPinnedVerifiedResolved(
                    result.verifiedReferenceResolution());
        }
        return new CacheSnapshotPublication(result,
                processingObserver(),
                derivedMutation,
                aliasMutation,
                gauges);
    }

    private ResolvedSnapshot publishProcessingSnapshot(
            ResolvedSnapshot snapshot,
            ResolvedReferenceCache transientReferenceCache,
            CacheGenerationStamp stamp) {
        if (snapshot == null || !snapshot.isResolutionComplete()) {
            return snapshot;
        }
        CacheSnapshotPublication publication;
        synchronized (lifecycleLock) {
            if (!isCurrentCacheStampLocked(stamp)
                    || transientReferenceCache != null
                    && !transientReferenceCache.isCurrentGeneration()) {
                return snapshot;
            }
            snapshot = publishableCacheSnapshot(snapshot, processingObserver());
            if (transientReferenceCache != null) {
                transientReferenceCache.promoteReferencesReachableFrom(
                        snapshot.frozenCanonicalRoot());
            }
            publication = cacheSnapshotLocked(snapshot);
        }
        publication.emit();
        return publication.result;
    }

    private void pinSnapshot(ResolvedSnapshot snapshot) {
        if (snapshot == null || !snapshot.isResolutionComplete()) {
            throw new IllegalArgumentException(
                    "Deferred-resolution snapshots cannot be pinned as complete resolved snapshots");
        }
        snapshot = publishableCacheSnapshot(snapshot);
        ensureOpen();
        if (snapshot.verifiedReferenceResolution() != null) {
            resolvedReferenceCache.putPinnedVerifiedResolved(snapshot.verifiedReferenceResolution());
        }
        resolvedReferenceCache.rememberResolvedGraph(snapshot.frozenResolvedRoot());
        FrozenNode.ResolvedStructuralKey key =
                snapshot.frozenCanonicalRoot().resolvedStructuralKey();
        ResolvedSnapshot selected;
        CacheGaugeSnapshot gauges;
        ProcessingObserver observer;
        synchronized (lifecycleLock) {
            ensureOpen();
            ResolvedSnapshot pinned = pinnedSnapshotsByCanonicalRepresentation.get(key);
            ResolvedSnapshot derived = derivedSnapshotsByCanonicalRepresentation.peek(key);
            selected = preferVerified(
                    pinned != null ? pinned : derived,
                    snapshot);
            if (pinned == null) {
                pinnedSnapshotsByCanonicalRepresentation.put(key, selected);
                pinnedSnapshotWeightBytes = saturatedAdd(
                        pinnedSnapshotWeightBytes,
                        approximateSnapshotWeightBytes(selected));
            } else if (selected != pinned) {
                replacePinnedSnapshot(key, pinned, selected);
            }
            pinnedSnapshotHighWaterBytes = Math.max(
                    pinnedSnapshotHighWaterBytes,
                    pinnedSnapshotWeightBytes);
            derivedSnapshotsByCanonicalRepresentation.remove(key);
            if (selected.verifiedReferenceResolution() != null) {
                pinnedSnapshotsByBlueId.put(selected.blueId(), selected);
                derivedSnapshotsByBlueId.remove(selected.blueId());
            }
            gauges = captureCacheGauges();
            observer = processingObserver();
        }
        if (selected.verifiedReferenceResolution() != null) {
            resolvedReferenceCache.putPinnedVerifiedResolved(
                    selected.verifiedReferenceResolution());
        }
        gauges.emit(observer);
    }

    private ResolvedSnapshot publishableCacheSnapshot(ResolvedSnapshot snapshot) {
        return publishableCacheSnapshot(snapshot, null);
    }

    private ResolvedSnapshot publishableCacheSnapshot(
            ResolvedSnapshot snapshot,
            ProcessingObserver observer) {
        Objects.requireNonNull(snapshot, "snapshot");
        FrozenNode canonicalRoot = snapshot.frozenCanonicalRoot();
        if (canonicalRoot.isStrictCanonical()
                && canonicalRoot.isStrictBlueIdValidation()) {
            return snapshot;
        }
        if (observer != null) {
            recordObservation(
                    observer,
                    ProcessingMetricId.PROCESSOR_PUBLICATION_CANONICALIZATIONS,
                    1L);
            recordObservation(
                    observer,
                    ProcessingMetricId.PROCESSOR_PUBLICATION_CANONICAL_MATERIALIZATIONS,
                    1L);
            recordObservation(
                    observer,
                    ProcessingMetricId.PROCESSOR_PUBLICATION_STRICT_BLUE_ID_CALCULATIONS,
                    1L);
            long canonicalizationStart = System.nanoTime();
            try {
                return snapshot.toStrictBlueIdValidatedCanonical();
            } finally {
                recordObservation(
                        observer,
                        ProcessingMetricId.PROCESSOR_PUBLICATION_CANONICALIZATION_NANOS,
                        Math.max(1L, System.nanoTime() - canonicalizationStart));
            }
        }
        return snapshot.toStrictBlueIdValidatedCanonical();
    }

    private void replacePinnedSnapshot(FrozenNode.ResolvedStructuralKey key,
                                       ResolvedSnapshot previous,
                                       ResolvedSnapshot replacement) {
        pinnedSnapshotsByCanonicalRepresentation.put(key, replacement);
        pinnedSnapshotWeightBytes = Math.max(0L,
                pinnedSnapshotWeightBytes - approximateSnapshotWeightBytes(previous));
        pinnedSnapshotWeightBytes = saturatedAdd(
                pinnedSnapshotWeightBytes,
                approximateSnapshotWeightBytes(replacement));
        pinnedSnapshotHighWaterBytes = Math.max(
                pinnedSnapshotHighWaterBytes,
                pinnedSnapshotWeightBytes);
        if (replacement.verifiedReferenceResolution() != null) {
            pinnedSnapshotsByBlueId.put(replacement.blueId(), replacement);
        }
    }

    private ResolvedSnapshot preferVerified(ResolvedSnapshot existing,
                                            ResolvedSnapshot candidate) {
        if (existing == null) {
            return candidate;
        }
        return existing.verifiedReferenceResolution() == null
                && candidate.verifiedReferenceResolution() != null
                ? candidate
                : existing;
    }

    private ResolvedSnapshot cachedSnapshotByCanonical(
            FrozenNode.ResolvedStructuralKey key) {
        ensureOpen();
        ResolvedSnapshot pinned = pinnedSnapshotsByCanonicalRepresentation.get(key);
        if (pinned != null) {
            recordCacheObservation(
                    processingObserver(),
                    ProcessingMetricId.CACHE_HITS,
                    PINNED_SNAPSHOT_CACHE,
                    1L);
            return pinned;
        }
        ResolvedSnapshot derived = derivedSnapshotsByCanonicalRepresentation.get(key);
        if (derived != null) {
            recordCacheObservation(
                    processingObserver(),
                    ProcessingMetricId.CACHE_HITS,
                    DERIVED_SNAPSHOT_CACHE,
                    1L);
        } else {
            recordCacheObservation(
                    processingObserver(),
                    ProcessingMetricId.CACHE_MISSES,
                    DERIVED_SNAPSHOT_CACHE,
                    1L);
        }
        return derived;
    }

    private ResolvedSnapshot cachedSnapshotByBlueId(String blueId) {
        ensureOpen();
        ResolvedSnapshot pinned = pinnedSnapshotsByBlueId.get(blueId);
        if (pinned != null) {
            recordCacheObservation(
                    processingObserver(),
                    ProcessingMetricId.CACHE_HITS,
                    PINNED_SNAPSHOT_CACHE,
                    1L);
            return pinned;
        }
        WeakReference<ResolvedSnapshot> reference = derivedSnapshotsByBlueId.get(blueId);
        ResolvedSnapshot derived = reference != null ? reference.get() : null;
        if (derived == null) {
            if (reference != null) {
                derivedSnapshotsByBlueId.remove(blueId);
            }
            recordCacheObservation(
                    processingObserver(),
                    ProcessingMetricId.CACHE_MISSES,
                    CANONICAL_ALIAS_CACHE,
                    1L);
        } else {
            recordCacheObservation(
                    processingObserver(),
                    ProcessingMetricId.CACHE_HITS,
                    CANONICAL_ALIAS_CACHE,
                    1L);
        }
        return derived;
    }

    private CacheMutationMetrics putDerivedBlueIdAlias(ResolvedSnapshot snapshot) {
        long evictionsBefore = derivedSnapshotsByBlueId.evictions();
        long oversizedBefore = derivedSnapshotsByBlueId.oversizedRejections();
        derivedSnapshotsByBlueId.put(snapshot.blueId(), new WeakReference<>(snapshot));
        return captureCacheMutation(CANONICAL_ALIAS_CACHE,
                derivedSnapshotsByBlueId,
                evictionsBefore,
                oversizedBefore);
    }

    private <K, V> CacheMutationMetrics captureCacheMutation(
            String cacheName,
            WeightedLruCache<K, V> cache,
            long evictionsBefore,
            long oversizedBefore) {
        return new CacheMutationMetrics(
                cacheName,
                cache.evictions() - evictionsBefore,
                cache.oversizedRejections() - oversizedBefore,
                cache.currentWeight(),
                cache.highWaterWeight(),
                cache.size());
    }

    private CacheGaugeSnapshot captureCacheGauges() {
        List<CacheGauge> gauges = new ArrayList<>();
        gauges.add(new CacheGauge(
                PINNED_SNAPSHOT_CACHE,
                pinnedSnapshotWeightBytes,
                pinnedSnapshotHighWaterBytes,
                pinnedSnapshotsByCanonicalRepresentation.size(),
                pinnedSnapshotsByCanonicalRepresentation.size(),
                -1));
        gauges.add(new CacheGauge(
                DERIVED_SNAPSHOT_CACHE,
                derivedSnapshotsByCanonicalRepresentation.currentWeight(),
                derivedSnapshotsByCanonicalRepresentation.highWaterWeight(),
                derivedSnapshotsByCanonicalRepresentation.size(),
                -1,
                derivedSnapshotsByCanonicalRepresentation.size()));
        gauges.add(new CacheGauge(
                CANONICAL_ALIAS_CACHE,
                derivedSnapshotsByBlueId.currentWeight(),
                derivedSnapshotsByBlueId.highWaterWeight(),
                derivedSnapshotsByBlueId.size(),
                -1,
                derivedSnapshotsByBlueId.size()));
        gauges.add(new CacheGauge(
                RECENT_PROCESSING_CACHE,
                recentProcessingDocumentSnapshots.currentWeight(),
                recentProcessingDocumentSnapshots.highWaterWeight(),
                recentProcessingDocumentSnapshots.size(),
                -1,
                recentProcessingDocumentSnapshots.size()));
        ResolvedReferenceCache.CacheStats reference = resolvedReferenceCache.cacheStats();
        gauges.add(new CacheGauge(
                VERIFIED_REFERENCE_CACHE,
                reference.verifiedCurrentWeightBytes(),
                reference.verifiedHighWaterWeightBytes(),
                reference.verifiedEntries(),
                reference.pinnedVerifiedEntries(),
                reference.verifiedEntries() - reference.pinnedVerifiedEntries()));
        gauges.add(new CacheGauge(
                TRANSIENT_REFERENCE_CACHE,
                reference.transientTrustedCurrentWeightBytes(),
                reference.transientTrustedHighWaterWeightBytes(),
                reference.transientTrustedEntries(),
                -1,
                -1));
        gauges.add(new CacheGauge(
                STRUCTURAL_INTERNER_CACHE,
                reference.structuralCurrentWeightBytes(),
                reference.structuralHighWaterWeightBytes(),
                reference.structuralEntries(),
                -1,
                -1));
        return new CacheGaugeSnapshot(gauges);
    }

    private static final class CacheSnapshotPublication {
        private final ResolvedSnapshot result;
        private final ProcessingObserver observer;
        private final CacheMutationMetrics derivedMutation;
        private final CacheMutationMetrics aliasMutation;
        private final CacheGaugeSnapshot gauges;

        private CacheSnapshotPublication(ResolvedSnapshot result,
                                         ProcessingObserver observer,
                                         CacheMutationMetrics derivedMutation,
                                         CacheMutationMetrics aliasMutation,
                                         CacheGaugeSnapshot gauges) {
            this.result = result;
            this.observer = observer;
            this.derivedMutation = derivedMutation;
            this.aliasMutation = aliasMutation;
            this.gauges = gauges;
        }

        private void emit() {
            if (derivedMutation != null) {
                derivedMutation.emit(observer);
            }
            if (aliasMutation != null) {
                aliasMutation.emit(observer);
            }
            if (gauges != null) {
                gauges.emit(observer);
            }
        }
    }

    private static final class CacheMutationMetrics {
        private final String cacheName;
        private final long evictionDelta;
        private final long oversizedDelta;
        private final long currentWeight;
        private final long highWaterWeight;
        private final int entries;

        private CacheMutationMetrics(String cacheName,
                                     long evictionDelta,
                                     long oversizedDelta,
                                     long currentWeight,
                                     long highWaterWeight,
                                     int entries) {
            this.cacheName = cacheName;
            this.evictionDelta = evictionDelta;
            this.oversizedDelta = oversizedDelta;
            this.currentWeight = currentWeight;
            this.highWaterWeight = highWaterWeight;
            this.entries = entries;
        }

        private void emit(ProcessingObserver observer) {
            if (evictionDelta > 0L) {
                recordCacheObservation(
                        observer,
                        ProcessingMetricId.CACHE_EVICTIONS,
                        cacheName,
                        evictionDelta);
            }
            if (oversizedDelta > 0L) {
                recordCacheObservation(
                        observer,
                        ProcessingMetricId.CACHE_OVERSIZED_REJECTIONS,
                        cacheName,
                        oversizedDelta);
            }
            recordCacheObservation(
                    observer,
                    ProcessingMetricId.CACHE_CURRENT_WEIGHT_BYTES,
                    cacheName,
                    currentWeight);
            recordCacheObservation(
                    observer,
                    ProcessingMetricId.CACHE_HIGH_WATER_BYTES,
                    cacheName,
                    highWaterWeight);
            recordCacheObservation(
                    observer,
                    ProcessingMetricId.CACHE_ENTRIES,
                    cacheName,
                    entries);
        }
    }

    private static final class CacheGaugeSnapshot {
        private final List<CacheGauge> gauges;

        private CacheGaugeSnapshot(List<CacheGauge> gauges) {
            this.gauges = gauges;
        }

        private void emit(ProcessingObserver observer) {
            for (CacheGauge gauge : gauges) {
                recordCacheObservation(
                        observer,
                        ProcessingMetricId.CACHE_CURRENT_WEIGHT_BYTES,
                        gauge.cacheName,
                        gauge.currentWeight);
                recordCacheObservation(
                        observer,
                        ProcessingMetricId.CACHE_HIGH_WATER_BYTES,
                        gauge.cacheName,
                        gauge.highWaterWeight);
                recordCacheObservation(
                        observer,
                        ProcessingMetricId.CACHE_ENTRIES,
                        gauge.cacheName,
                        gauge.entries);
                if (gauge.pinnedEntries >= 0) {
                    recordCacheObservation(
                            observer,
                            ProcessingMetricId.CACHE_PINNED_ENTRIES,
                            gauge.cacheName,
                            gauge.pinnedEntries);
                }
                if (gauge.derivedEntries >= 0) {
                    recordCacheObservation(
                            observer,
                            ProcessingMetricId.CACHE_DERIVED_ENTRIES,
                            gauge.cacheName,
                            gauge.derivedEntries);
                }
            }
        }
    }

    private static final class CacheGauge {
        private final String cacheName;
        private final long currentWeight;
        private final long highWaterWeight;
        private final int entries;
        private final int pinnedEntries;
        private final int derivedEntries;

        private CacheGauge(String cacheName,
                           long currentWeight,
                           long highWaterWeight,
                           int entries,
                           int pinnedEntries,
                           int derivedEntries) {
            this.cacheName = cacheName;
            this.currentWeight = currentWeight;
            this.highWaterWeight = highWaterWeight;
            this.entries = entries;
            this.pinnedEntries = pinnedEntries;
            this.derivedEntries = derivedEntries;
        }
    }

    private static final class CacheGenerationStamp {
        private final Object ownerToken;
        private final long generation;

        private CacheGenerationStamp(Object ownerToken, long generation) {
            this.ownerToken = ownerToken;
            this.generation = generation;
        }

        private static CacheGenerationStamp invalid(Object ownerToken) {
            return new CacheGenerationStamp(ownerToken, -1L);
        }
    }

    private static final class ProcessingOperation {
        private final DocumentProcessor processor;
        private final CacheGenerationStamp stamp;

        private ProcessingOperation(DocumentProcessor processor,
                                    CacheGenerationStamp stamp) {
            this.processor = processor;
            this.stamp = stamp;
        }
    }

    private static final class ConfigurationRefresh {
        private final DocumentProcessor processorToClose;
        private final ProcessingObserver metrics;
        private final CacheGaugeSnapshot gauges;

        private ConfigurationRefresh(DocumentProcessor processorToClose,
                                     ProcessingObserver metrics,
                                     CacheGaugeSnapshot gauges) {
            this.processorToClose = processorToClose;
            this.metrics = metrics;
            this.gauges = gauges;
        }
    }

    private <K, V> BlueCacheStats.Region cacheRegion(WeightedLruCache<K, V> cache,
                                                      boolean pinned) {
        return new BlueCacheStats.Region(
                cache.size(),
                cache.currentWeight(),
                cache.highWaterWeight(),
                cache.hits(),
                cache.misses(),
                cache.evictions(),
                cache.oversizedRejections(),
                pinned);
    }

    private static long approximateSnapshotWeightBytes(ResolvedSnapshot snapshot) {
        long roots = FrozenNode.approximateRetainedWeightBytesOf(
                snapshot.frozenCanonicalRoot(), snapshot.frozenResolvedRoot());
        return saturatedAdd(192L + 2L * snapshot.blueId().length(), roots);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private long clearReloadableRuntimeCaches() {
        runtimeCacheGeneration++;
        long released =
                derivedSnapshotsByCanonicalRepresentation.clear();
        released = saturatedAdd(released, derivedSnapshotsByBlueId.clear());
        released = saturatedAdd(released, recentProcessingDocumentSnapshots.clear());
        ResolvedReferenceCache.CacheStats reference = resolvedReferenceCache.cacheStats();
        long pinnedReferenceWeight = resolvedReferenceCache.pinnedVerifiedWeightBytes();
        released = saturatedAdd(released, Math.max(0L,
                reference.verifiedCurrentWeightBytes() - pinnedReferenceWeight));
        released = saturatedAdd(released, reference.transientTrustedCurrentWeightBytes());
        released = saturatedAdd(released, reference.structuralCurrentWeightBytes());
        resolvedReferenceCache.clearReloadable();
        return released;
    }

    private long clearAllRuntimeCaches() {
        runtimeCacheGeneration++;
        long released = pinnedSnapshotWeightBytes;
        pinnedSnapshotsByBlueId.clear();
        pinnedSnapshotsByCanonicalRepresentation.clear();
        pinnedSnapshotWeightBytes = 0L;
        released = saturatedAdd(released,
                derivedSnapshotsByCanonicalRepresentation.clear());
        released = saturatedAdd(released, derivedSnapshotsByBlueId.clear());
        released = saturatedAdd(released, recentProcessingDocumentSnapshots.clear());
        ResolvedReferenceCache.CacheStats reference = resolvedReferenceCache.cacheStats();
        released = saturatedAdd(released, reference.verifiedCurrentWeightBytes());
        released = saturatedAdd(released, reference.transientTrustedCurrentWeightBytes());
        released = saturatedAdd(released, reference.structuralCurrentWeightBytes());
        resolvedReferenceCache.clear();
        return released;
    }

    private static void closeProcessor(DocumentProcessor processor) {
        if (processor != null) {
            processor.close();
        }
    }

    private ProcessingObserver processingObserver() {
        return documentProcessor != null
                ? documentProcessor.processingObserver()
                : lifecycleObserver;
    }

    /** Emits one context-free observation without exposing exporter failures. */
    private static void recordObservation(
            ProcessingObserver observer,
            ProcessingMetricId metricId,
            long value) {
        if (observer == null) {
            return;
        }
        try {
            observer.record(ProcessingObservation.of(metricId, value));
        } catch (ThreadDeath failure) {
            throw failure;
        } catch (VirtualMachineError failure) {
            throw failure;
        } catch (Throwable ignored) {
            // Telemetry is operational only and cannot change Language behavior.
        }
    }

    /** Emits one cache observation with the manifest's bounded cache dimension. */
    private static void recordCacheObservation(
            ProcessingObserver observer,
            ProcessingMetricId metricId,
            String cacheName,
            long value) {
        if (observer == null) {
            return;
        }
        try {
            observer.record(ProcessingObservation.of(
                    metricId,
                    value,
                    ProcessingObservationContext.of(
                            ProcessingObservationDimension.CACHE_NAME,
                            cacheName)));
        } catch (ThreadDeath failure) {
            throw failure;
        } catch (VirtualMachineError failure) {
            throw failure;
        } catch (Throwable ignored) {
            // Telemetry is operational only and cannot change Language behavior.
        }
    }

    private void ensureOpen() {
        if (closed || (closeInProgress
                && activeProcessingCacheStamp.get() == null
                && directCacheOperationDepth.get() == null
                && cacheInvalidationThread != Thread.currentThread())) {
            throw new IllegalStateException("Blue runtime is closed");
        }
    }

    /**
     * Returns whether this runtime has released its owned caches.
     *
     * @return true once close has transitioned the runtime and released its
     *         caches; this remains true if later dependency cleanup reports a
     *         failure
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Releases pinned authoritative content and all derived/transient cache
     * state owned by this runtime. Closing is idempotent. An external close
     * waits for provider-, processor-, and cache-backed operations admitted
     * through this {@code Blue} instance, while preventing new runtime work from
     * starting. Direct operations on a retained {@link #getDocumentProcessor()
     * processor handle} must be completed by the caller before close. A close
     * attempted reentrantly by active runtime work is rejected with
     * {@link IllegalStateException} to avoid waiting for itself. Pure serialization
     * helpers remain usable; runtime work rejects later calls.
     *
     * @throws IllegalStateException for close from active runtime work, an
     *                               interrupted close wait, or owned-resource
     *                               close failure
     */
    @Override
    public void close() {
        ProcessingObserver observer;
        DocumentProcessor processorToClose;
        CacheGaugeSnapshot gauges;
        long released;
        boolean firstClose;
        Throwable previousFailure;
        synchronized (lifecycleLock) {
            if (closeInProgress && closingThread == Thread.currentThread()) {
                // A close-time processor/metrics callback must not recursively
                // re-emit close metrics or wait for its own initiating frame.
                return;
            }
            if (activeProcessingCacheStamp.get() != null
                    || directCacheOperationDepth.get() != null) {
                throw new IllegalStateException(
                        "Blue runtime cannot close from active runtime work");
            }
            while (closeInProgress) {
                try {
                    lifecycleLock.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while waiting for Blue runtime close", exception);
                }
            }
            if (cacheInvalidationInProgress
                    && cacheInvalidationThread == Thread.currentThread()) {
                throw new IllegalStateException(
                        "Blue runtime cannot close while cache invalidation waits for current work");
            }
            closingThread = Thread.currentThread();
            closeInProgress = true;
            try {
                awaitCacheInvalidation();
                while (activeProcessingOperations > 0 || activeDirectCacheOperations > 0) {
                    try {
                        lifecycleLock.wait();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Interrupted while waiting for active Blue runtime work",
                                exception);
                    }
                }
            } catch (RuntimeException | Error exception) {
                closeInProgress = false;
                closingThread = null;
                lifecycleLock.notifyAll();
                throw exception;
            }
            observer = processingObserver();
            if (closed) {
                processorToClose = null;
                gauges = null;
                released = 0L;
                firstClose = false;
                previousFailure = lifecycleCloseFailure;
            } else {
                lifecycleObserver = observer;
                closed = true;
                processorOwnerToken = new Object();
                processorToClose = documentProcessorOwned ? documentProcessor : null;
                long processorWeight = processorToClose != null
                        ? processorToClose.cacheWeightBytes() : 0L;
                processorPlanCacheHighWaterBytes = Math.max(
                        processorPlanCacheHighWaterBytes, processorWeight);
                documentProcessor = null;
                documentProcessorOwned = false;
                released = saturatedAdd(clearAllRuntimeCaches(), processorWeight);
                externalContractTypeNodes.clear();
                synchronized (managedProcessorConformanceEngines) {
                    managedProcessorConformanceEngines.clear();
                }
                gauges = captureCacheGauges();
                firstClose = true;
                previousFailure = null;
            }
        }

        Throwable failure = previousFailure;
        if (firstClose) {
            try {
                resolvedReferenceCache.close();
            } catch (Throwable throwable) {
                failure = throwable;
            }
            try {
                closeProcessor(processorToClose);
            } catch (Throwable throwable) {
                failure = combineFailure(failure, throwable);
            }
        }
        try {
            recordObservation(
                    observer,
                    ProcessingMetricId.RUNTIME_CLOSE_CALLS,
                    1L);
            if (firstClose) {
                gauges.emit(observer);
                recordObservation(
                        observer,
                        ProcessingMetricId.RUNTIME_CLOSE_RELEASED_WEIGHT_BYTES,
                        released);
            }
        } catch (Throwable throwable) {
            failure = combineFailure(failure, throwable);
        } finally {
            synchronized (lifecycleLock) {
                lifecycleCloseFailure = failure;
                closeInProgress = false;
                closingThread = null;
                lifecycleLock.notifyAll();
            }
        }
        rethrowCloseFailure(failure);
    }

    private static Throwable combineFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void rethrowCloseFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("Failed to close Blue runtime", failure);
    }

    private ResolvedSnapshot cacheProcessingSnapshot(ResolvedSnapshot snapshot) {
        return cacheSnapshot(snapshot);
    }

    private ResolutionLimits combineWithGlobalLimits(ResolutionLimits methodLimits) {
        if (globalLimits == NO_LIMITS) {
            return methodLimits;
        }

        if (methodLimits == NO_LIMITS) {
            return globalLimits;
        }

        return ResolutionLimits.allOf(globalLimits, methodLimits);
    }

    private MergingProcessor createDefaultNodeProcessor() {
        return new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new ListProcessor(),
                        new DictionaryProcessor(),
                        new SchemaPropagator(),
                        new SchemaVerifier(),
                        new BasicTypesVerifier()
                )
        );
    }

    private static final class ReferenceBudget {
        private final int maximum;
        private final Set<String> requestedBlueIds = new LinkedHashSet<>();
        private final Set<String> outstandingBlueIds = new LinkedHashSet<>();
        private NodeProviderOutcome providerOutcome;

        private ReferenceBudget(int maximum) {
            this.maximum = maximum;
        }

        private boolean tryAcquire(String blueId) {
            if (requestedBlueIds.contains(blueId)) {
                return true;
            }
            if (requestedBlueIds.size() >= maximum) {
                outstandingBlueIds.add(blueId);
                return false;
            }
            requestedBlueIds.add(blueId);
            return true;
        }
    }

    /**
     * Includes only the ancestor/descendant closure of demanded semantic
     * paths. This prevents a limited resolution from spending provider budget
     * on an unrelated sibling while still completing the demanded subtree.
     */
    private static final class SemanticDemandLimits implements ResolutionLimits {
        private final List<List<String>> demands;
        private final List<String> currentPath = new ArrayList<>();
        private final List<Boolean> enteredSegments = new ArrayList<>();

        private SemanticDemandLimits(List<List<String>> demands) {
            this.demands = demands;
        }

        @Override
        public boolean shouldExpandPathSegment(String pathSegment, Node currentNode) {
            return isDemandedClosure(potentialPath(pathSegment));
        }

        /** Legacy binary-API spelling delegated to the canonical method. */
        @Override
        public boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
            return shouldExpandPathSegment(pathSegment, currentNode);
        }

        @Override
        public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
            return isDemandedClosure(potentialPath(pathSegment));
        }

        @Override
        public void enterPathSegment(String pathSegment, Node currentNode) {
            boolean entered = pathSegment != null && !pathSegment.isEmpty();
            enteredSegments.add(entered);
            if (entered) {
                currentPath.add(pathSegment);
            }
        }

        @Override
        public void exitPathSegment() {
            if (enteredSegments.isEmpty()) {
                return;
            }
            boolean entered = enteredSegments.remove(enteredSegments.size() - 1);
            if (entered && !currentPath.isEmpty()) {
                currentPath.remove(currentPath.size() - 1);
            }
        }

        private List<String> potentialPath(String segment) {
            List<String> path = new ArrayList<>(currentPath);
            if (segment != null && !segment.isEmpty()) {
                path.add(segment);
            }
            return path;
        }

        private boolean isDemandedClosure(List<String> path) {
            for (List<String> demand : demands) {
                if (isPrefix(path, demand) || isPrefix(demand, path)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isPrefix(List<String> prefix, List<String> value) {
            if (prefix.size() > value.size()) {
                return false;
            }
            for (int index = 0; index < prefix.size(); index++) {
                if (!Objects.equals(prefix.get(index), value.get(index))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class ReferenceExpansionLimitException extends RuntimeException {
        private ReferenceExpansionLimitException(String blueId) {
            super("Reference expansion limit reached for " + blueId + ".");
        }
    }

}
