package blue.lang.processor;

import blue.lang.*;

import java.util.List;

public class ListItemsTypeChecker implements NodeProcessor {

    private final Types types;

    public ListItemsTypeChecker(Types types) {
        this.types = types;
    }

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider) {
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