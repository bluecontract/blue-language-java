package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.utils.Nodes;
import blue.lang.graph.NodeProcessor;

public class FeaturesPropagator implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        if (Nodes.isEmptyNode(target)) {
            new InlineValuePropagator().process(target, source, nodeManager);
        }
    }
}
