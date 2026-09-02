package blue.language.merge;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalIdentityInputBuilder;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable capture of an exact input lane, a resolved runtime view, and any
 * whole-document canonical identity proven by the same resolution evidence.
 *
 * <p>Complete snapshots always carry a strict Canonical Identity Input and its
 * BlueId. A target-limited snapshot carries the exact preprocessed Source and
 * may expose a canonical lane only when the limited run nevertheless obtained
 * complete canonical-type evidence. Canonical access fails closed otherwise.
 * Mutable access returns defensive materializations; frozen roots and lazily
 * built path indexes are safe to share. Deferred snapshots are invocation-local
 * and must not be published as complete cache entries.</p>
 */
public final class ResolvedSnapshot {

    private final FrozenNode sourceRoot;
    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private volatile Map<String, FrozenNode> canonicalIndex;
    private volatile Map<String, FrozenNode> sourceIndex;
    private volatile Map<String, FrozenNode> resolvedIndex;
    private final ResolutionProvenance resolutionProvenance;
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;
    private final boolean resolutionComplete;
    private final boolean sourceBacked;
    private volatile String blueId;

    /**
     * Strictly freezes mutable roots and verifies the supplied canonical BlueId.
     *
     * <p>This constructor does not manufacture resolver evidence. Consumers
     * that need the identity of an expanded effective type fail closed; use
     * {@link #fromResolverResult(ResolutionSnapshot)} to retain evidence from
     * an authoritative resolution.</p>
     *
     * @param canonicalRoot mutable canonical identity root
     * @param resolvedRoot mutable resolved runtime root
     * @param blueId expected Content BlueId of {@code canonicalRoot}
     */
    public ResolvedSnapshot(Node canonicalRoot, Node resolvedRoot, String blueId) {
        this(FrozenNode.fromNode(canonicalRoot), FrozenNode.fromResolvedNode(resolvedRoot),
                blueId,
                ResolutionProvenance.none(),
                CanonicalTypeIdentityLookup.incomplete(),
                true);
    }

    /**
     * Creates a complete snapshot and verifies the supplied canonical BlueId.
     *
     * <p>"Complete" here describes the resolved lane only. This constructor
     * carries no effective-type identity evidence and cannot be used to
     * canonicalize or minimize expanded types.</p>
     *
     * @param canonicalRoot strict canonical identity root
     * @param resolvedRoot resolved runtime root
     * @param blueId expected Content BlueId of {@code canonicalRoot}
     */
    public ResolvedSnapshot(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String blueId) {
        this(canonicalRoot, resolvedRoot, blueId,
                ResolutionProvenance.none(),
                CanonicalTypeIdentityLookup.incomplete(),
                true);
    }

