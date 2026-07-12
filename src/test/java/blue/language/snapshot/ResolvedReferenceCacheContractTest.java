package blue.language.snapshot;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.merge.Merger;
import blue.language.merge.Merger.SnapshotResolution;
import blue.language.merge.Merger.VerifiedReferenceResolution;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedReferenceCacheContractTest {

    @Test
    void verifiedEvidenceProducerIsSealed() {
        assertTrue(Modifier.isFinal(Merger.class.getModifiers()),
                "Merger must be final because it issues verified resolution evidence");
    }

    @Test
    void identityEquivalentCanonicalRepresentationsDoNotConflict() {
        Node materializedSubject = new Node().name("Scenario Subject")
                .type(reference("vWaf5a4SM9DLWTVhuqLrj9uihL5TFZfEJUxPu8bRC5m"))
                .properties("identifier", new Node().value("subject-1"));
        String subjectId = new Blue().calculateBlueId(materializedSubject);
        Node referenceHolder = new Node().properties("subject", reference(subjectId));
        Node materializedHolder = new Node().properties("subject", materializedSubject);
        String holderId = new Blue().calculateBlueId(referenceHolder);
        FrozenNode referenced = FrozenNode.fromNode(referenceHolder);
        FrozenNode materialized = FrozenNode.fromNode(materializedHolder);
        ResolvedReferenceCache referenceFirst = new ResolvedReferenceCache();
        ResolvedReferenceCache materializedFirst = new ResolvedReferenceCache();

        assertEquals(holderId, new Blue().calculateBlueId(materializedHolder));
        assertEquals(holderId, referenced.blueId());
        assertEquals(holderId, materialized.blueId());
        assertNotEquals(referenced.resolvedStructuralKey(), materialized.resolvedStructuralKey());

        assertSame(referenced, referenceFirst.putVerifiedCanonical(holderId, referenced));
        assertSame(referenced, referenceFirst.putVerifiedCanonical(holderId, materialized));
        assertSame(referenced,
                referenceFirst.getVerifiedCanonical(holderId).orElseThrow(AssertionError::new));

        assertSame(materialized, materializedFirst.putVerifiedCanonical(holderId, materialized));
        assertSame(materialized, materializedFirst.putVerifiedCanonical(holderId, referenced));
        assertSame(materialized,
                materializedFirst.getVerifiedCanonical(holderId).orElseThrow(AssertionError::new));
        assertEquals(1, referenceFirst.size());
        assertEquals(1, materializedFirst.size());
    }

    @Test
    void unrelatedResolvedContentCannotBeCertified() throws Exception {
        assertArbitrarySnapshotCannotCertifyContent(false);
        assertValidEvidenceWinsConcurrentRaceWithArbitrarySnapshots();
    }

    @Test
    void structuralWarmupCannotChangeVerifiedCacheEligibility() {
        assertArbitrarySnapshotCannotCertifyContent(false);
        assertArbitrarySnapshotCannotCertifyContent(true);
    }

    @Test
    void putVerifiedCanonicalRejectsReferenceOnlyNode() {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        String referenceId = new Blue().calculateBlueId(new Node().value("referenced"));
        FrozenNode reference = FrozenNode.fromNode(new Node().blueId(referenceId));

        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedCanonical(referenceId, reference));
    }

    @Test
    void referenceOnlyCanonicalCannotProduceVerificationEvidence() {
        Node content = new Node().value("value");
        String referenceId = new Blue().calculateBlueId(content);
        Blue blue = new Blue();

        ResolvedSnapshot snapshot = blue.resolveToSnapshot(reference(referenceId));

        assertNull(snapshot.verifiedReferenceResolution());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    @Test
    void referenceOnlyResolvedCannotProduceVerificationEvidence() {
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedSnapshot arbitrary = new ResolvedSnapshot(
                canonicalNode, reference(blueId), blueId);
        Blue blue = new Blue().cacheResolvedSnapshot(arbitrary);

        assertNull(arbitrary.verifiedReferenceResolution());
        assertFalse(blue.cachedResolvedSnapshot(blueId).isPresent());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    @Test
    void putVerifiedCanonicalRejectsMismatchedBlueId() {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("value"));

        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedCanonical("wrong-id", canonical));
    }

    @Test
    void resolverEvidenceCannotCarryMismatchedBlueId() {
        ResolvedSnapshot snapshot = new Blue().resolveToSnapshot(new Node().value("value"));
        VerifiedReferenceResolution verification = snapshot.verifiedReferenceResolution();

        assertNotNull(verification);
        assertEquals(snapshot.blueId(), verification.requestedBlueId());
        assertEquals(verification.canonicalRoot().blueId(), verification.requestedBlueId());
        assertSourceConstructorsArePrivate(VerifiedReferenceResolution.class);
        assertSourceConstructorsArePrivate(SnapshotResolution.class);
        assertNoPublicArbitraryResolutionFactory(Merger.class);
        assertNoPublicArbitraryResolutionFactory(VerifiedReferenceResolution.class);
        assertNoPublicArbitraryResolutionFactory(SnapshotResolution.class);
        assertNoPublicArbitraryResolutionFactory(ResolvedSnapshot.class);
        assertVerifiedCacheAcceptsOnlyEvidence();
    }

    @Test
    void canonicalEntryWhoseComputedBlueIdDiffersFailsDeterministically() {
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode canonical = FrozenNode.fromNode(canonicalNode);
        FrozenNode forgedConflict = FrozenNode.fromUncheckedCanonicalNode(new Node().value("different"));

        cache.putVerifiedCanonical(blueId, canonical);

        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedCanonical(blueId, forgedConflict));
    }

    @Test
    void uncheckedCanonicalNodeCannotEnterVerifiedCache() {
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        assertThrows(IllegalArgumentException.class, () -> cache.putVerifiedCanonical(
                blueId, FrozenNode.fromUncheckedCanonicalNode(canonicalNode)));
        assertFalse(cache.getVerifiedCanonical(blueId).isPresent());
    }

    @Test
    void contextualResolvedNodeCannotProduceVerificationEvidence() {
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode contextual = cache.freezeResolved(canonicalNode);
        ResolvedSnapshot arbitrary = new ResolvedSnapshot(
                FrozenNode.fromNode(canonicalNode), contextual, blueId);

        assertNull(arbitrary.verifiedReferenceResolution());
        assertFalse(cache.getVerifiedResolved(blueId).isPresent());
        assertEquals(0, cache.size());
    }

    @Test
    void validVerifiedCanonicalAndResolvedContentAreReused() {
        ResolvedSnapshot snapshot = new Blue().resolveToSnapshot(new Node().value("value"));
        VerifiedReferenceResolution verification = snapshot.verifiedReferenceResolution();
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        assertNotNull(verification);
        assertSame(verification.canonicalRoot(), cache.putVerifiedCanonical(
                verification.requestedBlueId(), verification.canonicalRoot()));
        assertSame(verification.resolvedRoot(), cache.putVerifiedResolved(verification));
        assertSame(verification.canonicalRoot(), cache.getVerifiedCanonical(
                verification.requestedBlueId()).orElseThrow(AssertionError::new));
        assertSame(verification.resolvedRoot(), cache.getVerifiedResolved(
                verification.requestedBlueId()).orElseThrow(AssertionError::new));
        assertEquals(1, cache.size());
    }

    @Test
    void providerOrProcessorChangeClearsVerifiedEntries() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node().name("Type"));
        String typeId = provider.getBlueIdByName("Type");
        Blue blue = new Blue(provider);

        blue.resolve(new Node().type(new Node().blueId(typeId)));
        assertTrue(blue.resolvedReferenceCacheSize() > 0);

        blue.nodeProvider(new BasicNodeProvider());
        assertEquals(0, blue.resolvedReferenceCacheSize());

        blue.nodeProvider(provider);
        blue.resolve(new Node().type(new Node().blueId(typeId)));
        assertTrue(blue.resolvedReferenceCacheSize() > 0);

        blue.mergingProcessor(blue.getMergingProcessor());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    private void assertArbitrarySnapshotCannotCertifyContent(boolean warmStructuralInterner) {
        Node canonicalNode = new Node().name("Canonical A");
        Node unrelatedNode = new Node().name("Resolved B");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        AtomicInteger fetches = new AtomicInteger();
        NodeProvider provider = requestedBlueId -> {
            fetches.incrementAndGet();
            return blueId.equals(requestedBlueId)
                    ? Collections.singletonList(canonicalNode.clone()) : null;
        };
        Blue blue = new Blue(provider);
        if (warmStructuralInterner) {
            Node warmCanonical = new Node().name("Structural Warmup");
            blue.cacheResolvedSnapshot(new ResolvedSnapshot(
                    warmCanonical, unrelatedNode, new Blue().calculateBlueId(warmCanonical)));
        }

        ResolvedSnapshot arbitrary = new ResolvedSnapshot(canonicalNode, unrelatedNode, blueId);
        blue.cacheResolvedSnapshot(arbitrary);

        assertNull(arbitrary.verifiedReferenceResolution());
        assertEquals(0, blue.resolvedReferenceCacheSize());
        ResolvedSnapshot loaded = blue.loadSnapshot(blueId);
        assertEquals("Canonical A", loaded.resolvedRoot().getName());
        assertEquals(1, fetches.get());
        assertEquals(1, blue.resolvedReferenceCacheSize());
    }

    private void assertValidEvidenceWinsConcurrentRaceWithArbitrarySnapshots() throws Exception {
        Node canonicalNode = new Node().name("Concurrent Canonical");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedSnapshot valid = new Blue().resolveToSnapshot(canonicalNode);
        ResolvedSnapshot invalid = new ResolvedSnapshot(
                canonicalNode, new Node().name("Concurrent Invalid"), blueId);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            for (int round = 0; round < 16; round++) {
                final Blue target = new Blue();
                final CountDownLatch start = new CountDownLatch(1);
                List<Callable<Blue>> work = new ArrayList<>();
                for (int index = 0; index < 24; index++) {
                    ResolvedSnapshot candidate = (index + round) % 2 == 0 ? valid : invalid;
                    work.add(() -> {
                        start.await();
                        return target.cacheResolvedSnapshot(candidate);
                    });
                }
                List<Future<Blue>> futures = new ArrayList<>(work.size());
                for (Callable<Blue> task : work) {
                    futures.add(executor.submit(task));
                }
                start.countDown();
                for (Future<Blue> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }

                ResolvedSnapshot retained = target.cachedResolvedSnapshot(blueId)
                        .orElseThrow(AssertionError::new);
                assertSame(valid, retained);
                assertSame(valid, target.resolveToSnapshot(canonicalNode));
                assertEquals("Concurrent Canonical", retained.resolvedRoot().getName());
                assertEquals(1, target.resolvedSnapshotCacheSize());
                assertEquals(1, target.resolvedReferenceCacheSize());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private void assertSourceConstructorsArePrivate(Class<?> type) {
        int sourceConstructors = 0;
        for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isSynthetic()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()),
                        type.getSimpleName() + " compiler bridge must not be public");
                continue;
            }
            sourceConstructors++;
            assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                    type.getSimpleName() + " constructor must be private");
        }
        assertEquals(1, sourceConstructors,
                type.getSimpleName() + " must have exactly one source constructor");
    }

    private void assertNoPublicArbitraryResolutionFactory(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())
                    || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            int frozenNodeParameters = 0;
            boolean acceptsBlueId = false;
            for (Class<?> parameterType : method.getParameterTypes()) {
                acceptsBlueId |= parameterType == String.class;
                frozenNodeParameters += parameterType == FrozenNode.class ? 1 : 0;
            }
            assertFalse(acceptsBlueId && frozenNodeParameters >= 2,
                    type.getSimpleName() + "." + method.getName()
                            + " must not accept an arbitrary BlueId/canonical/resolved tuple");
        }
    }

    private void assertVerifiedCacheAcceptsOnlyEvidence() {
        int verifiedWrites = 0;
        for (Method method : ResolvedReferenceCache.class.getDeclaredMethods()) {
            if (!"putVerifiedResolved".equals(method.getName())) {
                continue;
            }
            verifiedWrites++;
            assertEquals(1, method.getParameterTypes().length,
                    "verified resolved cache writes must accept one evidence object");
            assertEquals(VerifiedReferenceResolution.class, method.getParameterTypes()[0],
                    "verified resolved cache writes must accept only resolver evidence");
        }
        assertEquals(1, verifiedWrites,
                "there must be exactly one verified resolved cache-write API");
    }
}
