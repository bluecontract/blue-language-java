package blue.language.merge;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.utils.limits.Limits;

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

    /** Creates a merge facade without retained resolved-reference caching. */
    public Merger(MergingProcessor mergingProcessor, NodeProvider nodeProvider) {
        this.engine = new ResolutionEngine(mergingProcessor, nodeProvider);
    }

    /** Creates a merge facade with an optional verified-reference cache. */
    public Merger(MergingProcessor mergingProcessor,
                  NodeProvider nodeProvider,
                  ResolvedReferenceCache resolvedReferenceCache) {
        this.engine = new ResolutionEngine(
                mergingProcessor, nodeProvider, resolvedReferenceCache);
    }

    /** Creates a merge facade with an explicit host cache-admission policy. */
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

    /** Resolves a mutable source into a completed value. */
    @Override
    public Node resolve(Node node, Limits limits) {
        return engine.resolve(node, limits);
    }

    /** Merges one source contribution into a mutable target. */
    public void merge(Node target, Node source, Limits limits) {
        engine.merge(target, source, limits);
    }

    /** Resolves and binds canonical and completed representations. */
    public SnapshotResolution resolveSnapshot(
            Node preprocessedSource,
            Limits limits) {
        return new SnapshotResolution(
                engine.resolveSnapshot(preprocessedSource, limits));
    }

    /** Resolves an already strict-canonical source. */
    public SnapshotResolution resolveSnapshot(
            FrozenNode canonicalRoot,
            Limits limits) {
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

        @Override
        public FrozenNode canonicalRoot() {
            return standalone.canonicalRoot();
        }

        @Override
        public FrozenNode resolvedRoot() {
            return standalone.resolvedRoot();
        }

        @Override
        public ResolutionProvenance provenance() {
            return standalone.provenance();
        }

        /** Returns the focused standalone result. */
        public blue.language.merge.SnapshotResolution asStandalone() {
            return standalone;
        }

        /** Returns verified evidence, or {@code null} when ineligible. */
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

        /** Returns the focused standalone evidence. */
        public blue.language.merge.VerifiedReferenceResolution asStandalone() {
            return standalone;
        }

        /** Returns the exact BlueId requested by the resolver. */
        public String requestedBlueId() {
            return standalone.requestedBlueId();
        }

        /** Returns the strict canonical root covered by the evidence. */
        public FrozenNode canonicalRoot() {
            return standalone.canonicalRoot();
        }

        /** Returns the completed resolved root covered by the evidence. */
        public FrozenNode resolvedRoot() {
            return standalone.resolvedRoot();
        }
    }
}
