package blue.language.model;

import blue.language.model.wire.BlueLanguageConstants;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.*;

/**
 * Shape predicates and canonical scalar/placeholder factories for mutable
 * Blue nodes.
 */
public class Nodes {

    /** Structural fields understood by exact-shape predicates. */
    public enum NodeField {
        /** Human-readable node name. */
        NAME,
        /** Human-readable node description. */
        DESCRIPTION,
        /** Declared type metadata. */
        TYPE,
        /** Exact BlueId metadata or reference. */
        BLUE_ID,
        /** Dictionary key-type metadata. */
        KEY_TYPE,
        /** Dictionary value-type metadata. */
        VALUE_TYPE,
        /** List item-type metadata. */
        ITEM_TYPE,
        /** Scalar payload. */
        VALUE,
        /** Object-property payload. */
        PROPERTIES,
        /** Contracts metadata. */
        CONTRACTS,
        /** Preprocessing directives. */
        BLUE,
        /** List-item payload. */
        ITEMS,
        /** Schema metadata. */
        SCHEMA,
        /** List merge-policy metadata. */
        MERGE_POLICY,
        /** Previous-list anchor metadata. */
        PREVIOUS_BLUE_ID,
        /** List overlay position metadata. */
        POSITION
    }

    /**
     * Creates a node-shape helper.
     */
    public Nodes() {
    }

    /**
     * Tests whether every structural field is absent.
     *
     * @param node node to inspect
     * @return {@code true} when every structural field is absent
     */
    public static boolean isEmptyNode(Node node) {
        return hasFieldsAndMayHaveFields(node, EnumSet.noneOf(NodeField.class), EnumSet.noneOf(NodeField.class));
    }

    /**
     * Tests whether a parsed Source node is the literal {@code null} value.
     *
     * <p>Source null is an authoring control, not semantic Blue content. The
     * mandatory preprocessing pipeline consumes it before resolution or
     * identity calculation. Its inline marker deliberately distinguishes it
     * from both a temporary fieldless builder and an exact empty object.</p>
     *
     * @param node node to inspect
     * @return {@code true} only for the Source-null wrapper
     */
    public static boolean isSourceNullLiteral(Node node) {
        return node != null
                && node.isInlineValue()
                && isEmptyNode(node);
    }

    /**
     * Tests whether a node is only an unfinished mutable-builder shell.
     *
     * <p>A fieldless builder has neither Source-null provenance nor the
     * non-null empty properties map that represents the exact object value
     * {@code {}}. It may be useful while assembling a graph, but it is not
     * semantic Blue content and must not cross serialization or identity
     * boundaries.</p>
     *
     * @param node node to inspect
     * @return {@code true} only for a bare fieldless mutable builder
     */
    public static boolean isBareFieldlessBuilder(Node node) {
        return node != null
                && !node.isInlineValue()
                && isEmptyNode(node);
    }

    /**
     * Tests whether a node is the exact object payload {@code {}}.
     *
     * <p>An empty, non-null properties map records the presence of the object
     * payload. Metadata-only and temporary fieldless nodes therefore do not
     * satisfy this predicate.</p>
     *
     * @param node node to inspect
     * @return {@code true} only for an exact metadata-free empty object
     */
    public static boolean isExactEmptyObject(Node node) {
        return node != null
                && node.getProperties() != null
                && node.getProperties().isEmpty()
                && hasFieldsAndMayHaveFields(
                        node,
                        EnumSet.of(NodeField.PROPERTIES),
                        EnumSet.noneOf(NodeField.class));
    }

