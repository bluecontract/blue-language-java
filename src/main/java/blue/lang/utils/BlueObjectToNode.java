package blue.lang.utils;

import blue.lang.graph.Feature;
import blue.lang.graph.Node;
import blue.lang.graph.feature.InlineValueFeature;
import blue.lang.model.BlueObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.lang.utils.Properties.OBJECT_NAME;
import static blue.lang.utils.Properties.OBJECT_TYPE;

public class BlueObjectToNode {

    public static Node convert(BlueObject object) {
        return new Node()
                .name(object.getStringValue(OBJECT_NAME))
                .type(object.getStringValue(OBJECT_TYPE))
                .value(object.getValue())
                .blueId(object.getBlueId())
                .items(getItems(object))
                .properties(getProperties(object))
                .ref(object.getRef())
                .features(getFeatures(object));
    }

    private static List<Node> getItems(BlueObject object) {
        List<BlueObject> items = object.getItems();
        return items == null ? null : items.stream()
                .map(BlueObjectToNode::convert)
                .collect(Collectors.toList());
    }

    private static Map<String, Node> getProperties(BlueObject object) {
        Map<String, BlueObject> objectValue = object.getObjectValue();
        return objectValue == null ? null : objectValue.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(OBJECT_NAME) && !entry.getKey().equals(OBJECT_TYPE))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> BlueObjectToNode.convert(entry.getValue())
                ));
    }

    private static List<Feature> getFeatures(BlueObject object) {
        List<Feature> features = new ArrayList<>();
        if (object.isInlineValue())
            features.add(new InlineValueFeature());
        return features;
    }

}
