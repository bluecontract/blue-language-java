package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.TestEventChannel;

public class TestEventChannelProcessor implements ChannelProcessor<TestEventChannel> {

    private static final String DEFAULT_EVENT_TYPE = "TestEvent";

    @Override
    public Class<TestEventChannel> contractType() {
        return TestEventChannel.class;
    }

    @Override
    public boolean matches(TestEventChannel contract, ChannelEvaluationContext context) {
        Object eventObject = context.eventObject();
        if (!(eventObject instanceof TestEvent)) {
            return false;
        }
        String expectedType = contract.getEventType() != null ? contract.getEventType() : DEFAULT_EVENT_TYPE;
        if (!expectedType.equals(resolveEventType(context.event()))) {
            return false;
        }
        return true;
    }

    @Override
    public String eventId(TestEventChannel contract, ChannelEvaluationContext context) {
        Object eventObject = context.eventObject();
        if (eventObject instanceof TestEvent) {
            TestEvent event = (TestEvent) eventObject;
            return event.getEventId();
        }
        return null;
    }

    private String resolveEventType(Node event) {
        if (event == null) {
            return null;
        }
        Node typeNode = event.getType();
        if (typeNode == null) {
            return null;
        }
        if (typeNode.getBlueId() != null) {
            return typeNode.getBlueId();
        }
        if (typeNode.getProperties() == null) {
            return null;
        }
        Node blueIdNode = typeNode.getProperties().get("blueId");
        if (blueIdNode == null) {
            return null;
        }
        Object value = blueIdNode.getValue();
        return value instanceof String ? (String) value : null;
    }
}
