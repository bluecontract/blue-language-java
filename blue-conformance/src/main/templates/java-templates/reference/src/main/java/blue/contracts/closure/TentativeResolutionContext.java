package blue.contracts.closure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exact internal resolver view for one isolated document step.
 *
 * <p>The preferred {@link #from} factory derives every field from an already
 * verified invocation and the latest exact tentative closure snapshot. Runtime
 * application code never receives this object or its document map; the resolver
 * may use it only for references explicitly reachable from the target document.</p>
 */
public final class TentativeResolutionContext {
    private final String closureStateIdentity;
    private final String invocationIdentity;
    private final long graphGeneration;
    private final DocumentId targetDocumentId;
    private final ManagedScopeKey targetManagedScope;
    private final String targetBeforeBlueId;
    private final String componentIdentity;
    private final String componentStateIdentity;
    private final String cyclicProofIdentity;
    private final Map<DocumentId, String> stableDocumentIdToCurrentBlueId;
    private final String exactNodeProviderIdentity;
    private final String occurrenceBindingSetIdentity;

    private TentativeResolutionContext(
            String closureStateIdentity,
            String invocationIdentity,
            long graphGeneration,
            DocumentId targetDocumentId,
            ManagedScopeKey targetManagedScope,
            String targetBeforeBlueId,
            String componentIdentity,
            String componentStateIdentity,
            String cyclicProofIdentity,
            Map<DocumentId, String> stableDocumentIdToCurrentBlueId,
            String exactNodeProviderIdentity,
            String occurrenceBindingSetIdentity) {
        this.closureStateIdentity = Objects.requireNonNull(
                closureStateIdentity, "closureStateIdentity");
        this.invocationIdentity = Objects.requireNonNull(
                invocationIdentity, "invocationIdentity");
        this.graphGeneration = CanonicalOrders.requireSafeInteger(
                graphGeneration, "graphGeneration");
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.targetManagedScope = Objects.requireNonNull(
                targetManagedScope, "targetManagedScope");
        if (!this.targetManagedScope.isClosureRoot()
                || !this.targetDocumentId.equals(
                        this.targetManagedScope.documentId())) {
            throw new IllegalArgumentException("targetManagedScope");
        }
        this.targetBeforeBlueId = Objects.requireNonNull(
                targetBeforeBlueId, "targetBeforeBlueId");
        this.componentIdentity = Objects.requireNonNull(
                componentIdentity, "componentIdentity");
        this.componentStateIdentity = Objects.requireNonNull(
                componentStateIdentity, "componentStateIdentity");
        this.cyclicProofIdentity = cyclicProofIdentity;
        this.stableDocumentIdToCurrentBlueId = immutableCurrentBlueIds(
                stableDocumentIdToCurrentBlueId);
        if (!this.targetBeforeBlueId.equals(
                this.stableDocumentIdToCurrentBlueId.get(
                        this.targetDocumentId))) {
            throw new IllegalArgumentException("targetBeforeBlueId mismatch");
        }
        this.exactNodeProviderIdentity = Objects.requireNonNull(
                exactNodeProviderIdentity, "exactNodeProviderIdentity");
        this.occurrenceBindingSetIdentity = Objects.requireNonNull(
                occurrenceBindingSetIdentity, "occurrenceBindingSetIdentity");
    }

    /**
     * Reconstructs a fresh context immediately before a document step.
     * {@code tentativeState} may be the input snapshot or a later, exactly
     * re-finalized invocation-local snapshot.
     */
    public static TentativeResolutionContext from(
            ClosureInvocationInput invocation,
            AffectedClosureSnapshot tentativeState,
            DocumentId targetDocumentId) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(tentativeState, "tentativeState");
        Objects.requireNonNull(targetDocumentId, "targetDocumentId");
        AffectedClosureSnapshot.ManagedDocument target =
                tentativeState.documents().get(targetDocumentId);
        if (target == null) {
            throw new IllegalArgumentException("target document absent");
        }

        ComponentSnapshot targetComponent = null;
        for (ComponentSnapshot component : tentativeState.components()) {
            if (component.orderedMemberDocumentIds().contains(targetDocumentId)) {
                if (targetComponent != null) {
                    throw new IllegalArgumentException(
                            "target belongs to several components");
                }
                targetComponent = component;
            }
        }
        if (targetComponent == null) {
            throw new IllegalArgumentException("target component absent");
        }

        LinkedHashMap<DocumentId, String> currentBlueIds =
                new LinkedHashMap<DocumentId, String>();
        for (Map.Entry<DocumentId, AffectedClosureSnapshot.ManagedDocument> entry
                : tentativeState.documents().entrySet()) {
            currentBlueIds.put(entry.getKey(), entry.getValue().blueId());
        }
        for (int index = 0;
                index < targetComponent.orderedMemberDocumentIds().size();
                index++) {
            DocumentId member = targetComponent.orderedMemberDocumentIds().get(index);
            String componentBlueId =
                    targetComponent.orderedMemberBlueIds().get(index);
            if (!componentBlueId.equals(currentBlueIds.get(member))) {
                throw new IllegalArgumentException(
                        "component/document BlueId mismatch");
            }
        }

        return new TentativeResolutionContext(
                tentativeState.closureIdentity(),
                invocation.invocationIdentity(),
                tentativeState.graphGeneration(),
                targetDocumentId,
                ManagedScopeKey.closureRoot(targetDocumentId),
                target.blueId(),
                targetComponent.componentIdentity(),
                targetComponent.componentStateIdentity(),
                targetComponent.cyclicProofIdentity(),
                currentBlueIds,
                invocation.environment().exactNodeProviderDomain().identity(),
                tentativeState.occurrenceBindingSetIdentity());
    }

    /**
     * Adapter boundary for hosts whose verified snapshot is not represented by
     * the template classes. Every argument must already be bound by the named
     * invocation and latest tentative closure-state identity; this method does
     * not create a second identity domain.
     */
    public static TentativeResolutionContext fromBoundEvidence(
            String closureStateIdentity,
            String invocationIdentity,
            long graphGeneration,
            DocumentId targetDocumentId,
            String targetBeforeBlueId,
            String componentIdentity,
            String componentStateIdentity,
            String cyclicProofIdentity,
            Map<DocumentId, String> stableDocumentIdToCurrentBlueId,
            String exactNodeProviderIdentity,
            String occurrenceBindingSetIdentity) {
        return new TentativeResolutionContext(
                closureStateIdentity,
                invocationIdentity,
                graphGeneration,
                targetDocumentId,
                ManagedScopeKey.closureRoot(targetDocumentId),
                targetBeforeBlueId,
                componentIdentity,
                componentStateIdentity,
                cyclicProofIdentity,
                stableDocumentIdToCurrentBlueId,
                exactNodeProviderIdentity,
                occurrenceBindingSetIdentity);
    }

    private static Map<DocumentId, String> immutableCurrentBlueIds(
            Map<DocumentId, String> values) {
        LinkedHashMap<DocumentId, String> copy =
                new LinkedHashMap<DocumentId, String>();
        DocumentId previous = null;
        for (Map.Entry<DocumentId, String> entry
                : Objects.requireNonNull(
                        values, "stableDocumentIdToCurrentBlueId").entrySet()) {
            DocumentId current = Objects.requireNonNull(
                    entry.getKey(), "current BlueId documentId");
            if (previous != null && previous.compareTo(current) >= 0) {
                throw new IllegalArgumentException(
                        "current BlueIds not in canonical order");
            }
            copy.put(
                    current,
                    Objects.requireNonNull(
                            entry.getValue(), "current document BlueId"));
            previous = current;
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("stableDocumentIdToCurrentBlueId");
        }
        return Collections.unmodifiableMap(copy);
    }

    public String closureStateIdentity() { return closureStateIdentity; }
    public String invocationIdentity() { return invocationIdentity; }
    public long graphGeneration() { return graphGeneration; }
    public DocumentId targetDocumentId() { return targetDocumentId; }
    public ManagedScopeKey targetManagedScope() { return targetManagedScope; }
    public String targetBeforeBlueId() { return targetBeforeBlueId; }
    public String componentIdentity() { return componentIdentity; }
    public String componentStateIdentity() { return componentStateIdentity; }
    public String cyclicProofIdentity() { return cyclicProofIdentity; }
    public Map<DocumentId, String> stableDocumentIdToCurrentBlueId() {
        return stableDocumentIdToCurrentBlueId;
    }
    public String exactNodeProviderIdentity() {
        return exactNodeProviderIdentity;
    }
    public String occurrenceBindingSetIdentity() {
        return occurrenceBindingSetIdentity;
    }
}
