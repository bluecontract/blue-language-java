package blue.language.merge.processor;

import blue.language.*;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.utils.Types;

import java.util.List;

import static blue.language.utils.Types.isSubtype;

/**
 * Compatibility merge stage that checks contributed list-item types against
 * the target list type.
 */
public class ListItemsTypeChecker implements MergingProcessor {

    private final Types types;

    /**
     * Creates a checker using the supplied type hierarchy.
     *
     * @param types type hierarchy available to the compatibility check
     */
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
