package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.wire.JsonPointer;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes each post-admission PROCESS phase through its own class boundary. */
final class PostAdmissionPhaseExecutionTest {

    private static final String SOURCE_CHANNEL_KEY = "source";
    private static final String INCREMENT_HANDLER_KEY = "increment";
    private static final String CHANNEL_PROPERTY = "channel";
    private static final String PROPERTY_KEY_PROPERTY = "propertyKey";
    private static final String COUNTER_PROPERTY = "counter";
    private static final String COUNTER_POINTER = "/" + COUNTER_PROPERTY;
    private static final String PROCESS_EVENT_ID = "phase-event";
    private static final String PROCESS_EVENT_KIND = "phase";
    private static final String QUEUED_EVENT_ID = "queued-event";
    private static final String QUEUED_EVENT_KIND = "queued";
    private static final String CYCLIC_MEMBER_PROPERTY = "cyclic";
    private static final String CYCLIC_MEMBER_POINTER =
            "/" + CYCLIC_MEMBER_PROPERTY;
    private static final String CYCLIC_MEMBER_BLUE_ID =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";

    @Test
    void shouldAdmitEvidenceThroughEvidenceVerificationPhase() {
        // given
        AcceptedPhaseFixture fixture = acceptedFixture(null);
        ProcessingPhaseState admitted = ProcessingPhaseState.admitted(
                fixture.session,
                fixture.event);

        // when
        ProcessingPhaseState verified =
                new ProcessingEvidenceVerification().execute(admitted);
        ProcessingConformanceTrace trace =
                fixture.execution.runtime().conformanceTrace();
        fixture.close();

        // then
        assertEquals(
                ProcessingPhaseState.Stage.EVIDENCE_VERIFIED,
                verified.stage());
        assertEquals(
                1L,
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .DELIVERY_SNAPSHOT_ENTRY));
        assertEquals(
                1,
                trace.records(
                        ProcessingTraceRecord.Kind.EXTERNAL_DELIVERY)
                        .size());
        assertEquals(
                SOURCE_CHANNEL_KEY,
                trace.records(
                                ProcessingTraceRecord.Kind.EXTERNAL_DELIVERY)
                        .get(0)
                        .contractKey());
    }

    @Test
    void shouldRejectOpaqueCyclicBoundaryDuringClosurePreflight() {
        // given
        DocumentProcessor processor = DocumentProcessor.builder().build();
        Node processEmbedded = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        ProcessorContractConstants.KEY_PATHS,
                        new Node().items(
                                new Node().value(
                                        CYCLIC_MEMBER_POINTER)));
        Node document = new Node()
                .properties(
                        CYCLIC_MEMBER_PROPERTY,
                        new Node().blueId(CYCLIC_MEMBER_BLUE_ID))
                .contracts(new Node().properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        processEmbedded));
        ProcessingSession session = new ProcessingSession(
                new ProcessorInvocationState(processor, document));
        ProcessingPhaseState verified = stateAt(
                session,
                null,
                ProcessingPhaseState.Stage.EVIDENCE_VERIFIED);

        // when
        SubscriptionSurfaceInvalidException failure = captureFailure(
                () -> new ParticipatingClosurePreflight()
                        .execute(verified));
        processor.close();

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory
                        .CyclicSetEmbeddedBoundaryUnsupported,
                failure.diagnostic().category());
        assertEquals(
                JsonPointer.ROOT,
                failure.diagnostic().detail(
                        ProcessorDiagnosticConstants.FIELD_SCOPE_PATH));
        assertEquals(
                ProcessorContractConstants.KEY_EMBEDDED,
                failure.diagnostic().detail(
                        ProcessorDiagnosticConstants.FIELD_CONTRACT_KEY));
        assertTrue(failure.getMessage().contains(
                "Process Embedded traversal into cyclic-set member"));
    }

    @Test
    void shouldClassifyExternalDeliveryWithoutMutatingRoot() {
        // given
        AcceptedPhaseFixture fixture = acceptedFixture(null);
        fixture.session.admitEvidence();
        fixture.session.preflightOpaqueEmbeddedBoundaries();
        ProcessingPhaseState preflighted = stateAt(
                fixture.session,
                fixture.event,
                ProcessingPhaseState.Stage.CLOSURE_PREFLIGHTED);
        String beforeBlueId = DirectBlueIdCalculator.calculateBlueId(
                fixture.execution.runtime().document());

        // when
        ProcessingPhaseState classified =
                new ExternalDeliveryClassification()
                        .execute(preflighted);
        String afterBlueId = DirectBlueIdCalculator.calculateBlueId(
                fixture.execution.runtime().document());
        ProcessingConformanceTrace trace =
                fixture.execution.runtime().conformanceTrace();
        fixture.close();

        // then
        assertEquals(
                ProcessingPhaseState.Stage
                        .EXTERNAL_DELIVERIES_CLASSIFIED,
                classified.stage());
        assertEquals(beforeBlueId, afterBlueId);
        assertEquals(
                1,
                trace.records(
                        ProcessingTraceRecord.Kind.CHECKPOINT_COMPARE)
                        .size());
        assertTrue(trace.records(
                ProcessingTraceRecord.Kind.HANDLER_EXECUTION).isEmpty());
    }

    @Test
    void shouldPreflightAcceptedClosureWithoutInitializingScope() {
        // given
        AcceptedPhaseFixture fixture = acceptedFixture(null);
        fixture.session.admitEvidence();
        fixture.session.preflightOpaqueEmbeddedBoundaries();
        fixture.session.classifyExternalDeliveries(fixture.event);
        ProcessingPhaseState classified = stateAt(
                fixture.session,
                fixture.event,
                ProcessingPhaseState.Stage
                        .EXTERNAL_DELIVERIES_CLASSIFIED);

        // when
        ProcessingPhaseState initialized =
                new ScopeInitialization().execute(classified);
        ContractBundle rootBundle =
                fixture.execution.bundleForScope(JsonPointer.ROOT);
        Node initializationMarker = ProcessorEngine.nodeAt(
                fixture.execution.runtime().document(),
                ProcessorPointerConstants.RELATIVE_INITIALIZED);
        ProcessingConformanceTrace trace =
                fixture.execution.runtime().conformanceTrace();
        fixture.close();

        // then
        assertEquals(
                ProcessingPhaseState.Stage.SCOPES_INITIALIZED,
                initialized.stage());
        assertNotNull(rootBundle);
        assertNull(initializationMarker);
        assertTrue(trace.records(
                ProcessingTraceRecord.Kind.HANDLER_EXECUTION).isEmpty());
    }

    @Test
    void shouldExecuteLogicalDeliveryAndInitializeAcceptedScope() {
        // given
        AcceptedPhaseFixture fixture = acceptedFixture(null);
        fixture.session.admitEvidence();
        fixture.session.preflightOpaqueEmbeddedBoundaries();
        fixture.session.classifyExternalDeliveries(fixture.event);
        fixture.session.preflightParticipatingClosure();
        ProcessingPhaseState initialized = stateAt(
                fixture.session,
                fixture.event,
                ProcessingPhaseState.Stage.SCOPES_INITIALIZED);

        // when
        ProcessingPhaseState executed =
                new LogicalDeliveryExecution().execute(initialized);
        Node document = fixture.execution.runtime().document();
        Integer counter = document.getAsInteger(COUNTER_POINTER);
        Node initializationMarker = ProcessorEngine.nodeAt(
                document,
                ProcessorPointerConstants.RELATIVE_INITIALIZED);
        Node checkpoint = ProcessorEngine.nodeAt(
                document,
                ProcessorPointerConstants.RELATIVE_CHECKPOINT);
        ProcessingConformanceTrace trace =
                fixture.execution.runtime().conformanceTrace();
        fixture.close();

        // then
        assertEquals(
                ProcessingPhaseState.Stage.LOGICAL_DELIVERIES_EXECUTED,
                executed.stage());
        assertEquals(Integer.valueOf(1), counter);
        assertNotNull(initializationMarker);
        assertNull(checkpoint);
        assertEquals(
                1,
                trace.records(
                        ProcessingTraceRecord.Kind.HANDLER_EXECUTION)
                        .size());
    }

    @Test
    void shouldDrainQueuedOccurrenceThroughInternalOccurrencePhase() {
        // given
        AcceptedPhaseFixture fixture = acceptedFixture(null);
        prepareThroughLogicalDelivery(fixture);
        Node queuedEvent = new TestEvent()
                .eventId(QUEUED_EVENT_ID)
                .kind(QUEUED_EVENT_KIND)
                .toNode();
        fixture.execution.enqueueApplicationEvent(
                JsonPointer.ROOT,
                INCREMENT_HANDLER_KEY,
                queuedEvent,
                DirectBlueIdCalculator.calculateBlueId(queuedEvent));
        int pendingBefore =
                fixture.session.eventQueue().pendingOccurrenceCount();
        ProcessingPhaseState executed = stateAt(
                fixture.session,
                fixture.event,
                ProcessingPhaseState.Stage.LOGICAL_DELIVERIES_EXECUTED);

        // when
        ProcessingPhaseState drained =
                new InternalOccurrenceDrain().execute(executed);
        int pendingAfter =
                fixture.session.eventQueue().pendingOccurrenceCount();
        ProcessingConformanceTrace trace =
                fixture.execution.runtime().conformanceTrace();
        fixture.close();

        // then
        assertEquals(
                ProcessingPhaseState.Stage.INTERNAL_OCCURRENCES_DRAINED,
                drained.stage());
        assertEquals(1, pendingBefore);
        assertEquals(0, pendingAfter);
        assertEquals(
                1,
                trace.records(
                        ProcessingTraceRecord.Kind.EVENT_DEQUEUED)
                        .size());
    }

    @Test
    void shouldPersistPendingCheckpointDuringFinalSoundnessValidation() {
        // given
        AcceptedPhaseFixture fixture = acceptedFixture(null);
        prepareThroughLogicalDelivery(fixture);
        fixture.session.drainInternalOccurrences();
        ProcessingPhaseState drained = stateAt(
                fixture.session,
                fixture.event,
                ProcessingPhaseState.Stage.INTERNAL_OCCURRENCES_DRAINED);
        Node checkpointBefore = ProcessorEngine.nodeAt(
                fixture.execution.runtime().document(),
                ProcessorPointerConstants.RELATIVE_CHECKPOINT);

        // when
        ProcessingPhaseState validated =
                new FinalSoundnessValidation().execute(drained);
        Node checkpointAfter = ProcessorEngine.nodeAt(
                fixture.execution.runtime().document(),
                ProcessorPointerConstants.RELATIVE_CHECKPOINT);
        ProcessingConformanceTrace trace =
                fixture.execution.runtime().conformanceTrace();
        fixture.close();

        // then
        assertEquals(
                ProcessingPhaseState.Stage.SOUNDNESS_VALIDATED,
                validated.stage());
        assertNull(checkpointBefore);
        assertNotNull(checkpointAfter);
        assertEquals(
                1,
                trace.records(
                        ProcessingTraceRecord.Kind.CHECKPOINT_WRITE)
                        .size());
    }

    @Test
    void shouldInvokeSubscriptionValidatorAfterSoundness() {
        // given
        AtomicInteger validatorCalls = new AtomicInteger();
        AcceptedPhaseFixture fixture = acceptedFixture(context -> {
            validatorCalls.incrementAndGet();
            return SubscriptionDelta.empty();
        });
        prepareThroughFinalSoundness(fixture);
        ProcessingPhaseState sound = stateAt(
                fixture.session,
                fixture.event,
                ProcessingPhaseState.Stage.SOUNDNESS_VALIDATED);

        // when
        ProcessingPhaseState validated =
                new SubscriptionDeltaValidation().execute(sound);
        ProcessingConformanceTrace trace =
                fixture.execution.runtime().conformanceTrace();
        ProcessingTraceRecord deltaRecord = trace.records(
                        ProcessingTraceRecord.Kind.SUBSCRIPTION_DELTA)
                .get(0);
        fixture.close();

        // then
        assertEquals(
                ProcessingPhaseState.Stage
                        .SUBSCRIPTION_DELTA_VALIDATED,
                validated.stage());
        assertEquals(1, validatorCalls.get());
        assertEquals(
                String.valueOf(0),
                deltaRecord.detail(ProcessingTraceConstants.FIELD_ADDED));
        assertEquals(
                String.valueOf(0),
                deltaRecord.detail(ProcessingTraceConstants.FIELD_REMOVED));
    }

    @Test
    void shouldAssembleValidatedResultAndPlatformCompanion() {
        // given
        AcceptedPhaseFixture fixture = acceptedFixture(
                context -> SubscriptionDelta.empty());
        prepareThroughFinalSoundness(fixture);
        fixture.session.validateSubscriptionDelta();
        ProcessingPhaseState validated = stateAt(
                fixture.session,
                fixture.event,
                ProcessingPhaseState.Stage
                        .SUBSCRIPTION_DELTA_VALIDATED);

        // when
        ProcessingDebugResult assembled =
                new ProcessResultAssembly().execute(validated);
        DocumentProcessingResult result = assembled.processResult();
        PlatformCommitCompanion companion =
                assembled.platformCommitCompanion();
        fixture.close();

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertTrue(result.commits());
        assertEquals(1, result.document().getAsInteger(COUNTER_POINTER));
        assertTrue(result.events().isEmpty());
        assertNotNull(companion);
        assertEquals(0L, companion.expectedRootRevision());
        assertEquals(1L, companion.resultingRootRevision());
        assertTrue(companion.commitsRootAndOutbox());
        assertTrue(companion.subscriptionDelta().isEmpty());
    }

    private static void prepareThroughLogicalDelivery(
            AcceptedPhaseFixture fixture) {
        fixture.session.admitEvidence();
        fixture.session.preflightOpaqueEmbeddedBoundaries();
        fixture.session.classifyExternalDeliveries(fixture.event);
        fixture.session.preflightParticipatingClosure();
        fixture.session.executeLogicalDeliveries();
    }

    private static void prepareThroughFinalSoundness(
            AcceptedPhaseFixture fixture) {
        prepareThroughLogicalDelivery(fixture);
        fixture.session.drainInternalOccurrences();
        fixture.session.validateFinalSoundness();
    }

    private static ProcessingPhaseState stateAt(
            ProcessingSession session,
            Node event,
            ProcessingPhaseState.Stage target) {
        ProcessingPhaseState state = ProcessingPhaseState.admitted(
                session,
                event);
        ProcessingPhaseState.Stage[] stages =
                ProcessingPhaseState.Stage.values();
        while (state.stage() != target) {
            ProcessingPhaseState.Stage current = state.stage();
            state = state.advance(
                    current,
                    stages[current.ordinal() + 1]);
        }
        return state;
    }

    private static AcceptedPhaseFixture acceptedFixture(
            SubscriptionSurfaceValidator validator) {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                new TestEventChannelProcessor());
        blue.registerContractProcessor(
                new IncrementPropertyContractProcessor());
        DocumentProcessor current = blue.getDocumentProcessor();
        DocumentProcessor owner = validator != null
                ? DocumentProcessor.Builder.from(current)
                .subscriptionSurfaceValidator(validator)
                .build()
                : current;
        Node document = acceptedDocument();
        Node event = new TestEvent()
                .eventId(PROCESS_EVENT_ID)
                .kind(PROCESS_EVENT_KIND)
                .toNode();
        Node source = document.getContracts()
                .getProperties()
                .get(SOURCE_CHANNEL_KEY);
        String sourceBlueId =
                DirectBlueIdCalculator.calculateBlueId(source);
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        VerifiedExecutionEvidence evidence =
                VerifiedExecutionEvidence.builder(
                                DirectBlueIdCalculator.calculateBlueId(document),
                                eventBlueId)
                        .revisions(0L, 0L)
                        .runtimeRegistryIdentity(
                                owner.runtimeRegistryIdentity())
                        .eventOrderKey(ExternalOrderKey.of(
                                Collections.<Object>singletonList(
                                        PROCESS_EVENT_ID)))
                        .delivery(ExternalDeliverySnapshot.builder(
                                        JsonPointer.ROOT,
                                        SOURCE_CHANNEL_KEY)
                                .sourceContribution(sourceBlueId)
                                .effectiveTypeBlueId(
                                        ProcessorTestTypeBlueIds
                                                .TEST_EVENT_CHANNEL)
                                .subscriptionKey(
                                        ProcessorTestTypeBlueIds.TEST_EVENT)
                                .checkpointDomainBlueId(
                                        CheckpointDomain.derive(
                                                ProcessorTestTypeBlueIds
                                                        .TEST_EVENT_CHANNEL,
                                                Collections.singletonList(
                                                        sourceBlueId),
                                                null))
                                .checkpointSubjectBlueId(eventBlueId)
                                .build())
                        .build();
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        owner,
                        document,
                        event,
                        evidence);
        return new AcceptedPhaseFixture(
                blue,
                owner,
                owner != current,
                execution,
                event);
    }

    private static Node acceptedDocument() {
        Node source = new Node().type(new Node().blueId(
                ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL));
        Node increment = new Node()
                .type(new Node().blueId(
                        ProcessorTestTypeBlueIds.INCREMENT_PROPERTY))
                .properties(
                        CHANNEL_PROPERTY,
                        new Node().value(SOURCE_CHANNEL_KEY))
                .properties(
                        PROPERTY_KEY_PROPERTY,
                        new Node().value(COUNTER_POINTER));
        return new Node()
                .properties(
                        COUNTER_PROPERTY,
                        new Node().value(0))
                .contracts(new Node()
                        .properties(SOURCE_CHANNEL_KEY, source)
                        .properties(INCREMENT_HANDLER_KEY, increment));
    }

    /** Owns all resources and invocation state shared by one phase test. */
    private static final class AcceptedPhaseFixture {
        private final Blue blue;
        private final DocumentProcessor owner;
        private final boolean detachedOwner;
        private final ProcessorInvocationState execution;
        private final ProcessingSession session;
        private final Node event;

        private AcceptedPhaseFixture(
                Blue blue,
                DocumentProcessor owner,
                boolean detachedOwner,
                ProcessorInvocationState execution,
                Node event) {
            this.blue = blue;
            this.owner = owner;
            this.detachedOwner = detachedOwner;
            this.execution = execution;
            this.session = new ProcessingSession(execution);
            this.event = event;
        }

        private void close() {
            if (detachedOwner) {
                owner.close();
            }
            blue.close();
        }
    }
}
