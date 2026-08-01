package blue.language.conformance.api;

/** Immutable diagnostic for one failed Contracts conformance fixture. */
public final class BlueContractsConformanceFailure {

    private final String fixtureId;
    private final BlueContractsFixtureCategory category;
    private final String operation;
    private final String exceptionClass;
    private final String message;

    /**
     * Creates a failure record using the fixture's stable manifest identity.
     *
     * @param fixtureId stable fixture identity
     * @param category fixture category
     * @param operation operation exercised by the fixture
     * @param exceptionClass thrown exception class name
     * @param message diagnostic message
     */
    public BlueContractsConformanceFailure(String fixtureId,
                                           BlueContractsFixtureCategory category,
                                           String operation,
                                           String exceptionClass,
                                           String message) {
        this.fixtureId = fixtureId;
        this.category = category;
        this.operation = operation;
        this.exceptionClass = exceptionClass;
        this.message = message;
    }

    /**
     * Returns the failed fixture identity.
     *
     * @return stable fixture identity
     */
    public String getFixtureId() {
        return fixtureId;
    }

    /**
     * Returns the fixture category.
     *
     * @return fixture category
     */
    public BlueContractsFixtureCategory getCategory() {
        return category;
    }

    /**
     * Returns the operation exercised by the fixture.
     *
     * @return operation name
     */
    public String getOperation() {
        return operation;
    }

    /**
     * Returns the thrown exception class name.
     *
     * @return exception class name
     */
    public String getExceptionClass() {
        return exceptionClass;
    }

    /**
     * Returns the diagnostic message.
     *
     * @return diagnostic message
     */
    public String getMessage() {
        return message;
    }
}
