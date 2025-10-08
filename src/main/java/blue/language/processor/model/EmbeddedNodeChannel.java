package blue.language.processor.model;

import blue.language.model.TypeBlueId;

@TypeBlueId("EmbeddedNodeChannel")
public class EmbeddedNodeChannel extends ChannelContract {

    private String childPath;

    public String getChildPath() {
        return childPath;
    }

    public void setChildPath(String childPath) {
        this.childPath = childPath;
    }
}
