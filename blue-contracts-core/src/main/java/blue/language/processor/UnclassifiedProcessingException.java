package blue.language.processor;

import java.util.Objects;

/**
 * Operational/implementation failure, never a completed deterministic processor result.
 *
 * <p>The original cause is for host diagnostics only. It must not be serialized as semantic
 * evidence, consumed as an input outcome or translated to RUNTIME_FATAL. Like other noncommitting
 * exits this discards the tentative attempt; it does not promise that retry will repair a bug.</p>
 */
public final class UnclassifiedProcessingException extends NoncommittingExecutionException {
    private static final long serialVersionUID = 1L;

    public UnclassifiedProcessingException(Throwable cause) {
        super("Unclassified processing failure; no semantic result is available");
        initCause(Objects.requireNonNull(cause, "cause"));
    }
}
