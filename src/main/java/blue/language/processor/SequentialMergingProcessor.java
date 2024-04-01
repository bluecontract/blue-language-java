package blue.language.processor;

import blue.language.NodeProvider;
import blue.language.Node;
import blue.language.MergingProcessor;
import blue.language.NodeResolver;

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
