package blue.language.snapshot;

import blue.language.model.Node;

import java.util.Objects;

public final class ResolvedSnapshot {

    private final Node canonicalRoot;
    private final Node resolvedRoot;
    private final String blueId;

    public ResolvedSnapshot(Node canonicalRoot, Node resolvedRoot, String blueId) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot").clone();
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot").clone();
        this.blueId = Objects.requireNonNull(blueId, "blueId");
    }

    public Node canonicalRoot() {
        return canonicalRoot.clone();
    }

    public Node resolvedRoot() {
        return resolvedRoot.clone();
    }

    public String blueId() {
        return blueId;
    }
}
