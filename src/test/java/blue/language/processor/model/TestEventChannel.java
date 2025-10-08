package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.model.ChannelContract;

@TypeBlueId("TestEventChannel")
public class TestEventChannel extends ChannelContract {

    private String eventType;

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
}
