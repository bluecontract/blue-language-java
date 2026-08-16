package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable exact external cause and the policy that made its source order canonical. */
public final class ExternalEventCause extends ProcessingCause {
    private final String causeIdentity;
    private final Object event;
    private final String eventBlueId;
    private final List<Object> sourceOrder;
    private final String externalOrderPolicyIdentity;

    public ExternalEventCause(
            String causeIdentity,
            Object event,
            String eventBlueId,
            List<?> sourceOrder,
            String externalOrderPolicyIdentity) {
        this.causeIdentity = Objects.requireNonNull(causeIdentity, "causeIdentity");
        this.event = Objects.requireNonNull(event, "event");
        this.eventBlueId = Objects.requireNonNull(eventBlueId, "eventBlueId");
        ArrayList<Object> sourceOrderCopy = new ArrayList<Object>(
                Objects.requireNonNull(sourceOrder, "sourceOrder"));
        if (sourceOrderCopy.size() < 3) {
            throw new IllegalArgumentException("sourceOrder");
        }
        this.sourceOrder = Collections.unmodifiableList(sourceOrderCopy);
        this.externalOrderPolicyIdentity = Objects.requireNonNull(
                externalOrderPolicyIdentity, "externalOrderPolicyIdentity");
    }

    @Override
    public String causeIdentity() {
        return causeIdentity;
    }

    @Override
    public String kind() {
        return "external";
    }

    public Object event() {
        return event;
    }

    public String eventBlueId() {
        return eventBlueId;
    }

    public List<Object> sourceOrder() {
        return sourceOrder;
    }

    public String externalOrderPolicyIdentity() {
        return externalOrderPolicyIdentity;
    }
}
