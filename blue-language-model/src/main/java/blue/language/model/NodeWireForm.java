package blue.language.model;

import blue.language.model.value.BlueNumbers;
import blue.language.model.wire.JsonPointer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.*;
import static blue.language.model.NodeWireForm.Strategy.OFFICIAL;
import static blue.language.model.NodeWireForm.Strategy.SIMPLE;

/** Model-owned conversion from mutable nodes to Blue wire values. */
public final class NodeWireForm {

    /** Selects the wire projection applied to node payloads. */
    public enum Strategy {
        /**
         * Emits the normative Blue object form, including inferred scalar
         * type metadata where required.
         */
        OFFICIAL,
        /**
         * Projects scalar and list payloads directly into compact wire values.
         */
        SIMPLE
    }

    private NodeWireForm() {
    }

    /**
     * Projects a node using the normative Blue wire strategy.
     *
     * @param node node to project
     * @return deterministic Blue wire scalar, list, or object map
     * @throws NullPointerException if {@code node} is {@code null}
     * @throws IllegalArgumentException if the node combines incompatible
     *                                  payload kinds or has invalid list control
     */
    public static Object get(Node node) {
        return get(node, OFFICIAL);
    }

    /**
     * Projects a node using the selected wire strategy.
     *
     * @param node node to project
     * @param strategy wire projection strategy
     * @return deterministic Blue wire scalar, list, or object map
     * @throws NullPointerException if {@code node} is {@code null}
     * @throws IllegalArgumentException if the node combines incompatible
     *                                  payload kinds or has invalid list control
     */
    public static Object get(Node node, Strategy strategy) {
        return get(node, strategy, JsonPointer.ROOT);
    }

