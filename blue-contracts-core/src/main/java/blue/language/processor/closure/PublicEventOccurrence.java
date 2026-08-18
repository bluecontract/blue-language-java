package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One explicit public-Root event occurrence in canonical projection order. */
public final class PublicEventOccurrence {

    private final long publicEventOrdinal;
    private final long eventOccurrenceOrdinal;
    private final DocumentId publicRootDocumentId;
    private final String eventOccurrenceIdentity;
    private final String eventBlueId;
    private final Node event;

    /**
     * Creates one complete public event and verifies its exact event BlueId.
     *
     * @param publicEventOrdinal contiguous public projection ordinal
     * @param eventOccurrenceOrdinal invocation-global emission ordinal
     * @param publicRootDocumentId exact public Root lineage
     * @param eventOccurrenceIdentity asserted occurrence identity
     * @param eventBlueId exact event identity
     * @param event complete exact event value
     */
    public PublicEventOccurrence(
            long publicEventOrdinal,
            long eventOccurrenceOrdinal,
            DocumentId publicRootDocumentId,
            String eventOccurrenceIdentity,
            String eventBlueId,
            Node event) {
        this.publicEventOrdinal = ClosureValueSupport.requireSafeInteger(
                publicEventOrdinal, "publicEventOrdinal");
        this.eventOccurrenceOrdinal = ClosureValueSupport.requireSafeInteger(
                eventOccurrenceOrdinal, "eventOccurrenceOrdinal");
        this.publicRootDocumentId = Objects.requireNonNull(
                publicRootDocumentId, "publicRootDocumentId");
        this.eventOccurrenceIdentity =
                ClosureValueSupport.requireSha256Identity(
                        eventOccurrenceIdentity,
                        "eventOccurrenceIdentity");
        this.eventBlueId = ClosureValueSupport.requireBlueId(
                eventBlueId, "eventBlueId");
        this.event = Objects.requireNonNull(event, "event").clone();
        String computedBlueId = DirectBlueIdCalculator.calculateBlueId(
                this.event);
        if (!this.eventBlueId.equals(computedBlueId)) {
            throw new IllegalArgumentException(
                    "eventBlueId does not identify the complete event");
        }
    }

    /**
     * Returns contiguous public projection ordinal.
     *
     * @return contiguous public projection ordinal
     */
    public long publicEventOrdinal() {
        return publicEventOrdinal;
    }

    /**
     * Returns invocation-global emission ordinal.
     *
     * @return invocation-global emission ordinal
     */
    public long eventOccurrenceOrdinal() {
        return eventOccurrenceOrdinal;
    }

    /**
     * Returns exact public Root lineage.
     *
     * @return exact public Root lineage
     */
    public DocumentId publicRootDocumentId() {
        return publicRootDocumentId;
    }

    /**
     * Returns exact event occurrence identity.
     *
     * @return exact event occurrence identity
     */
    public String eventOccurrenceIdentity() {
        return eventOccurrenceIdentity;
    }

    /**
     * Returns exact event BlueId.
     *
     * @return exact event BlueId
     */
    public String eventBlueId() {
        return eventBlueId;
    }

    /**
     * Returns deep defensive copy of the complete event.
     *
     * @return deep defensive copy of the complete event
     */
    public Node event() {
        return event.clone();
    }

    Map<String, Object> identityValue() {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("publicEventOrdinal", Long.valueOf(publicEventOrdinal));
        value.put("eventOccurrenceOrdinal",
                Long.valueOf(eventOccurrenceOrdinal));
        value.put("publicRootDocumentId", publicRootDocumentId.value());
        value.put("eventOccurrenceIdentity", eventOccurrenceIdentity);
        value.put("eventBlueId", eventBlueId);
        return value;
    }
}
