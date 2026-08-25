package blue.language.processor;

/**
 * Invocation-local sink for exact contract surfaces discovered by the normal
 * effective-contract traversal.
 *
 * <p>The collector observes the already-resolved {@link ContractBundle}. It
 * does not parse contracts or manufacture an identity for merged effective
 * content.</p>
 */
interface ContractSurfaceCollector {

    /** Records the exact effective bundle selected for one scope. */
    void record(String scopePath, ContractBundle bundle);
}
