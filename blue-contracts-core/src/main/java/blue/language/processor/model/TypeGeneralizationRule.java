package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

/**
 * One path-specific type-generalization rule with an optional subtype
 * constraint.
 *
 * <p>This is a mutable wire model. The subtype boundary node is retained and
 * returned by reference.</p>
 */
@TypeBlueId(RuntimeBlueIds.TYPE_GENERALIZATION_RULE)
public class TypeGeneralizationRule {

    private String path;
    private String mode;
    private Node mustRemainSubtypeOf;

    /** Creates an empty type-generalization rule. */
    public TypeGeneralizationRule() {
    }

    /**
     * Returns the relative path selected by this rule.
     *
     * @return selected path, or {@code null} when absent
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets the relative path selected by this rule.
     *
     * @param path selected relative path, or {@code null} to clear it
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Returns the generalization mode applied at the selected path.
     *
     * @return configured mode, or {@code null} when absent
     */
    public String getMode() {
        return mode;
    }

    /**
     * Sets the generalization mode applied at the selected path.
     *
     * @param mode generalization mode, or {@code null} to clear it
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * Returns the optional type boundary the generalized value must retain.
     *
     * @return retained subtype-boundary reference, or {@code null}
     */
    public Node getMustRemainSubtypeOf() {
        return mustRemainSubtypeOf;
    }

    /**
     * Sets the optional retained-subtype boundary.
     *
     * @param mustRemainSubtypeOf boundary retained by reference, or
     *        {@code null}
     */
    public void setMustRemainSubtypeOf(Node mustRemainSubtypeOf) {
        this.mustRemainSubtypeOf = mustRemainSubtypeOf;
    }
}
