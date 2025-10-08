package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;

@TypeBlueId("DocumentUpdate")
public class DocumentUpdate {

    private String op;
    private String path;
    private Node before;
    private Node after;

    public String getOp() {
        return op;
    }

    public DocumentUpdate op(String op) {
        this.op = op;
        return this;
    }

    public String getPath() {
        return path;
    }

    public DocumentUpdate path(String path) {
        this.path = path;
        return this;
    }

    public Node getBefore() {
        return before;
    }

    public DocumentUpdate before(Node before) {
        this.before = before;
        return this;
    }

    public Node getAfter() {
        return after;
    }

    public DocumentUpdate after(Node after) {
        this.after = after;
        return this;
    }
}
