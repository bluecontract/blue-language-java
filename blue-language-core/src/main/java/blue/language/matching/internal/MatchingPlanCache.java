package blue.language.matching.internal;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.snapshot.FrozenNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE;

/**
 * Matcher-owned, access-ordered cache partitioned by semantic result region.
 *
 * <p>One global entry and retained-weight bound applies across every region,
 * preventing subtype or reference workloads from starving structural match
 * plans indefinitely.</p>
 */
public final class MatchingPlanCache {

    private static final long CACHE_ENTRY_OVERHEAD_BYTES = 80L;
    private static final long SIMPLE_VALUE_WEIGHT_BYTES = 16L;
    private static final long UNKNOWN_VALUE_WEIGHT_BYTES = 128L;
    private static final long STRING_OVERHEAD_BYTES = 48L;
    private static final long UTF_16_CODE_UNIT_BYTES = 2L;

    /** Independent matcher-result namespaces sharing the same bounded store. */
    public enum Region {
        RESOLVED_REFERENCE,
        SUBTYPE,
        MATCH,
        TYPE_COMPATIBILITY,
        UNRESOLVED_REFERENCE
    }

    /** Supplies retained weight for composite keys held by this cache. */
    public interface Weighted {

        /** Returns the approximate retained weight attributed to this value. */
        long retainedWeightBytes();
    }

    private final int maximumEntries;
    private final long maximumWeightBytes;
    private final long maximumEntryWeightBytes;
    private final LinkedHashMap<PlanCacheKey, CacheEntry> entries =
            new LinkedHashMap<PlanCacheKey, CacheEntry>(16, 0.75f, true);
    private long currentWeightBytes;

    /** Creates a cache using the conformance-plan bounds in {@code policy}. */
    public MatchingPlanCache(BlueCachePolicy policy) {
        BlueCachePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        this.maximumEntries = requiredPolicy.conformancePlanMaxEntries();
        this.maximumWeightBytes = requiredPolicy.conformancePlanMaxWeightBytes();
        this.maximumEntryWeightBytes = Math.min(
                requiredPolicy.maximumDerivedEntryWeightBytes(), maximumWeightBytes);
    }

    /** Returns a retained result, or {@code null} when this region/key is absent. */
    public synchronized Object get(Region region, Object key) {
        CacheEntry entry = entries.get(new PlanCacheKey(region, key));
        return entry != null ? entry.value : null;
    }

    /** Retains a result when both its individual and aggregate bounds permit it. */
    public synchronized void put(Region region, Object key, Object value) {
        PlanCacheKey cacheKey = new PlanCacheKey(region, key);
        long weight = estimateWeight(cacheKey, value);
        if (weight > maximumEntryWeightBytes || weight > maximumWeightBytes) {
            return;
        }
        CacheEntry previous = entries.remove(cacheKey);
        if (previous != null) {
            currentWeightBytes -= previous.weightBytes;
        }
        entries.put(cacheKey, new CacheEntry(value, weight));
        currentWeightBytes = saturatedAdd(currentWeightBytes, weight);
        evictToBounds();
    }

    /** Releases every reloadable result. */
    public synchronized void clear() {
        entries.clear();
        currentWeightBytes = 0L;
    }

    /** Returns the entry count across all semantic regions. */
    public synchronized int size() {
        return entries.size();
    }

    /** Returns the current approximate retained weight in bytes. */
    public synchronized long currentWeightBytes() {
        return currentWeightBytes;
    }

    private void evictToBounds() {
        Iterator<Map.Entry<PlanCacheKey, CacheEntry>> iterator =
                entries.entrySet().iterator();
        while ((entries.size() > maximumEntries
                || currentWeightBytes > maximumWeightBytes) && iterator.hasNext()) {
            CacheEntry eldest = iterator.next().getValue();
            currentWeightBytes -= eldest.weightBytes;
            iterator.remove();
        }
    }

    private long estimateWeight(PlanCacheKey key, Object value) {
        long weight = CACHE_ENTRY_OVERHEAD_BYTES + retainedWeight(key.key);
        return saturatedAdd(weight, retainedWeight(value));
    }

    private long retainedWeight(Object value) {
        if (value == null || value instanceof Boolean) {
            return SIMPLE_VALUE_WEIGHT_BYTES;
        }
        if (value instanceof String) {
            return STRING_OVERHEAD_BYTES
                    + UTF_16_CODE_UNIT_BYTES * ((String) value).length();
        }
        if (value instanceof FrozenNode) {
            return ((FrozenNode) value).approximateRetainedWeightBytes();
        }
        if (value instanceof Weighted) {
            return ((Weighted) value).retainedWeightBytes();
        }
        return UNKNOWN_VALUE_WEIGHT_BYTES;
    }

    private long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static final class PlanCacheKey {
        private final Region region;
        private final Object key;

        private PlanCacheKey(Region region, Object key) {
            this.region = Objects.requireNonNull(region, "region");
            this.key = Objects.requireNonNull(key, "key");
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof PlanCacheKey
                    && region == ((PlanCacheKey) other).region
                    && key.equals(((PlanCacheKey) other).key);
        }

        @Override
        public int hashCode() {
            return 31 * region.hashCode() + key.hashCode();
        }
    }

    private static final class CacheEntry {
        private final Object value;
        private final long weightBytes;

        private CacheEntry(Object value, long weightBytes) {
            this.value = Objects.requireNonNull(value, OBJECT_VALUE);
            this.weightBytes = weightBytes;
        }
    }
}
