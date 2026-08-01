package blue.language.processor.model;

import blue.language.model.Node;

/**
 * Base contract describing deterministic logic bound to a channel.
 *
 * <p>This is a mutable loader model. The event node is retained and exposed
 * by reference, so callers that need an isolated value must clone it.</p>
 */
public abstract class HandlerContract extends Contract {

    private String channel;
    private Node event;

    /** Creates an uninitialized handler contract. */
    public HandlerContract() {
    }

    /**
     * Returns the scope-local channel key to which this handler is bound.
     *
     * @return channel key, or {@code null} before it is assigned
     */
    public String getChannelKey() {
        return channel;
    }

    /**
     * Sets the scope-local channel key to which this handler is bound.
     *
     * @param channelKey channel key, or {@code null} to clear it
     */
    public void setChannelKey(String channelKey) {
        this.channel = channelKey;
    }

    /**
     * Sets the channel key for fluent construction.
     *
     * @param channelKey channel key, or {@code null} to clear it
     * @return this handler
     */
    public HandlerContract channelKey(String channelKey) {
        this.channel = channelKey;
        return this;
    }

    /**
     * Compatibility alias for {@link #getChannelKey()}.
     *
     * @return channel key, or {@code null} before it is assigned
     */
    public String getChannel() {
        return channel;
    }

    /**
     * Compatibility alias for {@link #setChannelKey(String)}.
     *
     * @param channel channel key, or {@code null} to clear it
     */
    public void setChannel(String channel) {
        this.channel = channel;
    }

    /**
     * Compatibility alias for {@link #channelKey(String)}.
     *
     * @param channel channel key, or {@code null} to clear it
     * @return this handler
     */
    public HandlerContract channel(String channel) {
        this.channel = channel;
        return this;
    }

    /**
     * Returns the event pattern that must match before execution.
     *
     * @return retained event-pattern reference, or {@code null} when absent
     */
    public Node getEvent() {
        return event;
    }

    /**
     * Sets the event pattern that must match before execution.
     *
     * @param event event pattern retained by reference, or {@code null}
     */
    public void setEvent(Node event) {
        this.event = event;
    }

    /**
     * Sets the event pattern for fluent construction.
     *
     * @param event event pattern retained by reference, or {@code null}
     * @return this handler
     */
    public HandlerContract event(Node event) {
        this.event = event;
        return this;
    }
}
