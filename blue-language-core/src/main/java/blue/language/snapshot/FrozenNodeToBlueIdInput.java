package blue.language.snapshot;

import blue.language.model.wire.SchemaPropertyConstants;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Schema;
import blue.language.identity.BlueIds;
import blue.language.model.value.BlueNumbers;
import blue.language.model.wire.JsonPointer;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.identity.SchemaEnumCanonicalizer;
import blue.language.model.SchemaWireForm;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.*;
import static blue.language.model.wire.SchemaPropertyConstants.*;

/**
 * Projects a {@link FrozenNode} into the exact map/list/scalar input consumed
 * by the BlueId algorithm.
 *
 * <p>The projection validates context-sensitive list controls, pure-reference
 * shapes, scalar types, and canonical number rules without mutating the frozen
 * graph.</p>
 */
public final class FrozenNodeToBlueIdInput {

    private FrozenNodeToBlueIdInput() {
    }

    /**
     * Returns the exact canonical identity input for one root node.
     *
     * @param node frozen root node to project
     * @return canonical map, list, or scalar identity input
     */
    public static Object get(FrozenNode node) {
        Context context = node != null && node.isListElementContext() ? Context.LIST_ELEMENT : Context.ROOT;
        int listIndex = node != null && node.isListElementContext() ? 0 : -1;
        return get(node, JsonPointer.ROOT, context, listIndex);
    }

    static Object getListElement(FrozenNode node, int index) {
        return get(
                node,
                JsonPointer.ROOT + index,
                Context.LIST_ELEMENT,
                index);
    }

    private enum Context {
        ROOT,
        OBJECT_FIELD,
        LIST_ELEMENT,
        METADATA
    }

    private static Object get(FrozenNode node, String path, Context context, int listIndex) {
        validateBlueIdInput(node, path, context, listIndex);

        if (context == Context.LIST_ELEMENT && isEmptyPlaceholder(node)) {
            Map<String, Object> placeholder = new LinkedHashMap<>();
            placeholder.put(LIST_CONTROL_EMPTY, true);
            return placeholder;
        }

        if (node.isReferenceOnly()) {
            String blueId = BlueIds.requireBlueIdOrCyclicMember(
                    BlueIds.requireNoThisPlaceholderOutsideCyclicApi(
                            node.getReferenceBlueId(),
                            appendPath(path, OBJECT_BLUE_ID)),
                    appendPath(path, OBJECT_BLUE_ID));
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
                items.add(get(node.getItems().get(i), appendPath(path, OBJECT_ITEMS, i), Context.LIST_ELEMENT, i));
            }
        }

        if (items != null && isPayloadOnlyList(node)) {
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
        if (value != null && node.getType() == null) {
            String inferredTypeBlueId = inferTypeBlueId(value);
            if (inferredTypeBlueId != null) {
                valueTypeBlueId = inferredTypeBlueId;
                Map<String, String> map = new LinkedHashMap<>();
                map.put(OBJECT_BLUE_ID, inferredTypeBlueId);
                result.put(OBJECT_TYPE, map);
            }
        } else if (node.getType() != null) {
            valueTypeBlueId = node.getType().getReferenceBlueId();
            result.put(OBJECT_TYPE, get(node.getType(), appendPath(path, OBJECT_TYPE), Context.METADATA, -1));
        }

        if (node.getItemType() != null) {
            result.put(OBJECT_ITEM_TYPE, get(node.getItemType(), appendPath(path, OBJECT_ITEM_TYPE), Context.METADATA, -1));
        }
        if (node.getKeyType() != null) {
            result.put(OBJECT_KEY_TYPE, get(node.getKeyType(), appendPath(path, OBJECT_KEY_TYPE), Context.METADATA, -1));
        }
        if (node.getValueType() != null) {
            result.put(OBJECT_VALUE_TYPE, get(node.getValueType(), appendPath(path, OBJECT_VALUE_TYPE), Context.METADATA, -1));
        }
        if (node.getMergePolicy() != null) {
            result.put(OBJECT_MERGE_POLICY, node.getMergePolicy());
        }
        if (value != null) {
            result.put(OBJECT_VALUE, handleValue(value, valueTypeBlueId));
        }
        if (items != null) {
            result.put(OBJECT_ITEMS, items);
        }
        if (node.getSchema() != null) {
            Schema schema = node.getSchema();
            validateSchemaNodes(schema, appendPath(path, OBJECT_SCHEMA));
            Schema identitySchema = schema.clone();
            if (identitySchema.getEnum() != null) {
                identitySchema.enumValues(
                        SchemaEnumCanonicalizer.canonicalize(
                                identitySchema.getEnum()));
            }
            result.put(OBJECT_SCHEMA, SchemaWireForm.get(
                    identitySchema,
                    child -> NodeToBlueIdInput.get(child)));
        }
        if (node.getContracts() != null) {
            result.put(OBJECT_CONTRACTS, get(node.getContracts(), appendPath(path, OBJECT_CONTRACTS), Context.METADATA, -1));
        }
        if (node.getProperties() != null) {
            node.getProperties().forEach((key, propertyValue) ->
                    result.put(key, get(propertyValue, appendPath(path, key), Context.OBJECT_FIELD, -1)));
        }
        return result;
    }

