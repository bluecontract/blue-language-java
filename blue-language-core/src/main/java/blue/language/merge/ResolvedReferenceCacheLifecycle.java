package blue.language.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generation invalidation, scope closure, and aggregate lifetime metrics. */
final class ResolvedReferenceCacheLifecycle {

    private ResolvedReferenceCacheLifecycle() {
    }

    static ResolvedReferenceCache.CacheStats cacheStats(
            ResolvedReferenceCache cache) {
        ResolvedReferenceCacheGeneration generation =
                cache.cacheGeneration;
        synchronized (generation.mutationLock) {
            if (cache.readThroughParent != null) {
                return cache.localCacheStats();
            }
            int verifiedEntries = 0;
            int pinnedVerifiedEntries = 0;
            long verifiedCurrentWeight = 0L;
            long verifiedHighWaterWeight = 0L;
            long verifiedEvictions = 0L;
            long verifiedOversizedRejections = 0L;
            int structuralEntries = 0;
            long structuralCurrentWeight = 0L;
            long structuralHighWaterWeight = 0L;
            long structuralEvictions = 0L;
            long structuralOversizedRejections = 0L;
            for (ResolvedReferenceCache live : generation.liveCaches()) {
                ResolvedReferenceCache.CacheStats local =
                        live.localCacheStats();
                verifiedEntries = add(
                        verifiedEntries, local.verifiedEntries());
                pinnedVerifiedEntries = add(
                        pinnedVerifiedEntries,
                        local.pinnedVerifiedEntries());
                verifiedCurrentWeight = add(
                        verifiedCurrentWeight,
                        local.verifiedCurrentWeightBytes());
                verifiedHighWaterWeight = add(
                        verifiedHighWaterWeight,
                        local.verifiedHighWaterWeightBytes());
                verifiedEvictions = add(
                        verifiedEvictions,
                        local.verifiedEvictions());
                verifiedOversizedRejections = add(
                        verifiedOversizedRejections,
                        local.verifiedOversizedRejections());
                structuralEntries = add(
                        structuralEntries,
                        local.structuralEntries());
                structuralCurrentWeight = add(
                        structuralCurrentWeight,
                        local.structuralCurrentWeightBytes());
                structuralHighWaterWeight = add(
                        structuralHighWaterWeight,
                        local.structuralHighWaterWeightBytes());
                structuralEvictions = add(
                        structuralEvictions,
                        local.structuralEvictions());
                structuralOversizedRejections = add(
                        structuralOversizedRejections,
                        local.structuralOversizedRejections());
            }
            generation.verifiedHighWaterWeight = Math.max(
                    generation.verifiedHighWaterWeight,
                    verifiedHighWaterWeight);
            generation.structuralHighWaterWeight = Math.max(
                    generation.structuralHighWaterWeight,
                    structuralHighWaterWeight);
            return new ResolvedReferenceCache.CacheStats(
                    verifiedEntries,
                    pinnedVerifiedEntries,
                    verifiedCurrentWeight,
                    generation.verifiedHighWaterWeight,
                    verifiedEvictions,
                    verifiedOversizedRejections,
                    0,
                    0L,
                    0L,
                    0L,
                    0L,
                    structuralEntries,
                    structuralCurrentWeight,
                    generation.structuralHighWaterWeight,
                    structuralEvictions,
                    structuralOversizedRejections);
        }
    }

    static void clearReloadable(ResolvedReferenceCache cache) {
        ResolvedReferenceCacheGeneration generation =
                cache.cacheGeneration;
        synchronized (generation.mutationLock) {
            requireOpen(cache);
            if (cache.readThroughParent != null) {
                throw new IllegalStateException(
                        "Reloadable state can only be cleared from the root reference cache");
            }
            retainLiveHighWaterMarks(cache);
            Map<String, VerifiedReferenceEntry> retainedPinned =
                    new HashMap<>();
            for (String blueId :
                    cache.accounting.pinnedBlueIdsSnapshot()) {
                VerifiedReferenceEntry entry =
                        cache.entriesByBlueId.get(blueId);
                if (entry != null) {
                    retainedPinned.put(blueId, entry);
                }
            }
            Set<String> retainedPinnedIds =
                    new HashSet<>(retainedPinned.keySet());
            cache.observedGeneration = generation.value.incrementAndGet();
            for (ResolvedReferenceCache live : generation.liveCaches()) {
                clearLocalState(live);
                live.observedGeneration = cache.observedGeneration;
            }
            cache.entriesByBlueId.putAll(retainedPinned);
            cache.rebuildLocalWeightAccounting(retainedPinnedIds);
        }
    }

