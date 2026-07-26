package blue.language.processor;

/**
 * Normative completed status for a Contracts 1.0 {@code PROCESS} run.
 *
 * <p>{@code NeedsResources} deliberately does not appear here. Resource
 * acquisition suspends {@code PROCESS_ATTEMPT}; it is not a completed
 * {@link DocumentProcessingResult}.</p>
 */
public enum ProcessorStatus {
    SUCCESS("success"),
    NO_MATCH("no-match"),
    STALE("stale"),
    TERMINATED("terminated"),
    INVALID_PROCESSING_DOCUMENT("invalid-processing-document"),
    CAPABILITY_FAILURE("capability-failure"),
    RUNTIME_FATAL("runtime-fatal"),
    GAS_LIMIT_EXCEEDED("gas-limit-exceeded"),
    PORTABLE_LIMIT_EXCEEDED("portable-limit-exceeded"),
    SUBSCRIPTION_SURFACE_INVALID("subscription-surface-invalid");

    private final String wireValue;

    ProcessorStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /**
     * Returns whether this status commits the tentative Root and Root outbox.
     */
    public boolean commits() {
        return this == SUCCESS;
    }

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
