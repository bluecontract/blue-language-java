package blue.language.utils;

import blue.language.processor.registry.RuntimeBlueIds;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Authoritative Language wire keys, merge controls, core type names, and
 * released type identities.
 *
 * <p>Callers should use these constants instead of duplicating wire literals.
 * Published BlueIds are protocol data and must not be recalculated or
 * reformatted.</p>
 */
public class Properties {

    /** Canonical object-field keys. */
    public static final String OBJECT_NAME = "name";
    /** Canonical key for a node description. */
    public static final String OBJECT_DESCRIPTION = "description";
    /** Canonical key for declared type metadata. */
    public static final String OBJECT_TYPE = "type";
    /** Canonical key for list item-type metadata. */
    public static final String OBJECT_ITEM_TYPE = "itemType";
    /** Canonical key for dictionary key-type metadata. */
    public static final String OBJECT_KEY_TYPE = "keyType";
    /** Canonical key for dictionary value-type metadata. */
    public static final String OBJECT_VALUE_TYPE = "valueType";
    /** Canonical key for schema metadata. */
    public static final String OBJECT_SCHEMA = "schema";
    /** Canonical key for contract metadata. */
    public static final String OBJECT_CONTRACTS = "contracts";
    /** Canonical key for list merge-policy metadata. */
    public static final String OBJECT_MERGE_POLICY = "mergePolicy";
    /** Canonical key for a scalar payload. */
    public static final String OBJECT_VALUE = "value";
    /** Canonical key for a list payload. */
    public static final String OBJECT_ITEMS = "items";
    /** Canonical key for a BlueId reference or metadata value. */
    public static final String OBJECT_BLUE_ID = "blueId";
    /** Canonical key for preprocessing directives. */
    public static final String OBJECT_BLUE = "blue";
    /** Portable-import map nested under the root {@link #OBJECT_BLUE} directive. */
    public static final String BLUE_DIRECTIVE_IMPORTS = "imports";
    /** Ordered transformation list nested under the root {@link #OBJECT_BLUE} directive. */
    public static final String BLUE_DIRECTIVE_TRANSFORMATIONS =
            "transformations";
    /** Rejected legacy wrapper that exposed the internal object-property map. */
    public static final String LEGACY_OBJECT_PROPERTIES = "properties";
    /** Rejected pre-1.0 constraints wrapper. */
    public static final String LEGACY_OBJECT_CONSTRAINTS = "constraints";
    /** Canonical textual form of a Boolean true value or dictionary key. */
    public static final String BOOLEAN_TEXT_TRUE = "true";
    /** Canonical textual form of a Boolean false value or dictionary key. */
    public static final String BOOLEAN_TEXT_FALSE = "false";

    /** Released list merge-policy values. */
    public static final String LIST_MERGE_POLICY_POSITIONAL = "positional";
    /** Append-only list merge policy. */
    public static final String LIST_MERGE_POLICY_APPEND_ONLY = "append-only";

    /** Reserved list-control keys. */
    public static final String LIST_CONTROL_PREVIOUS = "$previous";
    /** Reserved key for a positional list overlay. */
    public static final String LIST_CONTROL_POS = "$pos";
    /** Reserved key for whole-list replacement. */
    public static final String LIST_CONTROL_REPLACE = "$replace";
    /** Reserved key for an explicit empty-list placeholder. */
    public static final String LIST_CONTROL_EMPTY = "$empty";

