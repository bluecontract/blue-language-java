package blue.language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeightedLruCacheTest {

    @Test
    void evictsLeastRecentlyUsedEntriesByWeightAndCount() {
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(2, 6L, 6L,
                value -> value.length());
        cache.put("a", "aa");
        cache.put("b", "bb");
        assertEquals("aa", cache.get("a"));
        cache.put("c", "cccc");

        assertEquals("aa", cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals("cccc", cache.get("c"));
        assertEquals(1L, cache.evictions());
        assertEquals(6L, cache.currentWeight());
    }

    @Test
    void rejectsOversizedEntriesWithoutDroppingAnExistingValue() {
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(2, 8L, 4L,
                value -> value.length());
        cache.put("a", "old");
        assertEquals("old", cache.put("a", "oversized"));
        assertEquals("old", cache.get("a"));
        assertEquals(1L, cache.oversizedRejections());
    }

    @Test
    void clearReportsReleasedWeight() {
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(4, 100L, 100L,
                value -> value.length());
        cache.put("a", "abc");
        cache.put("b", "defg");
        assertEquals(7L, cache.clear());
        assertEquals(0L, cache.currentWeight());
        assertEquals(0, cache.size());
    }

    @Test
    void reportsLookupHitsAndMissesWithoutCountingPeeks() {
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(4, 100L, 100L,
                value -> value.length());
        cache.put("a", "abc");

        assertEquals("abc", cache.get("a"));
        assertNull(cache.get("missing"));
        assertEquals("abc", cache.peek("a"));

        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.misses());
    }
}
