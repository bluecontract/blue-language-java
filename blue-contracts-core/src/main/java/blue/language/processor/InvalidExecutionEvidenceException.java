package blue.language.processor;

/**
 * Deterministic rejection of stale, mismatched, or caller-forged execution
 * evidence.
 *
 * <p>This is a terminal input failure, not provider unavailability. The
 * processor preserves its category when mapping the exception to a public
 * diagnostic.</p>
 */
public final class InvalidExecutionEvidenceException extends RuntimeException {

    /** Stable category serialized with this deterministic rejection. */
    private final ProcessorErrorCategory errorCategory;

    /**
     * Creates a rejection in the default external-snapshot category.
     *
     * @param message deterministic failure explanation
     */
    public InvalidExecutionEvidenceException(String message) {
        this(
                message,
                ProcessorErrorCategory
                        .InvalidExternalChannelSnapshot);
    }

    /**
     * Creates a rejection with an explicit public category.
     *
     * @param message deterministic failure explanation
     * @param errorCategory stable diagnostic category; {@code null} selects
     *         the default external-snapshot category
     */
    public InvalidExecutionEvidenceException(
            String message,
            ProcessorErrorCategory errorCategory) {
        super(message);
        this.errorCategory = errorCategory != null
                ? errorCategory
                : ProcessorErrorCategory
                .InvalidExternalChannelSnapshot;
    }

    /**
     * Returns the stable category to expose to callers.
     *
     * @return non-null processor error category
     */
    public ProcessorErrorCategory errorCategory() {
        return errorCategory;
    }
}
