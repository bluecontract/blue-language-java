package blue.language.processor;

import blue.language.NodeProvider;
import blue.language.Node;
import blue.language.NodeResolver;
import blue.language.utils.Nodes;
import blue.language.MergingProcessor;

public class FeaturesPropagator implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        if (Nodes.isEmptyNode(target)) {
            new InlineValuePropagator().process(target, source, nodeProvider, nodeResolver);
        }
    }
}
