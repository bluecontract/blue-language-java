package blue.language.processor;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adversarial checks for the public prepared-plan platform boundary. */
final class PlatformProcessInvocationPlanVerificationTest {

    private static final Node CHANNEL_TYPE =
            new Node().name("Platform invocation test channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final String SUBSCRIPTION_KEY = "platform-topic";
    private static final String CHECKPOINT_DISCRIMINATOR =
            "platform-invocation-test";
    private static final long ROOT_REVISION = 29L;
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.<Object>asList(
                    "platform", 29L));

    @Test
    void shouldProcessNonEmptyPreparedPlanAsSuccess() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();
        AtomicInteger constructionDeriverCalls = new AtomicInteger();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .deliveryPlanDeriver((ignoredRoot, ignoredEvent) -> {
                         constructionDeriverCalls.incrementAndGet();
                         throw new AssertionError(
                                 "construction deriver must stay cold");
                     })
                     .build()) {
            ExternalDeliveryPlan plan = prepare(contracts, root, event);
            PlatformProcessInvocation invocation = invocation(
                    plan, runtimeTypes);

            // when
            PlatformProcessingResult result =
                    contracts.processForPlatformCommit(
                            root, event, invocation);

            // then
            assertEquals(1, plan.deliveries().size());
            assertEquals(ProcessorStatus.SUCCESS,
                    result.processResult().status());
            assertTrue(result.processResult().commits());
            assertTrue(result.processResult().events().isEmpty());
            assertEquals(0, constructionDeriverCalls.get());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(root),
                    result.commitCompanion().expectedRootBlueId());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(event),
                    result.commitCompanion().eventBlueId());
            assertEquals(ROOT_REVISION,
                    result.commitCompanion().expectedRootRevision());
            assertEquals(ROOT_REVISION + 1L,
                    result.commitCompanion().resultingRootRevision());
            assertEquals(EVENT_ORDER,
                    result.commitCompanion().eventOrderKey());
            assertTrue(result.commitCompanion().commitsRootAndOutbox());
            assertTrue(result.commitCompanion()
                    .subscriptionDelta().isEmpty());
        }
    }

    @Test
    void shouldAcceptReferenceInputsForPlanPreparedFromInlineTypeForms() {
        // given
        Node rootType = new Node().name(
                "Platform Input Root Type");
        Node eventType = new Node().name(
                "Platform Input Event Type");
        String rootTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(rootType);
        String eventTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(eventType);
        Node inlineRoot = root(true).type(rootType.clone());
        Node referenceRoot = root(true)
                .type(new Node().blueId(rootTypeBlueId));
        Node inlineEvent = event().type(eventType.clone());
        Node referenceEvent = event()
                .type(new Node().blueId(eventTypeBlueId));
        NodeProvider runtimeTypes = outcomeProvider(
                rootTypeBlueId,
                NodeProviderResult.found(
                        Collections.singletonList(rootType)),
                outcomeProvider(
                        eventTypeBlueId,
                        NodeProviderResult.found(
                                Collections.singletonList(eventType)),
                        platformTypes()));

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan plan = prepare(
                    contracts, inlineRoot, inlineEvent);

            // when
            PlatformProcessingResult result =
                    contracts.processForPlatformCommit(
                            referenceRoot,
                            referenceEvent,
                            invocation(plan, runtimeTypes));

            // then
            String inlineRootBlueId = language.identity()
                    .sourceDocumentBlueId(inlineRoot);
            String referenceRootBlueId = language.identity()
                    .sourceDocumentBlueId(referenceRoot);
            String inlineEventBlueId = language.identity()
                    .sourceDocumentBlueId(inlineEvent);
            String referenceEventBlueId = language.identity()
                    .sourceDocumentBlueId(referenceEvent);
            assertEquals(inlineRootBlueId, referenceRootBlueId);
            assertEquals(inlineRootBlueId,
                    plan.verifiedBinding().rootBlueId());
            assertEquals(inlineEventBlueId, referenceEventBlueId);
            assertEquals(inlineEventBlueId,
                    plan.verifiedBinding().eventBlueId());
            assertEquals(
                    plan.verifiedBinding().eventBlueId(),
                    plan.deliveries().get(0)
                            .checkpointSubjectBlueId());
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    result.processResult().status());
        }
    }

    @Test
    void shouldReplayHostedOutputsThroughInvocationProviderBoundary() {
        // given
        Node hostedOutput = new Node()
                .name("Platform hosted payload")
                .properties(
                        "message",
                        new Node().value("request-local"));
        String hostedOutputBlueId =
                DirectBlueIdCalculator.calculateBlueId(hostedOutput);
        Node root = root(true, hostedOutputBlueId);
        Node event = event();
        PlatformChannelProcessor processor =
                new PlatformChannelProcessor();
        NodeProvider preparationProvider = outcomeProvider(
                hostedOutputBlueId,
                NodeProviderResult.found(
                        Collections.singletonList(hostedOutput)),
                platformTypes());
        NodeProvider unavailableInvocationProvider = outcomeProvider(
                hostedOutputBlueId,
                NodeProviderResult.unavailable(
                        "hosted payload store offline"),
                platformTypes());

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(preparationProvider)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(
                             CHANNEL_TYPE_BLUE_ID,
                             processor))
                     .build()) {
            ExternalDeliveryPlan plan = prepare(
                    contracts, root, event);
            int callsAfterPreparation = processor.payloadCalls.get();
            assertTrue(callsAfterPreparation > 0);

            // when
            ExecutionEvidenceUnavailableException failure = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> contracts.processForPlatformCommit(
                            root,
                            event,
                            invocation(
                                    plan,
                                    unavailableInvocationProvider)));

            // then
            assertEquals(Collections.singletonList(hostedOutputBlueId),
                    failure.requiredExactBlueIds());
            assertEquals("hosted payload store offline",
                    failure.getMessage());
            assertEquals(callsAfterPreparation + 1,
                    processor.payloadCalls.get(),
                    "direct supplied-plan replay must fail before PROCESS "
                            + "can evaluate the hosted payload again");
        }
    }

    @Test
    void shouldProcessNonEmptyPreparedPlanAsStaleProgress() {
        // given
        Node root = root(false);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            PlatformProcessInvocation invocation = invocation(
                    prepare(contracts, root, event), runtimeTypes);

            // when
            PlatformProcessingResult result =
                    contracts.processForPlatformCommit(
                            root, event, invocation);

            // then
            assertEquals(ProcessorStatus.STALE,
                    result.processResult().status());
            assertFalse(result.processResult().commits());
            assertTrue(result.processResult().events().isEmpty());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(root),
                    result.commitCompanion().expectedRootBlueId());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(event),
                    result.commitCompanion().eventBlueId());
            assertEquals(ROOT_REVISION,
                    result.commitCompanion().expectedRootRevision());
            assertEquals(ROOT_REVISION,
                    result.commitCompanion().resultingRootRevision());
            assertEquals(EVENT_ORDER,
                    result.commitCompanion().eventOrderKey());
            assertFalse(result.commitCompanion().commitsRootAndOutbox());
            assertTrue(result.commitCompanion()
                    .subscriptionDelta().isEmpty());
        }
    }

    @Test
    void shouldRejectPlanWithoutCompleteActiveIntervalEvidence() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            ExternalDeliveryPlan forged = copyPlanWithoutIntervalSurface(
                    evaluated)
                    .build()
                    .withVerifiedBinding(
                            evaluated.verifiedBinding().rootBlueId(),
                            evaluated.verifiedBinding().eventBlueId(),
                            evaluated.verifiedBinding()
                                    .runtimeRegistryIdentity());
            PlatformProcessInvocation invocation = invocation(
                    forged, runtimeTypes);

            // when
            ExecutionEvidenceUnavailableException failure = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> contracts.processForPlatformCommit(
                            root, event, invocation));

            // then
            assertTrue(failure.getMessage().contains(
                    "evidence is unavailable"));
        }
    }

    @Test
    void shouldRejectPlanWithOmittedDeliveryDespiteCompleteSurface() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            ExternalDeliveryPlan forged = copyPlanWithoutDeliveries(
                    evaluated)
                    .build()
                    .withVerifiedBinding(
                            evaluated.verifiedBinding().rootBlueId(),
                            evaluated.verifiedBinding().eventBlueId(),
                            evaluated.verifiedBinding()
                                    .runtimeRegistryIdentity());
            PlatformProcessInvocation invocation = invocation(
                    forged, runtimeTypes);

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root, event, invocation));

            // then
            assertTrue(failure.getMessage().contains(
                    "omitted a true preselection"));
        }
    }

    @Test
    void shouldRejectPreparedPlanBoundToWrongRoot() {
        // given
        Node root = root(true);
        Node event = event();
        Node wrongRoot = new Node().value("wrong platform Root");
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            PlatformProcessInvocation invocation = invocation(
                    prepare(contracts, root, event), runtimeTypes);

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            wrongRoot, event, invocation));

            // then
            assertEquals(
                    "Execution evidence does not bind to the exact Root and event",
                    failure.getMessage());
        }
    }

    @Test
    void shouldRejectPreparedPlanBoundToWrongEvent() {
        // given
        Node root = root(true);
        Node event = event();
        Node wrongEvent = new Node().value("wrong platform event");
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            PlatformProcessInvocation invocation = invocation(
                    prepare(contracts, root, event), runtimeTypes);

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root, wrongEvent, invocation));

            // then
            assertEquals(
                    "Execution evidence does not bind to the exact Root and event",
                    failure.getMessage());
        }
    }

    @Test
    void shouldRejectPlanWithExtraDelivery() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            ExternalDeliverySnapshot original =
                    evaluated.deliveries().get(0);
            ExternalDeliveryPlan forged = copyPlan(evaluated)
                    .delivery(copyDelivery(
                            original,
                            "extra",
                            original.sourceContributionNodeBlueIds()))
                    .build()
                    .withVerifiedBinding(
                            evaluated.verifiedBinding().rootBlueId(),
                            evaluated.verifiedBinding().eventBlueId(),
                            evaluated.verifiedBinding()
                                    .runtimeRegistryIdentity());

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root,
                            event,
                            invocation(forged, runtimeTypes)));

            // then
            assertTrue(failure.getMessage().contains(
                    "outside the retained active subscription surface"));
        }
    }

    @Test
    void shouldRejectPlanWithDuplicateDelivery() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            ExternalDeliveryPlan forged = copyPlan(evaluated)
                    .delivery(evaluated.deliveries().get(0))
                    .build()
                    .withVerifiedBinding(
                            evaluated.verifiedBinding().rootBlueId(),
                            evaluated.verifiedBinding().eventBlueId(),
                            evaluated.verifiedBinding()
                                    .runtimeRegistryIdentity());

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root,
                            event,
                            invocation(forged, runtimeTypes)));

            // then
            assertTrue(failure.getMessage().contains(
                    "Duplicate External Channel occurrence"));
        }
    }

    @Test
    void shouldRejectPlanWithChangedSourceContribution() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            ExternalDeliverySnapshot original =
                    evaluated.deliveries().get(0);
            ExternalDeliverySnapshot changed = copyDelivery(
                    original,
                    original.channelKey(),
                    Collections.singletonList(
                            DirectBlueIdCalculator.calculateBlueId(
                                    new Node().value("forged source"))));
            ExternalDeliveryPlan forged = copyPlanWithoutDeliveries(
                    evaluated)
                    .delivery(changed)
                    .build()
                    .withVerifiedBinding(
                            evaluated.verifiedBinding().rootBlueId(),
                            evaluated.verifiedBinding().eventBlueId(),
                            evaluated.verifiedBinding()
                                    .runtimeRegistryIdentity());

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root,
                            event,
                            invocation(forged, runtimeTypes)));

            // then
            assertTrue(failure.getMessage() != null
                    && !failure.getMessage().isEmpty());
        }
    }

    @Test
    void shouldRejectPlanWithChangedDependencyCatalog() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            SubscriptionDelta.Entry original =
                    evaluated.activeSubscriptionIntervals().get(0);
            ExternalChannelDependencySnapshot changedDependencies =
                    new ExternalChannelDependencySnapshot(
                            Collections.singletonList(
                                    DirectBlueIdCalculator.calculateBlueId(
                                            new Node().value(
                                                    "forged dependency"))),
                            Collections.<ExternalChannelDependencySnapshot.Entry>
                                    emptyList(),
                            false);
            SubscriptionDelta.Entry changed = copyInterval(
                    original, changedDependencies);
            ExternalDeliveryPlan forged = copyPlan(evaluated)
                    .activeSubscriptionIntervals(
                            Collections.singletonList(changed))
                    .build()
                    .withVerifiedBinding(
                            evaluated.verifiedBinding().rootBlueId(),
                            evaluated.verifiedBinding().eventBlueId(),
                            evaluated.verifiedBinding()
                                    .runtimeRegistryIdentity());

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root,
                            event,
                            invocation(forged, runtimeTypes)));

            // then
            assertTrue(failure.getMessage() != null
                    && !failure.getMessage().isEmpty());
        }
    }

    @Test
    void shouldRejectInactiveDeliveryAtPlanConstruction() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            ExternalDeliverySnapshot inactive = copyDeliveryBuilder(
                    evaluated.deliveries().get(0),
                    "incoming",
                    evaluated.deliveries().get(0)
                            .sourceContributionNodeBlueIds())
                    .activationStartExclusive(EVENT_ORDER)
                    .build();

            // when
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> copyPlanWithoutDeliveries(evaluated)
                            .delivery(inactive)
                            .build());

            // then
            assertTrue(failure.getMessage().contains(
                    "outside its activation interval"));
        }
    }

    @Test
    void shouldRejectRevisionAndOrderDisagreementInIndependentVerifier() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            ExternalDeliveryPlan wrongRevision =
                    ExternalDeliveryPlan.builder()
                            .revisions(ROOT_REVISION + 1L,
                                    ROOT_REVISION + 1L)
                            .eventOrderKey(EVENT_ORDER)
                            .activeSubscriptionIntervals(
                                    evaluated.activeSubscriptionIntervals())
                            .delivery(evaluated.deliveries().get(0))
                            .exactRuntimeState()
                            .build();
            ExternalDeliveryPlan wrongOrder =
                    ExternalDeliveryPlan.builder()
                            .revisions(ROOT_REVISION, ROOT_REVISION)
                            .eventOrderKey(ExternalOrderKey.of(
                                    Arrays.<Object>asList(
                                            "platform", 30L)))
                            .activeSubscriptionIntervals(
                                    evaluated.activeSubscriptionIntervals())
                            .delivery(evaluated.deliveries().get(0))
                            .exactRuntimeState()
                            .build();

            // when
            InvalidExecutionEvidenceException revisionFailure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> RootExternalDeliveryEvidenceVerifier.INSTANCE
                            .verifyDerived(
                                    root,
                                    event,
                                    evaluated.verifiedBinding(),
                                    wrongRevision));
            InvalidExecutionEvidenceException orderFailure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> RootExternalDeliveryEvidenceVerifier.INSTANCE
                            .verifyDerived(
                                    root,
                                    event,
                                    evaluated.verifiedBinding(),
                                    wrongOrder));

            // then
            assertEquals("External delivery plan revision mismatch",
                    revisionFailure.getMessage());
            assertEquals("External delivery event order mismatch",
                    orderFailure.getMessage());
        }
    }

    @Test
    void shouldRejectPreparedPlanWithWrongRevisionThroughPublicPlatformApi() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            ExternalDeliveryPlan wrongRevision = copyPlanWithHeaders(
                    evaluated,
                    ROOT_REVISION + 1L,
                    ROOT_REVISION + 1L,
                    EVENT_ORDER)
                    .build();
            PlatformProcessInvocation invocation = invocation(
                    retainEvaluatorBinding(evaluated, wrongRevision),
                    runtimeTypes);

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root, event, invocation));

            // then
            assertEquals("External delivery plan revision mismatch",
                    failure.getMessage());
        }
    }

    @Test
    void shouldRejectPreparedPlanWithWrongEventOrderThroughPublicPlatformApi() {
        // given
        Node root = root(true);
        Node event = event();
        NodeProvider runtimeTypes = platformTypes();
        ExternalOrderKey wrongOrder = ExternalOrderKey.of(
                Arrays.<Object>asList("platform", 30L));

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            ExternalDeliveryPlan wrongEventOrder = copyPlanWithHeaders(
                    evaluated,
                    ROOT_REVISION,
                    ROOT_REVISION,
                    wrongOrder)
                    .build();
            PlatformProcessInvocation invocation = invocation(
                    retainEvaluatorBinding(evaluated, wrongEventOrder),
                    runtimeTypes);

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root, event, invocation));

            // then
            assertEquals("External delivery event order mismatch",
                    failure.getMessage());
        }
    }

    @Test
    void shouldCanonicalizeNoncanonicalDeliveryInputBeforeBinding() {
        // given
        ExternalDeliverySnapshot later = syntheticDelivery("later", 2);
        ExternalDeliverySnapshot earlier = syntheticDelivery("earlier", 1);

        // when
        ExternalDeliveryPlan canonical = ExternalDeliveryPlan.builder()
                .revisions(ROOT_REVISION, ROOT_REVISION)
                .eventOrderKey(EVENT_ORDER)
                .activeSubscriptionIntervals(
                        Collections.<SubscriptionDelta.Entry>emptyList())
                .delivery(later)
                .delivery(earlier)
                .exactRuntimeState()
                .build();

        // then
        assertEquals("earlier",
                canonical.deliveries().get(0).channelKey());
        assertEquals("later",
                canonical.deliveries().get(1).channelKey());
    }

    @Test
    void shouldRejectWrongCanonicalOrderInIndependentPlanVerifier() {
        // given
        ExternalDeliverySnapshot later = syntheticDelivery("later", 2);
        ExternalDeliverySnapshot earlier = syntheticDelivery("earlier", 1);
        List<ExternalDeliverySnapshot> wrongCanonicalOrder =
                Arrays.asList(later, earlier);

        // when
        InvalidExecutionEvidenceException failure = assertThrows(
                InvalidExecutionEvidenceException.class,
                () -> ExternalDeliveryPlanVerifier.verifyExactDeliveries(
                        wrongCanonicalOrder,
                        wrongCanonicalOrder));

        // then
        assertEquals(
                "External delivery snapshot is not in canonical order",
                failure.getMessage());
    }

    @Test
    void shouldBindCustomRegistryIdentityToItsExactGeneration() {
        // given
        Node root = new Node().value("registry-bound-root");
        Node event = new Node().value("registry-bound-event");
        String firstType = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("first custom generation"));
        String secondType = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("second custom generation"));

        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts first = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(firstType))
                     .build();
             BlueContracts second = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(secondType))
                     .build()) {
            ExternalDeliveryPlan firstPlan =
                    first.indexedDeliveryEvaluator().prepare(
                            root,
                            event,
                            ROOT_REVISION,
                            EVENT_ORDER,
                            Collections.<SubscriptionDelta.Entry>emptyList(),
                            Collections.<ExternalSubscriptionOccurrenceKey>
                                    emptyList())
                            .deliveryPlan();
            PlatformProcessInvocation invocation = invocation(
                    firstPlan, blueId -> null);

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> second.processForPlatformCommit(
                            root, event, invocation));

            // then
            assertEquals(
                    "Execution evidence runtime registry identity mismatch",
                    failure.getMessage());
        }
    }

    @Test
    void shouldEstablishRequiredExactResourceThroughInvocationProvider() {
        // given
        Node root = root(true);
        Node event = event();
        Node resource = new Node().value("required exact resource");
        String resourceBlueId =
                DirectBlueIdCalculator.calculateBlueId(resource);
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan evaluated = prepare(
                    contracts, root, event);
            ExternalDeliveryPlan plan = withRequiredResource(
                    evaluated, resourceBlueId);
            PlatformProcessInvocation invocation = invocation(
                    plan,
                    outcomeProvider(
                            resourceBlueId,
                            NodeProviderResult.found(
                                    Collections.singletonList(resource)),
                            runtimeTypes));

            // when
            PlatformProcessingResult result =
                    contracts.processForPlatformCommit(
                            root, event, invocation);

            // then
            assertEquals(ProcessorStatus.SUCCESS,
                    result.processResult().status());
        }
    }

    @Test
    void shouldPreserveTypedRequiredExactResourceOutcomes() {
        // given
        Node root = root(true);
        Node event = event();
        Node resource = new Node().value("typed required resource");
        String resourceBlueId =
                DirectBlueIdCalculator.calculateBlueId(resource);
        NodeProvider runtimeTypes = platformTypes();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(runtimeTypes)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan plan = withRequiredResource(
                    prepare(contracts, root, event),
                    resourceBlueId);

            // when
            InvalidExecutionEvidenceException missing = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root,
                            event,
                            invocation(
                                    plan,
                                    outcomeProvider(
                                            resourceBlueId,
                                            NodeProviderResult.notFound(),
                                            runtimeTypes))));
            ExecutionEvidenceUnavailableException unavailable = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> contracts.processForPlatformCommit(
                            root,
                            event,
                            invocation(
                                    plan,
                                    outcomeProvider(
                                            resourceBlueId,
                                            NodeProviderResult.unavailable(
                                                    "resource store offline"),
                                            runtimeTypes))));
            InvalidExecutionEvidenceException invalid = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            root,
                            event,
                            invocation(
                                    plan,
                                    outcomeProvider(
                                            resourceBlueId,
                                            NodeProviderResult.invalidEvidence(
                                                    "resource proof rejected"),
                                            runtimeTypes))));

            // then
            assertTrue(missing.getMessage().contains(resourceBlueId));
            assertEquals(Collections.singletonList(resourceBlueId),
                    unavailable.requiredExactBlueIds());
            assertEquals("resource store offline",
                    unavailable.getMessage());
            assertEquals("resource proof rejected", invalid.getMessage());
        }
    }

    @Test
    void shouldPreserveTypedOutcomesForEvaluatorSelectedReferenceRoot() {
        // given
        Node root = root(true);
        Node event = event();
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(root);
        NodeProvider runtimeTypes = platformTypes();
        NodeProvider preparationProvider = outcomeProvider(
                rootBlueId,
                NodeProviderResult.found(
                        Collections.singletonList(root)),
                runtimeTypes);

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(preparationProvider)
                .build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing())
                     .runtimeRegistry(registry(CHANNEL_TYPE_BLUE_ID))
                     .build()) {
            ExternalDeliveryPlan plan = contracts
                    .indexedDeliveryEvaluator()
                    .prepare(
                            new Node().blueId(rootBlueId),
                            event,
                            ROOT_REVISION,
                            EVENT_ORDER,
                            Collections.singletonList(
                                    interval(root.getContracts()
                                            .getProperties()
                                            .get("incoming"))),
                            Collections.singletonList(
                                    ExternalSubscriptionOccurrenceKey.of(
                                            "/", "incoming")))
                    .deliveryPlan();

            // when
            PlatformProcessingResult found =
                    contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            event,
                            invocation(
                                    plan,
                                    outcomeProvider(
                                            rootBlueId,
                                            NodeProviderResult.found(
                                                    Collections.singletonList(
                                                            root)),
                                            runtimeTypes)));
            InvalidExecutionEvidenceException missing = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            event,
                            invocation(
                                    plan,
                                    outcomeProvider(
                                            rootBlueId,
                                            NodeProviderResult.notFound(),
                                            runtimeTypes))));
            ExecutionEvidenceUnavailableException unavailable = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            event,
                            invocation(
                                    plan,
                                    outcomeProvider(
                                            rootBlueId,
                                            NodeProviderResult.unavailable(
                                                    "selected Root offline"),
                                            runtimeTypes))));
            InvalidExecutionEvidenceException invalid = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> contracts.processForPlatformCommit(
                            new Node().blueId(rootBlueId),
                            event,
                            invocation(
                                    plan,
                                    outcomeProvider(
                                            rootBlueId,
                                            NodeProviderResult.invalidEvidence(
                                                    "selected Root proof rejected"),
                                            runtimeTypes))));

            // then
            assertEquals(ProcessorStatus.SUCCESS,
                    found.processResult().status());
            assertTrue(missing.getMessage().contains(rootBlueId));
            assertEquals(Collections.singletonList(rootBlueId),
                    unavailable.requiredExactBlueIds());
            assertEquals("selected Root offline",
                    unavailable.getMessage());
            assertEquals("selected Root proof rejected",
                    invalid.getMessage());
        }
    }

    private static ContractProcessorRegistry registry(String blueId) {
        return registry(blueId, new PlatformChannelProcessor());
    }

    private static ContractProcessorRegistry registry(
            String blueId,
            PlatformChannelProcessor processor) {
        ContractProcessorRegistryBuilder builder =
                ContractProcessorRegistryBuilder.create();
        if (CHANNEL_TYPE_BLUE_ID.equals(blueId)) {
            builder.register(
                    blueId,
                    CHANNEL_TYPE,
                    processor);
        } else {
            builder.register(
                    blueId,
                    processor);
        }
        return builder.build();
    }

    private static ExternalDeliveryPlan prepare(
            BlueContracts contracts,
            Node root,
            Node event) {
        Node channel = root.getContracts().getProperties().get("incoming");
        return contracts.indexedDeliveryEvaluator().prepare(
                root,
                event,
                ROOT_REVISION,
                EVENT_ORDER,
                Collections.singletonList(interval(channel)),
                Collections.singletonList(
                        ExternalSubscriptionOccurrenceKey.of(
                                "/", "incoming")))
                .deliveryPlan();
    }

    private static PlatformProcessInvocation invocation(
            ExternalDeliveryPlan plan,
            NodeProvider provider) {
        return PlatformProcessInvocation.builder()
                .deliveryPlan(plan)
                .nodeProvider(provider)
                .build();
    }

    private static ExternalDeliveryPlan withRequiredResource(
            ExternalDeliveryPlan evaluated,
            String blueId) {
        return copyPlan(evaluated)
                .requiredExactNode(blueId)
                .build()
                .withVerifiedBinding(
                        evaluated.verifiedBinding().rootBlueId(),
                        evaluated.verifiedBinding().eventBlueId(),
                        evaluated.verifiedBinding()
                                .runtimeRegistryIdentity());
    }

    private static ExternalDeliveryPlan.Builder copyPlan(
            ExternalDeliveryPlan source) {
        ExternalDeliveryPlan.Builder builder = copyPlanWithoutDeliveries(
                source);
        for (ExternalDeliverySnapshot delivery : source.deliveries()) {
            builder.delivery(delivery);
        }
        return builder;
    }

    private static ExternalDeliveryPlan.Builder copyPlanWithHeaders(
            ExternalDeliveryPlan source,
            long managedRootRevision,
            long indexedRootRevision,
            ExternalOrderKey eventOrderKey) {
        ExternalDeliveryPlan.Builder builder =
                ExternalDeliveryPlan.builder()
                        .revisions(
                                managedRootRevision,
                                indexedRootRevision)
                        .eventOrderKey(eventOrderKey)
                        .exactRuntimeState();
        if (source.hasActiveSubscriptionIntervals()) {
            builder.activeSubscriptionIntervals(
                    source.activeSubscriptionIntervals());
        }
        for (ExternalDeliverySnapshot delivery : source.deliveries()) {
            builder.delivery(delivery);
        }
        for (String blueId : source.availableExactNodeBlueIds()) {
            builder.availableExactNode(blueId);
        }
        for (String blueId : source.requiredExactNodeBlueIds()) {
            builder.requiredExactNode(blueId);
        }
        return builder;
    }

    /**
     * Produces a test-only tampered plan which retains the evaluator's sealed
     * binding. Public construction cannot create this state, but the verifier
     * must still reject it if an object is corrupted after deserialization or
     * by an unsafe host boundary.
     */
    private static ExternalDeliveryPlan retainEvaluatorBinding(
            ExternalDeliveryPlan evaluated,
            ExternalDeliveryPlan tampered) {
        try {
            Constructor<ExternalDeliveryPlan> constructor =
                    ExternalDeliveryPlan.class.getDeclaredConstructor(
                            ExternalDeliveryPlan.class,
                            VerifiedExecutionEvidence.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    tampered,
                    evaluated.verifiedBinding());
        } catch (NoSuchMethodException
                 | InstantiationException
                 | IllegalAccessException
                 | InvocationTargetException failure) {
            throw new AssertionError(
                    "Unable to create adversarial sealed delivery plan",
                    failure);
        }
    }

    private static ExternalDeliveryPlan.Builder copyPlanWithoutDeliveries(
            ExternalDeliveryPlan source) {
        ExternalDeliveryPlan.Builder builder =
                ExternalDeliveryPlan.builder()
                        .revisions(
                                source.managedRootRevision(),
                                source.indexedRootRevision())
                        .eventOrderKey(source.eventOrderKey())
                        .exactRuntimeState();
        if (source.hasActiveSubscriptionIntervals()) {
            builder.activeSubscriptionIntervals(
                    source.activeSubscriptionIntervals());
        }
        for (String blueId : source.availableExactNodeBlueIds()) {
            builder.availableExactNode(blueId);
        }
        for (String blueId : source.requiredExactNodeBlueIds()) {
            builder.requiredExactNode(blueId);
        }
        return builder;
    }

    private static ExternalDeliveryPlan.Builder
    copyPlanWithoutIntervalSurface(ExternalDeliveryPlan source) {
        ExternalDeliveryPlan.Builder builder =
                ExternalDeliveryPlan.builder()
                        .revisions(
                                source.managedRootRevision(),
                                source.indexedRootRevision())
                        .eventOrderKey(source.eventOrderKey())
                        .exactRuntimeState();
        for (ExternalDeliverySnapshot delivery : source.deliveries()) {
            builder.delivery(delivery);
        }
        for (String blueId : source.availableExactNodeBlueIds()) {
            builder.availableExactNode(blueId);
        }
        for (String blueId : source.requiredExactNodeBlueIds()) {
            builder.requiredExactNode(blueId);
        }
        return builder;
    }

    private static ExternalDeliverySnapshot copyDelivery(
            ExternalDeliverySnapshot source,
            String channelKey,
            List<String> sourceContributions) {
        return copyDeliveryBuilder(
                source, channelKey, sourceContributions).build();
    }

    private static ExternalDeliverySnapshot.Builder copyDeliveryBuilder(
            ExternalDeliverySnapshot source,
            String channelKey,
            List<String> sourceContributions) {
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                source.scopePath(), channelKey)
                        .order(source.order())
                        .effectiveTypeBlueId(
                                source.effectiveTypeBlueId())
                        .checkpointDomainBlueId(
                                source.checkpointDomainBlueId())
                        .checkpointSubjectBlueId(
                                source.checkpointSubjectBlueId())
                        .activationStartExclusive(
                                source.activationStartExclusive())
                        .activationEndInclusive(
                                source.activationEndInclusive());
        for (String blueId : sourceContributions) {
            builder.sourceContribution(blueId);
        }
        for (String key : source.subscriptionKeys()) {
            builder.subscriptionKey(key);
        }
        return builder;
    }

    private static SubscriptionDelta.Entry copyInterval(
            SubscriptionDelta.Entry source,
            ExternalChannelDependencySnapshot dependencies) {
        return new SubscriptionDelta.Entry(
                source.scopePath(),
                source.channelKey(),
                source.effectiveTypeBlueId(),
                source.sourceContributionNodeBlueIds(),
                source.order(),
                source.subscriptionKeys(),
                source.checkpointDomainBlueId(),
                dependencies,
                source.activationRootRevision(),
                source.startAfterExternalOrderKey(),
                source.endAtRootRevision());
    }

    private static ExternalDeliverySnapshot syntheticDelivery(
            String channelKey,
            int order) {
        String contribution = DirectBlueIdCalculator.calculateBlueId(
                new Node().value(channelKey));
        return ExternalDeliverySnapshot.builder("/", channelKey)
                .order(order)
                .sourceContribution(contribution)
                .effectiveTypeBlueId(CHANNEL_TYPE_BLUE_ID)
                .subscriptionKey(SUBSCRIPTION_KEY)
                .checkpointDomainBlueId(
                        CheckpointDomain.derive(
                                CHANNEL_TYPE_BLUE_ID,
                                Collections.singletonList(contribution),
                                CHECKPOINT_DISCRIMINATOR))
                .checkpointSubjectBlueId(
                        DirectBlueIdCalculator.calculateBlueId(event()))
                .build();
    }

    private static NodeProvider outcomeProvider(
            String exactBlueId,
            NodeProviderResult exactResult,
            NodeProvider fallback) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult result = fetchResultByBlueId(blueId);
                return result.outcome() == NodeProviderOutcome.FOUND
                        ? result.nodes() : null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                return exactBlueId.equals(blueId)
                        ? exactResult
                        : fallback.fetchResultByBlueId(blueId);
            }
        };
    }

    private static NodeProvider platformTypes() {
        NodeProvider runtimeTypes =
                BlueRuntimeTypeRegistry.getDefault().asProvider();
        return outcomeProvider(
                CHANNEL_TYPE_BLUE_ID,
                NodeProviderResult.found(
                        Collections.singletonList(CHANNEL_TYPE)),
                runtimeTypes);
    }

    private static Node root(boolean newer) {
        return root(newer, null);
    }

    private static Node root(
            boolean newer,
            String payloadBlueId) {
        Node channel = new Node()
                .type(new Node().blueId(
                        CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "order", new Node().value(0))
                .properties(
                        "subscriptionKey",
                        new Node().value(SUBSCRIPTION_KEY))
                .properties(
                        "newer", new Node().value(newer));
        if (payloadBlueId != null) {
            channel.properties(
                    "payloadBlueId",
                    new Node().value(payloadBlueId));
        }
        return new Node().contracts(
                new Node().properties(
                        "incoming",
                        channel));
    }

    private static Node event() {
        return new Node().properties(
                "subscriptionKey",
                new Node().value(SUBSCRIPTION_KEY));
    }

    private static SubscriptionDelta.Entry interval(Node channel) {
        String contribution =
                DirectBlueIdCalculator.calculateBlueId(channel);
        List<String> contributions =
                Collections.singletonList(contribution);
        return new SubscriptionDelta.Entry(
                "/",
                "incoming",
                CHANNEL_TYPE_BLUE_ID,
                contributions,
                0,
                Collections.singletonList(SUBSCRIPTION_KEY),
                CheckpointDomain.derive(
                        CHANNEL_TYPE_BLUE_ID,
                        contributions,
                        CHECKPOINT_DISCRIMINATOR),
                ExternalChannelDependencySnapshot.none(),
                1L,
                null,
                null);
    }

    /** Mutable conversion model used only by the test registry. */
    public static final class PlatformChannel extends ChannelContract {
        private String subscriptionKey;
        private Boolean newer;
        private String payloadBlueId;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public Boolean getNewer() {
            return newer;
        }

        public void setNewer(Boolean newer) {
            this.newer = newer;
        }

        public String getPayloadBlueId() {
            return payloadBlueId;
        }

        public void setPayloadBlueId(String payloadBlueId) {
            this.payloadBlueId = payloadBlueId;
        }
    }

    /** Deterministic external-channel behavior for platform tests. */
    private static final class PlatformChannelProcessor
            implements ChannelProcessor<PlatformChannel> {

        private final AtomicInteger payloadCalls = new AtomicInteger();

        private final ExternalChannelSubscriptionFunctions<PlatformChannel>
                subscriptionFunctions =
                new ExternalChannelSubscriptionFunctions<PlatformChannel>() {
                    @Override
                    public List<String> channelKeys(
                            PlatformChannel channel) {
                        return Collections.singletonList(
                                channel.getSubscriptionKey());
                    }

                    @Override
                    public boolean preselects(
                            PlatformChannel channel,
                            Node exactEvent) {
                        return true;
                    }

                    @Override
                    public boolean accepts(
                            PlatformChannel channel,
                            Node exactEvent) {
                        return true;
                    }

                    @Override
                    public Node payload(
                            PlatformChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        payloadCalls.incrementAndGet();
                        if (!context.runtimeWorkSession()
                                .hasSemanticOutputBoundary()) {
                            throw new IllegalStateException(
                                    "supplied-plan replay lost its invocation "
                                            + "Language boundary");
                        }
                        return channel.getPayloadBlueId() != null
                                ? new Node().blueId(
                                        channel.getPayloadBlueId())
                                : exactEvent.clone();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            PlatformChannel channel) {
                        return CHECKPOINT_DISCRIMINATOR;
                    }
                };

        @Override
        public Class<PlatformChannel> contractType() {
            return PlatformChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<PlatformChannel>
        externalSubscriptionFunctions() {
            return subscriptionFunctions;
        }

        @Override
        public ChannelEvaluation evaluate(
                PlatformChannel channel,
                ChannelEvaluationContext context) {
            return ChannelEvaluation.match(context.event());
        }

        @Override
        public boolean isNewerEvent(
                PlatformChannel channel,
                ChannelCheckpointContext context) {
            return Boolean.TRUE.equals(channel.getNewer());
        }
    }
}
