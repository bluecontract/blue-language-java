package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContractExecutionResultPortableLimitTest {

    private static final String PATCH_LIMIT =
            "patchesPerContractExecutionResult";
    private static final String EVENT_LIMIT =
            "eventsPerContractExecutionResult";

    @Test
    void shouldVerifyPatchLimitIsCumulativeAcrossMutableAndFrozenBatches() {
        // given
        Fixture fixture = fixture();
        int limit = portableLimit(PATCH_LIMIT);
        int firstBatchSize = limit / 2;
        CloneCountingNode overflowValue =
                new CloneCountingNode();
        overflowValue.value("overflow");

        // when
        fixture.context.applyPatches(
                mutablePatches(0, firstBatchSize));
        fixture.context.applyFrozenPatches(
                frozenRemovals(
                        firstBatchSize,
                        limit - firstBatchSize));
        Throwable failure = captureFailure(
                () -> fixture.context.applyPatch(
                        JsonPatch.add(
                                "/overflow",
                                overflowValue)));
        int overflowCloneCalls = overflowValue.cloneCalls;
        fixture.context.close();

        // then
        assertTrue(failure instanceof PortableLimitExceededException);
        assertLimitFailure(
                (PortableLimitExceededException) failure,
                ProcessorErrorCategory.PatchLimitExceeded,
                PATCH_LIMIT,
                limit + 1L,
                limit);
        assertEquals(0, overflowCloneCalls,
                "overflow must be rejected before defensive patch copying");
    }

    @Test
    void shouldVerifyEventLimitAcceptsExactBoundaryAndRejectsNextBeforeClone() {
        // given
        Fixture fixture = fixture();
        int limit = portableLimit(EVENT_LIMIT);
        CloneCountingNode overflow =
                new CloneCountingNode();
        overflow.value("overflow");

        // when
        for (int index = 0; index < limit; index++) {
            fixture.context.emitEvent(
                    new Node().value("event-" + index));
        }
        Throwable failure = captureFailure(
                () -> fixture.context.emitEvent(overflow));
        int overflowCloneCalls = overflow.cloneCalls;
        fixture.context.close();

        // then
        assertTrue(failure instanceof PortableLimitExceededException);
        assertLimitFailure(
                (PortableLimitExceededException) failure,
                ProcessorErrorCategory.InternalEventLimitExceeded,
                EVENT_LIMIT,
                limit + 1L,
                limit);
        assertEquals(0, overflowCloneCalls,
                "overflow must be rejected before defensive event cloning");
    }

    @Test
    void shouldVerifyRejectedPreviewBatchDoesNotTransferPreviewOwnership() {
        // given
        Fixture fixture = fixture();
        int limit = portableLimit(PATCH_LIMIT);
        fixture.context.applyFrozenPatches(
                frozenRemovals(0, limit));

        // when
        CloneCountingNode overflowValue =
                new CloneCountingNode();
        overflowValue.value("overflow");
        List<JsonPatch> overflow = Collections.singletonList(
                JsonPatch.add("/overflow", overflowValue));
        WorkingDocument.Preview preview;
        try (WorkingDocument working =
                     fixture.context.newWorkingDocument()) {
            preview = working.previewAndApplyPatches(overflow);
        }
        overflowValue.resetCloneCalls();
        Throwable failure = captureFailure(
                () -> fixture.context.applyPreviewedPatches(
                        overflow,
                        preview));
        Object retainedPatch = preview.patch(0);
        int overflowCloneCalls = overflowValue.cloneCalls;
        preview.close();
        fixture.context.close();

        // then
        assertTrue(failure instanceof PortableLimitExceededException);
        assertLimitFailure(
                (PortableLimitExceededException) failure,
                ProcessorErrorCategory.PatchLimitExceeded,
                PATCH_LIMIT,
                limit + 1L,
                limit);
        assertNotNull(retainedPatch,
                "rejected preview remains owned by the caller");
        assertEquals(0, overflowCloneCalls,
                "overflow must be rejected before previewed patch copying");
    }

    @Test
    void shouldVerifyPatchOverflowRollsBackAllHandlerEffectsAtProcessBoundary() {
        // given
        ProcessRollbackCase rollbackCase =
                processRollbackCase("patches");

        // when
        DocumentProcessingResult result =
                processOverflow(rollbackCase);

        // then
        assertProcessLevelRollback(
                rollbackCase,
                result,
                ProcessorErrorCategory.PatchLimitExceeded,
                PATCH_LIMIT);
    }

    @Test
    void shouldVerifyEventOverflowRollsBackAllHandlerEffectsAtProcessBoundary() {
        // given
        ProcessRollbackCase rollbackCase =
                processRollbackCase("events");

        // when
        DocumentProcessingResult result =
                processOverflow(rollbackCase);

        // then
        assertProcessLevelRollback(
                rollbackCase,
                result,
                ProcessorErrorCategory.InternalEventLimitExceeded,
                EVENT_LIMIT);
    }

    private ProcessRollbackCase processRollbackCase(String mode) {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerContractProcessor(
                new OverflowingResultProcessor());
        DocumentProcessorExactFeederSupport.install(blue);

        Node source = blue.yamlToNode(
                "name: Result Limit\n"
                        + "untouched: original\n"
                        + "contracts:\n"
                        + "  events:\n"
                        + "    type:\n"
                        + "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n"
                        + "  overflow:\n"
                        + "    channel: events\n"
                        + "    type:\n"
                        + "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n"
                        + "    propertyKey: " + mode + "\n"
                        + "    propertyValue: 1\n");
        DocumentProcessingResult initialization =
                blue.initializeDocument(source);
        Node input = initialization.document();
        return new ProcessRollbackCase(
                blue,
                mode,
                initialization.status(),
                input,
                input.toString());
    }

    private DocumentProcessingResult processOverflow(
            ProcessRollbackCase rollbackCase) {
        return rollbackCase.blue.processDocument(
                rollbackCase.input,
                new TestEvent()
                        .eventId("result-limit-"
                                + rollbackCase.mode)
                        .toNode());
    }

    private void assertProcessLevelRollback(
            ProcessRollbackCase rollbackCase,
            DocumentProcessingResult result,
            ProcessorErrorCategory category,
            String limitName) {
        int limit = portableLimit(limitName);
        assertEquals(ProcessorStatus.SUCCESS,
                rollbackCase.initializationStatus);
        assertEquals(ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                result.status());
        assertFalse(result.commits());
        assertEquals(rollbackCase.exactInput,
                result.document().toString(),
                "portable-limit rejection returns the exact PROCESS input");
        assertEquals(rollbackCase.exactInput,
                rollbackCase.input.toString(),
                "PROCESS must not mutate its caller-owned input");
        assertTrue(result.events().isEmpty(),
                "noncommitting rejection exposes no buffered Root events");
        assertNotNull(result.diagnostic());
        assertEquals(category,
                result.diagnostic().category());
        assertEquals(limitName,
                result.diagnostic().detail("limitName"));
        assertEquals(String.valueOf(limit + 1L),
                result.diagnostic().detail("observed"));
        assertEquals(String.valueOf(limit),
                result.diagnostic().detail("limit"));
    }

    private static final class ProcessRollbackCase {
        private final Blue blue;
        private final String mode;
        private final ProcessorStatus initializationStatus;
        private final Node input;
        private final String exactInput;

        private ProcessRollbackCase(
                Blue blue,
                String mode,
                ProcessorStatus initializationStatus,
                Node input,
                String exactInput) {
            this.blue = blue;
            this.mode = mode;
            this.initializationStatus = initializationStatus;
            this.input = input;
            this.exactInput = exactInput;
        }
    }

    private Fixture fixture() {
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        new DocumentProcessor(),
                        new Node());
        execution.preflightScope("/");
        ProcessorExecutionContext context =
                execution.createContext(
                        "/",
                        execution.bundleForScope("/"),
                        new Node(),
                        false);
        return new Fixture(context);
    }

    private static List<JsonPatch> mutablePatches(
            int start,
            int count) {
        List<JsonPatch> patches = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            int index = start + offset;
            patches.add(JsonPatch.add(
                    "/accepted-" + index,
                    new Node().value(index)));
        }
        return patches;
    }

    private static List<FrozenJsonPatch> frozenRemovals(
            int start,
            int count) {
        List<FrozenJsonPatch> patches =
                new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            patches.add(FrozenJsonPatch.remove(
                    "/accepted-" + (start + offset)));
        }
        return patches;
    }

    private static int portableLimit(String name) {
        return Math.toIntExact(
                GasSchedule.contracts10()
                        .portableLimit(name));
    }

    private static void assertLimitFailure(
            PortableLimitExceededException failure,
            ProcessorErrorCategory category,
            String limitName,
            long observed,
            long limit) {
        assertEquals(category,
                failure.diagnostic().category());
        assertEquals(limitName,
                failure.limitName());
        assertEquals(observed,
                failure.observed());
        assertEquals(limit,
                failure.limit());
    }

    private static final class Fixture {
        private final ProcessorExecutionContext context;

        private Fixture(
                ProcessorExecutionContext context) {
            this.context = context;
        }
    }

    private static final class CloneCountingNode extends Node {
        private int cloneCalls;

        @Override
        public Node clone() {
            cloneCalls++;
            return super.clone();
        }

        private void resetCloneCalls() {
            cloneCalls = 0;
        }
    }

    private static final class OverflowingResultProcessor
            implements HandlerProcessor<SetProperty> {

        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(
                SetProperty contract,
                ProcessorExecutionContext context) {
            int limit = portableLimit(
                    "events".equals(contract.getPropertyKey())
                            ? EVENT_LIMIT
                            : PATCH_LIMIT);
            if ("events".equals(contract.getPropertyKey())) {
                context.applyPatch(JsonPatch.add(
                        "/mustRollBack",
                        new Node().value(true)));
                for (int index = 0; index <= limit; index++) {
                    context.emitEvent(
                            new Node().value(
                                    "event-" + index));
                }
                return;
            }

            context.emitEvent(
                    new Node().value(
                            "must-not-be-public"));
            int firstBatchSize = limit / 2;
            context.applyPatches(
                    mutablePatches(
                            0,
                            firstBatchSize));
            context.applyPatches(
                    mutablePatches(
                            firstBatchSize,
                            limit - firstBatchSize + 1));
        }
    }
}
