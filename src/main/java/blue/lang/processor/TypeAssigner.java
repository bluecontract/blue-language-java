package blue.lang.processor;

import blue.lang.Resolver;
import blue.lang.Node;
import blue.lang.Types;
import blue.lang.NodeProcessor;

public class TypeAssigner implements NodeProcessor {

    private final Types types;

    public TypeAssigner(Types types) {
        this.types = types;
    }

    @Override
    public void process(Node target, Node source, Resolver resolver) {
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
