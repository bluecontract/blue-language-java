package blue.language.processor;

/**
 * Optional metrics hook for document-processing instrumentation.
 *
 * <p>Implementations should be cheap and thread-safe. All methods are no-ops
 * by default so callers can record fine-grained timings without branching.</p>
 */
public interface ProcessingMetricsSink {

    /**
     * Shared stateless sink that discards all observations.
     *
     * <p>The interface owns this singleton. It retains no caller data, has no
     * lifecycle to close, and is safe to share across threads and processor
     * instances.</p>
     */
    ProcessingMetricsSink NOOP = new ProcessingMetricsSink() {
    };

    /**
     * Adds a sample to the {@code processDocumentNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addProcessDocumentNanos(long nanos) {
        addMetric("processDocumentNanos", nanos);
    }

    /**
     * Adds a sample to the {@code blueProcessDocumentNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBlueProcessDocumentNanos(long nanos) {
        addMetric("blueProcessDocumentNanos", nanos);
    }

    /**
     * Adds a sample to the {@code eventPreprocessNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addEventPreprocessNanos(long nanos) {
        addMetric("eventPreprocessNanos", nanos);
    }

    /**
     * Adds a sample to the {@code resultSnapshotAttachNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addResultSnapshotAttachNanos(long nanos) {
        addMetric("resultSnapshotAttachNanos", nanos);
    }

    /**
     * Adds a sample to the {@code blueIdCalculationNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBlueIdCalculationNanos(long nanos) {
        addMetric("blueIdCalculationNanos", nanos);
    }

    /**
     * Adds a sample to the {@code processingSnapshotCacheLookupNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addProcessingSnapshotCacheLookupNanos(long nanos) {
        addMetric("processingSnapshotCacheLookupNanos", nanos);
    }

    /**
     * Increments the {@code processingSnapshotCacheHits} counter.
     */
    default void incrementProcessingSnapshotCacheHits() {
        addMetric("processingSnapshotCacheHits", 1L);
    }

    /**
     * Increments the {@code processingSnapshotCacheMisses} counter.
     */
    default void incrementProcessingSnapshotCacheMisses() {
        addMetric("processingSnapshotCacheMisses", 1L);
    }

    /**
     * Adds a sample to the {@code processingSnapshotFromDocumentNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addProcessingSnapshotFromDocumentNanos(long nanos) {
        addMetric("processingSnapshotFromDocumentNanos", nanos);
    }

    /**
     * Increments the {@code processingSnapshotFromDocumentBuilds} counter.
     */
    default void incrementProcessingSnapshotFromDocumentBuilds() {
        addMetric("processingSnapshotFromDocumentBuilds", 1L);
    }

    /**
     * Records one attempt to create the immutable Processing Event snapshot.
     */
    default void incrementProcessEventSnapshotAttempts() {
        addMetric("processEventSnapshotAttempts", 1L);
    }

    /**
     * Records one successfully created immutable Processing Event snapshot.
     */
    default void incrementProcessEventSnapshotBuilds() {
        addMetric("processEventSnapshotBuilds", 1L);
    }

    /**
     * Records one failed immutable Processing Event snapshot construction.
     */
    default void incrementProcessEventSnapshotFailures() {
        addMetric("processEventSnapshotFailures", 1L);
    }

    /**
     * Records the duration of one immutable Processing Event snapshot attempt.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addProcessEventSnapshotConstructionNanos(long nanos) {
        addMetric("processEventSnapshotConstructionNanos", nanos);
    }

    /**
     * Adds a sample to the {@code bundleLoadNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBundleLoadNanos(long nanos) {
        addMetric("bundleLoadNanos", nanos);
    }

    /**
     * Adds a sample to the {@code bundleLoadCacheKeyBuildNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBundleLoadCacheKeyBuildNanos(long nanos) {
        addMetric("bundleLoadCacheKeyBuildNanos", nanos);
    }

    /**
     * Adds a sample to the {@code bundleLoadActualBuildNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBundleLoadActualBuildNanos(long nanos) {
        addMetric("bundleLoadActualBuildNanos", nanos);
    }

    /**
     * Adds a sample to the {@code bundleLoadReuseNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBundleLoadReuseNanos(long nanos) {
        addMetric("bundleLoadReuseNanos", nanos);
    }

    /**
     * Increments the {@code bundleLoadCacheHits} counter.
     */
    default void incrementBundleLoadCacheHits() {
        addMetric("bundleLoadCacheHits", 1L);
    }

    /**
     * Increments the {@code bundleLoadCacheMisses} counter.
     */
    default void incrementBundleLoadCacheMisses() {
        addMetric("bundleLoadCacheMisses", 1L);
    }

    /**
     * Increments the {@code bundlesBuilt} counter.
     */
    default void incrementBundlesBuilt() {
        addMetric("bundlesBuilt", 1L);
    }

    /**
     * Increments the {@code bundlesReused} counter.
     */
    default void incrementBundlesReused() {
        addMetric("bundlesReused", 1L);
    }

