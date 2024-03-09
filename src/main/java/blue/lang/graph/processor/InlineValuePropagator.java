package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.utils.Features;
import blue.lang.graph.utils.Nodes;
import blue.lang.graph.Feature;
import blue.lang.graph.NodeProcessor;
import blue.lang.graph.feature.InlineValueFeature;

public class InlineValuePropagator implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        Feature inlineValue = Features.getFeature(source, InlineValueFeature.class);
        if (inlineValue != null)
            Nodes.addFeature(target, inlineValue);
    }
}
