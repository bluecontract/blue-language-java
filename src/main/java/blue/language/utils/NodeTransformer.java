package blue.language.utils;

import blue.language.model.Node;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class NodeTransformer {
    public static Node transform(Node node, Function<Node, Node> nodeTransformer) {
        if (node == null) {
            return null;
        }

        Node transformedNode = nodeTransformer.apply(node.clone());

        if (transformedNode.getType() != null) {
            transformedNode.type(transform(transformedNode.getType(), nodeTransformer));
        }

        if (transformedNode.getItemType() != null) {
            transformedNode.itemType(transform(transformedNode.getItemType(), nodeTransformer));
        }

        if (transformedNode.getKeyType() != null) {
            transformedNode.keyType(transform(transformedNode.getKeyType(), nodeTransformer));
        }

        if (transformedNode.getValueType() != null) {
            transformedNode.valueType(transform(transformedNode.getValueType(), nodeTransformer));
        }

        if (transformedNode.getItems() != null) {
            List<Node> transformedItems = transformedNode.getItems().stream()
                    .map(item -> transform(item, nodeTransformer))
                    .collect(Collectors.toList());
            transformedNode.items(transformedItems);
        }

        if (transformedNode.getProperties() != null) {
            Map<String, Node> transformedProperties = new HashMap<>();
            for (Map.Entry<String, Node> entry : transformedNode.getProperties().entrySet()) {
                transformedProperties.put(entry.getKey(), transform(entry.getValue(), nodeTransformer));
            }
            transformedNode.properties(transformedProperties);
        }

        return transformedNode;
    }
}