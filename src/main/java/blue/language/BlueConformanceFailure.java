package blue.language;

public final class BlueConformanceFailure {

    private final String fixtureId;
    private final BlueFixtureCategory category;
    private final String operation;
    private final String exceptionClass;
    private final String message;
    private final BlueLanguageErrorCategory errorCategory;

    public BlueConformanceFailure(String fixtureId,
                                  BlueFixtureCategory category,
                                  String operation,
                                  String exceptionClass,
                                  String message) {
        this(fixtureId, category, operation, exceptionClass, message, null);
    }

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
