package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.NodeProcessor;
import blue.lang.graph.Types;
import lombok.AllArgsConstructor;

import java.util.List;

@AllArgsConstructor
public class ListItemsTypeChecker implements NodeProcessor {

    private final Types types;

    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        List<Node> items = source.getItems();
        String type = target.getType();
        if (items == null || type == null)
            return;
        for (Node item : items) {
            String itemType = item.getType();
            if (itemType != null && !types.isSubtype(itemType, type)) {
                String errorMessage = String.format("List item type '%s' is not a subtype of expected type '%s'.", itemType, type);
                throw new IllegalArgumentException(errorMessage);
            }
        }

    }
}