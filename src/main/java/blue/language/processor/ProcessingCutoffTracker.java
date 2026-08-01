package blue.language.processor;

import java.util.Objects;

/** Monotonic active-occurrence cut-off boundary. */
final class ProcessingCutoffTracker {

    private final ProcessorInvocationState execution;

    ProcessingCutoffTracker(ProcessorInvocationState execution) {
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    boolean shouldStop(String scopePath) {
        return execution.shouldStopScopeWork(scopePath);
    }

    void markCutOff(String scopePath) {
        execution.markCutOff(scopePath);
    }
}
