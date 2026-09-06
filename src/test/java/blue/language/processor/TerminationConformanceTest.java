package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.TerminateScopeContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.TestEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Blue Contracts termination transition through public processing results.
 */
final class TerminationConformanceTest {

    private static final String TEST_EVENT_CHANNEL = ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL;
    private static final String TEST_EVENT_TYPE = ProcessorTestTypeBlueIds.TEST_EVENT;
    private static final String TERMINATE_SCOPE = ProcessorTestTypeBlueIds.TERMINATE_SCOPE;
    private static final String SET_PROPERTY = ProcessorTestTypeBlueIds.SET_PROPERTY;
    private static final String LIFECYCLE_CHANNEL = RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL;

    @Test
    void shouldVerifyGracefulTerminationVisitsAllLifecycleChannelsInOrder() {
        // given
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful",
                lifecycleHandler("firstLifecycle", 1, "/first"),
                lifecycleHandler("secondLifecycle", 2, "/second")))).document();

        // when
        DocumentProcessingResult result =
                processExternal(blue, initialized, testEvent("all-lifecycle"));

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals(Arrays.asList("/first", "/second"), observed);
        assertEquals(new BigInteger("1"), nodeAt(result.document(), "/first").getValue());
        assertEquals(new BigInteger("2"), nodeAt(result.document(), "/second").getValue());
        assertTrue(result.events().isEmpty(),
                "processor-generated termination lifecycle is local");
    }

    @Test
    void shouldVerifyLegacyFatalModeRollsBackWithoutLifecycleOrMarker() {
        // given
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("fatal",
                lifecycleHandler("firstLifecycle", 1, "/first"),
                lifecycleHandler("secondLifecycle", 2, "/second")))).document();

        // when
        DocumentProcessingResult result =
                processExternal(blue, initialized, testEvent("fatal-lifecycle"));

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertTrue(observed.isEmpty());
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                diagnosticCategory(result));
        assertEquals("first", diagnosticMessage(result));
        assertRolledBack(initialized, result);
    }

    @Test
    void shouldVerifyReentrantGracefulRequestPreservesFirstCauseAndEarlierEffects() {
        // given
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful",
                lifecycleHandler("reentrantLifecycle", 1, "/reentrant"),
                lifecycleHandler("secondLifecycle", 2, "/after")))).document();

        // when
        DocumentProcessingResult result =
                processExternal(blue, initialized, testEvent("reentrant"));
        Node marker =
                result.document().getAsNode(
                        "/contracts/terminated");

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals(Arrays.asList("/reentrant", "/after"), observed);
        assertEquals(new BigInteger("1"), nodeAt(result.document(), "/reentrant").getValue());
        assertEquals(new BigInteger("2"), nodeAt(result.document(), "/after").getValue());
        assertEquals("graceful", marker.getAsText("/cause"));
        assertEquals("first", marker.getAsText("/reason"));
        assertTrue(result.events().isEmpty(),
                "processor-generated termination lifecycle is local");
    }

    @Test
    void shouldVerifyFatalCallDuringGracefulTerminationRollsBackTheInvocation() {
        // given
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful",
                lifecycleHandler("firstLifecycle", 1, "/reentrantFatal"),
                lifecycleHandler("secondLifecycle", 2, "/after")))).document();

        // when
        DocumentProcessingResult result =
                processExternal(blue, initialized, testEvent("reentrant-fatal"));

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(Collections.singletonList("/reentrantFatal"), observed);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                diagnosticCategory(result));
        assertEquals("ignored reentrant fatal request", diagnosticMessage(result));
        assertRolledBack(initialized, result);
    }

    @Test
    void shouldVerifyTerminationLifecyclePatchRunsImmediateDocumentUpdateCascade() {
        // given
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

        // when
        DocumentProcessingResult result =
                processExternal(blue, initialized, testEvent("ordinary-cutoff"));

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals(Arrays.asList("/lifecycleEffect", "/ordinary"), observed);
        assertEquals(new BigInteger("1"), nodeAt(result.document(), "/lifecycleEffect").getValue());
        assertNull(nodeOrNull(result.document(), "/ordinary"));
    }

    @Test
    void shouldVerifyChildTerminationEmissionReachesAncestorAsAnExactWrapper() {
        // given
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
                + "      propertyValue: 1\n"
                + "contracts:\n"
                + "  childEvents:\n"
                + "    type:\n"
                + "      blueId: "
                + RuntimeBlueIds.EMBEDDED_NODE_CHANNEL + "\n"
                + "    sourcePath: /child\n");
        ProcessorInvocationState execution = new ProcessorInvocationState(blue.getDocumentProcessor(), document);
        execution.preflightScope("/");
        execution.preflightScope("/child");
        execution.runtime().attachScopeOccurrence("/", "/child");

        // when
        execution.enterGracefulTermination("/child", execution.bundleForScope("/child"), "child graceful");
        DocumentProcessingResult result = execution.result();
        List<ProcessingTraceRecord> dequeued =
                execution.runtime().conformanceTrace().records(
                        ProcessingTraceRecord.Kind.EVENT_DEQUEUED);
        List<ProcessingTraceRecord> ancestorDeliveries =
                ancestorDeliveries(execution);
        String dequeuedEventBlueId =
                dequeued.size() == 1
                        ? CheckpointIdentityCalculator.identity(
                        dequeued.get(0).node(),
                        blue)
                        : null;

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals(Arrays.asList("/emitLifecycle"), observed);
        assertEquals(1, dequeued.size());
        assertEquals("termination-lifecycle",
                dequeued.get(0).node().getAsText("/kind"));
        assertEquals("invocation-event-fifo",
                dequeued.get(0).detail("drainOwner"));

        assertEquals(1, ancestorDeliveries.size());
        assertEmbeddedEventDelivery(
                ancestorDeliveries.get(0).node(),
                "/child",
                dequeuedEventBlueId);
        assertTrue(result.events().isEmpty(),
                "child events remain internal unless Root emits");
    }

    @Test
    void shouldVerifyLifecycleCutOffDiscardsChildMarkerButCompletesTheBusinessRun() {
        // given
        AtomicReference<ProcessorInvocationState> executionRef =
                new AtomicReference<>();
        Blue blue = blueWithLifecycleProbe(new ArrayList<String>());
        blue.registerContractProcessor(
                new CutOffOnLifecycleProcessor(executionRef));
        Node document = blue.yamlToNode(
                "name: Parent\n" +
                "child:\n" +
                "  name: Child\n" +
                "  contracts:\n" +
                "    lifecycle:\n" +
                "      type:\n" +
                "        blueId: " + LIFECYCLE_CHANNEL + "\n" +
                "    cutOff:\n" +
                "      channel: lifecycle\n" +
                "      type:\n" +
                "        blueId: " + SET_PROPERTY + "\n");
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        blue.getDocumentProcessor(),
                        document,
                        new Node().value("event"));
        executionRef.set(execution);
        execution.preflightScope("/child");

        // when
        execution.enterGracefulTermination(
                "/child",
                execution.bundleForScope("/child"),
                "completed",
                "replaced during lifecycle");
        DocumentProcessingResult result = execution.result();
        boolean childWasCutOff =
                execution.runtime()
                        .scope("/child")
                        .isCutOff();
        Node terminationMarker =
                nodeOrNull(
                        execution.runtime().document(),
                        "/child/contracts/terminated");

        // then
        assertEquals(ProcessorStatus.SUCCESS,
                result.status());
        assertTrue(childWasCutOff);
        assertNull(terminationMarker);
    }

    @Test
    void shouldVerifyRootEmissionFromTerminationLifecycleIsPublic() {
        // given
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

        // when
        DocumentProcessingResult result =
                processExternal(blue, initialized, testEvent("fifo-clear"));

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals(Collections.singletonList("/emitTriggered"), observed);
        assertNull(nodeOrNull(
                result.document(), "/triggeredDrained"));
        assertTerminationEventSequence(
                result.events(),
                TEST_EVENT_TYPE);
        assertEquals("termination-lifecycle-emission",
                result.events().get(0).getAsText("/eventId"));
    }

    @Test
    void shouldVerifyExplicitInitializationTerminationDoesNotWriteInitializedMarker() {
        // given
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

        // when
        DocumentProcessingResult result = blue.initializeDocument(blue.yamlToNode(document));

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals(Arrays.asList("/terminateOnInitialize"), observed);
        assertEquals("graceful", result.document().getAsNode("/contracts/terminated").getAsText("/cause"));
        assertNull(nodeOrNull(result.document(), "/contracts/initialized"));
        assertTrue(result.events().isEmpty(),
                "processor-generated lifecycle occurrences are local");
    }

    @Test
    void shouldVerifyImplicitInitializationTerminationStopsTheExternalPhase() {
        // given
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

        // when
        Node uninitialized = blue.yamlToNode(document);
        DocumentProcessingResult result =
                processExternal(blue, uninitialized, testEvent("implicit-init"));

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals(Arrays.asList("/terminateOnInitialize"), observed);
        assertEquals("graceful", result.document().getAsNode("/contracts/terminated").getAsText("/cause"));
        assertNull(nodeOrNull(result.document(), "/contracts/initialized"));
        assertNull(nodeOrNull(result.document(), "/external"));
        assertTrue(result.events().isEmpty(),
                "processor-generated lifecycle occurrences are local");
    }

    @Test
    void shouldVerifyTerminationDoesNotCreateOrAdvanceCheckpoint() {
        // given
        Blue blue = blueWithLifecycleProbe(new ArrayList<String>());
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful"))).document();

        // when
        DocumentProcessingResult result =
                processExternal(blue, initialized, testEvent("checkpoint-cutoff"));
        Node checkpoint =
                nodeOrNull(
                        result.document(),
                        "/contracts/checkpoint");
        Node marker =
                nodeOrNull(
                        result.document(),
                        "/contracts/terminated");

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertTrue(result.commits());
        assertNotNull(marker);
        assertEquals("graceful", marker.getAsText("/cause"));
        assertEquals("first", marker.getAsText("/reason"));
        assertNull(checkpoint);
    }

    @Test
    void shouldVerifySuccessfulGracefulTerminationHasNoFailureReason() {
        // given
        Blue blue = blueWithLifecycleProbe(new ArrayList<String>());
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful"))).document();

        // when
        DocumentProcessingResult result =
                processExternal(blue, initialized, testEvent("graceful-result"));

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertNull(diagnosticCategory(result));
        assertNull(diagnosticMessage(result));
        assertEquals("first", result.document().getAsNode("/contracts/terminated").getAsText("/reason"));
    }

    @Test
    void shouldVerifyChildLifecycleFailureAbortsImmediatelyAndRollsBack() {
        // given
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
        ProcessorInvocationState execution = new ProcessorInvocationState(blue.getDocumentProcessor(), document);
        // when
        execution.preflightScope("/child");
        Throwable failure = captureFailure(
                () -> execution.enterGracefulTermination(
                        "/child",
                        execution.bundleForScope("/child"),
                        "child graceful"));
        DocumentProcessingResult result = execution.result();

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertInstanceOf(RunTerminationException.class, failure);
        assertEquals(Arrays.asList("/failing"), observed);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                diagnosticCategory(result));
        assertEquals("termination lifecycle handler failed",
                diagnosticMessage(result));
        assertRolledBack(document, result);
    }

    @Test
    void shouldVerifyDirectRuntimeFailureAbortsBeforeAnyLaterTerminationRequest() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        Node document = new Node()
                .name("Parent")
                .contracts(new Node())
                .properties("child", new Node().name("Child"));
        ProcessorInvocationState execution = new ProcessorInvocationState(blue.getDocumentProcessor(), document);

        // when
        Throwable failure = captureFailure(
                () -> execution.abortRuntimeFailure(
                        "/child",
                        null,
                        ProcessorErrorCategory.PatchBoundaryViolation,
                        "child failure"));
        DocumentProcessingResult result = execution.result();

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertInstanceOf(RunTerminationException.class, failure);
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                diagnosticCategory(result));
        assertEquals("child failure", diagnosticMessage(result));
        assertRolledBack(document, result);
    }

    @Test
    void shouldVerifyEarlierBufferedFailurePreventsQueuedGracefulTermination() {
        // given
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

        // when
        DocumentProcessingResult result =
                processExternal(blue, initialized, testEvent("buffered-failure"));

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                diagnosticCategory(result));
        assertRolledBack(initialized, result);
    }

    @Test
    void shouldVerifyLifecycleFailureRollsBackEarlierTerminationEffects() {
        // given
        List<String> observed = new ArrayList<>();
        Blue blue = blueWithLifecycleProbe(observed);
        Node initialized = blue.initializeDocument(blue.yamlToNode(terminationDocument("graceful",
                lifecycleHandler("aFirstLifecycle", 1, "/first"),
                lifecycleHandler("bFailingLifecycle", 2, "/failing"),
                lifecycleHandler("cThirdLifecycle", 3, "/third")))).document();

        // when
        DocumentProcessingResult result =
                processExternal(blue, initialized, testEvent("escalation"));

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(Arrays.asList("/first", "/failing"), observed);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                diagnosticCategory(result));
        assertEquals("termination lifecycle handler failed", diagnosticMessage(result));
        assertRolledBack(initialized, result);
    }

    @Test
    void shouldVerifyChildTerminationFailureDoesNotCommitMarkerOrBridgeEvent() {
        // given
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
        ProcessorInvocationState execution = new ProcessorInvocationState(blue.getDocumentProcessor(), document);
        // when
        execution.preflightScope("/child");
        Throwable failure = captureFailure(
                () -> execution.enterGracefulTermination(
                        "/child",
                        execution.bundleForScope("/child"),
                        "first"));
        DocumentProcessingResult result = execution.result();

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertInstanceOf(RunTerminationException.class, failure);
        assertEquals(Arrays.asList("/failing"), observed);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                diagnosticCategory(result));
        assertRolledBack(document, result);
    }

    @Test
    void shouldVerifyMalformedRootContractsRollBackTerminationMarkerFailure() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        Node document = new Node().name("Malformed Root").contracts(new Node().value("not-an-object"));
        ProcessorInvocationState execution = new ProcessorInvocationState(blue.getDocumentProcessor(), document);

        // when
        Throwable failure = captureFailure(
                () -> execution.enterGracefulTermination("/", null, "cannot write"));
        DocumentProcessingResult result = execution.result();

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertInstanceOf(RunTerminationException.class, failure);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                diagnosticCategory(result));
        assertEquals("not-an-object", result.document().getContracts().getValue());
        assertNull(nodeOrNull(result.document(), "/contracts/terminated"));
        assertRolledBack(document, result);
    }

    @Test
    void shouldVerifyMalformedChildContractsRollBackWithoutReplacingApplicationContracts() {
        // given
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
        ProcessorInvocationState execution = new ProcessorInvocationState(blue.getDocumentProcessor(), document);

        // when
        Throwable failure = captureFailure(
                () -> execution.enterGracefulTermination(
                        "/child", null, "child fallback"));
        DocumentProcessingResult result = execution.result();

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertInstanceOf(RunTerminationException.class, failure);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                diagnosticCategory(result));
        assertEquals("preserve", nodeAt(result.document(), "/contracts/rootOnly").getValue());
        assertNull(nodeOrNull(result.document(), "/contracts/terminated"));
        assertNull(nodeOrNull(result.document(), "/child/contracts/terminated"));
        assertEquals("not-an-object",
                nodeAt(result.document(), "/child/contracts").getValue());
        assertEquals("kept", nodeAt(result.document(), "/child/contracts/checkpoint/lastEvents/events").getValue());
        assertEquals("drop",
                nodeAt(result.document(), "/child/contracts/ordinaryContract").getValue());
        assertRolledBack(document, result);
    }

    @Test
    void shouldVerifyMarkerFailureReturnsExactInputWithRuntimeFailure() {
        // given
        Node invalidUnrelatedContent = new Node()
                .value("invalid")
                .properties("alsoInvalid", new Node().value("content"));
        Node document = new Node()
                .name("Broken Fallback")
                .contracts(new Node().value("malformed"))
                .properties("unrelated", invalidUnrelatedContent);
        ProcessorInvocationState execution = new ProcessorInvocationState(new DocumentProcessor(), document);

        // when
        Throwable failure = captureFailure(
                () -> execution.enterGracefulTermination("/", null, "cannot write"));
        DocumentProcessingResult result = execution.result();

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertInstanceOf(RunTerminationException.class, failure);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                diagnosticCategory(result));
        assertEquals("malformed", result.document().getContracts().getValue());
        assertNull(nodeOrNull(result.document(), "/contracts/terminated"));
        assertFalse(diagnosticMessage(result).isEmpty());
        assertRolledBack(document, result);
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static List<ProcessingTraceRecord> ancestorDeliveries(
            ProcessorInvocationState execution) {
        List<ProcessingTraceRecord> deliveries =
                new ArrayList<>();
        for (ProcessingTraceRecord delivered
                : execution.runtime().conformanceTrace().records(
                ProcessingTraceRecord.Kind.EVENT_DELIVERED)) {
            if ("/".equals(delivered.scopePath())
                    && "childEvents".equals(
                    delivered.contractKey())) {
                deliveries.add(delivered);
            }
        }
        return deliveries;
    }

    private Blue blueWithLifecycleProbe(List<String> observed) {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                new TerminationTestEventChannelProcessor());
        blue.registerContractProcessor(new TerminateScopeContractProcessor());
        blue.registerContractProcessor(new LifecycleProbeProcessor(observed));
        return blue;
    }

    /**
     * Termination conformance is downstream of feeder-plan verification. Supply
     * an exact, revision-bound occurrence directly so these tests exercise the
     * Contracts kernel instead of the default unavailable feeder.
     */
    private DocumentProcessingResult processExternal(
            Blue blue,
            Node document,
            Node event) {
        Node channel = nodeAt(document, "/contracts/events");
        String contributionBlueId =
                DirectBlueIdCalculator.calculateBlueId(channel);
        String checkpointDomainBlueId =
                CheckpointDomain.derive(
                        TEST_EVENT_CHANNEL,
                        Collections.singletonList(
                                contributionBlueId),
                        null);
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        ExternalOrderKey eventOrder =
                ExternalOrderKey.of(
                        Collections.singletonList(eventBlueId));
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder("/", "events")
                        .order(0)
                        .sourceContribution(
                                contributionBlueId)
                        .effectiveTypeBlueId(
                                TEST_EVENT_CHANNEL)
                        .subscriptionKey(
                                TEST_EVENT_TYPE)
                        .checkpointDomainBlueId(
                                checkpointDomainBlueId)
                        .checkpointSubjectBlueId(
                                eventBlueId)
                        .build();
        VerifiedExecutionEvidence evidence =
                VerifiedExecutionEvidence.builder(
                                DirectBlueIdCalculator
                                        .calculateBlueId(document),
                                eventBlueId)
                        .revisions(1L, 1L)
                        .runtimeRegistryIdentity(
                                RuntimeBlueIds
                                        .REGISTRY_PACKAGE_IDENTITY)
                        .eventOrderKey(eventOrder)
                        .delivery(delivery)
                        .activeSubscriptionInterval(
                                new SubscriptionDelta.Entry(
                                        "/",
                                        "events",
                                        TEST_EVENT_CHANNEL,
                                        Collections.singletonList(
                                                contributionBlueId),
                                        0,
                                        Collections.singletonList(
                                                TEST_EVENT_TYPE),
                                        checkpointDomainBlueId,
                                        1L,
                                        null,
                                        null))
                        .build();
        return ProcessorEngine.processDocument(
                blue.getDocumentProcessor(),
                document,
                event,
                evidence);
    }

    private void assertRolledBack(
            Node input,
            DocumentProcessingResult result) {
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(input.toString(),
                result.document().toString());
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

    private void assertEmbeddedEventDelivery(
            Node delivery,
            String expectedSourcePath,
            String expectedEventBlueId) {
        assertNotNull(delivery);
        assertNotNull(delivery.getType());
        assertEquals(RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY,
                delivery.getType().getBlueId());
        assertNotNull(delivery.getProperties());
        assertEquals(2, delivery.getProperties().size());
        assertEquals(expectedSourcePath,
                delivery.getAsText("/sourcePath"));
        assertFalse(delivery.getProperties()
                .containsKey("childPath"));
        Node eventReference =
                delivery.getProperties().get("event");
        assertNotNull(eventReference);
        assertTrue(eventReference.isReferenceOnly());
        assertEquals(expectedEventBlueId,
                eventReference.getBlueId());
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
                context.throwFatal("ignored reentrant fatal request");
            }
            if ("/failing".equals(propertyKey)) {
                throw new ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure,
                        "termination lifecycle handler failed");
            }
        }
    }

    private static final class CutOffOnLifecycleProcessor
            implements HandlerProcessor<SetProperty> {
        private final AtomicReference<ProcessorInvocationState>
                execution;

        private CutOffOnLifecycleProcessor(
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

    private static final class TerminationTestEventChannelProcessor
            extends TestEventChannelProcessor {

        @Override
        public ExternalChannelSubscriptionFunctions<TestEventChannel>
        externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<TestEventChannel>() {
                @Override
                public List<String> channelKeys(
                        TestEventChannel channel) {
                    String eventType =
                            channel.getEventType() != null
                                    ? channel.getEventType()
                                    : TEST_EVENT_TYPE;
                    return Collections.singletonList(eventType);
                }

                @Override
                public List<String> eventKeys(Node event) {
                    Node type = event != null
                            ? event.getType() : null;
                    String eventType = type != null
                            ? type.getBlueId() : null;
                    return eventType != null
                            ? Collections.singletonList(
                            eventType)
                            : Collections.<String>emptyList();
                }

                @Override
                public String checkpointDomainDiscriminator(
                        TestEventChannel channel) {
                    return null;
                }
            };
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