    /**
     * Creates an immutable snapshot whose canonical identity is calculated on
     * first request. This is useful for short-lived runtime checkpoints that
     * may never be published outside their active patch sequence.
     *
     * <p>The caller supplies an already strict canonical root; no identity is
     * reconstructed from {@code resolvedRoot}. Effective-type evidence is
     * intentionally unavailable.</p>
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
        this.sourceRoot = Objects.requireNonNull(
                canonicalRoot, "canonicalRoot");
        this.canonicalRoot = sourceRoot;
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        if (!this.canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException("Snapshot canonical root must be strict canonical FrozenNode.");
        }
        this.resolutionProvenance = ResolutionProvenance.none();
        this.canonicalTypeIdentities = CanonicalTypeIdentityLookup.incomplete();
        this.resolutionComplete = resolutionComplete;
        this.sourceBacked = false;
        this.blueId = null;
    }

    private ResolvedSnapshot(FrozenNode canonicalRoot,
                             FrozenNode resolvedRoot,
                             String blueId,
                             ResolutionProvenance resolutionProvenance,
                             CanonicalTypeIdentityLookup canonicalTypeIdentities,
                             boolean resolutionComplete) {
        this(
                canonicalRoot,
                Objects.requireNonNull(canonicalRoot, "canonicalRoot"),
                resolvedRoot,
                blueId,
                resolutionProvenance,
                canonicalTypeIdentities,
                resolutionComplete,
                false);
    }

    private ResolvedSnapshot(FrozenNode sourceRoot,
                             FrozenNode canonicalRoot,
                             FrozenNode resolvedRoot,
                             String blueId,
                             ResolutionProvenance resolutionProvenance,
                             CanonicalTypeIdentityLookup canonicalTypeIdentities,
                             boolean resolutionComplete,
                             boolean sourceBacked) {
        this.sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot");
        this.canonicalRoot = canonicalRoot;
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        if (canonicalRoot != null && !canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException("Snapshot canonical root must be strict canonical FrozenNode.");
        }
        if (canonicalRoot == null) {
            if (resolutionComplete || blueId != null) {
                throw new IllegalArgumentException(
                        "A snapshot without whole-document canonical identity must be deferred");
            }
        } else {
            String expectedBlueId = canonicalRoot.blueId();
            if (!expectedBlueId.equals(Objects.requireNonNull(
                    blueId, BlueLanguageConstants.OBJECT_BLUE_ID))) {
                throw new IllegalArgumentException(
                        "Snapshot blueId must match canonical root blueId.");
            }
            this.blueId = expectedBlueId;
        }
        this.resolutionProvenance = Objects.requireNonNull(
                resolutionProvenance, "resolutionProvenance");
        this.canonicalTypeIdentities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
        this.resolutionComplete = resolutionComplete;
        this.sourceBacked = sourceBacked;
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
                resolution.canonicalTypeIdentities(),
                resolution.isResolutionComplete());
    }

    /**
     * Preserves the exact preprocessed Source lane of an authoritative
     * resolution started from Source input.
     *
     * <p>The resolver result remains the authority for the canonical and
     * resolved lanes, type-identity evidence, completeness, and verified
     * reference provenance. Rebuilding canonical identity input from the
     * supplied Source lane verifies that both arguments belong to the same
     * resolution instead of silently attaching unrelated provenance.</p>
     *
     * @param sourceRoot exact preprocessed Source input supplied to the
     *        resolver invocation
     * @param resolution authoritative result from that invocation
     * @return immutable Source-backed snapshot retaining all resolver evidence
     * @throws NullPointerException if an argument is null
     * @throws IllegalStateException if the Source and resolver result do not
     *         describe the same canonical identity input
     */
    public static ResolvedSnapshot fromSourceResolverResult(
            FrozenNode sourceRoot,
            ResolutionSnapshot resolution) {
        FrozenNode source = Objects.requireNonNull(
                sourceRoot, "sourceRoot");
        ResolutionSnapshot result = Objects.requireNonNull(
                resolution, "resolution");
        Node reconstructed = new CanonicalIdentityInputBuilder().build(
                result.resolvedRoot().toNode(),
                source.toNode(),
                result.canonicalTypeIdentities());
        FrozenNode reconstructedRoot = FrozenNode.fromNode(reconstructed);
        if (!result.canonicalRoot().sameResolvedStructure(
                reconstructedRoot)) {
            throw new IllegalStateException(
                    "Source root does not match the resolver's canonical "
                            + "identity input");
        }
        return new ResolvedSnapshot(
                source,
                result.canonicalRoot(),
                result.resolvedRoot(),
                result.canonicalRoot().blueId(),
                result.provenance(),
                result.canonicalTypeIdentities(),
                result.isResolutionComplete(),
                true);
    }

    /**
     * Creates a complete snapshot from an already strict canonical/resolved
     * pair and authoritative whole-graph effective-type evidence.
     *
     * <p>The pair is validated by reconstructing canonical identity input from
     * the resolved lane with the supplied evidence. This rejects stale or
     * incomplete evidence instead of silently creating a complete snapshot
     * that later fails identity-sensitive operations.</p>
     *
     * @param canonicalRoot strict canonical identity root
     * @param resolvedRoot complete resolved runtime root
     * @param canonicalTypeIdentities authoritative whole-graph type evidence
     * @return complete immutable snapshot carrying the supplied evidence
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code canonicalRoot} is not strict
     *         canonical input
     * @throws IllegalStateException if evidence is incomplete or does not
     *                               reconstruct {@code canonicalRoot}
     */
    public static ResolvedSnapshot withCanonicalTypeIdentities(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        CanonicalTypeIdentityLookup identities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
        identities.requireCompleteCoverage();
        Node reconstructed = new CanonicalIdentityInputBuilder().build(
                resolvedRoot.toNode(),
                canonicalRoot.toNode(),
                identities);
        FrozenNode reconstructedRoot = FrozenNode.fromNode(reconstructed);
        if (!canonicalRoot.sameResolvedStructure(reconstructedRoot)) {
            throw new IllegalStateException(
                    "Canonical and resolved roots do not match the supplied "
                            + "canonical type identity evidence");
        }
        return new ResolvedSnapshot(
                canonicalRoot,
                resolvedRoot,
                canonicalRoot.blueId(),
                ResolutionProvenance.none(),
                identities,
                true);
    }