    /**
     * Increments the {@code bundleScopeLoadAttempts} counter.
     */
    default void incrementBundleScopeLoadAttempts() {
        addMetric("bundleScopeLoadAttempts", 1L);
    }

    /**
     * Increments the {@code bundleScopeExecutionCacheHits} counter.
     */
    default void incrementBundleScopeExecutionCacheHits() {
        addMetric("bundleScopeExecutionCacheHits", 1L);
    }

    /**
     * Increments the {@code bundleScopeRefreshes} counter.
     */
    default void incrementBundleScopeRefreshes() {
        addMetric("bundleScopeRefreshes", 1L);
    }

    /**
     * Adds a sample to the {@code bundleScopeTerminationCheckNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBundleScopeTerminationCheckNanos(long nanos) {
        addMetric("bundleScopeTerminationCheckNanos", nanos);
    }

    /**
     * Adds a sample to the {@code bundleScopeResolvedLookupNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBundleScopeResolvedLookupNanos(long nanos) {
        addMetric("bundleScopeResolvedLookupNanos", nanos);
    }

    /**
     * Adds a sample to the {@code bundleScopeContractLoadNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBundleScopeContractLoadNanos(long nanos) {
        addMetric("bundleScopeContractLoadNanos", nanos);
    }

    /**
     * Adds a sample to the {@code channelDiscoveryNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addChannelDiscoveryNanos(long nanos) {
        addMetric("channelDiscoveryNanos", nanos);
    }

    /**
     * Adds a sample to the {@code channelMatchNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addChannelMatchNanos(long nanos) {
        addMetric("channelMatchNanos", nanos);
    }

    /**
     * Increments the {@code channelEvaluations} counter.
     */
    default void incrementChannelEvaluations() {
        addMetric("channelEvaluations", 1L);
    }

    /**
     * Records one handler dispatch through an explicitly routed channel delivery.
     */
    default void incrementRoutedChannelDeliveries() {
        addMetric("routedChannelDeliveries", 1L);
    }

    /**
     * Records one eligible source delivery whose successful logical route was already dispatched.
     */
    default void incrementDeduplicatedChannelDeliveries() {
        addMetric("deduplicatedChannelDeliveries", 1L);
    }

    /**
     * Adds a sample to the {@code handlerDiscoveryNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addHandlerDiscoveryNanos(long nanos) {
        addMetric("handlerDiscoveryNanos", nanos);
    }

    /**
     * Adds a sample to the {@code handlerMatchNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addHandlerMatchNanos(long nanos) {
        addMetric("handlerMatchNanos", nanos);
    }

    /**
     * Increments the {@code handlerMatchAttempts} counter.
     */
    default void incrementHandlerMatchAttempts() {
        addMetric("handlerMatchAttempts", 1L);
    }

    /**
     * Adds a sample to the {@code handlerExecutionNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addHandlerExecutionNanos(long nanos) {
        addMetric("handlerExecutionNanos", nanos);
    }

    /**
     * Increments the {@code handlersExecuted} counter.
     */
    default void incrementHandlersExecuted() {
        addMetric("handlersExecuted", 1L);
    }

    /**
     * Adds a sample to the {@code triggeredEventRoutingNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addTriggeredEventRoutingNanos(long nanos) {
        addMetric("triggeredEventRoutingNanos", nanos);
    }

    /**
     * Increments the {@code triggeredEventsRouted} counter.
     */
    default void incrementTriggeredEventsRouted() {
        addMetric("triggeredEventsRouted", 1L);
    }

    /**
     * Adds a sample to the {@code checkpointUpdateNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addCheckpointUpdateNanos(long nanos) {
        addMetric("checkpointUpdateNanos", nanos);
    }

    /**
     * Adds a sample to the {@code checkpointEnsureNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addCheckpointEnsureNanos(long nanos) {
        addMetric("checkpointEnsureNanos", nanos);
    }

    /**
     * Adds a sample to the {@code checkpointFindNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addCheckpointFindNanos(long nanos) {
        addMetric("checkpointFindNanos", nanos);
    }

    /**
     * Adds a sample to the {@code checkpointCurrentIdentityNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addCheckpointCurrentIdentityNanos(long nanos) {
        addMetric("checkpointCurrentIdentityNanos", nanos);
    }

    /**
     * Adds a sample to the {@code checkpointIsNewerNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addCheckpointIsNewerNanos(long nanos) {
        addMetric("checkpointIsNewerNanos", nanos);
    }

    /**
     * Adds a sample to the {@code checkpointDuplicateNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addCheckpointDuplicateNanos(long nanos) {
        addMetric("checkpointDuplicateNanos", nanos);
    }

    /**
     * Adds a sample to the {@code checkpointPersistNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addCheckpointPersistNanos(long nanos) {
        addMetric("checkpointPersistNanos", nanos);
    }

    /**
     * Increments the {@code checkpointIdentityCacheHits} counter.
     */
    default void incrementCheckpointIdentityCacheHits() {
        addMetric("checkpointIdentityCacheHits", 1L);
    }

    /**
     * Increments the {@code checkpointIdentityCacheMisses} counter.
     */
    default void incrementCheckpointIdentityCacheMisses() {
        addMetric("checkpointIdentityCacheMisses", 1L);
    }

