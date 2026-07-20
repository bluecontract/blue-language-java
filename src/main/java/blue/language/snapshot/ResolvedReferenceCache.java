package blue.language.snapshot;

import blue.language.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.merge.Merger.VerifiedReferenceResolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Cache of content whose canonical identity has been verified against its BlueId.
 *
 * <p>Resolved graph nodes must never be inserted merely because they carry a
 * {@code blueId}: inherited schema and other contextual contributions can make
 * such a node differ from the standalone content addressed by that identity.</p>
 */
public final class ResolvedReferenceCache implements AutoCloseable {

    private final ResolvedReferenceCache readThroughParent;
    private final CacheGeneration cacheGeneration;
    private final BlueCachePolicy cachePolicy;
    private final long openedGeneration;
    private volatile long observedGeneration;
    private volatile boolean locallyClosed;
    private final ConcurrentMap<String, VerifiedReferenceEntry> entriesByBlueId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, FrozenNode> transientTrustedCanonicalByBlueId =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<FrozenNode.ResolvedStructuralKey, FrozenNode> resolvedGraphNodesByStructure =
            new ConcurrentHashMap<>();
    private final FrozenNode.ResolvedStructuralInterner resolvedGraphInterner;
    private final FrozenNode.ResolvedStructuralInterner existingResolvedGraphInterner;
    private final Set<String> pinnedVerifiedBlueIds = new HashSet<>();
    private final LinkedHashSet<String> verifiedInsertionOrder = new LinkedHashSet<>();
    private final LinkedHashSet<String> trustedInsertionOrder = new LinkedHashSet<>();
    private final LinkedHashSet<FrozenNode.ResolvedStructuralKey> structuralInsertionOrder =
            new LinkedHashSet<>();
    private long verifiedCurrentWeight;
    private long verifiedHighWaterWeight;
    private long verifiedEvictions;
    private long verifiedOversizedRejections;
    private long trustedCurrentWeight;
    private long trustedHighWaterWeight;
    private long structuralCurrentWeight;
    private long structuralHighWaterWeight;
    private long structuralEvictions;
    private long structuralOversizedRejections;

    public ResolvedReferenceCache() {
        this(BlueCachePolicy.boundedDefaults());
    }

    public ResolvedReferenceCache(BlueCachePolicy cachePolicy) {
        this.readThroughParent = null;
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        this.cacheGeneration = new CacheGeneration();
        this.openedGeneration = -1L;
        this.observedGeneration = cacheGeneration.value.get();
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
                    recordStructuralInsertion(structuralKey, node);
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

    /**
     * Returns a cache that can reuse this cache's published entries but retains
     * all newly resolved references and graph nodes locally. Discarding the
     * child therefore discards every transient working-state cache insertion.
     */
    public ResolvedReferenceCache transientChild() {
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            return new ResolvedReferenceCache(this);
        }
    }

