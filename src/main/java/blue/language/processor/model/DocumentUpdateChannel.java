package blue.language.processor.model;

import blue.language.model.TypeBlueId;

@TypeBlueId("DocumentUpdateChannel")
public class DocumentUpdateChannel extends ChannelContract {

    private String path;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
