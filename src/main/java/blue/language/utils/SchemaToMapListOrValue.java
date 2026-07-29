package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static blue.language.utils.SchemaPropertyConstants.*;

/**
 * Projects a schema into its deterministic wire-map representation, delegating
 * nested node conversion to the caller.
 */
public final class SchemaToMapListOrValue {

    private SchemaToMapListOrValue() {
    }

    /**
     * Converts a schema without mutating it.
     *
     * @param schema schema to project
     * @param nodeConverter converter for nested non-scalar nodes
     * @return deterministic schema wire-map representation
     * @throws IllegalArgumentException if a schema reference has sibling
     *                                  keywords
     */
    public static Map<String, Object> get(Schema schema, Function<Node, Object> nodeConverter) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (schema.getBlueId() != null) {
            if (!schema.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "schema.blueId must be a pure reference without sibling keywords.");
            }
            result.put(Properties.OBJECT_BLUE_ID, schema.getBlueId());
            return result;
        }
        put(result, KEY_REQUIRED, schema.getRequired() == null ? null : schema.getRequiredValue());
        put(result, KEY_MIN_LENGTH, countValue(schema.getMinLength()));
        put(result, KEY_MAX_LENGTH, countValue(schema.getMaxLength()));
        put(result, KEY_MINIMUM, numericValue(schema.getMinimum(), nodeConverter));
        put(result, KEY_MAXIMUM, numericValue(schema.getMaximum(), nodeConverter));
        put(result, KEY_EXCLUSIVE_MINIMUM, numericValue(schema.getExclusiveMinimum(), nodeConverter));
        put(result, KEY_EXCLUSIVE_MAXIMUM, numericValue(schema.getExclusiveMaximum(), nodeConverter));
        put(result, KEY_MULTIPLE_OF, numericValue(schema.getMultipleOf(), nodeConverter));
        put(result, KEY_MIN_ITEMS, countValue(schema.getMinItems()));
        put(result, KEY_MAX_ITEMS, countValue(schema.getMaxItems()));
        put(result, KEY_UNIQUE_ITEMS,
                schema.getUniqueItems() == null ? null : schema.getUniqueItemsValue());
        put(result, KEY_MIN_FIELDS, countValue(schema.getMinFields()));
        put(result, KEY_MAX_FIELDS, countValue(schema.getMaxFields()));
        if (schema.getEnum() != null) {
            List<Object> values = new ArrayList<>(schema.getEnum().size());
            for (Node value : schema.getEnum()) {
                values.add(scalarOrExplicitNode(value, nodeConverter));
            }
            result.put(KEY_ENUM, values);
        }
        return result;
    }

    private static Object countValue(Node node) {
        return node == null ? null : node.getValue();
    }

    private static Object numericValue(Node node, Function<Node, Object> nodeConverter) {
        if (node == null) {
            return null;
        }
        return isPlainScalar(node) ? node.getValue() : nodeConverter.apply(node);
    }

    private static Object scalarOrExplicitNode(Node node, Function<Node, Object> nodeConverter) {
        return isPlainScalar(node) ? node.getValue() : nodeConverter.apply(node);
    }

    private static boolean isPlainScalar(Node node) {
        return node != null
                && node.getValue() != null
                && node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getItems() == null
                && node.getProperties() == null
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    private static void put(Map<String, Object> result, String key, Object value) {
        if (value != null) {
            result.put(key, value);
        }
    }
}
