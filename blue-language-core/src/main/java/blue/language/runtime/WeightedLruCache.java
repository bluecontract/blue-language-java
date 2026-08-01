package blue.language.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small synchronized, access-ordered cache for reloadable derived state.
 *
 * <p>Entries are bounded by count, aggregate weight, and individual weight.
 * Values rejected by a disabled or undersized policy remain usable by their
 * caller but are not retained.</p>
 *
 * @param <K> cache-key type
 * @param <V> cached-value type
 */
public final class WeightedLruCache<K, V> {

    /**
     * Calculates the approximate retained weight of a cache value.
     *
     * @param <V> weighed-value type
     */
    public interface Weigher<V> {

        /**
         * Returns the approximate retained weight of {@code value}.
         *
         * @param value non-null candidate value
         * @return retained weight; values below one are normalized to one
         */
        long weightOf(V value);
    }

    private final int maximumEntries;
    private final long maximumWeight;
    private final long maximumEntryWeight;
    private final Weigher<V> weigher;
    private final LinkedHashMap<K, Entry<V>> entries =
            new LinkedHashMap<K, Entry<V>>(16, 0.75f, true);
    private long currentWeight;
    private long highWaterWeight;
    private long evictions;
    private long oversizedRejections;
    private long hits;
    private long misses;

    /**
     * Creates an empty cache with simultaneous entry and weight bounds.
     *
     * @param maximumEntries maximum retained entry count
     * @param maximumWeight maximum aggregate retained weight
     * @param maximumEntryWeight maximum retained weight of one entry
     * @param weigher value-weight calculator
     * @throws IllegalArgumentException if a bound is negative or the weigher
     *         is {@code null}
     */
    public WeightedLruCache(
            int maximumEntries,
            long maximumWeight,
            long maximumEntryWeight,
            Weigher<V> weigher) {
        if (maximumEntries < 0 || maximumWeight < 0L || maximumEntryWeight < 0L) {
            throw new IllegalArgumentException("Cache bounds must not be negative");
        }
        if (weigher == null) {
            throw new IllegalArgumentException("weigher must not be null");
        }
        this.maximumEntries = maximumEntries;
        this.maximumWeight = maximumWeight;
        this.maximumEntryWeight = maximumEntryWeight;
        this.weigher = weigher;
    }

    /**
     * Returns the cached value and records a hit or miss.
     *
     * @param key lookup key
     * @return retained value, or {@code null} when absent
     */
    public synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            misses++;
        } else {
            hits++;
        }
        return entry != null ? entry.value : null;
    }

    /**
     * Returns a value without changing hit/miss counters.
     *
     * @param key lookup key
     * @return retained value, or {@code null} when absent
     */
    public synchronized V peek(K key) {
        Entry<V> entry = entries.get(key);
        return entry != null ? entry.value : null;
    }

    /**
     * Retains a value when it fits every configured bound.
     *
     * <p>An oversized rejection leaves an existing value for the same key
     * untouched. A successful replacement updates access order before the
     * least-recently-used entries are evicted to restore the bounds.</p>
     *
     * @param key non-null cache key
     * @param value non-null candidate value
     * @return previously retained value for {@code key}, or {@code null}
     * @throws IllegalArgumentException if the key or value is {@code null}
     */
    public synchronized V put(K key, V value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("Cache keys and values must not be null");
        }
        if (maximumEntries == 0 || maximumWeight == 0L || maximumEntryWeight == 0L) {
            oversizedRejections++;
            Entry<V> previous = entries.get(key);
            return previous != null ? previous.value : null;
        }
        long weight = Math.max(1L, weigher.weightOf(value));
        if (weight > maximumEntryWeight || weight > maximumWeight) {
            oversizedRejections++;
            Entry<V> previous = entries.get(key);
            return previous != null ? previous.value : null;
        }
        Entry<V> previous = entries.remove(key);
        if (previous != null) {
            currentWeight -= previous.weight;
        }
        entries.put(key, new Entry<V>(value, weight));
        currentWeight += weight;
        if (currentWeight > highWaterWeight) {
            highWaterWeight = currentWeight;
        }
        evictToBounds();
        return previous != null ? previous.value : null;
    }

    /**
     * Removes one retained entry.
     *
     * @param key key to remove
     * @return removed value, or {@code null} when absent
     */
    public synchronized V remove(K key) {
        Entry<V> removed = entries.remove(key);
        if (removed != null) {
            currentWeight -= removed.weight;
            return removed.value;
        }
        return null;
    }

    /**
     * Removes every retained entry without resetting lifetime counters.
     *
     * @return aggregate weight released by the clear
     */
    public synchronized long clear() {
        long released = currentWeight;
        entries.clear();
        currentWeight = 0L;
        return released;
    }

    /**
     * Returns the current retained entry count.
     *
     * @return current retained entry count
     */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * Returns the current aggregate retained weight.
     *
     * @return current aggregate retained weight
     */
    public synchronized long currentWeight() {
        return currentWeight;
    }

    /**
     * Returns the highest aggregate retained weight observed.
     *
     * @return highest aggregate retained weight observed
     */
    public synchronized long highWaterWeight() {
        return highWaterWeight;
    }

    /**
     * Returns the lifetime count of entries evicted to restore cache bounds.
     *
     * @return lifetime eviction count
     */
    public synchronized long evictions() {
        return evictions;
    }

    /**
     * Returns the lifetime count of candidates rejected by cache bounds.
     *
     * @return lifetime oversized-candidate rejection count
     */
    public synchronized long oversizedRejections() {
        return oversizedRejections;
    }

    /**
     * Returns the lifetime count of successful {@link #get(Object)} lookups.
     *
     * @return lifetime cache-hit count
     */
    public synchronized long hits() {
        return hits;
    }

    /**
     * Returns the lifetime count of unsuccessful {@link #get(Object)} lookups.
     *
     * @return lifetime cache-miss count
     */
    public synchronized long misses() {
        return misses;
    }

    /** Evicts least-recently-used entries until both live bounds are met. */
    private void evictToBounds() {
        while (entries.size() > maximumEntries || currentWeight > maximumWeight) {
            Map.Entry<K, Entry<V>> eldest = entries.entrySet().iterator().next();
            currentWeight -= eldest.getValue().weight;
            entries.remove(eldest.getKey());
            evictions++;
        }
    }

    /** Retained value paired with its normalized approximate weight. */
    private static final class Entry<V> {
        private final V value;
        private final long weight;

        private Entry(V value, long weight) {
            this.value = value;
            this.weight = weight;
        }
    }
}
