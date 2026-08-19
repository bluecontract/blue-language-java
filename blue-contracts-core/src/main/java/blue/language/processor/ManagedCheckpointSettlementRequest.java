package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable input for one managed Root participating in a closure-wide
 * checkpoint settlement batch.
 */
public final class ManagedCheckpointSettlementRequest {

    private final String targetManagedScopeIdentity;
    private final Node exactRoot;
    private final List<ManagedCheckpointSettlementEntry> completedEntries;
    private final GasChargeContext revalidationAttribution;

    /**
     * Creates one target request.
     *
     * @param targetManagedScopeIdentity exact target managed-scope identity
     * @param exactRoot exact final Root before checkpoint settlement
     * @param completedEntries accepted-new sources whose deliveries completed
     * @param revalidationAttribution exact target/barrier attribution used
     *        while revalidating this Root
     */
    public ManagedCheckpointSettlementRequest(
            String targetManagedScopeIdentity,
            Node exactRoot,
            List<ManagedCheckpointSettlementEntry> completedEntries,
            GasChargeContext revalidationAttribution) {
        if (targetManagedScopeIdentity == null
                || targetManagedScopeIdentity.isEmpty()) {
            throw new IllegalArgumentException(
                    "targetManagedScopeIdentity must be non-empty");
        }
        this.targetManagedScopeIdentity = targetManagedScopeIdentity;
        this.exactRoot = Objects.requireNonNull(
                exactRoot, "exactRoot").clone();
        ArrayList<ManagedCheckpointSettlementEntry> entries =
                new ArrayList<ManagedCheckpointSettlementEntry>();
        for (ManagedCheckpointSettlementEntry entry
                : Objects.requireNonNull(
                        completedEntries, "completedEntries")) {
            entries.add(Objects.requireNonNull(entry, "completed entry"));
        }
        this.completedEntries = Collections.unmodifiableList(entries);
        this.revalidationAttribution = Objects.requireNonNull(
                revalidationAttribution, "revalidationAttribution");
    }

    /**
     * Returns the exact target managed-scope identity.
     *
     * @return non-empty target identity
     */
    public String targetManagedScopeIdentity() {
        return targetManagedScopeIdentity;
    }

    /**
     * Returns the exact final Root defensively.
     *
     * @return detached exact Root
     */
    public Node exactRoot() {
        return exactRoot.clone();
    }

    /**
     * Returns accepted-new sources whose deliveries completed.
     *
     * @return immutable completed-entry sequence
     */
    public List<ManagedCheckpointSettlementEntry> completedEntries() {
        return completedEntries;
    }

    /**
     * Returns attribution for deterministic Root revalidation.
     *
     * @return exact revalidation attribution
     */
    public GasChargeContext revalidationAttribution() {
        return revalidationAttribution;
    }
}
