package blue.language.processor;

/**
 * Raised before bounded semantic work when a Contracts 1.0 portable limit is
 * exceeded.
 */
public final class PortableLimitExceededException extends RuntimeException {

    private final ProcessorErrorCategory category;
    private final String limitName;
    private final long observed;
    private final long limit;

    public PortableLimitExceededException(String limitName,
                                          long observed,
                                          long limit) {
        this(ProcessorErrorCategory.DirectNodeLimitExceeded,
                limitName,
                observed,
                limit);
    }

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

    public String limitName() {
        return limitName;
    }

    public long observed() {
        return observed;
    }

    public long limit() {
        return limit;
    }

    public ProcessorDiagnostic diagnostic() {
        return ProcessorDiagnostic.builder(category)
                .message(getMessage())
                .detail("limitName", limitName)
                .detail("observed", observed)
                .detail("limit", limit)
                .build();
    }
}
