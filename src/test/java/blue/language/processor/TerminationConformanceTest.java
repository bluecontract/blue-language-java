package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.TerminateScopeContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Blue Contracts termination transition through public processing results.
 */
final class TerminationConformanceTest {

    private static final String TEST_EVENT_CHANNEL = "BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L";
    private static final String TERMINATE_SCOPE = "AZNvNsADqpp7ZwAgpQyaQSz4cq3o3RMHZtB3sgDfudD4";
    private static final String SET_PROPERTY = "8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts";
    private static final String LIFECYCLE_CHANNEL = RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL;

    @Test
    void gracefulTerminationVisitsAllLifecycleChannelsInOrder() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful",
                lifecycleHandler("firstLifecycle", 1, "/first"),
                lifecycleHandler("secondLifecycle", 2, "/second")))).document();

        DocumentProcessingResult result = blue.processDocument(initialized, testEvent("all-lifecycle"));

        assertEquals(Arrays.asList("/first", "/second"), observed);
        assertEquals(new BigInteger("1"), nodeAt(result.document(), "/first").getValue());
        assertEquals(new BigInteger("2"), nodeAt(result.document(), "/second").getValue());
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertTerminationEventSequence(result.triggeredEvents(), RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED);
    }

    @Test
    void fatalTerminationVisitsAllLifecycleChannelsInOrder() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("fatal",
                lifecycleHandler("firstLifecycle", 1, "/first"),
                lifecycleHandler("secondLifecycle", 2, "/second")))).document();

        DocumentProcessingResult result = blue.processDocument(initialized, testEvent("fatal-lifecycle"));

        assertEquals(Arrays.asList("/first", "/second"), observed);
        assertEquals(new BigInteger("1"), nodeAt(result.document(), "/first").getValue());
        assertEquals(new BigInteger("2"), nodeAt(result.document(), "/second").getValue());
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertTerminationEventSequence(result.triggeredEvents(),
                RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED,
                RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR);
    }

    @Test
    void reentrantGracefulRequestPreservesFirstCauseAndEarlierEffects() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful",
                lifecycleHandler("reentrantLifecycle", 1, "/reentrant"),
                lifecycleHandler("secondLifecycle", 2, "/after")))).document();

        DocumentProcessingResult result = blue.processDocument(initialized, testEvent("reentrant"));

        assertEquals(Arrays.asList("/reentrant", "/after"), observed);
        assertEquals(new BigInteger("1"), nodeAt(result.document(), "/reentrant").getValue());
        assertEquals(new BigInteger("2"), nodeAt(result.document(), "/after").getValue());
        Node marker = result.document().getAsNode("/contracts/terminated");
        assertEquals("graceful", marker.getAsText("/cause"));
        assertEquals("first", marker.getAsText("/reason"));
        assertEquals(1, countEvents(result.triggeredEvents(), RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED));
        assertEquals(0, countEvents(result.triggeredEvents(), RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR));
    }

    @Test
    void reentrantFatalRequestPreservesTheFirstGracefulTermination() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful",
                lifecycleHandler("firstLifecycle", 1, "/reentrantFatal"),
                lifecycleHandler("secondLifecycle", 2, "/after")))).document();

        DocumentProcessingResult result = blue.processDocument(initialized, testEvent("reentrant-fatal"));

        assertEquals(Arrays.asList("/reentrantFatal", "/after"), observed);
        assertEquals(new BigInteger("1"), nodeAt(result.document(), "/reentrantFatal").getValue());
        assertEquals(new BigInteger("2"), nodeAt(result.document(), "/after").getValue());
        Node marker = result.document().getAsNode("/contracts/terminated");
        assertEquals("graceful", marker.getAsText("/cause"));
        assertEquals("first", marker.getAsText("/reason"));
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertTerminationEventSequence(result.triggeredEvents(), RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED);
    }

    @Test
    void terminationLifecyclePatchRunsImmediateDocumentUpdateCascade() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        String document = terminationDocument("graceful", lifecycleHandler("lifecycle", 1, "/lifecycleEffect"))
                + "  documentUpdates:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n"
                + "    path: /\n"
                + "  ordinaryHandler:\n"
                + "    channel: documentUpdates\n"
                + "    type:\n"
                + "      blueId: " + SET_PROPERTY + "\n"
                + "    propertyKey: /ordinary\n"
                + "    propertyValue: 9\n";
        Node initialized = blue.initializeDocument(blue.yamlToNode(document)).document();
        observed.clear();

        DocumentProcessingResult result = blue.processDocument(initialized, testEvent("ordinary-cutoff"));

        assertEquals(Arrays.asList("/lifecycleEffect", "/ordinary"), observed);
        assertEquals(new BigInteger("1"), nodeAt(result.document(), "/lifecycleEffect").getValue());
        assertNull(nodeOrNull(result.document(), "/ordinary"));
    }

    @Test
    void childTerminationLifecycleEmissionRemainsBridgeable() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node document = blue.yamlToNode("name: Parent\n"
                + "child:\n"
                + "  name: Child\n"
                + "  contracts:\n"
                + "    lifecycle:\n"
                + "      type:\n"
                + "        blueId: " + LIFECYCLE_CHANNEL + "\n"
                + "    emitter:\n"
                + "      channel: lifecycle\n"
                + "      type:\n"
                + "        blueId: " + SET_PROPERTY + "\n"
                + "      propertyKey: /emitLifecycle\n"
                + "      propertyValue: 1\n");
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(blue.getDocumentProcessor(), document);
        execution.loadBundles("/child");

        execution.enterGracefulTermination("/child", execution.bundleForScope("/child"), "child graceful");

        assertEquals(Arrays.asList("/emitLifecycle"), observed);
        List<Node> bridgeable = execution.runtime().scope("/child").drainBridgeableEvents();
        assertEquals(2, bridgeable.size());
        assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED, bridgeable.get(0).getType().getBlueId());
        assertEquals("termination-lifecycle", bridgeable.get(1).getAsText("/kind"));
    }

    @Test
    void terminationLifecycleEmissionFifoIsClearedBeforeDrain() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        String document = terminationDocument("graceful", lifecycleHandler("lifecycle", 1, "/emitTriggered"))
                + "  triggered:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL + "\n"
                + "  triggeredHandler:\n"
                + "    channel: triggered\n"
                + "    type:\n"
                + "      blueId: " + SET_PROPERTY + "\n"
                + "    propertyKey: /triggeredDrained\n"
                + "    propertyValue: 1\n";
        Node initialized = blue.initializeDocument(blue.yamlToNode(document)).document();

        DocumentProcessingResult result = blue.processDocument(initialized, testEvent("fifo-clear"));

        assertEquals(Arrays.asList("/emitTriggered"), observed);
        assertEquals(2, result.triggeredEvents().size());
        assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED,
                result.triggeredEvents().get(0).getType().getBlueId());
        assertEquals("termination-lifecycle-emission",
                result.triggeredEvents().get(1).getAsText("/eventId"));
    }

    @Test
    void explicitInitializationTerminationDoesNotWriteInitializedMarker() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        String document = "name: Initialization Termination\n"
                + "contracts:\n"
                + "  lifecycle:\n"
                + "    type:\n"
                + "      blueId: " + LIFECYCLE_CHANNEL + "\n"
                + "  initializationTermination:\n"
                + "    channel: lifecycle\n"
                + "    type:\n"
                + "      blueId: " + SET_PROPERTY + "\n"
                + "    propertyKey: /terminateOnInitialize\n"
                + "    propertyValue: 1\n";

        DocumentProcessingResult result = blue.initializeDocument(blue.yamlToNode(document));

        assertEquals(Arrays.asList("/terminateOnInitialize"), observed);
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals("graceful", result.document().getAsNode("/contracts/terminated").getAsText("/cause"));
        assertNull(nodeOrNull(result.document(), "/contracts/initialized"));
        assertTerminationEventSequence(result.triggeredEvents(),
                RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED,
                RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED);
    }

    @Test
    void implicitInitializationTerminationStopsTheExternalPhase() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        String document = "name: Implicit Initialization Termination\n"
                + "contracts:\n"
                + "  events:\n"
                + "    type:\n"
                + "      blueId: " + TEST_EVENT_CHANNEL + "\n"
                + "  externalHandler:\n"
                + "    channel: events\n"
                + "    type:\n"
                + "      blueId: " + SET_PROPERTY + "\n"
                + "    propertyKey: /external\n"
                + "    propertyValue: 1\n"
                + "  lifecycle:\n"
                + "    type:\n"
                + "      blueId: " + LIFECYCLE_CHANNEL + "\n"
                + "  initializationTermination:\n"
                + "    channel: lifecycle\n"
                + "    type:\n"
                + "      blueId: " + SET_PROPERTY + "\n"
                + "    propertyKey: /terminateOnInitialize\n"
                + "    propertyValue: 1\n";

        DocumentProcessingResult result = blue.processDocument(blue.yamlToNode(document), testEvent("implicit-init"));

        assertEquals(Arrays.asList("/terminateOnInitialize"), observed);
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals("graceful", result.document().getAsNode("/contracts/terminated").getAsText("/cause"));
        assertNull(nodeOrNull(result.document(), "/contracts/initialized"));
        assertNull(nodeOrNull(result.document(), "/external"));
        assertTerminationEventSequence(result.triggeredEvents(),
                RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED,
                RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED);
    }

    @Test
    void terminationPreventsCheckpointAdvancementButRetainsLazyCheckpoint() {
        Blue blue = blueWithLifecycleProbe(new ArrayList<String>());
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful"))).document();

        DocumentProcessingResult result = blue.processDocument(initialized, testEvent("checkpoint-cutoff"));

        assertNotNull(nodeOrNull(result.document(), "/contracts/checkpoint"));
        Node lastEvents = nodeOrNull(result.document(), "/contracts/checkpoint/lastEvents");
        assertNotNull(lastEvents);
        assertNotNull(lastEvents.getProperties());
        assertTrue(lastEvents.getProperties().isEmpty());
    }

    @Test
    void successfulGracefulTerminationHasNoFailureReason() {
        Blue blue = blueWithLifecycleProbe(new ArrayList<String>());
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful"))).document();

        DocumentProcessingResult result = blue.processDocument(initialized, testEvent("graceful-result"));

        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertNull(result.errorCategory());
        assertNull(result.failureReason());
        assertEquals("first", result.document().getAsNode("/contracts/terminated").getAsText("/reason"));
    }

    @Test
    void earlierChildEscalationDoesNotOverrideLaterRootFatalDiagnostic() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node document = blue.yamlToNode("name: Parent\n"
                + "contracts: {}\n"
                + "child:\n"
                + "  name: Child\n"
                + "  contracts:\n"
                + "    lifecycle:\n"
                + "      type:\n"
                + "        blueId: " + LIFECYCLE_CHANNEL + "\n"
                + "    failingHandler:\n"
                + "      channel: lifecycle\n"
                + "      type:\n"
                + "        blueId: " + SET_PROPERTY + "\n"
                + "      propertyKey: /failing\n"
                + "      propertyValue: 1\n");
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(blue.getDocumentProcessor(), document);
        execution.loadBundles("/child");
        execution.enterGracefulTermination("/child", execution.bundleForScope("/child"), "child graceful");

        assertThrows(RunTerminationException.class,
                () -> execution.enterFatalTermination("/", null, ProcessorErrorCategory.GasError, "later root fatal"));

        DocumentProcessingResult result = execution.result();
        assertEquals(Arrays.asList("/failing"), observed);
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(ProcessorErrorCategory.GasError, result.errorCategory());
        assertEquals("later root fatal", result.failureReason());
    }

    @Test
    void rootGracefulReasonDoesNotMaskChildFatalDiagnostic() {
        Blue blue = ProcessorTestSupport.blue();
        Node document = new Node()
                .name("Parent")
                .contracts(new Node())
                .properties("child", new Node().name("Child"));
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(blue.getDocumentProcessor(), document);

        execution.enterFatalTermination("/child",
                null,
                ProcessorErrorCategory.BoundaryViolation,
                "child fatal");
        assertThrows(RunTerminationException.class,
                () -> execution.enterGracefulTermination("/", null, "root graceful"));

        DocumentProcessingResult result = execution.result();
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(ProcessorErrorCategory.BoundaryViolation, result.errorCategory());
        assertEquals("child fatal", result.failureReason());
        assertEquals("root graceful", result.document().getAsNode("/contracts/terminated").getAsText("/reason"));
    }

    @Test
    void earlierBufferedFailurePreventsQueuedGracefulTermination() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new FailingBeforeTerminationProcessor());
        Node initialized = blue.initializeDocument(blue.yamlToNode("name: Buffered Failure\n"
                + "contracts:\n"
                + "  events:\n"
                + "    type:\n"
                + "      blueId: " + TEST_EVENT_CHANNEL + "\n"
                + "  prior:\n"
                + "    order: 0\n"
                + "    channel: events\n"
                + "    type:\n"
                + "      blueId: " + SET_PROPERTY + "\n"
                + "    propertyKey: /prior\n"
                + "    propertyValue: 1\n"
                + "  invalidThenTerminate:\n"
                + "    order: 1\n"
                + "    channel: events\n"
                + "    type:\n"
                + "      blueId: " + SET_PROPERTY + "\n"
                + "    propertyKey: /invalidThenTerminate\n"
                + "    propertyValue: 2\n")).document();

        DocumentProcessingResult result = blue.processDocument(initialized, testEvent("buffered-failure"));

        assertEquals(new BigInteger("1"), nodeAt(result.document(), "/prior").getValue());
        assertNull(nodeOrNull(result.document(), "/invalidThenTerminate"));
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals("fatal", result.document().getAsNode("/contracts/terminated").getAsText("/cause"));
        assertTerminationEventSequence(result.triggeredEvents(),
                RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED,
                RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR);
    }

    @Test
    void rootEscalationCategoryAndReasonComeFromSameRecord() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful",
                lifecycleHandler("aFirstLifecycle", 1, "/first"),
                lifecycleHandler("bFailingLifecycle", 2, "/failing"),
                lifecycleHandler("cThirdLifecycle", 3, "/third")))).document();

        DocumentProcessingResult result = blue.processDocument(initialized, testEvent("escalation"));

        assertEquals(Arrays.asList("/first", "/failing"), observed);
        assertEquals(new BigInteger("1"), nodeAt(result.document(), "/first").getValue());
        assertNull(nodeOrNull(result.document(), "/failing"), "failing handler effects must be discarded");
        assertNull(nodeOrNull(result.document(), "/third"), "later lifecycle channels must not run");
        Node marker = result.document().getAsNode("/contracts/terminated");
        assertEquals("graceful", marker.getAsText("/cause"));
        assertEquals("first", marker.getAsText("/reason"));
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(ProcessorErrorCategory.HandlerExecutionError, result.errorCategory());
        assertEquals("termination lifecycle handler failed", result.failureReason());
        assertTerminationEventSequence(result.triggeredEvents(),
                RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED,
                RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR);
        assertEquals(1, countEvents(result.triggeredEvents(), RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR));
    }

    @Test
    void fatalDuringChildTerminationStaysScopedAndRetainsTerminationBridge() {
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node document = blue.yamlToNode("name: Parent\n"
                + "child:\n"
                + "  name: Child\n"
                + "  contracts:\n"
                + "    lifecycle:\n"
                + "      type:\n"
                + "        blueId: " + LIFECYCLE_CHANNEL + "\n"
                + "    failingHandler:\n"
                + "      channel: lifecycle\n"
                + "      type:\n"
                + "        blueId: " + SET_PROPERTY + "\n"
                + "      propertyKey: /failing\n"
                + "      propertyValue: 1\n");
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(blue.getDocumentProcessor(), document);
        execution.loadBundles("/child");

        execution.enterGracefulTermination("/child", execution.bundleForScope("/child"), "first");

        DocumentProcessingResult result = execution.result();
        assertEquals(Arrays.asList("/failing"), observed);
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(ProcessorErrorCategory.HandlerExecutionError, result.errorCategory());
        assertNull(nodeOrNull(result.document(), "/contracts/terminated"));
        assertEquals("graceful", nodeAt(result.document(), "/child/contracts/terminated/cause").getValue());
        assertTrue(result.triggeredEvents().isEmpty(), "A child escalation must not create root fatal evidence");
        List<Node> bridgeable = execution.runtime().scope("/child").drainBridgeableEvents();
        assertTerminationEventSequence(bridgeable, RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED);
    }

    @Test
    void rootMalformedContractsUsesSingleFallbackWrite() {
        Blue blue = ProcessorTestSupport.blue();
        Node document = new Node().name("Malformed Root").contracts(new Node().value("not-an-object"));
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(blue.getDocumentProcessor(), document);

        assertThrows(RunTerminationException.class,
                () -> execution.enterGracefulTermination("/", null, "fallback"));

        DocumentProcessingResult result = execution.result();
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals("graceful", result.document().getAsNode("/contracts/terminated").getAsText("/cause"));
        assertEquals(50L, result.totalGas());
    }

    @Test
    void childMalformedContractsFallbackReplacesOnlyChildContractsAndPreservesCheckpoint() {
        Blue blue = ProcessorTestSupport.blue();
        Node checkpoint = new Node().properties("lastEvents", new Node().properties("events", new Node().value("kept")));
        Node malformedChildContracts = new Node()
                .value("not-an-object")
                .properties("checkpoint", checkpoint)
                .properties("ordinaryContract", new Node().value("drop"));
        Node document = new Node()
                .name("Parent")
                .contracts(new Node().properties("rootOnly", new Node().value("preserve")))
                .properties("child", new Node().name("Child").contracts(malformedChildContracts));
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(blue.getDocumentProcessor(), document);

        execution.enterGracefulTermination("/child", null, "child fallback");

        DocumentProcessingResult result = execution.result();
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals("preserve", nodeAt(result.document(), "/contracts/rootOnly").getValue());
        assertNull(nodeOrNull(result.document(), "/contracts/terminated"));
        assertEquals("graceful", nodeAt(result.document(), "/child/contracts/terminated/cause").getValue());
        assertEquals("kept", nodeAt(result.document(), "/child/contracts/checkpoint/lastEvents/events").getValue());
        assertNull(nodeOrNull(result.document(), "/child/contracts/ordinaryContract"));
    }

    @Test
    void fallbackFailureReturnsLastValidStateWithTerminationError() {
        Node invalidUnrelatedContent = new Node()
                .value("invalid")
                .properties("alsoInvalid", new Node().value("content"));
        Node document = new Node()
                .name("Broken Fallback")
                .contracts(new Node().value("malformed"))
                .properties("unrelated", invalidUnrelatedContent);
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(new DocumentProcessor(), document);

        assertThrows(RunTerminationException.class,
                () -> execution.enterGracefulTermination("/", null, "cannot write"));

        DocumentProcessingResult result = execution.result();
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(ProcessorErrorCategory.TerminationError, result.errorCategory());
        assertEquals("malformed", result.document().getContracts().getValue());
        assertNull(nodeOrNull(result.document(), "/contracts/terminated"));
        assertFalse(result.failureReason().isEmpty());
    }

    private Blue blueWithLifecycleProbe(List<String> observed) {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new TerminateScopeContractProcessor());
        blue.registerContractProcessor(new LifecycleProbeProcessor(observed));
        return blue;
    }

    private String terminationDocument(String mode, String... lifecycleHandlers) {
        StringBuilder yaml = new StringBuilder("name: Termination Conformance\n")
                .append("contracts:\n")
                .append("  events:\n")
                .append("    type:\n")
                .append("      blueId: ").append(TEST_EVENT_CHANNEL).append("\n")
                .append("  terminate:\n")
                .append("    channel: events\n")
                .append("    type:\n")
                .append("      blueId: ").append(TERMINATE_SCOPE).append("\n")
                .append("    mode: ").append(mode).append("\n")
                .append("    reason: first\n");
        for (String handler : lifecycleHandlers) {
            yaml.append(handler);
        }
        return yaml.toString();
    }

    private String lifecycleHandler(String lifecycleKey, int value, String propertyKey) {
        return "  " + lifecycleKey + ":\n"
                + "    type:\n"
                + "      blueId: " + LIFECYCLE_CHANNEL + "\n"
                + "  handler" + lifecycleKey + ":\n"
                + "    channel: " + lifecycleKey + "\n"
                + "    type:\n"
                + "      blueId: " + SET_PROPERTY + "\n"
                + "    propertyKey: " + propertyKey + "\n"
                + "    propertyValue: " + value + "\n";
    }

    private Node testEvent(String id) {
        return new TestEvent().eventId(id).toNode();
    }

    private void assertTerminationEventSequence(List<Node> events, String... expectedTypes) {
        assertEquals(expectedTypes.length, events.size());
        for (int index = 0; index < expectedTypes.length; index++) {
            assertEquals(expectedTypes[index], events.get(index).getType().getBlueId());
        }
    }

    private int countEvents(List<Node> events, String typeBlueId) {
        int count = 0;
        for (Node event : events) {
            if (event.getType() != null && typeBlueId.equals(event.getType().getBlueId())) {
                count++;
            }
        }
        return count;
    }

    private Node nodeAt(Node document, String pointer) {
        return document.getNode(pointer);
    }

    private Node nodeOrNull(Node document, String pointer) {
        try {
            return nodeAt(document, pointer);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class LifecycleProbeProcessor implements HandlerProcessor<SetProperty> {
        private final List<String> observed;

        LifecycleProbeProcessor(List<String> observed) {
            this.observed = observed;
        }

        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            String propertyKey = contract.getPropertyKey();
            if ("/terminateOnInitialize".equals(propertyKey)) {
                if (context.event().getType() != null
                        && RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED.equals(context.event().getType().getBlueId())) {
                    observed.add(propertyKey);
                    context.terminateGracefully("initialization lifecycle");
                }
                return;
            }
            if ("/external".equals(propertyKey)) {
                observed.add(propertyKey);
                return;
            }
            if ("/triggeredDrained".equals(propertyKey)) {
                observed.add(propertyKey);
                return;
            }
            if (context.event().getType() == null
                    || !RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED.equals(context.event().getType().getBlueId())) {
                if ("/ordinary".equals(propertyKey)) {
                    observed.add("/ordinary");
                }
                return;
            }
            observed.add(propertyKey);
            if ("/emitLifecycle".equals(propertyKey)) {
                context.emitEvent(new Node().properties("kind", new Node().value("termination-lifecycle")));
                return;
            }
            if ("/emitTriggered".equals(propertyKey)) {
                context.emitEvent(new TestEvent().eventId("termination-lifecycle-emission").toNode());
                return;
            }
            context.applyPatch(JsonPatch.add(context.resolvePointer(propertyKey), new Node().value(contract.getPropertyValue())));
            if ("/reentrant".equals(propertyKey)) {
                context.terminateGracefully("ignored reentrant request");
            }
            if ("/reentrantFatal".equals(propertyKey)) {
                context.terminateFatally("ignored reentrant fatal request");
            }
            if ("/failing".equals(propertyKey)) {
                throw new IllegalStateException("termination lifecycle handler failed");
            }
        }
    }

    private static final class FailingBeforeTerminationProcessor implements HandlerProcessor<SetProperty> {
        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            String propertyKey = contract.getPropertyKey();
            if ("/invalidThenTerminate".equals(propertyKey)) {
                context.applyPatch(JsonPatch.remove(context.resolvePointer("/missing")));
                context.terminateGracefully("must not become the first termination cause");
                return;
            }
            context.applyPatch(JsonPatch.add(context.resolvePointer(propertyKey),
                    new Node().value(contract.getPropertyValue())));
        }
    }
}
