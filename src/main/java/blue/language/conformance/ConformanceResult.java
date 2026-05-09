package blue.language.conformance;

public final class ConformanceResult {

    private static final ConformanceResult CONFORMANT = new ConformanceResult(true, null);

    private final boolean conformant;
    private final String message;

    private ConformanceResult(boolean conformant, String message) {
        this.conformant = conformant;
        this.message = message;
    }

    public static ConformanceResult conformant() {
        return CONFORMANT;
    }

    public static ConformanceResult nonConformant(String message) {
        return new ConformanceResult(false, message);
    }

    public boolean isConformant() {
        return conformant;
    }

    public String getMessage() {
        return message;
    }
}
