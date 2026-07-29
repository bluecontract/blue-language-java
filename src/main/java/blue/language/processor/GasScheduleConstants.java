package blue.language.processor;

/**
 * Stable names defined by the bundled Contracts 1.0 gas schedule.
 *
 * <p>These values are part of the schedule contract. Runtime code and host
 * integrations should use the constants instead of duplicating namespace,
 * counter, portable-limit, or formula-parameter strings.</p>
 */
public final class GasScheduleConstants {

    /** Property names in the canonical gas-manifest document. */
    public static final class ManifestField {
        /** Manifest field describing the manifest document kind. */
        public static final String MANIFEST_TYPE = "manifestType";
        /** Manifest field for schedule. */
        public static final String SCHEDULE = "schedule";
        /** Manifest field for the specification version. */
        public static final String SPECIFICATION_VERSION =
                "specificationVersion";
        /** Manifest field for package identity. */
        public static final String PACKAGE_IDENTITY = "packageIdentity";
        /** Manifest field defining when gas is admitted. */
        public static final String ADMISSION_RULE = "admissionRule";
        /** Manifest field for max process gas. */
        public static final String MAX_PROCESS_GAS = "maxProcessGas";
        /** Manifest field for namespaces. */
        public static final String NAMESPACES = "namespaces";
        /** Manifest field for counters. */
        public static final String COUNTERS = "counters";
        /** Manifest field for counter count. */
        public static final String COUNTER_COUNT = "counterCount";
        /** Manifest field for portable limits. */
        public static final String PORTABLE_LIMITS = "portableLimits";
        /** Manifest field for formulas. */
        public static final String FORMULAS = "formulas";
        /** Manifest field for text blocks. */
        public static final String TEXT_BLOCKS = "textBlocks";
        /** Manifest field for integer limbs. */
        public static final String INTEGER_LIMBS = "integerLimbs";
        /** Manifest field for sorting. */
        public static final String SORTING = "sorting";
        /** Manifest field for identity. */
        public static final String IDENTITY = "identity";
        /** Manifest field for block code points. */
        public static final String BLOCK_CODE_POINTS =
                "blockCodePoints";
        /** Manifest field for minimum limbs. */
        public static final String MINIMUM_LIMBS = "minimumLimbs";
        /** Manifest field for radix. */
        public static final String RADIX = "radix";
        /** Manifest field for initial run width. */
        public static final String INITIAL_RUN_WIDTH =
                "initialRunWidth";
        /** Manifest field for direct hash blocks. */
        public static final String DIRECT_HASH_BLOCKS =
                "directHashBlocks";

        private ManifestField() {
        }
    }

    /** Gas namespaces owned by the language kernel. */
    public static final class Namespace {
        /** Gas namespace for processor. */
        public static final String PROCESSOR = "processor";
        /** Gas namespace for semantic. */
        public static final String SEMANTIC = "semantic";

        private Namespace() {
        }
    }

