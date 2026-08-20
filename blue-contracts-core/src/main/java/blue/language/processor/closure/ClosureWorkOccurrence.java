package blue.language.processor.closure;

import java.util.Objects;

/** One immutable queued work occurrence targeting one independent document. */
public final class ClosureWorkOccurrence {

    private final long ordinal;
    private final WorkKind kind;
    private final DocumentId targetDocumentId;
    private final ManagedScopeKey targetManagedScopeKey;
    private final String channelKey;
    private final String eventBlueId;
    private final Long occurrenceOrdinal;
    private final String targetManagedScopeIdentity;
    private final String sourceOccurrenceIdentity;
    private final String workIdentity;

    /**
     * Creates one closed-shape work occurrence.
     *
     * @param ordinal invocation-global work ordinal
     * @param kind one of the seven closed work kinds
     * @param targetDocumentId independently processed target document
     * @param channelKey exact channel key, or empty when not applicable
     * @param eventBlueId exact event identity, or {@code null}
     * @param occurrenceOrdinal source event/update ordinal, or {@code null}
     * @param targetManagedScopeIdentity verified Root scope identity
     * @param sourceOccurrenceIdentity exact causal occurrence identity
     * @param workIdentity exact work occurrence identity
     */
    public ClosureWorkOccurrence(
            long ordinal,
            WorkKind kind,
            DocumentId targetDocumentId,
            String channelKey,
            String eventBlueId,
            Long occurrenceOrdinal,
            String targetManagedScopeIdentity,
            String sourceOccurrenceIdentity,
            String workIdentity) {
        this.ordinal = ClosureValueSupport.requireSafeInteger(
                ordinal, "ordinal");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.targetManagedScopeKey = ManagedScopeKey.root(
                this.targetDocumentId);
        this.channelKey = ClosureValueSupport.requirePortableText(
                channelKey, "channelKey");
        this.eventBlueId = eventBlueId == null
                ? null
                : ClosureValueSupport.requireBlueId(
                        eventBlueId, "eventBlueId");
        this.occurrenceOrdinal = occurrenceOrdinal == null
                ? null
                : Long.valueOf(ClosureValueSupport.requireSafeInteger(
                        occurrenceOrdinal.longValue(), "occurrenceOrdinal"));
        this.targetManagedScopeIdentity =
                ClosureValueSupport.requireSha256Identity(
                        targetManagedScopeIdentity,
                        "targetManagedScopeIdentity");
        this.sourceOccurrenceIdentity =
                ClosureValueSupport.requireSha256Identity(
                        sourceOccurrenceIdentity,
                        "sourceOccurrenceIdentity");
        this.workIdentity = ClosureValueSupport.requireSha256Identity(
                workIdentity, "workIdentity");
    }

    /**
     * Returns the invocation-global work ordinal.
     *
     * @return non-negative safe integer
     */
    public long ordinal() {
        return ordinal;
    }

    /**
     * Returns the closed work kind.
     *
     * @return work kind
     */
    public WorkKind kind() {
        return kind;
    }

    /**
     * Returns the independently processed target document.
     *
     * @return target lineage identity
     */
    public DocumentId targetDocumentId() {
        return targetDocumentId;
    }

    /**
     * Always returns the target document's managed Root execution key.
     *
     * @return Root managed-scope key
     */
    public ManagedScopeKey targetManagedScopeKey() {
        return targetManagedScopeKey;
    }

    /**
     * Returns the exact channel key, possibly empty.
     *
     * @return channel key
     */
    public String channelKey() {
        return channelKey;
    }

    /**
     * Returns the event identity when the work carries an event.
     *
     * @return event BlueId or {@code null}
     */
    public String eventBlueId() {
        return eventBlueId;
    }

    /**
     * Returns the source event/update ordinal when present.
     *
     * @return non-negative ordinal or {@code null}
     */
    public Long occurrenceOrdinal() {
        return occurrenceOrdinal;
    }

    /**
     * Returns the verified target Root scope identity.
     *
     * @return SHA-256 identity
     */
    public String targetManagedScopeIdentity() {
        return targetManagedScopeIdentity;
    }

    /**
     * Returns the exact causal occurrence identity.
     *
     * @return SHA-256 identity
     */
    public String sourceOccurrenceIdentity() {
        return sourceOccurrenceIdentity;
    }

    /**
     * Returns this work occurrence's exact identity.
     *
     * @return SHA-256 identity
     */
    public String workIdentity() {
        return workIdentity;
    }
}
