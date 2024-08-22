package blue.language;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.merge.processor.*;
import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.utils.*;
import blue.language.utils.NodeTypeMatcher.TargetTypeTransformer;
import blue.language.utils.limits.Limits;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.limits.Limits.NO_LIMITS;

public class Blue implements NodeResolver {

    private NodeProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private TypeClassResolver typeClassResolver;
    private Map<String, String> preprocessingAliases = new HashMap<>();


    public Blue() {
        this(node -> null);
    }

    public Blue(NodeProvider nodeProvider) {
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
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
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
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
        String json = JSON_MAPPER.writeValueAsString(object);
        return jsonToNode(json);
    }

    public boolean nodeMatchesType(Node node, Node type) {
        return new NodeTypeMatcher(this).matchesType(node, type);
    }

    public boolean nodeMatchesType(Node node, Node type, TargetTypeTransformer transformer) {
        return new NodeTypeMatcher(this).matchesType(node, type, transformer);
    }

    public Node yamlToNode(String yaml) {
        return preprocess(YAML_MAPPER.readValue(yaml, Node.class));
    }

    public Node jsonToNode(String json) {
        return preprocess(JSON_MAPPER.readValue(json, Node.class));
    }

    public void addPreprocessingAliases(Map<String, String> aliases) {
        preprocessingAliases.putAll(aliases);
    }

    public Node preprocess(Node node) {
        if (node.getBlue() != null && node.getBlue().getValue() instanceof String) {
            String blueValue = (String) node.getBlue().getValue();

            if (preprocessingAliases.containsKey(blueValue)) {
                Node clonedNode = node.clone();
                clonedNode.blue(new Node().blueId(preprocessingAliases.get(blueValue)));
                return new Preprocessor(nodeProvider).preprocessWithDefaultBlue(clonedNode);
            } else if (BlueIds.isPotentialBlueId(blueValue)) {
                Node clonedNode = node.clone();
                clonedNode.blue(new Node().blueId(blueValue));
                return new Preprocessor(nodeProvider).preprocessWithDefaultBlue(clonedNode);
            } else {
                throw new IllegalArgumentException("Invalid blue value: " + blueValue);
            }
        }

        return new Preprocessor(nodeProvider).preprocessWithDefaultBlue(node);
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
        return new NodeToObjectConverter(typeClassResolver).convert(node, clazz);
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

    public TypeClassResolver getTypeClassResolver() {
        return typeClassResolver;
    }

    public Map<String, String> getPreprocessingAliases() {
        return preprocessingAliases;
    }

    public Blue nodeProvider(NodeProvider nodeProvider) {
        this.nodeProvider = nodeProvider;
        return this;
    }

    public Blue mergingProcessor(MergingProcessor mergingProcessor) {
        this.mergingProcessor = mergingProcessor;
        return this;
    }

    public Blue typeClassResolver(TypeClassResolver typeClassResolver) {
        this.typeClassResolver = typeClassResolver;
        return this;
    }

    public Blue preprocessingAliases(Map<String, String> preprocessingAliases) {
        this.preprocessingAliases = preprocessingAliases;
        return this;
    }

    private MergingProcessor createDefaultNodeProcessor() {
        return new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new ListProcessor(),
                        new DictionaryProcessor(),
                        new ConstraintsPropagator(),
                        new ConstraintsVerifier(),
                        new BasicTypesVerifier()
                )
        );
    }

}