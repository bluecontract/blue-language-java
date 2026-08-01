package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProcessorPhasePrecedenceTest {

    private static final Node CHANNEL_TYPE =
            new Node().name("Phase Precedence External Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final String UNKNOWN_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    new Node().name("Unavailable Application Contract"));
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(
                    Arrays.asList(31, "phase-precedence"));

    @Test
    void shouldRejectedCandidatesSkipUnrelatedPreflight() {
        // given
        List<Node> unrelatedContracts = Arrays.asList(
                new Node().type(
                        new Node().blueId(
                                UNKNOWN_TYPE_BLUE_ID)),
                new Node().properties(
                        "body",
                        new Node().value("missing type")));

        // when
        List<PhaseObservation> observations = new ArrayList<>();
        for (Node unrelated : unrelatedContracts) {
            observations.add(classifyBeforePreflight(
                    false, true, unrelated));
        }

        // then
        for (PhaseObservation observation : observations) {
            assertClassificationPrecedesPreflight(
                    observation, ProcessorStatus.NO_MATCH);
        }
    }

    @Test
    void shouldStaleCandidatesSkipUnrelatedPreflight() {
        // given
        List<Node> unrelatedContracts = Arrays.asList(
                new Node().type(
                        new Node().blueId(
                                UNKNOWN_TYPE_BLUE_ID)),
                new Node().properties(
                        "body",
                        new Node().value("missing type")));

        // when
        List<PhaseObservation> observations = new ArrayList<>();
        for (Node unrelated : unrelatedContracts) {
            observations.add(classifyBeforePreflight(
                    true, false, unrelated));
        }

        // then
        for (PhaseObservation observation : observations) {
            assertClassificationPrecedesPreflight(
                    observation, ProcessorStatus.STALE);
        }
    }

    @Test
    void shouldVerifyAcceptedNewCandidatePreflightsUnsupportedOrMalformedSiblingBeforeInitialization() {
        // given
        List<Node> unrelatedContracts = Arrays.asList(
                new Node().type(
                        new Node().blueId(
                                UNKNOWN_TYPE_BLUE_ID)),
                new Node().properties(
                        "body",
                        new Node().value("missing type")));

        // when
        List<PhaseObservation> observations = new ArrayList<>();
        for (Node unrelated : unrelatedContracts) {
            observations.add(classifyBeforePreflight(
                    true, true, unrelated));
        }

        // then
        for (PhaseObservation observation : observations) {
            assertEquals(
                    ProcessorStatus.CAPABILITY_FAILURE,
                    observation.debug.processResult().status());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            observation.root),
                    DirectBlueIdCalculator.calculateBlueId(
                            observation.debug
                                    .processResult().document()));
            assertTrue(
                    observation.debug
                            .processResult().events().isEmpty());
            assertFalse(
                    hasInitializedMarker(
                            observation.debug
                                    .processResult().document()));
            assertEquals(
                    1L,
                    observation.debug.trace().counterQuantity(
                            "processor",
                            "channelAccepted"));
        }
    }

    @Test
    void shouldVerifyPhaseBChargesOneScopeAndEachExactHeaderBeforeItsRejectedCandidate() {
        // given
        Node first = channel(false, true);
        Node second = channel(false, true);
        second.properties(
                "order",
                new Node().value(1));
        Node root = new Node().contracts(
                new Node()
                        .properties("first", first)
                        .properties("second", second));
        Node event = event();
        ExternalDeliveryPlan plan =
                ExternalDeliveryPlan.builder()
                        .revisions(4L, 4L)
                        .eventOrderKey(EVENT_ORDER)
                        .delivery(snapshot(
                                first, event,
                                "first", 0))
                        .delivery(snapshot(
                                second, event,
                                "second", 1))
                        .exactRuntimeState()
                        .build();

        // when
        ProcessingDebugResult debug =
                phaseProcessor(plan)
                        .processDocumentWithTrace(
                                root, event);
        List<String> phaseBCounters =
                new ArrayList<>();
        for (GasTraceEntry entry
                : debug.trace().gas()) {
            if ("scopeOpened".equals(entry.counter())
                    || "contractHeaderRecognized".equals(
                    entry.counter())
                    || "channelCandidateTested".equals(
                    entry.counter())) {
                phaseBCounters.add(entry.counter());
            }
        }

        // then
        assertEquals(
                ProcessorStatus.NO_MATCH,
                debug.processResult().status());
        assertEquals(
                1L,
                debug.trace().counterQuantity(
                        "processor", "scopeOpened"));
        assertEquals(
                2L,
                debug.trace().counterQuantity(
                        "processor",
                        "contractHeaderRecognized"));
        assertEquals(
                2L,
                debug.trace().counterQuantity(
                        "processor",
                        "channelCandidateTested"));
        assertEquals(
                Arrays.asList(
                        "scopeOpened",
                        "contractHeaderRecognized",
                        "channelCandidateTested",
                        "contractHeaderRecognized",
                        "channelCandidateTested"),
                phaseBCounters);
    }

    @Test
    void shouldDirectTerminationBypassUnavailableFeederForNodeInput() {
        // given
        try (TerminatedPhaseFixture fixture =
                     terminatedPhaseFixture()) {
            // when
            ProcessingDebugResult result =
                    fixture.processor.processDocumentWithTrace(
                            fixture.root.clone(),
                            fixture.event.clone());

            // then
            assertTerminatedAtPhaseA(
                    result, fixture.root, null);
            assertEquals(0, fixture.feederCalls.get());
        }
    }

    @Test
    void shouldDirectTerminationBypassUnavailableFeederForSnapshotInput() {
        // given
        try (TerminatedPhaseFixture fixture =
                     terminatedPhaseFixture()) {
            // when
            ProcessingDebugResult result =
                    fixture.processor.processDocumentWithTrace(
                            fixture.snapshot,
                            fixture.event.clone());

            // then
            assertTerminatedAtPhaseA(
                    result, fixture.root, fixture.snapshot);
            assertEquals(0, fixture.feederCalls.get());
        }
    }

    @Test
    void shouldDirectTerminationPrecedeInvalidEvidenceForNodeInput() {
        // given
        try (TerminatedPhaseFixture fixture =
                     terminatedPhaseFixture()) {
            // when
            ProcessingDebugResult result =
                    fixture.processor.processDocumentWithTrace(
                            fixture.root.clone(),
                            fixture.event.clone(),
                            fixture.invalidEvidence);

            // then
            assertTerminatedAtPhaseA(
                    result, fixture.root, null);
            assertEquals(0, fixture.feederCalls.get());
        }
    }

    @Test
    void shouldDirectTerminationPrecedeInvalidEvidenceForSnapshotInput() {
        // given
        try (TerminatedPhaseFixture fixture =
                     terminatedPhaseFixture()) {
            // when
            ProcessingDebugResult result =
                    fixture.processor.processDocumentWithTrace(
                            fixture.snapshot,
                            fixture.event.clone(),
                            fixture.invalidEvidence);

            // then
            assertTerminatedAtPhaseA(
                    result, fixture.root, fixture.snapshot);
            assertEquals(0, fixture.feederCalls.get());
        }
    }

    @Test
    void shouldDirectTerminationCompleteProcessAttemptWithoutFeederWork() {
        // given
        try (TerminatedPhaseFixture fixture =
                     terminatedPhaseFixture()) {
            // when
            ProcessAttemptResult attempt =
                    fixture.processor.processAttempt(
                            fixture.root.clone(),
                            fixture.event.clone());

            // then
            assertEquals(
                    ProcessAttemptResult.Kind.COMPLETE,
                    attempt.kind());
            assertEquals(
                    ProcessorStatus.TERMINATED,
                    attempt.processResult().status());
            assertEquals(
                    Long.valueOf(
                            GasSchedule.contracts10().weight(
                                    "processor",
                                    "processInvocation")),
                    attempt.portableGas());
            assertEquals(0, fixture.feederCalls.get());
        }
    }

    private static PhaseObservation classifyBeforePreflight(
            boolean accepts,
            boolean newer,
            Node unrelated) {
        Node channel = channel(accepts, newer);
        Node root = new Node().contracts(
                new Node()
                        .properties("incoming", channel)
                        .properties(
                                "unrelated",
                                unrelated.clone()));
        Node event = event();
        ExternalDeliveryPlan plan =
                plan(snapshot(channel, event));
        DocumentProcessor processor =
                phaseProcessor(plan);

        ProcessingDebugResult debug =
                processor.processDocumentWithTrace(
                        root.clone(), event.clone());
        return new PhaseObservation(root, debug);
    }

    private static void assertClassificationPrecedesPreflight(
            PhaseObservation observation,
            ProcessorStatus expectedStatus) {
        ProcessingDebugResult debug = observation.debug;
        assertEquals(
                expectedStatus,
                debug.processResult().status(),
                diagnosticMessage(debug.processResult()));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        observation.root),
                DirectBlueIdCalculator.calculateBlueId(
                        debug.processResult().document()));
        assertTrue(
                debug.processResult().events().isEmpty());
        assertFalse(
                hasInitializedMarker(
                        debug.processResult().document()));
        /*
         * Phase B still opens the selected scope and recognizes the exact
         * target header before testing acceptance/newness. It must not widen
         * that work into the Phase-C participating-closure preflight.
         */
        assertEquals(
                1L,
                debug.trace().counterQuantity(
                        "processor",
                        "scopeOpened"));
        assertEquals(
                1L,
                debug.trace().counterQuantity(
                        "processor",
                        "contractHeaderRecognized"));
        assertTrue(
                debug.trace().contractSnapshots().isEmpty());
    }

    private static TerminatedPhaseFixture terminatedPhaseFixture() {
        Node root = terminatedRoot();
        Node event = event();
        String missing = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Unavailable feeder state"));
        AtomicInteger feederCalls = new AtomicInteger();
        ExternalDeliveryPlanDeriver unavailable =
                ExternalDeliveryPlanDeriver.needsResources(
                        Collections.singletonList(missing));
        Blue language = new Blue();
        ResolvedSnapshot snapshot =
                language.resolveToSnapshot(root.clone());
        DocumentProcessor processor =
                DocumentProcessor.builder()
                        .withSnapshotManager(
                                language.getDocumentProcessor()
                                        .snapshotManager())
                        .withExternalDeliveryPlanDeriver(
                                (document, processingEvent) -> {
                                    feederCalls.incrementAndGet();
                                    return unavailable.derive(
                                            document,
                                            processingEvent);
                                })
                        .build();
        VerifiedExecutionEvidence invalidEvidence =
                VerifiedExecutionEvidence.builder(
                                DirectBlueIdCalculator.calculateBlueId(
                                        new Node().properties(
                                                "different",
                                                new Node().value(true))),
                                DirectBlueIdCalculator.calculateBlueId(
                                        event))
                        .revisions(0L, 0L)
                        .runtimeRegistryIdentity(
                                RuntimeBlueIds
                                        .REGISTRY_PACKAGE_IDENTITY)
                        .eventOrderKey(EVENT_ORDER)
                        .build();
        return new TerminatedPhaseFixture(
                language,
                root,
                event,
                snapshot,
                processor,
                invalidEvidence,
                feederCalls);
    }

    private static DocumentProcessor phaseProcessor(
            ExternalDeliveryPlan plan) {
        return DocumentProcessor.builder()
                .registerContractProcessor(
                        CHANNEL_TYPE_BLUE_ID,
                        CHANNEL_TYPE,
                        new PhaseChannelProcessor())
                .withExternalDeliveryEvidenceVerifier(
                        (document, processingEvent, evidence) -> {
                            // Isolate semantic phase ordering from
                            // environmental feeder storage.
                        })
                .withExternalDeliveryPlanDeriver(
                        (document, processingEvent) -> plan)
                .build();
    }

    private static void assertTerminatedAtPhaseA(
            ProcessingDebugResult debug,
            Node inputRoot,
            ResolvedSnapshot expectedSnapshot) {
        DocumentProcessingResult result =
                debug.processResult();
        assertEquals(
                ProcessorStatus.TERMINATED,
                result.status(),
                diagnosticMessage(result));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(inputRoot),
                DirectBlueIdCalculator.calculateBlueId(
                        result.document()));
        assertTrue(result.events().isEmpty());
        if (expectedSnapshot != null) {
            assertSame(
                    expectedSnapshot,
                    debug.resultingSnapshot());
        }
        assertEquals(
                GasSchedule.contracts10().weight(
                        "processor",
                        "processInvocation"),
                result.totalGas());
        assertEquals(1, debug.trace().gas().size());
        GasTraceEntry only = debug.trace().gas().get(0);
        assertEquals("processor", only.namespace());
        assertEquals("processInvocation", only.counter());
        assertEquals(1L, only.quantity());
        assertEquals(
                0L,
                debug.trace().counterQuantity(
                        "processor",
                        "deliverySnapshotEntry"));
        assertTrue(
                debug.trace().semanticDemands().isEmpty());
        assertTrue(
                debug.trace().records().isEmpty());
        assertTrue(
                debug.trace().contractSnapshots().isEmpty());
    }

    private static ExternalDeliveryPlan plan(
            ExternalDeliverySnapshot delivery) {
        return ExternalDeliveryPlan.builder()
                .revisions(4L, 4L)
                .eventOrderKey(EVENT_ORDER)
                .delivery(delivery)
                .exactRuntimeState()
                .build();
    }

    private static ExternalDeliverySnapshot snapshot(
            Node channel,
            Node event) {
        return snapshot(
                channel, event, "incoming", 0);
    }

    private static ExternalDeliverySnapshot snapshot(
            Node channel,
            Node event,
            String channelKey,
            int order) {
        String contribution =
                DirectBlueIdCalculator.calculateBlueId(channel);
        return ExternalDeliverySnapshot.builder(
                        "/", channelKey)
                .order(order)
                .sourceContribution(contribution)
                .effectiveTypeBlueId(
                        CHANNEL_TYPE_BLUE_ID)
                .subscriptionKey("topic")
                .checkpointDomainBlueId(
                        CheckpointDomain.derive(
                                CHANNEL_TYPE_BLUE_ID,
                                Collections.singletonList(
                                        contribution),
                                "phase-domain"))
                .checkpointSubjectBlueId(
                        DirectBlueIdCalculator.calculateBlueId(
                                event))
                .build();
    }

    private static Node channel(
            boolean accepts,
            boolean newer) {
        return new Node()
                .type(new Node().blueId(
                        CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "order",
                        new Node().value(0))
                .properties(
                        "subscriptionKey",
                        new Node().value("topic"))
                .properties(
                        "accepts",
                        new Node().value(accepts))
                .properties(
                        "newer",
                        new Node().value(newer));
    }

    private static Node event() {
        return new Node().properties(
                "subscriptionKey",
                new Node().value("topic"));
    }

    private static Node terminatedRoot() {
        return new Node().contracts(
                new Node()
                        .properties(
                                "terminated",
                                new Node()
                                        .type(new Node().blueId(
                                                RuntimeBlueIds
                                                    .PROCESSING_TERMINATED_MARKER))
                                        .properties(
                                                "cause",
                                                new Node().value(
                                                        "business"))
                                        .properties(
                                                "reason",
                                                new Node().value(
                                                        "complete"))));
    }

    private static boolean hasInitializedMarker(
            Node document) {
        return document.getContracts() != null
                && document.getContracts().getProperties() != null
                && document.getContracts().getProperties()
                    .containsKey("initialized");
    }

    private static final class PhaseObservation {
        private final Node root;
        private final ProcessingDebugResult debug;

        private PhaseObservation(
                Node root,
                ProcessingDebugResult debug) {
            this.root = root;
            this.debug = debug;
        }
    }

    private static final class TerminatedPhaseFixture
            implements AutoCloseable {
        private final Blue language;
        private final Node root;
        private final Node event;
        private final ResolvedSnapshot snapshot;
        private final DocumentProcessor processor;
        private final VerifiedExecutionEvidence invalidEvidence;
        private final AtomicInteger feederCalls;

        private TerminatedPhaseFixture(
                Blue language,
                Node root,
                Node event,
                ResolvedSnapshot snapshot,
                DocumentProcessor processor,
                VerifiedExecutionEvidence invalidEvidence,
                AtomicInteger feederCalls) {
            this.language = language;
            this.root = root;
            this.event = event;
            this.snapshot = snapshot;
            this.processor = processor;
            this.invalidEvidence = invalidEvidence;
            this.feederCalls = feederCalls;
        }

        @Override
        public void close() {
            processor.close();
            language.close();
        }
    }

    public static final class PhaseChannel
            extends ChannelContract {
        private String subscriptionKey;
        private Boolean accepts;
        private Boolean newer;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public Boolean getAccepts() {
            return accepts;
        }

        public void setAccepts(Boolean accepts) {
            this.accepts = accepts;
        }

        public Boolean getNewer() {
            return newer;
        }

        public void setNewer(Boolean newer) {
            this.newer = newer;
        }
    }

    private static final class PhaseChannelProcessor
            implements ChannelProcessor<PhaseChannel> {

        private static final
        ExternalChannelSubscriptionFunctions<PhaseChannel>
                SUBSCRIPTION_FUNCTIONS =
                new ExternalChannelSubscriptionFunctions<PhaseChannel>() {
                    @Override
                    public List<String> channelKeys(
                            PhaseChannel immutableContractSnapshot) {
                        return Collections.singletonList(
                                immutableContractSnapshot
                                        .getSubscriptionKey());
                    }

                    @Override
                    public boolean accepts(
                            PhaseChannel immutableContractSnapshot,
                            Node exactEvent) {
                        return Boolean.TRUE.equals(
                                immutableContractSnapshot.getAccepts())
                                && preselects(
                                immutableContractSnapshot,
                                exactEvent);
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            PhaseChannel immutableContractSnapshot) {
                        return "phase-domain";
                    }
                };

        @Override
        public Class<PhaseChannel> contractType() {
            return PhaseChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<PhaseChannel>
        externalSubscriptionFunctions() {
            return SUBSCRIPTION_FUNCTIONS;
        }

        @Override
        public boolean matches(
                PhaseChannel contract,
                ChannelEvaluationContext context) {
            return Boolean.TRUE.equals(
                    contract.getAccepts());
        }

        @Override
        public boolean isNewerEvent(
                PhaseChannel contract,
                ChannelCheckpointContext context) {
            return Boolean.TRUE.equals(
                    contract.getNewer());
        }
    }
}