    /** Returns an independent transient cache with the same parent and local retained entries. */
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
                fork.transientTrustedCanonicalByBlueId.putAll(
                        transientTrustedCanonicalByBlueId);
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
     * state, and bounded storage for entries discovered later. Reloadable,
     * transient-trusted, and structural-interner entries are not copied.
     *
     * <p>The caller owns the returned cache and should close it when the
     * retained snapshot is no longer needed.</p>
     */
    public ResolvedReferenceCache isolatedCopyOfPinnedVerifiedEntries() {
        Map<String, VerifiedReferenceEntry> retainedPinned = new HashMap<>();
        BlueCachePolicy retainedPolicy;
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            ResolvedReferenceCache root = rootCache();
            root.ensureCurrentGeneration();
            retainedPolicy = root.cachePolicy;
            for (String blueId : root.pinnedVerifiedBlueIds) {
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
     * Returns non-certifying host-trusted content retained only by this
     * transient sequence. Such content is never read from or promoted to the
     * shared root cache.
     */
    public Optional<FrozenNode> getTransientTrustedCanonical(String blueId) {
        ensureCurrentGeneration();
        FrozenNode local = transientTrustedCanonicalByBlueId.get(blueId);
        if (local != null) {
            return Optional.of(local);
        }
        return readThroughParent != null && readThroughParent.readThroughParent != null
                ? readThroughParent.getTransientTrustedCanonical(blueId)
                : Optional.empty();
    }

    /** Retains non-certifying host-trusted content in a transient scope only. */
    public FrozenNode putTransientTrustedCanonical(String blueId, FrozenNode canonicalContent) {
        Objects.requireNonNull(blueId, "blueId");
        Objects.requireNonNull(canonicalContent, "canonicalContent");
        if (readThroughParent == null) {
            return canonicalContent;
        }
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            FrozenNode existing = transientTrustedCanonicalByBlueId.putIfAbsent(
                    blueId, canonicalContent);
            if (existing == null) {
                recordTrustedInsertion(blueId, canonicalContent);
            }
            return existing != null ? existing : canonicalContent;
        }
    }

    public Optional<FrozenNode> getVerifiedCanonical(String blueId) {
        ensureCurrentGeneration();
        VerifiedReferenceEntry entry = findEntry(blueId);
        return Optional.ofNullable(entry != null ? entry.canonicalContent : null);
    }

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

    public FrozenNode putVerifiedCanonical(String blueId, FrozenNode canonicalContent) {
        Objects.requireNonNull(blueId, "blueId");
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
                recordVerifiedInsertion(blueId, created);
                return canonicalContent;
            }
            return retained.canonicalContent;
        }
    }

    public FrozenNode getOrLoadVerifiedCanonical(String blueId,
                                                 Supplier<FrozenNode> canonicalLoader) {
        Objects.requireNonNull(blueId, "blueId");
        Objects.requireNonNull(canonicalLoader, "canonicalLoader");
        while (true) {
            long loadingGeneration;
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
                loadingGeneration = cacheGeneration.value.get();
            }

            CanonicalLoadKey loadKey = new CanonicalLoadKey(loadingGeneration, blueId);
            Deque<CanonicalLoadKey> loadingStack = cacheGeneration.loadingStack.get();
            if (isLoadingBlueId(loadingStack, blueId)) {
                throw new IllegalStateException("Recursive verified reference load: " + blueId);
            }
            CanonicalLoadFlight candidate = new CanonicalLoadFlight(Thread.currentThread());
            CanonicalLoadFlight existing = cacheGeneration.canonicalLoads.putIfAbsent(
                    loadKey, candidate);
            CanonicalLoadFlight flight = existing != null ? existing : candidate;
            boolean ownsLoad = existing == null;
            if (!ownsLoad && flight.owner == Thread.currentThread()) {
                throw new IllegalStateException("Recursive verified reference load: " + blueId);
            }

            try {
                if (ownsLoad) {
                    // Another flight may have published after this thread's
                    // initial cache check but before it installed a new flight.
                    // Recheck after winning ownership so that late contenders
                    // do not invoke the provider a second time.
                    synchronized (cacheGeneration.mutationLock) {
                        if (loadingGeneration != cacheGeneration.value.get()) {
                            continue;
                        }
                        ensureCurrentGeneration();
                        VerifiedReferenceEntry published = entriesByBlueId.get(blueId);
                        if (published == null) {
                            published = inheritedEntry(blueId);
                        }
                        if (published != null) {
                            return published.canonicalContent;
                        }
                    }
                }

                FrozenNode loaded;
                if (ownsLoad) {
                    loadingStack.addLast(loadKey);
                    try {
                        loaded = canonicalLoader.get();
                        requireCanonical(blueId, loaded);
                        flight.result.complete(loaded);
                    } catch (Throwable failure) {
                        flight.result.completeExceptionally(failure);
                        throw propagateLoadFailure(failure);
                    } finally {
                        CanonicalLoadKey removed = loadingStack.removeLast();
                        if (!loadKey.equals(removed)) {
                            throw new IllegalStateException(
                                    "Verified reference load stack became unbalanced");
                        }
                        if (loadingStack.isEmpty()) {
                            cacheGeneration.loadingStack.remove();
                        }
                    }
                } else {
                    loaded = awaitCanonicalLoad(flight);
                }

                synchronized (cacheGeneration.mutationLock) {
                    if (loadingGeneration != cacheGeneration.value.get()) {
                        continue;
                    }
                    ensureCurrentGeneration();
                    VerifiedReferenceEntry local = entriesByBlueId.get(blueId);
                    if (local != null) {
                        return local.canonicalContent;
                    }
                    VerifiedReferenceEntry inherited = inheritedEntry(blueId);
                    if (inherited != null) {
                        return inherited.canonicalContent;
                    }
                    VerifiedReferenceEntry retained = entriesByBlueId.putIfAbsent(
                            blueId, new VerifiedReferenceEntry(loaded, null));
                    if (retained != null) {
                        return retained.canonicalContent;
                    }
                    recordVerifiedInsertion(blueId, entriesByBlueId.get(blueId));
                    return loaded;
                }
            } finally {
                if (ownsLoad) {
                    cacheGeneration.canonicalLoads.remove(loadKey, flight);
                }
            }
        }
    }

    private static boolean isLoadingBlueId(Deque<CanonicalLoadKey> loadingStack,
                                           String blueId) {
        for (CanonicalLoadKey active : loadingStack) {
            if (active.blueId.equals(blueId)) {
                return true;
            }
        }
        return false;
    }

    private static FrozenNode awaitCanonicalLoad(CanonicalLoadFlight flight) {
        try {
            return flight.result.join();
        } catch (CompletionException failure) {
            throw propagateLoadFailure(failure.getCause() != null
                    ? failure.getCause()
                    : failure);
        }
    }

    private static RuntimeException propagateLoadFailure(Throwable failure) {
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return new IllegalStateException("Verified reference load failed", failure);
    }

    public FrozenNode putVerifiedResolved(VerifiedReferenceResolution verification) {
        Objects.requireNonNull(verification, "verification");
        return retainVerifiedResolved(verification.requestedBlueId(),
                verification.canonicalRoot(),
                verification.resolvedRoot());
    }

    /**
     * Retains caller-registered authoritative content until explicit clear.
     * Derived entries remain subject to this cache's configured weight bounds.
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
            pinnedVerifiedBlueIds.add(verification.requestedBlueId());
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
        Objects.requireNonNull(blueId, "blueId");
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
            recordVerifiedReplacement(blueId, local, retained);
            return retained.fullyResolvedContent;
        }
    }

    public FrozenNode freezeResolved(Node node) {
        ensureCurrentGeneration();
        return FrozenNode.fromResolvedNode(node, resolvedGraphInterner);
    }

    /**
     * Freezes a transient resolved graph while reusing already-published
     * subtrees, without retaining any new intermediate subtree in this cache.
     */
    public FrozenNode freezeResolvedWithoutRemembering(Node node) {
        ensureCurrentGeneration();
        return FrozenNode.fromResolvedNode(node, existingResolvedGraphInterner);
    }

    /**
     * Seeds structural sharing from a completed immutable graph without
     * promoting any node to verified provider content.
     */
    public void rememberResolvedGraph(FrozenNode node) {
        ensureCurrentGeneration();
        rememberResolvedGraph(node, new HashSet<>());
    }

    /**
     * Promotes only verified references that remain reachable from a completed
     * canonical graph. Entries discovered solely in discarded intermediate
     * states remain local to this transient child.
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
            Set<String> reachableReferences = new HashSet<>();
            collectReferenceBlueIds(canonicalRoot, new HashSet<>(), reachableReferences);
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
                Set<String> dependencies = new HashSet<>();
                collectReferenceBlueIds(retainedCanonical, new HashSet<>(), dependencies);
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
     */
    public void retainOnlyReachableFrom(FrozenNode canonicalRoot, FrozenNode resolvedRoot) {
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            if (readThroughParent == null) {
                return;
            }
            Set<String> reachableReferences = new HashSet<>();
            collectReferenceBlueIds(canonicalRoot, new HashSet<>(), reachableReferences);
            Deque<String> pending = new ArrayDeque<>(reachableReferences);
            while (!pending.isEmpty()) {
                String blueId = pending.removeFirst();
                VerifiedReferenceEntry local = entriesByBlueId.get(blueId);
                FrozenNode retainedCanonical = local != null
                        ? local.canonicalContent
                        : transientTrustedCanonicalByBlueId.get(blueId);
                if (retainedCanonical == null) {
                    continue;
                }
                Set<String> dependencies = new HashSet<>();
                collectReferenceBlueIds(retainedCanonical, new HashSet<>(), dependencies);
                for (String dependency : dependencies) {
                    if (reachableReferences.add(dependency)) {
                        pending.addLast(dependency);
                    }
                }
            }
            for (String blueId : new HashSet<>(entriesByBlueId.keySet())) {
                if (!reachableReferences.contains(blueId)) {
                    removeVerifiedEntry(blueId);
                }
            }
            for (String blueId : new HashSet<>(transientTrustedCanonicalByBlueId.keySet())) {
                if (!reachableReferences.contains(blueId)) {
                    removeTrustedEntry(blueId);
                }
            }

            Set<FrozenNode.ResolvedStructuralKey> reachableGraphNodes = new HashSet<>();
            collectResolvedGraphKeys(resolvedRoot, reachableGraphNodes);
            for (FrozenNode.ResolvedStructuralKey key
                    : new HashSet<>(resolvedGraphNodesByStructure.keySet())) {
                if (!reachableGraphNodes.contains(key)) {
                    removeStructuralEntry(key);
                }
            }
        }
    }

    private void collectResolvedGraphKeys(FrozenNode node,
                                          Set<FrozenNode.ResolvedStructuralKey> reachable) {
        if (node == null || !reachable.add(node.resolvedStructuralKey())) {
            return;
        }
        collectResolvedGraphKeys(node.getType(), reachable);
        collectResolvedGraphKeys(node.getItemType(), reachable);
        collectResolvedGraphKeys(node.getKeyType(), reachable);
        collectResolvedGraphKeys(node.getValueType(), reachable);
        collectResolvedGraphKeys(node.getBlue(), reachable);
        collectResolvedGraphKeys(node.getContracts(), reachable);
        if (node.getItems() != null) {
            node.getItems().forEach(item -> collectResolvedGraphKeys(item, reachable));
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(child -> collectResolvedGraphKeys(child, reachable));
        }
    }

    private void collectReferenceBlueIds(FrozenNode node,
                                         Set<FrozenNode.ResolvedStructuralKey> visited,
                                         Set<String> references) {
        if (node == null || !visited.add(node.resolvedStructuralKey())) {
            return;
        }
        if (node.getReferenceBlueId() != null) {
            references.add(node.getReferenceBlueId());
        }
        collectReferenceBlueIds(node.getType(), visited, references);
        collectReferenceBlueIds(node.getItemType(), visited, references);
        collectReferenceBlueIds(node.getKeyType(), visited, references);
        collectReferenceBlueIds(node.getValueType(), visited, references);
        collectReferenceBlueIds(node.getBlue(), visited, references);
        collectReferenceBlueIds(node.getContracts(), visited, references);
        if (node.getItems() != null) {
            node.getItems().forEach(item -> collectReferenceBlueIds(item, visited, references));
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(child ->
                    collectReferenceBlueIds(child, visited, references));
        }
    }

    private void rememberResolvedGraph(FrozenNode node,
                                       Set<FrozenNode.ResolvedStructuralKey> visited) {
        if (node == null) {
            return;
        }
        FrozenNode.ResolvedStructuralKey structuralKey = node.resolvedStructuralKey();
        if (!visited.add(structuralKey)) {
            return;
        }
        resolvedGraphInterner.intern(structuralKey, node);
        rememberResolvedGraph(node.getType(), visited);
        rememberResolvedGraph(node.getItemType(), visited);
        rememberResolvedGraph(node.getKeyType(), visited);
        rememberResolvedGraph(node.getValueType(), visited);
        rememberResolvedGraph(node.getBlue(), visited);
        rememberResolvedGraph(node.getContracts(), visited);
        if (node.getItems() != null) {
            node.getItems().forEach(item -> rememberResolvedGraph(item, visited));
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(child -> rememberResolvedGraph(child, visited));
        }
    }

    private void recordVerifiedInsertion(String blueId, VerifiedReferenceEntry entry) {
        recordVerifiedReplacement(blueId, null, entry);
    }

    private void recordVerifiedReplacement(String blueId,
                                           VerifiedReferenceEntry previous,
                                           VerifiedReferenceEntry replacement) {
        long replacementWeight = verifiedWeight(blueId, replacement);
        if (readThroughParent == null
                && !pinnedVerifiedBlueIds.contains(blueId)
                && (replacementWeight > cachePolicy.maximumDerivedEntryWeightBytes()
                || replacementWeight > cachePolicy.transientReferenceMaxWeightBytes())) {
            verifiedOversizedRejections++;
            if (previous == null) {
                entriesByBlueId.remove(blueId, replacement);
            } else {
                entriesByBlueId.put(blueId, previous);
            }
            return;
        }
        if (previous != null) {
            verifiedCurrentWeight = subtractFloorZero(
                    verifiedCurrentWeight, verifiedWeight(blueId, previous));
        }
        verifiedInsertionOrder.remove(blueId);
        verifiedInsertionOrder.add(blueId);
        verifiedCurrentWeight = saturatedAdd(verifiedCurrentWeight, replacementWeight);
        verifiedHighWaterWeight = Math.max(verifiedHighWaterWeight, verifiedCurrentWeight);
        evictVerifiedToBounds();
    }

    private void evictVerifiedToBounds() {
        if (readThroughParent != null) {
            return;
        }
        while (entriesByBlueId.size() > cachePolicy.transientReferenceMaxEntries()
                || verifiedCurrentWeight > cachePolicy.transientReferenceMaxWeightBytes()) {
            String victim = null;
            for (String candidate : verifiedInsertionOrder) {
                if (!pinnedVerifiedBlueIds.contains(candidate)) {
                    victim = candidate;
                    break;
                }
            }
            if (victim == null) {
                return;
            }
            removeVerifiedEntry(victim);
            verifiedEvictions++;
        }
    }

    private void recordTrustedInsertion(String blueId, FrozenNode node) {
        trustedInsertionOrder.remove(blueId);
        trustedInsertionOrder.add(blueId);
        trustedCurrentWeight = saturatedAdd(
                trustedCurrentWeight,
                trustedWeight(blueId, node));
        trustedHighWaterWeight = Math.max(trustedHighWaterWeight, trustedCurrentWeight);
    }

    private void recordStructuralInsertion(FrozenNode.ResolvedStructuralKey key,
                                           FrozenNode node) {
        long weight = structuralWeight(node);
        if (readThroughParent == null
                && (weight > cachePolicy.maximumDerivedEntryWeightBytes()
                || weight > cachePolicy.resolvedStructuralMaxWeightBytes())) {
            resolvedGraphNodesByStructure.remove(key, node);
            structuralOversizedRejections++;
            return;
        }
        structuralInsertionOrder.remove(key);
        structuralInsertionOrder.add(key);
        structuralCurrentWeight = saturatedAdd(structuralCurrentWeight, weight);
        structuralHighWaterWeight = Math.max(
                structuralHighWaterWeight, structuralCurrentWeight);
        evictStructuralToBounds();
    }

    private void evictStructuralToBounds() {
        if (readThroughParent != null) {
            return;
        }
        while (resolvedGraphNodesByStructure.size() > cachePolicy.resolvedStructuralMaxEntries()
                || structuralCurrentWeight > cachePolicy.resolvedStructuralMaxWeightBytes()) {
            if (structuralInsertionOrder.isEmpty()) {
                return;
            }
            FrozenNode.ResolvedStructuralKey victim = structuralInsertionOrder.iterator().next();
            removeStructuralEntry(victim);
            structuralEvictions++;
        }
    }

    private void removeVerifiedEntry(String blueId) {
        VerifiedReferenceEntry removed = entriesByBlueId.remove(blueId);
        verifiedInsertionOrder.remove(blueId);
        if (removed != null) {
            verifiedCurrentWeight = subtractFloorZero(
                    verifiedCurrentWeight, verifiedWeight(blueId, removed));
        }
    }

    private void removeTrustedEntry(String blueId) {
        FrozenNode removed = transientTrustedCanonicalByBlueId.remove(blueId);
        trustedInsertionOrder.remove(blueId);
        if (removed != null) {
            trustedCurrentWeight = subtractFloorZero(
                    trustedCurrentWeight, trustedWeight(blueId, removed));
        }
    }

    private void removeStructuralEntry(FrozenNode.ResolvedStructuralKey key) {
        FrozenNode removed = resolvedGraphNodesByStructure.remove(key);
        structuralInsertionOrder.remove(key);
        if (removed != null) {
            structuralCurrentWeight = subtractFloorZero(
                    structuralCurrentWeight, structuralWeight(removed));
        }
    }

    private void rebuildLocalWeightAccounting() {
        rebuildLocalWeightAccounting(Collections.<String>emptySet());
    }

    private void rebuildLocalWeightAccounting(Set<String> retainedPinnedBlueIds) {
        clearLocalWeightAccounting();
        pinnedVerifiedBlueIds.addAll(retainedPinnedBlueIds);
        for (java.util.Map.Entry<String, VerifiedReferenceEntry> entry : entriesByBlueId.entrySet()) {
            verifiedInsertionOrder.add(entry.getKey());
            verifiedCurrentWeight = saturatedAdd(verifiedCurrentWeight,
                    verifiedWeight(entry.getKey(), entry.getValue()));
        }
        for (java.util.Map.Entry<String, FrozenNode> entry
                : transientTrustedCanonicalByBlueId.entrySet()) {
            trustedInsertionOrder.add(entry.getKey());
            trustedCurrentWeight = saturatedAdd(trustedCurrentWeight,
                    trustedWeight(entry.getKey(), entry.getValue()));
        }
        for (java.util.Map.Entry<FrozenNode.ResolvedStructuralKey, FrozenNode> entry
                : resolvedGraphNodesByStructure.entrySet()) {
            structuralInsertionOrder.add(entry.getKey());
            structuralCurrentWeight = saturatedAdd(structuralCurrentWeight,
                    structuralWeight(entry.getValue()));
        }
        verifiedHighWaterWeight = Math.max(verifiedHighWaterWeight, verifiedCurrentWeight);
        trustedHighWaterWeight = Math.max(trustedHighWaterWeight, trustedCurrentWeight);
        structuralHighWaterWeight = Math.max(structuralHighWaterWeight, structuralCurrentWeight);
    }

    private void clearLocalWeightAccounting() {
        pinnedVerifiedBlueIds.clear();
        verifiedInsertionOrder.clear();
        trustedInsertionOrder.clear();
        structuralInsertionOrder.clear();
        verifiedCurrentWeight = 0L;
        trustedCurrentWeight = 0L;
        structuralCurrentWeight = 0L;
    }

    private long verifiedWeight(String blueId, VerifiedReferenceEntry entry) {
        return saturatedAdd(128L + 2L * blueId.length(),
                FrozenNode.approximateRetainedWeightBytesOf(
                        entry.canonicalContent, entry.fullyResolvedContent));
    }

    private long trustedWeight(String blueId, FrozenNode node) {
        return saturatedAdd(96L + 2L * blueId.length(),
                node.approximateRetainedWeightBytes());
    }

    private long structuralWeight(FrozenNode node) {
        return saturatedAdd(64L, node.approximateShallowRetainedWeightBytes());
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static int saturatedAdd(int left, int right) {
        return Integer.MAX_VALUE - left < right ? Integer.MAX_VALUE : left + right;
    }

    private static long subtractFloorZero(long left, long right) {
        return right >= left ? 0L : left - right;
    }

    /** Immutable approximate cache accounting for integration and lifecycle reports. */
    public CacheStats cacheStats() {
        synchronized (cacheGeneration.mutationLock) {
            if (readThroughParent != null) {
                return localCacheStats();
            }
            int verifiedEntries = 0;
            int pinnedVerifiedEntries = 0;
            long verifiedCurrentWeightBytes = 0L;
            long verifiedHighWaterWeightBytes = 0L;
            long verifiedEvictions = 0L;
            long verifiedOversizedRejections = 0L;
            int transientTrustedEntries = 0;
            long transientTrustedCurrentWeightBytes = 0L;
            long transientTrustedHighWaterWeightBytes = 0L;
            int structuralEntries = 0;
            long structuralCurrentWeightBytes = 0L;
            long structuralHighWaterWeightBytes = 0L;
            long structuralEvictions = 0L;
            long structuralOversizedRejections = 0L;
            for (ResolvedReferenceCache cache : cacheGeneration.liveCaches()) {
                CacheStats local = cache.localCacheStats();
                verifiedEntries = saturatedAdd(verifiedEntries, local.verifiedEntries());
                pinnedVerifiedEntries = saturatedAdd(
                        pinnedVerifiedEntries, local.pinnedVerifiedEntries());
                verifiedCurrentWeightBytes = saturatedAdd(
                        verifiedCurrentWeightBytes, local.verifiedCurrentWeightBytes());
                verifiedHighWaterWeightBytes = saturatedAdd(
                        verifiedHighWaterWeightBytes, local.verifiedHighWaterWeightBytes());
                verifiedEvictions = saturatedAdd(verifiedEvictions, local.verifiedEvictions());
                verifiedOversizedRejections = saturatedAdd(
                        verifiedOversizedRejections, local.verifiedOversizedRejections());
                transientTrustedEntries = saturatedAdd(
                        transientTrustedEntries, local.transientTrustedEntries());
                transientTrustedCurrentWeightBytes = saturatedAdd(
                        transientTrustedCurrentWeightBytes,
                        local.transientTrustedCurrentWeightBytes());
                transientTrustedHighWaterWeightBytes = saturatedAdd(
                        transientTrustedHighWaterWeightBytes,
                        local.transientTrustedHighWaterWeightBytes());
                structuralEntries = saturatedAdd(structuralEntries, local.structuralEntries());
                structuralCurrentWeightBytes = saturatedAdd(
                        structuralCurrentWeightBytes, local.structuralCurrentWeightBytes());
                structuralHighWaterWeightBytes = saturatedAdd(
                        structuralHighWaterWeightBytes, local.structuralHighWaterWeightBytes());
                structuralEvictions = saturatedAdd(
                        structuralEvictions, local.structuralEvictions());
                structuralOversizedRejections = saturatedAdd(
                        structuralOversizedRejections, local.structuralOversizedRejections());
            }
            cacheGeneration.verifiedHighWaterWeight = Math.max(
                    cacheGeneration.verifiedHighWaterWeight, verifiedHighWaterWeightBytes);
            cacheGeneration.trustedHighWaterWeight = Math.max(
                    cacheGeneration.trustedHighWaterWeight, transientTrustedHighWaterWeightBytes);
            cacheGeneration.structuralHighWaterWeight = Math.max(
                    cacheGeneration.structuralHighWaterWeight, structuralHighWaterWeightBytes);
            return new CacheStats(
                    verifiedEntries,
                    pinnedVerifiedEntries,
                    verifiedCurrentWeightBytes,
                    cacheGeneration.verifiedHighWaterWeight,
                    verifiedEvictions,
                    verifiedOversizedRejections,
                    transientTrustedEntries,
                    transientTrustedCurrentWeightBytes,
                    cacheGeneration.trustedHighWaterWeight,
                    structuralEntries,
                    structuralCurrentWeightBytes,
                    cacheGeneration.structuralHighWaterWeight,
                    structuralEvictions,
                    structuralOversizedRejections);
        }
    }

    private CacheStats localCacheStats() {
        return new CacheStats(
                entriesByBlueId.size(),
                pinnedVerifiedBlueIds.size(),
                verifiedCurrentWeight,
                verifiedHighWaterWeight,
                verifiedEvictions,
                verifiedOversizedRejections,
                transientTrustedCanonicalByBlueId.size(),
                trustedCurrentWeight,
                trustedHighWaterWeight,
                resolvedGraphNodesByStructure.size(),
                structuralCurrentWeight,
                structuralHighWaterWeight,
                structuralEvictions,
                structuralOversizedRejections);
    }

    public int size() {
        ensureCurrentGeneration();
        return entriesByBlueId.size();
    }

    /** Approximate weight of caller-pinned verified entries retained across configuration refresh. */
    public long pinnedVerifiedWeightBytes() {
        synchronized (cacheGeneration.mutationLock) {
            ensureCurrentGeneration();
            long weight = 0L;
            for (String blueId : pinnedVerifiedBlueIds) {
                VerifiedReferenceEntry entry = entriesByBlueId.get(blueId);
                if (entry != null) {
                    weight = saturatedAdd(weight, verifiedWeight(blueId, entry));
                }
            }
            return weight;
        }
    }

    /**
     * Invalidates transient children and reloadable acceleration data while
     * preserving caller-pinned verified content in the root cache.
     */
    public void clearReloadable() {
        synchronized (cacheGeneration.mutationLock) {
            if (locallyClosed || cacheGeneration.closed) {
                throw new IllegalStateException("Resolved reference cache is closed");
            }
            if (readThroughParent != null) {
                throw new IllegalStateException(
                        "Reloadable state can only be cleared from the root reference cache");
            }
            retainLiveHighWaterMarks();
            Map<String, VerifiedReferenceEntry> retainedPinned = new HashMap<>();
            for (String blueId : pinnedVerifiedBlueIds) {
                VerifiedReferenceEntry entry = entriesByBlueId.get(blueId);
                if (entry != null) {
                    retainedPinned.put(blueId, entry);
                }
            }
            Set<String> retainedPinnedIds = new HashSet<>(retainedPinned.keySet());
            observedGeneration = cacheGeneration.value.incrementAndGet();
            for (ResolvedReferenceCache cache : cacheGeneration.liveCaches()) {
                cache.clearLocalState();
                cache.observedGeneration = observedGeneration;
            }
            entriesByBlueId.putAll(retainedPinned);
            rebuildLocalWeightAccounting(retainedPinnedIds);
        }
    }

    /** Clears entries retained directly by this cache; inherited entries remain readable by a transient child. */
    public void clear() {
        synchronized (cacheGeneration.mutationLock) {
            if (locallyClosed || cacheGeneration.closed) {
                throw new IllegalStateException("Resolved reference cache is closed");
            }
            retainLiveHighWaterMarks();
            if (readThroughParent == null) {
                observedGeneration = cacheGeneration.value.incrementAndGet();
                for (ResolvedReferenceCache cache : cacheGeneration.liveCaches()) {
                    cache.clearLocalState();
                    cache.observedGeneration = observedGeneration;
                }
            } else {
                observedGeneration = cacheGeneration.value.get();
                clearLocalState();
            }
        }
    }

    public int resolvedGraphSize() {
        ensureCurrentGeneration();
        return resolvedGraphNodesByStructure.size();
    }

    /** Returns false when the parent cache has been invalidated since this child was opened. */
    public boolean isCurrentGeneration() {
        return !locallyClosed && !hasClosedAncestor() && !cacheGeneration.closed
                && (readThroughParent == null
                || openedGeneration == cacheGeneration.value.get());
    }

    private void ensureCurrentGeneration() {
        if (locallyClosed || hasClosedAncestor() || cacheGeneration.closed) {
            throw new IllegalStateException("Resolved reference cache is closed");
        }
        long current = cacheGeneration.value.get();
        if (observedGeneration == current) {
            return;
        }
        synchronized (cacheGeneration.mutationLock) {
            current = cacheGeneration.value.get();
            if (observedGeneration == current) {
                return;
            }
            entriesByBlueId.clear();
            transientTrustedCanonicalByBlueId.clear();
            resolvedGraphNodesByStructure.clear();
            clearLocalWeightAccounting();
            observedGeneration = current;
        }
    }

    /**
     * Closes this cache handle. Closing a transient child releases that child
     * scope and every descendant scope; closing the root permanently invalidates
     * the shared generation and eagerly releases every live child.
     */
    @Override
    public void close() {
        synchronized (cacheGeneration.mutationLock) {
            if (locallyClosed) {
                return;
            }
            if (readThroughParent != null) {
                retainLiveHighWaterMarks();
                List<ResolvedReferenceCache> closedScopes = new ArrayList<>();
                for (ResolvedReferenceCache cache : cacheGeneration.liveCaches()) {
                    if (cache == this || cache.isDescendantOf(this)) {
                        cache.locallyClosed = true;
                        cache.clearLocalState();
                        closedScopes.add(cache);
                    }
                }
                for (ResolvedReferenceCache cache : closedScopes) {
                    cacheGeneration.unregister(cache);
                }
                return;
            }
            if (cacheGeneration.closed) {
                locallyClosed = true;
                clearLocalState();
                return;
            }
            retainLiveHighWaterMarks();
            cacheGeneration.closed = true;
            cacheGeneration.value.incrementAndGet();
            for (ResolvedReferenceCache cache : cacheGeneration.liveCaches()) {
                cache.locallyClosed = true;
                cache.clearLocalState();
            }
            cacheGeneration.caches.clear();
        }
    }

    private void clearLocalState() {
        entriesByBlueId.clear();
        transientTrustedCanonicalByBlueId.clear();
        resolvedGraphNodesByStructure.clear();
        clearLocalWeightAccounting();
    }

    /** Preserves aggregate lifetime peaks before a live scope is cleared or unregistered. */
    private void retainLiveHighWaterMarks() {
        long verified = 0L;
        long trusted = 0L;
        long structural = 0L;
        for (ResolvedReferenceCache cache : cacheGeneration.liveCaches()) {
            verified = saturatedAdd(verified, cache.verifiedHighWaterWeight);
            trusted = saturatedAdd(trusted, cache.trustedHighWaterWeight);
            structural = saturatedAdd(structural, cache.structuralHighWaterWeight);
        }
        cacheGeneration.verifiedHighWaterWeight = Math.max(
                cacheGeneration.verifiedHighWaterWeight, verified);
        cacheGeneration.trustedHighWaterWeight = Math.max(
                cacheGeneration.trustedHighWaterWeight, trusted);
        cacheGeneration.structuralHighWaterWeight = Math.max(
                cacheGeneration.structuralHighWaterWeight, structural);
    }

    private boolean isDescendantOf(ResolvedReferenceCache ancestor) {
        ResolvedReferenceCache current = readThroughParent;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.readThroughParent;
        }
        return false;
    }

    private boolean hasClosedAncestor() {
        ResolvedReferenceCache current = readThroughParent;
        while (current != null) {
            if (current.locallyClosed) {
                return true;
            }
            current = current.readThroughParent;
        }
        return false;
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

    public static final class CacheStats {
        private final int verifiedEntries;
        private final int pinnedVerifiedEntries;
        private final long verifiedCurrentWeightBytes;
        private final long verifiedHighWaterWeightBytes;
        private final long verifiedEvictions;
        private final long verifiedOversizedRejections;
        private final int transientTrustedEntries;
        private final long transientTrustedCurrentWeightBytes;
        private final long transientTrustedHighWaterWeightBytes;
        private final int structuralEntries;
        private final long structuralCurrentWeightBytes;
        private final long structuralHighWaterWeightBytes;
        private final long structuralEvictions;
        private final long structuralOversizedRejections;

        private CacheStats(int verifiedEntries,
                           int pinnedVerifiedEntries,
                           long verifiedCurrentWeightBytes,
                           long verifiedHighWaterWeightBytes,
                           long verifiedEvictions,
                           long verifiedOversizedRejections,
                           int transientTrustedEntries,
                           long transientTrustedCurrentWeightBytes,
                           long transientTrustedHighWaterWeightBytes,
                           int structuralEntries,
                           long structuralCurrentWeightBytes,
                           long structuralHighWaterWeightBytes,
                           long structuralEvictions,
                           long structuralOversizedRejections) {
            this.verifiedEntries = verifiedEntries;
            this.pinnedVerifiedEntries = pinnedVerifiedEntries;
            this.verifiedCurrentWeightBytes = verifiedCurrentWeightBytes;
            this.verifiedHighWaterWeightBytes = verifiedHighWaterWeightBytes;
            this.verifiedEvictions = verifiedEvictions;
            this.verifiedOversizedRejections = verifiedOversizedRejections;
            this.transientTrustedEntries = transientTrustedEntries;
            this.transientTrustedCurrentWeightBytes = transientTrustedCurrentWeightBytes;
            this.transientTrustedHighWaterWeightBytes = transientTrustedHighWaterWeightBytes;
            this.structuralEntries = structuralEntries;
            this.structuralCurrentWeightBytes = structuralCurrentWeightBytes;
            this.structuralHighWaterWeightBytes = structuralHighWaterWeightBytes;
            this.structuralEvictions = structuralEvictions;
            this.structuralOversizedRejections = structuralOversizedRejections;
        }

        public int verifiedEntries() { return verifiedEntries; }

        public int pinnedVerifiedEntries() { return pinnedVerifiedEntries; }

        public long verifiedCurrentWeightBytes() { return verifiedCurrentWeightBytes; }

        public long verifiedHighWaterWeightBytes() { return verifiedHighWaterWeightBytes; }

        public long verifiedEvictions() { return verifiedEvictions; }

        public long verifiedOversizedRejections() { return verifiedOversizedRejections; }

        public int transientTrustedEntries() { return transientTrustedEntries; }

        public long transientTrustedCurrentWeightBytes() { return transientTrustedCurrentWeightBytes; }

        public long transientTrustedHighWaterWeightBytes() { return transientTrustedHighWaterWeightBytes; }

        public int structuralEntries() { return structuralEntries; }

        public long structuralCurrentWeightBytes() { return structuralCurrentWeightBytes; }

        public long structuralHighWaterWeightBytes() { return structuralHighWaterWeightBytes; }

        public long structuralEvictions() { return structuralEvictions; }

        public long structuralOversizedRejections() { return structuralOversizedRejections; }
    }

    private static final class VerifiedReferenceEntry {
        private final FrozenNode canonicalContent;
        private final FrozenNode fullyResolvedContent;

        private VerifiedReferenceEntry(FrozenNode canonicalContent, FrozenNode fullyResolvedContent) {
            if (canonicalContent == null) {
                throw new IllegalArgumentException("canonicalContent must not be null");
            }
            this.canonicalContent = canonicalContent;
            this.fullyResolvedContent = fullyResolvedContent;
        }
    }

    private static final class CanonicalLoadKey {
        private final long generation;
        private final String blueId;

        private CanonicalLoadKey(long generation, String blueId) {
            this.generation = generation;
            this.blueId = blueId;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof CanonicalLoadKey)) {
                return false;
            }
            CanonicalLoadKey other = (CanonicalLoadKey) object;
            return generation == other.generation && blueId.equals(other.blueId);
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(generation) + blueId.hashCode();
        }
    }

    private static final class CanonicalLoadFlight {
        private final Thread owner;
        private final CompletableFuture<FrozenNode> result = new CompletableFuture<>();

        private CanonicalLoadFlight(Thread owner) {
            this.owner = owner;
        }
    }

    private static final class CacheGeneration {
        private final AtomicLong value = new AtomicLong();
        private final Object mutationLock = new Object();
        private volatile boolean closed;
        private final ConcurrentMap<CanonicalLoadKey, CanonicalLoadFlight> canonicalLoads =
                new ConcurrentHashMap<>();
        private final ThreadLocal<Deque<CanonicalLoadKey>> loadingStack =
                ThreadLocal.withInitial(ArrayDeque::new);
        private final Set<ResolvedReferenceCache> caches = Collections.newSetFromMap(
                new WeakHashMap<ResolvedReferenceCache, Boolean>());
        private long verifiedHighWaterWeight;
        private long trustedHighWaterWeight;
        private long structuralHighWaterWeight;

        private void register(ResolvedReferenceCache cache) {
            synchronized (mutationLock) {
                if (closed || cache.hasClosedAncestor()) {
                    throw new IllegalStateException("Resolved reference cache is closed");
                }
                caches.add(cache);
            }
        }

        private List<ResolvedReferenceCache> liveCaches() {
            return new ArrayList<>(caches);
        }

        private void unregister(ResolvedReferenceCache target) {
            caches.remove(target);
        }
    }
}