    /**
     * Creates a deferred resolved lane while retaining complete identity
     * evidence obtained independently from an unlimited canonical run.
     *
     * @param canonicalRoot strict canonical identity input
     * @param resolvedRoot resolved graph corresponding to that input
     * @param canonicalTypeIdentities resolver-issued type evidence retained
     *        from the authoritative canonical run
     * @return deferred-resolution snapshot retaining canonical identity
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code canonicalRoot} is not strict
     *         canonical input
     */
    public static ResolvedSnapshot withDeferredResolution(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        return new ResolvedSnapshot(
                canonicalRoot,
                resolvedRoot,
                canonicalRoot.blueId(),
                ResolutionProvenance.none(),
                canonicalTypeIdentities,
                false);
    }

    /**
     * Creates a target-limited snapshot that retains exact preprocessed Source
     * input without claiming a whole-document Canonical Identity Input.
     *
     * <p>Individual effective-type evidence reached by the limited resolver is
     * retained. If that evidence covers the whole graph, the canonical lane is
     * reconstructed and verified without another provider demand. Otherwise
     * {@link #canonicalRoot()} and {@link #blueId()} fail closed. Callers that
     * only need the selected input lane must use {@link #sourceRoot()}.</p>
     *
     * @param sourceRoot exact preprocessed Source input
     * @param resolvedRoot target-limited resolved projection
     * @param canonicalTypeIdentities invocation-local type evidence
     * @return deferred snapshot, with a whole-document canonical identity only
     *         when the supplied evidence covers the entire graph
     * @throws NullPointerException if an argument is null
     * @throws IllegalStateException if evidence claims complete coverage but
     *         omits a required completed effective type
     */
    public static ResolvedSnapshot withDeferredSource(
            FrozenNode sourceRoot,
            FrozenNode resolvedRoot,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        return withSource(
                sourceRoot,
                resolvedRoot,
                canonicalTypeIdentities,
                false);
    }

    /**
     * Creates a snapshot that retains the exact preprocessed Source lane even
     * when whole-document canonical identity is available.
     *
     * <p>Source provenance is independent of resolution completeness and
     * canonical-identity availability. Callers must not infer that a snapshot
     * became Canonical-backed merely because a full resolver invocation
     * completed or reconstructed its Canonical Identity Input.</p>
     *
     * @param sourceRoot exact preprocessed Source input
     * @param resolvedRoot resolved runtime projection
     * @param canonicalTypeIdentities resolver-issued effective-type evidence
     * @param resolutionComplete whether the resolved lane is complete
     * @return immutable Source-backed snapshot
     * @throws NullPointerException if an argument is null
     * @throws IllegalStateException if a complete resolved lane lacks complete
     *         canonical type evidence, or claimed evidence coverage omits a
     *         required completed effective type
     */
    public static ResolvedSnapshot withSource(
            FrozenNode sourceRoot,
            FrozenNode resolvedRoot,
            CanonicalTypeIdentityLookup canonicalTypeIdentities,
            boolean resolutionComplete) {
        FrozenNode source = Objects.requireNonNull(sourceRoot, "sourceRoot");
        FrozenNode resolved = Objects.requireNonNull(
                resolvedRoot, "resolvedRoot");
        CanonicalTypeIdentityLookup identities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
        if (identities.hasCompleteCoverage()) {
            Node canonical = new CanonicalIdentityInputBuilder().build(
                    resolved.toNode(),
                    source.toNode(),
                    identities);
            FrozenNode canonicalRoot = FrozenNode.fromNode(canonical);
            return new ResolvedSnapshot(
                    source,
                    canonicalRoot,
                    resolved,
                    canonicalRoot.blueId(),
                    ResolutionProvenance.none(),
                    identities,
                    resolutionComplete,
                    true);
        }
        if (resolutionComplete) {
            throw new IllegalStateException(
                    "A complete Source-backed snapshot requires complete "
                            + "canonical type identity evidence");
        }
        return new ResolvedSnapshot(
                source,
                null,
                resolved,
                null,
                ResolutionProvenance.none(),
                identities,
                false,
                true);
    }

