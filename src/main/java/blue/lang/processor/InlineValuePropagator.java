package blue.lang.processor;

import blue.lang.Resolver;
import blue.lang.Node;
import blue.lang.utils.Features;
import blue.lang.utils.Nodes;
import blue.lang.Feature;
import blue.lang.NodeProcessor;
import blue.lang.feature.InlineValueFeature;

public class InlineValuePropagator implements NodeProcessor {
    @Override
    public void process(Node target, Node source, Resolver resolver) {
        Feature inlineValue = Features.getFeature(source, InlineValueFeature.class);
        if (inlineValue != null)
            Nodes.addFeature(target, inlineValue);
    }
}
