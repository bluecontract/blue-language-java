package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the slim handler context surface.
 */
final class ProcessorExecutionContextTest {

    @Test
    void shouldVerifyDocumentHelpersExposeSnapshots() {
        // given
        Node document = new Node()
                .properties("value", new Node().value(1))
                .properties("nested", new Node().properties("inner", new Node().value("x")));

        DocumentProcessor owner = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, document.clone());
        execution.preflightScope("/");

        // when
        ProcessorExecutionContext context = execution.createContext("/", execution.bundleForScope("/"), new Node(), false);
        String contractKey = context.contractKey();
        Node contractNode = context.contractNode();
        FrozenNode frozenContractNode = context.frozenContractNode();
        Node snapshot = context.documentAt("/nested/inner");
        Object snapshotValue = snapshot.getValue();
        Node missing = context.documentAt("/unknown");
        boolean containsValue = context.documentContains("/value");
        boolean containsMissing =
                context.documentContains("/value/missing");
        snapshot.value("mutated");
        Node reread = context.documentAt("/nested/inner");

        // then
        assertNull(contractKey);
        assertNull(contractNode);
        assertNull(frozenContractNode);
        assertNotNull(snapshot);
        assertEquals("x", snapshotValue);
        assertNull(missing);
        assertTrue(containsValue);
        assertFalse(containsMissing);
        assertEquals("x", reread.getValue());
    }

    @Test
    void shouldEnqueueOneInvocationOccurrenceAndRecordRootOutputWhenEmittingEvent() {
        // given
        DocumentProcessor owner = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, new Node());
        execution.preflightScope("/");
        ProcessorExecutionContext context = execution.createContext("/", execution.bundleForScope("/"), new Node(), false);

        // when
        context.emitEvent(new Node().value("payload"));
        context.applyBufferedEffects();

        // then
        assertEquals(1,
                execution.runtime().pendingEventOccurrenceCount());
        assertEquals(1,
                execution.runtime().rootEmissions().size());
        assertEquals("payload",
                execution.runtime().rootEmissions().get(0).getValue());
        assertTrue(execution.runtime().totalGas() >= 20L);
    }

    @Test
    void shouldVerifyCutOffScopeRecordsBufferedPatchesAndEventsAsDiscarded() {
        // given
        Node document = new Node().properties(
                "child",
                new Node().properties(
                        "x", new Node().value(0)));
        ProcessorEngine.Execution execution =
                new ProcessorEngine.Execution(
                        new DocumentProcessor(), document);
        execution.preflightScope("/child");
        ProcessorExecutionContext context = execution.createContext(
                "/child",
                execution.bundleForScope("/child"),
                new Node(),
                false);

        // when
        context.applyPatch(JsonPatch.replace(
                "/child/x", new Node().value(1)));
        context.emitEvent(new Node().properties(
                "id", new Node().value("late")));
        execution.runtime().scope("/child");
        execution.markCutOff("/child");
        context.applyBufferedEffects();
        java.util.List<ProcessingTraceRecord> discarded =
                execution.runtime().conformanceTrace().records(
                        ProcessingTraceRecord.Kind.DISCARDED_EFFECT);

        // then
        assertEquals("0", String.valueOf(
                execution.runtime().nodeAt(
                        "/child/x").getValue()));
        assertEquals(2, discarded.size());
        assertEquals("/child/x",
                discarded.get(0).detail("label"));
        assertEquals("late",
                discarded.get(1).detail("label"));
    }

    @Test
    void shouldVerifyInvalidEmitEventAbortsBeforeQueueOrPortableGas() {
        // given
        DocumentProcessor owner = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, new Node());
        execution.preflightScope("/");
        long admittedBeforeEffects = execution.runtime().totalGas();
        ProcessorExecutionContext context = execution.createContext("/", execution.bundleForScope("/"), new Node(), false);
        Node invalidEvent = new Node()
                .value("payload")
                .properties("alsoPayload", new Node().value("invalid"));

        // when
        context.emitEvent(invalidEvent);
        RunTerminationException failure =
                FailureCapture.captureFailure(
                        context::applyBufferedEffects);

        // then
        assertNotNull(failure);
        assertEquals(0,
                execution.runtime().pendingEventOccurrenceCount());
        assertEquals(admittedBeforeEffects, execution.runtime().totalGas(),
                "invalid emission admits no gas beyond exact contract-recognition preflight");
        assertTrue(execution.runtime().rootEmissions().isEmpty(),
                "Runtime failure must not manufacture committed fatal events");
    }

    @Test
    void shouldVerifyRuntimeFailureDoesNotApplyBufferedEffects() {
        // given
        DocumentProcessor owner = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, new Node().properties("existing", new Node().value(1)));
        execution.preflightScope("/");
        long admittedBeforeEffects = execution.runtime().totalGas();
        ProcessorExecutionContext context = execution.createContext("/", execution.bundleForScope("/"), new Node(), false);

        context.applyPatch(JsonPatch.add("/x", new Node().value(7)));
        context.emitEvent(new Node().properties("message", new Node().value("queued before fatal")));

        // when
        ProcessorFatalException ex =
                FailureCapture.captureFailure(
                        () -> context.throwFatal(
                                "fatal after partial work"));

        // then
        assertNotNull(ex);
        assertEquals("fatal after partial work", ex.getMessage());
        assertNotNull(ex.partialResult());
        assertEquals(ex.partialResult().totalGas(), ex.totalGas());
        assertEquals(admittedBeforeEffects, ex.totalGas(),
                "handler failure admits no gas beyond exact contract-recognition preflight");
        assertFalse(ex.partialResult().document().getProperties().containsKey("x"));
        assertTrue(ex.partialResult().events().isEmpty());
    }

    @Test
    void shouldVerifySubmittedRuntimeLedgerSurvivesFatalWhileEffectsRollBack() {
        // given
        Node input = new Node().properties(
                "existing", new Node().value(1));
        ProcessorEngine.Execution execution =
                new ProcessorEngine.Execution(
                        new DocumentProcessor(), input.clone());
        execution.preflightScope("/");
        long admittedBeforeRuntime =
                execution.runtime().totalGas();
        ProcessorExecutionContext context =
                execution.createContext(
                        "/",
                        execution.bundleForScope("/"),
                        new Node(),
                        false);
        GasMeter.ChildGasLedger ledger =
                context.newRuntimeGasLedger(
                        "fatal-runtime",
                        Collections.singletonMap("step", 7L));
        ledger.charge(
                "step",
                2L,
                GasChargeContext.reason("before-fatal"));
        context.applyPatch(JsonPatch.add(
                "/notApplied", new Node().value(9)));
        context.emitEvent(new Node().value("not-emitted"));

        context.submitRuntimeGasLedger(ledger);
        long admittedAfterRuntime =
                execution.runtime().totalGas();

        // when
        ProcessorFatalException failure =
                FailureCapture.captureFailure(
                        () -> context.throwFatal(
                                "fatal after admitted runtime work"));
        java.util.List<GasTraceEntry> trace =
                execution.runtime().gasMeter().trace();
        GasTraceEntry admitted =
                trace.get(trace.size() - 1);

        // then
        assertNotNull(failure);
        assertEquals(
                admittedBeforeRuntime,
                admittedAfterRuntime,
                "submitted work remains staged until the processor finalizes the execution unit");
        assertEquals(
                admittedBeforeRuntime + 14L,
                failure.totalGas());
        assertEquals(
                input.toString(),
                failure.partialResult().document().toString());
        assertTrue(failure.partialResult().events().isEmpty());
        assertNull(execution.runtime().nodeAt("/notApplied"));
        assertTrue(execution.runtime().rootEmissions().isEmpty());

        assertEquals("fatal-runtime", admitted.namespace());
        assertEquals("step", admitted.counter());
        assertEquals(2L, admitted.quantity());
        assertEquals(14L, admitted.subtotal());
        assertEquals("before-fatal", admitted.reason());
    }

    @Test
    void shouldVerifySeveralRuntimeLedgersMergeOnceInCanonicalNamespaceOrder() {
        // given
        ProcessorEngine.Execution execution =
                new ProcessorEngine.Execution(
                        new DocumentProcessor(), new Node());
        execution.preflightScope("/");
        ProcessorExecutionContext context =
                execution.createContext(
                        "/",
                        execution.bundleForScope("/"),
                        new Node(),
                        false);
        GasMeter.ChildGasLedger first =
                context.newRuntimeGasLedger(
                        "first-runtime",
                        Collections.singletonMap("step", 1L));
        GasMeter.ChildGasLedger second =
                context.newRuntimeGasLedger(
                        "second-runtime",
                        Collections.singletonMap("step", 1L));
        first.charge("step", 1L);
        second.charge("step", 1L);

        context.submitRuntimeGasLedger(first);

        // when
        context.submitRuntimeGasLedger(second);
        IllegalStateException failure =
                FailureCapture.captureFailure(
                        () -> context.submitRuntimeGasLedger(
                                first));
        context.applyBufferedEffects();

        // then
        assertNotNull(failure);
        assertEquals(
                1L,
                execution.runtime().conformanceTrace()
                        .counterQuantity("first-runtime", "step"));
        assertEquals(
                1L,
                execution.runtime().conformanceTrace()
                        .counterQuantity("second-runtime", "step"));
    }

    @Test
    void shouldVerifyExecutingHandlerContextExposesDefensiveContractSnapshot() {
        // given
        Node contract = new Node()
                .name("Probe Handler")
                .description("Captures execution context metadata")
                .properties("propertyKey", new Node().value("/x"));
        FrozenNode frozen = FrozenNode.fromResolvedNode(contract);
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(
                new DocumentProcessor(), new Node());
        execution.preflightScope("/");
        // when
        ProcessorExecutionContext context = execution.createContext(
                "/",
                execution.bundleForScope("/"),
                new Node(),
                "probe",
                frozen,
                false);
        String contractKey = context.contractKey();
        Node contractNode = context.contractNode();
        FrozenNode frozenContractNode =
                context.frozenContractNode();
        String contractName = contractNode.getName();
        String contractDescription = contractNode.getDescription();
        Object propertyKey = contractNode.get("/propertyKey");
        contractNode.name("Mutated");
        Node reread = context.contractNode();

        // then
        assertEquals("probe", contractKey);
        assertNotNull(contractNode);
        assertEquals("Probe Handler", contractName);
        assertEquals(
                "Captures execution context metadata",
                contractDescription);
        assertEquals("/x", propertyKey);
        assertEquals(
                "Probe Handler",
                frozenContractNode.toNode().getName());
        assertEquals("Probe Handler", reread.getName(),
                "contractNode() must return a defensive materialization");
    }
}
