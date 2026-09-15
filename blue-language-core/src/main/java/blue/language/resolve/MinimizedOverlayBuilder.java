package blue.language.resolve;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Builds an author-facing overlay that resolves to a completed node's meaning.
 *
 * <p>This operation is intentionally distinct from canonical identity
 * construction: it may omit derivable content and therefore must not be used
 * as Content BlueId input.</p>
 */
public final class MinimizedOverlayBuilder {

    /**
     * Creates a minimized author-facing overlay builder.
     */
    public MinimizedOverlayBuilder() {
    }

    /**
     * Returns a minimized overlay for a resolved subtree whose canonical type
     * evidence was issued by the same complete resolution.
     *
     * @param resolvedNode immutable completed subtree
     * @param typeIdentities resolver-issued effective-type evidence
     * @return mutable minimized author-facing overlay
     * @throws NullPointerException if an argument is null
     * @throws IllegalStateException if required canonical type evidence is
     *         unavailable
     */
    public Node build(
            FrozenNode resolvedNode,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(resolvedNode, "resolvedNode");
        return new MinimizedOverlayReconstructor(
                Objects.requireNonNull(typeIdentities, "typeIdentities"))
                .reconstruct(resolvedNode.toNode());
    }

    /**
     * Returns a minimized overlay retaining the supplied Source provenance.
     *
     * <p>The Source must be the preprocessed input of the same complete
     * resolution. In particular, an explicitly authored custom child type is
     * not interchangeable with an enclosing field's inherited type constraint.
     * Neither input is mutated.</p>
     *
     * @param resolvedNode immutable completed subtree
     * @param preprocessedSource exact preprocessed input of this resolution
     * @param typeIdentities resolver-issued effective-type evidence
     * @return mutable minimized author-facing overlay
     * @throws NullPointerException if an argument is null
     * @throws IllegalStateException if required canonical type evidence is unavailable
     */
    public Node build(
            FrozenNode resolvedNode,
            Node preprocessedSource,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(resolvedNode, "resolvedNode");
        Objects.requireNonNull(preprocessedSource, "preprocessedSource");
        return new MinimizedOverlayReconstructor(
                Objects.requireNonNull(typeIdentities, "typeIdentities"))
                .reconstruct(resolvedNode.toNode(), preprocessedSource);
    }
}
