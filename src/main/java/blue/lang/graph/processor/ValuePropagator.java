package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.NodeProcessor;

public class ValuePropagator implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        if (source.getValue() != null)
            target.value(source.getValue());
    }
}
