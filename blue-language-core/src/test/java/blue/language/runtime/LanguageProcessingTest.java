package blue.language.runtime;

import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.NodeProviderOutcome;
import blue.language.conformance.ConformanceEngine;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.registry.NodeProviderWrapper;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static blue.language.model.wire.BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LanguageProcessingTest {

    private static final String CACHE_DERIVED_SNAPSHOTS =
            "derivedResolvedSnapshots";
    private static final String CACHE_RECENT_PROCESSING_SNAPSHOTS =
            "recentProcessingSnapshots";
    private static final String CACHE_VERIFIED_REFERENCES =
            "verifiedReferences";

    @Test
    void shouldIdentifyConstrainedInlineTypeDeclarationWithoutInstanceValidation() {
        Node parent = new Node()
                .name("Constrained inline parent")
                .properties(
                        "requiredText",
                        new Node()
                                .type(new Node().blueId(
                                        blue.language.model.wire
                                                .BlueLanguageConstants
                                                .TEXT_TYPE_BLUE_ID))
                                .schema(new Schema()
                                        .required(true)
                                        .minLength(3)));
        Node child = new Node()
                .name("Inline child")
                .type(parent.clone());
        Node validInstance = new Node()
                .type(child.clone())
                .properties("requiredText", new Node().value("valid"));

        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            CanonicalTypeIdentityEvidence declarationIdentity =
                    scope.resolveTypeDeclarationIdentity(child.clone());
            ResolvedSnapshot instance = scope.resolveTransient(validInstance);
            String instanceTypeIdentity = instance.canonicalTypeIdentities()
                    .requireCanonicalTypeBlueId(
                            instance.resolvedRoot().getType(),
                            instance.sourceRoot().getType());
            Node canonicalDeclaration = declarationIdentity
                    .canonicalTypeIdentityInput();

            assertEquals(instanceTypeIdentity, declarationIdentity.blueId());
            assertTrue(canonicalDeclaration.getType().isReferenceOnly(),
                    "inline parent must become a canonical type reference");
            assertEquals(
                    declarationIdentity.blueId(),
                    DirectBlueIdCalculator.calculateBlueId(
                            canonicalDeclaration));
        }
    }

    @Test
    void shouldReusePublishedProcessingSnapshotWithoutChangingSemantics() {
        // given
        CountingObserver observer = new CountingObserver();
        Node document = new Node().value("published");

        // when
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope(observer)) {
            ResolvedSnapshot first = scope.resolve(document);
            ResolvedSnapshot second = scope.resolve(document.clone());

            // then
            assertSame(first, second);
            assertEquals(1, observer.hits.get());
            assertEquals(1, observer.misses.get());
            assertEquals(2, observer.lookupCount.get());
            assertTrue(observer.totalLookupNanos.get() >= 0L);
        }
    }

    @Test
    void shouldKeepExactSourceSpellingAfterEquivalentCanonicalCacheWarmup() {
        Node exactSubject = new Node()
                .name("Source-backed cache subject")
                .properties("payload", new Node().value("present"));
        BasicNodeProvider provider = new BasicNodeProvider(exactSubject);
        String subjectBlueId = provider.getBlueIdByName(
                exactSubject.getName());
        Node referenced = new Node().properties(
                "subject", new Node().blueId(subjectBlueId));
        Node inlineSubject = provider.fetchFirstByBlueId(
                subjectBlueId).clone();
        inlineSubject.blueId(null);
        Node inline = new Node().properties(
                "subject", inlineSubject);

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            ResolvedSnapshot referencedFirst =
                    language.snapshots().resolve(referenced);
            ResolvedSnapshot inlineSecond =
                    language.snapshots().resolve(inline);
            ResolvedSnapshot cachedCanonical = language.snapshots()
                    .load(referencedFirst.blueId());

            assertEquals(referencedFirst.blueId(), inlineSecond.blueId());
            assertTrue(referencedFirst.isSourceBacked());
            assertTrue(inlineSecond.isSourceBacked());
            assertTrue(referencedFirst.sourceRoot()
                    .getNode("/subject").isReferenceOnly());
            assertFalse(inlineSecond.sourceRoot()
                    .getNode("/subject").isReferenceOnly());
            assertNotNull(referencedFirst.verifiedReferenceResolution());
            assertNotNull(inlineSecond.verifiedReferenceResolution());
            assertFalse(cachedCanonical.isSourceBacked());
        }
    }

    @Test
    void shouldReturnTypedExactProviderOutcomes() {
        // given
        Node exactContent = new Node().value("exact");
        String exactBlueId = DirectBlueIdCalculator.calculateBlueId(
                exactContent);
        String absentBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("absent"));
        String unavailableBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("unavailable"));
        String invalidBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("expected-but-invalid"));
        NodeProvider provider = providerWithOutcomes(
                exactBlueId,
                exactContent,
                unavailableBlueId,
                invalidBlueId);

        // when
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            BlueOperationResult<FrozenNode> found =
                    scope.materializeVerifiedExactReference(
                            reference(exactBlueId));
            BlueOperationResult<FrozenNode> absent =
                    scope.materializeVerifiedExactReference(
                            reference(absentBlueId));
            BlueOperationResult<FrozenNode> unavailable =
                    scope.materializeVerifiedExactReference(
                            reference(unavailableBlueId));
            BlueOperationResult<FrozenNode> invalid =
                    scope.materializeVerifiedExactReference(
                            reference(invalidBlueId));

            // then
            assertEquals(BlueOperationOutcome.ESTABLISHED,
                    found.outcome());
            assertEquals(exactBlueId,
                    found.requireEstablished().blueId());
            assertEquals(BlueOperationOutcome.ABSENT,
                    absent.outcome());
            assertEquals(BlueOperationOutcome.INCOMPLETE,
                    unavailable.outcome());
            assertEquals(NodeProviderOutcome.UNAVAILABLE,
                    unavailable.providerOutcome().orElse(null));
            assertTrue(unavailable.outstandingBlueIds().contains(
                    unavailableBlueId));
            assertEquals(BlueOperationOutcome.INVALID,
                    invalid.outcome());
            assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                    invalid.providerOutcome().orElse(null));
        }
    }

    @Test
    void shouldPreserveTypedCyclicProofUnavailability() {
        // given
        Node memberContent = new Node().value("member");
        String masterBlueId = DirectBlueIdCalculator.calculateBlueId(
                memberContent);
        String memberBlueId = masterBlueId + "#0";
        NodeProvider provider = new UnavailableCyclicProofProvider(
                memberBlueId, memberContent);

        // when
        BlueOperationResult<FrozenNode> result;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            result = scope.materializeVerifiedExactReference(
                    reference(memberBlueId));
        }

        // then
        assertEquals(BlueOperationOutcome.INCOMPLETE,
                result.outcome());
        assertEquals(NodeProviderOutcome.UNAVAILABLE,
                result.providerOutcome().orElse(null));
        assertTrue(result.outstandingBlueIds().contains(memberBlueId));
    }

    @Test
    void shouldReleaseSequenceLocalEvidenceOnClose() {
        // given
        Node exactContent = new Node().value("sequence-local");
        String blueId = DirectBlueIdCalculator.calculateBlueId(
                exactContent);
        AtomicInteger fetches = new AtomicInteger();
        NodeProvider provider = blueIdRequest -> {
            if (!blueId.equals(blueIdRequest)) {
                return null;
            }
            fetches.incrementAndGet();
            return Collections.singletonList(exactContent.clone());
        };

        // when
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope root =
                     language.processing().openScope()) {
            LanguageProcessing.Scope sequence =
                    root.transientSequence();
            sequence.materializeVerifiedExactReference(
                    reference(blueId));
            sequence.materializeVerifiedExactReference(
                    reference(blueId));
            sequence.close();

            // then
            assertEquals(1, fetches.get());
            assertFalse(sequence.isTransientStateCurrent());
            assertThrows(IllegalStateException.class,
                    () -> sequence.materializeVerifiedExactReference(
                            reference(blueId)));

            root.materializeVerifiedExactReference(reference(blueId));
            assertEquals(2, fetches.get());
        }
    }

    @Test
    void shouldNotReuseConstructionProviderOrWarmedCacheInStrictScope() {
        // given
        Node exactContent = new Node().value("construction");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exactContent);
        AtomicInteger constructionFetches = new AtomicInteger();
        NodeProvider constructionProvider = countingFoundProvider(
                blueId, exactContent, constructionFetches);
        NodeProvider invocationProvider = providerWithResult(
                blueId,
                NodeProviderResult.invalidEvidence(
                        "invocation evidence rejected"));

        // when
        BlueOperationResult<FrozenNode> result;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(constructionProvider)
                .build()) {
            try (LanguageProcessing.Scope constructionScope =
                         language.processing().openScope()) {
                assertEquals(BlueOperationOutcome.ESTABLISHED,
                        constructionScope
                                .materializeVerifiedExactReference(
                                        reference(blueId))
                                .outcome());
            }
            try (LanguageProcessing.Scope invocationScope =
                         language.processing().openScope(
                                 invocationProvider)) {
                result = invocationScope
                        .materializeVerifiedExactReference(
                                reference(blueId));
            }
        }

        // then
        assertEquals(BlueOperationOutcome.INVALID, result.outcome());
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                result.providerOutcome().orElse(null));
        assertEquals(1, constructionFetches.get());
    }

    @Test
    void shouldNotInsertBootstrapFallbackIntoStrictScope() {
        // given
        String textBlueId = BlueCoreTypeRegistry.INSTANCE.blueId(TEXT_TYPE);
        NodeProvider invocationProvider = blueId -> null;

        // when
        BlueOperationResult<FrozenNode> result;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope = language.processing()
                     .openScope(invocationProvider)) {
            result = scope.materializeVerifiedExactReference(
                    reference(textBlueId));
        }

        // then
        assertEquals(BlueOperationOutcome.ABSENT, result.outcome());
        assertFalse(result.providerOutcome().isPresent());
    }

    @Test
    void shouldRestoreBootstrapForOrdinaryRuntimeConstruction() {
        // given
        String textBlueId = BlueCoreTypeRegistry.INSTANCE.blueId(TEXT_TYPE);
        NodeProvider strictMarker = StrictProviderAccess.isolate(
                blueId -> null);

        // when
        BlueOperationResult<FrozenNode> result;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(strictMarker)
                .build();
             LanguageProcessing.Scope scope = language.processing()
                     .openScope()) {
            result = scope.materializeVerifiedExactReference(
                    reference(textBlueId));
        }

        // then
        assertEquals(BlueOperationOutcome.ESTABLISHED, result.outcome());
    }

    @Test
    void shouldKeepInvocationProviderBorrowedAndGuardScopedRuntimeAccess() {
        // given
        Node exactContent = new Node().name("borrowed");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exactContent);
        CloseTrackingProvider provider = new CloseTrackingProvider(
                blueId, exactContent);
        LanguageRuntimeAccess access;
        NodeProvider guardedProvider;

        // when
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            LanguageProcessing.Scope scope = language.processing()
                    .openScope(provider);
            access = scope.runtimeAccess();
            guardedProvider = access.getNodeProvider();
            assertEquals(NodeProviderOutcome.FOUND,
                    guardedProvider.fetchResultByBlueId(blueId).outcome());
            scope.close();

            // then
            assertFalse(provider.closed.get());
            assertThrows(IllegalStateException.class,
                    () -> guardedProvider.fetchResultByBlueId(blueId));
            assertThrows(IllegalStateException.class,
                    access::cachePolicy);
        }
    }

    @Test
    void shouldShareOnlyInvocationLocalEvidenceWithChildSequences() {
        // given
        Node exactContent = new Node().name("invocation-child");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exactContent);
        AtomicInteger fetches = new AtomicInteger();
        NodeProvider provider = countingFoundProvider(
                blueId, exactContent, fetches);

        // when
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope root = language.processing()
                     .openScope(provider)) {
            BlueOperationResult<FrozenNode> rootResult = root
                    .materializeVerifiedExactReference(reference(blueId));
            LanguageProcessing.Scope child = root.transientSequence();
            BlueOperationResult<FrozenNode> childResult = child
                    .materializeVerifiedExactReference(reference(blueId));
            child.close();
            BlueOperationResult<FrozenNode> retainedRootResult = root
                    .materializeVerifiedExactReference(reference(blueId));

            // then
            assertEquals(BlueOperationOutcome.ESTABLISHED,
                    rootResult.outcome());
            assertEquals(BlueOperationOutcome.ESTABLISHED,
                    childResult.outcome());
            assertEquals(BlueOperationOutcome.ESTABLISHED,
                    retainedRootResult.outcome());
            assertEquals(1, fetches.get());
        }
    }

    @Test
    void shouldNotPublishStrictScopeStateIntoRuntimeCaches() {
        // given
        Node exactContent = new Node().name("strict-local");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exactContent);
        NodeProvider provider = countingFoundProvider(
                blueId, exactContent, new AtomicInteger());

        // when
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            try (LanguageProcessing.Scope scope = language.processing()
                    .openScope(provider)) {
                scope.materializeVerifiedExactReference(reference(blueId));
                ResolvedSnapshot snapshot = scope.resolve(Nodes.emptyObject());
                scope.publish(snapshot);
            }

            // then
            assertEquals(0, language.snapshots().stats()
                    .region(CACHE_VERIFIED_REFERENCES).entries());
            assertEquals(0, language.snapshots().stats()
                    .region(CACHE_DERIVED_SNAPSHOTS).entries());
            assertEquals(0, language.snapshots().stats()
                    .region(CACHE_RECENT_PROCESSING_SNAPSHOTS).entries());
        }
    }

    @Test
    void shouldKeepStructuralReferencesColdInsidePreservedInlineSubtree() {
        // given
        String schemaBlueId = blueId("preserved schema");
        String contractsBlueId = blueId("preserved contracts");
        String itemTypeBlueId = blueId("preserved item type");
        String keyTypeBlueId = blueId("preserved key type");
        String valueTypeBlueId = blueId("preserved value type");
        Node authored = new Node()
                .name("Authored preserved subtree")
                .schema(new Schema().blueId(schemaBlueId))
                .contracts(new Node().blueId(contractsBlueId))
                .properties(
                        "listShape",
                        new Node()
                                .type(new Node().blueId(
                                        LIST_TYPE_BLUE_ID))
                                .itemType(new Node().blueId(
                                        itemTypeBlueId)),
                        "dictionaryShape",
                        new Node()
                                .type(new Node().blueId(
                                        DICTIONARY_TYPE_BLUE_ID))
                                .keyType(new Node().blueId(
                                        keyTypeBlueId))
                                .valueType(new Node().blueId(
                                        valueTypeBlueId)),
                        "authored",
                        new Node().value("unchanged"));
        Node document = new Node().properties(
                "preserved", authored,
                "ordinary", new Node().value("resolved normally"));
        Set<String> preservedBlueIds = new java.util.HashSet<>(Arrays.asList(
                schemaBlueId,
                contractsBlueId,
                itemTypeBlueId,
                keyTypeBlueId,
                valueTypeBlueId));
        AtomicInteger preservedProviderReads = new AtomicInteger();
        NodeProvider provider = blueId -> {
            if (preservedBlueIds.contains(blueId)) {
                preservedProviderReads.incrementAndGet();
            }
            return null;
        };

        // when
        ResolvedSnapshot snapshot;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope = language.processing()
                     .openScope(provider)) {
            snapshot = scope.resolveTransientPreservingPaths(
                    document,
                    Collections.singleton("/preserved"));
        }

        // then
        Node retained = snapshot.resolvedRoot()
                .getProperties().get("preserved");
        assertEquals(0, preservedProviderReads.get());
        assertFalse(snapshot.isResolutionComplete());
        assertTrue(snapshot.canonicalTypeIdentities()
                .hasCompleteCoverage());
        assertTrue(snapshot.hasCanonicalIdentity());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        snapshot.sourceRoot()),
                snapshot.blueId());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(authored),
                DirectBlueIdCalculator.calculateBlueId(retained));
        assertTrue(retained.getSchema().isReferenceOnly());
        assertEquals(schemaBlueId, retained.getSchema().getBlueId());
        assertTrue(retained.getContracts().isReferenceOnly());
        assertEquals(contractsBlueId,
                retained.getContracts().getBlueId());
        Node retainedList = retained.getProperties().get("listShape");
        Node retainedDictionary = retained.getProperties()
                .get("dictionaryShape");
        assertEquals(itemTypeBlueId,
                retainedList.getItemType().getBlueId());
        assertEquals(keyTypeBlueId,
                retainedDictionary.getKeyType().getBlueId());
        assertEquals(valueTypeBlueId,
                retainedDictionary.getValueType().getBlueId());
    }

    @Test
    void shouldKeepInheritedMatcherOverlayOpaqueAtPreservedPath() {
        // given
        Node eventType = new Node().properties(
                "document",
                new Node().schema(new Schema().required(true)));
        String eventTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                eventType);
        Node handlerType = new Node().properties(
                "matcher",
                new Node().description("Inherited matcher declaration"));
        String handlerTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                handlerType);
        Node partialMatcher = new Node()
                .type(new Node().blueId(eventTypeBlueId))
                .properties("marker", new Node().value(true));
        Node document = new Node()
                .type(new Node().blueId(handlerTypeBlueId))
                .properties(
                        "matcher", partialMatcher,
                        "ordinary", new Node().value("resolved"));
        AtomicInteger handlerTypeProviderReads = new AtomicInteger();
        AtomicInteger eventTypeProviderReads = new AtomicInteger();
        NodeProvider provider = blueId -> {
            if (handlerTypeBlueId.equals(blueId)) {
                handlerTypeProviderReads.incrementAndGet();
                return Collections.singletonList(handlerType.clone());
            }
            if (eventTypeBlueId.equals(blueId)) {
                eventTypeProviderReads.incrementAndGet();
                return Collections.singletonList(eventType.clone());
            }
            return null;
        };

        // when
        ResolvedSnapshot snapshot;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope = language.processing()
                     .openScope(provider)) {
            snapshot = scope.resolveTransientPreservingPaths(
                    document,
                    Collections.singleton("/matcher"));
        }

        // then
        assertTrue(handlerTypeProviderReads.get() > 0);
        assertEquals(0, eventTypeProviderReads.get());
        assertFalse(snapshot.isResolutionComplete());
        assertTrue(snapshot.canonicalTypeIdentities()
                .hasCompleteCoverage());
        assertTrue(snapshot.hasCanonicalIdentity());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(document),
                snapshot.blueId());
        Node retained = snapshot.resolvedRoot()
                .getProperties().get("matcher");
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(partialMatcher),
                DirectBlueIdCalculator.calculateBlueId(retained));
        assertEquals(eventTypeBlueId,
                retained.getType().getBlueId());
        assertTrue(retained.getType().isReferenceOnly());
        assertNull(retained.getDescription());
        assertEquals(1, retained.getProperties().size());
        assertTrue(retained.getProperties().containsKey("marker"));
        assertEquals(true,
                retained.getProperties().get("marker").getValue());

        // and: ordinary resolution still validates complete event instances
        handlerTypeProviderReads.set(0);
        eventTypeProviderReads.set(0);
        IllegalArgumentException failure;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope = language.processing()
                     .openScope(provider)) {
            failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> scope.resolveTransient(document.clone()));
        }
        assertTrue(handlerTypeProviderReads.get() > 0);
        assertTrue(eventTypeProviderReads.get() > 0);
        assertTrue(failure.getMessage().contains("/matcher/document"));
        assertTrue(failure.getMessage().contains("Required node"));
    }

    @Test
    void shouldUseScopedProviderForRuntimeAccessAndConformance() {
        // given
        Node exactType = new Node().name("Invocation Type");
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(exactType);
        AtomicInteger constructionFetches = new AtomicInteger();
        AtomicInteger invocationFetches = new AtomicInteger();
        NodeProvider constructionProvider = countingFoundProvider(
                typeBlueId,
                new Node().name("Wrong Construction Type"),
                constructionFetches);
        NodeProvider invocationProvider = countingFoundProvider(
                typeBlueId, exactType, invocationFetches);

        // when
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(constructionProvider)
                .build();
             LanguageProcessing.Scope scope = language.processing()
                     .openScope(invocationProvider);
             blue.language.conformance.ConformanceEngine conformance =
                     scope.newConformanceEngine()) {
            blue.language.merge.TypeEvidenceResolution materialized =
                    scope.runtimeAccess()
                    .materializeTypeReferenceForMatching(
                            reference(typeBlueId));
            boolean conformant = conformance.conforms(
                    new Node().type(new Node().blueId(typeBlueId)));

            // then
            assertNotNull(materialized);
            assertEquals(
                    "Invocation Type",
                    materialized.resolvedRoot().getName());
            assertTrue(conformant);
            assertEquals(0, constructionFetches.get());
            assertEquals(1, invocationFetches.get());
        }
    }

    @Test
    void shouldNotInsertBootstrapFallbackIntoScopedConformance() {
        // given
        String textBlueId = BlueCoreTypeRegistry.INSTANCE.blueId(TEXT_TYPE);
        AtomicInteger invocationFetches = new AtomicInteger();
        NodeProvider invocationProvider = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                invocationFetches.incrementAndGet();
                return NodeProviderResult.invalidEvidence(
                        "strict provider rejects bootstrap substitution");
            }
        };

        // when
        String unrelatedBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("unrelated ancestor"));
        IllegalArgumentException failure;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope = language.processing()
                     .openScope(invocationProvider);
             ConformanceEngine conformance =
                     scope.newConformanceEngine()) {
            failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> conformance.isSubtypeOf(
                            textBlueId, unrelatedBlueId));
        }

        // then
        assertTrue(failure.getMessage().contains(
                "strict provider rejects bootstrap substitution"));
        assertTrue(invocationFetches.get() > 0);
    }

    @Test
    void shouldPreserveVerifiedCyclicMembersInScopedConformance() {
        // given
        Node declaredSet = new Node().items(
                new Node()
                        .name("Scoped Cyclic A")
                        .properties(
                                "next",
                                new Node().type(
                                        new Node().blueId("this#1"))),
                new Node()
                        .name("Scoped Cyclic B")
                        .properties(
                                "next",
                                new Node().type(
                                        new Node().blueId("this#0"))));
        BasicNodeProvider provider = new BasicNodeProvider(declaredSet);
        String memberBlueId = provider.getBlueIdByName(
                "Scoped Cyclic A");
        String unrelatedBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Unrelated Type"));

        // when
        boolean descendant;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope = language.processing()
                     .openScope(provider);
             ConformanceEngine conformance =
                     scope.newConformanceEngine()) {
            descendant = conformance.isSubtypeOf(
                    memberBlueId, unrelatedBlueId);
        }

        // then
        assertFalse(descendant);
    }

    @Test
    void shouldInvalidateEscapedConformanceEngineWhenScopeCloses() {
        // given
        CloseTrackingProvider provider = new CloseTrackingProvider(
                "unused",
                new Node().name("unused"));
        ConformanceEngine escaped;

        // when
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            LanguageProcessing.Scope scope = language.processing()
                    .openScope(provider);
            escaped = scope.newConformanceEngine();
            scope.close();

            // then
            assertThrows(IllegalStateException.class,
                    () -> escaped.conforms(new Node().value("after-close")));
            assertThrows(IllegalStateException.class,
                    escaped::supportsIncrementalValueResolution);
            assertFalse(provider.closed.get());
        }
    }

    @Test
    void shouldInvalidateEscapedConformanceEngineWhenRuntimeCloses() {
        // given
        CloseTrackingProvider provider = new CloseTrackingProvider(
                "unused",
                new Node().name("unused"));
        BlueLanguage language = BlueLanguage.builder().build();
        LanguageProcessing.Scope scope = language.processing()
                .openScope(provider);
        ConformanceEngine escaped = scope.newConformanceEngine();

        // when
        language.close();

        // then
        assertThrows(IllegalStateException.class,
                () -> escaped.conforms(new Node().value("after-close")));
        assertFalse(scope.isTransientStateCurrent());
        assertFalse(provider.closed.get());
        scope.close();
        language.close();
    }

    @Test
    void shouldCloseUncachedTransientConformanceViewIndependently() {
        // given
        ConformanceEngine parent = new ConformanceEngine(
                blueId -> null,
                (target, source, provider, resolver, typeIdentities) -> {
                });
        ConformanceEngine transientView = parent.transientView();

        // when
        transientView.close();

        // then
        assertTrue(parent.conforms(new Node().value("parent remains open")));
        assertThrows(IllegalStateException.class,
                () -> transientView.conforms(
                        new Node().value("closed transient view")));
        parent.close();
    }

    @Test
    void shouldRejectConformanceCloseFromProviderCallbackWithoutDeadlock() {
        // given
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Callback Parent"));
        Node child = new Node()
                .name("Callback Child")
                .type(new Node().blueId(parentBlueId));
        String childBlueId =
                DirectBlueIdCalculator.calculateBlueId(child);
        AtomicReference<ConformanceEngine> engineReference =
                new AtomicReference<>();
        NodeProvider provider = blueId -> {
            if (!childBlueId.equals(blueId)) {
                return null;
            }
            engineReference.get().close();
            return Collections.singletonList(child.clone());
        };
        ConformanceEngine engine = new ConformanceEngine(
                provider,
                (target, source, suppliedProvider, resolver,
                 typeIdentities) -> {
                });
        engineReference.set(engine);

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> engine.isSubtypeOf(childBlueId, parentBlueId));

        // then
        assertEquals("Conformance engine cannot close from active work",
                failure.getMessage());
        assertFalse(engine.supportsIncrementalValueResolution());
        engine.close();
    }

    @Test
    void shouldRejectReentrantScopeAndRuntimeCloseWhileConcurrentCloseWaits()
            throws Exception {
        // given
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("reentrant close parent"));
        Node exactContent = new Node()
                .name("reentrant runtime close")
                .type(new Node().blueId(parentBlueId));
        String blueId = DirectBlueIdCalculator.calculateBlueId(exactContent);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch attemptReentrantClose = new CountDownLatch(1);
        AtomicReference<BlueLanguage> languageReference =
                new AtomicReference<>();
        AtomicReference<LanguageProcessing.Scope> scopeReference =
                new AtomicReference<>();
        ReentrantCloseProvider provider = new ReentrantCloseProvider(
                blueId,
                exactContent,
                providerEntered,
                attemptReentrantClose,
                languageReference,
                scopeReference);
        ExecutorService executor = daemonExecutor(2);
        BlueLanguage language = BlueLanguage.builder().build();
        languageReference.set(language);
        LanguageProcessing.Scope scope = language.processing()
                .openScope(provider);
        scopeReference.set(scope);
        ConformanceEngine scopedConformance =
                scope.newConformanceEngine();

        try {
            Future<Boolean> processing = executor.submit(
                    () -> scopedConformance.isSubtypeOf(
                            blueId, parentBlueId));
            assertTrue(providerEntered.await(5L, TimeUnit.SECONDS));

            AtomicReference<Thread> closingThread = new AtomicReference<>();
            CountDownLatch closeStarted = new CountDownLatch(1);
            Future<?> closing = executor.submit(() -> {
                closingThread.set(Thread.currentThread());
                closeStarted.countDown();
                language.close();
            });
            assertTrue(closeStarted.await(5L, TimeUnit.SECONDS));
            awaitBlocked(closingThread.get());

            // when
            attemptReentrantClose.countDown();
            boolean result = processing.get(
                    5L, TimeUnit.SECONDS);
            closing.get(5L, TimeUnit.SECONDS);

            // then
            assertTrue(result);
            assertNotNull(provider.scopeCloseFailure.get());
            assertNotNull(provider.runtimeCloseFailure.get());
            assertEquals(
                    "Language processing scope cannot close from active work",
                    provider.scopeCloseFailure.get().getMessage());
            assertEquals(
                    "Blue Language runtime cannot close from active work",
                    provider.runtimeCloseFailure.get().getMessage());
            assertFalse(provider.closed.get());
            assertTrue(language.isClosed());
        } finally {
            attemptReentrantClose.countDown();
            scopedConformance.close();
            scope.close();
            language.close();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldSerializeConcurrentScopeCloseThroughCompleteTeardown()
            throws Exception {
        // given
        CountDownLatch conformanceEntered = new CountDownLatch(1);
        CountDownLatch releaseConformance = new CountDownLatch(1);
        ConformanceEngine parent = new ConformanceEngine(
                blueId -> null,
                (target, source, provider, resolver, typeIdentities) -> {
                    conformanceEntered.countDown();
                    await(releaseConformance, "conformance release");
                });
        CloseTrackingProvider provider = new CloseTrackingProvider(
                "unused", new Node().name("unused"));
        ExecutorService executor = daemonExecutor(3);

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            LanguageProcessing.Scope scope = language.processing()
                    .openScope();
            ConformanceEngine scoped = scope
                    .transientConformanceEngine(parent);
            Future<Boolean> conformance = executor.submit(
                    () -> scoped.conforms(
                            new Node().value("blocking conformance")));
            assertTrue(conformanceEntered.await(5L, TimeUnit.SECONDS));

            CountDownLatch firstCloseStarted = new CountDownLatch(1);
            Future<?> firstClose = executor.submit(() -> {
                firstCloseStarted.countDown();
                scope.close();
            });
            assertTrue(firstCloseStarted.await(5L, TimeUnit.SECONDS));
            awaitScopeClosed(scope);

            CountDownLatch secondCloseStarted = new CountDownLatch(1);
            Future<?> secondClose = executor.submit(() -> {
                secondCloseStarted.countDown();
                scope.close();
            });
            assertTrue(secondCloseStarted.await(5L, TimeUnit.SECONDS));

            // when
            assertThrows(TimeoutException.class,
                    () -> secondClose.get(100L, TimeUnit.MILLISECONDS));
            releaseConformance.countDown();
            assertTrue(conformance.get(5L, TimeUnit.SECONDS));
            firstClose.get(5L, TimeUnit.SECONDS);
            secondClose.get(5L, TimeUnit.SECONDS);

            // then
            assertThrows(IllegalStateException.class,
                    () -> scoped.conforms(
                            new Node().value("after scope close")));
            assertFalse(provider.closed.get());
        } finally {
            releaseConformance.countDown();
            parent.close();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldIsolateConcurrentInvocationProviderOutcomes()
            throws Exception {
        // given
        Node exactContent = new Node().name("concurrent");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exactContent);
        AtomicInteger foundFetches = new AtomicInteger();
        AtomicInteger unavailableFetches = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        NodeProvider foundProvider = waitingProvider(
                blueId,
                NodeProviderResult.found(
                        Collections.singletonList(exactContent)),
                foundFetches,
                start);
        NodeProvider unavailableProvider = waitingProvider(
                blueId,
                NodeProviderResult.unavailable("request store offline"),
                unavailableFetches,
                start);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope foundScope = language.processing()
                     .openScope(foundProvider);
             LanguageProcessing.Scope unavailableScope =
                     language.processing().openScope(unavailableProvider)) {
            Future<BlueOperationResult<FrozenNode>> found = executor.submit(
                    () -> foundScope.materializeVerifiedExactReference(
                            reference(blueId)));
            Future<BlueOperationResult<FrozenNode>> unavailable =
                    executor.submit(() -> unavailableScope
                            .materializeVerifiedExactReference(
                                    reference(blueId)));
            start.countDown();
            BlueOperationResult<FrozenNode> foundResult = found.get(
                    5L, TimeUnit.SECONDS);
            BlueOperationResult<FrozenNode> unavailableResult = unavailable.get(
                    5L, TimeUnit.SECONDS);

            // then
            assertEquals(BlueOperationOutcome.ESTABLISHED,
                    foundResult.outcome());
            assertEquals(BlueOperationOutcome.INCOMPLETE,
                    unavailableResult.outcome());
            assertEquals(NodeProviderOutcome.UNAVAILABLE,
                    unavailableResult.providerOutcome().orElse(null));
            assertEquals(1, foundFetches.get());
            assertEquals(1, unavailableFetches.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static FrozenNode reference(String blueId) {
        return FrozenNode.fromNode(new Node().blueId(blueId));
    }

    private static String blueId(String name) {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().name(name));
    }

    private static NodeProvider providerWithOutcomes(
            String exactBlueId,
            Node exactContent,
            String unavailableBlueId,
            String invalidBlueId) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult result = fetchResultByBlueId(blueId);
                return result.outcome() == NodeProviderOutcome.FOUND
                        ? result.nodes()
                        : null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                if (exactBlueId.equals(blueId)) {
                    return NodeProviderResult.found(
                            Collections.singletonList(exactContent));
                }
                if (unavailableBlueId.equals(blueId)) {
                    return NodeProviderResult.unavailable(
                            "provider offline");
                }
                if (invalidBlueId.equals(blueId)) {
                    return NodeProviderResult.found(
                            Collections.singletonList(
                                    new Node().value("wrong")));
                }
                return NodeProviderResult.notFound();
            }
        };
    }

    private static NodeProvider providerWithResult(
            String requestedBlueId,
            NodeProviderResult providerResult) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult result = fetchResultByBlueId(blueId);
                return result.outcome() == NodeProviderOutcome.FOUND
                        ? result.nodes()
                        : null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                return requestedBlueId.equals(blueId)
                        ? providerResult
                        : NodeProviderResult.notFound();
            }
        };
    }

    private static NodeProvider countingFoundProvider(
            String requestedBlueId,
            Node content,
            AtomicInteger fetches) {
        return waitingProvider(
                requestedBlueId,
                NodeProviderResult.found(
                        Collections.singletonList(content)),
                fetches,
                null);
    }

    private static NodeProvider waitingProvider(
            String requestedBlueId,
            NodeProviderResult result,
            AtomicInteger fetches,
            CountDownLatch start) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult fetched = fetchResultByBlueId(blueId);
                return fetched.outcome() == NodeProviderOutcome.FOUND
                        ? fetched.nodes()
                        : null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                if (!requestedBlueId.equals(blueId)) {
                    return NodeProviderResult.notFound();
                }
                if (start != null) {
                    try {
                        if (!start.await(5L, TimeUnit.SECONDS)) {
                            return NodeProviderResult.unavailable(
                                    "test start timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return NodeProviderResult.unavailable(
                                "test interrupted");
                    }
                }
                fetches.incrementAndGet();
                return result;
            }
        };
    }

    private static ExecutorService daemonExecutor(int threads) {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, work -> {
            Thread thread = new Thread(
                    work,
                    "language-processing-lifecycle-test-"
                            + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void awaitBlocked(Thread thread)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.BLOCKED
                    || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(1L);
        }
        throw new AssertionError(
                "concurrent close did not block on active runtime work");
    }

    private static void awaitScopeClosed(LanguageProcessing.Scope scope)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            if (!scope.isTransientStateCurrent()) {
                return;
            }
            Thread.sleep(1L);
        }
        throw new AssertionError(
                "scope close did not reach terminal teardown");
    }

    private static void await(
            CountDownLatch latch,
            String description) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        description + " timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    description + " interrupted", interrupted);
        }
    }

    private static final class CountingObserver
            implements LanguageProcessing.Observer {
        private final AtomicInteger hits = new AtomicInteger();
        private final AtomicInteger misses = new AtomicInteger();
        private final AtomicInteger lookupCount = new AtomicInteger();
        private final AtomicLong totalLookupNanos = new AtomicLong();

        @Override
        public void snapshotCacheHit() {
            hits.incrementAndGet();
        }

        @Override
        public void snapshotCacheMiss() {
            misses.incrementAndGet();
        }

        @Override
        public void snapshotCacheLookupNanos(long nanos) {
            lookupCount.incrementAndGet();
            totalLookupNanos.addAndGet(nanos);
        }
    }

    private static final class UnavailableCyclicProofProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String memberBlueId;
        private final Node memberContent;

        private UnavailableCyclicProofProvider(
                String memberBlueId,
                Node memberContent) {
            this.memberBlueId = memberBlueId;
            this.memberContent = memberContent;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return memberBlueId.equals(blueId)
                    ? Collections.singletonList(memberContent.clone())
                    : null;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return CyclicSetProofResult.unavailable(
                    "cyclic proof store offline");
        }
    }

    private static final class CloseTrackingProvider
            implements NodeProvider, AutoCloseable {
        private final String blueId;
        private final Node content;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseTrackingProvider(String blueId, Node content) {
            this.blueId = blueId;
            this.content = content;
        }

        @Override
        public List<Node> fetchByBlueId(String requestedBlueId) {
            return blueId.equals(requestedBlueId)
                    ? Collections.singletonList(content.clone())
                    : null;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class ReentrantCloseProvider
            implements NodeProvider, AutoCloseable {
        private final String blueId;
        private final Node content;
        private final CountDownLatch entered;
        private final CountDownLatch attemptClose;
        private final AtomicReference<BlueLanguage> language;
        private final AtomicReference<LanguageProcessing.Scope> scope;
        private final AtomicReference<IllegalStateException>
                scopeCloseFailure = new AtomicReference<>();
        private final AtomicReference<IllegalStateException>
                runtimeCloseFailure = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private ReentrantCloseProvider(
                String blueId,
                Node content,
                CountDownLatch entered,
                CountDownLatch attemptClose,
                AtomicReference<BlueLanguage> language,
                AtomicReference<LanguageProcessing.Scope> scope) {
            this.blueId = blueId;
            this.content = content;
            this.entered = entered;
            this.attemptClose = attemptClose;
            this.language = language;
            this.scope = scope;
        }

        @Override
        public List<Node> fetchByBlueId(String requestedBlueId) {
            if (!blueId.equals(requestedBlueId)) {
                return null;
            }
            entered.countDown();
            await(attemptClose, "reentrant close attempt");
            try {
                scope.get().close();
            } catch (IllegalStateException failure) {
                scopeCloseFailure.set(failure);
            }
            try {
                language.get().close();
            } catch (IllegalStateException failure) {
                runtimeCloseFailure.set(failure);
            }
            return Collections.singletonList(content.clone());
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class StrictProviderAccess
            extends NodeProviderWrapper {
        private static NodeProvider isolate(NodeProvider provider) {
            return verifyOnly(provider);
        }
    }
}