    /**
     * Increments the {@code checkpointStoredIdentityCacheHits} counter.
     */
    default void incrementCheckpointStoredIdentityCacheHits() {
        addMetric("checkpointStoredIdentityCacheHits", 1L);
    }

    /**
     * Increments the {@code checkpointStoredIdentityCacheMisses} counter.
     */
    default void incrementCheckpointStoredIdentityCacheMisses() {
        addMetric("checkpointStoredIdentityCacheMisses", 1L);
    }

    /**
     * Adds a sample to the {@code checkpointDirectBlueIdNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addCheckpointDirectBlueIdNanos(long nanos) {
        addMetric("checkpointDirectBlueIdNanos", nanos);
    }

    /**
     * Adds a sample to the {@code checkpointContentBlueIdNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addCheckpointContentBlueIdNanos(long nanos) {
        addMetric("checkpointContentBlueIdNanos", nanos);
    }

    /**
     * Adds a sample to the {@code checkpointFallbackNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addCheckpointFallbackNanos(long nanos) {
        addMetric("checkpointFallbackNanos", nanos);
    }

    /**
     * Adds a sample to the {@code snapshotCommitNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addSnapshotCommitNanos(long nanos) {
        addMetric("snapshotCommitNanos", nanos);
    }

    /**
     * Adds a sample to the {@code postProcessingNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addPostProcessingNanos(long nanos) {
        addMetric("postProcessingNanos", nanos);
    }

    /**
     * Adds a sample to the {@code patchBoundaryNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addPatchBoundaryNanos(long nanos) {
        addMetric("patchBoundaryNanos", nanos);
    }

    /**
     * Adds a sample to the {@code patchGasNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addPatchGasNanos(long nanos) {
        addMetric("patchGasNanos", nanos);
    }

    /**
     * Adds a sample to the {@code documentUpdateRoutingNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addDocumentUpdateRoutingNanos(long nanos) {
        addMetric("documentUpdateRoutingNanos", nanos);
    }

    /**
     * Increments the {@code documentUpdateEventsBuilt} counter.
     */
    default void incrementDocumentUpdateEventsBuilt() {
        addMetric("documentUpdateEventsBuilt", 1L);
    }

    /**
     * Increments the {@code documentUpdateEventsSkippedNoChannel} counter.
     */
    default void incrementDocumentUpdateEventsSkippedNoChannel() {
        addMetric("documentUpdateEventsSkippedNoChannel", 1L);
    }

    /**
     * Adds a sample to the {@code batchPatchPlanningNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBatchPatchPlanningNanos(long nanos) {
        addMetric("batchPatchPlanningNanos", nanos);
    }

    /**
     * Adds a sample to the {@code batchPatchConformanceNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBatchPatchConformanceNanos(long nanos) {
        addMetric("batchPatchConformanceNanos", nanos);
    }

    /**
     * Adds a sample to the {@code batchPatchBuildUpdatesNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBatchPatchBuildUpdatesNanos(long nanos) {
        addMetric("batchPatchBuildUpdatesNanos", nanos);
    }

    /**
     * Adds a sample to the {@code batchPatchCommitNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBatchPatchCommitNanos(long nanos) {
        addMetric("batchPatchCommitNanos", nanos);
    }

    /**
     * Increments the {@code documentUpdateBeforeMaterializations} counter.
     */
    default void incrementDocumentUpdateBeforeMaterializations() {
        addMetric("documentUpdateBeforeMaterializations", 1L);
    }

    /**
     * Increments the {@code documentUpdateAfterMaterializations} counter.
     */
    default void incrementDocumentUpdateAfterMaterializations() {
        addMetric("documentUpdateAfterMaterializations", 1L);
    }

    /** Records one reusable observable-sequential patch planning session. */
    default void incrementPatchSequencesPrepared() {
        addMetric("patchSequencesPrepared", 1L);
    }

    /**
     * Records patches accepted by reusable observable-sequential sessions.
     *
     * @param count amount to add to the metric
     */
    default void addPatchesPrepared(long count) {
        addMetric("patchesPrepared", count);
    }

    /** Records use of the legacy standalone one-patch transaction path. */
    default void incrementSingletonPatchTransactions() {
        addMetric("singletonPatchTransactions", 1L);
    }

    /**
     * Adds a sample to the {@code sequencePlanningNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addSequencePlanningNanos(long nanos) {
        addMetric("sequencePlanningNanos", nanos);
    }

    /**
     * Adds a sample to the {@code sequenceConformanceNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addSequenceConformanceNanos(long nanos) {
        addMetric("sequenceConformanceNanos", nanos);
    }

    /**
     * Adds a sample to the {@code sequenceCommitNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addSequenceCommitNanos(long nanos) {
        addMetric("sequenceCommitNanos", nanos);
    }

    /**
     * Adds a sample to the {@code sequenceFinalCacheCommitNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addSequenceFinalCacheCommitNanos(long nanos) {
        addMetric("sequenceFinalCacheCommitNanos", nanos);
    }

    /**
     * Increments the {@code sequenceIntermediateSnapshotAdvances} counter.
     */
    default void incrementSequenceIntermediateSnapshotAdvances() {
        addMetric("sequenceIntermediateSnapshotAdvances", 1L);
    }

