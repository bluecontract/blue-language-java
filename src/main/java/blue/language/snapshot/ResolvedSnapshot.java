package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.merge.Merger.SnapshotResolution;
import blue.language.merge.Merger.VerifiedReferenceResolution;
import blue.language.processor.model.JsonPatch;
import blue.language.utils.JsonPointer;

import java.util.Map;
import java.util.Objects;

public final class ResolvedSnapshot {

    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private final Map<String, FrozenNode> canonicalIndex;
    private final Map<String, FrozenNode> resolvedIndex;
    private final VerifiedReferenceResolution verifiedReferenceResolution;
    private final String blueId;

    public ResolvedSnapshot(Node canonicalRoot, Node resolvedRoot, String blueId) {
        this(FrozenNode.fromNode(canonicalRoot), FrozenNode.fromResolvedNode(resolvedRoot), blueId, null);
    }

    public ResolvedSnapshot(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String blueId) {
        this(canonicalRoot, resolvedRoot, blueId, null);
    }

    private ResolvedSnapshot(FrozenNode canonicalRoot,
                             FrozenNode resolvedRoot,
                             String blueId,
                             VerifiedReferenceResolution verifiedReferenceResolution) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        if (!this.canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException("Snapshot canonical root must be strict canonical FrozenNode.");
        }
        String expectedBlueId = this.canonicalRoot.blueId();
        if (!expectedBlueId.equals(Objects.requireNonNull(blueId, "blueId"))) {
            throw new IllegalArgumentException("Snapshot blueId must match canonical root blueId.");
        }
        this.canonicalIndex = this.canonicalRoot.pathIndex();
        this.resolvedIndex = this.resolvedRoot.pathIndex();
        this.verifiedReferenceResolution = verifiedReferenceResolution;
        this.blueId = expectedBlueId;
    }

    public static ResolvedSnapshot fromResolverResult(SnapshotResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        return new ResolvedSnapshot(
                resolution.canonicalRoot(),
                resolution.resolvedRoot(),
                resolution.canonicalRoot().blueId(),
                resolution.verifiedReferenceResolution());
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

    public FrozenNode canonicalAt(String pointer) {
        return canonicalIndex.get(JsonPointer.canonicalize(pointer));
    }

    public FrozenNode resolvedAt(String pointer) {
        return resolvedIndex.get(JsonPointer.canonicalize(pointer));
    }

    public Node canonicalNodeAt(String pointer) {
        FrozenNode node = canonicalAt(pointer);
        return node != null ? node.toNode() : null;
    }

    public Node resolvedNodeAt(String pointer) {
        FrozenNode node = resolvedAt(pointer);
        return node != null ? node.toNode() : null;
    }

    public Map<String, FrozenNode> canonicalIndex() {
        return canonicalIndex;
    }

    public Map<String, FrozenNode> resolvedIndex() {
        return resolvedIndex;
    }

    public String blueId() {
        return blueId;
    }

    public VerifiedReferenceResolution verifiedReferenceResolution() {
        return verifiedReferenceResolution;
    }

    public CanonicalOverlayPatchEngine canonicalPatchEngine() {
        return new CanonicalOverlayPatchEngine(canonicalRoot);
    }

    public CanonicalPatchResult applyCanonicalPatch(JsonPatch patch) {
        return canonicalPatchEngine().apply(patch);
    }

}
