package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodePathEditor;

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
            if (document.hasResidentBody()) documents.put(document.documentId(), document.document());
        }
        ArrayList<ManagedOccurrenceBinding> forwardBindings =
                new ArrayList<ManagedOccurrenceBinding>();
        Node targetBody = target.document();
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
        for (ManagedReadPin pin : tentativeState.readPins()) {
            managedReadExactNodes.put(pin.blueId(), pin.document());
        }
        if (invocation.cause() instanceof ManagedRevisionCause) {
            ManagedRevisionCause revision =
                    (ManagedRevisionCause) invocation.cause();
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
        Objects.requireNonNull(values, "currentDocuments");
        for (DocumentId documentId : identities.keySet()) {
            Node document = values.get(documentId);
            if (document != null) copy.put(documentId, document.clone());
        }
        if (copy.size() != values.size()) {
            throw new IllegalArgumentException(
                    "Resident documents must belong to current BlueIds");
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
}
