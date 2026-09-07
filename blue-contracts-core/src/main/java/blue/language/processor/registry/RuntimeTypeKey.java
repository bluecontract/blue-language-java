package blue.language.processor.registry;

/**
 * Stable symbolic keys for the closed Contracts runtime type registry.
 *
 * <p>Keys separate call-site intent from concrete BlueIds, which are verified
 * when the registry manifest is loaded.</p>
 */
public enum RuntimeTypeKey {
    /** Base channel contract type. */
    CHANNEL,
    /** Channel checkpoint marker type. */
    CHANNEL_EVENT_CHECKPOINT,
    /** One checkpoint entry type. */
    CHECKPOINT_ENTRY,
    /** Base contract type. */
    CONTRACT,
    /** Contract execution-result type. */
    CONTRACT_EXECUTION_RESULT,
    /** Processing-initiated event type. */
    DOCUMENT_PROCESSING_INITIATED,
    /** Processing-terminated event type. */
    DOCUMENT_PROCESSING_TERMINATED,
    /** Document-update event type. */
    DOCUMENT_UPDATE,
    /** Document-update channel type. */
    DOCUMENT_UPDATE_CHANNEL,
    /** Embedded-collection event channel type. */
    EMBEDDED_COLLECTION_EVENT_CHANNEL,
    /** Embedded-delivery event type. */
    EMBEDDED_EVENT_DELIVERY,
    /** Embedded-node channel type. */
    EMBEDDED_NODE_CHANNEL,
    /** Base external-channel type. */
    EXTERNAL_CHANNEL,
    /** Closed-conformance fixture event type. */
    FIXTURE_EVENT,
    /** Base handler contract type. */
    HANDLER,
    /** JSON-patch entry type. */
    JSON_PATCH_ENTRY,
    /** Lifecycle-event channel type. */
    LIFECYCLE_EVENT_CHANNEL,
    /** Base marker contract type. */
    MARKER,
    /** Embedded-processing configuration type. */
    PROCESS_EMBEDDED,
    /** Processing-initialized marker type. */
    PROCESSING_INITIALIZED_MARKER,
    /** Processing-terminated marker type. */
    PROCESSING_TERMINATED_MARKER,
    /** Runtime gas-counter entry type. */
    RUNTIME_COUNTER_ENTRY,
    /** Runtime gas-ledger type. */
    RUNTIME_LEDGER,
    /** Scripted external-channel conformance type. */
    SCRIPTED_EXTERNAL_CHANNEL,
    /** Scripted handler conformance type. */
    SCRIPTED_HANDLER,
    /** Triggered-event channel type. */
    TRIGGERED_EVENT_CHANNEL,
    /** Type-generalization policy type. */
    TYPE_GENERALIZATION_POLICY,
    /** One type-generalization rule type. */
    TYPE_GENERALIZATION_RULE
}
