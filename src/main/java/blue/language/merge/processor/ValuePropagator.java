package blue.language.merge.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;

public class ValuePropagator implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        if (source.getValue() != null) {
            if (target.getValue() == null)
                target.value(source.getValue());
            else if (!source.getValue().equals(target.getValue()))
                throw new IllegalArgumentException("Node values conflict. Source node value: " + source.getValue() +
                        ", target node value: " + target.getValue());
        }
        
    }
}
