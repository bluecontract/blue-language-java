package blue.language.processor;

import blue.language.BlueCachePolicy;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.Contract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.Nodes;
import blue.language.utils.TypeClassResolver;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Parses contracts under a scope and produces a {@link ContractBundle}.
 */
final class ContractLoader {

    private static final Set<String> INVALID_CONTRACT_KEYS = new LinkedHashSet<>();

    static {
        INVALID_CONTRACT_KEYS.add("type");
        INVALID_CONTRACT_KEYS.add("value");
        INVALID_CONTRACT_KEYS.add("items");
        INVALID_CONTRACT_KEYS.add("schema");
        INVALID_CONTRACT_KEYS.add("contracts");
        INVALID_CONTRACT_KEYS.add("properties");
        INVALID_CONTRACT_KEYS.add("constraints");
    }

    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;
    private final TypeClassResolver typeResolver;
    private final BundleCache bundleCache;

    ContractLoader(ContractProcessorRegistry registry,
                   NodeToObjectConverter converter,
                   TypeClassResolver typeResolver) {
        this(registry, converter, typeResolver, BlueCachePolicy.boundedDefaults());
    }

    ContractLoader(ContractProcessorRegistry registry,
                   NodeToObjectConverter converter,
                   TypeClassResolver typeResolver,
                   BlueCachePolicy cachePolicy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
        this.bundleCache = new BundleCache(Objects.requireNonNull(cachePolicy, "cachePolicy"));
    }

    ContractBundle load(ResolvedSnapshot snapshot, String scopePath) {
        Objects.requireNonNull(snapshot, "snapshot");
        return load(snapshot.canonicalAt(scopePath), snapshot.resolvedAt(scopePath), scopePath);
    }

    ContractBundle load(FrozenNode scopeNode, String scopePath) {
        return load(scopeNode, scopeNode, scopePath);
    }

    ContractBundle load(FrozenNode scopeNode, String scopePath, ProcessingMetricsSink metricsSink) {
        return load(scopeNode, scopeNode, scopePath, metricsSink);
    }

    ContractBundle load(FrozenNode selectedScopeNode,
                        FrozenNode effectiveScopeNode,
                        String scopePath) {
        return load(selectedScopeNode, effectiveScopeNode, scopePath, ProcessingMetricsSink.NOOP);
    }

    ContractBundle load(FrozenNode selectedScopeNode,
                        FrozenNode effectiveScopeNode,
                        String scopePath,
                        ProcessingMetricsSink metricsSink) {
        Node selectedScope = selectedScopeNode != null ? selectedContractContainer(selectedScopeNode) : null;
        return load(selectedScope, effectiveScopeNode, scopePath, metricsSink);
    }

    private Node selectedContractContainer(FrozenNode selectedScopeNode) {
        Node selectedScope = new Node();
        FrozenNode selectedContracts = property(selectedScopeNode, "contracts");
        if (selectedContracts != null) {
            selectedScope.contracts(selectedContracts.toNode());
        }
        return selectedScope;
    }

    ContractBundle load(Node selectedScopeNode,
                        FrozenNode effectiveScopeNode,
                        String scopePath,
                        ProcessingMetricsSink metricsSink) {
        ProcessingMetricsSink metrics = metricsSink != null ? metricsSink : ProcessingMetricsSink.NOOP;
        long keyStart = System.nanoTime();
        BundleCacheKey key;
        try {
            key = cacheKey(selectedScopeNode, effectiveScopeNode, scopePath);
        } finally {
            metrics.addBundleLoadCacheKeyBuildNanos(System.nanoTime() - keyStart);
        }
        ContractBundle cached = bundleCache.get(key);
        if (cached != null) {
            metrics.incrementBundleLoadCacheHits();
            long reuseStart = System.nanoTime();
            try {
                RuntimeMarkers runtimeMarkers = runtimeMarkers(selectedScopeNode, effectiveScopeNode);
                metrics.incrementBundlesReused();
                return cached.copyWithRuntimeMarkers(runtimeMarkers.markers,
                        runtimeMarkers.nodes,
                        runtimeMarkers.checkpointDeclared);
            } finally {
                metrics.addBundleLoadReuseNanos(System.nanoTime() - reuseStart);
            }
        }

        metrics.incrementBundleLoadCacheMisses();
        long buildStart = System.nanoTime();
        ContractBundle built;
        try {
            built = build(selectedScopeNode, effectiveScopeNode, scopePath);
        } finally {
            metrics.addBundleLoadActualBuildNanos(System.nanoTime() - buildStart);
        }
        bundleCache.putIfAbsent(key, built);
        metrics.incrementBundlesBuilt();
        RuntimeMarkers runtimeMarkers = runtimeMarkers(selectedScopeNode, effectiveScopeNode);
        return built.copyWithRuntimeMarkers(runtimeMarkers.markers,
                runtimeMarkers.nodes,
                runtimeMarkers.checkpointDeclared);
    }

