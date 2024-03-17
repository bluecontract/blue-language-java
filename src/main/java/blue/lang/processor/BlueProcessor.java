package blue.lang.processor;

import blue.lang.Resolver;
import blue.lang.Node;
import blue.lang.NodeProcessor;
import blue.lang.feature.BlueprintFeature;
import blue.lang.utils.Features;
import blue.lang.utils.Nodes;

public class BlueProcessor implements NodeProcessor {
    @Override
    public void process(Node target, Node source, Resolver resolver) {
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