    /** Counters in the processor namespace. */
    public static final class ProcessorCounter {
        /** Processor gas counter for process invocation. */
        public static final String PROCESS_INVOCATION =
                "processInvocation";
        /** Processor gas counter for delivery snapshot entry. */
        public static final String DELIVERY_SNAPSHOT_ENTRY =
                "deliverySnapshotEntry";
        /** Processor gas counter for scope opened. */
        public static final String SCOPE_OPENED = "scopeOpened";
        /** Processor gas counter for contract header recognized. */
        public static final String CONTRACT_HEADER_RECOGNIZED =
                "contractHeaderRecognized";
        /** Processor gas counter for channel candidate tested. */
        public static final String CHANNEL_CANDIDATE_TESTED =
                "channelCandidateTested";
        /** Processor gas counter for channel accepted. */
        public static final String CHANNEL_ACCEPTED = "channelAccepted";
        /** Processor gas counter for handler candidate tested. */
        public static final String HANDLER_CANDIDATE_TESTED =
                "handlerCandidateTested";
        /** Processor gas counter for handler call. */
        public static final String HANDLER_CALL = "handlerCall";
        /** Processor gas counter for scope initialization. */
        public static final String SCOPE_INITIALIZATION =
                "scopeInitialization";
        /** Processor gas counter for embedded path entry read. */
        public static final String EMBEDDED_PATH_ENTRY_READ =
                "embeddedPathEntryRead";
        /** Processor gas counter for embedded path segment validated. */
        public static final String EMBEDDED_PATH_SEGMENT_VALIDATED =
                "embeddedPathSegmentValidated";
        /** Processor gas counter for pointer segment traversed. */
        public static final String POINTER_SEGMENT_TRAVERSED =
                "pointerSegmentTraversed";
        /** Processor gas counter for patch boundary checked. */
        public static final String PATCH_BOUNDARY_CHECKED =
                "patchBoundaryChecked";
        /** Processor gas counter for patch add or replace. */
        public static final String PATCH_ADD_OR_REPLACE =
                "patchAddOrReplace";
        /** Processor gas counter for patch remove. */
        public static final String PATCH_REMOVE = "patchRemove";
        /** Processor gas counter for document update delivered. */
        public static final String DOCUMENT_UPDATE_DELIVERED =
                "documentUpdateDelivered";
        /** Processor gas counter for internal event enqueued. */
        public static final String INTERNAL_EVENT_ENQUEUED =
                "internalEventEnqueued";
        /** Processor gas counter for internal event dequeued. */
        public static final String INTERNAL_EVENT_DEQUEUED =
                "internalEventDequeued";
        /** Processor gas counter for triggered event delivered. */
        public static final String TRIGGERED_EVENT_DELIVERED =
                "triggeredEventDelivered";
        /** Processor gas counter for embedded event delivered. */
        public static final String EMBEDDED_EVENT_DELIVERED =
                "embeddedEventDelivered";
        /** Processor gas counter for root event recorded. */
        public static final String ROOT_EVENT_RECORDED =
                "rootEventRecorded";
        /** Processor gas counter for lifecycle delivered. */
        public static final String LIFECYCLE_DELIVERED =
                "lifecycleDelivered";
        /** Processor gas counter for checkpoint compared. */
        public static final String CHECKPOINT_COMPARED =
                "checkpointCompared";
        /** Processor gas counter for checkpoint written. */
        public static final String CHECKPOINT_WRITTEN =
                "checkpointWritten";
        /** Processor gas counter for processor marker written. */
        public static final String PROCESSOR_MARKER_WRITTEN =
                "processorMarkerWritten";
        /** Processor gas counter for termination requested. */
        public static final String TERMINATION_REQUESTED =
                "terminationRequested";

        private ProcessorCounter() {
        }
    }

    /** Counters in the semantic namespace. */
    public static final class SemanticCounter {
        /** Semantic gas counter for node manifest opened. */
        public static final String NODE_MANIFEST_OPENED =
                "nodeManifestOpened";
        /** Semantic gas counter for object member read. */
        public static final String OBJECT_MEMBER_READ =
                "objectMemberRead";
        /** Semantic gas counter for list item read. */
        public static final String LIST_ITEM_READ = "listItemRead";
        /** Semantic gas counter for text block examined. */
        public static final String TEXT_BLOCK_EXAMINED =
                "textBlockExamined";
        /** Semantic gas counter for text block constructed. */
        public static final String TEXT_BLOCK_CONSTRUCTED =
                "textBlockConstructed";
        /** Semantic gas counter for scalar comparison. */
        public static final String SCALAR_COMPARISON =
                "scalarComparison";
        /** Semantic gas counter for integer limb operation. */
        public static final String INTEGER_LIMB_OPERATION =
                "integerLimbOperation";
        /** Semantic gas counter for sort comparison. */
        public static final String SORT_COMPARISON = "sortComparison";
        /** Semantic gas counter for type edge followed. */
        public static final String TYPE_EDGE_FOLLOWED =
                "typeEdgeFollowed";
        /** Semantic gas counter for schema predicate evaluated. */
        public static final String SCHEMA_PREDICATE_EVALUATED =
                "schemaPredicateEvaluated";
        /** Semantic gas counter for validation member examined. */
        public static final String VALIDATION_MEMBER_EXAMINED =
                "validationMemberExamined";
        /** Semantic gas counter for validation proof reused. */
        public static final String VALIDATION_PROOF_REUSED =
                "validationProofReused";
        /** Semantic gas counter for subtype candidate tested. */
        public static final String SUBTYPE_CANDIDATE_TESTED =
                "subtypeCandidateTested";
        /** Semantic gas counter for node identity established. */
        public static final String NODE_IDENTITY_ESTABLISHED =
                "nodeIdentityEstablished";
        /** Semantic gas counter for object member rebuilt. */
        public static final String OBJECT_MEMBER_REBUILT =
                "objectMemberRebuilt";
        /** Semantic gas counter for list fold step recomputed. */
        public static final String LIST_FOLD_STEP_RECOMPUTED =
                "listFoldStepRecomputed";
        /** Semantic gas counter for direct identity hash block. */
        public static final String DIRECT_IDENTITY_HASH_BLOCK =
                "directIdentityHashBlock";