    /**
     * Increments the {@code sequenceSharedSnapshotCacheInserts} counter.
     */
    default void incrementSequenceSharedSnapshotCacheInserts() {
        addMetric("sequenceSharedSnapshotCacheInserts", 1L);
    }

    /**
     * Increments the {@code sequenceFinalSnapshotCacheInserts} counter.
     */
    default void incrementSequenceFinalSnapshotCacheInserts() {
        addMetric("sequenceFinalSnapshotCacheInserts", 1L);
    }

    /**
     * Increments the {@code sequenceSuffixRebases} counter.
     */
    default void incrementSequenceSuffixRebases() {
        addMetric("sequenceSuffixRebases", 1L);
    }

    /**
     * Increments the {@code sequenceStalePreviewFallbacks} counter.
     */
    default void incrementSequenceStalePreviewFallbacks() {
        addMetric("sequenceStalePreviewFallbacks", 1L);
    }

    /**
     * Increments the {@code sequenceFallbackPatches} counter.
     */
    default void incrementSequenceFallbackPatches() {
        addMetric("sequenceFallbackPatches", 1L);
    }

    /**
     * Increments the {@code parsedPointerCacheHits} counter.
     */
    default void incrementParsedPointerCacheHits() {
        addMetric("parsedPointerCacheHits", 1L);
    }

    /**
     * Increments the {@code parsedPointerCacheMisses} counter.
     */
    default void incrementParsedPointerCacheMisses() {
        addMetric("parsedPointerCacheMisses", 1L);
    }

    /**
     * Increments the {@code frozenPatchValueHits} counter.
     */
    default void incrementFrozenPatchValueHits() {
        addMetric("frozenPatchValueHits", 1L);
    }

    /**
     * Increments the {@code patchValueMaterializations} counter.
     */
    default void incrementPatchValueMaterializations() {
        addMetric("patchValueMaterializations", 1L);
    }

    /**
     * Increments the {@code frozenNodesCreated} counter.
     */
    default void incrementFrozenNodesCreated() {
        addMetric("frozenNodesCreated", 1L);
    }

    /**
     * Increments the {@code frozenNodesReused} counter.
     */
    default void incrementFrozenNodesReused() {
        addMetric("frozenNodesReused", 1L);
    }

    /**
     * Increments the {@code canonicalIdentityCalculations} counter.
     */
    default void incrementCanonicalIdentityCalculations() {
        addMetric("canonicalIdentityCalculations", 1L);
    }

    /**
     * Increments the {@code resolvedIdentityCalculations} counter.
     */
    default void incrementResolvedIdentityCalculations() {
        addMetric("resolvedIdentityCalculations", 1L);
    }

    /**
     * Adds a sample to the {@code canonicalBytesWritten} metric.
     *
     * @param count amount to add to the metric
     */
    default void addCanonicalBytesWritten(long count) {
        addMetric("canonicalBytesWritten", count);
    }

    /**
     * Increments the {@code jcsFallbacks} counter.
     */
    default void incrementJcsFallbacks() {
        addMetric("jcsFallbacks", 1L);
    }

    /**
     * Adds a sample to the {@code base58EncodeNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBase58EncodeNanos(long nanos) {
        addMetric("base58EncodeNanos", nanos);
    }

    /**
     * Adds a sample to the {@code base58DecodeNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBase58DecodeNanos(long nanos) {
        addMetric("base58DecodeNanos", nanos);
    }

    /**
     * Adds a sample to the {@code blueIdDigestNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addBlueIdDigestNanos(long nanos) {
        addMetric("blueIdDigestNanos", nanos);
    }

    /**
     * Increments the {@code resolvedStructuralKeyBuilds} counter.
     */
    default void incrementResolvedStructuralKeyBuilds() {
        addMetric("resolvedStructuralKeyBuilds", 1L);
    }

    /**
     * Generic additive counter hook used by the default phase-specific methods
     * below. Implementations may override individual methods instead. Metric
     * names are fixed library constants and must not contain document paths or
     * BlueIds.
     *
     * @param metricName stable metric name; implementations must not interpret it as document data
     * @param delta signed amount to add to the counter
     */
    default void addMetric(String metricName, long delta) {
    }

    /**
     * Records a current-value gauge rather than an additive counter.
     *
     * @param metricName stable metric name; implementations must not interpret it as document data
     * @param value gauge value to record
     */
    default void setMetric(String metricName, long value) {
    }

    /**
     * Records the maximum value observed for a gauge.
     *
     * @param metricName stable metric name; implementations must not interpret it as document data
     * @param value gauge value to record
     */
    default void recordMetricHighWater(String metricName, long value) {
    }

    /**
     * Increments the {@code patchImpactAnalyses} counter.
     */
    default void incrementPatchImpactAnalyses() {
        addMetric("patchImpactAnalyses", 1L);
    }

