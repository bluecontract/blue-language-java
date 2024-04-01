package blue.language.processor;

import blue.language.MergingProcessor;
import blue.language.Node;
import blue.language.NodeProvider;
import blue.language.NodeResolver;

import java.util.List;

public class ListBlueIdResolver implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        if (source.getItems() != null && !source.getItems().isEmpty()) {
            while (true) {
                List<Node> items = source.getItems();
                Node firstItem = items.get(0);
                String blueId = firstItem.getBlueId();
                if (blueId == null)
                    return;
                List<Node> resolved = nodeProvider.fetchByBlueId(blueId);
                if (resolved == null || resolved.size() == 1)
                    return;
                items.remove(0);
                items.addAll(0, resolved);
            }
        }
    }
}