    void clearCaches() {
        bundleCache.clear();
    }

    int cacheSize() {
        return bundleCache.size();
    }

    long cacheWeightBytes() {
        return bundleCache.currentWeightBytes();
    }

    private ContractBundle build(Node selectedScopeNode,
                                 FrozenNode effectiveScopeNode,
                                 String scopePath) {
        ContractBundle.Builder builder = ContractBundle.builder();
        if (selectedScopeNode == null) {
            return builder.build();
        }
        Node selectedContractsNode = selectedScopeNode.getContracts();
        if (selectedContractsNode == null) {
            return builder.build();
        }
        if (selectedContractsNode.getProperties() == null) {
            if (Nodes.isEmptyNode(selectedContractsNode)) {
                return builder.build();
            }
            throw new MustUnderstandFailureException("Contracts must be an object map",
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }

        FrozenNode effectiveContractsNode = property(effectiveScopeNode, "contracts");
        Map<String, FrozenNode> effectiveContractNodes = effectiveContractsNode != null
                && effectiveContractsNode.getProperties() != null
                ? effectiveContractsNode.getProperties()
                : java.util.Collections.emptyMap();
        Map<String, FrozenNode> contractNodes = new LinkedHashMap<>();
        for (String key : selectedContractsNode.getProperties().keySet()) {
            validateContractKey(key);
            contractNodes.put(key, effectiveContractNodes.get(key));
        }
        Map<String, String> contractTypeBlueIds = new LinkedHashMap<>();
        for (Map.Entry<String, FrozenNode> entry : contractNodes.entrySet()) {
            String typeBlueId = typeBlueId(entry.getValue());
            if (typeBlueId != null) {
                contractTypeBlueIds.put(entry.getKey(), typeBlueId);
            }
        }

        for (Map.Entry<String, FrozenNode> entry : contractNodes.entrySet()) {
            String key = entry.getKey();
            String typeBlueId = contractTypeBlueIds.get(key);
            if (typeBlueId == null) {
                throw new MustUnderstandFailureException(
                        "Contract '" + key + "' must declare a type",
                        ProcessorErrorCategory.UnsupportedContract);
            }
            Class<?> contractClass = typeResolver.resolveClass(typeBlueId);
            if (contractClass == null || !Contract.class.isAssignableFrom(contractClass)) {
                throw new MustUnderstandFailureException("Unsupported contract type: " + typeBlueId,
                        ProcessorErrorCategory.UnsupportedContract);
            }
            Contract contract = converter.convertWithType(entry.getValue().toNode(), Contract.class, false);
            if (contract == null) {
                continue;
            }
            contract.setKey(key);
            contract.setTypeBlueId(typeBlueId);
            if (contract instanceof ChannelContract) {
                ChannelContract channel = (ChannelContract) contract;
                if (!ProcessorContractConstants.isProcessorManagedChannel(channel)
                        && !registry.lookupChannel(channel).isPresent()) {
                    throw new MustUnderstandFailureException(
                            "Unsupported contract type: " + typeBlueId,
                            ProcessorErrorCategory.UnsupportedContract);
                }
                builder.addChannel(key, channel, entry.getValue());
            } else if (contract instanceof HandlerContract) {
                HandlerContract handler = (HandlerContract) contract;
                Optional<HandlerProcessor<? extends HandlerContract>> processor = registry.lookupHandler(handler);
                if (!processor.isPresent()) {
                    throw new MustUnderstandFailureException(
                            "Unsupported contract type: " + typeBlueId,
                            ProcessorErrorCategory.UnsupportedContract);
                }
                String channelKey = resolveHandlerChannel(scopePath,
                        key,
                        handler,
                        processor.get(),
                        contractNodes,
                        contractTypeBlueIds);
                handler.setChannelKey(channelKey);
                if (hasRegisteredSameScopeChannel(channelKey, contractNodes, contractTypeBlueIds)) {
                    builder.addHandler(key, handler, entry.getValue());
                }
            } else if (contract instanceof ProcessEmbedded) {
                validateEmbeddedPaths((ProcessEmbedded) contract);
                builder.setEmbedded((ProcessEmbedded) contract, entry.getValue());
            } else if (contract instanceof MarkerContract) {
                builder.addMarker(key, (MarkerContract) contract, entry.getValue());
            }
        }

        return builder.build();
    }

    private void validateContractKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new MustUnderstandFailureException("Invalid contract key: key must be non-empty",
                    ProcessorErrorCategory.InvalidRuntimePointer);
        }
        if (INVALID_CONTRACT_KEYS.contains(key)) {
            throw new MustUnderstandFailureException("Invalid contract key: reserved key '" + key + "'",
                    ProcessorErrorCategory.InvalidReservedMarker);
        }
    }

    private void validateEmbeddedPaths(ProcessEmbedded embedded) {
        Set<String> seen = new LinkedHashSet<>();
        for (String path : embedded.getPaths()) {
            if (!seen.add(path)) {
                throw new MustUnderstandFailureException("Unique items are required for Process Embedded paths",
                        ProcessorErrorCategory.BoundaryViolation);
            }
        }
    }

    private BundleCacheKey cacheKey(Node selectedScopeNode,
                                    FrozenNode effectiveScopeNode,
                                    String scopePath) {
        FrozenNode contractsNode = property(effectiveScopeNode, "contracts");
        FrozenNode channelBindingsNode = property(effectiveScopeNode, "channelBindings");
        return new BundleCacheKey(scopePath != null ? scopePath : "/",
                registry.version(),
                selectedContractKeysSignature(selectedScopeNode, contractsNode),
                contractsSignature(contractsNode),
                nodeSignature(channelBindingsNode));
    }

    private String selectedContractKeysSignature(Node selectedScopeNode, FrozenNode effectiveContractsNode) {
        Node contractsNode = selectedScopeNode != null ? selectedScopeNode.getContracts() : null;
        if (contractsNode == null) {
            return effectiveContractsNode == null ? "<matches-effective>" : "<missing>";
        }
        Map<String, Node> properties = contractsNode.getProperties();
        if (properties == null) {
            if (Nodes.isEmptyNode(contractsNode)
                    && (effectiveContractsNode == null || effectiveContractsNode.isEmptyNode())) {
                return "<matches-effective>";
            }
            return Nodes.isEmptyNode(contractsNode) ? "<empty>" : "<non-object>";
        }
        Map<String, FrozenNode> effectiveProperties = effectiveContractsNode != null
                ? effectiveContractsNode.getProperties()
                : null;
        if (sameOrderedKeys(properties, effectiveProperties)) {
            return "<matches-effective>";
        }
        StringBuilder builder = new StringBuilder("contracts{");
        for (String key : properties.keySet()) {
            builder.append(key.length()).append(':').append(key).append(';');
        }
        return builder.append('}').toString();
    }

    private boolean sameOrderedKeys(Map<String, Node> selected, Map<String, FrozenNode> effective) {
        if (effective == null || selected.size() != effective.size()) {
            return false;
        }
        Iterator<String> selectedKeys = selected.keySet().iterator();
        Iterator<String> effectiveKeys = effective.keySet().iterator();
        while (selectedKeys.hasNext()) {
            if (!Objects.equals(selectedKeys.next(), effectiveKeys.next())) {
                return false;
            }
        }
        return true;
    }

    private String contractsSignature(FrozenNode contractsNode) {
        if (contractsNode == null) {
            return "<missing>";
        }
        Map<String, FrozenNode> properties = contractsNode.getProperties();
        if (properties == null || !properties.containsKey(ProcessorContractConstants.KEY_CHECKPOINT)) {
            return nodeSignature(contractsNode);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("contracts{");
        for (Map.Entry<String, FrozenNode> entry : properties.entrySet()) {
            builder.append(entry.getKey()).append('=');
            if (ProcessorContractConstants.KEY_CHECKPOINT.equals(entry.getKey())) {
                builder.append(checkpointStaticSignature(entry.getValue()));
            } else {
                builder.append(nodeSignature(entry.getValue()));
            }
            builder.append(';');
        }
        builder.append('}');
        return builder.toString();
    }

    private String checkpointStaticSignature(FrozenNode checkpointNode) {
        if (checkpointNode == null) {
            return "<missing>";
        }
        Node node = checkpointNode.toNode();
        if (node.getProperties() != null) {
            node.getProperties().remove("lastEvents");
        }
        return FrozenNode.fromResolvedNode(node).blueId();
    }

    private String nodeSignature(FrozenNode node) {
        return node != null ? node.blueId() : "<missing>";
    }

    private FrozenNode property(FrozenNode node, String key) {
        if (node != null && "contracts".equals(key)) {
            return node.getContracts();
        }
        return node != null && node.getProperties() != null ? node.getProperties().get(key) : null;
    }

    private RuntimeMarkers runtimeMarkers(Node selectedScopeNode, FrozenNode effectiveScopeNode) {
        Map<String, MarkerContract> markers = new LinkedHashMap<>();
        Map<String, FrozenNode> markerNodes = new LinkedHashMap<>();
        boolean checkpointDeclared = false;
        Node selectedContractsNode = selectedScopeNode != null ? selectedScopeNode.getContracts() : null;
        FrozenNode effectiveContractsNode = property(effectiveScopeNode, "contracts");
        if (selectedContractsNode == null
                || selectedContractsNode.getProperties() == null
                || effectiveContractsNode == null
                || effectiveContractsNode.getProperties() == null) {
            return new RuntimeMarkers(markers, markerNodes, false);
        }
        for (String key : selectedContractsNode.getProperties().keySet()) {
            FrozenNode node = effectiveContractsNode.getProperties().get(key);
            String typeBlueId = typeBlueId(node);
            if (typeBlueId == null) {
                continue;
            }
            Class<?> contractClass = typeResolver.resolveClass(typeBlueId);
            if (contractClass == null || !MarkerContract.class.isAssignableFrom(contractClass)) {
                continue;
            }
            Contract contract = converter.convertWithType(node.toNode(), Contract.class, false);
            if (!(contract instanceof MarkerContract) || contract instanceof ProcessEmbedded) {
                continue;
            }
            MarkerContract marker = (MarkerContract) contract;
            marker.setKey(key);
            marker.setTypeBlueId(typeBlueId);
            if (ProcessorContractConstants.KEY_CHECKPOINT.equals(key) && !(marker instanceof ChannelEventCheckpoint)) {
                throw new IllegalStateException(
                        "Reserved key 'checkpoint' must contain a Channel Event Checkpoint");
            }
            if (marker instanceof ChannelEventCheckpoint) {
                if (!ProcessorContractConstants.KEY_CHECKPOINT.equals(key)) {
                    throw new IllegalStateException(
                            "Channel Event Checkpoint must use reserved key 'checkpoint' at key '" + key + "'");
                }
                if (checkpointDeclared) {
                    throw new IllegalStateException("Duplicate Channel Event Checkpoint markers detected in same contracts map");
                }
                checkpointDeclared = true;
            }
            markers.put(key, marker);
            markerNodes.put(key, node);
        }
        return new RuntimeMarkers(markers, markerNodes, checkpointDeclared);
    }

    @SuppressWarnings("unchecked")
    private String resolveHandlerChannel(String scopePath,
                                         String handlerKey,
                                         HandlerContract handler,
                                         HandlerProcessor<? extends HandlerContract> processor,
                                         Map<String, FrozenNode> contractNodes,
                                         Map<String, String> contractTypeBlueIds) {
        String channelKey = trimToNull(handler.getChannelKey());
        if (channelKey == null) {
            HandlerRegistrationContext context = new HandlerRegistrationContext(scopePath,
                    handlerKey,
                    contractNodes,
                    contractTypeBlueIds,
                    converter);
            HandlerProcessor<HandlerContract> typed = (HandlerProcessor<HandlerContract>) processor;
            channelKey = trimToNull(typed.deriveChannel(handler, context));
        }
        if (channelKey == null) {
            throw new IllegalStateException(
                    "Handler " + handlerKey + " must declare channel or derive one from its processor");
        }
        return channelKey;
    }

    private boolean hasRegisteredSameScopeChannel(String channelKey,
                                                  Map<String, FrozenNode> contractNodes,
                                                  Map<String, String> contractTypeBlueIds) {
        FrozenNode channelNode = contractNodes.get(channelKey);
        if (channelNode == null) {
            return false;
        }
        String channelTypeBlueId = contractTypeBlueIds.get(channelKey);
        if (channelTypeBlueId == null) {
            return false;
        }
        Class<?> channelClass = typeResolver.resolveClass(channelTypeBlueId);
        if (channelClass == null || !ChannelContract.class.isAssignableFrom(channelClass)) {
            return false;
        }
        Contract channelContract = converter.convertWithType(channelNode.toNode(), Contract.class, false);
        if (!(channelContract instanceof ChannelContract)) {
            return false;
        }
        ChannelContract channel = (ChannelContract) channelContract;
        channel.setKey(channelKey);
        channel.setTypeBlueId(channelTypeBlueId);
        if (!ProcessorContractConstants.isProcessorManagedChannel(channel)
                && !registry.lookupChannel(channel).isPresent()) {
            return false;
        }
        return true;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String typeBlueId(FrozenNode node) {
        if (node == null || node.getType() == null) {
            return null;
        }
        FrozenNode type = node.getType();
        return type.getReferenceBlueId() != null ? type.getReferenceBlueId() : type.blueId();
    }

    private static final class BundleCache {
        private final int maximumEntries;
        private final long maximumWeightBytes;
        private final long maximumEntryWeightBytes;
        private final LinkedHashMap<BundleCacheKey, BundleCacheEntry> entries =
                new LinkedHashMap<BundleCacheKey, BundleCacheEntry>(16, 0.75f, true);
        private long currentWeightBytes;

        private BundleCache(BlueCachePolicy policy) {
            this.maximumEntries = policy.conformancePlanMaxEntries();
            this.maximumWeightBytes = policy.conformancePlanMaxWeightBytes();
            this.maximumEntryWeightBytes = Math.min(
                    policy.maximumDerivedEntryWeightBytes(), maximumWeightBytes);
        }

        private synchronized ContractBundle get(BundleCacheKey key) {
            BundleCacheEntry entry = entries.get(key);
            return entry != null ? entry.bundle : null;
        }

        private synchronized void putIfAbsent(BundleCacheKey key, ContractBundle bundle) {
            if (entries.containsKey(key)) {
                entries.get(key);
                return;
            }
            long weight = estimateWeight(key, bundle);
            if (weight > maximumEntryWeightBytes || weight > maximumWeightBytes) {
                return;
            }
            entries.put(key, new BundleCacheEntry(bundle, weight));
            currentWeightBytes = saturatedAdd(currentWeightBytes, weight);
            evictToBounds();
        }

        private synchronized void clear() {
            entries.clear();
            currentWeightBytes = 0L;
        }

        private synchronized int size() {
            return entries.size();
        }

        private synchronized long currentWeightBytes() {
            return currentWeightBytes;
        }

        private void evictToBounds() {
            Iterator<Map.Entry<BundleCacheKey, BundleCacheEntry>> iterator =
                    entries.entrySet().iterator();
            while ((entries.size() > maximumEntries
                    || currentWeightBytes > maximumWeightBytes) && iterator.hasNext()) {
                BundleCacheEntry eldest = iterator.next().getValue();
                currentWeightBytes -= eldest.weightBytes;
                iterator.remove();
            }
        }

        private long estimateWeight(BundleCacheKey key, ContractBundle bundle) {
            long weight = 256L;
            weight = saturatedAdd(weight, retainedString(key.scopePath));
            weight = saturatedAdd(weight, retainedString(key.selectedContractKeysSignature));
            weight = saturatedAdd(weight, retainedString(key.contractsSignature));
            weight = saturatedAdd(weight, retainedString(key.channelBindingsSignature));
            weight = saturatedAdd(weight, 192L * bundle.channels().size());
            weight = saturatedAdd(weight, 160L * bundle.markers().size());
            weight = saturatedAdd(weight, 64L * bundle.embeddedPaths().size());
            for (String path : bundle.embeddedPaths()) {
                weight = saturatedAdd(weight, retainedString(path));
            }
            for (Map.Entry<String, FrozenNode> entry : bundle.contractNodes().entrySet()) {
                weight = saturatedAdd(weight, 96L + retainedString(entry.getKey()));
                weight = saturatedAdd(weight, entry.getValue().approximateRetainedWeightBytes());
            }
            for (String channelKey : bundle.channels().keySet()) {
                weight = saturatedAdd(weight, retainedString(channelKey));
                weight = saturatedAdd(weight, 160L * bundle.handlersFor(channelKey).size());
            }
            for (String markerKey : bundle.markers().keySet()) {
                weight = saturatedAdd(weight, retainedString(markerKey));
            }
            return weight;
        }

        private long retainedString(String value) {
            return value != null ? 48L + 2L * value.length() : 0L;
        }

        private long saturatedAdd(long left, long right) {
            return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
        }
    }

    private static final class BundleCacheEntry {
        private final ContractBundle bundle;
        private final long weightBytes;

        private BundleCacheEntry(ContractBundle bundle, long weightBytes) {
            this.bundle = Objects.requireNonNull(bundle, "bundle");
            this.weightBytes = weightBytes;
        }
    }

    private static final class RuntimeMarkers {
        final Map<String, MarkerContract> markers;
        final Map<String, FrozenNode> nodes;
        final boolean checkpointDeclared;

        RuntimeMarkers(Map<String, MarkerContract> markers,
                       Map<String, FrozenNode> nodes,
                       boolean checkpointDeclared) {
            this.markers = markers;
            this.nodes = nodes;
            this.checkpointDeclared = checkpointDeclared;
        }
    }

    private static final class BundleCacheKey {
        private final String scopePath;
        private final long registryVersion;
        private final String selectedContractKeysSignature;
        private final String contractsSignature;
        private final String channelBindingsSignature;

        BundleCacheKey(String scopePath,
                       long registryVersion,
                       String selectedContractKeysSignature,
                       String contractsSignature,
                       String channelBindingsSignature) {
            this.scopePath = scopePath;
            this.registryVersion = registryVersion;
            this.selectedContractKeysSignature = selectedContractKeysSignature;
            this.contractsSignature = contractsSignature;
            this.channelBindingsSignature = channelBindingsSignature;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof BundleCacheKey)) {
                return false;
            }
            BundleCacheKey that = (BundleCacheKey) other;
            return registryVersion == that.registryVersion
                    && Objects.equals(scopePath, that.scopePath)
                    && Objects.equals(selectedContractKeysSignature, that.selectedContractKeysSignature)
                    && Objects.equals(contractsSignature, that.contractsSignature)
                    && Objects.equals(channelBindingsSignature, that.channelBindingsSignature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scopePath,
                    registryVersion,
                    selectedContractKeysSignature,
                    contractsSignature,
                    channelBindingsSignature);
        }
    }
}
