package blue.language.processor;

/**
 * Raised before work when the next named charge cannot be admitted.
 *
 * <p>The exception records the rejected quantity and the exact already
 * admitted prefix. Throwing it never appends a partial trace entry, which
 * allows callers to publish deterministic exhaustion diagnostics.</p>
 */
public final class GasLimitExceededException extends RuntimeException {

    /** Schedule namespace of the rejected charge. */
    private final String namespace;
    /** Schedule counter of the rejected charge. */
    private final String counter;
    /** Counter quantity that could not be admitted. */
    private final long quantity;
    /** Schedule weight applied to each requested unit. */
    private final long weight;
    /** Exact gas admitted before rejection. */
    private final long admittedGas;
    /** Effective budget that rejected the charge. */
    private final long gasLimit;

    GasLimitExceededException(String namespace,
                              String counter,
                              long quantity,
                              long weight,
                              long admittedGas,
                              long gasLimit) {
        super("Gas limit exceeded before " + namespace + "." + counter);
        this.namespace = namespace;
        this.counter = counter;
        this.quantity = quantity;
        this.weight = weight;
        this.admittedGas = admittedGas;
        this.gasLimit = gasLimit;
    }

    /**
     * Returns the namespace whose charge was rejected.
     *
     * @return gas namespace
     */
    public String namespace() {
        return namespace;
    }

    /**
     * Returns the counter whose charge was rejected.
     *
     * @return counter name
     */
    public String counter() {
        return counter;
    }

    /**
     * Returns the rejected quantity.
     *
     * @return counter quantity
     */
    public long quantity() {
        return quantity;
    }

    /**
     * Returns the configured unit weight.
     *
     * @return gas per counter unit
     */
    public long weight() {
        return weight;
    }

    /**
     * Returns gas admitted before the rejected charge.
     *
     * @return exact admitted prefix
     */
    public long admittedGas() {
        return admittedGas;
    }

    /**
     * Returns the budget that rejected the charge.
     *
     * @return effective gas limit
     */
    public long gasLimit() {
        return gasLimit;
    }

    /**
     * Runtime-neutral name for the exact budget that rejected the charge.
     *
     * <p>{@link #gasLimit()} remains for binary compatibility.</p>
     *
     * @return effective gas budget
     */
    public long effectiveBudget() {
        return gasLimit;
    }

    /**
     * Converts the rejection to its stable public diagnostic.
     *
     * @return immutable gas-exhaustion diagnostic
     */
    public ProcessorDiagnostic diagnostic() {
        return ProcessorDiagnostic.builder(ProcessorErrorCategory.GasLimitExceeded)
                .message(getMessage())
                .detail(
                        ProcessorDiagnosticConstants.FIELD_NAMESPACE,
                        namespace)
                .detail(
                        ProcessorDiagnosticConstants.FIELD_COUNTER,
                        counter)
                .detail(
                        ProcessorDiagnosticConstants.FIELD_QUANTITY,
                        quantity)
                .detail(
                        ProcessorDiagnosticConstants.FIELD_WEIGHT,
                        weight)
                .detail(
                        ProcessorDiagnosticConstants.FIELD_ADMITTED_GAS,
                        admittedGas)
                .detail(
                        ProcessorDiagnosticConstants.FIELD_GAS_LIMIT,
                        gasLimit)
                .detail(
                        ProcessorDiagnosticConstants.FIELD_EFFECTIVE_BUDGET,
                        gasLimit)
                .build();
    }
}
