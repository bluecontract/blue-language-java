package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.NodeProcessor;

import java.util.List;

public class SequentialNodeProcessor implements NodeProcessor {

    private final List<NodeProcessor> nodeProcessors;

    public SequentialNodeProcessor(List<NodeProcessor> nodeProcessors) {
        this.nodeProcessors = nodeProcessors;
    }

    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        nodeProcessors.forEach(e -> e.process(target, source, nodeManager));
    }
}
