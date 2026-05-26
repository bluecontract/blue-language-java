package blue.language;

public final class BlueContractsConformanceFailure {

    private final String fixtureId;
    private final BlueContractsFixtureCategory category;
    private final String operation;
    private final String exceptionClass;
    private final String message;

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

    public String getFixtureId() {
        return fixtureId;
    }

    public BlueContractsFixtureCategory getCategory() {
        return category;
    }

    public String getOperation() {
        return operation;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public String getMessage() {
        return message;
    }
}
