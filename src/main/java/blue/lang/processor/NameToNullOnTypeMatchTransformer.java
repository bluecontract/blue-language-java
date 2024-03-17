package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.NodeProcessor;

public class NameToNullOnTypeMatchTransformer implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider) {
        if (target.getName() != null &&
                target.getType() != null &&
                target.getName().equals(target.getType())) {
            target.name(null);
        }
    }
}