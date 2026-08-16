package blue.contracts.closure;

import java.util.Objects;

/** One complete queued-work occurrence in the closed serialized shape. */
public final class WorkOccurrence implements Comparable<WorkOccurrence> {
    private final long ordinal;
    private final WorkKind kind;
    private final DocumentId targetDocumentId;
    private final String channelKey;
    private final String eventBlueId;
    private final Long occurrenceOrdinal;
    private final String targetManagedScopeIdentity;
    private final String sourceOccurrenceIdentity;
    private final String workIdentity;

    public WorkOccurrence(
            long ordinal,
            WorkKind kind,
            DocumentId targetDocumentId,
            String channelKey,
            String eventBlueId,
            Long occurrenceOrdinal,
            String targetManagedScopeIdentity,
            String sourceOccurrenceIdentity,
            String workIdentity) {
        this.ordinal = CanonicalOrders.requireSafeInteger(ordinal, "ordinal");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
        this.eventBlueId = eventBlueId;
        this.occurrenceOrdinal = occurrenceOrdinal == null
                ? null : Long.valueOf(CanonicalOrders.requireSafeInteger(
                        occurrenceOrdinal.longValue(), "occurrenceOrdinal"));
        this.targetManagedScopeIdentity = Objects.requireNonNull(
                targetManagedScopeIdentity, "targetManagedScopeIdentity");
        this.sourceOccurrenceIdentity = Objects.requireNonNull(
                sourceOccurrenceIdentity, "sourceOccurrenceIdentity");
        this.workIdentity = Objects.requireNonNull(workIdentity, "workIdentity");
    }

    public long ordinal() { return ordinal; }
    public WorkKind kind() { return kind; }
    public DocumentId targetDocumentId() { return targetDocumentId; }
    public String channelKey() { return channelKey; }
    public String eventBlueId() { return eventBlueId; }
    public Long occurrenceOrdinal() { return occurrenceOrdinal; }
    public String targetManagedScopeIdentity() { return targetManagedScopeIdentity; }
    public String sourceOccurrenceIdentity() { return sourceOccurrenceIdentity; }
    public String workIdentity() { return workIdentity; }

    @Override
    public int compareTo(WorkOccurrence other) {
        return Long.compare(ordinal, other.ordinal);
    }
}
