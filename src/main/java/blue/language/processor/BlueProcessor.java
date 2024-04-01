package blue.language.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.MergingProcessor;
import blue.language.NodeResolver;
import blue.language.feature.BlueprintFeature;
import blue.language.utils.Features;
import blue.language.utils.Nodes;

public class BlueProcessor implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
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
