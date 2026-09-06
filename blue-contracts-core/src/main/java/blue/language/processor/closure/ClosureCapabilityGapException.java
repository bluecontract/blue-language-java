package blue.language.processor.closure;

import blue.language.processor.NoncommittingExecutionException;

/**
 * Owning capability control for a not-yet-authoritative runtime seam.
 * Handler dispatch must preserve this signal without classifying it as an
 * application failure; only the closure attempt boundary assigns its explicit
 * capability-failure disposition and rolls back all tentative effects.
 */
final class ClosureCapabilityGapException
        extends NoncommittingExecutionException {

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
