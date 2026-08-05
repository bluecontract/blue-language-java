package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

/**
 * Event payload describing one committed document update.
 *
 * <p>The presence flags distinguish an absent value from a present Blue
 * {@code null}; {@code before} and {@code after} alone cannot express that
 * distinction. This is a mutable event model: node values are retained and
 * returned by reference.</p>
 */
@TypeBlueId(RuntimeBlueIds.DOCUMENT_UPDATE)
public class DocumentUpdate {

    private String op;
    private String path;
    private boolean beforePresent;
    private Node before;
    private boolean afterPresent;
    private Node after;
    private String sourceScopePath;

    /** Creates an empty document-update payload. */
    public DocumentUpdate() {
    }

    /**
     * Returns the canonical patch operation name that produced the update.
     *
     * @return operation name, or {@code null} before it is assigned
     */
    public String getOp() {
        return op;
    }

    /**
     * Sets the patch operation name for fluent construction.
     *
     * @param op canonical operation name, or {@code null} to clear it
     * @return this update
     */
    public DocumentUpdate op(String op) {
        this.op = op;
        return this;
    }

    /**
     * Returns the update path relative to the processing root.
     *
     * @return authored update path, or {@code null} before it is assigned
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets the update path for fluent construction.
     *
     * @param path root-relative update path, or {@code null} to clear it
     * @return this update
     */
    public DocumentUpdate path(String path) {
        this.path = path;
        return this;
    }

    /**
     * Returns the value before the update.
     *
     * <p>Consult {@link #isBeforePresent()} first because {@code null} may
     * represent either absence or a present Blue null. The retained node is
     * returned directly.</p>
     *
     * @return retained before-value reference, or {@code null}
     */
    public Node getBefore() {
        return before;
    }

    /**
     * Distinguishes an absent before-value from a present Blue {@code null}.
     *
     * @return {@code true} when the before-value is logically present
     */
    public boolean isBeforePresent() {
        return beforePresent;
    }

    /**
     * Sets explicit before-value presence without changing the stored value.
     *
     * @param beforePresent whether the before-value is logically present
     * @return this update
     */
    public DocumentUpdate beforePresent(boolean beforePresent) {
        this.beforePresent = beforePresent;
        return this;
    }

    /**
     * Stores the before-value; presence remains controlled independently.
     *
     * @param before value retained by reference, or {@code null}
     * @return this update
     */
    public DocumentUpdate before(Node before) {
        this.before = before;
        return this;
    }

    /**
     * Returns the value after the update.
     *
     * <p>Consult {@link #isAfterPresent()} first because {@code null} may
     * represent either absence or a present Blue null. The retained node is
     * returned directly.</p>
     *
     * @return retained after-value reference, or {@code null}
     */
    public Node getAfter() {
        return after;
    }

    /**
     * Distinguishes an absent after-value from a present Blue {@code null}.
     *
     * @return {@code true} when the after-value is logically present
     */
    public boolean isAfterPresent() {
        return afterPresent;
    }

    /**
     * Sets explicit after-value presence without changing the stored value.
     *
     * @param afterPresent whether the after-value is logically present
     * @return this update
     */
    public DocumentUpdate afterPresent(boolean afterPresent) {
        this.afterPresent = afterPresent;
        return this;
    }

    /**
     * Stores the after-value; presence remains controlled independently.
     *
     * @param after value retained by reference, or {@code null}
     * @return this update
     */
    public DocumentUpdate after(Node after) {
        this.after = after;
        return this;
    }

    /**
     * Returns the scope whose processing produced this update.
     *
     * @return source scope path, or {@code null} when not recorded
     */
    public String getSourceScopePath() {
        return sourceScopePath;
    }

    /**
     * Sets the producing scope path for fluent construction.
     *
     * @param sourceScopePath source scope path, or {@code null} to clear it
     * @return this update
     */
    public DocumentUpdate sourceScopePath(String sourceScopePath) {
        this.sourceScopePath = sourceScopePath;
        return this;
    }
}
