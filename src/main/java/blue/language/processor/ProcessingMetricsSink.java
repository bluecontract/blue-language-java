package blue.language.processor;

/**
 * Optional metrics hook for document-processing instrumentation.
 *
 * <p>Implementations should be cheap and thread-safe. All methods are no-ops
 * by default so callers can record fine-grained timings without branching.</p>
 */
public interface ProcessingMetricsSink {
    ProcessingMetricsSink NOOP = new ProcessingMetricsSink() {
    };

    default void addProcessDocumentNanos(long nanos) {
    }

    default void addBlueProcessDocumentNanos(long nanos) {
    }

    default void addEventPreprocessNanos(long nanos) {
    }

    default void addResultSnapshotAttachNanos(long nanos) {
    }

    default void addBlueIdCalculationNanos(long nanos) {
    }

    default void addProcessingSnapshotCacheLookupNanos(long nanos) {
    }

    default void incrementProcessingSnapshotCacheHits() {
    }

    default void incrementProcessingSnapshotCacheMisses() {
    }

    default void addProcessingSnapshotFromDocumentNanos(long nanos) {
    }

    default void incrementProcessingSnapshotFromDocumentBuilds() {
    }

    /**
     * Records one attempt to create the immutable Processing Event snapshot.
     */
    default void incrementProcessEventSnapshotAttempts() {
    }

    /**
     * Records one successfully created immutable Processing Event snapshot.
     */
    default void incrementProcessEventSnapshotBuilds() {
    }

    /**
     * Records one failed immutable Processing Event snapshot construction.
     */
    default void incrementProcessEventSnapshotFailures() {
    }

    /**
     * Records the duration of one immutable Processing Event snapshot attempt.
     */
    default void addProcessEventSnapshotConstructionNanos(long nanos) {
    }

    default void addBundleLoadNanos(long nanos) {
    }

    default void addBundleLoadCacheKeyBuildNanos(long nanos) {
    }

    default void addBundleLoadActualBuildNanos(long nanos) {
    }

    default void addBundleLoadReuseNanos(long nanos) {
    }

    default void incrementBundleLoadCacheHits() {
    }

    default void incrementBundleLoadCacheMisses() {
    }

    default void incrementBundlesBuilt() {
    }

    default void incrementBundlesReused() {
    }

    default void incrementBundleScopeLoadAttempts() {
    }

    default void incrementBundleScopeExecutionCacheHits() {
    }

    default void incrementBundleScopeRefreshes() {
    }

    default void addBundleScopeTerminationCheckNanos(long nanos) {
    }

    default void addBundleScopeResolvedLookupNanos(long nanos) {
    }

    default void addBundleScopeContractLoadNanos(long nanos) {
    }

    default void addChannelDiscoveryNanos(long nanos) {
    }

    default void addChannelMatchNanos(long nanos) {
    }

    default void incrementChannelEvaluations() {
    }

    /**
     * Records one handler dispatch through an explicitly routed channel delivery.
     */
    default void incrementRoutedChannelDeliveries() {
    }

    /**
     * Records one eligible source delivery whose successful logical route was already dispatched.
     */
    default void incrementDeduplicatedChannelDeliveries() {
    }

    default void addHandlerDiscoveryNanos(long nanos) {
    }

    default void addHandlerMatchNanos(long nanos) {
    }

    default void incrementHandlerMatchAttempts() {
    }

    default void addHandlerExecutionNanos(long nanos) {
    }

    default void incrementHandlersExecuted() {
    }

    default void addTriggeredEventRoutingNanos(long nanos) {
    }

    default void incrementTriggeredEventsRouted() {
    }

    default void addCheckpointUpdateNanos(long nanos) {
    }

    default void addCheckpointEnsureNanos(long nanos) {
    }

    default void addCheckpointFindNanos(long nanos) {
    }

    default void addCheckpointCurrentIdentityNanos(long nanos) {
    }

    default void addCheckpointIsNewerNanos(long nanos) {
    }

    default void addCheckpointDuplicateNanos(long nanos) {
    }

    default void addCheckpointPersistNanos(long nanos) {
    }

    default void incrementCheckpointIdentityCacheHits() {
    }

    default void incrementCheckpointIdentityCacheMisses() {
    }

    default void incrementCheckpointStoredIdentityCacheHits() {
    }

    default void incrementCheckpointStoredIdentityCacheMisses() {
    }

    default void addCheckpointDirectBlueIdNanos(long nanos) {
    }

    default void addCheckpointContentBlueIdNanos(long nanos) {
    }

    default void addCheckpointFallbackNanos(long nanos) {
    }

    default void addSnapshotCommitNanos(long nanos) {
    }

    default void addPostProcessingNanos(long nanos) {
    }

    default void addPatchBoundaryNanos(long nanos) {
    }

    default void addPatchGasNanos(long nanos) {
    }

    default void addDocumentUpdateRoutingNanos(long nanos) {
    }

    default void incrementDocumentUpdateEventsBuilt() {
    }

    default void incrementDocumentUpdateEventsSkippedNoChannel() {
    }

    default void addBatchPatchPlanningNanos(long nanos) {
    }

    default void addBatchPatchConformanceNanos(long nanos) {
    }

    default void addBatchPatchBuildUpdatesNanos(long nanos) {
    }

    default void addBatchPatchCommitNanos(long nanos) {
    }

    default void incrementDocumentUpdateBeforeMaterializations() {
    }

    default void incrementDocumentUpdateAfterMaterializations() {
    }

    /** Records one reusable observable-sequential patch planning session. */
    default void incrementPatchSequencesPrepared() {
    }

    /** Records patches accepted by reusable observable-sequential sessions. */
    default void addPatchesPrepared(long count) {
    }

    /** Records use of the legacy standalone one-patch transaction path. */
    default void incrementSingletonPatchTransactions() {
    }

    default void addSequencePlanningNanos(long nanos) {
    }

    default void addSequenceConformanceNanos(long nanos) {
    }

    default void addSequenceCommitNanos(long nanos) {
    }

    default void addSequenceFinalCacheCommitNanos(long nanos) {
    }

    default void incrementSequenceIntermediateSnapshotAdvances() {
    }

    default void incrementSequenceSharedSnapshotCacheInserts() {
    }

    default void incrementSequenceFinalSnapshotCacheInserts() {
    }

    default void incrementSequenceSuffixRebases() {
    }

    default void incrementSequenceStalePreviewFallbacks() {
    }

    default void incrementSequenceFallbackPatches() {
    }

    default void incrementParsedPointerCacheHits() {
    }

    default void incrementParsedPointerCacheMisses() {
    }

    default void incrementFrozenPatchValueHits() {
    }

    default void incrementPatchValueMaterializations() {
    }

    default void incrementFrozenNodesCreated() {
    }

    default void incrementFrozenNodesReused() {
    }

    default void incrementCanonicalIdentityCalculations() {
    }

    default void incrementResolvedIdentityCalculations() {
    }

    default void addCanonicalBytesWritten(long count) {
    }

    default void incrementJcsFallbacks() {
    }

    default void addBase58EncodeNanos(long nanos) {
    }

    default void addBase58DecodeNanos(long nanos) {
    }

    default void addBlueIdDigestNanos(long nanos) {
    }

    default void incrementResolvedStructuralKeyBuilds() {
    }
}
