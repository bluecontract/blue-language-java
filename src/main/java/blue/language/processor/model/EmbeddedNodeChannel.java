package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

@TypeBlueId(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
public class EmbeddedNodeChannel extends ChannelContract {

    private String sourcePath;
    private Node event;

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

}
