package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.NodeProcessor;

import java.util.List;

public class SequentialNodeProcessor implements NodeProcessor {

    private final List<NodeProcessor> nodeProcessors;

    public SequentialNodeProcessor(List<NodeProcessor> nodeProcessors) {
        this.nodeProcessors = nodeProcessors;
    }

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider) {
        nodeProcessors.forEach(e -> e.process(target, source, nodeProvider));
    }
}
