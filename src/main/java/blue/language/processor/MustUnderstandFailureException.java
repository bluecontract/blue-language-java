package blue.language.processor;

/**
 * Internal deterministic failure for a contract feature the processor cannot
 * safely interpret.
 *
 * <p>The category is preserved when the engine converts the exception to a
 * public capability or runtime diagnostic; it is not a suspension signal.</p>
 */
class MustUnderstandFailureException extends RuntimeException {

    private final ProcessorErrorCategory errorCategory;

    MustUnderstandFailureException(String message) {
        this(message, ProcessorErrorCategory.UnsupportedRuntimeType);
    }

    MustUnderstandFailureException(String message, ProcessorErrorCategory errorCategory) {
        super(message);
        this.errorCategory = errorCategory != null
                ? errorCategory
                : ProcessorErrorCategory.UnsupportedRuntimeType;
    }

    ProcessorErrorCategory errorCategory() {
        return errorCategory;
    }
}