    /**
     * Increments the {@code patchImpactValueOnly} counter.
     */
    default void incrementPatchImpactValueOnly() {
        addMetric("patchImpactValueOnly", 1L);
    }

    /**
     * Increments the {@code patchImpactObjectMemberValue} counter.
     */
    default void incrementPatchImpactObjectMemberValue() {
        addMetric("patchImpactObjectMemberValue", 1L);
    }

    /**
     * Increments the {@code patchImpactCollectionShape} counter.
     */
    default void incrementPatchImpactCollectionShape() {
        addMetric("patchImpactCollectionShape", 1L);
    }

    /**
     * Increments the {@code patchImpactTypeMetadata} counter.
     */
    default void incrementPatchImpactTypeMetadata() {
        addMetric("patchImpactTypeMetadata", 1L);
    }

    /**
     * Increments the {@code patchImpactSchemaMetadata} counter.
     */
    default void incrementPatchImpactSchemaMetadata() {
        addMetric("patchImpactSchemaMetadata", 1L);
    }

    /**
     * Increments the {@code patchImpactReference} counter.
     */
    default void incrementPatchImpactReference() {
        addMetric("patchImpactReference", 1L);
    }

    /**
     * Increments the {@code patchImpactMergePolicy} counter.
     */
    default void incrementPatchImpactMergePolicy() {
        addMetric("patchImpactMergePolicy", 1L);
    }

    /**
     * Increments the {@code patchImpactContractsOrProcessing} counter.
     */
    default void incrementPatchImpactContractsOrProcessing() {
        addMetric("patchImpactContractsOrProcessing", 1L);
    }

    /**
     * Increments the {@code patchImpactProcessorManagedState} counter.
     */
    default void incrementPatchImpactProcessorManagedState() {
        addMetric("patchImpactProcessorManagedState", 1L);
    }

    /**
     * Increments the {@code processorManagedMarkerPatches} counter.
     */
    default void incrementProcessorManagedMarkerPatches() {
        addMetric("processorManagedMarkerPatches", 1L);
    }

    /**
     * Increments the {@code processorManagedMarkerIncrementalResolutions} counter.
     */
    default void incrementProcessorManagedMarkerIncrementalResolutions() {
        addMetric("processorManagedMarkerIncrementalResolutions", 1L);
    }

    /**
     * Increments the {@code initializationDocumentIdContentBlueIdCalculations} counter.
     */
    default void incrementInitializationDocumentIdContentBlueIdCalculations() {
        addMetric("initializationDocumentIdContentBlueIdCalculations", 1L);
    }

    /**
     * Increments the {@code initializationDocumentIdCanonicalMaterializations} counter.
     */
    default void incrementInitializationDocumentIdCanonicalMaterializations() {
        addMetric("initializationDocumentIdCanonicalMaterializations", 1L);
    }

    /**
     * Increments the {@code initializationDocumentIdUncheckedCalculations} counter.
     */
    default void incrementInitializationDocumentIdUncheckedCalculations() {
        addMetric("initializationDocumentIdUncheckedCalculations", 1L);
    }

    /**
     * Increments the {@code initializationDocumentIdNodeMaterializations} counter.
     */
    default void incrementInitializationDocumentIdNodeMaterializations() {
        addMetric("initializationDocumentIdNodeMaterializations", 1L);
    }

    /**
     * Increments the {@code initializationDocumentIdFrozenUncheckedCalculations} counter.
     */
    default void incrementInitializationDocumentIdFrozenUncheckedCalculations() {
        addMetric("initializationDocumentIdFrozenUncheckedCalculations", 1L);
    }

    /**
     * Increments the {@code processorInputStrictCanonical} counter.
     */
    default void incrementProcessorInputStrictCanonical() {
        addMetric("processorInputStrictCanonical", 1L);
    }

    /**
     * Increments the {@code processorInputUncheckedCanonical} counter.
     */
    default void incrementProcessorInputUncheckedCanonical() {
        addMetric("processorInputUncheckedCanonical", 1L);
    }

    /**
     * Increments the {@code processorPublishedStrictCanonical} counter.
     */
    default void incrementProcessorPublishedStrictCanonical() {
        addMetric("processorPublishedStrictCanonical", 1L);
    }

    /**
     * Increments the {@code processorPublishedUncheckedCanonical} counter.
     */
    default void incrementProcessorPublishedUncheckedCanonical() {
        addMetric("processorPublishedUncheckedCanonical", 1L);
    }

    /**
     * Increments the {@code processorPublicationCanonicalizations} counter.
     */
    default void incrementProcessorPublicationCanonicalizations() {
        addMetric("processorPublicationCanonicalizations", 1L);
    }

    /**
     * Adds a sample to the {@code processorPublicationCanonicalizationNanos} metric.
     *
     * @param nanos elapsed duration in nanoseconds
     */
    default void addProcessorPublicationCanonicalizationNanos(long nanos) {
        addMetric("processorPublicationCanonicalizationNanos", nanos);
    }

