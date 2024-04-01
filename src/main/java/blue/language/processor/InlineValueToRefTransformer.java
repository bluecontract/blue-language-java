package blue.language.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.NodeResolver;
import blue.language.feature.InlineValueFeature;
import blue.language.utils.Features;
import blue.language.MergingProcessor;

public class InlineValueToRefTransformer implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        if (target.getType() != null &&
                source.getType() == null &&
                source.getValue() != null &&
                Features.containsFeature(source, InlineValueFeature.class)) {
            source.ref(source.getValue().toString());
            source.value(null);
        }
    }
}