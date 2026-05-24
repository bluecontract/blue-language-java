package blue.language.preprocess;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.preprocess.processor.InferBasicTypesForUntypedValues;
import blue.language.preprocess.processor.NormalizeListPlaceholders;
import blue.language.preprocess.processor.ReplaceInlineValuesForTypeAttributesWithImports;
import blue.language.provider.BootstrapProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.NodeExtender;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.Nodes;
import blue.language.utils.limits.PathLimits;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static blue.language.utils.Properties.CORE_TYPE_NAME_TO_BLUE_ID_MAP;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Preprocessor {

    public static final String DEFAULT_BLUE_BLUE_ID = calculateDefaultBlueBlueId();

    private TransformationProcessorProvider processorProvider;
    private NodeProvider nodeProvider;
    private Node defaultSimpleBlue;

    public Preprocessor(TransformationProcessorProvider processorProvider, NodeProvider nodeProvider) {
        this.processorProvider = processorProvider;
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        loadDefaultSimpleBlue();
    }

    public Preprocessor(NodeProvider nodeProvider) {
        this(getStandardProvider(), nodeProvider);
    }

    public Preprocessor() {
        this(BootstrapProvider.INSTANCE);
    }

    public Node preprocess(Node document) {
        return preprocessWithDefaultBlue(document);
    }

    public Node preprocessWithoutDefaultBlue(Node document) {
        return preprocess(document, null);
    }

    public Node preprocessWithDefaultBlue(Node document) {
        return preprocess(document, defaultSimpleBlue);
    }

    public Node preprocess(Node document, Node defaultBlue) {
        Node processedDocument = new NormalizeListPlaceholders().process(document.clone());
        processedDocument = applyPortableImports(processedDocument);
        Node blueNode = processedDocument.getBlue();

        if (blueNode == null && defaultBlue != null) {
            blueNode = defaultBlue.clone();
        }

        if (blueNode != null) {

            new NodeExtender(nodeProvider).extend(blueNode, PathLimits.withSinglePath("/*"));

            if (blueNode.getItems() != null) {
                List<Node> transformations = blueNode.getItems();

                for (Node transformation : transformations) {
                    Optional<TransformationProcessor> processor = processorProvider.getProcessor(transformation);
                    if (processor.isPresent()) {
                        processedDocument = processor.get().process(processedDocument);
                    } else {
                        throw new IllegalArgumentException("No processor found for transformation: " + transformation);
                    }
                }

                processedDocument.blue(null);
            }
        }

        return processedDocument;
    }

    private Node applyPortableImports(Node document) {
        Node blueNode = document.getBlue();
        if (blueNode == null || blueNode.getProperties() == null || !blueNode.getProperties().containsKey("imports")) {
            return document;
        }

        Node importsNode = blueNode.getProperties().get("imports");
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
            String coreBlueId = CORE_TYPE_NAME_TO_BLUE_ID_MAP.get(alias);
            if (coreBlueId != null && !coreBlueId.equals(blueId)) {
                throw new IllegalArgumentException("\"blue.imports\" cannot redefine core alias \"" + alias + "\".");
            }
            mappings.put(alias, blueId);
        }

        Node transformed = new ReplaceInlineValuesForTypeAttributesWithImports(mappings).process(document);
        Node transformedBlue = transformed.getBlue();
        if (transformedBlue != null && transformedBlue.getProperties() != null) {
            Map<String, Node> remainingProperties = new LinkedHashMap<>(transformedBlue.getProperties());
            remainingProperties.remove("imports");
            transformedBlue.properties(remainingProperties.isEmpty() ? null : remainingProperties);
        }
        if (transformedBlue != null && Nodes.isEmptyNode(transformedBlue)) {
            transformed.blue(null);
        }
        return transformed;
    }

    public static TransformationProcessorProvider getStandardProvider() {
        return new TransformationProcessorProvider() {
            private static final String REPLACE_INLINE_TYPES = "53yFLQ3dpuGwa2svHubDyzyhYz9RQNmctiJRdi3gRYr7";
            private static final String INFER_BASIC_TYPES = "49hrWpkoXavNmK8PpZag11zB2vYwzhQZahwioz6vDk2i";

            @Override
            public Optional<TransformationProcessor> getProcessor(Node transformation) {
                String blueId = transformation.getAsText("/type/blueId");
                if (REPLACE_INLINE_TYPES.equals(blueId))
                    return Optional.of(new ReplaceInlineValuesForTypeAttributesWithImports(transformation));
                else if (INFER_BASIC_TYPES.equals(blueId))
                    return Optional.of(new InferBasicTypesForUntypedValues());
                return Optional.empty();
            }
        };
    }

    private void loadDefaultSimpleBlue() {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("transformation/DefaultBlue.blue")) {
            if (inputStream == null) {
                throw new RuntimeException("Unable to find DefaultBlue.blue in classpath");
            }
            this.defaultSimpleBlue = YAML_MAPPER.readValue(inputStream, Node.class);
        } catch (IOException e) {
            throw new RuntimeException("Error loading DefaultBlue.blue from classpath", e);
        }
    }

    private static String calculateDefaultBlueBlueId() {
        try (InputStream inputStream = Preprocessor.class.getClassLoader().getResourceAsStream("transformation/DefaultBlue.blue")) {
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
