package blue.language.merge.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;

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