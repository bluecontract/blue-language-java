package blue.language.merge;

/**
 * Owns all mutable state for one resolution invocation.
 *
 * <p>A public {@link Merger} creates a fresh session for every top-level
 * operation. Recursive resolver calls on the owning thread reuse that session,
 * while later or cross-thread calls are routed to a new one. The class is
 * deliberately package-private because invocation state is not part of the
 * Language API.</p>
 */
final class ResolutionSession {

    private volatile Thread owner;
    private volatile boolean completed;
    private ResolutionEngine.ResolutionState state;

    /** Returns whether the current thread may enter or continue this session. */
    boolean acceptsCurrentThread() {
        Thread currentOwner = owner;
        return !completed
                && (currentOwner == null || currentOwner == Thread.currentThread());
    }

    /** Returns the active resolution state, or {@code null} before admission. */
    ResolutionEngine.ResolutionState state() {
        return state;
    }

    /** Admits the current thread as the sole owner of this invocation. */
    synchronized void begin(ResolutionEngine.ResolutionState initialState) {
        if (completed || owner != null || state != null) {
            throw new IllegalStateException("Resolution session has already been admitted.");
        }
        state = initialState;
        owner = Thread.currentThread();
    }

    /** Completes this invocation and releases its mutable graph for collection. */
    synchronized void complete(ResolutionEngine.ResolutionState expectedState) {
        if (owner != Thread.currentThread() || state != expectedState) {
            throw new IllegalStateException("Resolution session ownership is unbalanced.");
        }
        state = null;
        completed = true;
        owner = null;
    }
}
