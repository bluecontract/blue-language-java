package blue.language.conformance;

/**
 * Value result for a conformance check.
 *
 * <p>A conformant result has no message. A nonconformant result retains the
 * caller-supplied diagnostic, which may be {@code null}.</p>
 */
public final class ConformanceResult {

    private static final ConformanceResult CONFORMANT = new ConformanceResult(true, null);

    private final boolean conformant;
    private final String message;

    private ConformanceResult(boolean conformant, String message) {
        this.conformant = conformant;
        this.message = message;
    }

    /**
     * Returns the shared immutable conformant result.
     *
     * @return conformant result with no message
     */
    public static ConformanceResult conformant() {
        return CONFORMANT;
    }

    /**
     * Creates a nonconformant result.
     *
     * @param message diagnostic message, or {@code null}
     * @return new nonconformant result
     */
    public static ConformanceResult nonConformant(String message) {
        return new ConformanceResult(false, message);
    }

    /**
     * Tests whether the checked value conforms.
     *
     * @return whether the result is conformant
     */
    public boolean isConformant() {
        return conformant;
    }

    /**
     * Returns the diagnostic associated with this result.
     *
     * @return diagnostic message, or {@code null}
     */
    public String getMessage() {
        return message;
    }
}
