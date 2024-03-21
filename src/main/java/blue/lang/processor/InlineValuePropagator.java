package blue.lang.processor;

import blue.lang.*;
import blue.lang.utils.Features;
import blue.lang.utils.Nodes;
import blue.lang.feature.InlineValueFeature;

public class InlineValuePropagator implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        Feature inlineValue = Features.getFeature(source, InlineValueFeature.class);
        if (inlineValue != null)
            Nodes.addFeature(target, inlineValue);
    }
}
