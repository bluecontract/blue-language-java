package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Exact external event and its independently selected total-order evidence. */
public final class ExternalEventCause extends ProcessingCause {

    private final Node event;
    private final String eventBlueId;
    private final ExternalOrderKey sourceOrder;
    private final String externalOrderPolicyIdentity;

    /**
     * Creates one exact external cause.
     *
     * @param causeIdentity exact cause identity
     * @param event exact event node
     * @param eventBlueId exact event BlueId
     * @param sourceOrder verified external order key
     * @param externalOrderPolicyIdentity selected order-policy identity
     */
    public ExternalEventCause(
            String causeIdentity,
            Node event,
            String eventBlueId,
            ExternalOrderKey sourceOrder,
            String externalOrderPolicyIdentity) {
        super(causeIdentity);
        this.event = Objects.requireNonNull(event, "event").clone();
        this.eventBlueId = ClosureValueSupport.requireBlueId(
                eventBlueId, "eventBlueId");
        this.sourceOrder = Objects.requireNonNull(sourceOrder, "sourceOrder");
        this.externalOrderPolicyIdentity =
                ClosureValueSupport.requireSha256Identity(
                        externalOrderPolicyIdentity,
                        "externalOrderPolicyIdentity");
    }

    /**
     * Returns the documented value.
     *
     * @return {@link Kind#EXTERNAL}
     */
    @Override
    public Kind kind() {
        return Kind.EXTERNAL;
    }

    /**
     * Returns the documented value.
     *
     * @return defensive copy of the exact event
     */
    public Node event() {
        return event.clone();
    }

    /**
     * Returns the documented value.
     *
     * @return exact event BlueId
     */
    public String eventBlueId() {
        return eventBlueId;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable verified external order key
     */
    public ExternalOrderKey sourceOrder() {
        return sourceOrder;
    }

    /**
     * Returns the documented value.
     *
     * @return selected external-order-policy identity
     */
    public String externalOrderPolicyIdentity() {
        return externalOrderPolicyIdentity;
    }
}
