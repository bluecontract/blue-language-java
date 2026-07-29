package blue.language.merge;

import blue.language.NodeProvider;
import blue.language.model.Node;

/**
 * Stateless extension point for one stage of Blue type/instance merging.
 *
 * <p>Processors mutate the in-progress target. Per-resolution state belongs to
 * {@link Merger}; implementations must not retain mutable invocation state.</p>
 */
public interface MergingProcessor {

    /**
     * Applies this stage while merging one source contribution into a target.
     *
     * @param target mutable in-progress target
     * @param source source contribution being merged
     * @param nodeProvider provider used for referenced content
     * @param nodeResolver resolver bound to the active merge
     */
    void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver);

    /**
     * Runs after the source contribution has passed the primary stage.
     *
     * @param target mutable in-progress target
     * @param source source contribution that passed the primary stage
     * @param nodeProvider provider used for referenced content
     * @param nodeResolver resolver bound to the active merge
     */
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
