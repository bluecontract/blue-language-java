package blue.language.processor;

import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.model.Node;

import java.util.Map;

/**
 * Single invocation-owned checkpoint comparison and write transaction.
 *
 * <p>Every source update is merged through the current tentative marker state
 * in {@link CheckpointManager}; a later logical delivery therefore cannot
 * rebuild a stale marker and erase an earlier pending entry.</p>
 */
final class ProcessingCheckpointTransaction {

    private final CheckpointManager state;

    ProcessingCheckpointTransaction(
            DocumentProcessingRuntime runtime,
            LanguageRuntimeAccess languageRuntime,
            ProcessingObserver observer) {
        this.state = new CheckpointManager(
                runtime, languageRuntime, observer);
    }

    ProcessingCheckpointTransaction(CheckpointManager state) {
        this.state = java.util.Objects.requireNonNull(state, "state");
    }

    void ensureMarker(String scopePath, ContractBundle bundle) {
        state.ensureCheckpointMarker(scopePath, bundle);
    }

    CheckpointManager.CheckpointRecord find(
            ContractBundle bundle,
            String rawChannelKey,
            String checkpointDomainBlueId) {
        return state.findCheckpoint(
                bundle, rawChannelKey, checkpointDomainBlueId);
    }

    CheckpointManager.CheckpointRecord findForComparison(
            String scopePath,
            ContractBundle bundle,
            String rawChannelKey,
            String checkpointDomainBlueId,
            String subjectBlueId,
            GasChargeContext comparisonContext) {
        return state.findForComparison(
                scopePath,
                bundle,
                rawChannelKey,
                checkpointDomainBlueId,
                subjectBlueId,
                comparisonContext);
    }

    boolean isDuplicate(
            CheckpointManager.CheckpointRecord record,
            String subjectBlueId) {
        return state.isDuplicate(record, subjectBlueId);
    }

    void persist(
            String scopePath,
            ContractBundle bundle,
            CheckpointManager.CheckpointRecord record,
            String subjectBlueId,
            Node exactSubject) {
        state.persist(
                scopePath,
                bundle,
                record,
                subjectBlueId,
                exactSubject);
    }

    void persistSettlement(
            String scopePath,
            ContractBundle bundle,
            CheckpointManager.CheckpointRecord record,
            String subjectBlueId,
            Node exactSubject,
            GasChargeContext writeContext) {
        state.persistSettlement(
                scopePath,
                bundle,
                record,
                subjectBlueId,
                exactSubject,
                writeContext);
    }

    void cleanupInactiveEntries(
            String scopePath,
            ContractBundle bundle,
            Map<String, String> activeDomains) {
        state.cleanupInactiveEntries(scopePath, bundle, activeDomains);
    }

    void removeSettlementEntry(
            String scopePath,
            ContractBundle bundle,
            String rawChannelKey,
            String expectedDomainBlueId,
            GasChargeContext cleanupContext) {
        state.removeSettlementEntry(
                scopePath,
                bundle,
                rawChannelKey,
                expectedDomainBlueId,
                cleanupContext);
    }

    String eventIdentity(Node event) {
        return state.eventIdentity(event);
    }
}
