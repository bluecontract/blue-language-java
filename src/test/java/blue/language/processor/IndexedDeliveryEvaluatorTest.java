package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.provider.NodeProvider;
import blue.language.snapshot.FrozenNode;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IndexedDeliveryEvaluatorTest {

    private static final Node CHANNEL_TYPE =
            new Node().name("Indexed Delivery Test Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final String SUBSCRIPTION_KEY = "topic";
    private static final String CHECKPOINT_DISCRIMINATOR =
            "indexed-delivery-test";
    private static final long ROOT_REVISION = 7L;
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.asList(4, "source", 2));

    @Test
    void shouldPrepareDeliveriesAndDiagnosticsFromCompleteSurface() {
        // given
        Node candidateOnly = channel(0, false, false, false);
        Node accepted = channel(1, true, true, false);
        Node root = root(
                "candidateOnly", candidateOnly,
                "accepted", accepted);
        Node event = event();
        List<SubscriptionDelta.Entry> intervals = Arrays.asList(
                interval("accepted", accepted, null),
                interval("candidateOnly", candidateOnly, null));
        List<ExternalSubscriptionOccurrenceKey> candidates = Arrays.asList(
                ExternalSubscriptionOccurrenceKey.of(
                        "/", "candidateOnly"),
                ExternalSubscriptionOccurrenceKey.of(
                        "/", "accepted"));
        DocumentProcessor processor = processor();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        IndexedDeliveryPreparation preparation =
                evaluator.prepare(
                        root,
                        event,
                        ROOT_REVISION,
                        EVENT_ORDER,
                        intervals,
                        candidates);

        // then
        ExternalDeliveryPlan plan = preparation.deliveryPlan();
        assertEquals(ROOT_REVISION, plan.managedRootRevision());
        assertEquals(ROOT_REVISION, plan.indexedRootRevision());
        assertEquals(
                new SubscriptionDelta(
                        intervals,
                        Collections.<SubscriptionDelta.Entry>emptyList())
                        .added(),
                plan.activeSubscriptionIntervals());
        assertEquals(1, plan.deliveries().size());
        assertEquals("accepted", plan.deliveries().get(0).channelKey());

        List<IndexedDeliveryDiagnostic> diagnostics =
                preparation.diagnostics();
        assertEquals(2, diagnostics.size());
        IndexedDeliveryDiagnostic falsePreselection = diagnostics.get(0);
        assertEquals(
                ExternalSubscriptionOccurrenceKey.of(
                        "/", "candidateOnly"),
                falsePreselection.occurrenceKey());
        assertTrue(falsePreselection.eligibleAtEvent());
        assertTrue(falsePreselection.physicalCandidate());
        assertFalse(falsePreselection.preselects());
        assertFalse(falsePreselection.accepts());
        assertNull(falsePreselection.checkpointSubjectBlueId());

        IndexedDeliveryDiagnostic acceptedDiagnostic = diagnostics.get(1);
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        assertTrue(acceptedDiagnostic.preselects());
        assertTrue(acceptedDiagnostic.accepts());
        assertEquals(eventBlueId,
                acceptedDiagnostic.checkpointSubjectBlueId());
        assertEquals(eventBlueId, acceptedDiagnostic.payloadBlueId());
        assertEquals("accepted",
                acceptedDiagnostic.handlerChannelKey());
        assertEquals("accepted",
                acceptedDiagnostic.logicalDeliveryKey());
    }

    @Test
    void shouldUseEventIdentityWhenPreselectedOccurrenceIsNotAccepted() {
        // given
        Node preselected = channel(0, true, false, false);
        Node root = root("preselected", preselected);
        Node event = event();
        SubscriptionDelta.Entry interval =
                interval("preselected", preselected, null);
        DocumentProcessor processor = processor();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        IndexedDeliveryPreparation preparation =
                evaluator.prepare(
                        root,
                        event,
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.singletonList(interval),
                        Collections.singletonList(
                                ExternalSubscriptionOccurrenceKey.of(
                                        "/", "preselected")));

        // then
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        assertEquals(1, preparation.deliveryPlan().deliveries().size());
        assertEquals(
                eventBlueId,
                preparation.deliveryPlan().deliveries().get(0)
                        .checkpointSubjectBlueId());
        assertTrue(preparation.diagnostics().get(0).preselects());
        assertFalse(preparation.diagnostics().get(0).accepts());
        assertEquals(
                eventBlueId,
                preparation.diagnostics().get(0)
                        .checkpointSubjectBlueId());
    }

    @Test
    void shouldRejectCandidateListWithMissingOccurrence() {
        // given
        Node channel = channel(0, false, false, false);
        Node root = root("candidate", channel);
        DocumentProcessor processor = processor();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        Throwable failure = captureFailure(
                () -> evaluator.prepare(
                        root,
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.singletonList(
                                interval("candidate", channel, null)),
                        Collections.<ExternalSubscriptionOccurrenceKey>
                                emptyList()));

        // then
        assertEquals(
                InvalidExecutionEvidenceException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "does not match the complete evaluated"));
    }

    @Test
    void shouldRejectDuplicateCandidateOccurrence() {
        // given
        Node channel = channel(0, false, false, false);
        Node root = root("candidate", channel);
        ExternalSubscriptionOccurrenceKey candidate =
                ExternalSubscriptionOccurrenceKey.of("/", "candidate");
        DocumentProcessor processor = processor();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        Throwable failure = captureFailure(
                () -> evaluator.prepare(
                        root,
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.singletonList(
                                interval("candidate", channel, null)),
                        Arrays.asList(candidate, candidate)));

        // then
        assertEquals(
                InvalidExecutionEvidenceException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "Duplicate indexed physical candidate"));
    }

    @Test
    void shouldDeriveCurrentRootCandidatesInternally() {
        // given
        Node accepted = channel(0, true, true, false);
        Node root = root("accepted", accepted);
        Node event = event();
        DocumentProcessor processor = processor();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();
        ExternalDeliveryPlanDeriver deriver =
                evaluator.currentRootDeriver(
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.singletonList(
                                interval("accepted", accepted, null)));

        // when
        ExternalDeliveryPlan plan = deriver.derive(root, event);

        // then
        assertEquals(1, plan.deliveries().size());
        assertEquals("accepted", plan.deliveries().get(0).channelKey());
        assertTrue(plan.exactRuntimeState());
    }

    @Test
    void shouldKeepActivationIneligibleOccurrenceOutOfCandidatesAndDeliveries() {
        // given
        Node accepted = channel(0, true, true, false);
        Node root = root("future", accepted);
        ExternalOrderKey activationBoundary =
                ExternalOrderKey.of(Arrays.asList(5, "source", 1));
        DocumentProcessor processor = processor();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        IndexedDeliveryPreparation preparation =
                evaluator.prepare(
                        root,
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.singletonList(
                                interval(
                                        "future",
                                        accepted,
                                        activationBoundary)),
                        Collections.<ExternalSubscriptionOccurrenceKey>
                                emptyList());

        // then
        assertTrue(preparation.deliveryPlan().deliveries().isEmpty());
        assertEquals(1, preparation.diagnostics().size());
        assertFalse(preparation.diagnostics().get(0).eligibleAtEvent());
        assertFalse(preparation.diagnostics().get(0).physicalCandidate());
        assertTrue(preparation.diagnostics().get(0).preselects());
    }

    @Test
    void shouldPreserveGasExhaustionFromSharedAdmissionBudget() {
        // given
        Node charged = channel(0, true, true, true);
        Node root = root("charged", charged);
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(
                        CHANNEL_TYPE_BLUE_ID,
                        CHANNEL_TYPE,
                        new IndexedTestChannelProcessor())
                .gasLimit(0L)
                .build();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        Throwable failure = captureFailure(
                () -> evaluator.prepare(
                        root,
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.singletonList(
                                interval("charged", charged, null)),
                        Collections.singletonList(
                                ExternalSubscriptionOccurrenceKey.of(
                                        "/", "charged"))));

        // then
        assertEquals(GasLimitExceededException.class, failure.getClass());
        assertEquals(
                "indexed-test",
                ((GasLimitExceededException) failure).namespace());
    }

    @Test
    void shouldRejectEvaluationAfterProcessorCloses() {
        // given
        DocumentProcessor processor = processor();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();
        processor.close();

        // when
        Throwable failure = captureFailure(
                () -> evaluator.prepare(
                        new Node(),
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.<SubscriptionDelta.Entry>emptyList(),
                        Collections.<ExternalSubscriptionOccurrenceKey>
                                emptyList()));

        // then
        assertEquals(IllegalStateException.class, failure.getClass());
        assertTrue(failure.getMessage().contains("closed"));
    }

    @Test
    void shouldRejectStaleSnapshotGenerationBeforeEvaluation() {
        // given
        Node accepted = channel(0, true, true, false);
        DocumentProcessor processor = DocumentProcessor.builder()
                .snapshotStore(new StaleSnapshotManager())
                .registerContractProcessor(
                        CHANNEL_TYPE_BLUE_ID,
                        CHANNEL_TYPE,
                        new IndexedTestChannelProcessor())
                .build();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        Throwable failure = captureFailure(
                () -> evaluator.prepare(
                        root("accepted", accepted),
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.singletonList(
                                interval("accepted", accepted, null)),
                        Collections.singletonList(
                                ExternalSubscriptionOccurrenceKey.of(
                                        "/", "accepted"))));
        processor.close();

        // then
        assertEquals(IllegalStateException.class, failure.getClass());
        assertEquals(
                "Indexed delivery snapshot generation is no longer current",
                failure.getMessage());
    }

    @Test
    void shouldRejectNonReleaseGasPackageBeforeEvaluation() {
        // given
        GasSchedule altered = GasScheduleTestFixtures.withPortableLimit(
                GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES,
                GasSchedule.contracts10().portableLimit(
                        GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES)
                        - 1L);
        DocumentProcessor processor = DocumentProcessor.builder()
                .gasSchedule(altered)
                .registerContractProcessor(
                        CHANNEL_TYPE_BLUE_ID,
                        CHANNEL_TYPE,
                        new IndexedTestChannelProcessor())
                .build();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        Throwable failure = captureFailure(
                () -> evaluator.prepare(
                        new Node(),
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.<SubscriptionDelta.Entry>emptyList(),
                        Collections.<ExternalSubscriptionOccurrenceKey>
                                emptyList()));
        processor.close();

        // then
        assertEquals(IllegalStateException.class, failure.getClass());
        assertEquals(
                "Indexed delivery evaluation requires the released Contracts 1.0 gas package",
                failure.getMessage());
    }

    @Test
    void shouldRejectOmittedExternalChannelFromClaimedCompleteSurface() {
        // given
        Node channel = channel(0, false, false, false);
        Node root = root("omitted", channel);
        DocumentProcessor processor = processor();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        Throwable failure = captureFailure(
                () -> evaluator.prepare(
                        root,
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.<SubscriptionDelta.Entry>emptyList(),
                        Collections.<ExternalSubscriptionOccurrenceKey>
                                emptyList()));

        // then
        assertEquals(
                InvalidExecutionEvidenceException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains("omitted=1"));
    }

    @Test
    void shouldDetachCandidateListBeforeRegisteredFunctionsRun() {
        // given
        Node accepted = channel(0, true, true, false);
        Node root = root("accepted", accepted);
        List<ExternalSubscriptionOccurrenceKey> candidates =
                new ArrayList<>(Collections.singletonList(
                        ExternalSubscriptionOccurrenceKey.of(
                                "/", "accepted")));
        DocumentProcessor processor = processor(candidates::clear);
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        IndexedDeliveryPreparation preparation = evaluator.prepare(
                root,
                event(),
                ROOT_REVISION,
                EVENT_ORDER,
                Collections.singletonList(
                        interval("accepted", accepted, null)),
                candidates);

        // then
        assertTrue(candidates.isEmpty());
        assertEquals(1, preparation.deliveryPlan().deliveries().size());
        assertTrue(preparation.diagnostics().get(0).physicalCandidate());
    }

    @Test
    void shouldDetachRootEventAndIntervalsBeforeRegisteredFunctionsRun() {
        // given
        Node accepted = channel(0, true, true, false);
        Node root = root("accepted", accepted);
        Node event = event();
        List<SubscriptionDelta.Entry> intervals =
                new ArrayList<>(Collections.singletonList(
                        interval("accepted", accepted, null)));
        Runnable mutateInputs = () -> {
            intervals.clear();
            root.getContracts().getProperties().clear();
            event.properties(
                    ProcessorContractConstants.KEY_SUBSCRIPTION_KEY,
                    new Node().value("changed-after-entry"));
        };
        DocumentProcessor processor = processor(mutateInputs);
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        IndexedDeliveryPreparation preparation = evaluator.prepare(
                root,
                event,
                ROOT_REVISION,
                EVENT_ORDER,
                intervals,
                Collections.singletonList(
                        ExternalSubscriptionOccurrenceKey.of(
                                "/", "accepted")));

        // then
        assertTrue(intervals.isEmpty());
        assertTrue(root.getContracts().getProperties().isEmpty());
        assertEquals(
                "changed-after-entry",
                event.getAsText("/subscriptionKey"));
        assertEquals(1, preparation.deliveryPlan().deliveries().size());
        assertEquals(
                Collections.singletonList(SUBSCRIPTION_KEY),
                preparation.diagnostics().get(0).eventKeys());
    }

    @Test
    void shouldCanonicalizeIntervalSurfaceIndependentlyOfCallerOrder() {
        // given
        Node first = channel(0, true, true, false);
        Node second = channel(1, true, true, false);
        Node root = root("first", first, "second", second);
        SubscriptionDelta.Entry firstInterval =
                interval("first", first, null);
        SubscriptionDelta.Entry secondInterval =
                interval("second", second, null);
        List<ExternalSubscriptionOccurrenceKey> candidates = Arrays.asList(
                ExternalSubscriptionOccurrenceKey.of("/", "first"),
                ExternalSubscriptionOccurrenceKey.of("/", "second"));
        DocumentProcessor processor = processor();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        IndexedDeliveryPreparation forward = evaluator.prepare(
                root,
                event(),
                ROOT_REVISION,
                EVENT_ORDER,
                Arrays.asList(firstInterval, secondInterval),
                candidates);
        IndexedDeliveryPreparation reversed = evaluator.prepare(
                root,
                event(),
                ROOT_REVISION,
                EVENT_ORDER,
                Arrays.asList(secondInterval, firstInterval),
                candidates);

        // then
        List<SubscriptionDelta.Entry> canonical =
                Arrays.asList(firstInterval, secondInterval);
        assertEquals(
                canonical,
                forward.deliveryPlan().activeSubscriptionIntervals());
        assertEquals(
                canonical,
                reversed.deliveryPlan().activeSubscriptionIntervals());
        assertEquals(
                diagnosticOccurrences(forward),
                diagnosticOccurrences(reversed));
    }

    @Test
    void shouldUseCanonicalIntervalOrderForSharedGasFailure() {
        // given
        Node first = channel(0, true, true, true)
                .properties(
                        "runtimeNamespace",
                        new Node().value("gas-first"));
        Node second = channel(1, true, true, true)
                .properties(
                        "runtimeNamespace",
                        new Node().value("gas-second"));
        Node root = root("first", first, "second", second);
        SubscriptionDelta.Entry firstInterval =
                interval("first", first, null);
        SubscriptionDelta.Entry secondInterval =
                interval("second", second, null);
        List<ExternalSubscriptionOccurrenceKey> candidates = Arrays.asList(
                ExternalSubscriptionOccurrenceKey.of("/", "first"),
                ExternalSubscriptionOccurrenceKey.of("/", "second"));
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(
                        CHANNEL_TYPE_BLUE_ID,
                        CHANNEL_TYPE,
                        new IndexedTestChannelProcessor())
                .gasLimit(1L)
                .build();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        Throwable forwardFailure = captureFailure(
                () -> evaluator.prepare(
                        root,
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Arrays.asList(firstInterval, secondInterval),
                        candidates));
        Throwable reversedFailure = captureFailure(
                () -> evaluator.prepare(
                        root,
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Arrays.asList(secondInterval, firstInterval),
                        candidates));

        // then
        assertEquals(GasLimitExceededException.class,
                forwardFailure.getClass());
        assertEquals(GasLimitExceededException.class,
                reversedFailure.getClass());
        assertEquals(
                "gas-second",
                ((GasLimitExceededException) forwardFailure).namespace());
        assertEquals(
                "gas-second",
                ((GasLimitExceededException) reversedFailure).namespace());
    }

    @Test
    void shouldRejectCandidateChangeAcrossIndependentVerification() {
        // given
        Node channel = channel(0, false, false, false);
        Node root = root("changing", channel);
        DocumentProcessor processor = processor(
                new PairedChangingCandidateProcessor());
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        Throwable failure = captureFailure(
                () -> evaluator.prepare(
                        root,
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.singletonList(
                                interval("changing", channel, null)),
                        Collections.singletonList(
                                ExternalSubscriptionOccurrenceKey.of(
                                        "/", "changing"))));

        // then
        assertEquals(InvalidExecutionEvidenceException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "candidate set changed"));
    }

    @Test
    void shouldRejectDiagnosticChangeAcrossIndependentVerification() {
        // given
        Node channel = channel(0, false, false, false);
        Node root = root("changingDiagnostic", channel);
        DocumentProcessor processor = processor(
                new PairedChangingDiagnosticProcessor());
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        Throwable failure = captureFailure(
                () -> evaluator.prepare(
                        root,
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.singletonList(
                                interval(
                                        "changingDiagnostic",
                                        channel,
                                        null)),
                        Collections.singletonList(
                                ExternalSubscriptionOccurrenceKey.of(
                                        "/", "changingDiagnostic"))));

        // then
        assertEquals(InvalidExecutionEvidenceException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "diagnostic changed"));
    }

    @Test
    void shouldRejectGasTraceChangeAcrossIndependentVerification() {
        // given
        Node channel = channel(0, true, true, false);
        Node root = root("changingGas", channel);
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(
                        CHANNEL_TYPE_BLUE_ID,
                        CHANNEL_TYPE,
                        new PairedChangingGasProcessor())
                .gasLimit(10L)
                .build();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        Throwable failure = captureFailure(
                () -> evaluator.prepare(
                        root,
                        event(),
                        ROOT_REVISION,
                        EVENT_ORDER,
                        Collections.singletonList(
                                interval("changingGas", channel, null)),
                        Collections.singletonList(
                                ExternalSubscriptionOccurrenceKey.of(
                                        "/", "changingGas"))));

        // then
        assertEquals(InvalidExecutionEvidenceException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains("gas trace changed"));
    }

    @Test
    void shouldNotChargeDiagnosticReplayToInvocationBudget() {
        // given
        Node charged = channel(0, true, true, true);
        Node root = root("charged", charged);
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(
                        CHANNEL_TYPE_BLUE_ID,
                        CHANNEL_TYPE,
                        new IndexedTestChannelProcessor())
                .gasLimit(1L)
                .build();
        IndexedDeliveryEvaluator evaluator =
                processor.administration().indexedDeliveryEvaluator();

        // when
        IndexedDeliveryPreparation preparation = evaluator.prepare(
                root,
                event(),
                ROOT_REVISION,
                EVENT_ORDER,
                Collections.singletonList(
                        interval("charged", charged, null)),
                Collections.singletonList(
                        ExternalSubscriptionOccurrenceKey.of(
                                "/", "charged")));

        // then
        assertEquals(1, preparation.deliveryPlan().deliveries().size());
    }

    @Test
    void shouldProveCompleteSurfaceForPureReferenceRoot() {
        // given
        Node accepted = channel(0, true, true, false);
        Node exactRoot = root("accepted", accepted);
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactRoot);
        Map<String, Node> providerNodes = new LinkedHashMap<>();
        providerNodes.put(rootBlueId, exactRoot);
        providerNodes.put(CHANNEL_TYPE_BLUE_ID, CHANNEL_TYPE);
        NodeProvider provider = blueId -> {
            Node supplied = providerNodes.get(blueId);
            return supplied != null
                    ? Collections.singletonList(supplied.clone())
                    : null;
        };

        // when
        IndexedDeliveryPreparation preparation;
        try (Blue language = new Blue(provider)) {
            DocumentProcessor processor = DocumentProcessor.Builder
                    .from(language.getDocumentProcessor())
                    .registerContractProcessor(
                            CHANNEL_TYPE_BLUE_ID,
                            CHANNEL_TYPE,
                            new IndexedTestChannelProcessor())
                    .build();
            try {
                preparation = processor.administration()
                        .indexedDeliveryEvaluator()
                        .prepare(
                                new Node().blueId(rootBlueId),
                                event(),
                                ROOT_REVISION,
                                EVENT_ORDER,
                                Collections.singletonList(
                                        interval(
                                                "accepted",
                                                accepted,
                                                null)),
                                Collections.singletonList(
                                        ExternalSubscriptionOccurrenceKey.of(
                                                "/", "accepted")));
            } finally {
                processor.close();
            }
        }

        // then
        assertEquals(1, preparation.deliveryPlan().deliveries().size());
        assertEquals("accepted",
                preparation.deliveryPlan().deliveries().get(0).channelKey());
    }

    @Test
    void shouldPreserveRequiredBlueIdWhenReferenceRootIsUnavailable() {
        // given
        Node accepted = channel(0, true, true, false);
        Node exactRoot = root("accepted", accepted);
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactRoot);
        NodeProvider provider = blueId -> CHANNEL_TYPE_BLUE_ID.equals(blueId)
                ? Collections.singletonList(CHANNEL_TYPE.clone())
                : null;

        // when
        Throwable failure;
        try (Blue language = new Blue(provider)) {
            DocumentProcessor processor = DocumentProcessor.Builder
                    .from(language.getDocumentProcessor())
                    .registerContractProcessor(
                            CHANNEL_TYPE_BLUE_ID,
                            CHANNEL_TYPE,
                            new IndexedTestChannelProcessor())
                    .build();
            try {
                failure = captureFailure(
                        () -> processor.administration()
                                .indexedDeliveryEvaluator()
                                .prepare(
                                        new Node().blueId(rootBlueId),
                                        event(),
                                        ROOT_REVISION,
                                        EVENT_ORDER,
                                        Collections.singletonList(
                                                interval(
                                                        "accepted",
                                                        accepted,
                                                        null)),
                                        Collections.singletonList(
                                                ExternalSubscriptionOccurrenceKey.of(
                                                        "/", "accepted"))));
            } finally {
                processor.close();
            }
        }

        // then
        assertEquals(
                ExecutionEvidenceUnavailableException.class,
                failure.getClass());
        assertTrue(((ExecutionEvidenceUnavailableException) failure)
                .requiredExactBlueIds().contains(rootBlueId));
    }

    @Test
    void shouldRejectMismatchedProviderContentForReferenceRoot() {
        // given
        Node accepted = channel(0, true, true, false);
        Node exactRoot = root("accepted", accepted);
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactRoot);
        NodeProvider provider = blueId -> {
            if (rootBlueId.equals(blueId)) {
                return Collections.singletonList(
                        new Node().name("wrong root content"));
            }
            return CHANNEL_TYPE_BLUE_ID.equals(blueId)
                    ? Collections.singletonList(CHANNEL_TYPE.clone())
                    : null;
        };

        // when
        Throwable failure;
        try (Blue language = new Blue(provider)) {
            DocumentProcessor processor = DocumentProcessor.Builder
                    .from(language.getDocumentProcessor())
                    .registerContractProcessor(
                            CHANNEL_TYPE_BLUE_ID,
                            CHANNEL_TYPE,
                            new IndexedTestChannelProcessor())
                    .build();
            try {
                failure = captureFailure(
                        () -> processor.administration()
                                .indexedDeliveryEvaluator()
                                .prepare(
                                        new Node().blueId(rootBlueId),
                                        event(),
                                        ROOT_REVISION,
                                        EVENT_ORDER,
                                        Collections.singletonList(
                                                interval(
                                                        "accepted",
                                                        accepted,
                                                        null)),
                                        Collections.singletonList(
                                                ExternalSubscriptionOccurrenceKey.of(
                                                        "/", "accepted"))));
            } finally {
                processor.close();
            }
        }

        // then
        assertEquals(
                InvalidExecutionEvidenceException.class,
                failure.getClass());
        assertTrue(failure.getMessage() != null
                && !failure.getMessage().isEmpty());
    }

    @Test
    void shouldRejectRelativeOccurrenceScope() {
        // given
        String scope = "relative/scope";

        // when
        Throwable failure = captureFailure(
                () -> ExternalSubscriptionOccurrenceKey.of(
                        scope, "channel"));

        // then
        assertEquals(IllegalArgumentException.class, failure.getClass());
    }

    @Test
    void shouldRejectOccurrenceScopeWithEmptySegment() {
        // given
        String scope = "/scope//child";

        // when
        Throwable failure = captureFailure(
                () -> ExternalSubscriptionOccurrenceKey.of(
                        scope, "channel"));

        // then
        assertEquals(IllegalArgumentException.class, failure.getClass());
    }

    @Test
    void shouldRejectOccurrenceScopeWithTrailingSlash() {
        // given
        String scope = "/scope/";

        // when
        Throwable failure = captureFailure(
                () -> ExternalSubscriptionOccurrenceKey.of(
                        scope, "channel"));

        // then
        assertEquals(IllegalArgumentException.class, failure.getClass());
    }

    @Test
    void shouldRejectOccurrenceScopeWithInvalidEscape() {
        // given
        String scope = "/scope/~2child";

        // when
        Throwable failure = captureFailure(
                () -> ExternalSubscriptionOccurrenceKey.of(
                        scope, "channel"));

        // then
        assertEquals(IllegalArgumentException.class, failure.getClass());
    }

    @Test
    void shouldPreserveCanonicalOccurrenceScopeEquality() {
        // given
        ExternalSubscriptionOccurrenceKey first =
                ExternalSubscriptionOccurrenceKey.of(
                        "/scope/a~1b/~0value", "channel");
        ExternalSubscriptionOccurrenceKey second =
                ExternalSubscriptionOccurrenceKey.of(
                        "/scope/a~1b/~0value", "channel");

        // when
        ExternalSubscriptionOccurrenceKey root =
                ExternalSubscriptionOccurrenceKey.of(
                        "/", "channel");

        // then
        assertEquals("/scope/a~1b/~0value", first.scopePath());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(
                ExternalSubscriptionOccurrenceKey.of("/", "channel"),
                root);
    }

    private static DocumentProcessor processor() {
        return processor(new IndexedTestChannelProcessor());
    }

    private static DocumentProcessor processor(Runnable evaluationHook) {
        return processor(new IndexedTestChannelProcessor(evaluationHook));
    }

    private static DocumentProcessor processor(
            ChannelProcessor<IndexedTestChannel> channelProcessor) {
        return DocumentProcessor.builder()
                .registerContractProcessor(
                        CHANNEL_TYPE_BLUE_ID,
                        CHANNEL_TYPE,
                        channelProcessor)
                .build();
    }

    private static List<ExternalSubscriptionOccurrenceKey>
    diagnosticOccurrences(IndexedDeliveryPreparation preparation) {
        List<ExternalSubscriptionOccurrenceKey> occurrences =
                new ArrayList<>();
        for (IndexedDeliveryDiagnostic diagnostic
                : preparation.diagnostics()) {
            occurrences.add(diagnostic.occurrenceKey());
        }
        return occurrences;
    }

    private static Node root(Object... keyedChannels) {
        Node contracts = new Node();
        for (int index = 0; index < keyedChannels.length; index += 2) {
            contracts.properties(
                    (String) keyedChannels[index],
                    (Node) keyedChannels[index + 1]);
        }
        return new Node().contracts(contracts);
    }

    private static Node channel(
            int order,
            boolean preselects,
            boolean accepts,
            boolean chargeRuntime) {
        return new Node()
                .type(new Node().blueId(CHANNEL_TYPE_BLUE_ID))
                .properties("order", new Node().value(order))
                .properties(
                        "subscriptionKey",
                        new Node().value(SUBSCRIPTION_KEY))
                .properties(
                        "preselects",
                        new Node().value(preselects))
                .properties(
                        "accepts",
                        new Node().value(accepts))
                .properties(
                        "chargeRuntime",
                        new Node().value(chargeRuntime));
    }

    private static Node event() {
        return new Node().properties(
                ProcessorContractConstants.KEY_SUBSCRIPTION_KEY,
                new Node().value(SUBSCRIPTION_KEY));
    }

    private static SubscriptionDelta.Entry interval(
            String key,
            Node channel,
            ExternalOrderKey activationBoundary) {
        String contribution =
                DirectBlueIdCalculator.calculateBlueId(channel);
        List<String> contributions =
                Collections.singletonList(contribution);
        return new SubscriptionDelta.Entry(
                "/",
                key,
                CHANNEL_TYPE_BLUE_ID,
                contributions,
                channel.getAsInteger("/order"),
                Collections.singletonList(SUBSCRIPTION_KEY),
                CheckpointDomain.derive(
                        CHANNEL_TYPE_BLUE_ID,
                        contributions,
                        CHECKPOINT_DISCRIMINATOR),
                ExternalChannelDependencySnapshot.none(),
                1L,
                activationBoundary,
                null);
    }

    public static final class IndexedTestChannel extends ChannelContract {
        private String subscriptionKey;
        private Boolean preselects;
        private Boolean accepts;
        private Boolean chargeRuntime;
        private String runtimeNamespace;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public Boolean getPreselects() {
            return preselects;
        }

        public void setPreselects(Boolean preselects) {
            this.preselects = preselects;
        }

        public Boolean getAccepts() {
            return accepts;
        }

        public void setAccepts(Boolean accepts) {
            this.accepts = accepts;
        }

        public Boolean getChargeRuntime() {
            return chargeRuntime;
        }

        public void setChargeRuntime(Boolean chargeRuntime) {
            this.chargeRuntime = chargeRuntime;
        }

        public String getRuntimeNamespace() {
            return runtimeNamespace;
        }

        public void setRuntimeNamespace(String runtimeNamespace) {
            this.runtimeNamespace = runtimeNamespace;
        }
    }

    private static final class IndexedTestChannelProcessor
            implements ChannelProcessor<IndexedTestChannel> {

        private final Runnable evaluationHook;

        private IndexedTestChannelProcessor() {
            this(null);
        }

        private IndexedTestChannelProcessor(Runnable evaluationHook) {
            this.evaluationHook = evaluationHook;
        }

        private final ExternalChannelSubscriptionFunctions<
                IndexedTestChannel> subscriptionFunctions =
                new ExternalChannelSubscriptionFunctions<
                        IndexedTestChannel>() {
                    @Override
                    public List<String> channelKeys(
                            IndexedTestChannel channel) {
                        return Collections.singletonList(
                                channel.getSubscriptionKey());
                    }

                    @Override
                    public boolean preselects(
                            IndexedTestChannel channel,
                            Node exactEvent) {
                        return Boolean.TRUE.equals(
                                channel.getPreselects());
                    }

                    @Override
                    public boolean preselects(
                            IndexedTestChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        if (evaluationHook != null) {
                            evaluationHook.run();
                        }
                        return preselects(channel, exactEvent);
                    }

                    @Override
                    public boolean accepts(
                            IndexedTestChannel channel,
                            Node exactEvent) {
                        return Boolean.TRUE.equals(
                                channel.getAccepts());
                    }

                    @Override
                    public boolean accepts(
                            IndexedTestChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return accepts(channel, exactEvent);
                    }

                    @Override
                    public Node payload(
                            IndexedTestChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        if (Boolean.TRUE.equals(
                                channel.getChargeRuntime())) {
                            RuntimeWorkSession session =
                                    context.runtimeWorkSession();
                            GasMeter.ChildGasLedger ledger =
                                    session.openLedger(
                                            channel.getRuntimeNamespace() != null
                                                    ? channel
                                                    .getRuntimeNamespace()
                                                    : "indexed-test",
                                            Collections.singletonMap(
                                                    "evaluate", 1L));
                            ledger.charge("evaluate", 1L);
                            session.submit(ledger);
                        }
                        return exactEvent.clone();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            IndexedTestChannel channel) {
                        return CHECKPOINT_DISCRIMINATOR;
                    }
                };

        @Override
        public Class<IndexedTestChannel> contractType() {
            return IndexedTestChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<IndexedTestChannel>
        externalSubscriptionFunctions() {
            return subscriptionFunctions;
        }

        @Override
        public ChannelEvaluation evaluate(
                IndexedTestChannel channel,
                ChannelEvaluationContext context) {
            return Boolean.TRUE.equals(channel.getAccepts())
                    ? ChannelEvaluation.match(context.event())
                    : ChannelEvaluation.noMatch();
        }
    }

    private static final class PairedChangingCandidateProcessor
            implements ChannelProcessor<IndexedTestChannel> {

        private final AtomicInteger eventKeyCalls = new AtomicInteger();
        private final ExternalChannelSubscriptionFunctions<
                IndexedTestChannel> subscriptionFunctions =
                new ExternalChannelSubscriptionFunctions<
                        IndexedTestChannel>() {
                    @Override
                    public List<String> channelKeys(
                            IndexedTestChannel channel) {
                        return Collections.singletonList(SUBSCRIPTION_KEY);
                    }

                    @Override
                    public List<String> eventKeys(
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        int evaluationPair =
                                eventKeyCalls.getAndIncrement() / 2;
                        return Collections.singletonList(
                                evaluationPair == 0
                                        ? SUBSCRIPTION_KEY
                                        : "different-topic");
                    }

                    @Override
                    public boolean preselects(
                            IndexedTestChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return false;
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            IndexedTestChannel channel) {
                        return CHECKPOINT_DISCRIMINATOR;
                    }
                };

        @Override
        public Class<IndexedTestChannel> contractType() {
            return IndexedTestChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<IndexedTestChannel>
        externalSubscriptionFunctions() {
            return subscriptionFunctions;
        }

        @Override
        public ChannelEvaluation evaluate(
                IndexedTestChannel channel,
                ChannelEvaluationContext context) {
            return ChannelEvaluation.noMatch();
        }
    }

    private static final class StaleSnapshotManager
            implements ProcessingSnapshotManager {

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            FrozenNode canonical = FrozenNode.fromNode(document.clone());
            return new ResolvedSnapshot(
                    canonical,
                    FrozenNode.fromResolvedNode(document.clone()),
                    canonical.blueId());
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            throw new UnsupportedOperationException(
                    "Indexed delivery does not apply patches");
        }

        @Override
        public boolean isTransientStateCurrent() {
            return false;
        }
    }

    private static final class PairedChangingGasProcessor
            implements ChannelProcessor<IndexedTestChannel> {

        private final AtomicInteger payloadCalls = new AtomicInteger();
        private final ExternalChannelSubscriptionFunctions<
                IndexedTestChannel> subscriptionFunctions =
                new ExternalChannelSubscriptionFunctions<
                        IndexedTestChannel>() {
                    @Override
                    public List<String> channelKeys(
                            IndexedTestChannel channel) {
                        return Collections.singletonList(SUBSCRIPTION_KEY);
                    }

                    @Override
                    public Node payload(
                            IndexedTestChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        long quantity = payloadCalls.getAndIncrement() / 2 + 1L;
                        RuntimeWorkSession session =
                                context.runtimeWorkSession();
                        GasMeter.ChildGasLedger ledger = session.openLedger(
                                "paired-gas",
                                Collections.singletonMap("evaluate", 1L));
                        ledger.charge("evaluate", quantity);
                        session.submit(ledger);
                        return exactEvent.clone();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            IndexedTestChannel channel) {
                        return CHECKPOINT_DISCRIMINATOR;
                    }
                };

        @Override
        public Class<IndexedTestChannel> contractType() {
            return IndexedTestChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<IndexedTestChannel>
        externalSubscriptionFunctions() {
            return subscriptionFunctions;
        }

        @Override
        public ChannelEvaluation evaluate(
                IndexedTestChannel channel,
                ChannelEvaluationContext context) {
            return ChannelEvaluation.match(context.event());
        }
    }

    private static final class PairedChangingDiagnosticProcessor
            implements ChannelProcessor<IndexedTestChannel> {

        private final AtomicInteger eventKeyCalls = new AtomicInteger();
        private final ExternalChannelSubscriptionFunctions<
                IndexedTestChannel> subscriptionFunctions =
                new ExternalChannelSubscriptionFunctions<
                        IndexedTestChannel>() {
                    @Override
                    public List<String> channelKeys(
                            IndexedTestChannel channel) {
                        return Collections.singletonList(SUBSCRIPTION_KEY);
                    }

                    @Override
                    public List<String> eventKeys(
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        int evaluationPair =
                                eventKeyCalls.getAndIncrement() / 2;
                        return evaluationPair == 0
                                ? Collections.singletonList(SUBSCRIPTION_KEY)
                                : Arrays.asList(
                                        SUBSCRIPTION_KEY,
                                        "additional-topic");
                    }

                    @Override
                    public boolean preselects(
                            IndexedTestChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return false;
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            IndexedTestChannel channel) {
                        return CHECKPOINT_DISCRIMINATOR;
                    }
                };

        @Override
        public Class<IndexedTestChannel> contractType() {
            return IndexedTestChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<IndexedTestChannel>
        externalSubscriptionFunctions() {
            return subscriptionFunctions;
        }

        @Override
        public ChannelEvaluation evaluate(
                IndexedTestChannel channel,
                ChannelEvaluationContext context) {
            return ChannelEvaluation.noMatch();
        }
    }
}
