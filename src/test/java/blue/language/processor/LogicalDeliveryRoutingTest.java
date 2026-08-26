package blue.language.processor;

import blue.language.Blue;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic kernel coverage for immutable logical-delivery routing. The fixture
 * deliberately uses no application-specific runtime type or contract name.
 */
final class LogicalDeliveryRoutingTest {

    private static final Node DEFAULT_CHANNEL_TYPE =
            new Node().name("Generic Default External Channel");
    private static final String DEFAULT_CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    DEFAULT_CHANNEL_TYPE);
    private static final Node ROUTING_CHANNEL_TYPE =
            new Node().name("Generic Routing External Channel");
    private static final String ROUTING_CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    ROUTING_CHANNEL_TYPE);
    private static final Node HANDLER_TYPE =
            new Node().name("Generic Logical Delivery Handler");
    private static final String HANDLER_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(HANDLER_TYPE);
    private static final Node HEADER_PROBE_TYPE =
            new Node().name("Generic Header Materialization Probe");
    private static final String HEADER_PROBE_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    HEADER_PROBE_TYPE);
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(
                    Arrays.<Object>asList(
                            41, "logical-delivery", 1));

    @Test
    void shouldVerifyDefaultFunctionsPreserveRawSourceDispatchAndCheckpoint() {
        // given
        Node event = event("topic", "event-default");
        try (Fixture fixture = new Fixture(event)) {
            Node document = fixture.initialize(root(
                    defaultChannel(
                            "source",
                            0,
                            "topic",
                            "domain-source",
                            "default-payload"),
                    handler(
                            "handler",
                            "source",
                            fixture.selectedBodyBlueId)));
            PreparedRun prepared = fixture.prepare(
                    document, event, "source");

            // when
            ExternalChannelFunctionEvaluation evaluation =
                    fixture.evaluate(
                            document, event, "source");
            ProcessingDebugResult debug =
                    fixture.process(
                            document, event, prepared);

            // then
            assertEquals(
                    "source",
                    evaluation.handlerChannelKey());
            assertEquals(
                    "source",
                    evaluation.logicalDeliveryKey());
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(1, fixture.handlers.executions());
            assertEquals(
                    Collections.singletonList("source"),
                    fixture.handlers.matchedChannels());
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source"));
        }
    }

    @Test
    void shouldVerifyTwoFreshSourcesDispatchOnceAndAdvanceBothRawCheckpoints() {
        // given
        Node event = event("topic", "event-group");
        try (Fixture fixture = new Fixture(event)) {
            Node document = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            PreparedRun prepared = fixture.prepare(
                    document,
                    event,
                    "source-a",
                    "source-b");

            // when
            ProcessingDebugResult debug =
                    fixture.process(
                            document, event, prepared);

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(1, fixture.handlers.executions());
            assertEquals(
                    Collections.singletonList("target"),
                    fixture.handlers.matchedChannels());
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source-a"));
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source-b"));
            assertEquals(
                    Arrays.asList("source-a", "source-b"),
                    checkpointWrites(debug.trace()));
        }
    }

    @Test
    void shouldFreezeEveryAcceptedLogicalGroupBeforeAnEarlierGroupChangesRoutes() {
        // given
        Node event = event("topic", "event-frozen-groups");
        try (Fixture fixture = new Fixture(event)) {
            Node document = fixture.initialize(root(
                    routingChannel(
                            "source-one", 0, "topic", "domain-one",
                            "target-one", "logical-one", "payload-one"),
                    routingChannel(
                            "source-two", 1, "topic", "domain-two",
                            "target-two", "logical-two", "payload-two"),
                    routingChannel(
                            "target-one", 2, "other", "domain-target-one",
                            "target-one", "target-one", "target-one"),
                    routingChannel(
                            "target-two", 3, "other", "domain-target-two",
                            "target-two", "target-two", "target-two"),
                    handler(
                            "handler-one", "target-one",
                            fixture.selectedBodyBlueId),
                    handler(
                            "handler-two", "target-two",
                            fixture.selectedBodyBlueId)));
            fixture.handlers.enableRouteEvolution();

            // when
            ProcessingDebugResult current = fixture.process(
                    document,
                    event,
                    fixture.prepare(
                            document, event, "source-one", "source-two"));

            // then
            assertEquals(ProcessorStatus.SUCCESS,
                    current.processResult().status(),
                    diagnostic(current.processResult()));
            assertEquals(Arrays.asList("handler-one", "handler-two"),
                    fixture.handlers.executedContracts());
            Node currentContracts = current.processResult().document()
                    .getContracts();
            assertEquals("replacement-target-two",
                    property(currentContracts, "target-two")
                            .get("/payload"));
            assertNull(property(currentContracts, "handler-two"));
            assertNotNull(property(
                    currentContracts, "replacement-handler-two"));
            assertNotNull(property(currentContracts, "source-later"));
            assertNotNull(property(currentContracts, "target-later"));
            assertNotNull(property(currentContracts, "handler-later"));
            assertTrue(hasCheckpoint(
                    current.processResult().document(), "source-one"));
            assertTrue(hasCheckpoint(
                    current.processResult().document(), "source-two"));
            assertFalse(hasCheckpoint(
                    current.processResult().document(), "source-later"));

            fixture.handlers.disableRouteEvolutionAndReset();
            Node afterCurrent = current.processResult().document();
            ProcessingDebugResult later = fixture.process(
                    afterCurrent,
                    event,
                    fixture.prepare(afterCurrent, event, "source-later"));

            assertEquals(ProcessorStatus.SUCCESS,
                    later.processResult().status());
            assertEquals(Collections.singletonList("handler-later"),
                    fixture.handlers.executedContracts());
            assertTrue(hasCheckpoint(
                    later.processResult().document(), "source-later"));
        }
    }

    @Test
    void shouldPreserveSourceOrderAcrossTiedSourceArrivalPermutations() {
        // given
        Node event = event("topic", "event-arrival-permutation");
        try (Fixture fixture = new Fixture(event)) {
            Node document = fixture.initialize(root(
                    routingChannel(
                            "source-a", 0, "topic", "domain-a",
                            "target", "logical", "shared-payload"),
                    routingChannel(
                            "source-b", 0, "topic", "domain-b",
                            "target", "logical", "shared-payload"),
                    routingChannel(
                            "target", 2, "other", "domain-target",
                            "target", "target", "target"),
                    handler(
                            "handler",
                            "target",
                            fixture.selectedBodyBlueId)));
            PreparedRun sourceOrder = fixture.prepare(
                    document,
                    event,
                    "source-a",
                    "source-b");
            PreparedRun reversedArrival = fixture.prepare(
                    document,
                    event,
                    "source-b",
                    "source-a");

            // when
            ProcessingDebugResult sourceOrderResult = fixture.process(
                    document.clone(),
                    event,
                    sourceOrder);
            fixture.handlers.reset();
            ProcessingDebugResult reversedArrivalResult = fixture.process(
                    document.clone(),
                    event,
                    reversedArrival);

            // then
            assertEquals(
                    planProjection(sourceOrder.plan),
                    planProjection(reversedArrival.plan));
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    sourceOrderResult.processResult().status());
            assertEquals(
                    sourceOrderResult.processResult().status(),
                    reversedArrivalResult.processResult().status());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            sourceOrderResult.processResult().document()),
                    DirectBlueIdCalculator.calculateBlueId(
                            reversedArrivalResult.processResult().document()));
            assertEquals(
                    sourceOrderResult.processResult().totalGas(),
                    reversedArrivalResult.processResult().totalGas());
            assertEquals(
                    traceProjection(sourceOrderResult.trace()),
                    traceProjection(reversedArrivalResult.trace()));
            assertEquals(
                    Arrays.asList("source-a", "source-b"),
                    checkpointWrites(reversedArrivalResult.trace()));
        }
    }

    @Test
    void shouldVerifyStaleMemberIsExcludedAndOnlyFreshSourceAdvances() {
        // given
        Node event = event("topic", "event-stale");
        try (Fixture fixture = new Fixture(event)) {
            Node initialized = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            ProcessingDebugResult seed = fixture.process(
                    initialized,
                    event,
                    fixture.prepare(
                            initialized,
                            event,
                            "source-b"));
            fixture.handlers.reset();
            Node withStaleSource =
                    seed.processResult().document();

            // when
            ProcessingDebugResult debug = fixture.process(
                    withStaleSource,
                    event,
                    fixture.prepare(
                            withStaleSource,
                            event,
                            "source-a",
                            "source-b"));

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    seed.processResult().status());
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(1, fixture.handlers.executions());
            assertEquals(
                    Collections.singletonList("source-a"),
                    checkpointWrites(debug.trace()));
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source-a"));
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source-b"));
        }
    }

    @Test
    void shouldVerifyAllStaleSourcesExecuteNothingAndWriteNoCheckpoint() {
        // given
        Node event = event("topic", "event-all-stale");
        try (Fixture fixture = new Fixture(event)) {
            Node initialized = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            ProcessingDebugResult seed = fixture.process(
                    initialized,
                    event,
                    fixture.prepare(
                            initialized,
                            event,
                            "source-a",
                            "source-b"));
            fixture.handlers.reset();
            Node checkpointed =
                    seed.processResult().document();

            // when
            ProcessingDebugResult replay = fixture.process(
                    checkpointed,
                    event,
                    fixture.prepare(
                            checkpointed,
                            event,
                            "source-a",
                            "source-b"));

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    seed.processResult().status());
            assertEquals(
                    ProcessorStatus.STALE,
                    replay.processResult().status());
            assertEquals(0, fixture.handlers.executions());
            assertTrue(checkpointWrites(
                    replay.trace()).isEmpty());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            checkpointed),
                    DirectBlueIdCalculator.calculateBlueId(
                            replay.processResult().document()));
        }
    }

    @Test
    void shouldVerifyHandlerFailureCommitsNoParticipatingCheckpoint() {
        // given
        Node event = event("topic", "event-failure");
        try (Fixture fixture = new Fixture(event)) {
            Node document = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            fixture.handlers.setFailureEnabled(true);

            // when
            ProcessingDebugResult debug = fixture.process(
                    document,
                    event,
                    fixture.prepare(
                            document,
                            event,
                            "source-a",
                            "source-b"));

            // then
            assertEquals(
                    ProcessorStatus.RUNTIME_FATAL,
                    debug.processResult().status());
            assertEquals(1, fixture.handlers.executions());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(document),
                    DirectBlueIdCalculator.calculateBlueId(
                            debug.processResult().document()));
            assertFalse(hasCheckpoint(
                    debug.processResult().document(),
                    "source-a"));
            assertFalse(hasCheckpoint(
                    debug.processResult().document(),
                    "source-b"));
            assertTrue(checkpointWrites(
                    debug.trace()).isEmpty());
        }
    }

    @Test
    void shouldVerifyHandlerTargetIsNeitherEvaluatedNorCheckpointedAsSource() {
        // given
        Node event = event("topic", "event-target");
        try (Fixture fixture = new Fixture(event)) {
            Node document = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            fixture.routing.resetEventEvaluations();

            // when
            ProcessingDebugResult debug = fixture.process(
                    document,
                    event,
                    fixture.prepare(
                            document,
                            event,
                            "source-a",
                            "source-b"));

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(
                    0,
                    fixture.routing
                            .eventEvaluations("target"));
            assertFalse(hasCheckpoint(
                    debug.processResult().document(),
                    "target"));
            assertEquals(
                    Collections.singletonList("target"),
                    fixture.handlers.matchedChannels());
        }
    }

    @Test
    void shouldVerifyPhaseBRehydratesDeclaredCatalogForExternalAndManagedTargets() {
        // given
        Node event = event("topic", "event-phase-b-catalog");
        for (boolean managedTarget : Arrays.asList(
                false, true)) {
            try (Fixture fixture = new Fixture(event)) {
                String targetKey =
                        managedTarget ? "managed" : "target";
                Node target =
                        managedTarget
                                ? managedChannel(
                                targetKey, 2)
                                : routingChannel(
                                targetKey,
                                2,
                                "other",
                                "domain-target",
                                targetKey,
                                targetKey,
                                "target");
                Node document = fixture.initialize(
                        root(
                                catalogRoutingChannel(
                                        "source",
                                        0,
                                        "topic",
                                        "domain-source",
                                        targetKey,
                                        "logical",
                                        "payload"),
                                target,
                                handler(
                                        "handler",
                                        targetKey,
                                        fixture
                                                .selectedBodyBlueId)));
                fixture.routing.resetEventEvaluations();

                // when
                ProcessingDebugResult debug =
                        fixture.process(
                                document,
                                event,
                                fixture.prepareWithActiveIntervals(
                                        document,
                                        event,
                                        "source"));

                // then
                assertEquals(
                        ProcessorStatus.SUCCESS,
                        debug.processResult().status());
                assertEquals(
                        Collections.singletonList(targetKey),
                        fixture.handlers.matchedChannels());
                assertTrue(hasCheckpoint(
                        debug.processResult().document(),
                        "source"));
                assertFalse(hasCheckpoint(
                        debug.processResult().document(),
                        targetKey));
                if (!managedTarget) {
                    assertEquals(
                            0,
                            fixture.routing
                                    .eventEvaluations(
                                            targetKey));
                }
            }
        }
    }

    @Test
    void shouldVerifyPhaseBRehydratesAnInheritedExactTargetKey() {
        // given
        Node event = event(
                "topic",
                "event-inherited-phase-b-target");
        try (Fixture fixture = new Fixture(event)) {
            Node inheritedTarget =
                    routingChannel(
                            "target",
                            2,
                            "other",
                            "domain-target",
                            "target",
                            "target",
                            "target");
            inheritedTarget.name(null);
            Node inheritedHandler =
                    handler(
                            "handler",
                            "target",
                            fixture.selectedBodyBlueId);
            inheritedHandler.name(null);
            Node scopeType =
                    new Node().contracts(
                            new Node()
                                    .properties(
                                            "target",
                                            inheritedTarget)
                                    .properties(
                                            "handler",
                                            inheritedHandler));
            String scopeTypeBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            scopeType);
            fixture.provider.put(
                    scopeTypeBlueId,
                    scopeType);
            Node document = fixture.initialize(
                    root(
                            routingChannel(
                                    "source",
                                    0,
                                    "topic",
                                    "domain-source",
                                    "target",
                                    "logical",
                                    "payload"))
                            .type(reference(
                                    scopeTypeBlueId)));

            // when
            ProcessingDebugResult debug =
                    fixture.process(
                            document,
                            event,
                            fixture.prepare(
                                    document,
                                    event,
                                    "source"));

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(
                    Collections.singletonList("target"),
                    fixture.handlers.matchedChannels());
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source"));
            assertFalse(hasCheckpoint(
                    debug.processResult().document(),
                    "target"));
        }
    }

    @Test
    void shouldRejectDisagreeingHandlerTargetsBeforeMutation() {
        // given
        Node event = event("topic", "event-invalid");

        // when
        InvalidRoutingObservation observation =
                observeInvalidBeforeMutation(
                    event,
                    routingChannel(
                            "source-a", 0, "topic", "domain-a",
                            "target-a", "logical", "payload"),
                    routingChannel(
                            "source-b", 1, "topic", "domain-b",
                            "target-b", "logical", "payload"),
                    routingChannel(
                            "target-a", 2, "other", "domain-ta",
                            "target-a", "target-a", "target-a"),
                    routingChannel(
                            "target-b", 3, "other", "domain-tb",
                            "target-b", "target-b", "target-b"));

        // then
        assertInvalidBeforeMutation(observation);
    }

    @Test
    void shouldRejectDisagreeingLogicalPayloadsBeforeMutation() {
        // given
        Node event = event("topic", "event-invalid-payload");

        // when
        InvalidRoutingObservation observation =
                observeInvalidBeforeMutation(
                    event,
                    routingChannel(
                            "source-a", 0, "topic", "domain-a",
                            "target-a", "logical", "payload-a"),
                    routingChannel(
                            "source-b", 1, "topic", "domain-b",
                            "target-a", "logical", "payload-b"),
                    routingChannel(
                            "target-a", 2, "other", "domain-ta",
                            "target-a", "target-a", "target-a"));

        // then
        assertInvalidBeforeMutation(observation);
    }

    @Test
    void shouldRejectMissingHandlerTargetBeforeMutation() {
        // given
        Node event = event("topic", "event-invalid-missing-target");

        // when
        InvalidRoutingObservation observation =
                observeInvalidBeforeMutation(
                    event,
                    routingChannel(
                            "source-a", 0, "topic", "domain-a",
                            "missing", "logical", "payload"),
                    routingChannel(
                            "source-b", 1, "topic", "domain-b",
                            "missing", "logical", "payload"));

        // then
        assertInvalidBeforeMutation(observation);
    }

    @Test
    void shouldRejectEmptyLogicalDeliveryKeyBeforeMutation() {
        // given
        Node event = event("topic", "event-invalid-empty-key");

        // when
        InvalidKeyObservation observation =
                observeInvalidKeyBeforeMutation(
                    event,
                    routingChannel(
                            "source-a", 0, "topic", "domain-a",
                            "target-a", "", "payload"),
                    routingChannel(
                            "target-a", 1, "other", "domain-ta",
                            "target-a", "target-a", "target-a"));

        // then
        assertInstanceOf(
                IllegalStateException.class,
                observation.failure);
        assertTrue(observation.failure.getMessage().contains(
                "must be non-empty Text"));
        assertEquals(0, observation.handlerExecutions);
    }

    @Test
    void shouldVerifyExactFragmentEventHasSamePlanResultGasAndTraceAsInlineEvent() {
        // given
        Node inlineEvent =
                new Node()
                        .properties(
                                "subscriptionKeys",
                                new Node().items(
                                        Arrays.asList(
                                                new Node().value(
                                                        "topic"),
                                                new Node().value(
                                                        "other"))))
                        .properties(
                                "id",
                                new Node().value(
                                        "event-fragment"));
        ExactNodeGraphFragments eventFragments =
                new ExactNodeGraphFragments(
                        inlineEvent);
        Node fragmentEvent =
                eventFragments.roots().get(0)
                        .directFragment();
        ProcessingDebugResult inlineDebug;
        ProcessingDebugResult fragmentDebug;
        List<String> inlinePlan;
        List<String> fragmentPlan;
        String inlineDocumentBlueId;
        String fragmentDocumentBlueId;

        // when
        try (Fixture inline =
                     new Fixture(inlineEvent);
             Fixture fragmented =
                     new Fixture(inlineEvent)) {
            Node inlineDocument = inline.initialize(
                    routedDocument(
                            inline,
                            "shared-payload",
                            "shared-payload"));
            Node fragmentDocument =
                    fragmented.initialize(
                            routedDocument(
                                    fragmented,
                                    "shared-payload",
                                    "shared-payload"));
            inlineDocumentBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            inlineDocument);
            fragmentDocumentBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            fragmentDocument);
            fragmented.provider.put(
                    fragmentDocumentBlueId,
                    fragmentDocument);
            Node fragmentRoot =
                    reference(fragmentDocumentBlueId);

            PreparedRun inlinePrepared =
                    inline.prepare(
                            inlineDocument,
                            inlineEvent,
                            "source-a",
                            "source-b");
            PreparedRun fragmentPrepared =
                    fragmented.prepare(
                            fragmentDocument,
                            fragmentEvent,
                            "source-a",
                            "source-b");
            inlinePlan = planProjection(
                    inlinePrepared.plan);
            fragmentPlan = planProjection(
                    fragmentPrepared.plan);
            inlineDebug = inline.process(
                    inlineDocument,
                    inlineEvent,
                    inlinePrepared);
            fragmentDebug = fragmented.process(
                    fragmentRoot,
                    fragmentEvent,
                    fragmentPrepared);
        }

        // then
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        inlineEvent),
                DirectBlueIdCalculator.calculateBlueId(
                        fragmentEvent));
        assertEquals(
                inlineDocumentBlueId,
                fragmentDocumentBlueId);
        assertEquals(inlinePlan, fragmentPlan);
        assertEquals(
                inlineDebug.processResult().status(),
                fragmentDebug.processResult().status());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        inlineDebug.processResult()
                                .document()),
                DirectBlueIdCalculator.calculateBlueId(
                        fragmentDebug.processResult()
                                .document()));
        assertEquals(
                inlineDebug.processResult().totalGas(),
                fragmentDebug.processResult().totalGas());
        assertEquals(
                gasProjection(inlineDebug.trace()),
                gasProjection(fragmentDebug.trace()));
        assertEquals(
                traceProjection(inlineDebug.trace()),
                traceProjection(fragmentDebug.trace()));
    }

    @Test
    void shouldVerifyUnavailableEventFragmentSuspendsProcessAttempt() {
        // given
        Node inlineEvent = event(
                "topic", "event-suspension");
        Node keyFragment = new Node().value("topic");
        String keyBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        keyFragment);
        Node fragmentedEvent =
                inlineEvent.clone()
                        .properties(
                                "subscriptionKey",
                                reference(keyBlueId));
        ProcessAttemptResult attempt;
        int handlerExecutions;

        // when
        try (Fixture fixture = new Fixture(inlineEvent)) {
            Node document = fixture.initialize(
                    root(
                            defaultChannel(
                                    "source",
                                    0,
                                    "topic",
                                    "domain",
                                    "payload"),
                            handler(
                                    "handler",
                                    "source",
                                    fixture
                                            .selectedBodyBlueId)));
            PreparedRun prepared = fixture.prepare(
                    document,
                    inlineEvent,
                    "source");
            fixture.provider.unavailable(keyBlueId);

            attempt =
                    fixture.processAttempt(
                            document,
                            fragmentedEvent,
                            prepared);
            handlerExecutions =
                    fixture.handlers.executions();
        }

        // then
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        inlineEvent),
                DirectBlueIdCalculator.calculateBlueId(
                        fragmentedEvent));
        assertEquals(
                ProcessAttemptResult.Kind
                        .NEEDS_RESOURCES,
                attempt.kind(),
                attempt.processResult() != null
                        ? attempt.processResult().status()
                        + "|"
                        + attempt.processResult()
                        .diagnostic().category()
                        + "|"
                        + attempt.processResult()
                        .diagnostic().message()
                        : "no completed result");
        assertEquals(
                Collections.singletonList(
                        keyBlueId),
                attempt.requiredExactBlueIds());
        assertEquals(0, handlerExecutions);
    }

    @Test
    void shouldVerifySelectedHandlerBodyIsAdmittedLazilyAndUnselectedBodyIsNotDemanded() {
        // given
        Node event = event("topic", "event-body");
        try (Fixture fixture = new Fixture(event)) {
            fixture.provider.forbid(
                    fixture.missingBodyBlueId);
            Node document = fixture.initialize(root(
                    routingChannel(
                            "source-a", 0, "topic", "domain-a",
                            "target", "logical", "shared-payload"),
                    routingChannel(
                            "source-b", 1, "topic", "domain-b",
                            "target", "logical", "shared-payload"),
                    routingChannel(
                            "target", 2, "other", "domain-target",
                            "target", "target", "target"),
                    handler(
                            "selected-handler",
                            "target",
                            fixture.selectedBodyBlueId),
                    handler(
                            "unselected-handler",
                            "source-a",
                            fixture.missingBodyBlueId)));
            fixture.provider.reset();

            // when
            PreparedRun prepared = fixture.prepare(
                    document,
                    event,
                    "source-a",
                    "source-b");
            int requestsBeforeExecution =
                    fixture.provider.requests(
                            fixture.missingBodyBlueId);
            ProcessingDebugResult debug =
                    fixture.process(
                            document, event, prepared);
            int requestsAfterExecution =
                    fixture.provider.requests(
                            fixture.missingBodyBlueId);

            // then
            assertEquals(0, requestsBeforeExecution);
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(1, fixture.handlers.executions());
            assertTrue(fixture.handlers.bodyMaterialized());
            assertFalse(
                    fixture.handlers
                            .bodyRequestedBeforeMatch());
            assertEquals(0, requestsAfterExecution);
        }
    }

    @Test
    void shouldRejectExactMaterializationDuringHeaderEvaluation() {
        // given
        Node event = event("topic", "event-context");
        try (Fixture fixture = new Fixture(event)) {
            Node document = root(
                    headerProbe("probe"));
            ResolvedSnapshot snapshot =
                    fixture.processor
                            .snapshotManager()
                            .fromDocumentTransient(
                                    document);
            ContractBundle bundle =
                    fixture.processor
                            .contractLoader()
                            .load(snapshot, "/");
            EffectiveContractSnapshot probe =
                    bundle.effectiveContractSnapshot(
                            "probe");

            // when
            Throwable headerFailure = captureFailure(
                    () -> new ExternalChannelFunctionResolver(
                            fixture.processor.registry(),
                            fixture.processor
                                    .contractConverter(),
                            bundle)
                            .header(probe));

            // then
            assertInstanceOf(
                    IllegalStateException.class,
                    headerFailure);
            assertTrue(headerFailure.getMessage().contains(
                    "available only during event evaluation"));
        }
    }

    @Test
    void shouldRejectExactMaterializationAfterEventSessionCloses() {
        // given
        Node event = event("topic", "event-context-closed");
        try (Fixture fixture = new Fixture(event)) {
            Node routed = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            fixture.evaluate(
                    routed, event, "source-a");
            ExternalChannelFunctionContext retained =
                    fixture.routing.lastContext();
            Node exactReference =
                    new Node().blueId(
                            fixture.selectedBodyBlueId);

            // when
            Throwable closedFailure = captureFailure(
                    () -> retained.materializeExactReference(
                            exactReference));

            // then
            assertNotNull(retained);
            assertInstanceOf(
                    IllegalStateException.class,
                    closedFailure);
            assertTrue(closedFailure.getMessage().contains(
                    "no longer active"));
        }
    }

    private static InvalidRoutingObservation observeInvalidBeforeMutation(
            Node event,
            Node... contracts) {
        try (Fixture fixture = new Fixture(event)) {
            List<Node> all =
                    new ArrayList<>(
                            Arrays.asList(contracts));
            all.add(handler(
                    "handler",
                    "target-a",
                    fixture.selectedBodyBlueId));
            Node document = fixture.initialize(
                    root(all.toArray(
                            new Node[all.size()])));
            PreparedRun prepared;
            try {
                prepared = fixture.prepare(
                        document,
                        event,
                        "source-a",
                        contracts.length > 1
                                && "source-b".equals(
                                contracts[1].getName())
                                ? "source-b"
                                : "source-a");
            } catch (IllegalStateException invalidDependency) {
                return InvalidRoutingObservation.preparationFailure(
                        invalidDependency,
                        fixture.handlers.executions());
            }
            ProcessingDebugResult debug =
                    fixture.process(
                            document, event, prepared);

            return InvalidRoutingObservation.processingFailure(
                    debug.processResult().status(),
                    fixture.handlers.executions(),
                    DirectBlueIdCalculator.calculateBlueId(
                            document),
                    DirectBlueIdCalculator.calculateBlueId(
                            debug.processResult()
                                    .document()),
                    checkpointWrites(
                            debug.trace()).isEmpty());
        }
    }

    private static void assertInvalidBeforeMutation(
            InvalidRoutingObservation observation) {
        if (observation.preparationFailure != null) {
            assertTrue(
                    observation.preparationFailure
                            .getMessage()
                            .contains(
                                    "Missing required same-scope Channel"));
            assertEquals(0, observation.handlerExecutions);
            return;
        }
        assertEquals(
                ProcessorStatus.RUNTIME_FATAL,
                observation.status);
        assertEquals(0, observation.handlerExecutions);
        assertEquals(
                observation.documentBlueIdBefore,
                observation.documentBlueIdAfter);
        assertTrue(observation.checkpointWritesEmpty);
    }

    private static InvalidKeyObservation observeInvalidKeyBeforeMutation(
            Node event,
            Node... contracts) {
        try (Fixture fixture = new Fixture(event)) {
            List<Node> all =
                    new ArrayList<>(
                            Arrays.asList(contracts));
            all.add(handler(
                    "handler",
                    "target-a",
                    fixture.selectedBodyBlueId));
            Node document = fixture.initialize(
                    root(all.toArray(
                            new Node[all.size()])));

            Throwable failure = captureFailure(
                    () -> fixture.prepare(
                            document,
                            event,
                            "source-a"));
            return new InvalidKeyObservation(
                    failure,
                    fixture.handlers.executions());
        }
    }

    private static Node routedDocument(
            Fixture fixture,
            String firstPayload,
            String secondPayload) {
        return root(
                routingChannel(
                        "source-a", 0, "topic", "domain-a",
                        "target", "logical", firstPayload),
                routingChannel(
                        "source-b", 1, "topic", "domain-b",
                        "target", "logical", secondPayload),
                routingChannel(
                        "target", 2, "other", "domain-target",
                        "target", "target", "target"),
                handler(
                        "handler",
                        "target",
                        fixture.selectedBodyBlueId));
    }

    private static Node event(
            String subscriptionKey,
            String id) {
        return new Node()
                .properties(
                        "subscriptionKey",
                        new Node().value(
                                subscriptionKey))
                .properties(
                        "id",
                        new Node().value(id));
    }

    private static Node root(Node... contracts) {
        Node map = new Node();
        for (Node supplied : contracts) {
            String key = supplied.getName();
            Node contract = supplied.clone();
            contract.name(null);
            map.properties(key, contract);
        }
        return new Node().contracts(map);
    }

    private static Node defaultChannel(
            String key,
            int order,
            String subscriptionKey,
            String domain,
            String payload) {
        return new Node()
                .name(key)
                .type(reference(
                        DEFAULT_CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "order",
                        new Node().value(order))
                .properties(
                        "subscriptionKey",
                        new Node().value(
                                subscriptionKey))
                .properties(
                        "checkpointDomain",
                        new Node().value(domain))
                .properties(
                        "payload",
                        new Node().value(payload));
    }

    private static Node routingChannel(
            String key,
            int order,
            String subscriptionKey,
            String domain,
            String handlerChannelKey,
            String logicalDeliveryKey,
            String payload) {
        return new Node()
                .name(key)
                .type(reference(
                        ROUTING_CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "order",
                        new Node().value(order))
                .properties(
                        "subscriptionKey",
                        new Node().value(
                                subscriptionKey))
                .properties(
                        "checkpointDomain",
                        new Node().value(domain))
                .properties(
                        "handlerChannelKey",
                        new Node().value(
                                handlerChannelKey))
                .properties(
                        "logicalDeliveryKey",
                        new Node().value(
                                logicalDeliveryKey))
                .properties(
                        "payload",
                        new Node().value(payload));
    }

    private static Node catalogRoutingChannel(
            String key,
            int order,
            String subscriptionKey,
            String domain,
            String handlerChannelKey,
            String logicalDeliveryKey,
            String payload) {
        return routingChannel(
                key,
                order,
                subscriptionKey,
                domain,
                handlerChannelKey,
                logicalDeliveryKey,
                payload)
                .properties(
                        "declareChannelCatalog",
                        new Node().value(true));
    }

    private static Node managedChannel(
            String key,
            int order) {
        return new Node()
                .name(key)
                .type(reference(
                        RuntimeBlueIds
                                .TRIGGERED_EVENT_CHANNEL))
                .properties(
                        "order",
                        new Node().value(order))
                .properties(
                        "event",
                        new Node().properties(
                                "kind",
                                new Node().value(
                                        "managed-event")));
    }

    private static Node handler(
            String key,
            String channelKey,
            String bodyBlueId) {
        return new Node()
                .name(key)
                .type(reference(HANDLER_TYPE_BLUE_ID))
                .properties(
                        "channel",
                        new Node().value(channelKey))
                .properties(
                        "body",
                        reference(bodyBlueId));
    }

    private static Node contractValue(Node namedContract) {
        Node result = namedContract.clone();
        result.name(null);
        return result;
    }

    private static String diagnostic(DocumentProcessingResult result) {
        ProcessorDiagnostic diagnostic = result.diagnostic();
        return diagnostic == null
                ? null
                : diagnostic.category() + ": " + diagnostic.message()
                        + " " + diagnostic.details();
    }

    private static Node headerProbe(String key) {
        return new Node()
                .name(key)
                .type(reference(
                        HEADER_PROBE_TYPE_BLUE_ID));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static boolean hasCheckpoint(
            Node document,
            String rawChannelKey) {
        Node contracts =
                document != null
                        ? document.getContracts()
                        : null;
        Node checkpoint =
                property(contracts, "checkpoint");
        Node entries =
                property(checkpoint, "entries");
        return property(entries, rawChannelKey) != null;
    }

    private static Node property(
            Node owner,
            String key) {
        return owner != null
                && owner.getProperties() != null
                ? owner.getProperties().get(key)
                : null;
    }

    private static List<String> checkpointWrites(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record
                : trace.records(
                ProcessingTraceRecord.Kind
                        .CHECKPOINT_WRITE)) {
            result.add(record.contractKey());
        }
        return result;
    }

    private static List<String> planProjection(
            ExternalDeliveryPlan plan) {
        List<String> result = new ArrayList<>();
        result.add(plan.managedRootRevision()
                + "|" + plan.indexedRootRevision()
                + "|" + plan.eventOrderKey());
        for (ExternalDeliverySnapshot delivery
                : plan.deliveries()) {
            result.add(
                    delivery.scopePath()
                            + "|" + delivery.channelKey()
                            + "|" + delivery
                            .effectiveTypeBlueId()
                            + "|" + delivery.order()
                            + "|" + delivery
                            .sourceContributionNodeBlueIds()
                            + "|" + delivery.subscriptionKeys()
                            + "|" + delivery
                            .checkpointDomainBlueId()
                            + "|" + delivery
                            .checkpointSubjectBlueId());
        }
        return result;
    }

    private static List<String> gasProjection(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (GasTraceEntry entry : trace.gas()) {
            result.add(
                    entry.sequence()
                            + "|" + entry.namespace()
                            + "|" + entry.counter()
                            + "|" + entry.quantity()
                            + "|" + entry.weight()
                            + "|" + entry.subtotal()
                            + "|" + entry.scopePath()
                            + "|" + entry.contractKey()
                            + "|" + entry.logicalPath()
                            + "|" + entry.reason());
        }
        return result;
    }

    private static List<String> traceProjection(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record
                : trace.records()) {
            Node node = record.node();
            result.add(
                    record.sequence()
                            + "|" + record.kind()
                            + "|" + record.scopePath()
                            + "|" + record.contractKey()
                            + "|" + record.logicalPath()
                            + "|" + record.details()
                            + "|" + (node != null
                            ? DirectBlueIdCalculator
                            .calculateBlueId(node)
                            : null));
        }
        return result;
    }

    public static final class DefaultExternalChannel
            extends ChannelContract {
        private String subscriptionKey;
        private String checkpointDomain;
        private String payload;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public String getCheckpointDomain() {
            return checkpointDomain;
        }

        public void setCheckpointDomain(
                String checkpointDomain) {
            this.checkpointDomain = checkpointDomain;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    public static final class RoutingExternalChannel
            extends ChannelContract {
        private String subscriptionKey;
        private String checkpointDomain;
        private String handlerChannelKey;
        private String logicalDeliveryKey;
        private String payload;
        private Boolean declareChannelCatalog;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public String getCheckpointDomain() {
            return checkpointDomain;
        }

        public void setCheckpointDomain(
                String checkpointDomain) {
            this.checkpointDomain = checkpointDomain;
        }

        public String getHandlerChannelKey() {
            return handlerChannelKey;
        }

        public void setHandlerChannelKey(
                String handlerChannelKey) {
            this.handlerChannelKey =
                    handlerChannelKey;
        }

        public String getLogicalDeliveryKey() {
            return logicalDeliveryKey;
        }

        public void setLogicalDeliveryKey(
                String logicalDeliveryKey) {
            this.logicalDeliveryKey =
                    logicalDeliveryKey;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }

        public Boolean getDeclareChannelCatalog() {
            return declareChannelCatalog;
        }

        public void setDeclareChannelCatalog(
                Boolean declareChannelCatalog) {
            this.declareChannelCatalog =
                    declareChannelCatalog;
        }
    }

    public static final class LogicalHandler
            extends HandlerContract {
        private Node body;

        public Node getBody() {
            return body;
        }

        public void setBody(Node body) {
            this.body = body;
        }
    }

    public static final class HeaderProbeChannel
            extends ChannelContract {
    }

    private static final class DefaultProcessor
            implements ChannelProcessor<
            DefaultExternalChannel> {
        private final ExternalChannelSubscriptionFunctions<
                DefaultExternalChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        DefaultExternalChannel>() {
                    @Override
                    public List<String> channelKeys(
                            DefaultExternalChannel contract) {
                        return Collections.singletonList(
                                contract
                                        .getSubscriptionKey());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            DefaultExternalChannel contract) {
                        return contract
                                .getCheckpointDomain();
                    }

                    @Override
                    public Node payload(
                            DefaultExternalChannel contract,
                            Node exactEvent) {
                        return new Node().value(
                                contract.getPayload());
                    }
                };

        @Override
        public Class<DefaultExternalChannel>
        contractType() {
            return DefaultExternalChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                DefaultExternalChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    private static final class RoutingProcessor
            implements ChannelProcessor<
            RoutingExternalChannel> {
        private final Map<String, AtomicInteger>
                eventEvaluations =
                new LinkedHashMap<>();
        private ExternalChannelFunctionContext lastContext;
        private final ExternalChannelSubscriptionFunctions<
                RoutingExternalChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        RoutingExternalChannel>() {
                    @Override
                    public List<String> channelKeys(
                            RoutingExternalChannel contract,
                            ExternalChannelFunctionContext context) {
                        if (Boolean.TRUE.equals(
                                contract
                                        .getDeclareChannelCatalog())) {
                            context
                                    .dependOnSameScopeChannelCatalog();
                        } else if (!contract.getKey().equals(
                                contract
                                        .getHandlerChannelKey())) {
                            context.dependOnSameScopeChannel(
                                    contract
                                            .getHandlerChannelKey());
                        }
                        return Collections.singletonList(
                                contract
                                        .getSubscriptionKey());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            RoutingExternalChannel contract) {
                        return contract
                                .getCheckpointDomain();
                    }

                    @Override
                    public Node payload(
                            RoutingExternalChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        lastContext = context;
                        eventEvaluations
                                .computeIfAbsent(
                                        contract.getKey(),
                                        ignored ->
                                                new AtomicInteger())
                                .incrementAndGet();
                        return new Node().value(
                                contract.getPayload());
                    }

                    @Override
                    public String handlerChannelKey(
                            RoutingExternalChannel contract,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        if (Boolean.TRUE.equals(
                                contract
                                        .getDeclareChannelCatalog())) {
                            return context.channel(
                                            contract
                                                    .getHandlerChannelKey())
                                    .orElseThrow(
                                            () -> new IllegalStateException(
                                                    "Declared handler "
                                                            + "Channel is absent"))
                                    .channelKey();
                        }
                        if (!contract.getKey().equals(
                                contract
                                        .getHandlerChannelKey())) {
                            return context.channel(
                                            contract
                                                    .getHandlerChannelKey())
                                    .orElseThrow(
                                            () -> new IllegalStateException(
                                                    "Exact handler Channel "
                                                            + "is absent"))
                                    .channelKey();
                        }
                        return contract
                                .getHandlerChannelKey();
                    }

                    @Override
                    public String logicalDeliveryKey(
                            RoutingExternalChannel contract,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        return contract
                                .getLogicalDeliveryKey();
                    }
                };

        @Override
        public Class<RoutingExternalChannel>
        contractType() {
            return RoutingExternalChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                RoutingExternalChannel>
        externalSubscriptionFunctions() {
            return functions;
        }

        private int eventEvaluations(String key) {
            AtomicInteger count =
                    eventEvaluations.get(key);
            return count != null ? count.get() : 0;
        }

        private void resetEventEvaluations() {
            eventEvaluations.clear();
        }

        private ExternalChannelFunctionContext
        lastContext() {
            return lastContext;
        }
    }

    private static final class LogicalHandlerProcessor
            implements HandlerProcessor<LogicalHandler> {
        private final CountingProvider provider;
        private final String selectedBodyBlueId;
        private int executions;
        private boolean fail;
        private boolean bodyMaterialized;
        private boolean bodyRequestedBeforeMatch;
        private boolean routeEvolution;
        private final List<String> matchedChannels =
                new ArrayList<>();
        private final List<String> executedContracts =
                new ArrayList<>();

        private LogicalHandlerProcessor(
                CountingProvider provider,
                String selectedBodyBlueId) {
            this.provider = provider;
            this.selectedBodyBlueId =
                    selectedBodyBlueId;
        }

        @Override
        public Class<LogicalHandler> contractType() {
            return LogicalHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList("body");
        }

        @Override
        public boolean matches(
                LogicalHandler contract,
                HandlerMatchContext context) {
            matchedChannels.add(
                    context.channelKey());
            bodyRequestedBeforeMatch =
                    bodyRequestedBeforeMatch
                            || provider.requests(
                            selectedBodyBlueId) > 0;
            return true;
        }

        @Override
        public void execute(
                LogicalHandler contract,
                ProcessorExecutionContext context) {
            executions++;
            executedContracts.add(context.contractKey());
            bodyMaterialized =
                    contract.getBody() != null
                            && !contract.getBody()
                            .isReferenceOnly();
            if (routeEvolution
                    && "handler-one".equals(context.contractKey())) {
                context.applyPatches(Arrays.asList(
                        JsonPatch.replace(
                                "/contracts/target-two",
                                contractValue(routingChannel(
                                        "target-two", 3, "other",
                                        "domain-replacement-target-two",
                                        "target-two", "target-two",
                                        "replacement-target-two"))),
                        JsonPatch.remove("/contracts/handler-two"),
                        JsonPatch.add(
                                "/contracts/replacement-handler-two",
                                contractValue(handler(
                                        "replacement-handler-two",
                                        "target-two",
                                        selectedBodyBlueId))),
                        JsonPatch.add(
                                "/contracts/source-later",
                                contractValue(routingChannel(
                                        "source-later", 4, "topic",
                                        "domain-later", "target-later",
                                        "logical-later", "payload-later"))),
                        JsonPatch.add(
                                "/contracts/target-later",
                                contractValue(routingChannel(
                                        "target-later", 5, "other",
                                        "domain-target-later", "target-later",
                                        "target-later", "target-later"))),
                        JsonPatch.add(
                                "/contracts/handler-later",
                                contractValue(handler(
                                        "handler-later", "target-later",
                                        selectedBodyBlueId)))));
            }
            if (fail) {
                context.throwFatal(
                        "generic routed handler failure");
            }
        }

        private int executions() {
            return executions;
        }

        private List<String> matchedChannels() {
            return Collections.unmodifiableList(
                    new ArrayList<>(
                            matchedChannels));
        }

        private List<String> executedContracts() {
            return Collections.unmodifiableList(
                    new ArrayList<>(executedContracts));
        }

        private void enableRouteEvolution() {
            routeEvolution = true;
        }

        private void disableRouteEvolutionAndReset() {
            routeEvolution = false;
            reset();
        }

        private void setFailureEnabled(boolean fail) {
            this.fail = fail;
        }

        private boolean bodyMaterialized() {
            return bodyMaterialized;
        }

        private boolean bodyRequestedBeforeMatch() {
            return bodyRequestedBeforeMatch;
        }

        private void reset() {
            executions = 0;
            fail = false;
            bodyMaterialized = false;
            bodyRequestedBeforeMatch = false;
            matchedChannels.clear();
            executedContracts.clear();
        }
    }

    private static final class HeaderProbeProcessor
            implements ChannelProcessor<
            HeaderProbeChannel> {
        private final String exactReferenceBlueId;

        private HeaderProbeProcessor(
                String exactReferenceBlueId) {
            this.exactReferenceBlueId =
                    exactReferenceBlueId;
        }

        @Override
        public Class<HeaderProbeChannel>
        contractType() {
            return HeaderProbeChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                HeaderProbeChannel>
        externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<
                    HeaderProbeChannel>() {
                @Override
                public List<String> channelKeys(
                        HeaderProbeChannel contract,
                        ExternalChannelFunctionContext context) {
                    context.materializeExactReference(
                            reference(
                                    exactReferenceBlueId));
                    return Collections.singletonList(
                            "probe");
                }

                @Override
                public String checkpointDomainDiscriminator(
                        HeaderProbeChannel contract) {
                    return "probe-domain";
                }
            };
        }
    }

    private static final class InvalidRoutingObservation {
        private final IllegalStateException preparationFailure;
        private final ProcessorStatus status;
        private final int handlerExecutions;
        private final String documentBlueIdBefore;
        private final String documentBlueIdAfter;
        private final boolean checkpointWritesEmpty;

        private InvalidRoutingObservation(
                IllegalStateException preparationFailure,
                ProcessorStatus status,
                int handlerExecutions,
                String documentBlueIdBefore,
                String documentBlueIdAfter,
                boolean checkpointWritesEmpty) {
            this.preparationFailure = preparationFailure;
            this.status = status;
            this.handlerExecutions = handlerExecutions;
            this.documentBlueIdBefore = documentBlueIdBefore;
            this.documentBlueIdAfter = documentBlueIdAfter;
            this.checkpointWritesEmpty = checkpointWritesEmpty;
        }

        private static InvalidRoutingObservation preparationFailure(
                IllegalStateException failure,
                int handlerExecutions) {
            return new InvalidRoutingObservation(
                    failure,
                    null,
                    handlerExecutions,
                    null,
                    null,
                    true);
        }

        private static InvalidRoutingObservation processingFailure(
                ProcessorStatus status,
                int handlerExecutions,
                String documentBlueIdBefore,
                String documentBlueIdAfter,
                boolean checkpointWritesEmpty) {
            return new InvalidRoutingObservation(
                    null,
                    status,
                    handlerExecutions,
                    documentBlueIdBefore,
                    documentBlueIdAfter,
                    checkpointWritesEmpty);
        }
    }

    private static final class InvalidKeyObservation {
        private final Throwable failure;
        private final int handlerExecutions;

        private InvalidKeyObservation(
                Throwable failure,
                int handlerExecutions) {
            this.failure = failure;
            this.handlerExecutions = handlerExecutions;
        }
    }

    private static final class PreparedRun {
        private final ExternalDeliveryPlan plan;
        private final VerifiedExecutionEvidence evidence;

        private PreparedRun(
                ExternalDeliveryPlan plan,
                VerifiedExecutionEvidence evidence) {
            this.plan = plan;
            this.evidence = evidence;
        }
    }

    private static final class Fixture
            implements AutoCloseable {
        private final Node selectedBody =
                new Node().value("selected-body");
        private final String selectedBodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        selectedBody);
        private final String missingBodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value(
                                "missing-body"));
        private final CountingProvider provider;
        private final Blue language;
        private final DefaultProcessor defaults =
                new DefaultProcessor();
        private final RoutingProcessor routing =
                new RoutingProcessor();
        private final LogicalHandlerProcessor handlers;
        private final HeaderProbeProcessor headerProbe;
        private final DocumentProcessor processor;

        private Fixture(Node exactEvent) {
            ExactNodeGraphFragments fragments =
                    new ExactNodeGraphFragments(
                            Arrays.asList(
                                    selectedBody,
                                    exactEvent));
            this.provider = new CountingProvider(
                    fragments.fragments());
            this.language =
                    ProcessorTestSupport.blue(provider);
            this.handlers =
                    new LogicalHandlerProcessor(
                            provider,
                            selectedBodyBlueId);
            this.headerProbe =
                    new HeaderProbeProcessor(
                            selectedBodyBlueId);
            language.registerExternalContractType(
                    DEFAULT_CHANNEL_TYPE_BLUE_ID,
                    DEFAULT_CHANNEL_TYPE,
                    defaults);
            language.registerExternalContractType(
                    ROUTING_CHANNEL_TYPE_BLUE_ID,
                    ROUTING_CHANNEL_TYPE,
                    routing);
            language.registerExternalContractType(
                    HANDLER_TYPE_BLUE_ID,
                    HANDLER_TYPE,
                    handlers);
            language.registerExternalContractType(
                    HEADER_PROBE_TYPE_BLUE_ID,
                    HEADER_PROBE_TYPE,
                    headerProbe);
            this.processor =
                    DocumentProcessor.builder()
                            .registerContractProcessor(
                                    DEFAULT_CHANNEL_TYPE_BLUE_ID,
                                    DEFAULT_CHANNEL_TYPE,
                                    defaults)
                            .registerContractProcessor(
                                    ROUTING_CHANNEL_TYPE_BLUE_ID,
                                    ROUTING_CHANNEL_TYPE,
                                    routing)
                            .registerContractProcessor(
                                    HANDLER_TYPE_BLUE_ID,
                                    HANDLER_TYPE,
                                    handlers)
                            .registerContractProcessor(
                                    HEADER_PROBE_TYPE_BLUE_ID,
                                    HEADER_PROBE_TYPE,
                                    headerProbe)
                            .matchingService(
                                    new ContractMatchingService(
                                            language))
                            .snapshotStore(
                                    language
                                            .getDocumentProcessor()
                                            .snapshotManager())
                            .evidenceVerifier(
                                    (root, event, evidence) -> {
                                        // Exact binding is still revalidated
                                        // by VerifiedExecutionEvidence.
                                    })
                            .build();
        }

        private Node initialize(Node document) {
            DocumentProcessingResult result =
                    processor.initializeDocument(
                            document);
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    result.status());
            return result.document();
        }

        private ExternalChannelFunctionEvaluation evaluate(
                Node document,
                Node event,
                String sourceKey) {
            ResolvedSnapshot snapshot =
                    processor.snapshotManager()
                            .fromDocumentTransient(
                                    document);
            ContractBundle bundle =
                    processor.contractLoader()
                            .load(snapshot, "/");
            return ExternalChannelFunctionEvaluation
                    .evaluate(
                            processor.registry(),
                            processor.contractConverter(),
                            ExternalChannelFunctionEvaluation
                                    .verifiedMatcherSessions(
                                            processor
                                                    .snapshotManager()),
                            bundle,
                            bundle.effectiveContractSnapshot(
                                    sourceKey),
                            event);
        }

        private PreparedRun prepare(
                Node document,
                Node event,
                String... sourceKeys) {
            ResolvedSnapshot snapshot =
                    processor.snapshotManager()
                            .fromDocumentTransient(
                                    document);
            ContractBundle bundle =
                    processor.contractLoader()
                            .load(snapshot, "/");
            ExternalDeliveryPlan.Builder plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(7L, 7L)
                            .eventOrderKey(EVENT_ORDER)
                            .exactRuntimeState();
            for (String sourceKey : sourceKeys) {
                EffectiveContractSnapshot contract =
                        bundle.effectiveContractSnapshot(
                                sourceKey);
                ExternalChannelFunctionEvaluation
                        evaluation =
                        ExternalChannelFunctionEvaluation
                                .evaluate(
                                        processor.registry(),
                                        processor
                                                .contractConverter(),
                                        ExternalChannelFunctionEvaluation
                                                .verifiedMatcherSessions(
                                                        processor
                                                                .snapshotManager()),
                                        bundle,
                                        contract,
                                        event);
                plan.activeSubscriptionInterval(
                                activeInterval(
                                        contract,
                                        evaluation))
                        .delivery(delivery(
                                contract, evaluation));
            }
            ExternalDeliveryPlan built = plan.build();
            return new PreparedRun(
                    built,
                    built.bind(
                            document,
                            event,
                            processor
                                    .runtimeRegistryIdentity()));
        }

        private PreparedRun prepareWithActiveIntervals(
                Node document,
                Node event,
                String... sourceKeys) {
            return prepare(
                    document,
                    event,
                    sourceKeys);
        }

        private ProcessingDebugResult process(
                Node document,
                Node event,
                PreparedRun prepared) {
            return processor.processDocumentWithTrace(
                    document,
                    event,
                    prepared.evidence);
        }

        private ProcessAttemptResult processAttempt(
                Node document,
                Node event,
                PreparedRun prepared) {
            return processor.processAttempt(
                    document,
                    event,
                    prepared.evidence);
        }

        @Override
        public void close() {
            processor.close();
            language.close();
        }
    }

    private static ExternalDeliverySnapshot delivery(
            EffectiveContractSnapshot snapshot,
            ExternalChannelFunctionEvaluation evaluation) {
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                snapshot.scopePath(),
                                snapshot.key())
                        .effectiveTypeBlueId(
                                snapshot
                                        .effectiveTypeBlueId())
                        .order(snapshot.order())
                        .checkpointDomainBlueId(
                                evaluation
                                        .checkpointDomainBlueId())
                        .checkpointSubjectBlueId(
                                evaluation
                                        .checkpointSubjectBlueId());
        for (String contribution
                : snapshot
                .sourceContributionNodeBlueIds()) {
            builder.sourceContribution(
                    contribution);
        }
        for (String subscriptionKey
                : evaluation.channelKeys()) {
            builder.subscriptionKey(
                    subscriptionKey);
        }
        return builder.build();
    }

    private static SubscriptionDelta.Entry activeInterval(
            EffectiveContractSnapshot snapshot,
            ExternalChannelFunctionEvaluation evaluation) {
        return new SubscriptionDelta.Entry(
                snapshot.scopePath(),
                snapshot.key(),
                snapshot.effectiveTypeBlueId(),
                snapshot.sourceContributionNodeBlueIds(),
                snapshot.order(),
                evaluation.channelKeys(),
                evaluation.checkpointDomainBlueId(),
                evaluation.dependencies(),
                1L,
                null,
                null);
    }

    private static final class CountingProvider
            implements NodeProvider {
        private final Map<String, Node> exact =
                new LinkedHashMap<>();
        private final Map<String, AtomicInteger>
                requests = new LinkedHashMap<>();
        private final List<String> forbidden =
                new ArrayList<>();
        private final List<String> unavailable =
                new ArrayList<>();

        private CountingProvider(
                Map<String, Node> exact) {
            for (Map.Entry<String, Node> entry
                    : exact.entrySet()) {
                this.exact.put(
                        entry.getKey(),
                        entry.getValue().clone());
            }
        }

        @Override
        public synchronized List<Node> fetchByBlueId(
                String blueId) {
            if (forbidden.contains(blueId)) {
                throw new AssertionError(
                        "Forbidden exact body demand: "
                                + blueId);
            }
            if (unavailable.contains(blueId)) {
                throw new IllegalStateException(
                        "Provider unavailable for exact fragment "
                                + blueId);
            }
            requests.computeIfAbsent(
                            blueId,
                            ignored ->
                                    new AtomicInteger())
                    .incrementAndGet();
            Node node = exact.get(blueId);
            return node != null
                    ? Collections.singletonList(
                    node.clone())
                    : null;
        }

        private synchronized int requests(
                String blueId) {
            AtomicInteger count =
                    requests.get(blueId);
            return count != null ? count.get() : 0;
        }

        private synchronized void reset() {
            requests.clear();
        }

        private synchronized void forbid(
                String blueId) {
            forbidden.add(blueId);
        }

        private synchronized void unavailable(
                String blueId) {
            unavailable.add(blueId);
        }

        private synchronized void put(
                String blueId,
                Node node) {
            exact.put(
                    blueId,
                    node.clone());
        }
    }
}
