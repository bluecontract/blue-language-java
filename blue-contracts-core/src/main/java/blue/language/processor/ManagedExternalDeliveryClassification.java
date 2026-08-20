package blue.language.processor;

/** Read-only authoritative Phase-B result for one raw Root source Channel. */
public final class ManagedExternalDeliveryClassification {

    /** Closed classification state before Handler execution. */
    public enum State {
        /** Event keys did not preselect the source Channel. */
        NO_MATCH,
        /** Preselection matched but the source rejected the exact event. */
        REJECTED,
        /** Source accepted but the exact checkpoint subject was not new. */
        STALE,
        /** Source accepted a new exact subject and may execute one delivery. */
        ACCEPTED_NEW
    }

    private final State state;
    private final boolean preselected;
    private final boolean accepted;
    private final boolean handlerMatched;
    private final ManagedCheckpointCandidate candidate;

    ManagedExternalDeliveryClassification(
            State state,
            boolean preselected,
            boolean accepted,
            boolean handlerMatched,
            ManagedCheckpointCandidate candidate) {
        this.state = java.util.Objects.requireNonNull(state, "state");
        this.preselected = preselected;
        this.accepted = accepted;
        this.handlerMatched = handlerMatched;
        this.candidate = candidate;
        if ((state == State.STALE || state == State.ACCEPTED_NEW)
                != (candidate != null)) {
            throw new IllegalArgumentException(
                    "Accepted classification requires checkpoint candidate");
        }
        if (state == State.ACCEPTED_NEW
                != (candidate != null && candidate.eligibleNew())) {
            throw new IllegalArgumentException(
                    "Candidate eligibility disagrees with classification");
        }
    }

    /**
     * Returns the closed Phase-B state.
     *
     * @return exact classification state
     */
    public State state() { return state; }

    /**
     * Reports whether subscription preselection matched.
     *
     * @return immutable preselection outcome
     */
    public boolean preselected() { return preselected; }

    /**
     * Reports whether the registered source accepted the exact event.
     *
     * @return immutable acceptance outcome
     */
    public boolean accepted() { return accepted; }

    /**
     * Reports whether an exact same-Root Handler target was resolved.
     *
     * @return immutable Handler-target outcome
     */
    public boolean handlerMatched() { return handlerMatched; }

    /**
     * Returns the frozen candidate for accepted stale or new classifications.
     *
     * @return immutable candidate, or {@code null} for no-match/rejected
     */
    public ManagedCheckpointCandidate candidate() { return candidate; }
}