        private SemanticCounter() {
        }
    }

    /** Portable-limit names in the Contracts 1.0 manifest. */
    public static final class PortableLimit {
        /** Portable limit for effective contracts per scope. */
        public static final String EFFECTIVE_CONTRACTS_PER_SCOPE =
                "effectiveContractsPerParticipatingScope";
        /** Portable limit for external channels per scope. */
        public static final String EXTERNAL_CHANNELS_PER_SCOPE =
                "externalChannelsPerScope";
        /** Portable limit for handlers per delivery. */
        public static final String HANDLERS_PER_DELIVERY =
                "handlersBoundToOneDelivery";
        /** Portable limit for subscription keys per channel. */
        public static final String SUBSCRIPTION_KEYS_PER_CHANNEL =
                "subscriptionKeysPerChannel";
        /** Portable limit for preselected external occurrences. */
        public static final String PRESELECTED_EXTERNAL_OCCURRENCES =
                "preselectedExternalOccurrencesPerEvent";
        /** Portable limit for participating scopes per event. */
        public static final String PARTICIPATING_SCOPES_PER_EVENT =
                "participatingScopesPerEvent";
        /** Portable limit for process embedded paths per scope. */
        public static final String PROCESS_EMBEDDED_PATHS_PER_SCOPE =
                "processEmbeddedPathsPerScope";
        /** Portable limit for embedded depth. */
        public static final String EMBEDDED_DEPTH = "embeddedDepth";
        /** Portable limit for runtime pointer segments. */
        public static final String RUNTIME_POINTER_SEGMENTS =
                "runtimePointerSegments";
        /** Portable limit for runtime pointer utf8 bytes. */
        public static final String RUNTIME_POINTER_UTF8_BYTES =
                "normalizedRuntimePointerUtf8Bytes";
        /** Portable limit for contract key code points. */
        public static final String CONTRACT_KEY_CODE_POINTS =
                "contractKeyCodePoints";
        /** Portable limit for contract key utf8 bytes. */
        public static final String CONTRACT_KEY_UTF8_BYTES =
                "contractKeyUtf8Bytes";
        /** Portable limit for direct object entries. */
        public static final String DIRECT_OBJECT_ENTRIES =
                "directObjectEntriesMaterializedOrRebuilt";
        /** Portable limit for direct list items. */
        public static final String DIRECT_LIST_ITEMS =
                "directListItemsMaterializedOrRebuilt";
        /** Portable limit for direct canonical identity input bytes. */
        public static final String DIRECT_CANONICAL_IDENTITY_INPUT_BYTES =
                "directCanonicalIdentityInputBytes";
        /** Portable limit for type chain edges. */
        public static final String TYPE_CHAIN_EDGES = "typeChainEdges";
        /** Portable limit for patches per contract result. */
        public static final String PATCHES_PER_CONTRACT_RESULT =
                "patchesPerContractExecutionResult";
        /** Portable limit for events per contract result. */
        public static final String EVENTS_PER_CONTRACT_RESULT =
                "eventsPerContractExecutionResult";
        /** Portable limit for internal event occurrences. */
        public static final String INTERNAL_EVENT_OCCURRENCES =
                "internalEventOccurrencesPerInvocation";
        /** Portable limit for root events returned. */
        public static final String ROOT_EVENTS_RETURNED =
                "rootEventsReturned";
        /** Portable limit for document update cascade depth. */
        public static final String DOCUMENT_UPDATE_CASCADE_DEPTH =
                "nestedDocumentUpdateCascadeDepth";
        /** Portable limit for runtime child ledger counter kinds. */
        public static final String RUNTIME_CHILD_LEDGER_COUNTER_KINDS =
                "runtimeChildLedgerCounterKinds";
        /** Portable limit for direct object key code points. */
        public static final String DIRECT_OBJECT_KEY_CODE_POINTS =
                "directObjectKeyCodePoints";
        /** Portable limit for direct inline identity text code points. */
        public static final String DIRECT_INLINE_IDENTITY_TEXT_CODE_POINTS =
                "directInlineIdentityTextCodePoints";

