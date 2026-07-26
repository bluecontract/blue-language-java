package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

/**
 * Exact processor payload presented to an Embedded Node Channel handler.
 */
@TypeBlueId(RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY)
public final class EmbeddedEventDelivery {

    private String sourcePath;
    private Node event;

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public Node getEvent() {
        return event;
    }

    public void setEvent(Node event) {
        this.event = event;
    }
}
