package blue.language.snapshot;

import blue.language.Blue;
import blue.language.BlueCachePolicy;
import blue.language.NodeProvider;
import blue.language.merge.Merger;
import blue.language.merge.Merger.SnapshotResolution;
import blue.language.merge.Merger.VerifiedReferenceResolution;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void frozenCanonicalTracksNestedCyclicSetReferencesWithoutChangingIdentity() {
        String cyclicMemberId = "ENCwyUPUcBhZSYt7ho4Hyjm6iPGC1JrqdBhvJRFPgwFz#0";
        Node ordinary = new Node().properties("nested", new Node().value("value"));
        Node recursive = ordinary.clone().properties("typed",
                new Node().type(new Node().blueId(cyclicMemberId)));

        FrozenNode frozenOrdinary = FrozenNode.fromNode(ordinary);
        FrozenNode frozenRecursive = FrozenNode.fromNode(recursive);

        assertFalse(frozenOrdinary.containsCyclicSetReference());
        assertTrue(frozenRecursive.containsCyclicSetReference());
        assertEquals(new Blue().calculateBlueId(recursive), frozenRecursive.blueId());
        assertTrue(frozenOrdinary.withProperty("typed",
                FrozenNode.fromNode(new Node().type(new Node().blueId(cyclicMemberId))))
                .containsCyclicSetReference());
    }

    @Test
    void frozenNodeDistinguishesNestedTypedObjectsFromSafeTypeRoots() {
        FrozenNode nestedTypedObject = FrozenNode.fromResolvedNode(new Node()
                .properties("branch", new Node().type(reference("branch-type"))
                        .properties("declared", new Node().type("Text"))));
        FrozenNode typedRoot = FrozenNode.fromResolvedNode(new Node()
                .type(reference("parent-type"))
                .properties("declared", new Node().schema(new Schema().required(true))));
        FrozenNode untypedFixedObject = FrozenNode.fromResolvedNode(new Node()
                .properties("branch", new Node()
                        .properties("fixed", new Node().value("value"))));

        assertTrue(nestedTypedObject.containsNestedTypedObjectPayload());
        assertFalse(typedRoot.containsNestedTypedObjectPayload());
        assertFalse(untypedFixedObject.containsNestedTypedObjectPayload());
    }

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
    void transientChildReadsParentButKeepsNewEntriesAndGraphNodesLocal() {
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();
        ResolvedReferenceCache sibling = parent.transientChild();
        FrozenNode published = parent.freezeResolved(new Node().value("published"));
        FrozenNode local = child.freezeResolved(new Node().value("local"));

        assertSame(published, child.freezeResolved(new Node().value("published")));
        assertSame(local, child.freezeResolved(new Node().value("local")));
        assertEquals(1, parent.resolvedGraphSize());
        assertEquals(1, child.resolvedGraphSize());
        assertNotEquals(local, sibling.freezeResolved(new Node().value("local")));
        assertEquals(1, parent.resolvedGraphSize());
    }

    @Test
    void transientChildKeepsLocalFirstWinsIdentityAfterParentPublishesEquivalentContent() {
        Node materializedSubject = new Node().name("Scenario Subject")
                .type(reference("vWaf5a4SM9DLWTVhuqLrj9uihL5TFZfEJUxPu8bRC5m"))
                .properties("identifier", new Node().value("subject-1"));
        String subjectId = new Blue().calculateBlueId(materializedSubject);
        FrozenNode referenced = FrozenNode.fromNode(new Node().properties(
                "subject", reference(subjectId)));
        FrozenNode materialized = FrozenNode.fromNode(new Node().properties(
                "subject", materializedSubject));
        String holderId = referenced.blueId();
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();

        assertSame(referenced, child.putVerifiedCanonical(holderId, referenced));
        assertSame(materialized, parent.putVerifiedCanonical(holderId, materialized));
        assertSame(referenced, child.putVerifiedCanonical(holderId, materialized));
        assertSame(referenced,
                child.getVerifiedCanonical(holderId).orElseThrow(AssertionError::new));

        FrozenNode localGraph = child.freezeResolved(new Node().value("same graph"));
        parent.freezeResolved(new Node().value("same graph"));
        assertSame(localGraph, child.freezeResolved(new Node().value("same graph")));
    }

    @Test
    void promotionTraversesInheritedCanonicalEntriesToReachLocalDependencies() {
        Node nestedContent = new Node().value("nested");
        ResolvedSnapshot nestedSnapshot = new Blue().resolveToSnapshot(nestedContent);
        String nestedId = nestedSnapshot.blueId();
        Node holderContent = new Node().properties("nested", reference(nestedId));
        FrozenNode holderCanonical = FrozenNode.fromNode(holderContent);
        String holderId = holderCanonical.blueId();
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();

        parent.putVerifiedCanonical(holderId, holderCanonical);
        child.putVerifiedResolved(nestedSnapshot.verifiedReferenceResolution());

        child.promoteReferencesReachableFrom(FrozenNode.fromNode(reference(holderId)));

        assertSame(nestedSnapshot.frozenCanonicalRoot(),
                parent.getVerifiedCanonical(nestedId).orElseThrow(AssertionError::new));
        assertSame(nestedSnapshot.frozenResolvedRoot(),
                parent.getVerifiedResolved(nestedId).orElseThrow(AssertionError::new));
    }

    @Test
    void concurrentCanonicalMissesShareOneProviderLoad() throws Exception {
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("single-flight"));
        String blueId = canonical.blueId();
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<FrozenNode>> lookups = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                lookups.add(executor.submit(() -> cache.getOrLoadVerifiedCanonical(
                        blueId,
                        () -> {
                            loads.incrementAndGet();
                            loaderEntered.countDown();
                            awaitUnchecked(releaseLoader);
                            return canonical;
                        })));
            }

            assertTrue(loaderEntered.await(5, TimeUnit.SECONDS));
            releaseLoader.countDown();

            for (Future<FrozenNode> lookup : lookups) {
                assertSame(canonical, lookup.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get());
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void publishedEntryAfterOwnedFlightInstallCompletesWaitingLookupWithoutProviderLoad() throws Exception {
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("published-during-flight"));
        String blueId = canonical.blueId();
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        CountDownLatch ownerInstalled = new CountDownLatch(1);
        CountDownLatch waiterAwaiting = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicBoolean blockFirstOwner = new AtomicBoolean(true);
        AtomicInteger loads = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ResolvedReferenceCache.setCanonicalLoadObserverForTesting(installedBlueId -> {
            if (blueId.equals(installedBlueId) && blockFirstOwner.compareAndSet(true, false)) {
                ownerInstalled.countDown();
                awaitUnchecked(releaseOwner);
            }
        });
        ResolvedReferenceCache.setCanonicalLoadWaitObserverForTesting(waitingBlueId -> {
            if (blueId.equals(waitingBlueId)) {
                waiterAwaiting.countDown();
            }
        });
        try {
            Future<FrozenNode> owner = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> {
                        loads.incrementAndGet();
                        return canonical;
                    }));
            assertTrue(ownerInstalled.await(5, TimeUnit.SECONDS));
            Future<FrozenNode> waiter = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> {
                        loads.incrementAndGet();
                        return canonical;
                    }));
            assertTrue(waiterAwaiting.await(5, TimeUnit.SECONDS));

            assertSame(canonical, cache.putVerifiedCanonical(blueId, canonical));
            releaseOwner.countDown();

            assertSame(canonical, owner.get(5, TimeUnit.SECONDS));
            assertSame(canonical, waiter.get(5, TimeUnit.SECONDS));
            assertEquals(0, loads.get());
        } finally {
            ResolvedReferenceCache.setCanonicalLoadWaitObserverForTesting(null);
            ResolvedReferenceCache.setCanonicalLoadObserverForTesting(null);
            releaseOwner.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void generationChangeAfterOwnedFlightInstallReleasesWaitingLookup() throws Exception {
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("generation-during-flight"));
        String blueId = canonical.blueId();
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        CountDownLatch ownerInstalled = new CountDownLatch(1);
        CountDownLatch waiterAwaiting = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicBoolean blockFirstOwner = new AtomicBoolean(true);
        AtomicInteger loads = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ResolvedReferenceCache.setCanonicalLoadObserverForTesting(installedBlueId -> {
            if (blueId.equals(installedBlueId) && blockFirstOwner.compareAndSet(true, false)) {
                ownerInstalled.countDown();
                awaitUnchecked(releaseOwner);
            }
        });
        ResolvedReferenceCache.setCanonicalLoadWaitObserverForTesting(waitingBlueId -> {
            if (blueId.equals(waitingBlueId)) {
                waiterAwaiting.countDown();
            }
        });
        try {
            Future<FrozenNode> owner = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> {
                        loads.incrementAndGet();
                        return canonical;
                    }));
            assertTrue(ownerInstalled.await(5, TimeUnit.SECONDS));
            Future<FrozenNode> waiter = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> {
                        loads.incrementAndGet();
                        return canonical;
                    }));
            assertTrue(waiterAwaiting.await(5, TimeUnit.SECONDS));

            cache.clear();
            releaseOwner.countDown();

            assertSame(canonical, owner.get(5, TimeUnit.SECONDS));
            assertSame(canonical, waiter.get(5, TimeUnit.SECONDS));
            assertSame(canonical,
                    cache.getVerifiedCanonical(blueId).orElseThrow(AssertionError::new));
            assertTrue(loads.get() >= 1);
        } finally {
            ResolvedReferenceCache.setCanonicalLoadWaitObserverForTesting(null);
            ResolvedReferenceCache.setCanonicalLoadObserverForTesting(null);
            releaseOwner.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void providerLoadDoesNotHoldTheLegacyCollisionStripe() throws Exception {
        FrozenNode[] collision = canonicalNodesWhoseBlueIdsSharedLegacyStripe();
        FrozenNode first = collision[0];
        FrozenNode second = collision[1];
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FrozenNode> firstLookup = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(first.blueId(), () -> {
                        Future<FrozenNode> nested = executor.submit(() ->
                                cache.getOrLoadVerifiedCanonical(
                                        second.blueId(), () -> second));
                        try {
                            assertSame(second, nested.get(2, TimeUnit.SECONDS));
                        } catch (Exception failure) {
                            throw new IllegalStateException(
                                    "colliding provider lookup could not complete", failure);
                        }
                        return first;
                    }));

            assertSame(first, firstLookup.get(5, TimeUnit.SECONDS));
            assertSame(second,
                    cache.getVerifiedCanonical(second.blueId())
                            .orElseThrow(AssertionError::new));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void clearStartsANewGenerationLoadWithoutWaitingForTheOldProvider() throws Exception {
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("generation-flight"));
        String blueId = canonical.blueId();
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        CountDownLatch oldLoaderEntered = new CountDownLatch(1);
        CountDownLatch releaseOldLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FrozenNode> oldLookup = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> {
                        oldLoaderEntered.countDown();
                        awaitUnchecked(releaseOldLoader);
                        return canonical;
                    }));
            assertTrue(oldLoaderEntered.await(5, TimeUnit.SECONDS));

            cache.clear();
            Future<FrozenNode> newLookup = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> canonical));

            assertSame(canonical, newLookup.get(2, TimeUnit.SECONDS));
            releaseOldLoader.countDown();
            assertSame(canonical, oldLookup.get(5, TimeUnit.SECONDS));
            assertSame(canonical,
                    cache.getVerifiedCanonical(blueId).orElseThrow(AssertionError::new));
        } finally {
            releaseOldLoader.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void recursiveCanonicalLoadsFailDeterministicallyAndRemainRetryable() {
        FrozenNode first = FrozenNode.fromNode(new Node().value("recursive-first"));
        FrozenNode second = FrozenNode.fromNode(new Node().value("recursive-second"));
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        IllegalStateException direct = assertThrows(IllegalStateException.class,
                () -> cache.getOrLoadVerifiedCanonical(first.blueId(), () ->
                        cache.getOrLoadVerifiedCanonical(first.blueId(), () -> first)));
        assertEquals("Recursive verified reference load: " + first.blueId(),
                direct.getMessage());

        IllegalStateException indirect = assertThrows(IllegalStateException.class,
                () -> cache.getOrLoadVerifiedCanonical(first.blueId(), () ->
                        cache.getOrLoadVerifiedCanonical(second.blueId(), () ->
                                cache.getOrLoadVerifiedCanonical(
                                        first.blueId(), () -> first))));
        assertEquals("Recursive verified reference load: " + first.blueId(),
                indirect.getMessage());

        IllegalStateException acrossClear = assertThrows(IllegalStateException.class,
                () -> cache.getOrLoadVerifiedCanonical(first.blueId(), () -> {
                    cache.clear();
                    return cache.getOrLoadVerifiedCanonical(first.blueId(), () -> first);
                }));
        assertEquals("Recursive verified reference load: " + first.blueId(),
                acrossClear.getMessage());
        assertSame(first,
                cache.getOrLoadVerifiedCanonical(first.blueId(), () -> first));
    }

    @Test
    void closeDuringProviderLoadDoesNotDeadlockOrPublishLateContent() throws Exception {
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("closing-flight"));
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<FrozenNode> lookup = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(canonical.blueId(), () -> {
                        loaderEntered.countDown();
                        awaitUnchecked(releaseLoader);
                        return canonical;
                    }));
            assertTrue(loaderEntered.await(5, TimeUnit.SECONDS));

            cache.close();
            releaseLoader.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> lookup.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals("Resolved reference cache is closed",
                    failure.getCause().getMessage());
            assertEquals(0, cache.cacheStats().verifiedEntries());
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void parentInvalidationClearsAStaleChildAndPreventsOldEvidencePromotion() {
        ResolvedSnapshot verified = new Blue().resolveToSnapshot(new Node().value("verified"));
        VerifiedReferenceResolution evidence = verified.verifiedReferenceResolution();
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();

        child.putVerifiedResolved(evidence);
        child.freezeResolved(new Node().value("local graph"));
        assertEquals(1, child.size());
        assertEquals(1, child.resolvedGraphSize());

        parent.clear();

        assertFalse(child.isCurrentGeneration());
        ResolvedReferenceCache staleFork = child.forkTransient();
        assertFalse(staleFork.isCurrentGeneration(),
                "forking must preserve the source scope's generation witness");
        assertFalse(child.getVerifiedCanonical(evidence.requestedBlueId()).isPresent());
        assertFalse(child.getVerifiedResolved(evidence.requestedBlueId()).isPresent());
        assertEquals(0, child.resolvedGraphSize());
        assertFalse(child.isCurrentGeneration(),
                "touching a stale scope must not certify previews from its old generation");
        child.promoteReferencesReachableFrom(FrozenNode.fromNode(new Node()
                .type(reference(evidence.requestedBlueId()))));
        assertEquals(0, parent.size(),
                "evidence retained before invalidation must never be re-promoted");
    }

    @Test
    void closingParentReleasesLeakedTransientChildState() {
        ResolvedSnapshot verified = new Blue().resolveToSnapshot(new Node().value("verified"));
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache leakedChild = parent.transientChild();

        leakedChild.putVerifiedResolved(verified.verifiedReferenceResolution());
        leakedChild.putTransientTrustedCanonical(
                verified.blueId(), verified.frozenCanonicalRoot());
        leakedChild.freezeResolved(new Node().value("local graph"));
        assertTrue(leakedChild.cacheStats().verifiedCurrentWeightBytes() > 0L);
        assertTrue(leakedChild.cacheStats().transientTrustedCurrentWeightBytes() > 0L);
        assertTrue(leakedChild.cacheStats().structuralCurrentWeightBytes() > 0L);

        parent.close();

        assertEquals(0, leakedChild.cacheStats().verifiedEntries());
        assertEquals(0, leakedChild.cacheStats().transientTrustedEntries());
        assertEquals(0, leakedChild.cacheStats().structuralEntries());
        assertEquals(0L, leakedChild.cacheStats().verifiedCurrentWeightBytes());
        assertEquals(0L, leakedChild.cacheStats().transientTrustedCurrentWeightBytes());
        assertEquals(0L, leakedChild.cacheStats().structuralCurrentWeightBytes());
        assertThrows(IllegalStateException.class,
                () -> leakedChild.getVerifiedCanonical(verified.blueId()));
    }

    @Test
    void closingTransientChildDoesNotInvalidateParentOrSibling() {
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();
        ResolvedReferenceCache sibling = parent.transientChild();
        child.freezeResolved(new Node().value("child-local"));

        child.close();
        child.close();

        assertThrows(IllegalStateException.class,
                () -> child.freezeResolved(new Node().value("closed")));
        assertEquals(0, child.cacheStats().structuralEntries());
        assertTrue(parent.isCurrentGeneration());
        assertTrue(sibling.isCurrentGeneration());
        parent.freezeResolved(new Node().value("parent-still-open"));
        sibling.freezeResolved(new Node().value("sibling-still-open"));
    }

    @Test
    void closingTransientChildRetainsAggregateLifetimeHighWaterMarks() {
        ResolvedSnapshot verified = new Blue().resolveToSnapshot(new Node().value("verified"));
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();
        child.putVerifiedResolved(verified.verifiedReferenceResolution());
        child.putTransientTrustedCanonical(
                verified.blueId(), verified.frozenCanonicalRoot());
        child.freezeResolved(new Node().value("local graph"));

        child.close();

        ResolvedReferenceCache.CacheStats stats = parent.cacheStats();
        assertEquals(0, stats.verifiedEntries());
        assertEquals(0, stats.transientTrustedEntries());
        assertEquals(0, stats.structuralEntries());
        assertTrue(stats.verifiedHighWaterWeightBytes() > 0L);
        assertTrue(stats.transientTrustedHighWaterWeightBytes() > 0L);
        assertTrue(stats.structuralHighWaterWeightBytes() > 0L);
    }

    @Test
    void transientTrustedReferencesRespectPolicyBoundsAndDisabledMode() {
        Blue blue = new Blue();
        ResolvedSnapshot first = blue.resolveToSnapshot(new Node().value("trusted-1"));
        ResolvedSnapshot second = blue.resolveToSnapshot(new Node().value("trusted-2"));
        BlueCachePolicy oneEntryPolicy = BlueCachePolicy.builder()
                .transientReferences(1, 1024L * 1024L)
                .maximumDerivedEntryWeightBytes(1024L * 1024L)
                .build();
        ResolvedReferenceCache parent = new ResolvedReferenceCache(oneEntryPolicy);
        ResolvedReferenceCache child = parent.transientChild();

        child.putTransientTrustedCanonical(first.blueId(), first.frozenCanonicalRoot());
        child.putTransientTrustedCanonical(second.blueId(), second.frozenCanonicalRoot());

        ResolvedReferenceCache.CacheStats boundedStats = child.cacheStats();
        assertEquals(1, boundedStats.transientTrustedEntries());
        assertEquals(1L, boundedStats.transientTrustedEvictions());
        assertFalse(child.getTransientTrustedCanonical(first.blueId()).isPresent());
        assertTrue(child.getTransientTrustedCanonical(second.blueId()).isPresent());

        ResolvedReferenceCache disabledParent =
                new ResolvedReferenceCache(BlueCachePolicy.disabled());
        ResolvedReferenceCache disabledChild = disabledParent.transientChild();
        assertSame(first.frozenCanonicalRoot(), disabledChild.putTransientTrustedCanonical(
                first.blueId(), first.frozenCanonicalRoot()));

        ResolvedReferenceCache.CacheStats disabledStats = disabledChild.cacheStats();
        assertEquals(0, disabledStats.transientTrustedEntries());
        assertEquals(0L, disabledStats.transientTrustedCurrentWeightBytes());
        assertEquals(1L, disabledStats.transientTrustedOversizedRejections());
        assertFalse(disabledChild.getTransientTrustedCanonical(first.blueId()).isPresent());
    }

    @Test
    void closingIntermediateTransientScopeInvalidatesAndReleasesDescendants() {
        ResolvedReferenceCache root = new ResolvedReferenceCache();
        ResolvedReferenceCache child = root.transientChild();
        ResolvedReferenceCache grandchild = child.transientChild();
        grandchild.freezeResolved(new Node().value("local"));

        child.close();

        assertFalse(child.isCurrentGeneration());
        assertFalse(grandchild.isCurrentGeneration());
        assertEquals(0, grandchild.cacheStats().structuralEntries());
        assertThrows(IllegalStateException.class,
                () -> grandchild.freezeResolved(new Node().value("local")));
        assertThrows(IllegalStateException.class,
                () -> grandchild.freezeResolved(new Node().value("other")));
        assertThrows(IllegalStateException.class, child::transientChild);
        assertThrows(IllegalStateException.class, grandchild::transientChild);
        assertTrue(root.isCurrentGeneration());
    }

    @Test
    void pinningThroughTransientChildDelegatesOwnershipToRoot() {
        ResolvedSnapshot verified = new Blue().resolveToSnapshot(new Node().value("verified"));
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();

        child.putPinnedVerifiedResolved(verified.verifiedReferenceResolution());

        assertEquals(1, parent.cacheStats().pinnedVerifiedEntries());
        assertEquals(1, parent.cacheStats().verifiedEntries());
        assertEquals(0, child.cacheStats().pinnedVerifiedEntries());
        assertSame(verified.frozenResolvedRoot(),
                parent.getVerifiedResolved(verified.blueId()).orElseThrow(AssertionError::new));
    }

    @Test
    void isolatedPinnedCopyExcludesDerivedEntriesAndHasIndependentLifecycle() {
        ResolvedSnapshot pinned = new Blue().resolveToSnapshot(new Node().value("pinned"));
        ResolvedSnapshot derived = new Blue().resolveToSnapshot(new Node().value("derived"));
        ResolvedReferenceCache source = new ResolvedReferenceCache();
        source.putPinnedVerifiedResolved(pinned.verifiedReferenceResolution());
        source.putVerifiedResolved(derived.verifiedReferenceResolution());

        ResolvedReferenceCache firstCopy = source.isolatedCopyOfPinnedVerifiedEntries();
        assertSame(pinned.frozenResolvedRoot(),
                firstCopy.getVerifiedResolved(pinned.blueId()).orElseThrow(AssertionError::new));
        assertFalse(firstCopy.getVerifiedResolved(derived.blueId()).isPresent());

        firstCopy.close();
        assertSame(pinned.frozenResolvedRoot(),
                source.getVerifiedResolved(pinned.blueId()).orElseThrow(AssertionError::new));

        ResolvedReferenceCache retainedCopy = source.isolatedCopyOfPinnedVerifiedEntries();
        source.close();
        assertSame(pinned.frozenResolvedRoot(),
                retainedCopy.getVerifiedResolved(pinned.blueId()).orElseThrow(AssertionError::new));
        retainedCopy.close();
    }

    @Test
    void staleOrClosedTransientChildCannotPublishPinnedEvidenceToRoot() {
        VerifiedReferenceResolution evidence = new Blue()
                .resolveToSnapshot(new Node().value("verified"))
                .verifiedReferenceResolution();
        ResolvedReferenceCache root = new ResolvedReferenceCache();
        ResolvedReferenceCache stale = root.transientChild();

        root.clear();

        assertFalse(stale.isCurrentGeneration());
        assertThrows(IllegalStateException.class,
                () -> stale.putPinnedVerifiedResolved(evidence));
        assertEquals(0, root.cacheStats().verifiedEntries());
        assertEquals(0, root.cacheStats().pinnedVerifiedEntries());

        ResolvedReferenceCache closed = root.transientChild();
        closed.close();
        assertThrows(IllegalStateException.class,
                () -> closed.putPinnedVerifiedResolved(evidence));
        assertEquals(0, root.cacheStats().verifiedEntries());
        assertEquals(0, root.cacheStats().pinnedVerifiedEntries());
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

    private static FrozenNode[] canonicalNodesWhoseBlueIdsSharedLegacyStripe() {
        Map<Integer, FrozenNode> firstByStripe = new HashMap<>();
        for (int index = 0; index < 1024; index++) {
            FrozenNode candidate = FrozenNode.fromNode(
                    new Node().value("legacy-loading-stripe-" + index));
            int stripe = (candidate.blueId().hashCode() & Integer.MAX_VALUE) % 64;
            FrozenNode first = firstByStripe.putIfAbsent(stripe, candidate);
            if (first != null && !first.blueId().equals(candidate.blueId())) {
                return new FrozenNode[]{first, candidate};
            }
        }
        throw new AssertionError("could not find colliding canonical BlueIds");
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting test gate", interrupted);
        }
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