    /**
     * Human-readable core type names. Exposed list constants are fixed-size
     * compatibility collections; callers must not mutate them.
     */
    public static final String TEXT_TYPE = "Text";
    /** Human-readable released Double type name. */
    public static final String DOUBLE_TYPE = "Double";
    /** Human-readable released Integer type name. */
    public static final String INTEGER_TYPE = "Integer";
    /** Human-readable released Boolean type name. */
    public static final String BOOLEAN_TYPE = "Boolean";
    /** Human-readable released List type name. */
    public static final String LIST_TYPE = "List";
    /** Human-readable released Dictionary type name. */
    public static final String DICTIONARY_TYPE = "Dictionary";
    /** Fixed-size list of released basic scalar type names. */
    public static final List<String> BASIC_TYPES = Arrays.asList(TEXT_TYPE, DOUBLE_TYPE, INTEGER_TYPE, BOOLEAN_TYPE);
    /** Fixed-size list of all released core type names. */
    public static final List<String> CORE_TYPES =
            Arrays.asList(TEXT_TYPE, DOUBLE_TYPE, INTEGER_TYPE, BOOLEAN_TYPE, LIST_TYPE, DICTIONARY_TYPE);


    /**
     * Released core type BlueIds and lookup collections. Exposed lookup maps
     * are compatibility data and callers must not mutate them.
     */
    public static final String TEXT_TYPE_BLUE_ID = "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC";
    /** Released Double type BlueId. */
    public static final String DOUBLE_TYPE_BLUE_ID = "9eWaHYz2vKrFofdHTHAizNNu8xP6QE3WQ5y7DGrGZvyJ";
    /** Released Integer type BlueId. */
    public static final String INTEGER_TYPE_BLUE_ID = "E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq";
    /** Released Boolean type BlueId. */
    public static final String BOOLEAN_TYPE_BLUE_ID = "AwvXD961fmnmqcSQhjMA7r15HpVh39cefb6ZTyUz2Fm2";
    /** Released List type BlueId. */
    public static final String LIST_TYPE_BLUE_ID = "8DSFoWG9MqRSUhStqoPLrwVQiYByRh18NWbDEarN8MKF";
    /** Released Dictionary type BlueId. */
    public static final String DICTIONARY_TYPE_BLUE_ID = "Efkz9D1ARMM7rU43w3rDNVqat1naS6qXKCqP4eHin3yG";
    /** Fixed-size list of released basic scalar type BlueIds. */
    public static final List<String> BASIC_TYPE_BLUE_IDS = Arrays.asList(TEXT_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID, INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID);
    /** Fixed-size list of all released core type BlueIds. */
    public static final List<String> CORE_TYPE_BLUE_IDS =
            Arrays.asList(TEXT_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID, INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID, LIST_TYPE_BLUE_ID, DICTIONARY_TYPE_BLUE_ID);

    /** Mutable compatibility lookup from core type name to released BlueId. */
    public static final Map<String, String> CORE_TYPE_NAME_TO_BLUE_ID_MAP = IntStream.range(0, CORE_TYPES.size())
            .boxed()
            .collect(Collectors.toMap(CORE_TYPES::get, CORE_TYPE_BLUE_IDS::get));

    /** Mutable compatibility lookup from released core BlueId to type name. */
    public static final Map<String, String> CORE_TYPE_BLUE_ID_TO_NAME_MAP = IntStream.range(0, CORE_TYPES.size())
            .boxed()
            .collect(Collectors.toMap(CORE_TYPE_BLUE_IDS::get, CORE_TYPES::get));

    /**
     * Released Blue Contracts runtime type names in BlueId-list order. The
     * exposed compatibility list must be treated as read-only.
     */
    public static final List<String> BLUE_CONTRACTS_RUNTIME_TYPES = Arrays.asList(
            "Channel",
            "Channel Event Checkpoint",
            "Channel Checkpoint Entry",
            "Contract",
            "Contract Execution Result",
            "Document Processing Initiated",
            "Document Processing Terminated",
            "Document Update",
            "Document Update Channel",
            "Embedded Event Delivery",
            "Embedded Node Channel",
            "External Channel",
            "Contracts Fixture Event",
            "Handler",
            "Json Patch Entry",
            "Lifecycle Event Channel",
            "Marker",
            "Process Embedded",
            "Processing Initialized Marker",
            "Processing Terminated Marker",
            "Runtime Counter Entry",
            "Runtime Ledger",
            "Scripted External Channel",
            "Scripted Handler",
            "Triggered Event Channel",
            "Type Generalization Policy",
            "Type Generalization Rule"
    );

