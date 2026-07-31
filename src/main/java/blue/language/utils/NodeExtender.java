package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.limits.Limits;

/**
 * Compatibility bridge for the pre-1.0 name of {@link NodeExpander}.
 *
 * <p>New code should use {@link NodeExpander}. This bridge exists only for the
 * frozen 1.x binary API.</p>
 */
public class NodeExtender {

    /**
     * Compatibility form of {@link NodeExpander.MissingElementStrategy}.
     *
     * <p>New code should use {@link NodeExpander.MissingElementStrategy}.</p>
     */
    public enum MissingElementStrategy {
        /** Fail expansion immediately. */
        THROW_EXCEPTION,
        /** Leave the unresolved reference in place. */
        RETURN_EMPTY
    }

    private final NodeExpander delegate;

    /**
     * Creates a fail-fast compatibility bridge.
     *
     * @param nodeProvider provider used to materialize references
     */
    public NodeExtender(NodeProvider nodeProvider) {
        this(nodeProvider, MissingElementStrategy.THROW_EXCEPTION);
    }

    /**
     * Creates a bridge with an explicit missing-reference policy.
     *
     * @param nodeProvider provider used to materialize references
     * @param strategy behavior when a referenced node is unavailable
     */
    public NodeExtender(NodeProvider nodeProvider, MissingElementStrategy strategy) {
        this.delegate = new NodeExpander(nodeProvider, toExpansionStrategy(strategy));
    }

    /**
     * Delegates to canonical graph expansion.
     *
     * @param node mutable graph root to expand
     * @param limits traversal and reference-expansion limits
     * @throws IllegalArgumentException when fail-fast lookup cannot resolve a
     *                                  reference
     */
    public void extend(Node node, Limits limits) {
        delegate.expand(node, limits);
    }

    private NodeExpander.MissingElementStrategy toExpansionStrategy(
            MissingElementStrategy compatibilityStrategy) {
        if (compatibilityStrategy == MissingElementStrategy.RETURN_EMPTY) {
            return NodeExpander.MissingElementStrategy.RETURN_EMPTY;
        }
        return NodeExpander.MissingElementStrategy.THROW_EXCEPTION;
    }
}
