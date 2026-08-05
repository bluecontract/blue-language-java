package blue.language.resolve;

import blue.language.model.Node;

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
     * Returns a new minimized author-facing overlay.
     *
     * @param resolvedNode completed resolved node to reconstruct
     * @return new minimized overlay
     * @throws NullPointerException if {@code resolvedNode} is {@code null}
     */
    public Node build(Node resolvedNode) {
        Objects.requireNonNull(resolvedNode, "resolvedNode");
        return new MinimizedOverlayReconstructor()
                .reconstruct(resolvedNode);
    }
}