    /**
     * Released Blue Contracts runtime BlueIds in type-name-list order. The
     * exposed compatibility list must be treated as read-only.
     */
    public static final List<String> BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_IDS = Arrays.asList(
            RuntimeBlueIds.CHANNEL,
            RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT,
            RuntimeBlueIds.CHECKPOINT_ENTRY,
            RuntimeBlueIds.CONTRACT,
            RuntimeBlueIds.CONTRACT_EXECUTION_RESULT,
            RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED,
            RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED,
            RuntimeBlueIds.DOCUMENT_UPDATE,
            RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL,
            RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY,
            RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
            RuntimeBlueIds.EXTERNAL_CHANNEL,
            RuntimeBlueIds.FIXTURE_EVENT,
            RuntimeBlueIds.HANDLER,
            RuntimeBlueIds.JSON_PATCH_ENTRY,
            RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL,
            RuntimeBlueIds.MARKER,
            RuntimeBlueIds.PROCESS_EMBEDDED,
            RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER,
            RuntimeBlueIds.PROCESSING_TERMINATED_MARKER,
            RuntimeBlueIds.RUNTIME_COUNTER_ENTRY,
            RuntimeBlueIds.RUNTIME_LEDGER,
            RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
            RuntimeBlueIds.SCRIPTED_HANDLER,
            RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL,
            RuntimeBlueIds.TYPE_GENERALIZATION_POLICY,
            RuntimeBlueIds.TYPE_GENERALIZATION_RULE
    );

    /** Released mutable compatibility lookup maps; callers must treat them as read-only. */
    public static final Map<String, String> BLUE_CONTRACTS_RUNTIME_TYPE_NAME_TO_BLUE_ID_MAP =
            IntStream.range(0, BLUE_CONTRACTS_RUNTIME_TYPES.size())
                    .boxed()
                    .collect(Collectors.toMap(BLUE_CONTRACTS_RUNTIME_TYPES::get, BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_IDS::get));

    /** Mutable compatibility lookup from Contracts runtime BlueId to type name. */
    public static final Map<String, String> BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_ID_TO_NAME_MAP =
            IntStream.range(0, BLUE_CONTRACTS_RUNTIME_TYPES.size())
                    .boxed()
                    .collect(Collectors.toMap(BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_IDS::get, BLUE_CONTRACTS_RUNTIME_TYPES::get));

    /** Combined core and Contracts runtime type maps exposed by default. */
    public static final Map<String, String> DEFAULT_BLUE_TYPE_NAME_TO_BLUE_ID_MAP = buildDefaultBlueTypeNameToBlueIdMap();
    /** Combined released BlueId-to-name lookup exposed by default. */
    public static final Map<String, String> DEFAULT_BLUE_TYPE_BLUE_ID_TO_NAME_MAP = buildDefaultBlueTypeBlueIdToNameMap();

    /**
     * Creates a Language property and type-identity constants holder.
     */
    public Properties() {
    }

    private static Map<String, String> buildDefaultBlueTypeNameToBlueIdMap() {
        Map<String, String> result = new LinkedHashMap<>();
        result.putAll(CORE_TYPE_NAME_TO_BLUE_ID_MAP);
        result.putAll(BLUE_CONTRACTS_RUNTIME_TYPE_NAME_TO_BLUE_ID_MAP);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> buildDefaultBlueTypeBlueIdToNameMap() {
        Map<String, String> result = new LinkedHashMap<>();
        result.putAll(CORE_TYPE_BLUE_ID_TO_NAME_MAP);
        result.putAll(BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_ID_TO_NAME_MAP);
        return Collections.unmodifiableMap(result);
    }

}
