package blue.coordination.closure;

import blue.contracts.closure.DocumentId;
import blue.contracts.closure.ManagedRevisionEvidence;
import blue.contracts.closure.CanonicalOrders;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Exact contiguous managed-revision chain selected by the feeder/host. */
public final class HistoricalRevisionEvidence {
    private final DocumentId documentId;
    private final long fromEpoch;
    private final long throughEpoch;
    private final List<ManagedRevisionEvidence> transitions;
    private final String proofIdentity;

    public HistoricalRevisionEvidence(
            DocumentId documentId,
            long fromEpoch,
            long throughEpoch,
            List<ManagedRevisionEvidence> transitions,
            String proofIdentity) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.fromEpoch = CanonicalOrders.requireSafeInteger(fromEpoch, "fromEpoch");
        this.throughEpoch = CanonicalOrders.requireSafeInteger(
                throughEpoch, "throughEpoch");
        ArrayList<ManagedRevisionEvidence> copy =
                new ArrayList<ManagedRevisionEvidence>(Objects.requireNonNull(
                        transitions, "transitions"));
        for (ManagedRevisionEvidence transition : copy) {
            Objects.requireNonNull(transition, "transition");
        }
        this.transitions = Collections.unmodifiableList(copy);
        this.proofIdentity = Objects.requireNonNull(proofIdentity, "proofIdentity");
        long expected = fromEpoch;
        String previousAfter = null;
        for (ManagedRevisionEvidence transition : this.transitions) {
            if (!transition.documentId().equals(documentId)
                    || transition.fromEpoch() != expected
                    || (previousAfter != null && !previousAfter.equals(transition.beforeBlueId()))) {
                throw new IllegalArgumentException("revision evidence must be contiguous");
            }
            expected = transition.toEpoch();
            previousAfter = transition.afterBlueId();
        }
        if (expected != throughEpoch) {
            throw new IllegalArgumentException("revision evidence does not reach throughEpoch");
        }
    }

    public DocumentId documentId() {
        return documentId;
    }

    public long fromEpoch() {
        return fromEpoch;
    }

    public long throughEpoch() {
        return throughEpoch;
    }

    public List<ManagedRevisionEvidence> transitions() {
        return transitions;
    }

    public String proofIdentity() {
        return proofIdentity;
    }
}
