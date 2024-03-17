package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.NodeProcessor;

public class ValuePropagator implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider) {
        if (source.getValue() != null)
            target.value(source.getValue());
    }
}
