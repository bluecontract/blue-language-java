package blue.language;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.conformance.ConformanceEngine;
import blue.language.dictionary.DictionaryAwareExporter;
import blue.language.dictionary.DictionaryRegistry;
import blue.language.dictionary.ExportContext;
import blue.language.dictionary.TypeDictionary;
import blue.language.merge.Merger;
import blue.language.merge.IncrementalMergingProcessorCapability;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.merge.processor.*;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ContractProcessor;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingMetricsSink;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.model.Contract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.preprocess.Preprocessor;
import blue.language.provider.BootstrapProvider;
import blue.language.provider.PotentialBlueIdNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.*;
import blue.language.utils.limits.CompositeLimits;
import blue.language.utils.limits.ExcludedPathLimits;
import blue.language.utils.limits.Limits;

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
import java.util.function.Function;
import java.util.function.Predicate;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.limits.Limits.NO_LIMITS;

public class Blue implements NodeResolver, AutoCloseable {

    private static final int RECENT_PROCESSING_DOCUMENT_SNAPSHOT_LIMIT = 32;
    private static final String PINNED_SNAPSHOT_CACHE = "pinnedAuthoritativeSnapshots";
    private static final String DERIVED_SNAPSHOT_CACHE = "derivedResolvedSnapshots";
    private static final String CANONICAL_ALIAS_CACHE = "canonicalAliases";
    private static final String RECENT_PROCESSING_CACHE = "recentProcessingSnapshots";
    private static final String VERIFIED_REFERENCE_CACHE = "verifiedReferences";
    private static final String TRANSIENT_REFERENCE_CACHE = "transientTrustedReferences";
    private static final String STRUCTURAL_INTERNER_CACHE = "resolvedStructuralInterner";
    private static final String PROCESSOR_PLAN_CACHE = "processorPlans";

    private NodeProvider nodeProvider;
    private NodeProvider originalNodeProvider;
    private MergingProcessor mergingProcessor;
    private TypeClassResolver typeClassResolver;
    private Map<String, String> preprocessingAliases = new HashMap<>();
    private Limits globalLimits = NO_LIMITS;
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
    private volatile ProcessingMetricsSink lifecycleMetricsSink = ProcessingMetricsSink.NOOP;
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



    public Blue() {
        this(node -> null, null, null, BlueCachePolicy.boundedDefaults());
    }

    public Blue(NodeProvider nodeProvider) {
        this(nodeProvider, null, null, BlueCachePolicy.boundedDefaults());
    }

