package blue.language.processor;

/**
 * Signals that a tentative Root cannot produce a finite canonical subscription
 * delta and therefore cannot commit.
 */
public final class SubscriptionSurfaceInvalidException extends RuntimeException {

    /** Stable diagnostic serialized with this subscription rejection. */
    private final ProcessorDiagnostic diagnostic;

    /**
     * Creates a subscription-surface rejection without location details.
     *
     * @param message deterministic failure explanation
     */
    public SubscriptionSurfaceInvalidException(String message) {
        this(message, null, null);
    }

    /**
     * Creates a subscription-surface rejection at one contract location.
     *
     * @param message deterministic failure explanation
     * @param scopePath absolute processing scope, or {@code null}
     * @param contractKey contract key, or {@code null}
     */
    public SubscriptionSurfaceInvalidException(String message,
                                               String scopePath,
                                               String contractKey) {
        this(
                message,
                scopePath,
                contractKey,
                ProcessorErrorCategory
                        .SubscriptionSurfaceInvalid);
    }

    /**
     * Creates a categorized subscription-surface rejection.
     *
     * @param message deterministic failure explanation
     * @param scopePath absolute processing scope, or {@code null}
     * @param contractKey contract key, or {@code null}
     * @param errorCategory stable category; {@code null} selects
     *         {@link ProcessorErrorCategory#SubscriptionSurfaceInvalid}
     */
    public SubscriptionSurfaceInvalidException(
            String message,
            String scopePath,
            String contractKey,
            ProcessorErrorCategory errorCategory) {
        super(message);
        ProcessorDiagnostic.Builder builder =
                ProcessorDiagnostic.builder(
                                errorCategory != null
                                        ? errorCategory
                                        : ProcessorErrorCategory
                                        .SubscriptionSurfaceInvalid)
                        .message(message);
        if (scopePath != null) {
            builder.detail(
                    ProcessorDiagnosticConstants.FIELD_SCOPE_PATH,
                    scopePath);
        }
        if (contractKey != null) {
            builder.detail(
                    ProcessorDiagnosticConstants.FIELD_CONTRACT_KEY,
                    contractKey);
        }
        this.diagnostic = builder.build();
    }

    /**
     * Returns the stable diagnostic assembled at the rejection boundary.
     *
     * @return immutable processor diagnostic
     */
    public ProcessorDiagnostic diagnostic() {
        return diagnostic;
    }
}
