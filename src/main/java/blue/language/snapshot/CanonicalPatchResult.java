package blue.language.snapshot;

import blue.language.processor.model.JsonPatch;

public final class CanonicalPatchResult {

    private final FrozenNode root;
    private final FrozenNode before;
    private final FrozenNode after;
    private final JsonPatch.Op op;
    private final String path;

    CanonicalPatchResult(FrozenNode root, FrozenNode before, FrozenNode after, JsonPatch.Op op, String path) {
        this.root = root;
        this.before = before;
        this.after = after;
        this.op = op;
        this.path = path;
    }

    public FrozenNode root() {
        return root;
    }

    public FrozenNode before() {
        return before;
    }

    public FrozenNode after() {
        return after;
    }

    public JsonPatch.Op op() {
        return op;
    }

    public String path() {
        return path;
    }

    public String blueId() {
        return root.blueId();
    }
}
