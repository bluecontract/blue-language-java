package blue.language.merge.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;

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

    @Override
    public void postProcess(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        mergingProcessors.forEach(e -> e.postProcess(target, source, nodeProvider, nodeResolver));
    }
}
