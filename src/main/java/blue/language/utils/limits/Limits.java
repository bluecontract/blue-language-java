package blue.language.utils.limits;

import blue.language.model.Node;

import java.util.List;

/**
 * Stateful policy consulted while expanding and merging a Blue graph.
 *
 * <p>Traversal must pair each accepted
 * {@link #enterPathSegment(String, Node)} with one {@link #exitPathSegment()}.
 * Implementations may use that balanced state to evaluate descendant paths.</p>
 */
public interface Limits {

    /** Shared stateless policy that allows all traversal and reconstruction. */
    Limits NO_LIMITS = new NoLimits();

    /**
     * Tests whether reference expansion may enter a segment.
     *
     * @param pathSegment candidate path segment
     * @param currentNode node at the current traversal position
     * @return whether expansion is allowed
     */
    default boolean shouldExpandPathSegment(String pathSegment, Node currentNode) {
        return shouldExtendPathSegment(pathSegment, currentNode);
    }

    /**
     * Compatibility name for {@link #shouldExpandPathSegment(String, Node)}.
     *
     * @param pathSegment candidate path segment
     * @param currentNode node at the current traversal position
     * @return whether expansion is allowed
     * <p>Implementations must override this method or its canonical
     * counterpart. The reciprocal defaults allow both existing 1.x
     * implementations and new expansion-named implementations to work.</p>
     *
     * <p>New code should implement and call
     * {@link #shouldExpandPathSegment(String, Node)}. This descriptor is
     * retained only for the frozen 1.x binary API.</p>
     */
    default boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
        return shouldExpandPathSegment(pathSegment, currentNode);
    }

    /**
     * Tests whether merging may enter a segment.
     *
     * @param pathSegment candidate path segment
     * @param currentNode node at the current traversal position
     * @return whether merging is allowed
     */
    boolean shouldMergePathSegment(String pathSegment, Node currentNode);

    /**
     * Tests whether a list-history fragment may be reconstructed.
     *
     * @param currentNode current list node
     * @param items candidate reconstructed items
     * @return whether reconstruction is allowed
     */
    default boolean shouldReconstructList(Node currentNode, List<Node> items) {
        return true;
    }

    /**
     * Records entry when no current-node context is available.
     *
     * @param pathSegment accepted path segment
     */
    default void enterPathSegment(String pathSegment) {
        enterPathSegment(pathSegment, null);
    }

    /**
     * Records entry into an accepted segment.
     *
     * @param pathSegment accepted path segment
     * @param currentNode node at the entered position
     */
    void enterPathSegment(String pathSegment, Node currentNode);

    /** Balances the most recent accepted segment entry. */
    void exitPathSegment();
}