    /**
     * Increments the {@code processorPublicationCanonicalMaterializations} counter.
     */
    default void incrementProcessorPublicationCanonicalMaterializations() {
        addMetric("processorPublicationCanonicalMaterializations", 1L);
    }

    /**
     * Increments the {@code processorPublicationStrictBlueIdCalculations} counter.
     */
    default void incrementProcessorPublicationStrictBlueIdCalculations() {
        addMetric("processorPublicationStrictBlueIdCalculations", 1L);
    }

    /**
     * Increments the {@code processorPublicationIdentityMismatches} counter.
     */
    default void incrementProcessorPublicationIdentityMismatches() {
        addMetric("processorPublicationIdentityMismatches", 1L);
    }

    /**
     * Increments the {@code processorPublicationInvariantChecks} counter.
     */
    default void incrementProcessorPublicationInvariantChecks() {
        addMetric("processorPublicationInvariantChecks", 1L);
    }

    /**
     * Increments the {@code incrementalMergerCapabilityRequests} counter.
     */
    default void incrementIncrementalMergerCapabilityRequests() {
        addMetric("incrementalMergerCapabilityRequests", 1L);
    }

    /**
     * Increments the {@code incrementalMergerCapabilityAllowed} counter.
     */
    default void incrementIncrementalMergerCapabilityAllowed() {
        addMetric("incrementalMergerCapabilityAllowed", 1L);
    }

    /**
     * Increments the {@code incrementalMergerCapabilityDenied} counter.
     */
    default void incrementIncrementalMergerCapabilityDenied() {
        addMetric("incrementalMergerCapabilityDenied", 1L);
    }

    /**
     * Increments the {@code incrementalMergerCapabilityDeniedByConformance} counter.
     */
    default void incrementIncrementalMergerCapabilityDeniedByConformance() {
        addMetric("incrementalMergerCapabilityDeniedByConformance", 1L);
    }

    /**
     * Increments the {@code incrementalMergerCapabilityDeniedBySnapshotManager} counter.
     */
    default void incrementIncrementalMergerCapabilityDeniedBySnapshotManager() {
        addMetric("incrementalMergerCapabilityDeniedBySnapshotManager", 1L);
    }

    /**
     * Increments the {@code patchImpactRootReplacement} counter.
     */
    default void incrementPatchImpactRootReplacement() {
        addMetric("patchImpactRootReplacement", 1L);
    }

    /**
     * Increments the {@code patchImpactUnknown} counter.
     */
    default void incrementPatchImpactUnknown() {
        addMetric("patchImpactUnknown", 1L);
    }

    /**
     * Increments the {@code incrementalSnapshotResolutions} counter.
     */
    default void incrementIncrementalSnapshotResolutions() {
        addMetric("incrementalSnapshotResolutions", 1L);
    }

    /**
     * Increments the {@code fullSnapshotFallback} counter.
     *
     * @param reason stable fallback category used as a metric-name suffix
     */
    default void incrementFullSnapshotFallback(String reason) {
        addMetric("fullSnapshotFallbacks", 1L);
        addMetric("fullSnapshotFallbackReason." + reason, 1L);
    }

    /**
     * Increments the {@code fullCanonicalRootMaterializations} counter.
     */
    default void incrementFullCanonicalRootMaterializations() {
        addMetric("fullCanonicalRootMaterializations", 1L);
    }

    /**
     * Increments the {@code fullResolvedRootMaterializations} counter.
     */
    default void incrementFullResolvedRootMaterializations() {
        addMetric("fullResolvedRootMaterializations", 1L);
    }

    /**
     * Adds a sample to the {@code incrementalBoundaryPathDepth} metric.
     *
     * @param depth boundary path depth to add
     */
    default void addIncrementalBoundaryPathDepth(long depth) {
        addMetric("incrementalBoundaryPathDepth", depth);
    }

    /**
     * Adds a sample to the {@code incrementalBoundaryNodeCount} metric.
     *
     * @param count amount to add to the metric
     */
    default void addIncrementalBoundaryNodeCount(long count) {
        addMetric("incrementalBoundaryNodeCount", count);
    }

    /**
     * Adds a sample to the {@code incrementalAncestorsRevalidated} metric.
     *
     * @param count amount to add to the metric
     */
    default void addIncrementalAncestorsRevalidated(long count) {
        addMetric("incrementalAncestorsRevalidated", count);
    }

    /**
     * Adds a sample to the {@code referencesReResolved} metric.
     *
     * @param count amount to add to the metric
     */
    default void addReferencesReResolved(long count) {
        addMetric("referencesReResolved", count);
    }

    /**
     * Adds a sample to the {@code referencesReused} metric.
     *
     * @param count amount to add to the metric
     */
    default void addReferencesReused(long count) {
        addMetric("referencesReused", count);
    }

    /**
     * Increments the {@code conformancePlans} counter.
     */
    default void incrementConformancePlans() {
        addMetric("conformancePlans", 1L);
    }

    /**
     * Adds a sample to the {@code conformanceNodesVisited} metric.
     *
     * @param count amount to add to the metric
     */
    default void addConformanceNodesVisited(long count) {
        addMetric("conformanceNodesVisited", count);
    }

