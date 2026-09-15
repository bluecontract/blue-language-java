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
}
