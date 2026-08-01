package blue.language.merge.processor;

import blue.language.*;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.model.NodeWireForm;

import static blue.language.provider.Types.isSubtype;

/**
 * Applies a source declared type only when it is equal to or more specific than
 * the type already required by the target.
 */
public class TypeAssigner implements MergingProcessor {

    /**
     * Creates a stateless type-assignment stage.
     */
    public TypeAssigner() {
    }

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
                        NodeWireForm.get(sourceType), NodeWireForm.get(targetType));
                throw new IllegalArgumentException(errorMessage);
            }
            target.type(sourceType);
        }
    }
}
