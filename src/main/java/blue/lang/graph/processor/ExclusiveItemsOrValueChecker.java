package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.NodeProcessor;

import java.util.List;

public class ExclusiveItemsOrValueChecker implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        List<Node> items = source.getItems();
        Object value = source.getValue();
        if (items != null && value != null)
            throw new IllegalArgumentException("Node cannot have both 'items' and 'value' set at the same time.");
    }
}