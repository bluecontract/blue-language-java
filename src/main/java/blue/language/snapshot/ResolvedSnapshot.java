package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;

import java.util.Objects;

public final class ResolvedSnapshot {

    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private final String blueId;

    public ResolvedSnapshot(Node canonicalRoot, Node resolvedRoot, String blueId) {
        this(FrozenNode.fromNode(canonicalRoot), FrozenNode.fromResolvedNode(resolvedRoot), blueId);
    }

    public ResolvedSnapshot(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String blueId) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        if (!this.canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException("Snapshot canonical root must be strict canonical FrozenNode.");
        }
        String expectedBlueId = this.canonicalRoot.blueId();
        if (!expectedBlueId.equals(Objects.requireNonNull(blueId, "blueId"))) {
            throw new IllegalArgumentException("Snapshot blueId must match canonical root blueId.");
        }
        this.blueId = expectedBlueId;
    }

    public Node canonicalRoot() {
        return canonicalRoot.toNode();
    }

    public Node resolvedRoot() {
        return resolvedRoot.toNode();
    }

    public FrozenNode frozenCanonicalRoot() {
        return canonicalRoot;
    }

    public FrozenNode frozenResolvedRoot() {
        return resolvedRoot;
    }

    public String blueId() {
        return blueId;
    }

    public CanonicalOverlayPatchEngine canonicalPatchEngine() {
        return new CanonicalOverlayPatchEngine(canonicalRoot);
    }

    public CanonicalPatchResult applyCanonicalPatch(JsonPatch patch) {
        return canonicalPatchEngine().apply(patch);
    }
}
