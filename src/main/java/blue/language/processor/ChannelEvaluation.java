package blue.language.processor;

import blue.language.model.Node;

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

}
