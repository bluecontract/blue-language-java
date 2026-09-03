package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

/**
 * Processor-managed bridge for events emitted by members of one declared
 * embedded collection.
 *
 * <p>The exact runtime BlueId is supplied by the verified Contracts runtime
 * registry.</p>
 */
@TypeBlueId(RuntimeBlueIds.EMBEDDED_COLLECTION_EVENT_CHANNEL)
public class EmbeddedCollectionEventChannel extends ChannelContract {

    private String collectionPath;
    private Boolean includeDescendants;
    private Node event;

    /** Creates an unconfigured embedded-collection channel. */
    public EmbeddedCollectionEventChannel() {
    }

    /**
     * Returns the required collection declaration path.
     *
     * @return the collection declaration path
     */
    public String getCollectionPath() {
        return collectionPath;
    }

    /**
     * Sets the collection declaration path.
     *
     * @param collectionPath the collection declaration path
     */
    public void setCollectionPath(String collectionPath) {
        this.collectionPath = collectionPath;
    }

    /**
     * Returns the optional descendant-selection flag.
     *
     * @return the descendant-selection flag, or {@code null} when omitted
     */
    public Boolean getIncludeDescendants() {
        return includeDescendants;
    }

    /**
     * Sets the optional descendant-selection flag.
     *
     * @param includeDescendants the descendant-selection flag, or
     *                           {@code null} to omit it
     */
    public void setIncludeDescendants(Boolean includeDescendants) {
        this.includeDescendants = includeDescendants;
    }

    /**
     * Returns whether nested paths below direct members are selected.
     *
     * @return {@code true} only when descendant selection is explicitly enabled
     */
    public boolean includesDescendants() {
        return Boolean.TRUE.equals(includeDescendants);
    }

    /**
     * Returns the optional exact event pattern.
     *
     * @return the exact event pattern, or {@code null} when none is configured
     */
    public Node getEvent() {
        return event;
    }

    /**
     * Sets the optional exact event pattern, retained by reference.
     *
     * @param event the exact event pattern, or {@code null} to clear it
     */
    public void setEvent(Node event) {
        this.event = event;
    }
}