    /**
     * Adds a sample to the {@code conformanceTypedBoundariesConsidered} metric.
     *
     * @param count amount to add to the metric
     */
    default void addConformanceTypedBoundariesConsidered(long count) {
        addMetric("conformanceTypedBoundariesConsidered", count);
    }

    /**
     * Adds a sample to the {@code conformanceTypedBoundariesValidated} metric.
     *
     * @param count amount to add to the metric
     */
    default void addConformanceTypedBoundariesValidated(long count) {
        addMetric("conformanceTypedBoundariesValidated", count);
    }

    /**
     * Adds a sample to the {@code conformanceTypedBoundariesGeneralized} metric.
     *
     * @param count amount to add to the metric
     */
    default void addConformanceTypedBoundariesGeneralized(long count) {
        addMetric("conformanceTypedBoundariesGeneralized", count);
    }

    /**
     * Increments the {@code conformanceFullRootScans} counter.
     */
    default void incrementConformanceFullRootScans() {
        addMetric("conformanceFullRootScans", 1L);
    }

    /**
     * Adds a sample to the {@code conformanceMutableNodeMaterializations} metric.
     *
     * @param count amount to add to the metric
     */
    default void addConformanceMutableNodeMaterializations(long count) {
        addMetric("conformanceMutableNodeMaterializations", count);
    }

    /**
     * Adds a sample to the {@code conformanceMergerInvocations} metric.
     *
     * @param count amount to add to the metric
     */
    default void addConformanceMergerInvocations(long count) {
        addMetric("conformanceMergerInvocations", count);
    }

    /**
     * Increments the {@code conformanceTypePlanHits} counter.
     */
    default void incrementConformanceTypePlanHits() {
        addMetric("conformanceTypePlanHits", 1L);
    }

    /**
     * Increments the {@code conformanceTypePlanMisses} counter.
     */
    default void incrementConformanceTypePlanMisses() {
        addMetric("conformanceTypePlanMisses", 1L);
    }

    /**
     * Increments the {@code conformanceSchemaPlanHits} counter.
     */
    default void incrementConformanceSchemaPlanHits() {
        addMetric("conformanceSchemaPlanHits", 1L);
    }

    /**
     * Increments the {@code conformanceSchemaPlanMisses} counter.
     */
    default void incrementConformanceSchemaPlanMisses() {
        addMetric("conformanceSchemaPlanMisses", 1L);
    }

    /**
     * Increments the {@code compiledPatternHits} counter.
     */
    default void incrementCompiledPatternHits() {
        addMetric("compiledPatternHits", 1L);
    }

    /**
     * Increments the {@code compiledPatternMisses} counter.
     */
    default void incrementCompiledPatternMisses() {
        addMetric("compiledPatternMisses", 1L);
    }

    /**
     * Increments the {@code canonicalDigestWrites} counter.
     */
    default void incrementCanonicalDigestWrites() {
        addMetric("canonicalDigestWrites", 1L);
    }

    /**
     * Adds a sample to the {@code canonicalDigestBytes} metric.
     *
     * @param count amount to add to the metric
     */
    default void addCanonicalDigestBytes(long count) {
        addMetric("canonicalDigestBytes", count);
    }

    /**
     * Increments the {@code canonicalGenericGraphFallbacks} counter.
     */
    default void incrementCanonicalGenericGraphFallbacks() {
        addMetric("canonicalGenericGraphFallbacks", 1L);
    }

    /**
     * Increments the {@code canonicalWholeStringsCreated} counter.
     */
    default void incrementCanonicalWholeStringsCreated() {
        addMetric("canonicalWholeStringsCreated", 1L);
    }

    /**
     * Increments the {@code canonicalWholeByteArraysCreated} counter.
     */
    default void incrementCanonicalWholeByteArraysCreated() {
        addMetric("canonicalWholeByteArraysCreated", 1L);
    }

    /**
     * Increments the {@code blueIdCalculations} counter.
     */
    default void incrementBlueIdCalculations() {
        addMetric("blueIdCalculations", 1L);
    }

    /**
     * Increments the {@code blueIdMemoHits} counter.
     */
    default void incrementBlueIdMemoHits() {
        addMetric("blueIdMemoHits", 1L);
    }

    /**
     * Increments the {@code base58Encodes} counter.
     */
    default void incrementBase58Encodes() {
        addMetric("base58Encodes", 1L);
    }

    /**
     * Increments the {@code frozenPatchValuesAccepted} counter.
     */
    default void incrementFrozenPatchValuesAccepted() {
        addMetric("frozenPatchValuesAccepted", 1L);
    }

    /**
     * Increments the {@code mutablePatchValuesFrozen} counter.
     */
    default void incrementMutablePatchValuesFrozen() {
        incrementMutablePatchValuesFrozen(PatchSource.LEGACY_PUBLIC_API);
    }

    /**
     * Increments the {@code mutablePatchValuesFrozen} counter.
     *
     * @param source patch-source category; {@code null} is recorded as the unknown internal source
     */
    default void incrementMutablePatchValuesFrozen(PatchSource source) {
        PatchSource fixedSource = source != null ? source : PatchSource.UNKNOWN_INTERNAL;
        addMetric("mutablePatchValuesFrozen", 1L);
        addMetric("mutablePatchValuesFrozenBySource." + fixedSource.name(), 1L);
    }

