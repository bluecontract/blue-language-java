package blue.contracts.closure;

import java.util.Objects;

/** One explicit public-Root event and its invocation-global occurrence identity. */
public final class PublicEventOccurrence {
    private final long publicEventOrdinal;
    private final DocumentId publicRootDocumentId;
    private final String eventOccurrenceIdentity;
    private final String eventBlueId;
    private final Object event;

    public PublicEventOccurrence(
            long publicEventOrdinal,
            DocumentId publicRootDocumentId,
            String eventOccurrenceIdentity,
            String eventBlueId,
            Object event) {
        this.publicEventOrdinal = CanonicalOrders.requireSafeInteger(
                publicEventOrdinal, "publicEventOrdinal");
        this.publicRootDocumentId = Objects.requireNonNull(
                publicRootDocumentId, "publicRootDocumentId");
        this.eventOccurrenceIdentity = Objects.requireNonNull(
                eventOccurrenceIdentity, "eventOccurrenceIdentity");
        this.eventBlueId = Objects.requireNonNull(eventBlueId, "eventBlueId");
        this.event = Objects.requireNonNull(event, "event");
    }

    public long publicEventOrdinal() { return publicEventOrdinal; }
    public DocumentId publicRootDocumentId() { return publicRootDocumentId; }
    public String eventOccurrenceIdentity() { return eventOccurrenceIdentity; }
    public String eventBlueId() { return eventBlueId; }
    public Object event() { return event; }
}
