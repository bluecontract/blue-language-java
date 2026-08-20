package blue.language.processor;

import java.util.Objects;

/**
 * One completed external-source candidate paired with its exact settlement
 * write attribution.
 *
 * <p>The pair remains intact while a settlement batch is canonicalized, so a
 * caller-supplied list order can never move attribution from one raw source
 * occurrence to another.</p>
 */
public final class ManagedCheckpointSettlementEntry {

    private final ManagedCheckpointCandidate candidate;
    private final long rawOccurrenceOrder;
    private final GasChargeContext writeContext;

    /**
     * Creates one immutable settlement entry.
     *
     * @param candidate accepted-new candidate whose delivery completed
     * @param rawOccurrenceOrder frozen canonical external-source occurrence
     *        ordinal
     * @param writeContext exact attribution for its actual checkpoint write
     */
    public ManagedCheckpointSettlementEntry(
            ManagedCheckpointCandidate candidate,
            long rawOccurrenceOrder,
            GasChargeContext writeContext) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        if (rawOccurrenceOrder < 0L) {
            throw new IllegalArgumentException(
                    "rawOccurrenceOrder must be non-negative");
        }
        this.rawOccurrenceOrder = rawOccurrenceOrder;
        this.writeContext = Objects.requireNonNull(
                writeContext, "writeContext");
    }

    /**
     * Returns the completed frozen candidate.
     *
     * @return completed candidate
     */
    public ManagedCheckpointCandidate candidate() {
        return candidate;
    }

    /**
     * Returns the frozen canonical external-source occurrence ordinal.
     *
     * @return non-negative raw-source occurrence ordinal
     */
    public long rawOccurrenceOrder() {
        return rawOccurrenceOrder;
    }

    /**
     * Returns the exact attribution for this candidate's actual write.
     *
     * @return write attribution
     */
    public GasChargeContext writeContext() {
        return writeContext;
    }
}
