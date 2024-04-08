package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Feature;

import java.util.ArrayList;

public class Nodes {

    public static boolean isEmptyNode(Node node) {
        return node.getName() == null && node.getType() == null && node.getValue() == null && node.getDescription() == null &&
                node.getItems() == null && node.getProperties() == null && node.getRef() == null && node.getFeatures() == null;
    }

    public static boolean isEmptyNodeWithoutBlueId(Node node) {
        return node.getName() == null && node.getType() == null && node.getValue() == null &&
                node.getItems() == null && node.getProperties() == null && node.getRef() == null &&
                node.getFeatures() == null && node.getBlueId() == null;
    }

    public static void addFeature(Node node, Feature feature) {
        if (node.getFeatures() == null)
            node.features(new ArrayList<>());
        node.getFeatures().add(feature);
    }

    public static boolean isSingleValueNode(Node node) {
        if (hasSingleValue(node)) {
            return node.getName() == null && node.getType() == null && node.getItems() == null && node.getProperties() == null;
        }
        return false;
    }

    public static boolean hasSingleValue(Node node) {
        Object value = node.getValue();
        return (value instanceof String || value instanceof Number || value instanceof Boolean);
    }

    public static boolean isValueLess(Node node) {
        return node.getValue() == null && node.getItems() == null && node.getProperties() == null;
    }
}
