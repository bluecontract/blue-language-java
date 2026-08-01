package blue.language.processor;

import blue.language.Blue;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.model.MarkerContract;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.CircularBlueIdCalculator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerMatchContextDeclaredTypeLineageTest {

    @Test
    void shouldVerifyExactIdentityIsRepresentationIndependentAndDoesNotReadTheProvider() {
        // given
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        Blue blue = new Blue(provider);
        ContractMatchingService matching = new ContractMatchingService(blue);
        RepresentationMatrix matrix = representationMatrix(
                types,
                blue,
                types.expectedId,
                types.expectedId);

        // when
        provider.resetLookupCount();
        List<Boolean> matches = representationMatrixResults(matrix, matching);
        int lookupCount = provider.lookupCount();

        // then
        assertEquals(repeatedResult(true), matches);
        assertEquals(0, lookupCount);
    }

    @Test
    void shouldVerifyDirectAndTransitiveAncestryAreRepresentationIndependent() {
        // given
        TypeFixture types = TypeFixture.create();
        Blue blue = types.blue();
        RepresentationMatrix childMatrix = representationMatrix(
                types, blue, types.childId, types.expectedId);
        RepresentationMatrix grandchildMatrix = representationMatrix(
                types, blue, types.grandchildId, types.expectedId);
        RepresentationMatrix parentMatrix = representationMatrix(
                types, blue, types.grandchildId, types.childId);

        // when
        ContractMatchingService matching = new ContractMatchingService(blue);
        List<Boolean> childMatches =
                representationMatrixResults(childMatrix, matching);
        List<Boolean> grandchildMatches =
                representationMatrixResults(grandchildMatrix, matching);
        List<Boolean> parentMatches =
                representationMatrixResults(parentMatrix, matching);

        // then
        assertEquals(repeatedResult(true), childMatches);
        assertEquals(repeatedResult(true), grandchildMatches);
        assertEquals(repeatedResult(true), parentMatches);
    }

    @Test
    void shouldVerifySiblingsAndUnrelatedSameShapeTypesAreRejectedInEveryRepresentation() {
        // given
        TypeFixture types = TypeFixture.create();
        Blue blue = types.blue();
        RepresentationMatrix siblingMatrix = representationMatrix(
                types, blue, types.siblingId, types.childId);
        RepresentationMatrix sameShapeMatrix = representationMatrix(
                types, blue, types.unrelatedSameShapeId, types.expectedId);
        RepresentationMatrix differentShapeMatrix = representationMatrix(
                types,
                blue,
                types.unrelatedDifferentShapeId,
                types.expectedId);

        // when
        ContractMatchingService matching = new ContractMatchingService(blue);
        List<Boolean> siblingMatches =
                representationMatrixResults(siblingMatrix, matching);
        List<Boolean> sameShapeMatches =
                representationMatrixResults(sameShapeMatrix, matching);
        List<Boolean> differentShapeMatches =
                representationMatrixResults(differentShapeMatrix, matching);

        // then
        assertEquals(repeatedResult(false), siblingMatches);
        assertEquals(repeatedResult(false), sameShapeMatches);
        assertEquals(repeatedResult(false), differentShapeMatches);
    }

    @Test
    void shouldVerifyColdMaterializedAndReconstructedPureQueriesAgree() {
        // given
        TypeFixture types = TypeFixture.create();
        Blue materializer = types.blue();
        Node materializedChild = types.materializedEvent(materializer, types.childId);

        // when
        Node materializedExpected = types.materializedType(materializer, types.expectedId);
        boolean materializedMatch = context(
                materializedChild,
                types.matchingService())
                .eventDeclaredTypeIsSameOrDescendantOf(
                        materializedExpected);
        boolean pureMatch = context(
                types.event(types.childId),
                types.matchingService())
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        Node materializedUnrelated = types.materializedEvent(
                materializer,
                types.unrelatedSameShapeId);
        boolean materializedUnrelatedMatch = context(
                materializedUnrelated,
                types.matchingService())
                .eventDeclaredTypeIsSameOrDescendantOf(
                        materializedExpected);
        boolean pureUnrelatedMatch = context(
                types.event(types.unrelatedSameShapeId),
                types.matchingService())
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));

        // then
        assertTrue(materializedMatch);
        assertTrue(pureMatch);
        assertFalse(materializedUnrelatedMatch);
        assertFalse(pureUnrelatedMatch);
    }

    @Test
    void shouldVerifyCachedDirectEdgesServePositiveAndDefinitiveNegativeChecks() {
        // given
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));

        // when
        HandlerMatchContext grandchild = context(types.event(types.grandchildId), matching);
        boolean ancestryMatch = grandchild
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        int coldLookupCount = provider.lookupCount();
        boolean parentMatch = grandchild
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.childId));
        boolean siblingMatch = grandchild
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.siblingId));
        boolean unrelatedMatch = grandchild
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.unrelatedSameShapeId));
        int warmLookupCount = provider.lookupCount();
        int cacheSize = matching.declaredTypeLineageCacheSize();

        // then
        assertTrue(ancestryMatch);
        assertEquals(3, coldLookupCount);
        assertTrue(parentMatch);
        assertFalse(siblingMatch);
        assertFalse(unrelatedMatch);
        assertEquals(3, warmLookupCount);
        assertEquals(3, cacheSize);
    }

    @Test
    void shouldVerifyUnavailableAncestryIsNotCachedAndCanRecover() {
        // given
        TypeFixture types = TypeFixture.create();
        MutableCountingProvider provider = new MutableCountingProvider();
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));

        // when
        HandlerMatchContext child = context(types.event(types.childId), matching);
        boolean unavailableMatch = child
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        int unavailableLookupCount = provider.lookupCount();
        int unavailableCacheSize =
                matching.declaredTypeLineageCacheSize();
        provider.put(
                types.childId,
                types.definitions.get(types.childId));
        provider.put(
                types.expectedId,
                types.definitions.get(types.expectedId));
        boolean recoveredMatch = child
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        int recoveredLookupCount = provider.lookupCount();
        int recoveredCacheSize =
                matching.declaredTypeLineageCacheSize();

        // then
        assertFalse(unavailableMatch);
        assertEquals(1, unavailableLookupCount);
        assertEquals(0, unavailableCacheSize);
        assertTrue(recoveredMatch);
        assertEquals(3, recoveredLookupCount);
        assertEquals(2, recoveredCacheSize);
    }

    @Test
    void shouldVerifyVerifiedPrefixEdgesSurviveALaterUnavailableAncestorAndEnableRecovery() {
        // given
        TypeFixture types = TypeFixture.create();
        MutableCountingProvider provider = new MutableCountingProvider();
        provider.put(types.grandchildId, types.definitions.get(types.grandchildId));
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));

        // when
        HandlerMatchContext grandchild = context(types.event(types.grandchildId), matching);
        boolean unavailableMatch = grandchild
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        int unavailableLookupCount = provider.lookupCount();
        int prefixCacheSize =
                matching.declaredTypeLineageCacheSize();
        provider.put(
                types.childId,
                types.definitions.get(types.childId));
        provider.put(
                types.expectedId,
                types.definitions.get(types.expectedId));
        boolean recoveredMatch = grandchild
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        int recoveredLookupCount = provider.lookupCount();
        int recoveredCacheSize =
                matching.declaredTypeLineageCacheSize();

        // then
        assertFalse(unavailableMatch);
        assertEquals(2, unavailableLookupCount);
        assertEquals(1, prefixCacheSize);
        assertTrue(recoveredMatch);
        assertEquals(
                4,
                recoveredLookupCount,
                "the verified grandchild edge must be reused");
        assertEquals(3, recoveredCacheSize);
    }

    @Test
    void shouldVerifyIdentityFreeParentIsADistinctCachedTerminalFact() {
        // given
        TypeFixture types = TypeFixture.create();
        Node incomplete = new Node().type(new Node().name("Anonymous Parent"));
        String incompleteId = BlueIdCalculator.calculateBlueId(incomplete);
        MutableCountingProvider provider = new MutableCountingProvider();
        provider.put(incompleteId, incomplete);

        // when
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));
        boolean firstMatch = context(
                types.event(incompleteId),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        int firstLookupCount = provider.lookupCount();
        int cacheSize = matching.declaredTypeLineageCacheSize();
        boolean secondMatch = context(
                types.event(incompleteId),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        int secondLookupCount = provider.lookupCount();

        // then
        assertFalse(firstMatch);
        assertEquals(1, firstLookupCount);
        assertEquals(1, cacheSize);
        assertFalse(secondMatch);
        assertEquals(1, secondLookupCount);
    }

    @Test
    void shouldRejectReferenceOnlyProviderResultWithoutCachingIt() {
        // given
        TypeFixture types = TypeFixture.create();
        MutableCountingProvider provider = new MutableCountingProvider();
        provider.put(types.childId, reference(types.childId));

        // when
        ContractMatchingService matching = new ContractMatchingService(
                new Blue(provider));
        IllegalArgumentException failure = captureFailure(
                () -> context(
                        types.event(types.childId),
                        matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                reference(types.expectedId)));
        int lookupCount = provider.lookupCount();
        int cacheSize = matching.declaredTypeLineageCacheSize();

        // then
        assertTrue(failure.getMessage().contains(
                "pure reference"));
        assertEquals(1, lookupCount);
        assertEquals(0, cacheSize);
    }

    @Test
    void shouldVerifyAmbiguousProviderResultPreservesDeterministicFailureAndIsNotCached() {
        // given
        TypeFixture types = TypeFixture.create();
        List<Node> ambiguousDefinitions = Arrays.asList(
                new Node().name("Ambiguous declaration A"),
                new Node().name("Ambiguous declaration B"));
        String ambiguousId = BlueIdCalculator.calculateBlueId(ambiguousDefinitions);
        NodeProvider ambiguous = blueId -> ambiguousId.equals(blueId)
                ? ambiguousDefinitions
                : null;
        ContractMatchingService matching = new ContractMatchingService(new Blue(ambiguous));

        // when
        IllegalStateException failure = captureFailure(
                () -> context(types.event(ambiguousId), matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                reference(types.expectedId)));
        int cacheSize = matching.declaredTypeLineageCacheSize();

        // then
        assertNotNull(failure);
        assertTrue(failure.getMessage().contains("Expected a single node"));
        assertEquals(0, cacheSize);
    }

    @Test
    void shouldVerifyProviderVerificationFailureIsPropagatedAndNotCached() {
        // given
        TypeFixture types = TypeFixture.create();
        NodeProvider wrongContent = blueId -> Collections.singletonList(
                new Node().name("Content with a different BlueId"));
        ContractMatchingService matching = new ContractMatchingService(new Blue(wrongContent));

        // when
        IllegalArgumentException failure = captureFailure(
                () -> context(types.event(types.childId), matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                reference(types.expectedId)));
        int cacheSize = matching.declaredTypeLineageCacheSize();

        // then
        assertNotNull(failure);
        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertEquals(0, cacheSize);
    }

    @Test
    void shouldVerifyMalformedActualAndExpectedIdsFailBeforeEqualityOrProviderAccess() {
        // given
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));

        // when
        IllegalArgumentException malformedActual = captureFailure(
                () -> context(
                        types.event("not-a-blue-id"),
                        matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                reference("not-a-blue-id")));
        IllegalArgumentException malformedExpected = captureFailure(
                () -> context(
                        types.event(types.childId),
                        matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                reference("not-a-blue-id")));
        int lookupCount = provider.lookupCount();

        // then
        assertNotNull(malformedActual);
        assertNotNull(malformedExpected);
        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(malformedActual));
        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(malformedExpected));
        assertEquals(0, lookupCount);
    }

    @Test
    void shouldVerifyProviderBlueIdMismatchPrecedesDeclaredParentTraversal() {
        // given
        TypeFixture types = TypeFixture.create();
        Map<String, Node> definitions = new LinkedHashMap<String, Node>();
        definitions.put(types.childId,
                new Node().type(reference(types.expectedId)));
        ContractMatchingService matching =
                new ContractMatchingService(new Blue(new MapProvider(definitions)));

        // when
        IllegalArgumentException failure = captureFailure(
                () -> context(types.event(types.childId), matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                reference(types.expectedId)));
        int cacheSize = matching.declaredTypeLineageCacheSize();

        // then
        assertNotNull(failure);
        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertEquals(0, cacheSize);
    }

    @Test
    void shouldRejectSelfCycleAsTypeCycle() {
        // given
        String cycleBase = syntheticId("Cycle set");
        String a = cycleBase + "#0";
        Map<String, Node> cyclic = new LinkedHashMap<String, Node>();
        cyclic.put(a, new Node()
                .name("Self-referential type")
                .type(reference(a)));

        // when
        TypeCycleObservation observation =
                observeTypeCycle(
                        cyclic,
                        a,
                        syntheticId("Expected"));

        // then
        assertTypeCycle(observation);
    }

    @Test
    void shouldRejectTwoNodeCycleAsTypeCycle() {
        // given
        String cycleBase = syntheticId("Cycle set");
        String a = cycleBase + "#0";
        String b = cycleBase + "#1";
        Map<String, Node> cyclic = new LinkedHashMap<String, Node>();
        cyclic.put(a, new Node()
                .name("Two-node cycle A")
                .type(reference(b)));
        cyclic.put(b, new Node()
                .name("Two-node cycle B")
                .type(reference(a)));

        // when
        TypeCycleObservation observation =
                observeTypeCycle(
                        cyclic,
                        a,
                        syntheticId("Expected"));

        // then
        assertTypeCycle(observation);
    }

    @Test
    void shouldVerifyAncestryMatchDoesNotHideALaterCycle() {
        // given
        String cycleBase = syntheticId("Cycle after expected set");
        String a = cycleBase + "#0";
        String expected = cycleBase + "#1";
        Map<String, Node> cyclic = new LinkedHashMap<String, Node>();
        cyclic.put(a, new Node()
                .name("Cycle-before-expected type")
                .type(reference(expected)));
        cyclic.put(expected, new Node()
                .name("Expected-but-cyclic type")
                .type(reference(a)));

        // when
        TypeCycleObservation observation =
                observeTypeCycle(cyclic, a, expected);

        // then
        assertTypeCycle(observation);
    }

    @Test
    void shouldResolveTwentyThousandLevelLineageIteratively() {
        // given
        int depth = 20_000;

        // when
        Executable traversal =
                () -> verifyDeepLineage(depth);

        // then
        assertTimeoutPreemptively(
                Duration.ofSeconds(15),
                traversal);
    }

    @Test
    void shouldDetectTwentyThousandLevelTypeCycleIteratively() {
        // given
        int depth = 20_000;

        // when
        Executable traversal =
                () -> verifyDeepCycle(depth);

        // then
        assertTimeoutPreemptively(
                Duration.ofSeconds(15),
                traversal);
    }

    @Test
    void shouldVerifyDirectEdgeCacheIsLazyBoundedAndLeastRecentlyUsed() {
        // given
        Node rootDefinition = new Node().name("Cache root");
        String root = BlueIdCalculator.calculateBlueId(rootDefinition);
        Map<String, Node> definitions = new LinkedHashMap<String, Node>();
        definitions.put(root, rootDefinition);
        String[] children = new String[DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT];
        for (int index = 0; index < children.length; index++) {
            Node child = new Node().name("Cache child " + index).type(reference(root));
            children[index] = BlueIdCalculator.calculateBlueId(child);
            definitions.put(children[index], child);
        }
        CountingMapProvider provider = new CountingMapProvider(definitions);

        // when
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));
        int initialCacheSize =
                matching.declaredTypeLineageCacheSize();
        boolean primingMatches = true;
        for (int index = 0; index < children.length - 1; index++) {
            primingMatches &= context(
                    new Node().type(reference(children[index])),
                    matching)
                    .eventDeclaredTypeIsSameOrDescendantOf(
                            reference(root));
        }
        int primedCacheSize =
                matching.declaredTypeLineageCacheSize();
        int primedLookupCount = provider.lookupCount();
        boolean firstWarmMatch = context(
                new Node().type(reference(children[0])),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(root));
        int firstWarmLookupCount = provider.lookupCount();
        boolean newEntryMatch = context(
                new Node().type(
                        reference(children[children.length - 1])),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(root));
        int newEntryLookupCount = provider.lookupCount();
        boolean retainedEntryMatch = context(
                new Node().type(reference(children[0])),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(root));
        int retainedEntryLookupCount = provider.lookupCount();
        boolean evictedEntryMatch = context(
                new Node().type(reference(children[1])),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(root));
        int evictedEntryLookupCount = provider.lookupCount();
        int finalCacheSize =
                matching.declaredTypeLineageCacheSize();

        // then
        assertEquals(64, DeclaredTypeLineageMatcher.CACHE_INITIAL_CAPACITY);
        assertEquals(0, initialCacheSize);
        assertTrue(primingMatches);
        assertEquals(DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT,
                primedCacheSize);
        assertEquals(
                DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT,
                primedLookupCount);
        assertTrue(firstWarmMatch);
        assertEquals(
                DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT,
                firstWarmLookupCount);
        assertTrue(newEntryMatch);
        assertEquals(
                DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT + 1,
                newEntryLookupCount);
        assertTrue(retainedEntryMatch);
        assertEquals(
                DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT + 1,
                retainedEntryLookupCount,
                "the recently accessed first edge must remain resident");
        assertTrue(evictedEntryMatch);
        assertEquals(
                DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT + 2,
                evictedEntryLookupCount,
                "the least-recently-used second edge must have been evicted");
        assertEquals(DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT,
                finalCacheSize);
    }

    @Test
    void shouldVerifyProviderTraversalDoesNotHoldTheSharedCacheLock() throws Exception {
        // given
        TypeFixture types = TypeFixture.create();
        BlockingProvider provider = new BlockingProvider(types.definitions, types.childId);
        ContractMatchingService matching = new ContractMatchingService(
                new Blue(provider));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean initialMatch;
        boolean providerBlocked;
        boolean warmMatch;
        boolean blockedMatch;
        int cacheSize;

        // when
        try {
            initialMatch =
                    context(types.event(types.siblingId), matching)
                            .eventDeclaredTypeIsSameOrDescendantOf(
                                    reference(types.commonId));
            Future<Boolean> blocked = executor.submit(() -> context(types.event(types.childId), matching)
                    .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
            providerBlocked = provider.awaitBlocked();

            Future<Boolean> warm = executor.submit(() -> context(types.event(types.siblingId), matching)
                    .eventDeclaredTypeIsSameOrDescendantOf(reference(types.commonId)));
            warmMatch = warm.get(1, TimeUnit.SECONDS);

            provider.release();
            blockedMatch = blocked.get(5, TimeUnit.SECONDS);
            cacheSize =
                    matching.declaredTypeLineageCacheSize();
        } finally {
            provider.release();
            executor.shutdownNow();
        }

        // then
        assertTrue(initialMatch);
        assertTrue(providerBlocked);
        assertTrue(warmMatch);
        assertTrue(blockedMatch);
        assertTrue(
                cacheSize
                        <= DeclaredTypeLineageMatcher
                        .CACHE_ENTRY_LIMIT);
    }

    @Test
    void shouldVerifyConcurrentWarmQueriesAreStableAndDoNotRepeatProviderWork() throws Exception {
        // given
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Boolean> warmMatches = new ArrayList<Boolean>();
        boolean coldMatch;
        int coldLookupCount;
        int warmLookupCount;
        int cacheSize;

        // when
        HandlerMatchContext grandchild = context(types.event(types.grandchildId), matching);
        try {
            coldMatch = grandchild
                    .eventDeclaredTypeIsSameOrDescendantOf(
                            reference(types.expectedId));
            coldLookupCount = provider.lookupCount();
            @SuppressWarnings("unchecked")
            Future<Boolean>[] results = new Future[64];
            for (int index = 0; index < results.length; index++) {
                results[index] = executor.submit(() ->
                        grandchild.eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
            }
            for (Future<Boolean> result : results) {
                warmMatches.add(result.get(5, TimeUnit.SECONDS));
            }
            warmLookupCount = provider.lookupCount();
            cacheSize =
                    matching.declaredTypeLineageCacheSize();
        } finally {
            executor.shutdownNow();
        }

        // then
        assertTrue(coldMatch);
        assertEquals(3, coldLookupCount);
        assertEquals(Collections.nCopies(64, true), warmMatches);
        assertEquals(3, warmLookupCount);
        assertEquals(3, cacheSize);
    }

    @Test
    void shouldVerifyProviderFreeServiceSupportsOnlyExactIdentity() {
        // given
        TypeFixture types = TypeFixture.create();
        Blue materializer = types.blue();

        // when
        ContractMatchingService matching = new ContractMatchingService();
        boolean pureExactMatch = context(
                types.event(types.expectedId),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        boolean materializedExactMatch = context(
                types.materializedEvent(
                        materializer,
                        types.expectedId),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        types.materializedType(
                                materializer,
                                types.expectedId));
        boolean pureAncestryMatch = context(
                types.event(types.childId),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        boolean materializedAncestryMatch = context(
                types.materializedEvent(
                        materializer,
                        types.childId),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        types.materializedType(
                                materializer,
                                types.expectedId));

        // then
        assertTrue(pureExactMatch);
        assertTrue(materializedExactMatch);
        assertFalse(pureAncestryMatch);
        assertFalse(materializedAncestryMatch);
    }

    @Test
    void shouldVerifyMissingEventTypeIdentityOrExpectedTypeIsIncompatibleWithoutTraversal() {
        // given
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);

        // when
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));
        boolean nullEventMatch = context(null, matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        boolean untypedEventMatch = context(
                types.untypedEvent(),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(types.expectedId));
        boolean nullExpectedMatch = context(
                types.event(types.expectedId),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(null);
        boolean anonymousExpectedMatch = context(
                types.event(types.expectedId),
                matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        new Node().name("Anonymous"));
        int lookupCount = provider.lookupCount();

        // then
        assertFalse(nullEventMatch);
        assertFalse(untypedEventMatch);
        assertFalse(nullExpectedMatch);
        assertFalse(anonymousExpectedMatch);
        assertEquals(0, lookupCount);
    }

    @Test
    void shouldVerifyGenericMatcherRetainsStructuralCompatibilityForUnrelatedTypes() {
        // given
        TypeFixture types = TypeFixture.create();

        // when
        ContractMatchingService matching = types.matchingService();
        boolean sameShapeMatch = matching.matches(
                types.event(types.unrelatedSameShapeId),
                types.pattern(types.expectedId));
        boolean differentShapeMatch = matching.matches(
                types.differentEvent(),
                types.pattern(types.expectedId));

        // then
        assertTrue(sameShapeMatch);
        assertFalse(differentShapeMatch);
    }

    private static RepresentationMatrix representationMatrix(
            TypeFixture types,
            Blue blue,
            String actualId,
            String expectedId) {
        return new RepresentationMatrix(
                types.event(actualId),
                types.materializedEvent(blue, actualId),
                reference(expectedId),
                types.materializedType(blue, expectedId));
    }

    private static List<Boolean> representationMatrixResults(
            RepresentationMatrix matrix,
            ContractMatchingService matching) {
        return Arrays.asList(
                context(matrix.pureEvent, matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                matrix.pureExpected),
                context(matrix.pureEvent, matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                matrix.materializedExpected),
                context(matrix.materializedEvent, matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                matrix.pureExpected),
                context(matrix.materializedEvent, matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                matrix.materializedExpected));
    }

    private static final class RepresentationMatrix {
        private final Node pureEvent;
        private final Node materializedEvent;
        private final Node pureExpected;
        private final Node materializedExpected;

        private RepresentationMatrix(
                Node pureEvent,
                Node materializedEvent,
                Node pureExpected,
                Node materializedExpected) {
            this.pureEvent = pureEvent;
            this.materializedEvent = materializedEvent;
            this.pureExpected = pureExpected;
            this.materializedExpected = materializedExpected;
        }
    }

    private static List<Boolean> repeatedResult(boolean result) {
        return Collections.nCopies(4, result);
    }

    private static void verifyDeepLineage(int depth) {
        DeepChain valid = exactDeepChain(depth);
        ContractMatchingService validMatching =
                matching(valid.definitions);

        assertTrue(
                context(
                        new Node().type(
                                reference(valid.candidate)),
                        validMatching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                                reference(valid.expected)));
        assertEquals(
                DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT,
                validMatching.declaredTypeLineageCacheSize());
    }

    private static void verifyDeepCycle(int depth) {
        DeepChain cyclic = verifiedCyclicDeepChain(depth);
        assertTypeCycle(observeTypeCycle(
                cyclic.definitions,
                cyclic.candidate,
                cyclic.expected));
    }

    private static TypeCycleObservation observeTypeCycle(
            Map<String, Node> definitions,
            String candidate,
            String expected) {
        VerifiedCyclicMapProvider provider =
                new VerifiedCyclicMapProvider(definitions);
        String verifiedCandidate = provider.verifiedBlueId(candidate);
        String verifiedExpected = provider.verifiedBlueId(expected);
        ContractMatchingService matching = new ContractMatchingService(
                new Blue(provider));
        IllegalStateException failure = captureFailure(() ->
                context(new Node().type(reference(verifiedCandidate)), matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(
                        reference(verifiedExpected)));
        return new TypeCycleObservation(
                failure,
                matching.declaredTypeLineageCacheSize());
    }

    private static void assertTypeCycle(
            TypeCycleObservation observation) {
        assertNotNull(observation.failure);
        assertTrue(observation.failure.getMessage().startsWith(
                "Type cycle in declared type ancestry:"));
        assertEquals(BlueLanguageErrorCategory.TypeCycle,
                BlueLanguageErrorClassifier.classify(
                        observation.failure));
        assertTrue(observation.cacheSize > 0,
                "verified direct edges before cycle detection remain reusable");
        assertTrue(observation.cacheSize
                <= DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT);
    }

    private static DeepChain exactDeepChain(int depth) {
        Map<String, Node> definitions = new LinkedHashMap<String, Node>();
        Node rootDefinition = new Node().name("Deep root");
        String root = BlueIdCalculator.calculateBlueId(rootDefinition);
        definitions.put(root, rootDefinition);
        String parent = root;
        for (int index = depth - 1; index >= 0; index--) {
            Node definition = new Node()
                    .name("Deep type " + index)
                    .type(reference(parent));
            String current = BlueIdCalculator.calculateBlueId(definition);
            definitions.put(current, definition);
            parent = current;
        }
        return new DeepChain(definitions, parent, root);
    }

    private static DeepChain verifiedCyclicDeepChain(int depth) {
        Map<String, Node> definitions = new LinkedHashMap<String, Node>();
        String base = syntheticId("Deep cyclic type set");
        for (int index = 0; index < depth; index++) {
            String current = base + "#" + index;
            String parent = index + 1 < depth
                    ? base + "#" + (index + 1)
                    : current;
            definitions.put(current, new Node()
                    .name("Deep cyclic type " + index)
                    .type(reference(parent)));
        }
        return new DeepChain(
                definitions,
                base + "#0",
                syntheticId("Deep cyclic expected"));
    }

    private static ContractMatchingService matching(Map<String, Node> definitions) {
        return new ContractMatchingService(new Blue(new MapProvider(definitions)));
    }

    private static HandlerMatchContext context(Node event, ContractMatchingService matching) {
        return new HandlerMatchContext(
                "/",
                "handler",
                "channel",
                event,
                Collections.<String, MarkerContract>emptyMap(),
                matching);
    }

    private static String syntheticId(String name) {
        return BlueIdCalculator.calculateBlueId(new Node().name(name));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class DeepChain {
        private final Map<String, Node> definitions;
        private final String candidate;
        private final String expected;

        private DeepChain(Map<String, Node> definitions,
                          String candidate,
                          String expected) {
            this.definitions = definitions;
            this.candidate = candidate;
            this.expected = expected;
        }
    }

    private static final class TypeCycleObservation {
        private final IllegalStateException failure;
        private final int cacheSize;

        private TypeCycleObservation(
                IllegalStateException failure,
                int cacheSize) {
            this.failure = failure;
            this.cacheSize = cacheSize;
        }
    }

    private static final class TypeFixture {
        private final String expectedId;
        private final String childId;
        private final String grandchildId;
        private final String commonId;
        private final String siblingId;
        private final String unrelatedSameShapeId;
        private final String unrelatedDifferentShapeId;
        private final Map<String, Node> definitions;

        private TypeFixture(String expectedId,
                            String childId,
                            String grandchildId,
                            String commonId,
                            String siblingId,
                            String unrelatedSameShapeId,
                            String unrelatedDifferentShapeId,
                            Map<String, Node> definitions) {
            this.expectedId = expectedId;
            this.childId = childId;
            this.grandchildId = grandchildId;
            this.commonId = commonId;
            this.siblingId = siblingId;
            this.unrelatedSameShapeId = unrelatedSameShapeId;
            this.unrelatedDifferentShapeId = unrelatedDifferentShapeId;
            this.definitions = definitions;
        }

        private static TypeFixture create() {
            Node expected = sameShapeDefinition("Expected Event");
            String expectedId = BlueIdCalculator.calculateBlueId(expected);
            Node child = sameShapeDefinition("Child Event").type(reference(expectedId));
            String childId = BlueIdCalculator.calculateBlueId(child);
            Node grandchild = sameShapeDefinition("Grandchild Event").type(reference(childId));
            String grandchildId = BlueIdCalculator.calculateBlueId(grandchild);
            Node common = sameShapeDefinition("Common Event");
            String commonId = BlueIdCalculator.calculateBlueId(common);
            Node sibling = sameShapeDefinition("Sibling Event").type(reference(commonId));
            String siblingId = BlueIdCalculator.calculateBlueId(sibling);
            Node unrelatedSameShape = sameShapeDefinition("Unrelated Same Shape Event");
            String unrelatedSameShapeId = BlueIdCalculator.calculateBlueId(unrelatedSameShape);
            Node unrelatedDifferentShape = new Node()
                    .name("Unrelated Different Shape Event")
                    .properties("different", requiredText());
            String unrelatedDifferentShapeId = BlueIdCalculator.calculateBlueId(unrelatedDifferentShape);
            Map<String, Node> definitions = new LinkedHashMap<String, Node>();
            definitions.put(expectedId, expected);
            definitions.put(childId, child);
            definitions.put(grandchildId, grandchild);
            definitions.put(commonId, common);
            definitions.put(siblingId, sibling);
            definitions.put(unrelatedSameShapeId, unrelatedSameShape);
            definitions.put(unrelatedDifferentShapeId, unrelatedDifferentShape);
            return new TypeFixture(
                    expectedId,
                    childId,
                    grandchildId,
                    commonId,
                    siblingId,
                    unrelatedSameShapeId,
                    unrelatedDifferentShapeId,
                    definitions);
        }

        private Blue blue() {
            return new Blue(new MapProvider(definitions));
        }

        private ContractMatchingService matchingService() {
            return new ContractMatchingService(blue());
        }

        private static Node sameShapeDefinition(String name) {
            return new Node().name(name).properties("kind", requiredText());
        }

        private static Node requiredText() {
            return new Node()
                    .type(reference(TEXT_TYPE_BLUE_ID))
                    .schema(new Schema().required(true));
        }

        private Node event(String typeBlueId) {
            if (unrelatedDifferentShapeId.equals(typeBlueId)) {
                return differentEvent();
            }
            return new Node()
                    .type(reference(typeBlueId))
                    .properties("kind", new Node().value("accepted"));
        }

        private Node materializedEvent(Blue blue, String typeBlueId) {
            return blue.resolveToSnapshot(event(typeBlueId)).resolvedRoot();
        }

        private Node materializedType(Blue blue, String typeBlueId) {
            Node materialized = materializedEvent(blue, typeBlueId).getType();
            if (materialized == null
                    || !typeBlueId.equals(materialized.getBlueId())
                    || materialized.isReferenceOnly()) {
                throw new IllegalStateException(
                        "Fixture did not materialize the expected type "
                                + typeBlueId);
            }
            return materialized;
        }

        private Node untypedEvent() {
            return new Node().properties("kind", new Node().value("accepted"));
        }

        private Node pattern(String typeBlueId) {
            return new Node().type(reference(typeBlueId));
        }

        private Node differentEvent() {
            return new Node()
                    .type(reference(unrelatedDifferentShapeId))
                    .properties("different", new Node().value("value"));
        }
    }

    private static class MapProvider implements NodeProvider {
        protected final Map<String, Node> definitions;

        private MapProvider(Map<String, Node> definitions) {
            this.definitions = definitions;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node definition = definitions.get(blueId);
            return definition != null
                    ? Collections.singletonList(definition.clone())
                    : null;
        }
    }

    private static final class VerifiedCyclicMapProvider
            extends MapProvider implements CyclicAwareNodeProvider {
        private final Map<String, String> verifiedBlueIds;
        private final CyclicSetProof proof;

        private VerifiedCyclicMapProvider(Map<String, Node> definitions) {
            this(prepareCyclicDefinitions(definitions));
        }

        private VerifiedCyclicMapProvider(
                PreparedCyclicDefinitions prepared) {
            super(prepared.materializedDefinitions);
            this.verifiedBlueIds = prepared.verifiedBlueIds;
            this.proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                    prepared.placeholders);
        }

        private String verifiedBlueId(String symbolicBlueId) {
            String verified = verifiedBlueIds.get(symbolicBlueId);
            return verified != null ? verified : symbolicBlueId;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return definitions.containsKey(blueId)
                    ? CyclicSetProofResult.found(proof)
                    : CyclicSetProofResult.notFound();
        }
    }

    private static PreparedCyclicDefinitions prepareCyclicDefinitions(
            Map<String, Node> symbolicDefinitions) {
        List<String> symbolicBlueIds =
                new ArrayList<>(symbolicDefinitions.keySet());
        Map<String, Integer> indexBySymbol =
                new LinkedHashMap<String, Integer>();
        for (int index = 0; index < symbolicBlueIds.size(); index++) {
            indexBySymbol.put(symbolicBlueIds.get(index), index);
        }

        List<Node> placeholders =
                new ArrayList<Node>(symbolicBlueIds.size());
        for (String symbolicBlueId : symbolicBlueIds) {
            Node placeholder =
                    symbolicDefinitions.get(symbolicBlueId).clone();
            replaceReferencesWithPlaceholders(
                    placeholder, indexBySymbol);
            placeholders.add(placeholder);
        }
        List<String> calculatedBlueIds =
                CircularBlueIdCalculator.calculateCircularSetBlueIds(
                        placeholders);

        Map<String, String> verifiedBlueIds =
                new LinkedHashMap<String, String>();
        Map<String, Node> materialized =
                new LinkedHashMap<String, Node>();
        for (int index = 0; index < symbolicBlueIds.size(); index++) {
            String calculatedBlueId = calculatedBlueIds.get(index);
            verifiedBlueIds.put(
                    symbolicBlueIds.get(index), calculatedBlueId);
            Node definition = placeholders.get(index).clone();
            replacePlaceholdersWithReferences(
                    definition, calculatedBlueIds);
            materialized.put(calculatedBlueId, definition);
        }
        return new PreparedCyclicDefinitions(
                materialized, verifiedBlueIds, placeholders);
    }

    private static void replaceReferencesWithPlaceholders(
            Node node,
            Map<String, Integer> indexBySymbol) {
        if (node == null) {
            return;
        }
        Integer targetIndex = indexBySymbol.get(node.getBlueId());
        if (targetIndex != null) {
            node.blueId("this#" + targetIndex);
        }
        replaceReferencesWithPlaceholders(node.getType(), indexBySymbol);
        replaceReferencesWithPlaceholders(node.getItemType(), indexBySymbol);
        replaceReferencesWithPlaceholders(node.getKeyType(), indexBySymbol);
        replaceReferencesWithPlaceholders(node.getValueType(), indexBySymbol);
        replaceReferencesWithPlaceholders(node.getBlue(), indexBySymbol);
        replaceReferencesWithPlaceholders(node.getContracts(), indexBySymbol);
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                replaceReferencesWithPlaceholders(child, indexBySymbol);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                replaceReferencesWithPlaceholders(child, indexBySymbol);
            }
        }
    }

    private static void replacePlaceholdersWithReferences(
            Node node,
            List<String> calculatedBlueIds) {
        if (node == null) {
            return;
        }
        String blueId = node.getBlueId();
        if (blueId != null && blueId.startsWith("this#")) {
            node.blueId(calculatedBlueIds.get(
                    Integer.parseInt(blueId.substring("this#".length()))));
        }
        replacePlaceholdersWithReferences(node.getType(), calculatedBlueIds);
        replacePlaceholdersWithReferences(
                node.getItemType(), calculatedBlueIds);
        replacePlaceholdersWithReferences(
                node.getKeyType(), calculatedBlueIds);
        replacePlaceholdersWithReferences(
                node.getValueType(), calculatedBlueIds);
        replacePlaceholdersWithReferences(node.getBlue(), calculatedBlueIds);
        replacePlaceholdersWithReferences(
                node.getContracts(), calculatedBlueIds);
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                replacePlaceholdersWithReferences(
                        child, calculatedBlueIds);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                replacePlaceholdersWithReferences(
                        child, calculatedBlueIds);
            }
        }
    }

    private static final class PreparedCyclicDefinitions {
        private final Map<String, Node> materializedDefinitions;
        private final Map<String, String> verifiedBlueIds;
        private final List<Node> placeholders;

        private PreparedCyclicDefinitions(
                Map<String, Node> materializedDefinitions,
                Map<String, String> verifiedBlueIds,
                List<Node> placeholders) {
            this.materializedDefinitions = materializedDefinitions;
            this.verifiedBlueIds = verifiedBlueIds;
            this.placeholders = placeholders;
        }
    }

    private static class CountingMapProvider extends MapProvider {
        private final AtomicInteger lookupCount = new AtomicInteger();

        private CountingMapProvider(Map<String, Node> definitions) {
            super(definitions);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            lookupCount.incrementAndGet();
            return super.fetchByBlueId(blueId);
        }

        private int lookupCount() {
            return lookupCount.get();
        }

        private void resetLookupCount() {
            lookupCount.set(0);
        }
    }

    private static final class MutableCountingProvider implements NodeProvider {
        private final Map<String, Node> definitions = new ConcurrentHashMap<String, Node>();
        private final AtomicInteger lookupCount = new AtomicInteger();

        private void put(String blueId, Node definition) {
            definitions.put(blueId, definition.clone());
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            lookupCount.incrementAndGet();
            Node definition = definitions.get(blueId);
            return definition != null
                    ? Collections.singletonList(definition.clone())
                    : null;
        }

        private int lookupCount() {
            return lookupCount.get();
        }
    }

    private static final class BlockingProvider extends MapProvider {
        private final String blockedBlueId;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingProvider(Map<String, Node> definitions, String blockedBlueId) {
            super(definitions);
            this.blockedBlueId = blockedBlueId;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            if (blockedBlueId.equals(blueId)) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out awaiting provider release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted awaiting provider release", e);
                }
            }
            return super.fetchByBlueId(blueId);
        }

        private boolean awaitBlocked() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }
}
