package blue.language.processor;

import blue.language.Blue;
import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.model.MarkerContract;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeProviderWrapper;

import org.junit.jupiter.api.Test;

import java.time.Duration;
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

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerMatchContextDeclaredTypeLineageTest {

    @Test
    void exactIdentityIsRepresentationIndependentAndDoesNotReadTheProvider() {
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        Blue blue = new Blue(provider);
        ContractMatchingService matching = new ContractMatchingService(blue);
        Node pureEvent = types.event(types.expectedId);
        Node materializedEvent = types.materializedEvent(blue, types.expectedId);
        Node pureExpected = reference(types.expectedId);
        Node materializedExpected = types.materializedType(blue, types.expectedId);
        provider.resetLookupCount();

        assertTrue(context(pureEvent, matching)
                .eventDeclaredTypeIsSameOrDescendantOf(pureExpected));
        assertTrue(context(pureEvent, matching)
                .eventDeclaredTypeIsSameOrDescendantOf(materializedExpected));
        assertTrue(context(materializedEvent, matching)
                .eventDeclaredTypeIsSameOrDescendantOf(pureExpected));
        assertTrue(context(materializedEvent, matching)
                .eventDeclaredTypeIsSameOrDescendantOf(materializedExpected));
        assertEquals(0, provider.lookupCount());
    }

    @Test
    void directAndTransitiveAncestryAreRepresentationIndependent() {
        TypeFixture types = TypeFixture.create();
        Blue blue = types.blue();
        ContractMatchingService matching = new ContractMatchingService(blue);

        assertRepresentationMatrixMatches(types, blue, matching, types.childId, types.expectedId, true);
        assertRepresentationMatrixMatches(types, blue, matching, types.grandchildId, types.expectedId, true);
        assertRepresentationMatrixMatches(types, blue, matching, types.grandchildId, types.childId, true);
    }

    @Test
    void siblingsAndUnrelatedSameShapeTypesAreRejectedInEveryRepresentation() {
        TypeFixture types = TypeFixture.create();
        Blue blue = types.blue();
        ContractMatchingService matching = new ContractMatchingService(blue);

        assertRepresentationMatrixMatches(types, blue, matching, types.siblingId, types.childId, false);
        assertRepresentationMatrixMatches(
                types, blue, matching, types.unrelatedSameShapeId, types.expectedId, false);
        assertRepresentationMatrixMatches(
                types, blue, matching, types.unrelatedDifferentShapeId, types.expectedId, false);
    }

    @Test
    void coldMaterializedAndReconstructedPureQueriesAgree() {
        TypeFixture types = TypeFixture.create();
        Blue materializer = types.blue();
        Node materializedChild = types.materializedEvent(materializer, types.childId);
        Node materializedExpected = types.materializedType(materializer, types.expectedId);

        assertTrue(context(materializedChild, types.matchingService())
                .eventDeclaredTypeIsSameOrDescendantOf(materializedExpected));
        assertTrue(context(types.event(types.childId), types.matchingService())
                .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));

        Node materializedUnrelated = types.materializedEvent(
                materializer, types.unrelatedSameShapeId);
        assertFalse(context(materializedUnrelated, types.matchingService())
                .eventDeclaredTypeIsSameOrDescendantOf(materializedExpected));
        assertFalse(context(types.event(types.unrelatedSameShapeId), types.matchingService())
                .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
    }

    @Test
    void cachedDirectEdgesServePositiveAndDefinitiveNegativeChecks() {
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));
        HandlerMatchContext grandchild = context(types.event(types.grandchildId), matching);

        assertTrue(grandchild.eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertEquals(3, provider.lookupCount());

        assertTrue(grandchild.eventDeclaredTypeIsSameOrDescendantOf(reference(types.childId)));
        assertFalse(grandchild.eventDeclaredTypeIsSameOrDescendantOf(reference(types.siblingId)));
        assertFalse(grandchild.eventDeclaredTypeIsSameOrDescendantOf(
                reference(types.unrelatedSameShapeId)));
        assertEquals(3, provider.lookupCount());
        assertEquals(3, matching.declaredTypeLineageCacheSize());
    }

    @Test
    void unavailableAncestryIsNotCachedAndCanRecover() {
        TypeFixture types = TypeFixture.create();
        MutableCountingProvider provider = new MutableCountingProvider();
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));
        HandlerMatchContext child = context(types.event(types.childId), matching);

        assertFalse(child.eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertEquals(1, provider.lookupCount());
        assertEquals(0, matching.declaredTypeLineageCacheSize());

        provider.put(types.childId, types.definitions.get(types.childId));
        provider.put(types.expectedId, types.definitions.get(types.expectedId));

        assertTrue(child.eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertEquals(3, provider.lookupCount());
        assertEquals(2, matching.declaredTypeLineageCacheSize());
    }

    @Test
    void verifiedPrefixEdgesSurviveALaterUnavailableAncestorAndEnableRecovery() {
        TypeFixture types = TypeFixture.create();
        MutableCountingProvider provider = new MutableCountingProvider();
        provider.put(types.grandchildId, types.definitions.get(types.grandchildId));
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));
        HandlerMatchContext grandchild = context(types.event(types.grandchildId), matching);

        assertFalse(grandchild.eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertEquals(2, provider.lookupCount());
        assertEquals(1, matching.declaredTypeLineageCacheSize());

        provider.put(types.childId, types.definitions.get(types.childId));
        provider.put(types.expectedId, types.definitions.get(types.expectedId));

        assertTrue(grandchild.eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertEquals(4, provider.lookupCount(), "the verified grandchild edge must be reused");
        assertEquals(3, matching.declaredTypeLineageCacheSize());
    }

    @Test
    void identityFreeParentIsADistinctCachedTerminalFact() {
        TypeFixture types = TypeFixture.create();
        Node incomplete = new Node().type(new Node().name("Anonymous Parent"));
        MutableCountingProvider provider = new MutableCountingProvider();
        provider.put(types.childId, incomplete);
        ContractMatchingService matching = new ContractMatchingService(
                new Blue(NodeProviderWrapper.unverified(provider)));

        assertFalse(context(types.event(types.childId), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertEquals(1, provider.lookupCount());
        assertEquals(1, matching.declaredTypeLineageCacheSize());

        assertFalse(context(types.event(types.childId), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertEquals(1, provider.lookupCount());
    }

    @Test
    void referenceOnlyProviderResultThatMakesNoProgressIsNotCached() {
        TypeFixture types = TypeFixture.create();
        MutableCountingProvider provider = new MutableCountingProvider();
        provider.put(types.childId, reference(types.childId));
        ContractMatchingService matching = new ContractMatchingService(
                new Blue(NodeProviderWrapper.unverified(provider)));

        assertFalse(context(types.event(types.childId), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertEquals(1, provider.lookupCount());
        assertEquals(0, matching.declaredTypeLineageCacheSize());
    }

    @Test
    void ambiguousProviderResultPreservesDeterministicFailureAndIsNotCached() {
        TypeFixture types = TypeFixture.create();
        NodeProvider ambiguous = blueId -> Arrays.asList(new Node(), new Node());
        ContractMatchingService matching = new ContractMatchingService(
                new Blue(NodeProviderWrapper.unverified(ambiguous)));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                context(types.event(types.childId), matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));

        assertTrue(failure.getMessage().contains("Expected a single node"));
        assertEquals(0, matching.declaredTypeLineageCacheSize());
    }

    @Test
    void providerVerificationFailureIsPropagatedAndNotCached() {
        TypeFixture types = TypeFixture.create();
        NodeProvider wrongContent = blueId -> Collections.singletonList(
                new Node().name("Content with a different BlueId"));
        ContractMatchingService matching = new ContractMatchingService(new Blue(wrongContent));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                context(types.event(types.childId), matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));

        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertEquals(0, matching.declaredTypeLineageCacheSize());
    }

    @Test
    void malformedActualAndExpectedIdsFailBeforeEqualityOrProviderAccess() {
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));

        IllegalArgumentException malformedActual = assertThrows(IllegalArgumentException.class, () ->
                context(types.event("not-a-blue-id"), matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(reference("not-a-blue-id")));
        IllegalArgumentException malformedExpected = assertThrows(IllegalArgumentException.class, () ->
                context(types.event(types.childId), matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(reference("not-a-blue-id")));

        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(malformedActual));
        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(malformedExpected));
        assertEquals(0, provider.lookupCount());
    }

    @Test
    void malformedParentIdFailsAndDoesNotCreateACacheEntry() {
        TypeFixture types = TypeFixture.create();
        Map<String, Node> definitions = new LinkedHashMap<String, Node>();
        definitions.put(types.childId, new Node().type(reference("not-a-blue-id")));
        ContractMatchingService matching = unverifiedMatching(definitions);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                context(types.event(types.childId), matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));

        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(failure));
        assertEquals(0, matching.declaredTypeLineageCacheSize());
    }

    @Test
    void selfCycleAndTwoNodeCycleFailAsTypeCycle() {
        String a = syntheticId("Cycle A");
        String b = syntheticId("Cycle B");
        Map<String, Node> cyclic = new LinkedHashMap<String, Node>();
        cyclic.put(a, new Node().type(reference(a)));
        assertTypeCycle(cyclic, a, syntheticId("Expected"));

        cyclic.clear();
        cyclic.put(a, new Node().type(reference(b)));
        cyclic.put(b, new Node().type(reference(a)));
        assertTypeCycle(cyclic, a, syntheticId("Expected"));
    }

    @Test
    void ancestryMatchDoesNotHideALaterCycle() {
        String a = syntheticId("Cycle after expected A");
        String expected = syntheticId("Cycle after expected Expected");
        Map<String, Node> cyclic = new LinkedHashMap<String, Node>();
        cyclic.put(a, new Node().type(reference(expected)));
        cyclic.put(expected, new Node().type(reference(a)));

        assertTypeCycle(cyclic, a, expected);
    }

    @Test
    void twentyThousandLevelLineageAndDeepCycleAreIterative() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            int depth = 20_000;
            String expected = syntheticId("Deep root");
            Map<String, Node> valid = deepChain(depth, expected, null);
            String candidate = syntheticId("Deep type 0");
            ContractMatchingService validMatching = unverifiedMatching(valid);

            assertTrue(context(new Node().type(reference(candidate)), validMatching)
                    .eventDeclaredTypeIsSameOrDescendantOf(reference(expected)));
            assertEquals(DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT,
                    validMatching.declaredTypeLineageCacheSize());

            String cycleTarget = syntheticId("Deep type " + (depth - 1));
            Map<String, Node> cyclic = deepChain(depth, expected, cycleTarget);
            assertTypeCycle(cyclic, candidate, expected);
        });
    }

    @Test
    void directEdgeCacheIsLazyBoundedAndLeastRecentlyUsed() {
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
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));

        assertEquals(64, DeclaredTypeLineageMatcher.CACHE_INITIAL_CAPACITY);
        assertEquals(0, matching.declaredTypeLineageCacheSize());

        for (int index = 0; index < children.length - 1; index++) {
            assertTrue(context(new Node().type(reference(children[index])), matching)
                    .eventDeclaredTypeIsSameOrDescendantOf(reference(root)));
        }
        assertEquals(DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT,
                matching.declaredTypeLineageCacheSize());
        assertEquals(DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT, provider.lookupCount());

        assertTrue(context(new Node().type(reference(children[0])), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(root)));
        assertEquals(DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT, provider.lookupCount());

        assertTrue(context(new Node().type(reference(children[children.length - 1])), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(root)));
        assertEquals(DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT + 1, provider.lookupCount());

        assertTrue(context(new Node().type(reference(children[0])), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(root)));
        assertEquals(DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT + 1, provider.lookupCount(),
                "the recently accessed first edge must remain resident");

        assertTrue(context(new Node().type(reference(children[1])), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(root)));
        assertEquals(DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT + 2, provider.lookupCount(),
                "the least-recently-used second edge must have been evicted");
        assertEquals(DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT,
                matching.declaredTypeLineageCacheSize());
    }

    @Test
    void providerTraversalDoesNotHoldTheSharedCacheLock() throws Exception {
        TypeFixture types = TypeFixture.create();
        BlockingProvider provider = new BlockingProvider(types.definitions, types.childId);
        ContractMatchingService matching = new ContractMatchingService(
                new Blue(NodeProviderWrapper.unverified(provider)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            assertTrue(context(types.event(types.siblingId), matching)
                    .eventDeclaredTypeIsSameOrDescendantOf(reference(types.commonId)));
            Future<Boolean> blocked = executor.submit(() -> context(types.event(types.childId), matching)
                    .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
            assertTrue(provider.awaitBlocked());

            Future<Boolean> warm = executor.submit(() -> context(types.event(types.siblingId), matching)
                    .eventDeclaredTypeIsSameOrDescendantOf(reference(types.commonId)));
            assertTrue(warm.get(1, TimeUnit.SECONDS));

            provider.release();
            assertTrue(blocked.get(5, TimeUnit.SECONDS));
            assertTrue(matching.declaredTypeLineageCacheSize()
                    <= DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT);
        } finally {
            provider.release();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentWarmQueriesAreStableAndDoNotRepeatProviderWork() throws Exception {
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));
        HandlerMatchContext grandchild = context(types.event(types.grandchildId), matching);
        assertTrue(grandchild.eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertEquals(3, provider.lookupCount());

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            @SuppressWarnings("unchecked")
            Future<Boolean>[] results = new Future[64];
            for (int index = 0; index < results.length; index++) {
                results[index] = executor.submit(() ->
                        grandchild.eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
            }
            for (Future<Boolean> result : results) {
                assertTrue(result.get(5, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(3, provider.lookupCount());
        assertEquals(3, matching.declaredTypeLineageCacheSize());
    }

    @Test
    void providerFreeServiceSupportsOnlyExactIdentity() {
        TypeFixture types = TypeFixture.create();
        Blue materializer = types.blue();
        ContractMatchingService matching = new ContractMatchingService();

        assertTrue(context(types.event(types.expectedId), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertTrue(context(types.materializedEvent(materializer, types.expectedId), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        types.materializedType(materializer, types.expectedId)));
        assertFalse(context(types.event(types.childId), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertFalse(context(types.materializedEvent(materializer, types.childId), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(
                        types.materializedType(materializer, types.expectedId)));
    }

    @Test
    void missingEventTypeIdentityOrExpectedTypeIsIncompatibleWithoutTraversal() {
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));

        assertFalse(context(null, matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertFalse(context(types.untypedEvent(), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(reference(types.expectedId)));
        assertFalse(context(types.event(types.expectedId), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(null));
        assertFalse(context(types.event(types.expectedId), matching)
                .eventDeclaredTypeIsSameOrDescendantOf(new Node().name("Anonymous")));
        assertEquals(0, provider.lookupCount());
    }

    @Test
    void genericMatcherRetainsStructuralCompatibilityForUnrelatedTypes() {
        TypeFixture types = TypeFixture.create();
        ContractMatchingService matching = types.matchingService();

        assertTrue(matching.matches(
                types.event(types.unrelatedSameShapeId),
                types.pattern(types.expectedId)));
        assertFalse(matching.matches(
                types.differentEvent(),
                types.pattern(types.expectedId)));
    }

    private static void assertRepresentationMatrixMatches(TypeFixture types,
                                                           Blue blue,
                                                           ContractMatchingService matching,
                                                           String actualId,
                                                           String expectedId,
                                                           boolean expectedResult) {
        Node pureEvent = types.event(actualId);
        Node materializedEvent = types.materializedEvent(blue, actualId);
        Node pureExpected = reference(expectedId);
        Node materializedExpected = types.materializedType(blue, expectedId);

        assertEquals(expectedResult, context(pureEvent, matching)
                .eventDeclaredTypeIsSameOrDescendantOf(pureExpected));
        assertEquals(expectedResult, context(pureEvent, matching)
                .eventDeclaredTypeIsSameOrDescendantOf(materializedExpected));
        assertEquals(expectedResult, context(materializedEvent, matching)
                .eventDeclaredTypeIsSameOrDescendantOf(pureExpected));
        assertEquals(expectedResult, context(materializedEvent, matching)
                .eventDeclaredTypeIsSameOrDescendantOf(materializedExpected));
    }

    private static void assertTypeCycle(Map<String, Node> definitions,
                                        String candidate,
                                        String expected) {
        ContractMatchingService matching = unverifiedMatching(definitions);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                context(new Node().type(reference(candidate)), matching)
                        .eventDeclaredTypeIsSameOrDescendantOf(reference(expected)));

        assertTrue(failure.getMessage().startsWith("Type cycle in declared type ancestry:"));
        assertEquals(BlueLanguageErrorCategory.TypeCycle,
                BlueLanguageErrorClassifier.classify(failure));
        assertTrue(matching.declaredTypeLineageCacheSize() > 0,
                "verified direct edges before cycle detection remain reusable");
        assertTrue(matching.declaredTypeLineageCacheSize()
                <= DeclaredTypeLineageMatcher.CACHE_ENTRY_LIMIT);
    }

    private static Map<String, Node> deepChain(int depth, String root, String finalParent) {
        Map<String, Node> definitions = new LinkedHashMap<String, Node>();
        for (int index = 0; index < depth; index++) {
            String current = syntheticId("Deep type " + index);
            String parent = index + 1 < depth
                    ? syntheticId("Deep type " + (index + 1))
                    : (finalParent != null ? finalParent : root);
            definitions.put(current, new Node().type(reference(parent)));
        }
        definitions.put(root, new Node().name("Deep root"));
        return definitions;
    }

    private static ContractMatchingService unverifiedMatching(Map<String, Node> definitions) {
        return new ContractMatchingService(new Blue(
                NodeProviderWrapper.unverified(new MapProvider(definitions))));
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
            assertNotNull(materialized);
            assertEquals(typeBlueId, materialized.getBlueId());
            assertFalse(materialized.isReferenceOnly());
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
        private final Map<String, Node> definitions;

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
