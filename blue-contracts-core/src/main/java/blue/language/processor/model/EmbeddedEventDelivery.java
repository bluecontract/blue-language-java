package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

/**
 * Exact processor payload presented to an Embedded Node Channel handler.
 *
 * <p>This wire model is mutable. Its event node is retained and returned by
 * reference, so callers must clone the node when isolation is required.</p>
 */
@TypeBlueId(RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY)
public final class EmbeddedEventDelivery {

    private String sourcePath;
    private Node event;

    /** Creates an empty embedded-event delivery payload. */
    public EmbeddedEventDelivery() {
    }

    /**
     * Returns the embedded scope path that originally emitted the event.
     *
     * @return source scope path, or {@code null} when not assigned
     */
    public String getSourcePath() {
        return sourcePath;
    }

    /**
     * Sets the embedded scope path that originally emitted the event.
     *
     * @param sourcePath source scope path, or {@code null} to clear it
     */
    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    /**
     * Returns the exact event delivered across the embedded boundary.
     *
     * @return retained event reference, or {@code null} when absent
     */
    public Node getEvent() {
        return event;
    }

    /**
     * Sets the exact event delivered across the embedded boundary.
     *
     * @param event event retained by reference, or {@code null}
     */
    public void setEvent(Node event) {
        this.event = event;
    }
}
