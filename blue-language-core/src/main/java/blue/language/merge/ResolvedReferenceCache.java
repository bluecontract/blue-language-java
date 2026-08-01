package blue.language.merge;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Cache of content whose canonical identity has been verified against its BlueId.
 *
 * <p>Resolved graph nodes must never be inserted merely because they carry a
 * {@code blueId}: inherited schema and other contextual contributions can make
 * such a node differ from the standalone content addressed by that identity.</p>
 */
public final class ResolvedReferenceCache
        implements AutoCloseable {

    final ResolvedReferenceCache readThroughParent;
    final ResolvedReferenceCacheGeneration cacheGeneration;
    private final BlueCachePolicy cachePolicy;
    final long openedGeneration;
    volatile long observedGeneration;
    volatile boolean locallyClosed;
    final ConcurrentMap<String, VerifiedReferenceEntry> entriesByBlueId = new ConcurrentHashMap<>();
    final ConcurrentMap<FrozenNode.ResolvedStructuralKey, FrozenNode> resolvedGraphNodesByStructure =
            new ConcurrentHashMap<>();
    private final FrozenNode.ResolvedStructuralInterner resolvedGraphInterner;
    private final FrozenNode.ResolvedStructuralInterner existingResolvedGraphInterner;
    final ResolvedReferenceCacheAccounting accounting;
    private final VerifiedCanonicalLoadCoordinator.Access canonicalLoadAccess;

    /** Creates an independent root cache with the standard bounded policy. */
    public ResolvedReferenceCache() {
        this(BlueCachePolicy.boundedDefaults());
    }

    /**
     * Creates an independent root cache governed by {@code cachePolicy}.
     *
     * @param cachePolicy bounds and admission policy for retained cache entries
     */
    public ResolvedReferenceCache(BlueCachePolicy cachePolicy) {
        this.readThroughParent = null;
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        this.cacheGeneration =
                new ResolvedReferenceCacheGeneration();
        this.openedGeneration = -1L;
        this.observedGeneration = cacheGeneration.value.get();
        this.accounting = new ResolvedReferenceCacheAccounting(
                cachePolicy, true);
        this.canonicalLoadAccess = newCanonicalLoadAccess();
        this.resolvedGraphInterner = newResolvedGraphInterner();
        this.existingResolvedGraphInterner = newExistingResolvedGraphInterner();
        cacheGeneration.register(this);
    }

    private ResolvedReferenceCache(ResolvedReferenceCache readThroughParent) {
        this(readThroughParent,
                readThroughParent.readThroughParent == null
                        ? readThroughParent.cacheGeneration.value.get()
                        : readThroughParent.openedGeneration);
    }

    private ResolvedReferenceCache(ResolvedReferenceCache readThroughParent,
                                   long openedGeneration) {
        this.readThroughParent = readThroughParent;
        this.cachePolicy = readThroughParent.cachePolicy;
        this.cacheGeneration = readThroughParent.cacheGeneration;
        this.openedGeneration = openedGeneration;
        this.observedGeneration = cacheGeneration.value.get();
        this.accounting = new ResolvedReferenceCacheAccounting(
                cachePolicy, false);
        this.canonicalLoadAccess = newCanonicalLoadAccess();
        this.resolvedGraphInterner = newResolvedGraphInterner();
        this.existingResolvedGraphInterner = newExistingResolvedGraphInterner();
        cacheGeneration.register(this);
    }

    private FrozenNode.ResolvedStructuralInterner newResolvedGraphInterner() {
        return new FrozenNode.ResolvedStructuralInterner() {
            @Override
            public FrozenNode intern(FrozenNode.ResolvedStructuralKey structuralKey,
                                     FrozenNode node) {
                synchronized (cacheGeneration.mutationLock) {
                    ensureCurrentGeneration();
                    FrozenNode local = resolvedGraphNodesByStructure.get(structuralKey);
                    if (local != null) {
                        return local;
                    }
                    FrozenNode inherited = inheritedResolvedGraph(structuralKey);
                    if (inherited != null) {
                        return inherited;
                    }
                    FrozenNode existing = resolvedGraphNodesByStructure.putIfAbsent(
                            structuralKey, node);
                    if (existing != null) {
                        return existing;
                    }
                    accounting.recordStructuralInsertion(
                            structuralKey,
                            node,
                            resolvedGraphNodesByStructure);
                    return node;
                }
            }
        };
    }

    private FrozenNode.ResolvedStructuralInterner newExistingResolvedGraphInterner() {
        return new FrozenNode.ResolvedStructuralInterner() {
            @Override
            public FrozenNode intern(FrozenNode.ResolvedStructuralKey structuralKey,
                                     FrozenNode candidate) {
                FrozenNode existing = findResolvedGraph(structuralKey);
                return existing != null ? existing : candidate;
            }
        };
    }

    private VerifiedCanonicalLoadCoordinator.Access
    newCanonicalLoadAccess() {
        return new VerifiedCanonicalLoadCoordinator.Access() {
            @Override
            Object mutationLock() {
                return cacheGeneration.mutationLock;
            }

            @Override
            long currentGeneration() {
                return cacheGeneration.value.get();
            }

            @Override
            void ensureCurrentGeneration() {
                ResolvedReferenceCache.this
                        .ensureCurrentGeneration();
            }

            @Override
            FrozenNode visibleCanonical(String blueId) {
                VerifiedReferenceEntry local =
                        entriesByBlueId.get(blueId);
                VerifiedReferenceEntry visible = local != null
                        ? local
                        : inheritedEntry(blueId);
                return visible != null
                        ? visible.canonicalContent
                        : null;
            }

            @Override
            void requireCanonical(
                    String blueId,
                    FrozenNode canonical) {
                ResolvedReferenceCache.this.requireCanonical(
                        blueId, canonical);
            }

            @Override
            FrozenNode retainLoaded(
                    long loadingGeneration,
                    String blueId,
                    FrozenNode loaded) {
                if (loadingGeneration
                        != cacheGeneration.value.get()) {
                    return null;
                }
                ensureCurrentGeneration();
                VerifiedReferenceEntry local =
                        entriesByBlueId.get(blueId);
                if (local != null) {
                    return local.canonicalContent;
                }
                VerifiedReferenceEntry inherited =
                        inheritedEntry(blueId);
                if (inherited != null) {
                    return inherited.canonicalContent;
                }
                VerifiedReferenceEntry created =
                        new VerifiedReferenceEntry(loaded, null);
                VerifiedReferenceEntry retained =
                        entriesByBlueId.putIfAbsent(
                                blueId, created);
                if (retained != null) {
                    return retained.canonicalContent;
                }
                accounting.recordVerifiedInsertion(
                        blueId, created, entriesByBlueId);
                return loaded;
            }
        };
    }

    /**
     * Returns a cache that can reuse this cache's published entries but retains
     * all newly resolved references and graph nodes locally. Discarding the
     * child therefore discards every transient working-state cache insertion.
     *
     * @return a new transient child cache
     */
    public ResolvedReferenceCache transientChild() {
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            return new ResolvedReferenceCache(this);
        }
    }

    /**
     * Returns an independent transient cache with the same parent and local retained entries.
     *
     * @return a new transient cache containing this scope's retained entries
     */
    public ResolvedReferenceCache forkTransient() {
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            long forkGeneration = readThroughParent != null
                    ? openedGeneration
                    : cacheGeneration.value.get();
            ResolvedReferenceCache fork = new ResolvedReferenceCache(
                    readThroughParent != null ? readThroughParent : this,
                    forkGeneration);
            if (readThroughParent != null) {
                fork.entriesByBlueId.putAll(entriesByBlueId);
                fork.resolvedGraphNodesByStructure.putAll(resolvedGraphNodesByStructure);
                fork.rebuildLocalWeightAccounting();
            }
            return fork;
        }
    }

    /**
     * Creates an independent root cache containing only the caller-pinned
     * verified entries visible at the time of this call. The returned cache
     * shares immutable frozen graphs, but it has its own generation, mutation
     * state, and bounded storage for entries discovered later. Reloadable and
     * structural-interner entries are not copied.
     *
     * <p>The caller owns the returned cache and should close it when the
     * retained snapshot is no longer needed.</p>
     *
     * @return an independent root cache containing visible pinned evidence
     */
    public ResolvedReferenceCache isolatedCopyOfPinnedVerifiedEntries() {
        Map<String, VerifiedReferenceEntry> retainedPinned = new HashMap<>();
        BlueCachePolicy retainedPolicy;
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            ResolvedReferenceCache root = rootCache();
            root.ensureCurrentGeneration();
            retainedPolicy = root.cachePolicy;
            for (String blueId :
                    root.accounting.pinnedBlueIdsSnapshot()) {
                VerifiedReferenceEntry entry = root.entriesByBlueId.get(blueId);
                if (entry != null) {
                    retainedPinned.put(blueId, entry);
                }
            }
        }

        ResolvedReferenceCache isolated = new ResolvedReferenceCache(retainedPolicy);
        synchronized (isolated.cacheGeneration.mutationLock) {
            isolated.entriesByBlueId.putAll(retainedPinned);
            isolated.rebuildLocalWeightAccounting(retainedPinned.keySet());
        }
        return isolated;
    }

    /**
     * Binary-compatible fail-closed view of the removed transient-trust cache.
     * Only independently verified canonical entries are reusable.
     *
     * @param blueId requested content identity
     * @return an empty result because transient-trust reuse is disabled
     */
    public Optional<FrozenNode> getTransientTrustedCanonical(
            String blueId) {
        Objects.requireNonNull(blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        ensureCurrentGeneration();
        return Optional.empty();
    }

    /**
     * Binary-compatible fail-closed bridge. The supplied value is returned to
     * its caller but is deliberately not retained as verified evidence.
     *
     * @param blueId claimed content identity
     * @param canonicalContent content that must remain outside verified storage
     * @return {@code canonicalContent} unchanged
     */
    public FrozenNode putTransientTrustedCanonical(
            String blueId,
            FrozenNode canonicalContent) {
        Objects.requireNonNull(blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        Objects.requireNonNull(
                canonicalContent, "canonicalContent");
        ensureCurrentGeneration();
        return canonicalContent;
    }

    /**
     * Returns verified materialized canonical content visible to this scope.
     *
     * @param blueId content identity to look up
     * @return the visible canonical content, or an empty result when absent
     */
    public Optional<FrozenNode> getVerifiedCanonical(String blueId) {
        ensureCurrentGeneration();
        VerifiedReferenceEntry entry = findEntry(blueId);
        return Optional.ofNullable(entry != null ? entry.canonicalContent : null);
    }

    /**
     * Returns completed resolved content paired with verified canonical evidence.
     *
     * @param blueId content identity to look up
     * @return the visible resolved content, or an empty result when absent
     */
    public Optional<FrozenNode> getVerifiedResolved(String blueId) {
        ensureCurrentGeneration();
        VerifiedReferenceEntry local = entriesByBlueId.get(blueId);
        FrozenNode resolved = local != null ? local.fullyResolvedContent : null;
        if (resolved == null && readThroughParent != null) {
            resolved = readThroughParent.getVerifiedResolved(blueId).orElse(null);
        }
        if (resolved != null && resolved.isReferenceOnly()) {
            throw new IllegalStateException("Verified resolved content is reference-only for blueId: " + blueId);
        }
        return Optional.ofNullable(resolved);
    }

    /**
     * Retains strict, materialized canonical content only after its calculated
     * identity matches the key.
     *
     * @param blueId expected Content BlueId
     * @param canonicalContent strict materialized canonical content
     * @return the canonical instance retained for {@code blueId}
     */
    public FrozenNode putVerifiedCanonical(String blueId, FrozenNode canonicalContent) {
        Objects.requireNonNull(blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        requireCanonical(blueId, canonicalContent);
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            VerifiedReferenceEntry local = entriesByBlueId.get(blueId);
            if (local != null) {
                return local.canonicalContent;
            }
            VerifiedReferenceEntry inherited = inheritedEntry(blueId);
            if (inherited != null) {
                return inherited.canonicalContent;
            }
            VerifiedReferenceEntry created = new VerifiedReferenceEntry(canonicalContent, null);
            VerifiedReferenceEntry retained = entriesByBlueId.putIfAbsent(blueId, created);
            if (retained == null) {
                accounting.recordVerifiedInsertion(
                        blueId, created, entriesByBlueId);
                return canonicalContent;
            }
            return retained.canonicalContent;
        }
    }

    /**
     * Returns visible verified canonical content or loads and verifies it once
     * for the current cache generation. Concurrent requests for the same
     * identity share one in-flight load.
     *
     * @param blueId expected Content BlueId
     * @param canonicalLoader provider invoked when verified content is absent
     * @return the verified canonical instance retained for {@code blueId}
     */
    public FrozenNode getOrLoadVerifiedCanonical(String blueId,
                                                 Supplier<FrozenNode> canonicalLoader) {
        Objects.requireNonNull(blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        Objects.requireNonNull(canonicalLoader, "canonicalLoader");
        return cacheGeneration.canonicalLoads.getOrLoad(
                blueId, canonicalLoader, canonicalLoadAccess);
    }

    static void setCanonicalLoadObserverForTesting(Consumer<String> observer) {
        VerifiedCanonicalLoadCoordinator.setLoadObserver(
                observer);
    }

    static void setCanonicalLoadWaitObserverForTesting(Consumer<String> observer) {
        VerifiedCanonicalLoadCoordinator.setWaitObserver(
                observer);
    }

    /**
     * Retains a completed resolution backed by verified canonical evidence.
     *
     * @param verification verified canonical and resolved roots for one reference
     * @return the resolved instance retained for the requested BlueId
     */
    public FrozenNode putVerifiedResolved(VerifiedReferenceResolution verification) {
        Objects.requireNonNull(verification, "verification");
        return retainVerifiedResolved(verification.requestedBlueId(),
                verification.canonicalRoot(),
                verification.resolvedRoot());
    }

    /**
     * Retains caller-registered authoritative content until explicit clear.
     * Derived entries remain subject to this cache's configured weight bounds.
     *
     * @param verification verified authoritative content to pin
     * @return the resolved instance retained for the requested BlueId
     */
    public FrozenNode putPinnedVerifiedResolved(VerifiedReferenceResolution verification) {
        Objects.requireNonNull(verification, "verification");
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            if (!isCurrentGeneration()) {
                throw new IllegalStateException(
                        "Stale transient reference cache cannot publish pinned evidence");
            }
            if (readThroughParent != null) {
                return rootCache().putPinnedVerifiedResolved(verification);
            }
            accounting.pin(verification.requestedBlueId());
            return retainVerifiedResolved(verification.requestedBlueId(),
                    verification.canonicalRoot(),
                    verification.resolvedRoot());
        }
    }

    private ResolvedReferenceCache rootCache() {
        ResolvedReferenceCache root = this;
        while (root.readThroughParent != null) {
            root = root.readThroughParent;
        }
        return root;
    }

    private FrozenNode retainVerifiedResolved(String blueId,
                                              FrozenNode canonicalContent,
                                              FrozenNode fullyResolvedContent) {
        Objects.requireNonNull(blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        requireCanonical(blueId, canonicalContent);
        requireResolved(blueId, fullyResolvedContent);
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            VerifiedReferenceEntry local = entriesByBlueId.get(blueId);
            if (local != null && local.fullyResolvedContent != null) {
                return local.fullyResolvedContent;
            }
            VerifiedReferenceEntry inherited = inheritedEntry(blueId);
            if (inherited != null && inherited.fullyResolvedContent != null) {
                return inherited.fullyResolvedContent;
            }
            FrozenNode retainedCanonical = local != null
                    ? local.canonicalContent
                    : inherited != null ? inherited.canonicalContent : canonicalContent;
            FrozenNode retainedResolved = local != null && local.fullyResolvedContent != null
                    ? local.fullyResolvedContent
                    : fullyResolvedContent;
            VerifiedReferenceEntry retained = new VerifiedReferenceEntry(
                    retainedCanonical, retainedResolved);
            entriesByBlueId.put(blueId, retained);
            accounting.recordVerifiedReplacement(
                    blueId, local, retained, entriesByBlueId);
            return retained.fullyResolvedContent;
        }
    }

    /**
     * Freezes a resolved graph and interns new structural representations in this cache.
     *
     * @param node mutable resolved graph to freeze
     * @return an immutable resolved graph with reusable subtrees
     */
    public FrozenNode freezeResolved(Node node) {
        ensureCurrentGeneration();
        return FrozenNode.fromResolvedNode(node, resolvedGraphInterner);
    }

    /**
     * Freezes a transient resolved graph while reusing already-published
     * subtrees, without retaining any new intermediate subtree in this cache.
     *
     * @param node mutable resolved graph to freeze
     * @return an immutable graph reusing any previously retained subtrees
     */
    public FrozenNode freezeResolvedWithoutRemembering(Node node) {
        ensureCurrentGeneration();
        return FrozenNode.fromResolvedNode(node, existingResolvedGraphInterner);
    }

    /**
     * Seeds structural sharing from a completed immutable graph without
     * promoting any node to verified provider content.
     *
     * @param node completed resolved graph whose structure should be remembered
     */
    public void rememberResolvedGraph(FrozenNode node) {
        ensureCurrentGeneration();
        ResolvedReferenceGraphIndex.remember(
                node, resolvedGraphInterner);
    }

    /**
     * Promotes only verified references that remain reachable from a completed
     * canonical graph. Entries discovered solely in discarded intermediate
     * states remain local to this transient child.
     *
     * @param canonicalRoot completed canonical graph defining reachability
     */
    public void promoteReferencesReachableFrom(FrozenNode canonicalRoot) {
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            if (!isCurrentGeneration()
                    || readThroughParent == null
                    || canonicalRoot == null
                    || entriesByBlueId.isEmpty()) {
                return;
            }
            Set<String> reachableReferences =
                    ResolvedReferenceGraphIndex.referencedBlueIds(
                            canonicalRoot);
            Deque<String> pending = new ArrayDeque<>(reachableReferences);
            Set<String> visitedReferences = new HashSet<>();
            while (!pending.isEmpty()) {
                String blueId = pending.removeFirst();
                if (!visitedReferences.add(blueId)) {
                    continue;
                }
                VerifiedReferenceEntry local = entriesByBlueId.get(blueId);
                VerifiedReferenceEntry visible = local != null
                        ? local
                        : readThroughParent.findEntry(blueId);
                if (visible == null) {
                    continue;
                }
                FrozenNode retainedCanonical = local != null
                        ? readThroughParent.putVerifiedCanonical(blueId, local.canonicalContent)
                        : visible.canonicalContent;
                Set<String> dependencies =
                        ResolvedReferenceGraphIndex.referencedBlueIds(
                                retainedCanonical);
                for (String dependency : dependencies) {
                    if (!visitedReferences.contains(dependency)) {
                        pending.addLast(dependency);
                    }
                }
                if (local != null
                        && local.fullyResolvedContent != null
                        && (retainedCanonical == local.canonicalContent
                        || retainedCanonical.sameResolvedStructure(local.canonicalContent))) {
                    readThroughParent.retainVerifiedResolved(
                            blueId, retainedCanonical, local.fullyResolvedContent);
                }
            }
        }
    }

    /**
     * Drops transient entries that are not reachable from the current working
     * graph. This bounds a reusable WorkingDocument cache by current state,
     * rather than by the number of edits performed over its lifetime.
     *
     * @param canonicalRoot canonical graph defining reachable reference entries
     * @param resolvedRoot resolved graph defining reachable structural entries
     */
    public void retainOnlyReachableFrom(FrozenNode canonicalRoot, FrozenNode resolvedRoot) {
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            if (readThroughParent == null) {
                return;
            }
            Set<String> reachableReferences =
                    ResolvedReferenceGraphIndex.referencedBlueIds(
                            canonicalRoot);
            Deque<String> pending = new ArrayDeque<>(reachableReferences);
            while (!pending.isEmpty()) {
                String blueId = pending.removeFirst();
                VerifiedReferenceEntry local = entriesByBlueId.get(blueId);
                FrozenNode retainedCanonical = local != null ? local.canonicalContent : null;
                if (retainedCanonical == null) {
                    continue;
                }
                Set<String> dependencies =
                        ResolvedReferenceGraphIndex.referencedBlueIds(
                                retainedCanonical);
                for (String dependency : dependencies) {
                    if (reachableReferences.add(dependency)) {
                        pending.addLast(dependency);
                    }
                }
            }
            for (String blueId : new HashSet<>(entriesByBlueId.keySet())) {
                if (!reachableReferences.contains(blueId)) {
                    accounting.removeVerifiedEntry(
                            blueId, entriesByBlueId);
                }
            }

            Set<FrozenNode.ResolvedStructuralKey> reachableGraphNodes =
                    ResolvedReferenceGraphIndex.structuralKeys(
                            resolvedRoot);
            for (FrozenNode.ResolvedStructuralKey key
                    : new HashSet<>(resolvedGraphNodesByStructure.keySet())) {
                if (!reachableGraphNodes.contains(key)) {
                    accounting.removeStructuralEntry(
                            key, resolvedGraphNodesByStructure);
                }
            }
        }
    }

    void rebuildLocalWeightAccounting() {
        rebuildLocalWeightAccounting(Collections.<String>emptySet());
    }

    void rebuildLocalWeightAccounting(Set<String> retainedPinnedBlueIds) {
        accounting.rebuild(
                entriesByBlueId,
                resolvedGraphNodesByStructure,
                retainedPinnedBlueIds);
    }

    /**
     * Captures immutable approximate cache accounting for integration and lifecycle reports.
     *
     * @return current entries, weights, high-water marks, and eviction counts
     */
    public CacheStats cacheStats() {
        return ResolvedReferenceCacheLifecycle.cacheStats(this);
    }

    CacheStats localCacheStats() {
        return accounting.snapshot(
                entriesByBlueId.size(),
                resolvedGraphNodesByStructure.size());
    }

    /**
     * Returns the number of verified entries retained directly by this cache.
     *
     * @return the local verified-entry count
     */
    public int size() {
        ensureCurrentGeneration();
        return entriesByBlueId.size();
    }

    /**
     * Returns the approximate weight of caller-pinned verified entries retained
     * across configuration refresh.
     *
     * @return estimated pinned verified weight in bytes
     */
    public long pinnedVerifiedWeightBytes() {
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            return accounting.pinnedVerifiedWeightBytes(
                    entriesByBlueId);
        }
    }

    /**
     * Invalidates transient children and reloadable acceleration data while
     * preserving caller-pinned verified content in the root cache.
     */
    public void clearReloadable() {
        ResolvedReferenceCacheLifecycle.clearReloadable(this);
    }

    /** Clears entries retained directly by this cache; inherited entries remain readable by a transient child. */
    public void clear() {
        ResolvedReferenceCacheLifecycle.clear(this);
    }

    /**
     * Returns the number of resolved structural representations retained directly by this cache.
     *
     * @return the local structural-entry count
     */
    public int resolvedGraphSize() {
        ensureCurrentGeneration();
        return resolvedGraphNodesByStructure.size();
    }

    /**
     * Reports whether this handle still belongs to the active cache generation.
     *
     * @return {@code false} when this cache is closed or its parent generation was invalidated
     */
    public boolean isCurrentGeneration() {
        return ResolvedReferenceCacheLifecycle
                .isCurrentGeneration(this);
    }

    private void ensureCurrentGeneration() {
        ResolvedReferenceCacheLifecycle
                .ensureCurrentGeneration(this);
    }

    /**
     * Closes this cache handle. Closing a transient child releases that child
     * scope and every descendant scope; closing the root permanently invalidates
     * the shared generation and eagerly releases every live child.
     */
    @Override
    public void close() {
        ResolvedReferenceCacheLifecycle.close(this);
    }

    private VerifiedReferenceEntry findEntry(String blueId) {
        ensureCurrentGeneration();
        VerifiedReferenceEntry local = entriesByBlueId.get(blueId);
        return local != null ? local : inheritedEntry(blueId);
    }

    private VerifiedReferenceEntry inheritedEntry(String blueId) {
        return readThroughParent != null ? readThroughParent.findEntry(blueId) : null;
    }

    private FrozenNode findResolvedGraph(FrozenNode.ResolvedStructuralKey structuralKey) {
        ensureCurrentGeneration();
        FrozenNode local = resolvedGraphNodesByStructure.get(structuralKey);
        return local != null ? local : inheritedResolvedGraph(structuralKey);
    }

    private FrozenNode inheritedResolvedGraph(FrozenNode.ResolvedStructuralKey structuralKey) {
        return readThroughParent != null ? readThroughParent.findResolvedGraph(structuralKey) : null;
    }

    private void requireCanonical(String blueId, FrozenNode canonicalContent) {
        Objects.requireNonNull(canonicalContent, "canonicalContent");
        if (!canonicalContent.isStrictCanonical()) {
            throw new IllegalArgumentException("Verified canonical content must be strict canonical.");
        }
        if (!canonicalContent.isStrictBlueIdValidation()) {
            throw new IllegalArgumentException("Verified canonical content must pass strict BlueId validation.");
        }
        if (canonicalContent.isReferenceOnly()) {
            throw new IllegalArgumentException("A pure reference is not verified materialized content: " + blueId);
        }
        if (!blueId.equals(canonicalContent.blueId())) {
            throw new IllegalArgumentException("Verified canonical content hashes to "
                    + canonicalContent.blueId() + ", not cache key " + blueId + ".");
        }
    }

    private void requireResolved(String blueId, FrozenNode resolvedContent) {
        Objects.requireNonNull(resolvedContent, "fullyResolvedContent");
        if (resolvedContent.isReferenceOnly()) {
            throw new IllegalArgumentException("Verified resolved content must be materialized for blueId: " + blueId);
        }
    }

    /** Immutable snapshot of verified-evidence and structural-interner metrics. */
    public static final class CacheStats
            extends ResolvedReferenceCacheStatistics {

        CacheStats(
                int verifiedEntries, int pinnedVerifiedEntries,
                long verifiedCurrentWeightBytes, long verifiedHighWaterWeightBytes,
                long verifiedEvictions, long verifiedOversizedRejections,
                int transientTrustedEntries, long transientTrustedCurrentWeightBytes,
                long transientTrustedHighWaterWeightBytes, long transientTrustedEvictions,
                long transientTrustedOversizedRejections, int structuralEntries,
                long structuralCurrentWeightBytes, long structuralHighWaterWeightBytes,
                long structuralEvictions,
                long structuralOversizedRejections) {
            super(
                    verifiedEntries, pinnedVerifiedEntries,
                    verifiedCurrentWeightBytes, verifiedHighWaterWeightBytes,
                    verifiedEvictions, verifiedOversizedRejections,
                    transientTrustedEntries, transientTrustedCurrentWeightBytes,
                    transientTrustedHighWaterWeightBytes, transientTrustedEvictions,
                    transientTrustedOversizedRejections, structuralEntries,
                    structuralCurrentWeightBytes, structuralHighWaterWeightBytes,
                    structuralEvictions,
                    structuralOversizedRejections);
        }
    }

}
