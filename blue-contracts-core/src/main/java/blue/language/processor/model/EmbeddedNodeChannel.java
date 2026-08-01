package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

/**
 * Processor-managed bridge that delivers matching descendant occurrences to
 * an embedded receiving scope.
 *
 * <p>This mutable wire model retains the event pattern by reference.</p>
 */
@TypeBlueId(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
public class EmbeddedNodeChannel extends ChannelContract {

    private String sourcePath;
    private Node event;

    /** Creates an unconfigured embedded-node channel. */
    public EmbeddedNodeChannel() {
    }

    /**
     * Returns the descendant path observed by this embedded channel.
     *
     * @return configured source path, or {@code null} when absent
     */
    public String getSourcePath() {
        return sourcePath;
    }

    /**
     * Sets the descendant path observed by this embedded channel.
     *
     * @param sourcePath descendant source path, or {@code null} to clear it
     */
    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    /**
     * Returns the event pattern used to select descendant occurrences.
     *
     * @return retained event-pattern reference, or {@code null} when absent
     */
    public Node getEvent() {
        return event;
    }

    /**
     * Sets the event pattern used to select descendant occurrences.
     *
     * @param event event pattern retained by reference, or {@code null}
     */
    public void setEvent(Node event) {
        this.event = event;
    }

}
