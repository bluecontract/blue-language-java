package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.mapping.TypeClassResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
            FrozenNode effectiveScopeNode,
            CanonicalTypeIdentityLookup typeIdentities) {
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
                FrozenNode materialized = contribution != null
                        && contribution.isReferenceOnly()
                        ? contributions.materializeVerifiedReference(
                                contribution)
                        : contribution;
                String effectiveTypeBlueId = typeBlueId(
                        materialized, typeIdentities);
                List<String> deferredFields =
                        effectiveTypeBlueId != null
                                ? new ArrayList<>(
                                registry.executableBodyFields(
                                        effectiveTypeBlueId))
                                : new ArrayList<String>();
                if (effectiveTypeBlueId != null
                        && registry.lookupHandler(effectiveTypeBlueId)
                        .isPresent()
                        && !deferredFields.contains(
                        EffectiveContractSnapshotConstants
                                .DispatchField.EVENT)) {
                    deferredFields.add(
                            EffectiveContractSnapshotConstants
                                    .DispatchField.EVENT);
                }
                if (effectiveTypeBlueId != null) {
                    for (String nodeField
                            : registry.nodeValuedHeaderFields(
                                    effectiveTypeBlueId)) {
                        if (!deferredFields.contains(nodeField)) {
                            deferredFields.add(nodeField);
                        }
                    }
                }
                contracts.put(entry.getKey(),
                        materialized != null
                                ? contributions.materializeVerifiedHeader(
                                        materialized,
                                        deferredFields)
                                : null);
            }
        }
        return contracts;
    }

    void requireRegisteredProviderEvidence(
            FrozenNode effectiveScopeNode,
            CanonicalTypeIdentityLookup typeIdentities) {
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
            String blueId = typeBlueId(contract, typeIdentities);
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

    void collectEffectiveProcessEmbeddedKeys(
            FrozenNode scopeNode,
            Set<String> retainedKeys,
            CanonicalTypeIdentityLookup typeIdentities) {
        FrozenNode contracts = property(scopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null || contracts.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, FrozenNode> entry : contracts.getProperties().entrySet()) {
            if (entry.getValue() != null
                    && isProcessEmbeddedContract(
                            entry.getValue(), typeIdentities)) {
                retainedKeys.add(entry.getKey());
            }
        }
    }

    /**
     * Selects contract fields from exact authored Source without changing any
     * retained representation.
     */
    Node filterSelectedScopeContracts(
            FrozenNode selectedScopeNode,
            Set<String> retainedKeys) {
        return copyFilteredScopeContracts(selectedScopeNode, retainedKeys);
    }

    /**
     * Selects contract fields from a completed effective scope without
     * changing their resolved representation.
     *
     * <p>The resolver-issued identity sidecar remains mandatory at every
     * downstream type-identity and conversion boundary. Projecting this lane
     * back to Source would discard inherited effective contracts and would
     * make runtime recognition depend on the authored representation.</p>
     */
    Node filterEffectiveScopeContracts(
            FrozenNode effectiveScopeNode,
            Set<String> retainedKeys,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(typeIdentities, "typeIdentities");
        return copyFilteredScopeContracts(
                effectiveScopeNode, retainedKeys);
    }

    private Node copyFilteredScopeContracts(
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
            return filtered;
        }
        if (contracts.getProperties() == null) {
            filtered.contracts(contracts.toNode());
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
        return filtered;
    }

    boolean isProcessEmbeddedContract(
            FrozenNode contractNode,
            CanonicalTypeIdentityLookup typeIdentities) {
        String blueId = typeBlueId(contractNode, typeIdentities);
        return isProcessEmbeddedTypeBlueId(blueId);
    }

    boolean isProcessEmbeddedTypeBlueId(String blueId) {
        Class<?> contractClass = blueId != null ? typeResolver.resolveClass(blueId) : null;
        return contractClass != null
                && ProcessEmbedded.class.isAssignableFrom(contractClass);
    }

    MarkerValue directMarker(
            String key,
            Node selectedNode,
            FrozenNode effectiveNode,
            CanonicalTypeIdentityLookup typeIdentities) {
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
        if (directNode == null || directNode.getType() == null) {
            return null;
        }
        String typeBlueId = typeBlueId(
                effectiveNode, typeIdentities);
        if (typeBlueId == null) {
            return null;
        }
        Class<?> contractClass = typeResolver.resolveClass(typeBlueId);
        if (contractClass == null
                || !MarkerContract.class.isAssignableFrom(contractClass)) {
            return null;
        }
        Contract contract = converter.convertWithType(
                effectiveNode.toNode(),
                Contract.class,
                false,
                typeIdentities);
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

    String typeBlueId(
            FrozenNode node,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (node == null || node.getType() == null) {
            return null;
        }
        return CanonicalIdentityEvidence.resolvedTypeBlueId(
                node.getType(),
                typeIdentities,
                "Effective contract type");
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
