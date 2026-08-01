package blue.language.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable cache-ownership and weight snapshot for one
 * {@link blue.language.runtime.BlueLanguageRuntime}.
 * Weights are conservative estimates intended for bounding and operational
 * observability rather than exact heap-size measurements.
 */
public final class BlueCacheStats {

    private final Map<String, Region> regions;
    private final boolean closed;

    public BlueCacheStats(Map<String, Region> regions, boolean closed) {
        this.regions = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(regions, "regions")));
        this.closed = closed;
    }

    /**
     * Returns cache regions keyed by runtime metric name.
     *
     * @return immutable region map
     */
    public Map<String, Region> regions() {
        return regions;
    }

    /**
     * Returns one named cache region.
     *
     * @param name runtime metric name
     * @return region statistics, or {@code null} when absent
     */
    public Region region(String name) {
        return regions.get(name);
    }

    /**
     * Returns saturated total retained weight across all regions.
     *
     * @return retained weight in bytes
     */
    public long currentWeightBytes() {
        long total = 0L;
        for (Region region : regions.values()) {
            total = saturatedAdd(total, region.currentWeightBytes());
        }
        return total;
    }

    /**
     * Returns saturated total entry count across all regions.
     *
     * @return retained entry count
     */
    public int entries() {
        int total = 0;
        for (Region region : regions.values()) {
            if (Integer.MAX_VALUE - total < region.entries()) {
                return Integer.MAX_VALUE;
            }
            total += region.entries();
        }
        return total;
    }

    /**
     * Tests whether the owning runtime has closed its cache lifecycle.
     *
     * @return whether the owning runtime is closed
     */
    public boolean isClosed() {
        return closed;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    /** Immutable counters for one ownership/cache region. */
    public static final class Region {
        private final int entries;
        private final long currentWeightBytes;
        private final long highWaterWeightBytes;
        private final long hits;
        private final long misses;
        private final long evictions;
        private final long oversizedRejections;
        private final boolean pinned;

        public Region(int entries,
               long currentWeightBytes,
               long highWaterWeightBytes,
               long hits,
               long misses,
               long evictions,
               long oversizedRejections,
               boolean pinned) {
            if (entries < 0
                    || currentWeightBytes < 0L
                    || highWaterWeightBytes < 0L
                    || hits < 0L
                    || misses < 0L
                    || evictions < 0L
                    || oversizedRejections < 0L) {
                throw new IllegalArgumentException("Cache statistics must not be negative");
            }
            this.entries = entries;
            this.currentWeightBytes = currentWeightBytes;
            this.highWaterWeightBytes = highWaterWeightBytes;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.oversizedRejections = oversizedRejections;
            this.pinned = pinned;
        }

        /** Returns retained entries.
         * @return retained entry count */
        public int entries() {
            return entries;
        }

        /** Returns current retained weight.
         * @return current retained weight in bytes */
        public long currentWeightBytes() {
            return currentWeightBytes;
        }

        /** Returns the retained-weight high-water mark.
         * @return highest observed retained weight in bytes */
        public long highWaterWeightBytes() {
            return highWaterWeightBytes;
        }

        /** Returns successful lookups.
         * @return successful lookup count */
        public long hits() {
            return hits;
        }

        /** Returns unsuccessful lookups.
         * @return unsuccessful lookup count */
        public long misses() {
            return misses;
        }

        /** Returns evictions.
         * @return eviction count */
        public long evictions() {
            return evictions;
        }

        /** Returns oversized-entry rejections.
         * @return oversized rejection count */
        public long oversizedRejections() {
            return oversizedRejections;
        }

        /** Tests whether authoritative entries are pinned.
         * @return whether the region is pinned */
        public boolean isPinned() {
            return pinned;
        }
    }
}
