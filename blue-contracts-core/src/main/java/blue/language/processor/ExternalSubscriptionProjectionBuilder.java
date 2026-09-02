package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds exact sparse Root projections for subscription completeness proofs. */
final class ExternalSubscriptionProjectionBuilder {

    private final ContractLoader contractLoader;
    private final ProcessingSnapshotManager snapshotManager;
    private final ExternalSubscriptionSelection selection;
    private final Map<String, List<String>> exactSourceFieldsByType;

    ExternalSubscriptionProjectionBuilder(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ExternalSubscriptionSelection selection,
            Map<String, List<String>> exactSourceFieldsByType) {
        this.contractLoader = contractLoader;
        this.snapshotManager = snapshotManager;
        this.selection = selection;
        this.exactSourceFieldsByType = exactSourceFieldsByType;
    }

    ExternalDeliveryResolution resolution(Node root) {
        if (contractLoader == null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Effective-contract resolver is unavailable");
        }
        Node exactRoot = materializeSelectedScope(root.clone());
        ResolvedSnapshot snapshot = snapshotManager != null
                ? ExecutableBodyPathCatalog
                        .resolveCanonicalTransientIncludingTypeContracts(
                        snapshotManager,
                        FrozenNode.fromNode(exactRoot),
                        ExecutableBodyPathCatalog.authoredNodePaths(
                                exactRoot),
                        exactSourceFieldsByType)
                : null;
        return new ExternalDeliveryResolution(
                contractLoader, this, exactRoot, snapshot);
    }

    ExternalSubscriptionProjection subscriptionIndexProjection(
            Node root,
            List<SubscriptionDelta.Entry> activeIntervals) {
        Map<String, Set<String>> subscriptionKeys =
                new LinkedHashMap<>();
        Map<String, Map<String, String>> selectorTypesByScope =
                new LinkedHashMap<>();
        Set<String> selectorScopes = new LinkedHashSet<>();
        Set<String> channelCatalogScopes = new LinkedHashSet<>();
        for (SubscriptionDelta.Entry interval : activeIntervals) {
            String scopePath = PointerUtils.normalizeScope(
                    interval.scopePath());
            subscriptionKeys.computeIfAbsent(
                    scopePath,
                    ignored -> new LinkedHashSet<>())
                    .addAll(selection.subscriptionContractKeys(
                            interval, null));
            if (selection.hasEnumerationSelector(interval)) {
                selectorScopes.add(scopePath);
            }
            if (interval.dependencies()
                    .wholeSameScopeChannelCatalog()) {
                channelCatalogScopes.add(scopePath);
            }
        }
        if (!selectorScopes.isEmpty()) {
            /*
             * Enumeration selectors are absence proofs. Resolve a scope-spine
             * projection with unrelated executable bodies deferred, then use
             * its same-scope Channel headers to expand the exact selector set.
             */
            Node selectorProjection = selectorCatalogProjection(
                    root, selectorScopes);
            try (ExternalDeliveryResolution selectorResolution =
                         selectorResolution(
                                 selectorProjection,
                                 selectorScopes,
                                 channelCatalogScopes)) {
                for (SubscriptionDelta.Entry interval
                        : activeIntervals) {
                    if (!selection.hasEnumerationSelector(interval)) {
                        continue;
                    }
                    String scopePath = PointerUtils.normalizeScope(
                            interval.scopePath());
                    Map<String, String> selectorTypes =
                            selectorTypesByScope.get(scopePath);
                    if (selectorTypes == null) {
                        selectorTypes = selectorEffectiveContractTypes(
                                selectorResolution, scopePath);
                        selectorTypesByScope.put(
                                scopePath, selectorTypes);
                    }
                    subscriptionKeys.get(scopePath).addAll(
                            selection.subscriptionContractKeys(
                                    interval, selectorTypes));
                }
            }
        }
        Node projected = copySubscriptionSpine(
                root, JsonPointer.ROOT, subscriptionKeys);
        if (projected == null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Retained active subscription scope is absent");
        }
        return new ExternalSubscriptionProjection(
                projected,
                subscriptionKeys,
                selectorTypesByScope);
    }

