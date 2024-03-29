package blue.lang.utils;

import blue.lang.Node;
import blue.lang.Feature;

import java.util.ArrayList;

public class Nodes {

    public static boolean isEmptyNode(Node node) {
        return node.getName() == null && node.getType() == null && node.getValue() == null &&
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

}
