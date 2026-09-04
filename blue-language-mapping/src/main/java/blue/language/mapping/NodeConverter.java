package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

/**
 * Validates the complete semantic node graph and produces a defensive mutable
 * clone when the requested Java type is {@link Node}.
 */
public class NodeConverter implements Converter<Node> {

    /** Creates a stateless defensive-node converter. */
    public NodeConverter() {
    }

    @Override
    public Node convert(Node node, Type targetType) {
        return MappingPayload.atSemanticBoundary(
                node,
                "node mapping",
                () -> {
                    if (node == null) {
                        return null;
                    }
                    if (targetType instanceof Class<?>
                            && Node.class.isAssignableFrom(
                                    (Class<?>) targetType)) {
                        return node.clone();
                    }
                    throw new IllegalArgumentException(
                            "Unsupported target type for Node conversion: "
                                    + targetType);
                });
    }
}
