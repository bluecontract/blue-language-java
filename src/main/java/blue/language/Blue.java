package blue.language;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.merge.processor.*;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ContractProcessor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.model.Contract;
import blue.language.preprocess.Preprocessor;
import blue.language.utils.*;
import blue.language.utils.limits.CompositeLimits;
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
    private Limits globalLimits = NO_LIMITS;
    private DocumentProcessor documentProcessor;



    public Blue() {
        this(node -> null);
    }

    public Blue(NodeProvider nodeProvider) {
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.mergingProcessor = createDefaultNodeProcessor();
        this.documentProcessor = createDefaultDocumentProcessor();
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
        this.documentProcessor = createDefaultDocumentProcessor();
    }

    public Node resolve(Node node) {
        return resolve(node, NO_LIMITS);
    }

    @Override
    public Node resolve(Node node, Limits limits) {
        Limits effectiveLimits = combineWithGlobalLimits(limits);
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        return merger.resolve(node, effectiveLimits);
    }

    public Node reverse(Node node) {
        return new MergeReverser().reverse(node);
    }

    public Node reverse(Object object) {
        return reverse(objectToNode(object));
    }

    public void extend(Node node, Limits limits) {
        Limits effectiveLimits = combineWithGlobalLimits(limits);
        new NodeExtender(nodeProvider).extend(node, effectiveLimits);
    }

    public Node objectToNode(Object object) {
        String json = JSON_MAPPER.writeValueAsString(object);
        return jsonToNode(json);
    }

    public <T> T convertObject(Object object, Class<T> clazz) {
        return nodeToObject(objectToNode(object).clone(), clazz);
    }

    public boolean nodeMatchesType(Node node, Node type) {
        return new NodeTypeMatcher(this).matchesType(node, type, globalLimits);
    }

    public void setGlobalLimits(Limits globalLimits) {
        this.globalLimits = globalLimits != null ? globalLimits : NO_LIMITS;
    }

    public Limits getGlobalLimits() {
        return globalLimits;
    }

    public Node yamlToNode(String yaml) {
        return preprocess(YAML_MAPPER.readValue(yaml, Node.class));
    }

    public Node jsonToNode(String json) {
        return preprocess(JSON_MAPPER.readValue(json, Node.class));
    }

    public String nodeToYaml(Node node) {
        return YAML_MAPPER.writeValueAsString(NodeToMapListOrValue.get(node));
    }

    public String nodeToSimpleYaml(Node node) {
        return YAML_MAPPER.writeValueAsString(NodeToMapListOrValue.get(node, NodeToMapListOrValue.Strategy.SIMPLE));
    }

    public String nodeToJson(Node node) {
        return JSON_MAPPER.writeValueAsString(NodeToMapListOrValue.get(node));
    }

    public String nodeToSimpleJson(Node node) {
        return JSON_MAPPER.writeValueAsString(NodeToMapListOrValue.get(node, NodeToMapListOrValue.Strategy.SIMPLE));
    }

    public String objectToYaml(Object object) {
        return nodeToYaml(objectToNode(object));
    }

    public String objectToSimpleYaml(Object object) {
        return nodeToSimpleYaml(objectToNode(object));
    }

    public String objectToJson(Object object) {
        return nodeToJson(objectToNode(object));
    }

    public String objectToSimpleJson(Object object) {
        return nodeToSimpleJson(objectToNode(object));
    }

    public <T> T clone(T object) {
        if (object == null) {
            return null;
        }

        if (object instanceof Node) {
            return (T) ((Node) object).clone();
        }

        Class<T> clazz = (Class<T>) object.getClass();
        Node node = objectToNode(object);
        Node clonedNode = node.clone();
        return nodeToObject(clonedNode, clazz);
    }

    public String calculateBlueId(Node node) {
        return BlueIdCalculator.calculateBlueId(node);
    }

    public String calculateBlueId(Object object) {
        return calculateBlueId(objectToNode(object));
    }

    public void addPreprocessingAliases(Map<String, String> aliases) {
        preprocessingAliases.putAll(aliases);
    }

    public Blue registerContractProcessor(ContractProcessor<? extends Contract> processor) {
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        if (documentProcessor == null) {
            documentProcessor = createDefaultDocumentProcessor();
        }
        documentProcessor.registerContractProcessor(processor);
        return this;
    }

    public DocumentProcessingResult processDocument(Node document, Node event) {
        return ensureDocumentProcessor().processDocument(document, event);
    }

    public DocumentProcessor getDocumentProcessor() {
        return ensureDocumentProcessor();
    }

    public Blue documentProcessor(DocumentProcessor documentProcessor) {
        if (documentProcessor == null) {
            throw new IllegalArgumentException("documentProcessor must not be null");
        }
        this.documentProcessor = documentProcessor;
        return this;
    }

    public DocumentProcessingResult initializeDocument(Node document) {
        return ensureDocumentProcessor().initializeDocument(document);
    }

    public boolean isInitialized(Node document) {
        return ensureDocumentProcessor().isInitialized(document);
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

    private DocumentProcessor ensureDocumentProcessor() {
        if (documentProcessor == null) {
            documentProcessor = createDefaultDocumentProcessor();
        }
        return documentProcessor;
    }

    private DocumentProcessor createDefaultDocumentProcessor() {
        return new DocumentProcessor();
    }

    private Limits combineWithGlobalLimits(Limits methodLimits) {
        if (globalLimits == NO_LIMITS) {
            return methodLimits;
        }

        if (methodLimits == NO_LIMITS) {
            return globalLimits;
        }

        return new CompositeLimits(globalLimits, methodLimits);
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
