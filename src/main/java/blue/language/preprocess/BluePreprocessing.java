package blue.language.preprocess;

import blue.language.model.Node;

/** Applies the deterministic Source-to-Preprocessed-Document pipeline. */
public interface BluePreprocessing {

    /**
     * Preprocesses a defensive copy of an authored Source Document.
     *
     * @param source authored source; it is not mutated
     * @return independent validated preprocessed document
     */
    Node preprocess(Node source);

    /**
     * Identifies the frozen aliases, imports, and transformation environment.
     *
     * @return stable identity of the preprocessing environment
     */
    String environmentIdentity();
}
