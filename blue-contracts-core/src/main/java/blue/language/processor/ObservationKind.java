package blue.language.processor;

/**
 * Describes how a processing observation is aggregated.
 *
 * <p>The kind is part of the observation rather than being inferred from a
 * string suffix. This keeps exporters in different runtimes aligned on the
 * same counter and gauge semantics.</p>
 */
public enum ObservationKind {

    /** Adds the observation value to an accumulated total. */
    COUNTER_DELTA,

    /** Replaces the current gauge value. */
    GAUGE_VALUE,

    /** Retains the greatest value observed for the gauge. */
    HIGH_WATER_MARK
}
