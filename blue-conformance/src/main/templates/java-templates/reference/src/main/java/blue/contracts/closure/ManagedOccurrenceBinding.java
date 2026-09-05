package blue.contracts.closure;

import java.util.List;
import java.util.Objects;

/**
 * Exact platform evidence binding authored embedded content to a managed lineage.
 *
 * <p>This immutable value validates local row shape only. The processor must
 * validate it against authoritative predecessor state: first reservation is
 * generation 1; inactive activation and same-lineage rebind preserve generation
 * and occurrence identity; active removal creates the inactive successor at
 * generation plus one with fresh occurrence and binding identities. That
 * successor is output-only until committed and supplied as a later invocation
 * input; later re-add preserves its generation and occurrence identity
 * (ordinary exact-state churn may still change binding identity).
 * Same-invocation remove-then-re-add and different-lineage retarget of an
 * active or reserved path are unsupported in Contracts 1.0. A DTO constructor
 * cannot prove those history-dependent transition laws.</p>
 */
public final class ManagedOccurrenceBinding {
    /** Recomputes the exact occurrenceBindingSetIdentity defined by the registry. */
    public interface BindingSetIdentityFactory {
        String identity(List<ManagedOccurrenceBinding> occurrenceBindings);
    }

    private final String occurrenceIdentity;
    private final String bindingIdentity;
    private final String bindingPolicyIdentity;
    private final DocumentId sourceDocumentId;
    private final String sourcePath;
    private final long activationGeneration;
    private final DocumentId targetDocumentId;
    private final String expectedTargetBlueId;
    private final boolean active;
    private final Long pendingHistoricalEpoch;

    public ManagedOccurrenceBinding(
            String occurrenceIdentity,
            String bindingIdentity,
            String bindingPolicyIdentity,
            DocumentId sourceDocumentId,
            String sourcePath,
            long activationGeneration,
            DocumentId targetDocumentId,
            String expectedTargetBlueId,
            boolean active,
            Long pendingHistoricalEpoch) {
        this.occurrenceIdentity = Objects.requireNonNull(
                occurrenceIdentity, "occurrenceIdentity");
        this.bindingIdentity = Objects.requireNonNull(
                bindingIdentity, "bindingIdentity");
        this.bindingPolicyIdentity = Objects.requireNonNull(
                bindingPolicyIdentity, "bindingPolicyIdentity");
        this.sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        this.sourcePath = CanonicalOrders.requireRuntimePointer(
                sourcePath, "sourcePath");
        this.activationGeneration = CanonicalOrders.requireSafeInteger(
                activationGeneration, "activationGeneration");
        if (this.activationGeneration == 0L) {
            throw new IllegalArgumentException(
                    "embedded occurrence activationGeneration starts at 1");
        }
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.expectedTargetBlueId = Objects.requireNonNull(
                expectedTargetBlueId, "expectedTargetBlueId");
        this.active = active;
        this.pendingHistoricalEpoch = pendingHistoricalEpoch == null
                ? null : Long.valueOf(CanonicalOrders.requireSafeInteger(
                        pendingHistoricalEpoch.longValue(), "pendingHistoricalEpoch"));
        if (this.active && this.pendingHistoricalEpoch != null) {
            throw new IllegalArgumentException(
                    "active occurrence cannot retain pendingHistoricalEpoch");
        }
    }

    public String occurrenceIdentity() { return occurrenceIdentity; }
    public String bindingIdentity() { return bindingIdentity; }
    public String bindingPolicyIdentity() { return bindingPolicyIdentity; }
    public DocumentId sourceDocumentId() { return sourceDocumentId; }
    public String sourcePath() { return sourcePath; }
    public long activationGeneration() { return activationGeneration; }
    public DocumentId targetDocumentId() { return targetDocumentId; }
    public String expectedTargetBlueId() { return expectedTargetBlueId; }
    public boolean active() { return active; }
    public Long pendingHistoricalEpoch() { return pendingHistoricalEpoch; }
}
