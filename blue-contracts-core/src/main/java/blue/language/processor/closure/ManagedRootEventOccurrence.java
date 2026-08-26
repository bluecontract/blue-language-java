package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One exact Root-boundary event occurrence emitted by a managed document.
 *
 * <p>The receipt-local ordinal preserves duplicates and order for one source
 * transition.  The occurrence ordinal and identity retain the event's exact
 * position in the owning source invocation.  Public visibility is evidence
 * about the source Root; it does not authorize later republication.</p>
 */
public final class ManagedRootEventOccurrence {

    private final long ordinal;
    private final long occurrenceOrdinal;
    private final DocumentId sourceDocumentId;
    private final String occurrenceIdentity;
    private final String eventBlueId;
    private final Node exactEvent;
    private final boolean publicAtSource;

    /**
     * Creates one self-validating managed Root event occurrence.
     *
     * @param ordinal contiguous receipt-local ordinal
     * @param occurrenceOrdinal source-invocation event ordinal
     * @param sourceDocumentId emitting managed Root
     * @param occurrenceIdentity exact source occurrence identity
     * @param eventBlueId exact event identity
     * @param exactEvent complete exact event
     * @param publicAtSource whether the emitting source was a public Root
     */
    public ManagedRootEventOccurrence(
            long ordinal,
            long occurrenceOrdinal,
            DocumentId sourceDocumentId,
            String occurrenceIdentity,
            String eventBlueId,
            Node exactEvent,
            boolean publicAtSource) {
        this.ordinal = ClosureValueSupport.requireSafeInteger(
                ordinal, "ordinal");
        this.occurrenceOrdinal = ClosureValueSupport.requireSafeInteger(
                occurrenceOrdinal, "occurrenceOrdinal");
        this.sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        this.occurrenceIdentity = ClosureValueSupport.requireSha256Identity(
                occurrenceIdentity, "occurrenceIdentity");
        this.eventBlueId = ClosureValueSupport.requireBlueId(
                eventBlueId, "eventBlueId");
        this.exactEvent = Objects.requireNonNull(
                exactEvent, "exactEvent").clone();
        this.publicAtSource = publicAtSource;
        String computed = DirectBlueIdCalculator.calculateBlueId(
                this.exactEvent);
        if (!this.eventBlueId.equals(computed)) {
            throw new IllegalArgumentException(
                    "eventBlueId does not identify the complete managed Root event");
        }
    }

    /**
     * Returns the contiguous receipt-local ordinal.
     *
     * @return contiguous receipt-local ordinal
     */
    public long ordinal() {
        return ordinal;
    }

    /**
     * Returns the source-invocation event occurrence ordinal.
     *
     * @return source-invocation event occurrence ordinal
     */
    public long occurrenceOrdinal() {
        return occurrenceOrdinal;
    }

    /**
     * Returns the emitting managed document.
     *
     * @return emitting managed document
     */
    public DocumentId sourceDocumentId() {
        return sourceDocumentId;
    }

    /**
     * Returns the exact source event occurrence identity.
     *
     * @return exact source event occurrence identity
     */
    public String occurrenceIdentity() {
        return occurrenceIdentity;
    }

    /**
     * Returns the exact source event occurrence identity.
     *
     * @return exact source event occurrence identity
     */
    public String eventOccurrenceIdentity() {
        return occurrenceIdentity;
    }

    /**
     * Returns the exact event BlueId.
     *
     * @return exact event BlueId
     */
    public String eventBlueId() {
        return eventBlueId;
    }

    /**
     * Returns a defensive copy of the complete exact event.
     *
     * @return defensive copy of the complete exact event
     */
    public Node exactEvent() {
        return exactEvent.clone();
    }

    /**
     * Returns whether the source document was public when it emitted.
     *
     * @return whether the source document was public when it emitted
     */
    public boolean publicAtSource() {
        return publicAtSource;
    }

    Map<String, Object> identityValue() {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("ordinal", Long.valueOf(ordinal));
        value.put("occurrenceOrdinal", Long.valueOf(occurrenceOrdinal));
        value.put("sourceDocumentId", sourceDocumentId.value());
        value.put("occurrenceIdentity", occurrenceIdentity);
        value.put("eventBlueId", eventBlueId);
        value.put("publicAtSource", Boolean.valueOf(publicAtSource));
        return value;
    }
}
