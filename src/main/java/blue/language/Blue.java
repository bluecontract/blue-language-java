package blue.language;

import blue.language.model.Node;
import blue.language.utils.NodeExtender;
import blue.language.utils.limits.Limits;
import blue.language.processor.*;

import java.util.Arrays;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.limits.Limits.NO_LIMITS;

public class Blue implements NodeResolver {

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

    public void extend(Node node, Limits limits) {
        new NodeExtender(nodeProvider).extend(node, limits);
    }

    public Node objectToNode(Object object) {
        return YAML_MAPPER.convertValue(object, Node.class);
    }

    private MergingProcessor createDefaultNodeProcessor() {
        return new SequentialMergingProcessor(
                Arrays.asList(
//                        new BlueIdResolver(),
                        new ValuePropagator(),
                        new TypeAssigner()
                )
        );
    }

}