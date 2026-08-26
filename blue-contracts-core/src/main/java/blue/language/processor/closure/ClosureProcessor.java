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
     * Validates and admits one closed closure through the legacy bounded
     * compatibility lane.
     *
     * <p>This method does not run initialization-caused Document Updates,
     * application events, lifecycle termination, or further queued work. It
     * is therefore not a conforming implementation of normative
     * {@code ADMIT_CLOSURE} when admission can enqueue lifecycle work. Use
     * {@link #admitClosureWithLifecycleQueue(ClosureInvocationInput)} for
     * normative conformance and production admission.</p>
     *
     * @param input complete closed admission input
     * @return completion or exact-resource suspension
     */
    ClosureAttemptResult admitClosure(ClosureInvocationInput input);

    /**
     * Admits one closed closure through the normative complete lifecycle work
     * and event queues.
     *
     * <p>This explicit lane preserves {@link #admitClosure(ClosureInvocationInput)}
     * as the bounded compatibility operation while allowing callers to select
     * initialization-caused updates, events, and graceful termination. This is
     * the conforming public Java entry point for {@code ADMIT_CLOSURE}.</p>
     *
     * @param input complete closed admission input
     * @return completion or exact-resource suspension
     */
    ClosureAttemptResult admitClosureWithLifecycleQueue(
            ClosureInvocationInput input);
}
