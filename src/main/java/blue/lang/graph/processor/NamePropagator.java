package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.NodeProcessor;

public class NamePropagator implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        if (source.getName() != null)
            target.name(source.getName());
    }
}
