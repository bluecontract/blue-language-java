package blue.language.merge;

import blue.language.model.Node;
import blue.language.resolve.ResolutionLimits;

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
    Node resolve(Node node, ResolutionLimits limits);

    /**
     * Resolves with no caller-imposed limits.
     *
     * @param node mutable root to resolve
     * @return resolved graph, normally the supplied root
     */
    default Node resolve(Node node) {
        return resolve(node, ResolutionLimits.NO_LIMITS);
    }

    /**
     * Resolves a graph together with the canonical identities established for
     * effective types reached by that same invocation.
     *
     * <p>Merge-time validators use this boundary when a nested materialized
     * type must be classified before ordinary child traversal reaches it. An
     * implementation must use an independent resolver invocation in that
     * situation; partially merged state is not valid identity evidence.</p>
     *
     * @param node source graph to resolve
     * @param limits traversal and reference-expansion budget
     * @return resolved graph and resolver-issued effective-type identities
     * @throws NullPointerException if {@code node} or {@code limits} is null
     * @throws IllegalArgumentException if the graph contains invalid reference
     *         or type metadata
     * @throws IllegalStateException if exact canonical type evidence cannot be
     *         established for every completed effective type
     */
    TypeEvidenceResolution resolveTypeEvidence(
            Node node,
            ResolutionLimits limits);

}
