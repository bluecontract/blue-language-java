package blue.language.utils;

import blue.language.model.Node;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.utils.NodeToMapListOrValue.Strategy.*;
import static blue.language.utils.Properties.*;

/**
 * Converts mutable Blue nodes to their map/list/scalar wire representation.
 *
 * <p>This compatibility conversion validates payload exclusivity but does not
 * provide the strict identity validation performed by
 * {@link NodeToBlueIdInput}.</p>
 */
public class NodeToMapListOrValue {

    /**
     * Creates a mutable-node wire-projection helper.
     */
    public NodeToMapListOrValue() {
    }

    /** Controls whether scalar/list sugar is preserved in the result. */
    public enum Strategy {
        /** Emits the complete normalized node representation. */
        OFFICIAL,
        /** Returns bare scalar or list payloads when possible. */
        SIMPLE
    }

    /**
     * Converts using the official normalized representation.
     *
     * @param node node to convert
     * @return map, list, or scalar wire representation
     */
    public static Object get(Node node) {
        return get(node, OFFICIAL);
    }

    /**
     * Converts using the requested representation strategy.
     *
     * @param node node to convert
     * @param strategy representation strategy
     * @return map, list, or scalar wire representation
     */
    public static Object get(Node node, Strategy strategy) {
        validatePayloadKind(node);

        if (Nodes.isEmptyPlaceholder(node)) {
            Map<String, Object> placeholder = new LinkedHashMap<>();
            placeholder.put(LIST_CONTROL_EMPTY, true);
            return placeholder;
        }

        if (node.isReferenceOnly()) {
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put(OBJECT_BLUE_ID, node.getBlueId());
            return reference;
        }

        if (node.getPreviousBlueId() != null) {
            Map<String, Object> previous = new LinkedHashMap<>();
            previous.put(OBJECT_BLUE_ID, node.getPreviousBlueId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(LIST_CONTROL_PREVIOUS, previous);
            return result;
        }

        Object value = node.getValue();

        if (value != null && strategy == SIMPLE)
            return value;

        List<Object> items = node.getItems() == null ? null :
                node.getItems().stream()
                        .map(item -> get(item, strategy))
                        .collect(Collectors.toList());
        if (items != null && strategy == SIMPLE)
            return items;

        Map<String, Object> result = new LinkedHashMap<>();
        if (node.getName() != null)
            result.put(OBJECT_NAME, node.getName());
        if (node.getDescription() != null)
            result.put(OBJECT_DESCRIPTION, node.getDescription());

        String valueTypeBlueId = null;
        if (strategy == OFFICIAL && value != null && node.getType() == null) {
            String inferredTypeBlueId = inferTypeBlueId(value);
            if (inferredTypeBlueId != null) {
                valueTypeBlueId = inferredTypeBlueId;
                Map<String, String> map = new LinkedHashMap<>();
                map.put(OBJECT_BLUE_ID, inferredTypeBlueId);
                result.put(OBJECT_TYPE, map);
            }
        } else if (node.getType() != null) {
            valueTypeBlueId = node.getType().getBlueId();
            result.put(OBJECT_TYPE, get(node.getType()));
        }

        if (node.getItemType() != null)
            result.put(OBJECT_ITEM_TYPE, get(node.getItemType()));
        if (node.getKeyType() != null)
            result.put(OBJECT_KEY_TYPE, get(node.getKeyType()));
        if (node.getValueType() != null)
            result.put(OBJECT_VALUE_TYPE, get(node.getValueType()));
        if (node.getMergePolicy() != null)
            result.put(OBJECT_MERGE_POLICY, node.getMergePolicy());
        if (node.getPosition() != null)
            result.put(LIST_CONTROL_POS, BigInteger.valueOf(node.getPosition()));
        if (value != null)
            result.put(OBJECT_VALUE, handleValue(value, valueTypeBlueId));
        if (items != null)
            result.put(OBJECT_ITEMS, items);
        if (node.getSchema() != null)
            result.put(OBJECT_SCHEMA, SchemaToMapListOrValue.get(node.getSchema(), child -> get(child, strategy)));
        if (node.getContracts() != null)
            result.put(OBJECT_CONTRACTS, get(node.getContracts(), strategy));
        if (node.getBlue() != null)
            result.put(OBJECT_BLUE, get(node.getBlue(), strategy));
        if (node.getProperties() != null) {
            node.getProperties().forEach((key, propertyValue) -> {
                if (OBJECT_VALUE.equals(key)
                        && node.isPreprocessingTransformationConfiguration()
                        && node.getType() != null
                        && node.getType().isReferenceOnly()) {
                    result.put(key, get(
                            propertyValue,
                            propertyValue.isInlineValue()
                                    ? SIMPLE : OFFICIAL));
                } else {
                    result.put(key, get(propertyValue, strategy));
                }
            });
        }
        return result;
    }

    private static void validatePayloadKind(Node node) {
        int payloadKinds = 0;
        if (node.getValue() != null) payloadKinds++;
        if (node.getItems() != null) payloadKinds++;
        if (node.getProperties() != null && !node.getProperties().isEmpty()) payloadKinds++;
        if (payloadKinds > 1) {
            throw new IllegalArgumentException("A Blue node may contain only one payload kind: value, items, or object fields.");
        }
        if (node.getPreviousBlueId() != null && (payloadKinds > 0
                || node.getName() != null
                || node.getDescription() != null
                || node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPosition() != null
                || node.getBlue() != null
                || node.getContracts() != null
                || node.getBlueId() != null)) {
            throw new IllegalArgumentException("\"$previous\" list anchors must be single-key list items.");
        }
        if (node.getPosition() != null && payloadKinds == 0
                && node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getBlue() == null
                && node.getBlueId() == null) {
            throw new IllegalArgumentException("\"$pos\" items must contain an overlay.");
        }
    }

    private static Object handleValue(Object value, String valueTypeBlueId) {
        if (DOUBLE_TYPE_BLUE_ID.equals(valueTypeBlueId)) {
            return BlueNumbers.toCanonicalDoubleValue(value);
        }
        if (value instanceof BigInteger) {
            BigInteger bigIntValue = (BigInteger) value;
            if (bigIntValue.compareTo(BlueNumbers.MIN_INTEROPERABLE_INTEGER) < 0
                    || bigIntValue.compareTo(BlueNumbers.MAX_INTEROPERABLE_INTEGER) > 0) {
                return bigIntValue.toString();
            }
        }
        return value;
    }

    private static String inferTypeBlueId(Object value) {
        if (value instanceof String) {
            return TEXT_TYPE_BLUE_ID;
        } else if (value instanceof BigInteger) {
            return INTEGER_TYPE_BLUE_ID;
        } else if (value instanceof BigDecimal) {
            return DOUBLE_TYPE_BLUE_ID;
        } else if (value instanceof Boolean) {
            return BOOLEAN_TYPE_BLUE_ID;
        }
        return null;
    }

}
