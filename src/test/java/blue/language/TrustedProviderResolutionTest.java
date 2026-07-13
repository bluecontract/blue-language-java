package blue.language;

import blue.language.model.Node;
import blue.language.provider.SequentialNodeProvider;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.limits.PathLimits;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedProviderResolutionTest {

    @Test
    void trustedNonDirectTypeResolvesWithoutPlainBlueIdCheck() {
        Fixture fixture = new Fixture();

        Node resolved = fixture.blue.resolve(fixture.instance());

        assertEquals("trusted", resolved.getAsText("/fixed"));
    }

    @Test
    void trustedNonDirectTypeDoesNotPopulateVerifiedReferenceCache() {
        Fixture fixture = new Fixture();

        blue.language.snapshot.ResolvedSnapshot snapshot =
                fixture.blue.resolveToSnapshot(fixture.instance());

        assertEquals(0, fixture.blue.resolvedReferenceCacheSize());
        assertNull(snapshot.verifiedReferenceResolution());
        assertEquals(fixture.requestedBlueId,
                snapshot.frozenCanonicalRoot().getType().getReferenceBlueId());
    }

    @Test
    void trustedNonDirectTypeFetchesOnceWithinOneResolution() {
        Fixture fixture = new Fixture();
        Node document = new Node().properties(
                "left", fixture.instance(),
                "right", fixture.instance());

        Node resolved = fixture.blue.resolve(document);

        assertEquals("trusted", resolved.getAsText("/left/fixed"));
        assertEquals("trusted", resolved.getAsText("/right/fixed"));
        assertEquals(1, fixture.fetches.get());
    }

    @Test
    void trustedNonDirectTypeMayRefetchAcrossIndependentResolutions() {
        Fixture fixture = new Fixture();

        fixture.blue.resolve(fixture.instance());
        fixture.blue.resolve(fixture.instance());

        assertEquals(2, fixture.fetches.get());
        assertEquals(0, fixture.blue.resolvedReferenceCacheSize());
    }

    @Test
    void plainProviderMismatchStillFailsAsProviderBlueIdMismatch() {
        Fixture fixture = new Fixture(false);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> fixture.blue.resolve(fixture.instance()));

        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertTrue(messageChain(failure).contains(fixture.requestedBlueId));
    }

    @Test
    void trustedMissDoesNotTransferTrustToPlainFallback() {
        Fixture fixture = new Fixture();
        AtomicInteger trustedFetches = new AtomicInteger();
        AtomicInteger plainFetches = new AtomicInteger();
        NodeProvider trustedMiss = blueId -> {
            trustedFetches.incrementAndGet();
            return null;
        };
        NodeProvider plainMismatch = blueId -> {
            plainFetches.incrementAndGet();
            return Collections.singletonList(fixture.trustedType.clone());
        };
        Blue blue = new Blue(new SequentialNodeProvider(
                NodeProviderWrapper.unverified(trustedMiss), plainMismatch));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(fixture.instance()));

        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertEquals(1, trustedFetches.get());
        assertEquals(1, plainFetches.get());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    @Test
    void plainWinnerBeforeTrustedProviderStillRequiresVerification() {
        Fixture fixture = new Fixture();
        AtomicInteger plainFetches = new AtomicInteger();
        AtomicInteger trustedFetches = new AtomicInteger();
        NodeProvider plainMismatch = blueId -> {
            plainFetches.incrementAndGet();
            return Collections.singletonList(fixture.trustedType.clone());
        };
        NodeProvider trustedFallback = blueId -> {
            trustedFetches.incrementAndGet();
            return Collections.singletonList(fixture.trustedType.clone());
        };
        Blue blue = new Blue(new SequentialNodeProvider(
                plainMismatch, NodeProviderWrapper.unverified(trustedFallback)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(fixture.instance()));

        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertEquals(1, plainFetches.get());
        assertEquals(0, trustedFetches.get());
    }

    @Test
    void verifiedEntryWinsAfterPriorTrustedResolution() {
        Fixture fixture = new Fixture();
        fixture.blue.resolve(fixture.instance());
        AtomicInteger verifiedFetches = new AtomicInteger();
        NodeProvider verifiedProvider = blueId -> {
            verifiedFetches.incrementAndGet();
            return Collections.singletonList(fixture.requestedType.clone());
        };

        fixture.blue.nodeProvider(verifiedProvider);
        Node first = fixture.blue.resolve(fixture.instance());
        Node second = fixture.blue.resolve(fixture.instance());

        assertEquals("verified", first.getAsText("/fixed"));
        assertEquals("verified", second.getAsText("/fixed"));
        assertEquals(1, verifiedFetches.get());
        assertEquals(1, fixture.blue.resolvedReferenceCacheSize());
    }

    @Test
    void limitedTrustedResolutionNeverPromotesSharedCacheEntry() {
        Fixture fixture = new Fixture();

        fixture.blue.resolve(fixture.instance(), PathLimits.withMaxDepth(2));

        assertEquals(1, fixture.fetches.get());
        assertEquals(0, fixture.blue.resolvedReferenceCacheSize());
    }

    @Test
    void concurrentTrustedAndPlainLookupsDoNotShareTrust() throws Exception {
        Fixture fixture = new Fixture();
        ThreadLocal<Boolean> useTrustedResult = new ThreadLocal<>();
        AtomicInteger plainFetches = new AtomicInteger();
        NodeProvider conditionalTrusted = blueId -> {
            return Boolean.TRUE.equals(useTrustedResult.get())
                    ? Collections.singletonList(fixture.trustedType.clone())
                    : null;
        };
        NodeProvider plainMismatch = blueId -> {
            plainFetches.incrementAndGet();
            return Collections.singletonList(fixture.trustedType.clone());
        };
        Blue blue = new Blue(new SequentialNodeProvider(
                NodeProviderWrapper.unverified(conditionalTrusted), plainMismatch));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Node> trusted = executor.submit(() -> {
                useTrustedResult.set(true);
                start.await();
                return blue.resolve(fixture.instance());
            });
            Future<Node> plain = executor.submit(() -> {
                useTrustedResult.set(false);
                start.await();
                return blue.resolve(fixture.instance());
            });
            start.countDown();

            assertEquals("trusted", trusted.get(10, TimeUnit.SECONDS).getAsText("/fixed"));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> plain.get(10, TimeUnit.SECONDS));
            assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                    BlueLanguageErrorClassifier.classify(failure.getCause()));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, plainFetches.get());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    private static String messageChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            result.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return result.toString();
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class Fixture {
        private final Node requestedType = new Node().name("Requested Type")
                .properties("fixed", new Node().value("verified"));
        private final Node trustedType = new Node().name("Trusted Source Type")
                .properties("fixed", new Node().value("trusted"));
        private final String requestedBlueId = new Blue().calculateBlueId(requestedType);
        private final AtomicInteger fetches = new AtomicInteger();
        private final Blue blue;

        private Fixture() {
            this(true);
        }

        private Fixture(boolean trusted) {
            NodeProvider provider = blueId -> {
                fetches.incrementAndGet();
                return requestedBlueId.equals(blueId)
                        ? Collections.singletonList(trustedType.clone())
                        : null;
            };
            blue = new Blue(trusted ? NodeProviderWrapper.unverified(provider) : provider);
        }

        private Node instance() {
            return new Node().type(reference(requestedBlueId));
        }
    }
}
