package blue.language.processor;

import blue.language.model.Node;

import java.util.Collections;
import java.util.List;

/**
 * Immutable result of evaluating an incoming event against a channel contract.
 *
 * <p>Contracts 1.0 channel evaluation has exactly one payload for the
 * preselected external occurrence. Caller-authored delivery occurrences are
 * not part of the two-input PROCESS model.</p>
 */
public final class ChannelEvaluation {

    private static final ChannelEvaluation NO_MATCH =
            new ChannelEvaluation(false, null, null);

    private final boolean matches;
    private final Node event;
    private final String eventId;

    private ChannelEvaluation(boolean matches, Node event, String eventId) {
        this.matches = matches;
        this.event = event != null ? event.clone() : null;
        this.eventId = eventId;
    }

    public static ChannelEvaluation noMatch() {
        return NO_MATCH;
    }

    public static ChannelEvaluation match(Node event) {
        return match(event, null);
    }

    public static ChannelEvaluation match(Node event, String eventId) {
        return new ChannelEvaluation(true, event, eventId);
    }

    /**
     * @deprecated Contracts 1.0 does not permit a runtime channel to create
     * caller-authored delivery occurrences. Return {@link #match(Node)} for
     * the single preselected occurrence instead.
     */
    @Deprecated
    public static ChannelEvaluation matchDeliveries(List<ChannelDelivery> deliveries) {
        throw new UnsupportedOperationException(
                "Caller-authored channel deliveries are not executable "
                        + "under Contracts 1.0");
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

    /**
     * @deprecated Caller-authored delivery occurrences are not executable
     * under Contracts 1.0. This compatibility view is always empty.
     */
    @Deprecated
    public List<ChannelDelivery> deliveries() {
        return Collections.emptyList();
    }
}
