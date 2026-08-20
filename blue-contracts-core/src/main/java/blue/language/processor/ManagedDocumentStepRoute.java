package blue.language.processor;

import blue.language.model.Node;

import java.util.Objects;

/**
 * Canonically selected Root channel delivery produced without executing work.
 *
 * <p>The route is closure-neutral: it names the local work kind and channel,
 * retains the exact adapter payload, and separately retains an originating
 * occurrence only when an embedded adapter requires it.</p>
 */
public final class ManagedDocumentStepRoute {

    private final ManagedDocumentWorkKind workKind;
    private final String channelKey;
    private final Node exactPayload;
    private final Node occurrenceEvent;

    ManagedDocumentStepRoute(
            ManagedDocumentWorkKind workKind,
            String channelKey,
            Node exactPayload,
            Node occurrenceEvent) {
        this.workKind = Objects.requireNonNull(workKind, "workKind");
        if (workKind != ManagedDocumentWorkKind.TRIGGERED_EVENT
                && workKind != ManagedDocumentWorkKind.EMBEDDED_EVENT
                && workKind != ManagedDocumentWorkKind.DOCUMENT_UPDATE
                && workKind != ManagedDocumentWorkKind.LIFECYCLE) {
            throw new IllegalArgumentException(
                    "Only routed event/update/lifecycle work has a classified route");
        }
        this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
        this.exactPayload = Objects.requireNonNull(
                exactPayload, "exactPayload").clone();
        this.occurrenceEvent = occurrenceEvent != null
                ? occurrenceEvent.clone()
                : null;
        if ((workKind == ManagedDocumentWorkKind.EMBEDDED_EVENT)
                != (this.occurrenceEvent != null)) {
            throw new IllegalArgumentException(
                    "Embedded routes alone require occurrenceEvent");
        }
    }

    /**
     * Returns the selected processor-managed work role.
     *
     * @return triggered, embedded, document-update, or lifecycle work kind
     */
    public ManagedDocumentWorkKind workKind() {
        return workKind;
    }

    /**
     * Returns the exact Root channel contract key.
     *
     * @return non-empty channel key
     */
    public String channelKey() {
        return channelKey;
    }

    /**
     * Returns the exact payload to pass to the selected channel.
     *
     * @return defensive payload copy
     */
    public Node exactPayload() {
        return exactPayload.clone();
    }

    /**
     * Returns the semantic occurrence behind an embedded adapter.
     *
     * @return defensive occurrence copy, or {@code null} for other routes
     */
    public Node occurrenceEvent() {
        return occurrenceEvent != null ? occurrenceEvent.clone() : null;
    }
}
