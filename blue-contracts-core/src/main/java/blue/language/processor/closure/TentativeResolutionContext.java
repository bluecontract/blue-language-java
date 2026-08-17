package blue.language.processor.closure;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        for (ManagedDocumentSnapshot document
                : tentativeState.managedDocuments()) {
            current.put(document.documentId(), document.blueId());
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
}
