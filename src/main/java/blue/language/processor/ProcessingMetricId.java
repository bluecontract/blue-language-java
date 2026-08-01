package blue.language.processor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Closed manifest of operational metrics emitted by the processing kernel.
 *
 * <p>The external names retain the historical spelling used by diagnostics.
 * Contextual metrics carry their variable category in a bounded typed context
 * instead of manufacturing an unbounded metric identifier.</p>
 */
public enum ProcessingMetricId {

    BASE58_DECODE_NANOS("base58DecodeNanos", ObservationKind.COUNTER_DELTA),
    BASE58_ENCODE_NANOS("base58EncodeNanos", ObservationKind.COUNTER_DELTA),
    BASE58_ENCODES("base58Encodes", ObservationKind.COUNTER_DELTA),
    BATCH_PATCH_BUILD_UPDATES_NANOS("batchPatchBuildUpdatesNanos", ObservationKind.COUNTER_DELTA),
    BATCH_PATCH_COMMIT_NANOS("batchPatchCommitNanos", ObservationKind.COUNTER_DELTA),
    BATCH_PATCH_CONFORMANCE_NANOS("batchPatchConformanceNanos", ObservationKind.COUNTER_DELTA),
    BATCH_PATCH_PLANNING_NANOS("batchPatchPlanningNanos", ObservationKind.COUNTER_DELTA),
    BLUE_ID_CALCULATION_NANOS("blueIdCalculationNanos", ObservationKind.COUNTER_DELTA),
    BLUE_ID_CALCULATIONS("blueIdCalculations", ObservationKind.COUNTER_DELTA),
    BLUE_ID_DIGEST_NANOS("blueIdDigestNanos", ObservationKind.COUNTER_DELTA),
    BLUE_ID_MEMO_HITS("blueIdMemoHits", ObservationKind.COUNTER_DELTA),
    BLUE_PROCESS_DOCUMENT_NANOS("blueProcessDocumentNanos", ObservationKind.COUNTER_DELTA),
    BUNDLE_LOAD_ACTUAL_BUILD_NANOS("bundleLoadActualBuildNanos", ObservationKind.COUNTER_DELTA),
    BUNDLE_LOAD_CACHE_HITS("bundleLoadCacheHits", ObservationKind.COUNTER_DELTA),
    BUNDLE_LOAD_CACHE_KEY_BUILD_NANOS("bundleLoadCacheKeyBuildNanos", ObservationKind.COUNTER_DELTA),
    BUNDLE_LOAD_CACHE_MISSES("bundleLoadCacheMisses", ObservationKind.COUNTER_DELTA),
    BUNDLE_LOAD_NANOS("bundleLoadNanos", ObservationKind.COUNTER_DELTA),
    BUNDLE_LOAD_REUSE_NANOS("bundleLoadReuseNanos", ObservationKind.COUNTER_DELTA),
    BUNDLE_SCOPE_CONTRACT_LOAD_NANOS("bundleScopeContractLoadNanos", ObservationKind.COUNTER_DELTA),
    BUNDLE_SCOPE_EXECUTION_CACHE_HITS("bundleScopeExecutionCacheHits", ObservationKind.COUNTER_DELTA),
    BUNDLE_SCOPE_LOAD_ATTEMPTS("bundleScopeLoadAttempts", ObservationKind.COUNTER_DELTA),
    BUNDLE_SCOPE_REFRESHES("bundleScopeRefreshes", ObservationKind.COUNTER_DELTA),
    BUNDLE_SCOPE_RESOLVED_LOOKUP_NANOS("bundleScopeResolvedLookupNanos", ObservationKind.COUNTER_DELTA),
    BUNDLE_SCOPE_TERMINATION_CHECK_NANOS("bundleScopeTerminationCheckNanos", ObservationKind.COUNTER_DELTA),
    BUNDLES_BUILT("bundlesBuilt", ObservationKind.COUNTER_DELTA),
    BUNDLES_REUSED("bundlesReused", ObservationKind.COUNTER_DELTA),
    CACHE_CURRENT_WEIGHT_BYTES("cacheCurrentWeightBytes", ObservationKind.GAUGE_VALUE,
            ProcessingObservationDimension.CACHE_NAME),
    CACHE_DERIVED_ENTRIES("cacheDerivedEntries", ObservationKind.GAUGE_VALUE,
            ProcessingObservationDimension.CACHE_NAME),
    CACHE_ENTRIES("cacheEntries", ObservationKind.GAUGE_VALUE,
            ProcessingObservationDimension.CACHE_NAME),
    CACHE_EVICTIONS("cacheEvictions", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.CACHE_NAME),
    CACHE_HIGH_WATER_BYTES("cacheHighWaterBytes", ObservationKind.HIGH_WATER_MARK,
            ProcessingObservationDimension.CACHE_NAME),
    CACHE_HITS("cacheHits", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.CACHE_NAME),
    CACHE_MISSES("cacheMisses", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.CACHE_NAME),
    CACHE_OVERSIZED_REJECTIONS("cacheOversizedRejections", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.CACHE_NAME),
    CACHE_PINNED_ENTRIES("cachePinnedEntries", ObservationKind.GAUGE_VALUE,
            ProcessingObservationDimension.CACHE_NAME),
    CANONICAL_BYTES_WRITTEN("canonicalBytesWritten", ObservationKind.COUNTER_DELTA),
    CANONICAL_DIGEST_BYTES("canonicalDigestBytes", ObservationKind.COUNTER_DELTA),
    CANONICAL_DIGEST_WRITES("canonicalDigestWrites", ObservationKind.COUNTER_DELTA),
    CANONICAL_GENERIC_GRAPH_FALLBACKS("canonicalGenericGraphFallbacks", ObservationKind.COUNTER_DELTA),
    CANONICAL_IDENTITY_CALCULATIONS("canonicalIdentityCalculations", ObservationKind.COUNTER_DELTA),
    CANONICAL_WHOLE_BYTE_ARRAYS_CREATED("canonicalWholeByteArraysCreated", ObservationKind.COUNTER_DELTA),
    CANONICAL_WHOLE_STRINGS_CREATED("canonicalWholeStringsCreated", ObservationKind.COUNTER_DELTA),
    CHANNEL_DISCOVERY_NANOS("channelDiscoveryNanos", ObservationKind.COUNTER_DELTA),
    CHANNEL_EVALUATIONS("channelEvaluations", ObservationKind.COUNTER_DELTA),
    CHANNEL_MATCH_NANOS("channelMatchNanos", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_CONTENT_BLUE_ID_NANOS("checkpointContentBlueIdNanos", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_CURRENT_IDENTITY_NANOS("checkpointCurrentIdentityNanos", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_DIRECT_BLUE_ID_NANOS("checkpointDirectBlueIdNanos", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_DUPLICATE_NANOS("checkpointDuplicateNanos", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_ENSURE_NANOS("checkpointEnsureNanos", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_FALLBACK_NANOS("checkpointFallbackNanos", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_FIND_NANOS("checkpointFindNanos", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_IDENTITY_CACHE_HITS("checkpointIdentityCacheHits", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_IDENTITY_CACHE_MISSES("checkpointIdentityCacheMisses", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_IS_NEWER_NANOS("checkpointIsNewerNanos", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_PERSIST_NANOS("checkpointPersistNanos", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_STORED_IDENTITY_CACHE_HITS("checkpointStoredIdentityCacheHits", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_STORED_IDENTITY_CACHE_MISSES("checkpointStoredIdentityCacheMisses", ObservationKind.COUNTER_DELTA),
    CHECKPOINT_UPDATE_NANOS("checkpointUpdateNanos", ObservationKind.COUNTER_DELTA),
    COMPILED_PATTERN_HITS("compiledPatternHits", ObservationKind.COUNTER_DELTA),
    COMPILED_PATTERN_MISSES("compiledPatternMisses", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_FULL_ROOT_SCANS("conformanceFullRootScans", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_MERGER_INVOCATIONS("conformanceMergerInvocations", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_MUTABLE_NODE_MATERIALIZATIONS("conformanceMutableNodeMaterializations", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_NODES_VISITED("conformanceNodesVisited", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_PLANS("conformancePlans", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_SCHEMA_PLAN_HITS("conformanceSchemaPlanHits", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_SCHEMA_PLAN_MISSES("conformanceSchemaPlanMisses", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_TYPE_PLAN_HITS("conformanceTypePlanHits", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_TYPE_PLAN_MISSES("conformanceTypePlanMisses", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_TYPED_BOUNDARIES_CONSIDERED("conformanceTypedBoundariesConsidered", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_TYPED_BOUNDARIES_GENERALIZED("conformanceTypedBoundariesGeneralized", ObservationKind.COUNTER_DELTA),
    CONFORMANCE_TYPED_BOUNDARIES_VALIDATED("conformanceTypedBoundariesValidated", ObservationKind.COUNTER_DELTA),
    DEDUPLICATED_CHANNEL_DELIVERIES("deduplicatedChannelDeliveries", ObservationKind.COUNTER_DELTA),
    DOCUMENT_UPDATE_AFTER_MATERIALIZATIONS("documentUpdateAfterMaterializations", ObservationKind.COUNTER_DELTA),
    DOCUMENT_UPDATE_BEFORE_MATERIALIZATIONS("documentUpdateBeforeMaterializations", ObservationKind.COUNTER_DELTA),
    DOCUMENT_UPDATE_EVENTS_BUILT("documentUpdateEventsBuilt", ObservationKind.COUNTER_DELTA),
    DOCUMENT_UPDATE_EVENTS_SKIPPED_NO_CHANNEL("documentUpdateEventsSkippedNoChannel", ObservationKind.COUNTER_DELTA),
    DOCUMENT_UPDATE_ROUTING_NANOS("documentUpdateRoutingNanos", ObservationKind.COUNTER_DELTA),
    EVENT_PREPROCESS_NANOS("eventPreprocessNanos", ObservationKind.COUNTER_DELTA),
    FROZEN_NODES_CREATED("frozenNodesCreated", ObservationKind.COUNTER_DELTA),
    FROZEN_NODES_REUSED("frozenNodesReused", ObservationKind.COUNTER_DELTA),
    FROZEN_PATCH_VALUE_HITS("frozenPatchValueHits", ObservationKind.COUNTER_DELTA),
    FROZEN_PATCH_VALUES_ACCEPTED("frozenPatchValuesAccepted", ObservationKind.COUNTER_DELTA),
    FROZEN_PATCH_VALUES_MATERIALIZED("frozenPatchValuesMaterialized", ObservationKind.COUNTER_DELTA),
    FULL_CANONICAL_ROOT_MATERIALIZATIONS("fullCanonicalRootMaterializations", ObservationKind.COUNTER_DELTA),
    FULL_FROZEN_ROOT_TO_NODE_MATERIALIZATIONS("fullFrozenRootToNodeMaterializations", ObservationKind.COUNTER_DELTA),
    FULL_RESOLVED_ROOT_MATERIALIZATIONS("fullResolvedRootMaterializations", ObservationKind.COUNTER_DELTA),
    FULL_SNAPSHOT_FALLBACK_REASON("fullSnapshotFallbackReason", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.FALLBACK_REASON),
    FULL_SNAPSHOT_FALLBACKS("fullSnapshotFallbacks", ObservationKind.COUNTER_DELTA),
    HANDLER_DISCOVERY_NANOS("handlerDiscoveryNanos", ObservationKind.COUNTER_DELTA),
    HANDLER_EXECUTION_NANOS("handlerExecutionNanos", ObservationKind.COUNTER_DELTA),
    HANDLER_MATCH_ATTEMPTS("handlerMatchAttempts", ObservationKind.COUNTER_DELTA),
    HANDLER_MATCH_NANOS("handlerMatchNanos", ObservationKind.COUNTER_DELTA),
    HANDLERS_EXECUTED("handlersExecuted", ObservationKind.COUNTER_DELTA),
    INCREMENTAL_ANCESTORS_REVALIDATED("incrementalAncestorsRevalidated", ObservationKind.COUNTER_DELTA),
    INCREMENTAL_BOUNDARY_NODE_COUNT("incrementalBoundaryNodeCount", ObservationKind.COUNTER_DELTA),
    INCREMENTAL_BOUNDARY_PATH_DEPTH("incrementalBoundaryPathDepth", ObservationKind.COUNTER_DELTA),
    INCREMENTAL_MERGER_CAPABILITY_ALLOWED("incrementalMergerCapabilityAllowed", ObservationKind.COUNTER_DELTA),
    INCREMENTAL_MERGER_CAPABILITY_DENIED("incrementalMergerCapabilityDenied", ObservationKind.COUNTER_DELTA),
    INCREMENTAL_MERGER_CAPABILITY_DENIED_BY_CONFORMANCE("incrementalMergerCapabilityDeniedByConformance", ObservationKind.COUNTER_DELTA),
    INCREMENTAL_MERGER_CAPABILITY_DENIED_BY_SNAPSHOT_MANAGER("incrementalMergerCapabilityDeniedBySnapshotManager", ObservationKind.COUNTER_DELTA),
    INCREMENTAL_MERGER_CAPABILITY_REQUESTS("incrementalMergerCapabilityRequests", ObservationKind.COUNTER_DELTA),
    INCREMENTAL_SNAPSHOT_RESOLUTIONS("incrementalSnapshotResolutions", ObservationKind.COUNTER_DELTA),
    INITIALIZATION_DOCUMENT_ID_CANONICAL_MATERIALIZATIONS("initializationDocumentIdCanonicalMaterializations", ObservationKind.COUNTER_DELTA),
    INITIALIZATION_DOCUMENT_ID_CONTENT_BLUE_ID_CALCULATIONS("initializationDocumentIdContentBlueIdCalculations", ObservationKind.COUNTER_DELTA),
    INITIALIZATION_DOCUMENT_ID_FROZEN_UNCHECKED_CALCULATIONS("initializationDocumentIdFrozenUncheckedCalculations", ObservationKind.COUNTER_DELTA),
    INITIALIZATION_DOCUMENT_ID_NODE_MATERIALIZATIONS("initializationDocumentIdNodeMaterializations", ObservationKind.COUNTER_DELTA),
    INITIALIZATION_DOCUMENT_ID_UNCHECKED_CALCULATIONS("initializationDocumentIdUncheckedCalculations", ObservationKind.COUNTER_DELTA),
    JCS_FALLBACKS("jcsFallbacks", ObservationKind.COUNTER_DELTA),
    MUTABLE_PATCH_VALUES_FROZEN("mutablePatchValuesFrozen", ObservationKind.COUNTER_DELTA),
    MUTABLE_PATCH_VALUES_FROZEN_BY_SOURCE("mutablePatchValuesFrozenBySource", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.PATCH_SOURCE),
    NODE_CLONE_CALLS_BY_PURPOSE("nodeCloneCallsByPurpose", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.CLONE_PURPOSE),
    PARSED_POINTER_CACHE_HITS("parsedPointerCacheHits", ObservationKind.COUNTER_DELTA),
    PARSED_POINTER_CACHE_MISSES("parsedPointerCacheMisses", ObservationKind.COUNTER_DELTA),
    PATCH_BOUNDARY_NANOS("patchBoundaryNanos", ObservationKind.COUNTER_DELTA),
    PATCH_GAS_NANOS("patchGasNanos", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_ANALYSES("patchImpactAnalyses", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_COLLECTION_SHAPE("patchImpactCollectionShape", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_CONTRACTS_OR_PROCESSING("patchImpactContractsOrProcessing", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_MERGE_POLICY("patchImpactMergePolicy", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_OBJECT_MEMBER_VALUE("patchImpactObjectMemberValue", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_PROCESSOR_MANAGED_STATE("patchImpactProcessorManagedState", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_REFERENCE("patchImpactReference", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_ROOT_REPLACEMENT("patchImpactRootReplacement", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_SCHEMA_METADATA("patchImpactSchemaMetadata", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_TYPE_METADATA("patchImpactTypeMetadata", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_UNKNOWN("patchImpactUnknown", ObservationKind.COUNTER_DELTA),
    PATCH_IMPACT_VALUE_ONLY("patchImpactValueOnly", ObservationKind.COUNTER_DELTA),
    PATCH_SEQUENCES_PREPARED("patchSequencesPrepared", ObservationKind.COUNTER_DELTA),
    PATCH_VALUE_MATERIALIZATIONS("patchValueMaterializations", ObservationKind.COUNTER_DELTA),
    PATCHES_PREPARED("patchesPrepared", ObservationKind.COUNTER_DELTA),
    POST_PROCESSING_NANOS("postProcessingNanos", ObservationKind.COUNTER_DELTA),
    PROCESS_DOCUMENT_NANOS("processDocumentNanos", ObservationKind.COUNTER_DELTA),
    PROCESS_EVENT_SNAPSHOT_ATTEMPTS("processEventSnapshotAttempts", ObservationKind.COUNTER_DELTA),
    PROCESS_EVENT_SNAPSHOT_BUILDS("processEventSnapshotBuilds", ObservationKind.COUNTER_DELTA),
    PROCESS_EVENT_SNAPSHOT_CONSTRUCTION_NANOS("processEventSnapshotConstructionNanos", ObservationKind.COUNTER_DELTA),
    PROCESS_EVENT_SNAPSHOT_FAILURES("processEventSnapshotFailures", ObservationKind.COUNTER_DELTA),
    PROCESSING_SNAPSHOT_CACHE_HITS("processingSnapshotCacheHits", ObservationKind.COUNTER_DELTA),
    PROCESSING_SNAPSHOT_CACHE_LOOKUP_NANOS("processingSnapshotCacheLookupNanos", ObservationKind.COUNTER_DELTA),
    PROCESSING_SNAPSHOT_CACHE_MISSES("processingSnapshotCacheMisses", ObservationKind.COUNTER_DELTA),
    PROCESSING_SNAPSHOT_FROM_DOCUMENT_BUILDS("processingSnapshotFromDocumentBuilds", ObservationKind.COUNTER_DELTA),
    PROCESSING_SNAPSHOT_FROM_DOCUMENT_NANOS("processingSnapshotFromDocumentNanos", ObservationKind.COUNTER_DELTA),
    PROCESSOR_INPUT_STRICT_CANONICAL("processorInputStrictCanonical", ObservationKind.COUNTER_DELTA),
    PROCESSOR_INPUT_UNCHECKED_CANONICAL("processorInputUncheckedCanonical", ObservationKind.COUNTER_DELTA),
    PROCESSOR_MANAGED_MARKER_INCREMENTAL_RESOLUTIONS("processorManagedMarkerIncrementalResolutions", ObservationKind.COUNTER_DELTA),
    PROCESSOR_MANAGED_MARKER_PATCHES("processorManagedMarkerPatches", ObservationKind.COUNTER_DELTA),
    PROCESSOR_PUBLICATION_CANONICAL_MATERIALIZATIONS("processorPublicationCanonicalMaterializations", ObservationKind.COUNTER_DELTA),
    PROCESSOR_PUBLICATION_CANONICALIZATION_NANOS("processorPublicationCanonicalizationNanos", ObservationKind.COUNTER_DELTA),
    PROCESSOR_PUBLICATION_CANONICALIZATIONS("processorPublicationCanonicalizations", ObservationKind.COUNTER_DELTA),
    PROCESSOR_PUBLICATION_IDENTITY_MISMATCHES("processorPublicationIdentityMismatches", ObservationKind.COUNTER_DELTA),
    PROCESSOR_PUBLICATION_INVARIANT_CHECKS("processorPublicationInvariantChecks", ObservationKind.COUNTER_DELTA),
    PROCESSOR_PUBLICATION_STRICT_BLUE_ID_CALCULATIONS("processorPublicationStrictBlueIdCalculations", ObservationKind.COUNTER_DELTA),
    PROCESSOR_PUBLISHED_STRICT_CANONICAL("processorPublishedStrictCanonical", ObservationKind.COUNTER_DELTA),
    PROCESSOR_PUBLISHED_UNCHECKED_CANONICAL("processorPublishedUncheckedCanonical", ObservationKind.COUNTER_DELTA),
    REFERENCE_REACHABILITY_DELTA_UPDATES("referenceReachabilityDeltaUpdates", ObservationKind.COUNTER_DELTA),
    REFERENCE_REACHABILITY_FULL_SCANS("referenceReachabilityFullScans", ObservationKind.COUNTER_DELTA),
    REFERENCES_RE_RESOLVED("referencesReResolved", ObservationKind.COUNTER_DELTA),
    REFERENCES_REUSED("referencesReused", ObservationKind.COUNTER_DELTA),
    RESOLVED_IDENTITY_CALCULATIONS("resolvedIdentityCalculations", ObservationKind.COUNTER_DELTA),
    RESOLVED_STRUCTURAL_KEY_BUILDS("resolvedStructuralKeyBuilds", ObservationKind.COUNTER_DELTA),
    RESULT_SNAPSHOT_ATTACH_NANOS("resultSnapshotAttachNanos", ObservationKind.COUNTER_DELTA),
    ROUTED_CHANNEL_DELIVERIES("routedChannelDeliveries", ObservationKind.COUNTER_DELTA),
    RUNTIME_CLOSE_CALLS("runtimeCloseCalls", ObservationKind.COUNTER_DELTA),
    RUNTIME_CLOSE_RELEASED_WEIGHT_BYTES("runtimeCloseReleasedWeightBytes", ObservationKind.COUNTER_DELTA),
    SEQUENCE_CACHE_ENTRIES_RELEASED("sequenceCacheEntriesReleased", ObservationKind.COUNTER_DELTA),
    SEQUENCE_COMMIT_NANOS("sequenceCommitNanos", ObservationKind.COUNTER_DELTA),
    SEQUENCE_CONFORMANCE_NANOS("sequenceConformanceNanos", ObservationKind.COUNTER_DELTA),
    SEQUENCE_FALLBACK_PATCHES("sequenceFallbackPatches", ObservationKind.COUNTER_DELTA),
    SEQUENCE_FINAL_CACHE_COMMIT_NANOS("sequenceFinalCacheCommitNanos", ObservationKind.COUNTER_DELTA),
    SEQUENCE_FINAL_SNAPSHOT_CACHE_INSERTS("sequenceFinalSnapshotCacheInserts", ObservationKind.COUNTER_DELTA),
    SEQUENCE_INTERMEDIATE_SNAPSHOT_ADVANCES("sequenceIntermediateSnapshotAdvances", ObservationKind.COUNTER_DELTA),
    SEQUENCE_PLANNING_NANOS("sequencePlanningNanos", ObservationKind.COUNTER_DELTA),
    SEQUENCE_SHARED_SNAPSHOT_CACHE_INSERTS("sequenceSharedSnapshotCacheInserts", ObservationKind.COUNTER_DELTA),
    SEQUENCE_STALE_PREVIEW_FALLBACKS("sequenceStalePreviewFallbacks", ObservationKind.COUNTER_DELTA),
    SEQUENCE_SUFFIX_REBASES("sequenceSuffixRebases", ObservationKind.COUNTER_DELTA),
    SINGLETON_PATCH_TRANSACTIONS("singletonPatchTransactions", ObservationKind.COUNTER_DELTA),
    SNAPSHOT_COMMIT_NANOS("snapshotCommitNanos", ObservationKind.COUNTER_DELTA),
    SUBTREE_TO_NODE_MATERIALIZATIONS("subtreeToNodeMaterializations", ObservationKind.COUNTER_DELTA),
    TRIGGERED_EVENT_ROUTING_NANOS("triggeredEventRoutingNanos", ObservationKind.COUNTER_DELTA),
    TRIGGERED_EVENTS_ROUTED("triggeredEventsRouted", ObservationKind.COUNTER_DELTA);

    private static final Map<String, ProcessingMetricId> EXACT_LEGACY_NAMES = exactNames();

    private final String externalName;
    private final ObservationKind kind;
    private final ProcessingObservationDimension requiredDimension;

    ProcessingMetricId(String externalName, ObservationKind kind) {
        this(externalName, kind, null);
    }

    ProcessingMetricId(
            String externalName,
            ObservationKind kind,
            ProcessingObservationDimension requiredDimension) {
        this.externalName = externalName;
        this.kind = kind;
        this.requiredDimension = requiredDimension;
    }

    /** @return stable manifest name */
    public String externalName() {
        return externalName;
    }

    /** @return the only valid aggregation kind for this metric */
    public ObservationKind kind() {
        return kind;
    }

    /**
     * Returns the required context dimension, if any.
     *
     * @return required dimension or {@code null} for a context-free metric
     */
    public ProcessingObservationDimension requiredDimension() {
        return requiredDimension;
    }

    String legacyName(ProcessingObservationContext context) {
        if (requiredDimension == null) {
            return externalName;
        }
        String dimension = context.value(requiredDimension);
        if (dimension == null) {
            throw new IllegalArgumentException(
                    name() + " requires dimension " + requiredDimension);
        }
        switch (this) {
            case FULL_SNAPSHOT_FALLBACK_REASON:
                return "fullSnapshotFallbackReason." + dimension;
            case MUTABLE_PATCH_VALUES_FROZEN_BY_SOURCE:
                return "mutablePatchValuesFrozenBySource." + dimension;
            case NODE_CLONE_CALLS_BY_PURPOSE:
                return "nodeCloneCallsByPurpose." + dimension;
            case CACHE_CURRENT_WEIGHT_BYTES:
                return cacheName(dimension, "currentWeightBytes");
            case CACHE_HIGH_WATER_BYTES:
                return cacheName(dimension, "highWaterBytes");
            case CACHE_ENTRIES:
                return cacheName(dimension, "entries");
            case CACHE_HITS:
                return cacheName(dimension, "hits");
            case CACHE_MISSES:
                return cacheName(dimension, "misses");
            case CACHE_EVICTIONS:
                return cacheName(dimension, "evictions");
            case CACHE_OVERSIZED_REJECTIONS:
                return cacheName(dimension, "oversizedRejections");
            case CACHE_PINNED_ENTRIES:
                return cacheName(dimension, "pinnedEntries");
            case CACHE_DERIVED_ENTRIES:
                return cacheName(dimension, "derivedEntries");
            default:
                throw new IllegalStateException("unsupported contextual metric " + name());
        }
    }

    static LegacyMetric fromLegacyName(String legacyName) {
        ProcessingMetricId exact = EXACT_LEGACY_NAMES.get(legacyName);
        if (exact != null) {
            return new LegacyMetric(exact, ProcessingObservationContext.empty());
        }
        LegacyMetric prefixed = prefixed(
                legacyName,
                "fullSnapshotFallbackReason.",
                FULL_SNAPSHOT_FALLBACK_REASON,
                ProcessingObservationDimension.FALLBACK_REASON);
        if (prefixed != null) {
            return prefixed;
        }
        prefixed = prefixed(
                legacyName,
                "mutablePatchValuesFrozenBySource.",
                MUTABLE_PATCH_VALUES_FROZEN_BY_SOURCE,
                ProcessingObservationDimension.PATCH_SOURCE);
        if (prefixed != null) {
            return prefixed;
        }
        prefixed = prefixed(
                legacyName,
                "nodeCloneCallsByPurpose.",
                NODE_CLONE_CALLS_BY_PURPOSE,
                ProcessingObservationDimension.CLONE_PURPOSE);
        if (prefixed != null) {
            return prefixed;
        }
        return cacheMetric(legacyName);
    }

    private static LegacyMetric cacheMetric(String legacyName) {
        if (!hasPrefix(legacyName, "cache.")) {
            return null;
        }
        ProcessingMetricId[] ids = {
                CACHE_CURRENT_WEIGHT_BYTES,
                CACHE_HIGH_WATER_BYTES,
                CACHE_ENTRIES,
                CACHE_HITS,
                CACHE_MISSES,
                CACHE_EVICTIONS,
                CACHE_OVERSIZED_REJECTIONS,
                CACHE_PINNED_ENTRIES,
                CACHE_DERIVED_ENTRIES
        };
        String[] suffixes = {
                "currentWeightBytes",
                "highWaterBytes",
                "entries",
                "hits",
                "misses",
                "evictions",
                "oversizedRejections",
                "pinnedEntries",
                "derivedEntries"
        };
        for (int index = 0; index < suffixes.length; index++) {
            String suffix = "." + suffixes[index];
            if (legacyName.endsWith(suffix)) {
                String cache = legacyName.substring("cache.".length(),
                        legacyName.length() - suffix.length());
                return contextual(ids[index], ProcessingObservationDimension.CACHE_NAME, cache);
            }
        }
        return null;
    }

    private static LegacyMetric prefixed(
            String legacyName,
            String prefix,
            ProcessingMetricId id,
            ProcessingObservationDimension dimension) {
        if (!hasPrefix(legacyName, prefix)) {
            return null;
        }
        return contextual(id, dimension, legacyName.substring(prefix.length()));
    }

    private static LegacyMetric contextual(
            ProcessingMetricId id,
            ProcessingObservationDimension dimension,
            String value) {
        try {
            return new LegacyMetric(id, ProcessingObservationContext.of(dimension, value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Tests a telemetry-name prefix without invoking JSON-pointer operations. */
    private static boolean hasPrefix(String value, String prefix) {
        return value != null
                && value.regionMatches(0, prefix, 0, prefix.length());
    }

    private static String cacheName(String cache, String suffix) {
        return "cache." + cache + "." + suffix;
    }

    private static Map<String, ProcessingMetricId> exactNames() {
        Map<String, ProcessingMetricId> result = new HashMap<>();
        for (ProcessingMetricId id : values()) {
            if (id.requiredDimension == null) {
                result.put(id.externalName, id);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static final class LegacyMetric {

        private final ProcessingMetricId id;
        private final ProcessingObservationContext context;

        private LegacyMetric(ProcessingMetricId id, ProcessingObservationContext context) {
            this.id = id;
            this.context = context;
        }

        ProcessingMetricId id() {
            return id;
        }

        ProcessingObservationContext context() {
            return context;
        }
    }
}
