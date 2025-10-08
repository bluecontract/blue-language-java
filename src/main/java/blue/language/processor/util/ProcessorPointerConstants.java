package blue.language.processor.util;

/**
 * Shared relative pointer constants for processor-managed contract paths.
 *
 * <p>Centralises the JSON-pointer fragments the runtime relies on when reading or
 * writing reserved contract entries. Keeping them here avoids drift between
 * runtime logic, tests, and documentation.</p>
 */
public final class ProcessorPointerConstants {

    public static final String RELATIVE_CONTRACTS = "/contracts";
    public static final String RELATIVE_INITIALIZED = RELATIVE_CONTRACTS + "/" + ProcessorContractConstants.KEY_INITIALIZED;
    public static final String RELATIVE_TERMINATED = RELATIVE_CONTRACTS + "/" + ProcessorContractConstants.KEY_TERMINATED;
    public static final String RELATIVE_EMBEDDED = RELATIVE_CONTRACTS + "/" + ProcessorContractConstants.KEY_EMBEDDED;
    public static final String RELATIVE_CHECKPOINT = RELATIVE_CONTRACTS + "/" + ProcessorContractConstants.KEY_CHECKPOINT;

    private static final String LAST_EVENTS_SUFFIX = "/lastEvents";
    private static final String LAST_SIGNATURES_SUFFIX = "/lastSignatures";

    private ProcessorPointerConstants() {
    }

    public static String relativeContractsEntry(String key) {
        return RELATIVE_CONTRACTS + "/" + key;
    }

    public static String relativeCheckpointLastEvent(String markerKey, String channelKey) {
        return relativeContractsEntry(markerKey) + LAST_EVENTS_SUFFIX + "/" + channelKey;
    }

    public static String relativeCheckpointLastSignature(String markerKey, String channelKey) {
        return relativeContractsEntry(markerKey) + LAST_SIGNATURES_SUFFIX + "/" + channelKey;
    }
}
