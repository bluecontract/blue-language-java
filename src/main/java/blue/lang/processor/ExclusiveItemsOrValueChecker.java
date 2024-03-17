package blue.lang.processor;

import blue.lang.Resolver;
import blue.lang.Node;
import blue.lang.NodeProcessor;

import java.util.List;

public class ExclusiveItemsOrValueChecker implements NodeProcessor {
    @Override
    public void process(Node target, Node source, Resolver resolver) {
        List<Node> items = source.getItems();
        Object value = source.getValue();
        if (items != null && value != null)
            throw new IllegalArgumentException("Node cannot have both 'items' and 'value' set at the same time.");
    }
}