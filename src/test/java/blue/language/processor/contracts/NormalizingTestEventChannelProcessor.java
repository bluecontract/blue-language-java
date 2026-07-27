package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.model.TestEventChannel;

import java.util.List;

/**
 * Test channel processor that normalizes the event payload before handlers run.
 */
public class NormalizingTestEventChannelProcessor extends TestEventChannelProcessor {

    public static final String NORMALIZED_KIND = "channelized";
    private final ExternalChannelSubscriptionFunctions<TestEventChannel>
            subscriptionFunctions =
            new ExternalChannelSubscriptionFunctions<TestEventChannel>() {
                @Override
                public List<String> channelKeys(
                        TestEventChannel channel) {
                    return NormalizingTestEventChannelProcessor.super
                            .externalSubscriptionFunctions()
                            .channelKeys(channel);
                }

                @Override
                public List<String> eventKeys(Node event) {
                    return NormalizingTestEventChannelProcessor.super
                            .externalSubscriptionFunctions()
                            .eventKeys(event);
                }

                @Override
                public Node payload(
                        TestEventChannel channel,
                        Node event) {
                    Node normalized = event.clone();
                    normalized.properties(
                            "kind",
                            new Node().value(NORMALIZED_KIND));
                    return normalized;
                }

                @Override
                public String checkpointDomainDiscriminator(
                        TestEventChannel channel) {
                    return NormalizingTestEventChannelProcessor.super
                            .externalSubscriptionFunctions()
                            .checkpointDomainDiscriminator(channel);
                }
            };

    @Override
    public ExternalChannelSubscriptionFunctions<TestEventChannel>
    externalSubscriptionFunctions() {
        return subscriptionFunctions;
    }

    @Override
    public ChannelEvaluation evaluate(TestEventChannel contract, ChannelEvaluationContext context) {
        ChannelEvaluation evaluation = super.evaluate(contract, context);
        if (!evaluation.matches()) {
            return evaluation;
        }
        Node event = evaluation.event();
        if (event != null) {
            event.properties("kind", new Node().value(NORMALIZED_KIND));
        }
        return ChannelEvaluation.match(event, evaluation.eventId());
    }
}
