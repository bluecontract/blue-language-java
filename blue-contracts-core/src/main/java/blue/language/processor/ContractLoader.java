package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.provider.NodeProvider;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.mapping.TypeClassResolver;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Compatibility root for deterministic contract discovery.
 *
 * <p>The root contains no discovery policy of its own. It composes effective
 * resolution, exact contribution collection, header recognition, immutable
 * snapshots, lazy bodies, structural caching, and invocation-local refresh
 * behind the historical package-private call surface.</p>
 */
final class ContractLoader {

    private final EffectiveContractResolver effectiveContracts;
    private final ContractContributionCollector contributions;
    private final ContractHeaderLoader headers;
    private final ExecutableBodyLoader executableBodies;
    private final ContractRefreshService refresh;
    private final ProcessingSnapshotManager snapshotManager;

    ContractLoader(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            TypeClassResolver typeResolver) {
        this(registry, converter, typeResolver, BlueCachePolicy.boundedDefaults());
    }

    ContractLoader(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            TypeClassResolver typeResolver,
            BlueCachePolicy cachePolicy) {
        this(registry, converter, typeResolver, cachePolicy, null);
    }

    ContractLoader(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            TypeClassResolver typeResolver,
            BlueCachePolicy cachePolicy,
            NodeProvider contributionProvider) {
        this(registry, converter, typeResolver, cachePolicy,
                contributionProvider, false);
    }

    ContractLoader(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            TypeClassResolver typeResolver,
            BlueCachePolicy cachePolicy,
            NodeProvider contributionProvider,
            boolean canonicalContractOrder) {
        this(
                registry,
                converter,
                typeResolver,
                cachePolicy,
                contributionProvider,
                canonicalContractOrder,
                null);
    }

