package blue.language.snapshot;

import blue.language.BlueCachePolicy;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mutation-lock-confined accounting and bounded-eviction policy for one
 * reference-cache scope.
 *
 * <p>The owner supplies its storage maps so this collaborator cannot publish
 * evidence by itself. It only records already-admitted entries and removes
 * derived entries when the configured bounds require it.</p>
 */
final class ResolvedReferenceCacheAccounting {

    private static final long VERIFIED_ENTRY_OVERHEAD_BYTES = 128L;
    private static final long STRUCTURAL_ENTRY_OVERHEAD_BYTES = 64L;
    private static final int BLUE_ID_CHARACTER_BYTES = 2;

    private final BlueCachePolicy cachePolicy;
    private final boolean rootScope;
    private final Set<String> pinnedVerifiedBlueIds = new HashSet<>();
    private final LinkedHashSet<String> verifiedInsertionOrder =
            new LinkedHashSet<>();
    private final LinkedHashSet<FrozenNode.ResolvedStructuralKey>
            structuralInsertionOrder = new LinkedHashSet<>();
    private long verifiedCurrentWeight;
    private long verifiedHighWaterWeight;
    private long verifiedEvictions;
    private long verifiedOversizedRejections;
    private long structuralCurrentWeight;
    private long structuralHighWaterWeight;
    private long structuralEvictions;
    private long structuralOversizedRejections;

    ResolvedReferenceCacheAccounting(
            BlueCachePolicy cachePolicy,
            boolean rootScope) {
        this.cachePolicy = cachePolicy;
        this.rootScope = rootScope;
    }

    void pin(String blueId) {
        pinnedVerifiedBlueIds.add(blueId);
    }

    Set<String> pinnedBlueIdsSnapshot() {
        return new HashSet<>(pinnedVerifiedBlueIds);
    }

    void recordVerifiedInsertion(
            String blueId,
            VerifiedReferenceEntry entry,
            Map<String, VerifiedReferenceEntry> entries) {
        recordVerifiedReplacement(blueId, null, entry, entries);
    }

    void recordVerifiedReplacement(
            String blueId,
            VerifiedReferenceEntry previous,
            VerifiedReferenceEntry replacement,
            Map<String, VerifiedReferenceEntry> entries) {
        long replacementWeight = verifiedWeight(blueId, replacement);
        if (rootScope
                && !pinnedVerifiedBlueIds.contains(blueId)
                && (replacementWeight
                > cachePolicy.maximumDerivedEntryWeightBytes()
                || replacementWeight
                > cachePolicy.transientReferenceMaxWeightBytes())) {
            verifiedOversizedRejections++;
            if (previous == null) {
                entries.remove(blueId, replacement);
            } else {
                entries.put(blueId, previous);
            }
            return;
        }
        if (previous != null) {
            verifiedCurrentWeight = subtractFloorZero(
                    verifiedCurrentWeight,
                    verifiedWeight(blueId, previous));
        }
        verifiedInsertionOrder.remove(blueId);
        verifiedInsertionOrder.add(blueId);
        verifiedCurrentWeight = saturatedAdd(
                verifiedCurrentWeight, replacementWeight);
        verifiedHighWaterWeight = Math.max(
                verifiedHighWaterWeight, verifiedCurrentWeight);
        evictVerifiedToBounds(entries);
    }

    void recordStructuralInsertion(
            FrozenNode.ResolvedStructuralKey key,
            FrozenNode node,
            Map<FrozenNode.ResolvedStructuralKey, FrozenNode> entries) {
        long weight = structuralWeight(node);
        if (rootScope
                && (weight > cachePolicy.maximumDerivedEntryWeightBytes()
                || weight
                > cachePolicy.resolvedStructuralMaxWeightBytes())) {
            entries.remove(key, node);
            structuralOversizedRejections++;
            return;
        }
        structuralInsertionOrder.remove(key);
        structuralInsertionOrder.add(key);
        structuralCurrentWeight = saturatedAdd(
                structuralCurrentWeight, weight);
        structuralHighWaterWeight = Math.max(
                structuralHighWaterWeight, structuralCurrentWeight);
        evictStructuralToBounds(entries);
    }

    void removeVerifiedEntry(
            String blueId,
            Map<String, VerifiedReferenceEntry> entries) {
        VerifiedReferenceEntry removed =
                entries.remove(blueId);
        verifiedInsertionOrder.remove(blueId);
        if (removed != null) {
            verifiedCurrentWeight = subtractFloorZero(
                    verifiedCurrentWeight,
                    verifiedWeight(blueId, removed));
        }
    }

