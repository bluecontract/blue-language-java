package blue.language.processor;

import blue.language.BlueCachePolicy;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.MarkerContract;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.TypeClassResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorConfigurationTest {

    private static final int CONCURRENT_WORKERS = 8;
    private static final int CALLS_PER_WORKER = 25;
    private static final String DETACHED_RESOLVER_TEST_BLUE_ID =
            "detached-resolver-test-blue-id";
    private static final String SUCCESSOR_RESOLVER_TEST_BLUE_ID =
            "successor-resolver-test-blue-id";

    @Test
    void shouldCaptureModernBuilderConfigurationAsImmutableGeneration() {
        // given
        NodeProvider provider = blueId -> null;
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create().build();
        GasSchedule schedule = GasSchedule.contracts10();
        ExternalDeliveryPlanDeriver deriver =
                ExternalDeliveryPlanDeriver.unavailable();
        ExternalDeliveryEvidenceVerifier verifier =
                (root, event, evidence) -> { };
        SubscriptionSurfaceValidator surfaceValidator =
                context -> SubscriptionDelta.empty();
        ProcessingSnapshotManager snapshotStore =
                new ProcessingSnapshotManager() {
                    @Override
                    public ResolvedSnapshot fromDocument(Node document) {
                        return new ResolvedSnapshot(
                                FrozenNode.fromNode(document),
                                FrozenNode.fromResolvedNode(document));
                    }

                    @Override
                    public ResolvedSnapshot applyPatch(
                            ResolvedSnapshot snapshot,
                            JsonPatch patch) {
                        throw new UnsupportedOperationException(
                                "not used by this configuration test");
                    }
                };
        ProcessingObserver observer = observation -> { };
        ProcessingObserver replacementObserver = observation -> { };
        BlueCachePolicy cachePolicy = BlueCachePolicy.disabled();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .nodeProvider(provider)
                .runtimeRegistry(registry)
                .gasSchedule(schedule)
                .gasLimit(0L)
                .deliveryPlanDeriver(deriver)
                .evidenceVerifier(verifier)
                .subscriptionSurfaceValidator(surfaceValidator)
                .snapshotStore(snapshotStore)
                .observer(observer)
                .cachePolicy(cachePolicy);

        // when
        DocumentProcessor processor = builder.build();
        builder.observer(replacementObserver);
        DocumentProcessor successor = DocumentProcessor.Builder.from(processor)
                .observer(replacementObserver)
                .registerContractType(
                        SUCCESSOR_RESOLVER_TEST_BLUE_ID,
                        MarkerContract.class)
                .build();
        TypeClassResolver resolverView =
                processor.getContractTypeResolver();
        resolverView.register(
                DETACHED_RESOLVER_TEST_BLUE_ID,
                String.class);

        // then
        assertTrue(processor.hasImmutableConfiguration());
        assertSame(provider, processor.configuredNodeProvider());
        assertNotSame(registry, processor.getContractRegistry());
        assertSame(schedule, processor.gasSchedule());
        assertSame(snapshotStore, processor.snapshotManager());
        assertSame(observer, processor.processingObserver());
        assertSame(replacementObserver, successor.processingObserver());
        assertSame(verifier, successor.deliveryEvidenceVerifier());
        assertSame(surfaceValidator, successor.subscriptionSurfaceValidator());
        assertSame(
                MarkerContract.class,
                successor.getContractTypeResolver()
                        .resolveClass(SUCCESSOR_RESOLVER_TEST_BLUE_ID));
        assertSame(cachePolicy, processor.cachePolicy());
        assertFalse(processor.getContractTypeResolver()
                .getBlueIdMap()
                .containsKey(DETACHED_RESOLVER_TEST_BLUE_ID));
        assertFalse(processor.getContractTypeResolver()
                .getBlueIdMap()
                .containsKey(SUCCESSOR_RESOLVER_TEST_BLUE_ID));
        assertThrows(
                UnsupportedOperationException.class,
                () -> processor.getContractRegistry().register(
                        (ContractProcessor<?>) null));
    }

    @Test
    void shouldRebindDerivedCollaboratorsToSuccessorGeneration() {
        // given
        DocumentProcessor source = DocumentProcessor.builder().build();

        // when
        DocumentProcessor successor =
                DocumentProcessor.Builder.from(source).build();

        // then
        assertNotSame(
                source.deliveryEvidenceVerifier(),
                successor.deliveryEvidenceVerifier());
        assertNotSame(
                source.subscriptionSurfaceValidator(),
                successor.subscriptionSurfaceValidator());
    }

    @Test
    void shouldSnapshotCollaboratorsSelectedThroughBuilderAliases() {
        // given
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create().build();
        TypeClassResolver resolver = new TypeClassResolver();
        ContractMatchingService matchingService =
                new ContractMatchingService();
        ProcessingObserver initialObserver = observation -> { };

        // when
        DocumentProcessor processor = DocumentProcessor.builder()
                .withRegistry(registry)
                .withContractTypeResolver(resolver)
                .withMatchingService(matchingService)
                .observer(initialObserver)
                .build();
        resolver.register(DETACHED_RESOLVER_TEST_BLUE_ID, String.class);

        // then
        assertTrue(processor.hasImmutableConfiguration());
        assertNotSame(registry, processor.getContractRegistry());
        assertNotSame(resolver, processor.getContractTypeResolver());
        assertSame(matchingService, processor.matchingService());
        assertSame(initialObserver, processor.processingObserver());
        assertFalse(processor.getContractTypeResolver()
                .getBlueIdMap()
                .containsKey(DETACHED_RESOLVER_TEST_BLUE_ID));
    }

    @Test
    void shouldAllowConcurrentCallsThroughOneImmutableProcessor() throws Exception {
        // given
        DocumentProcessor processor = DocumentProcessor.builder()
                .gasSchedule(GasSchedule.contracts10())
                .build();
        Node document = new Node().name("Concurrent immutable configuration");
        ExecutorService executor =
                Executors.newFixedThreadPool(CONCURRENT_WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int worker = 0; worker < CONCURRENT_WORKERS; worker++) {
            calls.add(() -> {
                start.await();
                for (int call = 0; call < CALLS_PER_WORKER; call++) {
                    if (processor.initializeDocument(document).status()
                            != ProcessorStatus.SUCCESS) {
                        return false;
                    }
                }
                return true;
            });
        }

        // when
        List<Future<Boolean>> results = new ArrayList<>();
        for (Callable<Boolean> call : calls) {
            results.add(executor.submit(call));
        }
        start.countDown();

        // then
        try {
            for (Future<Boolean> result : results) {
                assertTrue(result.get());
            }
            assertFalse(results.isEmpty());
        } finally {
            executor.shutdownNow();
            processor.close();
        }
        assertTrue(processor.isClosed());
    }
}
