package blue.language.processor;

import blue.language.model.Node;

import java.util.Objects;

/**
 * One handler delivery produced by a channel evaluation.
 */
public final class ChannelDelivery {

    private final Node event;
    private final String eventId;
    private final String checkpointKey;
    private final Boolean shouldProcess;

    private ChannelDelivery(Node event, String eventId, String checkpointKey, Boolean shouldProcess) {
        this.event = Objects.requireNonNull(event, "event").clone();
        this.eventId = eventId;
        this.checkpointKey = checkpointKey;
        this.shouldProcess = shouldProcess;
    }

    public static ChannelDelivery of(Node event) {
        return of(event, null, null, null);
    }

    public static ChannelDelivery of(Node event, String eventId, String checkpointKey, Boolean shouldProcess) {
        return new ChannelDelivery(event, eventId, checkpointKey, shouldProcess);
    }

    public Node event() {
        return event != null ? event.clone() : null;
    }

    Node eventForDelivery() {
        return event != null ? event.clone() : null;
    }

    public String eventId() {
        return eventId;
    }

    public String checkpointKey() {
        return checkpointKey;
    }

    public Boolean shouldProcess() {
        return shouldProcess;
    }
}
