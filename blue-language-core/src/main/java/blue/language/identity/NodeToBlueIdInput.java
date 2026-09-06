package blue.language.identity;

import blue.language.model.wire.SchemaPropertyConstants;

import blue.language.model.SchemaWireForm;

import blue.language.model.NodeWireForm;

import blue.language.model.value.BlueNumbers;

import blue.language.model.wire.JsonPointer;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.Schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.*;
import static blue.language.model.wire.SchemaPropertyConstants.*;

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
     * Projects a resolved node for model-SPI identity calculation.
     *
     * <p>Resolved graphs may retain expanded type declarations as runtime
     * evidence. Identity is nevertheless defined by the exact identity of
     * each effective type position, so expanded type bodies are reduced to
     * pure BlueId references before the enclosing value is hashed.</p>
     */
    static Object getResolvedForm(Node node) {
        Node identityNode = node == null
                ? null
                : stripResolvedBlueIdMetadata(node.clone());
        return get(identityNode, JsonPointer.ROOT, Context.ROOT, -1, false,
                true);
    }

    /** Projects an ordered resolved-node sequence for model-SPI identity. */
    static List<Object> getResolvedFormElements(List<Node> nodes) {
        if (nodes == null) {
            throw new IllegalArgumentException(
                    "Node identity input list must not be null.");
        }
        List<Object> result = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            Node identityNode = nodes.get(i) == null
                    ? null
                    : stripResolvedBlueIdMetadata(nodes.get(i).clone());
            result.add(get(identityNode, JsonPointer.ROOT + i,
                    Context.LIST_ELEMENT, i, false, true));
        }
        return result;
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
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        Set<Schema> visitedSchemas = Collections.newSetFromMap(
                new IdentityHashMap<Schema, Boolean>());
        pending.push(node);
        while (!pending.isEmpty()) {
            Node current = pending.pop();
            if (!visited.add(current) || current.isReferenceOnly()) {
                continue;
            }
            if (current.getBlueId() != null) {
                current.blueId(null);
            }
            push(pending, current.getType());
            push(pending, current.getItemType());
            push(pending, current.getKeyType());
            push(pending, current.getValueType());
            push(pending, current.getBlue());
            push(pending, current.getContracts());
            if (current.getItems() != null) {
                for (Node item : current.getItems()) {
                    push(pending, item);
                }
            }
            if (current.getProperties() != null) {
                for (Node property : current.getProperties().values()) {
                    push(pending, property);
                }
            }
            Schema schema = current.getSchema();
            if (schema == null
                    || !visitedSchemas.add(schema)
                    || schema.isReferenceOnly()) {
                continue;
            }
            if (schema.getBlueId() != null) {
                schema.blueId(null);
            }
            pushSchemaNodes(pending, schema);
        }
        return node;
    }

    private static void pushSchemaNodes(
            Deque<Node> pending,
            Schema schema) {
        push(pending, schema.getRequired());
        push(pending, schema.getMinLength());
        push(pending, schema.getMaxLength());
        push(pending, schema.getMinimum());
        push(pending, schema.getMaximum());
        push(pending, schema.getExclusiveMinimum());
        push(pending, schema.getExclusiveMaximum());
        push(pending, schema.getMultipleOf());
        push(pending, schema.getMinItems());
        push(pending, schema.getMaxItems());
        push(pending, schema.getUniqueItems());
        push(pending, schema.getMinFields());
        push(pending, schema.getMaxFields());
        if (schema.getEnum() != null) {
            for (Node enumValue : schema.getEnum()) {
                push(pending, enumValue);
            }
        }
    }

    private static void push(Deque<Node> pending, Node child) {
        if (child != null) {
            pending.push(child);
        }
    }

    private enum Context {
        ROOT,
        OBJECT_FIELD,
        LIST_ELEMENT,
        METADATA
    }

    private static Object get(Node node, String path, Context context,
                              int listIndex,
                              boolean allowCyclicPlaceholders) {
        return get(node, path, context, listIndex, allowCyclicPlaceholders,
                false);
    }

    private static Object get(Node node, String path, Context context,
                              int listIndex,
                              boolean allowCyclicPlaceholders,
                              boolean resolveTypeBodies) {
        if (resolveTypeBodies
                && node != null
                && context == Context.METADATA
                && isTypePosition(path)
                && !node.isReferenceOnly()) {
            Object typeInput = get(node, path, Context.ROOT, -1,
                    allowCyclicPlaceholders, true);
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put(OBJECT_BLUE_ID,
                    DirectBlueIdCalculator.INSTANCE
                            .directBlueIdFromCanonicalInput(typeInput));
            return reference;
        }

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
                items.add(get(node.getItems().get(i), appendPath(path, OBJECT_ITEMS, i), Context.LIST_ELEMENT, i, allowCyclicPlaceholders, resolveTypeBodies));
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
            Object typeInput = get(node.getType(),
                    appendPath(path, OBJECT_TYPE), Context.METADATA, -1,
                    allowCyclicPlaceholders, resolveTypeBodies);
            result.put(OBJECT_TYPE, typeInput);
            if (typeInput instanceof Map) {
                Object projectedBlueId = ((Map<?, ?>) typeInput).get(
                        OBJECT_BLUE_ID);
                if (projectedBlueId instanceof String) {
                    valueTypeBlueId = (String) projectedBlueId;
                }
            }
        }

        if (node.getItemType() != null)
            result.put(OBJECT_ITEM_TYPE, get(node.getItemType(), appendPath(path, OBJECT_ITEM_TYPE), Context.METADATA, -1, allowCyclicPlaceholders, resolveTypeBodies));
        if (node.getKeyType() != null)
            result.put(OBJECT_KEY_TYPE, get(node.getKeyType(), appendPath(path, OBJECT_KEY_TYPE), Context.METADATA, -1, allowCyclicPlaceholders, resolveTypeBodies));
        if (node.getValueType() != null)
            result.put(OBJECT_VALUE_TYPE, get(node.getValueType(), appendPath(path, OBJECT_VALUE_TYPE), Context.METADATA, -1, allowCyclicPlaceholders, resolveTypeBodies));
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
            result.put(OBJECT_SCHEMA, SchemaWireForm.get(
                    identitySchema,
                    child -> get(child, appendPath(path, OBJECT_SCHEMA), Context.METADATA, -1, allowCyclicPlaceholders, resolveTypeBodies)));
        }
        if (node.getContracts() != null) {
            result.put(OBJECT_CONTRACTS, get(node.getContracts(), appendPath(path, OBJECT_CONTRACTS), Context.METADATA, -1, allowCyclicPlaceholders, resolveTypeBodies));
        }
        if (node.getProperties() != null) {
            node.getProperties().forEach((key, propertyValue) -> {
                if (propertyValue == null) {
                    // Host-null object members represent omission. Source
                    // preprocessing normally removes them before this point;
                    // direct identity must never turn them into Blue values.
                    return;
                }
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
                            allowCyclicPlaceholders,
                            resolveTypeBodies));
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
        return NodeWireForm.get(
                value,
                value.isInlineValue()
                        ? NodeWireForm.Strategy.SIMPLE
                        : NodeWireForm.Strategy.OFFICIAL);
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
        if (Nodes.isSourceNullLiteral(node)) {
            throw new IllegalArgumentException(
                    "Source null is not valid direct BlueId input. Path: "
                            + path);
        }
        if (Nodes.isBareFieldlessBuilder(node)) {
            throw bareFieldlessBuilder(path);
        }
        if (context == Context.METADATA
                && isTypePosition(path)
                && !node.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Direct BlueId input type positions must contain pure "
                            + "references. Path: " + path);
        }
        if (node.getBlue() != null) {
            throw new IllegalArgumentException(
                    "\"blue\" is a preprocessing directive and must not be present in BlueId input. " +
                            "Call preprocess/canonicalize/calculateSourceDocumentBlueId first. Path: " + path);
        }
        if (node.getPosition() != null) {
            throw new IllegalArgumentException("\"$pos\" overlays are not valid direct BlueId input. Path: " + path);
        }
        if (context == Context.LIST_ELEMENT && node.getProperties() != null
                && node.getProperties().containsKey(LIST_CONTROL_REPLACE)) {
            throw new IllegalArgumentException("\"$replace\" overlays are not valid direct BlueId input. Path: " + path);
        }
        if (context == Context.LIST_ELEMENT) {
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

    private static IllegalArgumentException bareFieldlessBuilder(
            String path) {
        return new IllegalArgumentException(
                "Fieldless Node is an incomplete builder, not semantic Blue "
                        + "content. Use Nodes.emptyObject() for {} or omit the "
                        + "field for absence. Path: " + path);
    }

    private static void validatePayloadKind(Node node, String path) {
        int payloadKinds = 0;
        if (node.getValue() != null) payloadKinds++;
        if (node.getItems() != null) payloadKinds++;
        if (hasRetainedDirectObjectPayload(node)) payloadKinds++;
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

    private static boolean hasRetainedDirectObjectPayload(Node node) {
        if (node.getProperties() == null) {
            return false;
        }
        if (node.getProperties().isEmpty()) {
            return true;
        }
        for (Node child : node.getProperties().values()) {
            if (child != null && !Nodes.isSourceNullLiteral(child)) {
                return true;
            }
        }
        return false;
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
