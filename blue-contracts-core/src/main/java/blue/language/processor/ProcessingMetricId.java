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

    /** Measures cumulative nanoseconds spent decoding Base58 values. */
    BASE58_DECODE_NANOS("base58DecodeNanos", ObservationKind.COUNTER_DELTA),
    /** Measures cumulative nanoseconds spent encoding Base58 values. */
    BASE58_ENCODE_NANOS("base58EncodeNanos", ObservationKind.COUNTER_DELTA),
    /** Counts Base58 encoding operations. */
    BASE58_ENCODES("base58Encodes", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent building updates for batch patches. */
    BATCH_PATCH_BUILD_UPDATES_NANOS("batchPatchBuildUpdatesNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent committing batch patches. */
    BATCH_PATCH_COMMIT_NANOS("batchPatchCommitNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent checking batch-patch conformance. */
    BATCH_PATCH_CONFORMANCE_NANOS("batchPatchConformanceNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent planning batch patches. */
    BATCH_PATCH_PLANNING_NANOS("batchPatchPlanningNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent calculating Blue IDs. */
    BLUE_ID_CALCULATION_NANOS("blueIdCalculationNanos", ObservationKind.COUNTER_DELTA),
    /** Counts Blue ID calculation operations. */
    BLUE_ID_CALCULATIONS("blueIdCalculations", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent digesting Blue ID inputs. */
    BLUE_ID_DIGEST_NANOS("blueIdDigestNanos", ObservationKind.COUNTER_DELTA),
    /** Counts Blue ID memoization hits. */
    BLUE_ID_MEMO_HITS("blueIdMemoHits", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent in the Blue process-document boundary. */
    BLUE_PROCESS_DOCUMENT_NANOS("blueProcessDocumentNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent constructing a bundle after cache lookup. */
    BUNDLE_LOAD_ACTUAL_BUILD_NANOS("bundleLoadActualBuildNanos", ObservationKind.COUNTER_DELTA),
    /** Counts bundle-load cache hits. */
    BUNDLE_LOAD_CACHE_HITS("bundleLoadCacheHits", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent building bundle-load cache keys. */
    BUNDLE_LOAD_CACHE_KEY_BUILD_NANOS("bundleLoadCacheKeyBuildNanos", ObservationKind.COUNTER_DELTA),
    /** Counts bundle-load cache misses. */
    BUNDLE_LOAD_CACHE_MISSES("bundleLoadCacheMisses", ObservationKind.COUNTER_DELTA),
    /** Measures total nanoseconds spent loading bundles. */
    BUNDLE_LOAD_NANOS("bundleLoadNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent reusing previously loaded bundles. */
    BUNDLE_LOAD_REUSE_NANOS("bundleLoadReuseNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent loading contracts for bundle scopes. */
    BUNDLE_SCOPE_CONTRACT_LOAD_NANOS("bundleScopeContractLoadNanos", ObservationKind.COUNTER_DELTA),
    /** Counts execution-cache hits while loading bundle scopes. */
    BUNDLE_SCOPE_EXECUTION_CACHE_HITS("bundleScopeExecutionCacheHits", ObservationKind.COUNTER_DELTA),
    /** Counts bundle-scope load attempts. */
    BUNDLE_SCOPE_LOAD_ATTEMPTS("bundleScopeLoadAttempts", ObservationKind.COUNTER_DELTA),
    /** Counts refreshes of loaded bundle scopes. */
    BUNDLE_SCOPE_REFRESHES("bundleScopeRefreshes", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent looking up resolved bundle scopes. */
    BUNDLE_SCOPE_RESOLVED_LOOKUP_NANOS("bundleScopeResolvedLookupNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent checking bundle-scope termination. */
    BUNDLE_SCOPE_TERMINATION_CHECK_NANOS("bundleScopeTerminationCheckNanos", ObservationKind.COUNTER_DELTA),
    /** Counts newly built contract bundles. */
    BUNDLES_BUILT("bundlesBuilt", ObservationKind.COUNTER_DELTA),
    /** Counts reused contract bundles. */
    BUNDLES_REUSED("bundlesReused", ObservationKind.COUNTER_DELTA),
    /** Reports the current cache weight in bytes for the selected cache. */
    CACHE_CURRENT_WEIGHT_BYTES("cacheCurrentWeightBytes", ObservationKind.GAUGE_VALUE,
            ProcessingObservationDimension.CACHE_NAME),
    /** Reports the number of derived entries in the selected cache. */
    CACHE_DERIVED_ENTRIES("cacheDerivedEntries", ObservationKind.GAUGE_VALUE,
            ProcessingObservationDimension.CACHE_NAME),
    /** Reports the current entry count for the selected cache. */
    CACHE_ENTRIES("cacheEntries", ObservationKind.GAUGE_VALUE,
            ProcessingObservationDimension.CACHE_NAME),
    /** Counts evictions from the selected cache. */
    CACHE_EVICTIONS("cacheEvictions", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.CACHE_NAME),
    /** Reports the greatest observed cache weight in bytes. */
    CACHE_HIGH_WATER_BYTES("cacheHighWaterBytes", ObservationKind.HIGH_WATER_MARK,
            ProcessingObservationDimension.CACHE_NAME),
    /** Counts hits in the selected cache. */
    CACHE_HITS("cacheHits", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.CACHE_NAME),
    /** Counts misses in the selected cache. */
    CACHE_MISSES("cacheMisses", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.CACHE_NAME),
    /** Counts oversized entries rejected by the selected cache. */
    CACHE_OVERSIZED_REJECTIONS("cacheOversizedRejections", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.CACHE_NAME),
    /** Reports the number of pinned entries in the selected cache. */
    CACHE_PINNED_ENTRIES("cachePinnedEntries", ObservationKind.GAUGE_VALUE,
            ProcessingObservationDimension.CACHE_NAME),
    /** Counts bytes emitted by canonical serialization. */
    CANONICAL_BYTES_WRITTEN("canonicalBytesWritten", ObservationKind.COUNTER_DELTA),
    /** Counts canonical bytes supplied to digest operations. */
    CANONICAL_DIGEST_BYTES("canonicalDigestBytes", ObservationKind.COUNTER_DELTA),
    /** Counts writes performed while producing canonical digests. */
    CANONICAL_DIGEST_WRITES("canonicalDigestWrites", ObservationKind.COUNTER_DELTA),
    /** Counts canonicalization fallbacks to the generic graph path. */
    CANONICAL_GENERIC_GRAPH_FALLBACKS("canonicalGenericGraphFallbacks", ObservationKind.COUNTER_DELTA),
    /** Counts canonical identity calculations. */
    CANONICAL_IDENTITY_CALCULATIONS("canonicalIdentityCalculations", ObservationKind.COUNTER_DELTA),
    /** Counts whole byte arrays allocated during canonicalization. */
    CANONICAL_WHOLE_BYTE_ARRAYS_CREATED("canonicalWholeByteArraysCreated", ObservationKind.COUNTER_DELTA),
    /** Counts whole strings allocated during canonicalization. */
    CANONICAL_WHOLE_STRINGS_CREATED("canonicalWholeStringsCreated", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent discovering channels. */
    CHANNEL_DISCOVERY_NANOS("channelDiscoveryNanos", ObservationKind.COUNTER_DELTA),
    /** Counts channel evaluations. */
    CHANNEL_EVALUATIONS("channelEvaluations", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent matching channels. */
    CHANNEL_MATCH_NANOS("channelMatchNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent calculating checkpoint content Blue IDs. */
    CHECKPOINT_CONTENT_BLUE_ID_NANOS("checkpointContentBlueIdNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent obtaining current checkpoint identities. */
    CHECKPOINT_CURRENT_IDENTITY_NANOS("checkpointCurrentIdentityNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent calculating direct checkpoint Blue IDs. */
    CHECKPOINT_DIRECT_BLUE_ID_NANOS("checkpointDirectBlueIdNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent detecting duplicate checkpoints. */
    CHECKPOINT_DUPLICATE_NANOS("checkpointDuplicateNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent ensuring checkpoint state exists. */
    CHECKPOINT_ENSURE_NANOS("checkpointEnsureNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent in checkpoint fallback handling. */
    CHECKPOINT_FALLBACK_NANOS("checkpointFallbackNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent finding checkpoints. */
    CHECKPOINT_FIND_NANOS("checkpointFindNanos", ObservationKind.COUNTER_DELTA),
    /** Counts checkpoint identity-cache hits. */
    CHECKPOINT_IDENTITY_CACHE_HITS("checkpointIdentityCacheHits", ObservationKind.COUNTER_DELTA),
    /** Counts checkpoint identity-cache misses. */
    CHECKPOINT_IDENTITY_CACHE_MISSES("checkpointIdentityCacheMisses", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent comparing checkpoint event recency. */
    CHECKPOINT_IS_NEWER_NANOS("checkpointIsNewerNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent persisting checkpoints. */
    CHECKPOINT_PERSIST_NANOS("checkpointPersistNanos", ObservationKind.COUNTER_DELTA),
    /** Counts stored-checkpoint identity-cache hits. */
    CHECKPOINT_STORED_IDENTITY_CACHE_HITS("checkpointStoredIdentityCacheHits", ObservationKind.COUNTER_DELTA),
    /** Counts stored-checkpoint identity-cache misses. */
    CHECKPOINT_STORED_IDENTITY_CACHE_MISSES("checkpointStoredIdentityCacheMisses", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent updating checkpoints. */
    CHECKPOINT_UPDATE_NANOS("checkpointUpdateNanos", ObservationKind.COUNTER_DELTA),
    /** Counts compiled-pattern cache hits. */
    COMPILED_PATTERN_HITS("compiledPatternHits", ObservationKind.COUNTER_DELTA),
    /** Counts compiled-pattern cache misses. */
    COMPILED_PATTERN_MISSES("compiledPatternMisses", ObservationKind.COUNTER_DELTA),
    /** Counts conformance operations that scan the complete Root. */
    CONFORMANCE_FULL_ROOT_SCANS("conformanceFullRootScans", ObservationKind.COUNTER_DELTA),
    /** Counts merger invocations performed for conformance. */
    CONFORMANCE_MERGER_INVOCATIONS("conformanceMergerInvocations", ObservationKind.COUNTER_DELTA),
    /** Counts mutable nodes materialized for conformance. */
    CONFORMANCE_MUTABLE_NODE_MATERIALIZATIONS("conformanceMutableNodeMaterializations", ObservationKind.COUNTER_DELTA),
    /** Counts nodes visited during conformance evaluation. */
    CONFORMANCE_NODES_VISITED("conformanceNodesVisited", ObservationKind.COUNTER_DELTA),
    /** Counts conformance plans created. */
    CONFORMANCE_PLANS("conformancePlans", ObservationKind.COUNTER_DELTA),
    /** Counts schema-conformance plan cache hits. */
    CONFORMANCE_SCHEMA_PLAN_HITS("conformanceSchemaPlanHits", ObservationKind.COUNTER_DELTA),
    /** Counts schema-conformance plan cache misses. */
    CONFORMANCE_SCHEMA_PLAN_MISSES("conformanceSchemaPlanMisses", ObservationKind.COUNTER_DELTA),
    /** Counts type-conformance plan cache hits. */
    CONFORMANCE_TYPE_PLAN_HITS("conformanceTypePlanHits", ObservationKind.COUNTER_DELTA),
    /** Counts type-conformance plan cache misses. */
    CONFORMANCE_TYPE_PLAN_MISSES("conformanceTypePlanMisses", ObservationKind.COUNTER_DELTA),
    /** Counts typed boundaries considered for conformance. */
    CONFORMANCE_TYPED_BOUNDARIES_CONSIDERED("conformanceTypedBoundariesConsidered", ObservationKind.COUNTER_DELTA),
    /** Counts typed boundaries generalized during conformance. */
    CONFORMANCE_TYPED_BOUNDARIES_GENERALIZED("conformanceTypedBoundariesGeneralized", ObservationKind.COUNTER_DELTA),
    /** Counts typed boundaries validated during conformance. */
    CONFORMANCE_TYPED_BOUNDARIES_VALIDATED("conformanceTypedBoundariesValidated", ObservationKind.COUNTER_DELTA),
    /** Counts channel deliveries removed by deduplication. */
    DEDUPLICATED_CHANNEL_DELIVERIES("deduplicatedChannelDeliveries", ObservationKind.COUNTER_DELTA),
    /** Counts materializations performed after document updates. */
    DOCUMENT_UPDATE_AFTER_MATERIALIZATIONS("documentUpdateAfterMaterializations", ObservationKind.COUNTER_DELTA),
    /** Counts materializations performed before document updates. */
    DOCUMENT_UPDATE_BEFORE_MATERIALIZATIONS("documentUpdateBeforeMaterializations", ObservationKind.COUNTER_DELTA),
    /** Counts document-update events constructed for routing. */
    DOCUMENT_UPDATE_EVENTS_BUILT("documentUpdateEventsBuilt", ObservationKind.COUNTER_DELTA),
    /** Counts document-update events skipped because no channel was present. */
    DOCUMENT_UPDATE_EVENTS_SKIPPED_NO_CHANNEL("documentUpdateEventsSkippedNoChannel", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent routing document updates. */
    DOCUMENT_UPDATE_ROUTING_NANOS("documentUpdateRoutingNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent preprocessing events. */
    EVENT_PREPROCESS_NANOS("eventPreprocessNanos", ObservationKind.COUNTER_DELTA),
    /** Counts newly created frozen nodes. */
    FROZEN_NODES_CREATED("frozenNodesCreated", ObservationKind.COUNTER_DELTA),
    /** Counts reused frozen nodes. */
    FROZEN_NODES_REUSED("frozenNodesReused", ObservationKind.COUNTER_DELTA),
    /** Counts frozen patch-value cache hits. */
    FROZEN_PATCH_VALUE_HITS("frozenPatchValueHits", ObservationKind.COUNTER_DELTA),
    /** Counts frozen patch values accepted without materialization. */
    FROZEN_PATCH_VALUES_ACCEPTED("frozenPatchValuesAccepted", ObservationKind.COUNTER_DELTA),
    /** Counts frozen patch values materialized as mutable nodes. */
    FROZEN_PATCH_VALUES_MATERIALIZED("frozenPatchValuesMaterialized", ObservationKind.COUNTER_DELTA),
    /** Counts complete canonical Root materializations. */
    FULL_CANONICAL_ROOT_MATERIALIZATIONS("fullCanonicalRootMaterializations", ObservationKind.COUNTER_DELTA),
    /** Counts complete frozen-Root-to-node materializations. */
    FULL_FROZEN_ROOT_TO_NODE_MATERIALIZATIONS("fullFrozenRootToNodeMaterializations", ObservationKind.COUNTER_DELTA),
    /** Counts complete resolved Root materializations. */
    FULL_RESOLVED_ROOT_MATERIALIZATIONS("fullResolvedRootMaterializations", ObservationKind.COUNTER_DELTA),
    /** Counts full-snapshot fallbacks by the selected fallback reason. */
    FULL_SNAPSHOT_FALLBACK_REASON("fullSnapshotFallbackReason", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.FALLBACK_REASON),
    /** Counts full-snapshot fallback operations. */
    FULL_SNAPSHOT_FALLBACKS("fullSnapshotFallbacks", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent discovering handlers. */
    HANDLER_DISCOVERY_NANOS("handlerDiscoveryNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent executing handlers. */
    HANDLER_EXECUTION_NANOS("handlerExecutionNanos", ObservationKind.COUNTER_DELTA),
    /** Counts handler match attempts. */
    HANDLER_MATCH_ATTEMPTS("handlerMatchAttempts", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent matching handlers. */
    HANDLER_MATCH_NANOS("handlerMatchNanos", ObservationKind.COUNTER_DELTA),
    /** Counts handlers executed. */
    HANDLERS_EXECUTED("handlersExecuted", ObservationKind.COUNTER_DELTA),
    /** Counts ancestor nodes revalidated by incremental processing. */
    INCREMENTAL_ANCESTORS_REVALIDATED("incrementalAncestorsRevalidated", ObservationKind.COUNTER_DELTA),
    /** Counts nodes in incremental processing boundaries. */
    INCREMENTAL_BOUNDARY_NODE_COUNT("incrementalBoundaryNodeCount", ObservationKind.COUNTER_DELTA),
    /** Accumulates path depth across incremental processing boundaries. */
    INCREMENTAL_BOUNDARY_PATH_DEPTH("incrementalBoundaryPathDepth", ObservationKind.COUNTER_DELTA),
    /** Counts incremental merger capabilities that were allowed. */
    INCREMENTAL_MERGER_CAPABILITY_ALLOWED("incrementalMergerCapabilityAllowed", ObservationKind.COUNTER_DELTA),
    /** Counts incremental merger capabilities that were denied. */
    INCREMENTAL_MERGER_CAPABILITY_DENIED("incrementalMergerCapabilityDenied", ObservationKind.COUNTER_DELTA),
    /** Counts incremental merger capabilities denied by conformance. */
    INCREMENTAL_MERGER_CAPABILITY_DENIED_BY_CONFORMANCE("incrementalMergerCapabilityDeniedByConformance", ObservationKind.COUNTER_DELTA),
    /** Counts incremental merger capabilities denied by the snapshot manager. */
    INCREMENTAL_MERGER_CAPABILITY_DENIED_BY_SNAPSHOT_MANAGER("incrementalMergerCapabilityDeniedBySnapshotManager", ObservationKind.COUNTER_DELTA),
    /** Counts incremental merger capability requests. */
    INCREMENTAL_MERGER_CAPABILITY_REQUESTS("incrementalMergerCapabilityRequests", ObservationKind.COUNTER_DELTA),
    /** Counts incremental snapshot resolutions. */
    INCREMENTAL_SNAPSHOT_RESOLUTIONS("incrementalSnapshotResolutions", ObservationKind.COUNTER_DELTA),
    /** Counts canonical materializations used for initialization document IDs. */
    INITIALIZATION_DOCUMENT_ID_CANONICAL_MATERIALIZATIONS("initializationDocumentIdCanonicalMaterializations", ObservationKind.COUNTER_DELTA),
    /** Counts unchecked frozen calculations for initialization document IDs. */
    INITIALIZATION_DOCUMENT_ID_FROZEN_UNCHECKED_CALCULATIONS("initializationDocumentIdFrozenUncheckedCalculations", ObservationKind.COUNTER_DELTA),
    /** Counts node materializations used for initialization document IDs. */
    INITIALIZATION_DOCUMENT_ID_NODE_MATERIALIZATIONS("initializationDocumentIdNodeMaterializations", ObservationKind.COUNTER_DELTA),
    /** Counts unchecked calculations for initialization document IDs. */
    INITIALIZATION_DOCUMENT_ID_UNCHECKED_CALCULATIONS("initializationDocumentIdUncheckedCalculations", ObservationKind.COUNTER_DELTA),
    /** Counts fallbacks to JSON Canonicalization Scheme processing. */
    JCS_FALLBACKS("jcsFallbacks", ObservationKind.COUNTER_DELTA),
    /** Counts mutable patch values converted to frozen values. */
    MUTABLE_PATCH_VALUES_FROZEN("mutablePatchValuesFrozen", ObservationKind.COUNTER_DELTA),
    /** Counts mutable patch values frozen for the selected patch source. */
    MUTABLE_PATCH_VALUES_FROZEN_BY_SOURCE("mutablePatchValuesFrozenBySource", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.PATCH_SOURCE),
    /** Counts node clone calls for the selected clone purpose. */
    NODE_CLONE_CALLS_BY_PURPOSE("nodeCloneCallsByPurpose", ObservationKind.COUNTER_DELTA,
            ProcessingObservationDimension.CLONE_PURPOSE),
    /** Counts parsed-pointer cache hits. */
    PARSED_POINTER_CACHE_HITS("parsedPointerCacheHits", ObservationKind.COUNTER_DELTA),
    /** Counts parsed-pointer cache misses. */
    PARSED_POINTER_CACHE_MISSES("parsedPointerCacheMisses", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent enforcing patch boundaries. */
    PATCH_BOUNDARY_NANOS("patchBoundaryNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent accounting patch gas. */
    PATCH_GAS_NANOS("patchGasNanos", ObservationKind.COUNTER_DELTA),
    /** Counts patch impact analyses. */
    PATCH_IMPACT_ANALYSES("patchImpactAnalyses", ObservationKind.COUNTER_DELTA),
    /** Counts patches classified as changing collection shape. */
    PATCH_IMPACT_COLLECTION_SHAPE("patchImpactCollectionShape", ObservationKind.COUNTER_DELTA),
    /** Counts patches classified as affecting contracts or processing state. */
    PATCH_IMPACT_CONTRACTS_OR_PROCESSING("patchImpactContractsOrProcessing", ObservationKind.COUNTER_DELTA),
    /** Counts patches classified as affecting merge policy. */
    PATCH_IMPACT_MERGE_POLICY("patchImpactMergePolicy", ObservationKind.COUNTER_DELTA),
    /** Counts patches classified as changing an object member value. */
    PATCH_IMPACT_OBJECT_MEMBER_VALUE("patchImpactObjectMemberValue", ObservationKind.COUNTER_DELTA),
    /** Counts patches classified as affecting processor-managed state. */
    PATCH_IMPACT_PROCESSOR_MANAGED_STATE("patchImpactProcessorManagedState", ObservationKind.COUNTER_DELTA),
    /** Counts patches classified as affecting references. */
    PATCH_IMPACT_REFERENCE("patchImpactReference", ObservationKind.COUNTER_DELTA),
    /** Counts patches classified as replacing the Root. */
    PATCH_IMPACT_ROOT_REPLACEMENT("patchImpactRootReplacement", ObservationKind.COUNTER_DELTA),
    /** Counts patches classified as affecting schema metadata. */
    PATCH_IMPACT_SCHEMA_METADATA("patchImpactSchemaMetadata", ObservationKind.COUNTER_DELTA),
    /** Counts patches classified as affecting type metadata. */
    PATCH_IMPACT_TYPE_METADATA("patchImpactTypeMetadata", ObservationKind.COUNTER_DELTA),
    /** Counts patches whose impact could not be classified. */
    PATCH_IMPACT_UNKNOWN("patchImpactUnknown", ObservationKind.COUNTER_DELTA),
    /** Counts patches classified as changing only a value. */
    PATCH_IMPACT_VALUE_ONLY("patchImpactValueOnly", ObservationKind.COUNTER_DELTA),
    /** Counts patch sequences prepared for execution. */
    PATCH_SEQUENCES_PREPARED("patchSequencesPrepared", ObservationKind.COUNTER_DELTA),
    /** Counts patch values materialized as mutable nodes. */
    PATCH_VALUE_MATERIALIZATIONS("patchValueMaterializations", ObservationKind.COUNTER_DELTA),
    /** Counts individual patches prepared for execution. */
    PATCHES_PREPARED("patchesPrepared", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent in post-processing. */
    POST_PROCESSING_NANOS("postProcessingNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent processing documents. */
    PROCESS_DOCUMENT_NANOS("processDocumentNanos", ObservationKind.COUNTER_DELTA),
    /** Counts attempts to obtain process-event snapshots. */
    PROCESS_EVENT_SNAPSHOT_ATTEMPTS("processEventSnapshotAttempts", ObservationKind.COUNTER_DELTA),
    /** Counts process-event snapshots built. */
    PROCESS_EVENT_SNAPSHOT_BUILDS("processEventSnapshotBuilds", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent constructing process-event snapshots. */
    PROCESS_EVENT_SNAPSHOT_CONSTRUCTION_NANOS("processEventSnapshotConstructionNanos", ObservationKind.COUNTER_DELTA),
    /** Counts failures while obtaining process-event snapshots. */
    PROCESS_EVENT_SNAPSHOT_FAILURES("processEventSnapshotFailures", ObservationKind.COUNTER_DELTA),
    /** Counts processing-snapshot cache hits. */
    PROCESSING_SNAPSHOT_CACHE_HITS("processingSnapshotCacheHits", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent looking up processing snapshots in cache. */
    PROCESSING_SNAPSHOT_CACHE_LOOKUP_NANOS("processingSnapshotCacheLookupNanos", ObservationKind.COUNTER_DELTA),
    /** Counts processing-snapshot cache misses. */
    PROCESSING_SNAPSHOT_CACHE_MISSES("processingSnapshotCacheMisses", ObservationKind.COUNTER_DELTA),
    /** Counts processing snapshots built from documents. */
    PROCESSING_SNAPSHOT_FROM_DOCUMENT_BUILDS("processingSnapshotFromDocumentBuilds", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent deriving processing snapshots from documents. */
    PROCESSING_SNAPSHOT_FROM_DOCUMENT_NANOS("processingSnapshotFromDocumentNanos", ObservationKind.COUNTER_DELTA),
    /** Counts processor inputs canonicalized using strict semantics. */
    PROCESSOR_INPUT_STRICT_CANONICAL("processorInputStrictCanonical", ObservationKind.COUNTER_DELTA),
    /** Counts processor inputs canonicalized using unchecked semantics. */
    PROCESSOR_INPUT_UNCHECKED_CANONICAL("processorInputUncheckedCanonical", ObservationKind.COUNTER_DELTA),
    /** Counts incremental resolutions of processor-managed markers. */
    PROCESSOR_MANAGED_MARKER_INCREMENTAL_RESOLUTIONS("processorManagedMarkerIncrementalResolutions", ObservationKind.COUNTER_DELTA),
    /** Counts patches applied to processor-managed markers. */
    PROCESSOR_MANAGED_MARKER_PATCHES("processorManagedMarkerPatches", ObservationKind.COUNTER_DELTA),
    /** Counts canonical materializations performed for processor publication. */
    PROCESSOR_PUBLICATION_CANONICAL_MATERIALIZATIONS("processorPublicationCanonicalMaterializations", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent canonicalizing processor publication values. */
    PROCESSOR_PUBLICATION_CANONICALIZATION_NANOS("processorPublicationCanonicalizationNanos", ObservationKind.COUNTER_DELTA),
    /** Counts processor publication canonicalizations. */
    PROCESSOR_PUBLICATION_CANONICALIZATIONS("processorPublicationCanonicalizations", ObservationKind.COUNTER_DELTA),
    /** Counts identity mismatches detected during processor publication. */
    PROCESSOR_PUBLICATION_IDENTITY_MISMATCHES("processorPublicationIdentityMismatches", ObservationKind.COUNTER_DELTA),
    /** Counts processor publication invariant checks. */
    PROCESSOR_PUBLICATION_INVARIANT_CHECKS("processorPublicationInvariantChecks", ObservationKind.COUNTER_DELTA),
    /** Counts strict Blue ID calculations for processor publication. */
    PROCESSOR_PUBLICATION_STRICT_BLUE_ID_CALCULATIONS("processorPublicationStrictBlueIdCalculations", ObservationKind.COUNTER_DELTA),
    /** Counts published processor values canonicalized using strict semantics. */
    PROCESSOR_PUBLISHED_STRICT_CANONICAL("processorPublishedStrictCanonical", ObservationKind.COUNTER_DELTA),
    /** Counts published processor values canonicalized using unchecked semantics. */
    PROCESSOR_PUBLISHED_UNCHECKED_CANONICAL("processorPublishedUncheckedCanonical", ObservationKind.COUNTER_DELTA),
    /** Counts incremental updates to reference-reachability state. */
    REFERENCE_REACHABILITY_DELTA_UPDATES("referenceReachabilityDeltaUpdates", ObservationKind.COUNTER_DELTA),
    /** Counts full scans used to determine reference reachability. */
    REFERENCE_REACHABILITY_FULL_SCANS("referenceReachabilityFullScans", ObservationKind.COUNTER_DELTA),
    /** Counts references resolved again after invalidation. */
    REFERENCES_RE_RESOLVED("referencesReResolved", ObservationKind.COUNTER_DELTA),
    /** Counts resolved references reused without re-resolution. */
    REFERENCES_REUSED("referencesReused", ObservationKind.COUNTER_DELTA),
    /** Counts identity calculations for resolved values. */
    RESOLVED_IDENTITY_CALCULATIONS("resolvedIdentityCalculations", ObservationKind.COUNTER_DELTA),
    /** Counts structural cache keys built for resolved values. */
    RESOLVED_STRUCTURAL_KEY_BUILDS("resolvedStructuralKeyBuilds", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent attaching snapshots to results. */
    RESULT_SNAPSHOT_ATTACH_NANOS("resultSnapshotAttachNanos", ObservationKind.COUNTER_DELTA),
    /** Counts channel deliveries routed to handler targets. */
    ROUTED_CHANNEL_DELIVERIES("routedChannelDeliveries", ObservationKind.COUNTER_DELTA),
    /** Counts runtime close invocations. */
    RUNTIME_CLOSE_CALLS("runtimeCloseCalls", ObservationKind.COUNTER_DELTA),
    /** Counts cache-weight bytes released by runtime close operations. */
    RUNTIME_CLOSE_RELEASED_WEIGHT_BYTES("runtimeCloseReleasedWeightBytes", ObservationKind.COUNTER_DELTA),
    /** Counts sequence-cache entries released. */
    SEQUENCE_CACHE_ENTRIES_RELEASED("sequenceCacheEntriesReleased", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent committing patch sequences. */
    SEQUENCE_COMMIT_NANOS("sequenceCommitNanos", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent checking patch-sequence conformance. */
    SEQUENCE_CONFORMANCE_NANOS("sequenceConformanceNanos", ObservationKind.COUNTER_DELTA),
    /** Counts sequence patches executed through a fallback path. */
    SEQUENCE_FALLBACK_PATCHES("sequenceFallbackPatches", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent committing final sequence cache state. */
    SEQUENCE_FINAL_CACHE_COMMIT_NANOS("sequenceFinalCacheCommitNanos", ObservationKind.COUNTER_DELTA),
    /** Counts final snapshots inserted into the sequence cache. */
    SEQUENCE_FINAL_SNAPSHOT_CACHE_INSERTS("sequenceFinalSnapshotCacheInserts", ObservationKind.COUNTER_DELTA),
    /** Counts intermediate snapshot advances within patch sequences. */
    SEQUENCE_INTERMEDIATE_SNAPSHOT_ADVANCES("sequenceIntermediateSnapshotAdvances", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent planning patch sequences. */
    SEQUENCE_PLANNING_NANOS("sequencePlanningNanos", ObservationKind.COUNTER_DELTA),
    /** Counts shared snapshots inserted into the sequence cache. */
    SEQUENCE_SHARED_SNAPSHOT_CACHE_INSERTS("sequenceSharedSnapshotCacheInserts", ObservationKind.COUNTER_DELTA),
    /** Counts sequence fallbacks caused by stale previews. */
    SEQUENCE_STALE_PREVIEW_FALLBACKS("sequenceStalePreviewFallbacks", ObservationKind.COUNTER_DELTA),
    /** Counts suffix rebases performed for patch sequences. */
    SEQUENCE_SUFFIX_REBASES("sequenceSuffixRebases", ObservationKind.COUNTER_DELTA),
    /** Counts patch transactions containing a single patch. */
    SINGLETON_PATCH_TRANSACTIONS("singletonPatchTransactions", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent committing snapshots. */
    SNAPSHOT_COMMIT_NANOS("snapshotCommitNanos", ObservationKind.COUNTER_DELTA),
    /** Counts subtree-to-node materializations. */
    SUBTREE_TO_NODE_MATERIALIZATIONS("subtreeToNodeMaterializations", ObservationKind.COUNTER_DELTA),
    /** Measures nanoseconds spent routing triggered events. */
    TRIGGERED_EVENT_ROUTING_NANOS("triggeredEventRoutingNanos", ObservationKind.COUNTER_DELTA),
    /** Counts triggered events routed. */
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

    /**
     * Returns the stable external name recorded in the metric manifest.
     *
     * @return stable manifest name
     */
    public String externalName() {
        return externalName;
    }

    /**
     * Returns the observation kind required when recording this metric.
     *
     * @return the only valid aggregation kind for this metric
     */
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