    private static Object get(
            Node node,
            Strategy strategy,
            String path) {
        validatePayloadKind(node);

        if (Nodes.isSourceNullLiteral(node)) {
            return null;
        }
        if (Nodes.isBareFieldlessBuilder(node)) {
            throw bareFieldlessBuilder(path);
        }

        if (isEmptyPlaceholder(node)) {
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
        if (value != null && strategy == SIMPLE) {
            return value;
        }
        List<Object> items = null;
        if (node.getItems() != null) {
            items = new ArrayList<>(node.getItems().size());
            for (int index = 0; index < node.getItems().size(); index++) {
                items.add(get(
                        node.getItems().get(index),
                        strategy,
                        appendPath(
                                appendPath(path, OBJECT_ITEMS),
                                String.valueOf(index))));
            }
        }
        if (items != null && strategy == SIMPLE) {
            return items;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (node.getName() != null) {
            result.put(OBJECT_NAME, node.getName());
        }
        if (node.getDescription() != null) {
            result.put(OBJECT_DESCRIPTION, node.getDescription());
        }

        String valueTypeBlueId = null;
        if (strategy == OFFICIAL && value != null && node.getType() == null) {
            String inferredTypeBlueId = inferTypeBlueId(value);
            if (inferredTypeBlueId != null) {
                valueTypeBlueId = inferredTypeBlueId;
                Map<String, String> type = new LinkedHashMap<>();
                type.put(OBJECT_BLUE_ID, inferredTypeBlueId);
                result.put(OBJECT_TYPE, type);
            }
        } else if (node.getType() != null) {
            valueTypeBlueId = node.getType().getBlueId();
            result.put(OBJECT_TYPE, get(
                    node.getType(),
                    OFFICIAL,
                    appendPath(path, OBJECT_TYPE)));
        }
        if (node.getItemType() != null) {
            result.put(OBJECT_ITEM_TYPE, get(
                    node.getItemType(),
                    OFFICIAL,
                    appendPath(path, OBJECT_ITEM_TYPE)));
        }
        if (node.getKeyType() != null) {
            result.put(OBJECT_KEY_TYPE, get(
                    node.getKeyType(),
                    OFFICIAL,
                    appendPath(path, OBJECT_KEY_TYPE)));
        }
        if (node.getValueType() != null) {
            result.put(OBJECT_VALUE_TYPE, get(
                    node.getValueType(),
                    OFFICIAL,
                    appendPath(path, OBJECT_VALUE_TYPE)));
        }
        if (node.getMergePolicy() != null) {
            result.put(OBJECT_MERGE_POLICY, node.getMergePolicy());
        }
        if (node.getPosition() != null) {
            result.put(LIST_CONTROL_POS,
                    BigInteger.valueOf(node.getPosition()));
        }
        if (value != null) {
            result.put(OBJECT_VALUE, handleValue(value, valueTypeBlueId));
        }
        if (items != null) {
            result.put(OBJECT_ITEMS, items);
        }
        if (node.getSchema() != null) {
            result.put(OBJECT_SCHEMA,
                    SchemaWireForm.get(node.getSchema(),
                            child -> get(
                                    child,
                                    strategy,
                                    appendPath(path, OBJECT_SCHEMA))));
        }
        if (node.getContracts() != null) {
            result.put(OBJECT_CONTRACTS,
                    get(node.getContracts(), strategy,
                            appendPath(path, OBJECT_CONTRACTS)));
        }
        if (node.getBlue() != null) {
            result.put(OBJECT_BLUE, get(node.getBlue(), strategy,
                    appendPath(path, OBJECT_BLUE)));
        }
        if (node.getProperties() != null) {
            node.getProperties().forEach((key, propertyValue) -> {
                if (OBJECT_VALUE.equals(key)
                        && node.isPreprocessingTransformationConfiguration()
                        && node.getType() != null
                        && node.getType().isReferenceOnly()) {
                    result.put(key, get(
                            propertyValue,
                            propertyValue.isInlineValue()
                                    ? SIMPLE : OFFICIAL,
                            appendPath(path, key)));
                } else {
                    result.put(key, get(
                            propertyValue,
                            strategy,
                            appendPath(path, key)));
                }
            });
        }
        return result;
    }

    private static IllegalArgumentException bareFieldlessBuilder(
            String path) {
        return new IllegalArgumentException(
                "Fieldless Node is an incomplete builder, not semantic Blue "
                        + "content. Use Nodes.emptyObject() for {} or omit the "
                        + "field for absence. Path: " + path);
    }

    private static String appendPath(String path, String segment) {
        return JsonPointer.ROOT.equals(path)
                ? JsonPointer.ROOT + JsonPointer.escape(segment)
                : path + JsonPointer.ROOT + JsonPointer.escape(segment);
    }

    private static boolean isEmptyPlaceholder(Node node) {
        if (node == null || node.getProperties() == null
                || node.getProperties().size() != 1) {
            return false;
        }
        Node marker = node.getProperties().get(LIST_CONTROL_EMPTY);
        return marker != null
                && Boolean.TRUE.equals(marker.getValue())
                && hasNoMetadataOrStructure(marker, true)
                && hasNoMetadataOrStructure(node, false);
    }

    private static boolean hasNoMetadataOrStructure(
            Node node, boolean allowValue) {
        return node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && (allowValue || node.getValue() == null)
                && node.getItems() == null
                && (allowValue
                        ? node.getProperties() == null
                        : node.getProperties() != null)
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    private static void validatePayloadKind(Node node) {
        int payloadKinds = 0;
        if (node.getValue() != null) payloadKinds++;
        if (node.getItems() != null) payloadKinds++;
        if (Nodes.hasObjectPayload(node)) payloadKinds++;
        if (payloadKinds > 1) {
            throw new IllegalArgumentException(
                    "A Blue node may contain only one payload kind: value, items, or object fields.");
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
            throw new IllegalArgumentException(
                    "\"$previous\" list anchors must be single-key list items.");
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
            throw new IllegalArgumentException(
                    "\"$pos\" items must contain an overlay.");
        }
    }

    private static Object handleValue(
            Object value, String valueTypeBlueId) {
        if (DOUBLE_TYPE_BLUE_ID.equals(valueTypeBlueId)) {
            return BlueNumbers.toCanonicalDoubleValue(value);
        }
        if (value instanceof BigInteger) {
            BigInteger integer = (BigInteger) value;
            if (integer.compareTo(BlueNumbers.MIN_INTEROPERABLE_INTEGER) < 0
                    || integer.compareTo(
                            BlueNumbers.MAX_INTEROPERABLE_INTEGER) > 0) {
                return integer.toString();
            }
        }
        return value;
    }

    private static String inferTypeBlueId(Object value) {
        if (value instanceof String) return TEXT_TYPE_BLUE_ID;
        if (value instanceof BigInteger) return INTEGER_TYPE_BLUE_ID;
        if (value instanceof BigDecimal) return DOUBLE_TYPE_BLUE_ID;
        if (value instanceof Boolean) return BOOLEAN_TYPE_BLUE_ID;
        return null;
    }
}
