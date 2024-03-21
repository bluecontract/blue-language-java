package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.MergingProcessor;
import blue.lang.NodeResolver;

public class ValuePropagator implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        if (source.getValue() != null)
            target.value(source.getValue());
    }
}
