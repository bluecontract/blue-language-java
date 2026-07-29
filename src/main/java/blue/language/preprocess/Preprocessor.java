package blue.language.preprocess;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.preprocess.processor.InferBasicTypesForUntypedValues;
import blue.language.preprocess.processor.NormalizeListPlaceholders;
import blue.language.preprocess.processor.ReplaceInlineValuesForTypeAttributesWithImports;
import blue.language.provider.BootstrapProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodeExtender;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.Nodes;
import blue.language.utils.Properties;
import blue.language.utils.limits.PathLimits;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static blue.language.utils.Properties.DEFAULT_BLUE_TYPE_NAME_TO_BLUE_ID_MAP;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

/**
 * Applies Blue source transformations before resolution.
 *
 * <p>The standard path normalizes list placeholders, applies the bundled
 * Default Blue aliases and primitive inference, resolves portable
 * {@code blue.imports}, then executes explicitly declared transformations.
 * Input documents are cloned before transformation.</p>
 */
public class Preprocessor {

    /** Classpath resource containing the released Default Blue directives. */
    public static final String DEFAULT_BLUE_RESOURCE =
            "transformation/DefaultBlue.blue";
    /** Structural BlueId of the bundled Default Blue transformation list. */
    public static final String DEFAULT_BLUE_BLUE_ID = calculateDefaultBlueBlueId();
    private static final String STANDARD_TYPE_BLUE_ID_POINTER =
            JsonPointer.append(
                    JsonPointer.append(
                            JsonPointer.ROOT,
                            Properties.OBJECT_TYPE),
                    Properties.OBJECT_BLUE_ID);

    private TransformationProcessorProvider processorProvider;
    private NodeProvider nodeProvider;
    private Node defaultSimpleBlue;

    /**
     * Creates a preprocessor with an explicit transformation registry and provider.
     *
     * @param processorProvider registry used to resolve declared transformations
     * @param nodeProvider provider used to resolve transformation references
     */
    public Preprocessor(TransformationProcessorProvider processorProvider, NodeProvider nodeProvider) {
        this.processorProvider = processorProvider;
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        loadDefaultSimpleBlue();
    }

    /**
     * Creates a preprocessor with the standard transformation registry.
     *
     * @param nodeProvider provider used to resolve transformation references
     */
    public Preprocessor(NodeProvider nodeProvider) {
        this(getStandardProvider(), nodeProvider);
    }

    /**
     * Creates a preprocessor backed by the bootstrap provider and standard registry.
     */
    public Preprocessor() {
        this(BootstrapProvider.INSTANCE);
    }

    /**
     * Applies the complete standard preprocessing pipeline.
     *
     * @param document source document to preprocess
     * @return transformed clone of the source document
     */
    public Node preprocess(Node document) {
        return preprocessWithDefaultBlue(document);
    }

    /**
     * Applies declared transformations without Default Blue aliases or inference.
     *
     * @param document source document to preprocess
     * @return transformed clone of the source document
     */
    public Node preprocessWithoutDefaultBlue(Node document) {
        return preprocess(document, null);
    }

    /**
     * Applies the complete standard preprocessing pipeline.
     *
     * @param document source document to preprocess
     * @return transformed clone of the source document
     */
    public Node preprocessWithDefaultBlue(Node document) {
        return preprocess(document, defaultSimpleBlue);
    }

    /**
     * Applies preprocessing and uses a non-null {@code defaultBlue} as the
     * signal to enable the standard baseline transformations.
     *
     * @param document source document to preprocess
     * @param defaultBlue non-null to enable standard aliases and primitive inference
     * @return transformed clone of the source document
     */
    public Node preprocess(Node document, Node defaultBlue) {
        Node processedDocument = new NormalizeListPlaceholders().process(document.clone());
        if (defaultBlue != null) {
            processedDocument = applyStandardBaseline(processedDocument);
        }
        processedDocument = applyPortableImports(processedDocument);

        Node blueNode = processedDocument.getBlue();
        if (blueNode != null) {
            processedDocument = applyDeclaredBlueTransformations(processedDocument, blueNode);
        }

        return processedDocument;
    }

    private Node applyStandardBaseline(Node document) {
        Node transformed = new ReplaceInlineValuesForTypeAttributesWithImports(DEFAULT_BLUE_TYPE_NAME_TO_BLUE_ID_MAP)
                .process(document);
        return new InferBasicTypesForUntypedValues().process(transformed);
    }

    private Node applyDeclaredBlueTransformations(Node processedDocument, Node blueNode) {
        Node extendedBlue = blueNode.clone();
        new NodeExtender(nodeProvider).extend(extendedBlue, PathLimits.withSinglePath("/*"));

        if (extendedBlue.getItems() != null) {
            List<Node> transformations = extendedBlue.getItems();

            for (Node transformation : transformations) {
                Optional<TransformationProcessor> processor = processorProvider.getProcessor(transformation);
                if (processor.isPresent()) {
                    processedDocument = processor.get().process(processedDocument);
                } else {
                    throw new IllegalArgumentException("No processor found for transformation: " + transformation);
                }
            }
        }

        processedDocument.blue(null);
        return processedDocument;
    }

