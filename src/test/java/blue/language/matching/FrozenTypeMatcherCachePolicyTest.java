package blue.language.matching;

import blue.language.api.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenTypeMatcherCachePolicyTest {

    @Test
    void shouldShareConfiguredEntryAndWeightBudgetAcrossMatcherRegions() {
        // given
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .conformancePlans(5, 4_096L)
                .maximumDerivedEntryWeightBytes(4_096L)
                .build();
        FrozenTypeMatcher matcher = new FrozenTypeMatcher(null, true, policy);

        // when
        boolean allMatched = true;
        boolean entryBudgetRespected = true;
        boolean weightBudgetRespected = true;
        for (int index = 0; index < 40; index++) {
            FrozenNode value = value("value-" + index);
            allMatched &= matcher.matchesType(value, value);
            entryBudgetRespected &= matcher.cacheEntryCount() <= 5;
            weightBudgetRespected &= matcher.cacheWeightBytes() <= 4_096L;
        }
        int retainedEntries = matcher.cacheEntryCount();
        boolean recomputed = matcher.matchesType(value("value-0"), value("value-0"));
        int entriesAfterRecompute = matcher.cacheEntryCount();
        long weightAfterRecompute = matcher.cacheWeightBytes();

        // then
        assertTrue(allMatched);
        assertTrue(entryBudgetRespected);
        assertTrue(weightBudgetRespected);
        assertTrue(retainedEntries > 0);
        assertTrue(recomputed,
                "an evicted plan must remain safely recomputable");
        assertTrue(entriesAfterRecompute <= 5);
        assertTrue(weightAfterRecompute <= 4_096L);
    }

    @Test
    void shouldUseOversizedPlansWithoutRetainingThem() {
        // given
        BlueCachePolicy rejectingPolicy = BlueCachePolicy.builder()
                .conformancePlans(4, 256L)
                .maximumDerivedEntryWeightBytes(256L)
                .build();
        FrozenTypeMatcher rejecting = new FrozenTypeMatcher(null, true, rejectingPolicy);

        // when
        FrozenNode large = value(repeat('x', 2_048));
        boolean matched = rejecting.matchesType(large, large);
        int retainedEntries = rejecting.cacheEntryCount();
        long retainedWeight = rejecting.cacheWeightBytes();

        // then
        assertTrue(matched);
        assertEquals(0, retainedEntries);
        assertEquals(0L, retainedWeight);
    }

    @Test
    void shouldReleaseAcceptedPlansWhenClearingCacheAndAllowRecomputation() {
        // given
        BlueCachePolicy acceptingPolicy = BlueCachePolicy.builder()
                .conformancePlans(4, 8_192L)
                .maximumDerivedEntryWeightBytes(8_192L)
                .build();
        FrozenTypeMatcher accepting = new FrozenTypeMatcher(null, true, acceptingPolicy);

        // when
        boolean initiallyMatched = accepting.matchesType(value("small"), value("small"));
        int entriesBeforeClear = accepting.cacheEntryCount();
        accepting.clearCaches();
        int entriesAfterClear = accepting.cacheEntryCount();
        long weightAfterClear = accepting.cacheWeightBytes();
        boolean recomputed = accepting.matchesType(value("small"), value("small"));

        // then
        assertTrue(initiallyMatched);
        assertTrue(entriesBeforeClear > 0);
        assertEquals(0, entriesAfterClear);
        assertEquals(0L, weightAfterClear);
        assertTrue(recomputed);
    }

    private FrozenNode value(String value) {
        return FrozenNode.fromResolvedNode(new Node().value(value));
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