    /**
     * Attaches graph-scoped canonical identity evidence without changing the
     * exact Source lane, resolved lane, resolution completeness, or verified
     * reference provenance of this snapshot.
     *
     * <p>The supplied lookup must cover every materialized effective type in
     * this snapshot's exact resolved graph. This method does not claim that a
     * target-limited resolution became complete; pure-reference terminals may
     * remain intentionally deferred.</p>
     *
     * @param canonicalTypeIdentities evidence covering this resolved graph
     * @return equivalent snapshot with a proven canonical identity lane
     * @throws NullPointerException if {@code canonicalTypeIdentities} is null
     * @throws IllegalStateException if the evidence is incomplete or omits a
     *         required completed effective type
     */
    public ResolvedSnapshot withCanonicalIdentityEvidence(
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        CanonicalTypeIdentityLookup identities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
        identities.requireCompleteCoverage();
        Node canonical = new CanonicalIdentityInputBuilder().build(
                resolvedRoot.toNode(),
                sourceRoot.toNode(),
                identities);
        FrozenNode exactCanonical = FrozenNode.fromNode(canonical);
        return new ResolvedSnapshot(
                sourceRoot,
                exactCanonical,
                resolvedRoot,
                exactCanonical.blueId(),
                resolutionProvenance,
                identities,
                resolutionComplete,
                sourceBacked);
    }

    /**
     * Returns the canonical-backed view suitable for canonical-keyed shared
     * caches.
     *
     * <p>Source-backed snapshots are invocation results: their exact Source
     * spelling must be returned to that invocation, but must not become the
     * arbitrary Source spelling later returned for another equivalent
     * canonical input. This view changes only input-lane provenance. It keeps
     * the canonical and resolved roots, BlueId, type evidence, completeness,
     * and verified-reference provenance unchanged.</p>
     *
     * @return this snapshot when already canonical-backed, otherwise an
     *         evidence-equivalent canonical-backed view
     * @throws IllegalStateException when no whole-document canonical identity
     *         is available
     */
    public ResolvedSnapshot toCanonicalBacked() {
        FrozenNode canonical = requireCanonicalRoot();
        if (!sourceBacked) {
            return this;
        }
        return new ResolvedSnapshot(
                canonical,
                canonical,
                resolvedRoot,
                canonical.blueId(),
                resolutionProvenance,
                canonicalTypeIdentities,
                resolutionComplete,
                false);
    }

    /**
     * Returns this snapshot or a copy whose canonical lane passes strict identity validation.
     *
     * @return this snapshot when already validated, otherwise an equivalent validated snapshot
     */
    public ResolvedSnapshot toStrictBlueIdValidatedCanonical() {
        requireCanonicalRoot();
        if (canonicalRoot.isStrictCanonical()
                && canonicalRoot.isStrictBlueIdValidation()) {
            return this;
        }
        FrozenNode strictCanonicalRoot = FrozenNode.fromNode(canonicalRoot.toNode());
        return new ResolvedSnapshot(
                sourceRoot,
                strictCanonicalRoot,
                resolvedRoot,
                strictCanonicalRoot.blueId(),
                resolutionProvenance,
                canonicalTypeIdentities,
                resolutionComplete,
                sourceBacked);
    }

    /**
     * Returns a fresh mutable canonical root.
     *
     * @return a detached mutable materialization of the canonical root
     * @throws IllegalStateException when target-limited resolution did not
     *                               establish whole-document identity
     */
    public Node canonicalRoot() {
        return requireCanonicalRoot().toNode();
    }

