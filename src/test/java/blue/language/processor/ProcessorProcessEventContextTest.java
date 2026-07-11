package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the immutable, execution-scoped Processing Event.
 */
final class ProcessorProcessEventContextTest {

    private static final int CONCURRENT_READER_COUNT = 8;
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 5L;
    private static final String TEST_EVENT_TYPE = "Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf";
    private static final String TEST_EVENT_CHANNEL_TYPE = "BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L";
    private static final String SET_PROPERTY_TYPE = "8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts";

    @Test
    void explicitInitializeHasNoProcessEventForDocumentAndSnapshotExecutions() {
        DocumentProcessor owner = new DocumentProcessor();
        Node document = new Node();
        ResolvedSnapshot snapshot = snapshot(document);

        assertAbsentProcessEvent(new ProcessorEngine.Execution(owner, document));
        assertAbsentProcessEvent(new ProcessorEngine.Execution(owner, snapshot));
    }

    @Test
    void hasProcessEventDoesNotFreezeAndFirstAccessFreezesOnce() {
        RecordingMetrics metrics = new RecordingMetrics();
        DocumentProcessor owner = DocumentProcessor.builder()
                .withProcessingMetricsSink(metrics)
                .build();
        Node processEvent = processEvent("root");
        AtomicInteger freezerCalls = new AtomicInteger();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner,
                new Node(),
                processEvent,
                source -> {
                    freezerCalls.incrementAndGet();
                    return FrozenNode.fromResolvedNode(source);
                });
        ProcessorExecutionContext first = execution.createContext("/", ContractBundle.empty(), new Node(), false);
        ProcessorExecutionContext second = execution.createContext("/", ContractBundle.empty(), new Node(), false);

        assertTrue(first.hasProcessEvent());
        assertTrue(second.hasProcessEvent());
        assertEquals(0, freezerCalls.get());
        assertEquals(0L, metrics.processEventSnapshotAttempts);

        FrozenNode snapshot = first.frozenProcessEvent();

