package blue.language.utils;

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

    public Node build(Node resolvedNode) {
        Objects.requireNonNull(resolvedNode, "resolvedNode");
        return new OverlayReconstruction().minimizedOverlay(resolvedNode);
    }
}
