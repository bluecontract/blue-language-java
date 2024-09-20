package blue.language.utils;

import blue.language.model.Node;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Set;

import static blue.language.utils.Properties.*;

public class Nodes {

    public enum NodeField {
        NAME,
        DESCRIPTION,
        TYPE,
        BLUE_ID,
        KEY_TYPE,
        VALUE_TYPE,
        ITEM_TYPE,
        VALUE,
        PROPERTIES,
        BLUE,
        ITEMS,
        CONSTRAINTS
    }

    public static boolean isEmptyNode(Node node) {
        return hasFieldsAndMayHaveFields(node, EnumSet.noneOf(NodeField.class), EnumSet.noneOf(NodeField.class));
    }

    public static boolean hasBlueIdOnly(Node node) {
        return hasFieldsAndMayHaveFields(node, EnumSet.of(NodeField.BLUE_ID), EnumSet.noneOf(NodeField.class));
    }

    public static boolean hasItemsOnly(Node node) {
        return hasFieldsAndMayHaveFields(node, EnumSet.of(NodeField.ITEMS), EnumSet.noneOf(NodeField.class));
    }

    public static Node textNode(String text) {
        return new Node().type(new Node().blueId(TEXT_TYPE_BLUE_ID)).value(text);
    }

    public static Node integerNode(BigInteger number) {
        return new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID)).value(number);
    }

    public static Node doubleNode(BigDecimal number) {
        return new Node().type(new Node().blueId(DOUBLE_TYPE_BLUE_ID)).value(number);
    }

    public static Node booleanNode(Boolean booleanValue) {
        return new Node().type(new Node().blueId(BOOLEAN_TYPE_BLUE_ID)).value(booleanValue);
    }

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
            case BLUE: return node.getBlue();
            case ITEMS: return node.getItems();
            case CONSTRAINTS: return node.getConstraints();
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
