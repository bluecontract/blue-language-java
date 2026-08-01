package blue.language.processor;

import blue.language.api.LanguageRuntimeAccess;
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

    boolean isDuplicate(
            CheckpointManager.CheckpointRecord record,
            String subjectBlueId) {
        return state.isDuplicate(record, subjectBlueId);
    }

    void recordComparison(
            String scopePath,
            CheckpointManager.CheckpointRecord record,
            String subjectBlueId) {
        state.recordComparison(scopePath, record, subjectBlueId);
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

    void cleanupInactiveEntries(
            String scopePath,
            ContractBundle bundle,
            Map<String, String> activeDomains) {
        state.cleanupInactiveEntries(scopePath, bundle, activeDomains);
    }

    String eventIdentity(Node event) {
        return state.eventIdentity(event);
    }
}
