package blue.language.processor;

import blue.language.utils.Properties;

import blue.language.BlueCachePolicy;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.CheckpointEntry;
import blue.language.processor.model.Contract;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.JsonPointer;
import blue.language.utils.Nodes;
import blue.language.utils.TypeClassResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Parses one selected/effective scope pair into a {@link ContractBundle}.
 *
 * <p>Header recognition is exact and provider-verified. Registered executable
 * bodies stay collapsed until selected, while effective source-contribution
 * identities and body provenance remain available as immutable metadata.
 * Cached bundles never carry invocation-local runtime markers.</p>
 */
final class ContractLoader {

    private static final String LEGACY_CHANNEL_BINDINGS_PROPERTY =
            "channelBindings";
    private static final String LEGACY_LAST_EVENTS_PROPERTY =
            "lastEvents";
    private static final String HANDLER_EVENT_MATCHER_FIELD =
            EffectiveContractSnapshotConstants.DispatchField.EVENT;
    private static final Set<String> INVALID_CONTRACT_KEYS = new LinkedHashSet<>();

    static {
        INVALID_CONTRACT_KEYS.add(Properties.OBJECT_TYPE);
        INVALID_CONTRACT_KEYS.add(Properties.OBJECT_VALUE);
        INVALID_CONTRACT_KEYS.add(Properties.OBJECT_ITEMS);
        INVALID_CONTRACT_KEYS.add(Properties.OBJECT_SCHEMA);
        INVALID_CONTRACT_KEYS.add(ProcessorContractConstants.KEY_CONTRACTS);
        INVALID_CONTRACT_KEYS.add(
                Properties.LEGACY_OBJECT_PROPERTIES);
        INVALID_CONTRACT_KEYS.add(
                Properties.LEGACY_OBJECT_CONSTRAINTS);
    }

    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;
    private final TypeClassResolver typeResolver;
    private final BundleCache bundleCache;
    private final ContractContributionResolver contributionResolver;
    private GasSchedule gasSchedule =
            GasSchedule.contracts10();

    ContractLoader(ContractProcessorRegistry registry,
                   NodeToObjectConverter converter,
                   TypeClassResolver typeResolver) {
        this(registry, converter, typeResolver, BlueCachePolicy.boundedDefaults());
    }

    ContractLoader(ContractProcessorRegistry registry,
                   NodeToObjectConverter converter,
                   TypeClassResolver typeResolver,
                   BlueCachePolicy cachePolicy) {
        this(registry, converter, typeResolver, cachePolicy, null);
    }

