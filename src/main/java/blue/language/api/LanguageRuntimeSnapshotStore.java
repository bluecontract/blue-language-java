package blue.language.api;

import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.snapshot.ResolvedSnapshot;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Runtime-owned snapshot retention used by the focused Language composition.
 *
 * <p>Authoritative caller-published snapshots are pinned until an explicit
 * clear or close. Derived entries use the same count/weight bounds as the
 * legacy aggregate runtime and never become semantic state.</p>
 */
final class LanguageRuntimeSnapshotStore {

    private static final String PINNED_SNAPSHOT_CACHE =
            "pinnedAuthoritativeSnapshots";
    private static final String DERIVED_SNAPSHOT_CACHE =
            "derivedResolvedSnapshots";
    private static final String CANONICAL_ALIAS_CACHE =
            "canonicalAliases";
    private static final String VERIFIED_REFERENCE_CACHE =
            "verifiedReferences";
    private static final String TRANSIENT_REFERENCE_CACHE =
            "transientTrustedReferences";
    private static final String STRUCTURAL_INTERNER_CACHE =
            "resolvedStructuralInterner";

    private final Object mutationLock = new Object();
    private final ConcurrentMap<FrozenNode.ResolvedStructuralKey,
            ResolvedSnapshot> pinnedByCanonical =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ResolvedSnapshot> pinnedByBlueId =
            new ConcurrentHashMap<>();
    private final WeightedLruCache<FrozenNode.ResolvedStructuralKey,
            ResolvedSnapshot> derivedByCanonical;
    private final WeightedLruCache<String, WeakReference<ResolvedSnapshot>>
            derivedByBlueId;
    private final ResolvedReferenceCache referenceCache;

    private long pinnedWeightBytes;
    private long pinnedHighWaterBytes;

    LanguageRuntimeSnapshotStore(BlueCachePolicy policy) {
        this.derivedByCanonical = new WeightedLruCache<>(
                policy.derivedSnapshotMaxEntries(),
                policy.derivedSnapshotMaxWeightBytes(),
                policy.maximumDerivedEntryWeightBytes(),
                LanguageRuntimeSnapshotStore::snapshotWeight);
        this.derivedByBlueId = new WeightedLruCache<>(
                policy.canonicalAliasMaxEntries(),
                policy.canonicalAliasMaxWeightBytes(),
                Math.min(policy.maximumDerivedEntryWeightBytes(), 512L),
                ignored -> 64L);
        this.referenceCache = new ResolvedReferenceCache(policy);
    }

    ResolvedReferenceCache referenceCache() {
        return referenceCache;
    }

    ResolvedSnapshot derived(ResolvedSnapshot snapshot) {
        if (!snapshot.isResolutionComplete()) {
            return snapshot;
        }
        ResolvedSnapshot publishable =
                snapshot.toStrictBlueIdValidatedCanonical();
        if (publishable.verifiedReferenceResolution() != null) {
            referenceCache.putVerifiedResolved(
                    publishable.verifiedReferenceResolution());
        }
        referenceCache.rememberResolvedGraph(
                publishable.frozenResolvedRoot());
        FrozenNode.ResolvedStructuralKey key = publishable
                .frozenCanonicalRoot().resolvedStructuralKey();
        synchronized (mutationLock) {
            ResolvedSnapshot pinned = pinnedByCanonical.get(key);
            if (pinned != null) {
                return preferVerified(pinned, publishable);
            }
            ResolvedSnapshot existing = derivedByCanonical.peek(key);
            ResolvedSnapshot selected = preferVerified(
                    existing, publishable);
            derivedByCanonical.put(key, selected);
            ResolvedSnapshot retained = derivedByCanonical.peek(key);
            if (retained != null
                    && retained.verifiedReferenceResolution() != null) {
                derivedByBlueId.put(
                        retained.blueId(), new WeakReference<>(retained));
            }
            return retained != null ? retained : selected;
        }
    }

    ResolvedSnapshot pin(ResolvedSnapshot snapshot) {
        if (snapshot == null || !snapshot.isResolutionComplete()) {
            throw new IllegalArgumentException(
                    "Deferred-resolution snapshots cannot be pinned as "
                            + "complete resolved snapshots");
        }
        ResolvedSnapshot publishable =
                snapshot.toStrictBlueIdValidatedCanonical();
        if (publishable.verifiedReferenceResolution() != null) {
            referenceCache.putPinnedVerifiedResolved(
                    publishable.verifiedReferenceResolution());
        }
        referenceCache.rememberResolvedGraph(
                publishable.frozenResolvedRoot());
        FrozenNode.ResolvedStructuralKey key = publishable
                .frozenCanonicalRoot().resolvedStructuralKey();
        synchronized (mutationLock) {
            ResolvedSnapshot previous = pinnedByCanonical.get(key);
            ResolvedSnapshot selected = preferVerified(
                    previous != null
                            ? previous
                            : derivedByCanonical.peek(key),
                    publishable);
            if (previous == null) {
                pinnedByCanonical.put(key, selected);
                pinnedWeightBytes = saturatedAdd(
                        pinnedWeightBytes, snapshotWeight(selected));
            } else if (selected != previous) {
                pinnedByCanonical.put(key, selected);
                pinnedWeightBytes = Math.max(
                        0L, pinnedWeightBytes - snapshotWeight(previous));
                pinnedWeightBytes = saturatedAdd(
                        pinnedWeightBytes, snapshotWeight(selected));
            }
            pinnedHighWaterBytes = Math.max(
                    pinnedHighWaterBytes, pinnedWeightBytes);
            derivedByCanonical.remove(key);
            if (selected.verifiedReferenceResolution() != null) {
                pinnedByBlueId.put(selected.blueId(), selected);
                derivedByBlueId.remove(selected.blueId());
            }
            return selected;
        }
    }

