package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Exact contiguous transition chain from one admitted epoch to current state. */
public final class RevisionReconciliationPlan {
    private final DocumentId documentId;
    private final long admittedEpoch;
    private final long currentEpoch;
    private final List<ManagedRevisionEvidence> transitions;

    public RevisionReconciliationPlan(
            DocumentId documentId,
            long admittedEpoch,
            long currentEpoch,
            List<ManagedRevisionEvidence> transitions) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.admittedEpoch = CanonicalOrders.requireSafeInteger(
                admittedEpoch, "admittedEpoch");
        this.currentEpoch = CanonicalOrders.requireSafeInteger(
                currentEpoch, "currentEpoch");
        ArrayList<ManagedRevisionEvidence> copy =
                new ArrayList<ManagedRevisionEvidence>(Objects.requireNonNull(
                        transitions, "transitions"));
        for (ManagedRevisionEvidence transition : copy) {
            Objects.requireNonNull(transition, "transition");
        }
        this.transitions = Collections.unmodifiableList(copy);
        validate();
    }

    private void validate() {
        long epoch = admittedEpoch;
        String last = null;
        for (ManagedRevisionEvidence evidence : transitions) {
            if (!evidence.documentId().equals(documentId)
                    || evidence.fromEpoch() != epoch) {
                throw new IllegalArgumentException("noncontiguous transition chain");
            }
            if (last != null && !last.equals(evidence.beforeBlueId())) {
                throw new IllegalArgumentException("identity discontinuity");
            }
            epoch = evidence.toEpoch();
            last = evidence.afterBlueId();
        }
        if (epoch != currentEpoch) {
            throw new IllegalArgumentException("incomplete transition chain");
        }
    }

    public DocumentId documentId() { return documentId; }
    public long admittedEpoch() { return admittedEpoch; }
    public long currentEpoch() { return currentEpoch; }
    public List<ManagedRevisionEvidence> transitions() { return transitions; }
}
