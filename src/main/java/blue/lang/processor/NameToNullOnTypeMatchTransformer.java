package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.MergingProcessor;
import blue.lang.NodeResolver;

public class NameToNullOnTypeMatchTransformer implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        if (target.getName() != null &&
                target.getType() != null &&
                target.getName().equals(target.getType().getName())) {
            target.name(null);
        }
    }
}