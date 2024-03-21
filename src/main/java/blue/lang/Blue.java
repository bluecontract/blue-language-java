package blue.lang;

import blue.lang.model.Limits;
import blue.lang.processor.*;

import java.util.Arrays;

import static blue.lang.model.Limits.NO_LIMITS;

public class Blue implements NodeProvider, NodeResolver {

    private NodeProvider nodeProvider;
    private MergingProcessor mergingProcessor;

    public Blue() {
        this(node -> null);
    }

    public Blue(NodeProvider nodeProvider) {
        this.nodeProvider = nodeProvider;
        this.mergingProcessor = createDefaultNodeProcessor();
    }

    public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor) {
        this.nodeProvider = nodeProvider;
        this.mergingProcessor = mergingProcessor;
    }

    public Node resolve(Node node) {
        return resolve(node, NO_LIMITS);
    }

    @Override
    public Node resolve(Node node, Limits limits) {
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        return merger.resolve(node, limits);
    }

    @Override
    public Node fetchByBlueId(String blueId) {
        Merger merger = new Merger(mergingProcessor, this);
        Node resultNode = new Node();
        Node sourceNode = nodeProvider.fetchByBlueId(blueId);
        merger.merge(resultNode, sourceNode);
        return resultNode;
    }

    private MergingProcessor createDefaultNodeProcessor() {
        return new SequentialMergingProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new ValuePropagator(),
                        new NamePropagator(),
                        new TypeAssigner(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );
    }

}