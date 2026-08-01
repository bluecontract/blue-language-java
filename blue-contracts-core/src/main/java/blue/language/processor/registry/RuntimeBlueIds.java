package blue.language.processor.registry;

/**
 * Published Blue Contracts and Processor 1.0 runtime identities.
 *
 * <p>These constants are the verified identities from the bundled runtime
 * registry. They are protocol values: consumers should reference the named
 * constants instead of repeating their encoded strings.</p>
 */
public final class RuntimeBlueIds {

    /** SHA-256 identity of the complete runtime-registry package. */
    public static final String REGISTRY_PACKAGE_IDENTITY =
            "sha256:67ce3101449c5bca9e6093b081da239d5d699fdc02182a058d3ad795c6c6120b";

    /**
     * Legacy BlueId meta-type identity retained for binary/source
     * compatibility.
     *
     * <p>This compatibility-only identity is not an entry in the closed
     * Contracts 1.0 runtime registry. New runtime code must use a
     * {@link RuntimeTypeKey}-backed identity below. Test fixtures that need
     * this legacy value own their intent in test-only constants.</p>
     */
    public static final String BLUE_ID_TYPE =
            "APr87o8Wq358V8onThLEiW44hEn43wFGf9sKbw5TmmYz";

    /** Published BlueId of the Channel runtime type. */
    public static final String CHANNEL =
            "CaFMD5Tpz4LbGjJsftT3465hKBWa7Ti6dutYHnCSRQyR";
    /** Published BlueId of the Channel Event Checkpoint runtime type. */
    public static final String CHANNEL_EVENT_CHECKPOINT =
            "9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR";
    /** Published BlueId of the Checkpoint Entry runtime type. */
    public static final String CHECKPOINT_ENTRY =
            "2uJq8ZJGyUpMiZckxopH2koa7ZFRavVacpu2eGdK2UwY";
    /** Published BlueId of the Contract runtime type. */
    public static final String CONTRACT =
            "4ugZ87HaumAJezmgvi2QoqfEdqfwpviQavmak8C8ewF4";
    /** Published BlueId of the Contract Execution Result runtime type. */
    public static final String CONTRACT_EXECUTION_RESULT =
            "3aKiqpRW7E6kfk1LTrEijsQux49cx2T5xDX3faSzv3gv";
    /** Published BlueId of the processing-initiated lifecycle event. */
    public static final String DOCUMENT_PROCESSING_INITIATED =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";
    /** Published BlueId of the processing-terminated lifecycle event. */
    public static final String DOCUMENT_PROCESSING_TERMINATED =
            "xaVhnN73YeTiJ1vaLGwndpYQE2RsbckfvzihLQsp2Yi";
    /** Published BlueId of the Document Update runtime type. */
    public static final String DOCUMENT_UPDATE =
            "7HZ6UDNxDdGdvhowi92mwB4EAqKfeynpJEFUFVvjmTJ2";
    /** Published BlueId of the Document Update Channel runtime type. */
    public static final String DOCUMENT_UPDATE_CHANNEL =
            "4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An";
    /** Published BlueId of the Embedded Event Delivery runtime type. */
    public static final String EMBEDDED_EVENT_DELIVERY =
            "58trfDqLwD1F8JiPg86korUKEjgH1NXxgHSMjeLFRSFC";
    /** Published BlueId of the Embedded Node Channel runtime type. */
    public static final String EMBEDDED_NODE_CHANNEL =
            "7ZgUJxCyokHf84uibaQz138mFRLarykWLewVAn8bibTN";
    /** Published BlueId of the External Channel runtime type. */
    public static final String EXTERNAL_CHANNEL =
            "4wXKQivSASbs6PLnR562Q2XcT52x1bBViGk7cxhQ3swq";
    /** Published BlueId of the conformance Fixture Event type. */
    public static final String FIXTURE_EVENT =
            "5KUZWsqRuW7SyRj1oCK7hRTmJKVCHTiVJboxy4nas8KX";
    /** Published BlueId of the Handler runtime type. */
    public static final String HANDLER =
            "2Ag2NfcWpCfPqBAR7bFAEL9L3roX3UWGUkDq7nN3D4gV";
    /** Published BlueId of the JSON Patch Entry runtime type. */
    public static final String JSON_PATCH_ENTRY =
            "5UihWoxkyiUbv9TZk7HcHsa82sz2R3ex1WifQQpKtHpP";
    /** Published BlueId of the Lifecycle Event Channel runtime type. */
    public static final String LIFECYCLE_EVENT_CHANNEL =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    /** Published BlueId of the processor Marker runtime type. */
    public static final String MARKER =
            "8nWeksYEXxp5TBnRcYF5u3VsFHvMfxo4zjAFT6MLW8ZD";
    /** Published BlueId of the Process Embedded runtime type. */
    public static final String PROCESS_EMBEDDED =
            "D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr";
    /** Published BlueId of the initialized processor marker. */
    public static final String PROCESSING_INITIALIZED_MARKER =
            "Hp3fNbpFxKwLiTwWAf3swpN7gKbsr6ofwEDMntiwXPaB";
    /** Published BlueId of the terminated processor marker. */
    public static final String PROCESSING_TERMINATED_MARKER =
            "4c1aabU6a3idKpWPzTRS4upLjCb6eZh3F1PDXkNh7i6v";
    /** Published BlueId of a runtime gas-counter entry. */
    public static final String RUNTIME_COUNTER_ENTRY =
            "2fQHvWpJRfkPW9rqcqZKdcEKx4586LDkYR2bWTPRDZEo";
    /** Published BlueId of the runtime gas ledger. */
    public static final String RUNTIME_LEDGER =
            "EEcehN6F5zKoZbLFvoAqa8hiWKzPY2VFbd3j5qGDELS2";
    /** Published BlueId of the conformance Scripted External Channel. */
    public static final String SCRIPTED_EXTERNAL_CHANNEL =
            "2hesjWGVbvcJSu6woCUTssU9S7A69ep93UzdgvwosDLt";
    /** Published BlueId of the conformance Scripted Handler. */
    public static final String SCRIPTED_HANDLER =
            "6rznQbYVahD1UVqdRXbPy7wF1NV5LYhDyzThEL1znaFw";
    /** Published BlueId of the Triggered Event Channel runtime type. */
    public static final String TRIGGERED_EVENT_CHANNEL =
            "DRxc8GkSGPbdENdB8ZK976i1Jzc6M1QdG8UsVMHcqQcf";
    /** Published BlueId of the Type Generalization Policy runtime type. */
    public static final String TYPE_GENERALIZATION_POLICY =
            "8VeXb3GgP88WtosVLu2mamHmbvY8f5cxA9z6yAETbbFz";
    /** Published BlueId of an individual Type Generalization Rule. */
    public static final String TYPE_GENERALIZATION_RULE =
            "5BwjjfvodMVCfD2cKChbUMmjEBd83vv5kbEQwAFHcSnv";

