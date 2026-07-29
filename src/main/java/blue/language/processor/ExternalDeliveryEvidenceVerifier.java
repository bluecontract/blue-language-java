package blue.language.processor;

import blue.language.model.Node;

/**
 * Deterministic verifier for revision-bound feeder delivery evidence.
 *
 * <p>A runtime registry may supply a richer implementation for its exact
 * subscription and checkpoint laws.  Implementations must derive from the
 * exact Root/event and reject both forged entries and omitted true entries.</p>
 */
@FunctionalInterface
public interface ExternalDeliveryEvidenceVerifier {

    /**
     * Verifies that supplied evidence is complete and exact for an occurrence.
     *
     * @param root exact Processing Root
     * @param event exact incoming event
     * @param evidence caller-supplied immutable evidence
     * @throws InvalidExecutionEvidenceException when evidence is forged,
     *         stale, incomplete, or otherwise inconsistent
     * @throws ExecutionEvidenceUnavailableException when verification inputs
     *         cannot yet be acquired
     */
    void verify(Node root,
                Node event,
                VerifiedExecutionEvidence evidence);

    /**
     * Verifies evidence produced from a plan already captured under the
     * caller's configuration lock.  Custom verifiers retain their historical
     * behavior; the core verifier overrides this to avoid re-reading
     * environmental state.
     *
     * @param root exact Processing Root
     * @param event exact incoming event
     * @param evidence caller-supplied immutable evidence
     * @param derivedPlan immutable occurrence plan derived under the same lock
     * @throws InvalidExecutionEvidenceException when evidence does not match
     *         the derived plan
     */
    default void verifyDerived(Node root,
                               Node event,
                               VerifiedExecutionEvidence evidence,
                               ExternalDeliveryPlan derivedPlan) {
        verify(root, event, evidence);
    }
}
