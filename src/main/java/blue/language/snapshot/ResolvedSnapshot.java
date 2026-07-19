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
    private volatile Map<String, FrozenNode> canonicalIndex;
    private volatile Map<String, FrozenNode> resolvedIndex;
    private final VerifiedReferenceResolution verifiedReferenceResolution;
    private volatile String blueId;

    public ResolvedSnapshot(Node canonicalRoot, Node resolvedRoot, String blueId) {
        this(FrozenNode.fromNode(canonicalRoot), FrozenNode.fromResolvedNode(resolvedRoot), blueId, null);
    }

    public ResolvedSnapshot(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String blueId) {
        this(canonicalRoot, resolvedRoot, blueId, null);
    }

    /**
     * Creates an immutable snapshot whose canonical identity is calculated on
     * first request. This is useful for short-lived runtime checkpoints that
     * may never be published outside their active patch sequence.
     */
    public ResolvedSnapshot(FrozenNode canonicalRoot, FrozenNode resolvedRoot) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        if (!this.canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException("Snapshot canonical root must be strict canonical FrozenNode.");
        }
        this.verifiedReferenceResolution = null;
        this.blueId = null;
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
        return canonicalIndex().get(JsonPointer.canonicalize(pointer));
    }

    public FrozenNode resolvedAt(String pointer) {
        return resolvedIndex().get(JsonPointer.canonicalize(pointer));
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
        Map<String, FrozenNode> index = canonicalIndex;
        if (index == null) {
            synchronized (this) {
                index = canonicalIndex;
                if (index == null) {
                    index = canonicalRoot.pathIndex();
                    canonicalIndex = index;
                }
            }
        }
        return index;
    }

    public Map<String, FrozenNode> resolvedIndex() {
        Map<String, FrozenNode> index = resolvedIndex;
        if (index == null) {
            synchronized (this) {
                index = resolvedIndex;
                if (index == null) {
                    index = resolvedRoot.pathIndex();
                    resolvedIndex = index;
                }
            }
        }
        return index;
    }

    public String blueId() {
        String identity = blueId;
        if (identity == null) {
            synchronized (this) {
                identity = blueId;
                if (identity == null) {
                    identity = canonicalRoot.blueId();
                    blueId = identity;
                }
            }
        }
        return identity;
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
