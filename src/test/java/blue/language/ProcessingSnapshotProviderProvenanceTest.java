package blue.language;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.MarkerContract;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.PotentialBlueIdNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.UncheckedObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
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
    void directResolutionControlAcceptsExplicitTrustedNonDirectType() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        Node resolved = fixture.blue.resolve(fixture.document());

        assertEquals("trusted", resolved.getAsText("/fixed"));
        assertEquals(1, fixture.fetches.get());
    }

    @Test
    void initializationSnapshotAcceptsExplicitTrustedNonDirectType() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        Node directlyResolved = fixture.blue.resolve(fixture.document());

        DocumentProcessingResult result = fixture.blue.initializeDocument(fixture.document());

        assertEquals("trusted", directlyResolved.getAsText("/fixed"));
        assertFalse(result.capabilityFailure(), result.failureReason());
        assertNotNull(result.snapshot());
        assertEquals("trusted", result.snapshot().resolvedRoot().getAsText("/fixed"));
        assertEquals(2, fixture.fetches.get());
    }

    @Test
    void coldNodeProcessAcceptsExplicitTrustedNonDirectType() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        DocumentProcessingResult result = fixture.blue.processDocument(
                fixture.document(), new Node().properties("kind", new Node().value("process")));

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertNotNull(result.snapshot());
        assertEquals("trusted", result.snapshot().resolvedRoot().getAsText("/fixed"));
    }

    @Test
    void canonicalPatchReresolutionPreservesExplicitTrust() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        registerPatchContracts(fixture.blue);
        Node document = fixture.document().contracts(patchContracts(fixture.blue));
        DocumentProcessingResult initialized = fixture.blue.initializeDocument(document);

        DocumentProcessingResult processed = fixture.blue.processDocument(
                initialized.document(), new Node().properties("kind", new Node().value("patch")));

        assertFalse(processed.capabilityFailure(), processed.failureReason());
        assertEquals("applied", processed.document().getAsText("/patched"));
        assertNotNull(processed.snapshot());
        assertEquals("trusted", processed.snapshot().resolvedRoot().getAsText("/fixed"));
    }

    @Test
    void contractRecognitionUsesWinningTrustedLeafProvenance() {
        Node baseType = new Node().name("Generic Marker");
        String baseBlueId = new Blue().calculateBlueId(baseType);
        Node requestedType = new Node().name("Requested Derived Marker");
        String requestedBlueId = new Blue().calculateBlueId(requestedType);
        Node trustedDerivedType = new Node().name("Trusted Derived Marker")
                .type(reference(baseBlueId));
        NodeProvider trustedLeaf = blueId -> requestedBlueId.equals(blueId)
                ? Collections.singletonList(trustedDerivedType.clone())
                : null;
        Blue blue = new Blue(NodeProviderWrapper.unverified(trustedLeaf));
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

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertNotNull(result.snapshot());
        assertNotNull(result.snapshot().resolvedRoot().getAsNode("/contracts/derived"));
    }

    @Test
    void plainMismatchStillFailsDuringInitialization() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        Blue plainBlue = new Blue(fixture::fetch);

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
            return fixture.response(blueId, fixture.trustedType);
        };
        Blue blue = new Blue(new SequentialNodeProvider(
                NodeProviderWrapper.unverified(trustedMiss), plainMismatch));

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
            return fixture.response(blueId, fixture.trustedType);
        };
        NodeProvider trustedFallback = blueId -> {
            trustedFetches.incrementAndGet();
            return fixture.response(blueId, fixture.trustedType);
        };
        Blue blue = new Blue(new SequentialNodeProvider(
                plainMismatch, NodeProviderWrapper.unverified(trustedFallback)));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.initializeDocument(fixture.document()));

        assertProviderFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch);
        assertEquals(1, plainFetches.get());
        assertEquals(0, trustedFetches.get());
    }

    @Test
    void terminalEmptySnapshotResultDoesNotConsultFallback() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        AtomicInteger emptyFetches = new AtomicInteger();
        AtomicInteger fallbackFetches = new AtomicInteger();
        NodeProvider trustedEmpty = blueId -> {
            emptyFetches.incrementAndGet();
            return Collections.emptyList();
        };
        NodeProvider trustedFallback = blueId -> {
            fallbackFetches.incrementAndGet();
            return fixture.response(blueId, fixture.trustedType);
        };
        Blue blue = new Blue(new SequentialNodeProvider(
                NodeProviderWrapper.unverified(trustedEmpty),
                NodeProviderWrapper.unverified(trustedFallback)));

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
        NodeProvider topLevelTrustedMiss = NodeProviderWrapper.unverified(blueId -> null);
        NodeProvider trustedNested = new SequentialNodeProvider(
                blueId -> null,
                NodeProviderWrapper.unverified(blueId -> fixture.response(blueId, fixture.trustedType)));
        Blue trustedBlue = new Blue(new SequentialNodeProvider(topLevelTrustedMiss, trustedNested));

        DocumentProcessingResult trustedResult = trustedBlue.initializeDocument(fixture.document());

        assertFalse(trustedResult.capabilityFailure(), trustedResult.failureReason());
        assertEquals("trusted", trustedResult.snapshot().resolvedRoot().getAsText("/fixed"));

        AtomicInteger trustedFallbackFetches = new AtomicInteger();
        NodeProvider plainNested = new SequentialNodeProvider(
                blueId -> fixture.response(blueId, fixture.trustedType),
                NodeProviderWrapper.unverified(blueId -> {
                    trustedFallbackFetches.incrementAndGet();
                    return fixture.response(blueId, fixture.trustedType);
                }));
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
        DocumentProcessingResult initialized = new Blue(provider).initializeDocument(document);

        assertEquals("cyclic", direct.getAsText("/fixed"));
        assertFalse(initialized.capabilityFailure(), initialized.failureReason());
        assertEquals("cyclic", initialized.snapshot().resolvedRoot().getAsText("/fixed"));
    }

    @Test
    void processorProvidersPrecedeConfiguredFallback() {
        AtomicInteger bootstrapFallbackFetches = new AtomicInteger();
        Blue bootstrapBlue = new Blue(countingMiss(bootstrapFallbackFetches));
        DocumentProcessingResult bootstrap = bootstrapBlue.initializeDocument(
                new Node().type(reference(DICTIONARY_TYPE_BLUE_ID)).contracts(new Node()));
        assertFalse(bootstrap.capabilityFailure(), bootstrap.failureReason());
        assertEquals(0, bootstrapFallbackFetches.get());

        AtomicInteger runtimeFallbackFetches = new AtomicInteger();
        Blue runtimeBlue = new Blue(countingMiss(runtimeFallbackFetches));
        DocumentProcessingResult runtime = runtimeBlue.initializeDocument(new Node());
        assertFalse(runtime.capabilityFailure(), runtime.failureReason());
        assertNotNull(runtime.snapshot().resolvedRoot().getAsNode("/contracts/initialized"));
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
        assertFalse(extension.capabilityFailure(), extension.failureReason());
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
    void trustedProcessingSnapshotDoesNotPopulateVerifiedReferenceCache() {
        TrustedTypeFixture fixture = new TrustedTypeFixture();

        DocumentProcessingResult result = fixture.blue.initializeDocument(fixture.document());

        assertEquals(0, fixture.blue.resolvedReferenceCacheSize());
        assertNull(result.snapshot().verifiedReferenceResolution());
        assertEquals("trusted", result.snapshot().resolvedRoot().getAsText("/fixed"));
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

        assertFalse(first.capabilityFailure(), first.failureReason());
        assertFalse(second.capabilityFailure(), second.failureReason());
        assertTrue(cacheSize >= 1);
        assertTrue(blue.resolvedReferenceCacheSize() >= cacheSize);
        assertEquals(0, fetches.get());
        assertEquals("verified", second.snapshot().resolvedRoot().getAsText("/fixed"));
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

        assertEquals("trusted", trusted.snapshot().resolvedRoot().getAsText("/fixed"));
        assertEquals("verified", verified.snapshot().resolvedRoot().getAsText("/fixed"));
        assertEquals(trustedFetches, fixture.fetches.get());
        assertEquals(1, replacementFetches.get());
        assertTrue(fixture.blue.resolvedReferenceCacheSize() >= 1);
    }

    @Test
    void concurrentDirectAndSnapshotLookupsDoNotTransferTrust() throws Exception {
        TrustedTypeFixture fixture = new TrustedTypeFixture();
        CyclicBarrier lookupBarrier = new CyclicBarrier(2);
        NodeProvider sharedProvider = blueId -> {
            if (!fixture.requestedBlueId.equals(blueId)) {
                return null;
            }
            await(lookupBarrier);
            return Collections.singletonList(fixture.trustedType.clone());
        };
        Blue trustedBlue = new Blue(NodeProviderWrapper.unverified(sharedProvider));
        Blue plainBlue = new Blue(sharedProvider);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DocumentProcessingResult> trusted = executor.submit(
                    () -> trustedBlue.initializeDocument(fixture.document()));
            Future<DocumentProcessingResult> plain = executor.submit(
                    () -> plainBlue.initializeDocument(fixture.document()));

            assertEquals("trusted", trusted.get(10, TimeUnit.SECONDS)
                    .snapshot().resolvedRoot().getAsText("/fixed"));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> plain.get(10, TimeUnit.SECONDS));
            assertProviderFailure(failure.getCause(), BlueLanguageErrorCategory.ProviderBlueIdMismatch);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, trustedBlue.resolvedReferenceCacheSize());
        assertEquals(0, plainBlue.resolvedReferenceCacheSize());
    }

    private static void registerPatchContracts(Blue blue) {
        Node channelType = new Node().name("Patch Channel");
        String channelBlueId = blue.calculateBlueId(channelType);
        blue.registerExternalContractType(channelBlueId, channelType, new PatchChannelProcessor());
        Node handlerType = new Node().name("Patch Handler");
        String handlerBlueId = blue.calculateBlueId(handlerType);
        blue.registerExternalContractType(handlerBlueId, handlerType, new PatchHandlerProcessor());
    }

    private static Node patchContracts(Blue blue) {
        String channelBlueId = blue.calculateBlueId(new Node().name("Patch Channel"));
        String handlerBlueId = blue.calculateBlueId(new Node().name("Patch Handler"));
        return new Node()
                .properties("incoming", new Node().type(reference(channelBlueId)))
                .properties("patch", new Node().type(reference(handlerBlueId))
                        .properties("channel", new Node().value("incoming")));
    }

    private static NodeProvider countingMiss(AtomicInteger fetches) {
        return blueId -> {
            fetches.incrementAndGet();
            return null;
        };
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
        private final Node trustedType = new Node().name("Trusted Source Type")
                .properties("fixed", new Node().value("trusted"));
        private final String requestedBlueId = new Blue().calculateBlueId(requestedType);
        private final AtomicInteger fetches = new AtomicInteger();
        private final Blue blue = new Blue(NodeProviderWrapper.unverified(this::fetch));

        private List<Node> fetch(String blueId) {
            fetches.incrementAndGet();
            return response(blueId, trustedType);
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

    public static final class PatchChannel extends ChannelContract {
    }

    private static final class PatchChannelProcessor implements ChannelProcessor<PatchChannel> {
        @Override
        public Class<PatchChannel> contractType() {
            return PatchChannel.class;
        }

        @Override
        public boolean matches(PatchChannel contract, ChannelEvaluationContext context) {
            return true;
        }
    }

    public static final class PatchHandler extends HandlerContract {
    }

    private static final class PatchHandlerProcessor implements HandlerProcessor<PatchHandler> {
        @Override
        public Class<PatchHandler> contractType() {
            return PatchHandler.class;
        }

        @Override
        public void execute(PatchHandler contract, ProcessorExecutionContext context) {
            context.applyPatch(JsonPatch.add("/patched", new Node().value("applied")));
        }
    }
}
