package blue.language.processor;

/**
 * Deterministic rejection of stale, mismatched, or caller-forged execution
 * evidence.
 */
public final class InvalidExecutionEvidenceException extends RuntimeException {

    public InvalidExecutionEvidenceException(String message) {
        super(message);
    }
}
