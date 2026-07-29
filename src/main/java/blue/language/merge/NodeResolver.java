package blue.language.merge;

import blue.language.model.Node;
import blue.language.utils.limits.Limits;

/** Resolves mutable Blue content under an explicit traversal/reference budget. */
public interface NodeResolver {

    /**
     * Resolves {@code node}; implementations may mutate and return the supplied
     * graph.
     *
     * @param node mutable root to resolve
     * @param limits traversal and reference-expansion budget
     * @return resolved graph, normally the supplied root
     */
    Node resolve(Node node, Limits limits);

    /**
     * Resolves with no caller-imposed limits.
     *
     * @param node mutable root to resolve
     * @return resolved graph, normally the supplied root
     */
    default Node resolve(Node node) {
        return resolve(node, Limits.NO_LIMITS);
    }
}
