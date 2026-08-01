package blue.language.provider;

/**
 * Provider of complete cyclic-set evidence for independently verified member
 * lookups.
 */
public interface CyclicAwareNodeProvider {

    /**
     * Compatibility probe for callers that only need to know whether exact
     * content is already present. It never grants trusted-provider status.
     *
     * @param blueId plain or cyclic-member identity to probe
     * @return whether exact content is locally available
     */
    default boolean hasVerifiedContentForBlueId(String blueId) {
        return false;
    }

    /**
     * Acquires complete placeholder-set evidence for the requested member.
     *
     * <p>A definitive miss, temporary acquisition failure, and invalid
     * evidence remain distinct so callers never mistake unavailability for
     * proof that the cyclic set does not exist.</p>
     *
     * @param blueId cyclic-member identity
     * @return exhaustive proof-acquisition result
     */
    default CyclicSetProofResult cyclicSetProofFor(String blueId) {
        return CyclicSetProofResult.notFound();
    }
}
