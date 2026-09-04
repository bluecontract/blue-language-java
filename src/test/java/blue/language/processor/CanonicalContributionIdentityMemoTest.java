package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CanonicalContributionIdentityMemoTest {

    @Test
    void shouldReuseExactSourceAcrossEquivalentNormalizedPathSets() {
        CanonicalContributionIdentityMemo memo =
                new CanonicalContributionIdentityMemo();
        AtomicInteger resolutions = new AtomicInteger();
        FrozenNode first = source("same");
        FrozenNode equivalent = source("same");

        String firstIdentity = memo.resolve(
                first,
                Arrays.asList("/z", "/a", "/z"),
                Collections.singletonList("/z"),
                () -> identity(resolutions));
        String reusedIdentity = memo.resolve(
                equivalent,
                Arrays.asList("/a", "/z"),
                Collections.singletonList("/z"),
                () -> identity(resolutions));

        assertEquals(firstIdentity, reusedIdentity);
        assertEquals(1, resolutions.get());
        assertEquals(1, memo.size());
    }

    @Test
    void shouldKeepSourceAndPathLanesDistinct() {
        CanonicalContributionIdentityMemo memo =
                new CanonicalContributionIdentityMemo();
        AtomicInteger resolutions = new AtomicInteger();

        memo.resolve(
                source("first"),
                Collections.singletonList("/body"),
                Collections.singletonList("/body"),
                () -> identity(resolutions));
        memo.resolve(
                source("second"),
                Collections.singletonList("/body"),
                Collections.singletonList("/body"),
                () -> identity(resolutions));
        memo.resolve(
                source("first"),
                Collections.singletonList("/body"),
                Collections.<String>emptyList(),
                () -> identity(resolutions));

        assertEquals(3, resolutions.get());
        assertEquals(3, memo.size());
    }

    @Test
    void shouldResolveOneCanonicalIdentityForRepeated512EventContribution() {
        CanonicalContributionIdentityMemo memo =
                new CanonicalContributionIdentityMemo();
        AtomicInteger resolutions = new AtomicInteger();
        List<Node> events = new ArrayList<>(512);
        for (int index = 0; index < 512; index++) {
            events.add(new Node().properties(
                    "eventId",
                    new Node().value(String.format(
                            "finite-%04d", index))));
        }
        FrozenNode contribution = FrozenNode.fromSourceNode(
                new Node().properties(
                        "events", new Node().items(events)));

        memo.resolve(
                contribution,
                Collections.singletonList("/events"),
                Collections.singletonList("/events"),
                () -> identity(resolutions));
        memo.resolve(
                FrozenNode.fromSourceNode(contribution.toNode()),
                Collections.singletonList("/events"),
                Collections.singletonList("/events"),
                () -> identity(resolutions));

        assertEquals(1, resolutions.get());
        assertEquals(1, memo.size());
    }

    @Test
    void shouldNeverCacheAuthoritativeResolutionFailure() {
        CanonicalContributionIdentityMemo memo =
                new CanonicalContributionIdentityMemo();
        AtomicInteger resolutions = new AtomicInteger();

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThrows(
                    IllegalStateException.class,
                    () -> memo.resolve(
                            source("retry"),
                            Collections.<String>emptyList(),
                            Collections.<String>emptyList(),
                            () -> {
                                resolutions.incrementAndGet();
                                throw new IllegalStateException(
                                        "authoritative failure");
                            }));
        }

        assertEquals(2, resolutions.get());
        assertEquals(0, memo.size());
        assertEquals(0L, memo.weightBytes());
    }

    @Test
    void shouldValidateResolverEvenWhenIdentityIsWarm() {
        CanonicalContributionIdentityMemo memo =
                new CanonicalContributionIdentityMemo();
        FrozenNode contribution = source("warm");

        memo.resolve(
                contribution,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                () -> "identity-warm");

        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> memo.resolve(
                        contribution,
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        null));
        assertEquals("authoritativeResolver", failure.getMessage());
    }

    @Test
    void shouldEvictOldestSuccessfulIdentityAtEntryBound() {
        CanonicalContributionIdentityMemo memo =
                new CanonicalContributionIdentityMemo(
                        1,
                        1024L * 1024L,
                        512L * 1024L);
        AtomicInteger resolutions = new AtomicInteger();

        memo.resolve(
                source("first"),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                () -> identity(resolutions));
        memo.resolve(
                source("second"),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                () -> identity(resolutions));
        memo.resolve(
                source("first"),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                () -> identity(resolutions));

        assertEquals(3, resolutions.get());
        assertEquals(1, memo.size());
    }

    @Test
    void shouldEvictAtWeightBoundAndClearRetainedState() {
        FrozenNode first = source("first!");
        FrozenNode second = source("second");
        String identity = "identity-1";
        CanonicalContributionIdentityMemo measurement =
                new CanonicalContributionIdentityMemo();
        measurement.resolve(
                first,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                () -> identity);
        long singleEntryWeight = measurement.weightBytes();
        CanonicalContributionIdentityMemo memo =
                new CanonicalContributionIdentityMemo(
                        10,
                        singleEntryWeight,
                        singleEntryWeight);
        AtomicInteger resolutions = new AtomicInteger();

        memo.resolve(
                first,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                () -> identity(resolutions));
        memo.resolve(
                second,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                () -> identity(resolutions));
        memo.resolve(
                first,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                () -> identity(resolutions));

        assertEquals(3, resolutions.get());
        assertEquals(1, memo.size());
        assertEquals(singleEntryWeight, memo.weightBytes());

        memo.clear();

        assertEquals(0, memo.size());
        assertEquals(0L, memo.weightBytes());
    }

    @Test
    void shouldBypassEntriesAbovePerEntryWeightBound() {
        CanonicalContributionIdentityMemo memo =
                new CanonicalContributionIdentityMemo(10, 1L, 1L);
        AtomicInteger resolutions = new AtomicInteger();
        FrozenNode contribution = source("oversized");

        memo.resolve(
                contribution,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                () -> identity(resolutions));
        memo.resolve(
                contribution,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                () -> identity(resolutions));

        assertEquals(2, resolutions.get());
        assertEquals(0, memo.size());
        assertEquals(0L, memo.weightBytes());
    }

    private FrozenNode source(String value) {
        return FrozenNode.fromSourceNode(
                new Node().properties(
                        "body",
                        new Node().value(value)));
    }

    private String identity(AtomicInteger resolutions) {
        return "identity-" + resolutions.incrementAndGet();
    }
}
