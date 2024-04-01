package blue.language.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.MergingProcessor;
import blue.language.NodeResolver;
import blue.language.feature.BlueprintFeature;
import blue.language.utils.Features;
import blue.language.utils.Nodes;

import java.util.HashMap;
import java.util.Map;

public class BlueToFeatureTransformer implements MergingProcessor {

    public static final String BLUE_PROPERTY_KEY = "blue";

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
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