package blue.language.preprocess;

import blue.language.model.Node;

/** Deterministic source-to-source transformation used before Blue resolution. */
public interface TransformationProcessor {

    /**
     * Applies this transformation to a source document.
     *
     * @param document source document to transform
     * @return resulting transformed document
     */
    Node process(Node document);
}
