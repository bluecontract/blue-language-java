package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.model.ChannelContract;

@TypeBlueId(ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL)
public class TestEventChannel extends ChannelContract {

    private String eventType;

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
}
