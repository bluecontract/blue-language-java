package blue.language.preprocess;

import blue.language.model.Node;

import java.util.Optional;

public interface TransformationProcessorProvider {
    Optional<TransformationProcessor> getProcessor(Node transformation);
}