    static void clear(ResolvedReferenceCache cache) {
        ResolvedReferenceCacheGeneration generation =
                cache.cacheGeneration;
        synchronized (generation.mutationLock) {
            requireOpen(cache);
            retainLiveHighWaterMarks(cache);
            if (cache.readThroughParent == null) {
                cache.observedGeneration =
                        generation.value.incrementAndGet();
                for (ResolvedReferenceCache live : generation.liveCaches()) {
                    clearLocalState(live);
                    live.observedGeneration = cache.observedGeneration;
                }
            } else {
                cache.observedGeneration = generation.value.get();
                clearLocalState(cache);
            }
        }
    }

    static boolean isCurrentGeneration(ResolvedReferenceCache cache) {
        return !cache.locallyClosed
                && !hasClosedAncestor(cache)
                && !cache.cacheGeneration.closed
                && (cache.readThroughParent == null
                || cache.openedGeneration
                == cache.cacheGeneration.value.get());
    }

    static void ensureCurrentGeneration(ResolvedReferenceCache cache) {
        ResolvedReferenceCacheGeneration generation =
                cache.cacheGeneration;
        if (cache.locallyClosed
                || hasClosedAncestor(cache)
                || generation.closed) {
            throw new IllegalStateException(
                    "Resolved reference cache is closed");
        }
        long current = generation.value.get();
        if (cache.observedGeneration == current) {
            return;
        }
        synchronized (generation.mutationLock) {
            current = generation.value.get();
            if (cache.observedGeneration == current) {
                return;
            }
            clearLocalState(cache);
            cache.observedGeneration = current;
        }
    }

    static void close(ResolvedReferenceCache cache) {
        ResolvedReferenceCacheGeneration generation =
                cache.cacheGeneration;
        synchronized (generation.mutationLock) {
            if (cache.locallyClosed) {
                return;
            }
            if (cache.readThroughParent != null) {
                retainLiveHighWaterMarks(cache);
                List<ResolvedReferenceCache> closedScopes =
                        new ArrayList<>();
                for (ResolvedReferenceCache live : generation.liveCaches()) {
                    if (live == cache || isDescendantOf(live, cache)) {
                        live.locallyClosed = true;
                        clearLocalState(live);
                        closedScopes.add(live);
                    }
                }
                for (ResolvedReferenceCache closedScope : closedScopes) {
                    generation.unregister(closedScope);
                }
                return;
            }
            if (generation.closed) {
                cache.locallyClosed = true;
                clearLocalState(cache);
                return;
            }
            retainLiveHighWaterMarks(cache);
            generation.closed = true;
            generation.value.incrementAndGet();
            for (ResolvedReferenceCache live : generation.liveCaches()) {
                live.locallyClosed = true;
                clearLocalState(live);
            }
            generation.caches.clear();
        }
    }

    static boolean hasClosedAncestor(ResolvedReferenceCache cache) {
        ResolvedReferenceCache current = cache.readThroughParent;
        while (current != null) {
            if (current.locallyClosed) {
                return true;
            }
            current = current.readThroughParent;
        }
        return false;
    }

    private static boolean isDescendantOf(
            ResolvedReferenceCache cache,
            ResolvedReferenceCache ancestor) {
        ResolvedReferenceCache current = cache.readThroughParent;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.readThroughParent;
        }
        return false;
    }

    private static void clearLocalState(ResolvedReferenceCache cache) {
        cache.entriesByBlueId.clear();
        cache.resolvedGraphNodesByStructure.clear();
        cache.accounting.clearCurrent();
    }

    private static void retainLiveHighWaterMarks(
            ResolvedReferenceCache cache) {
        ResolvedReferenceCacheGeneration generation =
                cache.cacheGeneration;
        long verified = 0L;
        long structural = 0L;
        for (ResolvedReferenceCache live : generation.liveCaches()) {
            ResolvedReferenceCache.CacheStats local =
                    live.localCacheStats();
            verified = add(
                    verified,
                    local.verifiedHighWaterWeightBytes());
            structural = add(
                    structural,
                    local.structuralHighWaterWeightBytes());
        }
        generation.verifiedHighWaterWeight = Math.max(
                generation.verifiedHighWaterWeight, verified);
        generation.structuralHighWaterWeight = Math.max(
                generation.structuralHighWaterWeight, structural);
    }

    private static void requireOpen(ResolvedReferenceCache cache) {
        if (cache.locallyClosed || cache.cacheGeneration.closed) {
            throw new IllegalStateException(
                    "Resolved reference cache is closed");
        }
    }

    private static long add(long left, long right) {
        return ResolvedReferenceCacheAccounting.saturatedAdd(
                left, right);
    }

    private static int add(int left, int right) {
        return ResolvedReferenceCacheAccounting.saturatedAdd(
                left, right);
    }
}