    private static boolean isPayloadOnlyList(FrozenNode node) {
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
                && node.getReferenceBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    private static void validateBlueIdInput(FrozenNode node, String path, Context context, int listIndex) {
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
            if (node.isEmptyNode()) {
                throw new IllegalArgumentException("Direct BlueId input must use { \"$empty\": true } for empty list placeholders. Path: " + path);
            }
            if (node.getProperties() != null && node.getProperties().containsKey(LIST_CONTROL_EMPTY)) {
                validateEmptyPlaceholder(node, path);
            }
            if (node.getPreviousBlueId() != null && listIndex != 0) {
                throw new IllegalArgumentException("\"$previous\" must appear only as the first list item. Path: " + path);
            }
        } else if (node.getPreviousBlueId() != null) {
            throw new IllegalArgumentException("\"$previous\" is valid only as the first list item in direct BlueId input. Path: " + path);
        }
        validatePayloadKind(node, path);
    }

    private static void validatePayloadKind(FrozenNode node, String path) {
        int payloadKinds = 0;
        if (node.getValue() != null) payloadKinds++;
        if (node.getItems() != null) payloadKinds++;
        if (node.getProperties() != null) payloadKinds++;
        if (payloadKinds > 1) {
            throw new IllegalArgumentException("A Blue node may contain only one payload kind: value, items, or object fields. Path: " + path);
        }
        if (node.getReferenceBlueId() != null && !node.isReferenceOnly()) {
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
                || node.getReferenceBlueId() != null)) {
            throw new IllegalArgumentException("\"$previous\" list anchors must be single-key list items. Path: " + path);
        }
    }

    private static boolean isEmptyPlaceholder(FrozenNode node) {
        if (node == null || node.getProperties() == null || node.getProperties().size() != 1) {
            return false;
        }
        FrozenNode marker = node.getProperties().get(LIST_CONTROL_EMPTY);
        return marker != null
                && Boolean.TRUE.equals(marker.getValue())
                && marker.getName() == null
                && marker.getDescription() == null
                && marker.getType() == null
                && marker.getItemType() == null
                && marker.getKeyType() == null
                && marker.getValueType() == null
                && marker.getItems() == null
                && marker.getProperties() == null
                && marker.getContracts() == null
                && marker.getReferenceBlueId() == null
                && marker.getSchema() == null
                && marker.getMergePolicy() == null
                && marker.getPreviousBlueId() == null
                && marker.getPosition() == null
                && marker.getBlue() == null
                && node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getValue() == null
                && node.getItems() == null
                && node.getContracts() == null
                && node.getReferenceBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    private static void validateEmptyPlaceholder(FrozenNode node, String path) {
        if (isEmptyPlaceholder(node)) {
            return;
        }
        throw new IllegalArgumentException("\"$empty\" list placeholder must have exact shape { \"$empty\": true }. Path: " + path);
    }

    private static void validateSchemaNodes(Schema schema, String path) {
        if (schema == null) {
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

    private static void validateSchemaNode(blue.language.model.Node node, String path) {
        if (node != null) {
            NodeToBlueIdInput.get(node);
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
        }
        if (value instanceof BigInteger) {
            return INTEGER_TYPE_BLUE_ID;
        }
        if (value instanceof BigDecimal) {
            return DOUBLE_TYPE_BLUE_ID;
        }
        if (value instanceof Boolean) {
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
