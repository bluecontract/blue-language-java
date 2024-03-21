package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.MergingProcessor;
import blue.lang.NodeResolver;

import java.util.List;

public class ExclusiveItemsOrValueChecker implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        List<Node> items = source.getItems();
        Object value = source.getValue();
        if (items != null && value != null)
            throw new IllegalArgumentException("Node cannot have both 'items' and 'value' set at the same time.");
    }
}