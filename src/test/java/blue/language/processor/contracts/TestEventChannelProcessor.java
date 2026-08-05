package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.TestEventChannel;

import java.util.Collections;
import java.util.List;

public class TestEventChannelProcessor implements ChannelProcessor<TestEventChannel> {

    private static final String DEFAULT_EVENT_TYPE = ProcessorTestTypeBlueIds.TEST_EVENT;
    private final ExternalChannelSubscriptionFunctions<TestEventChannel>
            subscriptionFunctions =
            new ExternalChannelSubscriptionFunctions<TestEventChannel>() {
                @Override
                public List<String> channelKeys(
                        TestEventChannel channel) {
                    String eventType = channel.getEventType();
                    return Collections.singletonList(
                            eventType != null
                                    ? eventType
                                    : DEFAULT_EVENT_TYPE);
                }

                @Override
                public List<String> eventKeys(Node event) {
                    String eventType = resolveEventType(event);
                    return eventType != null
                            ? Collections.singletonList(eventType)
                            : Collections.<String>emptyList();
                }

                @Override
                public String checkpointDomainDiscriminator(
                        TestEventChannel channel) {
                    return null;
                }
            };

    @Override
    public Class<TestEventChannel> contractType() {
        return TestEventChannel.class;
    }

    @Override
    public ExternalChannelSubscriptionFunctions<TestEventChannel>
    externalSubscriptionFunctions() {
        return subscriptionFunctions;
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
