package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete authenticated receipt for one committed managed-document
 * transition in an affected-closure invocation.
 *
 * <p>This Contracts value deliberately has no Coordination epoch.  It binds
 * the exact source invocation, managed lineage, before/after identities,
 * complete Root-boundary event occurrences, original cause, and admitted gas.
 * Coordination may assign a stable managed epoch only after atomically
 * committing this receipt with the closure companion.</p>
 */
public final class ManagedDocumentTransitionReceipt {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private final String transitionReceiptIdentity;
    private final String sourceInvocationIdentity;
    private final long transitionOrdinal;
    private final String transitionOccurrenceIdentity;
    private final DocumentId documentId;
    private final String originalCauseIdentity;
    private final String beforeBlueId;
    private final String afterBlueId;
    private final List<ManagedRootEventOccurrence> emittedRootEvents;
    private final String emittedRootEventsIdentity;
    private final long admittedGas;

    /**
     * Creates and independently verifies one complete transition receipt.
     *
     * @param transitionReceiptIdentity asserted exact receipt identity
     * @param sourceInvocationIdentity exact source invocation identity
     * @param transitionOrdinal canonical receipt-sequence ordinal
     * @param transitionOccurrenceIdentity exact transition occurrence identity
     * @param documentId transitioned managed document
     * @param originalCauseIdentity original source cause identity
     * @param beforeBlueId exact predecessor document identity
     * @param afterBlueId exact successor document identity
     * @param emittedRootEvents complete ordered Root-event occurrences
     * @param emittedRootEventsIdentity exact ordered event sequence identity
     * @param admittedGas exact admitted gas partition for this transition
     */
    public ManagedDocumentTransitionReceipt(
            String transitionReceiptIdentity,
            String sourceInvocationIdentity,
            long transitionOrdinal,
            String transitionOccurrenceIdentity,
            DocumentId documentId,
            String originalCauseIdentity,
            String beforeBlueId,
            String afterBlueId,
            List<ManagedRootEventOccurrence> emittedRootEvents,
            String emittedRootEventsIdentity,
            long admittedGas) {
        this.sourceInvocationIdentity =
                ClosureValueSupport.requireSha256Identity(
                        sourceInvocationIdentity,
                        "sourceInvocationIdentity");
        this.transitionOrdinal = ClosureValueSupport.requireSafeInteger(
                transitionOrdinal, "transitionOrdinal");
        this.transitionOccurrenceIdentity =
                ClosureValueSupport.requireSha256Identity(
                        transitionOccurrenceIdentity,
                        "transitionOccurrenceIdentity");
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.originalCauseIdentity =
                ClosureValueSupport.requireSha256Identity(
                        originalCauseIdentity, "originalCauseIdentity");
        this.beforeBlueId = ClosureValueSupport.requireBlueId(
                beforeBlueId, "beforeBlueId");
        this.afterBlueId = ClosureValueSupport.requireBlueId(
                afterBlueId, "afterBlueId");
        this.emittedRootEvents = immutableEvents(emittedRootEvents);
        this.emittedRootEventsIdentity =
                ClosureValueSupport.requireSha256Identity(
                        emittedRootEventsIdentity,
                        "emittedRootEventsIdentity");
        this.admittedGas = ClosureValueSupport.requireSafeInteger(
                admittedGas, "admittedGas");
        if (this.beforeBlueId.equals(this.afterBlueId)
                && this.emittedRootEvents.isEmpty()) {
            throw new IllegalArgumentException(
                    "A managed transition receipt requires state change or Root events");
        }
        verifyEvents();
        requireIdentity(
                "transitionOccurrenceIdentity",
                this.transitionOccurrenceIdentity,
                IDENTITIES.managedTransitionOccurrenceIdentity(
                        this.sourceInvocationIdentity,
                        this.transitionOrdinal,
                        this.documentId,
                        this.originalCauseIdentity));
        requireIdentity(
                "emittedRootEventsIdentity",
                this.emittedRootEventsIdentity,
                IDENTITIES.managedRootEventsIdentity(
                        this.emittedRootEvents));
        String computed = IDENTITIES
                .managedDocumentTransitionReceiptIdentity(
                        this.sourceInvocationIdentity,
                        this.transitionOrdinal,
                        this.transitionOccurrenceIdentity,
                        this.documentId,
                        this.originalCauseIdentity,
                        this.beforeBlueId,
                        this.afterBlueId,
                        this.emittedRootEvents,
                        this.emittedRootEventsIdentity,
                        this.admittedGas);
        this.transitionReceiptIdentity =
                ClosureValueSupport.requireSha256Identity(
                        transitionReceiptIdentity,
                        "transitionReceiptIdentity");
        requireIdentity("transitionReceiptIdentity",
                this.transitionReceiptIdentity, computed);
    }

