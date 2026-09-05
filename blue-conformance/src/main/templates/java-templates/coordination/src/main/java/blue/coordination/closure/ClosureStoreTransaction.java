package blue.coordination.closure;

import blue.contracts.closure.ClosureCommitPlan;
import blue.contracts.closure.ClosureProcessResult;
import blue.contracts.closure.ManagedOccurrenceBinding;
import blue.contracts.closure.PublicEventOccurrence;
import blue.contracts.closure.ResultingComponent;
import blue.contracts.closure.ResultingDocument;
import java.util.List;

/** Storage-neutral atomic publication boundary for one required closure. */
public interface ClosureStoreTransaction {
    /** Verifies the complete compare-and-swap input carried by the companion. */
    void verifyExpectedState(ClosureCommitPlan plan);

    void stageDocuments(List<ResultingDocument> documents);

    void stageComponents(List<ResultingComponent> components);

    void stageOccurrences(
            List<ManagedOccurrenceBinding> occurrences,
            String occurrenceBindingSetIdentity);

    void stageGraphChanges(
            List<ClosureProcessResult.GraphChange> changes,
            String graphChangesIdentity);

    void stageCheckpointWrites(
            List<ClosureProcessResult.CheckpointWrite> writes,
            String checkpointWritesIdentity);

    void stageSubscriptionDeltas(
            List<ClosureProcessResult.SubscriptionDelta> deltas,
            String subscriptionDeltasIdentity);

    void stagePublicEvents(
            List<PublicEventOccurrence> events,
            String publicEventsIdentity);

    void stageReceipt(ClosureCommitReceipt receipt);

    void commit();
}
