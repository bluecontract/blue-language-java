package blue.language.merge.processor;

import blue.language.*;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.utils.NodeToObject;
import blue.language.utils.Types;

import static blue.language.utils.Types.isSubtype;

public class ListProcessor implements MergingProcessor {

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {

        if (source.getItemType() != null && !Types.isListType(source.getType())) {
            throw new IllegalArgumentException("Source node with itemType must have a List type");
        }

        Node targetItemType = target.getItemType();
        Node sourceItemType = source.getItemType();

        if (targetItemType == null) {
            if (sourceItemType != null) {
                target.itemType(sourceItemType);
            }
        } else if (sourceItemType != null) {
            boolean isSubtype = isSubtype(sourceItemType, targetItemType, nodeProvider);
            if (!isSubtype) {
                String errorMessage = String.format("The source item type '%s' is not a subtype of the target item type '%s'.",
                        NodeToObject.get(sourceItemType), NodeToObject.get(targetItemType));
                throw new IllegalArgumentException(errorMessage);
            }
            target.itemType(sourceItemType);
        }

        if (target.getItemType() != null && source.getItems() != null) {
            for (Node item : source.getItems()) {
                if (item.getType() != null && !isSubtype(item.getType(), target.getItemType(), nodeProvider)) {
                    String errorMessage = String.format("Item of type '%s' is not a subtype of the list's item type '%s'.",
                            NodeToObject.get(item.getType()), NodeToObject.get(target.getItemType()));
                    throw new IllegalArgumentException(errorMessage);
                }
            }
        }
    }

}