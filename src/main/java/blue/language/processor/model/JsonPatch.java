package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;

import java.util.Objects;

@TypeBlueId("JsonPatch")
public class JsonPatch {

    public enum Op {
        ADD,
        REPLACE,
        REMOVE
    }

    private final Op op;
    private final String path;
    private final Node val;

    private JsonPatch(Op op, String path, Node val) {
        this.op = Objects.requireNonNull(op, "op");
        this.path = Objects.requireNonNull(path, "path");
        if (op == Op.REMOVE) {
            this.val = null;
        } else {
            this.val = Objects.requireNonNull(val, "val");
        }
    }

    public static JsonPatch add(String path, Node val) {
        return new JsonPatch(Op.ADD, path, val);
    }

    public static JsonPatch replace(String path, Node val) {
        return new JsonPatch(Op.REPLACE, path, val);
    }

    public static JsonPatch remove(String path) {
        return new JsonPatch(Op.REMOVE, path, null);
    }

    public Op getOp() {
        return op;
    }

    public String getPath() {
        return path;
    }

    public Node getVal() {
        return val;
    }
}