    static ManagedDocumentTransitionReceipt identified(
            String sourceInvocationIdentity,
            long transitionOrdinal,
            DocumentId documentId,
            String originalCauseIdentity,
            String beforeBlueId,
            String afterBlueId,
            List<ManagedRootEventOccurrence> emittedRootEvents,
            long admittedGas) {
        String occurrenceIdentity = IDENTITIES
                .managedTransitionOccurrenceIdentity(
                        sourceInvocationIdentity,
                        transitionOrdinal,
                        documentId,
                        originalCauseIdentity);
        String eventsIdentity = IDENTITIES.managedRootEventsIdentity(
                emittedRootEvents);
        String receiptIdentity = IDENTITIES
                .managedDocumentTransitionReceiptIdentity(
                        sourceInvocationIdentity,
                        transitionOrdinal,
                        occurrenceIdentity,
                        documentId,
                        originalCauseIdentity,
                        beforeBlueId,
                        afterBlueId,
                        emittedRootEvents,
                        eventsIdentity,
                        admittedGas);
        return new ManagedDocumentTransitionReceipt(
                receiptIdentity,
                sourceInvocationIdentity,
                transitionOrdinal,
                occurrenceIdentity,
                documentId,
                originalCauseIdentity,
                beforeBlueId,
                afterBlueId,
                emittedRootEvents,
                eventsIdentity,
                admittedGas);
    }

    /**
     * Returns the exact receipt identity.
     *
     * @return exact receipt identity
     */
    public String transitionReceiptIdentity() {
        return transitionReceiptIdentity;
    }

    /**
     * Returns the exact source closure invocation identity.
     *
     * @return exact source closure invocation identity
     */
    public String sourceInvocationIdentity() {
        return sourceInvocationIdentity;
    }

    /**
     * Returns the contiguous transition ordinal in the result sequence.
     *
     * @return contiguous transition ordinal in the result receipt sequence
     */
    public long transitionOrdinal() {
        return transitionOrdinal;
    }

    /**
     * Returns the exact transition occurrence identity.
     *
     * @return exact transition occurrence identity
     */
    public String transitionOccurrenceIdentity() {
        return transitionOccurrenceIdentity;
    }

    /**
     * Returns the transitioned managed document.
     *
     * @return transitioned managed document
     */
    public DocumentId documentId() {
        return documentId;
    }

    /**
     * Returns the original source cause identity.
     *
     * @return original source cause identity
     */
    public String originalCauseIdentity() {
        return originalCauseIdentity;
    }

    /**
     * Returns the exact predecessor document identity.
     *
     * @return exact predecessor document identity
     */
    public String beforeBlueId() {
        return beforeBlueId;
    }

    /**
     * Returns the exact successor document identity.
     *
     * @return exact successor document identity
     */
    public String afterBlueId() {
        return afterBlueId;
    }

    /**
     * Returns the immutable complete Root-boundary event sequence.
     *
     * @return immutable complete Root-boundary event occurrence sequence
     */
    public List<ManagedRootEventOccurrence> emittedRootEvents() {
        return emittedRootEvents;
    }

    /**
     * Returns the exact ordered Root-event sequence identity.
     *
     * @return exact ordered Root-event sequence identity
     */
    public String emittedRootEventsIdentity() {
        return emittedRootEventsIdentity;
    }

    /**
     * Returns the exact gas partition attributed to this transition.
     *
     * @return exact gas attributed to this managed transition
     */
    public long admittedGas() {
        return admittedGas;
    }

    Map<String, Object> identityValue() {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("transitionOrdinal", Long.valueOf(transitionOrdinal));
        value.put("transitionReceiptIdentity", transitionReceiptIdentity);
        return value;
    }

    private void verifyEvents() {
        long previousOccurrenceOrdinal = -1L;
        for (int index = 0; index < emittedRootEvents.size(); index++) {
            ManagedRootEventOccurrence event = emittedRootEvents.get(index);
            if (event.ordinal() != index) {
                throw new IllegalArgumentException(
                        "Managed Root event ordinals must be contiguous");
            }
            if (!documentId.equals(event.sourceDocumentId())) {
                throw new IllegalArgumentException(
                        "Managed Root event belongs to another document");
            }
            if (event.occurrenceOrdinal() <= previousOccurrenceOrdinal) {
                throw new IllegalArgumentException(
                        "Managed Root event occurrence ordinals must increase");
            }
            previousOccurrenceOrdinal = event.occurrenceOrdinal();
            requireIdentity(
                    "eventOccurrenceIdentity",
                    event.occurrenceIdentity(),
                    IDENTITIES.eventOccurrenceIdentity(
                            sourceInvocationIdentity,
                            event.occurrenceOrdinal(),
                            event.eventBlueId()));
        }
    }

    private static List<ManagedRootEventOccurrence> immutableEvents(
            List<ManagedRootEventOccurrence> values) {
        ArrayList<ManagedRootEventOccurrence> copy =
                new ArrayList<ManagedRootEventOccurrence>();
        for (ManagedRootEventOccurrence value : Objects.requireNonNull(
                values, "emittedRootEvents")) {
            copy.add(Objects.requireNonNull(value, "managed Root event"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static void requireIdentity(
            String field, String asserted, String computed) {
        if (!asserted.equals(computed)) {
            throw new IllegalArgumentException(
                    field + " does not identify its exact value");
        }
    }
}
