package blue.language.processor;

/**
 * Invocation-owned operational counters for patch processing.
 *
 * <p>The counters are deliberately separate from semantic processor state:
 * they support tests and observations but never participate in planning,
 * charging, or result selection. Every {@link DocumentProcessingRuntime}
 * creates exactly one instance, so values cannot leak between invocations.</p>
 */
final class ProcessingRuntimeCounters {

    private long batchPatchCalls;
    private long batchPatchEntries;
    private long batchPatchPlanningNanos;
    private long batchPatchConformanceNanos;
    private long batchPatchBuildUpdatesNanos;
    private long batchPatchCommitNanos;
    private long batchPatchRollbackCopies;
    private long documentUpdateBeforeNodeMaterializations;
    private long documentUpdateAfterNodeMaterializations;
    private long patchSequencesPrepared;
    private long singletonPatchTransactions;
    private long sequenceIntermediateSnapshotAdvances;
    private long sequenceSharedSnapshotCacheInserts;
    private long sequenceFinalSnapshotCacheInserts;
    private long sequenceSuffixRebases;
    private long sequenceStalePreviewFallbacks;
    private long sequenceFallbackPatches;

    void recordBatchPatch(int entryCount) {
        batchPatchCalls++;
        batchPatchEntries += entryCount;
    }

    void recordPreparedPatchSequence() {
        patchSequencesPrepared++;
        batchPatchCalls++;
    }

    void recordPatchEntry() {
        batchPatchEntries++;
    }

    void recordSingletonPatchTransaction() {
        singletonPatchTransactions++;
    }

    void recordPatchPlanningNanos(long nanos) {
        batchPatchPlanningNanos += nanos;
    }

    void recordConformanceNanos(long nanos) {
        batchPatchConformanceNanos += nanos;
    }

    void recordBuildUpdatesNanos(long nanos) {
        batchPatchBuildUpdatesNanos += nanos;
    }

    void recordCommitNanos(long nanos) {
        batchPatchCommitNanos += nanos;
    }

    void recordBeforeNodeMaterialization() {
        documentUpdateBeforeNodeMaterializations++;
    }

    void recordAfterNodeMaterialization() {
        documentUpdateAfterNodeMaterializations++;
    }

    void recordIntermediateSnapshotAdvance() {
        sequenceIntermediateSnapshotAdvances++;
    }

    void recordFinalSharedSnapshotCacheInsert() {
        sequenceSharedSnapshotCacheInserts++;
        sequenceFinalSnapshotCacheInserts++;
    }

    void recordSuffixRebase() {
        sequenceSuffixRebases++;
    }

    void recordStalePreviewFallback() {
        sequenceStalePreviewFallbacks++;
    }

    long batchPatchCalls() {
        return batchPatchCalls;
    }

    long batchPatchEntries() {
        return batchPatchEntries;
    }

    long batchPatchPlanningNanos() {
        return batchPatchPlanningNanos;
    }

    long batchPatchConformanceNanos() {
        return batchPatchConformanceNanos;
    }

    long batchPatchBuildUpdatesNanos() {
        return batchPatchBuildUpdatesNanos;
    }

    long batchPatchCommitNanos() {
        return batchPatchCommitNanos;
    }

    long batchPatchRollbackCopies() {
        return batchPatchRollbackCopies;
    }

    long documentUpdateBeforeNodeMaterializations() {
        return documentUpdateBeforeNodeMaterializations;
    }

    long documentUpdateAfterNodeMaterializations() {
        return documentUpdateAfterNodeMaterializations;
    }

    long patchSequencesPrepared() {
        return patchSequencesPrepared;
    }

    long singletonPatchTransactions() {
        return singletonPatchTransactions;
    }

    long sequenceIntermediateSnapshotAdvances() {
        return sequenceIntermediateSnapshotAdvances;
    }

    long sequenceSharedSnapshotCacheInserts() {
        return sequenceSharedSnapshotCacheInserts;
    }

    long sequenceFinalSnapshotCacheInserts() {
        return sequenceFinalSnapshotCacheInserts;
    }

    long sequenceSuffixRebases() {
        return sequenceSuffixRebases;
    }

    long sequenceStalePreviewFallbacks() {
        return sequenceStalePreviewFallbacks;
    }

    long sequenceFallbackPatches() {
        return sequenceFallbackPatches;
    }
}
