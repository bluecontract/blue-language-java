package blue.language.processor;

/**
 * Stable field names and categorical values used in
 * {@link ProcessingTraceRecord#details()}.
 *
 * <p>The trace is consumed by conformance tooling and host diagnostics, so
 * these values form a small wire contract. Producers and consumers must refer
 * to the same named constants instead of duplicating string literals.</p>
 */
public final class ProcessingTraceConstants {

    /** Detail field describing an action. */
    public static final String FIELD_ACTION = "action";
    /** Detail field containing the active checkpoint domain. */
    public static final String FIELD_ACTIVE_DOMAIN = "activeDomain";
    /** Detail field containing the number of added subscriptions. */
    public static final String FIELD_ADDED = "added";
    /** Detail field indicating whether a value exists after an operation. */
    public static final String FIELD_AFTER_PRESENT = "afterPresent";
    /** Detail field indicating whether a value exists before an operation. */
    public static final String FIELD_BEFORE_PRESENT = "beforePresent";
    /** Detail field containing a channel key. */
    public static final String FIELD_CHANNEL_KEY = "channelKey";
    /** Detail field containing a checkpoint domain BlueId. */
    public static final String FIELD_CHECKPOINT_DOMAIN_BLUE_ID =
            "checkpointDomainBlueId";
    /** Detail field containing a checkpoint subject BlueId. */
    public static final String FIELD_CHECKPOINT_SUBJECT_BLUE_ID =
            "checkpointSubjectBlueId";
    /** Detail field indicating whether checkpoint domains match. */
    public static final String FIELD_DOMAIN_MATCHES = "domainMatches";
    /** Detail field containing a checkpoint domain. */
    public static final String FIELD_DOMAIN = "domain";
    /** Detail field identifying the owner of an event drain. */
    public static final String FIELD_DRAIN_OWNER = "drainOwner";
    /** Detail field describing the discarded effect category. */
    public static final String FIELD_EFFECT = "effect";
    /** Detail field containing an effective type BlueId. */
    public static final String FIELD_EFFECTIVE_TYPE_BLUE_ID =
            "effectiveTypeBlueId";
    /** Detail field containing an event label. */
    public static final String FIELD_EVENT = "event";
    /** Property used as the preferred human-readable event label. */
    public static final String EVENT_LABEL_PROPERTY = "id";
    /** Detail field containing a fallback event label. */
    public static final String FIELD_EVENT_LABEL = "eventLabel";
    /** Detail field containing the handler channel key. */
    public static final String FIELD_HANDLER_CHANNEL_KEY =
            "handlerChannelKey";
    /** Detail field containing a human-readable label. */
    public static final String FIELD_LABEL = "label";
    /** Detail field containing the canonical logical-delivery key. */
    public static final String FIELD_LOGICAL_DELIVERY_KEY =
            "logicalDeliveryKey";
    /** Detail field describing the delivery mode. */
    public static final String FIELD_MODE = "mode";
    /** Detail field containing an old checkpoint domain. */
    public static final String FIELD_OLD_DOMAIN = "oldDomain";
    /** Detail field containing an operation name. */
    public static final String FIELD_OPERATION = "op";
    /** Detail field containing canonical delivery order. */
    public static final String FIELD_ORDER = "order";
    /** Detail field explaining a discarded result. */
    public static final String FIELD_REASON = "reason";
    /** Detail field containing the number of removed subscriptions. */
    public static final String FIELD_REMOVED = "removed";
    /** Detail field containing a lookup or execution result. */
    public static final String FIELD_RESULT = "result";
    /** Detail field containing a source contribution count. */
    public static final String FIELD_SOURCE_COUNT = "sourceCount";
    /** Detail field containing an authored source path. */
    public static final String FIELD_SOURCE_PATH = "sourcePath";
    /** Detail field containing the source scope path. */
    public static final String FIELD_SOURCE_SCOPE_PATH = "sourceScopePath";
    /** Detail field containing a checkpoint subject. */
    public static final String FIELD_SUBJECT = "subject";

    /** Action value for checkpoint cleanup. */
    public static final String ACTION_CLEANUP = "cleanup";
    /** Effect value for a discarded checkpoint write. */
    public static final String EFFECT_CHECKPOINT = "checkpoint";
    /** Effect value for a discarded event. */
    public static final String EFFECT_EVENT = "event";
    /** Effect value for a discarded patch. */
    public static final String EFFECT_PATCH = "patch";
    /** Effect value for a discarded termination request. */
    public static final String EFFECT_TERMINATION = "termination";
    /** Delivery mode for embedded routing. */
    public static final String MODE_EMBEDDED = "embedded";
    /** Delivery mode for triggered routing. */
    public static final String MODE_TRIGGERED = "triggered";
    /** Reason value used when a scope has already been cut off. */
    public static final String REASON_SCOPE_CUT_OFF = "scope-cut-off";
    /** Drain-owner value for the invocation-wide event queue. */
    public static final String DRAIN_OWNER_INVOCATION_EVENT_FIFO =
            "invocation-event-fifo";
    /** Label prefix for a discarded checkpoint effect. */
    public static final String LABEL_PREFIX_CHECKPOINT = "checkpoint:";
    /** Label prefix for a discarded termination effect. */
    public static final String LABEL_PREFIX_TERMINATION = "termination:";
    /** Fallback label used when an event exposes no identifier or scalar value. */
    public static final String DEFAULT_EVENT_LABEL = "event";

    private ProcessingTraceConstants() {
    }

    /**
     * Returns the stable detail field for an indexed source channel.
     *
     * @param index zero-based source index
     * @return field name such as {@code source.0}
     * @throws IllegalArgumentException when {@code index} is negative
     */
    public static String sourceField(int index) {
        if (index < 0) {
            throw new IllegalArgumentException(
                    "Source index must not be negative");
        }
        return "source." + index;
    }
}
