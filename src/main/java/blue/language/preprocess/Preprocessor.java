package blue.language.preprocess;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.provider.BootstrapProvider;
import blue.language.provider.NodeProviderWrapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

    private final TransformationProcessorProvider processorProvider;
    private final NodeProvider nodeProvider;
    private final Map<String, String> directiveAliases;
    private final Map<String, String> environmentImports;
    private final StandardPreprocessingPipeline standardPipeline;
    private final TransformationExecutor transformationExecutor;

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
        this.transformationExecutor = new TransformationExecutor(
                standardPipeline);
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

        return transformationExecutor.execute(document, plan, context);
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
        return ReleasedTransformationCompatibilityRegistry.INSTANCE;
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
