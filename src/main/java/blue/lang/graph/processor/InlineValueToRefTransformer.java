package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.feature.InlineValueFeature;
import blue.lang.graph.utils.Features;
import blue.lang.graph.NodeProcessor;

public class InlineValueToRefTransformer implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        if (target.getType() != null &&
                source.getType() == null &&
                source.getValue() != null &&
                Features.containsFeature(source, InlineValueFeature.class)) {
            source.ref(source.getValue().toString());
            source.value(null);
        }
    }
}