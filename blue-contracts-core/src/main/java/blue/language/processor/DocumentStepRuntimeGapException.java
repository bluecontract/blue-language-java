package blue.language.processor;

/**
 * Machine-detectable fail-closed boundary for a missing closure continuation.
 */
public final class DocumentStepRuntimeGapException
        extends ProcessorFailureException {

    /** Closed reasons for effects that cannot be safely continued locally. */
    public enum Reason {
        /** A patch needs the closure's immediate continuation hook. */
        PATCH_CONTINUATION_HOOK_REQUIRED,
        /** An emitted event needs frozen closure occurrence continuation. */
        APPLICATION_EVENT_CONTINUATION_HOOK_REQUIRED,
        /** A termination request needs the closure continuation hook. */
        TERMINATION_CONTINUATION_HOOK_REQUIRED
    }

    /** Machine-readable reason retained by this failure. */
    private final Reason reason;

    /**
     * Creates a fail-closed continuation failure.
     *
     * @param reason exact missing continuation role
     * @param message deterministic diagnostic text
     */
    public DocumentStepRuntimeGapException(Reason reason, String message) {
        super(ProcessorErrorCategory.UnsupportedRuntimeRole, message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the exact missing continuation role.
     *
     * @return machine-readable reason
     */
    public Reason reason() {
        return reason;
    }
}