    ResolvedSnapshot byCanonical(
            FrozenNode.ResolvedStructuralKey key) {
        ResolvedSnapshot pinned = pinnedByCanonical.get(key);
        return pinned != null ? pinned : derivedByCanonical.get(key);
    }

    Optional<ResolvedSnapshot> byBlueId(String blueId) {
        ResolvedSnapshot pinned = pinnedByBlueId.get(blueId);
        if (pinned != null) {
            return Optional.of(pinned);
        }
        WeakReference<ResolvedSnapshot> reference =
                derivedByBlueId.get(blueId);
        ResolvedSnapshot derived = reference != null
                ? reference.get()
                : null;
        if (reference != null && derived == null) {
            derivedByBlueId.remove(blueId);
        }
        return Optional.ofNullable(derived);
    }

    void clear() {
        referenceCache.clear();
        synchronized (mutationLock) {
            pinnedByCanonical.clear();
            pinnedByBlueId.clear();
            pinnedWeightBytes = 0L;
            derivedByCanonical.clear();
            derivedByBlueId.clear();
        }
    }

    BlueCacheStats stats(boolean closed) {
        Map<String, BlueCacheStats.Region> regions =
                new LinkedHashMap<>();
        ResolvedReferenceCache.CacheStats reference =
                referenceCache.cacheStats();
        synchronized (mutationLock) {
            regions.put(PINNED_SNAPSHOT_CACHE,
                    new BlueCacheStats.Region(
                            pinnedByCanonical.size(),
                            pinnedWeightBytes,
                            pinnedHighWaterBytes,
                            0L, 0L, 0L, 0L, true));
            regions.put(DERIVED_SNAPSHOT_CACHE,
                    region(derivedByCanonical, false));
            regions.put(CANONICAL_ALIAS_CACHE,
                    region(derivedByBlueId, false));
            regions.put(VERIFIED_REFERENCE_CACHE,
                    new BlueCacheStats.Region(
                            reference.verifiedEntries(),
                            reference.verifiedCurrentWeightBytes(),
                            reference.verifiedHighWaterWeightBytes(),
                            0L,
                            0L,
                            reference.verifiedEvictions(),
                            reference.verifiedOversizedRejections(),
                            reference.pinnedVerifiedEntries() > 0));
            regions.put(TRANSIENT_REFERENCE_CACHE,
                    new BlueCacheStats.Region(
                            reference.transientTrustedEntries(),
                            reference.transientTrustedCurrentWeightBytes(),
                            reference.transientTrustedHighWaterWeightBytes(),
                            0L,
                            0L,
                            reference.transientTrustedEvictions(),
                            reference.transientTrustedOversizedRejections(),
                            false));
            regions.put(STRUCTURAL_INTERNER_CACHE,
                    new BlueCacheStats.Region(
                            reference.structuralEntries(),
                            reference.structuralCurrentWeightBytes(),
                            reference.structuralHighWaterWeightBytes(),
                            0L,
                            0L,
                            reference.structuralEvictions(),
                            reference.structuralOversizedRejections(),
                            false));
        }
        return new BlueCacheStats(regions, closed);
    }

    void close() {
        clear();
        referenceCache.close();
    }

    private static ResolvedSnapshot preferVerified(
            ResolvedSnapshot existing,
            ResolvedSnapshot candidate) {
        if (existing == null) {
            return candidate;
        }
        return existing.verifiedReferenceResolution() == null
                && candidate.verifiedReferenceResolution() != null
                ? candidate
                : existing;
    }

    private static <K, V> BlueCacheStats.Region region(
            WeightedLruCache<K, V> cache,
            boolean pinned) {
        return new BlueCacheStats.Region(
                cache.size(),
                cache.currentWeight(),
                cache.highWaterWeight(),
                cache.hits(),
                cache.misses(),
                cache.evictions(),
                cache.oversizedRejections(),
                pinned);
    }

    private static long snapshotWeight(ResolvedSnapshot snapshot) {
        return saturatedAdd(
                snapshot.frozenCanonicalRoot()
                        .approximateRetainedWeightBytes(),
                snapshot.frozenResolvedRoot()
                        .approximateRetainedWeightBytes());
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right
                ? Long.MAX_VALUE
                : left + right;
    }
}
