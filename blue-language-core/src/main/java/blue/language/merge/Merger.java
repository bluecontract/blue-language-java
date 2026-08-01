package blue.language.merge;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.resolve.ResolutionLimits;

/**
 * Public facade for one deterministic Blue Language merge configuration.
 *
 * <p>Every top-level call is delegated to an invocation-scoped resolution
 * engine. Recursive calls made through {@link NodeResolver} remain in that
 * invocation, while concurrent calls on this facade never share mutable
 * resolution state.</p>
 */
public final class Merger implements NodeResolver {

    private final ResolutionEngine engine;

    /**
     * Creates a merge facade without retained resolved-reference caching.
     *
     * @param mergingProcessor stateless processor implementing merge semantics
     * @param nodeProvider provider used to resolve exact referenced content
     * @throws NullPointerException if {@code nodeProvider} is {@code null}
     */
    public Merger(MergingProcessor mergingProcessor, NodeProvider nodeProvider) {
        this.engine = new ResolutionEngine(mergingProcessor, nodeProvider);
    }

    /**
     * Creates a merge facade with an optional verified-reference cache.
     *
     * @param mergingProcessor stateless processor implementing merge semantics
     * @param nodeProvider provider used to resolve exact referenced content
     * @param resolvedReferenceCache cache of identity-verified canonical and
     *                               resolved references, or {@code null}
     * @throws NullPointerException if {@code nodeProvider} is {@code null}
     */
    public Merger(MergingProcessor mergingProcessor,
                  NodeProvider nodeProvider,
                  ResolvedReferenceCache resolvedReferenceCache) {
        this.engine = new ResolutionEngine(
                mergingProcessor, nodeProvider, resolvedReferenceCache);
    }

    /**
     * Creates a merge facade with an explicit host cache-admission policy.
     *
     * @param mergingProcessor stateless processor implementing merge semantics
     * @param nodeProvider provider used to resolve exact referenced content
     * @param resolvedReferenceCache cache of identity-verified canonical and
     *                               resolved references, or {@code null}
     * @param referenceCacheAdmissionPolicy host policy controlling which exact
     *                                      provider content may be retained
     * @throws NullPointerException if {@code nodeProvider} or
     *                              {@code referenceCacheAdmissionPolicy} is
     *                              {@code null}
     */
    public Merger(MergingProcessor mergingProcessor,
                  NodeProvider nodeProvider,
                  ResolvedReferenceCache resolvedReferenceCache,
                  ReferenceCacheAdmissionPolicy referenceCacheAdmissionPolicy) {
        this.engine = new ResolutionEngine(
                mergingProcessor,
                nodeProvider,
                resolvedReferenceCache,
                referenceCacheAdmissionPolicy);
    }

    /**
     * Resolves a mutable source into a completed value.
     *
     * @param node mutable source root to resolve
     * @param limits invocation-scoped traversal and reference budget
     * @return resolved graph, normally the supplied root
     */
    @Override
    public Node resolve(Node node, ResolutionLimits limits) {
        return engine.resolve(node, limits);
    }

    /**
     * Merges one source contribution into a mutable target.
     *
     * @param target mutable target receiving the contribution
     * @param source source contribution to merge
     * @param limits invocation-scoped traversal and reference budget
     */
    public void merge(Node target, Node source, ResolutionLimits limits) {
        engine.merge(target, source, limits);
    }

    /**
     * Resolves and binds canonical and completed representations.
     *
     * @param preprocessedSource mutable preprocessed source root
     * @param limits invocation-scoped traversal and reference budget
     * @return immutable canonical/resolved pair with invocation provenance
     */
    public SnapshotResolution resolveSnapshot(
            Node preprocessedSource,
            ResolutionLimits limits) {
        return new SnapshotResolution(
                engine.resolveSnapshot(preprocessedSource, limits));
    }

    /**
     * Resolves an already strict-canonical source.
     *
     * @param canonicalRoot strict canonical source root
     * @param limits invocation-scoped traversal and reference budget
     * @return immutable canonical/resolved pair with invocation provenance
     */
    public SnapshotResolution resolveSnapshot(
            FrozenNode canonicalRoot,
            ResolutionLimits limits) {
        return new SnapshotResolution(
                engine.resolveSnapshot(canonicalRoot, limits));
    }

    /** Historical nested view over the standalone immutable result. */
    public static final class SnapshotResolution implements ResolutionSnapshot {
        private final blue.language.merge.SnapshotResolution standalone;
        private final VerifiedReferenceResolution verifiedReferenceResolution;

        private SnapshotResolution(
                blue.language.merge.SnapshotResolution standalone) {
            this.standalone = standalone;
            blue.language.merge.VerifiedReferenceResolution evidence =
                    standalone.verifiedReferenceResolution();
            this.verifiedReferenceResolution = evidence == null
                    ? null
                    : new VerifiedReferenceResolution(
                    evidence.requestedBlueId(),
                    evidence.canonicalRoot(),
                    evidence.resolvedRoot());
        }

        /**
         * Returns the strict canonical root captured by this resolution.
         *
         * @return immutable strict canonical root
         */
        @Override
        public FrozenNode canonicalRoot() {
            return standalone.canonicalRoot();
        }

        /**
         * Returns the completed root produced by this resolution.
         *
         * @return immutable completed resolved root
         */
        @Override
        public FrozenNode resolvedRoot() {
            return standalone.resolvedRoot();
        }

        /**
         * Returns provenance captured by the same resolver invocation.
         *
         * @return immutable resolution provenance
         */
        @Override
        public ResolutionProvenance provenance() {
            return standalone.provenance();
        }

        /**
         * Returns the focused standalone result.
         *
         * @return standalone immutable resolution result
         */
        public blue.language.merge.SnapshotResolution asStandalone() {
            return standalone;
        }

        /**
         * Returns resolver-issued verified-reference evidence when eligible.
         *
         * @return verified reference evidence, or {@code null} when the
         *         resolution is not cache-eligible reference materialization
         */
        public VerifiedReferenceResolution verifiedReferenceResolution() {
            return verifiedReferenceResolution;
        }
    }

    /** Historical nested view over standalone resolver-issued evidence. */
    public static final class VerifiedReferenceResolution {
        private final blue.language.merge.VerifiedReferenceResolution standalone;

        private VerifiedReferenceResolution(
                String requestedBlueId,
                FrozenNode canonicalRoot,
                FrozenNode resolvedRoot) {
            this.standalone = new blue.language.merge.VerifiedReferenceResolution(
                    requestedBlueId, canonicalRoot, resolvedRoot);
        }

        /**
         * Returns the focused standalone evidence.
         *
         * @return standalone immutable verified-reference evidence
         */
        public blue.language.merge.VerifiedReferenceResolution asStandalone() {
            return standalone;
        }

        /**
         * Returns the exact BlueId requested by the resolver.
         *
         * @return requested exact BlueId
         */
        public String requestedBlueId() {
            return standalone.requestedBlueId();
        }

        /**
         * Returns the strict canonical root covered by the evidence.
         *
         * @return immutable strict canonical root
         */
        public FrozenNode canonicalRoot() {
            return standalone.canonicalRoot();
        }

        /**
         * Returns the completed resolved root covered by the evidence.
         *
         * @return immutable completed resolved root
         */
        public FrozenNode resolvedRoot() {
            return standalone.resolvedRoot();
        }
    }
}
