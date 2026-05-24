package blue.language;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.conformance.ConformanceEngine;
import blue.language.dictionary.DictionaryAwareExporter;
import blue.language.dictionary.DictionaryRegistry;
import blue.language.dictionary.ExportContext;
import blue.language.dictionary.TypeDictionary;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.merge.processor.*;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ContractProcessor;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.model.Contract;
import blue.language.processor.model.JsonPatch;
import blue.language.preprocess.Preprocessor;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.*;
import blue.language.utils.limits.CompositeLimits;
import blue.language.utils.limits.ExcludedPathLimits;
import blue.language.utils.limits.Limits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.limits.Limits.NO_LIMITS;

public class Blue implements NodeResolver {

    private static final Set<String> PROCESSOR_MANAGED_TYPE_BLUE_IDS = new HashSet<>(Arrays.asList(
            "ChannelEventCheckpoint",
            "DocumentUpdate",
            "DocumentUpdateChannel",
            "EmbeddedNodeChannel",
            "InitializationMarker",
            "JsonPatch",
            "LifecycleChannel",
            "ProcessEmbedded",
            "ProcessingFailureMarker",
            "ProcessingTerminatedMarker",
            "TriggeredEventChannel"
    ));

    private NodeProvider nodeProvider;
    private NodeProvider originalNodeProvider;
    private MergingProcessor mergingProcessor;
    private TypeClassResolver typeClassResolver;
    private Map<String, String> preprocessingAliases = new HashMap<>();
    private Limits globalLimits = NO_LIMITS;
    private DocumentProcessor documentProcessor;
    private final ConcurrentMap<String, ResolvedSnapshot> resolvedSnapshotsByBlueId = new ConcurrentHashMap<>();
    private final ResolvedReferenceCache resolvedReferenceCache = new ResolvedReferenceCache();
    private final DictionaryRegistry dictionaryRegistry = new DictionaryRegistry();



    public Blue() {
        this(node -> null);
    }

    public Blue(NodeProvider nodeProvider) {
        this.originalNodeProvider = nodeProvider;
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.mergingProcessor = createDefaultNodeProcessor();
        this.documentProcessor = createDefaultDocumentProcessor();
    }

