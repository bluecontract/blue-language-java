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
}
