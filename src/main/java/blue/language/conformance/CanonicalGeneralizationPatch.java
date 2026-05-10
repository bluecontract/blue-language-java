package blue.language.conformance;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

public final class CanonicalGeneralizationPatch {

    private final String path;
    private final FrozenNode before;
    private final FrozenNode after;

    CanonicalGeneralizationPatch(String path, FrozenNode before, FrozenNode after) {
        this.path = Objects.requireNonNull(path, "path");
        this.before = before;
        this.after = Objects.requireNonNull(after, "after");
    }

    public String path() {
        return path;
    }

    public FrozenNode before() {
        return before;
    }

    public Node beforeNode() {
        return before != null ? before.toNode() : null;
    }

    public FrozenNode after() {
        return after;
    }

    public Node afterNode() {
        return after.toNode();
    }
}
