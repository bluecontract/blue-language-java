package blue.language.processor;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class ProcessingPhasePipelineTest {

    private static final String EVENT_PAYLOAD_PROPERTY = "payload";
    private static final String EVENT_PAYLOAD_POINTER =
            "/" + EVENT_PAYLOAD_PROPERTY;
    private static final String ORIGINAL_PAYLOAD = "original";
    private static final String MUTATED_SOURCE_PAYLOAD = "source-mutated";
    private static final String MUTATED_READ_PAYLOAD = "read-mutated";

    @Test
    void shouldDeclareEveryDeterministicPhaseBoundaryInSpecificationOrder() {
        // given
        List<ProcessingPhaseContract> contracts = Arrays.asList(
                ProcessingEvidenceVerification.CONTRACT,
                ParticipatingClosurePreflight.CONTRACT,
                ExternalDeliveryClassification.CONTRACT,
                ScopeInitialization.CONTRACT,
                LogicalDeliveryExecution.CONTRACT,
                InternalOccurrenceDrain.CONTRACT,
                FinalSoundnessValidation.CONTRACT,
                SubscriptionDeltaValidation.CONTRACT);

        // when
        List<ProcessingPhaseState.Stage> stages = Arrays.asList(
                contracts.get(0).stage(),
                contracts.get(1).stage(),
                contracts.get(2).stage(),
                contracts.get(3).stage(),
                contracts.get(4).stage(),
                contracts.get(5).stage(),
                contracts.get(6).stage(),
                contracts.get(7).stage());

        // then
        assertEquals(Arrays.asList(
                ProcessingPhaseState.Stage.EVIDENCE_VERIFIED,
                ProcessingPhaseState.Stage.CLOSURE_PREFLIGHTED,
                ProcessingPhaseState.Stage.EXTERNAL_DELIVERIES_CLASSIFIED,
                ProcessingPhaseState.Stage.SCOPES_INITIALIZED,
                ProcessingPhaseState.Stage.LOGICAL_DELIVERIES_EXECUTED,
                ProcessingPhaseState.Stage.INTERNAL_OCCURRENCES_DRAINED,
                ProcessingPhaseState.Stage.SOUNDNESS_VALIDATED,
                ProcessingPhaseState.Stage.SUBSCRIPTION_DELTA_VALIDATED),
                stages);
        for (ProcessingPhaseContract contract : contracts) {
            assertNotNull(contract.gasBehavior());
            assertNotNull(contract.providerDemand());
            assertNotNull(contract.failureCategory());
        }
    }

    @Test
    void shouldComposeIndependentInvocationOwnedStateComponents() {
        // given
        DocumentProcessor processor = DocumentProcessor.builder().build();
        ProcessorInvocationState firstExecution =
                new ProcessorInvocationState(processor, new Node());
        ProcessorInvocationState secondExecution =
                new ProcessorInvocationState(processor, new Node());

        // when
        ProcessingSession first = new ProcessingSession(firstExecution);
        ProcessingSession second = new ProcessingSession(secondExecution);

        // then
        assertNotNull(first.documentView());
        assertNotNull(first.mutationSession());
        assertNotNull(first.eventQueue());
        assertNotNull(first.lifecycleState());
        assertNotNull(first.gasContext());
        assertNotNull(first.scopeRegistry());
        assertNotNull(first.outputCollector());
        assertNotNull(first.cutoffTracker());
        assertNotNull(first.snapshotTransaction());
        assertNotSame(first.eventQueue(), second.eventQueue());
        assertNotSame(first.scopeRegistry(), second.scopeRegistry());
        assertNotSame(first.gasContext(), second.gasContext());
    }

    @Test
    void shouldDefensivelyCopyEventAcrossEveryPhaseHandOff() {
        // given
        DocumentProcessor processor = DocumentProcessor.builder().build();
        ProcessorInvocationState execution =
                new ProcessorInvocationState(processor, new Node());
        ProcessingSession session = new ProcessingSession(execution);
        Node supplied = new Node().properties(
                EVENT_PAYLOAD_PROPERTY,
                new Node().value(ORIGINAL_PAYLOAD));
        ProcessingPhaseState admitted =
                ProcessingPhaseState.admitted(session, supplied);

        // when
        supplied.getProperties()
                .get(EVENT_PAYLOAD_PROPERTY)
                .value(MUTATED_SOURCE_PAYLOAD);
        Node firstRead = admitted.event();
        firstRead.getProperties()
                .get(EVENT_PAYLOAD_PROPERTY)
                .value(MUTATED_READ_PAYLOAD);
        ProcessingPhaseState advanced = admitted.advance(
                ProcessingPhaseState.Stage.INPUT_ADMITTED,
                ProcessingPhaseState.Stage.EVIDENCE_VERIFIED);
        Node secondRead = advanced.event();
        processor.close();

        // then
        assertEquals(
                ORIGINAL_PAYLOAD,
                admitted.event().getAsText(EVENT_PAYLOAD_POINTER));
        assertEquals(
                ORIGINAL_PAYLOAD,
                secondRead.getAsText(EVENT_PAYLOAD_POINTER));
        assertNotSame(firstRead, secondRead);
    }
}
