package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingObserverTest {

    @Test
    void shouldCreateImmutableTypedObservation() {
        // given
        ProcessingObservationContext context = ProcessingObservationContext.of(
                ProcessingObservationDimension.CACHE_NAME,
                "resolvedSnapshots");

        // when
        ProcessingObservation observation = ProcessingObservation.of(
                ProcessingMetricId.CACHE_HITS,
                3L,
                context);

        // then
        assertEquals(ProcessingMetricId.CACHE_HITS, observation.metricId());
        assertEquals(ObservationKind.COUNTER_DELTA, observation.kind());
        assertEquals(3L, observation.value());
        assertEquals(context, observation.context());
        assertEquals("cache.resolvedSnapshots.hits", observation.legacyMetricName());
        assertThrows(UnsupportedOperationException.class,
                () -> context.dimensions().put(
                        ProcessingObservationDimension.FALLBACK_REASON,
                        "OTHER"));
    }

    @Test
    void shouldRejectUnboundedOrUnexpectedContext() {
        // given
        String oversized = repeat('a', ProcessingObservationContext.MAX_VALUE_LENGTH + 1);

        // when
        Throwable oversizedFailure = FailureCapture.captureFailure(
                () -> ProcessingObservationContext.of(
                        ProcessingObservationDimension.CACHE_NAME,
                        oversized));
        Throwable payloadFailure = FailureCapture.captureFailure(
                () -> ProcessingObservationContext.of(
                        ProcessingObservationDimension.CACHE_NAME,
                        "/document/private/value"));
        Throwable unexpectedFailure = FailureCapture.captureFailure(
                () -> ProcessingObservation.of(
                        ProcessingMetricId.PROCESS_DOCUMENT_NANOS,
                        1L,
                        ProcessingObservationContext.of(
                                ProcessingObservationDimension.CACHE_NAME,
                                "cache")));

        // then
        assertTrue(assertInstanceOf(
                IllegalArgumentException.class,
                oversizedFailure).getMessage().contains("exceeds"));
        assertTrue(assertInstanceOf(
                IllegalArgumentException.class,
                payloadFailure).getMessage().contains(
                        "unsupported character"));
        assertTrue(assertInstanceOf(
                IllegalArgumentException.class,
                unexpectedFailure).getMessage().contains(
                        "does not accept"));
    }

    @Test
    void shouldAggregateTypedMetricsAndBoundRecentTail() {
        // given
        RecordingProcessingObserver observer = new RecordingProcessingObserver(2);
        ProcessingObservationContext cache = ProcessingObservationContext.of(
                ProcessingObservationDimension.CACHE_NAME,
                "plans");

        // when
        observer.record(ProcessingObservation.of(
                ProcessingMetricId.PATCH_IMPACT_ANALYSES, 2L));
        observer.record(ProcessingObservation.of(
                ProcessingMetricId.PATCH_IMPACT_ANALYSES, 3L));
        observer.record(ProcessingObservation.of(
                ProcessingMetricId.CACHE_CURRENT_WEIGHT_BYTES, 100L, cache));
        observer.record(ProcessingObservation.of(
                ProcessingMetricId.CACHE_CURRENT_WEIGHT_BYTES, 40L, cache));
        observer.record(ProcessingObservation.of(
                ProcessingMetricId.CACHE_HIGH_WATER_BYTES, 100L, cache));
        observer.record(ProcessingObservation.of(
                ProcessingMetricId.CACHE_HIGH_WATER_BYTES, 40L, cache));
        ProcessingMetricsSnapshot snapshot = observer.snapshot();
        List<ProcessingObservation> recent = observer.observations();

        // then
        assertEquals(5L, observer.value(
                ProcessingMetricId.PATCH_IMPACT_ANALYSES,
                ProcessingObservationContext.empty()));
        assertEquals(5L, snapshot.counter("patchImpactAnalyses"));
        assertEquals(40L, snapshot.gauge("cache.plans.currentWeightBytes"));
        assertEquals(100L, snapshot.gauge("cache.plans.highWaterBytes"));
        assertEquals(100L, snapshot.gauge(
                ProcessingMetricId.CACHE_HIGH_WATER_BYTES,
                cache));
        assertEquals(2, recent.size());
        assertEquals(100L, recent.get(0).value());
        assertEquals(40L, recent.get(1).value());
        assertThrows(UnsupportedOperationException.class,
                () -> recent.add(ProcessingObservation.of(
                        ProcessingMetricId.RUNTIME_CLOSE_CALLS, 1L)));
    }

    @Test
    void shouldIsolateFailingObserverFromProcessingAndOtherObservers() {
        // given
        ProcessingObserver failing = observation -> {
            throw new IllegalStateException("exporter unavailable");
        };
        RecordingProcessingObserver recording = new RecordingProcessingObserver(1);
        CompositeProcessingObserver composite =
                new CompositeProcessingObserver(failing, recording);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> ProcessingObservations.record(
                        composite,
                        ProcessingMetricId.HANDLERS_EXECUTED,
                        1L));

        // then
        assertNull(failure);
        assertEquals(1L, recording.value(
                ProcessingMetricId.HANDLERS_EXECUTED,
                ProcessingObservationContext.empty()));
    }

    @Test
    void shouldDispatchTypedObservationsWithoutNameBasedAdapters() {
        // given
        AtomicLong patches = new AtomicLong();
        AtomicLong sequences = new AtomicLong();
        ProcessingObserver observer = new ProcessingObserver() {
            @Override
            public void record(ProcessingObservation observation) {
                if (observation.metricId()
                        == ProcessingMetricId.PATCHES_PREPARED) {
                    patches.addAndGet(observation.value());
                } else if (observation.metricId()
                        == ProcessingMetricId.PATCH_SEQUENCES_PREPARED) {
                    sequences.addAndGet(observation.value());
                }
            }
        };

        // when
        ProcessingObservations.record(
                observer,
                ProcessingMetricId.PATCHES_PREPARED,
                4L);
        ProcessingObservations.record(
                observer,
                ProcessingMetricId.PATCH_SEQUENCES_PREPARED,
                1L);

        // then
        assertEquals(4L, patches.get());
        assertEquals(1L, sequences.get());
    }

    @Test
    void shouldGenerateManifestFromEveryMetricId() {
        // given
        Set<String> names = new HashSet<>();

        // when
        String manifest = ProcessingMetricManifest.json();
        boolean uniqueNames = true;
        boolean containsEveryId = true;
        boolean containsEveryName = true;
        for (ProcessingMetricId metricId : ProcessingMetricId.values()) {
            uniqueNames &= names.add(metricId.externalName());
            containsEveryId &= manifest.contains(
                    "\"id\": \"" + metricId.name() + "\"");
            containsEveryName &= manifest.contains(
                    "\"name\": \"" + metricId.externalName() + "\"");
        }

        // then
        assertTrue(uniqueNames);
        assertTrue(containsEveryId);
        assertTrue(containsEveryName);
        assertTrue(ProcessingMetricId.values().length > 170);
        assertTrue(manifest.startsWith("{\n  \"schemaVersion\": 1"));
        assertTrue(manifest.endsWith("  ]\n}\n"));
    }

    @Test
    void shouldUseJfrAsOptionalOperationalSideChannel() {
        // given
        JfrProcessingObserver observer = new JfrProcessingObserver();

        // when
        boolean available = observer.isAvailable();

        // then
        assertDoesNotThrow(() -> observer.record(ProcessingObservation.of(
                ProcessingMetricId.PROCESS_DOCUMENT_NANOS, 10L)));
        assertDoesNotThrow(observer::close);
        assertEquals(available, observer.isAvailable());
    }

    private static String repeat(char character, int length) {
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            result.append(character);
        }
        return result.toString();
    }
}
