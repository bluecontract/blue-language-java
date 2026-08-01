package blue.language.processor;

/**
 * Monotonic invocation lifecycle state.
 *
 * <p>Run termination can only advance from active to terminated. Per-scope
 * lifecycle remains attached to immutable scope occurrences in the supplied
 * registry, which prevents a removed path from resurrecting old state.</p>
 */
final class ProcessingLifecycleState {

    private final ProcessingScopeRegistry scopes;
    private boolean runTerminated;

    ProcessingLifecycleState(ProcessingScopeRegistry scopes) {
        this.scopes = scopes;
    }

    boolean isRunTerminated() {
        return runTerminated;
    }

    void terminateRun() {
        runTerminated = true;
    }

    boolean isScopeTerminated(String scopePath) {
        return scopes.isTerminated(scopePath);
    }
}
