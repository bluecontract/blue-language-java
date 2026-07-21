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
        addMetric("processDocumentNanos", nanos);
    }

    default void addBlueProcessDocumentNanos(long nanos) {
        addMetric("blueProcessDocumentNanos", nanos);
    }

    default void addEventPreprocessNanos(long nanos) {
        addMetric("eventPreprocessNanos", nanos);
    }

    default void addResultSnapshotAttachNanos(long nanos) {
        addMetric("resultSnapshotAttachNanos", nanos);
    }

    default void addBlueIdCalculationNanos(long nanos) {
        addMetric("blueIdCalculationNanos", nanos);
    }

    default void addProcessingSnapshotCacheLookupNanos(long nanos) {
        addMetric("processingSnapshotCacheLookupNanos", nanos);
    }

    default void incrementProcessingSnapshotCacheHits() {
        addMetric("processingSnapshotCacheHits", 1L);
    }

    default void incrementProcessingSnapshotCacheMisses() {
        addMetric("processingSnapshotCacheMisses", 1L);
    }

    default void addProcessingSnapshotFromDocumentNanos(long nanos) {
        addMetric("processingSnapshotFromDocumentNanos", nanos);
    }

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
     */
    default void addProcessEventSnapshotConstructionNanos(long nanos) {
        addMetric("processEventSnapshotConstructionNanos", nanos);
    }

    default void addBundleLoadNanos(long nanos) {
        addMetric("bundleLoadNanos", nanos);
    }

    default void addBundleLoadCacheKeyBuildNanos(long nanos) {
        addMetric("bundleLoadCacheKeyBuildNanos", nanos);
    }

    default void addBundleLoadActualBuildNanos(long nanos) {
        addMetric("bundleLoadActualBuildNanos", nanos);
    }

    default void addBundleLoadReuseNanos(long nanos) {
        addMetric("bundleLoadReuseNanos", nanos);
    }

    default void incrementBundleLoadCacheHits() {
        addMetric("bundleLoadCacheHits", 1L);
    }

    default void incrementBundleLoadCacheMisses() {
        addMetric("bundleLoadCacheMisses", 1L);
    }

    default void incrementBundlesBuilt() {
        addMetric("bundlesBuilt", 1L);
    }

    default void incrementBundlesReused() {
        addMetric("bundlesReused", 1L);
    }

    default void incrementBundleScopeLoadAttempts() {
        addMetric("bundleScopeLoadAttempts", 1L);
    }

    default void incrementBundleScopeExecutionCacheHits() {
        addMetric("bundleScopeExecutionCacheHits", 1L);
    }

    default void incrementBundleScopeRefreshes() {
        addMetric("bundleScopeRefreshes", 1L);
    }

    default void addBundleScopeTerminationCheckNanos(long nanos) {
        addMetric("bundleScopeTerminationCheckNanos", nanos);
    }

    default void addBundleScopeResolvedLookupNanos(long nanos) {
        addMetric("bundleScopeResolvedLookupNanos", nanos);
    }

    default void addBundleScopeContractLoadNanos(long nanos) {
        addMetric("bundleScopeContractLoadNanos", nanos);
    }

    default void addChannelDiscoveryNanos(long nanos) {
        addMetric("channelDiscoveryNanos", nanos);
    }

    default void addChannelMatchNanos(long nanos) {
        addMetric("channelMatchNanos", nanos);
    }

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

    default void addHandlerDiscoveryNanos(long nanos) {
        addMetric("handlerDiscoveryNanos", nanos);
    }

    default void addHandlerMatchNanos(long nanos) {
        addMetric("handlerMatchNanos", nanos);
    }

    default void incrementHandlerMatchAttempts() {
        addMetric("handlerMatchAttempts", 1L);
    }

    default void addHandlerExecutionNanos(long nanos) {
        addMetric("handlerExecutionNanos", nanos);
    }

    default void incrementHandlersExecuted() {
        addMetric("handlersExecuted", 1L);
    }

    default void addTriggeredEventRoutingNanos(long nanos) {
        addMetric("triggeredEventRoutingNanos", nanos);
    }

    default void incrementTriggeredEventsRouted() {
        addMetric("triggeredEventsRouted", 1L);
    }

    default void addCheckpointUpdateNanos(long nanos) {
        addMetric("checkpointUpdateNanos", nanos);
    }

    default void addCheckpointEnsureNanos(long nanos) {
        addMetric("checkpointEnsureNanos", nanos);
    }

    default void addCheckpointFindNanos(long nanos) {
        addMetric("checkpointFindNanos", nanos);
    }

    default void addCheckpointCurrentIdentityNanos(long nanos) {
        addMetric("checkpointCurrentIdentityNanos", nanos);
    }

    default void addCheckpointIsNewerNanos(long nanos) {
        addMetric("checkpointIsNewerNanos", nanos);
    }

    default void addCheckpointDuplicateNanos(long nanos) {
        addMetric("checkpointDuplicateNanos", nanos);
    }

    default void addCheckpointPersistNanos(long nanos) {
        addMetric("checkpointPersistNanos", nanos);
    }

    default void incrementCheckpointIdentityCacheHits() {
        addMetric("checkpointIdentityCacheHits", 1L);
    }

    default void incrementCheckpointIdentityCacheMisses() {
        addMetric("checkpointIdentityCacheMisses", 1L);
    }

    default void incrementCheckpointStoredIdentityCacheHits() {
        addMetric("checkpointStoredIdentityCacheHits", 1L);
    }

    default void incrementCheckpointStoredIdentityCacheMisses() {
        addMetric("checkpointStoredIdentityCacheMisses", 1L);
    }

    default void addCheckpointDirectBlueIdNanos(long nanos) {
        addMetric("checkpointDirectBlueIdNanos", nanos);
    }

    default void addCheckpointContentBlueIdNanos(long nanos) {
        addMetric("checkpointContentBlueIdNanos", nanos);
    }

    default void addCheckpointFallbackNanos(long nanos) {
        addMetric("checkpointFallbackNanos", nanos);
    }

    default void addSnapshotCommitNanos(long nanos) {
        addMetric("snapshotCommitNanos", nanos);
    }

    default void addPostProcessingNanos(long nanos) {
        addMetric("postProcessingNanos", nanos);
    }

    default void addPatchBoundaryNanos(long nanos) {
        addMetric("patchBoundaryNanos", nanos);
    }

    default void addPatchGasNanos(long nanos) {
        addMetric("patchGasNanos", nanos);
    }

    default void addDocumentUpdateRoutingNanos(long nanos) {
        addMetric("documentUpdateRoutingNanos", nanos);
    }

    default void incrementDocumentUpdateEventsBuilt() {
        addMetric("documentUpdateEventsBuilt", 1L);
    }

    default void incrementDocumentUpdateEventsSkippedNoChannel() {
        addMetric("documentUpdateEventsSkippedNoChannel", 1L);
    }

    default void addBatchPatchPlanningNanos(long nanos) {
        addMetric("batchPatchPlanningNanos", nanos);
    }

    default void addBatchPatchConformanceNanos(long nanos) {
        addMetric("batchPatchConformanceNanos", nanos);
    }

    default void addBatchPatchBuildUpdatesNanos(long nanos) {
        addMetric("batchPatchBuildUpdatesNanos", nanos);
    }

    default void addBatchPatchCommitNanos(long nanos) {
        addMetric("batchPatchCommitNanos", nanos);
    }

    default void incrementDocumentUpdateBeforeMaterializations() {
        addMetric("documentUpdateBeforeMaterializations", 1L);
    }

    default void incrementDocumentUpdateAfterMaterializations() {
        addMetric("documentUpdateAfterMaterializations", 1L);
    }

    /** Records one reusable observable-sequential patch planning session. */
    default void incrementPatchSequencesPrepared() {
        addMetric("patchSequencesPrepared", 1L);
    }

    /** Records patches accepted by reusable observable-sequential sessions. */
    default void addPatchesPrepared(long count) {
        addMetric("patchesPrepared", count);
    }

    /** Records use of the legacy standalone one-patch transaction path. */
    default void incrementSingletonPatchTransactions() {
        addMetric("singletonPatchTransactions", 1L);
    }

    default void addSequencePlanningNanos(long nanos) {
        addMetric("sequencePlanningNanos", nanos);
    }

    default void addSequenceConformanceNanos(long nanos) {
        addMetric("sequenceConformanceNanos", nanos);
    }

    default void addSequenceCommitNanos(long nanos) {
        addMetric("sequenceCommitNanos", nanos);
    }

    default void addSequenceFinalCacheCommitNanos(long nanos) {
        addMetric("sequenceFinalCacheCommitNanos", nanos);
    }

    default void incrementSequenceIntermediateSnapshotAdvances() {
        addMetric("sequenceIntermediateSnapshotAdvances", 1L);
    }

    default void incrementSequenceSharedSnapshotCacheInserts() {
        addMetric("sequenceSharedSnapshotCacheInserts", 1L);
    }

    default void incrementSequenceFinalSnapshotCacheInserts() {
        addMetric("sequenceFinalSnapshotCacheInserts", 1L);
    }

    default void incrementSequenceSuffixRebases() {
        addMetric("sequenceSuffixRebases", 1L);
    }

    default void incrementSequenceStalePreviewFallbacks() {
        addMetric("sequenceStalePreviewFallbacks", 1L);
    }

    default void incrementSequenceFallbackPatches() {
        addMetric("sequenceFallbackPatches", 1L);
    }

    default void incrementParsedPointerCacheHits() {
        addMetric("parsedPointerCacheHits", 1L);
    }

    default void incrementParsedPointerCacheMisses() {
        addMetric("parsedPointerCacheMisses", 1L);
    }

    default void incrementFrozenPatchValueHits() {
        addMetric("frozenPatchValueHits", 1L);
    }

    default void incrementPatchValueMaterializations() {
        addMetric("patchValueMaterializations", 1L);
    }

    default void incrementFrozenNodesCreated() {
        addMetric("frozenNodesCreated", 1L);
    }

    default void incrementFrozenNodesReused() {
        addMetric("frozenNodesReused", 1L);
    }

    default void incrementCanonicalIdentityCalculations() {
        addMetric("canonicalIdentityCalculations", 1L);
    }

    default void incrementResolvedIdentityCalculations() {
        addMetric("resolvedIdentityCalculations", 1L);
    }

    default void addCanonicalBytesWritten(long count) {
        addMetric("canonicalBytesWritten", count);
    }

    default void incrementJcsFallbacks() {
        addMetric("jcsFallbacks", 1L);
    }

    default void addBase58EncodeNanos(long nanos) {
        addMetric("base58EncodeNanos", nanos);
    }

    default void addBase58DecodeNanos(long nanos) {
        addMetric("base58DecodeNanos", nanos);
    }

    default void addBlueIdDigestNanos(long nanos) {
        addMetric("blueIdDigestNanos", nanos);
    }

    default void incrementResolvedStructuralKeyBuilds() {
        addMetric("resolvedStructuralKeyBuilds", 1L);
    }

    /**
     * Generic additive counter hook used by the default phase-specific methods
     * below. Implementations may override individual methods instead. Metric
     * names are fixed library constants and must not contain document paths or
     * BlueIds.
     */
    default void addMetric(String metricName, long delta) {
    }

    /** Records a current-value gauge rather than an additive counter. */
    default void setMetric(String metricName, long value) {
    }

    /** Records the maximum value observed for a gauge. */
    default void recordMetricHighWater(String metricName, long value) {
    }

    default void incrementPatchImpactAnalyses() {
        addMetric("patchImpactAnalyses", 1L);
    }

    default void incrementPatchImpactValueOnly() {
        addMetric("patchImpactValueOnly", 1L);
    }

    default void incrementPatchImpactObjectMemberValue() {
        addMetric("patchImpactObjectMemberValue", 1L);
    }

    default void incrementPatchImpactCollectionShape() {
        addMetric("patchImpactCollectionShape", 1L);
    }

    default void incrementPatchImpactTypeMetadata() {
        addMetric("patchImpactTypeMetadata", 1L);
    }

    default void incrementPatchImpactSchemaMetadata() {
        addMetric("patchImpactSchemaMetadata", 1L);
    }

    default void incrementPatchImpactReference() {
        addMetric("patchImpactReference", 1L);
    }

    default void incrementPatchImpactMergePolicy() {
        addMetric("patchImpactMergePolicy", 1L);
    }

    default void incrementPatchImpactContractsOrProcessing() {
        addMetric("patchImpactContractsOrProcessing", 1L);
    }

    default void incrementPatchImpactProcessorManagedState() {
        addMetric("patchImpactProcessorManagedState", 1L);
    }

    default void incrementProcessorManagedMarkerPatches() {
        addMetric("processorManagedMarkerPatches", 1L);
    }

    default void incrementProcessorManagedMarkerIncrementalResolutions() {
        addMetric("processorManagedMarkerIncrementalResolutions", 1L);
    }

    default void incrementInitializationDocumentIdContentBlueIdCalculations() {
        addMetric("initializationDocumentIdContentBlueIdCalculations", 1L);
    }

    default void incrementInitializationDocumentIdCanonicalMaterializations() {
        addMetric("initializationDocumentIdCanonicalMaterializations", 1L);
    }

    default void incrementInitializationDocumentIdUncheckedCalculations() {
        addMetric("initializationDocumentIdUncheckedCalculations", 1L);
    }

    default void incrementInitializationDocumentIdNodeMaterializations() {
        addMetric("initializationDocumentIdNodeMaterializations", 1L);
    }

    default void incrementInitializationDocumentIdFrozenUncheckedCalculations() {
        addMetric("initializationDocumentIdFrozenUncheckedCalculations", 1L);
    }

    default void incrementProcessorInputStrictCanonical() {
        addMetric("processorInputStrictCanonical", 1L);
    }

    default void incrementProcessorInputUncheckedCanonical() {
        addMetric("processorInputUncheckedCanonical", 1L);
    }

    default void incrementProcessorPublishedStrictCanonical() {
        addMetric("processorPublishedStrictCanonical", 1L);
    }

    default void incrementProcessorPublishedUncheckedCanonical() {
        addMetric("processorPublishedUncheckedCanonical", 1L);
    }

    default void incrementProcessorPublicationCanonicalizations() {
        addMetric("processorPublicationCanonicalizations", 1L);
    }

    default void addProcessorPublicationCanonicalizationNanos(long nanos) {
        addMetric("processorPublicationCanonicalizationNanos", nanos);
    }

    default void incrementProcessorPublicationCanonicalMaterializations() {
        addMetric("processorPublicationCanonicalMaterializations", 1L);
    }

    default void incrementProcessorPublicationStrictBlueIdCalculations() {
        addMetric("processorPublicationStrictBlueIdCalculations", 1L);
    }

    default void incrementProcessorPublicationIdentityMismatches() {
        addMetric("processorPublicationIdentityMismatches", 1L);
    }

    default void incrementProcessorPublicationInvariantChecks() {
        addMetric("processorPublicationInvariantChecks", 1L);
    }

    default void incrementIncrementalMergerCapabilityRequests() {
        addMetric("incrementalMergerCapabilityRequests", 1L);
    }

    default void incrementIncrementalMergerCapabilityAllowed() {
        addMetric("incrementalMergerCapabilityAllowed", 1L);
    }

    default void incrementIncrementalMergerCapabilityDenied() {
        addMetric("incrementalMergerCapabilityDenied", 1L);
    }

    default void incrementIncrementalMergerCapabilityDeniedByConformance() {
        addMetric("incrementalMergerCapabilityDeniedByConformance", 1L);
    }

    default void incrementIncrementalMergerCapabilityDeniedBySnapshotManager() {
        addMetric("incrementalMergerCapabilityDeniedBySnapshotManager", 1L);
    }

    default void incrementPatchImpactRootReplacement() {
        addMetric("patchImpactRootReplacement", 1L);
    }

    default void incrementPatchImpactUnknown() {
        addMetric("patchImpactUnknown", 1L);
    }

    default void incrementIncrementalSnapshotResolutions() {
        addMetric("incrementalSnapshotResolutions", 1L);
    }

    default void incrementFullSnapshotFallback(String reason) {
        addMetric("fullSnapshotFallbacks", 1L);
        addMetric("fullSnapshotFallbackReason." + reason, 1L);
    }

    default void incrementFullCanonicalRootMaterializations() {
        addMetric("fullCanonicalRootMaterializations", 1L);
    }

    default void incrementFullResolvedRootMaterializations() {
        addMetric("fullResolvedRootMaterializations", 1L);
    }

    default void addIncrementalBoundaryPathDepth(long depth) {
        addMetric("incrementalBoundaryPathDepth", depth);
    }

    default void addIncrementalBoundaryNodeCount(long count) {
        addMetric("incrementalBoundaryNodeCount", count);
    }

    default void addIncrementalAncestorsRevalidated(long count) {
        addMetric("incrementalAncestorsRevalidated", count);
    }

    default void addReferencesReResolved(long count) {
        addMetric("referencesReResolved", count);
    }

    default void addReferencesReused(long count) {
        addMetric("referencesReused", count);
    }

    default void incrementConformancePlans() {
        addMetric("conformancePlans", 1L);
    }

    default void addConformanceNodesVisited(long count) {
        addMetric("conformanceNodesVisited", count);
    }

    default void addConformanceTypedBoundariesConsidered(long count) {
        addMetric("conformanceTypedBoundariesConsidered", count);
    }

    default void addConformanceTypedBoundariesValidated(long count) {
        addMetric("conformanceTypedBoundariesValidated", count);
    }

    default void addConformanceTypedBoundariesGeneralized(long count) {
        addMetric("conformanceTypedBoundariesGeneralized", count);
    }

    default void incrementConformanceFullRootScans() {
        addMetric("conformanceFullRootScans", 1L);
    }

    default void addConformanceMutableNodeMaterializations(long count) {
        addMetric("conformanceMutableNodeMaterializations", count);
    }

    default void addConformanceMergerInvocations(long count) {
        addMetric("conformanceMergerInvocations", count);
    }

    default void incrementConformanceTypePlanHits() {
        addMetric("conformanceTypePlanHits", 1L);
    }

    default void incrementConformanceTypePlanMisses() {
        addMetric("conformanceTypePlanMisses", 1L);
    }

    default void incrementConformanceSchemaPlanHits() {
        addMetric("conformanceSchemaPlanHits", 1L);
    }

    default void incrementConformanceSchemaPlanMisses() {
        addMetric("conformanceSchemaPlanMisses", 1L);
    }

    default void incrementCompiledPatternHits() {
        addMetric("compiledPatternHits", 1L);
    }

    default void incrementCompiledPatternMisses() {
        addMetric("compiledPatternMisses", 1L);
    }

    default void incrementCanonicalDigestWrites() {
        addMetric("canonicalDigestWrites", 1L);
    }

    default void addCanonicalDigestBytes(long count) {
        addMetric("canonicalDigestBytes", count);
    }

    default void incrementCanonicalGenericGraphFallbacks() {
        addMetric("canonicalGenericGraphFallbacks", 1L);
    }

    default void incrementCanonicalWholeStringsCreated() {
        addMetric("canonicalWholeStringsCreated", 1L);
    }

    default void incrementCanonicalWholeByteArraysCreated() {
        addMetric("canonicalWholeByteArraysCreated", 1L);
    }

    default void incrementBlueIdCalculations() {
        addMetric("blueIdCalculations", 1L);
    }

    default void incrementBlueIdMemoHits() {
        addMetric("blueIdMemoHits", 1L);
    }

    default void incrementBase58Encodes() {
        addMetric("base58Encodes", 1L);
    }

    default void incrementFrozenPatchValuesAccepted() {
        addMetric("frozenPatchValuesAccepted", 1L);
    }

    default void incrementMutablePatchValuesFrozen() {
        incrementMutablePatchValuesFrozen(PatchSource.LEGACY_PUBLIC_API);
    }

    default void incrementMutablePatchValuesFrozen(PatchSource source) {
        PatchSource fixedSource = source != null ? source : PatchSource.UNKNOWN_INTERNAL;
        addMetric("mutablePatchValuesFrozen", 1L);
        addMetric("mutablePatchValuesFrozenBySource." + fixedSource.name(), 1L);
    }

    default void incrementFrozenPatchValuesMaterialized() {
        addMetric("frozenPatchValuesMaterialized", 1L);
    }

    default void incrementFullFrozenRootToNodeMaterializations() {
        addMetric("fullFrozenRootToNodeMaterializations", 1L);
    }

    default void incrementSubtreeToNodeMaterializations() {
        addMetric("subtreeToNodeMaterializations", 1L);
    }

    default void incrementNodeCloneCalls(String purpose) {
        addMetric("nodeCloneCallsByPurpose." + purpose, 1L);
    }

    default void setCacheCurrentWeightBytes(String cacheName, long value) {
        setMetric("cache." + cacheName + ".currentWeightBytes", value);
    }

    default void recordCacheHighWaterBytes(String cacheName, long value) {
        recordMetricHighWater("cache." + cacheName + ".highWaterBytes", value);
    }

    default void setCacheEntries(String cacheName, long value) {
        setMetric("cache." + cacheName + ".entries", value);
    }

    default void incrementCacheHits(String cacheName) {
        addMetric("cache." + cacheName + ".hits", 1L);
    }

    default void incrementCacheMisses(String cacheName) {
        addMetric("cache." + cacheName + ".misses", 1L);
    }

    default void incrementCacheEvictions(String cacheName) {
        addMetric("cache." + cacheName + ".evictions", 1L);
    }

    default void incrementCacheOversizedRejections(String cacheName) {
        addMetric("cache." + cacheName + ".oversizedRejections", 1L);
    }

    default void setCachePinnedEntries(String cacheName, long value) {
        setMetric("cache." + cacheName + ".pinnedEntries", value);
    }

    default void setCacheDerivedEntries(String cacheName, long value) {
        setMetric("cache." + cacheName + ".derivedEntries", value);
    }

    default void incrementRuntimeCloseCalls() {
        addMetric("runtimeCloseCalls", 1L);
    }

    default void addRuntimeCloseReleasedWeightBytes(long count) {
        addMetric("runtimeCloseReleasedWeightBytes", count);
    }

    default void addSequenceCacheEntriesReleased(long count) {
        addMetric("sequenceCacheEntriesReleased", count);
    }

    default void incrementReferenceReachabilityDeltaUpdates() {
        addMetric("referenceReachabilityDeltaUpdates", 1L);
    }

    default void incrementReferenceReachabilityFullScans() {
        addMetric("referenceReachabilityFullScans", 1L);
    }
}
