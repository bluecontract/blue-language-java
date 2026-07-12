package blue.language;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootReferenceSnapshotTest {

    @Test
    void rootReferenceSnapshotDoesNotCertifyUnmaterializedContent() {
        Fixture fixture = new Fixture();

        ResolvedSnapshot snapshot = fixture.blue.resolveToSnapshot(reference(fixture.subjectId));

        assertTrue(snapshot.frozenCanonicalRoot().isReferenceOnly());
        assertTrue(snapshot.frozenResolvedRoot().isReferenceOnly());
        assertEquals(0, fixture.provider.fetches(fixture.subjectId));
        assertFalse(fixture.blue.cachedResolvedSnapshot(fixture.subjectId).isPresent());
        assertEquals(0, fixture.blue.resolvedReferenceCacheSize());
    }

    @Test
    void typedUseAfterRootReferenceSnapshotFetchesAndSucceeds() {
        Fixture fixture = new Fixture();
        fixture.blue.resolveToSnapshot(reference(fixture.subjectId));

        Node resolved = fixture.blue.resolve(fixture.typedUse());

        assertEquals("subject-1", resolved.getAsText("/subject/identifier"));
        assertEquals(1, fixture.provider.fetches(fixture.subjectId));
    }

    @Test
    void missingTypedUseAfterRootReferenceSnapshotFailsDeterministically() {
        Fixture fixture = new Fixture();
        String missingId = fixture.blue.calculateBlueId(new Node().name("Missing Subject"));
        fixture.blue.resolveToSnapshot(reference(missingId));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> fixture.blue.resolve(fixture.typedUse(missingId)));

        assertTrue(messageChain(failure).contains(missingId));
        assertEquals(1, fixture.provider.fetches(missingId));
    }

    @Test
    void rootReferenceSnapshotNeverCausesStackOverflow() {
        Fixture fixture = new Fixture();
        fixture.blue.resolveToSnapshot(reference(fixture.subjectId));

        assertDoesNotThrow(() -> fixture.blue.resolve(fixture.typedUse()));
    }

    @Test
    void loadSnapshotAfterRootReferenceStillMaterializesWhenRequired() {
        Fixture fixture = new Fixture();
        fixture.blue.resolveToSnapshot(reference(fixture.subjectId));

        ResolvedSnapshot loaded = fixture.blue.loadSnapshot(fixture.subjectId);

        assertEquals("subject-1", loaded.resolvedRoot().getAsText("/identifier"));
        assertEquals(1, fixture.provider.fetches(fixture.subjectId));
    }

    @Test
    void warmVerifiedContentAvoidsASecondProviderFetch() {
        Fixture fixture = new Fixture();
        fixture.blue.resolveToSnapshot(reference(fixture.subjectId));

        fixture.blue.resolve(fixture.typedUse());
        fixture.blue.resolve(fixture.typedUse());

        assertEquals(1, fixture.provider.fetches(fixture.subjectId));
    }

    @Test
    void parallelTypedUseAfterRootReferenceSnapshotDoesNotRecurseOrCrossContaminate() throws Exception {
        Fixture fixture = new Fixture();
        fixture.blue.resolveToSnapshot(reference(fixture.subjectId));
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            @SuppressWarnings("unchecked")
            Future<Node>[] futures = new Future[workers];
            for (int index = 0; index < workers; index++) {
                futures[index] = executor.submit(() -> {
                    start.await();
                    return fixture.blue.resolve(fixture.typedUse());
                });
            }
            start.countDown();
            for (Future<Node> future : futures) {
                assertEquals("subject-1", future.get(10, TimeUnit.SECONDS)
                        .getAsText("/subject/identifier"));
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, fixture.provider.fetches(fixture.subjectId));
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
        private final BasicNodeProvider delegate = new BasicNodeProvider();
        private final CountingProvider provider;
        private final Blue blue;
        private final String subjectId;
        private final String holderId;

        private Fixture() {
            delegate.addSingleNodes(new Node().name("Base Subject"));
            String baseId = delegate.getBlueIdByName("Base Subject");
            delegate.addSingleNodes(new Node().name("Subject")
                    .type(reference(baseId))
                    .properties("identifier", new Node().value("subject-1")));
            subjectId = delegate.getBlueIdByName("Subject");
            delegate.addSingleNodes(new Node().name("Holder")
                    .properties("subject", new Node().type(reference(baseId))
                            .schema(new Schema().required(true))));
            holderId = delegate.getBlueIdByName("Holder");
            provider = new CountingProvider(delegate);
            blue = new Blue(provider);
        }

        private Node typedUse() {
            return typedUse(subjectId);
        }

        private Node typedUse(String id) {
            return new Node().type(reference(holderId)).properties("subject", reference(id));
        }
    }

    private static final class CountingProvider implements NodeProvider {
        private final NodeProvider delegate;
        private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        private CountingProvider(NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            counts.computeIfAbsent(blueId, ignored -> new AtomicInteger()).incrementAndGet();
            return delegate.fetchByBlueId(blueId);
        }

        private int fetches(String blueId) {
            AtomicInteger count = counts.get(blueId);
            return count == null ? 0 : count.get();
        }
    }
}
