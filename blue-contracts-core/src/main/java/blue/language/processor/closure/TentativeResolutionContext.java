package blue.language.processor.closure;

import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.provider.CyclicSetProof;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Processor-private exact reference view reconstructed before one document
 * step.
 *
 * <p>The context is deliberately not application data. It lets the processor
 * resolve references already reachable from the target document against the
 * latest tentatively finalized closure state, but carries no reverse
 * occurrence or containing-document projection.</p>
 */
public final class TentativeResolutionContext {

    private final String closureIdentity;
    private final String invocationIdentity;
    private final long graphGeneration;
    private final DocumentId targetDocumentId;
    private final ManagedScopeKey targetScope;
    private final String targetBeforeBlueId;
    private final String componentIdentity;
    private final String componentStateIdentity;
    private final String cyclicProofIdentity;
    private final Map<DocumentId, String> currentBlueIds;
    private final Map<DocumentId, Node> currentDocuments;
    private final Map<String, String> targetManagedBlueIdsByPath;
    private final Map<String, Node> managedReadExactNodesByBlueId;
    private final Map<String, CyclicSetProof> cyclicProofsByMasterBlueId;
    private final String exactNodeProviderDomainIdentity;
    private final String occurrenceBindingSetIdentity;

    private TentativeResolutionContext(
            String closureIdentity,
            String invocationIdentity,
            long graphGeneration,
            DocumentId targetDocumentId,
            String targetBeforeBlueId,
            String componentIdentity,
            String componentStateIdentity,
            String cyclicProofIdentity,
            Map<DocumentId, String> currentBlueIds,
            Map<DocumentId, Node> currentDocuments,
            Map<String, String> targetManagedBlueIdsByPath,
            Map<String, Node> managedReadExactNodesByBlueId,
            Map<String, CyclicSetProof> cyclicProofsByMasterBlueId,
            String exactNodeProviderDomainIdentity,
            String occurrenceBindingSetIdentity) {
        this.closureIdentity = ClosureValueSupport.requireSha256Identity(
                closureIdentity, "closureIdentity");
        this.invocationIdentity = ClosureValueSupport.requireSha256Identity(
                invocationIdentity, "invocationIdentity");
        this.graphGeneration = ClosureValueSupport.requireSafeInteger(
                graphGeneration, "graphGeneration");
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.targetScope = ManagedScopeKey.root(this.targetDocumentId);
        this.targetBeforeBlueId = ClosureValueSupport.requireBlueId(
                targetBeforeBlueId, "targetBeforeBlueId");
        this.componentIdentity = ClosureValueSupport.requireSha256Identity(
                componentIdentity, "componentIdentity");
        this.componentStateIdentity = ClosureValueSupport.requireSha256Identity(
                componentStateIdentity, "componentStateIdentity");
        this.cyclicProofIdentity = cyclicProofIdentity == null
                ? null
                : ClosureValueSupport.requireSha256Identity(
                        cyclicProofIdentity, "cyclicProofIdentity");
        this.currentBlueIds = immutableCurrentBlueIds(currentBlueIds);
        this.currentDocuments = immutableCurrentDocuments(
                currentDocuments, this.currentBlueIds);
        this.targetManagedBlueIdsByPath = immutableManagedPaths(
                targetManagedBlueIdsByPath);
        this.managedReadExactNodesByBlueId = immutableExactNodes(
                managedReadExactNodesByBlueId);
        this.cyclicProofsByMasterBlueId = immutableCyclicProofs(
                cyclicProofsByMasterBlueId,
                this.currentBlueIds,
                this.managedReadExactNodesByBlueId);
        if (!this.targetBeforeBlueId.equals(
                this.currentBlueIds.get(this.targetDocumentId))) {
            throw new IllegalArgumentException(
                    "Target before BlueId is not the current closure state");
        }
        this.exactNodeProviderDomainIdentity =
                ClosureValueSupport.requireSha256Identity(
                        exactNodeProviderDomainIdentity,
                        "exactNodeProviderDomainIdentity");
        this.occurrenceBindingSetIdentity =
                ClosureValueSupport.requireSha256Identity(
                        occurrenceBindingSetIdentity,
                        "occurrenceBindingSetIdentity");
    }

