package blue.language.processor;

class MustUnderstandFailureException extends RuntimeException {

    private final ProcessorErrorCategory errorCategory;

    MustUnderstandFailureException(String message) {
        this(message, ProcessorErrorCategory.UnsupportedContract);
    }

    MustUnderstandFailureException(String message, ProcessorErrorCategory errorCategory) {
        super(message);
        this.errorCategory = errorCategory != null
                ? errorCategory
                : ProcessorErrorCategory.UnsupportedContract;
    }

    ProcessorErrorCategory errorCategory() {
        return errorCategory;
    }
}
