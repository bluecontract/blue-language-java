package blue.language.preprocess;

import blue.language.model.Node;
import blue.language.preprocess.processor.InferBasicTypesForUntypedValues;
import blue.language.preprocess.processor.ReplaceInlineValuesForTypeAttributesWithImports;

import java.util.Optional;

import static blue.language.utils.Properties.OBJECT_BLUE_ID;
import static blue.language.utils.Properties.OBJECT_TYPE;

/**
 * Immutable registry for explicitly authored, already-released transform IDs.
 *
 * <p>Recognition occurs only when a Source directive names one of these exact
 * types. Nothing in this registry is injected into mandatory baseline
 * preprocessing.</p>
 */
public final class ReleasedTransformationCompatibilityRegistry
        implements TransformationProcessorProvider {

    /** Released inline-type substitution transform. */
    public static final String REPLACE_INLINE_TYPES_BLUE_ID =
            "27B7fuxQCS1VAptiCPc2RMkKoutP5qxkh3uDxZ7dr6Eo";
    /** Historical identity retained for already-published source content. */
    public static final String LEGACY_REPLACE_INLINE_TYPES_BLUE_ID =
            "53yFLQ3dpuGwa2svHubDyzyhYz9RQNmctiJRdi3gRYr7";
    /** Released primitive-inference transform. */
    public static final String INFER_BASIC_TYPES_BLUE_ID =
            "FGYuTXwaoSKfZmpTysLTLsb8WzSqf43384rKZDkXhxD4";
    /** Historical identity retained for already-published source content. */
    public static final String LEGACY_INFER_BASIC_TYPES_BLUE_ID =
            "49hrWpkoXavNmK8PpZag11zB2vYwzhQZahwioz6vDk2i";

    /** Shared stateless immutable registry. */
    public static final ReleasedTransformationCompatibilityRegistry INSTANCE =
            new ReleasedTransformationCompatibilityRegistry();

    private ReleasedTransformationCompatibilityRegistry() {
    }

    @Override
    public Optional<TransformationProcessor> getProcessor(
            Node transformation) {
        if (transformation == null
                || transformation.getType() == null) {
            return Optional.empty();
        }
        return processorFor(
                transformation.getType().getBlueId(), transformation);
    }

    @Override
    public Optional<TransformationProcessor> processorFor(
            String exactTypeBlueId, Node exactTransformationNode) {
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
            return Optional.of(new InferBasicTypesForUntypedValues());
        }
        return Optional.empty();
    }
}