    ContractLoader(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            TypeClassResolver typeResolver,
            BlueCachePolicy cachePolicy,
            NodeProvider contributionProvider,
            boolean canonicalContractOrder,
            ProcessingSnapshotManager snapshotManager) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(converter, "converter");
        Objects.requireNonNull(typeResolver, "typeResolver");
        this.snapshotManager = snapshotManager;
        this.contributions = new ContractContributionCollector(
                contributionProvider,
                snapshotManager);
        this.effectiveContracts = new EffectiveContractResolver(
                registry, converter, typeResolver, contributions);
        this.executableBodies = new ExecutableBodyLoader(converter);
        this.headers = new ContractHeaderLoader(
                registry,
                converter,
                typeResolver,
                effectiveContracts,
                contributions,
                executableBodies,
                new ContractSnapshotFactory(snapshotManager),
                snapshotManager,
                canonicalContractOrder);
        this.refresh = new ContractRefreshService(
                registry,
                effectiveContracts,
                new ContractSnapshotCache(
                        Objects.requireNonNull(cachePolicy, "cachePolicy")));
    }

    void gasSchedule(GasSchedule gasSchedule) {
        GasSchedule required = Objects.requireNonNull(gasSchedule, "gasSchedule");
        contributions.gasSchedule(required);
        headers.gasSchedule(required);
    }

    FrozenNode materializeVerifiedReference(FrozenNode reference) {
        return contributions.materializeVerifiedReference(
                Objects.requireNonNull(reference, "reference"));
    }

    ContractBundle load(ResolvedSnapshot snapshot, String scopePath) {
        Objects.requireNonNull(snapshot, "snapshot");
        FrozenNode source = snapshot.sourceAt(scopePath);
        Node selectedScope = source != null
                ? effectiveContracts.selectedContractContainer(source)
                : null;
        return load(
                selectedScope,
                snapshot.resolvedAt(scopePath),
                scopePath,
                NoOpProcessingObserver.INSTANCE,
                null,
                null,
                snapshot.canonicalTypeIdentities(),
                ContractHeaderMappingEvidence.fromSnapshot(
                        snapshot, scopePath),
                null);
    }

    ContractBundle load(
            FrozenNode scopeNode,
            String scopePath,
            CanonicalTypeIdentityLookup typeIdentities) {
        return load(scopeNode, scopeNode, scopePath, typeIdentities);
    }

    ContractBundle load(
            FrozenNode scopeNode,
            String scopePath,
            ProcessingObserver observer,
            CanonicalTypeIdentityLookup typeIdentities) {
        return load(
                scopeNode,
                scopeNode,
                scopePath,
                observer,
                typeIdentities);
    }

    ContractBundle load(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            CanonicalTypeIdentityLookup typeIdentities) {
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                NoOpProcessingObserver.INSTANCE,
                typeIdentities);
    }

    ContractBundle load(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer,
            CanonicalTypeIdentityLookup typeIdentities) {
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                observer,
                null,
                null,
                typeIdentities);
    }

    ContractBundle load(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            CanonicalTypeIdentityLookup typeIdentities) {
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                observer,
                recognitionMeter,
                recognitionReason,
                typeIdentities,
                null);
    }

    ContractBundle load(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            CanonicalTypeIdentityLookup typeIdentities,
            CanonicalContributionIdentityMemo identityMemo) {
        Node selectedScope = selectedScopeNode != null
                ? effectiveContracts.selectedContractContainer(selectedScopeNode)
                : null;
        return load(
                selectedScope,
                effectiveScopeNode,
                scopePath,
                observer,
                recognitionMeter,
                recognitionReason,
                typeIdentities,
                identityMemo);
    }

    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            Set<String> retainedContractKeys,
            boolean includeProcessEmbedded,
            CanonicalTypeIdentityLookup typeIdentities) {
        Set<String> retainedKeys = new LinkedHashSet<>(
                Objects.requireNonNull(
                        retainedContractKeys, "retainedContractKeys"));
        if (includeProcessEmbedded) {
            collectSelectedProcessEmbeddedKeys(
                    selectedScopeNode, retainedKeys);
            effectiveContracts.collectEffectiveProcessEmbeddedKeys(
                    effectiveScopeNode,
                    retainedKeys,
                    typeIdentities);
        }
        Node selectedScope = effectiveContracts.filterSelectedScopeContracts(
                selectedScopeNode, retainedKeys);
        Node effectiveScope = effectiveContracts.filterEffectiveScopeContracts(
                effectiveScopeNode, retainedKeys, typeIdentities);
        FrozenNode frozenEffective = effectiveScope != null
                ? FrozenNode.fromResolvedNode(effectiveScope)
                : null;
        return load(
                selectedScope,
                frozenEffective,
                scopePath,
                NoOpProcessingObserver.INSTANCE,
                null,
                null,
                typeIdentities);
    }

    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ProcessingObserver observer,
            CanonicalTypeIdentityLookup typeIdentities) {
        return loadExternalClassification(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                channelKey,
                includeProcessEmbedded,
                observer,
                typeIdentities,
                null);
    }

    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ProcessingObserver observer,
            CanonicalTypeIdentityLookup typeIdentities,
            CanonicalContributionIdentityMemo identityMemo) {
        return loadExternalClassification(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                channelKey,
                includeProcessEmbedded,
                ExternalChannelDependencySnapshot.none(),
                observer,
                null,
                null,
                typeIdentities,
                identityMemo);
    }

    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            CanonicalTypeIdentityLookup typeIdentities) {
        return loadExternalClassification(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                channelKey,
                includeProcessEmbedded,
                ExternalChannelDependencySnapshot.none(),
                observer,
                recognitionMeter,
                recognitionReason,
                typeIdentities);
    }

    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ExternalChannelDependencySnapshot declaredDependencies,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            CanonicalTypeIdentityLookup typeIdentities) {
        return loadExternalClassification(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                channelKey,
                includeProcessEmbedded,
                declaredDependencies,
                observer,
                recognitionMeter,
                recognitionReason,
                typeIdentities,
                null);
    }

    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ExternalChannelDependencySnapshot declaredDependencies,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            CanonicalTypeIdentityLookup typeIdentities,
            CanonicalContributionIdentityMemo identityMemo) {
        Set<String> retainedKeys = externalClassificationContractKeys(
                selectedScopeNode,
                effectiveScopeNode,
                channelKey,
                includeProcessEmbedded,
                declaredDependencies,
                typeIdentities);
        Node selectedScope = effectiveContracts.filterSelectedScopeContracts(
                selectedScopeNode, retainedKeys);
        Node effectiveScope = effectiveContracts.filterEffectiveScopeContracts(
                effectiveScopeNode, retainedKeys, typeIdentities);
        FrozenNode frozenEffective = effectiveScope != null
                ? FrozenNode.fromResolvedNode(effectiveScope)
                : null;
        return load(
                selectedScope,
                frozenEffective,
                scopePath,
                observer,
                recognitionMeter,
                recognitionReason,
                typeIdentities,
                identityMemo);
    }

    Set<String> externalClassificationContractKeys(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String channelKey,
            boolean includeProcessEmbedded,
            ExternalChannelDependencySnapshot declaredDependencies,
            CanonicalTypeIdentityLookup typeIdentities) {
        Set<String> retainedKeys = new LinkedHashSet<>();
        if (channelKey != null) {
            retainedKeys.add(channelKey);
        }
        effectiveContracts.retainDeclaredClassificationDependencies(
                retainedKeys,
                Objects.requireNonNull(
                        declaredDependencies, "declaredDependencies"));
        if (includeProcessEmbedded) {
            collectSelectedProcessEmbeddedKeys(
                    selectedScopeNode, retainedKeys);
            effectiveContracts.collectEffectiveProcessEmbeddedKeys(
                    effectiveScopeNode,
                    retainedKeys,
                    typeIdentities);
        }
        return retainedKeys;
    }

    private void collectSelectedProcessEmbeddedKeys(
            FrozenNode selectedScopeNode,
            Set<String> retainedKeys) {
        FrozenNode contracts = effectiveContracts.property(
                selectedScopeNode,
                ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null || contracts.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, FrozenNode> entry
                : contracts.getProperties().entrySet()) {
            if (entry.getValue() != null
                    && isProcessEmbeddedContract(
                            entry.getValue().toNode())) {
                retainedKeys.add(entry.getKey());
            }
        }
    }

    ContractBundle load(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer,
            CanonicalTypeIdentityLookup typeIdentities) {
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                observer,
                null,
                null,
                typeIdentities);
    }

    ContractBundle load(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            CanonicalTypeIdentityLookup typeIdentities) {
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                observer,
                recognitionMeter,
                recognitionReason,
                typeIdentities,
                null);
    }

    ContractBundle load(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            CanonicalTypeIdentityLookup typeIdentities,
            CanonicalContributionIdentityMemo identityMemo) {
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                observer,
                recognitionMeter,
                recognitionReason,
                typeIdentities,
                ContractHeaderMappingEvidence.none(scopePath),
                identityMemo);
    }

    private ContractBundle load(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            CanonicalTypeIdentityLookup typeIdentities,
            ContractHeaderMappingEvidence mappingEvidence,
            CanonicalContributionIdentityMemo identityMemo) {
        ContractHeaderMappingEvidence contentEvidence = Objects.requireNonNull(
                mappingEvidence, "mappingEvidence");
        return refresh.load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                observer,
                recognitionMeter,
                recognitionReason,
                typeIdentities,
                contentEvidence.cacheSignature(),
                (selected, effective, path, meter, reason, identities) ->
                        headers.load(
                                selected,
                                effective,
                                path,
                                meter,
                                reason,
                                identities,
                                contentEvidence,
                                identityMemo));
    }

    void clearCaches() {
        refresh.clear();
    }

    ContractBundle.HandlerBinding materializeSelectedExecutableBodies(
            ContractBundle.HandlerBinding binding,
            Function<FrozenNode, ResolvedSnapshot> materializer) {
        return executableBodies.materializeSelected(binding, materializer);
    }

    int cacheSize() {
        return refresh.cacheSize();
    }

    long cacheWeightBytes() {
        return refresh.cacheWeightBytes();
    }

    boolean isProcessEmbeddedContract(Node contractNode) {
        Node type = contractNode != null ? contractNode.getType() : null;
        String typeBlueId = type != null
                ? CanonicalIdentityEvidence.sourceTypeBlueId(
                        type,
                        snapshotManager,
                        "Direct Process Embedded contract type")
                : null;
        return effectiveContracts.isProcessEmbeddedTypeBlueId(typeBlueId);
    }

    void preflightSelectedContractHeaders(FrozenNode selectedScopeNode) {
        headers.preflightSelectedContractHeaders(selectedScopeNode);
    }

    void preflightDirectContractHeader(
            String key,
            FrozenNode contractNode,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        headers.preflightDirectContractHeader(
                key, contractNode, canonicalTypeIdentities);
    }
}
