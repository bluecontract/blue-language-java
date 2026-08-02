package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds the read-only, delivery-selected view used by external-candidate
 * classification.
 *
 * <p>The projection retains only feeder-selected Channels, their declared
 * dependencies, processor-owned checkpoint/termination state, and Process
 * Embedded routes needed to reach selected scopes. It never mutates the
 * invocation document.</p>
 */
final class EvidenceClassificationView {

    private final DocumentProcessor owner;
    private final DocumentProcessingRuntime runtime;
    private final Node inputDocument;
    private final ResolvedSnapshot inputSnapshot;
    private final Supplier<VerifiedExecutionEvidence> evidenceSupplier;
    private Node classificationDocument;
    private ResolvedSnapshot classificationSnapshot;

    EvidenceClassificationView(
            DocumentProcessor owner,
            DocumentProcessingRuntime runtime,
            Node inputDocument,
            ResolvedSnapshot inputSnapshot,
            Supplier<VerifiedExecutionEvidence> evidenceSupplier) {
        this.owner = owner;
        this.runtime = runtime;
        this.inputDocument = inputDocument;
        this.inputSnapshot = inputSnapshot;
        this.evidenceSupplier = evidenceSupplier;
    }

    /**
     * Checks the directly admitted Root marker before a no-match shortcut can
     * avoid contract recognition. The revision-bound feeder is authoritative
     * for the already indexed transitive surface, so this preflight must not
     * recursively reopen every embedded branch on each event.
     */
    void preflightOpaqueProcessEmbeddedBoundaries() {
        String scopePath = JsonPointer.ROOT;
        FrozenNode selectedScope = runtime.selectedFrozenAt(scopePath);
        if (!requiresEmbeddedPreflight(selectedScope)) {
            return;
        }
        FrozenNode effectiveScope = requiresEffectiveScopeResolution(
                selectedScope)
                ? runtime.resolvedFrozenAt(scopePath)
                : selectedScope;
        if (effectiveScope == null) {
            return;
        }
        ContractBundle structural = owner.contractLoader()
                .loadExternalClassification(
                        selectedScope,
                        effectiveScope,
                        scopePath,
                        null,
                        true,
                        owner.observer());
        EmbeddedScopeEntryPlans.attach(
                runtime,
                scopePath,
                effectiveScope,
                structural);
    }

