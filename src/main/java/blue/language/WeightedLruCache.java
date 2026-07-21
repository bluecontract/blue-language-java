package blue.language;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small synchronized weighted LRU for reloadable derived state. */
final class WeightedLruCache<K, V> {

    public interface Weigher<V> {
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

    public WeightedLruCache(int maximumEntries,
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

    public synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            misses++;
        } else {
            hits++;
        }
        return entry != null ? entry.value : null;
    }

    /** Returns a value without changing hit/miss counters. */
    public synchronized V peek(K key) {
        Entry<V> entry = entries.get(key);
        return entry != null ? entry.value : null;
    }

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

    public synchronized V remove(K key) {
        Entry<V> removed = entries.remove(key);
        if (removed != null) {
            currentWeight -= removed.weight;
            return removed.value;
        }
        return null;
    }

    public synchronized long clear() {
        long released = currentWeight;
        entries.clear();
        currentWeight = 0L;
        return released;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long currentWeight() {
        return currentWeight;
    }

    public synchronized long highWaterWeight() {
        return highWaterWeight;
    }

    public synchronized long evictions() {
        return evictions;
    }

    public synchronized long oversizedRejections() {
        return oversizedRejections;
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long misses() {
        return misses;
    }

    private void evictToBounds() {
        while (entries.size() > maximumEntries || currentWeight > maximumWeight) {
            Map.Entry<K, Entry<V>> eldest = entries.entrySet().iterator().next();
            currentWeight -= eldest.getValue().weight;
            entries.remove(eldest.getKey());
            evictions++;
        }
    }

    private static final class Entry<V> {
        private final V value;
        private final long weight;

        private Entry(V value, long weight) {
            this.value = value;
            this.weight = weight;
        }
    }
}