    void removeStructuralEntry(
            FrozenNode.ResolvedStructuralKey key,
            Map<FrozenNode.ResolvedStructuralKey, FrozenNode> entries) {
        FrozenNode removed = entries.remove(key);
        structuralInsertionOrder.remove(key);
        if (removed != null) {
            structuralCurrentWeight = subtractFloorZero(
                    structuralCurrentWeight,
                    structuralWeight(removed));
        }
    }

    void rebuild(
            Map<String, VerifiedReferenceEntry> verifiedEntries,
            Map<FrozenNode.ResolvedStructuralKey, FrozenNode>
                    structuralEntries,
            Set<String> retainedPinnedBlueIds) {
        clearCurrent();
        pinnedVerifiedBlueIds.addAll(retainedPinnedBlueIds);
        for (Map.Entry<String, VerifiedReferenceEntry>
                entry : verifiedEntries.entrySet()) {
            verifiedInsertionOrder.add(entry.getKey());
            verifiedCurrentWeight = saturatedAdd(
                    verifiedCurrentWeight,
                    verifiedWeight(entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<FrozenNode.ResolvedStructuralKey, FrozenNode>
                entry : structuralEntries.entrySet()) {
            structuralInsertionOrder.add(entry.getKey());
            structuralCurrentWeight = saturatedAdd(
                    structuralCurrentWeight,
                    structuralWeight(entry.getValue()));
        }
        verifiedHighWaterWeight = Math.max(
                verifiedHighWaterWeight, verifiedCurrentWeight);
        structuralHighWaterWeight = Math.max(
                structuralHighWaterWeight, structuralCurrentWeight);
    }

    void clearCurrent() {
        pinnedVerifiedBlueIds.clear();
        verifiedInsertionOrder.clear();
        structuralInsertionOrder.clear();
        verifiedCurrentWeight = 0L;
        structuralCurrentWeight = 0L;
    }

    long pinnedVerifiedWeightBytes(
            Map<String, VerifiedReferenceEntry> entries) {
        long weight = 0L;
        for (String blueId : pinnedVerifiedBlueIds) {
            VerifiedReferenceEntry entry =
                    entries.get(blueId);
            if (entry != null) {
                weight = saturatedAdd(
                        weight, verifiedWeight(blueId, entry));
            }
        }
        return weight;
    }

    ResolvedReferenceCache.CacheStats snapshot(
            int verifiedEntries,
            int structuralEntries) {
        return new ResolvedReferenceCache.CacheStats(
                verifiedEntries,
                pinnedVerifiedBlueIds.size(),
                verifiedCurrentWeight,
                verifiedHighWaterWeight,
                verifiedEvictions,
                verifiedOversizedRejections,
                0,
                0L,
                0L,
                0L,
                0L,
                structuralEntries,
                structuralCurrentWeight,
                structuralHighWaterWeight,
                structuralEvictions,
                structuralOversizedRejections);
    }

    private void evictVerifiedToBounds(
            Map<String, VerifiedReferenceEntry> entries) {
        if (!rootScope) {
            return;
        }
        while (entries.size()
                > cachePolicy.transientReferenceMaxEntries()
                || verifiedCurrentWeight
                > cachePolicy.transientReferenceMaxWeightBytes()) {
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
            removeVerifiedEntry(victim, entries);
            verifiedEvictions++;
        }
    }

    private void evictStructuralToBounds(
            Map<FrozenNode.ResolvedStructuralKey, FrozenNode> entries) {
        if (!rootScope) {
            return;
        }
        while (entries.size()
                > cachePolicy.resolvedStructuralMaxEntries()
                || structuralCurrentWeight
                > cachePolicy.resolvedStructuralMaxWeightBytes()) {
            if (structuralInsertionOrder.isEmpty()) {
                return;
            }
            FrozenNode.ResolvedStructuralKey victim =
                    structuralInsertionOrder.iterator().next();
            removeStructuralEntry(victim, entries);
            structuralEvictions++;
        }
    }

    private static long verifiedWeight(
            String blueId,
            VerifiedReferenceEntry entry) {
        return saturatedAdd(
                VERIFIED_ENTRY_OVERHEAD_BYTES
                        + BLUE_ID_CHARACTER_BYTES * (long) blueId.length(),
                FrozenNode.approximateRetainedWeightBytesOf(
                        entry.canonicalContent,
                        entry.fullyResolvedContent));
    }

    private static long structuralWeight(FrozenNode node) {
        return saturatedAdd(
                STRUCTURAL_ENTRY_OVERHEAD_BYTES,
                node.approximateShallowRetainedWeightBytes());
    }

    static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right
                ? Long.MAX_VALUE
                : left + right;
    }

    static int saturatedAdd(int left, int right) {
        return Integer.MAX_VALUE - left < right
                ? Integer.MAX_VALUE
                : left + right;
    }

    private static long subtractFloorZero(long left, long right) {
        return right >= left ? 0L : left - right;
    }
}
