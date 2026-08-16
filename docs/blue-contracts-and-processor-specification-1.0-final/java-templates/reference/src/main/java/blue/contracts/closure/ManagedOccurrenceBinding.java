package blue.contracts.closure;

import java.util.List;
import java.util.Objects;

/** Exact platform evidence binding authored embedded content to a managed lineage. */
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
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.activationGeneration = CanonicalOrders.requireSafeInteger(
                activationGeneration, "activationGeneration");
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.expectedTargetBlueId = Objects.requireNonNull(
                expectedTargetBlueId, "expectedTargetBlueId");
        this.active = active;
        this.pendingHistoricalEpoch = pendingHistoricalEpoch == null
                ? null : Long.valueOf(CanonicalOrders.requireSafeInteger(
                        pendingHistoricalEpoch.longValue(), "pendingHistoricalEpoch"));
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