    /**
     * Returns the exact input lane used for further selection or patching.
     * A Source-backed snapshot returns the exact preprocessed Source input,
     * whether its resolved lane is complete or target-limited. A
     * canonical-backed snapshot returns its Canonical Identity Input.
     *
     * @return detached exact input root
     */
    public Node sourceRoot() {
        return sourceRoot.toNode();
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
     * @throws IllegalStateException when target-limited resolution did not
     *                               establish whole-document identity
     */
    public FrozenNode frozenCanonicalRoot() {
        return requireCanonicalRoot();
    }

    /**
     * Returns the immutable exact input lane used for selection or patching.
     *
     * @return immutable exact input root: preprocessed Source for a
     *         Source-backed snapshot, otherwise Canonical Identity Input
     */
    public FrozenNode frozenSourceRoot() {
        return sourceRoot;
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
     * @throws IllegalStateException when target-limited resolution did not
     *                               establish whole-document identity
     */
    public FrozenNode canonicalAt(String pointer) {
        return canonicalIndex().get(JsonPointer.canonicalize(pointer));
    }

    /**
     * Looks up an exact input node by RFC 6901 pointer.
     *
     * @param pointer source pointer to resolve
     * @return addressed exact input node, or {@code null}
     */
    public FrozenNode sourceAt(String pointer) {
        return sourceIndex().get(JsonPointer.canonicalize(pointer));
    }

    /**
     * Calculates the canonical Content BlueId at an RFC 6901 pointer.
     *
     * @param pointer canonical pointer to resolve
     * @return the addressed node's Content BlueId, or {@code null} when absent
     * @throws IllegalStateException when target-limited resolution did not
     *                               establish whole-document identity
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
     * @throws IllegalStateException when target-limited resolution did not
     *                               establish whole-document identity
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
     * @throws IllegalStateException when target-limited resolution did not
     *                               establish whole-document identity
     */
    public Map<String, FrozenNode> canonicalIndex() {
        requireCanonicalRoot();
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

    private Map<String, FrozenNode> sourceIndex() {
        Map<String, FrozenNode> index = sourceIndex;
        if (index == null) {
            synchronized (this) {
                index = sourceIndex;
                if (index == null) {
                    index = sourceRoot.pathIndex();
                    sourceIndex = index;
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
     * @throws IllegalStateException when target-limited resolution did not
     *                               establish whole-document identity
     */
    public String blueId() {
        requireCanonicalRoot();
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
     * Returns an equivalent snapshot without standalone root-reference
     * certification.
     *
     * <p>Processor-owned document snapshots may publish their canonical and
     * resolved lanes while promoting only provider references reachable from
     * the final document graph. Such a document is not itself provider
     * reference evidence. This operation preserves every snapshot lane,
     * completeness bit, and canonical type-identity proof while removing only
     * that root certification.</p>
     *
     * @return this snapshot when no root certification is present, otherwise
     *         an evidence-equivalent snapshot with empty root provenance
     */
    public ResolvedSnapshot withoutVerifiedReferenceProvenance() {
        if (verifiedReferenceResolution() == null) {
            return this;
        }
        return new ResolvedSnapshot(
                sourceRoot,
                canonicalRoot,
                resolvedRoot,
                blueId,
                ResolutionProvenance.none(),
                canonicalTypeIdentities,
                resolutionComplete,
                sourceBacked);
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
     * Returns effective-type identity evidence retained by this snapshot.
     * Directly constructed snapshots return the fail-closed incomplete lookup;
     * resolver results retain their authoritative invocation evidence.
     *
     * @return non-null resolver-issued or fail-closed type identity lookup
     */
    public CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        return canonicalTypeIdentities;
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

    /**
     * Whether this snapshot carries a proven whole-document Canonical Identity
     * Input and therefore permits {@link #blueId()}.
     *
     * @return {@code true} when whole-document canonical identity is available
     */
    public boolean hasCanonicalIdentity() {
        return canonicalRoot != null;
    }

    /**
     * Whether future selection and patching must continue from the retained
     * exact Source lane rather than from Canonical Identity Input.
     *
     * <p>This is construction-time provenance. It is never inferred from
     * resolution completeness, canonical-identity availability, or structural
     * equality between the two lanes.</p>
     *
     * @return {@code true} when {@link #sourceRoot()} is an authored Source lane
     */
    public boolean isSourceBacked() {
        return sourceBacked;
    }

    private FrozenNode requireCanonicalRoot() {
        if (canonicalRoot == null) {
            throw new IllegalStateException(
                    "Target-limited resolution has no whole-document canonical identity");
        }
        return canonicalRoot;
    }

}