    ExternalDeliveryResolution subscriptionResolution(
            ExternalSubscriptionProjection projection) {
        if (snapshotManager == null) {
            return resolution(projection.root);
        }
        Set<String> preserved = unrequestedContractPaths(projection);
        ResolvedSnapshot snapshot = preserved.isEmpty()
                ? snapshotManager.fromDocumentTransient(
                projection.root.clone())
                : snapshotManager
                .fromDocumentTransientPreservingPaths(
                        projection.root.clone(), preserved);
        return new ExternalDeliveryResolution(
                contractLoader, this, projection.root, snapshot);
    }

    /** Opens selected pure-reference scope content through verified evidence. */
    Node materializeSelectedScope(Node selected) {
        if (selected == null || !selected.isReferenceOnly()
                || snapshotManager == null) {
            return selected;
        }
        FrozenNode reference = FrozenNode.fromResolvedNode(selected);
        FrozenNode materialized = materializeVerifiedExactReference(
                reference);
        return requireMaterialized(
                reference,
                materialized,
                "Exact selected scope content was not found")
                .toNode();
    }

    /** Opens effective pure-reference scope content through verified evidence. */
    Node materializeEffectiveScope(Node effective) {
        if (effective == null || !effective.isReferenceOnly()
                || snapshotManager == null) {
            return effective;
        }
        FrozenNode reference = FrozenNode.fromResolvedNode(effective);
        FrozenNode materialized = snapshotManager
                .materializeVerifiedReference(reference);
        return requireMaterialized(
                reference,
                materialized,
                "Effective scope content was not found")
                .toNode();
    }

    /** Creates a planner bound to this projection's verified provider view. */
    EmbeddedScopePlanner embeddedScopePlanner() {
        return snapshotManager != null
                ? new EmbeddedScopePlanner(
                        this::materializeVerifiedExactReference)
                : new EmbeddedScopePlanner();
    }

    Map<String, String> selectorEffectiveContractTypes(
            ExternalDeliveryResolution resolution,
            String scopePath) {
        Node effective = resolution.effectiveNodeAt(scopePath);
        Node contracts = effective != null
                ? effective.getContracts()
                : null;
        Map<String, String> result = new LinkedHashMap<>();
        if (contracts == null
                || contracts.getProperties() == null) {
            return result;
        }
        for (Map.Entry<String, Node> entry
                : contracts.getProperties().entrySet()) {
            result.put(
                    entry.getKey(),
                    resolvedTypeBlueId(
                            entry.getValue(),
                            resolution.canonicalTypeIdentities()));
        }
        return result;
    }

