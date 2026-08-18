package blue.language.processor;

import java.util.Objects;

/**
 * One immutable admitted charge in the canonical gas trace.
 *
 * <p>Sequence is assigned only when the owning meter merges the entry.
 * Quantity, weight, and subtotal are retained independently so diagnostics can
 * verify the schedule calculation without re-executing work.</p>
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

    /**
     * Returns the sequence assigned when the owning meter admitted this entry.
     *
     * @return owning-meter merge sequence
     */
    public long sequence() {
        return sequence;
    }

    /**
     * Returns the schedule namespace that owns the charged counter.
     *
     * @return charged namespace
     */
    public String namespace() {
        return namespace;
    }

    /**
     * Returns the schedule counter that was charged.
     *
     * @return charged counter
     */
    public String counter() {
        return counter;
    }

    /**
     * Returns the non-negative counter quantity admitted by the meter.
     *
     * @return admitted counter quantity
     */
    public long quantity() {
        return quantity;
    }

    /**
     * Returns the schedule weight applied to each unit.
     *
     * @return schedule weight per unit
     */
    public long weight() {
        return weight;
    }

    /**
     * Returns the exact admitted product of quantity and weight.
     *
     * @return exact admitted subtotal
     */
    public long subtotal() {
        return subtotal;
    }

    /**
     * Returns the managed document to which the charge was attributed.
     *
     * @return document identity, or {@code null}
     */
    public String documentId() {
        return context.documentId();
    }

    /**
     * Returns the scope to which the charge was attributed.
     *
     * @return attributed scope, or {@code null}
     */
    public String scopePath() {
        return context.scopePath();
    }

    /**
     * Returns the managed-scope activation generation.
     *
     * @return activation generation, or {@code null}
     */
    public Long activationGeneration() {
        return context.activationGeneration();
    }

    /**
     * Returns the component generation to which the work was attributed.
     *
     * @return component generation, or {@code null}
     */
    public Long componentGeneration() {
        return context.componentGeneration();
    }

    /**
     * Returns the contract to which the charge was attributed.
     *
     * @return attributed contract key, or {@code null}
     */
    public String contractKey() {
        return context.contractKey();
    }

    /**
     * Returns the logical path to which the charge was attributed.
     *
     * @return attributed logical path, or {@code null}
     */
    public String logicalPath() {
        return context.logicalPath();
    }

    /**
     * Returns the owning affected-closure work occurrence identity.
     *
     * @return work occurrence identity, or {@code null}
     */
    public String workOccurrenceId() {
        return context.workOccurrenceId();
    }

    /**
     * Returns the stable reason recorded for the charge.
     *
     * @return non-null deterministic charge reason
     */
    public String reason() {
        return context.reason();
    }

    GasChargeContext context() {
        return context;
    }
}
