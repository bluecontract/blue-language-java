package blue.language.processor;

/**
 * Runtime exception carrying a processor diagnostic category.
 */
public class ProcessorFailureException extends IllegalArgumentException {

    private final ProcessorErrorCategory errorCategory;

    public ProcessorFailureException(ProcessorErrorCategory errorCategory, String message) {
        super(message);
        this.errorCategory = errorCategory != null
                ? errorCategory
                : ProcessorErrorCategory.InternalProcessorError;
    }

    public ProcessorFailureException(ProcessorErrorCategory errorCategory, String message, Throwable cause) {
        super(message, cause);
        this.errorCategory = errorCategory != null
                ? errorCategory
                : ProcessorErrorCategory.InternalProcessorError;
    }

    public ProcessorErrorCategory errorCategory() {
        return errorCategory;
    }
}
