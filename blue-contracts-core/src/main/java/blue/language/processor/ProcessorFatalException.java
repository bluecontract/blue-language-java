package blue.language.processor;

/**
 * Host-visible fatal processor failure that may carry the exact admitted
 * partial result.
 *
 * <p>The partial result, when present, is immutable and is the source of the
 * reported gas total. Absence means the failure occurred before a publishable
 * processor result existed.</p>
 */
public class ProcessorFatalException extends RuntimeException {

    /** Immutable admitted result available when the failure was raised. */
    private final DocumentProcessingResult partialResult;
    /** Stable category serialized with this fatal failure. */
    private final ProcessorErrorCategory errorCategory;

    /**
     * Creates a fatal failure without a publishable partial result.
     *
     * @param message host-facing failure explanation
     */
    public ProcessorFatalException(String message) {
        this(message, null);
    }

    /**
     * Creates a fatal failure with an optional admitted partial result.
     *
     * @param message host-facing failure explanation
     * @param partialResult immutable partial result, or {@code null}
     */
    public ProcessorFatalException(String message, DocumentProcessingResult partialResult) {
        this(message, partialResult, ProcessorErrorCategory.RuntimeExecutionFailure);
    }

    /**
     * Creates a categorized fatal failure.
     *
     * @param message host-facing failure explanation
     * @param partialResult immutable partial result, or {@code null}
     * @param errorCategory stable category; {@code null} selects runtime
     *         execution failure
     */
    public ProcessorFatalException(String message,
                                   DocumentProcessingResult partialResult,
                                   ProcessorErrorCategory errorCategory) {
        super(message);
        this.partialResult = partialResult;
        this.errorCategory = errorCategory != null
                ? errorCategory
                : ProcessorErrorCategory.RuntimeExecutionFailure;
    }

    /**
     * Returns the immutable admitted result available at failure time.
     *
     * @return partial result, or {@code null}
     */
    public DocumentProcessingResult partialResult() {
        return partialResult;
    }

    /**
     * Returns gas admitted by the partial result.
     *
     * @return admitted gas, or zero when no partial result exists
     */
    public long totalGas() {
        return partialResult != null ? partialResult.totalGas() : 0L;
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
