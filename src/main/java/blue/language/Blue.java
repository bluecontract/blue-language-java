package blue.language;

import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.utils.*;
import blue.language.utils.limits.Limits;

import java.util.Arrays;
import java.util.Optional;

import static blue.language.utils.NodeToObject.Strategy.SIMPLE;
import static blue.language.utils.NodeToObject.Strategy.SIMPLE_NO_TYPE;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.limits.Limits.NO_LIMITS;

public class Blue implements NodeResolver {

    private NodeProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private TypeClassResolver typeClassResolver;

    public Blue() {
        this(node -> null);
    }

    public Blue(NodeProvider nodeProvider) {
        this.nodeProvider = nodeProvider;
        this.mergingProcessor = createDefaultNodeProcessor();
    }

    public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor) {
        this(nodeProvider, mergingProcessor, null);
    }

    public Blue(NodeProvider nodeProvider, TypeClassResolver typeClassResolver) {
        this(nodeProvider, null, typeClassResolver);
        this.mergingProcessor = createDefaultNodeProcessor();
    }

    public Blue(NodeProvider nodeProvider, MergingProcessor mergingProcessor, TypeClassResolver typeClassResolver) {
        this.nodeProvider = nodeProvider;
        this.mergingProcessor = mergingProcessor;
        this.typeClassResolver = typeClassResolver;
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

    public Optional<Class<?>> determineClass(Node node) {
        if (typeClassResolver != null) {
            Class<?> clazz = typeClassResolver.resolveClass(node);
            if (clazz != null)
                return Optional.of(clazz);
        }
        return Optional.empty();
    }

    public <T> T nodeToObject(Node node, Class<T> clazz) {
        Node clone = node.clone();
        clone.type((Node) null);
        return YAML_MAPPER.convertValue(NodeToObject.get(clone, SIMPLE), clazz);
    }

    public boolean isNodeSubtypeOf(Node candidateNode, Node superTypeNode) {
        return Types.isSubtype(candidateNode, superTypeNode, nodeProvider);
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