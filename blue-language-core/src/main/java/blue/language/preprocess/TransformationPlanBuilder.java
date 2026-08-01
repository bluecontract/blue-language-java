package blue.language.preprocess;

import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.model.wire.BlueLanguageConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Resolves and validates every transformation before any one is executed. */
public final class TransformationPlanBuilder {

    private final TransformationProcessorProvider processorProvider;
    private final DirectiveResolver resolver;
    private final DirectiveValidator validator;

    /**
     * Creates a plan builder for one exact transformation registry.
     *
     * @param processorProvider registry used to resolve transformation types
     * @param resolver resolver used to fetch exact transformation resources
     * @param validator validator applied before any transformation executes
     */
    public TransformationPlanBuilder(
            TransformationProcessorProvider processorProvider,
            DirectiveResolver resolver,
            DirectiveValidator validator) {
        this.processorProvider = processorProvider;
        this.resolver = resolver;
        this.validator = validator;
    }

    /**
     * Freezes the declaration-order plan. Failure leaves execution untouched.
     */
    List<TransformationSnapshot> build(
            Node directive, List<String> dependencies) {
        Node transformations = property(
                directive, BlueLanguageConstants.BLUE_DIRECTIVE_TRANSFORMATIONS);
        if (transformations == null) {
            return Collections.emptyList();
        }
        if (transformations.isReferenceOnly()) {
            String blueId = BlueIds.requirePlainBlueId(
                    transformations.getBlueId(),
                    "blue.transformations.blueId");
            resolver.addDependency(dependencies, blueId);
            transformations = resolver.fetchExactNode(
                    blueId, "blue.transformations");
        }
        validator.validateTransformationList(transformations);

        List<TransformationSnapshot> result = new ArrayList<>();
        List<Node> items = transformations.getItems();
        if (items == null) {
            return result;
        }
        PreprocessingLimits.requireTransformationCount(items.size());
        for (int index = 0; index < items.size(); index++) {
            result.add(preflight(items.get(index), index, dependencies));
        }
        return result;
    }

    private TransformationSnapshot preflight(
            Node declared,
            int index,
            List<String> dependencies) {
        Node transformation = declared;
        String transformationBlueId = null;
        if (transformation != null && transformation.isReferenceOnly()) {
            transformationBlueId = BlueIds.requirePlainBlueId(
                    transformation.getBlueId(),
                    "blue.transformations." + index + ".blueId");
            resolver.addDependency(dependencies, transformationBlueId);
            transformation = resolver.fetchExactNode(
                    transformationBlueId,
                    "blue transformation " + index);
        } else if (transformation != null) {
            transformation = transformation.clone();
        }
        if (transformation == null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue.transformations\" cannot contain null.");
        }
        validator.rejectAnyBlue(
                transformation, "blue.transformations/" + index);
        Node type = transformation.getType();
        if (type == null || !type.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Reserved preprocessing transformation type must identify one exact type BlueId at blue.transformations/"
                            + index + ".");
        }
        String typeBlueId = BlueIds.requirePlainBlueId(
                type.getBlueId(),
                "blue.transformations." + index + ".type.blueId");
        Optional<TransformationProcessor> processor =
                processorProvider.processorFor(
                        typeBlueId, transformation.clone());
        if (!processor.isPresent()) {
            throw new IllegalArgumentException(
                    "Unsupported preprocessing transform type: "
                            + typeBlueId);
        }
        if (transformationBlueId == null) {
            transformationBlueId =
                    DirectBlueIdCalculator.calculateBlueId(transformation);
        }
        return new TransformationSnapshot(
                transformationBlueId,
                typeBlueId,
                transformation,
                processor.get());
    }

    private Node property(Node node, String key) {
        return node.getProperties() == null
                ? null : node.getProperties().get(key);
    }
}
