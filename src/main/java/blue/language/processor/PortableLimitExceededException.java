package blue.language.processor;

/**
 * Raised before bounded semantic work when a Contracts 1.0 portable limit is
 * exceeded.
 */
public final class PortableLimitExceededException extends RuntimeException {

    /** Stable diagnostic category for the exceeded boundary. */
    private final ProcessorErrorCategory category;
    /** Published name of the portable limit. */
    private final String limitName;
    /** Rejected observed value. */
    private final long observed;
    /** Published maximum value. */
    private final long limit;

    /**
     * Creates a direct-node portable-limit rejection.
     *
     * @param limitName published portable-limit name
     * @param observed rejected observed value
     * @param limit published maximum
     */
    public PortableLimitExceededException(String limitName,
                                          long observed,
                                          long limit) {
        this(ProcessorErrorCategory.DirectNodeLimitExceeded,
                limitName,
                observed,
                limit);
    }

    /**
     * Creates a portable-limit rejection with an explicit diagnostic category.
     *
     * @param category stable public category; {@code null} selects the
     *         direct-node category
     * @param limitName published portable-limit name
     * @param observed rejected observed value
     * @param limit published maximum
     */
    public PortableLimitExceededException(ProcessorErrorCategory category,
                                          String limitName,
                                          long observed,
                                          long limit) {
        super("Portable limit exceeded: " + limitName);
        this.category = category != null
                ? category
                : ProcessorErrorCategory.DirectNodeLimitExceeded;
        this.limitName = limitName;
        this.observed = observed;
        this.limit = limit;
    }

    /**
     * Returns the published limit name.
     *
     * @return portable-limit name
     */
    public String limitName() {
        return limitName;
    }

    /**
     * Returns the value that exceeded the limit.
     *
     * @return rejected observation
     */
    public long observed() {
        return observed;
    }

    /**
     * Returns the published maximum.
     *
     * @return portable bound
     */
    public long limit() {
        return limit;
    }

    /**
     * Converts this rejection to its stable public diagnostic.
     *
     * @return immutable limit diagnostic
     */
    public ProcessorDiagnostic diagnostic() {
        return ProcessorDiagnostic.builder(category)
                .message(getMessage())
                .detail(
                        ProcessorDiagnosticConstants.FIELD_LIMIT_NAME,
                        limitName)
                .detail(
                        ProcessorDiagnosticConstants.FIELD_OBSERVED,
                        observed)
                .detail(
                        ProcessorDiagnosticConstants.FIELD_LIMIT,
                        limit)
                .build();
    }
}
