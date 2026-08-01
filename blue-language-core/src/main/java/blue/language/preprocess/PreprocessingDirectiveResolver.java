package blue.language.preprocess;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

/**
 * Compatibility composition for resolving one complete preprocessing plan.
 *
 * <p>Fetching, validation, imports, and transformation preflight live in
 * focused collaborators. The class remains as the stable plan-building entry
 * point while carrying no transformation execution behavior.</p>
 */
public final class PreprocessingDirectiveResolver {

    private final DirectiveResolver directiveResolver;
    private final DirectiveValidator directiveValidator;
    private final ImportMapBuilder importMapBuilder;
    private final TransformationPlanBuilder transformationPlanBuilder;

    /**
     * Creates a resolver for one declared preprocessing environment.
     * A {@code null} alias or import map configures an empty mapping.
     *
     * @param processorProvider registry used to resolve transformation types
     * @param verifiedProvider identity-verifying provider for exact resources
     * @param directiveAliases directive alias-to-BlueId mappings, or
     *        {@code null} for none
     * @param environmentImports environment alias-to-BlueId mappings, or
     *        {@code null} for none
     * @throws NullPointerException when {@code processorProvider} or
     *         {@code verifiedProvider} is {@code null}
     * @throws IllegalArgumentException when an alias is empty or maps to a
     *         non-canonical BlueId
     */
    public PreprocessingDirectiveResolver(
            TransformationProcessorProvider processorProvider,
            NodeProvider verifiedProvider,
            Map<String, String> directiveAliases,
            Map<String, String> environmentImports) {
        Objects.requireNonNull(processorProvider, "processorProvider");
        this.directiveResolver = new DirectiveResolver(
                verifiedProvider, directiveAliases);
        this.directiveValidator = new DirectiveValidator();
        this.importMapBuilder = new ImportMapBuilder(
                directiveResolver,
                directiveValidator,
                environmentImports);
        this.transformationPlanBuilder = new TransformationPlanBuilder(
                processorProvider,
                directiveResolver,
                directiveValidator);
    }

    /**
     * Establishes the complete immutable plan without mutating Source.
     *
     * @param source Source Document whose root directive is resolved
     * @return the immutable preprocessing plan and exact dependencies
     * @throws NullPointerException when {@code source} is {@code null}
     * @throws IllegalArgumentException when the directive, imports, aliases,
     *         transformations, or returned provider evidence is invalid
     * @throws blue.language.provider.ProviderUnavailableException when an
     *         exact preprocessing resource cannot currently be fetched
     */
    public PreprocessingPlan resolve(Node source) {
        Objects.requireNonNull(source, "source");
        directiveValidator.validateSource(source);
        DirectiveResolver.ResolvedDirective resolved =
                directiveResolver.resolveRootDirective(source);
        directiveValidator.validateDirective(resolved.directive());
        Map<String, String> imports = importMapBuilder.build(
                resolved.directive(), resolved.dependencies());
        java.util.List<TransformationSnapshot> transformations =
                transformationPlanBuilder.build(
                        resolved.directive(), resolved.dependencies());
        return new PreprocessingPlan(
                resolved.blueId(),
                imports,
                transformations,
                new ArrayList<>(new LinkedHashSet<>(
                        resolved.dependencies())));
    }
}
