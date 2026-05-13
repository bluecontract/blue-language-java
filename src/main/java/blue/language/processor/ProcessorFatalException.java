package blue.language.processor;

public class ProcessorFatalException extends RuntimeException {

    private final DocumentProcessingResult partialResult;

    public ProcessorFatalException(String message) {
        this(message, null);
    }

    public ProcessorFatalException(String message, DocumentProcessingResult partialResult) {
        super(message);
        this.partialResult = partialResult;
    }

    public DocumentProcessingResult partialResult() {
        return partialResult;
    }

    public long totalGas() {
        return partialResult != null ? partialResult.totalGas() : 0L;
    }
}