    /**
     * Reports whether an object payload is present, including an exact empty
     * object but excluding the temporary Source-null entries consumed by
     * preprocessing.
     *
     * @param node node to inspect
     * @return {@code true} when the node carries object payload semantics
     */
    public static boolean hasObjectPayload(Node node) {
        if (node == null || node.getProperties() == null) {
            return false;
        }
        if (node.getProperties().isEmpty()) {
            return true;
        }
        for (Node child : node.getProperties().values()) {
            if (!isSourceNullLiteral(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests whether a node has a schema-enum scalar identity shape.
     *
     * <p>Schema enum members may be scalar values, explicitly typed scalar
     * values, or pure value references. Object/list payloads and metadata
     * unrelated to scalar identity are not enum values.</p>
     *
     * @param node node to inspect
     * @return {@code true} when the node is valid schema-enum scalar content
     */
    public static boolean isSchemaEnumValue(Node node) {
        if (node == null) {
            return false;
        }
        if (node.isReferenceOnly()) {
            return true;
        }
        Object value = node.getRawValue();
        return isSchemaScalarValue(value)
                && hasFieldsAndMayHaveFields(
                        node,
                        EnumSet.of(NodeField.VALUE),
                        EnumSet.of(NodeField.TYPE));
    }

    private static boolean isSchemaScalarValue(Object value) {
        return value instanceof String
                || value instanceof Boolean
                || value instanceof BigInteger
                || value instanceof BigDecimal;
    }

    /**
     * Creates the exact empty-object value {@code {}}.
     *
     * @return a new mutable exact empty object
     */
    public static Node emptyObject() {
        return new Node().properties(new java.util.LinkedHashMap<>());
    }

    /**
     * Creates the exact {@code {"$empty": true}} list placeholder shape.
     *
     * @return new canonical empty-list placeholder
     */
    public static Node emptyPlaceholder() {
        return new Node().properties(LIST_CONTROL_EMPTY, new Node().value(true).inlineValue(true));
    }

    /**
     * Tests whether a node has the exact empty-placeholder shape.
     *
     * @param node node to inspect
     * @return {@code true} when the node is a canonical empty-list placeholder
     */
    public static boolean isEmptyPlaceholder(Node node) {
        if (node == null || node.getProperties() == null || node.getProperties().size() != 1) {
            return false;
        }
        Node marker = node.getProperties().get(LIST_CONTROL_EMPTY);
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
                && marker.getBlueId() == null
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
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    /**
     * Requires the exact empty-placeholder shape and includes the path on failure.
     *
     * @param node node to validate
     * @param path path reported when validation fails
     */
    public static void validateEmptyPlaceholder(Node node, String path) {
        if (isEmptyPlaceholder(node)) {
            return;
        }
        throw new IllegalArgumentException("\"$empty\" list placeholder must have exact shape { \"$empty\": true }. Path: " + path);
    }

    /**
     * Tests whether only {@code blueId} is present.
     *
     * @param node node to inspect
     * @return {@code true} for a BlueId-only shape
     */
    public static boolean hasBlueIdOnly(Node node) {
        return hasFieldsAndMayHaveFields(node, EnumSet.of(NodeField.BLUE_ID), EnumSet.noneOf(NodeField.class));
    }

    /**
     * Tests whether only {@code items} is present.
     *
     * @param node node to inspect
     * @return {@code true} for an items-only shape
     */
    public static boolean hasItemsOnly(Node node) {
        return hasFieldsAndMayHaveFields(node, EnumSet.of(NodeField.ITEMS), EnumSet.noneOf(NodeField.class));
    }

    /**
     * Creates an explicitly typed Text scalar node.
     *
     * @param text text value
     * @return new Text node
     */
    public static Node textNode(String text) {
        return new Node().type(new Node().blueId(TEXT_TYPE_BLUE_ID)).value(text);
    }

    /**
     * Creates an explicitly typed Integer scalar node.
     *
     * @param number integer value
     * @return new Integer node
     */
    public static Node integerNode(BigInteger number) {
        return new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID)).value(number);
    }

    /**
     * Creates an explicitly typed Double scalar node.
     *
     * @param number decimal value
     * @return new Double node
     */
    public static Node doubleNode(BigDecimal number) {
        return new Node().type(new Node().blueId(DOUBLE_TYPE_BLUE_ID)).value(number);
    }

    /**
     * Creates an explicitly typed Boolean scalar node.
     *
     * @param booleanValue Boolean value
     * @return new Boolean node
     */
    public static Node booleanNode(Boolean booleanValue) {
        return new Node().type(new Node().blueId(BOOLEAN_TYPE_BLUE_ID)).value(booleanValue);
    }

    /**
     * Tests an exact required and allowed structural field set.
     *
     * @param node node to inspect
     * @param mustHaveFields fields that must be present
     * @param mayHaveFields additional fields permitted to be present
     * @return {@code true} when the node has exactly the permitted shape
     */
    public static boolean hasFieldsAndMayHaveFields(Node node, Set<NodeField> mustHaveFields, Set<NodeField> mayHaveFields) {
        for (NodeField field : NodeField.values()) {
            boolean fieldIsPresent = !isNull(getFieldValue(node, field));

            if (mustHaveFields.contains(field)) {
                if (!fieldIsPresent) return false;
            } else if (mayHaveFields.contains(field)) {
                // This field may or may not be present, so we don't need to check
            } else {
                if (fieldIsPresent) return false;
            }
        }
        return true;
    }

    private static Object getFieldValue(Node node, NodeField field) {
        switch (field) {
            case NAME: return node.getName();
            case TYPE: return node.getType();
            case VALUE: return node.getValue();
            case DESCRIPTION: return node.getDescription();
            case PROPERTIES: return node.getProperties();
            case CONTRACTS: return node.getContracts();
            case BLUE: return node.getBlue();
            case ITEMS: return node.getItems();
            case SCHEMA: return node.getSchema();
            case MERGE_POLICY: return node.getMergePolicy();
            case PREVIOUS_BLUE_ID: return node.getPreviousBlueId();
            case POSITION: return node.getPosition();
            case KEY_TYPE: return node.getKeyType();
            case VALUE_TYPE: return node.getValueType();
            case ITEM_TYPE: return node.getItemType();
            case BLUE_ID: return node.getBlueId();
            default: throw new IllegalArgumentException("Unknown field: " + field);
        }
    }

    private static boolean isNull(Object value) {
        return value == null;
    }

}
