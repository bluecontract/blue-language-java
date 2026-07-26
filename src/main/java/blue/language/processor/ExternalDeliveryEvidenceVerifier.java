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

    void verify(Node root,
                Node event,
                VerifiedExecutionEvidence evidence);

    /**
     * Verifies evidence produced from a plan already captured under the
     * caller's configuration lock.  Custom verifiers retain their historical
     * behavior; the core verifier overrides this to avoid re-reading
     * environmental state.
     */
    default void verifyDerived(Node root,
                               Node event,
                               VerifiedExecutionEvidence evidence,
                               ExternalDeliveryPlan derivedPlan) {
        verify(root, event, evidence);
    }
}
