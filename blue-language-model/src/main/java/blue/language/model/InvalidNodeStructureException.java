package blue.language.model;

/** Deterministic structural violation found by a local Blue node validator. */
public final class InvalidNodeStructureException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public InvalidNodeStructureException(String message) { super(message); }
    public InvalidNodeStructureException(String message, Throwable cause) { super(message, cause); }
}
