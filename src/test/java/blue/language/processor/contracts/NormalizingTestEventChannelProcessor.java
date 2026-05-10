package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.model.TestEventChannel;

/**
 * Test channel processor that normalizes the event payload before handlers run.
 */
public class NormalizingTestEventChannelProcessor extends TestEventChannelProcessor {

    public static final String NORMALIZED_KIND = "channelized";

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
