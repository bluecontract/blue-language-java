package blue.language.processor;

import java.util.Objects;

/**
 * Host-side control exit for processor work that requires retry evidence.
 *
 * <p>This exception crosses the processor continuation boundary without being
 * converted into an application/runtime failure. Concrete host integrations
 * retain their typed suspension payload and translate it at their own public
 * attempt boundary.</p>
 */
public abstract class NoncommittingExecutionException
        extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a noncommitting execution exit.
     *
     * @param message deterministic host-facing explanation
     */
    protected NoncommittingExecutionException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }
}
