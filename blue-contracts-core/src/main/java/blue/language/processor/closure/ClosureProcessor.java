package blue.language.processor.closure;

/**
 * Authoritative affected-closure processing seam.
 *
 * <p>No default adapter is supplied: ordinary document processing cannot
 * truthfully synthesize closure identity, proof, graph, or atomic-commit
 * behavior.</p>
 */
public interface ClosureProcessor {

    /**
     * Processes one external or managed-revision closure invocation.
     *
     * @param input complete closed processing input
     * @return completion or exact-resource suspension
     */
    ClosureAttemptResult processClosure(ClosureInvocationInput input);

    /**
     * Validates and admits one closed closure invocation.
     *
     * @param input complete closed admission input
     * @return completion or exact-resource suspension
     */
    ClosureAttemptResult admitClosure(ClosureInvocationInput input);

    /**
     * Admits one closed closure through the complete lifecycle work and event
     * queues.
     *
     * <p>This explicit lane preserves {@link #admitClosure(ClosureInvocationInput)}
     * as the bounded compatibility operation while allowing callers to select
     * initialization-caused updates, events, and graceful termination.</p>
     *
     * @param input complete closed admission input
     * @return completion or exact-resource suspension
     */
    ClosureAttemptResult admitClosureWithLifecycleQueue(
            ClosureInvocationInput input);
}
