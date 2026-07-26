package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

@TypeBlueId(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)
public class TriggeredEventChannel extends ChannelContract {

    private Node event;

    public Node getEvent() {
        return event;
    }

    public void setEvent(Node event) {
        this.event = event;
    }
}
