package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.NodeProcessor;

public class NameToNullOnTypeMatchTransformer implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        if (target.getName() != null &&
                target.getType() != null &&
                target.getName().equals(target.getType())) {
            target.name(null);
        }
    }
}