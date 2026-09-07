package blue.coordination.closure;

import blue.contracts.closure.SharedGasMeter;
import java.util.Objects;

/** Deterministic terminal/block evidence for an exact offending external entry. */
public final class BlockedEntryRecord {
    private final String entryBlueId;
    private final String closurePlanIdentity;
    private final String diagnostic;
    private final String gasTraceIdentity;
    private final SharedGasMeter.RejectedCharge rejectedCharge;
    private final String rejectedWorkIdentity;

    public BlockedEntryRecord(
            String entryBlueId,
            String closurePlanIdentity,
            String diagnostic,
            String gasTraceIdentity,
            SharedGasMeter.RejectedCharge rejectedCharge) {
        this.entryBlueId = Objects.requireNonNull(entryBlueId, "entryBlueId");
        this.closurePlanIdentity = Objects.requireNonNull(closurePlanIdentity, "closurePlanIdentity");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.gasTraceIdentity = Objects.requireNonNull(gasTraceIdentity, "gasTraceIdentity");
        this.rejectedCharge = rejectedCharge;
        this.rejectedWorkIdentity = rejectedCharge != null
                && rejectedCharge.owner() instanceof SharedGasMeter.WorkOwner
                ? ((SharedGasMeter.WorkOwner) rejectedCharge.owner())
                        .workOccurrenceIdentity()
                : null;
    }

    public String entryBlueId() {
        return entryBlueId;
    }

    public String closurePlanIdentity() {
        return closurePlanIdentity;
    }

    public String diagnostic() {
        return diagnostic;
    }

    public String gasTraceIdentity() {
        return gasTraceIdentity;
    }

    /** Null for non-gas failures; complete for every gas-limit failure. */
    public SharedGasMeter.RejectedCharge rejectedCharge() {
        return rejectedCharge;
    }

    public String rejectedChargeIdentity() {
        return rejectedCharge == null ? null : rejectedCharge.rejectedChargeIdentity();
    }

    /** Non-null exactly for a WORK-owned rejected charge. */
    public String rejectedWorkIdentity() {
        return rejectedWorkIdentity;
    }
}
