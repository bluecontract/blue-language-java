package blue.language.processor;

public class ProcessorFatalException extends RuntimeException {

    private final DocumentProcessingResult partialResult;
    private final ProcessorErrorCategory errorCategory;

    public ProcessorFatalException(String message) {
        this(message, null);
    }

    public ProcessorFatalException(String message, DocumentProcessingResult partialResult) {
        this(message, partialResult, ProcessorErrorCategory.RuntimeExecutionFailure);
    }

    public ProcessorFatalException(String message,
                                   DocumentProcessingResult partialResult,
                                   ProcessorErrorCategory errorCategory) {
        super(message);
        this.partialResult = partialResult;
        this.errorCategory = errorCategory != null
                ? errorCategory
                : ProcessorErrorCategory.RuntimeExecutionFailure;
    }

    public DocumentProcessingResult partialResult() {
        return partialResult;
    }

    public long totalGas() {
        return partialResult != null ? partialResult.totalGas() : 0L;
    }

    public ProcessorErrorCategory errorCategory() {
        return errorCategory;
    }
}
