package blue.language.utils;

import blue.language.model.Node;

import java.util.Objects;

/**
 * Builds the strict canonical identity input for a completed resolved node.
 *
 * <p>Canonical identity reconstruction requires both the completed resolved
 * view and the exact preprocessed source that produced it. The source retains
 * provenance, including pure references and explicit metadata, which cannot be
 * recovered from resolved content alone.</p>
 */
public final class CanonicalIdentityInputBuilder {

    public Node build(Node resolvedNode, Node preprocessedSource) {
        Objects.requireNonNull(resolvedNode, "resolvedNode");
        Objects.requireNonNull(preprocessedSource, "preprocessedSource");
        return new OverlayReconstruction()
                .canonicalIdentityInput(resolvedNode, preprocessedSource);
    }
}
