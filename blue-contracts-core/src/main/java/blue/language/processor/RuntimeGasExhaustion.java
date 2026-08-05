package blue.language.processor;

import java.util.Objects;

/**
 * Runtime-neutral description of a hosted component's rejected gas charge.
 *
 * <p>Concrete runtimes can carry this value across their own exception
 * boundary and hand it back to {@link RuntimeWorkSession}.  They therefore do
 * not need to construct processor exceptions or recover structured data from
 * an error message.</p>
 */
public final class RuntimeGasExhaustion {

    private final String namespace;
    private final String counter;
    private final long quantity;
    private final long weight;
    private final long admittedGas;
    private final long effectiveBudget;
    private final GasLimitExceededException source;

    private RuntimeGasExhaustion(
            String namespace,
            String counter,
            long quantity,
            long weight,
            long admittedGas,
            long effectiveBudget,
            GasLimitExceededException source) {
        this.namespace = requireName(namespace, "namespace");
        this.counter = requireName(counter, "counter");
        this.quantity = requireNonNegative(quantity, "quantity");
        this.weight = requireNonNegative(weight, "weight");
        this.admittedGas = requireNonNegative(
                admittedGas, "admittedGas");
        this.effectiveBudget = requireNonNegative(
                effectiveBudget, "effectiveBudget");
        if (admittedGas > effectiveBudget) {
            throw new IllegalArgumentException(
                    "Admitted runtime gas cannot exceed its effective budget");
        }
        this.source =
                Objects.requireNonNull(source, "source");
    }

    /**
     * Captures an exhaustion produced by a live child or semantic meter.
     *
     * @param exhaustion exact processor gas rejection
     * @return runtime-neutral view retaining the original rejection
     */
    public static RuntimeGasExhaustion from(
            GasLimitExceededException exhaustion) {
        GasLimitExceededException exact =
                Objects.requireNonNull(exhaustion, "exhaustion");
        return new RuntimeGasExhaustion(
                exact.namespace(),
                exact.counter(),
                exact.quantity(),
                exact.weight(),
                exact.admittedGas(),
                exact.effectiveBudget(),
                exact);
    }

    /**
     * Returns the runtime namespace whose charge was rejected.
     *
     * @return non-empty namespace
     */
    public String namespace() {
        return namespace;
    }

    /**
     * Returns the counter whose charge was rejected.
     *
     * @return non-empty counter name
     */
    public String counter() {
        return counter;
    }

    /**
     * Returns the rejected counter quantity.
     *
     * @return non-negative quantity
     */
    public long quantity() {
        return quantity;
    }

    /**
     * Returns the configured gas weight per unit.
     *
     * @return non-negative counter weight
     */
    public long weight() {
        return weight;
    }

    /**
     * Returns gas admitted before the rejected charge.
     *
     * @return non-negative admitted prefix
     */
    public long admittedGas() {
        return admittedGas;
    }

    /**
     * Returns the budget effective when the charge was attempted.
     *
     * @return non-negative effective budget
     */
    public long effectiveBudget() {
        return effectiveBudget;
    }

    GasLimitExceededException source() {
        return source;
    }

    private static String requireName(String value, String label) {
        String exact = Objects.requireNonNull(value, label);
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runtime gas " + label + " must not be empty");
        }
        return exact;
    }

    private static long requireNonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "Runtime gas " + label + " must be non-negative");
        }
        return value;
    }
}
