package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorHandlerFailureTest {

    private static final String FAILURE_RUNTIME =
            "handler-failure-runtime";
    private static final String FAILURE_STEP =
            "handlerStep";
    private static final long FAILURE_STEP_WEIGHT = 7L;
    private static final String EXISTING_DOCUMENT_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    new Node().value("existing"));

    @Test
    void shouldVerifyHandlerRuntimeExceptionRollsBackWithoutTerminationMarker() {
        // given
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode("name: Handler Failure\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    document:\n" +
                "      blueId: " + EXISTING_DOCUMENT_BLUE_ID + "\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  fail:\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /throwWithoutPatch\n" +
                "    propertyValue: 1\n");

        // when
        String input = document.toString();
        ProcessingDebugResult debug =
                blue.getDocumentProcessor()
                        .processDocumentWithTrace(
                                document,
                                event("evt-handler-fail"));
        DocumentProcessingResult result =
                debug.processResult();

        // then
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
    void shouldVerifyHandlerThrowAfterBufferingPatchDoesNotApplyBufferedPatch() {
        // given
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode("name: Handler Buffer Failure\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    document:\n" +
                "      blueId: " + EXISTING_DOCUMENT_BLUE_ID + "\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  fail:\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /shouldNotApply\n" +
                "    propertyValue: 2\n");

        // when
        String input = document.toString();
        ProcessingDebugResult debug =
                blue.getDocumentProcessor()
                        .processDocumentWithTrace(
                                document,
                                event("evt-buffer-fail"));
        DocumentProcessingResult result =
                debug.processResult();

        // then
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
    void shouldVerifyAdmittedRuntimeLedgerSurvivesLaterPatchFailure() {
        // given
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode(
                "name: Handler Patch Failure\n"
                        + "contracts:\n"
                        + "  initialized:\n"
                        + "    type:\n"
                        + "      blueId: "
                        + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER
                        + "\n"
                        + "    document:\n"
                        + "      blueId: "
                        + EXISTING_DOCUMENT_BLUE_ID
                        + "\n"
                        + "  events:\n"
                        + "    type:\n"
                        + "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n"
                        + "  fail:\n"
                        + "    channel: events\n"
                        + "    type:\n"
                        + "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n"
                        + "    propertyKey: /invalidLaterPatch\n"
                        + "    propertyValue: -999\n");
        String input = document.toString();

        // when
        ProcessingDebugResult debug =
                blue.getDocumentProcessor()
                        .processDocumentWithTrace(
                                document,
                                event("evt-patch-fail"));
        DocumentProcessingResult result =
                debug.processResult();
        long runtimeSequence =
                debug.trace().gas().stream()
                        .filter(entry ->
                                FAILURE_RUNTIME.equals(
                                        entry.namespace()))
                        .findFirst()
                        .map(GasTraceEntry::sequence)
                        .orElse(-1L);
        boolean runtimePrecedesApplicationWork =
                debug.trace().gas().stream()
                        .filter(entry ->
                                "processor".equals(entry.namespace())
                                        && ("patchBoundaryChecked".equals(
                                        entry.counter())
                                        || "patchAddOrReplace".equals(
                                        entry.counter())))
                        .allMatch(entry ->
                                runtimeSequence < entry.sequence());

        // then
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
        assertTrue(runtimeSequence >= 0L);
        assertTrue(runtimePrecedesApplicationWork,
                "runtime ledger must precede application-effect work");
    }

    @Test
    void shouldVerifyHandlerFailureRollsBackPriorHandlerEffects() {
        // given
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode("name: Handler Prior Effects\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    document:\n" +
                "      blueId: " + EXISTING_DOCUMENT_BLUE_ID + "\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  first:\n" +
                "    order: 0\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /prior\n" +
                "    propertyValue: 7\n" +
                "  fail:\n" +
                "    order: 1\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /shouldNotApply\n" +
                "    propertyValue: 9\n");

        // when
        String input = document.toString();
        DocumentProcessingResult result =
                blue.processDocument(
                        document,
                        event("evt-prior-preserved"));

        // then
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

    @Test
    void shouldVerifyHostedChildExhaustionUsesCanonicalStatusAndRetainsExactPrefix() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerContractProcessor(
                new ExhaustingSetPropertyProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
        Node document = blue.yamlToNode(
                "name: Hosted Gas Exhaustion\n"
                        + "contracts:\n"
                        + "  initialized:\n"
                        + "    type:\n"
                        + "      blueId: "
                        + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER
                        + "\n"
                        + "    document:\n"
                        + "      blueId: "
                        + EXISTING_DOCUMENT_BLUE_ID
                        + "\n"
                        + "  events:\n"
                        + "    type:\n"
                        + "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n"
                        + "  exhaust:\n"
                        + "    channel: events\n"
                        + "    type:\n"
                        + "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n"
                        + "    propertyKey: /neverApplied\n"
                        + "    propertyValue: 1\n");
        String input = document.toString();

        // when
        ProcessingDebugResult first =
                blue.getDocumentProcessor()
                        .processDocumentWithTrace(
                                document.clone(),
                                event("evt-hosted-gas"));
        ProcessingDebugResult second =
                blue.getDocumentProcessor()
                        .processDocumentWithTrace(
                                document.clone(),
                                event("evt-hosted-gas"));
        List<GasTraceEntry> hosted =
                first.trace().gas().stream()
                        .filter(entry ->
                                "hosted-exhaustion"
                                        .equals(
                                                entry.namespace()))
                        .collect(Collectors.toList());
        GasTraceEntry admitted = hosted.size() == 1
                ? hosted.get(0) : null;
        String admittedCounter = admitted == null
                ? null : admitted.counter();
        long admittedQuantity = admitted == null
                ? -1L : admitted.quantity();
        long admittedSubtotal = admitted == null
                ? -1L : admitted.subtotal();
        ProcessorDiagnostic diagnostic =
                first.processResult().diagnostic();

        // then
        assertEquals(
                ProcessorStatus.GAS_LIMIT_EXCEEDED,
                first.processResult().status());
        assertFalse(first.processResult().commits());
        assertEquals(
                input,
                first.processResult()
                        .document().toString());
        assertTrue(
                first.processResult()
                        .events().isEmpty());
        assertNull(nodeAt(
                first.processResult().document(),
                "/neverApplied"));
        assertEquals(
                first.processResult().totalGas(),
                first.trace().gas().stream()
                        .mapToLong(
                                GasTraceEntry::subtotal)
                        .sum());
        assertEquals(1, hosted.size());
        assertEquals(
                "iteration",
                admittedCounter);
        assertTrue(admittedQuantity > 0L);
        assertNotNull(diagnostic);
        assertEquals(
                "hosted-exhaustion",
                diagnostic.details()
                        .get("namespace"));
        assertEquals(
                "iteration",
                diagnostic.details()
                        .get("counter"));
        assertEquals(
                "1",
                diagnostic.details()
                        .get("quantity"));
        assertEquals(
                "1",
                diagnostic.details()
                        .get("weight"));
        assertEquals(
                Long.toString(
                        admittedSubtotal),
                diagnostic.details()
                        .get("admittedGas"));
        assertEquals(
                diagnostic.details().get(
                        "gasLimit"),
                diagnostic.details().get(
                        "effectiveBudget"));

        assertEquals(
                first.processResult().status(),
                second.processResult().status());
        assertEquals(
                first.processResult().totalGas(),
                second.processResult().totalGas());
        assertEquals(
                traceFingerprint(first.trace().gas()),
                traceFingerprint(second.trace().gas()));
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

    private List<String> traceFingerprint(
            List<GasTraceEntry> trace) {
        return trace.stream()
                .map(entry ->
                        entry.namespace()
                                + "|"
                                + entry.counter()
                                + "|"
                                + entry.quantity()
                                + "|"
                                + entry.weight()
                                + "|"
                                + entry.reason())
                .collect(Collectors.toList());
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

    private static final class ExhaustingSetPropertyProcessor
            implements HandlerProcessor<SetProperty> {
        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(
                SetProperty contract,
                ProcessorExecutionContext context) {
            GasMeter.ChildGasLedger ledger =
                    context.runtimeWorkSession()
                            .openLedger(
                                    "hosted-exhaustion",
                                    Collections.singletonMap(
                                            "iteration", 1L));
            ledger.charge(
                    "iteration",
                    ledger.effectiveBudget(),
                    GasChargeContext.reason(
                            "admitted-prefix"));
            try {
                ledger.charge(
                        "iteration",
                        1L,
                        GasChargeContext.reason(
                                "rejected"));
            } catch (GasLimitExceededException exhaustion) {
                context.runtimeWorkSession()
                        .propagateGasExhaustion(
                                RuntimeGasExhaustion
                                        .from(exhaustion));
            }
            throw new AssertionError(
                    "work continued after rejected hosted charge");
        }
    }
}
