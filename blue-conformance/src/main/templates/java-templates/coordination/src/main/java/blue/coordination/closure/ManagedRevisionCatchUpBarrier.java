package blue.coordination.closure;

import blue.contracts.closure.DocumentId;
import blue.contracts.closure.ManagedRevisionCause;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Coordination-owned ordered catch-up plan and no-overtake barrier.
 *
 * <p>The list is never passed to Contracts. Coordination selects only the cause
 * whose before state equals the last terminally committed cursor/state, invokes
 * PROCESS_CLOSURE once, and waits for that result to commit before selecting the
 * next cause.</p>
 */
public final class ManagedRevisionCatchUpBarrier {
    private final String targetOccurrenceIdentity;
    private final DocumentId childDocumentId;
    private final List<ManagedRevisionCause> causes;
    private final String barrierIdentity;

    public ManagedRevisionCatchUpBarrier(
            String targetOccurrenceIdentity,
            DocumentId childDocumentId,
            List<ManagedRevisionCause> causes,
            String barrierIdentity) {
        this.targetOccurrenceIdentity = Objects.requireNonNull(
                targetOccurrenceIdentity, "targetOccurrenceIdentity");
        this.childDocumentId = Objects.requireNonNull(
                childDocumentId, "childDocumentId");
        ArrayList<ManagedRevisionCause> copy = new ArrayList<ManagedRevisionCause>(
                Objects.requireNonNull(causes, "causes"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("causes");
        }
        ManagedRevisionCause previous = null;
        for (ManagedRevisionCause cause : copy) {
            Objects.requireNonNull(cause, "cause");
            if (!this.targetOccurrenceIdentity.equals(
                    cause.targetOccurrenceIdentity())
                    || !this.childDocumentId.equals(cause.childDocumentId())) {
                throw new IllegalArgumentException(
                        "catch-up cause targets another occurrence or child");
            }
            if (previous != null
                    && (cause.fromEpoch() != previous.toEpoch()
                    || !cause.beforeBlueId().equals(previous.afterBlueId()))) {
                throw new IllegalArgumentException(
                        "catch-up causes are not contiguous");
            }
            previous = cause;
        }
        this.causes = Collections.unmodifiableList(copy);
        this.barrierIdentity = Objects.requireNonNull(
                barrierIdentity, "barrierIdentity");
    }

    /** Returns the sole next cause after the supplied terminal commit. */
    public ManagedRevisionCause nextAfterCommit(
            long committedPendingEpoch, String committedTargetBlueId) {
        for (ManagedRevisionCause cause : causes) {
            if (cause.fromEpoch() == committedPendingEpoch
                    && cause.beforeBlueId().equals(committedTargetBlueId)) {
                return cause;
            }
        }
        throw new IllegalStateException(
                "no authenticated next cause for committed cursor/state");
    }

    /** Live/public work remains blocked exactly while the row has a cursor. */
    public boolean blocksLiveWork(Long pendingHistoricalEpoch) {
        return pendingHistoricalEpoch != null;
    }

    public String targetOccurrenceIdentity() { return targetOccurrenceIdentity; }
    public DocumentId childDocumentId() { return childDocumentId; }
    public List<ManagedRevisionCause> causes() { return causes; }
    public String barrierIdentity() { return barrierIdentity; }
}
