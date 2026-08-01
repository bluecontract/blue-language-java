package blue.language;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.model.Node;
import blue.language.processor.ContractProcessor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.model.MarkerContract;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.PotentialBlueIdNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.UncheckedObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.utils.Properties.DICTIONARY_TYPE_BLUE_ID;

class ProcessingSnapshotProviderProvenanceTest {

    @Test
    void shouldAcceptExplicitlyVerifiedExactTypeDuringDirectResolution() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        // when
        Node resolved = fixture.blue.resolve(fixture.document());

        // then
        assertEquals("verified", resolved.getAsText("/fixed"));
        assertEquals(1, fixture.fetches.get());
    }

    @Test
    void shouldAcceptExplicitlyVerifiedExactTypeInInitializationSnapshot() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        Node directlyResolved = fixture.blue.resolve(fixture.document());

        // when
        DocumentProcessingResult result = fixture.blue.initializeDocument(fixture.document());
        ResolvedSnapshot resultSnapshot = snapshot(fixture.blue, result);

        // then
        assertEquals("verified", directlyResolved.getAsText("/fixed"));
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertNotNull(resultSnapshot);
        assertEquals("verified", resultSnapshot.resolvedRoot().getAsText("/fixed"));
        assertTrue(fixture.fetches.get() > 0);
    }

    @Test
    void shouldAcceptExplicitlyVerifiedExactTypeDuringColdNodeProcessing() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        // when
        DocumentProcessingResult result = fixture.blue.processDocument(
                fixture.document(), new Node().properties("kind", new Node().value("process")));
        ResolvedSnapshot resultSnapshot = snapshot(fixture.blue, result);

        // then
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertNotNull(resultSnapshot);
        assertEquals("verified", resultSnapshot.resolvedRoot().getAsText("/fixed"));
    }

    @Test
    void shouldUseWinningVerifiedLeafProvenanceForContractRecognition() {
        // given
        Node baseType = new Node().name("Generic Marker");
        String baseBlueId = new Blue().calculateBlueId(baseType);
        Node exactDerivedType = new Node().name("Exact Derived Marker")
                .type(reference(baseBlueId));
        String requestedBlueId = new Blue().calculateBlueId(exactDerivedType);
        NodeProvider trustedLeaf = blueId -> {
            if (requestedBlueId.equals(blueId)) {
                return Collections.singletonList(exactDerivedType.clone());
            }
            if (baseBlueId.equals(blueId)) {
                return Collections.singletonList(baseType.clone());
            }
            return null;
        };
        Blue blue = new Blue(trustedLeaf);
        blue.registerExternalContractType(baseBlueId, baseType, new GenericMarkerProcessor());

        // when
        DocumentProcessor successor = DocumentProcessor.Builder
                .from(blue.getDocumentProcessor())
                .registerContractType(requestedBlueId, GenericMarker.class)
                .build();
        blue.documentProcessor(successor);
        Node document = new Node().contracts(new Node().properties(
                "derived", new Node().type(reference(requestedBlueId))));
        DocumentProcessingResult result = blue.initializeDocument(document);
        boolean baseProcessorRegistered = blue.getDocumentProcessor().getContractRegistry()
                .processors().containsKey(baseBlueId);
        boolean derivedProcessorRegistered = blue.getDocumentProcessor().getContractRegistry()
                .processors().containsKey(requestedBlueId);
        ResolvedSnapshot resultSnapshot = snapshot(blue, result);

        // then
        assertTrue(baseProcessorRegistered);
        assertFalse(derivedProcessorRegistered);
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertNotNull(resultSnapshot);
        assertNotNull(resultSnapshot.resolvedRoot().getAsNode("/contracts/derived"));
    }

    @Test
    void shouldRejectPlainMismatchDuringInitialization() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        Blue plainBlue = new Blue(fixture::fetchMismatch);

        // when
        RuntimeException failure = captureFailure(
                () -> plainBlue.initializeDocument(fixture.document()));
        int referenceCacheSize = plainBlue.resolvedReferenceCacheSize();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch);
        assertEquals(0, referenceCacheSize);
    }

    @Test
    void shouldNotTrustPlainSnapshotFallbackAfterTrustedMiss() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        AtomicInteger trustedFetches = new AtomicInteger();
        AtomicInteger plainFetches = new AtomicInteger();
        NodeProvider trustedMiss = blueId -> {
            trustedFetches.incrementAndGet();
            return null;
        };
        NodeProvider plainMismatch = blueId -> {
            plainFetches.incrementAndGet();
            return fixture.response(blueId, fixture.mismatchedType);
        };
        Blue blue = new Blue(new SequentialNodeProvider(
                new VerifyingNodeProvider(trustedMiss), plainMismatch));

        // when
        RuntimeException failure = captureFailure(
                () -> blue.initializeDocument(fixture.document()));
        int trustedFetchCount = trustedFetches.get();
        int plainFetchCount = plainFetches.get();
        int referenceCacheSize = blue.resolvedReferenceCacheSize();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch);
        assertEquals(1, trustedFetchCount);
        assertEquals(1, plainFetchCount);
        assertEquals(0, referenceCacheSize);
    }

    @Test
    void shouldFailPlainSnapshotWinnerBeforeTrustedFallback() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        AtomicInteger plainFetches = new AtomicInteger();
        AtomicInteger trustedFetches = new AtomicInteger();
        NodeProvider plainMismatch = blueId -> {
            plainFetches.incrementAndGet();
            return fixture.response(blueId, fixture.mismatchedType);
        };
        NodeProvider trustedFallback = blueId -> {
            trustedFetches.incrementAndGet();
            return fixture.response(blueId, fixture.mismatchedType);
        };
        Blue blue = new Blue(new SequentialNodeProvider(
                plainMismatch, trustedFallback));

        // when
        RuntimeException failure = captureFailure(
                () -> blue.initializeDocument(fixture.document()));
        int plainFetchCount = plainFetches.get();
        int trustedFetchCount = trustedFetches.get();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch);
        assertEquals(1, plainFetchCount);
        assertEquals(0, trustedFetchCount);
    }

    @Test
    void shouldNotConsultFallbackAfterExplicitUnavailableSnapshotResult() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        AtomicInteger emptyFetches = new AtomicInteger();
        AtomicInteger fallbackFetches = new AtomicInteger();
        NodeProvider trustedEmpty = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                emptyFetches.incrementAndGet();
                return Collections.emptyList();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(
                    String blueId) {
                emptyFetches.incrementAndGet();
                return NodeProviderResult.unavailable(
                        "Provider unavailable for requested test BlueId");
            }
        };
        NodeProvider trustedFallback = blueId -> {
            fallbackFetches.incrementAndGet();
            return fixture.response(blueId, fixture.mismatchedType);
        };
        Blue blue = new Blue(new SequentialNodeProvider(
                trustedEmpty,
                trustedFallback));

        // when
        RuntimeException failure = captureFailure(
                () -> blue.initializeDocument(fixture.document()));
        int emptyFetchCount = emptyFetches.get();
        int fallbackFetchCount = fallbackFetches.get();
        int referenceCacheSize = blue.resolvedReferenceCacheSize();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderUnavailable);
        assertEquals(1, emptyFetchCount);
        assertEquals(0, fallbackFetchCount);
        assertEquals(0, referenceCacheSize);
    }

    @Test
    void shouldRetainWinningVerifiedLeafPolicyInNestedSequentialLookup() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        NodeProvider topLevelTrustedMiss = blueId -> null;
        NodeProvider trustedNested = new SequentialNodeProvider(
                blueId -> null,
                blueId -> fixture.response(
                        blueId, fixture.requestedType));
        Blue trustedBlue = new Blue(new SequentialNodeProvider(topLevelTrustedMiss, trustedNested));

        // when
        DocumentProcessingResult trustedResult = trustedBlue.initializeDocument(fixture.document());
        ResolvedSnapshot trustedSnapshot = snapshot(trustedBlue, trustedResult);

        // then
        assertFalse(isCapabilityFailure(trustedResult), diagnosticMessage(trustedResult));
        assertEquals("verified", trustedSnapshot.resolvedRoot().getAsText("/fixed"));
    }

    @Test
    void shouldRejectPlainWinningLeafBeforeNestedFallback() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        NodeProvider topLevelTrustedMiss = blueId -> null;
        AtomicInteger trustedFallbackFetches = new AtomicInteger();
        NodeProvider plainNested = new SequentialNodeProvider(
                blueId -> fixture.response(blueId, fixture.mismatchedType),
                blueId -> {
                    trustedFallbackFetches.incrementAndGet();
                    return fixture.response(blueId, fixture.mismatchedType);
                });
        Blue plainBlue = new Blue(new SequentialNodeProvider(topLevelTrustedMiss, plainNested));

        // when
        RuntimeException failure = captureFailure(
                () -> plainBlue.initializeDocument(fixture.document()));
        int trustedFallbackFetchCount = trustedFallbackFetches.get();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch);
        assertEquals(0, trustedFallbackFetchCount);
    }

    @Test
    void shouldNotReachConfiguredProviderForNonBlueIdFilter() {
        // given
        AtomicInteger fetches = new AtomicInteger();
        PotentialBlueIdNodeProvider provider = new PotentialBlueIdNodeProvider(blueId -> {
            fetches.incrementAndGet();
            return Collections.singletonList(new Node().value("unexpected"));
        });

        // when
        List<Node> result = provider.fetchByBlueId("symbolic-type-name");
        boolean accepted = provider.acceptsBlueId("symbolic-type-name");
        int fetchCount = fetches.get();

        // then
        assertNull(result);
        assertFalse(accepted);
        assertEquals(0, fetchCount);
    }

    @Test
    void shouldKeepCyclicAwareConfiguredProviderVisibleThroughFilter() {
        // given
        Node cyclicSet = UncheckedObjectMapper.YAML_MAPPER.readValue(
                "- name: Cyclic Member Type\n"
                        + "  fixed: cyclic\n"
                        + "  peer:\n"
                        + "    blueId: this#1\n"
                        + "- name: Cyclic Companion Type\n"
                        + "  peer:\n"
                        + "    blueId: this#0\n",
                Node.class);
        BasicNodeProvider provider = new BasicNodeProvider(cyclicSet);
        String memberBlueId = provider.getBlueIdByName("Cyclic Member Type");
        Node document = new Node().type(reference(memberBlueId)).contracts(new Node());

        Node direct = new Blue(provider).resolve(document.clone());
        Blue cyclicBlue = new Blue(provider);
        // when
        DocumentProcessingResult initialized =
                cyclicBlue.initializeDocument(document);

        // then
        assertEquals("cyclic", direct.getAsText("/fixed"));
        assertFalse(isCapabilityFailure(initialized), diagnosticMessage(initialized));
        assertEquals("cyclic", snapshot(cyclicBlue, initialized)
                .resolvedRoot().getAsText("/fixed"));
    }

    @ParameterizedTest(name = "explicit verifying wrapper: {0}")
    @ValueSource(booleans = {false, true})
    void shouldPreserveCyclicTypedNodeAcrossClonedCanonicalSnapshotRebuild(
            boolean explicitlyWrapped) {
        // given
        BasicNodeProvider cyclicProvider = new BasicNodeProvider(UncheckedObjectMapper.YAML_MAPPER.readValue(
                "- name: Cyclic Checkpoint Event\n"
                        + "  fixed: event\n"
                        + "  peer:\n"
                        + "    blueId: this#1\n"
                        + "- name: Cyclic Checkpoint Companion\n"
                        + "  fixed: companion\n"
                        + "  peer:\n"
                        + "    blueId: this#0\n",
                Node.class));
        String eventTypeBlueId = cyclicProvider.getBlueIdByName("Cyclic Checkpoint Event");
        NodeProvider configuredProvider = explicitlyWrapped
                ? new VerifyingNodeProvider(cyclicProvider)
                : cyclicProvider;
        Blue blue = new Blue(configuredProvider);
        Node document = new Node().properties(
                "stored", cyclicEvent(eventTypeBlueId, 1));

        ResolvedSnapshot first = blue.resolveToSnapshot(document);
        ResolvedSnapshot rebuilt = blue.resolveToSnapshot(
                first.canonicalRoot().clone());
        // when
        ResolvedSnapshot loaded = blue.loadSnapshot(
                rebuilt.canonicalRoot().clone());

        // then
        assertEquals("event",
                first.resolvedRoot().getAsText("/stored/fixed"));
        assertEquals(1,
                rebuilt.resolvedRoot().getAsInteger("/stored/sequence"));
        assertEquals(first.blueId(), rebuilt.blueId());
        assertEquals(rebuilt.blueId(), loaded.blueId());
    }

    @Test
    void shouldPreferBootstrapProcessorProviderToConfiguredFallback() {
        // given
        AtomicInteger bootstrapFallbackFetches = new AtomicInteger();
        Blue bootstrapBlue = new Blue(countingMiss(bootstrapFallbackFetches));

        // when
        DocumentProcessingResult bootstrap = bootstrapBlue.initializeDocument(
                new Node().type(reference(DICTIONARY_TYPE_BLUE_ID)).contracts(new Node()));
        int fallbackFetchCount = bootstrapFallbackFetches.get();

        // then
        assertFalse(isCapabilityFailure(bootstrap), diagnosticMessage(bootstrap));
        assertEquals(0, fallbackFetchCount);
    }

    @Test
    void shouldPreferRuntimeProcessorProviderToConfiguredFallback() {
        // given
        AtomicInteger runtimeFallbackFetches = new AtomicInteger();
        Blue runtimeBlue = new Blue(countingMiss(runtimeFallbackFetches));

        // when
        DocumentProcessingResult runtime = runtimeBlue.initializeDocument(new Node());
        ResolvedSnapshot runtimeSnapshot = snapshot(runtimeBlue, runtime);
        int fallbackFetchCount = runtimeFallbackFetches.get();
        String initializedMarkerBlueId = BlueRuntimeTypeRegistry.getDefault().blueId(
                RuntimeTypeKey.PROCESSING_INITIALIZED_MARKER);

        // then
        assertFalse(isCapabilityFailure(runtime), diagnosticMessage(runtime));
        assertNotNull(runtimeSnapshot.resolvedRoot().getAsNode("/contracts/initialized"));
        assertEquals(0, fallbackFetchCount);
        assertTrue(initializedMarkerBlueId.length() > 0);
    }

    @Test
    void shouldPreferRegisteredExtensionProviderToConfiguredFallback() {
        // given
        AtomicInteger extensionFallbackFetches = new AtomicInteger();
        Blue extensionBlue = new Blue(countingMiss(extensionFallbackFetches));
        Node extensionType = new Node().name("Registered Extension Marker");
        String extensionBlueId = extensionBlue.calculateBlueId(extensionType);

        // when
        extensionBlue.registerExternalContractType(
                extensionBlueId, extensionType, new GenericMarkerProcessor());
        DocumentProcessingResult extension = extensionBlue.initializeDocument(
                new Node().contracts(new Node().properties(
                        "extension", new Node().type(reference(extensionBlueId)))));
        int fallbackFetchCount = extensionFallbackFetches.get();

        // then
        assertFalse(isCapabilityFailure(extension), diagnosticMessage(extension));
        assertEquals(0, fallbackFetchCount);
    }

    @Test
    void shouldDelegateAcceptedBlueIdExactlyOnceWithoutTransformingResult() {
        // given
        String blueId = new Blue().calculateBlueId(new Node().name("Accepted Provider Subject"));
        List<Node> sentinel = Collections.singletonList(new Node().value("sentinel"));
        AtomicInteger fetches = new AtomicInteger();
        AtomicReference<String> requestedBlueId = new AtomicReference<>();
        PotentialBlueIdNodeProvider provider = new PotentialBlueIdNodeProvider(requested -> {
            fetches.incrementAndGet();
            requestedBlueId.set(requested);
            return sentinel;
        });

        // when
        List<Node> result = provider.fetchByBlueId(blueId);
        boolean accepted = provider.acceptsBlueId(blueId);
        int fetchCount = fetches.get();
        String delegatedBlueId = requestedBlueId.get();

        // then
        assertEquals(blueId, delegatedBlueId);
        assertTrue(accepted);
        assertSame(sentinel, result);
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldPopulateVerifiedReferenceCacheFromExplicitlyVerifiedSnapshot() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        // when
        DocumentProcessingResult result = fixture.blue.initializeDocument(fixture.document());
        int cacheSize = fixture.blue.resolvedReferenceCacheSize();
        ResolvedSnapshot resultSnapshot = snapshot(fixture.blue, result);

        // then
        assertTrue(cacheSize > 0);
        assertNotNull(resultSnapshot);
        assertEquals("verified", resultSnapshot.resolvedRoot().getAsText("/fixed"));
    }

    @Test
    void shouldWarmSharedCacheFromDirectlyVerifiedProcessingSnapshot() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        AtomicInteger fetches = new AtomicInteger();
        NodeProvider exactProvider = blueId -> {
            fetches.incrementAndGet();
            return fixture.response(blueId, fixture.requestedType);
        };
        Blue blue = new Blue(exactProvider);

        DocumentProcessingResult first = blue.initializeDocument(fixture.document());
        int cacheSize = blue.resolvedReferenceCacheSize();
        fetches.set(0);
        // when
        DocumentProcessingResult second = blue.initializeDocument(fixture.document());

        // then
        assertFalse(isCapabilityFailure(first), diagnosticMessage(first));
        assertFalse(isCapabilityFailure(second), diagnosticMessage(second));
        assertTrue(cacheSize >= 1);
        assertTrue(blue.resolvedReferenceCacheSize() >= cacheSize);
        assertEquals(0, fetches.get());
        assertEquals("verified", snapshot(blue, second)
                .resolvedRoot().getAsText("/fixed"));
    }

    @Test
    void shouldClearOldSnapshotPolicyAfterProviderReplacement() {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        DocumentProcessingResult trusted = fixture.blue.initializeDocument(fixture.document());
        int trustedFetches = fixture.fetches.get();
        AtomicInteger replacementFetches = new AtomicInteger();
        fixture.blue.nodeProvider(blueId -> {
            replacementFetches.incrementAndGet();
            return fixture.response(blueId, fixture.requestedType);
        });

        // when
        DocumentProcessingResult verified = fixture.blue.initializeDocument(fixture.document());

        // then
        assertEquals("verified", snapshot(fixture.blue, trusted)
                .resolvedRoot().getAsText("/fixed"));
        assertEquals("verified", snapshot(fixture.blue, verified)
                .resolvedRoot().getAsText("/fixed"));
        assertEquals(trustedFetches, fixture.fetches.get());
        assertEquals(1, replacementFetches.get());
        assertTrue(fixture.blue.resolvedReferenceCacheSize() >= 1);
    }

    @Test
    void shouldNotTransferTrustBetweenConcurrentDirectAndSnapshotLookups() throws Exception {
        // given
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        CyclicBarrier lookupBarrier = new CyclicBarrier(2);
        AtomicInteger synchronizedLookups = new AtomicInteger();
        NodeProvider sharedProvider = blueId -> {
            if (!fixture.requestedBlueId.equals(blueId)) {
                return null;
            }
            if (synchronizedLookups.incrementAndGet() <= 2) {
                await(lookupBarrier);
            }
            return Collections.singletonList(fixture.requestedType.clone());
        };
        Blue trustedBlue = new Blue(sharedProvider);
        Blue plainBlue = new Blue(sharedProvider);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
        String trustedFixed;
        String plainFixed;
        try {
            Future<DocumentProcessingResult> trusted = executor.submit(
                    () -> trustedBlue.initializeDocument(fixture.document()));
            Future<DocumentProcessingResult> plain = executor.submit(
                    () -> plainBlue.initializeDocument(fixture.document()));
            trustedFixed = snapshot(
                    trustedBlue,
                    trusted.get(10, TimeUnit.SECONDS))
                    .resolvedRoot().getAsText("/fixed");
            plainFixed = snapshot(
                    plainBlue,
                    plain.get(10, TimeUnit.SECONDS))
                    .resolvedRoot().getAsText("/fixed");
        } finally {
            executor.shutdownNow();
        }
        int trustedCacheSize = trustedBlue.resolvedReferenceCacheSize();
        int plainCacheSize = plainBlue.resolvedReferenceCacheSize();

        // then
        assertEquals("verified", trustedFixed);
        assertEquals("verified", plainFixed);
        assertTrue(trustedCacheSize > 0);
        assertTrue(plainCacheSize > 0);
    }

    private static NodeProvider countingMiss(AtomicInteger fetches) {
        return blueId -> {
            fetches.incrementAndGet();
            return null;
        };
    }

    private static Node cyclicEvent(String typeBlueId, int sequence) {
        return new Node()
                .type(reference(typeBlueId))
                .properties("sequence", new Node().value(sequence));
    }

    private static void assertProviderFailure(Throwable failure, BlueLanguageErrorCategory category) {
        assertEquals(category, BlueLanguageErrorClassifier.classify(failure), messageChain(failure));
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

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Provider lookup barrier failed", ex);
        }
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class TrustedTypeFixture {
        private final Node requestedType = new Node().name("Requested Type")
                .properties("fixed", new Node().value("verified"));
        private final Node mismatchedType = new Node().name("Mismatched Source Type")
                .properties("fixed", new Node().value("mismatched"));
        private final String requestedBlueId = new Blue().calculateBlueId(requestedType);
        private final AtomicInteger fetches = new AtomicInteger();
        private final Blue blue = new Blue(this::fetch);

        private List<Node> fetch(String blueId) {
            fetches.incrementAndGet();
            return response(blueId, requestedType);
        }

        private List<Node> fetchMismatch(String blueId) {
            fetches.incrementAndGet();
            return response(blueId, mismatchedType);
        }

        private List<Node> response(String blueId, Node content) {
            return requestedBlueId.equals(blueId)
                    ? Collections.singletonList(content.clone())
                    : null;
        }

        private Node document() {
            return new Node().type(reference(requestedBlueId))
                    .contracts(new Node());
        }
    }

    public static final class GenericMarker extends MarkerContract {
    }

    private static final class GenericMarkerProcessor implements ContractProcessor<GenericMarker> {
        @Override
        public Class<GenericMarker> contractType() {
            return GenericMarker.class;
        }
    }

}
