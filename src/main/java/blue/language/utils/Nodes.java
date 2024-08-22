package blue.language.utils;

import blue.language.model.Node;

public class Nodes {

    public static boolean isEmptyNode(Node node) {
        return node.getName() == null && node.getType() == null && node.getValue() == null && node.getDescription() == null &&
                node.getItems() == null && node.getProperties() == null;
    }

    public static boolean hasItemsOnly(Node node) {
        return node.getName() == null && node.getType() == null && node.getValue() == null && node.getDescription() == null &&
               node.getProperties() == null;
    }

    public static boolean hasBlueIdOnly(Node node) {
        return node.getName() == null && node.getType() == null && node.getValue() == null && node.getDescription() == null &&
               node.getProperties() == null && node.getBlue() == null && node.getItems() == null && node.getConstraints() == null &&
               node.getKeyType() == null && node.getValueType() == null && node.getItemType() == null;
    }
    
}
