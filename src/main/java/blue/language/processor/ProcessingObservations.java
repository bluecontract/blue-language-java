package blue.language.processor;

/**
 * Failure-isolating dispatch helpers used by the processing kernel.
 *
 * <p>Observation construction and exporter invocation happen outside gas
 * accounting. Non-fatal observer failures are discarded so enabling telemetry
 * cannot change a processing result, diagnostic, or gas trace.</p>
 */
final class ProcessingObservations {

    private ProcessingObservations() {
    }

    /**
     * Records a context-free metric without exposing observer failures.
     *
     * @param observer observer or {@code null}
     * @param metricId manifest metric identifier
     * @param value signed observation value
     */
    static void record(
            ProcessingObserver observer,
            ProcessingMetricId metricId,
            long value) {
        record(observer, metricId, value, ProcessingObservationContext.empty());
    }

    /**
     * Forwards an already constructed observation without exposing failures.
     *
     * @param observer observer or {@code null}
     * @param observation immutable observation
     */
    static void record(
            ProcessingObserver observer,
            ProcessingObservation observation) {
        if (observer == null || observation == null) {
            return;
        }
        try {
            observer.record(observation);
        } catch (ThreadDeath failure) {
            throw failure;
        } catch (VirtualMachineError failure) {
            throw failure;
        } catch (Throwable ignored) {
            // Observability is explicitly outside deterministic processing.
        }
    }

    /**
     * Records a contextual metric without exposing observer failures.
     *
     * @param observer observer or {@code null}
     * @param metricId manifest metric identifier
     * @param value signed observation value
     * @param context bounded typed context
     */
    static void record(
            ProcessingObserver observer,
            ProcessingMetricId metricId,
            long value,
            ProcessingObservationContext context) {
        if (observer == null) {
            return;
        }
        try {
            record(observer, ProcessingObservation.of(metricId, value, context));
        } catch (ThreadDeath failure) {
            throw failure;
        } catch (VirtualMachineError failure) {
            throw failure;
        } catch (Throwable ignored) {
            // Observability is explicitly outside deterministic processing.
        }
    }

    static void recordLegacy(
            ProcessingObserver observer,
            String legacyName,
            ObservationKind kind,
            long value) {
        if (observer == null) {
            return;
        }
        try {
            ProcessingObservation observation =
                    ProcessingObservation.fromLegacy(legacyName, kind, value);
            if (observation != null) {
                observer.record(observation);
            }
        } catch (ThreadDeath failure) {
            throw failure;
        } catch (VirtualMachineError failure) {
            throw failure;
        } catch (Throwable ignored) {
            // Legacy adapters have the same isolation contract as typed calls.
        }
    }
}