    public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor) {
        this(nodeProvider, mergingProcessor, null);
    }

    public Blue(NodeProvider nodeProvider, TypeClassResolver typeClassResolver) {
        this(nodeProvider, null, typeClassResolver);
    }

    public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor, TypeClassResolver typeClassResolver) {
        this.originalNodeProvider = nodeProvider;
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.mergingProcessor = mergingProcessor != null ? mergingProcessor : createDefaultNodeProcessor();
        this.typeClassResolver = typeClassResolver;
        this.documentProcessor = createDefaultDocumentProcessor();
    }

    public Node resolve(Node node) {
        return resolve(node, NO_LIMITS);
    }

    @Override
    public Node resolve(Node node, Limits limits) {
        Limits effectiveLimits = combineWithGlobalLimits(limits);
        Merger merger = new Merger(mergingProcessor, nodeProvider, resolvedReferenceCache);
        return merger.resolve(node, effectiveLimits);
    }

    public Node resolvePreservingPaths(Node node, Collection<String> preservedPaths) {
        return resolvePreservingPaths(node, NO_LIMITS, preservedPaths);
    }

    public Node resolvePreservingPaths(Node node, Limits limits, Collection<String> preservedPaths) {
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
                : new CompositeLimits(limits, ExcludedPathLimits.excluding(canonicalPreservedPaths));
        Node resolved = resolve(node.clone(), preservingLimits);
        for (String path : canonicalPreservedPaths) {
            Node preserved = NodePathEditor.getOrNull(node, path);
            if (preserved != null) {
                NodePathEditor.put(resolved, path, preserved.clone());
            }
        }
        return resolved;
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
        return resolvePreservingPaths(node, limits, selectPaths(node, pathPatterns, predicate));
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
        return reverse(objectToNode(object));
    }

    public Node canonicalize(Node node) {
        Node preprocessed = preprocess(node.clone());
        Node resolved = resolve(preprocessed.clone());
        return new MergeReverser().reverseToCanonicalOverlay(resolved);
    }

    public Node canonicalize(Object object) {
        return canonicalize(objectToNode(object));
    }

    public Node expand(Node node) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        return expandReferences(node);
    }

    public Node expand(Object object) {
        return expand(objectToNode(object));
    }

    public Node collapse(Node node) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        return new Node().blueId(BlueIdCalculator.calculateBlueId(node));
    }

    public Node collapse(Object object) {
        return collapse(objectToNode(object));
    }

    public ResolvedSnapshot resolveToSnapshot(Node node) {
        Node preprocessed = preprocess(node.clone());
        Node resolved = resolve(preprocessed.clone());
        Node canonical = new MergeReverser().reverseToCanonicalOverlay(resolved.clone());
        FrozenNode canonicalRoot = FrozenNode.fromNode(canonical);
        return cacheSnapshot(new ResolvedSnapshot(canonicalRoot, resolvedReferenceCache.freezeResolved(resolved), canonicalRoot.blueId()));
    }

    public ResolvedSnapshot resolveToSnapshot(Object object) {
        return resolveToSnapshot(objectToNode(object));
    }

    public ResolvedSnapshot loadSnapshot(Node canonical) {
        FrozenNode canonicalRoot = FrozenNode.fromNode(canonical);
        ResolvedSnapshot cached = resolvedSnapshotsByBlueId.get(canonicalRoot.blueId());
        if (cached != null) {
            return cached;
        }
        Node resolved = resolve(canonicalRoot.toNode());
        return cacheSnapshot(new ResolvedSnapshot(canonicalRoot, resolvedReferenceCache.freezeResolved(resolved), canonicalRoot.blueId()));
    }

    public ResolvedSnapshot loadSnapshot(String blueId) {
        ResolvedSnapshot cached = resolvedSnapshotsByBlueId.get(blueId);
        if (cached != null) {
            return cached;
        }
        List<Node> nodes = nodeProvider.fetchByBlueId(blueId);
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("No content found for blueId: " + blueId);
        }
        Node canonical = nodes.size() == 1 ? providerContentWithoutRootIdentity(nodes.get(0)) : new Node().items(providerContentWithoutRootIdentity(nodes));
        return loadSnapshot(canonical);
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
        return applyCanonicalPatch(snapshot, patch, nodeProvider);
    }

    public Blue cacheResolvedSnapshot(ResolvedSnapshot snapshot) {
        cacheSnapshot(snapshot);
        return this;
    }

    public Blue cacheResolvedSnapshots(Collection<ResolvedSnapshot> snapshots) {
        snapshots.forEach(this::cacheResolvedSnapshot);
        return this;
    }

    public Optional<ResolvedSnapshot> cachedResolvedSnapshot(String blueId) {
        return Optional.ofNullable(resolvedSnapshotsByBlueId.get(blueId));
    }

    public int resolvedSnapshotCacheSize() {
        return resolvedSnapshotsByBlueId.size();
    }

    public int resolvedReferenceCacheSize() {
        return resolvedReferenceCache.size();
    }

    public void clearResolvedSnapshotCache() {
        resolvedSnapshotsByBlueId.clear();
        resolvedReferenceCache.clear();
    }

    public ConformanceEngine conformanceEngine() {
        return new ConformanceEngine(nodeProvider, mergingProcessor, resolvedReferenceCache);
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
                new LinkedHashMap<>(Properties.CORE_TYPE_NAME_TO_BLUE_ID_MAP),
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

    public void extend(Node node, Limits limits) {
        Limits effectiveLimits = combineWithGlobalLimits(limits);
        new NodeExtender(nodeProvider).extend(node, effectiveLimits);
    }

    public Node objectToNode(Object object) {
        String json = JSON_MAPPER.writeValueAsString(object);
        return jsonToNode(json);
    }

    public <T> T convertObject(Object object, Class<T> clazz) {
        return nodeToObject(objectToNode(object).clone(), clazz);
    }

    public boolean nodeMatchesType(Node node, Node type) {
        return new NodeTypeMatcher(this).matchesType(node, type, globalLimits);
    }

    public boolean nodeMatchesType(FrozenNode resolvedNode, FrozenNode resolvedType) {
        return new NodeTypeMatcher(this).matchesResolvedType(resolvedNode, resolvedType);
    }

    public boolean nodeMatchesType(ResolvedSnapshot snapshot, String pointer, FrozenNode resolvedType) {
        return new NodeTypeMatcher(this).matchesResolvedType(snapshot, pointer, resolvedType);
    }

    public void setGlobalLimits(Limits globalLimits) {
        this.globalLimits = globalLimits != null ? globalLimits : NO_LIMITS;
    }

    public Limits getGlobalLimits() {
        return globalLimits;
    }

    public Node yamlToNode(String yaml) {
        return preprocess(parseSourceYaml(yaml));
    }

    public Node jsonToNode(String json) {
        return preprocess(parseSourceJson(json));
    }

    public Node parseSourceYaml(String yaml) {
        return YAML_MAPPER.readValue(yaml, Node.class);
    }

    public Node parseSourceJson(String json) {
        return JSON_MAPPER.readValue(json, Node.class);
    }

    public Node parseBlueIdInputYaml(String yaml) {
        Node node = YAML_MAPPER.readValue(yaml, Node.class);
        validateBlueIdInputReferences(node, "/");
        BlueIdCalculator.calculateBlueId(node);
        return node;
    }

    public Node parseBlueIdInputJson(String json) {
        Node node = JSON_MAPPER.readValue(json, Node.class);
        validateBlueIdInputReferences(node, "/");
        BlueIdCalculator.calculateBlueId(node);
        return node;
    }

    private void validateBlueIdInputReferences(Node node, String path) {
        if (node == null) {
            return;
        }
        if (node.getBlueId() != null) {
            BlueIds.requireNoThisPlaceholderOutsideCyclicApi(node.getBlueId(), path + "/blueId");
            BlueIds.requireBlueIdOrCyclicMember(node.getBlueId(), path + "/blueId");
        }
        if (node.getPreviousBlueId() != null) {
            BlueIds.requirePlainBlueId(node.getPreviousBlueId(), path + "/$previous/blueId");
        }
        validateBlueIdInputReferences(node.getType(), appendPath(path, "type"));
        validateBlueIdInputReferences(node.getItemType(), appendPath(path, "itemType"));
        validateBlueIdInputReferences(node.getKeyType(), appendPath(path, "keyType"));
        validateBlueIdInputReferences(node.getValueType(), appendPath(path, "valueType"));
        validateBlueIdInputReferences(node.getBlue(), appendPath(path, "blue"));
        validateBlueIdInputReferences(node.getContracts(), appendPath(path, "contracts"));
        if (node.getItems() != null) {
            for (int i = 0; i < node.getItems().size(); i++) {
                validateBlueIdInputReferences(node.getItems().get(i), appendPath(path, String.valueOf(i)));
            }
        }
        if (node.getProperties() != null) {
            node.getProperties().forEach((key, value) ->
                    validateBlueIdInputReferences(value, appendPath(path, key)));
        }
        validateBlueIdInputReferences(node.getSchema(), appendPath(path, "schema"));
    }

    private void validateBlueIdInputReferences(Schema schema, String path) {
        if (schema == null) {
            return;
        }
        validateBlueIdInputReferences(schema.getRequired(), appendPath(path, "required"));
        validateBlueIdInputReferences(schema.getMinLength(), appendPath(path, "minLength"));
        validateBlueIdInputReferences(schema.getMaxLength(), appendPath(path, "maxLength"));
        validateBlueIdInputReferences(schema.getMinimum(), appendPath(path, "minimum"));
        validateBlueIdInputReferences(schema.getMaximum(), appendPath(path, "maximum"));
        validateBlueIdInputReferences(schema.getExclusiveMinimum(), appendPath(path, "exclusiveMinimum"));
        validateBlueIdInputReferences(schema.getExclusiveMaximum(), appendPath(path, "exclusiveMaximum"));
        validateBlueIdInputReferences(schema.getMultipleOf(), appendPath(path, "multipleOf"));
        validateBlueIdInputReferences(schema.getMinItems(), appendPath(path, "minItems"));
        validateBlueIdInputReferences(schema.getMaxItems(), appendPath(path, "maxItems"));
        validateBlueIdInputReferences(schema.getUniqueItems(), appendPath(path, "uniqueItems"));
        validateBlueIdInputReferences(schema.getMinFields(), appendPath(path, "minFields"));
        validateBlueIdInputReferences(schema.getMaxFields(), appendPath(path, "maxFields"));
        if (schema.getEnum() != null) {
            for (int i = 0; i < schema.getEnum().size(); i++) {
                validateBlueIdInputReferences(schema.getEnum().get(i), appendPath(path, "enum/" + i));
            }
        }
    }

    private String appendPath(String path, String segment) {
        String prefix = path == null || path.isEmpty() ? "/" : path;
        if ("/".equals(prefix)) {
            return "/" + segment.replace("~", "~0").replace("/", "~1");
        }
        return prefix + "/" + segment.replace("~", "~0").replace("/", "~1");
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
        return nodeToYaml(objectToNode(object));
    }

    public String objectToSimpleYaml(Object object) {
        return nodeToSimpleYaml(objectToNode(object));
    }

    public String objectToJson(Object object) {
        return nodeToJson(objectToNode(object));
    }

    public String objectToJson(Object object, ExportContext exportContext) {
        return nodeToJson(objectToNode(object), exportContext);
    }

    public String objectToSimpleJson(Object object) {
        return nodeToSimpleJson(objectToNode(object));
    }

    public Node exportNode(Node node, ExportContext exportContext) {
        return new DictionaryAwareExporter(dictionaryRegistry, exportContext).export(node);
    }

    public Blue registerTypeDictionary(TypeDictionary dictionary) {
        dictionaryRegistry.register(dictionary);
        return this;
    }

    public Blue registerTypeDictionaries(Collection<? extends TypeDictionary> dictionaries) {
        dictionaryRegistry.registerAll(dictionaries);
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

        Class<T> clazz = (Class<T>) object.getClass();
        Node node = objectToNode(object);
        Node clonedNode = node.clone();
        return nodeToObject(clonedNode, clazz);
    }

    public String calculateBlueId(Node node) {
        return BlueIdCalculator.calculateBlueId(node);
    }

    public String calculateBlueId(Object object) {
        return calculateBlueId(objectToNode(object));
    }

    public String calculateSemanticBlueId(Node node) {
        return BlueIdCalculator.calculateBlueId(canonicalize(node));
    }

    public String calculateSemanticBlueId(Object object) {
        return calculateSemanticBlueId(objectToNode(object));
    }

    public void addPreprocessingAliases(Map<String, String> aliases) {
        preprocessingAliases.putAll(aliases);
    }

    public Blue registerContractProcessor(ContractProcessor<? extends Contract> processor) {
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        if (documentProcessor == null) {
            documentProcessor = createDefaultDocumentProcessor();
        }
        documentProcessor.registerContractProcessor(processor);
        return this;
    }

    public Blue registerContractProcessor(String blueId, ContractProcessor<? extends Contract> processor) {
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        if (documentProcessor == null) {
            documentProcessor = createDefaultDocumentProcessor();
        }
        documentProcessor.registerContractProcessor(blueId, processor);
        return this;
    }

    public DocumentProcessingResult processDocument(Node document, Node event) {
        DocumentProcessor processor = ensureDocumentProcessor();
        long start = System.nanoTime();
        try {
            return attachProcessingSnapshot(processor, processor.processDocument(document, event));
        } finally {
            processor.processingMetricsSink().addBlueProcessDocumentNanos(System.nanoTime() - start);
        }
    }

    public DocumentProcessingResult processDocument(ResolvedSnapshot snapshot, Node event) {
        DocumentProcessor processor = ensureDocumentProcessor();
        long start = System.nanoTime();
        try {
            return processor.processDocument(snapshot, event);
        } finally {
            processor.processingMetricsSink().addBlueProcessDocumentNanos(System.nanoTime() - start);
        }
    }

    public DocumentProcessor getDocumentProcessor() {
        return ensureDocumentProcessor();
    }

    public Blue documentProcessor(DocumentProcessor documentProcessor) {
        if (documentProcessor == null) {
            throw new IllegalArgumentException("documentProcessor must not be null");
        }
        this.documentProcessor = documentProcessor;
        return this;
    }

    public DocumentProcessingResult initializeDocument(Node document) {
        DocumentProcessor processor = ensureDocumentProcessor();
        return attachProcessingSnapshot(processor, processor.initializeDocument(document));
    }

    public DocumentProcessingResult initializeDocument(ResolvedSnapshot snapshot) {
        return ensureDocumentProcessor().initializeDocument(snapshot);
    }

    public boolean isInitialized(Node document) {
        return ensureDocumentProcessor().isInitialized(document);
    }

    public boolean isInitialized(ResolvedSnapshot snapshot) {
        return ensureDocumentProcessor().isInitialized(snapshot);
    }

    public Node preprocess(Node node) {
        if (node.getBlue() != null && node.getBlue().getValue() instanceof String) {
            String blueValue = (String) node.getBlue().getValue();

            if (preprocessingAliases.containsKey(blueValue)) {
                Node clonedNode = node.clone();
                clonedNode.blue(new Node().blueId(preprocessingAliases.get(blueValue)));
                return new Preprocessor(nodeProvider).preprocessWithDefaultBlue(clonedNode);
            } else if (BlueIds.isPotentialBlueId(blueValue)) {
                Node clonedNode = node.clone();
                clonedNode.blue(new Node().blueId(blueValue));
                return new Preprocessor(nodeProvider).preprocessWithDefaultBlue(clonedNode);
            } else {
                throw new IllegalArgumentException("Invalid blue value: " + blueValue);
            }
        }

        return new Preprocessor(nodeProvider).preprocessWithDefaultBlue(node);
    }

    public Optional<Class<?>> determineClass(Node node) {
        if (typeClassResolver != null) {
            Class<?> clazz = typeClassResolver.resolveClass(node);
            if (clazz != null)
                return Optional.of(clazz);
        }
        return Optional.empty();
    }

    public <T> T nodeToObject(Node node, Class<T> clazz) {
        return new NodeToObjectConverter(typeClassResolver).convert(node, clazz);
    }

    public boolean isNodeSubtypeOf(Node candidateNode, Node superTypeNode) {
        return Types.isSubtype(candidateNode, superTypeNode, nodeProvider);
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
        return preprocessingAliases;
    }

    public Blue nodeProvider(NodeProvider nodeProvider) {
        this.originalNodeProvider = nodeProvider;
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        clearResolvedSnapshotCache();
        refreshDocumentProcessorConformanceEngine();
        return this;
    }

    public Blue mergingProcessor(MergingProcessor mergingProcessor) {
        this.mergingProcessor = mergingProcessor;
        clearResolvedSnapshotCache();
        refreshDocumentProcessorConformanceEngine();
        return this;
    }

    public Blue typeClassResolver(TypeClassResolver typeClassResolver) {
        this.typeClassResolver = typeClassResolver;
        return this;
    }

    public Blue preprocessingAliases(Map<String, String> preprocessingAliases) {
        this.preprocessingAliases = preprocessingAliases;
        return this;
    }

    private DocumentProcessor ensureDocumentProcessor() {
        if (documentProcessor == null) {
            documentProcessor = createDefaultDocumentProcessor();
        }
        return documentProcessor;
    }

    private DocumentProcessor createDefaultDocumentProcessor() {
        return DocumentProcessor.builder()
                .withConformanceEngine(conformanceEngine())
                .withSnapshotManager(processingSnapshotManager())
                .withMatchingService(new ContractMatchingService(this))
                .build();
    }

    private DocumentProcessingResult attachProcessingSnapshot(DocumentProcessor processor, DocumentProcessingResult result) {
        if (result == null || result.capabilityFailure() || result.snapshot() != null) {
            return result;
        }
        long start = System.nanoTime();
        try {
            return result.withSnapshot(resolveProcessingSnapshot(result.document()));
        } finally {
            long nanos = System.nanoTime() - start;
            processor.processingMetricsSink().addResultSnapshotAttachNanos(nanos);
            processor.processingMetricsSink().addBlueIdCalculationNanos(nanos);
        }
    }

    private void refreshDocumentProcessorConformanceEngine() {
        if (documentProcessor != null) {
            documentProcessor = new DocumentProcessor(documentProcessor.getContractRegistry(),
                    documentProcessor.getContractTypeResolver(),
                    conformanceEngine(),
                    processingSnapshotManager(),
                    new ContractMatchingService(this),
                    documentProcessor.processingMetricsSink());
        }
    }

    private ProcessingSnapshotManager processingSnapshotManager() {
        return new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(Node document) {
                return resolveProcessingSnapshot(document);
            }

            @Override
            public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
                return applyProcessingCanonicalPatch(snapshot, patch);
            }

            @Override
            public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
                return Blue.this.cacheSnapshot(snapshot);
            }
        };
    }

    private ResolvedSnapshot resolveProcessingSnapshot(Node node) {
        Node preprocessed = preprocess(node.clone());
        Node resolved = new Merger(mergingProcessor, processorSnapshotNodeProvider(), resolvedReferenceCache)
                .resolve(preprocessed.clone(), NO_LIMITS);
        Node canonical = new MergeReverser().reverseToCanonicalOverlay(resolved.clone());
        FrozenNode canonicalRoot = FrozenNode.fromUncheckedCanonicalNode(canonical);
        return cacheSnapshot(new ResolvedSnapshot(canonicalRoot, resolvedReferenceCache.freezeResolved(resolved), canonicalRoot.blueId()));
    }

    private ResolvedSnapshot applyProcessingCanonicalPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
        return applyCanonicalPatch(snapshot, patch, processorSnapshotNodeProvider());
    }

    private ResolvedSnapshot applyCanonicalPatch(ResolvedSnapshot snapshot, JsonPatch patch, NodeProvider snapshotNodeProvider) {
        CanonicalPatchResult patched = snapshot.applyCanonicalPatch(patch);
        ResolvedSnapshot patchedSnapshot = snapshotFromCanonical(patched.root(), snapshotNodeProvider);
        if (!canMinimizePatchedOverride(patch)) {
            return patchedSnapshot;
        }

        CanonicalPatchResult withoutOverride;
        try {
            withoutOverride = new CanonicalOverlayPatchEngine(patched.root()).apply(JsonPatch.remove(patched.path()));
        } catch (RuntimeException ignored) {
            return patchedSnapshot;
        }

        ResolvedSnapshot inheritedSnapshot = snapshotFromCanonical(withoutOverride.root(), snapshotNodeProvider);
        FrozenNode patchedEffective = patchedSnapshot.resolvedAt(patched.path());
        FrozenNode inheritedEffective = inheritedSnapshot.resolvedAt(patched.path());
        if (patchedEffective != null
                && inheritedEffective != null
                && patchedEffective.blueId().equals(inheritedEffective.blueId())) {
            return inheritedSnapshot;
        }
        return patchedSnapshot;
    }

    private ResolvedSnapshot snapshotFromCanonical(FrozenNode canonicalRoot, NodeProvider snapshotNodeProvider) {
        ResolvedSnapshot cached = resolvedSnapshotsByBlueId.get(canonicalRoot.blueId());
        if (cached != null) {
            return cached;
        }
        Node canonical = canonicalRoot.toNode();
        Node resolved = new Merger(mergingProcessor, snapshotNodeProvider, resolvedReferenceCache)
                .resolve(canonical.clone(), NO_LIMITS);
        return cacheSnapshot(new ResolvedSnapshot(canonicalRoot, resolvedReferenceCache.freezeResolved(resolved), canonicalRoot.blueId()));
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
        Set<String> processorTypeBlueIds = new HashSet<>(PROCESSOR_MANAGED_TYPE_BLUE_IDS);
        if (documentProcessor != null) {
            processorTypeBlueIds.addAll(documentProcessor.getContractRegistry().processors().keySet());
        }
        return NodeProviderWrapper.unverified(blueId -> {
            if (processorTypeBlueIds.contains(blueId) || !BlueIds.isPotentialBlueId(blueId)) {
                return Collections.singletonList(new Node().name(blueId));
            }
            return originalNodeProvider.fetchByBlueId(blueId);
        });
    }

    private ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
        resolvedReferenceCache.putIfAbsent(snapshot.blueId(), snapshot.frozenResolvedRoot());
        resolvedReferenceCache.indexResolved(snapshot.frozenResolvedRoot());
        ResolvedSnapshot existing = resolvedSnapshotsByBlueId.putIfAbsent(snapshot.blueId(), snapshot);
        return existing != null ? existing : snapshot;
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
