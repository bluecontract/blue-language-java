package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

@TypeBlueId(RuntimeBlueIds.TYPE_GENERALIZATION_RULE)
public class TypeGeneralizationRule {

    private String path;
    private String mode;
    private Node mustRemainSubtypeOf;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Node getMustRemainSubtypeOf() {
        return mustRemainSubtypeOf;
    }

    public void setMustRemainSubtypeOf(Node mustRemainSubtypeOf) {
        this.mustRemainSubtypeOf = mustRemainSubtypeOf;
    }
}
