package blue.language;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable cache-ownership and weight snapshot for one {@link Blue} runtime.
 * Weights are conservative estimates intended for bounding and operational
 * observability rather than exact heap-size measurements.
 */
public final class BlueCacheStats {

    private final Map<String, Region> regions;
    private final boolean closed;

    BlueCacheStats(Map<String, Region> regions, boolean closed) {
        this.regions = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(regions, "regions")));
        this.closed = closed;
    }

    /** Cache regions keyed by the metric name reported by this runtime. */
    public Map<String, Region> regions() {
        return regions;
    }

    public Region region(String name) {
        return regions.get(name);
    }

    public long currentWeightBytes() {
        long total = 0L;
        for (Region region : regions.values()) {
            total = saturatedAdd(total, region.currentWeightBytes());
        }
        return total;
    }

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

        Region(int entries,
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

        public int entries() {
            return entries;
        }

        public long currentWeightBytes() {
            return currentWeightBytes;
        }

        public long highWaterWeightBytes() {
            return highWaterWeightBytes;
        }

        public long hits() {
            return hits;
        }

        public long misses() {
            return misses;
        }

        public long evictions() {
            return evictions;
        }

        public long oversizedRejections() {
            return oversizedRejections;
        }

        public boolean isPinned() {
            return pinned;
        }
    }
}
