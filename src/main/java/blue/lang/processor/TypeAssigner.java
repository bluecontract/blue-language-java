package blue.lang.processor;

import blue.lang.*;
import blue.lang.utils.NodeToObject;

import static blue.lang.Types.isSubtype;

public class TypeAssigner implements MergingProcessor {

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        Node targetType = target.getType();
        Node sourceType = source.getType();
        if (targetType == null)
            target.type(sourceType);
        else if (sourceType != null) {
            boolean isSubtype = isSubtype(sourceType, targetType, nodeProvider);
            if (!isSubtype) {
                String errorMessage = String.format("The source type '%s' is not a subtype of the target type '%s'.",
                        NodeToObject.get(sourceType), NodeToObject.get(targetType));
                throw new IllegalArgumentException(errorMessage);
            }
            target.type(sourceType);
        }
    }
}