    private RuntimeBlueIds() {
    }

    /**
     * Returns the published BlueId corresponding to a runtime registry key.
     *
     * @param key closed runtime-type key
     * @return its published BlueId
     * @throws IllegalArgumentException if the key is not recognized
     */
    public static String blueId(RuntimeTypeKey key) {
        switch (key) {
            case CHANNEL:
                return CHANNEL;
            case CHANNEL_EVENT_CHECKPOINT:
                return CHANNEL_EVENT_CHECKPOINT;
            case CHECKPOINT_ENTRY:
                return CHECKPOINT_ENTRY;
            case CONTRACT:
                return CONTRACT;
            case CONTRACT_EXECUTION_RESULT:
                return CONTRACT_EXECUTION_RESULT;
            case DOCUMENT_PROCESSING_INITIATED:
                return DOCUMENT_PROCESSING_INITIATED;
            case DOCUMENT_PROCESSING_TERMINATED:
                return DOCUMENT_PROCESSING_TERMINATED;
            case DOCUMENT_UPDATE:
                return DOCUMENT_UPDATE;
            case DOCUMENT_UPDATE_CHANNEL:
                return DOCUMENT_UPDATE_CHANNEL;
            case EMBEDDED_EVENT_DELIVERY:
                return EMBEDDED_EVENT_DELIVERY;
            case EMBEDDED_NODE_CHANNEL:
                return EMBEDDED_NODE_CHANNEL;
            case EXTERNAL_CHANNEL:
                return EXTERNAL_CHANNEL;
            case FIXTURE_EVENT:
                return FIXTURE_EVENT;
            case HANDLER:
                return HANDLER;
            case JSON_PATCH_ENTRY:
                return JSON_PATCH_ENTRY;
            case LIFECYCLE_EVENT_CHANNEL:
                return LIFECYCLE_EVENT_CHANNEL;
            case MARKER:
                return MARKER;
            case PROCESS_EMBEDDED:
                return PROCESS_EMBEDDED;
            case PROCESSING_INITIALIZED_MARKER:
                return PROCESSING_INITIALIZED_MARKER;
            case PROCESSING_TERMINATED_MARKER:
                return PROCESSING_TERMINATED_MARKER;
            case RUNTIME_COUNTER_ENTRY:
                return RUNTIME_COUNTER_ENTRY;
            case RUNTIME_LEDGER:
                return RUNTIME_LEDGER;
            case SCRIPTED_EXTERNAL_CHANNEL:
                return SCRIPTED_EXTERNAL_CHANNEL;
            case SCRIPTED_HANDLER:
                return SCRIPTED_HANDLER;
            case TRIGGERED_EVENT_CHANNEL:
                return TRIGGERED_EVENT_CHANNEL;
            case TYPE_GENERALIZATION_POLICY:
                return TYPE_GENERALIZATION_POLICY;
            case TYPE_GENERALIZATION_RULE:
                return TYPE_GENERALIZATION_RULE;
            default:
                throw new IllegalArgumentException("Unknown runtime type key: " + key);
        }
    }
}
