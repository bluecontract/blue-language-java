package blue.language.utils;

import blue.language.model.Node;

import java.math.BigDecimal;
import java.math.BigInteger;

import static blue.language.utils.Properties.*;

public class Nodes {

    public static boolean isEmptyNode(Node node) {
        return node.getName() == null && node.getType() == null && node.getValue() == null && node.getDescription() == null &&
               node.getProperties() == null && node.getBlue() == null && node.getItems() == null && node.getConstraints() == null &&
               node.getKeyType() == null && node.getValueType() == null && node.getItemType() == null && node.getBlueId() == null;
    }

    public static boolean hasItemsOnly(Node node) {
        return node.getName() == null && node.getType() == null && node.getValue() == null && node.getDescription() == null &&
               node.getProperties() == null;
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

}
