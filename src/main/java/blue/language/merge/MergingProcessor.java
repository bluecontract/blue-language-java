package blue.language.merge;

import blue.language.NodeProvider;
import blue.language.model.Node;

public interface MergingProcessor {
    void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver);

    default void postProcess(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        // default implementation
    }

    /**
     * Returns whether this processor has completed-instance validation for the supplied node.
     * Implementations must remain stateless; validation state belongs to the active merger.
     *
     * @param node completed-value candidate
     * @return whether this processor validates the candidate after resolution
     */
    default boolean hasCompletedValidation(Node node) {
        return false;
    }

    /**
     * Returns whether evaluating this node requires the content behind a pure reference.
     *
     * @param node effective constrained node
     * @return whether referenced content is required
     */
    default boolean requiresReferenceMaterialization(Node node) {
        return false;
    }

    /**
     * Validates one completed resolved value after all ancestor and instance contributions merge.
     *
     * @param node completed resolved value
     * @param semanticallyPresent whether instance or inherited payload contributes semantic presence
     * @param path RFC 6901 path used for diagnostics
     */
    default void validateCompleted(Node node, boolean semanticallyPresent, String path) {
        // default implementation
    }
}
