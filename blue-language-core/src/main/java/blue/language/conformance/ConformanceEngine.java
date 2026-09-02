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
import blue.language.merge.TypeEvidenceResolution;
import blue.language.registry.NodeProviderWrapper;
import blue.language.resolve.ResolutionLimits;
import blue.language.identity.CanonicalTypeIdentityLookup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Checks resolved Blue conformance and plans immutable type generalization.
 *
 * <p>The engine verifies provider content through a wrapped
 * {@link NodeProvider}. It may borrow a caller cache or own an isolated cache;
 * only an owned cache is released by {@link #close()}. Closing any engine
 * invalidates that engine and waits for active work to finish.</p>
 */
public final class ConformanceEngine implements AutoCloseable {

    private final NodeProvider nodeProvider;
    private final MergingProcessor mergingProcessor;
    private final ResolvedReferenceCache resolvedReferenceCache;
    private final boolean ownsReferenceCache;
    private final ReentrantReadWriteLock lifecycle =
            new ReentrantReadWriteLock(true);
    private final ThreadLocal<Integer> operationDepth =
            new ThreadLocal<>();
    private volatile boolean closed;

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
     * @return independently closeable transient planning view
     */
    public ConformanceEngine transientView() {
        return call(() -> new ConformanceEngine(
                nodeProvider,
                mergingProcessor,
                resolvedReferenceCache == null
                        ? null
                        : resolvedReferenceCache.transientChild(),
                resolvedReferenceCache != null));
    }

    /**
     * Creates a planning view backed by a sequence-local cache.
     *
     * @param transientReferenceCache borrowed sequence-local cache
     * @return transient planning view
     */
    public ConformanceEngine transientView(ResolvedReferenceCache transientReferenceCache) {
        return call(() -> new ConformanceEngine(nodeProvider,
                mergingProcessor,
                Objects.requireNonNull(
                        transientReferenceCache,
                        "transientReferenceCache"),
                false));
    }

    @Override
    public void close() {
        Integer depth = operationDepth.get();
        if (depth != null && depth > 0) {
            throw new IllegalStateException(
                    "Conformance engine cannot close from active work");
        }
        lifecycle.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            if (ownsReferenceCache && resolvedReferenceCache != null) {
                resolvedReferenceCache.close();
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    /**
     * Returns whether this engine uses the exact built-in merge pipeline that
     * participates in conservative value-only dependency analysis.
     *
     * @return whether incremental value resolution is supported
     */
    public boolean supportsIncrementalValueResolution() {
        return call(() -> mergingProcessor
                instanceof IncrementalMergingProcessorCapability
                && ((IncrementalMergingProcessorCapability) mergingProcessor)
                .supportsIncrementalValueResolution());
    }

    /**
     * Tests whether the merge pipeline accepts an incremental request.
     *
     * @param request exact dependency request
     * @return whether incremental resolution is safe
     */
    public boolean supportsIncrementalValueResolution(IncrementalValueResolutionRequest request) {
        return call(() -> mergingProcessor
                instanceof IncrementalMergingProcessorCapability
                && ((IncrementalMergingProcessorCapability) mergingProcessor)
                .supportsIncrementalValueResolution(request));
    }

    /**
     * Resolves a defensive clone and captures a conformance failure as data.
     * A null node is conformant.
     *
     * @param node node to check, or {@code null}
     * @return conformance result
     */
    public ConformanceResult check(Node node) {
        return call(() -> {
            if (node == null) {
                return ConformanceResult.conformant();
            }
            try {
                new Merger(
                        mergingProcessor,
                        nodeProvider,
                        resolvedReferenceCache).resolve(
                        node.clone(), ResolutionLimits.NO_LIMITS);
                return ConformanceResult.conformant();
            } catch (RuntimeException ex) {
                return ConformanceResult.nonConformant(ex.getMessage());
            }
        });
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
     * Plans generalization with canonical type identities captured by the
     * resolver invocation that produced {@code resolvedRoot}.
     *
     * @param canonicalRoot canonical root
     * @param resolvedRoot resolved root from the same invocation as the lookup
     * @param changedPath changed RFC 6901 path
     * @param typeIdentities exact resolver-issued type identity evidence
     * @return immutable conformance plan
     * @throws NullPointerException if {@code resolvedRoot},
     *         {@code changedPath}, or {@code typeIdentities} is null
     * @throws IllegalArgumentException if {@code changedPath} is not a valid
     *         RFC 6901 pointer
     * @throws IllegalStateException if the engine is closed or required
     *         canonical type evidence is unavailable
     */
    public ConformancePlan planGeneralization(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            String changedPath,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(typeIdentities, "typeIdentities");
        return call(() -> new FrozenConformancePlanner(
                nodeProvider,
                mergingProcessor,
                resolvedReferenceCache,
                typeIdentities).plan(
                canonicalRoot, resolvedRoot, changedPath));
    }

    /**
     * Plans ordered generalization with exact resolver-issued type evidence.
     *
     * @param canonicalRoot canonical root, or {@code null} for a deferred lane
     * @param resolvedRoot resolved root from the evidence-producing invocation
     * @param changedPaths ordered RFC 6901 paths; null or empty means unchanged
     * @param typeIdentities exact resolver-issued type identity evidence
     * @return immutable aggregate conformance plan
     * @throws NullPointerException if {@code resolvedRoot}, an element of
     *         {@code changedPaths}, or {@code typeIdentities} is null
     * @throws IllegalArgumentException if a changed path is not a valid RFC
     *         6901 pointer
     * @throws IllegalStateException if the engine is closed or required
     *         canonical type evidence is unavailable
     */
    public ConformancePlan planGeneralization(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            List<String> changedPaths,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(typeIdentities, "typeIdentities");
        return planGeneralizationPreservingPaths(
                canonicalRoot,
                resolvedRoot,
                changedPaths,
                typeIdentities,
                Collections.emptySet());
    }

    /**
     * Plans ordered generalization while preserving selected references and
     * using exact resolver-issued type identity evidence.
     *
     * @param canonicalRoot canonical root, or {@code null} for a deferred lane
     * @param resolvedRoot resolved root from the evidence-producing invocation
     * @param changedPaths ordered RFC 6901 paths; null or empty means unchanged
     * @param typeIdentities exact resolver-issued type identity evidence
     * @param preservedReferencePaths RFC 6901 paths whose references remain
     *        deferred; null is treated as empty
     * @return immutable aggregate conformance plan
     * @throws NullPointerException if {@code resolvedRoot}, an element of
     *         either path collection, or {@code typeIdentities} is null
     * @throws IllegalArgumentException if a supplied path is not a valid RFC
     *         6901 pointer
     * @throws IllegalStateException if the engine is closed or required
     *         canonical type evidence is unavailable
     */
    public ConformancePlan planGeneralizationPreservingPaths(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            List<String> changedPaths,
            CanonicalTypeIdentityLookup typeIdentities,
            Collection<String> preservedReferencePaths) {
        Objects.requireNonNull(typeIdentities, "typeIdentities");
        return call(() -> {
            if (changedPaths == null || changedPaths.isEmpty()) {
                return ConformancePlan.unchanged(
                        canonicalRoot, resolvedRoot);
            }
            FrozenNode nextCanonical = canonicalRoot;
            FrozenNode nextResolved = resolvedRoot;
            boolean generalized = false;
            List<CanonicalGeneralizationPatch> canonicalPatches =
                    new ArrayList<>();
            List<String> allChangedPaths = new ArrayList<>();
            FrozenConformancePlanner planner =
                    new FrozenConformancePlanner(
                            nodeProvider,
                            mergingProcessor,
                            resolvedReferenceCache,
                            typeIdentities,
                            preservedReferencePaths);
            for (String changedPath : changedPaths) {
                ConformancePlan plan = planner.plan(
                        nextCanonical, nextResolved, changedPath);
                nextCanonical = plan.canonicalRoot() != null
                        ? plan.canonicalRoot()
                        : nextCanonical;
                nextResolved = plan.root();
                if (plan.generalized()) {
                    generalized = true;
                    canonicalPatches.addAll(plan.canonicalPatches());
                    allChangedPaths.addAll(plan.changedPaths());
                }
            }
            if (!generalized) {
                return ConformancePlan.unchanged(
                        nextCanonical, nextResolved);
            }
            return ConformancePlan.generalized(
                    nextCanonical,
                    nextResolved,
                    canonicalPatches,
                    allChangedPaths,
                    nextCanonical != null);
        });
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
        return call(() -> {
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
        });
    }

    private <T> T call(Supplier<T> work) {
        lifecycle.readLock().lock();
        Integer previous = operationDepth.get();
        try {
            ensureOpen();
            operationDepth.set(
                    previous == null ? 1 : previous + 1);
            return work.get();
        } finally {
            if (previous == null) {
                operationDepth.remove();
            } else {
                operationDepth.set(previous);
            }
            lifecycle.readLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Conformance engine is closed");
        }
    }

    private String parentTypeBlueId(String blueId) {
        List<Node> candidates = nodeProvider.fetchByBlueId(blueId);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact type definition for " + blueId
                            + " but found " + candidates.size());
        }
        Node candidate = candidates.get(0);
        Node authoredParent = candidate.getType();
        if (authoredParent == null) {
            return null;
        }
        if (authoredParent.isReferenceOnly()) {
            return authoredParent.getBlueId();
        }

        TypeEvidenceResolution resolution = new Merger(
                mergingProcessor,
                nodeProvider,
                resolvedReferenceCache).resolveTypeEvidence(
                candidate.clone(),
                ResolutionLimits.NO_LIMITS);
        FrozenNode resolvedParent = resolution.resolvedRoot().getType();
        if (resolvedParent == null) {
            throw new IllegalStateException(
                    "Completed type definition lost its declared parent for "
                            + blueId);
        }
        return resolution.canonicalTypeIdentities()
                .requireCanonicalTypeBlueId(resolvedParent.toNode());
    }
}
