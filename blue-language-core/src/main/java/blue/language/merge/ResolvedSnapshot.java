package blue.language.merge;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable pair of a strict canonical identity root and its resolved runtime
 * view.
 *
 * <p>The snapshot BlueId always belongs to the canonical root. Mutable access
 * returns defensive materializations; frozen roots and lazily built path
 * indexes are safe to share. Deferred snapshots are invocation-local and must
 * not be published as complete cache entries.</p>
 */
public final class ResolvedSnapshot {

    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private volatile Map<String, FrozenNode> canonicalIndex;
    private volatile Map<String, FrozenNode> resolvedIndex;
    private final ResolutionProvenance resolutionProvenance;
    private final boolean resolutionComplete;
    private volatile String blueId;

    /**
     * Strictly freezes mutable roots and verifies the supplied canonical BlueId.
     *
     * @param canonicalRoot mutable canonical identity root
     * @param resolvedRoot mutable resolved runtime root
     * @param blueId expected Content BlueId of {@code canonicalRoot}
     */
    public ResolvedSnapshot(Node canonicalRoot, Node resolvedRoot, String blueId) {
        this(FrozenNode.fromNode(canonicalRoot), FrozenNode.fromResolvedNode(resolvedRoot),
                blueId, ResolutionProvenance.none(), true);
    }

    /**
     * Creates a complete snapshot and verifies the supplied canonical BlueId.
     *
     * @param canonicalRoot strict canonical identity root
     * @param resolvedRoot resolved runtime root
     * @param blueId expected Content BlueId of {@code canonicalRoot}
     */
    public ResolvedSnapshot(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String blueId) {
        this(canonicalRoot, resolvedRoot, blueId,
                ResolutionProvenance.none(), true);
    }

    /**
     * Creates an immutable snapshot whose canonical identity is calculated on
     * first request. This is useful for short-lived runtime checkpoints that
     * may never be published outside their active patch sequence.
     *
     * @param canonicalRoot strict canonical identity root
     * @param resolvedRoot resolved runtime root
     */
    public ResolvedSnapshot(FrozenNode canonicalRoot, FrozenNode resolvedRoot) {
        this(canonicalRoot, resolvedRoot, true);
    }

