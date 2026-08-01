package blue.language.preprocess;

import blue.language.model.Node;

import java.util.Optional;

/** Resolves a declared Blue transformation node to its deterministic processor. */
public interface TransformationProcessorProvider {

    /**
     * Resolves a declared transformation to its registered processor.
     *
     * @param transformation declared transformation node
     * @return matching processor, or an empty optional when the type is not registered
     */
    Optional<TransformationProcessor> getProcessor(Node transformation);

    /**
     * Resolves an exact transformation type and its frozen configuration.
     *
     * <p>The default bridge preserves existing providers while allowing new
     * registries to select processors directly by the verified type BlueId.
     * Implementations must not select behavior from a human-readable name.</p>
     *
     * @param exactTypeBlueId verified plain BlueId of the transformation type
     * @param exactTransformationNode defensively copied transformation node
     * @return matching processor, or an empty optional when unsupported
     */
    default Optional<TransformationProcessor> processorFor(
            String exactTypeBlueId,
            Node exactTransformationNode) {
        return getProcessor(exactTransformationNode);
    }
}
