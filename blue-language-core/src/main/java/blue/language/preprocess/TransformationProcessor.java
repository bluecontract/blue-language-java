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

    /**
     * Applies this transformation with the immutable preprocessing context
     * established before any declared transformation executes.
     *
     * <p>The default bridge preserves source and binary compatibility for
     * context-free processors. A transformation whose exact specification
     * permits access to imports or verified provider evidence may override
     * this method.</p>
     *
     * @param document source document to transform
     * @param context immutable preprocessing context
     * @return resulting transformed document
     */
    default Node process(Node document, PreprocessingContext context) {
        return process(document);
    }
}
