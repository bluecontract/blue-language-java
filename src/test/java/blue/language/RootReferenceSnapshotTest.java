package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.merge.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootReferenceSnapshotTest {

    private static final String SCENARIO_BASE_SUBJECT_ID =
            "vWaf5a4SM9DLWTVhuqLrj9uihL5TFZfEJUxPu8bRC5m";
    private static final String SCENARIO_SUBJECT_ID =
            "EZjMCm64sChaawC4hqoiANWphWxW3mzmWaDUEwauBiW5";

    @Test
    void shouldKeepNestedReferenceThenMaterializedSnapshotsIndependent() {
        // given
        boolean referenceFirst = true;

        // when
        NestedSnapshotObservation observation =
                observeNestedSnapshots(referenceFirst);

        // then
        assertNestedSnapshotsRemainIndependent(observation);
    }

    @Test
    void shouldKeepNestedMaterializedThenReferenceSnapshotsIndependent() {
        // given
        boolean referenceFirst = false;

        // when
        NestedSnapshotObservation observation =
                observeNestedSnapshots(referenceFirst);

        // then
        assertNestedSnapshotsRemainIndependent(observation);
    }

    @Test
    void shouldRetainTwoExactRepresentationsAndOneVerifiedIdentityForEquivalentSnapshots() {
        // given
        Node subject = new Node().name("Cache Cardinality Subject")
                .properties("identifier", new Node().value("subject-1"));
        String subjectId = new Blue().calculateBlueId(subject);
        Node referenceHolder = new Node().properties("subject", reference(subjectId));
        Node materializedHolder = new Node().properties("subject", subject);
        Blue blue = new Blue();

        ResolvedSnapshot referenced = blue.resolveToSnapshot(referenceHolder);
        // when
        ResolvedSnapshot materialized = blue.resolveToSnapshot(materializedHolder);

        // then
        assertNotSame(referenced, materialized);
        assertEquals(referenced.blueId(), materialized.blueId());
        assertEquals(2, blue.resolvedSnapshotCacheSize());
        assertEquals(1, blue.resolvedReferenceCacheSize());
        assertTrue(blue.cachedResolvedSnapshot(referenced.blueId()).isPresent());
    }

    @Test
    void shouldNotCertifyUnmaterializedContentFromRootReferenceSnapshot() {
        // given
        Fixture fixture = new Fixture();

        // when
        ResolvedSnapshot snapshot = fixture.blue.resolveToSnapshot(reference(fixture.subjectId));

        // then
        assertTrue(snapshot.frozenCanonicalRoot().isReferenceOnly());
        assertTrue(snapshot.frozenResolvedRoot().isReferenceOnly());
        assertEquals(0, fixture.provider.fetches(fixture.subjectId));
        assertFalse(fixture.blue.cachedResolvedSnapshot(fixture.subjectId).isPresent());
        assertEquals(0, fixture.blue.resolvedReferenceCacheSize());
    }

    @Test
    void shouldFetchAndResolveTypedUseAfterRootReferenceSnapshot() {
        // given
        Fixture fixture = new Fixture();
        fixture.blue.resolveToSnapshot(reference(fixture.subjectId));

        // when
        Node resolved = fixture.blue.resolve(fixture.typedUse());

        // then
        assertEquals("subject-1", resolved.getAsText("/subject/identifier"));
        assertEquals(1, fixture.provider.fetches(fixture.subjectId));
    }

    @Test
    void shouldFailMissingTypedUseDeterministicallyAfterRootReferenceSnapshot() {
        // given
        Fixture fixture = new Fixture();
        String missingId = fixture.blue.calculateBlueId(new Node().name("Missing Subject"));
        fixture.blue.resolveToSnapshot(reference(missingId));

        // when
        IllegalArgumentException failure = captureFailure(
                () -> fixture.blue.resolve(fixture.typedUse(missingId)));
        int fetchCount = fixture.provider.fetches(missingId);

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(messageChain(failure).contains(missingId));
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldAvoidStackOverflowAfterRootReferenceSnapshot() {
        // given
        Fixture fixture = new Fixture();
        // when
        fixture.blue.resolveToSnapshot(reference(fixture.subjectId));

        Node resolved = fixture.blue.resolve(fixture.typedUse());

        // then
        assertEquals("subject-1", resolved.getAsText("/subject/identifier"));
    }

    @Test
    void shouldMaterializeLoadSnapshotAfterRootReferenceWhenRequired() {
        // given
        Fixture fixture = new Fixture();
        fixture.blue.resolveToSnapshot(reference(fixture.subjectId));

        // when
        ResolvedSnapshot loaded = fixture.blue.loadSnapshot(fixture.subjectId);

        // then
        assertEquals("subject-1", loaded.resolvedRoot().getAsText("/identifier"));
        assertEquals(1, fixture.provider.fetches(fixture.subjectId));
    }

    @Test
    void shouldAvoidSecondProviderFetchForWarmVerifiedContent() {
        // given
        Fixture fixture = new Fixture();
        fixture.blue.resolveToSnapshot(reference(fixture.subjectId));

        fixture.blue.resolve(fixture.typedUse());
        // when
        fixture.blue.resolve(fixture.typedUse());

        // then
        assertEquals(1, fixture.provider.fetches(fixture.subjectId));
    }

    @Test
    void shouldNotRecurseOrCrossContaminateParallelTypedUseAfterRootReferenceSnapshot()
            throws Exception {
        // given
        Fixture fixture = new Fixture();
        fixture.blue.resolveToSnapshot(reference(fixture.subjectId));
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);

        // when
        java.util.ArrayList<String> identifiers = new java.util.ArrayList<>();
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
                identifiers.add(future.get(10, TimeUnit.SECONDS)
                        .getAsText("/subject/identifier"));
            }
        } finally {
            executor.shutdownNow();
        }
        int fetchCount = fixture.provider.fetches(fixture.subjectId);

        // then
        assertEquals(workers, identifiers.size());
        for (String identifier : identifiers) {
            assertEquals("subject-1", identifier);
        }
        assertEquals(1, fetchCount);
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

    private NestedSnapshotObservation observeNestedSnapshots(boolean referenceFirst) {
        Node baseSubject = new Node().name("Scenario Base Subject");
        Node materializedSubject = new Node().name("Scenario Subject")
                .type(reference(SCENARIO_BASE_SUBJECT_ID))
                .properties("identifier", new Node().value("subject-1"));
        BasicNodeProvider provider = new BasicNodeProvider(baseSubject, materializedSubject);
        Blue blue = new Blue(provider);
        Node referenceHolder = new Node().properties("subject", reference(SCENARIO_SUBJECT_ID));
        Node materializedHolder = new Node().properties("subject", materializedSubject.clone());

        String actualBaseSubjectId = provider.getBlueIdByName("Scenario Base Subject");
        String actualSubjectId = provider.getBlueIdByName("Scenario Subject");
        String holderBlueId = blue.calculateBlueId(referenceHolder);
        String materializedHolderBlueId = blue.calculateBlueId(materializedHolder);

        ResolvedSnapshot referenced;
        ResolvedSnapshot materialized;
        if (referenceFirst) {
            referenced = blue.resolveToSnapshot(referenceHolder);
            materialized = blue.resolveToSnapshot(materializedHolder);
        } else {
            materialized = blue.resolveToSnapshot(materializedHolder);
            referenced = blue.resolveToSnapshot(referenceHolder);
        }

        return new NestedSnapshotObservation(
                actualBaseSubjectId,
                actualSubjectId,
                holderBlueId,
                materializedHolderBlueId,
                referenced,
                materialized,
                blue.resolvedSnapshotCacheSize());
    }

    private static void assertNestedSnapshotsRemainIndependent(
            NestedSnapshotObservation observation) {
        assertEquals(SCENARIO_BASE_SUBJECT_ID, observation.actualBaseSubjectId);
        assertEquals(SCENARIO_SUBJECT_ID, observation.actualSubjectId);
        assertEquals(observation.holderBlueId, observation.materializedHolderBlueId);
        assertEquals(observation.holderBlueId, observation.referenced.blueId());
        assertEquals(observation.holderBlueId, observation.materialized.blueId());
        assertNotSame(observation.referenced, observation.materialized);
        assertTrue(observation.referenced.frozenCanonicalRoot()
                .property("subject").isReferenceOnly());
        assertEquals(SCENARIO_SUBJECT_ID,
                observation.referenced.frozenCanonicalRoot()
                        .property("subject").getReferenceBlueId());
        assertFalse(observation.materialized.frozenCanonicalRoot()
                .property("subject").isReferenceOnly());
        assertEquals("Scenario Subject",
                observation.materialized.frozenCanonicalRoot()
                        .property("subject").getName());
        assertEquals(SCENARIO_SUBJECT_ID,
                observation.referenced.frozenResolvedRoot()
                        .property("subject").getReferenceBlueId());
        assertNull(observation.materialized.frozenResolvedRoot()
                .property("subject").getReferenceBlueId());
        assertEquals("subject-1", observation.materialized.resolvedRoot()
                .getAsText("/subject/identifier"));
        assertEquals(2, observation.snapshotCacheSize);
    }

    private static final class NestedSnapshotObservation {
        private final String actualBaseSubjectId;
        private final String actualSubjectId;
        private final String holderBlueId;
        private final String materializedHolderBlueId;
        private final ResolvedSnapshot referenced;
        private final ResolvedSnapshot materialized;
        private final int snapshotCacheSize;

        private NestedSnapshotObservation(
                String actualBaseSubjectId,
                String actualSubjectId,
                String holderBlueId,
                String materializedHolderBlueId,
                ResolvedSnapshot referenced,
                ResolvedSnapshot materialized,
                int snapshotCacheSize) {
            this.actualBaseSubjectId = actualBaseSubjectId;
            this.actualSubjectId = actualSubjectId;
            this.holderBlueId = holderBlueId;
            this.materializedHolderBlueId = materializedHolderBlueId;
            this.referenced = referenced;
            this.materialized = materialized;
            this.snapshotCacheSize = snapshotCacheSize;
        }
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