    /**
     * Avoids resolving an ordinary child merely because its parent embeds it.
     * A direct marker, an inherited type, or an opaque selected node is the
     * only reason this pre-no-match pass may demand the child's effective
     * scope. Unrelated contracts remain owned by participating-closure
     * recognition and keep their established failure precedence.
     */
    private boolean requiresEmbeddedPreflight(FrozenNode selectedScope) {
        if (selectedScope == null) {
            return false;
        }
        if (selectedScope.isReferenceOnly()
                || selectedScope.getType() != null) {
            return true;
        }
        FrozenNode contracts = selectedScope.getContracts();
        if (contracts == null) {
            return false;
        }
        if (contracts.isReferenceOnly()) {
            return true;
        }
        Map<String, FrozenNode> entries = contracts.getProperties();
        if (entries == null) {
            return false;
        }
        for (FrozenNode contract : entries.values()) {
            if (contract != null
                    && owner.contractLoader().isProcessEmbeddedContract(
                    contract.toNode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves only selected scopes whose effective Process Embedded marker or
     * target content can differ from the selected node. Untyped direct scopes
     * remain self-effective, so scanning their embedded children does not
     * demand unrelated descendant contract types ahead of closure discovery.
     */
    private boolean requiresEffectiveScopeResolution(
            FrozenNode selectedScope) {
        if (selectedScope.isReferenceOnly()
                || selectedScope.getType() != null) {
            return true;
        }
        FrozenNode contracts = selectedScope.getContracts();
        return contracts != null && contracts.isReferenceOnly();
    }

    FrozenNode selectedAt(String scopePath) {
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        if (inputSnapshot != null) {
            return selectedAt(inputSnapshot, normalized);
        }
        ensureProjected();
        if (classificationSnapshot != null) {
            return selectedAt(classificationSnapshot, normalized);
        }
        Node selected = ProcessorEngine.nodeAt(
                classificationDocument,
                normalized);
        return selected != null
                ? FrozenNode.fromResolvedNode(selected)
                : null;
    }

    FrozenNode resolvedAt(String scopePath) {
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        if (inputSnapshot != null) {
            return resolvedAt(inputSnapshot, normalized);
        }
        ensureProjected();
        if (classificationSnapshot != null) {
            return resolvedAt(classificationSnapshot, normalized);
        }
        Node selected = ProcessorEngine.nodeAt(
                classificationDocument,
                normalized);
        return selected != null
                ? FrozenNode.fromResolvedNode(selected)
                : null;
    }

    /**
     * Builds the effective form of an opaque selected occurrence without
     * inheriting an eagerly resolved executable body from the containing
     * document snapshot.
     */
    private FrozenNode resolvedAt(
            ResolvedSnapshot snapshot,
            String normalizedScope) {
        FrozenNode canonical = snapshot.canonicalAt(normalizedScope);
        if (canonical == null || !canonical.isReferenceOnly()) {
            return snapshot.resolvedAt(normalizedScope);
        }
        ProcessingSnapshotManager manager = owner.snapshotManager();
        if (manager == null) {
            return snapshot.resolvedAt(normalizedScope);
        }
        FrozenNode exact = selectedAt(snapshot, normalizedScope);
        return DocumentProcessingRuntime.resolveCanonicalTransient(
                manager,
                exact,
                Collections.singleton(JsonPointer.ROOT),
                runtime.executableBodyFieldsByType)
                .frozenResolvedRoot();
    }

    SubscriptionDelta.Entry activeSubscriptionInterval(
            String scopePath,
            String channelKey) {
        VerifiedExecutionEvidence evidence = evidenceSupplier.get();
        if (evidence == null
                || !evidence.hasActiveSubscriptionIntervals()) {
            return null;
        }
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        for (SubscriptionDelta.Entry interval
                : evidence.activeSubscriptionIntervals()) {
            if (interval.isActiveInterval()
                    && normalized.equals(ProcessorEngine.normalizeScope(
                    interval.scopePath()))
                    && channelKey.equals(interval.channelKey())) {
                return interval;
            }
        }
        return null;
    }

    private FrozenNode selectedAt(
            ResolvedSnapshot snapshot,
            String normalizedScope) {
        FrozenNode selected = snapshot.canonicalAt(normalizedScope);
        if (selected != null && selected.isReferenceOnly()) {
            ProcessingSnapshotManager manager = owner.snapshotManager();
            return manager != null
                    ? manager.materializeVerifiedExactReference(selected)
                    : selected;
        }
        if (selected != null) {
            return selected;
        }
        FrozenNode root = snapshot.frozenCanonicalRoot();
        if (!root.isReferenceOnly()) {
            return null;
        }
        ProcessingSnapshotManager manager = owner.snapshotManager();
        if (manager == null) {
            return null;
        }
        FrozenNode materializedRoot =
                manager.materializeVerifiedExactReference(root);
        return materializedRoot.pathIndex().get(normalizedScope);
    }

    private void ensureProjected() {
        if (classificationDocument != null
                || classificationSnapshot != null) {
            return;
        }
        Node projected = inputDocument.clone();
        Map<String, Set<String>> selectedKeys = new LinkedHashMap<>();
        Map<String, Map<String, String>> selectedTypes =
                new LinkedHashMap<>();
        VerifiedExecutionEvidence evidence = evidenceSupplier.get();
        if (evidence != null) {
            for (ExternalDeliverySnapshot delivery : evidence.deliveries()) {
                String scopePath = ProcessorEngine.normalizeScope(
                        delivery.scopePath());
                Set<String> retained = selectedKeys.computeIfAbsent(
                        scopePath,
                        ignored -> new LinkedHashSet<>());
                retained.add(delivery.channelKey());
                Map<String, String> types = selectedTypes.computeIfAbsent(
                        scopePath,
                        ignored -> new LinkedHashMap<>());
                recordClassificationType(
                        types,
                        delivery.channelKey(),
                        delivery.effectiveTypeBlueId());
                addDependencyKeys(
                        retained,
                        types,
                        activeSubscriptionInterval(
                                delivery.scopePath(),
                                delivery.channelKey()));
            }
        }
        pruneContracts(projected, JsonPointer.ROOT, selectedKeys);
        ProcessingSnapshotManager manager = owner.snapshotManager();
        if (manager != null) {
            Set<String> preservedBodies = executableBodyPaths(selectedTypes);
            classificationSnapshot = preservedBodies.isEmpty()
                    ? manager.fromDocumentTransient(projected)
                    : manager.fromDocumentTransientPreservingPaths(
                            projected,
                            preservedBodies);
        } else {
            classificationDocument = projected;
        }
    }

    private void addDependencyKeys(
            Set<String> retained,
            Map<String, String> retainedTypes,
            SubscriptionDelta.Entry interval) {
        if (interval == null) {
            return;
        }
        ExternalChannelDependencySnapshot dependencies =
                interval.dependencies();
        for (ExternalChannelDependencySnapshot.Entry dependency
                : dependencies.entries()) {
            retained.add(dependency.channelKey());
            recordClassificationType(
                    retainedTypes,
                    dependency.channelKey(),
                    dependency.effectiveTypeBlueId());
        }
        for (ExternalChannelDependencySnapshot.TypeFamily family
                : dependencies.typeFamilies()) {
            for (ExternalChannelDependencySnapshot.Member member
                    : family.members()) {
                retained.add(member.channelKey());
                recordClassificationType(
                        retainedTypes,
                        member.channelKey(),
                        member.effectiveTypeBlueId() != null
                                ? member.effectiveTypeBlueId()
                                : family.effectiveTypeBlueId());
            }
        }
        for (ExternalChannelDependencySnapshot.ChannelEntry channel
                : dependencies.channelEntries()) {
            retained.add(channel.channelKey());
            recordClassificationType(
                    retainedTypes,
                    channel.channelKey(),
                    channel.effectiveTypeBlueId());
        }
    }

    private void recordClassificationType(
            Map<String, String> retainedTypes,
            String contractKey,
            String effectiveTypeBlueId) {
        String prior = retainedTypes.put(contractKey, effectiveTypeBlueId);
        if (prior != null && !prior.equals(effectiveTypeBlueId)) {
            throw new InvalidExecutionEvidenceException(
                    "Conflicting retained Phase-B effective types for "
                            + contractKey);
        }
    }

    private Set<String> executableBodyPaths(
            Map<String, Map<String, String>> retainedTypes) {
        Map<String, List<String>> fieldsByType = owner.registry()
                .executableBodyFieldsByType();
        if (fieldsByType.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> preserved = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> scope
                : retainedTypes.entrySet()) {
            for (Map.Entry<String, String> contract
                    : scope.getValue().entrySet()) {
                List<String> fields = fieldsByType.get(contract.getValue());
                if (fields == null || fields.isEmpty()) {
                    continue;
                }
                String contractPath = ProcessorEngine.resolvePointer(
                        scope.getKey(),
                        ProcessorPointerConstants.RELATIVE_CONTRACTS
                                + "/"
                                + JsonPointer.escape(contract.getKey()));
                for (String field : fields) {
                    preserved.add(contractPath + "/"
                            + JsonPointer.escape(field));
                }
            }
        }
        return preserved;
    }

    private void pruneContracts(
            Node node,
            String scopePath,
            Map<String, Set<String>> selectedKeys) {
        if (node == null || node.isReferenceOnly()) {
            return;
        }
        Set<String> selected = selectedKeys.getOrDefault(
                ProcessorEngine.normalizeScope(scopePath),
                Collections.emptySet());
        boolean includeProcessEmbedded = requiresEmbeddedRouting(
                scopePath,
                selectedKeys.keySet());
        if (!RootExternalDeliveryEvidenceVerifier
                .typeContributesToSubscriptionSurface(
                        owner.snapshotManager(),
                        node.getType(),
                        selected,
                        includeProcessEmbedded,
                        new LinkedHashSet<String>())) {
            node.type((Node) null);
        }
        Node contracts = node.getContracts();
        if (contracts != null && contracts.getProperties() != null) {
            contracts.getProperties().entrySet().removeIf(entry ->
                    !selected.contains(entry.getKey())
                            && !isProcessorStateKey(entry.getKey())
                            && !owner.contractLoader()
                            .isProcessEmbeddedContract(entry.getValue()));
            if (contracts.getProperties().isEmpty()) {
                node.contracts(null);
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                pruneContracts(
                        entry.getValue(),
                        PointerUtils.appendPointer(
                                scopePath,
                                entry.getKey()),
                        selectedKeys);
            }
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                pruneContracts(
                        node.getItems().get(index),
                        PointerUtils.appendPointer(
                                scopePath,
                                Integer.toString(index)),
                        selectedKeys);
            }
        }
    }

    private boolean requiresEmbeddedRouting(
            String scopePath,
            Set<String> selectedScopes) {
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        for (String selectedScope : selectedScopes) {
            String selected = ProcessorEngine.normalizeScope(selectedScope);
            if (!selected.equals(normalized)
                    && PointerUtils.descendantOrEqual(
                    selected,
                    normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean isProcessorStateKey(String key) {
        return ProcessorContractConstants.KEY_TERMINATED.equals(key)
                || ProcessorContractConstants.KEY_CHECKPOINT.equals(key);
    }

}
