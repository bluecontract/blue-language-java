package blue.language.processor.util;

import blue.language.utils.JsonPointer;
import blue.language.utils.Properties;

/**
 * Shared relative pointer constants for processor-managed contract paths.
 *
 * <p>Centralises the JSON-pointer fragments the runtime relies on when reading or
 * writing reserved contract entries. Keeping them here avoids drift between
 * runtime logic, tests, and documentation.</p>
 */
public final class ProcessorPointerConstants {

    /** Relative pointer to a node's contract map. */
    public static final String RELATIVE_CONTRACTS =
            "/" + ProcessorContractConstants.KEY_CONTRACTS;
    /** Relative pointer to a node's declared type. */
    public static final String RELATIVE_TYPE =
            "/" + Properties.OBJECT_TYPE;
    /** Relative pointer to a scalar payload. */
    public static final String RELATIVE_VALUE =
            "/" + Properties.OBJECT_VALUE;
    /** Relative pointer to the initialized marker. */
    public static final String RELATIVE_INITIALIZED =
            relativeContractsEntry(
                    ProcessorContractConstants.KEY_INITIALIZED);
    /** Relative pointer to the terminated marker. */
    public static final String RELATIVE_TERMINATED =
            relativeContractsEntry(
                    ProcessorContractConstants.KEY_TERMINATED);
    /** Relative pointer to the embedded-channel configuration. */
    public static final String RELATIVE_EMBEDDED =
            relativeContractsEntry(
                    ProcessorContractConstants.KEY_EMBEDDED);
    /** Relative pointer to the embedded-channel path list. */
    public static final String RELATIVE_EMBEDDED_PATHS =
            JsonPointer.append(
                    RELATIVE_EMBEDDED,
                    ProcessorContractConstants.KEY_PATHS);
    /** Relative pointer to checkpoint state. */
    public static final String RELATIVE_CHECKPOINT =
            relativeContractsEntry(
                    ProcessorContractConstants.KEY_CHECKPOINT);
    /** Relative pointer to type-generalization policy. */
    public static final String RELATIVE_GENERALIZATION =
            relativeContractsEntry(
                    ProcessorContractConstants.KEY_GENERALIZATION);
    /** PROCESS-input pointer to the exact event. */
    public static final String PROCESS_EVENT = "/event";
    /** PROCESS-input pointer to the event's singular subscription key. */
    public static final String PROCESS_EVENT_SUBSCRIPTION_KEY =
            JsonPointer.append(
                    PROCESS_EVENT,
                    ProcessorContractConstants.KEY_SUBSCRIPTION_KEY);

    private static final String ENTRIES_SUFFIX =
            "/" + ProcessorContractConstants.KEY_ENTRIES;

    private ProcessorPointerConstants() {
    }

    /**
     * Builds a relative pointer for one contract entry.
     *
     * @param key contract key
     * @return canonical relative pointer
     */
    public static String relativeContractsEntry(String key) {
        return JsonPointer.append(RELATIVE_CONTRACTS, key);
    }

    /**
     * Builds a relative pointer for one checkpoint entry.
     *
     * @param markerKey checkpoint marker key
     * @param rawChannelKey channel key stored below the entry map
     * @return canonical relative pointer
     */
    public static String relativeCheckpointEntry(String markerKey, String rawChannelKey) {
        return JsonPointer.append(relativeContractsEntry(markerKey) + ENTRIES_SUFFIX, rawChannelKey);
    }

}
