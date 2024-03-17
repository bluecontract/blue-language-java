package blue.lang.processor;

import blue.lang.Resolver;
import blue.lang.Node;
import blue.lang.utils.Nodes;
import blue.lang.NodeProcessor;

public class FeaturesPropagator implements NodeProcessor {
    @Override
    public void process(Node target, Node source, Resolver resolver) {
        if (Nodes.isEmptyNode(target)) {
            new InlineValuePropagator().process(target, source, resolver);
        }
    }
}