        private PortableLimit() {
        }
    }

    /** Normalized formula-parameter names exposed by {@link GasSchedule}. */
    public static final class FormulaParameter {
        /** Formula parameter for text block code points. */
        public static final String TEXT_BLOCK_CODE_POINTS =
                "textBlockCodePoints";
        /** Formula parameter for integer minimum limbs. */
        public static final String INTEGER_MINIMUM_LIMBS =
                "integerMinimumLimbs";
        /** Formula parameter for integer radix bits. */
        public static final String INTEGER_RADIX_BITS =
                "integerRadixBits";
        /** Formula parameter for sorting initial run width. */
        public static final String SORTING_INITIAL_RUN_WIDTH =
                "sortingInitialRunWidth";
        /** Formula parameter for identity hash domain bytes. */
        public static final String IDENTITY_HASH_DOMAIN_BYTES =
                "identityHashDomainBytes";
        /** Formula parameter for identity hash block bytes. */
        public static final String IDENTITY_HASH_BLOCK_BYTES =
                "identityHashBlockBytes";

        private FormulaParameter() {
        }
    }

    /** Stable reason labels attached to processor gas trace entries. */
    public static final class ChargeReason {
        /** Gas charge reason for invocation. */
        public static final String INVOCATION = "invocation";
        /** Gas charge reason for revalidate delivery. */
        public static final String REVALIDATE_DELIVERY =
                "revalidate-delivery";
        /** Gas charge reason for participating scope. */
        public static final String PARTICIPATING_SCOPE =
                "participating-scope";
        /** Gas charge reason for participating closure. */
        public static final String PARTICIPATING_CLOSURE =
                "participating-closure";
        /** Gas charge reason for route. */
        public static final String ROUTE = "route";
        /** Gas charge reason for scope initialization. */
        public static final String SCOPE_INITIALIZATION =
                "scope-initialization";
        /** Gas charge reason for acceptance. */
        public static final String ACCEPTANCE = "acceptance";
        /** Gas charge reason for matching. */
        public static final String MATCHING = "matching";
        /** Gas charge reason for handler call. */
        public static final String HANDLER_CALL = "handler-call";
        /** Gas charge reason for patch boundary. */
        public static final String PATCH_BOUNDARY = "patch-boundary";
        /** Gas charge reason for runtime pointer. */
        public static final String RUNTIME_POINTER = "runtime-pointer";
        /** Gas charge reason for application patch. */
        public static final String APPLICATION_PATCH =
                "application-patch";
        /** Gas charge reason for document update. */
        public static final String DOCUMENT_UPDATE = "document-update";
        /** Gas charge reason for event emission. */
        public static final String EVENT_EMISSION = "event-emission";
        /** Gas charge reason for root emission. */
        public static final String ROOT_EMISSION = "root-emission";
        /** Gas charge reason for embedded event. */
        public static final String EMBEDDED_EVENT = "embedded-event";
        /** Gas charge reason for triggered event. */
        public static final String TRIGGERED_EVENT = "triggered-event";
        /** Gas charge reason for event drain. */
        public static final String EVENT_DRAIN = "event-drain";
        /** Gas charge reason for checkpoint compare. */
        public static final String CHECKPOINT_COMPARE =
                "checkpoint-compare";
        /** Gas charge reason for checkpoint write. */
        public static final String CHECKPOINT_WRITE = "checkpoint-write";
        /** Gas charge reason for termination request. */
        public static final String TERMINATION_REQUEST =
                "termination-request";
        /** Gas charge reason for termination marker. */
        public static final String TERMINATION_MARKER =
                "termination-marker";
        /** Gas charge reason for lifecycle. */
        public static final String LIFECYCLE = "lifecycle";

        private ChargeReason() {
        }
    }

    private GasScheduleConstants() {
    }
}
