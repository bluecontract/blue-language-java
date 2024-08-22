package blue.language.preprocess;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.preprocess.processor.InferBasicTypesForUntypedValues;
import blue.language.preprocess.processor.ReplaceInlineValuesForTypeAttributesWithImports;
import blue.language.provider.BootstrapProvider;
import blue.language.utils.NodeExtender;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.limits.PathLimits;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Preprocessor {

    public static final String DEFAULT_BLUE_BLUE_ID = "5y7V3sYaj72uet7gc3WFjADBUhFe4bGWzQtmZ8v3wr8z";

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
        return preprocess(document, null);
    }

    public Node preprocessWithDefaultBlue(Node document) {
        return preprocess(document, defaultSimpleBlue);
    }

    public Node preprocess(Node document, Node defaultBlue) {
        Node processedDocument = document.clone();
        Node blueNode = processedDocument.getBlue();

        if (blueNode == null) {
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

    public static TransformationProcessorProvider getStandardProvider() {
        return new TransformationProcessorProvider() {
            private static final String REPLACE_INLINE_TYPES = "648mybP3oHoi8kx6HvYzw6Jtuz6BjUpGPk1r2DpCq7e4";
            private static final String INFER_BASIC_TYPES = "45nboJisxsXXE2UBA2cY7V56roZ13u9MtCMcvv4iYrFp";

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
}
