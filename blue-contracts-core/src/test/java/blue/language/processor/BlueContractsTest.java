package blue.language.processor;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlueContractsTest {

    private static final long PLATFORM_ROOT_REVISION = 17L;
    private static final ExternalOrderKey PLATFORM_EVENT_ORDER =
            ExternalOrderKey.of(Collections.<Object>singletonList(
                    "platform-invocation"));

    @Test
    void shouldProcessThroughFocusedServiceAndLeaveLanguageOpen() {
        // given
        BlueLanguage language = BlueLanguage.builder().build();
        BlueContracts contracts = BlueContracts.builder(
                language.processing()).build();
        Node root = new Node().value("root");
        Node event = new Node().value("event");

        // when
        DocumentProcessingResult result = contracts.process(root, event);
        contracts.close();
        String directBlueId = language.identity().directBlueId(root);

        // then
        assertNotNull(result);
        assertNotNull(result.status());
        assertFalse(directBlueId.isEmpty());
        assertThrows(IllegalStateException.class,
                () -> contracts.process(root, event));
        language.close();
    }

    @Test
    void shouldExposeManagedHostServicesOnlyWhileOpen() {
        // given
        BlueLanguage language = BlueLanguage.builder().build();
        BlueContracts contracts = BlueContracts.builder(
                language.processing()).build();

        // when
        ProcessorRuntimeAccess runtimeAccess = contracts.runtimeAccess();
        SubscriptionSurfaceProjection projection =
                contracts.subscriptionSurfaceProjection();
        IndexedDeliveryEvaluator evaluator =
                contracts.indexedDeliveryEvaluator();
        ExternalDeliveryPlanDeriver deriver =
                contracts.currentRootDeliveryPlanDeriver(
                        0L,
                        ExternalOrderKey.of(Collections.emptyList()),
                        Collections.<SubscriptionDelta.Entry>emptyList());
        contracts.close();

        // then
        assertNotNull(projection);
        assertNotNull(evaluator);
        assertNotNull(deriver);
        assertFalse(runtimeAccess.isCurrent());
        assertThrows(IllegalStateException.class,
                contracts::runtimeAccess);
        assertThrows(IllegalStateException.class,
                contracts::subscriptionSurfaceProjection);
        assertThrows(IllegalStateException.class,
                contracts::indexedDeliveryEvaluator);
        language.close();
    }

    @Test
    void shouldTranslateExactProviderAbsenceToNull() {
        // given
        String absentBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("absent"));

        // when
        FrozenNode materialized;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            materialized = manager.materializeVerifiedExactReference(
                    reference(absentBlueId));
        }

        // then
        assertNull(materialized);
    }

    @Test
    void shouldTranslateProviderUnavailabilityToRetryableEvidence() {
        // given
        String unavailableBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("unavailable"));
        NodeProvider provider = providerWithResult(
                unavailableBlueId,
                NodeProviderResult.unavailable("offline"));

        // when
        ExecutionEvidenceUnavailableException failure;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            failure = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> manager.materializeVerifiedExactReference(
                            reference(unavailableBlueId)));
        }

        // then
        assertEquals(Collections.singletonList(unavailableBlueId),
                failure.requiredExactBlueIds());
        assertEquals("offline", failure.getMessage());
    }

    @Test
    void shouldTranslateInvalidProviderEvidenceToTerminalFailure() {
        // given
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("expected"));
        NodeProvider provider = providerWithResult(
                requestedBlueId,
                NodeProviderResult.found(Collections.singletonList(
                        new Node().value("wrong"))));

        // when
        RuntimeException failure;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> manager.materializeVerifiedExactReference(
                            reference(requestedBlueId)));
        }

        // then
        assertNotNull(failure.getMessage());
    }

    @Test
    void shouldProcessPreparedPlanWithoutCallingConstructionDeriver() {
        // given
        Node root = new Node().properties(
                "name", new Node().value("Prepared Root"));
        Node event = new Node().properties(
                "kind", new Node().value("unmatched"));
        AtomicInteger deriverCalls = new AtomicInteger();
        NodeProvider invocationProvider = blueId -> null;

        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .deliveryPlanDeriver((ignoredRoot, ignoredEvent) -> {
                         deriverCalls.incrementAndGet();
                         throw new AssertionError(
                                 "construction deriver must stay cold");
                     })
                     .build()) {
            IndexedDeliveryPreparation preparation = prepareEmptyPlan(
                    contracts, root, event);
            PlatformProcessInvocation invocation = invocation(
                    preparation.deliveryPlan(), invocationProvider);

            // when
            PlatformProcessingResult result =
                    contracts.processForPlatformCommit(
                            root, event, invocation);

            // then
            assertEquals(ProcessorStatus.NO_MATCH,
                    result.processResult().status());
            assertEquals(0, deriverCalls.get());
            PlatformCommitCompanion companion = result.commitCompanion();
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(root),
                    companion.expectedRootBlueId());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(event),
                    companion.eventBlueId());
            assertEquals(PLATFORM_ROOT_REVISION,
                    companion.expectedRootRevision());
            assertEquals(PLATFORM_ROOT_REVISION,
                    companion.resultingRootRevision());
            assertEquals(PLATFORM_EVENT_ORDER,
                    companion.eventOrderKey());
            assertFalse(result.processResult().commits());
            assertTrue(result.processResult().events().isEmpty());
            assertFalse(companion.commitsRootAndOutbox());
            assertTrue(companion.subscriptionDelta().isEmpty());
        }
    }

    @Test
    void shouldUseOnlyBorrowedInvocationProviderForPureReferenceInputs() {
        // given
        Node root = new Node().properties(
                "name", new Node().value("Request-local Root"));
        Node event = new Node().properties(
                "kind", new Node().value("request-local-event"));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        AtomicInteger fixedProviderReads = new AtomicInteger();
        NodeProvider fixedProvider = blueId -> {
            fixedProviderReads.incrementAndGet();
            return null;
        };
        CloseTrackingProvider invocationProvider =
                new CloseTrackingProvider(rootBlueId, root,
                        eventBlueId, event);

        PlatformProcessingResult result;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(fixedProvider)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            IndexedDeliveryPreparation preparation = prepareEmptyPlan(
                    contracts, root, event);
            PlatformProcessInvocation invocation = invocation(
                    preparation.deliveryPlan(), invocationProvider);

            // when
            result = contracts.processForPlatformCommit(
                    new Node().blueId(rootBlueId),
                    new Node().blueId(eventBlueId),
                    invocation);
        }

        // then
        assertEquals(ProcessorStatus.NO_MATCH,
                result.processResult().status());
        assertEquals(0, fixedProviderReads.get());
        assertEquals(2, invocationProvider.reads.get());
        assertFalse(invocationProvider.closed.get());
    }

    @Test
    void shouldRejectPreparedPlanBoundToDifferentRootOrEvent() {
        // given
        Node root = new Node().properties(
                "name", new Node().value("Bound Root"));
        Node event = new Node().properties(
                "kind", new Node().value("bound-event"));
        Node wrongRoot = new Node().properties(
                "name", new Node().value("Wrong Root"));
        Node wrongEvent = new Node().properties(
                "kind", new Node().value("wrong-event"));

        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            PlatformProcessInvocation invocation = invocation(
                    prepareEmptyPlan(contracts, root, event)
                            .deliveryPlan(),
                    blueId -> null);

            // when / then
            assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            wrongRoot, event, invocation));
            assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root, wrongEvent, invocation));
        }
    }

    @Test
    void shouldRejectPreparedPlanBoundToDifferentRuntimeRegistry() {
        // given
        Node root = new Node().properties(
                "name", new Node().value("Registry-bound Root"));
        Node event = new Node().properties(
                "kind", new Node().value("registry-bound-event"));
        ExternalDeliveryPlan foreignPlan;
        try (DocumentProcessor foreignProcessor = DocumentProcessor.builder()
                .runtimeRegistryIdentity("foreign-runtime-registry")
                .build()) {
            foreignPlan = foreignProcessor.administration()
                    .indexedDeliveryEvaluator()
                    .prepare(
                            root,
                            event,
                            PLATFORM_ROOT_REVISION,
                            PLATFORM_EVENT_ORDER,
                            Collections.<SubscriptionDelta.Entry>emptyList(),
                            Collections.<ExternalSubscriptionOccurrenceKey>
                                    emptyList())
                    .deliveryPlan();
        }

        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            PlatformProcessInvocation invocation = invocation(
                    foreignPlan, blueId -> null);

            // when / then
            assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root, event, invocation));
        }
    }

    @Test
    void shouldNotFallBackToFixedProviderAfterInvocationEvidenceIsInvalid() {
        // given
        Node root = new Node().properties(
                "name", new Node().value("Strict Root"));
        Node event = new Node().properties(
                "kind", new Node().value("strict-event"));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        AtomicInteger fixedProviderReads = new AtomicInteger();
        NodeProvider fixedProvider = blueId -> {
            if (!rootBlueId.equals(blueId)) {
                return null;
            }
            fixedProviderReads.incrementAndGet();
            return Collections.singletonList(root.clone());
        };
        NodeProvider invalidInvocationProvider = providerWithResult(
                rootBlueId,
                NodeProviderResult.invalidEvidence(
                        "request-local evidence rejected"));

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(fixedProvider)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            PlatformProcessInvocation invocation = invocation(
                    prepareEmptyPlan(contracts, root, event)
                            .deliveryPlan(),
                    invalidInvocationProvider);

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            event,
                            invocation));

            // then
            assertEquals(0, fixedProviderReads.get());
            assertNotNull(failure.getMessage());
        }
    }

    @Test
    void shouldPreserveDefinitiveInvocationProviderMiss() {
        // given
        Node root = new Node().properties(
                "name", new Node().value("Missing request Root"));
        Node event = new Node().properties(
                "kind", new Node().value("missing-request-root"));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        AtomicInteger fixedProviderReads = new AtomicInteger();
        NodeProvider fixedProvider = blueId -> {
            if (!rootBlueId.equals(blueId)) {
                return null;
            }
            fixedProviderReads.incrementAndGet();
            return Collections.singletonList(root.clone());
        };
        NodeProvider missingInvocationProvider = providerWithResult(
                rootBlueId,
                NodeProviderResult.notFound());

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(fixedProvider)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            PlatformProcessInvocation invocation = invocation(
                    prepareEmptyPlan(contracts, root, event)
                            .deliveryPlan(),
                    missingInvocationProvider);

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            event,
                            invocation));

            // then
            assertEquals(0, fixedProviderReads.get());
            assertTrue(failure.getMessage().contains(rootBlueId));
        }
    }

    @Test
    void shouldPreserveRetryableInvocationProviderUnavailability() {
        // given
        Node root = new Node().properties(
                "name", new Node().value("Unavailable request Root"));
        Node event = new Node().properties(
                "kind", new Node().value("unavailable-request-root"));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        AtomicInteger fixedProviderReads = new AtomicInteger();
        NodeProvider fixedProvider = blueId -> {
            if (!rootBlueId.equals(blueId)) {
                return null;
            }
            fixedProviderReads.incrementAndGet();
            return Collections.singletonList(root.clone());
        };
        NodeProvider unavailableInvocationProvider = providerWithResult(
                rootBlueId,
                NodeProviderResult.unavailable(
                        "request fragment store offline"));

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(fixedProvider)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            PlatformProcessInvocation invocation = invocation(
                    prepareEmptyPlan(contracts, root, event)
                            .deliveryPlan(),
                    unavailableInvocationProvider);

            // when
            ExecutionEvidenceUnavailableException failure = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            event,
                            invocation));

            // then
            assertEquals(0, fixedProviderReads.get());
            assertEquals(Collections.singletonList(rootBlueId),
                    failure.requiredExactBlueIds());
            assertEquals("request fragment store offline",
                    failure.getMessage());
        }
    }

    @Test
    void shouldReleaseFailedInvocationScopeAndReuseServiceWithoutClosingProviders() {
        // given
        Node root = new Node().properties(
                "name", new Node().value("Reusable request Root"));
        Node event = new Node().properties(
                "kind", new Node().value("reusable-request-event"));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        CloseTrackingOutcomeProvider rejectedProvider =
                new CloseTrackingOutcomeProvider(
                        rootBlueId,
                        NodeProviderResult.invalidEvidence(
                                "failed invocation proof rejected"));
        CloseTrackingProvider acceptedProvider =
                new CloseTrackingProvider(
                        rootBlueId, root, eventBlueId, event);

        InvalidExecutionEvidenceException failure;
        PlatformProcessingResult retried;
        boolean languageUsableAfterFailure;
        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            ExternalDeliveryPlan plan = prepareEmptyPlan(
                    contracts, root, event).deliveryPlan();
            PlatformProcessInvocation rejectedInvocation = invocation(
                    plan, rejectedProvider);
            PlatformProcessInvocation acceptedInvocation = invocation(
                    plan, acceptedProvider);

            // when
            failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            event,
                            rejectedInvocation));
            languageUsableAfterFailure = !language.identity()
                    .directBlueId(root).isEmpty();
            retried = contracts.processForPlatformCommit(
                    new Node().blueId(rootBlueId),
                    new Node().blueId(eventBlueId),
                    acceptedInvocation);
        }

        // then
        assertNotNull(failure.getMessage());
        assertTrue(languageUsableAfterFailure);
        assertEquals(ProcessorStatus.NO_MATCH,
                retried.processResult().status());
        assertEquals(rootBlueId,
                retried.commitCompanion().expectedRootBlueId());
        assertEquals(eventBlueId,
                retried.commitCompanion().eventBlueId());
        assertEquals(1, rejectedProvider.reads.get());
        assertEquals(2, acceptedProvider.reads.get());
        assertFalse(rejectedProvider.closed.get());
        assertFalse(acceptedProvider.closed.get());
    }

    @Test
    void shouldIsolateConcurrentPlatformInvocationProviders()
            throws Exception {
        // given
        Node firstRoot = new Node().properties(
                "name", new Node().value("Concurrent Root A"));
        Node firstEvent = new Node().properties(
                "kind", new Node().value("concurrent-event-a"));
        Node secondRoot = new Node().properties(
                "name", new Node().value("Concurrent Root B"));
        Node secondEvent = new Node().properties(
                "kind", new Node().value("concurrent-event-b"));
        String firstRootBlueId =
                DirectBlueIdCalculator.calculateBlueId(firstRoot);
        String firstEventBlueId =
                DirectBlueIdCalculator.calculateBlueId(firstEvent);
        String secondRootBlueId =
                DirectBlueIdCalculator.calculateBlueId(secondRoot);
        String secondEventBlueId =
                DirectBlueIdCalculator.calculateBlueId(secondEvent);
        CountDownLatch providersEntered = new CountDownLatch(2);
        CountDownLatch providersReleased = new CountDownLatch(1);
        CoordinatedProvider firstProvider = new CoordinatedProvider(
                firstRootBlueId,
                firstRoot,
                firstEventBlueId,
                firstEvent,
                providersEntered,
                providersReleased);
        CoordinatedProvider secondProvider = new CoordinatedProvider(
                secondRootBlueId,
                secondRoot,
                secondEventBlueId,
                secondEvent,
                providersEntered,
                providersReleased);
        AtomicInteger fixedProviderReads = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        PlatformProcessingResult firstResult;
        PlatformProcessingResult secondResult;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(blueId -> {
                    fixedProviderReads.incrementAndGet();
                    return null;
                })
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            PlatformProcessInvocation firstInvocation = invocation(
                    prepareEmptyPlan(contracts, firstRoot, firstEvent)
                            .deliveryPlan(),
                    firstProvider);
            PlatformProcessInvocation secondInvocation = invocation(
                    prepareEmptyPlan(contracts, secondRoot, secondEvent)
                            .deliveryPlan(),
                    secondProvider);

            // when
            Future<PlatformProcessingResult> first = executor.submit(
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(firstRootBlueId),
                            new Node().blueId(firstEventBlueId),
                            firstInvocation));
            Future<PlatformProcessingResult> second = executor.submit(
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(secondRootBlueId),
                            new Node().blueId(secondEventBlueId),
                            secondInvocation));
            assertTrue(providersEntered.await(5L, TimeUnit.SECONDS),
                    "both invocation providers must be active together");
            providersReleased.countDown();
            firstResult = first.get(5L, TimeUnit.SECONDS);
            secondResult = second.get(5L, TimeUnit.SECONDS);
        } finally {
            providersReleased.countDown();
            executor.shutdownNow();
        }

        // then
        assertEquals(ProcessorStatus.NO_MATCH,
                firstResult.processResult().status());
        assertEquals(ProcessorStatus.NO_MATCH,
                secondResult.processResult().status());
        assertEquals(firstRootBlueId,
                firstResult.commitCompanion().expectedRootBlueId());
        assertEquals(secondRootBlueId,
                secondResult.commitCompanion().expectedRootBlueId());
        assertEquals(2, firstProvider.reads.get());
        assertEquals(2, secondProvider.reads.get());
        assertEquals(0, firstProvider.unexpectedReads.get());
        assertEquals(0, secondProvider.unexpectedReads.get());
        assertEquals(0, fixedProviderReads.get());
    }

    @Test
    void shouldRejectCloseInsideActiveInvocationAndRetainBorrowedProvider() {
        // given
        Node root = new Node().properties(
                "name", new Node().value("Lifecycle Root"));
        Node event = new Node().properties(
                "kind", new Node().value("lifecycle-event"));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        AtomicReference<BlueContracts> service = new AtomicReference<>();
        AtomicReference<IllegalStateException> closeFailure =
                new AtomicReference<>();
        CloseTrackingProvider provider = new CloseTrackingProvider(
                rootBlueId, root, eventBlueId, event,
                () -> {
                    try {
                        service.get().close();
                    } catch (IllegalStateException failure) {
                        closeFailure.set(failure);
                    }
                });

        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            service.set(contracts);
            PlatformProcessInvocation invocation = invocation(
                    prepareEmptyPlan(contracts, root, event)
                            .deliveryPlan(),
                    provider);

            // when
            PlatformProcessingResult result =
                    contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            new Node().blueId(eventBlueId),
                            invocation);
            boolean openAfterRejectedClose = !contracts.isClosed();
            int readsBeforeClose = provider.reads.get();
            contracts.close();
            IllegalStateException afterClose = assertThrows(
                    IllegalStateException.class,
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            new Node().blueId(eventBlueId),
                            invocation));

            // then
            assertEquals(ProcessorStatus.NO_MATCH,
                    result.processResult().status());
            assertNotNull(closeFailure.get());
            assertEquals(
                    "Blue Contracts cannot close from active processing",
                    closeFailure.get().getMessage());
            assertTrue(openAfterRejectedClose);
            assertEquals("Blue Contracts is closed",
                    afterClose.getMessage());
            assertEquals(readsBeforeClose, provider.reads.get());
            assertFalse(provider.closed.get());
        }
    }

    @Test
    void shouldWaitForCrossThreadPlatformInvocationBeforeClosing()
            throws Exception {
        // given
        Node root = new Node().properties(
                "name", new Node().value("Blocking lifecycle Root"));
        Node event = new Node().properties(
                "kind", new Node().value("blocking-lifecycle-event"));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch providerReleased = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CoordinatedProvider provider = new CoordinatedProvider(
                rootBlueId,
                root,
                eventBlueId,
                event,
                providerEntered,
                providerReleased);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (BlueLanguage language = BlueLanguage.builder().build()) {
            BlueContracts contracts = BlueContracts.builder(
                    language.processing()).build();
            PlatformProcessInvocation invocation = invocation(
                    prepareEmptyPlan(contracts, root, event)
                            .deliveryPlan(),
                    provider);

            // when
            Future<PlatformProcessingResult> processing = executor.submit(
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            new Node().blueId(eventBlueId),
                            invocation));
            assertTrue(providerEntered.await(5L, TimeUnit.SECONDS));
            Future<?> closing = executor.submit(() -> {
                closeStarted.countDown();
                contracts.close();
            });
            assertTrue(closeStarted.await(5L, TimeUnit.SECONDS));
            boolean closeWaited = !closing.isDone();
            providerReleased.countDown();
            PlatformProcessingResult result = processing.get(
                    5L, TimeUnit.SECONDS);
            closing.get(5L, TimeUnit.SECONDS);

            // then
            assertTrue(closeWaited,
                    "close must wait while an admitted invocation holds the service");
            assertEquals(ProcessorStatus.NO_MATCH,
                    result.processResult().status());
            assertTrue(contracts.isClosed());
            assertEquals(2, provider.reads.get());
        } finally {
            providerReleased.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldBindTerminatedPlatformProgressToUnchangedRevision() {
        // given
        Node root = terminatedRoot();
        Node event = new Node().properties(
                "kind", new Node().value("after-termination"));
        NodeProvider runtimeTypes =
                BlueRuntimeTypeRegistry.getDefault().asProvider();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            PlatformProcessInvocation invocation = invocation(
                    prepareEmptyPlan(contracts, root, event)
                            .deliveryPlan(),
                    runtimeTypes);

            // when
            PlatformProcessingResult result =
                    contracts.processForPlatformCommit(
                            root, event, invocation);

            // then
            assertEquals(ProcessorStatus.TERMINATED,
                    result.processResult().status());
            assertFalse(result.processResult().commits());
            assertTrue(result.processResult().events().isEmpty());
            assertFalse(result.commitCompanion()
                    .commitsRootAndOutbox());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(root),
                    result.commitCompanion().expectedRootBlueId());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(event),
                    result.commitCompanion().eventBlueId());
            assertEquals(PLATFORM_ROOT_REVISION,
                    result.commitCompanion().expectedRootRevision());
            assertEquals(PLATFORM_ROOT_REVISION,
                    result.commitCompanion().resultingRootRevision());
            assertEquals(PLATFORM_EVENT_ORDER,
                    result.commitCompanion().eventOrderKey());
            assertTrue(result.commitCompanion()
                    .subscriptionDelta().isEmpty());
        }
    }

    private static IndexedDeliveryPreparation prepareEmptyPlan(
            BlueContracts contracts,
            Node root,
            Node event) {
        return contracts.indexedDeliveryEvaluator().prepare(
                root,
                event,
                PLATFORM_ROOT_REVISION,
                PLATFORM_EVENT_ORDER,
                Collections.<SubscriptionDelta.Entry>emptyList(),
                Collections.<ExternalSubscriptionOccurrenceKey>emptyList());
    }

    private static PlatformProcessInvocation invocation(
            ExternalDeliveryPlan plan,
            NodeProvider provider) {
        return PlatformProcessInvocation.builder()
                .deliveryPlan(plan)
                .nodeProvider(provider)
                .build();
    }

    private static Node terminatedRoot() {
        return new Node().contracts(
                new Node().properties(
                        "terminated",
                        new Node()
                                .type(new Node().blueId(
                                        RuntimeBlueIds
                                                .PROCESSING_TERMINATED_MARKER))
                                .properties(
                                        "cause",
                                        new Node().value("business"))
                                .properties(
                                        "reason",
                                        new Node().value("complete"))));
    }

    private static FrozenNode reference(String blueId) {
        return FrozenNode.fromNode(new Node().blueId(blueId));
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

    private static final class CloseTrackingProvider
            implements NodeProvider, AutoCloseable {
        private final Map<String, Node> content = new LinkedHashMap<>();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable firstRead;
        private final AtomicBoolean firstReadObserved =
                new AtomicBoolean();

        private CloseTrackingProvider(
                String firstBlueId,
                Node first,
                String secondBlueId,
                Node second) {
            this(firstBlueId, first, secondBlueId, second, () -> { });
        }

        private CloseTrackingProvider(
                String firstBlueId,
                Node first,
                String secondBlueId,
                Node second,
                Runnable firstRead) {
            content.put(firstBlueId, first.clone());
            content.put(secondBlueId, second.clone());
            this.firstRead = firstRead;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node exact = content.get(blueId);
            if (exact == null) {
                return null;
            }
            if (firstReadObserved.compareAndSet(false, true)) {
                firstRead.run();
            }
            reads.incrementAndGet();
            return Collections.singletonList(exact.clone());
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class CloseTrackingOutcomeProvider
            implements NodeProvider, AutoCloseable {
        private final String requestedBlueId;
        private final NodeProviderResult result;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseTrackingOutcomeProvider(
                String requestedBlueId,
                NodeProviderResult result) {
            this.requestedBlueId = requestedBlueId;
            this.result = result;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            NodeProviderResult fetched = fetchResultByBlueId(blueId);
            return fetched.outcome() == NodeProviderOutcome.FOUND
                    ? fetched.nodes()
                    : null;
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            reads.incrementAndGet();
            return requestedBlueId.equals(blueId)
                    ? result
                    : NodeProviderResult.notFound();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class CoordinatedProvider
            implements NodeProvider {
        private final Map<String, Node> content = new LinkedHashMap<>();
        private final CountDownLatch providersEntered;
        private final CountDownLatch providersReleased;
        private final AtomicBoolean entered = new AtomicBoolean();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger unexpectedReads = new AtomicInteger();

        private CoordinatedProvider(
                String firstBlueId,
                Node first,
                String secondBlueId,
                Node second,
                CountDownLatch providersEntered,
                CountDownLatch providersReleased) {
            content.put(firstBlueId, first.clone());
            content.put(secondBlueId, second.clone());
            this.providersEntered = providersEntered;
            this.providersReleased = providersReleased;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            NodeProviderResult result = fetchResultByBlueId(blueId);
            return result.outcome() == NodeProviderOutcome.FOUND
                    ? result.nodes()
                    : null;
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            Node exact = content.get(blueId);
            if (exact == null) {
                unexpectedReads.incrementAndGet();
                return NodeProviderResult.notFound();
            }
            if (entered.compareAndSet(false, true)) {
                providersEntered.countDown();
                try {
                    if (!providersReleased.await(5L, TimeUnit.SECONDS)) {
                        return NodeProviderResult.unavailable(
                                "concurrent test release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return NodeProviderResult.unavailable(
                            "concurrent test interrupted");
                }
            }
            reads.incrementAndGet();
            return NodeProviderResult.found(
                    Collections.singletonList(exact.clone()));
        }
    }
}
