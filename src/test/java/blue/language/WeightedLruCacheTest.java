package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeightedLruCacheTest {

    @Test
    void shouldEvictLeastRecentlyUsedEntriesByWeightAndCount() {
        // given
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(2, 6L, 6L,
                value -> value.length());
        cache.put("a", "aa");

        // when
        cache.put("b", "bb");
        String touchedA = cache.get("a");
        cache.put("c", "cccc");
        String retainedA = cache.get("a");
        String evictedB = cache.get("b");
        String retainedC = cache.get("c");
        long evictions = cache.evictions();
        long weight = cache.currentWeight();

        // then
        assertEquals("aa", touchedA);
        assertEquals("aa", retainedA);
        assertNull(evictedB);
        assertEquals("cccc", retainedC);
        assertEquals(1L, evictions);
        assertEquals(6L, weight);
    }

    @Test
    void shouldRejectOversizedEntriesWithoutDroppingAnExistingValue() {
        // given
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(2, 8L, 4L,
                value -> value.length());
        cache.put("a", "old");

        // when
        String rejectedReplacement = cache.put("a", "oversized");
        String retained = cache.get("a");
        long rejections = cache.oversizedRejections();

        // then
        assertEquals("old", rejectedReplacement);
        assertEquals("old", retained);
        assertEquals(1L, rejections);
    }

    @Test
    void shouldZeroBoundsDisableRetentionWithoutThrowing() {
        // given
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(0, 0L, 0L,
                value -> value.length());

        // when
        String rejected = cache.put("a", "value");
        String missing = cache.get("a");
        int size = cache.size();
        long weight = cache.currentWeight();
        long rejections = cache.oversizedRejections();

        // then
        assertNull(rejected);
        assertNull(missing);
        assertEquals(0, size);
        assertEquals(0L, weight);
        assertEquals(1L, rejections);
    }

    @Test
    void shouldClearReportsReleasedWeight() {
        // given
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(4, 100L, 100L,
                value -> value.length());
        cache.put("a", "abc");
        cache.put("b", "defg");

        // when
        long releasedWeight = cache.clear();
        long remainingWeight = cache.currentWeight();
        int remainingEntries = cache.size();

        // then
        assertEquals(7L, releasedWeight);
        assertEquals(0L, remainingWeight);
        assertEquals(0, remainingEntries);
    }

    @Test
    void shouldReportLookupHitsAndMissesWithoutCountingPeeks() {
        // given
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(4, 100L, 100L,
                value -> value.length());
        cache.put("a", "abc");

        // when
        String hit = cache.get("a");
        String miss = cache.get("missing");
        String peek = cache.peek("a");
        long hits = cache.hits();
        long misses = cache.misses();

        // then
        assertEquals("abc", hit);
        assertNull(miss);
        assertEquals("abc", peek);
        assertEquals(1L, hits);
        assertEquals(1L, misses);
    }
}
