package blue.language.merge.processor;

import blue.language.*;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.utils.NodeToMapListOrValue;

import static blue.language.utils.Types.isSubtype;

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
                        NodeToMapListOrValue.get(sourceType), NodeToMapListOrValue.get(targetType));
                throw new IllegalArgumentException(errorMessage);
            }
            target.type(sourceType);
        }
    }
}
