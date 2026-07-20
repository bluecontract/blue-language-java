package blue.language.utils;

import blue.language.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenTypeMatcherCachePolicyTest {

    @Test
    void allMatcherRegionsShareTheConfiguredEntryAndWeightBudget() {
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .conformancePlans(5, 4_096L)
                .maximumDerivedEntryWeightBytes(4_096L)
                .build();
        FrozenTypeMatcher matcher = new FrozenTypeMatcher(null, true, policy);

        for (int index = 0; index < 40; index++) {
            FrozenNode value = value("value-" + index);
            assertTrue(matcher.matchesType(value, value));
            assertTrue(matcher.cacheEntryCount() <= 5);
            assertTrue(matcher.cacheWeightBytes() <= 4_096L);
        }

        assertTrue(matcher.cacheEntryCount() > 0);
        assertTrue(matcher.matchesType(value("value-0"), value("value-0")),
                "an evicted plan must remain safely recomputable");
        assertTrue(matcher.cacheEntryCount() <= 5);
        assertTrue(matcher.cacheWeightBytes() <= 4_096L);
    }

    @Test
    void oversizedPlansAreUsedWithoutBeingRetainedAndClearReleasesAcceptedPlans() {
        BlueCachePolicy rejectingPolicy = BlueCachePolicy.builder()
                .conformancePlans(4, 256L)
                .maximumDerivedEntryWeightBytes(256L)
                .build();
        FrozenTypeMatcher rejecting = new FrozenTypeMatcher(null, true, rejectingPolicy);

        FrozenNode large = value(repeat('x', 2_048));
        assertTrue(rejecting.matchesType(large, large));
        assertEquals(0, rejecting.cacheEntryCount());
        assertEquals(0L, rejecting.cacheWeightBytes());

        BlueCachePolicy acceptingPolicy = BlueCachePolicy.builder()
                .conformancePlans(4, 8_192L)
                .maximumDerivedEntryWeightBytes(8_192L)
                .build();
        FrozenTypeMatcher accepting = new FrozenTypeMatcher(null, true, acceptingPolicy);
        assertTrue(accepting.matchesType(value("small"), value("small")));
        assertTrue(accepting.cacheEntryCount() > 0);

        accepting.clearCaches();

        assertEquals(0, accepting.cacheEntryCount());
        assertEquals(0L, accepting.cacheWeightBytes());
        assertTrue(accepting.matchesType(value("small"), value("small")));
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
