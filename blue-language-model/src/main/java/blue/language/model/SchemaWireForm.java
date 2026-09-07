package blue.language.model;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.value.BlueNumbers;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static blue.language.model.wire.SchemaPropertyConstants.*;

/** Deterministic model-owned projection of a schema to its wire map. */
public final class SchemaWireForm {

    private SchemaWireForm() {
    }

    /**
     * Projects a schema into its deterministic Blue wire map.
     *
     * <p>Plain scalar constraints remain scalars. Constraints with explicit
     * node metadata are projected through {@code nodeConverter}.</p>
     *
     * @param schema schema to project
     * @param nodeConverter converter for non-plain constraint nodes
     * @return insertion-ordered deterministic schema wire map
     * @throws NullPointerException if {@code schema} is {@code null}, or if a
     *                              required conversion is attempted with a
     *                              {@code null} {@code nodeConverter}
     * @throws IllegalArgumentException if a schema BlueId reference has
     *                                  sibling constraint keywords
     */
    public static Map<String, Object> get(
            Schema schema, Function<Node, Object> nodeConverter) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (schema.getBlueId() != null) {
            if (!schema.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "schema.blueId must be a pure reference without sibling keywords.");
            }
            result.put(BlueLanguageConstants.OBJECT_BLUE_ID,
                    schema.getBlueId());
            return result;
        }
        put(result, KEY_REQUIRED, scalarOrExplicitNode(
                schema.getRequired(), nodeConverter));
        put(result, KEY_MIN_LENGTH, scalarOrExplicitNode(
                schema.getMinLength(), nodeConverter));
        put(result, KEY_MAX_LENGTH, scalarOrExplicitNode(
                schema.getMaxLength(), nodeConverter));
        put(result, KEY_MINIMUM, scalarOrExplicitNode(
                schema.getMinimum(), nodeConverter));
        put(result, KEY_MAXIMUM, scalarOrExplicitNode(
                schema.getMaximum(), nodeConverter));
        put(result, KEY_EXCLUSIVE_MINIMUM, scalarOrExplicitNode(
                schema.getExclusiveMinimum(), nodeConverter));
        put(result, KEY_EXCLUSIVE_MAXIMUM, scalarOrExplicitNode(
                schema.getExclusiveMaximum(), nodeConverter));
        put(result, KEY_MULTIPLE_OF, scalarOrExplicitNode(
                schema.getMultipleOf(), nodeConverter));
        put(result, KEY_MIN_ITEMS, scalarOrExplicitNode(
                schema.getMinItems(), nodeConverter));
        put(result, KEY_MAX_ITEMS, scalarOrExplicitNode(
                schema.getMaxItems(), nodeConverter));
        put(result, KEY_UNIQUE_ITEMS, scalarOrExplicitNode(
                schema.getUniqueItems(), nodeConverter));
        put(result, KEY_MIN_FIELDS, scalarOrExplicitNode(
                schema.getMinFields(), nodeConverter));
        put(result, KEY_MAX_FIELDS, scalarOrExplicitNode(
                schema.getMaxFields(), nodeConverter));
        if (schema.getEnum() != null) {
            List<Object> values = new ArrayList<>(schema.getEnum().size());
            for (Node value : schema.getEnum()) {
                values.add(scalarOrExplicitNode(value, nodeConverter));
            }
            result.put(KEY_ENUM, values);
        }
        return result;
    }

    private static Object scalarOrExplicitNode(
            Node node, Function<Node, Object> nodeConverter) {
        return node == null
                ? null
                : isPlainScalar(node)
                ? node.getValue() : nodeConverter.apply(node);
    }

    private static boolean isPlainScalar(Node node) {
        return node != null
                && node.getValue() != null
                && isInteroperableScalar(node.getValue())
                && node.getName() == null
                && node.getDescription() == null
                && isImplicitScalarType(
                        node.getType(), node.getValue())
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

    private static boolean isInteroperableScalar(Object value) {
        if (!(value instanceof BigInteger)) {
            return true;
        }
        BigInteger integer = (BigInteger) value;
        // Large Integers require their explicit type when the wire value is
        // quoted; plain schema sugar would turn the constraint into Text.
        return integer.compareTo(BlueNumbers.MIN_INTEROPERABLE_INTEGER) >= 0
                && integer.compareTo(BlueNumbers.MAX_INTEROPERABLE_INTEGER) <= 0;
    }

    private static boolean isImplicitScalarType(
            Node type, Object value) {
        if (type == null) {
            return true;
        }
        if (!type.isReferenceOnly()) {
            return false;
        }
        String blueId = type.getBlueId();
        return value instanceof Boolean
                && BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID.equals(blueId)
                || value instanceof BigInteger
                && BlueLanguageConstants.INTEGER_TYPE_BLUE_ID.equals(blueId)
                || value instanceof BigDecimal
                && BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID.equals(blueId)
                || value instanceof String
                && BlueLanguageConstants.TEXT_TYPE_BLUE_ID.equals(blueId);
    }

    private static void put(
            Map<String, Object> result, String key, Object value) {
        if (value != null) {
            result.put(key, value);
        }
    }
}