    private Node applyPortableImports(Node document) {
        Node blueNode = document.getBlue();
        if (blueNode == null || blueNode.getProperties() == null
                || !blueNode.getProperties().containsKey(
                Properties.BLUE_DIRECTIVE_IMPORTS)) {
            return document;
        }

        Node importsNode = blueNode.getProperties().get(
                Properties.BLUE_DIRECTIVE_IMPORTS);
        if (importsNode == null || importsNode.getProperties() == null || importsNode.getValue() != null
                || importsNode.getItems() != null || importsNode.getBlueId() != null) {
            throw new IllegalArgumentException("\"blue.imports\" must be an object mapping aliases to pure references.");
        }

        Map<String, String> mappings = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : importsNode.getProperties().entrySet()) {
            String alias = entry.getKey();
            Node reference = entry.getValue();
            if (reference == null || !reference.isReferenceOnly()) {
                throw new IllegalArgumentException("\"blue.imports." + alias + "\" must be a pure reference.");
            }
            String blueId = BlueIds.requirePlainBlueId(reference.getBlueId(), "blue.imports." + alias);
            String defaultBlueId = DEFAULT_BLUE_TYPE_NAME_TO_BLUE_ID_MAP.get(alias);
            if (defaultBlueId != null && !defaultBlueId.equals(blueId)) {
                throw new IllegalArgumentException("\"blue.imports\" cannot redefine default Blue alias \"" + alias + "\".");
            }
            mappings.put(alias, blueId);
        }

        Node transformed = new ReplaceInlineValuesForTypeAttributesWithImports(mappings).process(document);
        Node transformedBlue = transformed.getBlue();
        if (transformedBlue != null && transformedBlue.getProperties() != null) {
            Map<String, Node> remainingProperties = new LinkedHashMap<>(transformedBlue.getProperties());
            remainingProperties.remove(
                    Properties.BLUE_DIRECTIVE_IMPORTS);
            transformedBlue.properties(remainingProperties.isEmpty() ? null : remainingProperties);
        }
        if (transformedBlue != null && Nodes.isEmptyNode(transformedBlue)) {
            transformed.blue(null);
        }
        return transformed;
    }

    /**
     * Returns the built-in registry for current and legacy standard transformations.
     *
     * @return standard transformation processor registry
     */
    public static TransformationProcessorProvider getStandardProvider() {
        return new TransformationProcessorProvider() {
            private static final String REPLACE_INLINE_TYPES = "27B7fuxQCS1VAptiCPc2RMkKoutP5qxkh3uDxZ7dr6Eo";
            private static final String LEGACY_REPLACE_INLINE_TYPES = "53yFLQ3dpuGwa2svHubDyzyhYz9RQNmctiJRdi3gRYr7";
            private static final String INFER_BASIC_TYPES = "FGYuTXwaoSKfZmpTysLTLsb8WzSqf43384rKZDkXhxD4";
            private static final String LEGACY_INFER_BASIC_TYPES = "49hrWpkoXavNmK8PpZag11zB2vYwzhQZahwioz6vDk2i";

            @Override
            public Optional<TransformationProcessor> getProcessor(Node transformation) {
                String blueId = transformation.getAsText(
                        STANDARD_TYPE_BLUE_ID_POINTER);
                if (REPLACE_INLINE_TYPES.equals(blueId) || LEGACY_REPLACE_INLINE_TYPES.equals(blueId))
                    return Optional.of(new ReplaceInlineValuesForTypeAttributesWithImports(transformation));
                else if (INFER_BASIC_TYPES.equals(blueId) || LEGACY_INFER_BASIC_TYPES.equals(blueId))
                    return Optional.of(new InferBasicTypesForUntypedValues());
                return Optional.empty();
            }
        };
    }

    private void loadDefaultSimpleBlue() {
        try (InputStream inputStream = getClass()
                .getClassLoader()
                .getResourceAsStream(DEFAULT_BLUE_RESOURCE)) {
            if (inputStream == null) {
                throw new RuntimeException("Unable to find DefaultBlue.blue in classpath");
            }
            this.defaultSimpleBlue = YAML_MAPPER.readValue(inputStream, Node.class);
        } catch (IOException e) {
            throw new RuntimeException("Error loading DefaultBlue.blue from classpath", e);
        }
    }

    private static String calculateDefaultBlueBlueId() {
        try (InputStream inputStream = Preprocessor.class
                .getClassLoader()
                .getResourceAsStream(DEFAULT_BLUE_RESOURCE)) {
            if (inputStream == null) {
                throw new RuntimeException("Unable to find DefaultBlue.blue in classpath");
            }
            Node defaultBlue = YAML_MAPPER.readValue(inputStream, Node.class);
            if (defaultBlue.getItems() != null) {
                return BlueIdCalculator.calculateBlueId(defaultBlue.getItems());
            }
            return BlueIdCalculator.calculateBlueId(defaultBlue);
        } catch (IOException e) {
            throw new RuntimeException("Error loading DefaultBlue.blue from classpath", e);
        }
    }
}
