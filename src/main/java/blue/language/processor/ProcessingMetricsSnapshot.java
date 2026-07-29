package blue.language.processor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable point-in-time view of production processing counters and gauges.
 *
 * <p>Both maps are defensive unmodifiable copies. Missing names read as zero
 * through the typed accessors, allowing metrics to evolve without exposing a
 * mutable sink.</p>
 */
public final class ProcessingMetricsSnapshot {

    private final Map<String, Long> counters;
    private final Map<String, Long> gauges;

    ProcessingMetricsSnapshot(Map<String, Long> counters, Map<String, Long> gauges) {
        this.counters = Collections.unmodifiableMap(new LinkedHashMap<>(counters));
        this.gauges = Collections.unmodifiableMap(new LinkedHashMap<>(gauges));
    }

    /**
     * Returns all additive counters captured by this snapshot.
     *
     * @return immutable additive counter map
     */
    public Map<String, Long> counters() {
        return counters;
    }

    /**
     * Returns all current-value gauges captured by this snapshot.
     *
     * @return immutable current-value gauge map
     */
    public Map<String, Long> gauges() {
        return gauges;
    }

    /**
     * Reads one additive counter.
     *
     * @param name metric name
     * @return current value, or zero when absent
     */
    public long counter(String name) {
        Long value = counters.get(name);
        return value != null ? value : 0L;
    }

    /**
     * Reads one current-value gauge.
     *
     * @param name metric name
     * @return current value, or zero when absent
     */
    public long gauge(String name) {
        Long value = gauges.get(name);
        return value != null ? value : 0L;
    }

    /**
     * Returns a deterministic diagnostic representation of both metric maps.
     *
     * @return snapshot description containing counters and gauges
     */
    @Override
    public String toString() {
        return "ProcessingMetricsSnapshot{" +
                "counters=" + counters +
                ", gauges=" + gauges +
                '}';
    }
}
