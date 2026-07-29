package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

/**
 * Processor-managed channel that receives document updates matching a
 * configured path.
 *
 * <p>The path is mutable configuration consumed when the processor loads the
 * channel.</p>
 */
@TypeBlueId(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL)
public class DocumentUpdateChannel extends ChannelContract {

    private String path;

    /** Creates an unconfigured document-update channel. */
    public DocumentUpdateChannel() {
    }

    /**
     * Returns the document-update path pattern evaluated by this channel.
     *
     * @return configured path pattern, or {@code null} when absent
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets the document-update path pattern evaluated by this channel.
     *
     * @param path path pattern, or {@code null} to clear it
     */
    public void setPath(String path) {
        this.path = path;
    }
}
