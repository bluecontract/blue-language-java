package blue.coordination.closure;

import blue.contracts.closure.ClosureProcessResult;
import java.util.Objects;

/** Host-level result joining Contracts output with atomic publication evidence. */
public final class ClosureDispatchResult {
    private final AffectedClosurePlan plan;
    private final ClosureProcessResult.Attempt attempt;
    private final ClosureCommitReceipt commitReceipt;
    private final BlockedEntryRecord blockedEntry;

    private ClosureDispatchResult(
            AffectedClosurePlan plan,
            ClosureProcessResult.Attempt attempt,
            ClosureCommitReceipt commitReceipt,
            BlockedEntryRecord blockedEntry) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        this.commitReceipt = commitReceipt;
        this.blockedEntry = blockedEntry;
        if (attempt instanceof ClosureProcessResult.NeedsResources) {
            if (commitReceipt != null || blockedEntry != null) {
                throw new IllegalArgumentException(
                        "NeedsResources has no commit or blocked-entry record");
            }
            return;
        }
        if (!(attempt instanceof ClosureProcessResult.Complete)) {
            throw new IllegalArgumentException("unknown attempt branch");
        }
        if ((commitReceipt == null) == (blockedEntry == null)) {
            throw new IllegalArgumentException(
                    "Complete requires exactly one commit or blocked-entry record");
        }
        ClosureProcessResult completed =
                ((ClosureProcessResult.Complete) attempt).result();
        boolean successful = completed.platformCommitCompanion() != null;
        if (successful != (commitReceipt != null)) {
            throw new IllegalArgumentException("publication branch does not match result");
        }
        if (commitReceipt != null) {
            if (!plan.planIdentity().equals(commitReceipt.planIdentity())
                    || !completed.platformCommitCompanion().companionIdentity().equals(
                            commitReceipt.contractsCommitCompanionIdentity())) {
                throw new IllegalArgumentException("commit receipt mismatch");
            }
        } else {
            if (!plan.planIdentity().equals(blockedEntry.closurePlanIdentity())
                    || !completed.gasTraceIdentity().equals(
                            blockedEntry.gasTraceIdentity())
                    || !Objects.equals(
                            completed.rejectedCharge() == null
                                    ? null
                                    : completed.rejectedCharge()
                                            .rejectedChargeIdentity(),
                            blockedEntry.rejectedChargeIdentity())) {
                throw new IllegalArgumentException("blocked entry mismatch");
            }
        }
    }

    public static ClosureDispatchResult committed(
            AffectedClosurePlan plan,
            ClosureProcessResult.Complete complete,
            ClosureCommitReceipt receipt) {
        return new ClosureDispatchResult(plan, complete, receipt, null);
    }

    public static ClosureDispatchResult blocked(
            AffectedClosurePlan plan,
            ClosureProcessResult.Complete complete,
            BlockedEntryRecord blocked) {
        return new ClosureDispatchResult(plan, complete, null, blocked);
    }

    public static ClosureDispatchResult needsResources(
            AffectedClosurePlan plan,
            ClosureProcessResult.NeedsResources needsResources) {
        return new ClosureDispatchResult(plan, needsResources, null, null);
    }

    public boolean committed() {
        return commitReceipt != null;
    }

    public AffectedClosurePlan plan() {
        return plan;
    }

    public ClosureProcessResult.Attempt attempt() {
        return attempt;
    }

    /** Returns the completed result; callers must branch on {@link #attempt()}. */
    public ClosureProcessResult processResult() {
        if (!(attempt instanceof ClosureProcessResult.Complete)) {
            throw new IllegalStateException("attempt needs resources");
        }
        return ((ClosureProcessResult.Complete) attempt).result();
    }

    public ClosureCommitReceipt commitReceipt() {
        return commitReceipt;
    }

    public BlockedEntryRecord blockedEntry() {
        return blockedEntry;
    }
}
