package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

@TypeBlueId(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
public class EmbeddedNodeChannel extends ChannelContract {

    private String sourcePath;
    private Node event;

    /**
     * Preview compatibility alias. Contracts 1.0 calls this field
     * {@code sourcePath}.
     */
    @Deprecated
    private String childPath;

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public Node getEvent() {
        return event;
    }

    public void setEvent(Node event) {
        this.event = event;
    }

    @Deprecated
    public String getChildPath() {
        return childPath;
    }

    @Deprecated
    public void setChildPath(String childPath) {
        this.childPath = childPath;
    }
}
