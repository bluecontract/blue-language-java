package blue.language.identity;

import blue.language.model.Node;

import java.util.Objects;

/**
 * Builds the strict canonical identity input for a completed resolved node.
 *
 * <p>Canonical identity reconstruction requires the completed resolved view,
 * the exact preprocessed source that produced it, and resolver-issued
 * effective-type identity evidence. Matching {@code blueId} strings on source
 * and resolved nodes are not proof of materialization.</p>
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
     * @param typeIdentities complete resolver-issued effective-type evidence
     * @return canonical identity input
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if resolution or type evidence is incomplete
     */
    public Node build(
            Node resolvedNode,
            Node preprocessedSource,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(resolvedNode, "resolvedNode");
        Objects.requireNonNull(preprocessedSource, "preprocessedSource");
        Objects.requireNonNull(typeIdentities, "typeIdentities")
                .requireCompleteCoverage();
        return new CanonicalIdentityInputReconstructor(typeIdentities)
                .reconstruct(resolvedNode, preprocessedSource);
    }

    /**
     * Reconstructs one completely resolved inline type while its enclosing
     * resolver invocation is still collecting evidence.
     *
     * <p>This method does not require whole-invocation coverage. Every nested
     * effective type used by the supplied type must nevertheless already have
     * resolver-issued evidence, so derivation remains bottom-up and
     * fail-closed.</p>
     *
     * @param resolvedType completed effective type
     * @param authoredType exact preprocessed inline declaration
     * @param typeIdentities active resolver-issued evidence lookup
     * @return canonical identity input of the type itself
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if canonical evidence is absent for a
     *         nested completed effective type
     */
    public Node buildResolvedType(
            Node resolvedType,
            Node authoredType,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(resolvedType, "resolvedType");
        Objects.requireNonNull(authoredType, "authoredType");
        Objects.requireNonNull(typeIdentities, "typeIdentities");
        return new CanonicalIdentityInputReconstructor(typeIdentities)
                .reconstruct(resolvedType, authoredType);
    }
}
