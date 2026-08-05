package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordingProcessingObserverTest {

    @Test
    void shouldSnapshotCountersGaugesAndHighWaterImmutably() {
        // given
        RecordingProcessingObserver sink = new RecordingProcessingObserver();
        record(sink, ProcessingMetricId.PATCH_IMPACT_ANALYSES, 1L);
        record(sink, ProcessingMetricId.PATCH_IMPACT_ANALYSES, 1L);
        record(sink, ProcessingMetricId.FULL_SNAPSHOT_FALLBACKS, 1L);
        record(sink, ProcessingMetricId.FULL_SNAPSHOT_FALLBACK_REASON, 1L,
                ProcessingObservationDimension.FALLBACK_REASON,
                "ROOT_REPLACEMENT");
        record(sink, ProcessingMetricId.PROCESS_DOCUMENT_NANOS, 7L);
        record(sink, ProcessingMetricId.PROCESS_DOCUMENT_NANOS, 5L);
        record(sink, ProcessingMetricId.BUNDLE_LOAD_NANOS, 11L);
        record(sink, ProcessingMetricId.BUNDLE_LOAD_CACHE_HITS, 1L);
        record(sink, ProcessingMetricId.HANDLER_EXECUTION_NANOS, 13L);
        record(sink, ProcessingMetricId.HANDLERS_EXECUTED, 1L);
        record(sink, ProcessingMetricId.CACHE_CURRENT_WEIGHT_BYTES, 100L,
                ProcessingObservationDimension.CACHE_NAME,
                "resolvedSnapshots");
        record(sink, ProcessingMetricId.CACHE_HIGH_WATER_BYTES, 100L,
                ProcessingObservationDimension.CACHE_NAME,
                "resolvedSnapshots");
        record(sink, ProcessingMetricId.CACHE_CURRENT_WEIGHT_BYTES, 40L,
                ProcessingObservationDimension.CACHE_NAME,
                "resolvedSnapshots");
        record(sink, ProcessingMetricId.CACHE_HIGH_WATER_BYTES, 40L,
                ProcessingObservationDimension.CACHE_NAME,
                "resolvedSnapshots");

        // when
        ProcessingMetricsSnapshot first = sink.snapshot();
        record(sink, ProcessingMetricId.PATCH_IMPACT_ANALYSES, 1L);
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
        RecordingProcessingObserver sink = new RecordingProcessingObserver();
        int threads = 8;
        int iterations = 2_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        record(sink,
                                ProcessingMetricId.INCREMENTAL_SNAPSHOT_RESOLUTIONS,
                                1L);
                        record(sink,
                                ProcessingMetricId.CACHE_HIGH_WATER_BYTES,
                                iteration,
                                ProcessingObservationDimension.CACHE_NAME,
                                "plans");
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
        RecordingProcessingObserver sink = new RecordingProcessingObserver();

        // when
        recordMutablePatch(sink,
                PatchSource.PROCESSOR_INITIALIZATION_MARKER);
        recordMutablePatch(sink, PatchSource.CONFORMANCE_FIXTURE);
        recordMutablePatch(sink, null);
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

    private static void record(
            RecordingProcessingObserver observer,
            ProcessingMetricId metricId,
            long value) {
        observer.record(ProcessingObservation.of(metricId, value));
    }

    private static void record(
            RecordingProcessingObserver observer,
            ProcessingMetricId metricId,
            long value,
            ProcessingObservationDimension dimension,
            String dimensionValue) {
        observer.record(ProcessingObservation.of(
                metricId,
                value,
                ProcessingObservationContext.of(
                        dimension, dimensionValue)));
    }

    private static void recordMutablePatch(
            RecordingProcessingObserver observer,
            PatchSource source) {
        PatchSource effective = source != null
                ? source
                : PatchSource.UNKNOWN_INTERNAL;
        record(observer, ProcessingMetricId.MUTABLE_PATCH_VALUES_FROZEN, 1L);
        record(observer,
                ProcessingMetricId.MUTABLE_PATCH_VALUES_FROZEN_BY_SOURCE,
                1L,
                ProcessingObservationDimension.PATCH_SOURCE,
                effective.name());
    }
}
