package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies marker and checkpoint write barriers after embedded cut-off. */
final class ActiveScopeCutOffBoundaryTest {

    @Test
    void shouldSkipInitializationMarkerWhenLifecycleCutsOffTheScope() {
        // given
        AtomicReference<ProcessorInvocationState> executionRef =
                new AtomicReference<>();
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                new CutOffDuringLifecycleProcessor(executionRef));
        Node document = blue.yamlToNode(
                "child:\n"
                        + "  contracts:\n"
                        + "    lifecycle:\n"
                        + "      type:\n"
                        + "        blueId: "
                        + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL
                        + "\n"
                        + "    cutOff:\n"
                        + "      channel: lifecycle\n"
                        + "      type:\n"
                        + "        blueId: "
                        + ProcessorTestTypeBlueIds.SET_PROPERTY
                        + "\n");
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        blue.getDocumentProcessor(),
                        document);
        executionRef.set(execution);

        // when
        execution.initializeScope("/child", false);
        ScopeRuntimeContext child =
                execution.runtime().scope("/child");
        Node marker = ProcessorEngine.nodeAt(
                execution.runtime().document(),
                ProcessorEngine.resolvePointer(
                        "/child",
                        ProcessorPointerConstants.RELATIVE_INITIALIZED));
        List<ProcessingTraceRecord> markerWrites =
                execution.runtime().conformanceTrace().records(
                        ProcessingTraceRecord.Kind.MARKER_WRITE);
        blue.close();

        // then
        assertTrue(child.isCutOff());
        assertNull(marker);
        assertTrue(markerWrites.stream().noneMatch(
                record -> "/child".equals(record.scopePath())
                        && ProcessorContractConstants.KEY_INITIALIZED
                        .equals(record.contractKey())));
    }

    @Test
    void shouldDiscardPendingCheckpointWhenScopeIsCutOffBeforeWrite() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                new TestEventChannelProcessor());
        Node document = blue.yamlToNode(
                "child:\n"
                        + "  contracts:\n"
                        + "    source:\n"
                        + "      type:\n"
                        + "        blueId: "
                        + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL
                        + "\n");
        Node event = new TestEvent()
                .eventId("cut-off-before-checkpoint")
                .toNode();
        ProcessorInvocationState execution = execution(
                blue.getDocumentProcessor(),
                document,
                event);
        execution.preflightScope("/child");
        execution.runtime().scope("/child");
        ContractBundle bundle = execution.bundleForScope("/child");
        ChannelRunner runner = new ChannelRunner(
                blue.getDocumentProcessor(),
                execution,
                execution.runtime(),
                new CheckpointManager(
                        execution.runtime(),
                        ProcessorEngine::canonicalSignature));
        runner.runExternalChannel(
                "/child",
                bundle,
                bundle.channelBinding("source"),
                event);
        boolean activeBeforeCutOff =
                execution.isScopeActive("/child");

        // when
        execution.markCutOff("/child");
        runner.persistPendingCheckpoints("/child");
        Node checkpoint = ProcessorEngine.nodeAt(
                execution.runtime().document(),
                ProcessorEngine.resolvePointer(
                        "/child",
                        ProcessorPointerConstants.RELATIVE_CHECKPOINT));
        List<ProcessingTraceRecord> discarded =
                execution.runtime().conformanceTrace().records(
                        ProcessingTraceRecord.Kind.DISCARDED_EFFECT);
        List<ProcessingTraceRecord> writes =
                execution.runtime().conformanceTrace().records(
                        ProcessingTraceRecord.Kind.CHECKPOINT_WRITE);
        List<ProcessingTraceRecord> comparisons =
                execution.runtime().conformanceTrace().records(
                        ProcessingTraceRecord.Kind.CHECKPOINT_COMPARE);
        blue.close();

        // then
        assertTrue(activeBeforeCutOff);
        assertFalse(execution.isScopeActive("/child"));
        assertNull(checkpoint);
        assertEquals(1, comparisons.size(),
                "the source must reach checkpoint comparison before cut-off");
        assertTrue(writes.isEmpty());
        assertEquals(1, discarded.size());
        assertEquals(
                ProcessingTraceConstants.EFFECT_CHECKPOINT,
                discarded.get(0).detail(
                        ProcessingTraceConstants.FIELD_EFFECT));
        assertEquals(
                ProcessingTraceConstants.REASON_SCOPE_CUT_OFF,
                discarded.get(0).detail(
                        ProcessingTraceConstants.FIELD_REASON));
    }

    private static ProcessorInvocationState execution(
            DocumentProcessor owner,
            Node document,
            Node event) {
        Node channel = ProcessorEngine.nodeAt(
                document,
                "/child/contracts/source");
        String contributionBlueId =
                DirectBlueIdCalculator.calculateBlueId(channel);
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        String checkpointDomainBlueId = CheckpointDomain.derive(
                ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL,
                Collections.singletonList(contributionBlueId),
                null);
        VerifiedExecutionEvidence evidence =
                VerifiedExecutionEvidence.builder(
                                DirectBlueIdCalculator.calculateBlueId(
                                        document),
                                eventBlueId)
                        .revisions(0L, 0L)
                        .runtimeRegistryIdentity(
                                owner.runtimeRegistryIdentity())
                        .eventOrderKey(
                                ExternalOrderKey.of(
                                        Collections
                                                .<Object>singletonList(
                                                        "checkpoint-cut-off")))
                        .delivery(
                                ExternalDeliverySnapshot.builder(
                                                "/child",
                                                "source")
                                        .sourceContribution(
                                                contributionBlueId)
                                        .effectiveTypeBlueId(
                                                ProcessorTestTypeBlueIds
                                                        .TEST_EVENT_CHANNEL)
                                        .subscriptionKey(
                                                event.getType().getBlueId())
                                        .checkpointDomainBlueId(
                                                checkpointDomainBlueId)
                                        .checkpointSubjectBlueId(
                                                eventBlueId)
                                        .build())
                        .build();
        return new ProcessorInvocationState(
                owner,
                document.clone(),
                event,
                evidence);
    }

    private static final class CutOffDuringLifecycleProcessor
            implements HandlerProcessor<SetProperty> {

        private final AtomicReference<ProcessorInvocationState>
                execution;

        private CutOffDuringLifecycleProcessor(
                AtomicReference<ProcessorInvocationState> execution) {
            this.execution = execution;
        }

        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(
                SetProperty contract,
                ProcessorExecutionContext context) {
            execution.get().markCutOff(context.scopePath());
        }
    }
}
