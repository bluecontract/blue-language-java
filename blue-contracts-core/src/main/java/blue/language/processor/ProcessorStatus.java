package blue.language.processor;

import blue.language.processor.util.ProcessorContractConstants;

/**
 * Normative completed status for a Contracts 1.0 {@code PROCESS} run.
 *
 * <p>{@code NeedsResources} deliberately does not appear here. Resource
 * acquisition suspends {@code PROCESS_ATTEMPT}; it is not a completed
 * {@link DocumentProcessingResult}.</p>
 */
public enum ProcessorStatus {
    /** Processing completed and commits the tentative root and outbox. */
    SUCCESS("success"),
    /** No eligible channel or handler matched the event. */
    NO_MATCH("no-match"),
    /** Supplied ordering or revision evidence was stale. */
    STALE("stale"),
    /** Processing observed a processor-managed termination marker. */
    TERMINATED(ProcessorContractConstants.KEY_TERMINATED),
    /** Admission rejected the processing root. */
    INVALID_PROCESSING_DOCUMENT("invalid-processing-document"),
    /** A required runtime capability was unavailable or invalid. */
    CAPABILITY_FAILURE("capability-failure"),
    /** Runtime execution ended with a fatal deterministic failure. */
    RUNTIME_FATAL("runtime-fatal"),
    /** Processing exhausted its admitted gas budget. */
    GAS_LIMIT_EXCEEDED("gas-limit-exceeded"),
    /** Processing exceeded a portable cardinality or size limit. */
    PORTABLE_LIMIT_EXCEEDED("portable-limit-exceeded"),
    /** Processing produced an invalid subscription surface. */
    SUBSCRIPTION_SURFACE_INVALID("subscription-surface-invalid");

    private final String wireValue;

    ProcessorStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Returns the stable serialized status value.
     *
     * @return Contracts wire value
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Returns whether this status commits the tentative Root and Root outbox.
     *
     * @return {@code true} only for {@link #SUCCESS}
     */
    public boolean commits() {
        return this == SUCCESS;
    }

    /**
     * Resolves a stable serialized status.
     *
     * @param value Contracts wire value
     * @return matching completed status
     * @throws IllegalArgumentException when {@code value} is unknown
     */
    public static ProcessorStatus fromWireValue(String value) {
        if (value != null) {
            for (ProcessorStatus status : values()) {
                if (status.wireValue.equals(value)) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("Unknown Contracts 1.0 status: " + value);
    }
}
