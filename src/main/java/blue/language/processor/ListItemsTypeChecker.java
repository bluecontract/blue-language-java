package blue.language.processor;

import blue.language.*;

import java.util.List;

import static blue.language.Types.isSubtype;

public class ListItemsTypeChecker implements MergingProcessor {

    private final Types types;

    public ListItemsTypeChecker(Types types) {
        this.types = types;
    }

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        List<Node> items = source.getItems();
        Node type = target.getType();
        if (items == null || type == null)
            return;
        for (Node item : items) {
            Node itemType = item.getType();
            if (itemType != null && !isSubtype(itemType, type, nodeProvider)) {
                String errorMessage = String.format("List item type '%s' is not a subtype of expected type '%s'.", itemType, type);
                throw new IllegalArgumentException(errorMessage);
            }
        }

    }
}