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
    private final String handlerChannelKey;
    private final String logicalDeliveryKey;

    private ChannelDelivery(Node event,
                            String eventId,
                            String checkpointKey,
                            Boolean shouldProcess,
                            String handlerChannelKey,
                            String logicalDeliveryKey) {
        this.event = Objects.requireNonNull(event, "event").clone();
        this.eventId = eventId;
        this.checkpointKey = checkpointKey;
        this.shouldProcess = shouldProcess;
        this.handlerChannelKey = handlerChannelKey;
        this.logicalDeliveryKey = logicalDeliveryKey;
    }

    public static ChannelDelivery of(Node event) {
        return of(event, null, null, null);
    }

    public static ChannelDelivery of(Node event, String eventId, String checkpointKey, Boolean shouldProcess) {
        return of(event, eventId, checkpointKey, shouldProcess, null, null);
    }

    /**
     * Creates a delivery with optional same-scope handler routing and logical-delivery identity.
     *
     * <p>When {@code handlerChannelKey} is absent, handlers are selected from the accepting
     * channel. When {@code logicalDeliveryKey} is absent, the delivery is not deduplicated
     * across accepting channels.</p>
     */
    public static ChannelDelivery of(Node event,
                                     String eventId,
                                     String checkpointKey,
                                     Boolean shouldProcess,
                                     String handlerChannelKey,
                                     String logicalDeliveryKey) {
        return new ChannelDelivery(event,
                eventId,
                checkpointKey,
                shouldProcess,
                handlerChannelKey,
                logicalDeliveryKey);
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

    /**
     * Returns the same-scope channel used for handler discovery, or {@code null} to use the
     * accepting channel.
     */
    public String handlerChannelKey() {
        return handlerChannelKey;
    }

    /**
     * Returns the caller-supplied stable domain key for execution-scoped route deduplication.
     */
    public String logicalDeliveryKey() {
        return logicalDeliveryKey;
    }
}