        assertSame(snapshot, second.frozenProcessEvent(),
                "the package-private execution seam may verify the memoized optimization");
        assertSame(snapshot, first.frozenProcessEvent());
        assertEquals(1, freezerCalls.get());
        assertSnapshotKind(snapshot, "root");
        assertEquals(1L, metrics.processEventSnapshotAttempts);
        assertEquals(1L, metrics.processEventSnapshotBuilds);
        assertEquals(0L, metrics.processEventSnapshotFailures);
        assertEquals(1L, metrics.processEventSnapshotConstructionSamples);
        assertTrue(metrics.processEventSnapshotConstructionNanos >= 0L);
    }

    @Test
    void snapshotFailureIsStableAndUsesBoundedMetrics() {
        RecordingMetrics metrics = new RecordingMetrics();
        IllegalStateException expected = new IllegalStateException("snapshot failed");
        AtomicInteger freezerCalls = new AtomicInteger();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(
                DocumentProcessor.builder().withProcessingMetricsSink(metrics).build(),
                new Node(),
                processEvent("root"),
                source -> {
                    freezerCalls.incrementAndGet();
                    throw expected;
                });
        ProcessorExecutionContext context = execution.createContext("/", ContractBundle.empty(), new Node(), false);

        IllegalStateException first = assertThrows(IllegalStateException.class, context::frozenProcessEvent);
        IllegalStateException second = assertThrows(IllegalStateException.class, context::frozenProcessEvent);

        assertSame(expected, first);
        assertSame(first, second, "a failed snapshot must not be rebuilt or replaced");
        assertEquals(1, freezerCalls.get());
        assertEquals(1L, metrics.processEventSnapshotAttempts);
        assertEquals(0L, metrics.processEventSnapshotBuilds);
        assertEquals(1L, metrics.processEventSnapshotFailures);
        assertEquals(1L, metrics.processEventSnapshotConstructionSamples);
    }

    @Test
    void nullSnapshotFactoryResultIsAStableFailure() {
        RecordingMetrics metrics = new RecordingMetrics();
        AtomicInteger freezerCalls = new AtomicInteger();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(
                DocumentProcessor.builder().withProcessingMetricsSink(metrics).build(),
                new Node(),
                processEvent("root"),
                source -> {
                    freezerCalls.incrementAndGet();
                    return null;
                });
        ProcessorExecutionContext context = execution.createContext("/", ContractBundle.empty(), new Node(), false);

        IllegalStateException failure = assertThrows(IllegalStateException.class, context::frozenProcessEvent);
        assertSame(failure, assertThrows(IllegalStateException.class, context::frozenProcessEvent));
        assertEquals("Processing Event snapshot construction returned null", failure.getMessage());
        assertEquals(1, freezerCalls.get());
        assertEquals(1L, metrics.processEventSnapshotAttempts);
        assertEquals(1L, metrics.processEventSnapshotFailures);
        assertEquals(0L, metrics.processEventSnapshotBuilds);
    }

    @Test
    void concurrentFirstAccessBuildsOnceAndPublishesOneSnapshot() throws Exception {
        RecordingMetrics metrics = new RecordingMetrics();
        AtomicInteger freezerCalls = new AtomicInteger();
        CountDownLatch readersReady = new CountDownLatch(CONCURRENT_READER_COUNT);
        CountDownLatch startReaders = new CountDownLatch(1);
        CountDownLatch readAttempts = new CountDownLatch(CONCURRENT_READER_COUNT);
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(
                DocumentProcessor.builder().withProcessingMetricsSink(metrics).build(),
                new Node(),
                processEvent("concurrent-root"),
                source -> {
                    freezerCalls.incrementAndGet();
                    awaitLatch(readAttempts, "all concurrent readers to attempt snapshot access");
                    return FrozenNode.fromResolvedNode(source);
                });
        ProcessorExecutionContext context = execution.createContext(
                "/", ContractBundle.empty(), new Node(), false);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_READER_COUNT);
        List<Future<FrozenNode>> reads = new ArrayList<>();

        try {
            for (int index = 0; index < CONCURRENT_READER_COUNT; index++) {
                reads.add(executor.submit(() -> {
                    readersReady.countDown();
                    awaitLatch(startReaders, "concurrent snapshot readers to start");
                    readAttempts.countDown();
                    return context.frozenProcessEvent();
                }));
            }
            assertTrue(readersReady.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "all concurrent readers should be ready");
            startReaders.countDown();

            FrozenNode expected = reads.get(0).get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            for (Future<FrozenNode> read : reads) {
                assertSame(expected, read.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            assertSnapshotKind(expected, "concurrent-root");
        } finally {
            startReaders.countDown();
            shutdownExecutor(executor);
        }

        assertEquals(1, freezerCalls.get());
        assertEquals(1L, metrics.processEventSnapshotAttempts);
        assertEquals(1L, metrics.processEventSnapshotBuilds);
        assertEquals(0L, metrics.processEventSnapshotFailures);
        assertEquals(1L, metrics.processEventSnapshotConstructionSamples);
    }

    @Test
    void concurrentFailedFirstAccessPublishesOneFailureWithoutRetry() throws Exception {
        RecordingMetrics metrics = new RecordingMetrics();
        IllegalStateException expected = new IllegalStateException("concurrent snapshot failure");
        AtomicInteger freezerCalls = new AtomicInteger();
        CountDownLatch readersReady = new CountDownLatch(CONCURRENT_READER_COUNT);
        CountDownLatch startReaders = new CountDownLatch(1);
        CountDownLatch readAttempts = new CountDownLatch(CONCURRENT_READER_COUNT);
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(
                DocumentProcessor.builder().withProcessingMetricsSink(metrics).build(),
                snapshot(new Node()),
                processEvent("concurrent-root"),
                source -> {
                    freezerCalls.incrementAndGet();
                    awaitLatch(readAttempts, "all concurrent readers to attempt failed snapshot access");
                    throw expected;
                });
        ProcessorExecutionContext context = execution.createContext(
                "/", ContractBundle.empty(), new Node(), false);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_READER_COUNT);
        List<Future<FrozenNode>> reads = new ArrayList<>();

        try {
            for (int index = 0; index < CONCURRENT_READER_COUNT; index++) {
                reads.add(executor.submit(() -> {
                    readersReady.countDown();
                    awaitLatch(startReaders, "concurrent failed snapshot readers to start");
                    readAttempts.countDown();
                    return context.frozenProcessEvent();
                }));
            }
            assertTrue(readersReady.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "all concurrent readers should be ready");
            startReaders.countDown();

            for (Future<FrozenNode> read : reads) {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> read.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertSame(expected, failure.getCause());
            }
            assertSame(expected, assertThrows(IllegalStateException.class, context::frozenProcessEvent));
        } finally {
            startReaders.countDown();
            shutdownExecutor(executor);
        }

        assertEquals(1, freezerCalls.get());
        assertEquals(1L, metrics.processEventSnapshotAttempts);
        assertEquals(0L, metrics.processEventSnapshotBuilds);
        assertEquals(1L, metrics.processEventSnapshotFailures);
        assertEquals(1L, metrics.processEventSnapshotConstructionSamples);
    }

    @Test
    void directAndTriggeredHandlersShareOneSnapshotWhileCurrentEventsDiffer() {
        CapturingHandler capture = new CapturingHandler();
        RecordingMetrics metrics = new RecordingMetrics();
        Blue blue = configuredBlue(capture, new TestEventChannelProcessor(), metrics);
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "name: Direct and Triggered\n" +
                        "contracts:\n" +
                        "  events:\n" +
                        "    type:\n" +
                        "      blueId: " + TEST_EVENT_CHANNEL_TYPE + "\n" +
                        "  triggered:\n" +
                        "    type:\n" +
                        "      blueId: " + RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL + "\n" +
                        handler("emitFirst", "events", 0) +
                        handler("captureTriggered", "triggered", 1))).document();
        capture.clear();

        DocumentProcessingResult result = blue.getDocumentProcessor().processDocument(initialized, processEvent("root"));

        assertFalse(result.capabilityFailure(), result.failureReason());
        Observation direct = capture.only("emitFirst");
        Observation triggered = capture.only("captureTriggered");
        assertEquals("root", eventKind(direct.currentEvent));
        assertEquals("first", eventKind(triggered.currentEvent));
        assertSnapshotKind(direct.processEvent, "root");
        assertSnapshotKind(triggered.processEvent, "root");
        assertSame(direct.processEvent, triggered.processEvent,
                "one execution must reuse its completed immutable snapshot");
        assertEquals(1L, metrics.processEventSnapshotAttempts);
        assertEquals(1L, metrics.processEventSnapshotBuilds);
    }

    @Test
    void multiHopTriggeredHandlersKeepTheRootContext() {
        CapturingHandler capture = new CapturingHandler();
        Blue blue = configuredBlue(capture, new TestEventChannelProcessor(), new RecordingMetrics());
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "name: Multi Hop\n" +
                        "contracts:\n" +
                        "  events:\n" +
                        "    type:\n" +
                        "      blueId: " + TEST_EVENT_CHANNEL_TYPE + "\n" +
                        "  triggered:\n" +
                        "    type:\n" +
                        "      blueId: " + RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL + "\n" +
                        handler("emitFirst", "events", 0) +
                        handler("captureFirst", "triggered", 1) +
                        handler("emitSecond", "triggered", 2) +
                        handler("captureSecond", "triggered", 3))).document();
        capture.clear();

        blue.getDocumentProcessor().processDocument(initialized, processEvent("root"));

        assertEquals("first", eventKind(capture.only("captureFirst").currentEvent));
        assertEquals("second", eventKind(capture.only("captureSecond").currentEvent));
        assertSnapshotKind(capture.only("captureFirst").processEvent, "root");
        assertSnapshotKind(capture.only("captureSecond").processEvent, "root");
    }

    @Test
    void implicitInitializationSharesTheProcessEventWithLifecycleHandlers() {
        CapturingHandler capture = new CapturingHandler();
        Blue blue = configuredBlue(capture, new TestEventChannelProcessor(), new RecordingMetrics());
        Node document = blue.yamlToNode(
                "name: Implicit Initialization\n" +
                        "contracts:\n" +
                        "  lifecycle:\n" +
                        "    type:\n" +
                        "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                        "  events:\n" +
                        "    type:\n" +
                        "      blueId: " + TEST_EVENT_CHANNEL_TYPE + "\n" +
                        handler("captureLifecycle", "lifecycle", 0) +
                        handler("captureDirect", "events", 1));

        DocumentProcessingResult result = blue.getDocumentProcessor().processDocument(document, processEvent("root"));

        assertFalse(result.capabilityFailure(), result.failureReason());
        Observation lifecycle = capture.only("captureLifecycle");
        Observation direct = capture.only("captureDirect");
        assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED,
                lifecycle.currentEvent.getType().getBlueId());
        assertEquals("root", eventKind(direct.currentEvent));
        assertSnapshotKind(lifecycle.processEvent, "root");
        assertSnapshotKind(direct.processEvent, "root");
    }

    @Test
    void embeddedAndBridgedHandlersKeepTheRootContext() {
        CapturingHandler capture = new CapturingHandler();
        Blue blue = configuredBlue(capture, new TestEventChannelProcessor(), new RecordingMetrics());
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "name: Embedded Context\n" +
                        "child:\n" +
                        "  name: Child\n" +
                        "  contracts:\n" +
                        "    events:\n" +
                        "      type:\n" +
                        "        blueId: " + TEST_EVENT_CHANNEL_TYPE + "\n" +
                        handler("captureChild", "events", 0, "    ") +
                        handler("emitBridge", "events", 1, "    ") +
                        "contracts:\n" +
                        "  embedded:\n" +
                        "    type:\n" +
                        "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                        "    paths:\n" +
                        "      - /child\n" +
                        "  childBridge:\n" +
                        "    type:\n" +
                        "      blueId: " + RuntimeBlueIds.EMBEDDED_NODE_CHANNEL + "\n" +
                        "    childPath: /child\n" +
                        handler("captureBridge", "childBridge", 2))).document();
        capture.clear();

        blue.getDocumentProcessor().processDocument(initialized, processEvent("root"));

        Observation child = capture.only("captureChild");
        Observation bridge = capture.only("captureBridge");
        assertEquals("root", eventKind(child.currentEvent));
        assertEquals("bridge", eventKind(bridge.currentEvent));
        assertSnapshotKind(child.processEvent, "root");
        assertSnapshotKind(bridge.processEvent, "root");
    }

    @Test
    void channelAdaptationDoesNotReplaceTheProcessEvent() {
        CapturingHandler capture = new CapturingHandler();
        Blue blue = configuredBlue(capture, new AdaptingTestEventChannelProcessor(), new RecordingMetrics());
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "name: Adapted Event\n" +
                        "contracts:\n" +
                        "  events:\n" +
                        "    type:\n" +
                        "      blueId: " + TEST_EVENT_CHANNEL_TYPE + "\n" +
                        handler("captureAdapted", "events", 0))).document();
        capture.clear();

        blue.getDocumentProcessor().processDocument(initialized, processEvent("root"));

        Observation adapted = capture.only("captureAdapted");
        assertEquals("adapted", eventKind(adapted.currentEvent));
        assertSnapshotKind(adapted.processEvent, "root");
    }

    @Test
    void handlerEventMutationCannotMutateTheFrozenProcessEvent() {
        CapturingHandler capture = new CapturingHandler();
        Blue blue = configuredBlue(capture, new TestEventChannelProcessor(), new RecordingMetrics());
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "name: Mutation Isolation\n" +
                        "contracts:\n" +
                        "  events:\n" +
                        "    type:\n" +
                        "      blueId: " + TEST_EVENT_CHANNEL_TYPE + "\n" +
                        handler("mutateCurrent", "events", 0) +
                        handler("captureAfterMutation", "events", 1))).document();
        capture.clear();

        blue.getDocumentProcessor().processDocument(initialized, processEvent("root"));

        Observation mutated = capture.only("mutateCurrent");
        Observation afterMutation = capture.only("captureAfterMutation");
        assertEquals("mutated-current", eventKind(mutated.currentEvent));
        assertEquals("root", eventKind(afterMutation.currentEvent));
        assertSnapshotKind(mutated.processEvent, "root");
        assertSnapshotKind(afterMutation.processEvent, "root");
    }

    @Test
    void separateProcessRunsDoNotLeakContextAndBothProcessOverloadsRetainInput() {
        CapturingHandler capture = new CapturingHandler();
        RecordingMetrics metrics = new RecordingMetrics();
        Blue blue = configuredBlue(capture, new TestEventChannelProcessor(), metrics);
        DocumentProcessingResult initialized = blue.initializeDocument(blue.yamlToNode(
                "name: Separate Runs\n" +
                        "contracts:\n" +
                        "  events:\n" +
                        "    type:\n" +
                        "      blueId: " + TEST_EVENT_CHANNEL_TYPE + "\n" +
                        handler("capture", "events", 0)));
        capture.clear();

        blue.getDocumentProcessor().processDocument(initialized.document(), processEvent("direct-root"));
        assertSnapshotKind(capture.only("capture").processEvent, "direct-root");

        capture.clear();
        blue.getDocumentProcessor().processDocument(initialized.snapshot(), processEvent("snapshot-root"));
        assertSnapshotKind(capture.only("capture").processEvent, "snapshot-root");
        assertEquals(2L, metrics.processEventSnapshotAttempts);
        assertEquals(2L, metrics.processEventSnapshotBuilds);
    }

    @Test
    void unusedContextDoesNotBuildSnapshotForWideOrDeepEventsAcrossProcessOverloads() {
        RecordingMetrics metrics = new RecordingMetrics();
        Blue blue = configuredBlue(null, new TestEventChannelProcessor(), metrics);
        DocumentProcessingResult initialized = blue.initializeDocument(blue.yamlToNode(
                "name: Unused Context\n" +
                        "contracts:\n" +
                        "  events:\n" +
                        "    type:\n" +
                        "      blueId: " + TEST_EVENT_CHANNEL_TYPE + "\n"));
        Node wide = wideProcessEvent();
        Node deep = deepProcessEvent();

        blue.getDocumentProcessor().processDocument(initialized.document(), wide);
        blue.getDocumentProcessor().processDocument(initialized.document(), deep);
        blue.getDocumentProcessor().processDocument(initialized.snapshot(), wide);
        blue.getDocumentProcessor().processDocument(initialized.snapshot(), deep);

        assertEquals(0L, metrics.processEventSnapshotAttempts);
        assertEquals(0L, metrics.processEventSnapshotBuilds);
        assertEquals(0L, metrics.processEventSnapshotFailures);
        assertEquals(0L, metrics.processEventSnapshotConstructionSamples);
    }

    @Test
    void snapshotFailureFollowsExistingHandlerFailureMapping() {
        RecordingMetrics metrics = new RecordingMetrics();
        DocumentProcessor owner = DocumentProcessor.builder()
                .withProcessingMetricsSink(metrics)
                .registerContractProcessor(new TestEventChannelProcessor())
                .registerContractProcessor(new ReadProcessEventHandler())
                .build();
        Node document = ProcessorTestSupport.blue().yamlToNode(
                "name: Failure Mapping\n" +
                        "contracts:\n" +
                        "  events:\n" +
                        "    type:\n" +
                        "      blueId: " + TEST_EVENT_CHANNEL_TYPE + "\n" +
                        handler("read", "events", 0));
        AtomicInteger freezerCalls = new AtomicInteger();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner,
                document,
                processEvent("root"),
                source -> {
                    freezerCalls.incrementAndGet();
                    throw new IllegalStateException("snapshot host failure");
                });
        execution.loadBundles("/");

        assertThrows(RunTerminationException.class,
                () -> execution.processExternalEvent("/", processEvent("delivery")));

        DocumentProcessingResult result = execution.result();
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(ProcessorErrorCategory.HandlerExecutionError, result.errorCategory());
        assertEquals("snapshot host failure", result.failureReason());
        assertEquals(1, freezerCalls.get());
        assertEquals(1L, metrics.processEventSnapshotAttempts);
        assertEquals(1L, metrics.processEventSnapshotFailures);
    }

    private void assertAbsentProcessEvent(ProcessorEngine.Execution execution) {
        ProcessorExecutionContext context = execution.createContext("/", ContractBundle.empty(), new Node(), false);
        assertFalse(context.hasProcessEvent());
        assertNull(context.frozenProcessEvent());
    }

    private static Blue configuredBlue(CapturingHandler capture,
                                       ChannelProcessor<TestEventChannel> channelProcessor,
                                       RecordingMetrics metrics) {
        Blue blue = ProcessorTestSupport.blue();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        blue.registerContractProcessor(channelProcessor);
        if (capture != null) {
            blue.registerContractProcessor(capture);
        }
        return blue;
    }

    private static String handler(String propertyKey, String channel, int order) {
        return handler(propertyKey, channel, order, "  ");
    }

    private static String handler(String propertyKey, String channel, int order, String indent) {
        return indent + propertyKey + ":\n" +
                indent + "  channel: " + channel + "\n" +
                indent + "  order: " + order + "\n" +
                indent + "  type:\n" +
                indent + "    blueId: " + SET_PROPERTY_TYPE + "\n" +
                indent + "  propertyKey: " + propertyKey + "\n";
    }

    private static Node processEvent(String kind) {
        return new Node()
                .type(new Node().blueId(TEST_EVENT_TYPE))
                .properties("eventId", new Node().value("event-" + kind))
                .properties("kind", new Node().value(kind));
    }

    private static Node wideProcessEvent() {
        Node event = processEvent("wide");
        for (int index = 0; index < 256; index++) {
            event.properties("field" + index, new Node().value(index));
        }
        return event;
    }

    private static Node deepProcessEvent() {
        Node event = processEvent("deep");
        Node cursor = event;
        for (int index = 0; index < 128; index++) {
            Node child = new Node();
            cursor.properties("next", child);
            cursor = child;
        }
        cursor.properties("leaf", new Node().value("end"));
        return event;
    }

    private static ResolvedSnapshot snapshot(Node document) {
        FrozenNode canonicalRoot = FrozenNode.fromNode(document);
        FrozenNode resolvedRoot = FrozenNode.fromResolvedNode(document);
        return new ResolvedSnapshot(canonicalRoot, resolvedRoot, canonicalRoot.blueId());
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + description, exception);
        }
    }

    private static void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "concurrent reader executor should terminate");
    }

    private static String eventKind(Node event) {
        if (event == null || event.getProperties() == null) {
            return null;
        }
        Node kind = event.getProperties().get("kind");
        return kind != null && kind.getValue() != null ? String.valueOf(kind.getValue()) : null;
    }

    private static void assertSnapshotKind(FrozenNode snapshot, String expectedKind) {
        assertNotNull(snapshot);
        assertEquals(expectedKind, snapshot.toNode().getAsText("/kind"));
    }

    private static final class CapturingHandler implements HandlerProcessor<SetProperty> {
        private final List<Observation> observations = new ArrayList<>();

        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            Node currentEvent = context.event();
            FrozenNode processEvent = context.frozenProcessEvent();
            String propertyKey = contract.getPropertyKey();
            if ("mutateCurrent".equals(propertyKey) && currentEvent != null) {
                currentEvent.properties("kind", new Node().value("mutated-current"));
            }
            if (shouldObserve(propertyKey, currentEvent)) {
                observations.add(new Observation(propertyKey,
                        currentEvent != null ? currentEvent.clone() : null,
                        context.hasProcessEvent(),
                        processEvent));
            }
            if ("emitFirst".equals(propertyKey)) {
                context.emitEvent(processEvent("first"));
            } else if ("emitSecond".equals(propertyKey) && "first".equals(eventKind(currentEvent))) {
                context.emitEvent(processEvent("second"));
            } else if ("emitBridge".equals(propertyKey)) {
                context.emitEvent(new Node().properties("kind", new Node().value("bridge")));
            }
        }

        void clear() {
            observations.clear();
        }

        Observation only(String propertyKey) {
            List<Observation> matches = new ArrayList<>();
            for (Observation observation : observations) {
                if (propertyKey.equals(observation.propertyKey)) {
                    matches.add(observation);
                }
            }
            assertEquals(1, matches.size(), "expected exactly one observation for " + propertyKey);
            return matches.get(0);
        }

        private boolean shouldObserve(String propertyKey, Node currentEvent) {
            if ("captureFirst".equals(propertyKey)) {
                return "first".equals(eventKind(currentEvent));
            }
            if ("captureSecond".equals(propertyKey)) {
                return "second".equals(eventKind(currentEvent));
            }
            return true;
        }
    }

    private static final class ReadProcessEventHandler implements HandlerProcessor<SetProperty> {
        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            context.frozenProcessEvent();
        }
    }

    private static final class AdaptingTestEventChannelProcessor implements ChannelProcessor<TestEventChannel> {
        @Override
        public Class<TestEventChannel> contractType() {
            return TestEventChannel.class;
        }

        @Override
        public ChannelEvaluation evaluate(TestEventChannel contract, ChannelEvaluationContext context) {
            Node adapted = context.event();
            if (adapted == null) {
                return ChannelEvaluation.noMatch();
            }
            adapted.properties("kind", new Node().value("adapted"));
            return ChannelEvaluation.match(adapted, adapted.getAsText("/eventId"));
        }
    }

    private static final class Observation {
        final String propertyKey;
        final Node currentEvent;
        final boolean hasProcessEvent;
        final FrozenNode processEvent;

        Observation(String propertyKey, Node currentEvent, boolean hasProcessEvent, FrozenNode processEvent) {
            this.propertyKey = propertyKey;
            this.currentEvent = currentEvent;
            this.hasProcessEvent = hasProcessEvent;
            this.processEvent = processEvent;
        }
    }

    private static final class RecordingMetrics implements ProcessingMetricsSink {
        long processEventSnapshotAttempts;
        long processEventSnapshotBuilds;
        long processEventSnapshotFailures;
        long processEventSnapshotConstructionSamples;
        long processEventSnapshotConstructionNanos;

        @Override
        public void incrementProcessEventSnapshotAttempts() {
            processEventSnapshotAttempts++;
        }

        @Override
        public void incrementProcessEventSnapshotBuilds() {
            processEventSnapshotBuilds++;
        }

        @Override
        public void incrementProcessEventSnapshotFailures() {
            processEventSnapshotFailures++;
        }

        @Override
        public void addProcessEventSnapshotConstructionNanos(long nanos) {
            processEventSnapshotConstructionSamples++;
            processEventSnapshotConstructionNanos += nanos;
        }
    }
}
