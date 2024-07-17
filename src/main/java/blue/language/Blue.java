package blue.language;

import blue.language.model.Node;
import blue.language.utils.NodeExtender;
import blue.language.utils.NodeToObject;
import blue.language.utils.NodeTypeMatcher;
import blue.language.utils.limits.Limits;
import blue.language.processor.*;

import java.util.Arrays;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
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

    public boolean nodeMatchesType(Node node, Node type) {
        return new NodeTypeMatcher(this).matchesType(node, type);
    }

    public Node yamlToNode(String yaml) {
        return YAML_MAPPER.readValue(yaml, Node.class);
    }

    public Node jsonToNode(String json) {
        return JSON_MAPPER.readValue(json, Node.class);
    }

    public Object nodeToObject(Node node) {
        return NodeToObject.get(node, NodeToObject.Strategy.SIMPLE);
    }

    public <T> T nodeToClass(Node node, Class<T> clazz) {
        return YAML_MAPPER.convertValue(node, clazz);
    }

    public NodeProvider getNodeProvider() {
        return nodeProvider;
    }

    public MergingProcessor getMergingProcessor() {
        return mergingProcessor;
    }

    private MergingProcessor createDefaultNodeProcessor() {
        return new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new ConstraintsPropagator(),
                        new ConstraintsVerifier(),
                        new BasicTypesVerifier()
                )
        );
    }

}