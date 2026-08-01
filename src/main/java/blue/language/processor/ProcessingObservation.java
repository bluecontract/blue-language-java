package blue.language.processor;

import java.util.Objects;

/**
 * Immutable operational observation emitted by document processing.
 *
 * <p>An observation is deliberately limited to a manifest metric, its fixed
 * aggregation kind, one signed value, and bounded typed context. It has no
 * document or gas-ledger reference.</p>
 */
public final class ProcessingObservation {

    private final ProcessingMetricId metricId;
    private final ObservationKind kind;
    private final long value;
    private final ProcessingObservationContext context;

    private ProcessingObservation(
            ProcessingMetricId metricId,
            ObservationKind kind,
            long value,
            ProcessingObservationContext context) {
        this.metricId = Objects.requireNonNull(metricId, "metricId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.context = Objects.requireNonNull(context, "context");
        if (metricId.kind() != kind) {
            throw new IllegalArgumentException(
                    metricId + " requires observation kind " + metricId.kind());
        }
        validateContext(metricId, context);
        this.value = value;
    }

    /**
     * Creates an observation using the metric's fixed aggregation kind.
     *
     * @param metricId manifest metric identifier
     * @param value signed observation value
     * @return context-free immutable observation
     */
    public static ProcessingObservation of(ProcessingMetricId metricId, long value) {
        return of(metricId, value, ProcessingObservationContext.empty());
    }

    /**
     * Creates an observation using the metric's fixed aggregation kind.
     *
     * @param metricId manifest metric identifier
     * @param value signed observation value
     * @param context bounded typed context
     * @return immutable observation
     */
    public static ProcessingObservation of(
            ProcessingMetricId metricId,
            long value,
            ProcessingObservationContext context) {
        Objects.requireNonNull(metricId, "metricId");
        return new ProcessingObservation(metricId, metricId.kind(), value, context);
    }

    /** @return manifest metric identifier */
    public ProcessingMetricId metricId() {
        return metricId;
    }

    /** @return fixed aggregation kind */
    public ObservationKind kind() {
        return kind;
    }

    /** @return signed observation value */
    public long value() {
        return value;
    }

    /** @return bounded immutable context */
    public ProcessingObservationContext context() {
        return context;
    }

    /**
     * Returns the diagnostic name used by the pre-observer metrics API.
     *
     * <p>This is provided only for migration and legacy snapshot rendering.
     * New exporters should use {@link #metricId()} and {@link #context()}.</p>
     *
     * @return stable legacy metric name
     */
    public String legacyMetricName() {
        return metricId.legacyName(context);
    }

    static ProcessingObservation fromLegacy(
            String legacyName,
            ObservationKind kind,
            long value) {
        ProcessingMetricId.LegacyMetric metric =
                ProcessingMetricId.fromLegacyName(legacyName);
        if (metric == null || metric.id().kind() != kind) {
            return null;
        }
        return new ProcessingObservation(metric.id(), kind, value, metric.context());
    }

    private static void validateContext(
            ProcessingMetricId metricId,
            ProcessingObservationContext context) {
        ProcessingObservationDimension required = metricId.requiredDimension();
        if (required == null) {
            if (!context.isEmpty()) {
                throw new IllegalArgumentException(
                        metricId + " does not accept observation context");
            }
            return;
        }
        if (context.dimensions().size() != 1 || context.value(required) == null) {
            throw new IllegalArgumentException(
                    metricId + " requires exactly dimension " + required);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessingObservation)) {
            return false;
        }
        ProcessingObservation that = (ProcessingObservation) other;
        return value == that.value
                && metricId == that.metricId
                && kind == that.kind
                && context.equals(that.context);
    }

    @Override
    public int hashCode() {
        int result = metricId.hashCode();
        result = 31 * result + kind.hashCode();
        result = 31 * result + Long.hashCode(value);
        result = 31 * result + context.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ProcessingObservation{" +
                "metricId=" + metricId +
                ", kind=" + kind +
                ", value=" + value +
                ", context=" + context +
                '}';
    }
}
