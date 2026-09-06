package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Proposed independently metered same-epoch historical step; not a revision cause. */
public final class ManagedRepresentationCause extends ProcessingCause implements ManagedHistoryStep {
    private final String targetOccurrenceIdentity;
    private final ManagedRepresentationTransition transition;
    private final String targetPositionIdentity;
    private final String nextRevisionReceiptIdentity;
    private final CyclicSetProof afterCyclicProof;

    /**
     * Describes one authenticated representation transition for a pending occurrence.
     * @param targetOccurrenceIdentity exact occurrence selected for this step
     * @param transition original committed transition and its historical position
     * @param targetPositionIdentity captured terminal position for this chain
     * @param nextRevisionReceiptIdentity next immutable numbered receipt, or null for a terminal tail
     * @param afterCyclicProof exact successor proof, required only for cyclic members
     */
    public ManagedRepresentationCause(String targetOccurrenceIdentity,
            ManagedRepresentationTransition transition, String targetPositionIdentity,
            String nextRevisionReceiptIdentity, CyclicSetProof afterCyclicProof) {
        super(identity(targetOccurrenceIdentity, transition, targetPositionIdentity, nextRevisionReceiptIdentity));
        this.targetOccurrenceIdentity = ClosureValueSupport.requireSha256Identity(targetOccurrenceIdentity, "targetOccurrenceIdentity");
        this.transition = Objects.requireNonNull(transition, "transition");
        this.targetPositionIdentity = ClosureValueSupport.requireSha256Identity(targetPositionIdentity, "targetPositionIdentity");
        this.nextRevisionReceiptIdentity = nextRevisionReceiptIdentity == null ? null
                : ClosureValueSupport.requireSha256Identity(nextRevisionReceiptIdentity, "nextRevisionReceiptIdentity");
        this.afterCyclicProof = copyProof(afterCyclicProof);
        boolean cyclic = blue.language.identity.BlueIds.hasCyclicMemberSeparator(afterBlueId());
        if (cyclic != (afterCyclicProof != null)) throw new IllegalArgumentException("Representation successor requires its exact cyclic proof");
        if (cyclic) ManagedRevisionCyclicEvidenceVerifier.verify(afterBlueId(), afterDocument(), afterCyclicProof);
    }
    private static String identity(String occurrence, ManagedRepresentationTransition transition,
            String target, String nextRevision) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("targetOccurrenceIdentity", occurrence);
        value.put("representationPositionIdentity", transition.positionIdentity());
        value.put("targetPositionIdentity", target);
        value.put("nextRevisionReceiptIdentity", nextRevision);
        return ClosureIdentityService.INSTANCE.identity(
                ClosureIdentityService.Constructor.MANAGED_REPRESENTATION_CAUSE, value);
    }
    String recomputedIdentity() { return identity(targetOccurrenceIdentity, transition, targetPositionIdentity, nextRevisionReceiptIdentity); }
    public Kind kind() { return Kind.MANAGED_REPRESENTATION; }
    public String targetOccurrenceIdentity() { return targetOccurrenceIdentity; }
    public DocumentId childDocumentId() { return transition.documentId(); }
    public long fromEpoch() { return transition.epoch(); }
    public long toEpoch() { return transition.epoch(); }
    public String beforeBlueId() { return transition.transitionReceipt().beforeBlueId(); }
    public String afterBlueId() { return transition.transitionReceipt().afterBlueId(); }
    public Node afterDocument() { return transition.afterDocument(); }
    public String originalSourceCauseIdentity() { return transition.transitionReceipt().originalCauseIdentity(); }
    public String sourceRevisionReceiptIdentity() { return transition.transitionReceipt().transitionReceiptIdentity(); }
    public Optional<ManagedDocumentTransitionReceipt> sourceTransitionReceipt() { return Optional.of(transition.transitionReceipt()); }
    public Optional<CyclicSetProof> afterCyclicProof() { return Optional.ofNullable(copyProof(afterCyclicProof)); }
    /** @return the original transition whose evidence this cause carries */
    public ManagedRepresentationTransition transition() { return transition; }
    /** @return the captured terminal position, unchanged by intermediate progress */
    public String targetPositionIdentity() { return targetPositionIdentity; }
    /** @return the next immutable numbered receipt, or null for a representation-only tail */
    public String nextRevisionReceiptIdentity() { return nextRevisionReceiptIdentity; }
    private static CyclicSetProof copyProof(CyclicSetProof proof) {
        return proof == null ? null : CyclicSetProof.fromDeclaredPlaceholderSet(proof.declaredPlaceholderSet());
    }
    /** @return whether this step reaches the captured end of a representation-only tail */
    public boolean terminalPositionReached() {
        return nextRevisionReceiptIdentity == null && targetPositionIdentity.equals(transition.positionIdentity());
    }
}
