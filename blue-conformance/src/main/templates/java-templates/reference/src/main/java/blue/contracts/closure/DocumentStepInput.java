package blue.contracts.closure;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One isolated managed-document execution input.
 *
 * <p>The target document is the complete application-visible execution Root
 * for this step. Reverse containment is deliberately unrepresentable.</p>
 */
public final class DocumentStepInput {
    private final long stepOrdinal;
    private final long workOrdinal;
    private final DocumentId targetDocumentId;
    private final String exactDocumentBlueId;
    private final String workOccurrenceIdentity;
    private final String exactPayloadBlueId;
    private final TentativeResolutionContext resolutionContext;
    private final String scopePath;
    private final List<DocumentId> ambientContainingDocumentIds;

    public DocumentStepInput(
            long stepOrdinal,
            long workOrdinal,
            DocumentId targetDocumentId,
            String exactDocumentBlueId,
            String workOccurrenceIdentity,
            String exactPayloadBlueId,
            TentativeResolutionContext resolutionContext,
            List<DocumentId> ambientContainingDocumentIds) {
        this.stepOrdinal = CanonicalOrders.requireSafeInteger(
                stepOrdinal, "stepOrdinal");
        this.workOrdinal = CanonicalOrders.requireSafeInteger(
                workOrdinal, "workOrdinal");
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.exactDocumentBlueId = Objects.requireNonNull(
                exactDocumentBlueId, "exactDocumentBlueId");
        this.workOccurrenceIdentity = Objects.requireNonNull(
                workOccurrenceIdentity, "workOccurrenceIdentity");
        this.exactPayloadBlueId = Objects.requireNonNull(
                exactPayloadBlueId, "exactPayloadBlueId");
        this.resolutionContext = Objects.requireNonNull(
                resolutionContext, "resolutionContext");
        if (!this.targetDocumentId.equals(
                this.resolutionContext.targetDocumentId())
                || !this.exactDocumentBlueId.equals(
                        this.resolutionContext.targetBeforeBlueId())
                || !this.resolutionContext.targetManagedScope()
                        .isClosureRoot()) {
            throw new IllegalArgumentException(
                    "resolutionContext does not describe this step");
        }
        this.scopePath = "/";
        if (!Objects.requireNonNull(
                ambientContainingDocumentIds,
                "ambientContainingDocumentIds").isEmpty()) {
            throw new IllegalArgumentException(
                    "reverse containment is not part of document execution");
        }
        this.ambientContainingDocumentIds = Collections.emptyList();
    }

    public long stepOrdinal() { return stepOrdinal; }
    public long workOrdinal() { return workOrdinal; }
    public DocumentId targetDocumentId() { return targetDocumentId; }
    public String exactDocumentBlueId() { return exactDocumentBlueId; }
    public String workOccurrenceIdentity() { return workOccurrenceIdentity; }
    public String exactPayloadBlueId() { return exactPayloadBlueId; }
    public TentativeResolutionContext resolutionContext() {
        return resolutionContext;
    }
    public String scopePath() { return scopePath; }
    public List<DocumentId> ambientContainingDocumentIds() {
        return ambientContainingDocumentIds;
    }
}
