package blue.language.processor.closure;

/** Receives immutable evidence only for a completed closure attempt. */
public interface ClosureExecutionObserver {

    /** Opts into retaining externally observable source continuation sites. */
    default boolean capturesSourceObservationProgram() { return false; }

    /** Receives a program only after complete successful result construction. */
    default void onSourceObservationProgram(SourceObservationProgram program) { }

    /**
     * Observes an attempt after completion or deterministic fail-closed exit.
     * Retryable resource suspensions publish no snapshot because their work
     * is incomplete and must not be mistaken for completion evidence.
     *
     * @param evidence immutable implementation evidence
     */
    void onExecutionEvidence(ClosureImplementationEvidence evidence);
}
