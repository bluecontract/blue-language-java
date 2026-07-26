package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the slim handler context surface.
 */
final class ProcessorExecutionContextTest {

    @Test
    void documentHelpersExposeSnapshots() {
        Node document = new Node()
                .properties("value", new Node().value(1))
                .properties("nested", new Node().properties("inner", new Node().value("x")));

        DocumentProcessor owner = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, document.clone());
        execution.preflightScope("/");

        ProcessorExecutionContext context = execution.createContext("/", execution.bundleForScope("/"), new Node(), false);

        assertNull(context.contractKey());
        assertNull(context.contractNode());
        assertNull(context.frozenContractNode());

        Node snapshot = context.documentAt("/nested/inner");
        assertNotNull(snapshot);
        assertEquals("x", snapshot.getValue());

        Node missing = context.documentAt("/unknown");
        assertNull(missing);

        assertTrue(context.documentContains("/value"));
        assertFalse(context.documentContains("/value/missing"));

        // Ensure the returned node is a clone (mutation should not leak back).
        snapshot.value("mutated");
        Node reread = context.documentAt("/nested/inner");
        assertEquals("x", reread.getValue());
    }

    @Test
    void emitEventEnqueuesOneInvocationOccurrenceAndRecordsRootOutput() {
        DocumentProcessor owner = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, new Node());
        execution.preflightScope("/");
        ProcessorExecutionContext context = execution.createContext("/", execution.bundleForScope("/"), new Node(), false);

        context.emitEvent(new Node().value("payload"));
        context.applyBufferedEffects();

        assertEquals(1,
                execution.runtime().pendingEventOccurrenceCount());
        assertEquals(1,
                execution.runtime().rootEmissions().size());
        assertEquals("payload",
                execution.runtime().rootEmissions().get(0).getValue());
        assertTrue(execution.runtime().totalGas() >= 20L);
    }

    @Test
    void cutOffScopeRecordsBufferedPatchesAndEventsAsDiscarded() {
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

        context.applyPatch(JsonPatch.replace(
                "/child/x", new Node().value(1)));
        context.emitEvent(new Node().properties(
                "id", new Node().value("late")));
        execution.runtime().scope("/child");
        execution.markCutOff("/child");
        context.applyBufferedEffects();

        assertEquals("0", String.valueOf(
                execution.runtime().nodeAt(
                        "/child/x").getValue()));
        java.util.List<ProcessingTraceRecord> discarded =
                execution.runtime().conformanceTrace().records(
                        ProcessingTraceRecord.Kind.DISCARDED_EFFECT);
        assertEquals(2, discarded.size());
        assertEquals("/child/x",
                discarded.get(0).detail("label"));
        assertEquals("late",
                discarded.get(1).detail("label"));
    }

    @Test
    void invalidEmitEventAbortsBeforeQueueOrPortableGas() {
        DocumentProcessor owner = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, new Node());
        execution.preflightScope("/");
        long admittedBeforeEffects = execution.runtime().totalGas();
        ProcessorExecutionContext context = execution.createContext("/", execution.bundleForScope("/"), new Node(), false);
        Node invalidEvent = new Node()
                .value("payload")
                .properties("alsoPayload", new Node().value("invalid"));

        context.emitEvent(invalidEvent);
        assertThrows(RunTerminationException.class, context::applyBufferedEffects);

        assertEquals(0,
                execution.runtime().pendingEventOccurrenceCount());
        assertEquals(admittedBeforeEffects, execution.runtime().totalGas(),
                "invalid emission admits no gas beyond exact contract-recognition preflight");
        assertTrue(execution.runtime().rootEmissions().isEmpty(),
                "Runtime failure must not manufacture committed fatal events");
    }

    @Test
    void runtimeFailureDoesNotApplyBufferedEffectsOrAnonymousGas() {
        DocumentProcessor owner = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, new Node().properties("existing", new Node().value(1)));
        execution.preflightScope("/");
        long admittedBeforeEffects = execution.runtime().totalGas();
        ProcessorExecutionContext context = execution.createContext("/", execution.bundleForScope("/"), new Node(), false);

        context.applyPatch(JsonPatch.add("/x", new Node().value(7)));
        context.emitEvent(new Node().properties("message", new Node().value("queued before fatal")));
        assertThrows(UnsupportedOperationException.class,
                () -> context.consumeGas(123L));

        ProcessorFatalException ex = assertThrows(ProcessorFatalException.class,
                () -> context.throwFatal("fatal after partial work"));

        assertEquals("fatal after partial work", ex.getMessage());
        assertNotNull(ex.partialResult());
        assertEquals(ex.partialResult().totalGas(), ex.totalGas());
        assertEquals(admittedBeforeEffects, ex.totalGas(),
                "handler failure admits no gas beyond exact contract-recognition preflight");
        assertFalse(ex.partialResult().document().getProperties().containsKey("x"));
        assertTrue(ex.partialResult().triggeredEvents().isEmpty());
        assertNull(ex.partialResult().blueId(), "plain processor executions have no snapshot identity unless one is available");
    }

    @Test
    void executingHandlerContextExposesDefensiveContractSnapshot() {
        Node contract = new Node()
                .name("Probe Handler")
                .description("Captures execution context metadata")
                .properties("propertyKey", new Node().value("/x"));
        FrozenNode frozen = FrozenNode.fromResolvedNode(contract);
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(
                new DocumentProcessor(), new Node());
        execution.preflightScope("/");
        ProcessorExecutionContext context = execution.createContext(
                "/",
                execution.bundleForScope("/"),
                new Node(),
                "probe",
                frozen,
                false);

        assertEquals("probe", context.contractKey());
        Node contractNode = context.contractNode();
        assertNotNull(contractNode);
        assertEquals("Probe Handler", contractNode.getName());
        assertEquals("Captures execution context metadata", contractNode.getDescription());
        assertEquals("/x", contractNode.get("/propertyKey"));
        assertEquals("Probe Handler", context.frozenContractNode().toNode().getName());

        contractNode.name("Mutated");
        assertEquals("Probe Handler", context.contractNode().getName(),
                "contractNode() must return a defensive materialization");
    }
}
