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
        return engine.resolveSnapshot(preprocessedSource, limits);
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
        return engine.resolveSnapshot(canonicalRoot, limits);
    }

    /**
     * Resolves a graph under caller limits without claiming a whole-document
     * canonical identity, while retaining exact identities for effective
     * types reached by that invocation.
     *
     * <p>This is the appropriate boundary for target-limited operations. The
     * returned evidence may be incomplete for whole-graph canonicalization;
     * individual type lookups still fail closed when uncovered.</p>
     *
     * @param source mutable source root to resolve
     * @param limits target-driven traversal and reference limits
     * @return immutable resolved graph and invocation-local type evidence
     * @throws NullPointerException if {@code source} or {@code limits} is null
     * @throws IllegalArgumentException if the source contains malformed
     *         reference or type metadata
     * @throws IllegalStateException if exact type evidence cannot be
     *         established
     */
    @Override
    public TypeEvidenceResolution resolveTypeEvidence(
            Node source,
            ResolutionLimits limits) {
        return engine.resolveTypeEvidence(
                source,
                limits);
    }

    /**
     * Resolves one preprocessed authored type declaration as metadata.
     *
     * <p>The declaration is never treated as a runtime instance of itself, so
     * instance-field requirements contributed by the declaration or an inline
     * parent are not applied to the metadata probe.</p>
     *
     * @param declaration preprocessed authored type declaration
     * @param limits invocation-scoped traversal and reference budget
     * @return resolved declaration and same-invocation canonical type evidence
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if declaration metadata or provider
     *         evidence is invalid
     * @throws IllegalStateException if exact identity evidence cannot be
     *         established
     */
    public TypeEvidenceResolution resolveTypeDeclarationEvidence(
            Node declaration,
            ResolutionLimits limits) {
        return engine.resolveTypeDeclarationEvidence(declaration, limits);
    }

    /**
     * Materializes one pure type reference as declaration metadata rather
     * than as an instance of that type.
     *
     * <p>Required-field and other instance-schema checks therefore do not run
     * against the type declaration itself. The returned evidence is produced
     * by the same resolver invocation that materialized the declaration.</p>
     *
     * @param reference pure verified type reference
     * @param limits invocation-scoped traversal and reference budget
     * @return immutable resolved wrapper and canonical type evidence
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code reference} is not a pure
     *         valid reference
     * @throws IllegalStateException if exact materialization or canonical type
     *         evidence cannot be established
     */
    public TypeEvidenceResolution materializeTypeReferenceEvidence(
            FrozenNode reference,
            ResolutionLimits limits) {
        return engine.materializeTypeReferenceEvidence(reference, limits);
    }
}
