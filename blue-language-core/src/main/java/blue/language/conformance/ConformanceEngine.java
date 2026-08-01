package blue.language.conformance;

import blue.language.api.BlueCachePolicy;
import blue.language.provider.NodeProvider;
import blue.language.merge.Merger;
import blue.language.merge.IncrementalMergingProcessorCapability;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.registry.NodeProviderWrapper;
import blue.language.utils.limits.Limits;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks resolved Blue conformance and plans immutable type generalization.
 *
 * <p>The engine verifies provider content through a wrapped
 * {@link NodeProvider}. It may borrow a caller cache or own an isolated cache;
 * only an owned cache is released by {@link #close()}.</p>
 */
public final class ConformanceEngine implements AutoCloseable {

    private final NodeProvider nodeProvider;
    private final MergingProcessor mergingProcessor;
    private final ResolvedReferenceCache resolvedReferenceCache;
    private final boolean ownsReferenceCache;

    /**
     * Creates an engine without retained resolved-reference caching.
     *
     * @param nodeProvider referenced-content provider
     * @param mergingProcessor stateless merge pipeline
     */
    public ConformanceEngine(NodeProvider nodeProvider, MergingProcessor mergingProcessor) {
        this(nodeProvider, mergingProcessor, null);
    }

    /**
     * Creates an engine that borrows the supplied reference cache.
     *
     * <p>Closing this engine does not close the borrowed cache.</p>
     *
     * @param nodeProvider referenced-content provider
     * @param mergingProcessor stateless merge pipeline
     * @param resolvedReferenceCache borrowed cache, or {@code null}
     */
    public ConformanceEngine(NodeProvider nodeProvider,
                             MergingProcessor mergingProcessor,
                             ResolvedReferenceCache resolvedReferenceCache) {
        this(nodeProvider, mergingProcessor, resolvedReferenceCache, false);
    }

    /**
     * Creates an engine with an independent bounded reference cache that is
     * released when the engine is closed. This is suitable for handles whose
     * lifetime may outlast the runtime configuration that created them.
     *
     * @param nodeProvider referenced-content provider
     * @param mergingProcessor stateless merge pipeline
     * @param cachePolicy isolated cache bounds
     * @return cache-owning conformance engine
     */
    public static ConformanceEngine withIsolatedCache(
            NodeProvider nodeProvider,
            MergingProcessor mergingProcessor,
            BlueCachePolicy cachePolicy) {
        return new ConformanceEngine(nodeProvider,
                mergingProcessor,
                new ResolvedReferenceCache(Objects.requireNonNull(cachePolicy, "cachePolicy")),
                true);
    }

    /**
     * Creates an engine with an independent cache seeded from the verified
     * entries that are caller-pinned in {@code seedSource} at creation time.
     * Later source-cache invalidation cannot affect this engine, and entries
     * discovered by this engine cannot be published back to the source.
     *
     * @param nodeProvider referenced-content provider
     * @param mergingProcessor stateless merge pipeline
     * @param seedSource cache supplying pinned verified entries
     * @return cache-owning conformance engine
     */
    public static ConformanceEngine withIsolatedCache(
            NodeProvider nodeProvider,
            MergingProcessor mergingProcessor,
            ResolvedReferenceCache seedSource) {
        return new ConformanceEngine(nodeProvider,
                mergingProcessor,
                Objects.requireNonNull(seedSource, "seedSource")
                        .isolatedCopyOfPinnedVerifiedEntries(),
                true);
    }

    private ConformanceEngine(NodeProvider nodeProvider,
                              MergingProcessor mergingProcessor,
                              ResolvedReferenceCache resolvedReferenceCache,
                              boolean ownsReferenceCache) {
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.mergingProcessor = Objects.requireNonNull(mergingProcessor, "mergingProcessor");
        this.resolvedReferenceCache = resolvedReferenceCache;
        this.ownsReferenceCache = ownsReferenceCache;
    }

    /**
     * Creates a planning view that can read published reference content while
     * retaining all newly discovered reference and graph entries locally.
     *
     * @return transient planning view, or this engine when uncached
     */
    public ConformanceEngine transientView() {
        if (resolvedReferenceCache == null) {
            return this;
        }
        return new ConformanceEngine(nodeProvider,
                mergingProcessor,
                resolvedReferenceCache.transientChild(),
                true);
    }

    /**
     * Creates a planning view backed by a sequence-local cache.
     *
     * @param transientReferenceCache borrowed sequence-local cache
     * @return transient planning view
     */
    public ConformanceEngine transientView(ResolvedReferenceCache transientReferenceCache) {
        return new ConformanceEngine(nodeProvider,
                mergingProcessor,
                Objects.requireNonNull(transientReferenceCache, "transientReferenceCache"),
                false);
    }

    @Override
    public void close() {
        if (ownsReferenceCache && resolvedReferenceCache != null) {
            resolvedReferenceCache.close();
        }
    }

    /**
     * Returns whether this engine uses the exact built-in merge pipeline that
     * participates in conservative value-only dependency analysis.
     *
     * @return whether incremental value resolution is supported
     */
    public boolean supportsIncrementalValueResolution() {
        return mergingProcessor instanceof IncrementalMergingProcessorCapability
                && ((IncrementalMergingProcessorCapability) mergingProcessor)
                .supportsIncrementalValueResolution();
    }

    /**
     * Tests whether the merge pipeline accepts an incremental request.
     *
     * @param request exact dependency request
     * @return whether incremental resolution is safe
     */
    public boolean supportsIncrementalValueResolution(IncrementalValueResolutionRequest request) {
        return mergingProcessor instanceof IncrementalMergingProcessorCapability
                && ((IncrementalMergingProcessorCapability) mergingProcessor)
                .supportsIncrementalValueResolution(request);
    }

    /**
     * Resolves a defensive clone and captures a conformance failure as data.
     * A null node is conformant.
     *
     * @param node node to check, or {@code null}
     * @return conformance result
     */
    public ConformanceResult check(Node node) {
        if (node == null) {
            return ConformanceResult.conformant();
        }
        try {
            new Merger(mergingProcessor, nodeProvider, resolvedReferenceCache).resolve(node.clone(), Limits.NO_LIMITS);
            return ConformanceResult.conformant();
        } catch (RuntimeException ex) {
            return ConformanceResult.nonConformant(ex.getMessage());
        }
    }

    /**
     * Tests resolved conformance.
     *
     * @param node node to check, or {@code null}
     * @return whether the node conforms
     */
    public boolean conforms(Node node) {
        return check(node).isConformant();
    }

    /**
     * Requires resolved conformance.
     *
     * @param node node to check, or {@code null}
     * @throws IllegalArgumentException when {@code node} does not conform
     */
    public void requireConformant(Node node) {
        ConformanceResult result = check(node);
        if (!result.isConformant()) {
            throw new IllegalArgumentException(result.getMessage());
        }
    }

    /**
     * Plans generalization for one changed resolved path.
     *
     * @param resolvedRoot resolved root
     * @param changedPath changed RFC 6901 path
     * @return immutable conformance plan
     */
    public ConformancePlan planGeneralization(FrozenNode resolvedRoot, String changedPath) {
        return planGeneralization(null, resolvedRoot, changedPath);
    }

    /**
     * Plans generalization for canonical and resolved roots.
     *
     * @param canonicalRoot canonical root
     * @param resolvedRoot resolved root
     * @param changedPath changed RFC 6901 path
     * @return immutable conformance plan
     */
    public ConformancePlan planGeneralization(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String changedPath) {
        return new FrozenConformancePlanner(nodeProvider, mergingProcessor, resolvedReferenceCache)
                .plan(canonicalRoot, resolvedRoot, changedPath);
    }

    /**
     * Plans ordered generalization for several changed paths.
     *
     * @param canonicalRoot canonical root
     * @param resolvedRoot resolved root
     * @param changedPaths changed RFC 6901 paths
     * @return immutable conformance plan
     */
    public ConformancePlan planGeneralization(FrozenNode canonicalRoot,
                                              FrozenNode resolvedRoot,
                                              List<String> changedPaths) {
        return planGeneralizationPreservingPaths(
                canonicalRoot,
                resolvedRoot,
                changedPaths,
                Collections.emptySet());
    }

    /**
     * Plans generalization while leaving selected pure-reference subtrees
     * collapsed. Callers remain responsible for materializing any selected
     * executable subtree before it is used.
     *
     * @param canonicalRoot canonical root
     * @param resolvedRoot resolved root
     * @param changedPaths changed RFC 6901 paths
     * @param preservedReferencePaths paths that must remain collapsed
     * @return immutable conformance plan
     */
    public ConformancePlan planGeneralizationPreservingPaths(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            List<String> changedPaths,
            Collection<String> preservedReferencePaths) {
        if (changedPaths == null || changedPaths.isEmpty()) {
            return ConformancePlan.unchanged(canonicalRoot, resolvedRoot);
        }
        FrozenNode nextCanonical = canonicalRoot;
        FrozenNode nextResolved = resolvedRoot;
        boolean generalized = false;
        List<CanonicalGeneralizationPatch> canonicalPatches = new ArrayList<>();
        List<String> allChangedPaths = new ArrayList<>();
        FrozenConformancePlanner planner = new FrozenConformancePlanner(nodeProvider,
                mergingProcessor,
                resolvedReferenceCache,
                preservedReferencePaths);
        for (String changedPath : changedPaths) {
            ConformancePlan plan = planner.plan(nextCanonical, nextResolved, changedPath);
            nextCanonical = plan.canonicalRoot() != null ? plan.canonicalRoot() : nextCanonical;
            nextResolved = plan.root();
            if (plan.generalized()) {
                generalized = true;
                canonicalPatches.addAll(plan.canonicalPatches());
                allChangedPaths.addAll(plan.changedPaths());
            }
        }
        if (!generalized) {
            return ConformancePlan.unchanged(nextCanonical, nextResolved);
        }
        return ConformancePlan.generalized(nextCanonical,
                nextResolved,
                canonicalPatches,
                allChangedPaths,
                nextCanonical != null);
    }

    /**
     * Follows verified declared-type ancestry and returns whether the candidate
     * is the expected type or one of its subtypes. Missing evidence and cycles
     * fail closed.
     *
     * @param candidateBlueId candidate type identity
     * @param expectedAncestorBlueId expected ancestor identity
     * @return whether the candidate is the same type or a verified subtype
     */
    public boolean isSubtypeOf(String candidateBlueId, String expectedAncestorBlueId) {
        if (candidateBlueId == null || expectedAncestorBlueId == null) {
            return false;
        }
        String current = candidateBlueId;
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (Objects.equals(current, expectedAncestorBlueId)) {
                return true;
            }
            current = parentTypeBlueId(current);
        }
        return false;
    }

    private String parentTypeBlueId(String blueId) {
        List<Node> candidates = nodeProvider.fetchByBlueId(blueId);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Node type = candidates.get(0).getType();
        return type != null ? type.getBlueId() : null;
    }
}
