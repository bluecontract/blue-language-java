package blue.language.processor.model;

import blue.language.model.Node;

/**
 * Base contract describing a channel available within a scope.
 *
 * <p>This is a mutable loader model. Definition nodes are retained and
 * returned by reference; callers that require isolation must clone them.</p>
 */
public abstract class ChannelContract extends Contract {

    private String path;
    private Node definition;

    /** Creates an uninitialized channel contract. */
    public ChannelContract() {
    }

    /**
     * Returns the channel's declared path selector.
     *
     * @return declared path selector, or {@code null} when none is present
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets the channel's declared path selector.
     *
     * @param path path selector, or {@code null} to clear it
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Sets the path selector for fluent construction.
     *
     * @param path path selector, or {@code null} to clear it
     * @return this contract
     */
    public ChannelContract path(String path) {
        this.path = path;
        return this;
    }

    /**
     * Returns the optional definition node used to describe the channel.
     *
     * @return retained definition reference, or {@code null} when absent
     */
    public Node getDefinition() {
        return definition;
    }

    /**
     * Sets the optional channel definition node.
     *
     * @param definition definition retained by reference, or {@code null}
     */
    public void setDefinition(Node definition) {
        this.definition = definition;
    }

    /**
     * Sets the definition for fluent construction.
     *
     * @param definition definition retained by reference, or {@code null}
     * @return this contract
     */
    public ChannelContract definition(Node definition) {
        this.definition = definition;
        return this;
    }
}
