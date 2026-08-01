package blue.language.identity;

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

    /** Creates a canonical identity projection builder. */
    public CanonicalIdentityInputBuilder() {
    }

    /**
     * Reconstructs canonical identity input without mutating either source.
     *
     * @param resolvedNode resolved semantic node
     * @param preprocessedSource exact preprocessed source representation
     * @return canonical identity input
     * @throws NullPointerException if either argument is {@code null}
     */
    public Node build(Node resolvedNode, Node preprocessedSource) {
        Objects.requireNonNull(resolvedNode, "resolvedNode");
        Objects.requireNonNull(preprocessedSource, "preprocessedSource");
        return new CanonicalIdentityInputReconstructor()
                .reconstruct(resolvedNode, preprocessedSource);
    }
}
