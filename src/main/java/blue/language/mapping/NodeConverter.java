package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

public class NodeConverter implements Converter<Node> {
    @Override
    public Node convert(Node node, Type targetType) {
        if (targetType instanceof Class<?> && Node.class.isAssignableFrom((Class<?>) targetType)) {
            return node.clone();
        } else {
            throw new IllegalArgumentException("Unsupported target type for Node conversion: " + targetType);
        }
    }
}