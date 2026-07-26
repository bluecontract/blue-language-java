package blue.language.processor;

import blue.language.model.Node;

import java.util.Objects;

/**
 * Legacy pre-1.0 routed-delivery value.
 *
 * @deprecated Contracts 1.0 derives the one external occurrence from verified
 * feeder evidence. Values of this type are retained only for source
 * compatibility and cannot be submitted to PROCESS.
 */
@Deprecated
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
     * Creates a legacy value for source compatibility. The returned value is
     * not executable by the Contracts 1.0 processor.
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
