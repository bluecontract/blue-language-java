package blue.language.provider;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.BlueIds;
import blue.language.utils.Properties;

import java.lang.reflect.Array;
import java.util.IdentityHashMap;
import java.util.Map;

import static blue.language.provider.ExactFragmentSupport.pointerPath;
import static blue.language.provider.ExactFragmentSupport.requireFinalReference;
import static blue.language.utils.SchemaPropertyConstants.KEY_ENUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_FIELDS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_ITEMS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_LENGTH;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAXIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_FIELDS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_ITEMS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_LENGTH;
import static blue.language.utils.SchemaPropertyConstants.KEY_MINIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MULTIPLE_OF;
import static blue.language.utils.SchemaPropertyConstants.KEY_REQUIRED;
import static blue.language.utils.SchemaPropertyConstants.KEY_UNIQUE_ITEMS;

/**
 * Validates the ordinary acyclic graph boundary accepted by exact fragment
 * assembly.
 */
final class ExactFragmentGraphValidator {

    private final IdentityHashMap<Node, String> activeNodes =
            new IdentityHashMap<>();
    private final IdentityHashMap<Node, Boolean> completeNodes =
            new IdentityHashMap<>();
    private final IdentityHashMap<Object, String> activeValues =
            new IdentityHashMap<>();
    private final IdentityHashMap<Object, Boolean> completeValues =
            new IdentityHashMap<>();

    /** Validates one root or nested semantic node. */
    void validate(Node node, String path) {
        if (node == null || completeNodes.containsKey(node)) {
            return;
        }
        String activePath = activeNodes.put(node, path);
        if (activePath != null) {
            throw new IllegalArgumentException(
                    "Mixed reference/object cycle or Blue object cycle "
                            + "between " + activePath + " and " + path
                            + " cannot be fragmented.");
        }
        try {
            if (node.getBlueId() != null) {
                requireFinalReference(
                        node.getBlueId(),
                        pointerPath(path, Properties.OBJECT_BLUE_ID));
                if (!node.isReferenceOnly()) {
                    throw new IllegalArgumentException(
                            "Mixed reference/object content at " + path
                                    + ": a BlueId reference must be pure, "
                                    + "and a node's own BlueId must not "
                                    + "appear in its content.");
                }
                return;
            }

            validate(node.getType(),
                    pointerPath(path, Properties.OBJECT_TYPE));
            validate(node.getItemType(),
                    pointerPath(path, Properties.OBJECT_ITEM_TYPE));
            validate(node.getKeyType(),
                    pointerPath(path, Properties.OBJECT_KEY_TYPE));
            validate(node.getValueType(),
                    pointerPath(path, Properties.OBJECT_VALUE_TYPE));
            validate(node.getContracts(),
                    pointerPath(path, Properties.OBJECT_CONTRACTS));
            validate(node.getBlue(),
                    pointerPath(path, Properties.OBJECT_BLUE));
            validateItems(node, path);
            validateProperties(node, path);
            validate(node.getSchema(),
                    pointerPath(path, Properties.OBJECT_SCHEMA));
            validateValue(node.getRawValue(),
                    pointerPath(path, Properties.OBJECT_VALUE));
            if (node.getPreviousBlueId() != null) {
                BlueIds.requirePlainBlueId(
                        node.getPreviousBlueId(),
                        pointerPath(
                                pointerPath(
                                        path,
                                        Properties.LIST_CONTROL_PREVIOUS),
                                Properties.OBJECT_BLUE_ID));
            }
        } finally {
            activeNodes.remove(node);
            completeNodes.put(node, Boolean.TRUE);
        }
    }

    private void validateItems(Node node, String path) {
        if (node.getItems() == null) {
            return;
        }
        for (int index = 0; index < node.getItems().size(); index++) {
            validate(
                    node.getItems().get(index),
                    pointerPath(
                            pointerPath(path, Properties.OBJECT_ITEMS),
                            String.valueOf(index)));
        }
    }

    private void validateProperties(Node node, String path) {
        if (node.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Node> property
                : node.getProperties().entrySet()) {
            validate(
                    property.getValue(),
                    pointerPath(path, property.getKey()));
        }
    }

    private void validate(Schema schema, String path) {
        if (schema == null) {
            return;
        }
        if (schema.getBlueId() != null) {
            requireFinalReference(
                    schema.getBlueId(),
                    pointerPath(path, Properties.OBJECT_BLUE_ID));
            if (!schema.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Mixed reference/object schema at " + path
                                + ": a schema BlueId reference must be pure.");
            }
            return;
        }
        validate(schema.getRequired(), pointerPath(path, KEY_REQUIRED));
        validate(schema.getMinLength(), pointerPath(path, KEY_MIN_LENGTH));
        validate(schema.getMaxLength(), pointerPath(path, KEY_MAX_LENGTH));
        validate(schema.getMinimum(), pointerPath(path, KEY_MINIMUM));
        validate(schema.getMaximum(), pointerPath(path, KEY_MAXIMUM));
        validate(schema.getExclusiveMinimum(),
                pointerPath(path, KEY_EXCLUSIVE_MINIMUM));
        validate(schema.getExclusiveMaximum(),
                pointerPath(path, KEY_EXCLUSIVE_MAXIMUM));
        validate(schema.getMultipleOf(),
                pointerPath(path, KEY_MULTIPLE_OF));
        validate(schema.getMinItems(), pointerPath(path, KEY_MIN_ITEMS));
        validate(schema.getMaxItems(), pointerPath(path, KEY_MAX_ITEMS));
        validate(schema.getUniqueItems(),
                pointerPath(path, KEY_UNIQUE_ITEMS));
        validate(schema.getMinFields(), pointerPath(path, KEY_MIN_FIELDS));
        validate(schema.getMaxFields(), pointerPath(path, KEY_MAX_FIELDS));
        if (schema.getEnum() != null) {
            for (int index = 0; index < schema.getEnum().size(); index++) {
                validate(
                        schema.getEnum().get(index),
                        pointerPath(
                                pointerPath(path, KEY_ENUM),
                                String.valueOf(index)));
            }
        }
    }

    private void validateValue(Object value, String path) {
        if (value == null || value instanceof String
                || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum) {
            return;
        }
        if (value instanceof Node || value instanceof Schema) {
            throw new IllegalArgumentException(
                    "Node and Schema objects are not scalar value content at "
                            + path + ".");
        }
        boolean traversable = value instanceof Map
                || value instanceof Iterable
                || value.getClass().isArray();
        if (!traversable || completeValues.containsKey(value)) {
            return;
        }
        String activePath = activeValues.put(value, path);
        if (activePath != null) {
            throw new IllegalArgumentException(
                    "Cyclic value content between " + activePath + " and "
                            + path + " cannot be fragmented.");
        }
        try {
            validateCompositeValue(value, path);
        } finally {
            activeValues.remove(value);
            completeValues.put(value, Boolean.TRUE);
        }
    }

    private void validateCompositeValue(Object value, String path) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                validateValue(
                        entry.getValue(),
                        pointerPath(path, String.valueOf(entry.getKey())));
            }
            return;
        }
        if (value instanceof Iterable) {
            int index = 0;
            for (Object item : (Iterable<?>) value) {
                validateValue(
                        item,
                        pointerPath(path, String.valueOf(index)));
                index++;
            }
            return;
        }
        int length = Array.getLength(value);
        for (int index = 0; index < length; index++) {
            validateValue(
                    Array.get(value, index),
                    pointerPath(path, String.valueOf(index)));
        }
    }
}