    ContractLoader(ContractProcessorRegistry registry,
                   NodeToObjectConverter converter,
                   TypeClassResolver typeResolver,
                   BlueCachePolicy cachePolicy,
                   blue.language.NodeProvider contributionProvider) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
        this.bundleCache = new BundleCache(Objects.requireNonNull(cachePolicy, "cachePolicy"));
        this.contributionResolver =
                new ContractContributionResolver(contributionProvider);
    }

    void gasSchedule(GasSchedule gasSchedule) {
        this.gasSchedule =
                Objects.requireNonNull(
                        gasSchedule, "gasSchedule");
        contributionResolver.gasSchedule(
                this.gasSchedule);
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
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                metricsSink,
                null,
                null);
    }

    ContractBundle load(FrozenNode selectedScopeNode,
                        FrozenNode effectiveScopeNode,
                        String scopePath,
                        ProcessingMetricsSink metricsSink,
                        ContractRecognitionMeter recognitionMeter,
                        String recognitionReason) {
        Node selectedScope = selectedScopeNode != null ? selectedContractContainer(selectedScopeNode) : null;
        return load(
                selectedScope,
                effectiveScopeNode,
                scopePath,
                metricsSink,
                recognitionMeter,
                recognitionReason);
    }

    /**
     * Loads only the immutable headers needed to classify one feeder
     * candidate.  Phase-B classification must not recognize unrelated
     * application contracts: rejected and stale-only candidates never create
     * a participating closure.
     */
    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ProcessingMetricsSink metricsSink) {
        return loadExternalClassification(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                channelKey,
                includeProcessEmbedded,
                metricsSink,
                null,
                null);
    }

    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ProcessingMetricsSink metricsSink,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason) {
        return loadExternalClassification(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                channelKey,
                includeProcessEmbedded,
                ExternalChannelDependencySnapshot.none(),
                metricsSink,
                recognitionMeter,
                recognitionReason);
    }

    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ExternalChannelDependencySnapshot declaredDependencies,
            ProcessingMetricsSink metricsSink,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason) {
        Set<String> retainedKeys = new LinkedHashSet<>();
        if (channelKey != null) {
            retainedKeys.add(channelKey);
        }
        retainDeclaredClassificationDependencies(
                retainedKeys,
                Objects.requireNonNull(
                        declaredDependencies,
                        "declaredDependencies"));
        if (includeProcessEmbedded) {
            /*
             * Process Embedded is a contract type, not a raw-key convention.
             * Restrict the scan to the selected/effective same-scope contract
             * maps, then retain only declarations whose effective header is
             * Process Embedded. LinkedHashMap encounter order makes the scan
             * deterministic without broadening Phase-B classification to
             * unrelated contract bodies.
             */
            collectProcessEmbeddedKeys(
                    selectedScopeNode, retainedKeys);
            collectProcessEmbeddedKeys(
                    effectiveScopeNode, retainedKeys);
        }
        Node selectedScope = filterScopeContracts(
                selectedScopeNode, retainedKeys);
        Node effectiveScope = filterScopeContracts(
                effectiveScopeNode, retainedKeys);
        FrozenNode frozenEffective = effectiveScope != null
                ? FrozenNode.fromResolvedNode(effectiveScope)
                : null;
        return load(
                selectedScope,
                frozenEffective,
                scopePath,
                metricsSink,
                recognitionMeter,
                recognitionReason);
    }

    private void retainDeclaredClassificationDependencies(
            Set<String> retainedKeys,
            ExternalChannelDependencySnapshot dependencies) {
        for (ExternalChannelDependencySnapshot.Entry dependency
                : dependencies.entries()) {
            retainedKeys.add(dependency.channelKey());
        }
        for (ExternalChannelDependencySnapshot.TypeFamily family
                : dependencies.typeFamilies()) {
            for (ExternalChannelDependencySnapshot.Member member
                    : family.members()) {
                retainedKeys.add(member.channelKey());
            }
        }
        for (ExternalChannelDependencySnapshot.ChannelEntry channel
                : dependencies.channelEntries()) {
            retainedKeys.add(channel.channelKey());
        }
    }

    private Node selectedContractContainer(FrozenNode selectedScopeNode) {
        Node selectedScope = new Node();
        if (selectedScopeNode.getType() != null) {
            selectedScope.type(selectedScopeNode.getType().toNode());
        }
        FrozenNode selectedContracts = property(selectedScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (selectedContracts != null) {
            selectedScope.contracts(selectedContracts.toNode());
        }
        MaterializationProvenance.clear(selectedScope);
        return selectedScope;
    }

    private void collectProcessEmbeddedKeys(
            FrozenNode scopeNode,
            Set<String> retainedKeys) {
        FrozenNode contracts = property(scopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null
                || contracts.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, FrozenNode> entry
                : contracts.getProperties().entrySet()) {
            FrozenNode contract = entry.getValue();
            if (contract != null
                    && isProcessEmbeddedContract(
                    contract)) {
                retainedKeys.add(entry.getKey());
            }
        }
    }

    private Node filterScopeContracts(
            FrozenNode scopeNode,
            Set<String> retainedKeys) {
        if (scopeNode == null) {
            return null;
        }
        Node filtered = new Node();
        if (scopeNode.getType() != null) {
            filtered.type(scopeNode.getType().toNode());
        }
        FrozenNode contracts = property(scopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null) {
            MaterializationProvenance.clear(filtered);
            return filtered;
        }
        if (contracts.getProperties() == null) {
            filtered.contracts(contracts.toNode());
            MaterializationProvenance.clear(filtered);
            return filtered;
        }
        Node retained = new Node();
        for (Map.Entry<String, FrozenNode> entry
                : contracts.getProperties().entrySet()) {
            if (isDirectProcessorStateKey(entry.getKey())
                    || retainedKeys.contains(entry.getKey())) {
                retained.properties(
                        entry.getKey(),
                        entry.getValue().toNode());
            }
        }
        if (retained.getProperties() != null
                && !retained.getProperties().isEmpty()) {
            filtered.contracts(retained);
        }
        MaterializationProvenance.clear(filtered);
        return filtered;
    }

    ContractBundle load(Node selectedScopeNode,
                        FrozenNode effectiveScopeNode,
                        String scopePath,
                        ProcessingMetricsSink metricsSink) {
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                metricsSink,
                null,
                null);
    }

    ContractBundle load(Node selectedScopeNode,
                        FrozenNode effectiveScopeNode,
                        String scopePath,
                        ProcessingMetricsSink metricsSink,
                        ContractRecognitionMeter recognitionMeter,
                        String recognitionReason) {
        ProcessingMetricsSink metrics = metricsSink != null ? metricsSink : ProcessingMetricsSink.NOOP;
        requireRegisteredProviderEvidence(effectiveScopeNode);
        /*
         * A bundle cache is a physical optimization. Metered PROCESS
         * recognition must execute the same logical reads and charges on warm
         * and cold invocations, so it deliberately bypasses this shared cache.
         */
        if (recognitionMeter != null) {
            long buildStart = System.nanoTime();
            ContractBundle built;
            try {
                built = build(
                        selectedScopeNode,
                        effectiveScopeNode,
                        scopePath,
                        recognitionMeter,
                        recognitionReason);
            } finally {
                metrics.addBundleLoadActualBuildNanos(
                        System.nanoTime() - buildStart);
            }
            metrics.incrementBundlesBuilt();
            RuntimeMarkers runtimeMarkers =
                    runtimeMarkers(selectedScopeNode, effectiveScopeNode);
            return built.copyWithRuntimeMarkers(
                    runtimeMarkers.markers,
                    runtimeMarkers.nodes,
                    runtimeMarkers.checkpointDeclared);
        }
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
            built = build(
                    selectedScopeNode,
                    effectiveScopeNode,
                    scopePath,
                    null,
                    null);
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

    private void requireRegisteredProviderEvidence(
            FrozenNode effectiveScopeNode) {
        /*
         * An explicit Java dispatch mapping is not provider evidence. A
         * provider-backed resolved view expands the type node; an exact
         * canonical registration clears the registry demand.
         */
        FrozenNode contracts =
                property(effectiveScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        Map<String, FrozenNode> entries =
                contracts != null ? contracts.getProperties() : null;
        if (entries == null) {
            return;
        }
        for (Map.Entry<String, FrozenNode> entry
                : entries.entrySet()) {
            if (isDirectProcessorStateKey(entry.getKey())) {
                continue;
            }
            FrozenNode contract = entry.getValue();
            String blueId = typeBlueId(contract);
            if (blueId == null
                    || !registry.requiresProviderEvidence(blueId)) {
                continue;
            }
            FrozenNode resolvedType =
                    contract != null ? contract.getType() : null;
            if (resolvedType == null
                    || resolvedType.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Missing provider content for registered contract BlueId "
                                + blueId);
            }
        }
    }

    void clearCaches() {
        bundleCache.clear();
    }

    /**
     * Opens only the executable body of a Handler whose matcher has already
     * succeeded. Preflight and nonmatching candidates retain exact body
     * references and therefore make no provider demand for them.
     */
    ContractBundle.HandlerBinding materializeSelectedExecutableBodies(
            ContractBundle.HandlerBinding binding,
            Function<FrozenNode, FrozenNode> materializer) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(materializer, "materializer");
        FrozenNode frozen = binding.node();
        if (frozen == null) {
            return binding;
        }
        Node executable = frozen.toNode();
        for (String field : binding.executableBodyFields()) {
            materializeExecutableField(
                    executable, frozen, field, materializer);
        }
        if (binding.executableBodyFields().isEmpty()) {
            return binding;
        }
        Node exactEventMatcher =
                binding.contract().getEvent();
        Contract converted = converter.convertWithType(
                matcherHeaderNode(
                        executable,
                        Collections.singletonList(
                                HANDLER_EVENT_MATCHER_FIELD)),
                Contract.class,
                false);
        if (!(converted instanceof HandlerContract)) {
            throw new MustUnderstandFailureException(
                    "Selected executable body no longer belongs to a Handler",
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        HandlerContract handler = (HandlerContract) converted;
        restoreEventMatcher(handler, exactEventMatcher);
        handler.setKey(binding.key());
        handler.setTypeBlueId(
                binding.contract().getTypeBlueId());
        handler.setChannelKey(
                binding.contract().getChannelKey());
        return new ContractBundle.HandlerBinding(
                binding.key(),
                handler,
                FrozenNode.fromResolvedNode(executable),
                binding.executableBodyFields());
    }

    private void materializeExecutableField(
            Node executable,
            FrozenNode frozen,
            String field,
            Function<FrozenNode, FrozenNode> materializer) {
        FrozenNode body = property(frozen, field);
        if (body == null || !body.isReferenceOnly()) {
            return;
        }
        FrozenNode materialized = materializer.apply(body);
        executable.properties(
                field, materialized.toNode());
    }

    int cacheSize() {
        return bundleCache.size();
    }

    long cacheWeightBytes() {
        return bundleCache.currentWeightBytes();
    }

    boolean isProcessEmbeddedContract(Node contractNode) {
        if (contractNode == null || contractNode.getType() == null) {
            return false;
        }
        return isProcessEmbeddedContract(
                FrozenNode.fromResolvedNode(contractNode));
    }

    private boolean isProcessEmbeddedContract(
            FrozenNode contractNode) {
        String typeBlueId = typeBlueId(contractNode);
        Class<?> contractClass = typeBlueId != null
                ? typeResolver.resolveClass(typeBlueId)
                : null;
        return contractClass != null
                && ProcessEmbedded.class.isAssignableFrom(contractClass);
    }

    /**
     * Rejects an explicitly unsupported direct contract header before
     * resolving the surrounding scope. A direct overlay may legally omit its
     * type and inherit the effective contract type; the effective build below
     * remains responsible for rejecting a contract for which no resulting
     * type exists.
     *
     * <p>Reference-only contract entries are deferred to ordinary effective
     * resolution because their header is not directly present.</p>
     */
    void preflightSelectedContractHeaders(FrozenNode selectedScopeNode) {
        FrozenNode contracts = property(selectedScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null) {
            return;
        }
        if (contracts.isReferenceOnly()) {
            contracts =
                    contributionResolver
                            .materializeVerifiedReference(
                                    contracts);
        }
        if (contracts.getProperties() == null) {
            if (contracts.isEmptyNode()) {
                return;
            }
            throw new MustUnderstandFailureException(
                    "Contracts must be an object map",
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        for (Map.Entry<String, FrozenNode> entry
                : contracts.getProperties().entrySet()) {
            if (!isDirectProcessorStateKey(entry.getKey())) {
                preflightDirectContractHeader(
                        entry.getKey(), entry.getValue());
            }
        }
    }

    void preflightDirectContractHeader(String key,
                                       FrozenNode contractNode) {
        validateContractKey(key);
        if (contractNode == null || contractNode.isReferenceOnly()) {
            return;
        }
        String typeBlueId = typeBlueId(contractNode);
        if (typeBlueId == null) {
            return;
        }
        Class<?> contractClass = typeResolver.resolveClass(typeBlueId);
        if (contractClass == null
                || !Contract.class.isAssignableFrom(contractClass)) {
            throw new MustUnderstandFailureException(
                    "Unsupported contract type: " + typeBlueId,
                    ProcessorErrorCategory.UnsupportedRuntimeType);
        }
    }

    private ContractBundle build(Node selectedScopeNode,
                                 FrozenNode effectiveScopeNode,
                                 String scopePath,
                                 ContractRecognitionMeter recognitionMeter,
                                 String recognitionReason) {
        ContractBundle.Builder builder = ContractBundle.builder();
        Node exactSelectedScope =
                materializeSelectedContractsMap(
                        selectedScopeNode);
        Node selectedContractsNode =
                exactSelectedScope != null
                        ? exactSelectedScope.getContracts()
                        : null;
        if (selectedContractsNode != null
                && selectedContractsNode.getProperties() == null) {
            if (Nodes.isEmptyNode(selectedContractsNode)) {
                selectedContractsNode = null;
            } else {
                throw new MustUnderstandFailureException("Contracts must be an object map",
                        ProcessorErrorCategory.InvalidProcessingDocument);
            }
        }

        FrozenNode effectiveContractsNode =
                property(effectiveScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        Map<String, FrozenNode> effectiveContractNodes = effectiveContractsNode != null
                && effectiveContractsNode.getProperties() != null
                ? effectiveContractsNode.getProperties()
                : java.util.Collections.emptyMap();
        Map<String, FrozenNode> contractNodes = new LinkedHashMap<>();
        /*
         * Application contracts are enumerated from the full effective map.
         * Only processor-owned history is selected/direct (runtimeMarkers()).
         */
        for (Map.Entry<String, FrozenNode> effective
                : effectiveContractNodes.entrySet()) {
            String key = effective.getKey();
            validateContractKey(key);
            if (!isDirectProcessorStateKey(key)) {
                FrozenNode contribution = effective.getValue();
                contractNodes.put(
                        key,
                        contribution != null
                                && contribution.isReferenceOnly()
                                ? contributionResolver
                                .materializeVerifiedReference(contribution)
                                : contribution);
            }
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
                        ProcessorErrorCategory.UnsupportedRuntimeType);
            }
            Class<?> contractClass = typeResolver.resolveClass(typeBlueId);
            if (contractClass == null || !Contract.class.isAssignableFrom(contractClass)) {
                throw new MustUnderstandFailureException("Unsupported contract type: " + typeBlueId,
                        ProcessorErrorCategory.UnsupportedRuntimeType);
            }
            boolean handlerContract =
                    HandlerContract.class.isAssignableFrom(
                            contractClass);
            List<String> executableBodyFields =
                    handlerContract
                            ? registry.executableBodyFields(
                            typeBlueId)
                            : Collections.emptyList();
            List<String> deferredHandlerFields =
                    handlerContract
                            ? handlerDeferredFields(
                            executableBodyFields)
                            : executableBodyFields;
            ContractContributionResolver.BindingResolution
                    bindingResolution =
                    contributionResolver.resolveBinding(
                            exactSelectedScope,
                            effectiveScopeNode,
                            key,
                            true,
                            deferredHandlerFields);
            List<String> sourceContributions =
                    bindingResolution
                            .sourceContributions();
            if (recognitionMeter != null) {
                recognitionMeter.recognizeHeader(
                        scopePath,
                        key,
                        sourceContributions,
                        recognitionReason != null
                                ? recognitionReason
                                : "effective-contract-header");
            }
            List<String> meteredEmbeddedPaths = null;
            if (recognitionMeter != null
                    && ProcessEmbedded.class.isAssignableFrom(
                    contractClass)) {
                /*
                 * The exact effective header is now established and charged.
                 * Path fields are dispatch/structural content and are inspected
                 * only after that header charge.
                 */
                meteredEmbeddedPaths =
                        validateMeteredEmbeddedPaths(
                                scopePath,
                                key,
                                entry.getValue(),
                                recognitionMeter);
            }
            /*
             * Executable bodies are contribution content, not instances of
             * the result/body type definitions inherited while resolving the
             * contract header.  Converting the fully resolved body would turn
             * descriptive schema members (for example the optional
             * ContractExecutionResult.termination field) into requested
             * effects.  Preserve effective dispatch fields, but bind an
             * explicitly selected executable body to its exact authored
             * subtree.
             */
            Node executableContractNode = executableContractNode(
                    entry.getValue(),
                    deferredHandlerFields,
                    bindingResolution
                            .exactExecutableBodies());
            FrozenNode exactExecutableContract =
                    FrozenNode.fromResolvedNode(
                            executableContractNode);
            Node conversionNode =
                    deferredHandlerFields.isEmpty()
                            ? executableContractNode
                            : matcherHeaderNode(
                            executableContractNode,
                            deferredHandlerFields);
            Contract contract = converter.convertWithType(
                    conversionNode,
                    Contract.class,
                    false);
            if (contract == null) {
                continue;
            }
            if (contract instanceof HandlerContract) {
                restoreEventMatcher(
                        (HandlerContract) contract,
                        bindingResolution
                                .exactExecutableBodies()
                                .get(HANDLER_EVENT_MATCHER_FIELD));
            }
            contract.setKey(key);
            contract.setTypeBlueId(typeBlueId);
            EffectiveContractSnapshot.Builder snapshot =
                    EffectiveContractSnapshot.builder(scopePath, key)
                            .effectiveTypeBlueId(typeBlueId)
                            .order(contractOrder(contract));
            for (String contribution : sourceContributions) {
                snapshot.sourceContribution(contribution);
            }
            addHeaderFields(
                    snapshot,
                    exactExecutableContract,
                    executableBodyFields);
            if (contract instanceof ChannelContract) {
                ChannelContract channel = (ChannelContract) contract;
                if (!ProcessorContractConstants.isProcessorManagedChannel(channel)
                        && !registry.lookupChannel(channel).isPresent()) {
                    throw new MustUnderstandFailureException(
                            "Unsupported contract type: " + typeBlueId,
                            ProcessorErrorCategory.UnsupportedRuntimeType);
                }
                builder.addChannel(key, channel, entry.getValue());
                snapshot.role(ProcessorContractConstants.isProcessorManagedChannel(channel)
                                ? EffectiveContractSnapshotConstants
                                .Role.PROCESSOR_CHANNEL
                                : EffectiveContractSnapshotConstants
                                .Role.EXTERNAL_CHANNEL)
                        .dispatchField(
                                EffectiveContractSnapshotConstants
                                        .DispatchField.ORDER,
                                channel.getOrder());
                if (channel instanceof EmbeddedNodeChannel) {
                    EmbeddedNodeChannel embedded =
                            (EmbeddedNodeChannel) channel;
                    String sourcePath =
                            embedded.getSourcePath();
                    snapshot.dispatchField(
                            EffectiveContractSnapshotConstants
                                    .DispatchField.SOURCE_PATH,
                            sourcePath);
                    addEventDispatchSnapshot(
                            snapshot, embedded.getEvent());
                } else if (channel
                        instanceof TriggeredEventChannel) {
                    addEventDispatchSnapshot(
                            snapshot,
                            ((TriggeredEventChannel) channel)
                                    .getEvent());
                }
            } else if (contract instanceof HandlerContract) {
                HandlerContract handler = (HandlerContract) contract;
                Optional<HandlerProcessor<? extends HandlerContract>> processor = registry.lookupHandler(handler);
                if (!processor.isPresent()) {
                    throw new MustUnderstandFailureException(
                            "Unsupported contract type: " + typeBlueId,
                            ProcessorErrorCategory.UnsupportedRuntimeType);
                }
                String channelKey = resolveHandlerChannel(scopePath,
                        key,
                        handler,
                        processor.get(),
                        contractNodes,
                        contractTypeBlueIds,
                        recognitionMeter);
                handler.setChannelKey(channelKey);
                if (hasRegisteredSameScopeChannel(channelKey, contractNodes, contractTypeBlueIds)) {
                    builder.addHandler(key, handler,
                            exactExecutableContract,
                            executableBodyFields);
                }
                snapshot.role(
                                EffectiveContractSnapshotConstants
                                        .Role.HANDLER)
                        .dispatchField(
                                EffectiveContractSnapshotConstants
                                        .DispatchField.ORDER,
                                handler.getOrder())
                        .dispatchField(
                                EffectiveContractSnapshotConstants
                                        .DispatchField.CHANNEL,
                                channelKey);
                for (String field : executableBodyFields) {
                    snapshot.executableBodyField(field);
                    addExecutableBody(
                            snapshot,
                            field,
                            scopePath,
                            key,
                            typeBlueId,
                            bindingResolution);
                }
            } else if (contract instanceof ProcessEmbedded) {
                if (meteredEmbeddedPaths != null) {
                    ((ProcessEmbedded) contract).setPaths(
                            meteredEmbeddedPaths);
                } else {
                    validateEmbeddedPaths(
                            (ProcessEmbedded) contract);
                }
                builder.setEmbedded((ProcessEmbedded) contract, entry.getValue());
                snapshot.role(
                        EffectiveContractSnapshotConstants
                                .Role.PROCESS_EMBEDDED);
                FrozenNode paths = property(
                        entry.getValue(),
                        ProcessorContractConstants.KEY_PATHS);
                if (paths != null) {
                    snapshot.deterministicDependency(paths.blueId());
                }
            } else if (contract instanceof MarkerContract) {
                builder.addMarker(key, (MarkerContract) contract, entry.getValue());
                snapshot.role(
                        EffectiveContractSnapshotConstants.Role.MARKER);
            } else {
                snapshot.role(
                        EffectiveContractSnapshotConstants
                                .Role.EXECUTABLE_EXTENSION);
            }
            builder.addEffectiveContractSnapshot(snapshot.build());
        }

        return builder.build();
    }

    private Node materializeSelectedContractsMap(
            Node selectedScope) {
        if (selectedScope == null
                || selectedScope.getContracts() == null
                || !selectedScope.getContracts()
                .isReferenceOnly()) {
            return selectedScope;
        }
        Node exactScope = selectedScope.clone();
        exactScope.contracts(
                contributionResolver
                        .materializeVerifiedReference(
                                FrozenNode.fromNode(
                                        selectedScope
                                                .getContracts()))
                        .toNode());
        return exactScope;
    }

    private Node matcherHeaderNode(
            Node executableContract,
            List<String> executableBodyFields) {
        Node header = executableContract.clone();
        if (header.getProperties() == null) {
            return header;
        }
        Map<String, Node> fields =
                new LinkedHashMap<>(
                        header.getProperties());
        for (String field : executableBodyFields) {
            fields.remove(field);
        }
        return header.properties(fields);
    }

    private List<String> handlerDeferredFields(
            List<String> executableBodyFields) {
        List<String> fields =
                new ArrayList<>(
                        executableBodyFields != null
                                ? executableBodyFields
                                : Collections.<String>emptyList());
        if (!fields.contains(HANDLER_EVENT_MATCHER_FIELD)) {
            fields.add(HANDLER_EVENT_MATCHER_FIELD);
        }
        return fields;
    }

    private void restoreEventMatcher(
            HandlerContract handler,
            Node exactEventMatcher) {
        handler.setEvent(
                exactEventMatcher != null
                        ? exactEventMatcher.clone()
                        : null);
    }

    private Node executableContractNode(
            FrozenNode effectiveContract,
            List<String> executableBodyFields,
            Map<String, Node> exactExecutableBodies) {
        Node executable = effectiveContract.toNode();
        if (executableBodyFields.isEmpty()) {
            return executable;
        }
        Map<String, Node> properties =
                executable.getProperties() != null
                        ? new LinkedHashMap<>(
                                executable.getProperties())
                        : new LinkedHashMap<String, Node>();
        for (String field : executableBodyFields) {
            Node exactBody =
                    exactExecutableBodies.get(field);
            if (exactBody != null) {
                properties.put(
                        field, exactBody.clone());
            } else {
                /*
                 * A completed/eager view may contain schema defaults or
                 * merged body structure that no exact Source contribution
                 * declared. Such content is not executable.
                 */
                properties.remove(field);
            }
        }
        return executable.properties(properties);
    }

    private int contractOrder(Contract contract) {
        if (contract instanceof ChannelContract) {
            Integer order = ((ChannelContract) contract).getOrder();
            return order != null ? order : 0;
        }
        if (contract instanceof HandlerContract) {
            Integer order = ((HandlerContract) contract).getOrder();
            return order != null ? order : 0;
        }
        return 0;
    }

    private boolean isDirectProcessorStateKey(String key) {
        return ProcessorContractConstants.KEY_INITIALIZED.equals(key)
                || ProcessorContractConstants.KEY_TERMINATED.equals(key)
                || ProcessorContractConstants.KEY_CHECKPOINT.equals(key);
    }

    private void addExecutableBody(EffectiveContractSnapshot.Builder snapshot,
                                   String field,
                                   String scopePath,
                                   String contractKey,
                                   String contractTypeBlueId,
                                   ContractContributionResolver.BindingResolution
                                           bindingResolution) {
        Node exactBody =
                bindingResolution
                        .exactExecutableBodies()
                        .get(field);
        if (exactBody != null) {
            Node canonicalBody =
                    exactBody.clone();
            MaterializationProvenance.clear(
                    canonicalBody);
            String exactBodyBlueId =
                    FrozenNode.fromNode(
                            canonicalBody)
                            .blueId();
            ContractContributionResolver.ExecutableBodySource
                    source =
                    bindingResolution
                            .executableBodySources()
                            .get(field);
            if (source == null) {
                throw new MustUnderstandFailureException(
                        "Cannot establish executable-body Source for contract '"
                                + contractKey
                                + "' field '"
                                + field
                                + "'",
                        ProcessorErrorCategory
                                .InvalidContractBinding);
            }
            snapshot.executableBody(
                            field,
                            exactBodyBlueId)
                    .executableBodySourceDescriptor(
                            field,
                            new ExecutableBodySourceDescriptor(
                                    scopePath,
                                    contractKey,
                                    contractTypeBlueId,
                                    field,
                                    exactBodyBlueId,
                                    bindingResolution
                                            .sourceContributions(),
                                    source
                                            .owningContributionBlueId(),
                                    source.sourcePointer(),
                                    source.pureReference()));
        }
    }

    private void addHeaderFields(
            EffectiveContractSnapshot.Builder snapshot,
            FrozenNode contract,
            List<String> executableBodyFields) {
        if (contract == null
                || contract.getProperties() == null
                || contract.getProperties().isEmpty()) {
            return;
        }
        Set<String> executable = new LinkedHashSet<>(
                executableBodyFields != null
                        ? executableBodyFields
                        : Collections.<String>emptyList());
        List<String> names = new ArrayList<>(
                contract.getProperties().keySet());
        names.sort(ExternalOrderKey::compareTextCodePoints);
        for (String name : names) {
            if (!executable.contains(name)) {
                snapshot.headerField(
                        name,
                        contract.getProperties().get(name));
            }
        }
    }

    private void addEventDispatchSnapshot(
            EffectiveContractSnapshot.Builder snapshot,
            Node eventPattern) {
        if (eventPattern == null) {
            return;
        }
        String identity =
                FrozenNode.fromResolvedNode(
                        eventPattern).blueId();
        snapshot.dispatchField(
                        EffectiveContractSnapshotConstants
                                .DispatchField.EVENT,
                        identity)
                .deterministicDependency(identity);
    }

    private void validateContractKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new MustUnderstandFailureException("Invalid contract key: key must be non-empty",
                    ProcessorErrorCategory.InvalidRuntimePointer);
        }
        if (INVALID_CONTRACT_KEYS.contains(key)) {
            throw new MustUnderstandFailureException("Invalid contract key: reserved key '" + key + "'",
                    ProcessorErrorCategory.InvalidReservedRuntimeState);
        }
    }

    private void validateEmbeddedPaths(ProcessEmbedded embedded) {
        Set<String> seen = new LinkedHashSet<>();
        for (String path : embedded.getPaths()) {
            if (!seen.add(path)) {
                throw new MustUnderstandFailureException("Unique items are required for Process Embedded paths",
                        ProcessorErrorCategory.PatchBoundaryViolation);
            }
        }
    }

    private List<String> validateMeteredEmbeddedPaths(
            String scopePath,
            String contractKey,
            FrozenNode contractNode,
            ContractRecognitionMeter meter) {
        FrozenNode pathsNode = property(
                contractNode,
                ProcessorContractConstants.KEY_PATHS);
        if (pathsNode == null) {
            return Collections.emptyList();
        }
        List<FrozenNode> items = pathsNode.getItems();
        if (items == null) {
            throw new MustUnderstandFailureException(
                    "Process Embedded paths must be a List",
                    ProcessorErrorCategory.PatchBoundaryViolation);
        }

        List<String> paths = new ArrayList<>(items.size());
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < items.size(); index++) {
            FrozenNode item = items.get(index);
            Object value = item != null ? item.getValue() : null;
            String logicalPath = value instanceof String
                    ? logicalEmbeddedPath(
                    scopePath, (String) value)
                    : null;
            /*
             * The immutable entry exposes enough context to name the charge.
             * Debit it before validating or using the value, then debit all
             * pointer segments before validating any of them.
             */
            meter.embeddedPathEntryRead(
                    scopePath,
                    contractKey,
                    index,
                    logicalPath);
            if (!(value instanceof String)) {
                throw new MustUnderstandFailureException(
                        "Process Embedded path must be Text",
                        ProcessorErrorCategory.PatchBoundaryViolation);
            }
            String path = (String) value;
            long segmentCount =
                    uncheckedPointerSegmentCount(path);
            meter.embeddedPathSegmentsValidated(
                    scopePath,
                    contractKey,
                    index,
                    logicalPath,
                    segmentCount);
            final String normalized;
            try {
                normalized =
                        PointerUtils.assertValidRuntimePointer(path);
            } catch (IllegalArgumentException invalidPointer) {
                throw new MustUnderstandFailureException(
                        invalidPointer.getMessage(),
                        ProcessorErrorCategory.PatchBoundaryViolation);
            }
            if (JsonPointer.ROOT.equals(normalized)) {
                throw new MustUnderstandFailureException(
                        "Process Embedded path '/' cannot embed its declaring scope",
                        ProcessorErrorCategory.PatchBoundaryViolation);
            }
            if (!seen.add(normalized)) {
                throw new MustUnderstandFailureException(
                        "Unique items are required for Process Embedded paths",
                        ProcessorErrorCategory.PatchBoundaryViolation);
            }
            paths.add(normalized);
        }
        return Collections.unmodifiableList(paths);
    }

    private String logicalEmbeddedPath(
            String scopePath,
            String rawPath) {
        try {
            return PointerUtils.resolvePointer(
                    scopePath, rawPath);
        } catch (IllegalArgumentException invalidPath) {
            /*
             * The following validation reports the normative pointer error.
             * Retain the raw authored value only as trace context.
             */
            return rawPath;
        }
    }

    private long uncheckedPointerSegmentCount(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return 1L;
        }
        long count = 0L;
        for (int index = 0; index < pointer.length(); index++) {
            if (pointer.charAt(index) == '/') {
                count++;
            }
        }
        return Math.max(1L, count);
    }

    private BundleCacheKey cacheKey(Node selectedScopeNode,
                                    FrozenNode effectiveScopeNode,
                                    String scopePath) {
        FrozenNode contractsNode = property(effectiveScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        FrozenNode channelBindingsNode = property(
                effectiveScopeNode,
                LEGACY_CHANNEL_BINDINGS_PROPERTY);
        return new BundleCacheKey(scopePath != null ? scopePath : JsonPointer.ROOT,
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
            node.getProperties().remove(
                    LEGACY_LAST_EVENTS_PROPERTY);
        }
        return FrozenNode.fromResolvedNode(node).blueId();
    }

    private String nodeSignature(FrozenNode node) {
        return node != null ? node.blueId() : "<missing>";
    }

    private FrozenNode property(FrozenNode node, String key) {
        if (node != null && ProcessorContractConstants.KEY_CONTRACTS.equals(key)) {
            return node.getContracts();
        }
        return node != null && node.getProperties() != null ? node.getProperties().get(key) : null;
    }

    private RuntimeMarkers runtimeMarkers(Node selectedScopeNode, FrozenNode effectiveScopeNode) {
        Map<String, MarkerContract> markers = new LinkedHashMap<>();
        Map<String, FrozenNode> markerNodes = new LinkedHashMap<>();
        boolean checkpointDeclared = false;
        Node exactSelectedScope =
                materializeSelectedContractsMap(
                        selectedScopeNode);
        Node selectedContractsNode =
                exactSelectedScope != null
                        ? exactSelectedScope.getContracts()
                        : null;
        FrozenNode effectiveContractsNode = property(effectiveScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (selectedContractsNode == null
                || selectedContractsNode.getProperties() == null
                || effectiveContractsNode == null
                || effectiveContractsNode.getProperties() == null) {
            return new RuntimeMarkers(markers, markerNodes, false);
        }
        for (Map.Entry<String, Node> selectedEntry
                : selectedContractsNode.getProperties().entrySet()) {
            String key = selectedEntry.getKey();
            if (!isDirectProcessorStateKey(key)) {
                continue;
            }
            Node selectedNode = selectedEntry.getValue();
            FrozenNode directNode;
            try {
                directNode = selectedNode != null
                        ? FrozenNode.fromResolvedNode(selectedNode)
                        : null;
            } catch (RuntimeException invalidDirectState) {
                throw new IllegalStateException(
                        "Invalid direct processor state at reserved key '" + key + "'",
                        invalidDirectState);
            }
            String directTypeBlueId = typeBlueId(directNode);
            if (directTypeBlueId == null) {
                // An inherited/type-derived marker has no runtime effect.
                continue;
            }
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
                restoreExactCheckpointSubjects(
                        (ChannelEventCheckpoint) marker,
                        selectedNode);
            }
            markers.put(key, marker);
            markerNodes.put(key, node);
        }
        return new RuntimeMarkers(markers, markerNodes, checkpointDeclared);
    }

    /**
     * Restores checkpoint subjects from the selected/direct lane after the
     * marker header and domain data have been converted from the effective
     * lane.
     *
     * <p>Resolution may add inherited type fields and schemas to an inline
     * subject. Those fields are useful in the effective view but are not part
     * of the exact subject whose BlueId defines checkpoint newness.</p>
     */
    private void restoreExactCheckpointSubjects(
            ChannelEventCheckpoint checkpoint,
            Node selectedCheckpoint) {
        Node selectedEntries = selectedCheckpoint != null
                && selectedCheckpoint.getProperties() != null
                ? selectedCheckpoint.getProperties().get(
                ProcessorContractConstants.KEY_ENTRIES)
                : null;
        if (selectedEntries == null
                || selectedEntries.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Node> selectedEntry
                : selectedEntries.getProperties().entrySet()) {
            CheckpointEntry checkpointEntry = checkpoint.entry(
                    selectedEntry.getKey());
            Node entryNode = selectedEntry.getValue();
            Node exactSubject = entryNode != null
                    && entryNode.getProperties() != null
                    ? entryNode.getProperties().get(
                    ProcessorContractConstants.KEY_SUBJECT)
                    : null;
            if (checkpointEntry != null && exactSubject != null) {
                checkpointEntry.subject(exactSubject);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveHandlerChannel(String scopePath,
                                         String handlerKey,
                                         HandlerContract handler,
                                         HandlerProcessor<? extends HandlerContract> processor,
                                         Map<String, FrozenNode> contractNodes,
                                         Map<String, String> contractTypeBlueIds,
                                         ContractRecognitionMeter
                                                 recognitionMeter) {
        String channelKey = trimToNull(handler.getChannelKey());
        if (channelKey == null) {
            RuntimeWorkSession work =
                    recognitionMeter != null
                            ? recognitionMeter
                            .newRuntimeWorkSession()
                            : new RuntimeWorkSession(
                                    new GasMeter(gasSchedule),
                                    RuntimeWorkSession.Mode
                                            .ADMISSION);
            HandlerRegistrationContext context =
                    new HandlerRegistrationContext(
                            scopePath,
                            handlerKey,
                            contractNodes,
                            contractTypeBlueIds,
                            converter,
                            work);
            HandlerProcessor<HandlerContract> typed =
                    (HandlerProcessor<HandlerContract>) processor;
            try {
                channelKey = trimToNull(
                        typed.deriveChannel(
                                handler, context));
                work.complete();
            } catch (ExecutionEvidenceUnavailableException unavailable) {
                work.suspend();
                throw unavailable;
            } catch (RuntimeException | Error failure) {
                work.failDeterministically();
                throw failure;
            } finally {
                work.close();
            }
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
