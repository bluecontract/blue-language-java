package blue.language.processor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe observer that aggregates all metrics and retains a bounded tail
 * of individual observations.
 */
public final class RecordingProcessingObserver implements ProcessingObserver {

    /** Default maximum number of individual observations retained. */
    public static final int DEFAULT_RECENT_CAPACITY = 4_096;

    private final ConcurrentMap<ObservationKey, AtomicLong> counters =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<ObservationKey, AtomicLong> gauges =
            new ConcurrentHashMap<>();
    private final int recentCapacity;
    private final Object recentLock = new Object();
    private final ArrayDeque<ProcessingObservation> recent = new ArrayDeque<>();

    /** Creates an observer with {@link #DEFAULT_RECENT_CAPACITY}. */
    public RecordingProcessingObserver() {
        this(DEFAULT_RECENT_CAPACITY);
    }

    /**
     * Creates an observer with a bounded individual-observation tail.
     *
     * @param recentCapacity maximum retained observations; zero disables the tail
     */
    public RecordingProcessingObserver(int recentCapacity) {
        if (recentCapacity < 0 || recentCapacity > 1_000_000) {
            throw new IllegalArgumentException(
                    "recentCapacity must be between 0 and 1000000");
        }
        this.recentCapacity = recentCapacity;
    }

    /**
     * Aggregates and, when enabled, retains one immutable observation.
     *
     * @param observation immutable observation
     */
    @Override
    public void record(ProcessingObservation observation) {
        if (observation == null) {
            return;
        }
        ObservationKey key = new ObservationKey(
                observation.metricId(), observation.context());
        switch (observation.kind()) {
            case COUNTER_DELTA:
                counters.computeIfAbsent(key, ignored -> new AtomicLong())
                        .addAndGet(observation.value());
                break;
            case GAUGE_VALUE:
                gauges.computeIfAbsent(key, ignored -> new AtomicLong())
                        .set(observation.value());
                break;
            case HIGH_WATER_MARK:
                raise(gauges.computeIfAbsent(key, ignored -> new AtomicLong()),
                        observation.value());
                break;
            default:
                throw new IllegalStateException(
                        "unsupported observation kind " + observation.kind());
        }
        retain(observation);
    }

    /**
     * Captures legacy-name counters and gauges for compatibility reporting.
     *
     * @return immutable point-in-time metrics snapshot
     */
    public ProcessingMetricsSnapshot snapshot() {
        return new ProcessingMetricsSnapshot(
                legacyValues(counters), legacyValues(gauges));
    }

    /**
     * Reads one typed aggregate.
     *
     * @param metricId metric identifier
     * @param context metric context
     * @return aggregate value or zero when absent
     */
    public long value(
            ProcessingMetricId metricId,
            ProcessingObservationContext context) {
        ObservationKey key = new ObservationKey(metricId, context);
        AtomicLong value = metricId.kind() == ObservationKind.COUNTER_DELTA
                ? counters.get(key)
                : gauges.get(key);
        return value != null ? value.get() : 0L;
    }

    /**
     * Returns the bounded retained observation tail in arrival order.
     *
     * @return immutable point-in-time list
     */
    public List<ProcessingObservation> observations() {
        synchronized (recentLock) {
            return Collections.unmodifiableList(new ArrayList<>(recent));
        }
    }

    /** Clears all aggregate and retained state. */
    public void clear() {
        counters.clear();
        gauges.clear();
        synchronized (recentLock) {
            recent.clear();
        }
    }

    private void retain(ProcessingObservation observation) {
        if (recentCapacity == 0) {
            return;
        }
        synchronized (recentLock) {
            while (recent.size() >= recentCapacity) {
                recent.removeFirst();
            }
            recent.addLast(observation);
        }
    }

    private static void raise(AtomicLong highWater, long candidate) {
        long current = highWater.get();
        while (candidate > current
                && !highWater.compareAndSet(current, candidate)) {
            current = highWater.get();
        }
    }

    private static Map<String, Long> legacyValues(
            ConcurrentMap<ObservationKey, AtomicLong> values) {
        List<Map.Entry<ObservationKey, AtomicLong>> entries =
                new ArrayList<>(values.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().legacyName()));
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<ObservationKey, AtomicLong> entry : entries) {
            result.put(entry.getKey().legacyName(), entry.getValue().get());
        }
        return result;
    }

    private static final class ObservationKey {

        private final ProcessingMetricId metricId;
        private final ProcessingObservationContext context;

        private ObservationKey(
                ProcessingMetricId metricId,
                ProcessingObservationContext context) {
            this.metricId = metricId;
            this.context = context;
        }

        private String legacyName() {
            return metricId.legacyName(context);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ObservationKey)) {
                return false;
            }
            ObservationKey that = (ObservationKey) other;
            return metricId == that.metricId && context.equals(that.context);
        }

        @Override
        public int hashCode() {
            return 31 * metricId.hashCode() + context.hashCode();
        }
    }
}