    public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor) {
        this(nodeProvider, mergingProcessor, null, BlueCachePolicy.boundedDefaults());
    }

    public Blue(NodeProvider nodeProvider, TypeClassResolver typeClassResolver) {
        this(nodeProvider, null, typeClassResolver, BlueCachePolicy.boundedDefaults());
    }

    public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor, TypeClassResolver typeClassResolver) {
        this(nodeProvider, mergingProcessor, typeClassResolver, BlueCachePolicy.boundedDefaults());
    }

    /** Creates a default runtime with explicit bounded acceleration-cache policy. */
    public static Blue withCachePolicy(BlueCachePolicy cachePolicy) {
        return new Blue(node -> null, null, null, cachePolicy);
    }

    /**
     * Additive constructor for hosts that need explicit per-runtime cache bounds.
     * Existing constructors continue to use {@link BlueCachePolicy#boundedDefaults()}.
     */
    public Blue(NodeProvider nodeProvider,
                MergingProcessor mergingProcessor,
                TypeClassResolver typeClassResolver,
                BlueCachePolicy cachePolicy) {
        this.originalNodeProvider = nodeProvider;
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
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

    public Node resolve(Node node) {
        return resolve(node, NO_LIMITS);
    }

    @Override
    public Node resolve(Node node, Limits limits) {
        beginDirectCacheOperation();
        try {
            Limits effectiveLimits = combineWithGlobalLimits(limits);
            Merger merger = new Merger(mergingProcessor, nodeProvider, resolvedReferenceCache);
            return merger.resolve(node, effectiveLimits);
        } finally {
            endDirectCacheOperation();
        }
    }

    public Node resolvePreservingPaths(Node node, Collection<String> preservedPaths) {
        return resolvePreservingPaths(node, NO_LIMITS, preservedPaths);
    }

    public Node resolvePreservingPaths(Node node, Limits limits, Collection<String> preservedPaths) {
        beginDirectCacheOperation();
        try {
            if (node == null) {
                throw new IllegalArgumentException("node must not be null");
            }
            Set<String> canonicalPreservedPaths = canonicalPreservedPaths(preservedPaths);
            if (canonicalPreservedPaths.isEmpty()) {
                return resolve(node.clone(), limits);
            }
            if (canonicalPreservedPaths.contains("/")) {
                return node.clone();
            }

            Limits preservingLimits = limits == NO_LIMITS
                    ? ExcludedPathLimits.excluding(canonicalPreservedPaths)
                    : new CompositeLimits(
                    limits, ExcludedPathLimits.excluding(canonicalPreservedPaths));
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

    public List<String> selectPaths(Node node, Collection<String> pathPatterns, Predicate<Node> predicate) {
        return NodePathSelector.select(node, pathPatterns, predicate);
    }

    public Node resolvePreservingMatchingPaths(Node node,
                                               Collection<String> pathPatterns,
                                               Predicate<Node> predicate) {
        return resolvePreservingMatchingPaths(node, NO_LIMITS, pathPatterns, predicate);
    }

    public Node resolvePreservingMatchingPaths(Node node,
                                               Limits limits,
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
     * @deprecated Use {@link #canonicalize(Node)} for Content BlueId identity
     * or {@link MergeReverser#reverseToMinimizedOverlay(Node)} for author-facing
     * minimized output.
     */
    @Deprecated
    public Node reverse(Node node) {
        return new MergeReverser().reverse(node);
    }

    /**
     * @deprecated Use {@link #canonicalize(Object)} for Content BlueId identity
     * or {@link MergeReverser#reverseToMinimizedOverlay(Node)} for author-facing
     * minimized output.
     */
    @Deprecated
    public Node reverse(Object object) {
        beginDirectCacheOperation();
        try {
            return reverse(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    public Node canonicalize(Node node) {
        beginDirectCacheOperation();
        try {
            Node preprocessed = preprocess(node.clone());
            Node resolved = resolve(preprocessed.clone());
            return new MergeReverser().reverseToCanonicalOverlay(resolved, preprocessed);
        } finally {
            endDirectCacheOperation();
        }
    }

    public Node canonicalize(Object object) {
        beginDirectCacheOperation();
        try {
            return canonicalize(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    public Node expand(Node node) {
        beginDirectCacheOperation();
        try {
            if (node == null) {
                throw new IllegalArgumentException("node must not be null");
            }
            return expandReferences(node);
        } finally {
            endDirectCacheOperation();
        }
    }

    public Node expand(Object object) {
        beginDirectCacheOperation();
        try {
            return expand(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    public Node collapse(Node node) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        return new Node().blueId(BlueIdCalculator.calculateBlueId(node));
    }

    public Node collapse(Object object) {
        beginDirectCacheOperation();
        try {
            return collapse(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    public ResolvedSnapshot resolveToSnapshot(Node node) {
        beginDirectCacheOperation();
        try {
            Node preprocessed = preprocess(node.clone());
            Limits limits = combineWithGlobalLimits(NO_LIMITS);
            Merger merger = new Merger(mergingProcessor, nodeProvider, resolvedReferenceCache);
            return cacheSnapshot(ResolvedSnapshot.fromResolverResult(
                    merger.resolveSnapshot(preprocessed, limits)));
        } finally {
            endDirectCacheOperation();
        }
    }

    public ResolvedSnapshot resolveToSnapshot(Object object) {
        beginDirectCacheOperation();
        try {
            return resolveToSnapshot(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

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

    private Node expandReferences(Node node) {
        if (node == null) {
            return null;
        }
        if (node.isReferenceOnly()) {
            List<Node> nodes = nodeProvider.fetchByBlueId(node.getBlueId());
            if (nodes == null || nodes.isEmpty()) {
                throw new IllegalArgumentException("No content found for blueId: " + node.getBlueId());
            }
            if (nodes.size() == 1) {
                return expandReferences(providerContentWithoutRootIdentity(nodes.get(0)));
            }
            return new Node().items(expandReferences(providerContentWithoutRootIdentity(nodes)));
        }

        Node expanded = node.clone();
        expanded.type(expandReferences(expanded.getType()));
        expanded.itemType(expandReferences(expanded.getItemType()));
        expanded.keyType(expandReferences(expanded.getKeyType()));
        expanded.valueType(expandReferences(expanded.getValueType()));
        expanded.blue(expandReferences(expanded.getBlue()));
        expanded.contracts(expandReferences(expanded.getContracts()));
        if (expanded.getItems() != null) {
            expanded.items(expandReferences(expanded.getItems()));
        }
        if (expanded.getProperties() != null) {
            Map<String, Node> expandedProperties = new LinkedHashMap<>();
            expanded.getProperties().forEach((key, value) ->
                    expandedProperties.put(key, expandReferences(value)));
            expanded.properties(expandedProperties);
        }
        if (expanded.getSchema() != null) {
            expanded.schema(expandReferences(expanded.getSchema()));
        }
        return expanded;
    }

    private List<Node> expandReferences(List<Node> nodes) {
        List<Node> expanded = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            expanded.add(expandReferences(node));
        }
        return expanded;
    }

    private Schema expandReferences(Schema schema) {
        if (schema == null) {
            return null;
        }
        Schema expanded = schema.clone();
        expanded.required(expandReferences(expanded.getRequired()));
        expanded.minLength(expandReferences(expanded.getMinLength()));
        expanded.maxLength(expandReferences(expanded.getMaxLength()));
        expanded.minimum(expandReferences(expanded.getMinimum()));
        expanded.maximum(expandReferences(expanded.getMaximum()));
        expanded.exclusiveMinimum(expandReferences(expanded.getExclusiveMinimum()));
        expanded.exclusiveMaximum(expandReferences(expanded.getExclusiveMaximum()));
        expanded.multipleOf(expandReferences(expanded.getMultipleOf()));
        expanded.minItems(expandReferences(expanded.getMinItems()));
        expanded.maxItems(expandReferences(expanded.getMaxItems()));
        expanded.uniqueItems(expandReferences(expanded.getUniqueItems()));
        expanded.minFields(expandReferences(expanded.getMinFields()));
        expanded.maxFields(expandReferences(expanded.getMaxFields()));
        if (expanded.getEnum() != null) {
            expanded.enumValues(expandReferences(expanded.getEnum()));
        }
        return expanded;
    }

    public CanonicalOverlayPatchEngine canonicalPatchEngine(Node canonical) {
        return new CanonicalOverlayPatchEngine(FrozenNode.fromNode(canonical));
    }

    public CanonicalPatchResult applyCanonicalPatch(Node canonical, JsonPatch patch) {
        return canonicalPatchEngine(canonical).apply(patch);
    }

    public ResolvedSnapshot applyCanonicalPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
        beginDirectCacheOperation();
        try {
            return applyCanonicalPatch(snapshot, patch, this::snapshotFromVerifiedCanonical);
        } finally {
            endDirectCacheOperation();
        }
    }

    public Blue cacheResolvedSnapshot(ResolvedSnapshot snapshot) {
        beginDirectCacheOperation();
        try {
            pinSnapshot(snapshot);
            return this;
        } finally {
            endDirectCacheOperation();
        }
    }

    public Blue cacheResolvedSnapshots(Collection<ResolvedSnapshot> snapshots) {
        beginDirectCacheOperation();
        try {
            snapshots.forEach(this::cacheResolvedSnapshot);
            return this;
        } finally {
            endDirectCacheOperation();
        }
    }

    public Optional<ResolvedSnapshot> cachedResolvedSnapshot(String blueId) {
        beginDirectCacheOperation();
        try {
            return Optional.ofNullable(cachedSnapshotByBlueId(blueId));
        } finally {
            endDirectCacheOperation();
        }
    }

    public int resolvedSnapshotCacheSize() {
        return pinnedSnapshotsByCanonicalRepresentation.size()
                + derivedSnapshotsByCanonicalRepresentation.size();
    }

    public int resolvedReferenceCacheSize() {
        return resolvedReferenceCache.size();
    }

    public int resolvedStructuralCacheSize() {
        return resolvedReferenceCache.resolvedGraphSize();
    }

    public void clearResolvedSnapshotCache() {
        DocumentProcessor ownedProcessor;
        ProcessingMetricsSink metrics;
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
                metrics = metricsSink();
                gauges = captureCacheGauges();
                endCacheInvalidation();
            }
        } catch (RuntimeException | Error exception) {
            synchronized (lifecycleLock) {
                endCacheInvalidation();
            }
            throw exception;
        }
        gauges.emit(metrics);
    }

    /** Returns the immutable cache policy selected when this runtime was created. */
    public BlueCachePolicy cachePolicy() {
        return cachePolicy;
    }

    /** Returns approximate retained weights and ownership counters by cache region. */
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
                    0L,
                    0L,
                    true));
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

    public String languageVersion() {
        return "1.0";
    }

    public BlueConformanceReport conformanceReport() {
        String fixturePackageIdentity = BlueConformanceReport.loadFixturePackageIdentity("blue-language-1.0-fixtures:unavailable");
        List<String> fixtureIds = BlueConformanceReport.loadFixtureIds();
        Map<String, BlueFixtureCategory> fixtureCategories = BlueConformanceReport.loadFixtureCategories();
        return new BlueConformanceReport(
                languageVersion(),
                new LinkedHashMap<>(BlueCoreTypeRegistry.INSTANCE.blueIdsByName()),
                fixturePackageIdentity,
                fixtureIds,
                Collections.emptyList(),
                Collections.emptyList(),
                fixtureCategories
        );
    }

    public BlueConformanceReport runConformanceSuite() {
        return BlueConformanceSuiteRunner.run(this);
    }

    public BlueContractsConformanceReport contractsConformanceReport() {
        String fixturePackageIdentity = BlueContractsConformanceReport.loadFixturePackageIdentity(
                "blue-contracts-1.0-fixtures:unavailable");
        List<String> fixtureIds = BlueContractsConformanceReport.loadFixtureIds();
        Map<String, BlueContractsFixtureCategory> fixtureCategories =
                BlueContractsConformanceReport.loadFixtureCategories();
        return new BlueContractsConformanceReport(
                languageVersion(),
                fixturePackageIdentity,
                fixtureIds,
                Collections.emptyList(),
                Collections.emptyList(),
                fixtureCategories,
                Collections.emptyList());
    }

    public BlueContractsConformanceReport runContractsConformanceSuite() {
        return BlueContractsConformanceSuiteRunner.run(this);
    }

    public void extend(Node node, Limits limits) {
        beginDirectCacheOperation();
        try {
            Limits effectiveLimits = combineWithGlobalLimits(limits);
            new NodeExtender(nodeProvider).extend(node, effectiveLimits);
        } finally {
            endDirectCacheOperation();
        }
    }

    public Node objectToNode(Object object) {
        beginDirectCacheOperation();
        try {
            String json = JSON_MAPPER.writeValueAsString(object);
            return jsonToNode(json);
        } finally {
            endDirectCacheOperation();
        }
    }

    public <T> T convertObject(Object object, Class<T> clazz) {
        beginDirectCacheOperation();
        try {
            return nodeToObject(objectToNode(object).clone(), clazz);
        } finally {
            endDirectCacheOperation();
        }
    }

    public boolean nodeMatchesType(Node node, Node type) {
        beginDirectCacheOperation();
        try {
            return new NodeTypeMatcher(this).matchesType(node, type, globalLimits);
        } finally {
            endDirectCacheOperation();
        }
    }

    public boolean nodeMatchesType(FrozenNode resolvedNode, FrozenNode resolvedType) {
        beginDirectCacheOperation();
        try {
            return new NodeTypeMatcher(this).matchesResolvedType(resolvedNode, resolvedType);
        } finally {
            endDirectCacheOperation();
        }
    }

    public boolean nodeMatchesType(ResolvedSnapshot snapshot, String pointer, FrozenNode resolvedType) {
        beginDirectCacheOperation();
        try {
            return new NodeTypeMatcher(this).matchesResolvedType(snapshot, pointer, resolvedType);
        } finally {
            endDirectCacheOperation();
        }
    }

    public void setGlobalLimits(Limits globalLimits) {
        ConfigurationRefresh refresh = refreshRuntimeConfiguration(() ->
                this.globalLimits = globalLimits != null ? globalLimits : NO_LIMITS,
                false);
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
    }

    public Limits getGlobalLimits() {
        return globalLimits;
    }

    public Node yamlToNode(String yaml) {
        beginDirectCacheOperation();
        try {
            return preprocess(parseSourceYaml(yaml));
        } finally {
            endDirectCacheOperation();
        }
    }

    public Node jsonToNode(String json) {
        beginDirectCacheOperation();
        try {
            return preprocess(parseSourceJson(json));
        } finally {
            endDirectCacheOperation();
        }
    }

    public Node parseSourceYaml(String yaml) {
        return YAML_MAPPER.readValue(yaml, Node.class);
    }

    public Node parseSourceJson(String json) {
        return JSON_MAPPER.readValue(json, Node.class);
    }

    public Node parseBlueIdInputYaml(String yaml) {
        Node node = YAML_MAPPER.readValue(yaml, Node.class);
        BlueIdReferenceValidator.validate(node);
        BlueIdCalculator.calculateBlueId(node);
        return node;
    }

    public Node parseBlueIdInputJson(String json) {
        Node node = JSON_MAPPER.readValue(json, Node.class);
        BlueIdReferenceValidator.validate(node);
        BlueIdCalculator.calculateBlueId(node);
        return node;
    }

    public String nodeToYaml(Node node) {
        return YAML_MAPPER.writeValueAsString(NodeToMapListOrValue.get(node));
    }

    public String nodeToYaml(Node node, ExportContext exportContext) {
        return YAML_MAPPER.writeValueAsString(NodeToMapListOrValue.get(exportNode(node, exportContext)));
    }

    public String nodeToSimpleYaml(Node node) {
        return YAML_MAPPER.writeValueAsString(NodeToMapListOrValue.get(node, NodeToMapListOrValue.Strategy.SIMPLE));
    }

    public String nodeToJson(Node node) {
        return JSON_MAPPER.writeValueAsString(NodeToMapListOrValue.get(node));
    }

    public String nodeToJson(Node node, ExportContext exportContext) {
        return JSON_MAPPER.writeValueAsString(NodeToMapListOrValue.get(exportNode(node, exportContext)));
    }

    public String nodeToSimpleJson(Node node) {
        return JSON_MAPPER.writeValueAsString(NodeToMapListOrValue.get(node, NodeToMapListOrValue.Strategy.SIMPLE));
    }

    public String objectToYaml(Object object) {
        beginDirectCacheOperation();
        try {
            return nodeToYaml(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    public String objectToSimpleYaml(Object object) {
        beginDirectCacheOperation();
        try {
            return nodeToSimpleYaml(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    public String objectToJson(Object object) {
        beginDirectCacheOperation();
        try {
            return nodeToJson(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    public String objectToJson(Object object, ExportContext exportContext) {
        beginDirectCacheOperation();
        try {
            return nodeToJson(objectToNode(object), exportContext);
        } finally {
            endDirectCacheOperation();
        }
    }

    public String objectToSimpleJson(Object object) {
        beginDirectCacheOperation();
        try {
            return nodeToSimpleJson(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    public Node exportNode(Node node, ExportContext exportContext) {
        return new DictionaryAwareExporter(dictionaryRegistry, exportContext).export(node);
    }

    public Blue registerTypeDictionary(TypeDictionary dictionary) {
        synchronized (lifecycleLock) {
            ensureOpen();
            dictionaryRegistry.register(dictionary);
        }
        return this;
    }

    public Blue registerTypeDictionaries(Collection<? extends TypeDictionary> dictionaries) {
        synchronized (lifecycleLock) {
            ensureOpen();
            dictionaryRegistry.registerAll(dictionaries);
        }
        return this;
    }

    public DictionaryRegistry dictionaryRegistry() {
        return dictionaryRegistry;
    }

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

    public String calculateBlueId(Node node) {
        return BlueIdCalculator.calculateBlueId(node);
    }

    public String calculateBlueId(Object object) {
        beginDirectCacheOperation();
        try {
            return calculateBlueId(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    public String calculateSemanticBlueId(Node node) {
        return BlueIdCalculator.calculateBlueId(canonicalize(node));
    }

    public String calculateSemanticBlueId(Object object) {
        beginDirectCacheOperation();
        try {
            return calculateSemanticBlueId(objectToNode(object));
        } finally {
            endDirectCacheOperation();
        }
    }

    public void addPreprocessingAliases(Map<String, String> aliases) {
        ConfigurationRefresh refresh = refreshRuntimeConfiguration(() -> {
            Map<String, String> nextAliases = new HashMap<>(preprocessingAliases);
            nextAliases.putAll(aliases);
            preprocessingAliases = nextAliases;
        }, false);
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
    }

    public Blue registerContractProcessor(ContractProcessor<? extends Contract> processor) {
        ensureOpen();
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        DocumentProcessor target = beginDocumentProcessorMutation();
        try {
            target.registerContractProcessor(processor);
        } finally {
            endDocumentProcessorMutation();
        }
        return this;
    }

    public Blue registerContractProcessor(String blueId, ContractProcessor<? extends Contract> processor) {
        ensureOpen();
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        DocumentProcessor target = beginDocumentProcessorMutation();
        try {
            target.registerContractProcessor(blueId, processor);
        } finally {
            endDocumentProcessorMutation();
        }
        return this;
    }

    public Blue registerContractProcessor(String blueId,
                                          Node canonicalTypeNode,
                                          ContractProcessor<? extends Contract> processor) {
        return registerExternalContractType(blueId, canonicalTypeNode, processor);
    }

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
        DocumentProcessor target = beginDocumentProcessorMutation();
        ProcessingMetricsSink metrics;
        CacheGaugeSnapshot gauges;
        try {
            target.registerContractProcessor(blueId, processor);
            synchronized (lifecycleLock) {
                externalContractTypeNodes.put(blueId, validatedCanonicalType);
                // The extension provider is consulted by snapshot resolution. Any
                // unresolved/false result produced before registration is stale.
                clearReloadableRuntimeCaches();
                metrics = metricsSink();
                gauges = captureCacheGauges();
            }
        } finally {
            endDocumentProcessorMutation();
        }
        gauges.emit(metrics);
        return this;
    }

    public DocumentProcessingResult processDocument(Node document, Node event) {
        ProcessingOperation operation = beginProcessingOperation();
        DocumentProcessor processor = operation.processor;
        CacheGenerationStamp previousStamp = activeProcessingCacheStamp.get();
        activeProcessingCacheStamp.set(operation.stamp);
        long start = System.nanoTime();
        try {
            return attachProcessingSnapshot(
                    operation, processor.processDocument(document, event));
        } finally {
            try {
                processor.processingMetricsSink().addBlueProcessDocumentNanos(System.nanoTime() - start);
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
            return rememberProcessingResultSnapshot(
                    processor.processDocument(snapshot, event), operation.stamp);
        } finally {
            try {
                processor.processingMetricsSink().addBlueProcessDocumentNanos(System.nanoTime() - start);
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
     */
    public DocumentProcessor getDocumentProcessor() {
        synchronized (lifecycleLock) {
            awaitCacheInvalidation();
            ensureOpen();
            return ensureDocumentProcessor();
        }
    }

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

    public DocumentProcessingResult initializeDocument(Node document) {
        ProcessingOperation operation = beginProcessingOperation();
        CacheGenerationStamp previousStamp = activeProcessingCacheStamp.get();
        activeProcessingCacheStamp.set(operation.stamp);
        try {
            return attachProcessingSnapshot(
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
            return rememberProcessingResultSnapshot(
                    operation.processor.initializeDocument(snapshot), operation.stamp);
        } finally {
            finishProcessingOperation(previousStamp);
        }
    }

    public boolean isInitialized(Node document) {
        beginDirectCacheOperation();
        try {
            return ensureDocumentProcessor().isInitialized(document);
        } finally {
            endDirectCacheOperation();
        }
    }

    public boolean isInitialized(ResolvedSnapshot snapshot) {
        beginDirectCacheOperation();
        try {
            return ensureDocumentProcessor().isInitialized(snapshot);
        } finally {
            endDirectCacheOperation();
        }
    }

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
        if (node.getBlue() != null && node.getBlue().getValue() instanceof String) {
            String blueValue = (String) node.getBlue().getValue();

            if (aliases.containsKey(blueValue)) {
                Node clonedNode = node.clone();
                clonedNode.blue(new Node().blueId(aliases.get(blueValue)));
                return new Preprocessor(preprocessingNodeProvider)
                        .preprocessWithDefaultBlue(clonedNode);
            } else if (BlueIds.isPotentialBlueId(blueValue)) {
                Node clonedNode = node.clone();
                clonedNode.blue(new Node().blueId(blueValue));
                return new Preprocessor(preprocessingNodeProvider)
                        .preprocessWithDefaultBlue(clonedNode);
            } else {
                throw new IllegalArgumentException("Invalid blue value: " + blueValue);
            }
        }

        return new Preprocessor(preprocessingNodeProvider).preprocessWithDefaultBlue(node);
    }

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

    public boolean isNodeSubtypeOf(Node candidateNode, Node superTypeNode) {
        beginDirectCacheOperation();
        try {
            return Types.isSubtype(candidateNode, superTypeNode, nodeProvider);
        } finally {
            endDirectCacheOperation();
        }
    }

    public NodeProvider getNodeProvider() {
        return nodeProvider;
    }

    public MergingProcessor getMergingProcessor() {
        return mergingProcessor;
    }

    public TypeClassResolver getTypeClassResolver() {
        return typeClassResolver;
    }

    public Map<String, String> getPreprocessingAliases() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableMap(new HashMap<>(preprocessingAliases));
        }
    }

    public Blue nodeProvider(NodeProvider nodeProvider) {
        ConfigurationRefresh refresh = refreshRuntimeConfiguration(() -> {
            this.originalNodeProvider = nodeProvider;
            this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        }, true);
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
        return this;
    }

    public Blue mergingProcessor(MergingProcessor mergingProcessor) {
        ConfigurationRefresh refresh = refreshRuntimeConfiguration(() ->
                this.mergingProcessor = mergingProcessor, true);
        closeProcessor(refresh.processorToClose);
        refresh.gauges.emit(refresh.metrics);
        return this;
    }

    public Blue typeClassResolver(TypeClassResolver typeClassResolver) {
        synchronized (lifecycleLock) {
            ensureOpen();
            this.typeClassResolver = typeClassResolver;
            return this;
        }
    }

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
                            processorOwnerToken, runtimeCacheGeneration),
                    nodeProvider,
                    processorSnapshotNodeProvider(),
                    mergingProcessor,
                    Collections.unmodifiableMap(new HashMap<>(preprocessingAliases)),
                    globalLimits);
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
        Limits capturedLimits = globalLimits;
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

    private DocumentProcessingResult attachProcessingSnapshot(ProcessingOperation operation,
                                                              DocumentProcessingResult result) {
        DocumentProcessor processor = operation.processor;
        if (result == null || result.capabilityFailure() || result.snapshot() != null) {
            return rememberProcessingResultSnapshot(result, operation.stamp);
        }
        long start = System.nanoTime();
        try {
            DocumentProcessingResult attached = result.withSnapshot(
                    resolveProcessingSnapshot(result.document(), operation));
            return rememberProcessingResultSnapshot(attached, operation.stamp);
        } finally {
            long nanos = System.nanoTime() - start;
            processor.processingMetricsSink().addResultSnapshotAttachNanos(nanos);
            processor.processingMetricsSink().addBlueIdCalculationNanos(nanos);
        }
    }

    private DocumentProcessingResult rememberProcessingResultSnapshot(DocumentProcessingResult result,
                                                                      CacheGenerationStamp stamp) {
        if (result != null && result.snapshot() != null && result.document() != null) {
            rememberProcessingSnapshot(result.document(), result.snapshot(), stamp);
        }
        return result;
    }

    private ResolvedSnapshot cachedProcessingSnapshotFor(Node document,
                                                         ProcessingMetricsSink metrics,
                                                         CacheGenerationStamp stamp) {
        if (document == null) {
            return null;
        }
        long start = System.nanoTime();
        try {
            FrozenNode.ResolvedStructuralKey selectedKey = selectedStructuralKey(document);
            if (selectedKey == null) {
                metrics.incrementProcessingSnapshotCacheMisses();
                return null;
            }
            ResolvedSnapshot cached = recentProcessingSnapshot(selectedKey, stamp);
            if (cached != null) {
                metrics.incrementProcessingSnapshotCacheHits();
                return cached;
            }
            metrics.incrementProcessingSnapshotCacheMisses();
            return null;
        } finally {
            metrics.addProcessingSnapshotCacheLookupNanos(System.nanoTime() - start);
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
        FrozenNode.ResolvedStructuralKey selectedKey = selectedStructuralKey(document);
        if (selectedKey == null) {
            return;
        }
        CacheMutationMetrics mutation;
        ProcessingMetricsSink metrics;
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
            metrics = metricsSink();
        }
        mutation.emit(metrics);
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
            Limits capturedLimits = globalLimits;
            documentProcessor = new DocumentProcessor(previous.getContractRegistry(),
                    previous.getContractTypeResolver(),
                    processorConformanceEngine(
                            capturedSnapshotProvider, capturedMergingProcessor),
                    new BlueProcessingSnapshotManager(
                            ownerToken,
                            capturedPreprocessingProvider,
                            capturedSnapshotProvider,
                            capturedMergingProcessor,
                            capturedAliases,
                            capturedLimits,
                            null,
                            null),
                    new ContractMatchingService(this),
                    previous.processingMetricsSink());
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
                        processorToClose, metricsSink(), captureCacheGauges());
            } finally {
                endCacheInvalidation();
            }
        }
    }

    private final class BlueProcessingSnapshotManager implements ProcessingSnapshotManager {
        private final Object ownerToken;
        private final NodeProvider preprocessingNodeProvider;
        private final NodeProvider snapshotNodeProvider;
        private final MergingProcessor snapshotMergingProcessor;
        private final Map<String, String> aliases;
        private final Limits limits;
        private final ResolvedReferenceCache sequenceReferenceCache;
        private final CacheGenerationStamp fixedStamp;
        private final ThreadLocal<CacheGenerationStamp> directOperationStamp = new ThreadLocal<>();

        private BlueProcessingSnapshotManager(Object ownerToken,
                                              NodeProvider preprocessingNodeProvider,
                                              NodeProvider snapshotNodeProvider,
                                              MergingProcessor snapshotMergingProcessor,
                                              Map<String, String> aliases,
                                              Limits limits,
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

        private ProcessingMetricsSink processingMetrics() {
            synchronized (lifecycleLock) {
                return processorOwnerToken == ownerToken && documentProcessor != null
                        ? documentProcessor.processingMetricsSink()
                        : ProcessingMetricsSink.NOOP;
            }
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            CacheGenerationStamp stamp = operationStamp();
            ResolvedSnapshot cached = cachedProcessingSnapshotFor(
                    document, processingMetrics(), stamp);
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
                    document, processingMetrics(), stamp);
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

    private ResolvedSnapshot resolveProcessingSnapshot(Node node,
                                                       ProcessingOperation operation) {
        ResolvedReferenceCache oneShot = resolvedReferenceCache.transientChild();
        try {
            ResolvedSnapshot resolved = resolveProcessingSnapshot(node,
                    oneShot,
                    operation.preprocessingNodeProvider,
                    operation.aliases,
                    operation.snapshotNodeProvider,
                    operation.snapshotMergingProcessor,
                    operation.limits);
            return publishProcessingSnapshot(resolved, oneShot, operation.stamp);
        } finally {
            oneShot.close();
        }
    }

    private ResolvedSnapshot resolveProcessingSnapshot(
            Node node,
            ResolvedReferenceCache resolutionCache,
            NodeProvider preprocessingNodeProvider,
            Map<String, String> aliases,
            NodeProvider snapshotNodeProvider,
            MergingProcessor snapshotMergingProcessor,
            Limits limits) {
        Node preprocessed = preprocess(node.clone(), preprocessingNodeProvider, aliases);
        Node resolved = new Merger(snapshotMergingProcessor,
                snapshotNodeProvider,
                resolutionCache)
                .resolve(preprocessed.clone(), limits);
        FrozenNode canonicalRoot = FrozenNode.fromNode(new MergeReverser()
                .reverseToCanonicalOverlay(resolved.clone(), preprocessed));
        FrozenNode resolvedRoot = resolutionCache.freezeResolved(resolved);
        return new ResolvedSnapshot(canonicalRoot, resolvedRoot, canonicalRoot.blueId());
    }

    private ResolvedSnapshot applyProcessingCanonicalPatch(
            ResolvedSnapshot snapshot,
            JsonPatch patch,
            NodeProvider snapshotNodeProvider,
            MergingProcessor snapshotMergingProcessor,
            Limits limits,
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
        CanonicalPatchResult patched = snapshot.applyCanonicalPatch(patch);
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
        Merger merger = new Merger(mergingProcessor, nodeProvider, resolvedReferenceCache);
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
        Merger merger = new Merger(mergingProcessor, snapshotNodeProvider, resolvedReferenceCache);
        Node canonical = canonicalRoot.toNode();
        Node resolved = merger.resolve(canonical.clone(), combineWithGlobalLimits(NO_LIMITS));
        return snapshotFromResolved(canonical, resolved, canonicalRoot);
    }

    private ResolvedSnapshot snapshotFromCanonical(
            FrozenNode canonicalRoot,
            NodeProvider snapshotNodeProvider,
            MergingProcessor snapshotMergingProcessor,
            Limits limits,
            ResolvedReferenceCache resolutionCache) {
        Merger merger = new Merger(
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
            Node canonical = new MergeReverser().reverseToCanonicalOverlay(
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
            contractsPath.add("contracts");
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
        if (path == null || path.isEmpty() || "/".equals(path)) {
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
        String calculated = BlueIdCalculator.calculateBlueId(canonical);
        if (!blueId.equals(calculated)) {
            throw new IllegalArgumentException("External contract type node hashes to " + calculated
                    + ", not declared BlueId " + blueId);
        }
        return canonical;
    }

    private ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
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
                metricsSink(),
                derivedMutation,
                aliasMutation,
                gauges);
    }

    private ResolvedSnapshot publishProcessingSnapshot(
            ResolvedSnapshot snapshot,
            ResolvedReferenceCache transientReferenceCache,
            CacheGenerationStamp stamp) {
        CacheSnapshotPublication publication;
        synchronized (lifecycleLock) {
            if (!isCurrentCacheStampLocked(stamp)
                    || transientReferenceCache != null
                    && !transientReferenceCache.isCurrentGeneration()) {
                return snapshot;
            }
            snapshot = publishableCacheSnapshot(snapshot, metricsSink());
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
        ProcessingMetricsSink metrics;
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
            metrics = metricsSink();
        }
        if (selected.verifiedReferenceResolution() != null) {
            resolvedReferenceCache.putPinnedVerifiedResolved(
                    selected.verifiedReferenceResolution());
        }
        gauges.emit(metrics);
    }

    private ResolvedSnapshot publishableCacheSnapshot(ResolvedSnapshot snapshot) {
        return publishableCacheSnapshot(snapshot, null);
    }

    private ResolvedSnapshot publishableCacheSnapshot(ResolvedSnapshot snapshot,
                                                      ProcessingMetricsSink metrics) {
        Objects.requireNonNull(snapshot, "snapshot");
        FrozenNode canonicalRoot = snapshot.frozenCanonicalRoot();
        if (canonicalRoot.isStrictCanonical()
                && canonicalRoot.isStrictBlueIdValidation()) {
            return snapshot;
        }
        if (metrics != null) {
            metrics.incrementProcessorPublicationCanonicalizations();
            metrics.incrementProcessorPublicationCanonicalMaterializations();
            metrics.incrementProcessorPublicationStrictBlueIdCalculations();
            long canonicalizationStart = System.nanoTime();
            try {
                return snapshot.toStrictBlueIdValidatedCanonical();
            } finally {
                metrics.addProcessorPublicationCanonicalizationNanos(
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
            metricsSink().incrementCacheHits(PINNED_SNAPSHOT_CACHE);
            return pinned;
        }
        ResolvedSnapshot derived = derivedSnapshotsByCanonicalRepresentation.get(key);
        if (derived != null) {
            metricsSink().incrementCacheHits(DERIVED_SNAPSHOT_CACHE);
        } else {
            metricsSink().incrementCacheMisses(DERIVED_SNAPSHOT_CACHE);
        }
        return derived;
    }

    private ResolvedSnapshot cachedSnapshotByBlueId(String blueId) {
        ensureOpen();
        ResolvedSnapshot pinned = pinnedSnapshotsByBlueId.get(blueId);
        if (pinned != null) {
            metricsSink().incrementCacheHits(PINNED_SNAPSHOT_CACHE);
            return pinned;
        }
        WeakReference<ResolvedSnapshot> reference = derivedSnapshotsByBlueId.get(blueId);
        ResolvedSnapshot derived = reference != null ? reference.get() : null;
        if (derived == null) {
            if (reference != null) {
                derivedSnapshotsByBlueId.remove(blueId);
            }
            metricsSink().incrementCacheMisses(CANONICAL_ALIAS_CACHE);
        } else {
            metricsSink().incrementCacheHits(CANONICAL_ALIAS_CACHE);
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
        private final ProcessingMetricsSink metrics;
        private final CacheMutationMetrics derivedMutation;
        private final CacheMutationMetrics aliasMutation;
        private final CacheGaugeSnapshot gauges;

        private CacheSnapshotPublication(ResolvedSnapshot result,
                                         ProcessingMetricsSink metrics,
                                         CacheMutationMetrics derivedMutation,
                                         CacheMutationMetrics aliasMutation,
                                         CacheGaugeSnapshot gauges) {
            this.result = result;
            this.metrics = metrics;
            this.derivedMutation = derivedMutation;
            this.aliasMutation = aliasMutation;
            this.gauges = gauges;
        }

        private void emit() {
            if (derivedMutation != null) {
                derivedMutation.emit(metrics);
            }
            if (aliasMutation != null) {
                aliasMutation.emit(metrics);
            }
            if (gauges != null) {
                gauges.emit(metrics);
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

        private void emit(ProcessingMetricsSink metrics) {
            if (evictionDelta > 0L) {
                metrics.addMetric("cache." + cacheName + ".evictions", evictionDelta);
            }
            if (oversizedDelta > 0L) {
                metrics.addMetric(
                        "cache." + cacheName + ".oversizedRejections", oversizedDelta);
            }
            metrics.setCacheCurrentWeightBytes(cacheName, currentWeight);
            metrics.recordCacheHighWaterBytes(cacheName, highWaterWeight);
            metrics.setCacheEntries(cacheName, entries);
        }
    }

    private static final class CacheGaugeSnapshot {
        private final List<CacheGauge> gauges;

        private CacheGaugeSnapshot(List<CacheGauge> gauges) {
            this.gauges = gauges;
        }

        private void emit(ProcessingMetricsSink metrics) {
            for (CacheGauge gauge : gauges) {
                metrics.setCacheCurrentWeightBytes(gauge.cacheName, gauge.currentWeight);
                metrics.recordCacheHighWaterBytes(gauge.cacheName, gauge.highWaterWeight);
                metrics.setCacheEntries(gauge.cacheName, gauge.entries);
                if (gauge.pinnedEntries >= 0) {
                    metrics.setCachePinnedEntries(gauge.cacheName, gauge.pinnedEntries);
                }
                if (gauge.derivedEntries >= 0) {
                    metrics.setCacheDerivedEntries(gauge.cacheName, gauge.derivedEntries);
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
        private final NodeProvider preprocessingNodeProvider;
        private final NodeProvider snapshotNodeProvider;
        private final MergingProcessor snapshotMergingProcessor;
        private final Map<String, String> aliases;
        private final Limits limits;

        private ProcessingOperation(DocumentProcessor processor,
                                    CacheGenerationStamp stamp,
                                    NodeProvider preprocessingNodeProvider,
                                    NodeProvider snapshotNodeProvider,
                                    MergingProcessor snapshotMergingProcessor,
                                    Map<String, String> aliases,
                                    Limits limits) {
            this.processor = processor;
            this.stamp = stamp;
            this.preprocessingNodeProvider = preprocessingNodeProvider;
            this.snapshotNodeProvider = snapshotNodeProvider;
            this.snapshotMergingProcessor = snapshotMergingProcessor;
            this.aliases = aliases;
            this.limits = limits;
        }
    }

    private static final class ConfigurationRefresh {
        private final DocumentProcessor processorToClose;
        private final ProcessingMetricsSink metrics;
        private final CacheGaugeSnapshot gauges;

        private ConfigurationRefresh(DocumentProcessor processorToClose,
                                     ProcessingMetricsSink metrics,
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
        long released = derivedSnapshotsByCanonicalRepresentation.clear();
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

    private ProcessingMetricsSink metricsSink() {
        return documentProcessor != null
                ? documentProcessor.processingMetricsSink()
                : lifecycleMetricsSink;
    }

    private void ensureOpen() {
        if (closed || (closeInProgress
                && activeProcessingCacheStamp.get() == null
                && directCacheOperationDepth.get() == null
                && cacheInvalidationThread != Thread.currentThread())) {
            throw new IllegalStateException("Blue runtime is closed");
        }
    }

    /** Returns whether this runtime has released its owned caches. */
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
     */
    @Override
    public void close() {
        ProcessingMetricsSink metrics;
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
            metrics = metricsSink();
            if (closed) {
                processorToClose = null;
                gauges = null;
                released = 0L;
                firstClose = false;
                previousFailure = lifecycleCloseFailure;
            } else {
                lifecycleMetricsSink = metrics;
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
            metrics.incrementRuntimeCloseCalls();
            if (firstClose) {
                gauges.emit(metrics);
                metrics.addRuntimeCloseReleasedWeightBytes(released);
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

    private Limits combineWithGlobalLimits(Limits methodLimits) {
        if (globalLimits == NO_LIMITS) {
            return methodLimits;
        }

        if (methodLimits == NO_LIMITS) {
            return globalLimits;
        }

        return new CompositeLimits(globalLimits, methodLimits);
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

}
