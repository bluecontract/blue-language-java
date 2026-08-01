package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.TypeClassResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the selected and effective lanes used during contract discovery.
 *
 * <p>Selected nodes retain exact authored content while effective nodes supply
 * representation-blind inherited headers. Reference materialization always
 * crosses the verified contribution boundary; an explicit Java registration
 * is never treated as provider evidence.</p>
 */
final class EffectiveContractResolver {

    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;
    private final TypeClassResolver typeResolver;
    private final ContractContributionCollector contributions;

    EffectiveContractResolver(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            TypeClassResolver typeResolver,
            ContractContributionCollector contributions) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
        this.contributions = Objects.requireNonNull(contributions, "contributions");
    }

    Node selectedContractContainer(FrozenNode selectedScopeNode) {
        Node selectedScope = new Node();
        if (selectedScopeNode != null && selectedScopeNode.getType() != null) {
            selectedScope.type(selectedScopeNode.getType().toNode());
        }
        FrozenNode selectedContracts =
                property(selectedScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (selectedContracts != null) {
            selectedScope.contracts(selectedContracts.toNode());
        }
        MaterializationProvenance.clear(selectedScope);
        return selectedScope;
    }

    Node materializeSelectedContractsMap(Node selectedScope) {
        if (selectedScope == null
                || selectedScope.getContracts() == null
                || !selectedScope.getContracts().isReferenceOnly()) {
            return selectedScope;
        }
        Node exactScope = selectedScope.clone();
        exactScope.contracts(
                contributions.materializeVerifiedReference(
                                FrozenNode.fromNode(selectedScope.getContracts()))
                        .toNode());
        return exactScope;
    }

    Map<String, FrozenNode> effectiveApplicationContracts(
            FrozenNode effectiveScopeNode) {
        FrozenNode effectiveContracts =
                property(effectiveScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        Map<String, FrozenNode> fields =
                effectiveContracts != null ? effectiveContracts.getProperties() : null;
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, FrozenNode> contracts = new LinkedHashMap<>();
        for (Map.Entry<String, FrozenNode> entry : fields.entrySet()) {
            if (!isDirectProcessorStateKey(entry.getKey())) {
                FrozenNode contribution = entry.getValue();
                contracts.put(
                        entry.getKey(),
                        contribution != null && contribution.isReferenceOnly()
                                ? contributions.materializeVerifiedReference(contribution)
                                : contribution);
            }
        }
        return contracts;
    }

    void requireRegisteredProviderEvidence(FrozenNode effectiveScopeNode) {
        FrozenNode contracts =
                property(effectiveScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        Map<String, FrozenNode> entries =
                contracts != null ? contracts.getProperties() : null;
        if (entries == null) {
            return;
        }
        for (Map.Entry<String, FrozenNode> entry : entries.entrySet()) {
            if (isDirectProcessorStateKey(entry.getKey())) {
                continue;
            }
            FrozenNode contract = entry.getValue();
            String blueId = typeBlueId(contract);
            if (blueId == null || !registry.requiresProviderEvidence(blueId)) {
                continue;
            }
            FrozenNode resolvedType = contract != null ? contract.getType() : null;
            if (resolvedType == null || resolvedType.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Missing provider content for registered contract BlueId " + blueId);
            }
        }
    }

    void retainDeclaredClassificationDependencies(
            Set<String> retainedKeys,
            ExternalChannelDependencySnapshot dependencies) {
        for (ExternalChannelDependencySnapshot.Entry dependency : dependencies.entries()) {
            retainedKeys.add(dependency.channelKey());
        }
        for (ExternalChannelDependencySnapshot.TypeFamily family : dependencies.typeFamilies()) {
            for (ExternalChannelDependencySnapshot.Member member : family.members()) {
                retainedKeys.add(member.channelKey());
            }
        }
        for (ExternalChannelDependencySnapshot.ChannelEntry channel
                : dependencies.channelEntries()) {
            retainedKeys.add(channel.channelKey());
        }
    }

    void collectProcessEmbeddedKeys(
            FrozenNode scopeNode,
            Set<String> retainedKeys) {
        FrozenNode contracts = property(scopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null || contracts.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, FrozenNode> entry : contracts.getProperties().entrySet()) {
            if (entry.getValue() != null && isProcessEmbeddedContract(entry.getValue())) {
                retainedKeys.add(entry.getKey());
            }
        }
    }

    Node filterScopeContracts(FrozenNode scopeNode, Set<String> retainedKeys) {
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
        for (Map.Entry<String, FrozenNode> entry : contracts.getProperties().entrySet()) {
            if (isDirectProcessorStateKey(entry.getKey())
                    || retainedKeys.contains(entry.getKey())) {
                retained.properties(entry.getKey(), entry.getValue().toNode());
            }
        }
        if (retained.getProperties() != null && !retained.getProperties().isEmpty()) {
            filtered.contracts(retained);
        }
        MaterializationProvenance.clear(filtered);
        return filtered;
    }

    boolean isProcessEmbeddedContract(Node contractNode) {
        return contractNode != null
                && contractNode.getType() != null
                && isProcessEmbeddedContract(FrozenNode.fromResolvedNode(contractNode));
    }

    boolean isProcessEmbeddedContract(FrozenNode contractNode) {
        String blueId = typeBlueId(contractNode);
        Class<?> contractClass = blueId != null ? typeResolver.resolveClass(blueId) : null;
        return contractClass != null
                && ProcessEmbedded.class.isAssignableFrom(contractClass);
    }

    MarkerValue directMarker(
            String key,
            Node selectedNode,
            FrozenNode effectiveNode) {
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
        if (typeBlueId(directNode) == null) {
            return null;
        }
        String typeBlueId = typeBlueId(effectiveNode);
        if (typeBlueId == null) {
            return null;
        }
        Class<?> contractClass = typeResolver.resolveClass(typeBlueId);
        if (contractClass == null
                || !MarkerContract.class.isAssignableFrom(contractClass)) {
            return null;
        }
        Contract contract = converter.convertWithType(
                effectiveNode.toNode(), Contract.class, false);
        return contract instanceof MarkerContract
                ? new MarkerValue(
                        typeBlueId,
                        (MarkerContract) contract)
                : null;
    }

    FrozenNode property(FrozenNode node, String key) {
        if (node != null && ProcessorContractConstants.KEY_CONTRACTS.equals(key)) {
            return node.getContracts();
        }
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    String typeBlueId(FrozenNode node) {
        if (node == null || node.getType() == null) {
            return null;
        }
        FrozenNode type = node.getType();
        return type.getReferenceBlueId() != null
                ? type.getReferenceBlueId()
                : type.blueId();
    }

    static boolean isDirectProcessorStateKey(String key) {
        return ProcessorContractConstants.KEY_INITIALIZED.equals(key)
                || ProcessorContractConstants.KEY_TERMINATED.equals(key)
                || ProcessorContractConstants.KEY_CHECKPOINT.equals(key);
    }

    static final class MarkerValue {
        private final String typeBlueId;
        private final MarkerContract marker;

        MarkerValue(
                String typeBlueId,
                MarkerContract marker) {
            this.typeBlueId = typeBlueId;
            this.marker = marker;
        }

        String typeBlueId() {
            return typeBlueId;
        }

        MarkerContract marker() {
            return marker;
        }
    }
}
