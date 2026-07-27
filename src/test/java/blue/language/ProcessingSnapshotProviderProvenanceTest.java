package blue.language;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.model.Node;
import blue.language.processor.ContractProcessor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.utils.Properties.DICTIONARY_TYPE_BLUE_ID;

class ProcessingSnapshotProviderProvenanceTest {

    @Test
    void directResolutionAcceptsExplicitlyVerifiedExactType() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        Node resolved = fixture.blue.resolve(fixture.document());

        assertEquals("verified", resolved.getAsText("/fixed"));
        assertEquals(1, fixture.fetches.get());
    }

    @Test
    void initializationSnapshotAcceptsExplicitlyVerifiedExactType() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        Node directlyResolved = fixture.blue.resolve(fixture.document());

        DocumentProcessingResult result = fixture.blue.initializeDocument(fixture.document());

        assertEquals("verified", directlyResolved.getAsText("/fixed"));
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        ResolvedSnapshot resultSnapshot =
                snapshot(fixture.blue, result);
        assertNotNull(resultSnapshot);
        assertEquals("verified", resultSnapshot.resolvedRoot().getAsText("/fixed"));
        assertTrue(fixture.fetches.get() > 0);
    }

    @Test
    void coldNodeProcessAcceptsExplicitlyVerifiedExactType() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        DocumentProcessingResult result = fixture.blue.processDocument(
                fixture.document(), new Node().properties("kind", new Node().value("process")));

        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        ResolvedSnapshot resultSnapshot =
                snapshot(fixture.blue, result);
        assertNotNull(resultSnapshot);
        assertEquals("verified", resultSnapshot.resolvedRoot().getAsText("/fixed"));
    }

    @Test
    void contractRecognitionUsesWinningVerifiedLeafProvenance() {
        Node baseType = new Node().name("Generic Marker");
        String baseBlueId = new Blue().calculateBlueId(baseType);
        Node exactDerivedType = new Node().name("Exact Derived Marker")
                .type(reference(baseBlueId));
        String requestedBlueId = new Blue().calculateBlueId(exactDerivedType);
        NodeProvider trustedLeaf = blueId -> requestedBlueId.equals(blueId)
                ? Collections.singletonList(exactDerivedType.clone())
                : null;
        Blue blue = new Blue(trustedLeaf);
        blue.registerExternalContractType(baseBlueId, baseType, new GenericMarkerProcessor());
        blue.getDocumentProcessor().getContractTypeResolver()
                .register(requestedBlueId, GenericMarker.class);
        assertTrue(blue.getDocumentProcessor().getContractRegistry()
                .processors().containsKey(baseBlueId));
        assertFalse(blue.getDocumentProcessor().getContractRegistry()
                .processors().containsKey(requestedBlueId));
        Node document = new Node().contracts(new Node().properties(
                "derived", new Node().type(reference(requestedBlueId))));

        DocumentProcessingResult result = blue.initializeDocument(document);

        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        ResolvedSnapshot resultSnapshot = snapshot(blue, result);
        assertNotNull(resultSnapshot);
        assertNotNull(resultSnapshot.resolvedRoot().getAsNode("/contracts/derived"));
    }

    @Test
    void plainMismatchStillFailsDuringInitialization() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        Blue plainBlue = new Blue(fixture::fetchMismatch);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> plainBlue.initializeDocument(fixture.document()));

        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch);
        assertEquals(0, plainBlue.resolvedReferenceCacheSize());
    }

    @Test
    void trustedMissDoesNotTrustPlainSnapshotFallback() {
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

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.initializeDocument(fixture.document()));

        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch);
        assertEquals(1, trustedFetches.get());
        assertEquals(1, plainFetches.get());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    @Test
    void plainSnapshotWinnerFailsBeforeTrustedFallback() {
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

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.initializeDocument(fixture.document()));

        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch);
        assertEquals(1, plainFetches.get());
        assertEquals(0, trustedFetches.get());
    }

    @Test
    void explicitUnavailableSnapshotResultDoesNotConsultFallback() {
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

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.initializeDocument(fixture.document()));

        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderUnavailable);
        assertEquals(1, emptyFetches.get());
        assertEquals(0, fallbackFetches.get());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    @Test
    void nestedSequentialSnapshotLookupRetainsWinningLeafPolicy() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        NodeProvider topLevelTrustedMiss = blueId -> null;
        NodeProvider trustedNested = new SequentialNodeProvider(
                blueId -> null,
                blueId -> fixture.response(
                        blueId, fixture.requestedType));
        Blue trustedBlue = new Blue(new SequentialNodeProvider(topLevelTrustedMiss, trustedNested));

        DocumentProcessingResult trustedResult = trustedBlue.initializeDocument(fixture.document());

        assertFalse(isCapabilityFailure(trustedResult), diagnosticMessage(trustedResult));
        assertEquals("verified", snapshot(trustedBlue, trustedResult)
                .resolvedRoot().getAsText("/fixed"));

        AtomicInteger trustedFallbackFetches = new AtomicInteger();
        NodeProvider plainNested = new SequentialNodeProvider(
                blueId -> fixture.response(blueId, fixture.mismatchedType),
                blueId -> {
                    trustedFallbackFetches.incrementAndGet();
                    return fixture.response(blueId, fixture.mismatchedType);
                });
        Blue plainBlue = new Blue(new SequentialNodeProvider(topLevelTrustedMiss, plainNested));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> plainBlue.initializeDocument(fixture.document()));
        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch);
        assertEquals(0, trustedFallbackFetches.get());
    }

    @Test
    void nonBlueIdFilterDoesNotReachConfiguredProvider() {
        AtomicInteger fetches = new AtomicInteger();
        PotentialBlueIdNodeProvider provider = new PotentialBlueIdNodeProvider(blueId -> {
            fetches.incrementAndGet();
            return Collections.singletonList(new Node().value("unexpected"));
        });

        assertNull(provider.fetchByBlueId("symbolic-type-name"));
        assertFalse(provider.acceptsBlueId("symbolic-type-name"));
        assertEquals(0, fetches.get());
    }

    @Test
    void cyclicAwareConfiguredProviderRemainsVisibleThroughFilter() {
        Node cyclicSet = UncheckedObjectMapper.YAML_MAPPER.readValue(
                "- name: Cyclic Member Type\n"
                        + "  fixed: cyclic\n"
                        + "- name: Cyclic Companion Type\n",
                Node.class);
        BasicNodeProvider provider = new BasicNodeProvider(cyclicSet);
        String memberBlueId = provider.getBlueIdByName("Cyclic Member Type");
        Node document = new Node().type(reference(memberBlueId)).contracts(new Node());

        Node direct = new Blue(provider).resolve(document.clone());
        Blue cyclicBlue = new Blue(provider);
        DocumentProcessingResult initialized =
                cyclicBlue.initializeDocument(document);

        assertEquals("cyclic", direct.getAsText("/fixed"));
        assertFalse(isCapabilityFailure(initialized), diagnosticMessage(initialized));
        assertEquals("cyclic", snapshot(cyclicBlue, initialized)
                .resolvedRoot().getAsText("/fixed"));
    }

    @ParameterizedTest(name = "explicit verifying wrapper: {0}")
    @ValueSource(booleans = {false, true})
    void cyclicTypedNodeSurvivesClonedCanonicalSnapshotRebuild(
            boolean explicitlyWrapped) {
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
        ResolvedSnapshot loaded = blue.loadSnapshot(
                rebuilt.canonicalRoot().clone());

        assertEquals("event",
                first.resolvedRoot().getAsText("/stored/fixed"));
        assertEquals(1,
                rebuilt.resolvedRoot().getAsInteger("/stored/sequence"));
        assertEquals(first.blueId(), rebuilt.blueId());
        assertEquals(rebuilt.blueId(), loaded.blueId());
    }

    @Test
    void processorProvidersPrecedeConfiguredFallback() {
        AtomicInteger bootstrapFallbackFetches = new AtomicInteger();
        Blue bootstrapBlue = new Blue(countingMiss(bootstrapFallbackFetches));
        DocumentProcessingResult bootstrap = bootstrapBlue.initializeDocument(
                new Node().type(reference(DICTIONARY_TYPE_BLUE_ID)).contracts(new Node()));
        assertFalse(isCapabilityFailure(bootstrap), diagnosticMessage(bootstrap));
        assertEquals(0, bootstrapFallbackFetches.get());

        AtomicInteger runtimeFallbackFetches = new AtomicInteger();
        Blue runtimeBlue = new Blue(countingMiss(runtimeFallbackFetches));
        DocumentProcessingResult runtime = runtimeBlue.initializeDocument(new Node());
        assertFalse(isCapabilityFailure(runtime), diagnosticMessage(runtime));
        assertNotNull(snapshot(runtimeBlue, runtime)
                .resolvedRoot().getAsNode("/contracts/initialized"));
        assertEquals(0, runtimeFallbackFetches.get());

        AtomicInteger extensionFallbackFetches = new AtomicInteger();
        Blue extensionBlue = new Blue(countingMiss(extensionFallbackFetches));
        Node extensionType = new Node().name("Registered Extension Marker");
        String extensionBlueId = extensionBlue.calculateBlueId(extensionType);
        extensionBlue.registerExternalContractType(
                extensionBlueId, extensionType, new GenericMarkerProcessor());
        DocumentProcessingResult extension = extensionBlue.initializeDocument(
                new Node().contracts(new Node().properties(
                        "extension", new Node().type(reference(extensionBlueId)))));
        assertFalse(isCapabilityFailure(extension), diagnosticMessage(extension));
        assertEquals(0, extensionFallbackFetches.get());

        assertTrue(BlueRuntimeTypeRegistry.getDefault().blueId(
                RuntimeTypeKey.PROCESSING_INITIALIZED_MARKER).length() > 0);
    }

    @Test
    void acceptedBlueIdDelegatesExactlyOnceWithoutTransformingResult() {
        String blueId = new Blue().calculateBlueId(new Node().name("Accepted Provider Subject"));
        List<Node> sentinel = Collections.singletonList(new Node().value("sentinel"));
        AtomicInteger fetches = new AtomicInteger();
        PotentialBlueIdNodeProvider provider = new PotentialBlueIdNodeProvider(requested -> {
            fetches.incrementAndGet();
            assertEquals(blueId, requested);
            return sentinel;
        });

        List<Node> result = provider.fetchByBlueId(blueId);

        assertTrue(provider.acceptsBlueId(blueId));
        assertSame(sentinel, result);
        assertEquals(1, fetches.get());
    }

    @Test
    void explicitlyVerifyingSnapshotPopulatesTheVerifiedReferenceCache() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        DocumentProcessingResult result = fixture.blue.initializeDocument(fixture.document());

        assertTrue(fixture.blue.resolvedReferenceCacheSize() > 0);
        ResolvedSnapshot resultSnapshot =
                snapshot(fixture.blue, result);
        assertNotNull(resultSnapshot);
        assertEquals("verified", resultSnapshot.resolvedRoot().getAsText("/fixed"));
    }

    @Test
    void directlyVerifiedProcessingSnapshotStillWarmsSharedCache() {
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
        DocumentProcessingResult second = blue.initializeDocument(fixture.document());

        assertFalse(isCapabilityFailure(first), diagnosticMessage(first));
        assertFalse(isCapabilityFailure(second), diagnosticMessage(second));
        assertTrue(cacheSize >= 1);
        assertTrue(blue.resolvedReferenceCacheSize() >= cacheSize);
        assertEquals(0, fetches.get());
        assertEquals("verified", snapshot(blue, second)
                .resolvedRoot().getAsText("/fixed"));
    }

    @Test
    void providerReplacementAfterInitializationClearsOldSnapshotPolicy() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        DocumentProcessingResult trusted = fixture.blue.initializeDocument(fixture.document());
        int trustedFetches = fixture.fetches.get();
        AtomicInteger replacementFetches = new AtomicInteger();
        fixture.blue.nodeProvider(blueId -> {
            replacementFetches.incrementAndGet();
            return fixture.response(blueId, fixture.requestedType);
        });

        DocumentProcessingResult verified = fixture.blue.initializeDocument(fixture.document());

        assertEquals("verified", snapshot(fixture.blue, trusted)
                .resolvedRoot().getAsText("/fixed"));
        assertEquals("verified", snapshot(fixture.blue, verified)
                .resolvedRoot().getAsText("/fixed"));
        assertEquals(trustedFetches, fixture.fetches.get());
        assertEquals(1, replacementFetches.get());
        assertTrue(fixture.blue.resolvedReferenceCacheSize() >= 1);
    }

    @Test
    void concurrentDirectAndSnapshotLookupsDoNotTransferTrust() throws Exception {
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
        try {
            Future<DocumentProcessingResult> trusted = executor.submit(
                    () -> trustedBlue.initializeDocument(fixture.document()));
            Future<DocumentProcessingResult> plain = executor.submit(
                    () -> plainBlue.initializeDocument(fixture.document()));

            assertEquals("verified", snapshot(
                    trustedBlue,
                    trusted.get(10, TimeUnit.SECONDS))
                    .resolvedRoot().getAsText("/fixed"));
            assertEquals("verified", snapshot(
                    plainBlue,
                    plain.get(10, TimeUnit.SECONDS))
                    .resolvedRoot().getAsText("/fixed"));
        } finally {
            executor.shutdownNow();
        }

        assertTrue(trustedBlue.resolvedReferenceCacheSize() > 0);
        assertTrue(plainBlue.resolvedReferenceCacheSize() > 0);
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
