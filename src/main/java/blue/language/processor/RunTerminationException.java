package blue.language.processor;

/**
 * Private control-flow signal that stops the current run after termination
 * semantics have already been recorded by the runtime.
 *
 * <p>It is intentionally distinct from processor failure and must be caught
 * only at orchestration boundaries that can finalize the current result.</p>
 */
final class RunTerminationException extends RuntimeException {
    RunTerminationException() {
    }

    RunTerminationException(String message) {
        super(message);
    }
}
