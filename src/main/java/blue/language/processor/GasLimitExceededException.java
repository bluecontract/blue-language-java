package blue.language.processor;

/**
 * Raised before work when the next named charge cannot be admitted.
 */
public final class GasLimitExceededException extends RuntimeException {

    private final String namespace;
    private final String counter;
    private final long quantity;
    private final long weight;
    private final long admittedGas;
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

    public String namespace() {
        return namespace;
    }

    public String counter() {
        return counter;
    }

    public long quantity() {
        return quantity;
    }

    public long weight() {
        return weight;
    }

    public long admittedGas() {
        return admittedGas;
    }

    public long gasLimit() {
        return gasLimit;
    }

    public ProcessorDiagnostic diagnostic() {
        return ProcessorDiagnostic.builder(ProcessorErrorCategory.GasLimitExceeded)
                .message(getMessage())
                .detail("namespace", namespace)
                .detail("counter", counter)
                .detail("quantity", quantity)
                .detail("weight", weight)
                .detail("admittedGas", admittedGas)
                .detail("gasLimit", gasLimit)
                .build();
    }
}
