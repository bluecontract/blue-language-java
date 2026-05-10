package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of evaluating an incoming event against a channel contract.
 */
public final class ChannelEvaluation {

    private static final ChannelEvaluation NO_MATCH = new ChannelEvaluation(false, null, null, Collections.emptyList());

    private final boolean matches;
    private final Node event;
    private final String eventId;
    private final List<ChannelDelivery> deliveries;

    private ChannelEvaluation(boolean matches, Node event, String eventId, List<ChannelDelivery> deliveries) {
        this.matches = matches;
        this.event = event != null ? event.clone() : null;
        this.eventId = eventId;
        this.deliveries = copyDeliveries(deliveries);
    }

    public static ChannelEvaluation noMatch() {
        return NO_MATCH;
    }

    public static ChannelEvaluation match(Node event) {
        return match(event, null);
    }

    public static ChannelEvaluation match(Node event, String eventId) {
        return new ChannelEvaluation(true, event, eventId, Collections.emptyList());
    }

    public static ChannelEvaluation matchDeliveries(List<ChannelDelivery> deliveries) {
        List<ChannelDelivery> copy = copyDeliveries(deliveries);
        if (copy.isEmpty()) {
            return noMatch();
        }
        return new ChannelEvaluation(true, null, null, copy);
    }

    public boolean matches() {
        return matches;
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

    public List<ChannelDelivery> deliveries() {
        return deliveries;
    }

    private static List<ChannelDelivery> copyDeliveries(List<ChannelDelivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChannelDelivery> copy = new ArrayList<>();
        for (ChannelDelivery delivery : deliveries) {
            if (delivery != null) {
                copy.add(ChannelDelivery.of(delivery.event(),
                        delivery.eventId(),
                        delivery.checkpointKey(),
                        delivery.shouldProcess()));
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
