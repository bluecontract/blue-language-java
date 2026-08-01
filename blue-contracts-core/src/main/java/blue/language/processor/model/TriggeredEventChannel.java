package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

/**
 * Processor-managed channel that receives FIFO occurrences matching an event
 * pattern.
 *
 * <p>The mutable event pattern is retained and returned by reference.</p>
 */
@TypeBlueId(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)
public class TriggeredEventChannel extends ChannelContract {

    private Node event;

    /** Creates an unconfigured triggered-event channel. */
    public TriggeredEventChannel() {
    }

    /**
     * Returns the event pattern used to select queued occurrences.
     *
     * @return retained event-pattern reference, or {@code null} when absent
     */
    public Node getEvent() {
        return event;
    }

    /**
     * Sets the event pattern used to select queued occurrences.
     *
     * @param event event pattern retained by reference, or {@code null}
     */
    public void setEvent(Node event) {
        this.event = event;
    }
}
