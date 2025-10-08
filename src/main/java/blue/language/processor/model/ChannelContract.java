package blue.language.processor.model;

import blue.language.model.Node;

/**
 * Base contract describing a channel available within a scope.
 */
public abstract class ChannelContract extends Contract {

    private String path;
    private Node definition;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public ChannelContract path(String path) {
        this.path = path;
        return this;
    }

    public Node getDefinition() {
        return definition;
    }

    public void setDefinition(Node definition) {
        this.definition = definition;
    }

    public ChannelContract definition(Node definition) {
        this.definition = definition;
        return this;
    }
}
