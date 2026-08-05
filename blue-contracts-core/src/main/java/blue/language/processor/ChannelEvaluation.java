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

    /**
     * Returns the shared result used when the channel rejected an event.
     *
     * @return an immutable, nonmatching result with no event or event identity
     */
    public static ChannelEvaluation noMatch() {
        return NO_MATCH;
    }

    /**
     * Creates a matching result without a separately supplied event identity.
     *
     * @param event accepted event; the result stores a defensive copy
     * @return a new immutable matching result
     */
    public static ChannelEvaluation match(Node event) {
        return match(event, null);
    }

    /**
     * Creates a matching result for an accepted event and its stable identity.
     *
     * @param event accepted event; the result stores a defensive copy
     * @param eventId stable event identity, or {@code null} when none is known
     * @return a new immutable matching result
     */
    public static ChannelEvaluation match(Node event, String eventId) {
        return new ChannelEvaluation(true, event, eventId);
    }

    /**
     * Reports whether the channel accepted the event.
     *
     * @return {@code true} for a matching result
     */
    public boolean matches() {
        return matches;
    }

    /**
     * Returns the accepted event without exposing the stored snapshot.
     *
     * @return a defensive event copy, or {@code null} for a nonmatch
     */
    public Node event() {
        return event != null ? event.clone() : null;
    }

    Node eventForDelivery() {
        return event != null ? event.clone() : null;
    }

    /**
     * Returns the supplied stable event identity.
     *
     * @return the event identity, or {@code null} when it was not supplied
     */
    public String eventId() {
        return eventId;
    }

}
