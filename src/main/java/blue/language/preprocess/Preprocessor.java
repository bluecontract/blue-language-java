package blue.language.preprocess;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.preprocess.processor.InferBasicTypesForUntypedValues;
import blue.language.preprocess.processor.ReplaceInlineValuesForTypeAttributesWithImports;
import blue.language.provider.BootstrapProvider;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.Properties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies the complete Blue Language 1.0 Source preprocessing algorithm.
 *
 * <p>Every entry point establishes and verifies the complete root
 * {@code blue} directive before executing a transformation. It then removes
 * the directive, executes the frozen transformations once in declaration
 * order, and finally applies the mandatory Language baseline. The baseline is
 * intrinsic Language behavior; it is not an injected transformation list.</p>
 */
public class Preprocessor {

    private static final String REPLACE_INLINE_TYPES_BLUE_ID =
            "27B7fuxQCS1VAptiCPc2RMkKoutP5qxkh3uDxZ7dr6Eo";
    private static final String LEGACY_REPLACE_INLINE_TYPES_BLUE_ID =
            "53yFLQ3dpuGwa2svHubDyzyhYz9RQNmctiJRdi3gRYr7";
    private static final String INFER_BASIC_TYPES_BLUE_ID =
            "FGYuTXwaoSKfZmpTysLTLsb8WzSqf43384rKZDkXhxD4";
    private static final String LEGACY_INFER_BASIC_TYPES_BLUE_ID =
            "49hrWpkoXavNmK8PpZag11zB2vYwzhQZahwioz6vDk2i";
    private static final String STANDARD_TYPE_BLUE_ID_POINTER =
            JsonPointer.append(
                    JsonPointer.append(
                            JsonPointer.ROOT,
                            Properties.OBJECT_TYPE),
                    Properties.OBJECT_BLUE_ID);

    private final TransformationProcessorProvider processorProvider;
    private final NodeProvider nodeProvider;
    private final Map<String, String> directiveAliases;
    private final Map<String, String> environmentImports;
    private final StandardPreprocessingPipeline standardPipeline;

    /**
     * Creates a preprocessor with an explicit transformation registry and
     * provider, canonical core imports, and no directive aliases.
     *
     * @param processorProvider registry used to resolve exact transformation types
     * @param nodeProvider provider used to obtain referenced directive content
     */
    public Preprocessor(
            TransformationProcessorProvider processorProvider,
            NodeProvider nodeProvider) {
        this(processorProvider, nodeProvider,
                Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * Creates a preprocessor for an explicitly declared host environment.
     *
     * <p>Directive aliases bind string-valued root {@code blue} forms to exact
     * directive BlueIds. Environment imports supplement canonical core aliases
     * for a host such as the Contracts runtime; they are not Language core.</p>
     *
     * @param processorProvider registry used to resolve exact transformation types
     * @param nodeProvider provider used to obtain referenced directive content
     * @param directiveAliases string directive aliases mapped to exact BlueIds
     * @param environmentImports host type aliases mapped to exact BlueIds
     */
    public Preprocessor(
            TransformationProcessorProvider processorProvider,
            NodeProvider nodeProvider,
            Map<String, String> directiveAliases,
            Map<String, String> environmentImports) {
        this.processorProvider = Objects.requireNonNull(
                processorProvider, "processorProvider");
        this.nodeProvider = NodeProviderWrapper.wrap(
                Objects.requireNonNull(nodeProvider, "nodeProvider"));
        this.directiveAliases = immutableCopy(directiveAliases);
        this.environmentImports = immutableCopy(environmentImports);
        this.standardPipeline = new StandardPreprocessingPipeline();
    }

    /**
     * Creates a preprocessor with the standard explicit transformation
     * registry, canonical core imports, and no directive aliases.
     *
     * @param nodeProvider provider used to obtain referenced directive content
     */
    public Preprocessor(NodeProvider nodeProvider) {
        this(getStandardProvider(), nodeProvider);
    }

    /**
     * Creates a preprocessor backed by the bootstrap provider and standard
     * explicit transformation registry.
     */
    public Preprocessor() {
        this(BootstrapProvider.INSTANCE);
    }

    /**
     * Applies the complete mandatory preprocessing algorithm.
     *
     * @param document parsed Source Document
     * @return independent validated Preprocessed Document
     */
    public Node preprocess(Node document) {
        Objects.requireNonNull(document, "document");
        PreprocessingLimits.requireGraphWithinBounds(
                document, "Source Document");
        PreprocessingDirectiveResolver resolver =
                new PreprocessingDirectiveResolver(
                        processorProvider,
                        nodeProvider,
                        directiveAliases,
                        environmentImports);
        PreprocessingPlan plan = resolver.resolve(document);
        PreprocessingContext context = new PreprocessingContext(
                plan.effectiveImports(), nodeProvider);

        Node working = document.clone();
        working.blue(null);
        for (TransformationSnapshot transformation
                : plan.transformations()) {
            working = transformation.apply(working, context);
            PreprocessingLimits.requireGraphWithinBounds(
                    working, "transformation output");
            standardPipeline.rejectBlueDirective(working);
        }
        Node preprocessed = standardPipeline.apply(
                working, plan.effectiveImports());
        PreprocessingLimits.requireGraphWithinBounds(
                preprocessed, "Preprocessed Document");
        return preprocessed;
    }

    /**
     * Returns the registry for the released explicit source transformations
     * retained by this implementation.
     *
     * <p>These processors run only when a directive explicitly lists a node
     * with one of their exact type BlueIds. They are never injected as the
     * Language baseline.</p>
     *
     * @return standard explicit transformation registry
     */
    public static TransformationProcessorProvider getStandardProvider() {
        return new TransformationProcessorProvider() {
            @Override
            public Optional<TransformationProcessor> getProcessor(
                    Node transformation) {
                if (transformation == null) {
                    return Optional.empty();
                }
                String typeBlueId = transformation.getAsText(
                        STANDARD_TYPE_BLUE_ID_POINTER);
                return processorFor(typeBlueId, transformation);
            }

            @Override
            public Optional<TransformationProcessor> processorFor(
                    String exactTypeBlueId,
                    Node exactTransformationNode) {
                if (REPLACE_INLINE_TYPES_BLUE_ID.equals(exactTypeBlueId)
                        || LEGACY_REPLACE_INLINE_TYPES_BLUE_ID
                        .equals(exactTypeBlueId)) {
                    return Optional.of(
                            new ReplaceInlineValuesForTypeAttributesWithImports(
                                    exactTransformationNode));
                }
                if (INFER_BASIC_TYPES_BLUE_ID.equals(exactTypeBlueId)
                        || LEGACY_INFER_BASIC_TYPES_BLUE_ID
                        .equals(exactTypeBlueId)) {
                    return Optional.of(
                            new InferBasicTypesForUntypedValues());
                }
                return Optional.empty();
            }
        };
    }

    private static Map<String, String> immutableCopy(
            Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(values));
    }

}
