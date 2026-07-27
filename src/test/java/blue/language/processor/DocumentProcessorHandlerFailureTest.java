package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorHandlerFailureTest {

    private static final String FAILURE_RUNTIME =
            "handler-failure-runtime";
    private static final String FAILURE_STEP =
            "handlerStep";
    private static final long FAILURE_STEP_WEIGHT = 7L;

    @Test
    void handlerRuntimeExceptionRollsBackWithoutTerminationMarker() {
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode("name: Handler Failure\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    documentId: existing\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  fail:\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /throwWithoutPatch\n" +
                "    propertyValue: 1\n");

        String input = document.toString();
        ProcessingDebugResult debug =
                blue.getDocumentProcessor()
                        .processDocumentWithTrace(
                                document,
                                event("evt-handler-fail"));
        DocumentProcessingResult result =
                debug.processResult();

        assertFalse(isCapabilityFailure(result));
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertEquals(input, result.document().toString());
        assertFalse(result.document().getContracts()
                .getProperties().containsKey("terminated"));
        assertNull(nodeAt(result.document(), "/throwWithoutPatch"));
        assertTrue(result.events().isEmpty());
        assertTrue(result.totalGas() > 0L,
                "admitted work remains charged on deterministic failure");
        assertRuntimeLedgerPreserved(debug);
    }

    @Test
    void handlerThrowAfterBufferingPatchDoesNotApplyBufferedPatch() {
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode("name: Handler Buffer Failure\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    documentId: existing\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  fail:\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /shouldNotApply\n" +
                "    propertyValue: 2\n");

        String input = document.toString();
        ProcessingDebugResult debug =
                blue.getDocumentProcessor()
                        .processDocumentWithTrace(
                                document,
                                event("evt-buffer-fail"));
        DocumentProcessingResult result =
                debug.processResult();

        assertFalse(isCapabilityFailure(result));
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertEquals(input, result.document().toString());
        assertNull(nodeAt(result.document(), "/shouldNotApply"),
                "buffered effects from the failing handler must be discarded");
        assertFalse(result.document().getContracts()
                .getProperties().containsKey("terminated"));
        assertTrue(result.events().isEmpty());
        assertRuntimeLedgerPreserved(debug);
    }

    @Test
    void admittedRuntimeLedgerSurvivesLaterPatchFailure() {
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode(
                "name: Handler Patch Failure\n"
                        + "contracts:\n"
                        + "  initialized:\n"
                        + "    type:\n"
                        + "      blueId: "
                        + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER
                        + "\n"
                        + "    documentId: existing\n"
                        + "  events:\n"
                        + "    type:\n"
                        + "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n"
                        + "  fail:\n"
                        + "    channel: events\n"
                        + "    type:\n"
                        + "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n"
                        + "    propertyKey: /invalidLaterPatch\n"
                        + "    propertyValue: -999\n");
        String input = document.toString();

        ProcessingDebugResult debug =
                blue.getDocumentProcessor()
                        .processDocumentWithTrace(
                                document,
                                event("evt-patch-fail"));
        DocumentProcessingResult result =
                debug.processResult();

        assertEquals(
                ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertEquals(input, result.document().toString());
        assertTrue(result.events().isEmpty());
        assertNull(nodeAt(
                result.document(),
                "/contracts/checkpoint"));
        assertRuntimeLedgerPreserved(debug);

        long runtimeSequence =
                debug.trace().gas().stream()
                        .filter(entry ->
                                FAILURE_RUNTIME.equals(
                                        entry.namespace()))
                        .findFirst()
                        .orElseThrow(AssertionError::new)
                        .sequence();
        debug.trace().gas().stream()
                .filter(entry ->
                        "processor".equals(entry.namespace())
                                && ("patchBoundaryChecked".equals(
                                        entry.counter())
                                || "patchAddOrReplace".equals(
                                        entry.counter())))
                .forEach(entry ->
                        assertTrue(
                                runtimeSequence < entry.sequence(),
                                "runtime ledger must precede application-effect work"));
    }

    @Test
    void handlerFailureRollsBackPriorHandlerEffects() {
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode("name: Handler Prior Effects\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    documentId: existing\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  first:\n" +
                "    order: 0\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /prior\n" +
                "    propertyValue: 7\n" +
                "  fail:\n" +
                "    order: 1\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /shouldNotApply\n" +
                "    propertyValue: 9\n");

        String input = document.toString();
        DocumentProcessingResult result =
                blue.processDocument(
                        document,
                        event("evt-prior-preserved"));

        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertEquals(input, result.document().toString());
        assertNull(nodeAt(result.document(), "/prior"));
        assertNull(nodeAt(result.document(), "/shouldNotApply"));
        assertFalse(result.document().getContracts()
                .getProperties().containsKey("terminated"));
        assertTrue(result.events().isEmpty());
    }

    private Blue blueWithThrowingProcessor() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerContractProcessor(new ConditionalThrowingSetPropertyProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
        return blue;
    }

    private Node event(String id) {
        return new TestEvent().eventId(id).toNode();
    }

    private Node nodeAt(Node document, String pointer) {
        try {
            return document.getNode(pointer);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void assertRuntimeLedgerPreserved(
            ProcessingDebugResult debug) {
        assertEquals(
                1L,
                debug.trace().counterQuantity(
                        FAILURE_RUNTIME,
                        FAILURE_STEP));
        GasTraceEntry entry =
                debug.trace().gas().stream()
                        .filter(candidate ->
                                FAILURE_RUNTIME.equals(
                                        candidate.namespace())
                                        && FAILURE_STEP.equals(
                                        candidate.counter()))
                        .findFirst()
                        .orElseThrow(AssertionError::new);
        assertEquals(FAILURE_STEP_WEIGHT, entry.weight());
        assertEquals(FAILURE_STEP_WEIGHT, entry.subtotal());
        long tracedTotal =
                debug.trace().gas().stream()
                        .mapToLong(GasTraceEntry::subtotal)
                        .sum();
        assertEquals(
                tracedTotal,
                debug.processResult().totalGas());
    }

    private static final class ConditionalThrowingSetPropertyProcessor implements HandlerProcessor<SetProperty> {
        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            GasMeter.ChildGasLedger ledger =
                    context.newRuntimeGasLedger(
                            FAILURE_RUNTIME,
                            Collections.singletonMap(
                                    FAILURE_STEP,
                                    FAILURE_STEP_WEIGHT));
            ledger.charge(
                    FAILURE_STEP,
                    1L,
                    GasChargeContext.reason(
                            "before-handler-result"));
            context.submitRuntimeGasLedger(ledger);
            String propertyKey = contract.getPropertyKey() != null ? contract.getPropertyKey() : "/x";
            if ("/throwWithoutPatch".equals(propertyKey)) {
                throw new IllegalArgumentException("handler failed before buffering effects");
            }
            String patchPath =
                    contract.getPropertyValue() == -999
                            ? "/contracts/checkpoint"
                            : context.resolvePointer(
                                    propertyKey);
            JsonPatch patch = JsonPatch.add(
                    patchPath,
                    new Node().value(
                            contract.getPropertyValue()));
            context.applyPatch(patch);
            if ("/shouldNotApply".equals(propertyKey)) {
                throw new IllegalArgumentException("handler failed after buffering effects");
            }
        }
    }
}
