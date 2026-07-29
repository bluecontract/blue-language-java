package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies a transformation recursively to a defensive clone of every node in
 * a graph, including schema constraint nodes.
 */
public class NodeTransformer {

    /**
     * Creates a recursive node transformation helper.
     */
    public NodeTransformer() {
    }

    /**
     * Returns a transformed deep graph, or {@code null} for a null root.
     *
     * <p>The callback receives a clone of each source node, so the input graph
     * is never modified.</p>
     *
     * @param node source graph root, or {@code null}
     * @param nodeTransformer transformation applied to each cloned node
     * @return transformed deep graph, or {@code null} for a null root
     */
    public static Node transform(Node node, Function<Node, Node> nodeTransformer) {
        if (node == null) {
            return null;
        }

        Node transformedNode = nodeTransformer.apply(node.clone());
        if (Nodes.isEmptyPlaceholder(transformedNode)) {
            return transformedNode;
        }

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

        if (transformedNode.getContracts() != null) {
            transformedNode.contracts(transform(transformedNode.getContracts(), nodeTransformer));
        }

        if (transformedNode.getItems() != null) {
            List<Node> transformedItems = transformedNode.getItems().stream()
                    .map(item -> transform(item, nodeTransformer))
                    .collect(Collectors.toList());
            transformedNode.items(transformedItems);
        }

        if (transformedNode.getProperties() != null) {
            Map<String, Node> transformedProperties = new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry : transformedNode.getProperties().entrySet()) {
                transformedProperties.put(entry.getKey(), transform(entry.getValue(), nodeTransformer));
            }
            transformedNode.properties(transformedProperties);
        }

        transformSchema(transformedNode.getSchema(), nodeTransformer);

        return transformedNode;
    }

    private static void transformSchema(Schema schema, Function<Node, Node> nodeTransformer) {
        if (schema == null) {
            return;
        }
        schema.required(transform(schema.getRequired(), nodeTransformer));
        schema.minLength(transform(schema.getMinLength(), nodeTransformer));
        schema.maxLength(transform(schema.getMaxLength(), nodeTransformer));
        schema.minimum(transform(schema.getMinimum(), nodeTransformer));
        schema.maximum(transform(schema.getMaximum(), nodeTransformer));
        schema.exclusiveMinimum(transform(schema.getExclusiveMinimum(), nodeTransformer));
        schema.exclusiveMaximum(transform(schema.getExclusiveMaximum(), nodeTransformer));
        schema.multipleOf(transform(schema.getMultipleOf(), nodeTransformer));
        schema.minItems(transform(schema.getMinItems(), nodeTransformer));
        schema.maxItems(transform(schema.getMaxItems(), nodeTransformer));
        schema.uniqueItems(transform(schema.getUniqueItems(), nodeTransformer));
        schema.minFields(transform(schema.getMinFields(), nodeTransformer));
        schema.maxFields(transform(schema.getMaxFields(), nodeTransformer));
        if (schema.getEnum() != null) {
            schema.enumValues(schema.getEnum().stream()
                    .map(value -> transform(value, nodeTransformer))
                    .collect(Collectors.toList()));
        }
    }
}
