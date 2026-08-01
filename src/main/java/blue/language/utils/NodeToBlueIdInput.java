package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.utils.Properties.*;
import static blue.language.utils.SchemaPropertyConstants.*;

/**
 * Projects mutable nodes into strict canonical BlueId identity input.
 *
 * <p>The conversion validates reference syntax, mutually exclusive payload
 * kinds, metadata positions, list controls, scalar types, schemas, and
 * canonical number rules. It does not mutate the supplied graph unless the
 * explicit metadata-stripping helper is called.</p>
 */
public final class NodeToBlueIdInput {

    private NodeToBlueIdInput() {
    }

    /**
     * Returns strict canonical identity input for a root node.
     *
     * @param node root node to project
     * @return canonical map, list, or scalar identity input
     */
    public static Object get(Node node) {
        return get(node, JsonPointer.ROOT, Context.ROOT, -1, false);
    }

    /**
     * Returns strict identity input while accepting invocation-local cyclic placeholders.
     *
     * @param node root node to project
     * @return canonical map, list, or scalar identity input
     */
    public static Object getAllowingCyclicPlaceholders(Node node) {
        return get(node, JsonPointer.ROOT, Context.ROOT, -1, true);
    }

    /**
     * Projects one node using list-element validation rules.
     *
     * @param node list element
     * @param index zero-based list position
     * @return canonical element identity input
     */
    public static Object getListElement(Node node, int index) {
        return get(
                node,
                JsonPointer.ROOT + index,
                Context.LIST_ELEMENT,
                index,
                false);
    }

    /**
     * Projects one cyclic-set member using list-element validation rules.
     *
     * @param node list element
     * @param index zero-based list position
     * @return canonical element identity input
     */
    public static Object getListElementAllowingCyclicPlaceholders(Node node, int index) {
        return get(
                node,
                JsonPointer.ROOT + index,
                Context.LIST_ELEMENT,
                index,
                true);
    }

    /**
     * Returns strict identity input after excluding non-reference BlueId
     * metadata from a defensive clone.
     *
     * @param node root node to clone and project
     * @return canonical identity input without expanded-content BlueId metadata
     */
    public static Object getWithResolvedBlueIdMetadata(Node node) {
        return get(
                stripResolvedBlueIdMetadata(node.clone()),
                JsonPointer.ROOT,
                Context.ROOT,
                -1,
                false);
    }

    /**
     * Recursively removes BlueIds that annotate expanded content.
     *
     * <p>The supplied graph is mutated and returned; pure references are
     * preserved.</p>
     *
     * @param node mutable graph root, or {@code null}
     * @return the supplied graph after metadata removal, or {@code null}
     */
    public static Node stripResolvedBlueIdMetadata(Node node) {
        if (node == null) {
            return null;
        }
        if (node.getBlueId() != null && !node.isReferenceOnly()) {
            node.blueId(null);
        }
        stripResolvedBlueIdMetadata(node.getType());
        stripResolvedBlueIdMetadata(node.getItemType());
        stripResolvedBlueIdMetadata(node.getKeyType());
        stripResolvedBlueIdMetadata(node.getValueType());
        stripResolvedBlueIdMetadata(node.getBlue());
        stripResolvedBlueIdMetadata(node.getContracts());
        if (node.getItems() != null) {
            node.getItems().forEach(NodeToBlueIdInput::stripResolvedBlueIdMetadata);
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(NodeToBlueIdInput::stripResolvedBlueIdMetadata);
        }
        stripResolvedBlueIdMetadata(node.getSchema());
        return node;
    }

    private static void stripResolvedBlueIdMetadata(Schema schema) {
        if (schema == null) {
            return;
        }
        stripResolvedBlueIdMetadata(schema.getRequired());
        stripResolvedBlueIdMetadata(schema.getMinLength());
        stripResolvedBlueIdMetadata(schema.getMaxLength());
        stripResolvedBlueIdMetadata(schema.getMinimum());
        stripResolvedBlueIdMetadata(schema.getMaximum());
        stripResolvedBlueIdMetadata(schema.getExclusiveMinimum());
        stripResolvedBlueIdMetadata(schema.getExclusiveMaximum());
        stripResolvedBlueIdMetadata(schema.getMultipleOf());
        stripResolvedBlueIdMetadata(schema.getMinItems());
        stripResolvedBlueIdMetadata(schema.getMaxItems());
        stripResolvedBlueIdMetadata(schema.getUniqueItems());
        stripResolvedBlueIdMetadata(schema.getMinFields());
        stripResolvedBlueIdMetadata(schema.getMaxFields());
        if (schema.getEnum() != null) {
            schema.getEnum().forEach(NodeToBlueIdInput::stripResolvedBlueIdMetadata);
        }
    }