    /**
     * Increments the {@code frozenPatchValuesMaterialized} counter.
     */
    default void incrementFrozenPatchValuesMaterialized() {
        addMetric("frozenPatchValuesMaterialized", 1L);
    }

    /**
     * Increments the {@code fullFrozenRootToNodeMaterializations} counter.
     */
    default void incrementFullFrozenRootToNodeMaterializations() {
        addMetric("fullFrozenRootToNodeMaterializations", 1L);
    }

    /**
     * Increments the {@code subtreeToNodeMaterializations} counter.
     */
    default void incrementSubtreeToNodeMaterializations() {
        addMetric("subtreeToNodeMaterializations", 1L);
    }

    /**
     * Increments the {@code nodeCloneCalls} counter.
     *
     * @param purpose stable clone-purpose category used as a metric-name suffix
     */
    default void incrementNodeCloneCalls(String purpose) {
        addMetric("nodeCloneCallsByPurpose." + purpose, 1L);
    }

    /**
     * Sets the {@code cacheCurrentWeightBytes} gauge.
     *
     * @param cacheName stable cache name used as a metric-name segment
     * @param value gauge value to record
     */
    default void setCacheCurrentWeightBytes(String cacheName, long value) {
        setMetric("cache." + cacheName + ".currentWeightBytes", value);
    }

    /**
     * Records an observation for the {@code cacheHighWaterBytes} high-water gauge.
     *
     * @param cacheName stable cache name used as a metric-name segment
     * @param value gauge value to record
     */
    default void recordCacheHighWaterBytes(String cacheName, long value) {
        recordMetricHighWater("cache." + cacheName + ".highWaterBytes", value);
    }

    /**
     * Sets the {@code cacheEntries} gauge.
     *
     * @param cacheName stable cache name used as a metric-name segment
     * @param value gauge value to record
     */
    default void setCacheEntries(String cacheName, long value) {
        setMetric("cache." + cacheName + ".entries", value);
    }

    /**
     * Increments the {@code cacheHits} counter.
     *
     * @param cacheName stable cache name used as a metric-name segment
     */
    default void incrementCacheHits(String cacheName) {
        addMetric("cache." + cacheName + ".hits", 1L);
    }

    /**
     * Increments the {@code cacheMisses} counter.
     *
     * @param cacheName stable cache name used as a metric-name segment
     */
    default void incrementCacheMisses(String cacheName) {
        addMetric("cache." + cacheName + ".misses", 1L);
    }

    /**
     * Increments the {@code cacheEvictions} counter.
     *
     * @param cacheName stable cache name used as a metric-name segment
     */
    default void incrementCacheEvictions(String cacheName) {
        addMetric("cache." + cacheName + ".evictions", 1L);
    }

    /**
     * Increments the {@code cacheOversizedRejections} counter.
     *
     * @param cacheName stable cache name used as a metric-name segment
     */
    default void incrementCacheOversizedRejections(String cacheName) {
        addMetric("cache." + cacheName + ".oversizedRejections", 1L);
    }

    /**
     * Sets the {@code cachePinnedEntries} gauge.
     *
     * @param cacheName stable cache name used as a metric-name segment
     * @param value gauge value to record
     */
    default void setCachePinnedEntries(String cacheName, long value) {
        setMetric("cache." + cacheName + ".pinnedEntries", value);
    }

    /**
     * Sets the {@code cacheDerivedEntries} gauge.
     *
     * @param cacheName stable cache name used as a metric-name segment
     * @param value gauge value to record
     */
    default void setCacheDerivedEntries(String cacheName, long value) {
        setMetric("cache." + cacheName + ".derivedEntries", value);
    }

    /**
     * Increments the {@code runtimeCloseCalls} counter.
     */
    default void incrementRuntimeCloseCalls() {
        addMetric("runtimeCloseCalls", 1L);
    }

    /**
     * Adds a sample to the {@code runtimeCloseReleasedWeightBytes} metric.
     *
     * @param count amount to add to the metric
     */
    default void addRuntimeCloseReleasedWeightBytes(long count) {
        addMetric("runtimeCloseReleasedWeightBytes", count);
    }

    /**
     * Adds a sample to the {@code sequenceCacheEntriesReleased} metric.
     *
     * @param count amount to add to the metric
     */
    default void addSequenceCacheEntriesReleased(long count) {
        addMetric("sequenceCacheEntriesReleased", count);
    }

    /**
     * Increments the {@code referenceReachabilityDeltaUpdates} counter.
     */
    default void incrementReferenceReachabilityDeltaUpdates() {
        addMetric("referenceReachabilityDeltaUpdates", 1L);
    }

    /**
     * Increments the {@code referenceReachabilityFullScans} counter.
     */
    default void incrementReferenceReachabilityFullScans() {
        addMetric("referenceReachabilityFullScans", 1L);
    }
}
