package blue.language.processor;

import blue.language.*;
import blue.language.utils.Features;
import blue.language.utils.Nodes;
import blue.language.feature.InlineValueFeature;

public class InlineValuePropagator implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        Feature inlineValue = Features.getFeature(source, InlineValueFeature.class);
        if (inlineValue != null)
            Nodes.addFeature(target, inlineValue);
    }
}
