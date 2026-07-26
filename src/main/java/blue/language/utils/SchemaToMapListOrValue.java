package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class SchemaToMapListOrValue {

    private SchemaToMapListOrValue() {
    }

    public static Map<String, Object> get(Schema schema, Function<Node, Object> nodeConverter) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (schema.getBlueId() != null) {
            if (!schema.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "schema.blueId must be a pure reference without sibling keywords.");
            }
            result.put("blueId", schema.getBlueId());
            return result;
        }
        put(result, "required", schema.getRequired() == null ? null : schema.getRequiredValue());
        put(result, "minLength", countValue(schema.getMinLength()));
        put(result, "maxLength", countValue(schema.getMaxLength()));
        put(result, "minimum", numericValue(schema.getMinimum(), nodeConverter));
        put(result, "maximum", numericValue(schema.getMaximum(), nodeConverter));
        put(result, "exclusiveMinimum", numericValue(schema.getExclusiveMinimum(), nodeConverter));
        put(result, "exclusiveMaximum", numericValue(schema.getExclusiveMaximum(), nodeConverter));
        put(result, "multipleOf", numericValue(schema.getMultipleOf(), nodeConverter));
        put(result, "minItems", countValue(schema.getMinItems()));
        put(result, "maxItems", countValue(schema.getMaxItems()));
        put(result, "uniqueItems", schema.getUniqueItems() == null ? null : schema.getUniqueItemsValue());
        put(result, "minFields", countValue(schema.getMinFields()));
        put(result, "maxFields", countValue(schema.getMaxFields()));
        if (schema.getEnum() != null) {
            List<Object> values = new ArrayList<>(schema.getEnum().size());
            for (Node value : schema.getEnum()) {
                values.add(scalarOrExplicitNode(value, nodeConverter));
            }
            result.put("enum", values);
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
