package blue.language.processor;

import java.util.Objects;

/**
 * Invocation-owned gas budget shared by one or more named runtime ledgers.
 *
 * <p>A budget is created by {@link RuntimeWorkSession#openSharedBudget(long)}
 * and can only be attached to ledgers opened by that same session. Every
 * attached ledger contributes its weighted admitted charges to one shared
 * total. A charge that would exceed the maximum is rejected before either the
 * child trace or the parent reservation is mutated.</p>
 */
public final class RuntimeWorkBudget {

    private final Object ownerToken;
    private final long maximumGas;
    private long admittedGas;

    RuntimeWorkBudget(
            Object ownerToken,
            long maximumGas) {
        this.ownerToken =
                Objects.requireNonNull(ownerToken, "ownerToken");
        if (maximumGas < 0L) {
            throw new IllegalArgumentException(
                    "Runtime work budget must be non-negative");
        }
        this.maximumGas = maximumGas;
    }

    /**
     * Returns the maximum weighted gas this shared budget can admit.
     *
     * @return non-negative shared gas maximum
     */
    public long maximumGas() {
        return maximumGas;
    }

    /**
     * Returns the weighted gas admitted across every attached ledger.
     *
     * @return exact shared admitted total
     */
    public synchronized long admittedGas() {
        return admittedGas;
    }

    /**
     * Returns the weighted gas still available to attached ledgers.
     *
     * @return exact remaining shared gas
     */
    public synchronized long remainingGas() {
        return maximumGas - admittedGas;
    }

    boolean isOwnedBy(Object candidateOwnerToken) {
        return ownerToken == candidateOwnerToken;
    }

    synchronized void ensureAdmissible(
            String namespace,
            String counter,
            long quantity,
            long weight,
            long subtotal,
            GasChargeContext context) {
        if (subtotal > maximumGas - admittedGas) {
            throw new GasLimitExceededException(
                    namespace,
                    counter,
                    quantity,
                    weight,
                    admittedGas,
                    maximumGas,
                    context);
        }
    }

    synchronized void recordAdmission(long subtotal) {
        if (subtotal < 0L
                || subtotal > maximumGas - admittedGas) {
            throw new IllegalStateException(
                    "Runtime work budget admission was not prevalidated");
        }
        admittedGas += subtotal;
    }
}