    private ResolvedSnapshot(FrozenNode canonicalRoot,
                             FrozenNode resolvedRoot,
                             boolean resolutionComplete) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        if (!this.canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException("Snapshot canonical root must be strict canonical FrozenNode.");
        }
        this.resolutionProvenance = ResolutionProvenance.none();
        this.resolutionComplete = resolutionComplete;
        this.blueId = null;
    }

    private ResolvedSnapshot(FrozenNode canonicalRoot,
                             FrozenNode resolvedRoot,
                             String blueId,
                             ResolutionProvenance resolutionProvenance,
                             boolean resolutionComplete) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        if (!this.canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException("Snapshot canonical root must be strict canonical FrozenNode.");
        }
        String expectedBlueId = this.canonicalRoot.blueId();
        if (!expectedBlueId.equals(Objects.requireNonNull(blueId, BlueLanguageConstants.OBJECT_BLUE_ID))) {
            throw new IllegalArgumentException("Snapshot blueId must match canonical root blueId.");
        }
        this.resolutionProvenance = Objects.requireNonNull(
                resolutionProvenance, "resolutionProvenance");
        this.resolutionComplete = resolutionComplete;
        this.blueId = expectedBlueId;
    }

    /**
     * Preserves verified-reference provenance from one authoritative resolver run.
     *
     * @param resolution authoritative resolver result
     * @return a complete immutable snapshot carrying the result's verification evidence
     */
    public static ResolvedSnapshot fromResolverResult(ResolutionSnapshot resolution) {
        Objects.requireNonNull(resolution, "resolution");
        return new ResolvedSnapshot(
                resolution.canonicalRoot(),
                resolution.resolvedRoot(),
                resolution.canonicalRoot().blueId(),
                resolution.provenance(),
                true);
    }

    /**
     * Creates an invocation-local snapshot whose resolved lane intentionally
     * retains one or more deferred references. Its canonical identity remains
     * exact, but it must never be published as the complete resolved value for
     * that canonical key.
     *
     * @param canonicalRoot strict canonical identity root
     * @param resolvedRoot runtime root containing deferred references
     * @return an incomplete invocation-local snapshot
     */
    public static ResolvedSnapshot withDeferredResolution(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot) {
        return new ResolvedSnapshot(
                canonicalRoot, resolvedRoot, false);
    }

    /**
     * Returns this snapshot or a copy whose canonical lane passes strict identity validation.
     *
     * @return this snapshot when already validated, otherwise an equivalent validated snapshot
     */
    public ResolvedSnapshot toStrictBlueIdValidatedCanonical() {
        if (canonicalRoot.isStrictCanonical()
                && canonicalRoot.isStrictBlueIdValidation()) {
            return this;
        }
        FrozenNode strictCanonicalRoot = FrozenNode.fromNode(canonicalRoot.toNode());
        return new ResolvedSnapshot(strictCanonicalRoot,
                resolvedRoot,
                strictCanonicalRoot.blueId(),
                resolutionProvenance,
                resolutionComplete);
    }

    /**
     * Returns a fresh mutable canonical root.
     *
     * @return a detached mutable materialization of the canonical root
     */
    public Node canonicalRoot() {
        return canonicalRoot.toNode();
    }

    /**
     * Returns a fresh mutable resolved root.
     *
     * @return a detached mutable materialization of the resolved root
     */
    public Node resolvedRoot() {
        return resolvedRoot.toNode();
    }

    /**
     * Returns the immutable canonical identity root.
     *
     * @return the shareable frozen canonical root
     */
    public FrozenNode frozenCanonicalRoot() {
        return canonicalRoot;
    }

    /**
     * Returns the immutable resolved runtime root.
     *
     * @return the shareable frozen resolved root
     */
    public FrozenNode frozenResolvedRoot() {
        return resolvedRoot;
    }

    /**
     * Looks up a frozen canonical node by RFC 6901 pointer.
     *
     * @param pointer canonical pointer to resolve
     * @return the addressed frozen node, or {@code null} when absent
     */
    public FrozenNode canonicalAt(String pointer) {
        return canonicalIndex().get(JsonPointer.canonicalize(pointer));
    }

    /**
     * Calculates the canonical Content BlueId at an RFC 6901 pointer.
     *
     * @param pointer canonical pointer to resolve
     * @return the addressed node's Content BlueId, or {@code null} when absent
     */
    public String canonicalBlueIdAt(String pointer) {
        FrozenNode node = canonicalAt(pointer);
        return node != null ? node.blueId() : null;
    }

    /**
     * Looks up a frozen resolved node by RFC 6901 pointer.
     *
     * @param pointer resolved pointer to resolve
     * @return the addressed frozen node, or {@code null} when absent
     */
    public FrozenNode resolvedAt(String pointer) {
        return resolvedIndex().get(JsonPointer.canonicalize(pointer));
    }

    /**
     * Materializes a mutable canonical node at an RFC 6901 pointer.
     *
     * @param pointer canonical pointer to resolve
     * @return a detached mutable node, or {@code null} when absent
     */
    public Node canonicalNodeAt(String pointer) {
        FrozenNode node = canonicalAt(pointer);
        return node != null ? node.toNode() : null;
    }

    /**
     * Materializes a mutable resolved node at an RFC 6901 pointer.
     *
     * @param pointer resolved pointer to resolve
     * @return a detached mutable node, or {@code null} when absent
     */
    public Node resolvedNodeAt(String pointer) {
        FrozenNode node = resolvedAt(pointer);
        return node != null ? node.toNode() : null;
    }

    /**
     * Returns the lazily created unmodifiable canonical path index.
     *
     * @return canonical RFC 6901 paths mapped to frozen nodes
     */
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

    /**
     * Returns the lazily created unmodifiable resolved path index.
     *
     * @return resolved RFC 6901 paths mapped to frozen nodes
     */
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

    /**
     * Returns the lazily cached Content BlueId of the canonical root.
     *
     * @return this snapshot's canonical Content BlueId
     */
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

    /**
     * Returns the authoritative reference-resolution evidence, when retained.
     *
     * @return verified resolution evidence, or {@code null} when unavailable
     */
    public VerifiedReferenceResolution verifiedReferenceResolution() {
        return resolutionProvenance.verifiedReferenceResolution();
    }

    /**
     * Returns immutable provenance captured by the authoritative resolver run.
     *
     * @return non-null resolution provenance
     */
    public ResolutionProvenance resolutionProvenance() {
        return resolutionProvenance;
    }

    /**
     * Whether the resolved lane is a complete value suitable for publication
     * in canonical-keyed snapshot caches.
     *
     * @return {@code true} when the resolved root contains no intentionally deferred references
     */
    public boolean isResolutionComplete() {
        return resolutionComplete;
    }

}
