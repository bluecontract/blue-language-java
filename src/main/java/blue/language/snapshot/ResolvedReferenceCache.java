package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.merge.Merger.VerifiedReferenceResolution;

import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
public final class ResolvedReferenceCache {

    private final ResolvedReferenceCache readThroughParent;
    private final CacheGeneration cacheGeneration;
    private final long openedGeneration;
    private volatile long observedGeneration;
    private final ConcurrentMap<String, VerifiedReferenceEntry> entriesByBlueId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, FrozenNode> transientTrustedCanonicalByBlueId =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<FrozenNode.ResolvedStructuralKey, FrozenNode> resolvedGraphNodesByStructure =
            new ConcurrentHashMap<>();
    private final FrozenNode.ResolvedStructuralInterner resolvedGraphInterner;
    private final FrozenNode.ResolvedStructuralInterner existingResolvedGraphInterner;

    public ResolvedReferenceCache() {
        this.readThroughParent = null;
        this.cacheGeneration = new CacheGeneration();
        this.openedGeneration = -1L;
        this.observedGeneration = cacheGeneration.value.get();
        this.resolvedGraphInterner = newResolvedGraphInterner();
        this.existingResolvedGraphInterner = newExistingResolvedGraphInterner();
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
        this.cacheGeneration = readThroughParent.cacheGeneration;
        this.openedGeneration = openedGeneration;
        this.observedGeneration = cacheGeneration.value.get();
        this.resolvedGraphInterner = newResolvedGraphInterner();
        this.existingResolvedGraphInterner = newExistingResolvedGraphInterner();
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
                    return existing != null ? existing : node;
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
        return new ResolvedReferenceCache(this);
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
            }
            return fork;
        }
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
            VerifiedReferenceEntry retained = entriesByBlueId.compute(blueId, (ignored, existing) -> {
                if (existing != null) {
                    return existing;
                }
                return new VerifiedReferenceEntry(canonicalContent, null);
            });
            return retained.canonicalContent;
        }
    }

    public FrozenNode getOrLoadVerifiedCanonical(String blueId,
                                                 Supplier<FrozenNode> canonicalLoader) {
        Objects.requireNonNull(blueId, "blueId");
        Objects.requireNonNull(canonicalLoader, "canonicalLoader");
        synchronized (cacheGeneration.loadingLock(blueId)) {
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

                FrozenNode loaded = canonicalLoader.get();
                requireCanonical(blueId, loaded);

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
                    return retained != null ? retained.canonicalContent : loaded;
                }
            }
        }
    }

    public FrozenNode putVerifiedResolved(VerifiedReferenceResolution verification) {
        Objects.requireNonNull(verification, "verification");
        return retainVerifiedResolved(verification.requestedBlueId(),
                verification.canonicalRoot(),
                verification.resolvedRoot());
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
            VerifiedReferenceEntry retained = entriesByBlueId.compute(blueId, (ignored, existing) -> {
                FrozenNode retainedCanonical = existing != null
                        ? existing.canonicalContent
                        : inherited != null ? inherited.canonicalContent : canonicalContent;
                FrozenNode retainedResolved = existing != null && existing.fullyResolvedContent != null
                        ? existing.fullyResolvedContent
                        : fullyResolvedContent;
                return new VerifiedReferenceEntry(retainedCanonical, retainedResolved);
            });
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
                if (local == null) {
                    continue;
                }
                FrozenNode retainedCanonical = readThroughParent.putVerifiedCanonical(
                        blueId, local.canonicalContent);
                Set<String> dependencies = new HashSet<>();
                collectReferenceBlueIds(retainedCanonical, new HashSet<>(), dependencies);
                for (String dependency : dependencies) {
                    if (!visitedReferences.contains(dependency)) {
                        pending.addLast(dependency);
                    }
                }
                if (local.fullyResolvedContent != null
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
            entriesByBlueId.keySet().removeIf(blueId -> !reachableReferences.contains(blueId));
            transientTrustedCanonicalByBlueId.keySet().removeIf(
                    blueId -> !reachableReferences.contains(blueId));

            Set<FrozenNode.ResolvedStructuralKey> reachableGraphNodes = new HashSet<>();
            collectResolvedGraphKeys(resolvedRoot, reachableGraphNodes);
            resolvedGraphNodesByStructure.keySet().removeIf(key -> !reachableGraphNodes.contains(key));
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

    public int size() {
        ensureCurrentGeneration();
        return entriesByBlueId.size();
    }

    /** Clears entries retained directly by this cache; inherited entries remain readable by a transient child. */
    public void clear() {
        synchronized (cacheGeneration.mutationLock) {
            if (readThroughParent == null) {
                observedGeneration = cacheGeneration.value.incrementAndGet();
            } else {
                observedGeneration = cacheGeneration.value.get();
            }
            entriesByBlueId.clear();
            transientTrustedCanonicalByBlueId.clear();
            resolvedGraphNodesByStructure.clear();
        }
    }

    public int resolvedGraphSize() {
        ensureCurrentGeneration();
        return resolvedGraphNodesByStructure.size();
    }

    /** Returns false when the parent cache has been invalidated since this child was opened. */
    public boolean isCurrentGeneration() {
        return readThroughParent == null
                || openedGeneration == cacheGeneration.value.get();
    }

    private void ensureCurrentGeneration() {
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
            observedGeneration = current;
        }
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

    private static final class CacheGeneration {
        private static final int LOADING_STRIPES = 64;
        private final AtomicLong value = new AtomicLong();
        private final Object mutationLock = new Object();
        private final Object[] loadingLocks = new Object[LOADING_STRIPES];

        private CacheGeneration() {
            for (int index = 0; index < loadingLocks.length; index++) {
                loadingLocks[index] = new Object();
            }
        }

        private Object loadingLock(String blueId) {
            return loadingLocks[(blueId.hashCode() & Integer.MAX_VALUE) % loadingLocks.length];
        }
    }
}
