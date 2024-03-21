package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.NodeResolver;
import blue.lang.utils.Nodes;
import blue.lang.MergingProcessor;

public class FeaturesPropagator implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        if (Nodes.isEmptyNode(target)) {
            new InlineValuePropagator().process(target, source, nodeProvider, nodeResolver);
        }
    }
}
