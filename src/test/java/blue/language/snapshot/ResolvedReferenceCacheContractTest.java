package blue.language.snapshot;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.merge.Merger;
import blue.language.merge.SnapshotResolution;
import blue.language.merge.VerifiedReferenceResolution;
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
import java.util.concurrent.atomic.AtomicReference;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedReferenceCacheContractTest {

    @Test
    void shouldTrackNestedCyclicSetReferencesInFrozenCanonicalWithoutChangingIdentity() {
        // given
        String cyclicMemberId = "ENCwyUPUcBhZSYt7ho4Hyjm6iPGC1JrqdBhvJRFPgwFz#0";
        Node ordinary = new Node().properties("nested", new Node().value("value"));
        Node recursive = ordinary.clone().properties("typed",
                new Node().type(new Node().blueId(cyclicMemberId)));

        FrozenNode frozenOrdinary = FrozenNode.fromNode(ordinary);
        // when
        FrozenNode frozenRecursive = FrozenNode.fromNode(recursive);

        // then
        assertFalse(frozenOrdinary.containsCyclicSetReference());
        assertTrue(frozenRecursive.containsCyclicSetReference());
        assertEquals(new Blue().calculateBlueId(recursive), frozenRecursive.blueId());
        assertTrue(frozenOrdinary.withProperty("typed",
                FrozenNode.fromNode(new Node().type(new Node().blueId(cyclicMemberId))))
                .containsCyclicSetReference());
    }

    @Test
    void shouldDistinguishNestedTypedObjectsFromSafeTypeRootsInFrozenNode() {
        // given
        FrozenNode nestedTypedObject = FrozenNode.fromResolvedNode(new Node()
                .properties("branch", new Node().type(reference("branch-type"))
                        .properties("declared", new Node().type("Text"))));
        FrozenNode typedRoot = FrozenNode.fromResolvedNode(new Node()
                .type(reference("parent-type"))
                .properties("declared", new Node().schema(new Schema().required(true))));
        // when
        FrozenNode untypedFixedObject = FrozenNode.fromResolvedNode(new Node()
                .properties("branch", new Node()
                        .properties("fixed", new Node().value("value"))));

        // then
        assertTrue(nestedTypedObject.containsNestedTypedObjectPayload());
        assertFalse(typedRoot.containsNestedTypedObjectPayload());
        assertFalse(untypedFixedObject.containsNestedTypedObjectPayload());
    }

    @Test
    void shouldKeepVerifiedEvidenceValueOpaqueWhenMergerIsFinal()
            throws NoSuchMethodException {
        // given
        Class<?> mergerType = Merger.class;
        Class<?> evidenceType =
                Merger.VerifiedReferenceResolution.class;

        // when
        int mergerModifiers = mergerType.getModifiers();
        int evidenceModifiers = evidenceType.getModifiers();
        int evidenceConstructorModifiers = evidenceType
                .getDeclaredConstructor(String.class, FrozenNode.class, FrozenNode.class)
                .getModifiers();

        // then
        assertTrue(Modifier.isFinal(mergerModifiers),
                "Merger is a concrete engine; MergingProcessor is the supported extension point");
        assertTrue(Modifier.isFinal(evidenceModifiers));
        assertTrue(Modifier.isPrivate(evidenceConstructorModifiers),
                "subclasses must not be able to fabricate verification evidence");
    }

    @Test
    void shouldNotConflictForIdentityEquivalentCanonicalRepresentations() {
        // given
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
        // when
        ResolvedReferenceCache materializedFirst = new ResolvedReferenceCache();

        // then
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
    void shouldReadParentFromTransientChildWhileKeepingNewEntriesAndGraphNodesLocal() {
        // given
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();
        ResolvedReferenceCache sibling = parent.transientChild();
        FrozenNode published = parent.freezeResolved(new Node().value("published"));

        // when
        FrozenNode local = child.freezeResolved(new Node().value("local"));
        FrozenNode inheritedPublished =
                child.freezeResolved(new Node().value("published"));
        FrozenNode retainedLocal =
                child.freezeResolved(new Node().value("local"));
        int parentSizeBeforeSiblingWrite =
                parent.resolvedGraphSize();
        int childSize = child.resolvedGraphSize();
        FrozenNode siblingLocal =
                sibling.freezeResolved(new Node().value("local"));
        int parentSizeAfterSiblingWrite =
                parent.resolvedGraphSize();

        // then
        assertSame(published, inheritedPublished);
        assertSame(local, retainedLocal);
        assertEquals(1, parentSizeBeforeSiblingWrite);
        assertEquals(1, childSize);
        assertNotEquals(local, siblingLocal);
        assertEquals(1, parentSizeAfterSiblingWrite);
    }

    @Test
    void shouldKeepLocalFirstWinsIdentityAfterParentPublishesEquivalentContent() {
        // given
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

        // when
        ResolvedReferenceCache child = parent.transientChild();
        FrozenNode childFirst =
                child.putVerifiedCanonical(holderId, referenced);
        FrozenNode parentFirst =
                parent.putVerifiedCanonical(holderId, materialized);
        FrozenNode childAfterParent =
                child.putVerifiedCanonical(holderId, materialized);
        FrozenNode childRetained = child.getVerifiedCanonical(holderId)
                .orElseThrow(AssertionError::new);
        FrozenNode localGraph =
                child.freezeResolved(new Node().value("same graph"));
        parent.freezeResolved(new Node().value("same graph"));
        FrozenNode retainedLocalGraph =
                child.freezeResolved(new Node().value("same graph"));

        // then
        assertSame(referenced, childFirst);
        assertSame(materialized, parentFirst);
        assertSame(referenced, childAfterParent);
        assertSame(referenced, childRetained);
        assertSame(localGraph, retainedLocalGraph);
    }

    @Test
    void shouldTraverseInheritedCanonicalEntriesDuringPromotionToReachLocalDependencies() {
        // given
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

        // when
        child.promoteReferencesReachableFrom(FrozenNode.fromNode(reference(holderId)));

        // then
        assertSame(nestedSnapshot.frozenCanonicalRoot(),
                parent.getVerifiedCanonical(nestedId).orElseThrow(AssertionError::new));
        assertSame(nestedSnapshot.frozenResolvedRoot(),
                parent.getVerifiedResolved(nestedId).orElseThrow(AssertionError::new));
    }

    @Test
    void shouldShareOneProviderLoadAcrossConcurrentCanonicalMisses() throws Exception {
        // given
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("single-flight"));
        String blueId = canonical.blueId();
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);

        // when
        boolean loaderStarted = false;
        List<FrozenNode> results = new ArrayList<>();
        int loadCount = -1;
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

            loaderStarted =
                    loaderEntered.await(5, TimeUnit.SECONDS);
            releaseLoader.countDown();

            for (Future<FrozenNode> lookup : lookups) {
                results.add(
                        lookup.get(5, TimeUnit.SECONDS));
            }
            loadCount = loads.get();
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }

        // then
        assertTrue(loaderStarted);
        for (FrozenNode result : results) {
            assertSame(canonical, result);
        }
        assertEquals(1, loadCount);
    }

    @Test
    void shouldCompleteWaitingLookupFromPublishedEntryWithoutProviderLoad() throws Exception {
        // given
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

        // when
        boolean ownerWasInstalled = false;
        boolean waiterStartedWaiting = false;
        FrozenNode published = null;
        FrozenNode ownerResult = null;
        FrozenNode waiterResult = null;
        int loadCount = -1;
        try {
            Future<FrozenNode> owner = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> {
                        loads.incrementAndGet();
                        return canonical;
                    }));
            ownerWasInstalled =
                    ownerInstalled.await(5, TimeUnit.SECONDS);
            Future<FrozenNode> waiter = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> {
                        loads.incrementAndGet();
                        return canonical;
                    }));
            waiterStartedWaiting =
                    waiterAwaiting.await(5, TimeUnit.SECONDS);

            published =
                    cache.putVerifiedCanonical(blueId, canonical);
            releaseOwner.countDown();

            ownerResult =
                    owner.get(5, TimeUnit.SECONDS);
            waiterResult =
                    waiter.get(5, TimeUnit.SECONDS);
            loadCount = loads.get();
        } finally {
            ResolvedReferenceCache.setCanonicalLoadWaitObserverForTesting(null);
            ResolvedReferenceCache.setCanonicalLoadObserverForTesting(null);
            releaseOwner.countDown();
            executor.shutdownNow();
        }

        // then
        assertTrue(ownerWasInstalled);
        assertTrue(waiterStartedWaiting);
        assertSame(canonical, published);
        assertSame(canonical, ownerResult);
        assertSame(canonical, waiterResult);
        assertEquals(0, loadCount);
    }

    @Test
    void shouldReleaseWaitingLookupAfterGenerationChange() throws Exception {
        // given
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

        // when
        boolean ownerWasInstalled = false;
        boolean waiterStartedWaiting = false;
        FrozenNode ownerResult = null;
        FrozenNode waiterResult = null;
        FrozenNode cachedResult = null;
        int loadCount = -1;
        try {
            Future<FrozenNode> owner = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> {
                        loads.incrementAndGet();
                        return canonical;
                    }));
            ownerWasInstalled =
                    ownerInstalled.await(5, TimeUnit.SECONDS);
            Future<FrozenNode> waiter = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> {
                        loads.incrementAndGet();
                        return canonical;
                    }));
            waiterStartedWaiting =
                    waiterAwaiting.await(5, TimeUnit.SECONDS);

            cache.clear();
            releaseOwner.countDown();

            ownerResult =
                    owner.get(5, TimeUnit.SECONDS);
            waiterResult =
                    waiter.get(5, TimeUnit.SECONDS);
            cachedResult = cache.getVerifiedCanonical(blueId)
                    .orElseThrow(AssertionError::new);
            loadCount = loads.get();
        } finally {
            ResolvedReferenceCache.setCanonicalLoadWaitObserverForTesting(null);
            ResolvedReferenceCache.setCanonicalLoadObserverForTesting(null);
            releaseOwner.countDown();
            executor.shutdownNow();
        }

        // then
        assertTrue(ownerWasInstalled);
        assertTrue(waiterStartedWaiting);
        assertSame(canonical, ownerResult);
        assertSame(canonical, waiterResult);
        assertSame(canonical, cachedResult);
        assertTrue(loadCount >= 1);
    }

    @Test
    void shouldNotHoldLegacyCollisionStripeDuringProviderLoad() throws Exception {
        // given
        FrozenNode[] collision = canonicalNodesWhoseBlueIdsSharedLegacyStripe();
        FrozenNode first = collision[0];
        FrozenNode second = collision[1];
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<FrozenNode> nestedResult =
                new AtomicReference<>();

        // when
        FrozenNode firstResult = null;
        FrozenNode cachedSecond = null;
        try {
            Future<FrozenNode> firstLookup = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(first.blueId(), () -> {
                        Future<FrozenNode> nested = executor.submit(() ->
                                cache.getOrLoadVerifiedCanonical(
                                        second.blueId(), () -> second));
                        try {
                            nestedResult.set(
                                    nested.get(2, TimeUnit.SECONDS));
                        } catch (Exception failure) {
                            throw new IllegalStateException(
                                    "colliding provider lookup could not complete", failure);
                        }
                        return first;
                    }));

            firstResult =
                    firstLookup.get(5, TimeUnit.SECONDS);
            cachedSecond = cache.getVerifiedCanonical(
                    second.blueId())
                    .orElseThrow(AssertionError::new);
        } finally {
            executor.shutdownNow();
        }

        // then
        assertSame(second, nestedResult.get());
        assertSame(first, firstResult);
        assertSame(second, cachedSecond);
    }

    @Test
    void shouldClearStartsANewGenerationLoadWithoutWaitingForTheOldProvider() throws Exception {
        // given
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("generation-flight"));
        String blueId = canonical.blueId();
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        CountDownLatch oldLoaderEntered = new CountDownLatch(1);
        CountDownLatch releaseOldLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
        boolean oldLoaderStarted = false;
        FrozenNode newResult = null;
        FrozenNode oldResult = null;
        FrozenNode cachedResult = null;
        try {
            Future<FrozenNode> oldLookup = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> {
                        oldLoaderEntered.countDown();
                        awaitUnchecked(releaseOldLoader);
                        return canonical;
                    }));
            oldLoaderStarted =
                    oldLoaderEntered.await(5, TimeUnit.SECONDS);

            cache.clear();
            Future<FrozenNode> newLookup = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(blueId, () -> canonical));

            newResult =
                    newLookup.get(2, TimeUnit.SECONDS);
            releaseOldLoader.countDown();
            oldResult =
                    oldLookup.get(5, TimeUnit.SECONDS);
            cachedResult = cache.getVerifiedCanonical(blueId)
                    .orElseThrow(AssertionError::new);
        } finally {
            releaseOldLoader.countDown();
            executor.shutdownNow();
        }

        // then
        assertTrue(oldLoaderStarted);
        assertSame(canonical, newResult);
        assertSame(canonical, oldResult);
        assertSame(canonical, cachedResult);
    }

    @Test
    void shouldFailRecursiveCanonicalLoadsDeterministicallyAndRemainRetryable() {
        // given
        FrozenNode first = FrozenNode.fromNode(new Node().value("recursive-first"));
        FrozenNode second = FrozenNode.fromNode(new Node().value("recursive-second"));
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        // when
        Throwable direct = captureFailure(
                () -> cache.getOrLoadVerifiedCanonical(first.blueId(), () ->
                        cache.getOrLoadVerifiedCanonical(first.blueId(), () -> first)));
        Throwable indirect = captureFailure(
                () -> cache.getOrLoadVerifiedCanonical(first.blueId(), () ->
                        cache.getOrLoadVerifiedCanonical(second.blueId(), () ->
                                cache.getOrLoadVerifiedCanonical(
                                        first.blueId(), () -> first))));
        Throwable acrossClear = captureFailure(
                () -> cache.getOrLoadVerifiedCanonical(first.blueId(), () -> {
                    cache.clear();
                    return cache.getOrLoadVerifiedCanonical(first.blueId(), () -> first);
                }));
        FrozenNode retry =
                cache.getOrLoadVerifiedCanonical(
                        first.blueId(), () -> first);

        // then
        assertInstanceOf(IllegalStateException.class, direct);
        assertEquals("Recursive verified reference load: " + first.blueId(),
                direct.getMessage());
        assertInstanceOf(IllegalStateException.class, indirect);
        assertEquals("Recursive verified reference load: " + first.blueId(),
                indirect.getMessage());
        assertInstanceOf(IllegalStateException.class, acrossClear);
        assertEquals("Recursive verified reference load: " + first.blueId(),
                acrossClear.getMessage());
        assertSame(first, retry);
    }

    @Test
    void shouldNotDeadlockOrPublishLateContentWhenClosingDuringProviderLoad() throws Exception {
        // given
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("closing-flight"));
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // when
        boolean loaderStarted = false;
        Throwable lookupFailure = null;
        Throwable lookupCause = null;
        int verifiedEntries = -1;
        try {
            Future<FrozenNode> lookup = executor.submit(() ->
                    cache.getOrLoadVerifiedCanonical(canonical.blueId(), () -> {
                        loaderEntered.countDown();
                        awaitUnchecked(releaseLoader);
                        return canonical;
                    }));
            loaderStarted =
                    loaderEntered.await(5, TimeUnit.SECONDS);

            cache.close();
            releaseLoader.countDown();

            lookupFailure = captureFailure(
                    () -> lookup.get(5, TimeUnit.SECONDS));
            lookupCause = lookupFailure == null
                    ? null : lookupFailure.getCause();
            verifiedEntries =
                    cache.cacheStats().verifiedEntries();
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }

        // then
        assertTrue(loaderStarted);
        assertInstanceOf(ExecutionException.class, lookupFailure);
        assertInstanceOf(IllegalStateException.class, lookupCause);
        assertEquals("Resolved reference cache is closed",
                lookupCause.getMessage());
        assertEquals(0, verifiedEntries);
    }

    @Test
    void shouldClearStaleChildAndPreventOldEvidencePromotionDuringParentInvalidation() {
        // given
        ResolvedSnapshot verified = new Blue().resolveToSnapshot(new Node().value("verified"));
        VerifiedReferenceResolution evidence = verified.verifiedReferenceResolution();
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();

        child.putVerifiedResolved(evidence);

        // when
        child.freezeResolved(new Node().value("local graph"));
        int initialVerifiedSize = child.size();
        int initialGraphSize = child.resolvedGraphSize();
        parent.clear();
        boolean childCurrentAfterClear =
                child.isCurrentGeneration();
        ResolvedReferenceCache staleFork = child.forkTransient();
        boolean staleForkCurrent =
                staleFork.isCurrentGeneration();
        boolean canonicalStillPresent =
                child.getVerifiedCanonical(
                        evidence.requestedBlueId())
                        .isPresent();
        boolean resolvedStillPresent =
                child.getVerifiedResolved(
                        evidence.requestedBlueId())
                        .isPresent();
        int graphSizeAfterClear =
                child.resolvedGraphSize();
        boolean childCurrentAfterTouch =
                child.isCurrentGeneration();
        child.promoteReferencesReachableFrom(
                FrozenNode.fromNode(new Node()
                        .type(reference(
                                evidence.requestedBlueId()))));
        int parentSizeAfterPromotion = parent.size();

        // then
        assertEquals(1, initialVerifiedSize);
        assertEquals(1, initialGraphSize);
        assertFalse(childCurrentAfterClear);
        assertFalse(staleForkCurrent,
                "forking must preserve the source scope's generation witness");
        assertFalse(canonicalStillPresent);
        assertFalse(resolvedStillPresent);
        assertEquals(0, graphSizeAfterClear);
        assertFalse(childCurrentAfterTouch,
                "touching a stale scope must not certify previews from its old generation");
        assertEquals(0, parentSizeAfterPromotion,
                "evidence retained before invalidation must never be re-promoted");
    }

    @Test
    void shouldReleaseLeakedTransientChildStateWhenClosingParent() {
        // given
        ResolvedSnapshot verified = new Blue().resolveToSnapshot(new Node().value("verified"));
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache leakedChild = parent.transientChild();

        leakedChild.putVerifiedResolved(verified.verifiedReferenceResolution());

        // when
        leakedChild.freezeResolved(new Node().value("local graph"));
        ResolvedReferenceCache.CacheStats beforeClose =
                leakedChild.cacheStats();
        parent.close();
        ResolvedReferenceCache.CacheStats afterClose =
                leakedChild.cacheStats();
        Throwable closedReadFailure = captureFailure(
                () -> leakedChild.getVerifiedCanonical(
                        verified.blueId()));

        // then
        assertTrue(beforeClose.verifiedCurrentWeightBytes() > 0L);
        assertTrue(beforeClose.structuralCurrentWeightBytes() > 0L);
        assertEquals(0, afterClose.verifiedEntries());
        assertEquals(0, afterClose.transientTrustedEntries());
        assertEquals(0, afterClose.structuralEntries());
        assertEquals(0L, afterClose.verifiedCurrentWeightBytes());
        assertEquals(0L,
                afterClose.transientTrustedCurrentWeightBytes());
        assertEquals(0L,
                afterClose.structuralCurrentWeightBytes());
        assertInstanceOf(IllegalStateException.class,
                closedReadFailure);
    }

    @Test
    void shouldNotInvalidateParentOrSiblingWhenClosingTransientChild() {
        // given
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();
        ResolvedReferenceCache sibling = parent.transientChild();
        child.freezeResolved(new Node().value("child-local"));

        // when
        child.close();
        child.close();
        Throwable closedWriteFailure = captureFailure(
                () -> child.freezeResolved(
                        new Node().value("closed")));
        int childStructuralEntries =
                child.cacheStats().structuralEntries();
        boolean parentCurrent =
                parent.isCurrentGeneration();
        boolean siblingCurrent =
                sibling.isCurrentGeneration();
        FrozenNode parentWrite = parent.freezeResolved(
                new Node().value("parent-still-open"));
        FrozenNode siblingWrite = sibling.freezeResolved(
                new Node().value("sibling-still-open"));

        // then
        assertInstanceOf(IllegalStateException.class,
                closedWriteFailure);
        assertEquals(0, childStructuralEntries);
        assertTrue(parentCurrent);
        assertTrue(siblingCurrent);
        assertNotNull(parentWrite);
        assertNotNull(siblingWrite);
    }

    @Test
    void shouldRetainAggregateLifetimeHighWaterMarksWhenClosingTransientChild() {
        // given
        ResolvedSnapshot verified = new Blue().resolveToSnapshot(new Node().value("verified"));
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();
        child.putVerifiedResolved(verified.verifiedReferenceResolution());
        child.freezeResolved(new Node().value("local graph"));

        child.close();

        // when
        ResolvedReferenceCache.CacheStats stats = parent.cacheStats();
        // then
        assertEquals(0, stats.verifiedEntries());
        assertEquals(0, stats.transientTrustedEntries());
        assertEquals(0, stats.structuralEntries());
        assertTrue(stats.verifiedHighWaterWeightBytes() > 0L);
        assertEquals(0L, stats.transientTrustedHighWaterWeightBytes());
        assertTrue(stats.structuralHighWaterWeightBytes() > 0L);
    }

    @Test
    void shouldPreventPublicCanonicalCacheBypassFromSeedingMismatchedContent() {
        // given
        FrozenNode requested = FrozenNode.fromNode(new Node().value("requested"));
        FrozenNode mismatched = FrozenNode.fromNode(new Node().value("mismatched"));
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        int directCanonicalInsertionMethods = 0;
        List<String> unexpectedInsertionMethods =
                new ArrayList<>();

        // when
        for (Method method : ResolvedReferenceCache.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0] == String.class
                    && parameters[1] == FrozenNode.class) {
                directCanonicalInsertionMethods++;
                if (!"putVerifiedCanonical".equals(
                        method.getName())
                        && !"putTransientTrustedCanonical".equals(
                        method.getName())) {
                    unexpectedInsertionMethods.add(
                            method.getName());
                }
            }
        }
        Throwable directInsertionFailure = captureFailure(
                () -> cache.putVerifiedCanonical(requested.blueId(), mismatched));
        FrozenNode compatibilityResult =
                cache.putTransientTrustedCanonical(
                        requested.blueId(), mismatched);
        boolean compatibilityEntryPresent =
                cache.getTransientTrustedCanonical(
                        requested.blueId()).isPresent();
        Throwable loadFailure = captureFailure(
                () -> cache.getOrLoadVerifiedCanonical(
                        requested.blueId(),
                        () -> mismatched));
        boolean rejectedLoadRetained =
                cache.getVerifiedCanonical(
                        requested.blueId()).isPresent();
        FrozenNode validResult =
                cache.getOrLoadVerifiedCanonical(
                        requested.blueId(),
                        () -> requested);

        // then
        assertEquals(2, directCanonicalInsertionMethods);
        assertTrue(unexpectedInsertionMethods.isEmpty(),
                "only the verifying insertion and its fail-closed "
                        + "binary compatibility bridge may exist: "
                        + unexpectedInsertionMethods);
        assertInstanceOf(IllegalArgumentException.class,
                directInsertionFailure);
        assertSame(mismatched, compatibilityResult);
        assertFalse(compatibilityEntryPresent,
                "the compatibility bridge must not retain trusted content");
        assertInstanceOf(IllegalArgumentException.class,
                loadFailure);
        assertFalse(rejectedLoadRetained,
                "mismatched content must not survive a rejected load");
        assertSame(requested, validResult);
    }

    @Test
    void shouldInvalidateAndReleaseDescendantsWhenClosingIntermediateTransientScope() {
        // given
        ResolvedReferenceCache root = new ResolvedReferenceCache();
        ResolvedReferenceCache child = root.transientChild();
        ResolvedReferenceCache grandchild = child.transientChild();
        grandchild.freezeResolved(new Node().value("local"));

        // when
        child.close();

        // then
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
    void shouldDelegateOwnershipToRootWhenPinningThroughTransientChild() {
        // given
        ResolvedSnapshot verified = new Blue().resolveToSnapshot(new Node().value("verified"));
        ResolvedReferenceCache parent = new ResolvedReferenceCache();
        ResolvedReferenceCache child = parent.transientChild();

        // when
        child.putPinnedVerifiedResolved(verified.verifiedReferenceResolution());

        // then
        assertEquals(1, parent.cacheStats().pinnedVerifiedEntries());
        assertEquals(1, parent.cacheStats().verifiedEntries());
        assertEquals(0, child.cacheStats().pinnedVerifiedEntries());
        assertSame(verified.frozenResolvedRoot(),
                parent.getVerifiedResolved(verified.blueId()).orElseThrow(AssertionError::new));
    }

    @Test
    void shouldExcludeDerivedEntriesAndKeepIndependentLifecycleInIsolatedPinnedCopy() {
        // given
        ResolvedSnapshot pinned = new Blue().resolveToSnapshot(new Node().value("pinned"));
        ResolvedSnapshot derived = new Blue().resolveToSnapshot(new Node().value("derived"));
        ResolvedReferenceCache source = new ResolvedReferenceCache();
        source.putPinnedVerifiedResolved(pinned.verifiedReferenceResolution());
        source.putVerifiedResolved(derived.verifiedReferenceResolution());

        // when
        ResolvedReferenceCache firstCopy = source.isolatedCopyOfPinnedVerifiedEntries();
        FrozenNode firstCopyPinned =
                firstCopy.getVerifiedResolved(pinned.blueId())
                        .orElseThrow(AssertionError::new);
        boolean firstCopyContainsDerived =
                firstCopy.getVerifiedResolved(derived.blueId())
                        .isPresent();
        firstCopy.close();
        FrozenNode sourcePinnedAfterFirstCopyClose =
                source.getVerifiedResolved(pinned.blueId())
                        .orElseThrow(AssertionError::new);
        ResolvedReferenceCache retainedCopy = source.isolatedCopyOfPinnedVerifiedEntries();
        source.close();
        FrozenNode retainedPinnedAfterSourceClose =
                retainedCopy.getVerifiedResolved(pinned.blueId())
                        .orElseThrow(AssertionError::new);
        retainedCopy.close();

        // then
        assertSame(pinned.frozenResolvedRoot(),
                firstCopyPinned);
        assertFalse(firstCopyContainsDerived);
        assertSame(pinned.frozenResolvedRoot(),
                sourcePinnedAfterFirstCopyClose);
        assertSame(pinned.frozenResolvedRoot(),
                retainedPinnedAfterSourceClose);
    }

    @Test
    void shouldPreventStaleOrClosedTransientChildFromPublishingPinnedEvidenceToRoot() {
        // given
        VerifiedReferenceResolution evidence = new Blue()
                .resolveToSnapshot(new Node().value("verified"))
                .verifiedReferenceResolution();
        ResolvedReferenceCache root = new ResolvedReferenceCache();
        ResolvedReferenceCache stale = root.transientChild();

        // when
        root.clear();
        boolean staleCurrent =
                stale.isCurrentGeneration();
        Throwable stalePublicationFailure = captureFailure(
                () -> stale.putPinnedVerifiedResolved(evidence));
        ResolvedReferenceCache.CacheStats afterStaleAttempt =
                root.cacheStats();
        ResolvedReferenceCache closed = root.transientChild();
        closed.close();
        Throwable closedPublicationFailure = captureFailure(
                () -> closed.putPinnedVerifiedResolved(evidence));
        ResolvedReferenceCache.CacheStats afterClosedAttempt =
                root.cacheStats();

        // then
        assertFalse(staleCurrent);
        assertInstanceOf(IllegalStateException.class,
                stalePublicationFailure);
        assertEquals(0, afterStaleAttempt.verifiedEntries());
        assertEquals(0,
                afterStaleAttempt.pinnedVerifiedEntries());
        assertInstanceOf(IllegalStateException.class,
                closedPublicationFailure);
        assertEquals(0, afterClosedAttempt.verifiedEntries());
        assertEquals(0,
                afterClosedAttempt.pinnedVerifiedEntries());
    }

    @Test
    void shouldNotCertifyUnrelatedResolvedContent() throws Exception {
        // given
        boolean warmStructuralInterner = false;

        // when
        ArbitraryCertificationObservation arbitraryObservation =
                observeArbitrarySnapshotCertification(warmStructuralInterner);
        List<ConcurrentRaceObservation> concurrentObservations =
                observeValidEvidenceRacingArbitrarySnapshots();

        // then
        assertArbitrarySnapshotCannotCertifyContent(arbitraryObservation);
        assertValidEvidenceWinsConcurrentRace(concurrentObservations);
    }

    @Test
    void shouldPreventStructuralWarmupFromChangingVerifiedCacheEligibility() {
        // given
        boolean withoutWarmup = false;
        boolean withWarmup = true;

        // when
        ArbitraryCertificationObservation coldObservation =
                observeArbitrarySnapshotCertification(withoutWarmup);
        ArbitraryCertificationObservation warmObservation =
                observeArbitrarySnapshotCertification(withWarmup);

        // then
        assertArbitrarySnapshotCannotCertifyContent(coldObservation);
        assertArbitrarySnapshotCannotCertifyContent(warmObservation);
    }

    @Test
    void shouldRejectReferenceOnlyNodeWhenPuttingVerifiedCanonical() {
        // given
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        String referenceId = new Blue().calculateBlueId(new Node().value("referenced"));
        // when
        FrozenNode reference = FrozenNode.fromNode(new Node().blueId(referenceId));

        // then
        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedCanonical(referenceId, reference));
    }

    @Test
    void shouldNotProduceVerificationEvidenceFromReferenceOnlyCanonical() {
        // given
        Node content = new Node().value("value");
        String referenceId = new Blue().calculateBlueId(content);
        Blue blue = new Blue();

        // when
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(reference(referenceId));

        // then
        assertNull(snapshot.verifiedReferenceResolution());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    @Test
    void shouldNotProduceVerificationEvidenceFromReferenceOnlyResolvedNode() {
        // given
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedSnapshot arbitrary = new ResolvedSnapshot(
                canonicalNode, reference(blueId), blueId);
        // when
        Blue blue = new Blue().cacheResolvedSnapshot(arbitrary);

        // then
        assertNull(arbitrary.verifiedReferenceResolution());
        assertFalse(blue.cachedResolvedSnapshot(blueId).isPresent());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    @Test
    void shouldRejectMismatchedBlueIdWhenPuttingVerifiedCanonical() {
        // given
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        // when
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("value"));

        // then
        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedCanonical("wrong-id", canonical));
    }

    @Test
    void shouldPreventResolverEvidenceFromCarryingMismatchedBlueId() {
        // given
        ResolvedSnapshot snapshot = new Blue().resolveToSnapshot(new Node().value("value"));
        // when
        VerifiedReferenceResolution verification = snapshot.verifiedReferenceResolution();

        // then
        assertNotNull(verification);
        assertEquals(snapshot.blueId(), verification.requestedBlueId());
        assertEquals(verification.canonicalRoot().blueId(), verification.requestedBlueId());
        assertSourceConstructorsArePrivate(
                Merger.VerifiedReferenceResolution.class);
        assertSourceConstructorsArePrivate(
                Merger.SnapshotResolution.class);
        assertSourceConstructorsAreNotPublic(
                VerifiedReferenceResolution.class);
        assertSourceConstructorsAreNotPublic(
                SnapshotResolution.class);
        assertNoPublicArbitraryResolutionFactory(Merger.class);
        assertNoPublicArbitraryResolutionFactory(VerifiedReferenceResolution.class);
        assertNoPublicArbitraryResolutionFactory(SnapshotResolution.class);
        assertNoPublicArbitraryResolutionFactory(ResolvedSnapshot.class);
        assertVerifiedCacheAcceptsOnlyEvidence();
    }

    @Test
    void shouldFailDeterministicallyWhenCanonicalEntryComputedBlueIdDiffers() {
        // given
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode canonical = FrozenNode.fromNode(canonicalNode);
        FrozenNode forgedConflict = FrozenNode.fromUncheckedCanonicalNode(new Node().value("different"));

        // when
        cache.putVerifiedCanonical(blueId, canonical);

        // then
        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedCanonical(blueId, forgedConflict));
    }

    @Test
    void shouldPreventUncheckedCanonicalNodeFromEnteringVerifiedCache() {
        // given
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        // when
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        // then
        assertThrows(IllegalArgumentException.class, () -> cache.putVerifiedCanonical(
                blueId, FrozenNode.fromUncheckedCanonicalNode(canonicalNode)));
        assertFalse(cache.getVerifiedCanonical(blueId).isPresent());
    }

    @Test
    void shouldNotProduceVerificationEvidenceFromContextualResolvedNode() {
        // given
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode contextual = cache.freezeResolved(canonicalNode);
        // when
        ResolvedSnapshot arbitrary = new ResolvedSnapshot(
                FrozenNode.fromNode(canonicalNode), contextual, blueId);

        // then
        assertNull(arbitrary.verifiedReferenceResolution());
        assertFalse(cache.getVerifiedResolved(blueId).isPresent());
        assertEquals(0, cache.size());
    }

    @Test
    void shouldReuseValidVerifiedCanonicalAndResolvedContent() {
        // given
        ResolvedSnapshot snapshot = new Blue().resolveToSnapshot(new Node().value("value"));
        VerifiedReferenceResolution verification = snapshot.verifiedReferenceResolution();
        // when
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        // then
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
    void shouldClearVerifiedEntriesAfterProviderOrProcessorChange() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node().name("Type"));
        String typeId = provider.getBlueIdByName("Type");
        Blue blue = new Blue(provider);

        // when
        blue.resolve(new Node().type(new Node().blueId(typeId)));
        int populatedBeforeProviderChange =
                blue.resolvedReferenceCacheSize();
        blue.nodeProvider(new BasicNodeProvider());
        int sizeAfterProviderChange =
                blue.resolvedReferenceCacheSize();
        blue.nodeProvider(provider);
        blue.resolve(new Node().type(new Node().blueId(typeId)));
        int populatedBeforeProcessorChange =
                blue.resolvedReferenceCacheSize();
        blue.mergingProcessor(blue.getMergingProcessor());
        int sizeAfterProcessorChange =
                blue.resolvedReferenceCacheSize();

        // then
        assertTrue(populatedBeforeProviderChange > 0);
        assertEquals(0, sizeAfterProviderChange);
        assertTrue(populatedBeforeProcessorChange > 0);
        assertEquals(0, sizeAfterProcessorChange);
    }

    private ArbitraryCertificationObservation observeArbitrarySnapshotCertification(
            boolean warmStructuralInterner) {
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
        VerifiedReferenceResolution arbitraryEvidence =
                arbitrary.verifiedReferenceResolution();
        int cacheSizeBeforeLoad = blue.resolvedReferenceCacheSize();
        ResolvedSnapshot loaded = blue.loadSnapshot(blueId);
        return new ArbitraryCertificationObservation(
                arbitraryEvidence,
                cacheSizeBeforeLoad,
                loaded.resolvedRoot().getName(),
                fetches.get(),
                blue.resolvedReferenceCacheSize());
    }

    private List<ConcurrentRaceObservation> observeValidEvidenceRacingArbitrarySnapshots()
            throws Exception {
        Node canonicalNode = new Node().name("Concurrent Canonical");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedSnapshot valid = new Blue().resolveToSnapshot(canonicalNode);
        ResolvedSnapshot invalid = new ResolvedSnapshot(
                canonicalNode, new Node().name("Concurrent Invalid"), blueId);
        List<ConcurrentRaceObservation> observations = new ArrayList<>();
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
                ResolvedSnapshot resolvedAgain = target.resolveToSnapshot(canonicalNode);
                observations.add(new ConcurrentRaceObservation(
                        valid,
                        retained,
                        resolvedAgain,
                        retained.resolvedRoot().getName(),
                        target.resolvedSnapshotCacheSize(),
                        target.resolvedReferenceCacheSize()));
            }
        } finally {
            executor.shutdownNow();
        }
        return observations;
    }

    private void assertArbitrarySnapshotCannotCertifyContent(
            ArbitraryCertificationObservation observation) {
        assertNull(observation.arbitraryEvidence);
        assertEquals(0, observation.cacheSizeBeforeLoad);
        assertEquals("Canonical A", observation.loadedName);
        assertEquals(1, observation.fetches);
        assertEquals(1, observation.cacheSizeAfterLoad);
    }

    private void assertValidEvidenceWinsConcurrentRace(
            List<ConcurrentRaceObservation> observations) {
        for (ConcurrentRaceObservation observation : observations) {
            assertSame(observation.valid, observation.retained);
            assertSame(observation.valid, observation.resolvedAgain);
            assertEquals("Concurrent Canonical", observation.retainedName);
            assertEquals(1, observation.snapshotCacheSize);
            assertEquals(1, observation.referenceCacheSize);
        }
    }

    private static final class ArbitraryCertificationObservation {
        private final VerifiedReferenceResolution arbitraryEvidence;
        private final int cacheSizeBeforeLoad;
        private final String loadedName;
        private final int fetches;
        private final int cacheSizeAfterLoad;

        private ArbitraryCertificationObservation(
                VerifiedReferenceResolution arbitraryEvidence,
                int cacheSizeBeforeLoad,
                String loadedName,
                int fetches,
                int cacheSizeAfterLoad) {
            this.arbitraryEvidence = arbitraryEvidence;
            this.cacheSizeBeforeLoad = cacheSizeBeforeLoad;
            this.loadedName = loadedName;
            this.fetches = fetches;
            this.cacheSizeAfterLoad = cacheSizeAfterLoad;
        }
    }

    private static final class ConcurrentRaceObservation {
        private final ResolvedSnapshot valid;
        private final ResolvedSnapshot retained;
        private final ResolvedSnapshot resolvedAgain;
        private final String retainedName;
        private final int snapshotCacheSize;
        private final int referenceCacheSize;

        private ConcurrentRaceObservation(
                ResolvedSnapshot valid,
                ResolvedSnapshot retained,
                ResolvedSnapshot resolvedAgain,
                String retainedName,
                int snapshotCacheSize,
                int referenceCacheSize) {
            this.valid = valid;
            this.retained = retained;
            this.resolvedAgain = resolvedAgain;
            this.retainedName = retainedName;
            this.snapshotCacheSize = snapshotCacheSize;
            this.referenceCacheSize = referenceCacheSize;
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

    private void assertSourceConstructorsAreNotPublic(Class<?> type) {
        int sourceConstructors = 0;
        for (java.lang.reflect.Constructor<?> constructor
                : type.getDeclaredConstructors()) {
            if (constructor.isSynthetic()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()),
                        type.getSimpleName()
                                + " compiler bridge must not be public");
                continue;
            }
            sourceConstructors++;
            assertFalse(Modifier.isPublic(constructor.getModifiers()),
                    type.getSimpleName() + " constructor must not be public");
        }
        assertEquals(1, sourceConstructors,
                type.getSimpleName()
                        + " must have exactly one source constructor");
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
