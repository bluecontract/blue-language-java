package blue.language.processor;

import blue.language.BlueCachePolicy;
import blue.language.NodeProvider;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.TypeClassResolver;

import java.util.LinkedHashSet;
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
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(converter, "converter");
        Objects.requireNonNull(typeResolver, "typeResolver");
        this.contributions = new ContractContributionCollector(contributionProvider);
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
                new ContractSnapshotFactory());
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

    ContractBundle load(ResolvedSnapshot snapshot, String scopePath) {
        Objects.requireNonNull(snapshot, "snapshot");
        return load(
                snapshot.canonicalAt(scopePath),
                snapshot.resolvedAt(scopePath),
                scopePath);
    }

    ContractBundle load(FrozenNode scopeNode, String scopePath) {
        return load(scopeNode, scopeNode, scopePath);
    }

    ContractBundle load(
            FrozenNode scopeNode,
            String scopePath,
            ProcessingObserver observer) {
        return load(scopeNode, scopeNode, scopePath, observer);
    }

    ContractBundle load(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath) {
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                NoOpProcessingObserver.INSTANCE);
    }

    ContractBundle load(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer) {
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                observer,
                null,
                null);
    }

    ContractBundle load(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason) {
        Node selectedScope = selectedScopeNode != null
                ? effectiveContracts.selectedContractContainer(selectedScopeNode)
                : null;
        return load(
                selectedScope,
                effectiveScopeNode,
                scopePath,
                observer,
                recognitionMeter,
                recognitionReason);
    }

    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ProcessingObserver observer) {
        return loadExternalClassification(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                channelKey,
                includeProcessEmbedded,
                observer,
                null,
                null);
    }

    ContractBundle loadExternalClassification(
            FrozenNode selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason) {
        return loadExternalClassification(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                channelKey,
                includeProcessEmbedded,
                ExternalChannelDependencySnapshot.none(),
                observer,
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
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason) {
        Set<String> retainedKeys = new LinkedHashSet<>();
        if (channelKey != null) {
            retainedKeys.add(channelKey);
        }
        effectiveContracts.retainDeclaredClassificationDependencies(
                retainedKeys,
                Objects.requireNonNull(declaredDependencies, "declaredDependencies"));
        if (includeProcessEmbedded) {
            effectiveContracts.collectProcessEmbeddedKeys(
                    selectedScopeNode, retainedKeys);
            effectiveContracts.collectProcessEmbeddedKeys(
                    effectiveScopeNode, retainedKeys);
        }
        Node selectedScope = effectiveContracts.filterScopeContracts(
                selectedScopeNode, retainedKeys);
        Node effectiveScope = effectiveContracts.filterScopeContracts(
                effectiveScopeNode, retainedKeys);
        FrozenNode frozenEffective = effectiveScope != null
                ? FrozenNode.fromResolvedNode(effectiveScope)
                : null;
        return load(
                selectedScope,
                frozenEffective,
                scopePath,
                observer,
                recognitionMeter,
                recognitionReason);
    }

    ContractBundle load(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer) {
        return load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                observer,
                null,
                null);
    }

    ContractBundle load(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason) {
        return refresh.load(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                observer,
                recognitionMeter,
                recognitionReason,
                headers::load);
    }

    void clearCaches() {
        refresh.clear();
    }

    ContractBundle.HandlerBinding materializeSelectedExecutableBodies(
            ContractBundle.HandlerBinding binding,
            Function<FrozenNode, FrozenNode> materializer) {
        return executableBodies.materializeSelected(binding, materializer);
    }

    int cacheSize() {
        return refresh.cacheSize();
    }

    long cacheWeightBytes() {
        return refresh.cacheWeightBytes();
    }

    boolean isProcessEmbeddedContract(Node contractNode) {
        return effectiveContracts.isProcessEmbeddedContract(contractNode);
    }

    void preflightSelectedContractHeaders(FrozenNode selectedScopeNode) {
        headers.preflightSelectedContractHeaders(selectedScopeNode);
    }

    void preflightDirectContractHeader(String key, FrozenNode contractNode) {
        headers.preflightDirectContractHeader(key, contractNode);
    }
}
