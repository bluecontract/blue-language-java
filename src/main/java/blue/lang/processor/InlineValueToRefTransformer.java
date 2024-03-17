package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.feature.InlineValueFeature;
import blue.lang.utils.Features;
import blue.lang.NodeProcessor;

public class InlineValueToRefTransformer implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider) {
        if (target.getType() != null &&
                source.getType() == null &&
                source.getValue() != null &&
                Features.containsFeature(source, InlineValueFeature.class)) {
            source.ref(source.getValue().toString());
            source.value(null);
        }
    }
}