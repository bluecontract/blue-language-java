package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.NodeProcessor;
import blue.lang.graph.feature.BlueprintFeature;
import blue.lang.graph.utils.Features;
import blue.lang.graph.utils.Nodes;
import blue.lang.utils.NodeToObject;

public class BlueProcessor implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        if (Features.containsFeature(target, BlueprintFeature.class))
            System.out.println("target has feature: " + Features.getFeature(target, BlueprintFeature.class));
        if (Features.containsFeature(source, BlueprintFeature.class)) {
            BlueprintFeature feature = Features.getFeature(source, BlueprintFeature.class);
            System.out.println("source has feature: " + feature);
            if (!Features.containsFeature(target, BlueprintFeature.class))
                Nodes.addFeature(target, feature);
        }
    }
}
