package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.Types;
import blue.lang.graph.NodeProcessor;

public class TypeAssigner implements NodeProcessor {

    private final Types types;

    public TypeAssigner(Types types) {
        this.types = types;
    }

    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        String targetType = target.getType();
        String sourceType = source.getType();
        if (targetType == null)
            target.type(sourceType);
        else if (sourceType != null) {
            boolean isSubtype = types.isSubtype(sourceType, targetType);
            if (!isSubtype) {
                String errorMessage = String.format("The source type '%s' is not a subtype of the target type '%s'.", sourceType, targetType);
                throw new IllegalArgumentException(errorMessage);
            }
            target.type(sourceType);
        }
    }
}
