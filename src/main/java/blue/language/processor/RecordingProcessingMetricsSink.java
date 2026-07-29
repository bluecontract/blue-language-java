package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metrics sink intended for diagnostics, tests, and integration
 * reports. Normal production deployments may continue to use the no-op sink or
 * their existing metrics adapter.
 */
public final class RecordingProcessingMetricsSink implements ProcessingMetricsSink {

    private final ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    /**
     * Creates an empty thread-safe recording sink.
     */
    public RecordingProcessingMetricsSink() {
    }

    /**
     * Atomically adds a signed delta to an additive counter.
     *
     * @param metricName non-empty counter name
     * @param delta signed amount to add
     * @throws IllegalArgumentException when {@code metricName} is null or empty
     */
    @Override
    public void addMetric(String metricName, long delta) {
        requireMetricName(metricName);
        counters.computeIfAbsent(metricName, ignored -> new AtomicLong()).addAndGet(delta);
    }

    /**
     * Atomically replaces the current value of a gauge.
     *
     * @param metricName non-empty gauge name
     * @param value new gauge value
     * @throws IllegalArgumentException when {@code metricName} is null or empty
     */
    @Override
    public void setMetric(String metricName, long value) {
        requireMetricName(metricName);
        gauges.computeIfAbsent(metricName, ignored -> new AtomicLong()).set(value);
    }

    /**
     * Atomically raises a gauge while never lowering its recorded maximum.
     *
     * @param metricName non-empty gauge name
     * @param value candidate high-water value
     * @throws IllegalArgumentException when {@code metricName} is null or empty
     */
    @Override
    public void recordMetricHighWater(String metricName, long value) {
        requireMetricName(metricName);
        AtomicLong highWater = gauges.computeIfAbsent(metricName, ignored -> new AtomicLong());
        long current = highWater.get();
        while (value > current && !highWater.compareAndSet(current, value)) {
            current = highWater.get();
        }
    }

    /**
     * Captures counters and gauges in deterministic metric-name order.
     *
     * @return immutable point-in-time counter and gauge snapshot
     */
    public ProcessingMetricsSnapshot snapshot() {
        return new ProcessingMetricsSnapshot(sortedValues(counters), sortedValues(gauges));
    }

    /**
     * Clears all recorded counters and gauges.
     *
     * <p>Concurrent updates may race with this administrative operation; the
     * sink remains valid and thread-safe afterward.</p>
     */
    public void clear() {
        counters.clear();
        gauges.clear();
    }

    private Map<String, Long> sortedValues(ConcurrentMap<String, AtomicLong> source) {
        List<String> names = new ArrayList<>(source.keySet());
        Collections.sort(names);
        Map<String, Long> values = new LinkedHashMap<>();
        for (String name : names) {
            AtomicLong value = source.get(name);
            if (value != null) {
                values.put(name, value.get());
            }
        }
        return values;
    }

    private void requireMetricName(String metricName) {
        if (metricName == null || metricName.isEmpty()) {
            throw new IllegalArgumentException("metricName must not be empty");
        }
    }
}
