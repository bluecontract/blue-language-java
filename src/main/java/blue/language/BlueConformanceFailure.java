package blue.language;

public final class BlueConformanceFailure {

    private final String fixtureId;
    private final BlueFixtureCategory category;
    private final String operation;
    private final String exceptionClass;
    private final String message;

    public BlueConformanceFailure(String fixtureId,
                                  BlueFixtureCategory category,
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

    public BlueFixtureCategory getCategory() {
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
