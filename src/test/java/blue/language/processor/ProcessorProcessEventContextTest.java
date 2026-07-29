package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import java.util.ArrayList;
import java.util.Collections;
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
    void shouldVerifyExplicitInitializeHasNoProcessEventForDocumentAndSnapshotExecutions() {
        // given
        DocumentProcessor owner = new DocumentProcessor();
        Node document = new Node();

        // when
        ResolvedSnapshot snapshot = snapshot(document);
        ProcessorEngine.Execution documentExecution =
                new ProcessorEngine.Execution(owner, document);
        ProcessorEngine.Execution snapshotExecution =
                new ProcessorEngine.Execution(owner, snapshot);
        ProcessorExecutionContext documentContext =
                documentExecution.createContext(
                        "/",
                        ContractBundle.empty(),
                        new Node(),
                        false);
        ProcessorExecutionContext snapshotContext =
                snapshotExecution.createContext(
                        "/",
                        ContractBundle.empty(),
                        new Node(),
                        false);
        boolean documentHasProcessEvent =
                documentContext.hasProcessEvent();
        boolean snapshotHasProcessEvent =
                snapshotContext.hasProcessEvent();
        FrozenNode documentProcessEvent =
                documentContext.frozenProcessEvent();
        FrozenNode snapshotProcessEvent =
                snapshotContext.frozenProcessEvent();

        // then
        assertFalse(documentHasProcessEvent);
        assertFalse(snapshotHasProcessEvent);
        assertNull(documentProcessEvent);
        assertNull(snapshotProcessEvent);
    }

    @Test
    void shouldVerifyHasProcessEventDoesNotFreezeAndFirstAccessFreezesOnce() {
        // given
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
        // when
        ProcessorExecutionContext second = execution.createContext("/", ContractBundle.empty(), new Node(), false);
        boolean firstHasProcessEvent = first.hasProcessEvent();
        boolean secondHasProcessEvent = second.hasProcessEvent();
        int callsBeforeSnapshot = freezerCalls.get();
        long attemptsBeforeSnapshot =
                metrics.processEventSnapshotAttempts;
        FrozenNode snapshot = first.frozenProcessEvent();
        FrozenNode secondSnapshot = second.frozenProcessEvent();
        FrozenNode repeatedFirstSnapshot =
                first.frozenProcessEvent();
        int freezerCallCount = freezerCalls.get();
        long snapshotAttempts =
                metrics.processEventSnapshotAttempts;
        long snapshotBuilds =
                metrics.processEventSnapshotBuilds;
        long snapshotFailures =
                metrics.processEventSnapshotFailures;
        long constructionSamples =
                metrics.processEventSnapshotConstructionSamples;
        long constructionNanos =
                metrics.processEventSnapshotConstructionNanos;

        // then
        assertTrue(firstHasProcessEvent);
        assertTrue(secondHasProcessEvent);
        assertEquals(0, callsBeforeSnapshot);
        assertEquals(0L, attemptsBeforeSnapshot);
        assertSame(snapshot, secondSnapshot,
                "the package-private execution seam may verify the memoized optimization");
        assertSame(snapshot, repeatedFirstSnapshot);
        assertEquals(1, freezerCallCount);
        assertSnapshotKind(snapshot, "root");
        assertEquals(1L, snapshotAttempts);
        assertEquals(1L, snapshotBuilds);
        assertEquals(0L, snapshotFailures);
        assertEquals(1L, constructionSamples);
        assertTrue(constructionNanos >= 0L);
    }

    @Test
    void shouldVerifySnapshotFailureIsStableAndUsesBoundedMetrics() {
        // given
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

        // when
        IllegalStateException first =
                FailureCapture.captureFailure(
                        context::frozenProcessEvent);
        IllegalStateException second =
                FailureCapture.captureFailure(
                        context::frozenProcessEvent);
        int freezerCallCount = freezerCalls.get();
        long snapshotAttempts =
                metrics.processEventSnapshotAttempts;
        long snapshotBuilds =
                metrics.processEventSnapshotBuilds;
        long snapshotFailures =
                metrics.processEventSnapshotFailures;
        long constructionSamples =
                metrics.processEventSnapshotConstructionSamples;

        // then
        assertNotNull(first);
        assertNotNull(second);
        assertSame(expected, first);
        assertSame(first, second, "a failed snapshot must not be rebuilt or replaced");
        assertEquals(1, freezerCallCount);
        assertEquals(1L, snapshotAttempts);
        assertEquals(0L, snapshotBuilds);
        assertEquals(1L, snapshotFailures);
        assertEquals(1L, constructionSamples);
    }

    @Test
    void shouldVerifyNullSnapshotFactoryResultIsAStableFailure() {
        // given
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

        // when
        IllegalStateException failure =
                FailureCapture.captureFailure(
                        context::frozenProcessEvent);
        IllegalStateException repeatedFailure =
                FailureCapture.captureFailure(
                        context::frozenProcessEvent);
        int freezerCallCount = freezerCalls.get();
        long snapshotAttempts =
                metrics.processEventSnapshotAttempts;
        long snapshotFailures =
                metrics.processEventSnapshotFailures;
        long snapshotBuilds =
                metrics.processEventSnapshotBuilds;

        // then
        assertNotNull(failure);
        assertSame(failure, repeatedFailure);
        assertEquals("Processing Event snapshot construction returned null", failure.getMessage());
        assertEquals(1, freezerCallCount);
        assertEquals(1L, snapshotAttempts);
        assertEquals(1L, snapshotFailures);
        assertEquals(0L, snapshotBuilds);
    }

    @Test
    void shouldVerifyConcurrentFirstAccessBuildsOnceAndPublishesOneSnapshot() throws Exception {
        // given
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

        // when
        boolean readersBecameReady = false;
        boolean executorTerminated = false;
        FrozenNode expected = null;
        List<FrozenNode> snapshots = new ArrayList<>();
        try {
            for (int index = 0; index < CONCURRENT_READER_COUNT; index++) {
                reads.add(executor.submit(() -> {
                    readersReady.countDown();
                    awaitLatch(startReaders, "concurrent snapshot readers to start");
                    readAttempts.countDown();
                    return context.frozenProcessEvent();
                }));
            }
            readersBecameReady = readersReady.await(
                    CONCURRENCY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            startReaders.countDown();

            expected = reads.get(0).get(
                    CONCURRENCY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            for (Future<FrozenNode> read : reads) {
                snapshots.add(read.get(
                        CONCURRENCY_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS));
            }
        } finally {
            startReaders.countDown();
            executorTerminated = shutdownExecutor(executor);
        }
        int freezerCallCount = freezerCalls.get();
        long snapshotAttempts =
                metrics.processEventSnapshotAttempts;
        long snapshotBuilds =
                metrics.processEventSnapshotBuilds;
        long snapshotFailures =
                metrics.processEventSnapshotFailures;
        long constructionSamples =
                metrics.processEventSnapshotConstructionSamples;

        // then
        assertTrue(readersBecameReady,
                "all concurrent readers should be ready");
        assertNotNull(expected);
        for (FrozenNode snapshot : snapshots) {
            assertSame(expected, snapshot);
        }
        assertTrue(executorTerminated,
                "concurrent reader executor should terminate");
        assertSnapshotKind(expected, "concurrent-root");
        assertEquals(1, freezerCallCount);
        assertEquals(1L, snapshotAttempts);
        assertEquals(1L, snapshotBuilds);
        assertEquals(0L, snapshotFailures);
        assertEquals(1L, constructionSamples);
    }

    @Test
    void shouldVerifyConcurrentFailedFirstAccessPublishesOneFailureWithoutRetry() throws Exception {
        // given
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

        // when
        boolean readersBecameReady = false;
        boolean executorTerminated = false;
        List<ExecutionException> failures = new ArrayList<>();
        IllegalStateException cachedFailure = null;
        try {
            for (int index = 0; index < CONCURRENT_READER_COUNT; index++) {
                reads.add(executor.submit(() -> {
                    readersReady.countDown();
                    awaitLatch(startReaders, "concurrent failed snapshot readers to start");
                    readAttempts.countDown();
                    return context.frozenProcessEvent();
                }));
            }
            readersBecameReady = readersReady.await(
                    CONCURRENCY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            startReaders.countDown();

            for (Future<FrozenNode> read : reads) {
                failures.add(FailureCapture.captureFailure(
                        () -> read.get(
                                CONCURRENCY_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS)));
            }
            cachedFailure = FailureCapture.captureFailure(
                    context::frozenProcessEvent);
        } finally {
            startReaders.countDown();
            executorTerminated = shutdownExecutor(executor);
        }
        int freezerCallCount = freezerCalls.get();
        long snapshotAttempts =
                metrics.processEventSnapshotAttempts;
        long snapshotBuilds =
                metrics.processEventSnapshotBuilds;
        long snapshotFailures =
                metrics.processEventSnapshotFailures;
        long constructionSamples =
                metrics.processEventSnapshotConstructionSamples;

        // then
        assertTrue(readersBecameReady,
                "all concurrent readers should be ready");
        assertEquals(CONCURRENT_READER_COUNT, failures.size());
        for (ExecutionException failure : failures) {
            assertNotNull(failure);
            assertSame(expected, failure.getCause());
        }
        assertTrue(executorTerminated,
                "concurrent reader executor should terminate");
        assertSame(expected, cachedFailure);
        assertEquals(1, freezerCallCount);
        assertEquals(1L, snapshotAttempts);
        assertEquals(0L, snapshotBuilds);
        assertEquals(1L, snapshotFailures);
        assertEquals(1L, constructionSamples);
    }

    @Test
    void shouldVerifyDirectAndTriggeredHandlersShareOneSnapshotWhileCurrentEventsDiffer() {
        // given
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

        // when
        DocumentProcessingResult result = blue.getDocumentProcessor().processDocument(initialized, processEvent("root"));
        Observation direct = capture.only("emitFirst");
        Observation triggered = capture.only("captureTriggered");
        long snapshotAttempts =
                metrics.processEventSnapshotAttempts;
        long snapshotBuilds =
                metrics.processEventSnapshotBuilds;

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertEquals("root", eventKind(direct.currentEvent));
        assertEquals("first", eventKind(triggered.currentEvent));
        assertSnapshotKind(direct.processEvent, "root");
        assertSnapshotKind(triggered.processEvent, "root");
        assertSame(direct.processEvent, triggered.processEvent,
                "one execution must reuse its completed immutable snapshot");
        assertEquals(1L, snapshotAttempts);
        assertEquals(1L, snapshotBuilds);
    }

    @Test
    void shouldVerifyMultiHopTriggeredHandlersKeepTheRootContext() {
        // given
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

        // when
        DocumentProcessingResult result =
                blue.getDocumentProcessor().processDocument(
                        initialized,
                        processEvent("root"));
        Observation first = capture.only("captureFirst");
        Observation second = capture.only("captureSecond");

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals("first", eventKind(first.currentEvent));
        assertEquals("second", eventKind(second.currentEvent));
        assertSnapshotKind(first.processEvent, "root");
        assertSnapshotKind(second.processEvent, "root");
    }

    @Test
    void shouldVerifyImplicitInitializationSharesTheProcessEventWithLifecycleHandlers() {
        // given
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

        // when
        DocumentProcessingResult result = blue.getDocumentProcessor().processDocument(document, processEvent("root"));
        Observation lifecycle = capture.only("captureLifecycle");
        Observation direct = capture.only("captureDirect");

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED,
                lifecycle.currentEvent.getType().getBlueId());
        assertEquals("root", eventKind(direct.currentEvent));
        assertSnapshotKind(lifecycle.processEvent, "root");
        assertSnapshotKind(direct.processEvent, "root");
    }

    @Test
    void shouldVerifyEmbeddedAndBridgedHandlersKeepTheRootContext() {
        // given
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
                        "    sourcePath: /child\n" +
                        handler("captureBridge", "childBridge", 2))).document();
        capture.clear();
        String expectedBridgeEventBlueId =
                CheckpointIdentityCalculator.identity(
                        new Node().properties(
                                "kind",
                                new Node().value("bridge")));

        // when
        DocumentProcessingResult result =
                blue.getDocumentProcessor().processDocument(
                        initialized,
                        processEvent("root"));
        Observation child = capture.only("captureChild");
        Observation bridge = capture.only("captureBridge");

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals("root", eventKind(child.currentEvent));
        assertEmbeddedEventDelivery(
                bridge.currentEvent,
                "/child",
                expectedBridgeEventBlueId);
        assertSnapshotKind(child.processEvent, "root");
        assertSnapshotKind(bridge.processEvent, "root");
    }

    @Test
    void shouldVerifyChannelAdaptationDoesNotReplaceTheProcessEvent() {
        // given
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

        // when
        DocumentProcessingResult result =
                blue.getDocumentProcessor().processDocument(
                        initialized,
                        processEvent("root"));
        Observation adapted = capture.only("captureAdapted");

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals("adapted", eventKind(adapted.currentEvent));
        assertSnapshotKind(adapted.processEvent, "root");
    }

    @Test
    void shouldVerifyHandlerEventMutationCannotMutateTheFrozenProcessEvent() {
        // given
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

        // when
        DocumentProcessingResult result =
                blue.getDocumentProcessor().processDocument(
                        initialized,
                        processEvent("root"));
        Observation mutated = capture.only("mutateCurrent");
        Observation afterMutation =
                capture.only("captureAfterMutation");

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertEquals("mutated-current", eventKind(mutated.currentEvent));
        assertEquals("root", eventKind(afterMutation.currentEvent));
        assertSnapshotKind(mutated.processEvent, "root");
        assertSnapshotKind(afterMutation.processEvent, "root");
    }

    @Test
    void shouldVerifySeparateProcessRunsDoNotLeakContextAndBothProcessOverloadsRetainInput() {
        // given
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

        // when
        DocumentProcessingResult directResult =
                blue.getDocumentProcessor().processDocument(
                        initialized.document(),
                        processEvent("direct-root"));
        Observation direct = capture.only("capture");
        capture.clear();
        DocumentProcessingResult snapshotResult =
                blue.getDocumentProcessor().processDocument(
                        DocumentProcessingResultTestSupport.snapshot(
                                blue,
                                initialized),
                        processEvent("snapshot-root"));
        Observation fromSnapshot = capture.only("capture");
        long snapshotAttempts =
                metrics.processEventSnapshotAttempts;
        long snapshotBuilds =
                metrics.processEventSnapshotBuilds;

        // then
        assertEquals(ProcessorStatus.SUCCESS, directResult.status());
        assertEquals(ProcessorStatus.SUCCESS, snapshotResult.status());
        assertSnapshotKind(direct.processEvent, "direct-root");
        assertSnapshotKind(
                fromSnapshot.processEvent,
                "snapshot-root");
        assertEquals(2L, snapshotAttempts);
        assertEquals(2L, snapshotBuilds);
    }

    @Test
    void shouldVerifyUnusedContextDoesNotBuildSnapshotForWideOrDeepEventsAcrossProcessOverloads() {
        // given
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

        // when
        DocumentProcessingResult wideDocumentResult =
                blue.getDocumentProcessor().processDocument(
                        initialized.document(),
                        wide);
        DocumentProcessingResult deepDocumentResult =
                blue.getDocumentProcessor().processDocument(
                        initialized.document(),
                        deep);
        ResolvedSnapshot wideInputSnapshot =
                DocumentProcessingResultTestSupport.snapshot(
                        blue,
                        initialized);
        DocumentProcessingResult wideSnapshotResult =
                blue.getDocumentProcessor().processDocument(
                        wideInputSnapshot,
                        wide);
        ResolvedSnapshot deepInputSnapshot =
                DocumentProcessingResultTestSupport.snapshot(
                        blue,
                        initialized);
        DocumentProcessingResult deepSnapshotResult =
                blue.getDocumentProcessor().processDocument(
                        deepInputSnapshot,
                        deep);
        long snapshotAttempts =
                metrics.processEventSnapshotAttempts;
        long snapshotBuilds =
                metrics.processEventSnapshotBuilds;
        long snapshotFailures =
                metrics.processEventSnapshotFailures;
        long constructionSamples =
                metrics.processEventSnapshotConstructionSamples;

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                wideDocumentResult.status());
        assertEquals(
                ProcessorStatus.SUCCESS,
                deepDocumentResult.status());
        assertEquals(
                ProcessorStatus.SUCCESS,
                wideSnapshotResult.status());
        assertEquals(
                ProcessorStatus.SUCCESS,
                deepSnapshotResult.status());
        assertEquals(0L, snapshotAttempts);
        assertEquals(0L, snapshotBuilds);
        assertEquals(0L, snapshotFailures);
        assertEquals(0L, constructionSamples);
    }

    private static Blue configuredBlue(CapturingHandler capture,
                                       ChannelProcessor<TestEventChannel> channelProcessor,
                                       RecordingMetrics metrics) {
        Blue blue = ProcessorTestSupport.blue();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        ChannelProcessor<TestEventChannel> exactChannelProcessor =
                channelProcessor.getClass() == TestEventChannelProcessor.class
                        ? DocumentProcessorExactFeederSupport
                                .testEventChannelProcessor()
                        : channelProcessor;
        blue.registerContractProcessor(exactChannelProcessor);
        if (capture != null) {
            blue.registerContractProcessor(capture);
        }
        DocumentProcessorExactFeederSupport.install(blue);
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

    private static boolean shutdownExecutor(
            ExecutorService executor)
            throws InterruptedException {
        executor.shutdownNow();
        return executor.awaitTermination(
                CONCURRENCY_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
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

    private static void assertEmbeddedEventDelivery(
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
        private final ExternalChannelSubscriptionFunctions<TestEventChannel>
                subscriptionFunctions =
                new ExternalChannelSubscriptionFunctions<TestEventChannel>() {
                    @Override
                    public List<String> channelKeys(
                            TestEventChannel contract) {
                        return Collections.singletonList(
                                contract.getEventType() != null
                                        ? contract.getEventType()
                                        : TEST_EVENT_TYPE);
                    }

                    @Override
                    public List<String> eventKeys(Node event) {
                        Node type = event != null ? event.getType() : null;
                        return type != null && type.getBlueId() != null
                                ? Collections.singletonList(type.getBlueId())
                                : Collections.<String>emptyList();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            TestEventChannel contract) {
                        return null;
                    }

                    @Override
                    public Node payload(
                            TestEventChannel immutableContractSnapshot,
                            Node exactEvent) {
                        Node adapted = exactEvent.clone();
                        adapted.properties(
                                "kind", new Node().value("adapted"));
                        return adapted;
                    }
                };

        @Override
        public Class<TestEventChannel> contractType() {
            return TestEventChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<TestEventChannel>
        externalSubscriptionFunctions() {
            return subscriptionFunctions;
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
