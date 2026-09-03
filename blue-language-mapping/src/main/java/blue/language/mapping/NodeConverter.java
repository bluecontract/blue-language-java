package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

/** Produces a defensive mutable clone when the requested Java type is {@link Node}. */
public class NodeConverter implements Converter<Node> {

    /** Creates a stateless defensive-node converter. */
    public NodeConverter() {
    }

    @Override
    public Node convert(Node node, Type targetType) {
        if (node == null) {
            return null;
        }
        MappingPayload.requireCompatible(node, targetType, "node mapping");
        if (targetType instanceof Class<?> && Node.class.isAssignableFrom((Class<?>) targetType)) {
            return node.clone();
        } else {
            throw new IllegalArgumentException("Unsupported target type for Node conversion: " + targetType);
        }
    }
}
