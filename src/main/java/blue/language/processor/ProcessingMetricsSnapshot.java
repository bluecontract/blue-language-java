package blue.language.processor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable point-in-time view of production processing counters and gauges.
 */
public final class ProcessingMetricsSnapshot {

    private final Map<String, Long> counters;
    private final Map<String, Long> gauges;

    ProcessingMetricsSnapshot(Map<String, Long> counters, Map<String, Long> gauges) {
        this.counters = Collections.unmodifiableMap(new LinkedHashMap<>(counters));
        this.gauges = Collections.unmodifiableMap(new LinkedHashMap<>(gauges));
    }

    public Map<String, Long> counters() {
        return counters;
    }

    public Map<String, Long> gauges() {
        return gauges;
    }

    public long counter(String name) {
        Long value = counters.get(name);
        return value != null ? value : 0L;
    }

    public long gauge(String name) {
        Long value = gauges.get(name);
        return value != null ? value : 0L;
    }

    @Override
    public String toString() {
        return "ProcessingMetricsSnapshot{" +
                "counters=" + counters +
                ", gauges=" + gauges +
                '}';
    }
}
