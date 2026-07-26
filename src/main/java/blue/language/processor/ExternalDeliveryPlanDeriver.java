package blue.language.processor;

import blue.language.model.Node;

import java.util.Collection;
import java.util.Objects;

/**
 * Runtime-neutral hook for deriving the complete canonical external-delivery
 * occurrence plan for the exact Root/event pair.
 *
 * <p>Implementations are expected to read one revision-complete environmental
 * snapshot.  They must not mutate either semantic input.</p>
 */
@FunctionalInterface
public interface ExternalDeliveryPlanDeriver {

    ExternalDeliveryPlanDeriver UNAVAILABLE = (root, event) -> {
        throw new ExecutionEvidenceUnavailableException(
                "Exact external delivery subscription and activation state "
                        + "is unavailable");
    };

    ExternalDeliveryPlan derive(Node root, Node event);

    static ExternalDeliveryPlanDeriver unavailable() {
        return UNAVAILABLE;
    }

    /**
     * Returns a deriver that suspends until the listed exact evidence nodes are
     * available. This is useful for feeder snapshots whose content-addressed
     * identities are known before acquisition.
     */
    static ExternalDeliveryPlanDeriver needsResources(
            Collection<String> requiredExactBlueIds) {
        Objects.requireNonNull(
                requiredExactBlueIds, "requiredExactBlueIds");
        return (root, event) -> {
            throw new ExecutionEvidenceUnavailableException(
                    "Exact external delivery subscription and activation "
                            + "evidence is unavailable",
                    requiredExactBlueIds);
        };
    }
}
