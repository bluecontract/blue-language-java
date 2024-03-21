package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.MergingProcessor;
import blue.lang.NodeResolver;

import java.util.List;

public class SequentialMergingProcessor implements MergingProcessor {

    private final List<MergingProcessor> mergingProcessors;

    public SequentialMergingProcessor(List<MergingProcessor> mergingProcessors) {
        this.mergingProcessors = mergingProcessors;
    }

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        mergingProcessors.forEach(e -> e.process(target, source, nodeProvider, nodeResolver));
    }
}
