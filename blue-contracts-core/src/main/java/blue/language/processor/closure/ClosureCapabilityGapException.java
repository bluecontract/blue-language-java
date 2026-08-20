package blue.language.processor.closure;

/** Deterministic fail-closed exit for a not-yet-authoritative runtime seam. */
final class ClosureCapabilityGapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    ClosureCapabilityGapException(String code, String message) {
        super(message);
        this.code = ClosureValueSupport.requireNonEmptyText(code, "code");
    }

    String code() {
        return code;
    }
}
