package blue.language.graph;

import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationResult;
import blue.language.model.Node;

/** Exact graph operations that do not apply type-resolution semantics. */
public interface BlueGraph {

    /**
     * Reveals verified referenced content while preserving exact identity.
     *
     * @param source exact node to expand; it is not mutated
     * @return independent expanded node
     */
    Node expand(Node source);

    /**
     * Expands only the demanded semantic closure.
     *
     * @param source exact node to expand; it is not mutated
     * @param limits demand and provider-expansion limits
     * @return exhaustive established, absent, incomplete, or invalid outcome
     */
    BlueOperationResult<Node> expandLimited(
            Node source, BlueOperationLimits limits);

    /**
     * Hides exact content behind its direct BlueId.
     *
     * @param exactInput valid direct identity input
     * @return a new pure reference node
     */
    Node collapse(Node exactInput);

    /**
     * Creates a new authored node using {@code type} and a compatible overlay.
     *
     * @param type type node or pure reference
     * @param overlay authored instance contribution without its own type
     * @return independent specialization
     */
    Node specialize(Node type, Node overlay);
}