    /**
     * Reconstructs the exact context from a verified invocation and latest
     * tentatively finalized closure snapshot.
     *
     * @param invocation verified invocation evidence
     * @param tentativeState latest exact tentative state
     * @param targetDocumentId one selected document
     * @return fresh processor-private resolution context
     */
    public static TentativeResolutionContext from(
            ClosureInvocationInput invocation,
            AffectedClosureSnapshot tentativeState,
            DocumentId targetDocumentId) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(tentativeState, "tentativeState");
        Objects.requireNonNull(targetDocumentId, "targetDocumentId");

        ManagedDocumentSnapshot target =
                tentativeState.managedDocument(targetDocumentId);
        if (target == null) {
            throw new IllegalArgumentException(
                    "Target is outside the tentative affected closure");
        }
        ComponentSnapshot owner = null;
        for (ComponentSnapshot component : tentativeState.components()) {
            if (component.orderedMemberDocumentIds().contains(
                    targetDocumentId)) {
                if (owner != null) {
                    throw new IllegalArgumentException(
                            "Target belongs to more than one component");
                }
                owner = component;
            }
        }
        if (owner == null) {
            throw new IllegalArgumentException(
                    "Target has no component state");
        }

        LinkedHashMap<DocumentId, String> current =
                new LinkedHashMap<DocumentId, String>();
        LinkedHashMap<DocumentId, Node> documents =
                new LinkedHashMap<DocumentId, Node>();
        for (ManagedDocumentSnapshot document
                : tentativeState.managedDocuments()) {
            current.put(document.documentId(), document.blueId());
            documents.put(document.documentId(), document.document());
        }
        ArrayList<ManagedOccurrenceBinding> forwardBindings =
                new ArrayList<ManagedOccurrenceBinding>();
        Node targetBody = documents.get(targetDocumentId);
        for (ManagedOccurrenceBinding binding
                : tentativeState.occurrences()) {
            if (binding.sourceDocumentId().equals(targetDocumentId)
                    && (binding.active()
                            || binding.pendingHistoricalEpoch() != null)
                    && NodePathEditor.getOrNull(
                            targetBody, binding.sourcePath()) != null) {
                forwardBindings.add(binding);
            }
        }
        Collections.sort(forwardBindings,
                new Comparator<ManagedOccurrenceBinding>() {
                    @Override
                    public int compare(
                            ManagedOccurrenceBinding left,
                            ManagedOccurrenceBinding right) {
                        return ClosureValueSupport.comparePortableText(
                                left.sourcePath(), right.sourcePath());
                    }
                });
        LinkedHashMap<String, String> canonicalForwardPaths =
                new LinkedHashMap<String, String>();
        for (ManagedOccurrenceBinding binding : forwardBindings) {
            if (canonicalForwardPaths.put(
                    binding.sourcePath(),
                    binding.expectedTargetBlueId()) != null) {
                throw new IllegalArgumentException(
                        "Managed Root has duplicate current occurrence paths");
            }
        }
        LinkedHashMap<String, Node> managedReadExactNodes =
                new LinkedHashMap<String, Node>();
        LinkedHashMap<String, CyclicSetProof> cyclicProofs =
                new LinkedHashMap<String, CyclicSetProof>();
        for (ComponentSnapshot component : tentativeState.components()) {
            if (component.kind() == ComponentKind.CYCLIC) {
                cyclicProofs.put(
                        component.masterBlueId(),
                        component.completeCyclicProof());
            }
        }
        if (invocation.cause() instanceof ManagedHistoryStep) {
            ManagedHistoryStep revision =
                    (ManagedHistoryStep) invocation.cause();
            for (ManagedOccurrenceBinding binding : forwardBindings) {
                if (binding.occurrenceIdentity().equals(
                            revision.targetOccurrenceIdentity())
                        && binding.targetDocumentId().equals(
                                revision.childDocumentId())
                        && binding.expectedTargetBlueId().equals(
                                revision.afterBlueId())) {
                    managedReadExactNodes.put(
                            revision.afterBlueId(),
                            revision.afterDocument());
                    if (BlueIds.hasCyclicMemberSeparator(
                            revision.afterBlueId())) {
                        cyclicProofs.putIfAbsent(
                                BlueIds.cyclicSetMasterBlueId(
                                        revision.afterBlueId()),
                                revision.afterCyclicProof().orElseThrow(
                                        () -> new IllegalArgumentException(
                                                "Cyclic managed read omitted its complete proof")));
                    }
                    break;
                }
            }
        }
        return new TentativeResolutionContext(
                tentativeState.closureIdentity(),
                invocation.invocationIdentity(),
                tentativeState.graphGeneration(),
                targetDocumentId,
                target.blueId(),
                owner.componentIdentity(),
                owner.componentStateIdentity(),
                owner.cyclicProofIdentity(),
                current,
                documents,
                canonicalForwardPaths,
                managedReadExactNodes,
                cyclicProofs,
                invocation.environment().exactNodeProviderDomainIdentity(),
                tentativeState.occurrenceBindingSetIdentity());
    }

    /** Returns the state-only closure identity.
     * @return closure identity */
    public String closureIdentity() { return closureIdentity; }

    /** Returns the exact invocation identity.
     * @return invocation identity */
    public String invocationIdentity() { return invocationIdentity; }

    /** Returns the current graph generation.
     * @return graph generation */
    public long graphGeneration() { return graphGeneration; }

    /** Returns the selected managed document.
     * @return document identity */
    public DocumentId targetDocumentId() { return targetDocumentId; }

    /** Returns the selected Root scope.
     * @return managed-scope key */
    public ManagedScopeKey targetScope() { return targetScope; }

    /** Returns the exact pre-step target identity.
     * @return before BlueId */
    public String targetBeforeBlueId() { return targetBeforeBlueId; }

    /** Returns the stable target component identity.
     * @return component identity */
    public String componentIdentity() { return componentIdentity; }

    /** Returns the exact component-state identity.
     * @return state identity */
    public String componentStateIdentity() { return componentStateIdentity; }

    /**
     * Returns the exact cyclic proof identity.
     * @return proof identity, or {@code null} for an acyclic target
     */
    public String cyclicProofIdentity() { return cyclicProofIdentity; }

    /**
     * Returns the current exact document identities. Application runtime code
     * must never receive this map.
     *
     * @return immutable map in canonical DocumentId order
     */
    public Map<DocumentId, String> currentBlueIds() { return currentBlueIds; }

    /**
     * Returns defensive exact current closure documents for processor-only
     * provider overlay construction. Application runtime code must never
     * receive this map.
     *
     * @return immutable map in canonical DocumentId order
     */
    public Map<DocumentId, Node> currentDocuments() {
        LinkedHashMap<DocumentId, Node> copy =
                new LinkedHashMap<DocumentId, Node>();
        for (Map.Entry<DocumentId, Node> entry
                : currentDocuments.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns concrete forward managed paths present in the selected Root.
     *
     * @return immutable canonical absolute paths
     */
    public List<String> targetManagedPaths() {
        return Collections.unmodifiableList(new ArrayList<String>(
                targetManagedBlueIdsByPath.keySet()));
    }

    /**
     * Returns each forward managed path's admitted exact target BlueId.
     *
     * @return immutable canonical path-to-BlueId map
     */
    public Map<String, String> targetManagedBlueIdsByPath() {
        return targetManagedBlueIdsByPath;
    }

    /** Returns defensive invocation-local historical managed-read bodies. */
    Map<String, Node> managedReadExactNodesByBlueId() {
        LinkedHashMap<String, Node> copy =
                new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry
                : managedReadExactNodesByBlueId.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Returns defensive complete proofs for invocation-local cyclic members. */
    Map<String, CyclicSetProof> cyclicProofsByMasterBlueId() {
        LinkedHashMap<String, CyclicSetProof> copy =
                new LinkedHashMap<String, CyclicSetProof>();
        for (Map.Entry<String, CyclicSetProof> entry
                : cyclicProofsByMasterBlueId.entrySet()) {
            copy.put(entry.getKey(), copyProof(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Returns the selected provider-domain identity.
     * @return identity */
    public String exactNodeProviderDomainIdentity() {
        return exactNodeProviderDomainIdentity;
    }

    /** Returns the occurrence-binding-set identity.
     * @return identity */
    public String occurrenceBindingSetIdentity() {
        return occurrenceBindingSetIdentity;
    }

    private static Map<DocumentId, String> immutableCurrentBlueIds(
            Map<DocumentId, String> values) {
        LinkedHashMap<DocumentId, String> copy =
                new LinkedHashMap<DocumentId, String>();
        DocumentId previous = null;
        for (Map.Entry<DocumentId, String> entry
                : Objects.requireNonNull(values, "currentBlueIds").entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "current BlueId documentId");
            if (previous != null && previous.compareTo(documentId) >= 0) {
                throw new IllegalArgumentException(
                        "Current document identities are not canonical");
            }
            copy.put(
                    documentId,
                    ClosureValueSupport.requireBlueId(
                            entry.getValue(), "current document BlueId"));
            previous = documentId;
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "A resolution context requires closure membership");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<DocumentId, Node> immutableCurrentDocuments(
            Map<DocumentId, Node> values,
            Map<DocumentId, String> identities) {
        LinkedHashMap<DocumentId, Node> copy =
                new LinkedHashMap<DocumentId, Node>();
        for (DocumentId documentId : identities.keySet()) {
            Node document = Objects.requireNonNull(
                    Objects.requireNonNull(values, "currentDocuments")
                            .get(documentId),
                    "current document");
            copy.put(documentId, document.clone());
        }
        if (copy.size() != values.size()) {
            throw new IllegalArgumentException(
                    "Current documents must match current BlueIds");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> immutableManagedPaths(
            Map<String, String> values) {
        LinkedHashMap<String, String> copy =
                new LinkedHashMap<String, String>();
        String previous = null;
        for (Map.Entry<String, String> entry : Objects.requireNonNull(
                values, "targetManagedBlueIdsByPath").entrySet()) {
            String value = Objects.requireNonNull(
                    entry.getKey(), "target managed path");
            if (previous != null
                    && ClosureValueSupport.comparePortableText(
                            previous, value) >= 0) {
                throw new IllegalArgumentException(
                        "Target managed paths are not canonical");
            }
            copy.put(value, ClosureValueSupport.requireBlueId(
                    entry.getValue(), "target managed BlueId"));
            previous = value;
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Node> immutableExactNodes(
            Map<String, Node> values) {
        LinkedHashMap<String, Node> copy =
                new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : Objects.requireNonNull(
                values, "managedReadExactNodesByBlueId").entrySet()) {
            String blueId = ClosureValueSupport.requireBlueId(
                    entry.getKey(), "managed read exact BlueId");
            copy.put(blueId, Objects.requireNonNull(
                    entry.getValue(), "managed read exact node").clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, CyclicSetProof> immutableCyclicProofs(
            Map<String, CyclicSetProof> values,
            Map<DocumentId, String> currentBlueIds,
            Map<String, Node> managedReadExactNodes) {
        LinkedHashMap<String, CyclicSetProof> copy =
                new LinkedHashMap<String, CyclicSetProof>();
        for (Map.Entry<String, CyclicSetProof> entry
                : Objects.requireNonNull(
                        values, "cyclicProofsByMasterBlueId").entrySet()) {
            String masterBlueId = BlueIds.requirePlainBlueId(
                    entry.getKey(), "resolution-context cyclic proof master");
            copy.put(masterBlueId, copyProof(Objects.requireNonNull(
                    entry.getValue(), "resolution-context cyclic proof")));
        }
        java.util.LinkedHashSet<String> requiredMasters =
                new java.util.LinkedHashSet<String>();
        for (String blueId : currentBlueIds.values()) {
            if (BlueIds.hasCyclicMemberSeparator(blueId)) {
                requiredMasters.add(BlueIds.cyclicSetMasterBlueId(blueId));
            }
        }
        for (String blueId : managedReadExactNodes.keySet()) {
            if (BlueIds.hasCyclicMemberSeparator(blueId)) {
                requiredMasters.add(BlueIds.cyclicSetMasterBlueId(blueId));
            }
        }
        if (!copy.keySet().equals(requiredMasters)) {
            throw new IllegalArgumentException(
                    "Resolution-context cyclic proofs must exactly cover cyclic exact nodes");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static CyclicSetProof copyProof(CyclicSetProof proof) {
        return CyclicSetProof.fromDeclaredPlaceholderSet(
                proof.declaredPlaceholderSet());
    }
}
