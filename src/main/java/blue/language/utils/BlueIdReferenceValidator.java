package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the syntax of every BlueId reference in a complete input graph.
 */
public final class BlueIdReferenceValidator {

    private BlueIdReferenceValidator() {
    }

    /**
     * Validates every reference reachable from the supplied input graph.
     *
     * @param root complete input graph; {@code null} is accepted
     */
    public static void validate(Node root) {
        if (root == null) {
            return;
        }
        try {
            validateNodeFast(root, new IdentityHashMap<Node, Boolean>());
        } catch (IllegalArgumentException malformedReference) {
            validateNodeDetailed(root,
                    new ArrayList<String>(),
                    new IdentityHashMap<Node, Boolean>());
            throw malformedReference;
        }
    }

    private static void validateNodeFast(Node node, IdentityHashMap<Node, Boolean> visited) {
        if (node == null) {
            return;
        }
        String blueId = node.getBlueId();
        String previousBlueId = node.getPreviousBlueId();
        Node type = node.getType();
        Node itemType = node.getItemType();
        Node keyType = node.getKeyType();
        Node valueType = node.getValueType();
        Node blue = node.getBlue();
        Node contracts = node.getContracts();
        List<Node> items = node.getItems();
        Map<String, Node> properties = node.getProperties();
        Schema schema = node.getSchema();
        if (blueId == null
                && previousBlueId == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && blue == null
                && contracts == null
                && items == null
                && properties == null
                && schema == null) {
            // Reference-free leaves cannot recurse, so recording their identity only adds
            // allocation and lookup cost to the common schema-free document case.
            return;
        }
        if (visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (blueId != null) {
            BlueIds.requireNoThisPlaceholderOutsideCyclicApi(blueId, "/blueId");
            BlueIds.requireBlueIdOrCyclicMember(blueId, "/blueId");
        }
        if (previousBlueId != null) {
            BlueIds.requirePlainBlueId(previousBlueId, "/$previous/blueId");
        }

        validateNodeFast(type, visited);
        validateNodeFast(itemType, visited);
        validateNodeFast(keyType, visited);
        validateNodeFast(valueType, visited);
        validateNodeFast(blue, visited);
        validateNodeFast(contracts, visited);

        if (items != null) {
            for (Node item : items) {
                validateNodeFast(item, visited);
            }
        }
        if (properties != null) {
            for (Node property : properties.values()) {
                validateNodeFast(property, visited);
            }
        }
        validateSchemaFast(schema, visited);
    }

    private static void validateSchemaFast(Schema schema, IdentityHashMap<Node, Boolean> visited) {
        if (schema == null) {
            return;
        }
        validateNodeFast(schema.getRequired(), visited);
        validateNodeFast(schema.getMinLength(), visited);
        validateNodeFast(schema.getMaxLength(), visited);
        validateNodeFast(schema.getMinimum(), visited);
        validateNodeFast(schema.getMaximum(), visited);
        validateNodeFast(schema.getExclusiveMinimum(), visited);
        validateNodeFast(schema.getExclusiveMaximum(), visited);
        validateNodeFast(schema.getMultipleOf(), visited);
        validateNodeFast(schema.getMinItems(), visited);
        validateNodeFast(schema.getMaxItems(), visited);
        validateNodeFast(schema.getUniqueItems(), visited);
        validateNodeFast(schema.getMinFields(), visited);
        validateNodeFast(schema.getMaxFields(), visited);
        if (schema.getEnum() != null) {
            for (Node enumValue : schema.getEnum()) {
                validateNodeFast(enumValue, visited);
            }
        }
    }

    private static void validateNodeDetailed(Node node,
                                             List<String> path,
                                             IdentityHashMap<Node, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node.getBlueId() != null) {
            String blueIdPath = childPath(path, "blueId");
            BlueIds.requireNoThisPlaceholderOutsideCyclicApi(node.getBlueId(), blueIdPath);
            BlueIds.requireBlueIdOrCyclicMember(node.getBlueId(), blueIdPath);
        }
        if (node.getPreviousBlueId() != null) {
            BlueIds.requirePlainBlueId(node.getPreviousBlueId(), childPath(path, "$previous", "blueId"));
        }

        validateChildDetailed(node.getType(), "type", path, visited);
        validateChildDetailed(node.getItemType(), "itemType", path, visited);
        validateChildDetailed(node.getKeyType(), "keyType", path, visited);
        validateChildDetailed(node.getValueType(), "valueType", path, visited);
        validateChildDetailed(node.getBlue(), "blue", path, visited);
        validateChildDetailed(node.getContracts(), "contracts", path, visited);

        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                validateChildDetailed(node.getItems().get(index), Integer.toString(index), path, visited);
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> property : node.getProperties().entrySet()) {
                validateChildDetailed(property.getValue(), property.getKey(), path, visited);
            }
        }
        if (node.getSchema() != null) {
            path.add("schema");
            validateSchemaDetailed(node.getSchema(), path, visited);
            path.remove(path.size() - 1);
        }
    }

    private static void validateSchemaDetailed(Schema schema,
                                               List<String> path,
                                               IdentityHashMap<Node, Boolean> visited) {
        validateChildDetailed(schema.getRequired(), "required", path, visited);
        validateChildDetailed(schema.getMinLength(), "minLength", path, visited);
        validateChildDetailed(schema.getMaxLength(), "maxLength", path, visited);
        validateChildDetailed(schema.getMinimum(), "minimum", path, visited);
        validateChildDetailed(schema.getMaximum(), "maximum", path, visited);
        validateChildDetailed(schema.getExclusiveMinimum(), "exclusiveMinimum", path, visited);
        validateChildDetailed(schema.getExclusiveMaximum(), "exclusiveMaximum", path, visited);
        validateChildDetailed(schema.getMultipleOf(), "multipleOf", path, visited);
        validateChildDetailed(schema.getMinItems(), "minItems", path, visited);
        validateChildDetailed(schema.getMaxItems(), "maxItems", path, visited);
        validateChildDetailed(schema.getUniqueItems(), "uniqueItems", path, visited);
        validateChildDetailed(schema.getMinFields(), "minFields", path, visited);
        validateChildDetailed(schema.getMaxFields(), "maxFields", path, visited);
        if (schema.getEnum() != null) {
            path.add("enum");
            for (int index = 0; index < schema.getEnum().size(); index++) {
                validateChildDetailed(schema.getEnum().get(index), Integer.toString(index), path, visited);
            }
            path.remove(path.size() - 1);
        }
    }

    private static void validateChildDetailed(Node child,
                                              String segment,
                                              List<String> path,
                                              IdentityHashMap<Node, Boolean> visited) {
        if (child == null) {
            return;
        }
        path.add(segment);
        validateNodeDetailed(child, path, visited);
        path.remove(path.size() - 1);
    }

    private static String childPath(List<String> path, String... childSegments) {
        int originalSize = path.size();
        for (String childSegment : childSegments) {
            path.add(childSegment);
        }
        String pointer = JsonPointer.toPointer(path);
        path.subList(originalSize, path.size()).clear();
        return pointer;
    }

}
