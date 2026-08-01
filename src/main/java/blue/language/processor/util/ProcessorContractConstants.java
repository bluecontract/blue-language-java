package blue.language.processor.util;

import blue.language.utils.Properties;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Defines the stable property names and channel categories owned by the
 * Contracts processor.
 *
 * <p>Processor code must use these names instead of repeating wire-format
 * strings. That keeps readers, writers, validation, and conformance tooling
 * aligned when a reserved property is referenced from several phases.</p>
 */
public final class ProcessorContractConstants {

    /** Property containing the contracts attached to a Blue node. */
    public static final String KEY_CONTRACTS =
            Properties.OBJECT_CONTRACTS;
    /** Reserved contract key for embedded-node processing configuration. */
    public static final String KEY_EMBEDDED = "embedded";
    /** Reserved contract key for the processing-initialized marker. */
    public static final String KEY_INITIALIZED = "initialized";
    /** Reserved contract key for the processing-terminated marker. */
    public static final String KEY_TERMINATED = "terminated";
    /** Reserved contract key for channel checkpoint state. */
    public static final String KEY_CHECKPOINT = "checkpoint";
    /** Property containing the checkpoint entry map. */
    public static final String KEY_ENTRIES = "entries";
    /** Property containing selected embedded child paths. */
    public static final String KEY_PATHS = "paths";
    /** Contract key containing type-generalization policy. */
    public static final String KEY_GENERALIZATION = "generalization";
    /** Property containing the exact initialized document. */
    public static final String KEY_DOCUMENT = "document";
    /** Removed preview property accepted only for fail-closed shape checks. */
    public static final String LEGACY_KEY_DOCUMENT_ID = "documentId";
    /** Property containing a stable termination cause. */
    public static final String KEY_CAUSE = "cause";
    /** Property containing optional termination detail. */
    public static final String KEY_REASON = "reason";
    /** Property containing a checkpoint domain. */
    public static final String KEY_DOMAIN = "domain";
    /** Property containing a checkpoint subject. */
    public static final String KEY_SUBJECT = "subject";
    /** Property containing an event payload. */
    public static final String KEY_EVENT = "event";
    /** Property containing an embedded event's source path. */
    public static final String KEY_SOURCE_PATH = "sourcePath";
    /** Property containing a patch operation. */
    public static final String KEY_OPERATION = "op";
    /** Property containing a root-relative path. */
    public static final String KEY_PATH = "path";
    /** Property indicating whether a before-value is present. */
    public static final String KEY_BEFORE_PRESENT = "beforePresent";
    /** Property containing an optional before-value. */
    public static final String KEY_BEFORE = "before";
    /** Property indicating whether an after-value is present. */
    public static final String KEY_AFTER_PRESENT = "afterPresent";
    /** Property containing an optional after-value. */
    public static final String KEY_AFTER = "after";
    /** Property identifying the scope that produced an update. */
    public static final String KEY_SOURCE_SCOPE_PATH = "sourceScopePath";
    /** Property containing the fallback type-generalization mode. */
    public static final String KEY_DEFAULT_MODE = "defaultMode";
    /** Property containing ordered type-generalization rules. */
    public static final String KEY_RULES = "rules";
    /** Property containing a type-generalization rule's mode. */
    public static final String KEY_MODE = "mode";
    /** Property containing a type-generalization rule's subtype floor. */
    public static final String KEY_MUST_REMAIN_SUBTYPE_OF =
            "mustRemainSubtypeOf";
    /** Singular property containing one External Channel subscription key. */
    public static final String KEY_SUBSCRIPTION_KEY = "subscriptionKey";
    /** Plural property containing ordered External Channel subscription keys. */
    public static final String KEY_SUBSCRIPTION_KEYS = "subscriptionKeys";

    /** Generalization mode that selects the nearest conforming ancestor. */
    public static final String GENERALIZATION_MODE_NEAREST_VALID_ANCESTOR =
            "nearest-valid-ancestor";
    /** Generalization mode that rejects generated type changes. */
    public static final String GENERALIZATION_MODE_REJECT = "reject";

    /** Contract keys that callers may not repurpose for custom channels. */
    public static final Set<String> RESERVED_CONTRACT_KEYS =
            Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
                    KEY_EMBEDDED,
                    KEY_INITIALIZED,
                    KEY_TERMINATED,
                    KEY_CHECKPOINT
            )));

    private ProcessorContractConstants() {
    }

    /**
     * Returns whether {@code key} is reserved by the Contracts processor.
     *
     * @param key contract property name, or {@code null}
     * @return {@code true} only for a processor-owned key
     */
    public static boolean isReservedKey(String key) {
        return key != null && RESERVED_CONTRACT_KEYS.contains(key);
    }

}
