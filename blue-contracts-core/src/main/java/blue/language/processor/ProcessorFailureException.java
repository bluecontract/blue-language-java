package blue.language.processor;

/**
 * Deterministic processor rejection carrying its public diagnostic category.
 *
 * <p>A null category is normalized to
 * {@link ProcessorErrorCategory#RuntimeExecutionFailure}. Callers should map
 * the category rather than parsing the exception message.</p>
 */
public class ProcessorFailureException extends IllegalArgumentException {

    /** Stable category serialized with this processor rejection. */
    private final ProcessorErrorCategory errorCategory;

    /**
     * Creates a deterministic processor rejection.
     *
     * @param errorCategory stable public category; {@code null} selects
     *         {@link ProcessorErrorCategory#RuntimeExecutionFailure}
     * @param message deterministic failure explanation
     */
    public ProcessorFailureException(ProcessorErrorCategory errorCategory, String message) {
        super(message);
        this.errorCategory = errorCategory != null
                ? errorCategory
                : ProcessorErrorCategory.RuntimeExecutionFailure;
    }

    /**
     * Creates a deterministic processor rejection with its underlying cause.
     *
     * @param errorCategory stable public category; {@code null} selects
     *         {@link ProcessorErrorCategory#RuntimeExecutionFailure}
     * @param message deterministic failure explanation
     * @param cause underlying deterministic failure
     */
    public ProcessorFailureException(ProcessorErrorCategory errorCategory, String message, Throwable cause) {
        super(message, cause);
        this.errorCategory = errorCategory != null
                ? errorCategory
                : ProcessorErrorCategory.RuntimeExecutionFailure;
    }

    /**
     * Returns the stable category to publish to callers.
     *
     * @return non-null processor error category
     */
    public ProcessorErrorCategory errorCategory() {
        return errorCategory;
    }
}