    private enum Context {
        ROOT,
        OBJECT_FIELD,
        LIST_ELEMENT,
        METADATA
    }

    private static Object get(Node node, String path, Context context, int listIndex, boolean allowCyclicPlaceholders) {
        validateBlueIdInput(node, path, context, listIndex);

        if (context == Context.LIST_ELEMENT && Nodes.isEmptyPlaceholder(node)) {
            Map<String, Object> placeholder = new LinkedHashMap<>();
            placeholder.put(LIST_CONTROL_EMPTY, true);
            return placeholder;
        }

        if (node.isReferenceOnly()) {
            String blueId = validateReferenceBlueId(node.getBlueId(), appendPath(path, OBJECT_BLUE_ID), allowCyclicPlaceholders);
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put(OBJECT_BLUE_ID, blueId);
            return reference;
        }

        if (node.getPreviousBlueId() != null) {
            String previousBlueId = BlueIds.requirePlainBlueId(
                    node.getPreviousBlueId(),
                    appendPath(appendPath(path, LIST_CONTROL_PREVIOUS), OBJECT_BLUE_ID));
            Map<String, Object> previous = new LinkedHashMap<>();
            previous.put(OBJECT_BLUE_ID, previousBlueId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(LIST_CONTROL_PREVIOUS, previous);
            return result;
        }

        Object value = node.getValue();
        List<Object> items = null;
        if (node.getItems() != null) {
            items = new ArrayList<>(node.getItems().size());
            for (int i = 0; i < node.getItems().size(); i++) {
                items.add(get(node.getItems().get(i), appendPath(path, OBJECT_ITEMS, i), Context.LIST_ELEMENT, i, allowCyclicPlaceholders));
            }
        }

        if (items != null && isPayloadOnlyList(node)) {
            return items;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (node.getName() != null)
            result.put(OBJECT_NAME, node.getName());
        if (node.getDescription() != null)
            result.put(OBJECT_DESCRIPTION, node.getDescription());

        String valueTypeBlueId = null;
        if (value != null && node.getType() == null) {
            String inferredTypeBlueId = inferTypeBlueId(value);
            if (inferredTypeBlueId != null) {
                valueTypeBlueId = inferredTypeBlueId;
                Map<String, String> map = new LinkedHashMap<>();
                map.put(OBJECT_BLUE_ID, inferredTypeBlueId);
                result.put(OBJECT_TYPE, map);
            }
        } else if (node.getType() != null) {
            valueTypeBlueId = node.getType().getBlueId();
            result.put(OBJECT_TYPE, get(node.getType(), appendPath(path, OBJECT_TYPE), Context.METADATA, -1, allowCyclicPlaceholders));
        }

        if (node.getItemType() != null)
            result.put(OBJECT_ITEM_TYPE, get(node.getItemType(), appendPath(path, OBJECT_ITEM_TYPE), Context.METADATA, -1, allowCyclicPlaceholders));
        if (node.getKeyType() != null)
            result.put(OBJECT_KEY_TYPE, get(node.getKeyType(), appendPath(path, OBJECT_KEY_TYPE), Context.METADATA, -1, allowCyclicPlaceholders));
        if (node.getValueType() != null)
            result.put(OBJECT_VALUE_TYPE, get(node.getValueType(), appendPath(path, OBJECT_VALUE_TYPE), Context.METADATA, -1, allowCyclicPlaceholders));
        if (node.getMergePolicy() != null)
            result.put(OBJECT_MERGE_POLICY, node.getMergePolicy());
        if (value != null)
            result.put(OBJECT_VALUE, handleValue(value, valueTypeBlueId));
        if (items != null)
            result.put(OBJECT_ITEMS, items);
        if (node.getSchema() != null) {
            validateSchemaNodes(node.getSchema(), appendPath(path, OBJECT_SCHEMA));
            Schema identitySchema = node.getSchema().clone();
            if (identitySchema.getEnum() != null) {
                identitySchema.enumValues(
                        SchemaEnumCanonicalizer.canonicalize(
                                identitySchema.getEnum()));
            }
            result.put(OBJECT_SCHEMA, SchemaToMapListOrValue.get(
                    identitySchema,
                    child -> get(child, appendPath(path, OBJECT_SCHEMA), Context.METADATA, -1, allowCyclicPlaceholders)));
        }
        if (node.getContracts() != null) {
            result.put(OBJECT_CONTRACTS, get(node.getContracts(), appendPath(path, OBJECT_CONTRACTS), Context.METADATA, -1, allowCyclicPlaceholders));
        }
        if (node.getProperties() != null) {
            node.getProperties().forEach((key, propertyValue) -> {
                if (isTransformationConfigurationValue(
                        node, key)) {
                    result.put(key,
                            transformationConfigurationValue(
                                    propertyValue));
                } else {
                    result.put(key, get(
                            propertyValue,
                            appendPath(path, key),
                            Context.OBJECT_FIELD,
                            -1,
                            allowCyclicPlaceholders));
                }
            });
        }
        return result;
    }

    private static boolean isTransformationConfigurationValue(
            Node node,
            String key) {
        return OBJECT_VALUE.equals(key)
                && node.isPreprocessingTransformationConfiguration()
                && node.getType() != null
                && node.getType().isReferenceOnly();
    }

    private static Object transformationConfigurationValue(
            Node value) {
        return NodeToMapListOrValue.get(
                value,
                value.isInlineValue()
                        ? NodeToMapListOrValue.Strategy.SIMPLE
                        : NodeToMapListOrValue.Strategy.OFFICIAL);
    }

    private static boolean isPayloadOnlyList(Node node) {
        return node.getItems() != null
                && node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getValue() == null
                && node.getProperties() == null
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    private static String validateReferenceBlueId(String blueId, String path, boolean allowCyclicPlaceholders) {
        if (allowCyclicPlaceholders && BlueIds.isCyclicCalculationPlaceholder(blueId)) {
            return blueId;
        }
        return BlueIds.requireBlueIdOrCyclicMember(
                BlueIds.requireNoThisPlaceholderOutsideCyclicApi(blueId, path),
                path);
    }

    private static void validateBlueIdInput(Node node, String path, Context context, int listIndex) {
        if (node == null) {
            throw new IllegalArgumentException("BlueId input must not contain null nodes. Path: " + path);
        }
        if (context == Context.METADATA && isTypePosition(path) && node.isInlineValue()) {
            throw new IllegalArgumentException("Direct BlueId input must not contain unresolved type aliases. Path: " + path);
        }
        if (node.getBlue() != null) {
            throw new IllegalArgumentException(
                    "\"blue\" is a preprocessing directive and must not be present in BlueId input. " +
                            "Call preprocess/canonicalize/calculateSourceDocumentBlueId first. Path: " + path);
        }
        if (node.getPosition() != null) {
            throw new IllegalArgumentException("\"$pos\" overlays are not valid direct BlueId input. Path: " + path);
        }
        if (node.getProperties() != null && node.getProperties().containsKey(LIST_CONTROL_REPLACE)) {
            throw new IllegalArgumentException("\"$replace\" overlays are not valid direct BlueId input. Path: " + path);
        }
        if (context == Context.LIST_ELEMENT) {
            if (Nodes.isEmptyNode(node)) {
                throw new IllegalArgumentException("Direct BlueId input must use { \"$empty\": true } for empty list placeholders. Path: " + path);
            }
            if (node.getProperties() != null && node.getProperties().containsKey(LIST_CONTROL_EMPTY)) {
                Nodes.validateEmptyPlaceholder(node, path);
            }
            if (node.getPreviousBlueId() != null && listIndex != 0) {
                throw new IllegalArgumentException("\"$previous\" must appear only as the first list item. Path: " + path);
            }
        } else if (node.getPreviousBlueId() != null) {
            throw new IllegalArgumentException("\"$previous\" is valid only as the first list item in direct BlueId input. Path: " + path);
        }
        validatePayloadKind(node, path);
    }

    private static void validatePayloadKind(Node node, String path) {
        int payloadKinds = 0;
        if (node.getValue() != null) payloadKinds++;
        if (node.getItems() != null) payloadKinds++;
        if (node.getProperties() != null && !node.getProperties().isEmpty()) payloadKinds++;
        if (payloadKinds > 1) {
            throw new IllegalArgumentException("A Blue node may contain only one payload kind: value, items, or object fields. Path: " + path);
        }
        if (node.getBlueId() != null && !node.isReferenceOnly()) {
            throw new IllegalArgumentException("\"blueId\" nodes must be reference-only and cannot contain sibling fields. Path: " + path);
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
                || node.getContracts() != null
                || node.getBlueId() != null)) {
            throw new IllegalArgumentException("\"$previous\" list anchors must be single-key list items. Path: " + path);
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
                && node.getBlueId() == null) {
            throw new IllegalArgumentException("\"$pos\" items must contain an overlay. Path: " + path);
        }
    }

    private static void validateSchemaNodes(Schema schema, String path) {
        if (schema == null) {
            return;
        }
        if (schema.getBlueId() != null) {
            BlueIds.requireBlueIdOrCyclicMember(
                    schema.getBlueId(), appendPath(path, OBJECT_BLUE_ID));
            if (!schema.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Direct BlueId input requires schema BlueId references to be pure references. Path: "
                                + path);
            }
            return;
        }
        validateSchemaNode(schema.getRequired(), appendPath(path, KEY_REQUIRED));
        validateSchemaNode(schema.getMinLength(), appendPath(path, KEY_MIN_LENGTH));
        validateSchemaNode(schema.getMaxLength(), appendPath(path, KEY_MAX_LENGTH));
        validateSchemaNode(schema.getMinimum(), appendPath(path, KEY_MINIMUM));
        validateSchemaNode(schema.getMaximum(), appendPath(path, KEY_MAXIMUM));
        validateSchemaNode(
                schema.getExclusiveMinimum(),
                appendPath(path, KEY_EXCLUSIVE_MINIMUM));
        validateSchemaNode(
                schema.getExclusiveMaximum(),
                appendPath(path, KEY_EXCLUSIVE_MAXIMUM));
        validateSchemaNode(schema.getMultipleOf(), appendPath(path, KEY_MULTIPLE_OF));
        validateSchemaNode(schema.getMinItems(), appendPath(path, KEY_MIN_ITEMS));
        validateSchemaNode(schema.getMaxItems(), appendPath(path, KEY_MAX_ITEMS));
        validateSchemaNode(schema.getUniqueItems(), appendPath(path, KEY_UNIQUE_ITEMS));
        validateSchemaNode(schema.getMinFields(), appendPath(path, KEY_MIN_FIELDS));
        validateSchemaNode(schema.getMaxFields(), appendPath(path, KEY_MAX_FIELDS));
        if (schema.getEnum() != null) {
            for (int i = 0; i < schema.getEnum().size(); i++) {
                validateSchemaNode(schema.getEnum().get(i), appendPath(path, KEY_ENUM, i));
            }
        }
    }

    private static void validateSchemaNode(Node node, String path) {
        if (node != null) {
            validateBlueIdInput(node, path, Context.METADATA, -1);
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

    private static String appendPath(String path, String segment) {
        return JsonPointer.append(path, segment);
    }

    private static String appendPath(String path, String segment, int index) {
        return appendPath(appendPath(path, segment), String.valueOf(index));
    }

    private static boolean isTypePosition(String path) {
        return path != null
                && (path.endsWith(JsonPointer.append(
                JsonPointer.ROOT,
                OBJECT_TYPE))
                || path.endsWith(JsonPointer.append(
                JsonPointer.ROOT,
                OBJECT_ITEM_TYPE))
                || path.endsWith(JsonPointer.append(
                JsonPointer.ROOT,
                OBJECT_KEY_TYPE))
                || path.endsWith(JsonPointer.append(
                JsonPointer.ROOT,
                OBJECT_VALUE_TYPE)));
    }
}
