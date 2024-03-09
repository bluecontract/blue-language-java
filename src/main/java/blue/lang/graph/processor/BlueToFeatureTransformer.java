package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.NodeProcessor;
import blue.lang.graph.feature.BlueprintFeature;
import blue.lang.graph.utils.Features;
import blue.lang.graph.utils.Nodes;

import java.util.HashMap;
import java.util.Map;

public class BlueToFeatureTransformer implements NodeProcessor {

    public static final String BLUE_PROPERTY_KEY = "blue";

    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        Map<String, Node> properties = source.getProperties();
        if (properties == null)
            return;

        Node blueNode = properties.get(BLUE_PROPERTY_KEY);
        if (blueNode == null)
            return;

        BlueprintFeature feature = new BlueprintFeature(new HashMap<>(blueNode.getProperties()));

        if (Features.containsFeature(source, BlueprintFeature.class))
            System.out.println("!!! CONTAINS");

        System.out.println("converting from prop to feature");
        Nodes.addFeature(source, feature);
        properties.remove(BLUE_PROPERTY_KEY);

    }
}