package blue.language.processor;

/**
 * Signals that a tentative Root cannot produce a finite canonical subscription
 * delta and therefore cannot commit.
 */
public final class SubscriptionSurfaceInvalidException extends RuntimeException {

    private final ProcessorDiagnostic diagnostic;

    public SubscriptionSurfaceInvalidException(String message) {
        this(message, null, null);
    }

    public SubscriptionSurfaceInvalidException(String message,
                                               String scopePath,
                                               String contractKey) {
        super(message);
        ProcessorDiagnostic.Builder builder =
                ProcessorDiagnostic.builder(
                                ProcessorErrorCategory.SubscriptionSurfaceInvalid)
                        .message(message);
        if (scopePath != null) {
            builder.detail("scopePath", scopePath);
        }
        if (contractKey != null) {
            builder.detail("contractKey", contractKey);
        }
        this.diagnostic = builder.build();
    }

    public ProcessorDiagnostic diagnostic() {
        return diagnostic;
    }
}
