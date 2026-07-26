package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

@TypeBlueId(RuntimeBlueIds.DOCUMENT_UPDATE)
public class DocumentUpdate {

    private String op;
    private String path;
    private boolean beforePresent;
    private Node before;
    private boolean afterPresent;
    private Node after;
    private String sourceScopePath;

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

    public boolean isBeforePresent() {
        return beforePresent;
    }

    public DocumentUpdate beforePresent(boolean beforePresent) {
        this.beforePresent = beforePresent;
        return this;
    }

    public DocumentUpdate before(Node before) {
        this.before = before;
        return this;
    }

    public Node getAfter() {
        return after;
    }

    public boolean isAfterPresent() {
        return afterPresent;
    }

    public DocumentUpdate afterPresent(boolean afterPresent) {
        this.afterPresent = afterPresent;
        return this;
    }

    public DocumentUpdate after(Node after) {
        this.after = after;
        return this;
    }

    public String getSourceScopePath() {
        return sourceScopePath;
    }

    public DocumentUpdate sourceScopePath(String sourceScopePath) {
        this.sourceScopePath = sourceScopePath;
        return this;
    }
}
