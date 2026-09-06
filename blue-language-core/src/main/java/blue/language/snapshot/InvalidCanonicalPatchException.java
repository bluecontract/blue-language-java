package blue.language.snapshot;

/** Deterministic rejection of an authored patch against the exact canonical pre-state. */
public final class InvalidCanonicalPatchException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    public InvalidCanonicalPatchException(String message) { super(message); }
}
