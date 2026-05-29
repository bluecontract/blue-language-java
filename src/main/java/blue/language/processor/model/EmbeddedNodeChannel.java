package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

@TypeBlueId(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
public class EmbeddedNodeChannel extends ChannelContract {

    private String childPath;

    public String getChildPath() {
        return childPath;
    }

    public void setChildPath(String childPath) {
        this.childPath = childPath;
    }
}
