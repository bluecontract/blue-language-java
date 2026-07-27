package blue.language.processor;

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
