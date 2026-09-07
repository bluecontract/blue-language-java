package blue.contracts.closure;

import java.util.Objects;

/**
 * Local effects of one isolated document step, before orchestrator identity
 * finalization.
 *
 * <p>This value deliberately has no {@code afterBlueId}. An acyclic identity
 * is calculated after the local body is applied, while a cyclic member identity
 * exists only after complete-set finalization. The commit-facing boundary is
 * {@link ResultingDocument}.</p>
 */
public final class LocalDocumentStepResult {
    private final DocumentId documentId;
    private final String workOccurrenceIdentity;
    private final String beforeBlueId;
    private final Object resultingBody;
    private final long gasBefore;
    private final long gasAfter;
    private final boolean componentIdentityAffected;

    public LocalDocumentStepResult(
            DocumentId documentId,
            String workOccurrenceIdentity,
            String beforeBlueId,
            Object resultingBody,
            long gasBefore,
            long gasAfter,
            boolean componentIdentityAffected) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.workOccurrenceIdentity = Objects.requireNonNull(
                workOccurrenceIdentity, "workOccurrenceIdentity");
        this.beforeBlueId = Objects.requireNonNull(beforeBlueId, "beforeBlueId");
        this.resultingBody = Objects.requireNonNull(resultingBody, "resultingBody");
        this.gasBefore = CanonicalOrders.requireSafeInteger(gasBefore, "gasBefore");
        this.gasAfter = CanonicalOrders.requireSafeInteger(gasAfter, "gasAfter");
        if (this.gasAfter < this.gasBefore) {
            throw new IllegalArgumentException("gasAfter < gasBefore");
        }
        this.componentIdentityAffected = componentIdentityAffected;
    }

    public DocumentId documentId() { return documentId; }
    public String workOccurrenceIdentity() { return workOccurrenceIdentity; }
    public String beforeBlueId() { return beforeBlueId; }
    public Object resultingBody() { return resultingBody; }
    public long gasBefore() { return gasBefore; }
    public long gasAfter() { return gasAfter; }
    public boolean componentIdentityAffected() {
        return componentIdentityAffected;
    }
}