    FrozenNode subscriptionProjection(
            Node selectedScope,
            Node effectiveScope,
            Set<String> retainedChannelKeys,
            boolean includeProcessEmbedded,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        Node projected = effectiveScope.clone();
        Node contracts = projected.getContracts();
        Node selectedContracts = selectedScope != null
                ? selectedScope.getContracts()
                : null;
        Map<String, Node> selectedDirectContracts =
                selectedContracts != null
                        && selectedContracts.getProperties() != null
                        ? selectedContracts.getProperties()
                        : Collections.<String, Node>emptyMap();
        if (contracts != null
                && contracts.getProperties() != null) {
            java.util.Iterator<Map.Entry<String, Node>> entries =
                    contracts.getProperties().entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, Node> entry = entries.next();
                boolean retained =
                        isSubscriptionProcessorStateKey(entry.getKey())
                                || (retainedChannelKeys != null
                                ? retainedChannelKeys.contains(entry.getKey())
                                : isSubscriptionContract(
                                        entry.getValue(),
                                        canonicalTypeIdentities))
                                || includeProcessEmbedded
                                && isResolvedProcessEmbeddedContract(
                                        entry.getValue(),
                                        canonicalTypeIdentities);
                if (retained) {
                    continue;
                }
                Node selectedDirect = selectedDirectContracts.get(
                        entry.getKey());
                if (selectedDirect == null) {
                    entries.remove();
                    continue;
                }
                entry.setValue(exactReference(
                        selectedDirect, snapshotManager));
            }
            if (contracts.getProperties().isEmpty()) {
                projected.contracts(null);
            }
        }
        return FrozenNode.fromResolvedNode(projected);
    }

    private Node selectorCatalogProjection(
            Node root,
            Set<String> selectorScopes) {
        Node projected = copySelectorCatalogSpine(
                root, JsonPointer.ROOT, selectorScopes);
        if (projected == null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Enumeration-selector scope is absent");
        }
        return projected;
    }

    /** Copies only branches and headers leading to selector scopes. */
    private Node copySelectorCatalogSpine(
            Node source,
            String path,
            Set<String> selectorScopes) {
        if (source == null) {
            return null;
        }
        if (source.isReferenceOnly()) {
            source = exactHeaderNode(source);
        }
        String normalized = PointerUtils.normalizeScope(path);
        boolean selected = selectorScopes.contains(normalized);
        boolean includeRouting =
                ExternalEvidenceVerificationSupport
                        .requiresEmbeddedRouting(path, selectorScopes);
        Node projected = copyNodeHeader(source);
        Node contracts = selected
                ? cloneNullable(source.getContracts())
                : copySubscriptionContracts(
                        source.getContracts(),
                        Collections.<String>emptySet(),
                        includeRouting);
        if (contracts != null) {
            projected.contracts(contracts);
        }
        if (source.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : source.getProperties().entrySet()) {
                String childPath = PointerUtils.appendPointer(
                        path, entry.getKey());
                if (!ExternalEvidenceVerificationSupport
                        .requestedBranch(childPath, selectorScopes)) {
                    continue;
                }
                Node child = copySelectorCatalogSpine(
                        entry.getValue(), childPath, selectorScopes);
                if (child != null) {
                    projected.properties(entry.getKey(), child);
                }
            }
        }
        return projected;
    }

    private ExternalDeliveryResolution selectorResolution(
            Node selectorProjection,
            Set<String> selectorScopes,
            Set<String> channelCatalogScopes) {
        if (snapshotManager == null) {
            return resolution(selectorProjection);
        }
        Set<String> preserved = selectorDeferredContractPaths(
                selectorProjection,
                selectorScopes,
                channelCatalogScopes);
        ResolvedSnapshot snapshot = preserved.isEmpty()
                ? snapshotManager.fromDocumentTransient(
                selectorProjection.clone())
                : snapshotManager
                .fromDocumentTransientPreservingPaths(
                        selectorProjection.clone(), preserved);
        return new ExternalDeliveryResolution(
                contractLoader, this, selectorProjection, snapshot);
    }

    private Set<String> unrequestedContractPaths(
            ExternalSubscriptionProjection projection) {
        Set<String> paths = new LinkedHashSet<>();
        Set<String> openedScopes = openedScopeAncestors(
                projection.requestedKeys.keySet());
        for (String scopePath : openedScopes) {
            Set<String> requested =
                    projection.requestedKeys.getOrDefault(
                            scopePath,
                            Collections.<String>emptySet());
            boolean includeRouting =
                    ExternalEvidenceVerificationSupport
                            .requiresEmbeddedRouting(
                                    scopePath,
                                    projection.requestedKeys.keySet());
            Set<String> contractKeys = exactContractKeys(
                    exactScopeContributionsAt(
                            projection.root, scopePath));
            for (String contractKey : contractKeys) {
                if (requested.contains(contractKey)
                        || isSubscriptionProcessorStateKey(contractKey)
                        || includeRouting
                        && ProcessorContractConstants.KEY_EMBEDDED.equals(
                        contractKey)) {
                    continue;
                }
                paths.add(contractPath(scopePath, contractKey));
            }
        }
        return paths;
    }

    private Set<String> openedScopeAncestors(
            Iterable<String> scopes) {
        Set<String> opened = new LinkedHashSet<>();
        opened.add(JsonPointer.ROOT);
        for (String scope : scopes) {
            String current = JsonPointer.ROOT;
            for (String segment : JsonPointer.split(scope)) {
                current = PointerUtils.appendPointer(
                        current, segment);
                opened.add(current);
            }
        }
        return opened;
    }

    private String contractPath(
            String scopePath, String contractKey) {
        List<String> segments = new ArrayList<>(
                JsonPointer.split(scopePath));
        segments.add(ProcessorContractConstants.KEY_CONTRACTS);
        segments.add(contractKey);
        return JsonPointer.toPointer(segments);
    }

    private Set<String> selectorDeferredContractPaths(
            Node selectorProjection,
            Set<String> selectorScopes,
            Set<String> channelCatalogScopes) {
        Set<String> paths = new LinkedHashSet<>();
        Set<String> openedScopes =
                openedScopeAncestors(selectorScopes);
        for (String scopePath : openedScopes) {
            boolean includeAllChannels =
                    channelCatalogScopes.contains(
                            PointerUtils.normalizeScope(scopePath));
            Map<String, String> types = exactContractTypes(
                    exactScopeContributionsAt(
                            selectorProjection, scopePath));
            for (Map.Entry<String, String> entry
                    : types.entrySet()) {
                if (selection.isExternalChannelType(entry.getValue())
                        || includeAllChannels
                        && selection.isChannelType(entry.getValue())) {
                    continue;
                }
                paths.add(contractPath(scopePath, entry.getKey()));
            }
        }
        return paths;
    }

    private List<Node> exactScopeContributionsAt(
            Node root, String scopePath) {
        List<Node> current = new ArrayList<>();
        Node exactRoot = exactHeaderNode(root);
        if (exactRoot != null) {
            current.add(exactRoot);
        }
        for (String segment : JsonPointer.split(scopePath)) {
            List<Node> next = new ArrayList<>();
            Set<String> identities = new LinkedHashSet<>();
            for (Node contribution : current) {
                for (Node source : exactNodeAndTypeLineage(
                        contribution)) {
                    Node child = source.getProperties() != null
                            ? source.getProperties().get(segment)
                            : null;
                    Node exactChild = exactHeaderNode(child);
                    if (exactChild == null) {
                        continue;
                    }
                    String identity = canonicalSourceBlueId(
                            exactChild,
                            "Enumeration-selector child contribution");
                    if (identities.add(identity)) {
                        next.add(exactChild);
                    }
                }
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        return current;
    }

    private List<Node> exactNodeAndTypeLineage(Node node) {
        List<Node> result = new ArrayList<>();
        collectExactTypeLineage(
                node,
                result,
                new LinkedHashSet<String>(),
                new IdentityHashMap<Node, Boolean>(),
                0);
        return result;
    }

    private void collectExactTypeLineage(
            Node node,
            List<Node> result,
            Set<String> activeReferences,
            IdentityHashMap<Node, Boolean> activeInline,
            int depth) {
        if (node == null) {
            return;
        }
        long limit = GasSchedule.contracts10().portableLimit(
                GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES);
        if (depth > limit) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Enumeration-selector type hierarchy exceeds "
                            + limit);
        }
        Node exact = exactHeaderNode(node);
        if (exact == null) {
            return;
        }
        String referenceIdentity = node.isReferenceOnly()
                ? node.getBlueId()
                : null;
        boolean entered = referenceIdentity != null
                ? activeReferences.add(referenceIdentity)
                : activeInline.put(node, Boolean.TRUE) == null;
        if (!entered) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Cyclic type hierarchy in enumeration-selector "
                            + "header catalog");
        }
        collectExactTypeLineage(
                exact.getType(),
                result,
                activeReferences,
                activeInline,
                depth + 1);
        result.add(exact);
        if (referenceIdentity != null) {
            activeReferences.remove(referenceIdentity);
        } else {
            activeInline.remove(node);
        }
    }

    private Node exactHeaderNode(Node node) {
        if (node == null || !node.isReferenceOnly()) {
            return node;
        }
        if (snapshotManager == null) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Enumeration-selector exact header materialization "
                            + "is unavailable");
        }
        FrozenNode reference = FrozenNode.fromNode(node);
        FrozenNode materialized = materializeVerifiedExactReference(
                reference);
        return requireMaterialized(
                reference,
                materialized,
                "Enumeration-selector exact header content was not found")
                .toNode();
    }

    /** Uses the verified exact provider boundary without resolving content. */
    private FrozenNode materializeVerifiedExactReference(
            FrozenNode reference) {
        if (contractLoader != null) {
            return contractLoader.materializeVerifiedReference(reference);
        }
        return snapshotManager != null
                ? snapshotManager.materializeVerifiedExactReference(reference)
                : null;
    }

    private static FrozenNode requireMaterialized(
            FrozenNode reference,
            FrozenNode materialized,
            String message) {
        if (materialized != null) {
            return materialized;
        }
        throw ExternalEvidenceVerificationSupport.invalid(
                message + " for " + reference.getReferenceBlueId());
    }

    /** Enumerates effective keys without opening individual contract values. */
    private Set<String> exactContractKeys(
            List<Node> scopeContributions) {
        Set<String> result = new LinkedHashSet<>();
        for (Node scopeContribution : scopeContributions) {
            for (Node source : exactNodeAndTypeLineage(
                    scopeContribution)) {
                Node contracts = exactHeaderNode(source.getContracts());
                if (contracts == null
                        || contracts.getProperties() == null) {
                    continue;
                }
                result.addAll(contracts.getProperties().keySet());
            }
        }
        return result;
    }

    private Map<String, String> exactContractTypes(
            List<Node> scopeContributions) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Node scopeContribution : scopeContributions) {
            for (Node source : exactNodeAndTypeLineage(
                    scopeContribution)) {
                Node contracts = exactHeaderNode(source.getContracts());
                if (contracts == null
                        || contracts.getProperties() == null) {
                    continue;
                }
                for (Map.Entry<String, Node> entry
                        : contracts.getProperties().entrySet()) {
                    Node contract = exactHeaderNode(entry.getValue());
                    String typeBlueId = exactSourceTypeBlueId(contract);
                    if (!result.containsKey(entry.getKey())
                            || typeBlueId != null) {
                        result.put(entry.getKey(), typeBlueId);
                    }
                }
            }
        }
        return result;
    }

    private String exactSourceTypeBlueId(Node contract) {
        Node type = contract != null ? contract.getType() : null;
        if (type == null) {
            return null;
        }
        return canonicalSourceBlueId(
                type,
                "Enumeration-selector contract type");
    }

    private String resolvedTypeBlueId(
            Node contract,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        Node type = contract != null ? contract.getType() : null;
        if (type == null) {
            return null;
        }
        return CanonicalIdentityEvidence.resolvedTypeBlueId(
                type,
                canonicalTypeIdentities,
                "Effective external contract type");
    }

    private Node copySubscriptionSpine(
            Node source,
            String path,
            Map<String, Set<String>> subscriptionKeys) {
        if (source == null) {
            return null;
        }
        if (source.isReferenceOnly()) {
            source = exactHeaderNode(source);
        }
        Set<String> requestedKeys = subscriptionKeys.getOrDefault(
                PointerUtils.normalizeScope(path),
                Collections.emptySet());
        boolean includeProcessEmbedded =
                ExternalEvidenceVerificationSupport
                        .requiresEmbeddedRouting(
                                path, subscriptionKeys.keySet());
        Node projected = copyNodeHeader(source);
        boolean retainType = SubscriptionSurfaceTypeInspector.contributes(
                this::materializeVerifiedExactReference,
                source.getType(),
                requestedKeys,
                includeProcessEmbedded,
                new LinkedHashSet<String>());
        if (!retainType) {
            projected.type((Node) null);
        }
        Node contracts = copySubscriptionContracts(
                source.getContracts(),
                requestedKeys,
                includeProcessEmbedded,
                retainType);
        if (contracts != null) {
            projected.contracts(contracts);
        }
        if (source.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : source.getProperties().entrySet()) {
                String childPath = PointerUtils.appendPointer(
                        path, entry.getKey());
                if (!ExternalEvidenceVerificationSupport
                        .requestedBranch(
                                childPath,
                                subscriptionKeys.keySet())) {
                    continue;
                }
                Node child = copySubscriptionSpine(
                        entry.getValue(), childPath, subscriptionKeys);
                if (child != null) {
                    projected.properties(entry.getKey(), child);
                }
            }
        }
        return projected;
    }

    private Node copySubscriptionContracts(
            Node sourceContracts,
            Set<String> requestedKeys,
            boolean includeProcessEmbedded) {
        return copySubscriptionContracts(
                sourceContracts,
                requestedKeys,
                includeProcessEmbedded,
                false);
    }

    private Node copySubscriptionContracts(
            Node sourceContracts,
            Set<String> requestedKeys,
            boolean includeProcessEmbedded,
            boolean retainDirectUnrequestedAsReferences) {
        if (sourceContracts == null) {
            return null;
        }
        if (sourceContracts.isReferenceOnly()) {
            return sourceContracts.clone();
        }
        Node projected = copyNodeHeader(sourceContracts);
        if (sourceContracts.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : sourceContracts.getProperties().entrySet()) {
                if (requestedKeys.contains(entry.getKey())
                        || isSubscriptionProcessorStateKey(entry.getKey())
                        || includeProcessEmbedded
                        && isDirectProcessEmbeddedContract(
                        entry.getValue())) {
                    projected.properties(
                            entry.getKey(), entry.getValue().clone());
                } else if (retainDirectUnrequestedAsReferences) {
                    /*
                     * A retained scope type can constrain direct application
                     * contracts that are outside this subscription read set.
                     * Keep only their already-selected exact identities. The
                     * corresponding paths are deferred below, so validation
                     * can prove presence without opening executable bodies or
                     * fetching unrelated provider content.
                     */
                    projected.properties(
                            entry.getKey(), exactReference(
                                    entry.getValue(), snapshotManager));
                }
            }
        }
        return projected;
    }

    static Node exactReference(
            Node source,
            ProcessingSnapshotManager snapshotManager) {
        if (source.isReferenceOnly()) {
            return source.clone();
        }
        return new Node().blueId(
                CanonicalIdentityEvidence.sourceBlueId(
                        source,
                        snapshotManager,
                        "Retained exact subscription contract"));
    }

    private Node copyNodeHeader(Node source) {
        Node copy = new Node()
                .name(source.getName())
                .description(source.getDescription())
                .value(source.getRawValue())
                .type(cloneNullable(source.getType()))
                .itemType(cloneNullable(source.getItemType()))
                .keyType(cloneNullable(source.getKeyType()))
                .valueType(cloneNullable(source.getValueType()))
                .schema(source.getSchema() != null
                        ? source.getSchema().clone()
                        : null)
                .mergePolicy(source.getMergePolicy())
                .previousBlueId(source.getPreviousBlueId())
                .position(source.getPosition())
                .blue(cloneNullable(source.getBlue()))
                .inlineValue(source.isInlineValue());
        if (source.isReferenceOnly()) {
            copy.blueId(source.getBlueId());
        }
        return copy;
    }

    private Node cloneNullable(Node source) {
        return source != null ? source.clone() : null;
    }

    private boolean isSubscriptionContract(
            Node contract,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        if (contract == null) {
            return false;
        }
        if (isResolvedProcessEmbeddedContract(
                contract, canonicalTypeIdentities)) {
            return true;
        }
        Node type = contract.getType();
        if (type == null) {
            return false;
        }
        String typeBlueId = CanonicalIdentityEvidence.resolvedTypeBlueId(
                type,
                canonicalTypeIdentities,
                "Effective subscription contract type");
        return selection.isChannelType(typeBlueId);
    }

    private String canonicalSourceBlueId(Node source, String purpose) {
        Set<String> preserved = snapshotManager != null
                ? ExecutableBodyPathCatalog
                        .fromNodeIncludingTypeContractsForSourceIdentity(
                        source,
                        ExecutableBodyPathCatalog.authoredNodePaths(source),
                        exactSourceFieldsByType,
                        snapshotManager)
                : Collections.<String>emptySet();
        return CanonicalIdentityEvidence.sourceBlueId(
                source,
                snapshotManager,
                purpose,
                preserved);
    }

    private boolean isResolvedProcessEmbeddedContract(
            Node contract,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        Node type = contract != null ? contract.getType() : null;
        return type != null
                && RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                CanonicalIdentityEvidence.resolvedTypeBlueId(
                        type,
                        canonicalTypeIdentities,
                        "Effective Process Embedded contract type"));
    }

    private boolean isDirectProcessEmbeddedContract(Node contract) {
        Node type = contract != null ? contract.getType() : null;
        return type != null
                && RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                CanonicalIdentityEvidence.sourceTypeBlueId(
                        type,
                        snapshotManager,
                        "Direct Process Embedded contract type"));
    }

    private boolean isSubscriptionProcessorStateKey(String key) {
        return ProcessorContractConstants.KEY_TERMINATED.equals(key)
                || ProcessorContractConstants.KEY_CHECKPOINT.equals(key);
    }
}
