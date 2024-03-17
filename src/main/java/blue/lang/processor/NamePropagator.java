package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.NodeProcessor;

public class NamePropagator implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider) {
        if (source.getName() != null)
            target.name(source.getName());
    }
}
