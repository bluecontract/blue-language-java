package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.model.TestEventChannel;

/**
 * Test channel processor that normalizes the event payload before handlers run.
 */
public class NormalizingTestEventChannelProcessor extends TestEventChannelProcessor {

    public static final String NORMALIZED_KIND = "channelized";

    @Override
    public boolean matches(TestEventChannel contract, ChannelEvaluationContext context) {
        boolean matches = super.matches(contract, context);
        if (!matches) {
            return false;
        }
        Node event = context.event();
        if (event != null) {
            event.properties("kind", new Node().value(NORMALIZED_KIND));
        }
        return true;
    }
}
