package blue.language;

import blue.language.api.BlueLanguageErrorCategory;

/**
 * Immutable diagnostic for one failed Blue Language conformance fixture.
 *
 * <p>The optional {@link #getErrorCategory()} is the stable semantic category;
 * the exception class and message retain implementation-level evidence.</p>
 */
public final class BlueConformanceFailure {

    private final String fixtureId;
    private final BlueFixtureCategory category;
    private final String operation;
    private final String exceptionClass;
    private final String message;
    private final BlueLanguageErrorCategory errorCategory;

    /**
     * Creates a failure without a classified semantic error category.
     *
     * @param fixtureId stable fixture identity
     * @param category fixture category
     * @param operation operation exercised by the fixture
     * @param exceptionClass thrown exception class name
     * @param message diagnostic message
     */
    public BlueConformanceFailure(String fixtureId,
                                  BlueFixtureCategory category,
                                  String operation,
                                  String exceptionClass,
                                  String message) {
        this(fixtureId, category, operation, exceptionClass, message, null);
    }

    /**
     * Creates a complete fixture failure record.
     *
     * @param fixtureId stable fixture identity
     * @param category fixture category
     * @param operation operation exercised by the fixture
     * @param exceptionClass thrown exception class name
     * @param message diagnostic message
     * @param errorCategory stable semantic error category, if classified
     */
    public BlueConformanceFailure(String fixtureId,
                                  BlueFixtureCategory category,
                                  String operation,
                                  String exceptionClass,
                                  String message,
                                  BlueLanguageErrorCategory errorCategory) {
        this.fixtureId = fixtureId;
        this.category = category;
        this.operation = operation;
        this.exceptionClass = exceptionClass;
        this.message = message;
        this.errorCategory = errorCategory;
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
    public BlueFixtureCategory getCategory() {
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

    /**
     * Returns the stable semantic error category.
     *
     * @return error category, or {@code null} when unclassified
     */
    public BlueLanguageErrorCategory getErrorCategory() {
        return errorCategory;
    }

    @Override
    public String toString() {
        return "BlueConformanceFailure{" +
                "fixtureId='" + fixtureId + '\'' +
                ", operation='" + operation + '\'' +
                ", exceptionClass='" + exceptionClass + '\'' +
                ", errorCategory=" + errorCategory +
                ", message='" + message + '\'' +
                '}';
    }
}
