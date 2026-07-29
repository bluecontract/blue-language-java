package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordingProcessingMetricsSinkTest {

    @Test
    void shouldSnapshotCountersGaugesAndHighWaterImmutably() {
        // given
        RecordingProcessingMetricsSink sink = new RecordingProcessingMetricsSink();
        sink.incrementPatchImpactAnalyses();
        sink.incrementPatchImpactAnalyses();
        sink.incrementFullSnapshotFallback("ROOT_REPLACEMENT");
        sink.addProcessDocumentNanos(7L);
        sink.addProcessDocumentNanos(5L);
        sink.addBundleLoadNanos(11L);
        sink.incrementBundleLoadCacheHits();
        sink.addHandlerExecutionNanos(13L);
        sink.incrementHandlersExecuted();
        sink.setCacheCurrentWeightBytes("resolvedSnapshots", 100L);
        sink.recordCacheHighWaterBytes("resolvedSnapshots", 100L);
        sink.setCacheCurrentWeightBytes("resolvedSnapshots", 40L);
        sink.recordCacheHighWaterBytes("resolvedSnapshots", 40L);

        // when
        ProcessingMetricsSnapshot first = sink.snapshot();
        sink.incrementPatchImpactAnalyses();
        ProcessingMetricsSnapshot second = sink.snapshot();

        // then
        assertEquals(2L, first.counter("patchImpactAnalyses"));
        assertEquals(1L, first.counter("fullSnapshotFallbacks"));
        assertEquals(1L, first.counter("fullSnapshotFallbackReason.ROOT_REPLACEMENT"));
        assertEquals(12L, first.counter("processDocumentNanos"));
        assertEquals(11L, first.counter("bundleLoadNanos"));
        assertEquals(1L, first.counter("bundleLoadCacheHits"));
        assertEquals(13L, first.counter("handlerExecutionNanos"));
        assertEquals(1L, first.counter("handlersExecuted"));
        assertEquals(40L, first.gauge("cache.resolvedSnapshots.currentWeightBytes"));
        assertEquals(100L, first.gauge("cache.resolvedSnapshots.highWaterBytes"));
        assertThrows(UnsupportedOperationException.class,
                () -> first.counters().put("other", 1L));

        assertEquals(2L, first.counter("patchImpactAnalyses"));
        assertEquals(3L, second.counter("patchImpactAnalyses"));
    }

    @Test
    void shouldNotLoseConcurrentUpdates() throws Exception {
        // given
        RecordingProcessingMetricsSink sink = new RecordingProcessingMetricsSink();
        int threads = 8;
        int iterations = 2_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        sink.incrementIncrementalSnapshotResolutions();
                        sink.recordCacheHighWaterBytes("plans", iteration);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        // when
        ProcessingMetricsSnapshot snapshot = sink.snapshot();
        // then
        assertEquals((long) threads * iterations,
                snapshot.counter("incrementalSnapshotResolutions"));
        assertEquals(iterations - 1L, snapshot.gauge("cache.plans.highWaterBytes"));
    }

    @Test
    void shouldAttributeMutablePatchesUsingFixedSourceNames() {
        // given
        RecordingProcessingMetricsSink sink = new RecordingProcessingMetricsSink();

        // when
        sink.incrementMutablePatchValuesFrozen(PatchSource.PROCESSOR_INITIALIZATION_MARKER);
        sink.incrementMutablePatchValuesFrozen(PatchSource.CONFORMANCE_FIXTURE);
        sink.incrementMutablePatchValuesFrozen(null);
        ProcessingMetricsSnapshot snapshot = sink.snapshot();

        // then
        assertEquals(3L, snapshot.counter("mutablePatchValuesFrozen"));
        assertEquals(1L, snapshot.counter(
                "mutablePatchValuesFrozenBySource.PROCESSOR_INITIALIZATION_MARKER"));
        assertEquals(1L, snapshot.counter(
                "mutablePatchValuesFrozenBySource.CONFORMANCE_FIXTURE"));
        assertEquals(1L, snapshot.counter(
                "mutablePatchValuesFrozenBySource.UNKNOWN_INTERNAL"));
    }
}
