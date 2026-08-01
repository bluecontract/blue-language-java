package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
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
     * Checks opaque Process Embedded boundaries before a no-match shortcut
     * can avoid complete contract recognition.
     */
    void preflightOpaqueProcessEmbeddedBoundaries() {
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        pending.add(JsonPointer.ROOT);
        while (!pending.isEmpty()) {
            String scopePath = ProcessorEngine.normalizeScope(
                    pending.removeFirst());
            if (!visited.add(scopePath)) {
                continue;
            }
            Node scope = ProcessorEngine.nodeAt(inputDocument, scopePath);
            if (scope == null || scope.isReferenceOnly()) {
                continue;
            }
            Node contracts = scope.getContracts();
            Map<String, Node> entries = contracts != null
                    ? contracts.getProperties()
                    : null;
            if (entries == null) {
                continue;
            }
            for (Map.Entry<String, Node> entry : entries.entrySet()) {
                Node contract = entry.getValue();
                Node type = contract != null ? contract.getType() : null;
                if (type == null
                        || !type.isReferenceOnly()
                        || !RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                        type.getBlueId())) {
                    continue;
                }
                Node paths = directProperty(
                        contract,
                        ProcessorContractConstants.KEY_PATHS);
                if (paths == null || paths.getItems() == null) {
                    continue;
                }
                for (Node declared : paths.getItems()) {
                    Object raw = declared != null
                            ? declared.getValue()
                            : null;
                    if (!(raw instanceof String)) {
                        continue;
                    }
                    String target;
                    try {
                        target = ProcessorEngine.resolvePointer(
                                scopePath,
                                PointerUtils.assertValidRuntimePointer(
                                        (String) raw));
                        runtime
                                .validateProcessEmbeddedTraversalWithoutResolution(
                                        target);
                    } catch (ProcessorFailureException exception) {
                        if (exception.errorCategory()
                                != ProcessorErrorCategory
                                .CyclicSetEmbeddedBoundaryUnsupported) {
                            throw exception;
                        }
                        throw new SubscriptionSurfaceInvalidException(
                                exception.getMessage(),
                                scopePath,
                                entry.getKey(),
                                exception.errorCategory());
                    } catch (IllegalArgumentException ignored) {
                        // Contract recognition owns malformed-path precedence.
                        continue;
                    }
                    Node targetNode = ProcessorEngine.nodeAt(
                            inputDocument,
                            target);
                    if (targetNode != null
                            && !targetNode.isReferenceOnly()) {
                        pending.addLast(target);
                    }
                }
            }
        }
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
            return inputSnapshot.resolvedAt(normalized);
        }
        ensureProjected();
        if (classificationSnapshot != null) {
            return classificationSnapshot.resolvedAt(normalized);
        }
        Node selected = ProcessorEngine.nodeAt(
                classificationDocument,
                normalized);
        return selected != null
                ? FrozenNode.fromResolvedNode(selected)
                : null;
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

    private Node directProperty(Node node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }
}
