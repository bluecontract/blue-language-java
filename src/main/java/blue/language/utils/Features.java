package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Feature;

public class Features {

    public static boolean containsFeature(Node node, Class<? extends Feature> feature) {
        return node.getFeatures() != null && node.getFeatures().stream().anyMatch(feature::isInstance);
    }

    public static <T extends Feature> T getFeature(Node node, Class<T> feature) {
        if (node.getFeatures() == null)
            return null;
        return node.getFeatures().stream()
                .filter(feature::isInstance)
                .map(feature::cast)
                .findFirst()
                .orElse(null);
    }

}
