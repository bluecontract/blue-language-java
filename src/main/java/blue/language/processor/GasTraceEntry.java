package blue.language.processor;

import java.util.Objects;

/**
 * One admitted canonical gas charge.
 */
public final class GasTraceEntry {

    private final long sequence;
    private final String namespace;
    private final String counter;
    private final long quantity;
    private final long weight;
    private final long subtotal;
    private final GasChargeContext context;

    GasTraceEntry(long sequence,
                  String namespace,
                  String counter,
                  long quantity,
                  long weight,
                  long subtotal,
                  GasChargeContext context) {
        this.sequence = sequence;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.counter = Objects.requireNonNull(counter, "counter");
        this.quantity = quantity;
        this.weight = weight;
        this.subtotal = subtotal;
        this.context = context != null ? context : GasChargeContext.empty();
    }

    public long sequence() {
        return sequence;
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

    public long subtotal() {
        return subtotal;
    }

    public String scopePath() {
        return context.scopePath();
    }

    public String contractKey() {
        return context.contractKey();
    }

    public String logicalPath() {
        return context.logicalPath();
    }

    public String reason() {
        return context.reason();
    }

    GasChargeContext context() {
        return context;
    }
}
